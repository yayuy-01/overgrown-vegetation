package com.yayuy.overgrownvegetation.entity.crawler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

/**
 * 「小さな地這いの虫」の群れ(2026-11-16、グレガリアの能力で足した。群れ v2 の {@code swarm/} は parked のままなので、ここは軽い作り)。
 * 使う種だけが入る({@link SmallCrawler#usesPack()}。ストロビラ・ヴェシカリアは入らない)。サーバーの中だけにあり、保存はしない:
 * 群れの番号は各個体が持ち、読み込み直したら同じ番号の個体が集まって組み直す(ヴェロフィルの Flock と同じ考え)。
 *
 * <h2>決まり【仮・Claude 11-16】</h2>
 * <ul>
 *   <li>入る: 湧いた時と {@link #JOIN_EVERY} tick おき(1 体だけの群れのとき)に、{@link #JOIN_RADIUS} ブロック以内の同じ種の群れへ。上限 {@link #MAX_SIZE} 体。
 *       群れの一覧を見るだけで、エンティティの箱の検索はしない。</li>
 *   <li>先導役 = いちばん古い個体(並びの頭)。先導役だけが {@link #SEARCH_EVERY} tick おきに相手を探す: プレイヤーは一覧から(安い)、
 *       ほかの生き物の箱の検索は {@link #MOB_SEARCH_EVERY} 回に 1 回(ヴェロフィル 98b3476 と同じ作り)。見つけた相手は群れの全員の標的になる。</li>
 *   <li>叩かれたら叩いた相手が群れの標的になる。相手が死ぬ・狙えなくなる・先導役から {@link #LOSE_RANGE} より離れると外す。</li>
 *   <li>相手のいない間: 先導役はいつものうろつき、後続は先導役のまわりへゆるくまとまって付いていく({@link FollowGoal})。
 *       司令塔の送り込み({@code spawn/PlantMarch})で歩いている個体には付いていかせない(送り込みが先)。</li>
 *   <li>相手のいる間: 全員が相手へ走り、近くでは足元を囲む位置へ直に舵を切る({@link ChaseGoal})。</li>
 * </ul>
 */
public final class CrawlerPack {

    public static final double JOIN_RADIUS = 12.0D;
    public static final int MAX_SIZE = 24;
    public static final int JOIN_EVERY = 100;
    public static final int SEARCH_EVERY = 40, MOB_SEARCH_EVERY = 5;
    public static final double SEARCH_RANGE = 24.0D, LOSE_RANGE = 32.0D;
    /** 送り込みで歩いている印(spawn/PlantMarch の保存データの鍵)。 */
    static final String MARCH_KEY = "ov_plant_march";

    private static final Map<ServerLevel, Map<UUID, CrawlerPack>> BY_LEVEL = new WeakHashMap<>();

    public final UUID id;
    final EntityType<?> type;
    /** 並びの頭が先導役。グレガリアは SmallCrawler、混ぜた群れ(11-17 enemy-spawns)はアルボラなどほかの Mob も入る。 */
    final List<Mob> members = new ArrayList<>();
    @Nullable
    private LivingEntity target;
    private int searchCount;

    /** 計測: 相手探しにかけた時間・回数(サーバー全体)。 */
    public static volatile long searchNanos, searchCalls;

    private CrawlerPack(UUID id, EntityType<?> type) {
        this.id = id;
        this.type = type;
    }

