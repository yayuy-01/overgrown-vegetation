package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.data.DesignData;
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
 *   <li>フェーズを進める器官: 今のフェーズの閾値の % をポイントに(難易度の倍率は {@link PhaseData#addPoints} が掛ける)。</li>
 *   <li>胞子の塔: 口から胞子の場へ点の発生源({@code SporeFieldServer.emitPoint})。</li>
 *   <li>蓄えの嚢: 司令塔の上限(ForestDirector#cap が {@link OrganWorld#sacHiveEquivalent} を読む)。ここでは何もしない。</li>
 *   <li>苗床の群体: プレイヤーが近い間、予算で G4・G5 を産む。</li>
 *   <li>地脈を吸う根: 植物側の Mob のステータス({@link LeyBuffs}。ワールド全体)と、根晶の生え直し。</li>
 * </ul>
 * どれも、守りの Mob(プレイヤーが近いとき、入口と芯の近く)を置く。
 */
public final class OrganRoles {
    private OrganRoles() {
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
                int phase = pd.getPhase();
                long threshold = DesignData.phases().pointsToAdvance(phase);
                if (threshold > 0L && !peaceful) {
                    double raw = threshold * t.phasePercentPerDay / 100.0D * t.strength(o.small) * days + o.pointCarry;
                    long whole = (long) Math.floor(raw);
                    o.pointCarry = raw - whole;
                    if (whole > 0L) pd.addPoints(level, whole);
                }
            }
            case TOWER -> {
                if (!peaceful && t.towerSporePerDay > 0.0D) {
                    double perDay = t.towerSporePerDay * t.strength(o.small);
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
                }
            }
            case NURSERY -> nursery(level, o, now, t, peaceful);
            case LEY -> {
                if (now - o.crystalAt >= (long) (t.crystalRegrowDays * 24000.0D) && level.hasChunk(o.x >> 4, o.z >> 4)) {
                    if (OrganWorld.regrowCrystal(level, o)) o.crystalAt = now;
                    else o.crystalAt = now; // 全部生えていても、次はまた半日後に見る
                }
            }
            case SAC -> {
            }
        }
        regrowSacs(level, o, t);
        guards(level, o, now, t, peaceful);
        data.changed();
    }

    /**
     * 破裂した嚢が生え直す(11-17 深夜ユーザー「時間で生え直す」。一度通った器官も時間を置くとまた危なくなる)。100 tick ごとに
     * 設計図から 64 か所をくじで引き、破れた嚢を確率で戻す。確率は「嚢 1 つあたり平均 {@code sacRegrowDays} 日」になるように
     * (引いた所のうち嚢の割合で数が釣り合うので、設計図の嚢の数を数えなくてよい)。器官が枯れたら呼ばれない(生え直さない)。
     */
    private static void regrowSacs(ServerLevel level, ForestOrgans.Organ o, OrganTable t) {
        if (!o.resolved() || !level.hasChunk(o.x >> 4, o.z >> 4)) return;
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
            if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) continue;
            var s = level.getBlockState(at);
            if (s.is(OrganBlocks.ORGAN_SAC.get()) && s.getValue(OrganTissueBlock.WITHERED)) {
                level.setBlock(at, s.setValue(OrganTissueBlock.WITHERED, false), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
    }

    private static void nursery(ServerLevel level, ForestOrgans.Organ o, long now, OrganTable t, boolean peaceful) {
        if (peaceful || !o.resolved() || !level.hasChunk(o.x >> 4, o.z >> 4)) return;
        if (now - o.nurseryAt < (long) (t.nurseryIntervalSeconds * 20L / Math.max(0.05D, t.strength(o.small)))) return; // 小さい版はまれに
        BlockPos at = o.anchor();
        if (!OrganWorld.playerNear(level, at, t.nurseryPlayerBlocks)) return;
        o.nurseryAt = now;
        if (countGuards(level, at, t.nurseryPlayerBlocks) >= Math.max(1, (int) Math.round(t.nurseryMaxAlive * t.strength(o.small))) + t.guardCount) return;
        // 予算で産む(苗床のゲスタトリクスと同じ値段)。G4 と G5 から
        net.minecraft.world.entity.EntityType<?> type = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickPlant(level, level.getRandom(), 0);
        if (type == null) return;
        ForestDirector dir = ForestDirector.get(level);
        double cost = ForestDirector.spawnCost(type);
        if (!dir.tryPay(level, cost)) return;
        OrganBlueprint bp = OrganWorld.blueprint(o);
        BlockPos spot = bp != null ? at.offset(bp.coreCenter) : at.above();
        Mob m = OrganWorld.spawnNear(level, spot, 0);
        if (m == null) dir.refund(level, cost);
    }

    private static void guards(ServerLevel level, ForestOrgans.Organ o, long now, OrganTable t, boolean peaceful) {
        int want = o.small ? t.smallGuardCount : t.guardCount;
        if (peaceful || want <= 0 || !o.resolved() || !level.hasChunk(o.x >> 4, o.z >> 4)) return;
        if (now - o.guardAt < t.guardCooldownSeconds * 20L) return;
        BlockPos at = o.anchor();
        if (!OrganWorld.playerNear(level, at, t.guardPlayerBlocks)) return;
        o.guardAt = now;
        int have = countGuards(level, at, o.small ? 16 : OrganBlueprint.MAX_HALF + 16);
        OrganBlueprint bp = OrganWorld.blueprint(o);
        for (int i = have; i < want; i++) {
            BlockPos spot = bp == null ? at.above() : at.offset(i % 2 == 0 ? bp.entrance : bp.coreCenter);
            OrganWorld.spawnNear(level, spot, i % 2 == 0 ? 4 : 5);
        }
    }

    private static int countGuards(ServerLevel level, BlockPos at, double r) {
        return level.getEntitiesOfClass(Mob.class, new AABB(at).inflate(r, 48.0D, r), m -> m.getTags().contains(OrganWorld.GUARD_TAG)).size();
    }
}
