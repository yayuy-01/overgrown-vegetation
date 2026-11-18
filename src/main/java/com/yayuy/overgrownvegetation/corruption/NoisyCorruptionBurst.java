package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 「その場を一発でごっそり汚染する」共通処理 — {@link CorruptionSpreader} のように少しずつ広がる
 * のではなく、呼ばれたその瞬間に球状の範囲を汚染ブロックへ変える。
 *
 * <p>境界は<b>綺麗な球にならない</b>。方向ごとに低周波の正弦波ノイズ({@link Wobble})で
 * {@code noiseAmplitude} 分だけ波打たせ、さらに最外周 1 ブロックを半々で欠けさせるので、
 * 瘤とへこみの混じった輪郭になる(ユーザー要望:「完璧な円ではなくある程度のノイズ混じりに」)。
 *
 * <p>使い手:
 * <ul>
 *   <li>{@link com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation} — 過繁茂した
 *       クリーパーの爆発。</li>
 *   <li>{@link com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity} — グループ4
 *       (拡大種)の死亡時({@link #deathSphere})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NoisyCorruptionBurst {

    // ------------------------------------------------------------------------------------------
    // 死亡時の汚染球の 1 tick あたりの上限(2026-09-24 軽量化、報告 235 / 268 / 578 / 686)
    // ------------------------------------------------------------------------------------------

    /**
     * 死亡時の汚染球({@link #deathSphere})を、1 tick に何個までその場で作るか。仮決め 2026-09-24。
     *
     * <p>以前は死んだ tick のうちに、死んだ数だけ球を作っていた(1 個で数十〜150 マスの設置・草生やし・
     * バイオーム変換の打診)。砲台やレイドで一度に何体も倒れると、その tick だけ数千マスの書き換えが重なる。
     * この数までは今までどおり死んだその場で作り(ふだんの戦闘は何も変わらない)、超えたぶんだけ死んだ順に
     * 積んで、次の tick 以降の頭に同じ数ずつ作る。作る球の形・置き換えの規則は同じで、作る tick だけが後ろへずれる。
     */
    private static final int DEATH_BURSTS_PER_TICK = 3;

    /** 積んである死亡時の汚染球 1 個ぶん(中心・半径・揺らぎ・胞子の場へ放出するか)。 */
    private record PendingBurst(Vec3 center, int radius, double noiseAmplitude, boolean emitSpores) {
    }

    /** 次元ごとの「この tick に作った数」と、超えたぶんの待ち行列(死んだ順)。サーバースレッドだけが触る。 */
    private static final class DeathBurstBudget {
        final ArrayDeque<PendingBurst> pending = new ArrayDeque<>();
        int usedThisTick;
    }

    private static final Map<ResourceKey<Level>, DeathBurstBudget> DEATH_BURSTS = new HashMap<>();

    /**
     * 死亡時の汚染球。{@link #sphere} と同じ球を作るが、同じ tick の {@link #DEATH_BURSTS_PER_TICK} 個目より後ろは
     * 次の tick 以降へ回す(胞子除去装置の判定などの「作るかどうか」は呼ぶ側が死んだ時点で済ませること)。
     */
    public static void deathSphere(ServerLevel level, Vec3 center, int radius, double noiseAmplitude) {
        deathSphere(level, center, radius, noiseAmplitude, true);
    }

    /**
     * {@link #deathSphere(ServerLevel, Vec3, int, double)} の、胞子の場への点の放出を選べる形(2026-11-12 G1 進化体の破裂)。
     * {@code emitSpores} が false なら球はブロックだけを汚染し、浮遊は呼ぶ側が自分の量で上げる
     * ({@link com.yayuy.overgrownvegetation.corpse.G1DeathBurst}。ユーザー決定「G1 だけ小さく」。ほかの死に方の量は変えない)。
     */
    public static void deathSphere(ServerLevel level, Vec3 center, int radius, double noiseAmplitude, boolean emitSpores) {
        DeathBurstBudget budget = DEATH_BURSTS.computeIfAbsent(level.dimension(), key -> new DeathBurstBudget());
        if (budget.pending.isEmpty() && budget.usedThisTick < DEATH_BURSTS_PER_TICK) {
            budget.usedThisTick++;
            sphere(level, center, radius, noiseAmplitude, emitSpores);
            return;
        }
        if (budget.pending.size() >= MAX_PENDING) {
            // 待ち行列があふれた(2026-11-16 コードの見直し ③5: 1 万体の襲撃で約 3 分遅れていた): 球を作らず、胞子の場の降り積もりに替える
            // (そのチャンクがやがて発芽して汚染が始まる。すぐ汚れないだけで、汚染そのものは消えない)
            toDeposit(level, center);
            return;
        }
        budget.pending.addLast(new PendingBurst(center, radius, noiseAmplitude, emitSpores));
    }

    /**
     * 待ち行列の長さの上限(1 tick 3 個で約 10 秒ぶん)【仮】。これより後ろは球を作らず {@link #DEPOSIT_PER_DROPPED} の降り積もりに替える。
     */
    static final int MAX_PENDING = 600;
    /** 作らなかった球 1 個の替わりの降り積もり(場の単位。発芽のしきい値 0.05 と同じ)【仮】。 */
    static final double DEPOSIT_PER_DROPPED = 0.05D;
    /** 数え(計測・テスト): 降り積もりに替えた球の数。 */
    public static long droppedToDeposit;

    /** テスト用: その次元の待ち行列の長さ。 */
    public static int pendingForTests(ServerLevel level) {
        DeathBurstBudget b = DEATH_BURSTS.get(level.dimension());
        return b == null ? 0 : b.pending.size();
    }

    /** テスト用: その次元の待ち行列を捨てる(ほかのテストの場を汚さない)。 */
    public static void clearPendingForTests(ServerLevel level) {
        DeathBurstBudget b = DEATH_BURSTS.get(level.dimension());
        if (b != null) b.pending.clear();
    }

    private static void toDeposit(ServerLevel level, Vec3 center) {
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(level, net.minecraft.core.BlockPos.containing(center), DEPOSIT_PER_DROPPED);
        droppedToDeposit++;
    }

    /** tick の頭で数え直し、積んであるぶんを死んだ順に上限まで作る(残りはさらに次の tick)。 */
    @SubscribeEvent
    public static void onLevelTickStart(LevelTickEvent.Pre event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        DeathBurstBudget budget = DEATH_BURSTS.get(level.dimension());
        if (budget == null) {
            return;
        }
        budget.usedThisTick = 0;
        while (!budget.pending.isEmpty() && budget.usedThisTick < DEATH_BURSTS_PER_TICK) {
            PendingBurst burst = budget.pending.pollFirst();
            budget.usedThisTick++;
            sphere(level, burst.center(), burst.radius(), burst.noiseAmplitude(), burst.emitSpores());
        }
    }

    /**
     * 止める直前: 積み残しは作らず、胞子の場の降り積もりに替えて捨てる(2026-11-16 コードの見直し ③5: 前は全部その場で作り、
     * 1 万個なら約 2 秒止まっていた)。降り積もりは胞子の場の入力なので、場が写しを取る前に流れるよう優先度を高くする。
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    public static void onServerStopping(ServerStoppingEvent event) {
        // キーの写しで回す
        for (ResourceKey<Level> dimension : new ArrayList<>(DEATH_BURSTS.keySet())) {
            ServerLevel level = event.getServer().getLevel(dimension);
            ArrayDeque<PendingBurst> pending = DEATH_BURSTS.get(dimension).pending;
            while (level != null && !pending.isEmpty()) toDeposit(level, pending.pollFirst().center());
        }
        DEATH_BURSTS.clear();
    }

    /** 次元が閉じたらその次元の積み残しを捨てる(同じキーで開き直した次元へ持ち越さない)。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            DEATH_BURSTS.remove(level.dimension());
        }
    }

    /** ワールドを閉じたら全部捨てる(キーは次元だけなので、次に開いた別のワールドへ持ち越さない)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        DEATH_BURSTS.clear();
    }

    /**
     * {@code center} を中心にした半径 {@code radius} の球を汚染する。
     *
     * @param noiseAmplitude 境界を方向ごとに波打たせる振幅(ブロック、±)。0 なら綺麗な球。
     * @return 実際に汚染したブロック数。
     */
    public static int sphere(ServerLevel level, Vec3 center, int radius, double noiseAmplitude) {
        return sphere(level, center, radius, noiseAmplitude, true);
    }

    /** {@link #sphere(ServerLevel, Vec3, int, double)} の、胞子の場への点の放出を選べる形(2026-11-12)。 */
    private static int sphere(ServerLevel level, Vec3 center, int radius, double noiseAmplitude, boolean emitSpores) {
        if (radius <= 0) {
            return 0;
        }
        // 胞子の場にも点の放出を足す(2026-10-23 胞子の場と風 第 2 段 C。G4 の死・寄生クリーパーなどの汚染の球。
        // G1 進化体の破裂は 11-12 から自分の小さな量で放出するので、ここでは足さない = emitSpores false)
        if (emitSpores && !CorruptionSwitch.isOff()) {
            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.emitPoint(level, net.minecraft.core.BlockPos.containing(center),
                    com.yayuy.overgrownvegetation.data.DesignData.sporeField().burstPerRadiusSq() * radius * radius);
        }
        RandomSource random = level.random;
        // 呼び出しごとに違う波打ち方にするための位相。
        double seed = (center.x * 0.11D) + (center.y * 0.07D) + (center.z * 0.13D) + random.nextInt(1024);

        int cx = (int) Math.floor(center.x);
        int cy = (int) Math.floor(center.y);
        int cz = (int) Math.floor(center.z);
        int scan = radius + (int) Math.ceil(noiseAmplitude) + 1;
        // wobble は ±1 に収まるので、中心から radius + |noiseAmplitude| より遠いマスは下の
        // 「dist > effectiveRadius」で必ず弾かれる(縁の乱数を引く前)。その外側を sin/sqrt の前に捨てる。
        // 浮動小数の誤差で境目のマスを取りこぼさないよう 0.5 ブロックの余裕を持たせてある。
        // 位相が NaN・無限(中心座標が壊れているとき)だと wobble も NaN になり元の判定では弾かれないので、
        // そのときは絞らない(振幅が NaN・無限のときも reachSq が NaN・無限になって絞られない)。
        double reach = radius + Math.abs(noiseAmplitude) + 0.5D;
        double reachSq = Math.abs(seed) < 1.0e300D ? reach * reach : Double.POSITIVE_INFINITY;
        // wobble の 4 つの sin は、それぞれ dx・dz・dy・(dx + dz) だけの関数なので、この呼び出しの分を先に表へ
        // 計算しておき、マスごとには表を引く(2026-09-24 軽量化、報告 578 の (3))。sin に渡す式も 4 つを足す順番も
        // 元の wobble と同じなので、値はビット単位で同じ(爆発 1 回で数千回あった sin が 100 回ほどになる)。
        Wobble wobble = scan >= 0 ? new Wobble(scan, seed) : null;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongOpenHashSet touchedChunks = new LongOpenHashSet();
        int corruptedBlocks = 0;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > reachSq) {
                        continue;
                    }
                    double dist = Math.sqrt(distSq);
                    double effectiveRadius = radius + wobble.at(dx, dy, dz, dist) * noiseAmplitude;
                    if (dist > effectiveRadius) {
                        continue;
                    }
                    // 縁(外周1ブロック)は半々で欠けさせて、輪郭をさらにギザギザにする。
                    if (dist > effectiveRadius - 1.0D && random.nextFloat() < 0.5F) {
                        continue;
                    }

                    pos.set(cx + dx, cy + dy, cz + dz);
                    if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
                        continue;
                    }
                    BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, random);
                    if (CorruptionRegistry.noChange(state, corrupted)) {
                        CorruptionSpreader.growPlantsIfNewlyCovered(level, pos, state); // 覆っただけのマスにも植物(2026-10-10)
                        continue;
                    }

                    BlockPos placed = pos.immutable();
                    CorruptionRegistry.placeCorrupted(level, placed, state, corrupted, Block.UPDATE_ALL);
                    corruptedBlocks++;
                    CorruptionSpreader.growSurfacePlants(level, placed, corrupted,
                            OvergrownVegetationConfig.COMMON.growthChance.get());
                    touchedChunks.add(new ChunkPos(placed).toLong());
                }
            }
        }

        CorruptionCounter.record(level, corruptedBlocks);

        for (long packedChunk : touchedChunks) {
            OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(packedChunk));
        }
        return corruptedBlocks;
    }

    /**
     * 1 ブロックだけ汚染する(グループ4の「じわじわ広がる弱い浸食」用)。
     * 既に汚染済み・汚染できないブロックなら何もせず {@code false}。
     *
     * <p>{@link #sphere} の 1 マス分と同じ手順 — 変換・設置・地表の草生やし・カウント・
     * バイオーム変換の打診まで面倒を見る。呼ぶ側は「どこを 1 マス汚すか」だけ決めればよい。
     */
    public static boolean single(ServerLevel level, BlockPos pos) {
        return single(level, pos, null);
    }

    /**
     * {@link #single(ServerLevel, BlockPos)} の、<b>カウントとバイオーム変換の打診を呼び出し側でまとめる</b>版
     * (2026-09-24 軽量化、報告 739)。{@code touchedChunks} が null でなければ、汚染できたマスのチャンクをそこへ足すだけで
     * {@link CorruptionCounter#record} と {@link OvergrownBiomeConverter#maybeConvert} は呼ばない —— 呼び出し側がループの後で
     * {@link #sphere} と同じ形(成功数を 1 回で記録、触れたチャンクごとに打診 1 回)で呼ぶ。
     * 近い場所をまとめて汚染するとき、同じチャンクへの打診(地表 256 列の走査・縁の描き直し)がマスの数だけ走るのを防ぐ。
     * 汚染・設置・草生やしはどちらでも同じ(どれもバイオームを見ないので、打診が後ろへずれても置かれるものは変わらない)。
     */
    public static boolean single(ServerLevel level, BlockPos pos, @Nullable LongSet touchedChunks) {
        if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(ModTags.CORRUPTED) || !CorruptionRegistry.canCorrupt(level, pos, state)) {
            return false;
        }
        BlockState corrupted = CorruptionRegistry.corrupt(level, pos, state, level.random);
        if (CorruptionRegistry.noChange(state, corrupted)) {
            CorruptionSpreader.growPlantsIfNewlyCovered(level, pos, state); // 覆っただけのマスにも植物(2026-10-10)
            return false;
        }
        BlockPos placed = pos.immutable();
        CorruptionRegistry.placeCorrupted(level, placed, state, corrupted, Block.UPDATE_ALL);
        CorruptionSpreader.growSurfacePlants(level, placed, corrupted,
                OvergrownVegetationConfig.COMMON.growthChance.get());
        if (touchedChunks != null) {
            touchedChunks.add(new ChunkPos(placed).toLong());
            return true;
        }
        CorruptionCounter.record(level, 1);
        OvergrownBiomeConverter.maybeConvert(level, new ChunkPos(placed));
        return true;
    }

    /**
     * 方向ごとの境界の波打ち量(おおよそ -1〜+1)。オフセットそのものを低周波の正弦波に通した
     * 3D の値ノイズで、なだらかな瘤ができる(方向を正規化しないので原点付近では 0 に近く、
     * 綺麗な球からの膨らみ/へこみとして効く)。
     *
     * <p>式は {@code sin(dx*0.9+seed) + sin(dz*0.9+seed*1.3) + sin(dy*1.1+seed*0.7) + sin((dx+dz)*0.5+seed*1.9)}
     * を 4 で割ったもの。各項は 1 つの整数だけで決まるので、{@link #sphere} の 1 回ぶん(|dx|, |dy|, |dz| <= scan)を
     * 生成時に表へ計算しておく(2026-09-24 軽量化、報告 578。以前はマスごとに sin を 4 回計算していた)。
     * 表に入れる値は元の式とまったく同じ引数で {@link Math#sin} を呼んだもので、足す順番も同じ。
     */
    private static final class Wobble {
        private final int scan;
        private final double[] sinX;
        private final double[] sinZ;
        private final double[] sinY;
        /** {@code dx + dz}(-2scan 〜 2scan)で引く。 */
        private final double[] sinXZ;

        Wobble(int scan, double seed) {
            this.scan = scan;
            int side = scan * 2 + 1;
            this.sinX = new double[side];
            this.sinZ = new double[side];
            this.sinY = new double[side];
            for (int i = 0; i < side; i++) {
                int d = i - scan;
                sinX[i] = Math.sin(d * 0.9D + seed);
                sinZ[i] = Math.sin(d * 0.9D + seed * 1.3D);
                sinY[i] = Math.sin(d * 1.1D + seed * 0.7D);
            }
            this.sinXZ = new double[scan * 4 + 1];
            for (int i = 0; i < sinXZ.length; i++) {
                int d = i - scan * 2;
                sinXZ[i] = Math.sin(d * 0.5D + seed * 1.9D);
            }
        }

        double at(int dx, int dy, int dz, double dist) {
            if (dist < 1.0e-4D) {
                return 0.0D;
            }
            double v = sinX[dx + scan]
                    + sinZ[dz + scan]
                    + sinY[dy + scan]
                    + sinXZ[dx + dz + scan * 2];
            return v * 0.25D;
        }
    }

    private NoisyCorruptionBurst() {
    }
}
