package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 巨大樹({@link GiantTreeFeature})を<b>複数ゲームティックに分けて</b>設置するキュー。
 *
 * <p>巨大樹は枝分かれ・傘の拡大で約10〜18万ブロックあり、苗木のランダムティック中に一度に置くと
 * サーバースレッドが固まる(不透明な原木の光更新が大量に走るため)。そこで {@code place()} は
 * 座標計算だけ行って {@link #enqueue} でここへ登録し、実際の設置は毎ティック
 * {@code giantTreeBlocksPerTick}(既定2500)個ずつに分けて行う。どの1ティックも重くならない。
 *
 * <p>設置は<b>外殻(原木)→内側(形成層)→核→髄→鉱石ノード→葉</b>の順。フェーズ2の走査対象は外殻と葉だけ
 * (内側・核・髄・鉱石は空気に触れないので装飾しない)。
 *
 * <p><b>3フェーズ</b>: (1) 全ブロックを設置 → (2) 外殻・葉をもう一周して、露出面に
 * {@code giantTreePlantChance} で汚染ブロックと同じ装飾植物(シダ・苔・蔦・垂れ植物、
 * {@link CorruptionSpreader#growSurfacePlants})を、原木の露出面に {@code giantTreeGlowLichenChance}
 * でヒカリゴケ({@code minecraft:glow_lichen})を生やす → (3) 根を這わせた各歩の足元座標
 * ({@link GiantTreeFeature.Plan#rootColumns})をたどり、かまぼこ状の盛り上がりの頂上に
 * {@code giantTreeRootPlantChance} で汚染時と同じ上面の下草({@link CorruptionSpreader#growTopUndergrowth})
 * を生やす(根が地面と一体化して苔むして見える)。各フェーズの確率が 0 ならそのフェーズはスキップ。
 * フェーズ2・3も同じ毎ティック予算で分割する。
 *
 * <p>キューはメモリ上のみ(NBT保存はしない)。セーブ&終了を生成の途中で行うと木が
 * 未完成のまま止まるが、生成は数秒で終わるうえプレイヤーはその場で見ているので実害は小さい。
 * レベルのアンロード時にそのレベルのジョブは破棄する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GiantTreeGrowthQueue {

    /** {@link GiantTreeFeature} と同じ軽い設置フラグ(近傍更新・形状更新を省略)。 */
    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 「巣の広間」の内側を掘るときに置くブロック(= 完全な空洞)。 */
    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    /** フェーズ2(装飾植物)は1面判定が軽いので、毎ティック予算1あたりこの数のブロックを処理する。 */
    private static final int DECOR_PER_BUDGET = 3;

    private static final List<Job> JOBS = new ArrayList<>();

    private static final class Job {
        final ServerLevel level;
        final BlockState trunkState;
        final BlockState cambiumState;
        final BlockState chlorophylliteOreState;
        final BlockState hardenedSapCrystalOreState;
        final BlockState leafState;
        final BlockState hiveShellState;
        final BlockState hiveBarkState;
        final LongArrayList trunk;    // 外殻(原木)
        final LongArrayList cambium;  // 内側(形成層)
        final LongArrayList chlorophylliteOre;      // 形成層に埋まる鉱石ノード(多め)
        final LongArrayList hardenedSapCrystalOre;  // 形成層に埋まる鉱石ノード(まれ)
        final LongArrayList hiveBark;    // 「巣の広間」の殻を包む樹皮(巨大樹の原木)
        final LongArrayList hiveShell;   // 幹の真下の「巣の広間」の殻(硬化した巨大樹の幹)
        final LongArrayList hiveFill;    // 「巣の球」の詰め物(形成層)
        final LongArrayList hiveHollow;  // 「巣の球」の中で掘った空気(小部屋・儀式の広間・通路)
        final LongArrayList hiveLining;  // 掘った空気に面した詰め物 = 通路・広間の壁面(巨大樹の幹)
        final LongArrayList leaves;
        /** フェーズ3: 根を這わせた各歩の足元(地表)の座標。頂上を探して下草を生やす。 */
        final LongArrayList rootColumns;
        // 洞窟の柱の付け根の天井から垂らす dangling_roots の房(2026-11-07)。天井の真下の空気のマスと長さ。
        final LongArrayList danglingTops;
        final it.unimi.dsi.fastutil.ints.IntArrayList danglingLens;
        final int trunkSize;
        final int cambiumSize;
        final int chlorophylliteOreSize;
        final int hardenedSapCrystalOreSize;
        final int hiveBarkSize;
        final int hiveShellSize;
        final int hiveFillSize;
        final int hiveHollowSize;
        final int hiveLiningSize;
        /** 儀式の広間の床の中心({@link BlockPos#asLong})。作らなかった場合は {@link Long#MIN_VALUE}。 */
        final long hiveRitualCenter;
        /** 広間からハイヴノードの小部屋を見る向き(yaw、度)= 儀式中のプレイヤーの向き。 */
        final float hiveRitualYaw;
        /** ハイヴノードが向く方角(度)= 広間の方。 */
        final float hiveNodeYaw;
        /** フェーズ2: 通路・広間の壁面(内張り)に下草・蔦・垂れ植物を生やす1面あたりの確率。 */
        final double hiveGrassChance;
        /** 「巣の広間」の中心({@link BlockPos#asLong})。作らなかった場合は {@link Long#MIN_VALUE}。 */
        final long hiveCenter;
        /** 「巣の広間」の球(殻まで)の半径。ハイヴノードを吊る高さを決めるのに使う。 */
        final int hiveRadius;
        /** フェーズ1: 外殻+内側+核+髄+鉱石+葉 の合計。 */
        final long placeTotal;
        /** フェーズ2: 走査対象は外殻+葉のみ。 */
        final long decorTotal;
        /** フェーズ3: 走査対象は根の足元座標。 */
        final long rootTotal;
        /** フェーズ2: 露出面に装飾植物を生やす1面あたりの確率(0 ならスキップ)。 */
        final double plantChance;
        /** フェーズ2: 原木の露出面にヒカリゴケを貼る1面あたりの確率(0 ならスキップ)。 */
        final double glowLichenChance;
        /** フェーズ3: 根の盛り上がりの頂上に汚染時と同じ下草を生やす確率(0 ならスキップ)。 */
        final double rootPlantChance;
        /** フェーズ1: ブロック設置のカーソル(0〜placeTotal)。 */
        long cursor;
        /** フェーズ2: 外殻+葉を走査するカーソル(0〜decorTotal)。 */
        long decorCursor;
        /** フェーズ3: 根の足元座標を走査するカーソル(0〜rootTotal)。 */
        long rootCursor;

        Job(ServerLevel level, GiantTreeFeature.Plan plan) {
            this.level = level;
            this.trunkState = plan.trunkState;
            this.cambiumState = plan.cambiumState;
            this.chlorophylliteOreState = plan.chlorophylliteOreState;
            this.hardenedSapCrystalOreState = plan.hardenedSapCrystalOreState;
            this.leafState = plan.leafState;
            this.hiveShellState = plan.hiveShellState;
            this.hiveBarkState = plan.hiveBarkState;
            this.trunk = plan.trunk;
            this.cambium = plan.cambium;
            this.chlorophylliteOre = plan.chlorophylliteOre;
            this.hardenedSapCrystalOre = plan.hardenedSapCrystalOre;
            this.hiveBark = plan.hiveBark;
            this.hiveShell = plan.hiveShell;
            this.hiveFill = plan.hiveFill;
            this.hiveHollow = plan.hiveHollow;
            this.hiveLining = plan.hiveLining;
            this.leaves = plan.leaves;
            this.rootColumns = plan.rootColumns;
            this.danglingTops = plan.danglingTops;
            this.danglingLens = plan.danglingLens;
            this.trunkSize = plan.trunk.size();
            this.cambiumSize = plan.cambium.size();
            this.chlorophylliteOreSize = plan.chlorophylliteOre.size();
            this.hardenedSapCrystalOreSize = plan.hardenedSapCrystalOre.size();
            this.hiveBarkSize = plan.hiveBark.size();
            this.hiveShellSize = plan.hiveShell.size();
            this.hiveFillSize = plan.hiveFill.size();
            this.hiveHollowSize = plan.hiveHollow.size();
            this.hiveLiningSize = plan.hiveLining.size();
            this.hiveRitualCenter = plan.hiveRitualCenter;
            this.hiveRitualYaw = plan.hiveRitualYaw;
            this.hiveNodeYaw = plan.hiveNodeYaw;
            this.hiveCenter = plan.hiveCenter;
            this.hiveRadius = plan.hiveRadius;
            this.placeTotal = (long) trunkSize + cambiumSize
                    + chlorophylliteOreSize + hardenedSapCrystalOreSize
                    + hiveBarkSize + hiveShellSize + hiveFillSize + hiveLiningSize + hiveHollowSize
                    + leaves.size();
            // フェーズ2 の走査対象: 外殻 → 葉 → 巣の球の内張り(通路・広間の壁面)。
            this.decorTotal = (long) trunkSize + leaves.size() + hiveLiningSize;
            this.rootTotal = rootColumns.size();
            this.plantChance = OvergrownVegetationConfig.COMMON.giantTreePlantChance.get();
            this.glowLichenChance = OvergrownVegetationConfig.COMMON.giantTreeGlowLichenChance.get();
            this.rootPlantChance = OvergrownVegetationConfig.COMMON.giantTreeRootPlantChance.get();
            this.hiveGrassChance = OvergrownVegetationConfig.COMMON.hiveTunnelGrassChance.get();
        }

        boolean hasDecorPhase() {
            return plantChance > 0.0D || glowLichenChance > 0.0D || (hiveGrassChance > 0.0D && hiveLiningSize > 0);
        }

        boolean hasRootPhase() {
            return rootPlantChance > 0.0D && rootTotal > 0L;
        }

        boolean decorDone() {
            return !hasDecorPhase() || decorCursor >= decorTotal;
        }

        boolean done() {
            return cursor >= placeTotal && decorDone() && (!hasRootPhase() || rootCursor >= rootTotal);
        }
    }


    /**
     * 巨大樹が生えきったら、その真下の「巣の球」の中心の小部屋に「ハイヴノード」を 1 体置く
     * ({@link com.yayuy.overgrownvegetation.entity.HiveNodeEntity})。旧「古代樹の核」ブロックの代わりで、
     * この mob が生きている間だけ周囲が胞子感染3 の強制ゾーンになる。
     *
     * <p>位置は球の<b>中心</b>({@code hiveNodeHeightOffset} でずらせる)。2026-09-18 から<b>吊り蔦は無い</b>
     * (球が中まで詰まった構造になり、ノードは小部屋の中に据わっている)。
     */
    private static void spawnHiveNode(ServerLevel level, Job job) {
        if (job.hiveCenter == Long.MIN_VALUE) {
            return;
        }
        int cx = BlockPos.getX(job.hiveCenter);
        int cy = BlockPos.getY(job.hiveCenter);
        int cz = BlockPos.getZ(job.hiveCenter);
        int y = cy + OvergrownVegetationConfig.COMMON.hiveNodeHeightOffset.get();
        BlockPos pos = new BlockPos(cx, y, cz);
        if (!level.isLoaded(pos)) {
            return;
        }
        // 二重湧き防止(同じ球に既に 1 体いるなら何もしない)。
        if (!level.getEntitiesOfClass(HiveNodeEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(job.hiveRadius)).isEmpty()) {
            return;
        }
        HiveNodeEntity node = ModEntities.HIVE_NODE.get().create(level);
        if (node == null) {
            return;
        }
        // 広間の方を向いて据わる(向きは以後固定。儀式中のプレイヤーと向かい合う)。
        node.moveTo(cx + 0.5D, y - node.getBbHeight() * 0.5D, cz + 0.5D, job.hiveNodeYaw, 0.0F);
        if (job.hiveRitualCenter != Long.MIN_VALUE) {
            node.setRitual(BlockPos.of(job.hiveRitualCenter), job.hiveRitualYaw);
        }
        level.addFreshEntity(node);
        OvergrownVegetation.LOGGER.info("GiantTree: 巣の球の小部屋に hive_node を置きました {} / 儀式の広間 {} (yaw {})",
                pos, job.hiveRitualCenter == Long.MIN_VALUE ? "なし" : BlockPos.of(job.hiveRitualCenter),
                job.hiveRitualYaw);
    }

    /**
     * 洞窟の柱の付け根の天井から {@code dangling_roots} の房を垂らす(2026-11-07)。根を全部置き終えた後に 1 回だけ。
     * 置く所が空気でなくなっていれば、{@code CaveFlora.hangRoots} が短くするか置かない。
     */
    /** 房を垂らす所のどれかのチャンクが読み込み途中なら、終わりを次の tick へ延ばす(房を永久に飛ばさない。11-18 見直し ③4)。 */
    private static boolean tuftsWaiting(ServerLevel level, Job job) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < job.danglingTops.size(); i++) {
            long k = job.danglingTops.getLong(i);
            p.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (ChunkEdgeGuard.edge(level, p, 1) == ChunkEdgeGuard.Edge.WAIT) return true;
        }
        return false;
    }

    private static void hangPillarTufts(ServerLevel level, Job job) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < job.danglingTops.size(); i++) {
            long k = job.danglingTops.getLong(i);
            p.set(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
            if (ChunkEdgeGuard.isLoaded(level, p) && ChunkEdgeGuard.isLoaded(level, p.above())) { // 11-18 見直し ③4
                com.yayuy.overgrownvegetation.corruption.CaveFlora.hangRoots(level, p.immutable(), job.danglingLens.getInt(i));
            }
        }
    }

    /** {@link GiantTreeFeature#place} から呼ぶ。以降このジョブを毎ティック少しずつ設置していく。 */
    public static void enqueue(ServerLevel level, GiantTreeFeature.Plan plan) {
        if (level == null || plan == null
                || (plan.trunk.isEmpty() && plan.cambium.isEmpty() && plan.leaves.isEmpty())) {
            return;
        }
        JOBS.add(new Job(level, plan));
        // このワールドに巨大樹(= 古代樹の核)が生えたことを記録する。汚染時の装飾アーチ(根)は
        // このフラグが立って初めて生えるようになる(CorruptionSpreader 参照)。
        PhaseData.get(level).markGiantTreeGrown();
    }

    /** そのワールドで設置待ち・設置中の巨大樹の数(GameTest から)。 */
    public static int pendingJobs(ServerLevel level) {
        int n = GiantTreePlanner.inFlight(level); // 計画中(別スレッド)の木も数える(2026-11-18 案 A)
        for (Job job : JOBS) {
            if (job.level == level) {
                n++;
            }
        }
        return n;
    }

    /**
     * そのワールドの設置待ち・設置中の巨大樹を捨てる(GameTest から: 誤って積まれた巨大樹をテストのワールドへ生やさない。
     * 積まれた同じ tick のうちに捨てれば 1 ブロックも置かれない)。
     *
     * @return 捨てた数
     */
    public static int discardJobsForTests(ServerLevel level) {
        int before = JOBS.size();
        JOBS.removeIf(job -> job.level == level);
        return before - JOBS.size() + GiantTreePlanner.discard(level);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (JOBS.isEmpty() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。番人に聞くのはこの次元に仕事があるときだけ。
        if (pendingJobs(serverLevel) == 0 || !TickBudget.mayStart(PerfJob.GIANT_TREE)) {
            return;
        }
        long start = TickBudget.begin();
        int perTick = OvergrownVegetationConfig.COMMON.giantTreeBlocksPerTick.get();
        int left = perTick;
        try {
            left = tickJobs(serverLevel, perTick);
        } finally {
            TickBudget.end(PerfJob.GIANT_TREE, start, perTick - left);
        }
    }

    /** 1 tick ぶん進める。戻り値は使い残した予算。 */
    private static int tickJobs(ServerLevel serverLevel, int perTick) {
        // 同時に複数本の生成中でも、1ティックあたりの合計がこの数を超えないよう配分する。
        int budget = perTick;
        BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            if (budget <= 0) {
                break;
            }
            Job job = it.next();
            if (job.level != serverLevel) {
                continue;
            }
            // フェーズ1: ブロックを設置する(外殻→内側→核→髄→葉の順)。
            while (job.cursor < job.placeTotal && budget > 0) {
                long c = job.cursor;
                long afterTrunk = job.trunkSize;
                long afterCambium = afterTrunk + job.cambiumSize;
                long afterChloro = afterCambium + job.chlorophylliteOreSize;
                long afterSap = afterChloro + job.hardenedSapCrystalOreSize;
                long afterHiveBark = afterSap + job.hiveBarkSize;
                long afterHiveShell = afterHiveBark + job.hiveShellSize;
                long afterHiveFill = afterHiveShell + job.hiveFillSize;
                long afterHiveLining = afterHiveFill + job.hiveLiningSize;
                long afterHiveHollow = afterHiveLining + job.hiveHollowSize;
                long packed;
                BlockState state;
                if (c < afterTrunk) {
                    packed = job.trunk.getLong((int) c);
                    state = job.trunkState;
                } else if (c < afterCambium) {
                    packed = job.cambium.getLong((int) (c - afterTrunk));
                    state = job.cambiumState;
                } else if (c < afterChloro) {
                    packed = job.chlorophylliteOre.getLong((int) (c - afterCambium));
                    state = job.chlorophylliteOreState;
                } else if (c < afterSap) {
                    packed = job.hardenedSapCrystalOre.getLong((int) (c - afterChloro));
                    state = job.hardenedSapCrystalOreState;
                } else if (c < afterHiveBark) {
                    // 「巣の広間」の殻を包む樹皮。殻より先に置いて、外から見えるのは樹皮だけにする。
                    packed = job.hiveBark.getLong((int) (c - afterSap));
                    state = job.hiveBarkState;
                } else if (c < afterHiveShell) {
                    // 幹の真下の「巣の広間」の殻。中身を掘る前に閉じた殻を先に作る。
                    packed = job.hiveShell.getLong((int) (c - afterHiveBark));
                    state = job.hiveShellState;
                } else if (c < afterHiveFill) {
                    // 「巣の球」の詰め物(形成層)。殻の内側を中まで埋める。
                    packed = job.hiveFill.getLong((int) (c - afterHiveShell));
                    state = job.cambiumState;
                } else if (c < afterHiveLining) {
                    // 通路・広間の壁面(内張り)。巨大樹の幹(原木)で、あとでフェーズ2 が下草・蔦を生やす。
                    packed = job.hiveLining.getLong((int) (c - afterHiveFill));
                    state = job.trunkState;
                } else if (c < afterHiveHollow) {
                    // 「巣の球」の中で掘る部分(小部屋・儀式の広間・通路)。石でも水でも建築物でも空気にする。
                    packed = job.hiveHollow.getLong((int) (c - afterHiveLining));
                    state = AIR;
                } else {
                    packed = job.leaves.getLong((int) (c - afterHiveHollow));
                    state = job.leafState;
                }
                cursorPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                // 途中でチャンクがアンロードされた場合はそのブロックは諦める(同期生成を避ける)。読み込み・生成の途中なら触らず、
                // このジョブは次の tick に同じ所から(11-18 見直し ③4。isLoaded は生成中でも true で、主スレッドが生成を待っていた)
                ChunkEdgeGuard.Edge edge = ChunkEdgeGuard.edge(serverLevel, cursorPos, 0);
                if (edge == ChunkEdgeGuard.Edge.WAIT) break;
                if (edge == ChunkEdgeGuard.Edge.READY && !TreePlacementRules.treeKeeps(serverLevel.getBlockState(cursorPos))
                        && !com.yayuy.overgrownvegetation.corruption.pushback.RingWards.guardedFrom(serverLevel, job.trunk.isEmpty() ? null : BlockPos.of(job.trunk.getLong(0)), cursorPos)) { // 根付いた萌芽体は残す。帯の線の向こう・杭の範囲には置かない(押し返し 11-18 夜)
                    serverLevel.setBlock(cursorPos.immutable(), state, UPDATE_FLAGS);
                }
                job.cursor++;
                budget = TickBudget.spend(budget);
            }
            // フェーズ2: 設置し終えたら、外殻・葉の露出面に装飾植物 + 原木にヒカリゴケを生やす。
            // 1面の判定は setBlock より軽いので、予算1あたり DECOR_PER_BUDGET 個ずつ進める。
            boolean stall = false;
            while (!stall && job.cursor >= job.placeTotal && job.hasDecorPhase()
                    && job.decorCursor < job.decorTotal && budget > 0) {
                for (int k = 0; k < DECOR_PER_BUDGET && job.decorCursor < job.decorTotal; k++) {
                    long packed;
                    BlockState host;
                    boolean isTrunk = job.decorCursor < job.trunkSize;
                    // 巣の球の内張り(通路・広間の壁面)。幹と同じ原木に、専用の確率(hiveTunnelGrassChance)で
                    // 下草・蔦・垂れ植物を生やす。洞窟なので growSurfacePlants の洞窟植物も混ざる。
                    boolean isLining = job.decorCursor >= job.trunkSize + job.leaves.size();
                    double chance;
                    if (isTrunk) {
                        packed = job.trunk.getLong((int) job.decorCursor);
                        host = job.trunkState;
                        chance = job.plantChance;
                    } else if (isLining) {
                        packed = job.hiveLining.getLong((int) (job.decorCursor - job.trunkSize - job.leaves.size()));
                        host = job.trunkState;
                        chance = job.hiveGrassChance;
                    } else {
                        packed = job.leaves.getLong((int) (job.decorCursor - job.trunkSize));
                        host = job.leafState;
                        chance = job.plantChance;
                    }
                    cursorPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                    ChunkEdgeGuard.Edge edge = ChunkEdgeGuard.edge(serverLevel, cursorPos, 1); // 飾りは隣のマスにも生える(③4)
                    if (edge == ChunkEdgeGuard.Edge.WAIT) {
                        stall = true;
                        break;
                    }
                    if (edge == ChunkEdgeGuard.Edge.READY
                            && serverLevel.getBlockState(cursorPos).is(host.getBlock())) {
                        BlockPos hostPos = cursorPos.immutable();
                        if (chance > 0.0D) {
                            CorruptionSpreader.growSurfacePlants(serverLevel, hostPos, host, chance);
                        }
                        if ((isTrunk || isLining) && job.glowLichenChance > 0.0D) {
                            growGlowLichen(serverLevel, hostPos, job.glowLichenChance);
                        }
                        if (!isTrunk && !isLining) {
                            // 葉の真下へ「大きな未発達な果実」を低確率でぶら下げる。
                            // 装飾植物と同じくこのフェーズで回すので、葉はもう全部置き終わっている。
                            UndevelopedFruitPlacement.tryHang(serverLevel, serverLevel.random, hostPos,
                                    OvergrownVegetationConfig.COMMON.giantTreeUndevelopedFruitChance.get());
                        }
                    }
                    job.decorCursor++;
                }
                budget = TickBudget.spend(budget);
            }
            // フェーズ3: フェーズ2まで終えたら、根の足元座標をたどって盛り上がりの頂上に
            // 汚染時と同じ下草(シダ・苔・背高植物)を生やす。根が地面と一体化して苔むして見える。
            while (job.cursor >= job.placeTotal && job.decorDone()
                    && job.hasRootPhase() && job.rootCursor < job.rootTotal && budget > 0) {
                for (int k = 0; k < DECOR_PER_BUDGET && job.rootCursor < job.rootTotal; k++) {
                    long packed = job.rootColumns.getLong((int) job.rootCursor);
                    CorruptionSpreader.growTopUndergrowth(serverLevel,
                            BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed),
                            job.trunkState.getBlock(), job.rootPlantChance);
                    job.rootCursor++;
                }
                budget = TickBudget.spend(budget);
            }
            if (job.done() && !tuftsWaiting(serverLevel, job)) {
                // 側面・底面に生えるもの(2026-11-01)。巨大樹は数を増やし、幹の巻き付きは面の一部の列だけ。
                TreeSideGrowth.decorate(serverLevel, serverLevel.random, new it.unimi.dsi.fastutil.longs.LongOpenHashSet(job.trunk),
                        new it.unimi.dsi.fastutil.longs.LongOpenHashSet(job.leaves), true);
                hangPillarTufts(serverLevel, job);
                spawnHiveNode(serverLevel, job);
                it.remove();
            }
        }
        return Math.max(0, budget);
    }

    /**
     * 原木({@code hostPos})の露出面それぞれに、確率 {@code chance} でヒカリゴケ
     * ({@code minecraft:glow_lichen} = {@link MultifaceBlock})を貼り付ける。既に空気でない面には貼らず、
     * 同じ空気マスに複数方向から貼る場合は面プロパティを追加してまとめる。
     */
    private static void growGlowLichen(ServerLevel level, BlockPos hostPos, double chance) {
        RandomSource random = level.random;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.values()) {
            if (random.nextDouble() >= chance) {
                continue;
            }
            p.setWithOffset(hostPos, dir);
            if (!ChunkEdgeGuard.isLoaded(level, p)) { // 読み込み途中も飛ばす(飾り。11-18 見直し ③4)
                continue;
            }
            BlockState there = level.getBlockState(p);
            // ヒカリゴケは「原木に向いた面」= dir.getOpposite() 側に貼り付く。
            BooleanProperty faceProp = MultifaceBlock.getFaceProperty(dir.getOpposite());
            if (there.isAir()) {
                level.setBlock(p.immutable(),
                        Blocks.GLOW_LICHEN.defaultBlockState().setValue(faceProp, Boolean.TRUE),
                        Block.UPDATE_ALL);
            } else if (there.is(Blocks.GLOW_LICHEN) && !there.getValue(faceProp)) {
                level.setBlock(p.immutable(), there.setValue(faceProp, Boolean.TRUE), Block.UPDATE_ALL);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            JOBS.removeIf(job -> job.level == serverLevel);
        }
    }

    private GiantTreeGrowthQueue() {
    }
}
