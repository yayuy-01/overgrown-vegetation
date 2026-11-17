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
        if (this.members.isEmpty()) packs(level).remove(this.id);
    }

    /** 最後に外した tick(群れの 1 tick に 1 回だけ。前は全員が毎 tick 全員を見ていた = 群れの大きさの 2 乗。11-16 見直し 第 3 便)。 */
    private long prunedAt = Long.MIN_VALUE;

    /** 死んだ・消えた個体を外す(同じ tick の 2 回目からは何もしない)。 */
    void prune(ServerLevel level) {
        long now = level.getGameTime();
        if (now == this.prunedAt) return;
        this.prunedAt = now;
        this.members.removeIf(m -> !m.isAlive() || m.isRemoved());
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
        return p;
    }

    /** 保存から戻った外の個体を、同じ番号の群れへ入れ直す(入る種を湧かせる側が EntityJoinLevelEvent から呼ぶ)。 */
    public static void rejoin(ServerLevel level, Mob mob) {
        if (!mob.getPersistentData().hasUUID(EXTERNAL_KEY)) return;
        CrawlerPack p = obtain(level, mob.getPersistentData().getUUID(EXTERNAL_KEY), null);
        p.add(mob);
        EXTERNAL.put(mob, p);
    }

    /** 外の個体の群れ(無ければ null)。 */
    @Nullable
    public static CrawlerPack externalOf(Mob mob) {
        CrawlerPack p = EXTERNAL.get(mob);
        if (p == null) return null;
        if (mob.level() instanceof ServerLevel sl) p.prune(sl);
        if (!p.members.contains(mob)) {
            EXTERNAL.remove(mob);
            return null;
        }
        return p;
    }

    /**
     * 外の個体の後続: 先導役のまわりへゆるくまとまって付いていく({@link FollowGoal} と同じ考え。種のクラスに手を入れず、湧かせる側が入るときに足す)。
     * 相手を追っている間・拠点へ歩いている間・自分が先導役の間は付いていかない。箱の検索はしない(群れの一覧の頭を見るだけ)。
     */
    public static final class ExternalFollowGoal extends Goal {
        private final net.minecraft.world.entity.PathfinderMob mob;
        private int timer;

        public ExternalFollowGoal(net.minecraft.world.entity.PathfinderMob mob) {
            this.mob = mob;
            this.setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (this.mob.getTarget() != null || marching(this.mob)) return false;
            CrawlerPack p = externalOf(this.mob);
            return p != null && !p.members.isEmpty() && p.leader() != this.mob;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (--this.timer > 0) return;
            this.timer = 10 + (this.mob.getId() & 3);
            CrawlerPack p = externalOf(this.mob);
            if (p == null || p.members.isEmpty()) return;
            Mob l = p.leader();
            double slack = 2.0D + Math.sqrt(p.size()) * 0.8D;
            double a = (this.mob.getId() * 2.399963D) % (Math.PI * 2.0D);
            double gx = l.getX() + Math.cos(a) * slack * 0.7D, gz = l.getZ() + Math.sin(a) * slack * 0.7D;
            double dx = gx - this.mob.getX(), dz = gz - this.mob.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < slack * 0.5D) {
                this.mob.getNavigation().stop();
                return;
            }
            this.mob.getNavigation().moveTo(gx, l.getY(), gz, d > 8.0D ? 1.0D : 0.8D);
        }
    }
}
