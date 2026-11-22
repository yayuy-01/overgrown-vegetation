package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * <b>巨大樹の建て方と建て直し</b>(2026-11-21 前線と部隊 区切り 4。正本は Vault「前線と部隊」の 11-21 の決定と「森の知性(司令塔)」の 11-21 の節)。
 * <ul>
 *   <li>巨大樹の無い過繁茂がチャンク treeClusterChunks(64)ほどのかたまり(treeCellChunks 四方の升で数える)になったら、真ん中あたりに建てる。
 *       巨大樹どうしは、それぞれの場所から決まる半径の和(300〜500 で揺れる。treeSpacingMinBlocks・treeSpacingMaxBlocks。11-22)だけあけ、置く点は升の重心から treeSiteJitterBlocks まで揺らす(水の上は避ける)。苗木の 0.25% のくじは外した(森が決めた所にだけ生える)。</li>
 *   <li>倒された巨大樹(ハイヴノードを倒された)の跡も同じやり方で建て直す。</li>
 *   <li>予算で建てられれば払う。足りなければ足りない分を Mob で補う: 前線の部隊から「建てる」部隊を出して建てる所へ集め、着いたら使う(使った Mob は消える = 体が木になる)。</li>
 *   <li>払い終えたら台帳の上で treeGrowDays 育つ。読み込まれている間は「育ち途中のハイヴノード」が建てる所にいて、<b>それを倒されたときだけ</b>建て直しが止まる。</li>
 *   <li>育ち切って読み込まれたら実物を建てる(新しい所は巨大樹の苗木を育てる・建て直しは育ち途中のノードがそのまま本物のノードになる)。</li>
 *   <li>森の視界の縁(64)は巨大樹に管理された森(巨大樹から treeManageBlocks)だけ({@link #managed})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FrontTrees extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_front_trees";
    public static final SavedData.Factory<FrontTrees> FACTORY = new SavedData.Factory<>(FrontTrees::new, (tag, registries) -> load(tag), null);
    /** 育ち途中のハイヴノードの印(エンティティの保存データ。建てる所の id)。 */
    public static final String SITE_KEY = "ov_tree_site";

    public enum State {
        /** 払っている(予算・Mob)。 */
        FUNDING,
        /** 台帳の上で育っている。 */
        GROWING,
        /** 育ち切った。読み込まれたら実物を建てる。 */
        READY
    }

    /** 建てる所 1 つ。 */
    public static final class Site {
        final long id;
        final int x, z;
        /** 建て直しなら倒された巨大樹の中心(Long.MIN_VALUE = 新しい所)。 */
        final long rebuildOf;
        State state = State.FUNDING;
        double remaining;
        double growStartDay = Double.NaN;
        @Nullable
        UUID node;
        /** 苗木が育たなかった回数(保存しない)。 */
        int failures;

        Site(long id, int x, int z, long rebuildOf, double cost) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.rebuildOf = rebuildOf;
            this.remaining = cost;
        }

        public long id() {
            return id;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public State state() {
            return state;
        }

        public double remaining() {
            return remaining;
        }

        public boolean rebuild() {
            return rebuildOf != Long.MIN_VALUE;
        }

        public UUID group() {
            return new UUID(0x54524545L, id); // "TREE"
        }
    }

    private final List<Site> sites = new ArrayList<>();
    /** 育ち途中のノードを倒された所(升のキー → 休み明けのゲーム時刻)。 */
    private final Long2LongOpenHashMap stopped = new Long2LongOpenHashMap();
    private long nextId = 1L;
    /** 巨大樹に払える額(毎秒 稼ぎの treeIncomeShare ずつ貯まる。上限は巨大樹 1 本)。 */
    private double allowance;

    /** 払う所: 前線(いちばん近い前線)ごとに、いちばん古い FUNDING の所 1 つ。 */
    Set<Long> activeSites(ServerLevel level) {
        Fronts fronts = Fronts.get(level.getServer());
        Map<Long, Site> oldest = new HashMap<>();
        for (Site s : sites) {
            if (s.state != State.FUNDING) continue;
            Fronts.Front f = fronts.nearest(s.x, s.z, -1);
            long key = f != null ? f.id : -1L;
            Site o = oldest.get(key);
            if (o == null || s.id < o.id) oldest.put(key, s);
        }
        Set<Long> ids = new HashSet<>();
        for (Site s : oldest.values()) ids.add(s.id);
        return ids;
    }

    /**
     * 育ち切ったがまだ置かれていない所(読み込まれていない)。巨大樹として数える: 前線・巨大樹の稼ぎ・管理された森(11-21 放置 1 回目)。
     * キーは巨大樹の中心と同じ詰め方(建て直しは倒された巨大樹のキー)。
     */
    public static long[] pendingTreeKeys(ServerLevel level) {
        if (!active(level)) return new long[0];
        List<Site> ss = get(level.getServer()).sites;
        long[] out = new long[ss.size()];
        int n = 0;
        for (Site s : ss) {
            if (s.state != State.READY) continue;
            out[n++] = s.rebuild() ? s.rebuildOf : ((long) s.x << 32) | (s.z & 0xFFFFFFFFL);
        }
        return java.util.Arrays.copyOf(out, n);
    }
    public static long started, rebuilt, builtByBudget, usedHeads, stoppedCount, placed;
    public static double usedValue;
    /** テスト用: 置く(苗木を育てる)代わりに呼ぶ(null で本物)。 */
    @Nullable
    public static java.util.function.Consumer<Site> placeForTests;

    public static FrontTrees get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Site> sites() {
        return sites;
    }

    public static boolean active(ServerLevel level) {
        return FrontDirector.enabled() && DesignData.front().treesByForest
                && (FrontDirector.tickInGameTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer));
    }

    // ---------------------------------------------------------------- 管理された森

    /**
     * 生きている巨大樹の数 = 置かれて倒されていない巨大樹 + 育ち切って置く待ち(読み込まれていない)の巨大樹(11-22。貯蓄の上限・器官の上限のハイヴノード。
     * 置く待ちの巨大樹も樹域になって稼ぐので、上限でも数える。置かれた分と重ならないように数える)。
     */
    public static int livingTreeCount(ServerLevel level) {
        PhaseData pd = PhaseData.get(level);
        int n = pd.livingGiantTreeCount();
        // 11-22 見直し: 置く待ちの巨大樹が、倒された巨大樹と同じ根元に建て直す途中なら数える(生きている根元とだけ比べる)。比べは集合で
        it.unimi.dsi.fastutil.longs.LongOpenHashSet living = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long o : pd.giantTreeOriginSnapshot()) if (!pd.isGiantTreeDead(o)) living.add(o);
        for (long k : pendingTreeKeys(level)) {
            if (!living.contains(k)) n++;
        }
        return n;
    }

    /** (x, z) が生きている巨大樹から treeManageBlocks 以内か(森の視界の縁はここだけ効く)。 */
    public static boolean managed(ServerLevel level, double x, double z) {
        PhaseData pd = PhaseData.get(level);
        double r = DesignData.front().treeManageBlocks;
        for (long k : pd.giantTreeOriginSnapshot()) {
            if (pd.isGiantTreeDead(k)) continue;
            double dx = (int) (k >> 32) + 0.5D - x, dz = (int) k + 0.5D - z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        for (long k : pendingTreeKeys(level)) {
            double dx = (int) (k >> 32) + 0.5D - x, dz = (int) k + 0.5D - z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** 森の視界の縁を、管理された森だけにするか(GameTest のサーバーでは、テストが前線を動かすときだけ)。 */
    public static boolean visionLimited(ServerLevel level) {
        return FrontDirector.enabled() && DesignData.front().treeVisionOnlyManaged
                && (FrontDirector.tickInGameTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer));
    }

    // ---------------------------------------------------------------- まわす

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 11) return;
        ServerLevel level = server.overworld();
        if (!active(level)) return;
        FrontTrees data = get(server);
        if (server.getTickCount() % (int) (DesignData.front().treeCheckSeconds * 20.0D) == 11) data.findSites(level);
        data.tick(level);
    }

    /** 建てる所を探す(テストからも): 倒された巨大樹の跡と、巨大樹の無い過繁茂のかたまり。 */
    public void findSites(ServerLevel level) {
        FrontTable t = DesignData.front();
        PhaseData pd = PhaseData.get(level);
        long now = level.getGameTime();
        long[] trees = pd.giantTreeOriginSnapshot();
        // 建て直し
        for (long k : trees) {
            if (!pd.isGiantTreeDead(k) || siteFor(k) != null) continue;
            int x = (int) (k >> 32), z = (int) k;
            if (stoppedAt(x, z, now)) continue;
            addSite(level, x, z, k);
            rebuilt++;
        }
        // 新しい所: 管理されていない過繁茂のチャンクを升で数える
        int cell = t.treeCellChunks;
        int manageChunks = (int) Math.ceil(t.treeManageBlocks / 16.0D);
        LongOpenHashSet managedChunks = new LongOpenHashSet();
        for (long k : trees) {
            if (pd.isGiantTreeDead(k)) continue;
            int cx = (int) (k >> 32) >> 4, cz = (int) k >> 4;
            for (int dx = -manageChunks; dx <= manageChunks; dx++) {
                for (int dz = -manageChunks; dz <= manageChunks; dz++) {
                    if (dx * dx + dz * dz <= manageChunks * manageChunks) managedChunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
        }
        Long2IntOpenHashMap count = new Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap sumX = new Long2LongOpenHashMap(), sumZ = new Long2LongOpenHashMap();
        pd.forEachOvergrownChunk(k -> {
            if (managedChunks.contains(k)) return;
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            long key = ChunkPos.asLong(Math.floorDiv(cx, cell), Math.floorDiv(cz, cell));
            count.addTo(key, 1);
            sumX.addTo(key, cx);
            sumZ.addTo(key, cz);
        });
        // 置く点(11-22 ユーザー「かなり揺れる」): 升の重心から、升ごとに決まる向き・長さ(treeSiteJitterBlocks まで)ずらした点に近い過繁茂のチャンク。
        // 近い順に SITE_PICKS 個まで覚え、水の上(元のバイオームが海・川)でないものを使う
        long seed = level.getSeed();
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> aim = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (var e : count.long2IntEntrySet()) {
            long key = e.getLongKey();
            int n = e.getIntValue();
            if (n < t.treeClusterChunks) continue;
            double gx = (double) sumX.get(key) / n * 16.0D + 8.0D, gz = (double) sumZ.get(key) / n * 16.0D + 8.0D;
            double a = hash01(seed, key, 1) * Math.PI * 2.0D, len = Math.sqrt(hash01(seed, key, 2)) * t.treeSiteJitterBlocks;
            aim.put(key, new double[]{gx + Math.cos(a) * len, gz + Math.sin(a) * len});
        }
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<long[]> pickKeys = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> pickDist = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        if (!aim.isEmpty()) pd.forEachOvergrownChunk(k -> {
            if (managedChunks.contains(k)) return;
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            long key = ChunkPos.asLong(Math.floorDiv(cx, cell), Math.floorDiv(cz, cell));
            double[] p = aim.get(key);
            if (p == null) return;
            double dx = cx * 16.0D + 8.0D - p[0], dz = cz * 16.0D + 8.0D - p[1], d = dx * dx + dz * dz;
            long[] ks = pickKeys.computeIfAbsent(key, q -> {
                long[] arr = new long[SITE_PICKS];
                java.util.Arrays.fill(arr, Long.MIN_VALUE);
                return arr;
            });
            double[] ds = pickDist.computeIfAbsent(key, q -> {
                double[] arr = new double[SITE_PICKS];
                java.util.Arrays.fill(arr, Double.MAX_VALUE);
                return arr;
            });
            for (int i = 0; i < SITE_PICKS; i++) {
                if (d >= ds[i]) continue;
                for (int j = SITE_PICKS - 1; j > i; j--) {
                    ds[j] = ds[j - 1];
                    ks[j] = ks[j - 1];
                }
                ds[i] = d;
                ks[i] = k;
                break;
            }
        });
        List<Long> keys = new ArrayList<>(aim.keySet());
        keys.sort(Comparator.comparingInt(k -> -count.get((long) k)));
        for (long key : keys) {
            long[] ks = pickKeys.get(key);
            if (ks == null) continue;
            for (long k : ks) {
                if (k == Long.MIN_VALUE) break;
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                // チャンクの中でも揺らす(±6 ブロック。チャンクの外へは出ない = 升も変わらない)
                int bx = cx * 16 + 8 + (int) Math.floor((hash01(seed, k, 3) - 0.5D) * 12.0D);
                int bz = cz * 16 + 8 + (int) Math.floor((hash01(seed, k, 4) - 0.5D) * 12.0D);
                if (onWater(level, bx, bz)) continue;
                if (!tooClose(level, pd, bx, bz) && !stoppedAt(bx, bz, now)) addSite(level, bx, bz, Long.MIN_VALUE);
                break; // 水でない一番近い点だけを見る(近すぎれば、この升は今回は見送り)
            }
        }
    }

    private static final int SITE_PICKS = 4;

    /** 場所から決まる 0〜1(世界の seed 込み。保存・読み込みで変わらない)。 */
    static double hash01(long seed, long key, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + key * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 巨大樹・予定地の (x, z) の半径(treeSpacingMinBlocks/2〜treeSpacingMaxBlocks/2)。2 本は半径の和だけあける。 */
    public static double spacingRadius(long seed, int x, int z) {
        FrontTable t = DesignData.front();
        double lo = t.treeSpacingMinBlocks * 0.5D, hi = t.treeSpacingMaxBlocks * 0.5D;
        return lo + (hi - lo) * hash01(seed, ChunkPos.asLong(x, z), 7);
    }

    /** 元のバイオーム(生成器の値。チャンクを読み込まない)が海・川か。 */
    static boolean onWater(ServerLevel level, int x, int z) {
        try {
            var src = level.getChunkSource();
            var biome = src.getGenerator().getBiomeSource().getNoiseBiome(x >> 2, 64 >> 2, z >> 2, src.randomState().sampler());
            return biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || biome.is(net.minecraft.tags.BiomeTags.IS_RIVER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** (x, z) が、ほかの巨大樹・予定地から半径の和より近いか。 */
    public boolean tooClose(ServerLevel level, PhaseData pd, int x, int z) {
        long seed = level.getSeed();
        double r = spacingRadius(seed, x, z);
        for (long k : pd.giantTreeOriginSnapshot()) {
            int tx = (int) (k >> 32), tz = (int) k;
            double s = r + spacingRadius(seed, tx, tz), dx = tx - x, dz = tz - z;
            if (dx * dx + dz * dz < s * s) return true;
        }
        for (Site st : sites) {
            double s = r + spacingRadius(seed, st.x, st.z), dx = st.x - x, dz = st.z - z;
            if (dx * dx + dz * dz < s * s) return true;
        }
        return false;
    }

    private boolean stoppedAt(int x, int z, long now) {
        long key = cellKey(x, z);
        return stopped.containsKey(key) && stopped.get(key) > now;
    }

    private static long cellKey(int x, int z) {
        int cell = DesignData.front().treeCellChunks * 16;
        return ChunkPos.asLong(Math.floorDiv(x, cell), Math.floorDiv(z, cell));
    }

    @Nullable
    Site siteFor(long rebuildOf) {
        for (Site s : sites) if (s.rebuildOf == rebuildOf) return s;
        return null;
    }

    /** 建てる所を足す。払うのは tick で(毎秒 稼ぎの treeIncomeShare まで・前線ごとに古い 1 か所ずつ)。 */
    public Site addSite(ServerLevel level, int x, int z, long rebuildOf) {
        Site s = new Site(nextId++, x, z, rebuildOf, DesignData.front().treeCost);
        sites.add(s);
        started++;
        setDirty();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "巨大樹を%s(%d, %d)。残り %.0f", rebuildOf != Long.MIN_VALUE ? "建て直す" : "建てる", x, z, s.remaining));
        return s;
    }

    /** 1 回まわす(テストからも)。 */
    public void tick(ServerLevel level) {
        tick(level, 1.0D);
    }

    /** seconds = 前の回からの秒(巨大樹に払える額が貯まる)。 */
    public void tick(ServerLevel level, double seconds) {
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        double day = PhaseData.clockNow(level) / 24000.0D;
        ForestDirector dir = ForestDirector.get(level);
        FrontTable t = DesignData.front();
        // 巨大樹に払える額: 毎秒 稼ぎの treeIncomeShare まで(部隊を作る分を食べ尽くさない。11-21 放置 1 回目)
        allowance = Math.min(t.treeCost, allowance + t.treeIncomeShare * ForestDirector.incomePerSecond(level) * seconds);
        Set<Long> active = activeSites(level);
        for (Site s : new ArrayList<>(sites)) {
            switch (s.state) {
                case FUNDING -> {
                    useArrived(level, hordes, s);
                    if (!active.contains(s.id) && s.remaining > 1.0E-6D) continue; // 前線ごとに古い 1 か所ずつ
                    double pay = Math.min(s.remaining, Math.min(allowance, dir.available(level)));
                    if (pay > 0.0D && dir.tryPay(level, pay)) {
                        s.remaining -= pay;
                        allowance -= pay;
                    }
                    if (s.remaining <= 1.0E-6D) {
                        s.state = State.GROWING;
                        s.growStartDay = day;
                        releaseBuilders(level, hordes, s);
                        builtByBudget++;
                    } else {
                        callBuilders(level, hordes, s);
                    }
                }
                case GROWING -> {
                    ensureGrowingNode(level, s);
                    if (day - s.growStartDay >= DesignData.front().treeGrowDays) s.state = State.READY;
                }
                case READY -> {
                    if (level.isPositionEntityTicking(new BlockPos(s.x, 64, s.z))) place(level, s);
                }
            }
        }
        setDirty();
    }

    /** 足りない分を補う「建てる」部隊を前線の部隊から出す(もう向かっている分の値段を引いた残りだけ)。 */
    void callBuilders(ServerLevel level, VirtualHordes hordes, Site s) {
        double coming = 0.0D;
        for (VirtualHordes.Swarm u : hordes.swarms()) if (u.purpose() == UnitPurpose.BUILD && s.group().equals(u.hunt())) coming += value(u);
        double need = s.remaining - coming;
        if (need <= 0.0D) return;
        Fronts fronts = Fronts.get(level.getServer());
        Long2IntOpenHashMap real = FrontDirector.realByUnit(level);
        Fronts.Front f = fronts.nearest(s.x, s.z, -1);
        if (f == null) return;
        // 出せる頭数は襲撃と同じ(守りの下限を超えた守り + 広げる)
        int can = FrontRaids.raidable(hordes, f, real);
        if (can <= 0) return;
        double each = averagePrice(hordes, f);
        int want = Math.min(can, (int) Math.ceil(need / Math.max(1.0D, each)));
        int max = DesignData.front().unitMax(com.yayuy.overgrownvegetation.director.DirectorGates.phase(level));
        // FrontRaids.pull と同じ抜き方で、目的だけ「建てる」に
        int got = pullBuilders(level, hordes, f, real, want, max, s);
        if (got > 0) setDirty();
    }

    private static double averagePrice(VirtualHordes hordes, Fronts.Front f) {
        double sum = 0.0D;
        int n = 0;
        for (VirtualHordes.Swarm u : hordes.swarms()) {
            if (u.front() != f.id) continue;
            sum += value(u);
            n += u.size();
        }
        return n > 0 ? sum / n : DesignData.director().largeRaid.costStageZero;
    }

    /** 部隊の頭数の値段の合計(定価)。 */
    static double value(VirtualHordes.Swarm u) {
        double v = 0.0D;
        for (Map.Entry<net.minecraft.resources.ResourceLocation, Integer> e : u.counts().entrySet()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(e.getKey());
            v += ForestUpkeep.unitPrice(type, FrontDirector.isG1(type)) * e.getValue();
        }
        return v;
    }

    static int pullBuilders(ServerLevel level, VirtualHordes hordes, Fronts.Front f, Long2IntOpenHashMap real, int want, int max, Site s) {
        FrontTable t = DesignData.front();
        int total = 0, defend = 0;
        List<VirtualHordes.Swarm> src = new ArrayList<>();
        for (VirtualHordes.Swarm u : hordes.swarms()) {
            if (u.front() != f.id) continue;
            int n = u.size() + real.get(u.id());
            total += n;
            if (u.purpose() == UnitPurpose.DEFEND) defend += n;
            if ((u.purpose() == UnitPurpose.DEFEND || u.purpose() == UnitPurpose.EXPAND) && u.size() > 0) src.add(u);
        }
        int floor = Math.max(t.unitMin, (int) Math.ceil(t.defendShareQuiet * total)); // 守りを空にしない(11-21 放置 1 回目で 244 → 0)
        src.sort(Comparator.<VirtualHordes.Swarm>comparingInt(u -> u.purpose() == UnitPurpose.EXPAND ? 0 : 1)
                .thenComparingDouble(u -> (u.x() - s.x) * (u.x() - s.x) + (u.z() - s.z) * (u.z() - s.z)));
        long now = level.getGameTime();
        int got = 0;
        VirtualHordes.Swarm into = null;
        for (VirtualHordes.Swarm u : src) {
            if (got >= want) break;
            int can = u.size();
            if (u.purpose() == UnitPurpose.DEFEND) can = Math.min(can, defend - floor);
            int take = Math.min(can, want - got);
            while (take > 0) {
                if (into == null || into.size() >= max) {
                    into = hordes.newUnit(u.x(), u.y(), u.z(), f.id, UnitPurpose.BUILD, now);
                    into.setHunt(s.group());
                    into.assign(UnitPurpose.BUILD, s.x + 0.5D, s.z + 0.5D, now);
                }
                int k = Math.min(take, max - into.size());
                double paidBefore = u.paid();
                var heads = u.takeHeads(k);
                int n = heads.values().stream().mapToInt(Integer::intValue).sum();
                if (n <= 0) break;
                for (var e : heads.entrySet()) into.addHeads(e.getKey(), e.getValue(), 0.0D);
                into.paidAdd(paidBefore - u.paid());
                if (u.purpose() == UnitPurpose.DEFEND) defend -= n;
                take -= n;
                got += n;
            }
        }
        hordes.touch();
        return got;
    }

    /** 着いた「建てる」部隊を使う(仮想の頭数だけ。実物は戻ってから使う)。 */
    void useArrived(ServerLevel level, VirtualHordes hordes, Site s) {
        for (VirtualHordes.Swarm u : new ArrayList<>(hordes.swarms())) {
            if (u.purpose() != UnitPurpose.BUILD || !s.group().equals(u.hunt()) || !u.arrived() || s.remaining <= 0.0D) continue;
            // 値段の高い順でなく、並びの順に 1 体ずつ使う(使いすぎない)
            while (u.size() > 0 && s.remaining > 1.0E-6D) {
                var one = u.takeHeads(1);
                for (var e : one.entrySet()) {
                    EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(e.getKey());
                    double v = ForestUpkeep.unitPrice(type, FrontDirector.isG1(type));
                    s.remaining -= v;
                    usedValue += v;
                    usedHeads++;
                }
            }
            hordes.touch();
        }
    }

    /** 払い終えた: 残った「建てる」部隊は前線の守りへ戻す。 */
    void releaseBuilders(ServerLevel level, VirtualHordes hordes, Site s) {
        Fronts fronts = Fronts.get(level.getServer());
        for (VirtualHordes.Swarm u : hordes.swarms()) {
            if (u.purpose() != UnitPurpose.BUILD || !s.group().equals(u.hunt())) continue;
            Fronts.Front f = fronts.byId(u.front());
            double[] post = f != null ? FrontDirector.post(level, f, level.getRandom()) : new double[]{u.x(), u.z()};
            u.setHunt(null);
            u.assign(UnitPurpose.DEFEND, post[0], post[1], level.getGameTime());
        }
        hordes.touch();
    }

    /** 読み込まれている間は、建てる所に育ち途中のハイヴノードを置く(倒されたら止まる)。 */
    void ensureGrowingNode(ServerLevel level, Site s) {
        BlockPos at = surface(level, s);
        if (at == null || !level.isPositionEntityTicking(at)) return;
        if (s.node != null && level.getEntity(s.node) != null) return;
        HiveNodeEntity node = ModEntities.HIVE_NODE.get().create(level);
        if (node == null) return;
        node.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        node.getPersistentData().putLong(SITE_KEY, s.id);
        if (level.addFreshEntity(node)) s.node = node.getUUID();
    }

    @Nullable
    static BlockPos surface(ServerLevel level, Site s) {
        // 樹冠の上でなく地面(読み込み途中なら null)。建て直しは倒された巨大樹の幹の横に
        int x = s.x + (s.rebuild() ? 10 : 0);
        if (level.getChunkSource().getChunkNow(x >> 4, s.z >> 4) == null) return null;
        return com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.findSpawnSurface(level, x, s.z);
    }

    /** 育ち切って読み込まれた: 実物を建てる。 */
    void place(ServerLevel level, Site s) {
        PhaseData pd = PhaseData.get(level);
        if (placeForTests != null) {
            placeForTests.accept(s);
        } else if (s.rebuild()) {
            // 建て直し: 育ち途中のノードがそのまま本物のノードになる(巨大樹の形は残っている)
            Entity n = s.node != null ? level.getEntity(s.node) : null;
            if (n == null) {
                ensureGrowingNode(level, s);
                n = s.node != null ? level.getEntity(s.node) : null;
            }
            if (n != null) {
                n.getPersistentData().remove(SITE_KEY);
                pd.recordHiveNode(n.blockPosition());
            }
            pd.reviveGiantTree(s.rebuildOf);
        } else {
            // 新しい所: 育ち途中のノードを外し、巨大樹の苗木を育てる(巨大樹の形と本物のノードは苗木の育ちが作る)
            BlockPos at = surface(level, s);
            if (at == null) return;
            Entity n = s.node != null ? level.getEntity(s.node) : null;
            if (n != null) {
                pd.removeHiveNode(n.blockPosition()); // 育ち途中のノードは自分をハイヴノードとして記録しているので外す(上限を膨らませない)
                n.discard();
                s.node = null;
            }
            BlockState sap = ModBlocks.GIANT_OVERGROWN_SAPLING.get().defaultBlockState();
            level.setBlock(at, sap, 3);
            if (sap.getBlock() instanceof SaplingBlock sb) sb.advanceTree(level, at, sap.setValue(SaplingBlock.STAGE, 1), level.getRandom());
            boolean grew = !level.getBlockState(at).is(sap.getBlock()) || com.yayuy.overgrownvegetation.worldgen.GiantTreePlanner.inFlightAt(level, at);
            if (!grew) {
                // 育たなかった(場所が狭いなど): 3 回まで試し直し、だめなら苗木を消してこの所をやめる
                if (++s.failures < 3) return;
                level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                sites.remove(s);
                setDirty();
                ForestDirector.get(level).note(String.format(Locale.ROOT, "巨大樹が育たなかった(%d, %d)。この所はやめる", s.x, s.z));
                return;
            }
        }
        placed++;
        sites.remove(s);
        setDirty();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "巨大樹を%s(%d, %d)", s.rebuild() ? "建て直した" : "建てた", s.x, s.z));
    }

    /** 育ち途中のハイヴノードが倒された: その建てる所は止まる(しばらくその所には建てない)。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e instanceof HiveNodeEntity) || !(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(SITE_KEY)) return;
        FrontTrees data = get(level.getServer());
        long id = e.getPersistentData().getLong(SITE_KEY);
        for (Site s : new ArrayList<>(data.sites)) {
            if (s.id != id) continue;
            data.stop(level, s);
        }
    }

    /** 止める(倒された・テスト)。 */
    public void stop(ServerLevel level, Site s) {
        sites.remove(s);
        stopped.put(cellKey(s.x, s.z), level.getGameTime() + (long) (DesignData.front().treeStopCooldownDays * 24000.0D));
        releaseBuilders(level, VirtualHordes.get(level.getServer()), s);
        stoppedCount++;
        setDirty();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "育ち途中のハイヴノードが倒され、巨大樹が止まった(%d, %d)", s.x, s.z));
    }

    public static String describe(ServerLevel level) {
        FrontTrees data = get(level.getServer());
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "巨大樹: %s・建てる所 %d・これまで 始めた %d(建て直し %d)・予算で払い終えた %d・使った Mob %d 体(%.0f)・止められた %d・建てた %d",
                active(level) ? "入" : "切", data.sites.size(), started, rebuilt, builtByBudget, usedHeads, usedValue, stoppedCount, placed));
        for (Site s : data.sites) sb.append(String.format(Locale.ROOT, " / #%d (%d, %d) %s 残り %.0f", s.id, s.x, s.z, s.state.name(), s.remaining));
        return sb.toString();
    }

    /** テスト用: 巨大樹に払える額を決める。 */
    public void allowanceForTests(double v) {
        allowance = v;
    }

    /** テスト用: 育ち切ったことにする。 */
    public void readyForTests(Site s) {
        s.state = State.READY;
    }

    public void clearForTests() {
        sites.clear();
        allowance = 0.0D;
        stopped.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site s : sites) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", s.id);
            c.putInt("X", s.x);
            c.putInt("Z", s.z);
            c.putLong("Of", s.rebuildOf);
            c.putString("State", s.state.name());
            c.putDouble("Left", s.remaining);
            c.putDouble("Grow", s.growStartDay);
            if (s.node != null) c.putUUID("Node", s.node);
            list.add(c);
        }
        tag.put("Sites", list);
        ListTag st = new ListTag();
        for (var e : stopped.long2LongEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("K", e.getLongKey());
            c.putLong("U", e.getLongValue());
            st.add(c);
        }
        tag.put("Stopped", st);
        tag.putLong("NextId", nextId);
        tag.putDouble("Allow", allowance);
        return tag;
    }

    public static FrontTrees load(CompoundTag tag) {
        FrontTrees d = new FrontTrees();
        d.nextId = Math.max(1L, tag.getLong("NextId"));
        d.allowance = tag.getDouble("Allow");
        for (Tag t : tag.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Site s = new Site(c.getLong("Id"), c.getInt("X"), c.getInt("Z"), c.getLong("Of"), c.getDouble("Left"));
            try {
                s.state = State.valueOf(c.getString("State"));
            } catch (IllegalArgumentException ex) {
                s.state = State.FUNDING;
            }
            s.growStartDay = c.getDouble("Grow");
            if (c.hasUUID("Node")) s.node = c.getUUID("Node");
            d.sites.add(s);
            d.nextId = Math.max(d.nextId, s.id + 1);
        }
        for (Tag t : tag.getList("Stopped", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.stopped.put(c.getLong("K"), c.getLong("U"));
        }
        return d;
    }
}
