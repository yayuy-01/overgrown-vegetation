package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.TubeTreePlumeBlock;
import com.yayuy.overgrownvegetation.block.TubeTreePlumeBlock.Pose;
import com.yayuy.overgrownvegetation.block.TubeTreeStemBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * <b>管の樹</b>(2026-11-12 ネザー段階 2 の植物 1。正本は Vault「ネザーの環境の作り直し 2」の「植物の仕様」の「1 管の樹」)。
 * 殻を貫いた管で下の溶岩から熱を吸う、ネザーで生まれた種(元ネタはチューブワーム)。
 *
 * <ul>
 *     <li><b>生える所</b>: 段階 2 の殻(冷えた殻・熱の結晶の殻)の上。殻の輪と同じ間隔で、殻になった所から立てる({@link #onHeatTick})。
 *     世界の座標で {@code tubeCell} 角の升ごとに 1 本の候補(升の真ん中)を置き、立つかは座標の決まった乱数 × 群れのまだら({@code tubeGroveScale})
 *     × 熱さ(真下 3 段に溶岩 = 溶岩の上の殻 {@code tubeDensityLava}・そうでない殻 {@code tubeDensityDry})で決める。
 *     座標だけで決まるので、チャンクの境目で群れが切れない。候補の列はそのチャンクが持ち主の列(境目のまだら)だけ。</li>
 *     <li><b>形</b>: 真ん中の管(高さ {@code tubeMiddleMin}〜{@code tubeMiddleMax} = ユーザー決定 4〜9)と、まわりの 8 マスのうち
 *     {@code tubeSidesMin}〜{@code tubeSidesMax} 本の管(3〜8 = ユーザー決定、真ん中より低く)。管の先ごとに羽根 1 つ。1 本は途中で止めずにまとめて立てる。</li>
 *     <li><b>姿</b>: 昼は開く・夜は閉じる・満月の夜は吐く(地上の時計。{@link #desiredPose})。羽根のランダム tick で合わせる。
 *     プレイヤーが水平 {@code tubeRetractRadius} ブロック以内に来ると引っ込み、離れると戻る({@link #onLevelTick})。</li>
 *     <li><b>湖</b>: 段階 4 で足元の殻が湖になると、管を湖の底まで伸ばして水に浸ける({@link #sinkInto}。湖は 1〜2 段なので羽根は水の上)。</li>
 *     <li><b>胞子</b>: 立てた数を台帳に残し({@code tubes})、胞子の場が満月の夜の放出に使う({@code NetherThermal#tubeFactor})。
 *     羽根の粒はクライアントの見た目だけ。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class NetherTubeTrees implements NetherFloraHooks.Flora {

    public static final NetherTubeTrees INSTANCE = new NetherTubeTrees();

    private static final int SALT_CELL = 101, SALT_GROVE = 103, SALT_HEIGHT = 107, SALT_SIDES = 109, SALT_SIDE_H = 113, SALT_PICK = 127;
    /** 管の樹の羽根を見に行く間隔(tick)と、引っ込んだ羽根を戻すか見る間隔。 */
    private static final int SCAN_TICKS = 4, RETURN_TICKS = 20;
    /** 引っ込む高さの範囲(足元から下・上)。 */
    private static final int REACH_DOWN = 3, REACH_UP = 12;

    /** 次に殻の上を見回す時刻(チャンクごと。保存しない)。 */
    private final Long2LongOpenHashMap nextScan = new Long2LongOpenHashMap();
    /** 引っ込んでいる羽根(保存しない。読み込み直しでは羽根のランダム tick が戻す)。 */
    private static final LongOpenHashSet RETRACTED = new LongOpenHashSet();

    private NetherTubeTrees() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        NetherFloraHooks.register(INSTANCE);
        NeoForge.EVENT_BUS.addListener(NetherTubeTrees::onLevelTick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            RETRACTED.clear();
            INSTANCE.nextScan.clear();
        });
    }

    // ==========================================================================================
    // 立てる
    // ==========================================================================================

    @Override
    public boolean onHeatTick(ServerLevel level, LevelChunk chunk, int[] cols, boolean crustDone, int[] budget) {
        long key = chunk.getPos().toLong();
        NetherLedgerData data = NetherLedgerData.get(level);
        if ((data.flora.get(key) & 4) != 0) return true;
        NetherSpreadTable t = DesignData.netherSpread();
        long now = level.getGameTime();
        if (!crustDone && now < nextScan.getOrDefault(key, Long.MIN_VALUE)) return false;
        nextScan.put(key, now + t.floraHeavyTicks);
        int[] cap = {Math.min(t.floraPlaceCap, budget[0])};
        int before = cap[0];
        boolean complete = plant(level, data, t, key, cols, cap);
        budget[0] -= before - cap[0];
        if (!crustDone || !complete) return false;
        data.flora.put(key, (byte) (data.flora.get(key) | 4));
        if (!data.tubes.containsKey(key)) data.tubes.put(key, 0);
        data.setDirty();
        nextScan.remove(key);
        return true;
    }

    /** 持ち主の列の候補を全部見て、立てられる樹を立てる。上限で止まれば false。 */
    static boolean plant(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key, int[] cols, int[] cap) {
        long seed = level.getSeed();
        int half = t.tubeCell / 2;
        int top = NetherSurface.topY(level), bottom = NetherSurface.bottomY(level);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Pose pose = desiredPose(level);
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            if (Math.floorMod(x, t.tubeCell) != half || Math.floorMod(z, t.tubeCell) != half) continue;
            double grove = grove(seed, x, z, t);
            if (grove <= 0) continue;
            double roll = NetherGround.hash01(seed, x, 0, z, SALT_CELL);
            if (roll >= Math.max(t.tubeDensityLava, t.tubeDensityDry) * grove) continue; // どの殻でも立たない(先に軽く外す)
            // 列の殻の床を上から探す(真上が空いている殻。もう立っていれば数えない)
            for (int y = top; y > bottom; y--) {
                BlockState s = level.getBlockState(p.set(x, y, z));
                if (!NetherLava.isCrust(s)) continue;
                BlockState up = level.getBlockState(p.set(x, y + 1, z));
                if (up.is(ModBlocks.TUBE_TREE_STEM.get())) break; // もう立っている
                if (!roomFor(up)) continue;
                double density = (lavaBelow(level, p, x, y, z) ? t.tubeDensityLava : t.tubeDensityDry) * grove;
                if (roll >= density) break;
                int n = tree(level, t, seed, x, y, z, pose, cap);
                if (n < 0) return false; // 上限: 次の呼び出しで続き
                if (n > 0) {
                    data.tubes.addTo(key, 1);
                    data.setDirty();
                }
                break;
            }
        }
        return true;
    }

    /** 群れのまだら(0〜1)。値のノイズを tubeGroveFrom から上で 0〜1 に伸ばす。 */
    static double grove(long seed, int x, int z, NetherSpreadTable t) {
        double fx = x / (double) t.tubeGroveScale, fz = z / (double) t.tubeGroveScale;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = NetherGround.hash01(seed, ix, 0, iz, SALT_GROVE), b = NetherGround.hash01(seed, ix + 1, 0, iz, SALT_GROVE);
        double c = NetherGround.hash01(seed, ix, 0, iz + 1, SALT_GROVE), d = NetherGround.hash01(seed, ix + 1, 0, iz + 1, SALT_GROVE);
        double v = (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
        return Math.max(0.0, Math.min(1.0, (v - t.tubeGroveFrom) / (1 - t.tubeGroveFrom)));
    }

    private static double smooth(double v) {
        return v * v * (3 - 2 * v);
    }

    /** 真下 3 段のどこかが溶岩(溶岩の上の殻)。 */
    static boolean lavaBelow(ServerLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        for (int k = 1; k <= 3; k++) {
            if (NetherLava.isLava(level.getBlockState(p.set(x, y - k, z)))) return true;
        }
        return false;
    }

    /** 管を立てる場所として空いているか(空気だけ。浮き草の上には立てない = 浮き草は段階 4 で水面へ浮く)。 */
    private static boolean roomFor(BlockState s) {
        return s.isAir();
    }

    /** 列 (x, z) の床 y の上に、羽根を含めて何段空いているか({@code max} まで)。 */
    private static int headroom(ServerLevel level, BlockPos.MutableBlockPos p, int x, int y, int z, int max) {
        int h = 0;
        while (h < max && roomFor(level.getBlockState(p.set(x, y + 1 + h, z)))) h++;
        return h;
    }

    /**
     * 床 (x, y, z) の上に 1 本立てる。置いた数を返す(0 = 立てられない・-1 = 上限に足りない)。
     * まわりの管は同じ高さの殻の床だけ(段差のある所には立たない)。
     */
    static int tree(ServerLevel level, NetherSpreadTable t, long seed, int x, int y, int z, Pose pose, int[] cap) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int span = t.tubeMiddleMax - t.tubeMiddleMin + 1;
        int middle = t.tubeMiddleMin + (int) (NetherGround.hash01(seed, x, y, z, SALT_HEIGHT) * span);
        middle = Math.min(middle, headroom(level, p, x, y, z, t.tubeMiddleMax + 1) - 1);
        if (middle < t.tubeMiddleMin) return 0;
        // まわりの管: 8 マスを決まった乱数の順に並べて、立てられる所から本数ぶん
        int want = t.tubeSidesMin + (int) (NetherGround.hash01(seed, x, y, z, SALT_SIDES) * (t.tubeSidesMax - t.tubeSidesMin + 1));
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        double[] order = new double[8];
        for (int i = 0; i < 8; i++) order[i] = NetherGround.hash01(seed, x + around[i][0], y, z + around[i][1], SALT_PICK);
        int[] sideX = new int[8], sideZ = new int[8], sideH = new int[8];
        int sides = 0;
        boolean[] used = new boolean[8];
        for (int k = 0; k < 8 && sides < want; k++) {
            int best = -1;
            for (int i = 0; i < 8; i++) if (!used[i] && (best < 0 || order[i] < order[best])) best = i;
            used[best] = true;
            int sx = x + around[best][0], sz = z + around[best][1];
            if (!NetherLava.isCrust(level.getBlockState(p.set(sx, y, sz)))) continue;
            int sspan = t.tubeSideMax - t.tubeSideMin + 1;
            int h = t.tubeSideMin + (int) (NetherGround.hash01(seed, sx, y, sz, SALT_SIDE_H) * sspan);
            h = Math.min(h, Math.max(t.tubeSideMin, middle - 1)); // 真ん中より低く
            h = Math.min(h, headroom(level, p, sx, y, sz, h + 1) - 1);
            if (h < t.tubeSideMin) continue;
            sideX[sides] = sx;
            sideZ[sides] = sz;
            sideH[sides] = h;
            sides++;
        }
        int blocks = middle + 1;
        for (int i = 0; i < sides; i++) blocks += sideH[i] + 1;
        if (blocks > cap[0]) return -1;
        column(level, p, x, y, z, middle, pose);
        for (int i = 0; i < sides; i++) column(level, p, sideX[i], y, sideZ[i], sideH[i], pose);
        cap[0] -= blocks;
        NetherFloraHooks.blocks += blocks;
        return blocks;
    }

    /** 床 y の上に高さ h の管と羽根 1 つ。 */
    private static void column(ServerLevel level, BlockPos.MutableBlockPos p, int x, int y, int z, int h, Pose pose) {
        BlockState stem = ModBlocks.TUBE_TREE_STEM.get().defaultBlockState();
        for (int k = 1; k <= h; k++) {
            NetherGround.put(level, p.set(x, y + k, z), stem.setValue(TubeTreeStemBlock.BASE, k == 1));
        }
        NetherGround.put(level, p.set(x, y + h + 1, z), ModBlocks.TUBE_TREE_PLUME.get().defaultBlockState().setValue(TubeTreePlumeBlock.POSE, pose));
    }

    // ==========================================================================================
    // 湖(段階 4)
    // ==========================================================================================

    /**
     * 湖になる列 (x, z) の上(高さ s + 1)に管が立っていれば、管を湖の底(bottom + 1)まで伸ばして水に浸ける。
     * 根元の段(継ぎ目が光る)は湖の底へ移る。置いた数を返す(管が無ければ 0。呼ぶ側はその列に水を置かない)。
     */
    static int sinkInto(ServerLevel level, int x, int z, int s, int bottom) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, s + 1, z);
        BlockState above = level.getBlockState(p);
        if (!above.is(ModBlocks.TUBE_TREE_STEM.get())) return 0;
        NetherGround.put(level, p, above.setValue(TubeTreeStemBlock.BASE, false));
        BlockState stem = ModBlocks.TUBE_TREE_STEM.get().defaultBlockState().setValue(TubeTreeStemBlock.WATERLOGGED, true);
        int placed = 1;
        for (int y = bottom + 1; y <= s; y++) {
            NetherGround.put(level, p.set(x, y, z), stem.setValue(TubeTreeStemBlock.BASE, y == bottom + 1));
            placed++;
        }
        return placed;
    }

    static boolean isStem(BlockState s) {
        return s.is(ModBlocks.TUBE_TREE_STEM.get());
    }

    // ==========================================================================================
    // 姿(昼夜・満月・引っ込み)
    // ==========================================================================================

    /** 今の時刻(地上の時計。胞子の場と同じ)の姿: 昼は開く・夜は閉じる・満月の夜は吐く。 */
    public static Pose desiredPose(ServerLevel level) {
        long dayTime = level.getServer().overworld().getDayTime();
        long tod = Math.floorMod(dayTime, 24000L);
        boolean night = tod >= 13000 && tod < 23000;
        if (!night) return Pose.OPEN;
        return SporeField.fullMoon(dayTime) ? Pose.SPITTING : Pose.CLOSED;
    }

    /** 羽根を今の姿に合わせる(ランダム tick)。プレイヤーが近ければ引っ込む。 */
    public static void settle(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(ModBlocks.TUBE_TREE_PLUME.get())) return;
        int r = DesignData.netherSpread().tubeRetractRadius;
        Pose want = r > 0 && playerNear(level, pos, r + 1) ? Pose.RETRACTED : desiredPose(level);
        if (want == Pose.RETRACTED) RETRACTED.add(pos.asLong());
        if (state.getValue(TubeTreePlumeBlock.POSE) != want) {
            level.setBlock(pos, state.setValue(TubeTreePlumeBlock.POSE, want), Block.UPDATE_CLIENTS);
        }
    }

    private static boolean playerNear(ServerLevel level, BlockPos pos, double r) {
        for (ServerPlayer pl : level.players()) {
            if (pl.isSpectator() || !pl.isAlive()) continue;
            if (near(pl.getX(), pl.getY(), pl.getZ(), pos, r)) return true;
        }
        return false;
    }

    /** 水平の距離が r 以内で、足元から下 REACH_DOWN・上 REACH_UP の中。 */
    private static boolean near(double px, double py, double pz, BlockPos pos, double r) {
        double dx = pos.getX() + 0.5 - px, dz = pos.getZ() + 0.5 - pz;
        double dy = pos.getY() - Math.floor(py);
        return dx * dx + dz * dz <= r * r && dy >= -REACH_DOWN && dy <= REACH_UP;
    }

    /** プレイヤーのまわりの羽根を引っ込め、離れた羽根を戻す。 */
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !NetherLedger.active(level)) return; // 実験的要素がオフなら引っ込みも止める(羽根は残る)
        long now = level.getGameTime();
        int r = DesignData.netherSpread().tubeRetractRadius;
        if (r > 0 && now % SCAN_TICKS == 0) {
            NetherLedgerData data = NetherLedgerData.get(level);
            for (ServerPlayer pl : level.players()) {
                if (pl.isSpectator() || !pl.isAlive()) continue;
                if (!anyReached(data, pl.getBlockX(), pl.getBlockZ(), r)) continue;
                retractAround(level, pl.getX(), pl.getY(), pl.getZ(), r);
            }
        }
        if (now % RETURN_TICKS == 0) returnAway(level, r);
    }

    /** (px, py, pz) のまわり(水平 r)の羽根を引っ込める。引っ込めた数を返す。 */
    public static int retractAround(ServerLevel level, double px, double py, double pz, int r) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        int n = 0;
        for (int x = bx - r; x <= bx + r; x++) {
            for (int z = bz - r; z <= bz + r; z++) {
                for (int y = by - REACH_DOWN; y <= by + REACH_UP; y++) {
                    p.set(x, y, z);
                    if (!near(px, py, pz, p, r)) continue;
                    BlockState s = level.getBlockState(p);
                    if (!s.is(ModBlocks.TUBE_TREE_PLUME.get()) || s.getValue(TubeTreePlumeBlock.POSE) == Pose.RETRACTED) continue;
                    level.setBlock(p, s.setValue(TubeTreePlumeBlock.POSE, Pose.RETRACTED), Block.UPDATE_CLIENTS);
                    RETRACTED.add(p.asLong());
                    n++;
                }
            }
        }
        return n;
    }

    /** 引っ込んだ羽根のうち、プレイヤーが少し(r + 1)離れたものを今の姿へ戻す。 */
    public static void returnAway(ServerLevel level, int r) {
        if (RETRACTED.isEmpty()) return;
        {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (LongIterator it = RETRACTED.iterator(); it.hasNext(); ) {
                long l = it.nextLong();
                p.set(l);
                if (!level.isLoaded(p)) {
                    it.remove();
                    continue;
                }
                BlockState s = level.getBlockState(p);
                if (!s.is(ModBlocks.TUBE_TREE_PLUME.get())) {
                    it.remove();
                    continue;
                }
                if (r > 0 && playerNear(level, p, r + 1)) continue; // 少し離れるまで戻らない
                it.remove();
                Pose want = desiredPose(level);
                if (s.getValue(TubeTreePlumeBlock.POSE) != want) {
                    level.setBlock(p, s.setValue(TubeTreePlumeBlock.POSE, want), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean anyReached(NetherLedgerData data, int x, int z, int r) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (data.isReached(ChunkPos.asLong((x + dx * r) >> 4, (z + dz * r) >> 4))) return true;
            }
        }
        return false;
    }

    // ==========================================================================================
    // テスト用
    // ==========================================================================================

    /** テスト用: そのチャンクの殻の上に、今ある殻のぶん全部立てる(上限なし)。立てた樹の数を返す。 */
    public static int plantForTests(ServerLevel level, long key) {
        NetherLedgerData data = NetherLedgerData.get(level);
        int before = data.tubes.get(key);
        int[] cols = NetherJitter.ownedColumns(level.getSeed(), new ChunkPos(key), DesignData.netherSpread().edgeJitterBlocks,
                DesignData.netherSpread().edgeJitterScale);
        plant(level, data, DesignData.netherSpread(), key, cols, new int[]{Integer.MAX_VALUE});
        return data.tubes.get(key) - before;
    }

    /** テスト用: 群れのまだら(0〜1)。 */
    public static double groveForTests(ServerLevel level, int x, int z) {
        return grove(level.getSeed(), x, z, DesignData.netherSpread());
    }

    /** テスト用: 引っ込みの覚えを捨てる。 */
    public static void clearForTests() {
        RETRACTED.clear();
        INSTANCE.nextScan.clear();
    }
}