    private static Map<UUID, CrawlerPack> packs(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, k -> new HashMap<>());
    }

    /** 群れの番号から群れを引く(無ければ作る)。 */
    static CrawlerPack obtain(ServerLevel level, UUID id, EntityType<?> type) {
        return packs(level).computeIfAbsent(id, k -> new CrawlerPack(k, type));
    }

    /** 近くの同じ種の群れ(先導役が {@link #JOIN_RADIUS} 以内・空きあり)。 */
    @Nullable
    static CrawlerPack nearby(ServerLevel level, SmallCrawler mob, @Nullable CrawlerPack except) {
        CrawlerPack best = null;
        double bestD = JOIN_RADIUS * JOIN_RADIUS;
        for (CrawlerPack p : packs(level).values()) {
            if (p == except || p.type != mob.getType()) continue;
            p.prune(level);
            if (p.members.isEmpty() || p.members.size() >= MAX_SIZE) continue;
            double d = p.leader().distanceToSqr(mob);
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    /** 群れの数(テスト・計測用)。 */
    public static int count(ServerLevel level) {
        return packs(level).size();
    }

    void add(Mob mob) {
        if (!this.members.contains(mob)) this.members.add(mob);
    }

    void remove(ServerLevel level, Mob mob) {
        this.members.remove(mob);
        if (EXTERNAL.get(mob) == this) EXTERNAL.remove(mob);
        if (this.members.isEmpty()) packs(level).remove(this.id);
        else if (this.type == null && this.members.get(0) != this.lastLeader) electLeader();
    }

    /** 最後に外した tick(群れの 1 tick に 1 回だけ。前は全員が毎 tick 全員を見ていた = 群れの大きさの 2 乗。11-16 見直し 第 3 便)。 */
    private long prunedAt = Long.MIN_VALUE;

    /** 死んだ・消えた個体を外す(同じ tick の 2 回目からは何もしない)。 */
    void prune(ServerLevel level) {
        long now = level.getGameTime();
        if (now == this.prunedAt) return;
        this.prunedAt = now;
        this.members.removeIf(m -> {
            if (m.isAlive() && !m.isRemoved()) return false;
            if (EXTERNAL.get(m) == this) EXTERNAL.remove(m);
            return true;
        });
        // 外の群れで先導役が倒れたら、1 回だけ選び直す(11-17 深夜)
        if (this.type == null && !this.members.isEmpty() && this.members.get(0) != this.lastLeader) electLeader();
    }

    public Mob leader() {
        return this.members.get(0);
    }

    public int size() {
        return this.members.size();
    }

    public List<Mob> membersView() {
        return java.util.Collections.unmodifiableList(this.members);
    }

    @Nullable
    public LivingEntity target() {
        return this.target;
    }

    /** 叩かれた・テストから: 群れの標的にする。 */
    public void setTarget(@Nullable LivingEntity t) {
        this.target = t;
    }

    /** 先導役の毎 tick(サーバー): 標的を見直し、間をあけて探す。 */
    void leaderTick(ServerLevel level, SmallCrawler leader, BiPredicate<SmallCrawler, LivingEntity> prey) {
        LivingEntity t = this.target;
        if (t != null && (t.level() != level || !prey.test(leader, t) || leader.distanceToSqr(t) > LOSE_RANGE * LOSE_RANGE)) {
            this.target = null;
            t = null;
        }
        if (t == null && (leader.tickCount + leader.getId()) % SEARCH_EVERY == 0 && !leader.huntsDisabled()) {
            this.target = search(level, leader, prey);
        }
    }

    @Nullable
    private LivingEntity search(ServerLevel level, SmallCrawler leader, BiPredicate<SmallCrawler, LivingEntity> prey) {
        long t0 = System.nanoTime();
        try {
            LivingEntity best = null;
            double bestD = SEARCH_RANGE * SEARCH_RANGE;
            for (ServerPlayer p : level.players()) {
                double d = p.distanceToSqr(leader);
                if (d < bestD && prey.test(leader, p) && leader.hasLineOfSight(p)) {
                    best = p;
                    bestD = d;
                }
            }
            if (best != null) return best;
            if ((this.searchCount++ + leader.getId()) % MOB_SEARCH_EVERY != 0) return null;
            AABB box = leader.getBoundingBox().inflate(SEARCH_RANGE, 6.0D, SEARCH_RANGE);
            for (Mob e : level.getEntitiesOfClass(Mob.class, box, e -> prey.test(leader, e))) {
                double d = e.distanceToSqr(leader);
                if (d < bestD && leader.hasLineOfSight(e)) {
                    best = e;
                    bestD = d;
                }
            }
            return best;
        } finally {
            searchNanos += System.nanoTime() - t0;
            searchCalls++;
        }
    }

    /** 送り込みで歩いているか(付いていかせない)。 */
    static boolean marching(Mob mob) {
        return mob.getPersistentData().contains(MARCH_KEY);
    }

    /** 群れのみんなを消す(ワールドが閉じたとき)。 */
    @net.neoforged.fml.common.EventBusSubscriber(modid = com.yayuy.overgrownvegetation.OvergrownVegetation.MODID, bus = net.neoforged.fml.common.EventBusSubscriber.Bus.GAME)
    public static final class Unload {
        private Unload() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
            if (event.getLevel() instanceof ServerLevel sl) BY_LEVEL.remove(sl);
        }
    }


    // ------------------------------------------------------------------ Goal

    /**
     * 後続: 相手のいない間、先導役のまわりへゆるくまとまって付いていく【仮】。離れているほど急ぐ。
     * 10 tick おき(個体ごとにずらす)に行き先を決め、遠ければ経路、近ければ直に舵を切る(経路の計算を減らす)。
     */
    static final class FollowGoal extends Goal {
        private final SmallCrawler mob;
        private int timer;

        FollowGoal(SmallCrawler mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            CrawlerPack p = this.mob.pack();
            return p != null && p.target == null && p.leader() != this.mob && !marching(this.mob) && this.mob.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (--this.timer > 0) return;
            this.timer = 10;
            CrawlerPack p = this.mob.pack();
            if (p == null) return;
            Mob l = p.leader();
            double slack = 1.5D + Math.sqrt(p.size()) * 0.6D;   // 群れが大きいほどゆるく
            double a = (this.mob.getId() * 2.399963D) % (Math.PI * 2.0D);   // 個体ごとに先導役のまわりの向きを散らす
            double r = slack * 0.7D;
            double gx = l.getX() + Math.cos(a) * r, gz = l.getZ() + Math.sin(a) * r;
            double dx = gx - this.mob.getX(), dz = gz - this.mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < slack * 0.5D) {
                this.mob.getNavigation().stop();
                return;
            }
            double speed = d > 8.0D ? 1.0D : this.mob.strollSpeed() * 1.3D;
            if (d > 6.0D || Math.abs(l.getY() - this.mob.getY()) > 1.2D) {
                this.mob.getNavigation().moveTo(gx, l.getY(), gz, speed);
            } else {
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(gx, l.getY(), gz, speed);
                this.timer = 3;
            }
        }
    }

    /**
     * 相手へ走る【仮】: 遠ければ経路(10 tick おき)、{@link #CLOSE} 以内では相手の足元を囲む位置(個体ごとの向き・半径 {@link #RING})へ毎 tick 直に舵を切る。
     * 相手を囲んだまま走り回り、噛める間合いに入った個体が噛む(噛むのは種の側)。
     */
    static final class ChaseGoal extends Goal {
        static final double CLOSE = 4.0D, RING = 0.7D;
        private final SmallCrawler mob;
        private int repath;

        ChaseGoal(SmallCrawler mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = this.mob.getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            this.repath = 0;
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity t = this.mob.getTarget();
            if (t == null) return;
            double dx = t.getX() - this.mob.getX(), dz = t.getZ() - this.mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= CLOSE && Math.abs(t.getY() - this.mob.getY()) < 1.5D) {
                this.mob.getNavigation().stop();
                net.minecraft.world.phys.Vec3 lunge = this.mob.lungePoint(t);
                if (lunge != null) {   // 噛む間: 種が決めた所へ(口先が相手に乗る間合い。その場で止まって向きを合わせることもある)
                    this.mob.getMoveControl().setWantedPosition(lunge.x, lunge.y, lunge.z, this.mob.rushSpeed());
                    return;
                }
                double a = (this.mob.getId() * 2.399963D) % (Math.PI * 2.0D);
                double r = RING + t.getBbWidth() * 0.5D;
                this.mob.getMoveControl().setWantedPosition(t.getX() + Math.cos(a) * r, t.getY(), t.getZ() + Math.sin(a) * r, this.mob.rushSpeed());
                return;
            }
            if (--this.repath > 0 && !this.mob.getNavigation().isDone()) return;
            this.repath = 10 + (this.mob.getId() & 3);
            this.mob.getNavigation().moveTo(t, this.mob.rushSpeed());
        }
    }


    // ------------------------------------------------------------------ 外から作る群れ(2026-11-17 enemy-spawns)

    /**
     * 外から入った個体(SmallCrawler の自前の群れを使わない個体: アルボラ・ストロビラ・ヴェシカリアなど)と、その群れ。
     * 個体は保存データの {@link #EXTERNAL_KEY} に群れの番号を持ち、読み込み直したら {@link #rejoin} で組み直す。
     */
    private static final Map<Mob, CrawlerPack> EXTERNAL = new WeakHashMap<>();
    public static final String EXTERNAL_KEY = "ov_pack";

    /**
     * 湧かせた個体から群れを作る(並びの頭が先導役)。種が混ざっていてもよい(混ぜた群れは近くの群れへ自分から合わさらない)。
     * 自前の群れを使う SmallCrawler(グレガリア)はここに入れない(同じ種の群れへ自分で集まる)。
     */
    public static CrawlerPack form(ServerLevel level, List<? extends Mob> mobs) {
        CrawlerPack p = obtain(level, UUID.randomUUID(), null);
        for (Mob m : mobs) {
            p.add(m);
            EXTERNAL.put(m, p);
            m.getPersistentData().putUUID(EXTERNAL_KEY, p.id);
        }
        if (!p.members.isEmpty()) p.lastLeader = p.members.get(0);
        return p;
    }

    /** 保存から戻った外の個体を、同じ番号の群れへ入れ直す(入る種を湧かせる側が EntityJoinLevelEvent から呼ぶ)。 */
    public static void rejoin(ServerLevel level, Mob mob) {
        if (!mob.getPersistentData().hasUUID(EXTERNAL_KEY)) return;
        CrawlerPack p = obtain(level, mob.getPersistentData().getUUID(EXTERNAL_KEY), null);
        p.add(mob);
        EXTERNAL.put(mob, p);
    }


    /** 外の群れに入っているか(O(1)。自前の群れを止める判定に使う)。 */
    static boolean inExternal(Mob mob) {
        return EXTERNAL.containsKey(mob);
    }

    /** 外の個体の群れ(無ければ null)。並びからの出入りは EXTERNAL と一緒に直すので、ここは引くだけ(群れが大きくても O(1)。11-17 深夜)。 */
    @Nullable
    public static CrawlerPack externalOf(Mob mob) {
        CrawlerPack p = EXTERNAL.get(mob);
        if (p == null) return null;
        if (mob.level() instanceof ServerLevel sl) p.prune(sl);
        return EXTERNAL.get(mob);
    }

    // ------------------------------------------------------------------ 司令塔のまとめ(2026-11-17 深夜 ユーザー決定)

    /** 外の群れに 1 体足す(ほかの外の群れ・自前の群れからは抜ける)。前衛が来て今の先導役が前衛でなければ、先導役を替える。 */
    public static void addExternal(ServerLevel level, CrawlerPack p, Mob m) {
        CrawlerPack old = EXTERNAL.get(m);
        if (old == p) return;
        if (old != null) old.remove(level, m);
        if (m instanceof SmallCrawler sc) sc.leaveOwnPack(level);
        p.add(m);
        EXTERNAL.put(m, p);
        m.getPersistentData().putUUID(EXTERNAL_KEY, p.id);
        if (p.members.size() == 1) p.lastLeader = m;
        else if (rank(m) < rank(p.leader())) p.electLeader();
    }

    /** 外の群れを作る(並びは役割の順 = 前衛が頭)。 */
    public static CrawlerPack formExternal(ServerLevel level, List<? extends Mob> mobs) {
        CrawlerPack p = obtain(level, UUID.randomUUID(), null);
        List<Mob> sorted = new ArrayList<>(mobs);
        sorted.sort(java.util.Comparator.comparingInt(CrawlerPack::rank));
        for (Mob m : sorted) addExternal(level, p, m);
        return p;
    }

    /** 外の群れから抜ける(腰を据えた・根を下ろした・入れない状態になった)。 */
    public static void leaveExternal(ServerLevel level, Mob m) {
        CrawlerPack p = EXTERNAL.get(m);
        if (p != null) p.remove(level, m);
        EXTERNAL.remove(m);
        m.getPersistentData().remove(EXTERNAL_KEY);
    }

    /** 2 つの外の群れを 1 つに(小さい方を大きい方へ移す)。大きい方を返す。 */
    public static CrawlerPack mergeExternal(ServerLevel level, CrawlerPack a, CrawlerPack b) {
        if (a == b) return a;
        CrawlerPack big = a.size() >= b.size() ? a : b, small = big == a ? b : a;
        for (Mob m : new ArrayList<>(small.members)) addExternal(level, big, m);
        return big;
    }

    /** 役割の順位(前衛 0 … 支援 3。役割の無い種は 4)。先導役は小さい方から。 */
    static int rank(Mob m) {
        com.yayuy.overgrownvegetation.spawn.SpawnTable.Role r = com.yayuy.overgrownvegetation.spawn.DirectorHooks.roleOf(m.getType());
        return r == null ? 4 : r.ordinal();
    }

    /** 役割の数(前衛・後衛・かく乱・支援。役割の無い種は数えない)。 */
    public int[] roleCounts() {
        int[] c = new int[4];
        for (Mob m : this.members) {
            int r = rank(m);
            if (r < 4) c[r]++;
        }
        return c;
    }

    /**
     * 先導役を選び直す(先導役が倒れた・前衛が入った)。役割の順位がいちばん小さい生きた個体を並びの頭へ入れ替えるだけ(O(n) を 1 回。
     * 後続は先導役を覚えず群れの頭を見るので、後続ごとの処理は無い。11-17 深夜 ユーザー「先導役の変更の処理は最適化しておきたい」)。
     */
    void electLeader() {
        if (this.members.isEmpty()) return;
        int best = 0, bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < this.members.size(); i++) {
            int r = rank(this.members.get(i));
            if (r < bestRank) {
                best = i;
                bestRank = r;
                if (r == 0) break;
            }
        }
        if (best != 0) java.util.Collections.swap(this.members, 0, best);
        this.lastLeader = this.members.get(0);
        leaderChanges++;
    }

    /** 外の群れの、前の先導役(替わったかを見る)。 */
    @Nullable
    private Mob lastLeader;
    /** 確かめ用: 先導役を選び直した回数。 */
    public static long leaderChanges;

    /** 外の群れで誰かが狙っているプレイヤー(群れの全員の相手にする)。同じ tick は使い回す。 */
    @Nullable
    LivingEntity sharedTarget(ServerLevel level) {
        long now = level.getGameTime();
        if (now == this.sharedAt) return this.shared;
        this.sharedAt = now;
        this.shared = null;
        for (Mob m : this.members) {
            LivingEntity t = m.getTarget();
            if (t instanceof net.minecraft.world.entity.player.Player pl && pl.isAlive() && !pl.isCreative() && !pl.isSpectator()) {
                this.shared = t;
                break;
            }
        }
        return this.shared;
    }

    private long sharedAt = Long.MIN_VALUE;
    @Nullable
    private LivingEntity shared;

    /** 確かめ用の 1 行: 外から作った群れの数・後続の数・先導役から 12 ブロック以内の後続・距離・いちばん大きい群れ・先導役の交代・後続の経路と舵の数。 */
    public static String externalStats() {
        java.util.Set<CrawlerPack> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int followers = 0, near = 0, biggest = 0;
        double sum = 0.0D, max = 0.0D;
        for (CrawlerPack p : new ArrayList<>(EXTERNAL.values())) {
            if (!seen.add(p) || p.members.isEmpty()) continue;
            biggest = Math.max(biggest, p.size());
            Mob l = p.leader();
            for (Mob m : p.members) {
                if (m == l || !m.isAlive()) continue;
                double d = m.distanceTo(l);
                followers++;
                sum += d;
                max = Math.max(max, d);
                if (d <= 12.0D) near++;
            }
        }
        return String.format(java.util.Locale.ROOT, "外の群れ %d・後続 %d(先導役から 12 以内 %d)・距離 平均 %.1f・最大 %.1f・いちばん大きい群れ %d・先導役の交代 %d・後続の経路 %d・真っ直ぐ %d",
                seen.size(), followers, near, followers > 0 ? sum / followers : 0.0D, max, biggest, leaderChanges, pathCalls, steerCalls);
    }

    /** 群れに入っていてはいけない状態か(腰を据えた・根付いたゲスタトリクス)。 */
    public static boolean anchored(Mob m) {
        return m instanceof com.yayuy.overgrownvegetation.entity.GestatrixEntity g && g.state() != com.yayuy.overgrownvegetation.entity.GestatrixEntity.ROAM;
    }

    /**
     * 外の群れの後続: 先導役の向きに合わせた役割ごとの持ち場(前衛は前・後衛は後ろ・かく乱は横・支援は後ろの真ん中)へ付いていく。
     * <b>なるべく経路を探さない</b>: 持ち場が近く段差が小さいうちは移動の舵(MoveControl)で真っ直ぐ寄り、遠い({@link #PATH_FAR} より)・段差がある
     * ときだけ経路を引く({@link #REPATH} tick おき)。経路を探すのは先導役(と遠く離れた個体)だけ(11-17 深夜 ユーザー「探索経路が一番重い」)。
     * 群れの誰かがプレイヤーを狙ったら、狙っていない個体にも同じ相手を渡す(そこからは各種の狩りの Goal)。拠点へ歩いている間・自分が先導役の間は付いていかない。
     */
    public static final class ExternalFollowGoal extends Goal {
        static final double PATH_FAR = 10.0D, STEP_DY = 1.5D;
        static final int STEER_EVERY = 4, REPATH = 30;
        private final net.minecraft.world.entity.PathfinderMob mob;
        private int timer;
        private int repath;

        public ExternalFollowGoal(net.minecraft.world.entity.PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (marching(this.mob)) return false;
            CrawlerPack p = externalOf(this.mob);
            if (p == null || p.members.isEmpty()) return false;
            if (!(this.mob.level() instanceof ServerLevel sl)) return false;
            if (anchored(this.mob)) {
                leaveExternal(sl, this.mob);
                return false;
            }
            if (this.mob.getTarget() == null) {
                LivingEntity t = p.sharedTarget(sl);
                if (t != null && this.mob.canAttack(t)) {
                    this.mob.setTarget(t);
                    return false;
                }
            }
            return this.mob.getTarget() == null && p.leader() != this.mob;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void start() {
            this.timer = 0;
            this.repath = 0;
        }

        @Override
        public void stop() {
            if (this.mob instanceof com.yayuy.overgrownvegetation.entity.ArboraEntity a) a.mover().stop();
            else this.mob.getNavigation().stop();
        }

        /** 持ち場(先導役の向きの前・右に、役割と個体ごとのずれを掛ける)。{x, z} を返す。 */
        static double[] slot(Mob l, Mob mob, int size) {
            double s = 1.6D + Math.sqrt(size) * 0.35D;
            int h = (mob.getId() * 0x9E3779B1) >>> 8;
            double fwd, side;
            switch (rank(mob)) {
                case 0 -> {
                    fwd = 1.5D * s;
                    side = ((h % 5) - 2) * s;
                }
                case 1 -> {
                    fwd = -2.5D * s - (h % 2) * s;
                    side = ((h % 4) - 1.5D) * s;
                }
                case 3 -> {
                    fwd = -1.5D * s;
                    side = ((h % 3) - 1) * 0.8D * s;
                }
                default -> {
                    fwd = ((h % 3) - 1) * s;
                    side = ((h & 1) == 0 ? 1 : -1) * (2.5D + (h >> 1) % 2) * s;
                }
            }
            float yaw = l.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
            double fx = -net.minecraft.util.Mth.sin(yaw), fz = net.minecraft.util.Mth.cos(yaw);
            return new double[]{l.getX() + fx * fwd - fz * side, l.getZ() + fz * fwd + fx * side};
        }

        @Override
        public void tick() {
            if (--this.timer > 0) return;
            this.timer = STEER_EVERY;
            CrawlerPack p = externalOf(this.mob);
            if (p == null || p.members.isEmpty()) return;
            Mob l = p.leader();
            double[] g = slot(l, this.mob, p.size());
            double dx = g[0] - this.mob.getX(), dz = g[1] - this.mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            double dy = Math.abs(l.getY() - this.mob.getY());
            // アルボラは自前の歩き(ClingMover)で動く。ナビゲーションに渡しても動かない(11-17 クライアントで群れが散ったので直した)
            if (this.mob instanceof com.yayuy.overgrownvegetation.entity.ArboraEntity arb) {
                if (d < 1.5D) {
                    arb.mover().stop();
                } else if (--this.repath <= 0) {
                    this.repath = 3;
                    arb.mover().moveTo(new net.minecraft.world.phys.Vec3(g[0], l.getY(), g[1]), d > 8.0D ? 1.0D : 0.8D, 1.0D);
                }
                return;
            }
            if (d < 1.2D) {
                this.mob.getNavigation().stop();
                return;
            }
            double speed = d > 6.0D ? 1.0D : 0.8D;
            if (d > PATH_FAR || dy > STEP_DY) {
                // 遠い・段差: 経路(引き直しは間をあける)
                if (--this.repath <= 0 || this.mob.getNavigation().isDone()) {
                    this.repath = REPATH / STEER_EVERY;
                    this.mob.getNavigation().moveTo(g[0], l.getY(), g[1], speed);
                    pathCalls++;
                }
                return;
            }
            // 近い: 経路なしで真っ直ぐ寄る
            this.mob.getNavigation().stop();
            this.mob.getMoveControl().setWantedPosition(g[0], this.mob.getY(), g[1], speed);
            steerCalls++;
        }
    }

    /** 確かめ用: 後続が経路を引いた回数・真っ直ぐ寄った回数。 */
    public static long pathCalls, steerCalls;
}
