package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirectorTable;
import com.yayuy.overgrownvegetation.director.ForestDirector.Route;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 段階 0 の G1 の 1 体が持つ「司令塔との約束」(2026-11-13、司令塔 第 1 段)。
 *
 * <ul>
 *   <li><b>見に来る時刻</b>(D-5): 起き上がった(世界に出た)ときに、数分〜数日のどこかに 1 つ決める。世界の時計(絶対のゲーム時刻)で持つので、
 *   読み込まれていない間に過ぎた個体は、次に読み込まれた tick に見てもらう。司令塔の側に個体の一覧は持たない。</li>
 *   <li>見に来たら {@link ForestDirector#visit} が道を決める。何もしなければ、後でまた見に来る。</li>
 *   <li><b>進化</b>: 前兆(D-11。立ち止まって震え、植物が膨らむ = {@code client/G1OmenRig})を {@code evolveOmenTicks} 流してから、
 *   同じ場所で 1 段目({@link DevelopedParasitized})に入れ替え、{@code evolve_in} を流させる。前兆の間に倒せば、何も起きない。</li>
 * </ul>
 * ゴーレム・ウォーデンは進化体が無いので持たない(11-13 ユーザー「今はまだ用意できてないから司令塔は見に来ない」)。1 段目も持たない。
 */
public final class G1Fate {

    /** 前兆の種類(同期データ {@code Omen} の値)。 */
    public static final int OMEN_NONE = 0;
    public static final int OMEN_EVOLVE = 1;
    /** 根を下ろす(根付く直前。立ち止まって体が沈む)。 */
    public static final int OMEN_ROOT = 2;

    /**
     * 根付きの姿(種 → Mob の型)。組み込んだ種だけここに登録する(区切り 2 以降)。登録の無い種は根付きを選べない
     * (アニメの無い姿は組み込まない、11-13 ユーザー)。
     */
    private static final java.util.Map<com.yayuy.overgrownvegetation.corpse.G1Species,
            java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> ROOTED =
            new java.util.EnumMap<>(com.yayuy.overgrownvegetation.corpse.G1Species.class);

    public static void registerRooted(com.yayuy.overgrownvegetation.corpse.G1Species species,
                                      java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        ROOTED.put(species, type);
    }

    /** 根付きの姿の側が持つ口: 入れ替わった直後に「生える」(emerge)を始める。 */
    public interface Emerging {
        void startEmerge();
    }

    /** 入れ替える場所が狭いとき、前兆を延ばして待つ長さ(tick)と回数【仮】。それでも空かなければ、やめて予算を戻す。 */
    private static final int NO_ROOM_WAIT = 20;
    private static final int NO_ROOM_TRIES = 5;
    /** 根付き・苗床: 入らなかったら歩き直す長さ(歩いた tick)と、試す場所の数【仮】。 */
    private static final int NO_ROOM_WALK = 100;
    private static final int NO_ROOM_SITES = 6;
    /** 見に来たが今は変われない(起き上がりの途中・乗っている・宙にいる)ときの待ち(tick)。 */
    private static final int BUSY_WAIT = 100;

    private final AbstractParasitizedMonster mob;
    private long bornAt = -1L;
    private long nextVisit = -1L;
    private Route route = Route.NONE;
    private int omenLeft;
    private int noRoomTries;
    /** 入らなかった所から、これだけ歩くまでは根を下ろし直さない(歩いた tick)。 */
    private int retryAfterWalked;
    /** 根付き: 森の縁からこれだけ離れたら根を下ろす(ブロック。決まったときに 48〜96 から 1 つ)。 */
    private double awayWanted;
    /** 根付き: 森から離れて歩いた tick(読み込まれている間だけ進む)。 */
    private int walkedTicks;
    /** 司令塔が予算を払って決めた道か(コマンドで決めた道は払っていない = やめても戻さない)。 */
    private boolean paid;
    /** 払った額(段の強さで割った値段。変われなかったら、これを戻す)。 */
    private double paidPrice;
    /** テスト用の差し替え(森の見え方・根付きの姿)。保存しない。 */
    @Nullable
    private ForestEdge forestOverride;
    @Nullable
    private java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> rootedOverride;

    /** 偵察の使い(第 2 段 区切り 2)。 */
    private final G1Scout scout;

    public G1Fate(AbstractParasitizedMonster mob) {
        this.mob = mob;
        this.scout = new G1Scout(mob);
    }

    public G1Scout scout() {
        return scout;
    }

    /** この個体の種の 1 段目。無ければ null(ゴーレム・ウォーデン、1 段目自身)。 */
    @Nullable
    public static G1Developed developedOf(AbstractParasitizedMonster mob) {
        if (mob instanceof DevelopedParasitized) return null;
        for (G1Developed d : G1Developed.values()) {
            if (d.base() == mob.species()) return d;
        }
        return null;
    }

    /** 最初に見に来るまでの長さ(tick)。最小〜最大の間で対数に一様(数分の個体も、数日の個体もいる)。 */
    public static int firstDelay(DirectorTable t, RandomSource random) {
        double lo = Math.log(t.firstVisitMinTicks);
        double hi = Math.log(t.firstVisitMaxTicks);
        return (int) Math.round(Math.exp(lo + (hi - lo) * random.nextDouble()));
    }

    private static int revisitDelay(DirectorTable t, RandomSource random) {
        return t.revisitMinTicks + random.nextInt(t.revisitMaxTicks - t.revisitMinTicks + 1);
    }

    /** 最後に殴った・殴られたときから、これだけたつまでは「戦っている最中」(tick)【仮】。 */
    public static final int COMBAT_QUIET_TICKS = 200;

    /**
     * 戦っている最中か(11-13 ユーザー決定「戦っている最中は進化しないように」。決め方は【仮】): 生きた標的がいる、または
     * 最後に殴った・殴られたときから {@link #COMBAT_QUIET_TICKS}(10 秒)たっていない。この間は司令塔は見に来ず、5 秒おきに見直す
     * (戦いが終わったら見に来る)。進化だけでなく根付きも同じ扱い【仮】。コマンドで決めた道はここを通らない。
     */
    public static boolean inCombat(AbstractParasitizedMonster mob) {
        net.minecraft.world.entity.LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) return true;
        if (mob.combatBy() != null && mob.tickCount - mob.combatByAt() < COMBAT_QUIET_TICKS) return true;
        return mob.getLastHurtMob() != null && mob.tickCount - mob.getLastHurtMobTimestamp() < COMBAT_QUIET_TICKS;
    }

    // ---------------------------------------------------------------- 毎 tick(サーバー)

    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) return;
        DirectorTable t = DesignData.director();
        if (!t.enabled) return;
        long now = level.getGameTime();
        if (bornAt < 0L) bornAt = now;
        if (nextVisit < 0L) nextVisit = now + firstDelay(t, mob.getRandom());
        if (route == Route.EVOLVE) {
            // 震えの音(2.4 秒)は前兆の頭から 10 tick 遅らせると、終わりが前兆(60 tick)に合う。入り切らずに待つ間は鳴らし直さない。音量【仮】
            if (omenLeft == t.evolveOmenTicks - 10 + 1 && noRoomTries == 0) {
                mob.playSound(com.yayuy.overgrownvegetation.registry.ModSounds.G1_EVOLVE_OMEN.get(), 1.0F, 1.0F);
            }
            if (--omenLeft <= 0) finishEvolve(level);
            return;
        }
        if (route == Route.ROOTED || route == Route.NURSERY) {
            tickRooted(level, t);
            return;
        }
        if (scout.active()) {
            // 偵察の間は見に来ない(使いが済んでから)
            scout.tick(level);
            if (nextVisit < now + BUSY_WAIT) nextVisit = now + BUSY_WAIT;
            return;
        }
        if (now < nextVisit) return;
        if (ForestDirector.hoarding()) {
            // 森が貯めている間は、見に来ても道を選ばない(第 3 段)。貯め終えたらまた見に来る
            nextVisit = now + BUSY_WAIT * 6;
            return;
        }
        if (mob.isRising() || mob.isPassenger() || mob.isVehicle() || !mob.onGround() || inCombat(mob)) {
            nextVisit = now + BUSY_WAIT;
            return;
        }
        ForestDirector.Decision d = ForestDirector.get(level).visit(level, mob, bornAt);
        paid = d.route() != Route.NONE;
        paidPrice = paid ? ForestDirector.get(level).lastPrice() : 0.0D;
        if (d.route() == Route.EVOLVE) {
            beginEvolve();
            // 認識した拠点のそばで進化した個体は、拠点へ向かう(D-8。距離は evolveBaseBlocks)
            BaseMemory.Base near = ForestDirector.nearestRecognized(level, mob.getX(), mob.getZ());
            if (near != null) mob.march().begin(near);
        } else if (d.route() == Route.ROOTED) {
            beginRooted();
        } else if (d.route() == Route.NURSERY) {
            beginNursery();
        } else {
            nextVisit = now + revisitDelay(t, mob.getRandom());
        }
    }

    /** 司令塔が予算を払って決めた道にする(送り込みが進化させたとき。やめたら予算を戻す)。 */
    void markPaid(double price) {
        paid = true;
        paidPrice = price;
    }

    /** 進化の前兆を始める(予算は呼ぶ側が払ってある)。 */
    public void beginEvolve() {
        scout.cancel();
        route = Route.EVOLVE;
        omenLeft = DesignData.director().evolveOmenTicks;
        noRoomTries = 0;
        mob.getNavigation().stop();
        mob.setOmen(OMEN_EVOLVE);
    }

    private void finishEvolve(ServerLevel level) {
        G1Developed d = developedOf(mob);
        DevelopedParasitized next = d == null ? null : ModG1.developed(d).get().create(level);
        if (next == null) {
            cancel(level);
            return;
        }
        next.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), mob.getXRot());
        if (!level.noCollision(next)) {
            // 1 段目は段階 0 より体が大きい種がある。壁際で入らなければ少し待ち、それでも駄目ならやめる
            if (++noRoomTries <= NO_ROOM_TRIES) {
                omenLeft = NO_ROOM_WAIT;
            } else {
                cancel(level);
            }
            return;
        }
        next.yBodyRot = mob.yBodyRot;
        next.yBodyRotO = mob.yBodyRot;
        next.yHeadRot = mob.yHeadRot;
        next.yHeadRotO = mob.yHeadRot;
        if (mob.hasCustomName()) {
            next.setCustomName(mob.getCustomName());
            next.setCustomNameVisible(mob.isCustomNameVisible());
        }
        if (mob.isPersistenceRequired()) next.setPersistenceRequired();
        next.setTarget(mob.getTarget());
        next.march().copyFrom(mob.march()); // 拠点へ送られる途中の個体は、進化した後も向かう(第 2 段)
        // 襲撃の印(大きな襲撃・貯めた襲撃。11-16)は引き継ぐ。縁・地下の印は引き継がない(進化体は縁の上限にも片付けにも入れない。見直し ②4)
        for (String tag : mob.getTags()) {
            if (tag.startsWith("ov_") && !com.yayuy.overgrownvegetation.spawn.EnemySpawns.isSpawnTag(tag)) next.addTag(tag);
        }
        level.addFreshEntity(next);
        next.startEvolveIn();
        mob.discard();
    }

    // ---------------------------------------------------------------- 根付き(区切り 2): 森から離れて歩く → 根を下ろす → 入れ替え

    /** 根付きの姿の型。まだ組み込んでいない種は null(根付きを選べない)。 */
    @Nullable
    public java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> rootedForm() {
        if (noRootedForTests) return null;
        return rootedOverride != null ? rootedOverride : ROOTED.get(mob.species());
    }

    /** 苗床の姿(種 → Mob の型)。登録の無い種は苗床を選べない。 */
    private static final java.util.Map<com.yayuy.overgrownvegetation.corpse.G1Species,
            java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> NURSERY =
            new java.util.EnumMap<>(com.yayuy.overgrownvegetation.corpse.G1Species.class);

    public static void registerNursery(com.yayuy.overgrownvegetation.corpse.G1Species species,
                                       java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        NURSERY.put(species, type);
    }

    /** 苗床の姿の型。まだ組み込んでいない種は null(苗床を選べない)。 */
    @Nullable
    public java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> nurseryForm() {
        if (noRootedForTests) return null;
        return NURSERY.get(mob.species());
    }

    /** 森の見え方(本物は過繁茂のチャンクの台帳)。 */
    public ForestEdge forest(ServerLevel level) {
        return forestOverride != null ? forestOverride : ForestEdge.of(level);
    }

    /** 根付きに決まった: 森の縁から離れる長さを決めて歩き始める(予算は呼ぶ側が払ってある)。歩くのは {@link G1WalkAwayGoal}。 */
    public void beginRooted() {
        beginSettle(Route.ROOTED);
    }

    /** 苗床に決まった: 根付きと同じく、森から離れて歩いてから変わる(D-15。苗床に湿った暗がりは要らない = D-17)。 */
    public void beginNursery() {
        beginSettle(Route.NURSERY);
    }

    private void beginSettle(Route to) {
        scout.cancel();
        DirectorTable t = DesignData.director();
        route = to;
        awayWanted = t.rootAwayMinBlocks + mob.getRandom().nextDouble() * (t.rootAwayMaxBlocks - t.rootAwayMinBlocks);
        walkedTicks = 0;
        omenLeft = 0;
        noRoomTries = 0;
        retryAfterWalked = 0;
    }

    /**
     * 根付き・苗床が歩く向き。苗床は森から離れるだけ(11-14 ユーザー決定 Q1 = 1「苗床は拠点を見ない」)。
     * 根付きは、覚えた拠点が近くにあれば「森から離れつつ拠点へ寄る」(D-16。拠点と森の間に立って、プレイヤーを妨害しつつほかの G1 を支える)。
     * 拠点を覚えていなければ、ただ森から離れる。
     */
    @Nullable
    public Vec3 settleDirection(ServerLevel level) {
        Vec3 away = forest(level).away(mob.position());
        if (route != Route.ROOTED) return away;
        BaseMemory.Base b = settleBase(level);
        // 弱い所を突く(第 3 段): 守りの薄い方角が分かっていれば、拠点の真ん中ではなく弱い方角の輪の上の点へ寄る
        // (回り込む割合 × 根付きの段の強さ。個体ごとに決まった当たり外れ)
        Vec3 toward = b == null ? null : new Vec3(b.centerX(), mob.getY(), b.centerZ());
        if (b != null && b.weakSector() >= 0 && flankLot() < DesignData.director().base.flankShare * DirectorGates.strength(level, DirectorGates.Gate.ROOTED)) {
            Vec3 w = b.weakPoint(DesignData.director().base.flankRadius, mob.getY());
            if (w != null) toward = w;
        }
        Vec3 dir = blendTowardBase(away, mob.position(), toward, DesignData.director().base);
        // 学ぶ(11-14 ユーザー): 遠距離の根付き(スケルトン・エンダーマンなど)は、危ない所から離れて根付く
        KillMemory.Danger d = rangedDanger(level);
        return d == null ? dir : awayFromDanger(dir, mob.position(), new Vec3(d.x(), mob.getY(), d.z()));
    }

    /** 個体ごとに決まった 0〜1 の当たりくじ(回り込むか)。 */
    private double flankLot() {
        return (mob.getUUID().getLeastSignificantBits() & 0xFFFFL) / 65536.0D;
    }

    /** 遠距離の根付きにとって近すぎる危ない所(rangedKeepAwayBlocks の中)。遠距離でない種・危ない所が無ければ null。 */
    @Nullable
    private KillMemory.Danger rangedDanger(ServerLevel level) {
        if (route != Route.ROOTED || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
        var k = DesignData.director().kills;
        if (!k.isRanged(mob.species().key())) return null;
        return KillMemory.get(level.getServer()).nearest(mob.getX(), mob.getZ(), k.rangedKeepAwayBlocks, BaseMemory.today(level.getServer()));
    }

    /** 歩く向きに、危ない所から離れる向きを足す(純粋な計算)。 */
    public static Vec3 awayFromDanger(@Nullable Vec3 dir, Vec3 pos, Vec3 danger) {
        double dx = pos.x - danger.x, dz = pos.z - danger.z, len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6D) return dir;
        double x = (dir == null ? 0.0D : dir.x) + dx / len, z = (dir == null ? 0.0D : dir.z) + dz / len;
        double l = Math.sqrt(x * x + z * z);
        return l < 1.0E-9D ? dir : new Vec3(x / l, 0.0D, z / l);
    }

    /** 根付きが寄る拠点(覚えた段から。真ん中まで rootBaseBlocks 以内)。テストは差し替える。 */
    @Nullable
    private BaseMemory.Base settleBase(ServerLevel level) {
        if (baseOverride != null) return baseOverride;
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return null;
        return BaseMemory.get(level.getServer()).nearest(mob.getX(), mob.getZ(), DesignData.director().base.rootBaseBlocks);
    }

    /**
     * 森から離れる向きに、拠点へ寄る向きを混ぜる(純粋な計算)。拠点に rootBaseMinBlocks より近ければ、拠点から離れる向きを混ぜる
     * (拠点の中には根付かない =「拠点と森のどちらからも少し離れた所」11-13 ユーザー)。
     */
    @Nullable
    public static Vec3 blendTowardBase(@Nullable Vec3 away, Vec3 pos, @Nullable Vec3 base, com.yayuy.overgrownvegetation.data.BaseMemoryTable t) {
        if (base == null) return away;
        double dx = base.x - pos.x, dz = base.z - pos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-6D) return away;
        double tx = dx / dist, tz = dz / dist;
        double ax = away == null ? 0.0D : away.x, az = away == null ? 0.0D : away.z;
        double w = dist < t.rootBaseMinBlocks ? -1.0D : t.rootTowardBase;
        double x = ax + w * tx, z = az + w * tz;
        double len = Math.sqrt(x * x + z * z);
        return len < 1.0E-9D ? away : new Vec3(x / len, 0.0D, z / len);
    }

    /** 根付き: 拠点に近すぎて、ここには根付かない。 */
    private boolean tooCloseToBase(ServerLevel level) {
        if (route != Route.ROOTED) return false;
        BaseMemory.Base b = settleBase(level);
        if (b == null) return false;
        double dx = b.centerX() - mob.getX(), dz = b.centerZ() - mob.getZ();
        double min = DesignData.director().base.rootBaseMinBlocks;
        return dx * dx + dz * dz < min * min;
    }

    @Nullable
    private BaseMemory.Base baseOverride;

    public void setBaseForTests(@Nullable BaseMemory.Base b) {
        this.baseOverride = b;
    }

    /** 森から離れて歩いている最中か(根を下ろす前)。 */
    public boolean walkingAway() {
        return (route == Route.ROOTED || route == Route.NURSERY) && mob.omen() == OMEN_NONE;
    }

    /**
     * 森から離れて歩いている途中で殴られ、殴り返している最中か(11-13 ユーザー決定「殴り返すようにしてほしい」)。
     * 決め方は【仮】: 殴ってきた相手が生きていて、最後に殴られてから {@link #COMBAT_QUIET_TICKS}(10 秒)たっていない。
     * この間は {@link G1WalkAwayGoal} が止まり(3 段の 1 撃が殴ってきた相手を追う)、歩いた時間は数えず、根も下ろさない。
     * 相手が死ぬか 10 秒殴られなければ、また歩きに戻る。
     */
    public static boolean retaliating(AbstractParasitizedMonster mob) {
        net.minecraft.world.entity.LivingEntity by = mob.combatBy();
        return by != null && by.isAlive() && mob.tickCount - mob.combatByAt() < COMBAT_QUIET_TICKS;
    }

    private void tickRooted(ServerLevel level, DirectorTable t) {
        if (mob.omen() == OMEN_ROOT) {
            if (--omenLeft <= 0) finishRooted(level);
            return;
        }
        if (retaliating(mob)) return;
        walkedTicks++;
        boolean timeout = walkedTicks >= t.walkTimeoutTicks;
        if (!timeout && walkedTicks % 20 != 0) return;
        // 着いた(森の縁から決めた長さだけ離れた・森が見当たらない)か、歩く時間を使い切ったら、その場で根を下ろす
        if ((timeout || (forest(level).distance(mob.position()) >= awayWanted && !tooCloseToBase(level) && rangedDanger(level) == null)) && walkedTicks >= retryAfterWalked
                && mob.onGround() && !mob.isPassenger() && !mob.isVehicle()) {
            omenLeft = t.rootOmenTicks;
            mob.getNavigation().stop();
            mob.setOmen(OMEN_ROOT);
        }
    }

    private void finishRooted(ServerLevel level) {
        var form = route == Route.NURSERY ? nurseryForm() : rootedForm();
        net.minecraft.world.entity.Mob next = form == null ? null : form.get().create(level);
        if (next == null) {
            cancel(level);
            return;
        }
        next.moveTo(mob.getX(), mob.getY(), mob.getZ(), mob.getYRot(), 0.0F);
        if (!level.noCollision(next)) {
            // 根付き・苗床は体が大きい(柱は宿主の 2 倍ほどの高さ)。木の下・斜面で入らなければ、根を下ろすのをやめて少し歩き、
            // 別の所でやり直す(本物の地形で、4 体中 2 体がここで諦めていた。11-13)。何か所も駄目なら、やめて予算を戻す
            if (++noRoomTries <= NO_ROOM_SITES) {
                omenLeft = 0;
                mob.setOmen(OMEN_NONE);
                retryAfterWalked = walkedTicks + NO_ROOM_WALK;
            } else {
                cancel(level);
            }
            return;
        }
        next.yBodyRot = mob.yBodyRot;
        next.yBodyRotO = mob.yBodyRot;
        next.yHeadRot = mob.yBodyRot;
        next.yHeadRotO = mob.yBodyRot;
        if (mob.hasCustomName()) {
            next.setCustomName(mob.getCustomName());
            next.setCustomNameVisible(mob.isCustomNameVisible());
        }
        next.setPersistenceRequired();
        level.addFreshEntity(next);
        if (next instanceof Emerging e) e.startEmerge();
        mob.discard();
    }

    /** 変われなかった: 予算を戻し、後でまた見に来る。 */
    private void cancel(ServerLevel level) {
        if (route != Route.NONE && paid) {
            ForestDirector.get(level).refund(level, paidPrice > 0.0D ? paidPrice : ForestDirector.cost(DesignData.director(), route));
        }
        route = Route.NONE;
        omenLeft = 0;
        mob.setOmen(OMEN_NONE);
        nextVisit = level.getGameTime() + revisitDelay(DesignData.director(), mob.getRandom());
    }

    // ---------------------------------------------------------------- 見る口・確かめ用

    public Route route() {
        return route;
    }

    /** 次に見に来るゲーム時刻(まだ決めていなければ −1)。 */
    public long nextVisit() {
        return nextVisit;
    }

    public long bornAt() {
        return bornAt;
    }

    /** 見に来る時刻をこの時刻にする(確かめ用のコマンドとテスト)。 */
    public void setNextVisit(long gameTime) {
        nextVisit = gameTime;
    }

    // ---------------------------------------------------------------- 保存

    public void save(CompoundTag tag) {
        CompoundTag f = new CompoundTag();
        f.putLong("BornAt", bornAt);
        f.putLong("NextVisit", nextVisit);
        f.putString("Route", route.name());
        f.putInt("OmenLeft", omenLeft);
        f.putInt("Omen", mob.omen());
        f.putDouble("AwayWanted", awayWanted);
        f.putInt("Walked", walkedTicks);
        f.putBoolean("Paid", paid);
        f.putDouble("PaidPrice", paidPrice);
        scout.save(f);
        tag.put("Fate", f);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains("Fate")) return;
        CompoundTag f = tag.getCompound("Fate");
        bornAt = f.getLong("BornAt");
        nextVisit = f.getLong("NextVisit");
        route = Route.byName(f.getString("Route"));
        omenLeft = f.getInt("OmenLeft");
        awayWanted = f.getDouble("AwayWanted");
        walkedTicks = f.getInt("Walked");
        paid = f.getBoolean("Paid");
        paidPrice = f.getDouble("PaidPrice");
        scout.load(f);
        mob.setOmen(route == Route.EVOLVE ? OMEN_EVOLVE : route == Route.ROOTED || route == Route.NURSERY ? f.getInt("Omen") : OMEN_NONE);
    }

    // ---------------------------------------------------------------- テスト用

    /** テスト用: 根付きの姿が無いことにする(根付きを選べない。司令塔の判断を進化だけで試すとき)。 */
    private boolean noRootedForTests;

    public void setNoRootedForTests(boolean none) {
        this.noRootedForTests = none;
    }

    public void setForestForTests(ForestEdge forest) {
        this.forestOverride = forest;
    }

    public void setRootedFormForTests(java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        this.rootedOverride = type;
    }

    public void setAwayWantedForTests(double blocks) {
        this.awayWanted = blocks;
    }

    public void setWalkedForTests(int ticks) {
        this.walkedTicks = ticks;
    }

    public int walkedTicks() {
        return walkedTicks;
    }
}
