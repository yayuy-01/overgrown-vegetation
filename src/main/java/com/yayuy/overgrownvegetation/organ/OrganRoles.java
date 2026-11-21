package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * 器官の役割(2026-11-17 ユーザー決定の 5 つ。強さは design/organs.json の【仮】)。育ち切った器官だけが、100 tick ごとに
 * 前に数えた時刻からの時間ぶん働く(寝て飛ばした時間も。時計は {@link PhaseData#clock})。
 * <ul>
 *   <li>フェーズを進める器官: 過繁茂 {@code roles.phaseChunks} チャンク分の面積の点を足す(11-19 固定値。難易度の倍率は {@link PhaseData#addPoints} が掛ける)。</li>
 *   <li>胞子の塔: 口から胞子の場へ点の発生源({@code SporeFieldServer.emitPoint})。</li>
 *   <li>蓄えの嚢: 司令塔の上限(ForestDirector#cap が {@link OrganWorld#sacHiveEquivalent} を読む)。ここでは何もしない。</li>
 *   <li>苗床の群体: プレイヤーが近い間、予算で G2・G3 を産む。</li>
 *   <li>地脈を吸う根: 植物側の Mob のステータス({@link LeyBuffs}。ワールド全体)と、根晶の生え直し。</li>
 * </ul>
 * どれも、守りの Mob(プレイヤーが近いとき、入口と芯の近く)を置く。
 */
public final class OrganRoles {
    private OrganRoles() {
    }

    /**
     * フェーズを進める器官 1 本が 1 日に足すポイント(難易度の倍率の前。{@link PhaseData#addPoints} が掛ける)。
     * 固定値(2026-11-19 ユーザー了承): 過繁茂 {@code roles.phaseChunks} チャンク分の面積の点(面積の点と同じ式)× 小さい版の強さ。
     * フェーズに依らない({@code phase} は閾値の無い所で 0 にするためだけ)。{@code /ov forest} もここを読む。
     */
    public static double phasePointsPerDay(int phase, ForestOrgans.Organ o) {
        if (DesignData.phases().pointsToAdvance(phase) <= 0L) return 0.0D;
        OrganTable t = DesignData.organs();
        return chunkPointsPerDay(t.phaseChunks) * t.strength(o.small);
    }

    /** 過繁茂 {@code chunks} チャンクが 1 日に稼ぐ面積の点(時間の点は含めない)。 */
    public static double chunkPointsPerDay(double chunks) {
        PhaseTable p = DesignData.phases();
        return (p.pointsPerTick(1L) - p.pointsPerTick(0L)) * chunks * PhaseTable.TICKS_PER_DAY;
    }

    /** 胞子の塔 1 本が 1 日に胞子の場へ出す量。 */
    public static double towerSporePerDay(ForestOrgans.Organ o) {
        OrganTable t = DesignData.organs();
        return t.towerSporePerDay * t.strength(o.small);
    }

    /** 苗床の群体 1 本のまわりに産む数の上限(守りを含む)。 */
    public static int nurseryMaxNear(ForestOrgans.Organ o) {
        OrganTable t = DesignData.organs();
        return Math.max(1, (int) Math.round(t.nurseryMaxAlive * t.strength(o.small))) + t.guardCount;
    }

    static void tick(ServerLevel level, ForestOrgans data, ForestOrgans.Organ o, long now) {
        OrganTable t = DesignData.organs();
        if (o.roleAt < 0L) o.roleAt = now;
        double days = Math.max(0L, now - o.roleAt) / 24000.0D;
        o.roleAt = now;
        boolean peaceful = DirectorGates.peaceful(level);
        switch (o.kind) {
            case PHASE -> {
                PhaseData pd = PhaseData.get(level);
                double perDay = phasePointsPerDay(pd.getPhase(), o);
                if (perDay > 0.0D && !peaceful) {
                    double raw = perDay * days + o.pointCarry;
                    long whole = (long) Math.floor(raw);
                    o.pointCarry = raw - whole;
                    if (whole > 0L) pd.addPoints(level, whole);
                }
                // 11-20 ユーザー決定: 毎秒バイオマスを使う代わりに、使った分に応じてフェーズの点を足す(今の点に足す。【仮】)
                if (perDay > 0.0D && !peaceful) phaseSpend(level, o, pd, perDay, days);
            }
            case TOWER -> {
                if (!peaceful && t.towerSporePerDay > 0.0D) {
                    double perDay = towerSporePerDay(o);
                    OrganBlueprint bp = OrganWorld.blueprint(o);
                    BlockPos anchor = o.anchor();
                    if (bp == null || bp.mouths.isEmpty()) {
                        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, anchor.above(o.small ? 8 : 40), perDay * days);
                    } else {
                        double each = perDay * days / bp.mouths.size();
                        for (BlockPos m : bp.mouths) {
                            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, anchor.offset(m), each);
                        }
                    }
                    towerFlight(level, o, now); // 11-20: バイオマスを払って長距離の飛翔をわざと起こす
                }
            }
            case NURSERY -> nursery(level, o, now, t, peaceful);
            case LEY -> {
                if (now - o.crystalAt >= (long) (t.crystalRegrowDays * 24000.0D) && OrganWorld.ready(level, o.x >> 4, o.z >> 4)) {
                    if (OrganWorld.regrowCrystal(level, o)) o.crystalAt = now;
                    else o.crystalAt = now; // 全部生えていても、次はまた半日後に見る
                }
            }
            case SAC -> {
            }
        }
        regrowSacs(level, o, t);
        guards(level, o, now, t, peaceful);
        com.yayuy.overgrownvegetation.director.ThornHedges.tend(level, o, now); // 11-20: 棘の生垣を置く・置き直す(森の防衛設備)
        data.changed();
    }

    /**
     * 破裂した嚢が生え直す(11-17 深夜ユーザー「時間で生え直す」。一度通った器官も時間を置くとまた危なくなる)。100 tick ごとに
     * 設計図から 64 か所をくじで引き、破れた嚢を確率で戻す。確率は「嚢 1 つあたり平均 {@code sacRegrowDays} 日」になるように
     * (引いた所のうち嚢の割合で数が釣り合うので、設計図の嚢の数を数えなくてよい)。器官が枯れたら呼ばれない(生え直さない)。
     */
    private static void regrowSacs(ServerLevel level, ForestOrgans.Organ o, OrganTable t) {
        if (!o.resolved() || !OrganWorld.ready(level, o.x >> 4, o.z >> 4)) return;
        OrganBlueprint bp = OrganWorld.blueprint(o);
        if (bp == null || o.placed <= 0) return;
        final int tries = 64;
        double p = Math.min(1.0D, 100.0D * o.placed / (tries * t.sacRegrowDays * 24000.0D));
        BlockPos anchor = o.anchor();
        net.minecraft.util.RandomSource r = level.getRandom();
        for (int i = 0; i < tries; i++) {
            long e = bp.entries[r.nextInt(o.placed)];
            if (OrganBlueprint.material(e) != OrganBlueprint.M_SAC) continue;
            if (r.nextDouble() >= p) continue;
            BlockPos at = anchor.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
            if (!OrganWorld.ready(level, at.getX() >> 4, at.getZ() >> 4)) continue;
            var s = level.getBlockState(at);
            if (s.is(OrganBlocks.ORGAN_SAC.get()) && s.getValue(OrganTissueBlock.WITHERED)) {
                level.setBlock(at, s.setValue(OrganTissueBlock.WITHERED, false), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
    }

    private static void nursery(ServerLevel level, ForestOrgans.Organ o, long now, OrganTable t, boolean peaceful) {
        if (peaceful || !o.resolved() || !OrganWorld.ready(level, o.x >> 4, o.z >> 4)) return;
        if (now - o.nurseryAt < (long) (t.nurseryIntervalSeconds * 20L / Math.max(0.05D, t.strength(o.small)))) return; // 小さい版はまれに
        BlockPos at = o.anchor();
        if (!OrganWorld.playerNear(level, at, t.nurseryPlayerBlocks)) return;
        o.nurseryAt = now;
        if (countGuards(level, at, t.nurseryPlayerBlocks) >= nurseryMaxNear(o)) return;
        // 予算で産む(苗床のゲスタトリクスと同じ値段)。G2 と G3 から
        net.minecraft.world.entity.EntityType<?> type = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickPlant(level, level.getRandom(), 0);
        if (type == null) return;
        ForestDirector dir = ForestDirector.get(level);
        double cost = ForestDirector.spawnCost(type);
        if (!dir.tryPay(level, cost)) return;
        OrganBlueprint bp = OrganWorld.blueprint(o);
        BlockPos spot = bp != null ? at.offset(bp.coreCenter) : at.above();
        Mob m = OrganWorld.spawnNear(level, spot, 0);
        if (m == null) dir.refund(level, cost);
        else com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(m, cost); // 森の中で倒されたら一部が戻る(11-20)
    }

    /** 器官の入口の向き(ラジアン。芯の真ん中から入口へ)。設計図がまだ無ければ NaN(生垣の切れ目に使う)。 */
    public static double entranceAngle(ForestOrgans.Organ o) {
        OrganBlueprint bp = OrganWorld.blueprint(o);
        if (bp == null || bp.entrance == null) return Double.NaN;
        return Math.atan2(bp.entrance.getZ(), bp.entrance.getX());
    }

    /**
     * フェーズを進める器官がバイオマスを使って点を足す(11-20 ユーザー決定「毎秒バイオマスを消費する代わりに、消費した分に応じてフェーズの点数を増やす」)。
     * 使いたい量 = {@code economy.phaseSpendPerDay}(決まった量。11-20 夜)× 小さい版の強さ × たった日数。払えた分だけ(足りなければあるだけ)、
     * 今の点({@code perDay})× {@code phaseSpendPointsFactor} × 払えた割合 を足す。数は【仮】。
     */
    public static void phaseSpend(ServerLevel level, ForestOrgans.Organ o, PhaseData pd, double perDay, double days) {
        com.yayuy.overgrownvegetation.data.EconomyTable e = com.yayuy.overgrownvegetation.director.ForestEconomy.table();
        double want = e.phaseSpendPerDay * DesignData.organs().strength(o.small) * days;
        if (want <= 0.0D || e.phaseSpendPointsFactor <= 0.0D) return;
        ForestDirector dir = ForestDirector.get(level);
        double pay = Math.min(want, dir.available(level));
        if (pay <= 0.0D || !dir.tryPay(level, pay)) return;
        com.yayuy.overgrownvegetation.director.ForestEconomy.notePhaseSpend(pay);
        double raw = perDay * days * e.phaseSpendPointsFactor * (pay / want) + o.spendCarry;
        long whole = (long) Math.floor(raw);
        o.spendCarry = raw - whole;
        if (whole > 0L) pd.addPoints(level, whole);
    }

    /**
     * 胞子の塔が、バイオマスを払って長距離の飛翔をわざと起こす(11-20 ユーザー決定。今までは嵐の間だけ)。
     * {@code towerFlightIntervalDays} ごとに見直し、予算が上限の {@code towerFlightMinBudgetShare} より多いときだけ、{@code towerFlightChance} の確率で
     * 値段({@code towerFlightCost}(決まった量)× 強さ)を払い、塔の 1 日の放出 × {@code towerFlightMassDays} を上空の塊として風下へ飛ばす。【仮】
     */
    static boolean towerFlight(ServerLevel level, ForestOrgans.Organ o, long now) {
        com.yayuy.overgrownvegetation.data.EconomyTable e = com.yayuy.overgrownvegetation.director.ForestEconomy.table();
        if (e.towerFlightChance <= 0.0D || e.towerFlightMassDays <= 0.0D) return false;
        if (o.flightAt < 0L) o.flightAt = now;
        if (now - o.flightAt < (long) (e.towerFlightIntervalDays * 24000.0D)) return false;
        o.flightAt = now;
        ForestDirector dir = ForestDirector.get(level);
        if (dir.available(level) < ForestDirector.cap(level) * e.towerFlightMinBudgetShare) return false;
        if (level.getRandom().nextDouble() >= e.towerFlightChance) return false;
        return launchFlight(level, o);
    }

    /** 払って飛ばす(見直しの間・確率を見ない。テストとコマンドから)。払えなければ false。 */
    public static boolean launchFlight(ServerLevel level, ForestOrgans.Organ o) {
        com.yayuy.overgrownvegetation.data.EconomyTable e = com.yayuy.overgrownvegetation.director.ForestEconomy.table();
        double k = DesignData.organs().strength(o.small);
        double price = e.towerFlightCost * k;
        ForestDirector dir = ForestDirector.get(level);
        if (!dir.tryPay(level, price)) return false;
        com.yayuy.overgrownvegetation.director.ForestEconomy.noteTowerSpend(price);
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.launchParcel(level, o.anchor(), towerSporePerDay(o) * e.towerFlightMassDays);
        dir.note(String.format(java.util.Locale.ROOT, "胞子の塔 #%d が長距離の飛翔を起こした(予算 −%.0f)", o.id, price));
        return true;
    }

    private static void guards(ServerLevel level, ForestOrgans.Organ o, long now, OrganTable t, boolean peaceful) {
        int want = o.small ? t.smallGuardCount : t.guardCount;
        if (o.takeover != null) want = takeoverGuards(o); // 乗っ取りの器官は守りを減らす(11-20 ユーザー)
        if (peaceful || want <= 0 || !o.resolved() || !OrganWorld.ready(level, o.x >> 4, o.z >> 4)) return;
        if (now - o.guardAt < t.guardCooldownSeconds * 20L) return;
        BlockPos at = o.anchor();
        if (!OrganWorld.playerNear(level, at, t.guardPlayerBlocks)) return;
        o.guardAt = now;
        int have = trackedGuards(level, o, at);
        OrganBlueprint bp = OrganWorld.blueprint(o);
        for (int i = have; i < want; i++) {
            BlockPos spot = bp == null ? at.above() : at.offset(i % 2 == 0 ? bp.entrance : bp.coreCenter);
            Mob m = OrganWorld.spawnNear(level, spot, i % 2 == 0 ? 4 : 5);
            if (m != null) o.guards.add(m.getUUID());
        }
    }

    /**
     * 乗っ取りで生えた器官の守りの数(11-20 ユーザー「守りの mob の湧く量を少なくするだけにしとこう」。働きは変えない)。
     * 小さい器官は guardSmallChance の割りで 1 体(場所と種で決まるので、出し直しのたびに引き直さない)、大きい器官は guardBig 体。値は【仮】。
     */
    static int takeoverGuards(ForestOrgans.Organ o) {
        com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable ft = com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.table();
        if (!o.small) return ft.takeoverGuardBig;
        long h = net.minecraft.util.Mth.getSeed(o.x, o.seed, o.z);
        double roll = ((h >>> 11) & ((1L << 24) - 1)) / (double) (1 << 24);
        return roll < ft.takeoverGuardSmallChance ? 1 : 0;
    }

    /**
     * この器官の守りの数(11-18 見直し ②4・ユーザー決定 3)。前は芯のまわりの箱で数えていたので、守りを外へ連れ出すと
     * 数に入らず、予算を払わずに 120 秒ごとに増えた(倒すと標本と素材が落ちる = ただで稼げた)。今は出した守りの UUID で数え、
     * 死んだ・消えた守りだけを外す({@link OrganWorld#onGuardLeave})。読み込みの外へ歩いた守りは数えたまま。
     * 11-18 より前の保存は UUID を持たないので、空のときはまわりの守りの印の Mob を引き取る。
     */
    static int trackedGuards(ServerLevel level, ForestOrgans.Organ o, BlockPos at) {
        o.guards.removeIf(u -> {
            net.minecraft.world.entity.Entity e = level.getEntity(u);
            return e != null && !e.isAlive();
        });
        if (o.guards.isEmpty()) {
            double r = o.small ? 16 : OrganBlueprint.MAX_HALF + 16;
            for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(r, 48.0D, r), m -> m.getTags().contains(OrganWorld.GUARD_TAG))) {
                o.guards.add(m.getUUID());
            }
        }
        return o.guards.size();
    }

    private static int countGuards(ServerLevel level, BlockPos at, double r) {
        return level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(r, 48.0D, r), m -> m.getTags().contains(OrganWorld.GUARD_TAG)).size();
    }
}
