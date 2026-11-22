package com.yayuy.overgrownvegetation.front;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FrontTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestUpkeep;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>前線の割り振り役</b>(2026-11-21 前線と部隊 区切り 2。正本は Vault「前線と部隊(地図の上の森の軍勢)(11-21)」)。1 秒ごとに:
 * <ol>
 *   <li>前線を巨大樹の記録に合わせる(巨大樹ごとに 1 つ・無ければ汚染の発生源 1 つ)。倒された前線の部隊は近くの前線へ合流する。</li>
 *   <li>前線ごとに Mob を作り、作る途中の部隊(前線の真ん中の守る部隊)へ足す。払うのは定価、維持費の 8 割の関所を通る分だけ。
 *       予算のうち 1 日の稼ぎ × reserveIncomeDays(半日分)を残し、樹域の頭数が目標(過繁茂のチャンク数 × unitTargetPerChunk)に届いたら作らずに貯める(11-22)。</li>
 *   <li>割り振り: 下限に届いた部隊を、守りの持ち場(前線の森の中)か広げる升へ出す。攻められた前線は広げる部隊を守りへ呼び戻す。</li>
 *   <li>広げる部隊: 升に着いたら {@link CellLife} で感染を進め、稼ぎを入れ、感染した生き物を G1 として部隊に足す。升が森になったら隣の升へ。</li>
 * </ol>
 * 狩る・襲うは区切り 3、建てる(巨大樹)は区切り 4。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FrontDirector {

    /** テストで世界の tick から動かす(ふだんの GameTest のサーバーでは動かさない)。 */
    public static boolean tickInGameTests;
    /** 確かめ用の数(保存しない)。 */
    public static long produced, producedPlant, refusedByGate, sentDefend, sentExpand, recalled, merged, joined, expandInfected;
    public static double expandGain;
    /** 11-21 夜 確かめ用: 広げる部隊を出した距離(ブロック)の合計・最大、着いた数と着くまでの日数の合計・最大。 */
    public static double expandDistSum, expandDistMax, expandArriveDaysSum, expandArriveDaysMax;
    public static long expandSends, expandArrivals;
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap EXPAND_LEG_START = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

    static void noteExpandSend(VirtualHordes.Swarm s, double tx, double tz, long now) {
        if (EXPAND_LEG_START.size() > 4096) EXPAND_LEG_START.clear();
        EXPAND_LEG_START.put(s.id(), now);
        double d = Math.sqrt((s.x() - tx) * (s.x() - tx) + (s.z() - tz) * (s.z() - tz));
        expandDistSum += d;
        expandDistMax = Math.max(expandDistMax, d);
        expandSends++;
    }

    static void noteExpandArrival(VirtualHordes.Swarm s, long now) {
        if (!EXPAND_LEG_START.containsKey(s.id())) return;
        double days = (now - EXPAND_LEG_START.remove(s.id())) / 24000.0D;
        expandArriveDaysSum += days;
        expandArriveDaysMax = Math.max(expandArriveDaysMax, days);
        expandArrivals++;
    }

    private FrontDirector() {
    }

    static {
        VirtualHordes.STATIONARY = FrontDirector::isRootedForm;
    }

    @Nullable
    private static java.util.Set<ResourceLocation> rootedIds;

    /** 根付きの姿(動けない)か。初めて聞かれたときに G1 の根付きの表から作る。 */
    public static boolean isRootedForm(ResourceLocation id) {
        java.util.Set<ResourceLocation> s = rootedIds;
        if (s == null) {
            s = new java.util.HashSet<>();
            for (G1Species g : G1Species.values()) {
                EntityType<?> r = com.yayuy.overgrownvegetation.director.G1Fate.rootedTypeOf(g);
                if (r != null) s.add(BuiltInRegistries.ENTITY_TYPE.getKey(r));
            }
            rootedIds = s;
        }
        return s.contains(id);
    }

    public static boolean enabled() {
        return DesignData.front().enabled && DesignData.director().enabled && VirtualHordes.enabled();
    }

    /**
     * 縁・地下・森の中の湧きと、実物の G1 の進化・根付き・苗床を止めて前線の部隊に任せるか(11-21 区切り 6)。GameTest のサーバーでは、テストが前線を動かすときだけ。
     */
    public static boolean replacesOldSpawns(ServerLevel level) {
        return enabled() && DesignData.front().replaceOldSpawns
                && (tickInGameTests || !(level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 7) return;
        if (!tickInGameTests && server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        if (!enabled()) return;
        run(server.overworld(), 1.0D);
    }

    /** 1 回まわす(テストからも)。seconds = 前の回からの秒。 */
    public static void run(ServerLevel level, double seconds) {
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        sync(level, fronts, hordes);
        if (fronts.fronts().isEmpty()) return;
        Long2IntOpenHashMap real = realByUnit(level);
        long now = level.getGameTime();
        FrontTable t = DesignData.front();
        countHeads(hordes, real);
        if (territoryAt == Long.MIN_VALUE || now - territoryAt >= TERRITORY_INTERVAL) {
            countTerritory(level, fronts);
            repostEdges(level, fronts, hordes, real); // 縁が進んで森の中になった縁の持ち場を、今の縁へ置き直す
            returnReinforcements(level, fronts, hordes, now); // 駆けつけた予備を、攻められなくなったら戻す
            FrontOutposts outposts = FrontOutposts.get(level.getServer());
            outposts.takeRootedFromUnits(level, hordes); // 前の決まりで部隊の中に根付いた頭数を前哨へ
            outposts.witherSwallowed(level); // 森に飲み込まれた前哨は枯れて返す
            rebalance(level, fronts, hordes, real); // 11-22 ユーザー決定: 目標を超えた樹域の守りを、足りない樹域へ移す
        }
        for (Fronts.Front f : new ArrayList<>(fronts.fronts())) {
            decayLosses(level, f, t);
            produce(level, fronts, hordes, f, seconds);
            // 前線ごとに見直す秒をずらす(全部の前線が同じ tick に重ならないように)。初回は前線の id で散らす
            long interval = (long) (t.assignIntervalSeconds * 20.0D);
            if (f.assignedAt == Long.MIN_VALUE) f.assignedAt = now - Math.floorMod(f.id * 7919L, Math.max(1L, interval / 20L)) * 20L;
            if (now - f.assignedAt >= interval) {
                f.assignedAt = now;
                assign(level, hordes, f, real);
            }
        }
        expand(level, hordes, seconds);
        FrontNurseries.get(level.getServer()).tick(level, hordes);
        FrontWatchers.get(level.getServer()).tick(level);
        FrontOutposts.get(level.getServer()).tick(level);
        if (replacesOldSpawns(level) && level.getGameTime() % 200L < 20L) absorbLoose(level, fronts, hordes);
        // 空の部隊(実物も残っていない)を片付ける
        for (VirtualHordes.Swarm s : new ArrayList<>(hordes.swarms())) {
            if (s.unit() && s.size() <= 0 && real.get(s.id()) <= 0) hordes.removeUnit(s);
        }
    }

    // ---------------------------------------------------------------- 前線を巨大樹に合わせる

    public static void sync(ServerLevel level, Fronts fronts, VirtualHordes hordes) {
        PhaseData pd = PhaseData.get(level);
        long[] trees = pd.giantTreeOriginSnapshot();
        // 育ち切って置く待ち(読み込まれていない)の巨大樹も前線にする(11-21 放置 1 回目)
        long[] pending = FrontTrees.pendingTreeKeys(level);
        boolean anyTree = false;
        for (long key : trees) {
            if (pd.isGiantTreeDead(key) && !contains(pending, key)) continue;
            anyTree = true;
            if (fronts.byTree(key) == null) fronts.add(Fronts.Kind.TREE, key, (int) (key >> 32) + 0.5D, (int) key + 0.5D);
        }
        for (long key : pending) {
            anyTree = true;
            if (fronts.byTree(key) == null) fronts.add(Fronts.Kind.TREE, key, (int) (key >> 32) + 0.5D, (int) key + 0.5D);
        }
        // 倒された(ハイヴノードを倒された・記録から消えた)巨大樹の前線: 部隊は近くの前線へ合流
        for (Fronts.Front f : new ArrayList<>(fronts.fronts())) {
            boolean alive = contains(pending, f.treeKey) || (contains(trees, f.treeKey) && !pd.isGiantTreeDead(f.treeKey));
            boolean gone = f.kind == Fronts.Kind.TREE && !alive;
            boolean handOver = f.kind == Fronts.Kind.SOURCE && anyTree; // 最初の巨大樹ができたら発生源の前線は引き継ぐ
            if (!gone && !handOver) continue;
            fronts.remove(f);
            Fronts.Front to = fronts.nearest(f.x, f.z, f.id);
            for (VirtualHordes.Swarm s : hordes.swarms()) {
                if (s.front() != f.id) continue;
                if (to == null) {
                    s.setFront(-1);
                    s.assign(UnitPurpose.MARCH, s.x(), s.z(), level.getGameTime());
                    continue;
                }
                s.setFront(to.id);
                if (s.purpose() == UnitPurpose.DEFEND) s.assign(UnitPurpose.DEFEND, to.x, to.z, level.getGameTime());
                joined++;
            }
            hordes.touch();
        }
        // 巨大樹がまだ無い: 汚染の発生源を最初の前線に(いちばん早く過繁茂になったチャンク)
        if (!anyTree && fronts.source() == null && pd.overgrownLedgerSize() > 0) {
            long[] best = {Long.MIN_VALUE, Long.MAX_VALUE};
            pd.forEachOvergrownChunk(k -> {
                long since = pd.overgrownSinceTick(new ChunkPos(k));
                if (since < best[1] || best[0] == Long.MIN_VALUE) {
                    best[0] = k;
                    best[1] = since;
                }
            });
            if (best[0] != Long.MIN_VALUE) {
                ChunkPos c = new ChunkPos(best[0]);
                fronts.add(Fronts.Kind.SOURCE, Long.MIN_VALUE, c.getMiddleBlockX() + 0.5D, c.getMiddleBlockZ() + 0.5D);
            }
        }
    }

    private static boolean contains(long[] a, long v) {
        for (long x : a) if (x == v) return true;
        return false;
    }

    // ---------------------------------------------------------------- 攻められた度合い

    static void decayLosses(ServerLevel level, Fronts.Front f, FrontTable t) {
        double day = level.getGameTime() / 24000.0D;
        if (f.losses > 0.0D && day > f.lossesAt) f.losses *= Math.pow(0.5D, (day - f.lossesAt) / t.attackDecayDays);
        f.lossesAt = day;
    }

    /** 前線の部隊の実物が倒された: その前線の「最近倒された値段」に足す。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!e.getPersistentData().contains(VirtualHordes.UNIT_KEY)) return;
        int fid = e.getPersistentData().getInt(VirtualHordes.FRONT_KEY);
        // 11-22 見直し: 駆けつけた予備が倒されたら、駆けつけた先の樹域が攻められたと数える(元の内側の樹域ではなく)
        int[] r = REINFORCING.get(e.getPersistentData().getLong(VirtualHordes.UNIT_KEY));
        if (r != null) fid = r[1];
        Fronts.Front f = Fronts.get(level.getServer()).byId(fid);
        if (f == null) return;
        decayLosses(level, f, DesignData.front());
        f.losses += Math.max(1.0D, ForestUpkeep.unitPrice(e));
        Fronts.get(level.getServer()).touch();
    }

    /** 前線が攻められているか(最近倒された値段が前線の値段の attackedLossShare を超えた)。 */
    public static boolean attacked(Fronts.Front f, double frontValue) {
        return f.losses > DesignData.front().attackedLossShare * Math.max(1.0D, frontValue);
    }

    // ---------------------------------------------------------------- 作る

    public static void produce(ServerLevel level, Fronts fronts, VirtualHordes hordes, Fronts.Front f, double seconds) {
        FrontTable t = DesignData.front();
        f.produceCarry = Math.min(t.producePerFrontPerSecond * 4.0D + 1.0D, f.produceCarry + t.producePerFrontPerSecond * seconds);
        int n = (int) Math.floor(f.produceCarry);
        if (n <= 0) return;
        // 目標の頭数に届いた樹域は作らずに貯める(11-22 ユーザー決定。倒されて減ったらまた作る)
        int room = target(f.id) - heads(f.id);
        if (room <= 0) {
            atTargetSkips++;
            return;
        }
        // 11-22 ユーザー決定: 全体の頭数が目標の合計の unitGlobalCapShare 倍を超えている間は、どの樹域も作らない
        if (overGlobalCap(fronts)) {
            globalCapSkips++;
            return;
        }
        n = Math.min(n, room);
        ForestDirector dir = ForestDirector.get(level);
        double reserve = reserve(level);
        int phase = DirectorGates.phase(level);
        int max = t.unitMax(phase);
        RandomSource random = level.getRandom();
        VirtualHordes.Swarm forming = null;
        for (int i = 0; i < n; i++) {
            EntityType<?> type = pickType(level, random, phase);
            if (type == null) break;
            boolean g1 = isG1(type);
            double price = ForestUpkeep.unitPrice(type, g1);
            if (price <= 0.0D) break;
            if (dir.available(level) - price < reserve) break;
            if (!dir.tryPayUnit(level, price, price, true)) {
                refusedByGate++;
                f.produceCarry = Math.min(f.produceCarry, 1.0D);
                break;
            }
            if (forming == null || forming.size() >= max) forming = formingUnit(level, hordes, f, max);
            forming.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(type), 1, price);
            f.produceCarry -= 1.0D;
            HEADS.addTo(f.id, 1);
            produced++;
            if (!g1) producedPlant++;
        }
        hordes.touch();
        fronts.touch();
    }

    // ---------------------------------------------------------------- 取り置きと目標の頭数(11-22 ユーザー決定)

    /** 作る・苗床の補充・進化・根付きで残す予算 = 1 日の稼ぎの見積もり × reserveIncomeDays(半日分)。ただし上限 × reserveCapMaxShare を超えない。 */
    public static double reserve(ServerLevel level) {
        FrontTable t = DesignData.front();
        double byIncome = ForestDirector.incomePerSecond(level) * com.yayuy.overgrownvegetation.data.EconomyTable.SECONDS_PER_DAY * t.reserveIncomeDays;
        return Math.min(byIncome, ForestDirector.cap(level) * t.reserveCapMaxShare); // 11-22: 上限が低いときの歯止め(上限 × reserveCapMaxShare まで)
    }

    /** 樹域ごとの過繁茂のチャンク数(いちばん近い樹域に数える)・守る + 広げるの頭数。run が数え直す。 */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap TERRITORY = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap HEADS = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    private static final long TERRITORY_INTERVAL = 1200L;
    private static long territoryAt = Long.MIN_VALUE;
    public static long atTargetSkips, nurseryAtTargetSkips;

    /** 樹域の目標の頭数 = 過繁茂のチャンク数 × unitTargetPerChunk(下限 unitTargetMin)。 */
    public static int target(int front) {
        FrontTable t = DesignData.front();
        return Math.max(t.unitTargetMin, (int) Math.ceil(TERRITORY.get(front) * t.unitTargetPerChunk));
    }

    /** 樹域の今の頭数(守る + 広げる。襲撃・狩り・建てるに出た分は数えない)。 */
    public static int heads(int front) {
        return HEADS.get(front);
    }

    static void addHead(int front) {
        HEADS.addTo(front, 1);
    }

    public static int territoryChunks(int front) {
        return TERRITORY.get(front);
    }

    /** 目標に届いた樹域か(苗床の補充もやめる)。 */
    public static boolean atTarget(int front) {
        return heads(front) >= target(front);
    }

    public static long globalCapSkips, nurseryGlobalCapSkips, routedInfected;

    /** 全体の頭数(全部の樹域の守る + 広げる)/ 目標の合計(目標が無ければ 0)。 */
    public static double globalRatio(Fronts fronts) {
        long h = 0, t = 0;
        for (Fronts.Front f : fronts.fronts()) {
            h += heads(f.id);
            t += target(f.id);
        }
        return t > 0 ? h / (double) t : 0.0D;
    }

    /** 11-22 ユーザー決定: 全体の頭数が目標の合計の unitGlobalCapShare【仮 1.3】倍を超えているか(超えている間は作らない・苗床も補充しない)。 */
    public static boolean overGlobalCap(Fronts fronts) {
        double share = DesignData.front().unitGlobalCapShare;
        return share > 0.0D && globalRatio(fronts) > share;
    }

    static void countHeads(VirtualHordes hordes, Long2IntOpenHashMap real) {
        HEADS.clear();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() < 0 || (s.purpose() != UnitPurpose.DEFEND && s.purpose() != UnitPurpose.EXPAND)) continue;
            HEADS.addTo(s.front(), s.size() + real.get(s.id()));
        }
    }

    /** 過繁茂の台帳の各チャンクを、いちばん近い樹域に数える。 */
    public static void countTerritory(ServerLevel level, Fronts fronts) {
        territoryAt = level.getGameTime();
        TERRITORY.clear();
        List<Fronts.Front> fs = fronts.fronts();
        int n = fs.size();
        if (n == 0) return;
        double[] xs = new double[n], zs = new double[n];
        int[] ids = new int[n], counts = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = fs.get(i).x;
            zs[i] = fs.get(i).z;
            ids[i] = fs.get(i).id;
        }
        EDGES.clear();
        EDGE_ALL.clear();
        PhaseData pd = PhaseData.get(level);
        it.unimi.dsi.fastutil.longs.LongArrayList[] edges = new it.unimi.dsi.fastutil.longs.LongArrayList[n];
        // 11-22 見直し: いちばん近い樹域は升の索引で引く(チャンク × 樹域の総当たりをやめた。結果は総当たりと同じ)
        NearestIndex index = new NearestIndex(xs, zs);
        pd.forEachOvergrownChunk(k -> {
            int ccx = ChunkPos.getX(k), ccz = ChunkPos.getZ(k);
            int best = index.nearest(ccx * 16.0D + 8.0D, ccz * 16.0D + 8.0D);
            counts[best]++;
            // 縁 = 4 方向のどれかが森でない過繁茂のチャンク(11-22 守りの持ち場)
            if (!pd.isOvergrownKey(ChunkPos.asLong(ccx + 1, ccz)) || !pd.isOvergrownKey(ChunkPos.asLong(ccx - 1, ccz))
                    || !pd.isOvergrownKey(ChunkPos.asLong(ccx, ccz + 1)) || !pd.isOvergrownKey(ChunkPos.asLong(ccx, ccz - 1))) {
                if (edges[best] == null) edges[best] = new it.unimi.dsi.fastutil.longs.LongArrayList();
                edges[best].add(k);
                EDGE_ALL.put(k, ids[best]);
            }
        });
        for (int i = 0; i < n; i++) {
            TERRITORY.put(ids[i], counts[i]);
            if (counts[i] > PEAK.get(ids[i])) PEAK.put(ids[i], counts[i]);
            if (edges[i] != null) EDGES.put(ids[i], edges[i]);
        }
    }

    /**
     * いちばん近い点を NearestIndex.CELL ブロックの升に分けて引く索引(11-22 見直し)。升を内から輪に広げ、見つけた点より近い点が
     * 残りの輪に居ないと分かったら止める。同じ距離なら一覧の前の方(総当たりの {@code d < bd} と同じ)。
     */
    public static final class NearestIndex {
        static final int CELL = 512;
        private final double[] xs, zs;
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> grid = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        public NearestIndex(double[] xs, double[] zs) {
            this.xs = xs;
            this.zs = zs;
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList> lists = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            for (int i = 0; i < xs.length; i++) {
                int gx = Math.floorDiv(Mth.floor(xs[i]), CELL), gz = Math.floorDiv(Mth.floor(zs[i]), CELL);
                minX = Math.min(minX, gx);
                maxX = Math.max(maxX, gx);
                minZ = Math.min(minZ, gz);
                maxZ = Math.max(maxZ, gz);
                lists.computeIfAbsent(ChunkPos.asLong(gx, gz), k -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
            }
            for (var e : lists.long2ObjectEntrySet()) grid.put(e.getLongKey(), e.getValue().toIntArray());
        }

        /** (x, z) にいちばん近い点の番号(点が無ければ 0)。 */
        public int nearest(double x, double z) {
            if (xs.length == 0) return 0;
            int gx = Math.floorDiv(Mth.floor(x), CELL), gz = Math.floorDiv(Mth.floor(z), CELL);
            int maxR = Math.max(Math.max(Math.abs(gx - minX), Math.abs(gx - maxX)), Math.max(Math.abs(gz - minZ), Math.abs(gz - maxZ)));
            // 点の升の囲みより内側の輪は空なので飛ばし、輪も囲みの中だけ見る(11-23: 遠く離れた所を引くと輪を何万も回っていた)
            int rMin = Math.max(0, Math.max(Math.max(minX - gx, gx - maxX), Math.max(minZ - gz, gz - maxZ)));
            // 囲みの外から引くときは輪がいくつも要るので、総当たりの方が速い(同じ答え)
            if (rMin > 0) return brute(xs, zs, x, z);
            qBest = -1;
            qBd = Double.MAX_VALUE;
            for (int r = rMin; r <= maxR; r++) {
                int x0 = Math.max(-r, minX - gx), x1 = Math.min(r, maxX - gx);
                int z0 = Math.max(-r, minZ - gz), z1 = Math.min(r, maxZ - gz);
                if (x0 <= x1 && z0 <= z1) {
                    for (int dx = x0; dx <= x1; dx++) {
                        if (dx == -r || dx == r) {
                            for (int dz = z0; dz <= z1; dz++) visit(gx + dx, gz + dz, x, z);
                        } else {
                            if (-r >= z0) visit(gx + dx, gz - r, x, z);
                            if (r <= z1) visit(gx + dx, gz + r, x, z);
                        }
                    }
                }
                int best = qBest;
                double bd = qBd;
                // 輪 r より外の升の点は r × CELL より遠い
                double lim = (double) r * CELL;
                if (best >= 0 && bd <= lim * lim) break;
            }
            return Math.max(0, qBest);
        }

        private int qBest;
        private double qBd;

        private void visit(int cx, int cz, double x, double z) {
            int[] l = grid.get(ChunkPos.asLong(cx, cz));
            if (l == null) return;
            for (int i : l) {
                double ddx = xs[i] - x, ddz = zs[i] - z, d = ddx * ddx + ddz * ddz;
                if (d < qBd || (d == qBd && i < qBest)) {
                    qBd = d;
                    qBest = i;
                }
            }
        }

        /** 総当たり(テストで比べる用)。 */
        public static int brute(double[] xs, double[] zs, double x, double z) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                double dx = xs[i] - x, dz = zs[i] - z, d = dx * dx + dz * dz;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }
    }

    /** 樹域ごとの縁のチャンク(countTerritory が数え直す)。守りの持ち場の 7 割【仮】はここから。 */
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList> EDGES = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /** 縁のチャンク → その縁を持つ樹域(11-22 見直し: 置き直しは自分の樹域の縁かで見る)。 */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap EDGE_ALL = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    static {
        EDGE_ALL.defaultReturnValue(Integer.MIN_VALUE);
    }
    public static long rebalancedHeads, rebalancedUnits, postEdge, postTree;
    /** 樹域の過繁茂のチャンク数の これまでの最大(この起動の中)。目標を超えた訳を確かめる用(11-22)。 */
    private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap PEAK = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
    /** 直前の rebalance の前に、目標を超えていた頭数・そのうち樹域が最大より 5% 以上狭まった樹域の分(計測の CSV 用)。 */
    public static long overBefore, overShrunkBefore;

    public static boolean isEdgeChunk(long chunkKey) {
        return EDGE_ALL.containsKey(chunkKey);
    }

    /**
     * 計測の CSV 用(縁をまだ数えていなければ空 3 つ): 守りの頭数のうち持ち場が縁のチャンクにある割合・縁を持つ樹域の守りの中でのその割合・
     * 守りのうち縁を持つ樹域のものの割合(内側の巨大樹の樹域には縁が無い)。
     */
    public static String defendEdgeStats(VirtualHordes hordes) {
        if (EDGE_ALL.isEmpty()) return ",,";
        long all = 0, edge = 0, inEdgeFronts = 0;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() < 0 || s.purpose() != UnitPurpose.DEFEND || s.size() <= 0) continue;
            all += s.size();
            var es = EDGES.get(s.front());
            if (es != null && !es.isEmpty()) inEdgeFronts += s.size();
            if (EDGE_ALL.containsKey(ChunkPos.asLong(Mth.floor(s.tx()) >> 4, Mth.floor(s.tz()) >> 4))) edge += s.size();
        }
        if (all <= 0) return "0,0,0";
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", edge / (double) all, inEdgeFronts > 0 ? edge / (double) inEdgeFronts : 0.0D, inEdgeFronts / (double) all);
    }

    /** 目標を超えた頭数の合計・超えた樹域の数(計測の CSV 用)。 */
    public static String overTargetStats(Fronts fronts) {
        long over = 0;
        int n = 0;
        for (Fronts.Front f : fronts.fronts()) {
            int d = heads(f.id) - target(f.id);
            if (d > 0) {
                over += d;
                n++;
            }
        }
        return over + "," + n;
    }

    /**
     * 11-22 ユーザー決定「目標の頭数を超えた樹域の部隊は、近くの目標に届いていない樹域へ歩いて移す。数は減らさない」。
     * 超えた樹域の守り(持ち場に着いた・実物のいない部隊)から、超えた分だけを抜いて新しい部隊にし、
     * rebalanceMaxBlocks の中でいちばん近い足りない樹域の守りの持ち場へ歩かせる(受け手の足りない分まで)。根付いた姿は動かない。
     */
    public static void rebalance(ServerLevel level, Fronts fronts, VirtualHordes hordes, Long2IntOpenHashMap real) {
        FrontTable t = DesignData.front();
        List<Fronts.Front> fs = fronts.fronts();
        overBefore = 0;
        overShrunkBefore = 0;
        for (Fronts.Front g : fs) {
            int d = heads(g.id) - target(g.id);
            if (d <= 0) continue;
            overBefore += d;
            if (TERRITORY.get(g.id) < PEAK.get(g.id) * 0.95D) overShrunkBefore += d;
        }
        if (!t.rebalanceEnabled) return;
        List<Fronts.Front> short_ = new ArrayList<>();
        for (Fronts.Front g : fs) if (heads(g.id) < target(g.id)) short_.add(g);
        if (short_.isEmpty()) return;
        long now = level.getGameTime();
        double max2 = t.rebalanceMaxBlocks * t.rebalanceMaxBlocks;
        RandomSource random = level.getRandom();
        for (Fronts.Front f : new ArrayList<>(fs)) {
            int surplus = heads(f.id) - target(f.id);
            if (surplus <= 0) continue;
            List<Fronts.Front> near = new ArrayList<>();
            for (Fronts.Front g : short_) {
                double dx = g.x - f.x, dz = g.z - f.z;
                if (g.id != f.id && dx * dx + dz * dz <= max2 && heads(g.id) < target(g.id)) near.add(g);
            }
            if (near.isEmpty()) continue;
            near.sort(java.util.Comparator.comparingDouble(g -> (g.x - f.x) * (g.x - f.x) + (g.z - f.z) * (g.z - f.z)));
            List<VirtualHordes.Swarm> mine = new ArrayList<>();
            for (VirtualHordes.Swarm s : hordes.swarms()) {
                if (s.front() == f.id && s.purpose() == UnitPurpose.DEFEND && s.arrived() && s.size() > 0 && real.get(s.id()) <= 0 && !REINFORCING.containsKey(s.id())
                        && !atFormingSpot(level, f, s.tx(), s.tz())) mine.add(s);
            }
            for (Fronts.Front g : near) {
                if (surplus <= 0) break;
                int need = target(g.id) - heads(g.id);
                for (VirtualHordes.Swarm s : mine) {
                    if (surplus <= 0 || need <= 0) break;
                    int want = Math.min(s.size(), Math.min(surplus, need));
                    if (want <= 0) continue;
                    double paid0 = s.paid();
                    var took = s.takeHeads(want);
                    int k = 0;
                    for (int v : took.values()) k += v;
                    if (k <= 0) continue; // 根付いた姿だけ
                    VirtualHordes.Swarm moved = hordes.newUnit(s.x(), s.y(), s.z(), g.id, UnitPurpose.DEFEND, now);
                    for (var e : took.entrySet()) moved.addHeads(e.getKey(), e.getValue(), 0.0D);
                    moved.paidAdd(paid0 - s.paid());
                    double[] post = post(level, g, random);
                    moved.assign(UnitPurpose.DEFEND, post[0], post[1], now);
                    HEADS.addTo(f.id, -k);
                    HEADS.addTo(g.id, k);
                    surplus -= k;
                    need -= k;
                    rebalancedHeads += k;
                    rebalancedUnits++;
                }
            }
        }
        hordes.setDirty();
    }

    public static long reposted;

    /**
     * 縁の持ち場の置き直し(11-22・仮の決め): 森が広がると縁の持ち場は森の奥になる。巨大樹のまわり(postRadiusChunks)の外にいて、持ち場がもう縁でない
     * 守り(着いた・実物のいない部隊)を、その樹域の今の縁へ歩かせる。巨大樹のまわりの 3 割はそのまま。
     */
    public static void repostEdges(ServerLevel level, Fronts fronts, VirtualHordes hordes, Long2IntOpenHashMap real) {
        if (EDGE_ALL.isEmpty()) return;
        long now = level.getGameTime();
        double r = (DesignData.front().postRadiusChunks + 1) * 16.0D;
        RandomSource random = level.getRandom();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() < 0 || s.purpose() != UnitPurpose.DEFEND || !s.arrived() || s.size() <= 0 || real.get(s.id()) > 0 || REINFORCING.containsKey(s.id())) continue;
            if (EDGE_ALL.get(ChunkPos.asLong(Mth.floor(s.tx()) >> 4, Mth.floor(s.tz()) >> 4)) == s.front()) continue; // 11-22 見直し: 自分の樹域の縁にいる(ほかの樹域の縁は置き直す)
            Fronts.Front f = fronts.byId(s.front());
            if (f == null || near(s.tx(), s.tz(), f.x, f.z, r)) continue; // 巨大樹のまわりの持ち場
            it.unimi.dsi.fastutil.longs.LongArrayList edges = EDGES.get(f.id);
            if (edges == null || edges.isEmpty()) {
                // 縁の無い内側の樹域: 巨大樹のまわりの外にいる守り(起動し直して駆けつけの記録が消えた予備など)を巨大樹のまわりへ戻す
                double[] p = post(level, f, random);
                s.assign(UnitPurpose.DEFEND, p[0], p[1], now);
                reposted++;
                continue;
            }
            long k = edges.getLong(random.nextInt(edges.size()));
            s.assign(UnitPurpose.DEFEND, ChunkPos.getX(k) * 16 + 8.5D, ChunkPos.getZ(k) * 16 + 8.5D, now);
            reposted++;
        }
    }

    // ---------------------------------------------------------------- 内側の予備が駆けつける(11-22 ユーザー決定「内側は予備で駆けつける」)

    /** 駆けつけている部隊の id → {元の樹域, 駆けつけた先の樹域}。起動し直すと消える(そのときは repostEdges が元の巨大樹のまわりへ戻す)。 */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<int[]> REINFORCING = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /** 樹域が最後に攻められた tick。 */
    private static final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap ATTACKED_AT = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    public static long reinforcedHeads, reinforcedUnits, reinforceReturned;
    /** 増援を考えた回数と、止まった訳(縁が無い = 攻められたのが内側の樹域・近くに内側の樹域が無い・予備に空きが無い)。11-23 D4 で増援 0 の訳を見る用。 */
    public static long reinforceCalls, reinforceNoEdge, reinforceNoInner, reinforceNoRoom;

    static boolean hasEdges(int front) {
        var e = EDGES.get(front);
        return e != null && !e.isEmpty();
    }

    public static boolean reinforcing(long swarmId) {
        return REINFORCING.containsKey(swarmId);
    }

    /** 樹域が最後に攻められた tick(攻められたことが無ければ Long.MIN_VALUE)。 */
    public static long lastAttacked(int front) {
        return ATTACKED_AT.containsKey(front) ? ATTACKED_AT.get(front) : Long.MIN_VALUE;
    }

    /** 11-22 見直し: 駆けつけの記録と攻められた tick を樹域の台帳(Fronts)と一緒に保存する。 */
    static void saveState(net.minecraft.nbt.CompoundTag tag) {
        it.unimi.dsi.fastutil.longs.LongArrayList r = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (var e : REINFORCING.long2ObjectEntrySet()) {
            r.add(e.getLongKey());
            r.add(((long) e.getValue()[0] << 32) | (e.getValue()[1] & 0xFFFFFFFFL));
        }
        tag.putLongArray("Reinforcing", r.toLongArray());
        it.unimi.dsi.fastutil.longs.LongArrayList a = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (var e : ATTACKED_AT.int2LongEntrySet()) {
            a.add(e.getIntKey());
            a.add(e.getLongValue());
        }
        tag.putLongArray("AttackedAt", a.toLongArray());
    }

    static void loadState(net.minecraft.nbt.CompoundTag tag) {
        REINFORCING.clear();
        ATTACKED_AT.clear();
        long[] r = tag.getLongArray("Reinforcing");
        for (int i = 0; i + 1 < r.length; i += 2) REINFORCING.put(r[i], new int[]{(int) (r[i + 1] >> 32), (int) r[i + 1]});
        long[] a = tag.getLongArray("AttackedAt");
        for (int i = 0; i + 1 < a.length; i += 2) ATTACKED_AT.put((int) a[i], a[i + 1]);
    }

    /** 11-22 見直し: 起動の間だけの覚え(樹域の広さ・縁・駆けつけなど)を、サーバーが止まったら消す(別のワールドへ持ち越さない)。 */
    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        TERRITORY.clear();
        HEADS.clear();
        territoryAt = Long.MIN_VALUE;
        EDGES.clear();
        EDGE_ALL.clear();
        PEAK.clear();
        REINFORCING.clear();
        ATTACKED_AT.clear();
        EXPAND_LEG_START.clear();
        FrontOutposts.clearStatics();
        FrontRaids.clearStatics();
        com.yayuy.overgrownvegetation.director.StorageOverflow.clearStatics();
    }

    /**
     * 外側の樹域 f(縁を持つ)が攻められた: 巨大樹の間が reinforceMaxBlocks の中の、縁の無い内側の樹域の守り(予備)から、
     * 予備の reinforceShare(出している分と合わせて)を f の縁へ出す。予備は reinforceKeepHeads を巨大樹のまわりに残す。
     */
    public static void reinforce(ServerLevel level, Fronts fronts, VirtualHordes hordes, Fronts.Front f, Long2IntOpenHashMap real) {
        FrontTable t = DesignData.front();
        if (t.reinforceShare <= 0.0D) return;
        reinforceCalls++;
        if (!hasEdges(f.id)) {
            reinforceNoEdge++;
            return;
        }
        var edges = EDGES.get(f.id);
        double max2 = t.reinforceMaxBlocks * t.reinforceMaxBlocks;
        List<Fronts.Front> inner = new ArrayList<>();
        for (Fronts.Front g : fronts.fronts()) {
            double dx = g.x - f.x, dz = g.z - f.z;
            if (g.id != f.id && !hasEdges(g.id) && dx * dx + dz * dz <= max2) inner.add(g);
        }
        if (inner.isEmpty()) {
            reinforceNoInner++;
            return;
        }
        long sentBefore = reinforcedUnits;
        inner.sort(java.util.Comparator.comparingDouble(g -> (g.x - f.x) * (g.x - f.x) + (g.z - f.z) * (g.z - f.z)));
        long now = level.getGameTime();
        RandomSource random = level.getRandom();
        for (Fronts.Front g : inner) {
            int all = 0, out = 0;
            List<VirtualHordes.Swarm> home = new ArrayList<>();
            for (VirtualHordes.Swarm s : hordes.swarms()) {
                if (s.front() != g.id || s.purpose() != UnitPurpose.DEFEND || s.size() <= 0) continue;
                all += s.size();
                if (REINFORCING.containsKey(s.id())) out += s.size();
                else if (s.arrived() && real.get(s.id()) <= 0 && !atFormingSpot(level, g, s.tx(), s.tz())) home.add(s);
            }
            int room = Math.min((int) Math.floor(all * t.reinforceShare), all - t.reinforceKeepHeads) - out;
            for (VirtualHordes.Swarm s : home) {
                if (room <= 0) break;
                double paid0 = s.paid();
                var took = s.takeHeads(Math.min(room, s.size()));
                int k = 0;
                for (int v : took.values()) k += v;
                if (k <= 0) continue;
                VirtualHordes.Swarm go = hordes.newUnit(s.x(), s.y(), s.z(), g.id, UnitPurpose.DEFEND, now);
                for (var e : took.entrySet()) go.addHeads(e.getKey(), e.getValue(), 0.0D);
                go.paidAdd(paid0 - s.paid());
                long c = edges.getLong(random.nextInt(edges.size()));
                go.assign(UnitPurpose.DEFEND, ChunkPos.getX(c) * 16 + 8.5D, ChunkPos.getZ(c) * 16 + 8.5D, now);
                REINFORCING.put(go.id(), new int[]{g.id, f.id});
                fronts.touch();
                room -= k;
                reinforcedHeads += k;
                reinforcedUnits++;
            }
        }
        if (reinforcedUnits == sentBefore) reinforceNoRoom++;
        hordes.setDirty();
    }

    /** 駆けつけた先が reinforceReturnDays 攻められていなければ、元の樹域の巨大樹のまわりへ戻す。消えた・ほかの役に回った部隊は外す。 */
    public static void returnReinforcements(ServerLevel level, Fronts fronts, VirtualHordes hordes, long now) {
        if (REINFORCING.isEmpty()) return;
        long wait = (long) (DesignData.front().reinforceReturnDays * 24000.0D);
        RandomSource random = level.getRandom();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet alive = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            int[] r = REINFORCING.get(s.id());
            if (r == null) continue;
            if (s.purpose() != UnitPurpose.DEFEND || s.size() <= 0 || s.front() != r[0]) continue;
            alive.add(s.id());
            long at = ATTACKED_AT.containsKey(r[1]) ? ATTACKED_AT.get(r[1]) : Long.MIN_VALUE / 2;
            if (now - at < wait) continue;
            Fronts.Front g = fronts.byId(r[0]);
            if (g == null) continue;
            double[] post = post(level, g, random);
            s.assign(UnitPurpose.DEFEND, post[0], post[1], now);
            alive.remove(s.id());
            reinforceReturned++;
        }
        if (REINFORCING.keySet().removeIf(id -> !alive.contains(id))) fronts.touch();
    }

    /** テスト用: 前線の「最近倒された値段」を決めて assign を 1 回まわす(実物なし)。 */
    public static void assignForTests(ServerLevel level, VirtualHordes hordes, Fronts.Front f, double losses) {
        f.losses = losses;
        f.lossesAt = level.getGameTime() / 24000.0D;
        assign(level, hordes, f, new Long2IntOpenHashMap());
    }

    public static void markAttackedForTests(int front, long tick) {
        ATTACKED_AT.put(front, tick);
    }

    public static double[] postForTests(ServerLevel level, Fronts.Front f, RandomSource random) {
        return post(level, f, random);
    }

    /** テスト用: 樹域の縁のチャンクを決める。 */
    public static void setEdgesForTests(int front, long... chunks) {
        it.unimi.dsi.fastutil.longs.LongArrayList l = new it.unimi.dsi.fastutil.longs.LongArrayList(chunks);
        EDGES.put(front, l);
        for (long k : chunks) EDGE_ALL.put(k, front);
    }

    /** 目標の合計・届いた樹域の数(計測の CSV 用)。 */
    public static String targetStats(Fronts fronts) {
        long sum = 0;
        int full = 0;
        for (Fronts.Front f : fronts.fronts()) {
            sum += target(f.id);
            if (atTarget(f.id)) full++;
        }
        return sum + "," + full + "," + atTargetSkips + "," + nurseryAtTargetSkips;
    }

    /** テスト用: 樹域の過繁茂の広さと頭数を決める。 */
    public static void setCountsForTests(int front, int chunks, int heads) {
        TERRITORY.put(front, chunks);
        HEADS.put(front, heads);
        territoryAt = Long.MAX_VALUE / 2;
    }

    public static void clearCountsForTests() {
        TERRITORY.clear();
        REINFORCING.clear();
        ATTACKED_AT.clear();
        HEADS.clear();
        territoryAt = Long.MIN_VALUE;
        EDGES.clear();
        EDGE_ALL.clear();
    }

    /** 作る中の 1 体の種(段階 0 の感染 Mob か、フェーズの割合で G2 以降)。 */
    @Nullable
    static EntityType<?> pickType(ServerLevel level, RandomSource random, int phase) {
        if (random.nextDouble() < DesignData.front().plantShare(phase)) {
            EntityType<?> p = DirectorHooks.pickPlant(level, random, 0);
            if (p != null && VirtualHordes.allowed(p)) return p;
        }
        G1Species s = DirectorHooks.pickG1(random);
        return s != null ? s.entityType() : null;
    }

    public static boolean isG1(EntityType<?> type) {
        for (G1Species s : G1Species.values()) if (s.entityType() == type) return true;
        return false;
    }

    /** 前線の真ん中で作っている途中の守る部隊(満員でないもの)。無ければ作る。 */
    static VirtualHordes.Swarm formingUnit(ServerLevel level, VirtualHordes hordes, Fronts.Front f, int max) {
        // 作る所 = 前線の真ん中 + 過繁茂になった現地の苗床(出す所。11-21 夜)。作った数で順に回す
        List<double[]> spots = formingSpots(level, f);
        double[] at = spots.get((int) Math.floorMod(produced, (long) spots.size()));
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() == f.id && s.purpose() == UnitPurpose.DEFEND && s.size() < max && near(s, at[0], at[1], 8.0D) && near(s.tx(), s.tz(), at[0], at[1], 8.0D)) return s;
        }
        double y = surfaceY(level, at[0], at[1]);
        return hordes.newUnit(at[0], y, at[1], f.id, UnitPurpose.DEFEND, level.getGameTime());
    }

    /** 前線の作る所(真ん中と、過繁茂になった現地の苗床)。 */
    static List<double[]> formingSpots(ServerLevel level, Fronts.Front f) {
        List<double[]> spots = new ArrayList<>();
        spots.add(new double[]{f.x, f.z});
        if (level.getServer() != null) {
            for (FrontNurseries.Nursery n : FrontNurseries.get(level.getServer()).sitesOf(f.id)) spots.add(new double[]{n.x(), n.z()});
        }
        return spots;
    }

    /** (x, z) が前線の作る所(8 ブロック以内)か。 */
    static boolean atFormingSpot(ServerLevel level, Fronts.Front f, double x, double z) {
        for (double[] p : formingSpots(level, f)) if (near(x, z, p[0], p[1], 8.0D)) return true;
        return false;
    }

    // ---------------------------------------------------------------- 割り振り

    static void assign(ServerLevel level, VirtualHordes hordes, Fronts.Front f, Long2IntOpenHashMap real) {
        FrontTable t = DesignData.front();
        long now = level.getGameTime();
        List<VirtualHordes.Swarm> mine = new ArrayList<>();
        int total = 0, defend = 0, expand = 0;
        double value = 0.0D;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() != f.id) continue;
            mine.add(s);
            int n = s.size() + real.get(s.id());
            total += n;
            value += s.paid();
            if (s.purpose() == UnitPurpose.DEFEND) defend += n;
            else if (s.purpose() == UnitPurpose.EXPAND) expand += n;
        }
        if (total <= 0) return;
        boolean attacked = attacked(f, value);
        if (attacked) {
            ATTACKED_AT.put(f.id, now);
            Fronts.get(level.getServer()).touch(); // 攻められた tick を保存する
            reinforce(level, Fronts.get(level.getServer()), hordes, f, real); // 11-22 ユーザー決定: 内側の予備が縁へ駆けつける
        }
        double defendShare = attacked ? t.defendShareAttacked : t.defendShareQuiet;
        RandomSource random = level.getRandom();
        // 攻められた: 広げる部隊を守りへ呼び戻す(守りの割合に届くまで)
        if (attacked) {
            for (VirtualHordes.Swarm s : mine) {
                if (defend >= defendShare * total) break;
                if (s.purpose() != UnitPurpose.EXPAND) continue;
                double[] post = post(level, f, random);
                s.assign(UnitPurpose.DEFEND, post[0], post[1], now);
                int n = s.size() + real.get(s.id());
                defend += n;
                expand -= n;
                recalled++;
            }
        }
        // 下限に届いた作りたての部隊を出す
        for (VirtualHordes.Swarm s : mine) {
            if (s.purpose() != UnitPurpose.DEFEND || s.size() < t.unitMin) continue;
            if (!atFormingSpot(level, f, s.tx(), s.tz())) continue; // もう持ち場へ出た
            int n = s.size();
            int defendOthers = defend - n; // この部隊を除いた守り
            if (!attacked && defendOthers >= defendShare * total && expand < t.expandShareQuiet * total) {
                long cell = expandTarget(level, hordes, f);
                if (cell != Long.MIN_VALUE) {
                    double cx = ((int) (cell >> 32) * 4 + 2) * 16.0D + 8.0D, cz = ((int) cell * 4 + 2) * 16.0D + 8.0D;
                    noteExpandSend(s, cx, cz, now);
                    s.assign(UnitPurpose.EXPAND, cx, cz, now);
                    defend -= n;
                    expand += n;
                    sentExpand++;
                    continue;
                }
            }
            double[] post = post(level, f, random);
            s.assign(UnitPurpose.DEFEND, post[0], post[1], now);
            sentDefend++;
        }
        evolve(level, hordes, f);
        if (attacked) FrontOutposts.get(level.getServer()).rootDefenders(level, hordes, f); // 11-22 ユーザー決定: 守りは攻められたときだけ根付く(前哨として部隊から外す)
        mergeDefenders(level, hordes, f);
    }

    // ---------------------------------------------------------------- 部隊の中の進化(区切り 6。G1Fate の進化を置き換える)

    public static long evolved;

    /**
     * 進化の道が開いていれば(F3 から)、前線の部隊の段階 0 を 1 回に evolvePerUnit 体まで 1 段目へ進化させる。値段は進化先の値段 − 段階 0
     * (11-21 ユーザー決定)、維持費は差の分だけ増えるので 8 割の関所を通す。予算は半日分の稼ぎ(reserveIncomeDays)を残す。
     */
    public static void evolve(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        FrontTable t = DesignData.front();
        if (t.evolvePerUnit <= 0 || !DirectorGates.open(level, DirectorGates.Gate.EVOLVE)) return;
        ForestDirector dir = ForestDirector.get(level);
        double reserve = reserve(level);
        double stageZero = DesignData.director().largeRaid.costStageZero * com.yayuy.overgrownvegetation.director.ForestEconomy.priceFactor();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() != f.id || s.size() <= 0) continue;
            int done = 0;
            int devNow = 0;
            for (var e : s.counts().entrySet()) {
                if (com.yayuy.overgrownvegetation.entity.g1.G1Developed.of(BuiltInRegistries.ENTITY_TYPE.get(e.getKey())) != null) devNow += e.getValue();
            }
            int devMax = (int) Math.floor(t.evolveMaxShare * s.size());
            for (var e : new ArrayList<>(s.counts().entrySet())) {
                if (done >= t.evolvePerUnit) break;
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(e.getKey());
                G1Species sp = null;
                for (G1Species g : G1Species.values()) if (g.entityType() == type) sp = g;
                if (sp == null) continue;
                com.yayuy.overgrownvegetation.entity.g1.G1Developed dev = null;
                for (var d : com.yayuy.overgrownvegetation.entity.g1.G1Developed.values()) if (d.base() == sp) dev = d;
                if (dev == null) continue;
                EntityType<?> devType = BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id(dev.id()));
                double devPrice = ForestUpkeep.unitPrice(devType, false);
                double cost = Math.max(0.0D, devPrice - stageZero);
                while (done < t.evolvePerUnit && devNow < devMax && s.counts().getOrDefault(e.getKey(), 0) > 0) {
                    if (dir.available(level) - cost < reserve) return;
                    if (!dir.tryPayUnit(level, cost, Math.max(0.0D, devPrice - stageZero), !s.purpose().marches)) return;
                    s.takeHeadsOf(e.getKey(), 1);
                    s.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(devType), 1, cost);
                    done++;
                    evolved++;
                    devNow++;
                }
            }
        }
        hordes.touch();
    }

    // ---------------------------------------------------------------- 部隊の中の根付き(11-21 ユーザー了承)

    public static long rooted;

    /**
     * 11-22 から: 攻められたときだけ assign が呼ぶ(FrontOutposts.rootDefenders)。根付いた個体は部隊から外して前哨に残す。
     * 根付きの道が開いていれば(F2 から)、守りの持ち場に着いた部隊の段階 0 を 1 回の見直しに rootedPerUnit 体まで根付きの姿へ替える
     * (1 部隊のうち rootedMaxShare まで)。値段は根付きの姿の値段 − 段階 0(進化と同じ「進化先の危険度に応じた追加の費用」)。
     * 予算は半日分の稼ぎ(reserveIncomeDays)を残し、維持費の差は 8 割の関所を通す。
     */
    public static void root(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        rooted += FrontOutposts.get(level.getServer()).rootDefenders(level, hordes, f); // 11-22: 根付いた個体は部隊から外して前哨へ(FrontOutposts)
    }
    // ---------------------------------------------------------------- 部隊に入っていない Mob を吸い込む(区切り 6)

    public static long absorbed;

    /**
     * 前線の部隊に入っていない植物側の Mob(感染で起きた G1・古い湧きの残り)を、どのプレイヤーからも absorbBlocks より遠く、戦っていなければ、
     * いちばん近い前線の作る途中の部隊へ吸い込む(感染で生産 → 部隊になる、の道。11-21)。名札・乗り物・偵察は除く。
     */
    public static void absorbLoose(ServerLevel level, Fronts fronts, VirtualHordes hordes) {
        double far = DesignData.front().absorbBlocks;
        int max = DesignData.front().unitMax(DirectorGates.phase(level));
        List<Mob> loose = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob m) || !m.isAlive() || m.getPersistentData().contains(VirtualHordes.UNIT_KEY) || m.getPersistentData().contains(FrontOutposts.KEY) || m.getTags().contains(VirtualHordes.TAG)) continue;
            if (!ForestUpkeep.army(m.getType()) || !VirtualHordes.allowed(m.getType()) || m.hasCustomName() || m.isPassenger() || m.isVehicle() || m.getTarget() != null) continue;
            if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster g && g.fate() != null && g.fate().scout().active()) continue;
            if (level.getNearestPlayer(m.getX(), m.getY(), m.getZ(), far, false) != null) continue;
            loose.add(m);
        }
        for (Mob m : loose) {
            Fronts.Front f = fronts.nearest(m.getX(), m.getZ(), -1);
            if (f == null) return;
            VirtualHordes.Swarm s = formingUnit(level, hordes, f, max);
            if (hordes.absorb(level, s, m)) absorbed++;
        }
    }

    /** 守りの持ち場: 前線のまわり postRadiusChunks の過繁茂のチャンクから 1 つ(見つからなければ前線の真ん中の少し外)。 */
    static double[] post(ServerLevel level, Fronts.Front f, RandomSource random) {
        // 11-22 ユーザー決定: postEdgeShare【仮 0.7】は樹域の縁、残りは巨大樹のまわり(縁が無ければ巨大樹のまわり)
        it.unimi.dsi.fastutil.longs.LongArrayList edges = EDGES.get(f.id);
        if (edges != null && !edges.isEmpty() && random.nextDouble() < DesignData.front().postEdgeShare) {
            long k = edges.getLong(random.nextInt(edges.size()));
            postEdge++;
            return new double[]{ChunkPos.getX(k) * 16 + 8.5D, ChunkPos.getZ(k) * 16 + 8.5D};
        }
        postTree++;
        int r = DesignData.front().postRadiusChunks;
        PhaseData pd = PhaseData.get(level);
        int fcx = Mth.floor(f.x) >> 4, fcz = Mth.floor(f.z) >> 4;
        for (int i = 0; i < 16 && r > 0; i++) {
            int cx = fcx + random.nextInt(2 * r + 1) - r, cz = fcz + random.nextInt(2 * r + 1) - r;
            if (Math.abs(cx - fcx) + Math.abs(cz - fcz) < 1) continue;
            if (pd.isOvergrownKey(ChunkPos.asLong(cx, cz))) return new double[]{cx * 16 + 8.5D, cz * 16 + 8.5D};
        }
        double a = random.nextDouble() * Math.PI * 2.0D;
        return new double[]{f.x + Math.cos(a) * 24.0D, f.z + Math.sin(a) * 24.0D};
    }

    /** 守りの持ち場に着いた部隊どうしを、近ければ合わせる(上限まで)。 */
    static void mergeDefenders(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        int max = DesignData.front().unitMax(DirectorGates.phase(level));
        double mb = DesignData.front().mergeBlocks;
        List<VirtualHordes.Swarm> at = new ArrayList<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() == f.id && s.purpose() == UnitPurpose.DEFEND && s.arrived() && s.size() > 0 && !atFormingSpot(level, f, s.x(), s.z())) at.add(s);
        }
        for (int i = 0; i < at.size(); i++) {
            VirtualHordes.Swarm a = at.get(i);
            if (a.size() <= 0) continue;
            for (int j = i + 1; j < at.size(); j++) {
                VirtualHordes.Swarm b = at.get(j);
                if (b.size() <= 0 || a.size() + b.size() > max || !near(a, b.x(), b.z(), mb)) continue;
                double paid = b.paid();
                int n = b.size();
                for (var e : b.takeHeads(n).entrySet()) a.addHeads(e.getKey(), e.getValue(), 0.0D);
                addPaid(a, paid);
                merged++;
            }
        }
    }

    private static void addPaid(VirtualHordes.Swarm s, double paid) {
        s.paidAdd(paid); // takeHeads で抜いた額を足し直す
    }

    // ---------------------------------------------------------------- 広げる

    /** 前線のまわりで、森のすぐ外の升(4×4 チャンク)のうち、水でなく、生き物の少ない升でなく、ほかの広げる部隊が向かっていない、いちばん近い升。無ければ Long.MIN_VALUE。 */
    static long expandTarget(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        PhaseData pd = PhaseData.get(level);
        int fcx = (Mth.floor(f.x) >> 4) >> 2, fcz = (Mth.floor(f.z) >> 4) >> 2;
        int rc = Math.max(1, DesignData.front().expandSearchChunks / 4);
        // 11-21 夜: 胞子で森の縁が遠くへ出たら、上限 expandSearchMaxChunks まで外へ探し広げる(全体管理了承)
        int rcMax = Math.max(rc, DesignData.front().expandSearchMaxChunks / 4);
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.purpose() == UnitPurpose.EXPAND) taken.add(cellKey(s.tx(), s.tz()));
        }
        // 11-21 ほかの感染系 MOD: 印の付いた升を先に(森の中でも・生き物の少ない升でも)
        ForeignInfection foreign = ForeignInfection.get(level.getServer());
        if (!foreign.marks().isEmpty()) {
            long best = Long.MIN_VALUE;
            double bd = Double.MAX_VALUE;
            for (long k : foreign.marks().keySet()) {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (taken.contains(key)) continue;
                double d = (double) (cx - fcx) * (cx - fcx) + (double) (cz - fcz) * (cz - fcz);
                if (d > (double) rc * rc || d >= bd) continue;
                bd = d;
                best = key;
            }
            if (best != Long.MIN_VALUE) return best;
        }
        for (int r = 1; r <= rcMax; r++) {
            long best = Long.MIN_VALUE;
            double bd = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = fcx + dx, cz = fcz + dz;
                    long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                    if (taken.contains(key) || cellForest(pd, cx, cz) || !nextToForest(pd, cx, cz)) continue;
                    double bx = (cx * 4 + 2) * 16.0D + 8.0D, bz = (cz * 4 + 2) * 16.0D + 8.0D;
                    if (UnitTerrain.blocked(UnitTerrain.kind(level, bx, bz))) continue;
                    if (CellLife.get().view(level, cx, cz).sparse()) continue;
                    double d = dx * dx + dz * dz;
                    if (d < bd) {
                        bd = d;
                        best = key;
                    }
                }
            }
            if (best != Long.MIN_VALUE) return best;
        }
        return Long.MIN_VALUE;
    }

    /** テスト用: 前線 f の広げる升(無ければ Long.MIN_VALUE)。 */
    public static long expandTargetForTests(ServerLevel level, VirtualHordes hordes, Fronts.Front f) {
        return expandTarget(level, hordes, f);
    }

    static long cellKey(double x, double z) {
        int cx = (Mth.floor(x) >> 4) >> 2, cz = (Mth.floor(z) >> 4) >> 2;
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** 升の真ん中のチャンクが過繁茂か。 */
    static boolean cellForest(PhaseData pd, int cx, int cz) {
        return pd.isOvergrownKey(ChunkPos.asLong(cx * 4 + 2, cz * 4 + 2));
    }

    /** 升のまわり 4 方向のどれかが森か。 */
    static boolean nextToForest(PhaseData pd, int cx, int cz) {
        return cellForest(pd, cx + 1, cz) || cellForest(pd, cx - 1, cz) || cellForest(pd, cx, cz + 1) || cellForest(pd, cx, cz - 1);
    }

    /** 着いた広げる部隊が升を食べる。食べ終えた(森になった・もう生き物がいない)ら次の升へ、無ければ守りへ戻る。 */
    public static void expand(ServerLevel level, VirtualHordes hordes, double seconds) {
        FrontTable t = DesignData.front();
        long now = level.getGameTime();
        Fronts fronts = Fronts.get(level.getServer());
        PhaseData pd = PhaseData.get(level);
        for (VirtualHordes.Swarm s : new ArrayList<>(hordes.swarms())) {
            if (s.purpose() != UnitPurpose.EXPAND || !s.arrived() || s.size() <= 0) continue;
            noteExpandArrival(s, now);
            if ((now - s.purposeSince()) % (long) (t.expandIntervalSeconds * 20.0D) >= (long) (seconds * 20.0D)) continue;
            long cell = cellKey(s.tx(), s.tz());
            int cx = (int) (cell >> 32), cz = (int) cell;
            FrontNurseries.get(level.getServer()).tryMake(level, s, cx, cz); // 11-21 夜: 着いた升に苗床を 1 つ
            FrontWatchers.get(level.getServer()).tryPlant(level, s, cx, cz); // 11-22: 巨大樹の管理の外なら前線のフィロセンスを 1 体
            FrontOutposts.get(level.getServer()).tryOutpost(level, s, fronts.byId(s.front()), cx, cz); // 11-22: 巨大樹から遠い升では取り込んだ生き物を根付きの前哨に
            CellLife.Result r = CellLife.get().infect(level, cx, cz, s.size(), t.expandIntervalSeconds);
            if (r.gain() > 0.0D) {
                ForestDirector.get(level).refund(level, r.gain());
                expandGain += r.gain();
            }
            if (r.infected() > 0) {
                expandInfected += r.infected();
                addInfected(level, s, r.infected(), r.villagers());
            }
            // 11-21 ほかの感染系 MOD の印: いる間は減らし、消えたら森で上書き。消えるまでは次へ行かない
            ForeignInfection foreign = ForeignInfection.get(level.getServer());
            if (foreign.mark(cx, cz) > 0.0D && !foreign.clean(level, cx, cz, s.size())) continue;
            CellLife.View v = CellLife.get().view(level, cx, cz);
            boolean done = r.nowOvergrown() || cellForest(pd, cx, cz) || v.sparse() || (CellLife.get() != CellLife.NONE && v.left() <= 0);
            if (!done) continue;
            Fronts.Front f = fronts.byId(s.front());
            if (f == null) continue;
            long next = expandTarget(level, hordes, f);
            if (next != Long.MIN_VALUE) {
                noteExpandSend(s, ((int) (next >> 32) * 4 + 2) * 16.0D + 8.0D, ((int) next * 4 + 2) * 16.0D + 8.0D, now);
                s.assign(UnitPurpose.EXPAND, ((int) (next >> 32) * 4 + 2) * 16.0D + 8.0D, ((int) next * 4 + 2) * 16.0D + 8.0D, now);
            } else {
                double[] post = post(level, f, level.getRandom());
                s.assign(UnitPurpose.DEFEND, post[0], post[1], now);
            }
        }
    }

    /** 感染した生き物を G1 として部隊に足す(払いは無し = 森の稼ぎ。部隊の上限を超えた分は新しい部隊に)。 */
    static void addInfected(ServerLevel level, VirtualHordes.Swarm s, int infected, int villagers) {
        RandomSource random = level.getRandom();
        G1Species[] animals = {G1Species.COW, G1Species.PIG, G1Species.SHEEP, G1Species.CHICKEN};
        List<ResourceLocation> got = new ArrayList<>();
        for (int i = 0; i < infected; i++) {
            G1Species sp = i < villagers ? G1Species.VILLAGER : animals[random.nextInt(animals.length)];
            got.add(BuiltInRegistries.ENTITY_TYPE.getKey(sp.entityType()));
        }
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        int kept = routeInfected(level, Fronts.get(level.getServer()), hordes, s, got);
        FrontOutposts.noteInfected(s.id(), kept); // 前哨にするのは部隊に残った分だけ
        hordes.touch();
    }

    /**
     * 11-22 ユーザー決定「感染で増えた分は、まず目標に届いていない樹域へ回す」: 部隊の居る所から rebalanceMaxBlocks の中の、目標に届いていない樹域へ
     * (自分の樹域が足りなければ自分を先に、ほかは近い順。足りない分まで)。ほかの樹域の分は新しい守りの部隊にしてその持ち場へ歩かせる。
     * 届いていない樹域が無ければ今の部隊に入れる。部隊 s に残した数を返す。
     */
    static int routeInfected(ServerLevel level, Fronts fronts, VirtualHordes hordes, VirtualHordes.Swarm s, List<ResourceLocation> got) {
        FrontTable t = DesignData.front();
        int left = got.size(), idx = 0, kept = 0;
        if (left <= 0) return 0;
        double max2 = t.rebalanceMaxBlocks * t.rebalanceMaxBlocks;
        List<Fronts.Front> short_ = new ArrayList<>();
        for (Fronts.Front g : fronts.fronts()) {
            double dx = g.x - s.x(), dz = g.z - s.z();
            if (heads(g.id) < target(g.id) && (g.id == s.front() || dx * dx + dz * dz <= max2)) short_.add(g);
        }
        short_.sort(java.util.Comparator.comparingDouble((Fronts.Front g) -> g.id == s.front() ? -1.0D : (g.x - s.x()) * (g.x - s.x()) + (g.z - s.z()) * (g.z - s.z())));
        long now = level.getGameTime();
        RandomSource random = level.getRandom();
        for (Fronts.Front g : short_) {
            if (left <= 0) break;
            int k = Math.min(left, target(g.id) - heads(g.id));
            if (k <= 0) continue;
            VirtualHordes.Swarm into = s;
            if (g.id != s.front()) {
                into = hordes.newUnit(s.x(), s.y(), s.z(), g.id, UnitPurpose.DEFEND, now);
                double[] post = post(level, g, random);
                into.assign(UnitPurpose.DEFEND, post[0], post[1], now);
                routedInfected += k;
            } else {
                kept += k;
            }
            for (int i = 0; i < k; i++) into.addHeads(got.get(idx++), 1, 0.0D);
            HEADS.addTo(g.id, k);
            left -= k;
        }
        // 届いていない樹域が無い(残り): 今の部隊へ
        for (; idx < got.size(); idx++) s.addHeads(got.get(idx), 1, 0.0D);
        if (left > 0 && s.front() >= 0) HEADS.addTo(s.front(), left);
        return kept + left;
    }

    /** テスト用。 */
    public static int routeInfectedForTests(ServerLevel level, Fronts fronts, VirtualHordes hordes, VirtualHordes.Swarm s, List<ResourceLocation> got) {
        return routeInfected(level, fronts, hordes, s, got);
    }

    // ---------------------------------------------------------------- 補助

    /** 部隊の id → 読み込まれた実物の数。 */
    static Long2IntOpenHashMap realByUnit(ServerLevel level) {
        Long2IntOpenHashMap m = new Long2IntOpenHashMap();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob mob && mob.isAlive() && mob.getPersistentData().contains(VirtualHordes.UNIT_KEY)) {
                m.addTo(mob.getPersistentData().getLong(VirtualHordes.UNIT_KEY), 1);
            }
        }
        return m;
    }

    static boolean near(VirtualHordes.Swarm s, double x, double z, double r) {
        return near(s.x(), s.z(), x, z, r);
    }

    static boolean near(double ax, double az, double bx, double bz, double r) {
        return (ax - bx) * (ax - bx) + (az - bz) * (az - bz) <= r * r;
    }

    static double surfaceY(ServerLevel level, double x, double z) {
        net.minecraft.world.level.chunk.LevelChunk ch = level.getChunkSource().getChunkNow(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        return ch == null ? level.getSeaLevel() : ch.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x) & 15, Mth.floor(z) & 15) + 1;
    }

    /** 確かめ用の 1 行。 */
    public static String describe(ServerLevel level) {
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes hordes = VirtualHordes.get(level.getServer());
        int[] byPurpose = new int[UnitPurpose.values().length];
        int units = 0;
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (!s.unit()) continue;
            units++;
            byPurpose[s.purpose().ordinal()] += s.size();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "樹域と部隊: %s・樹域 %d・部隊 %d(", enabled() ? "入" : "切", fronts.fronts().size(), units));
        for (UnitPurpose p : UnitPurpose.values()) if (byPurpose[p.ordinal()] > 0) sb.append(p.label).append(' ').append(byPurpose[p.ordinal()]).append(' ');
        sb.append(String.format(Locale.ROOT, ")/ 作った %d(G2 以降 %d)・関所で止まった %d・守りへ %d・広げるへ %d・呼び戻し %d・合わさった %d・合流 %d・升で感染 %d(+%.0f)・部隊の中の進化 %d・吸い込み %d",
                produced, producedPlant, refusedByGate, sentDefend, sentExpand, recalled, merged, joined, expandInfected, expandGain, evolved, absorbed));
        return sb.toString();
    }
}
