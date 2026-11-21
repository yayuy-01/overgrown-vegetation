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
 *       予算が上限の reserveCapShare より多い分だけを使う(残りは器官・巨大樹・進化)。</li>
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

    private FrontDirector() {
    }

    public static boolean enabled() {
        return DesignData.front().enabled && DesignData.director().enabled && VirtualHordes.enabled();
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
        for (Fronts.Front f : new ArrayList<>(fronts.fronts())) {
            decayLosses(level, f, t);
            produce(level, fronts, hordes, f, seconds);
            if (f.assignedAt == Long.MIN_VALUE || now - f.assignedAt >= (long) (t.assignIntervalSeconds * 20.0D)) {
                f.assignedAt = now;
                assign(level, hordes, f, real);
            }
        }
        expand(level, hordes, seconds);
        // 空の部隊(実物も残っていない)を片付ける
        for (VirtualHordes.Swarm s : new ArrayList<>(hordes.swarms())) {
            if (s.unit() && s.size() <= 0 && real.get(s.id()) <= 0) hordes.removeUnit(s);
        }
    }

    // ---------------------------------------------------------------- 前線を巨大樹に合わせる

    public static void sync(ServerLevel level, Fronts fronts, VirtualHordes hordes) {
        PhaseData pd = PhaseData.get(level);
        long[] trees = pd.giantTreeOriginSnapshot();
        boolean anyTree = false;
        for (long key : trees) {
            if (pd.isGiantTreeDead(key)) continue;
            anyTree = true;
            if (fronts.byTree(key) == null) fronts.add(Fronts.Kind.TREE, key, (int) (key >> 32) + 0.5D, (int) key + 0.5D);
        }
        // 倒された(ハイヴノードを倒された・記録から消えた)巨大樹の前線: 部隊は近くの前線へ合流
        for (Fronts.Front f : new ArrayList<>(fronts.fronts())) {
            boolean gone = f.kind == Fronts.Kind.TREE && (pd.isGiantTreeDead(f.treeKey) || !contains(trees, f.treeKey));
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
        Fronts.Front f = Fronts.get(level.getServer()).byId(e.getPersistentData().getInt(VirtualHordes.FRONT_KEY));
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
        ForestDirector dir = ForestDirector.get(level);
        double reserve = ForestDirector.cap(level) * t.reserveCapShare;
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
            produced++;
            if (!g1) producedPlant++;
        }
        hordes.touch();
        fronts.touch();
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

    static boolean isG1(EntityType<?> type) {
        for (G1Species s : G1Species.values()) if (s.entityType() == type) return true;
        return false;
    }

    /** 前線の真ん中で作っている途中の守る部隊(満員でないもの)。無ければ作る。 */
    static VirtualHordes.Swarm formingUnit(ServerLevel level, VirtualHordes hordes, Fronts.Front f, int max) {
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.front() == f.id && s.purpose() == UnitPurpose.DEFEND && s.size() < max && near(s, f.x, f.z, 8.0D) && near(s.tx(), s.tz(), f.x, f.z, 8.0D)) return s;
        }
        double y = surfaceY(level, f.x, f.z);
        return hordes.newUnit(f.x, y, f.z, f.id, UnitPurpose.DEFEND, level.getGameTime());
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
            if (!near(s.tx(), s.tz(), f.x, f.z, 8.0D)) continue; // もう持ち場へ出た
            int n = s.size();
            int defendOthers = defend - n; // この部隊を除いた守り
            if (!attacked && defendOthers >= defendShare * total && expand < t.expandShareQuiet * total) {
                long cell = expandTarget(level, hordes, f);
                if (cell != Long.MIN_VALUE) {
                    double cx = ((int) (cell >> 32) * 4 + 2) * 16.0D + 8.0D, cz = ((int) cell * 4 + 2) * 16.0D + 8.0D;
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
        mergeDefenders(level, hordes, f);
    }

    /** 守りの持ち場: 前線のまわり postRadiusChunks の過繁茂のチャンクから 1 つ(見つからなければ前線の真ん中の少し外)。 */
    static double[] post(ServerLevel level, Fronts.Front f, RandomSource random) {
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
            if (s.front() == f.id && s.purpose() == UnitPurpose.DEFEND && s.arrived() && s.size() > 0 && !near(s, f.x, f.z, 8.0D)) at.add(s);
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
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (VirtualHordes.Swarm s : hordes.swarms()) {
            if (s.purpose() == UnitPurpose.EXPAND) taken.add(cellKey(s.tx(), s.tz()));
        }
        for (int r = 1; r <= rc; r++) {
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
            if ((now - s.purposeSince()) % (long) (t.expandIntervalSeconds * 20.0D) >= (long) (seconds * 20.0D)) continue;
            long cell = cellKey(s.tx(), s.tz());
            int cx = (int) (cell >> 32), cz = (int) cell;
            CellLife.Result r = CellLife.get().infect(level, cx, cz, s.size(), t.expandIntervalSeconds);
            if (r.gain() > 0.0D) {
                ForestDirector.get(level).refund(level, r.gain());
                expandGain += r.gain();
            }
            if (r.infected() > 0) {
                expandInfected += r.infected();
                addInfected(level, s, r.infected(), r.villagers());
            }
            CellLife.View v = CellLife.get().view(level, cx, cz);
            boolean done = r.nowOvergrown() || cellForest(pd, cx, cz) || v.sparse() || (CellLife.get() != CellLife.NONE && v.left() <= 0);
            if (!done) continue;
            Fronts.Front f = fronts.byId(s.front());
            if (f == null) continue;
            long next = expandTarget(level, hordes, f);
            if (next != Long.MIN_VALUE) {
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
        for (int i = 0; i < infected; i++) {
            G1Species sp = i < villagers ? G1Species.VILLAGER : animals[random.nextInt(animals.length)];
            s.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(sp.entityType()), 1, 0.0D);
        }
        VirtualHordes.get(level.getServer()).touch();
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
        sb.append(String.format(Locale.ROOT, "前線と部隊: %s・前線 %d・部隊 %d(", enabled() ? "入" : "切", fronts.fronts().size(), units));
        for (UnitPurpose p : UnitPurpose.values()) if (byPurpose[p.ordinal()] > 0) sb.append(p.label).append(' ').append(byPurpose[p.ordinal()]).append(' ');
        sb.append(String.format(Locale.ROOT, ")/ 作った %d(G2 以降 %d)・関所で止まった %d・守りへ %d・広げるへ %d・呼び戻し %d・合わさった %d・合流 %d・升で感染 %d(+%.0f)",
                produced, producedPlant, refusedByGate, sentDefend, sentExpand, recalled, merged, joined, expandInfected, expandGain));
        return sb.toString();
    }
}
