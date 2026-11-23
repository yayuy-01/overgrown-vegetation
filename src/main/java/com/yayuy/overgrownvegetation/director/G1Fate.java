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

import javax.annotation.Nullable;

/**
 * 段階 0 の G1 の 1 体が持つ道(2026-11-13、司令塔 第 1 段)。
 *
 * <ul>
 *   <li><b>進化</b>: 前兆(D-11。立ち止まって震え、植物が膨らむ = {@code client/G1OmenRig})を {@code evolveOmenTicks} 流してから、
 *   同じ場所で 1 段目({@link DevelopedParasitized})に入れ替え、{@code evolve_in} を流させる。前兆の間に倒せば、何も起きない。
 *   始めるのは、森が貯めた分を一気に使うとき({@link ForestMoods#spend})と確かめのコマンド。</li>
 *   <li><b>偵察</b>の使い({@link G1Scout})。</li>
 * </ul>
 * 2026-11-23 片付け E: 司令塔が 1 体ずつ見に来て道(進化・根付き・苗床)を選ぶ仕組みと、根付き・苗床へ歩いて変わる道は消した
 * (11-21 から前線と部隊が受け持つ: 進化は部隊の中・根付きは前哨・苗床は現地の苗床)。根付き・苗床の姿の表はその前線が使う。
 * ゴーレム・ウォーデンは進化体が無いので持たない。1 段目も持たない。
 */
public final class G1Fate {

    /** 前兆の種類(同期データ {@code Omen} の値)。 */
    public static final int OMEN_NONE = 0;
    public static final int OMEN_EVOLVE = 1;
    /** 根を下ろす(根付く直前。立ち止まって体が沈む)。2026-11-23 から G1Fate は出さない(描画の側の枝は残す)。 */
    public static final int OMEN_ROOT = 2;

    /** 根付きの姿(種 → Mob の型)。組み込んだ種だけここに登録する。前線の前哨が使う(11-21)。 */
    private static final java.util.Map<com.yayuy.overgrownvegetation.corpse.G1Species,
            java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> ROOTED =
            new java.util.EnumMap<>(com.yayuy.overgrownvegetation.corpse.G1Species.class);

    /** 苗床の姿(種 → Mob の型)。前線の現地の苗床が使う(11-21)。 */
    private static final java.util.Map<com.yayuy.overgrownvegetation.corpse.G1Species,
            java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>>> NURSERY =
            new java.util.EnumMap<>(com.yayuy.overgrownvegetation.corpse.G1Species.class);

    /** 種の根付きの姿の型(無ければ null。前線の部隊の中の根付き、11-21)。 */
    @Nullable
    public static net.minecraft.world.entity.EntityType<?> rootedTypeOf(com.yayuy.overgrownvegetation.corpse.G1Species species) {
        var s = ROOTED.get(species);
        return s == null ? null : s.get();
    }

    /** 種の苗床の姿の型(無ければ null。前線の現地の苗床の実物、11-21)。 */
    @Nullable
    public static net.minecraft.world.entity.EntityType<?> nurseryTypeOf(com.yayuy.overgrownvegetation.corpse.G1Species species) {
        var s = NURSERY.get(species);
        return s == null ? null : s.get();
    }

    public static void registerRooted(com.yayuy.overgrownvegetation.corpse.G1Species species,
                                      java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        ROOTED.put(species, type);
    }

    public static void registerNursery(com.yayuy.overgrownvegetation.corpse.G1Species species,
                                       java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>> type) {
        NURSERY.put(species, type);
    }

    /** 根付きの姿の側が持つ口: 現れた直後に「生える」(emerge)を始める。 */
    public interface Emerging {
        void startEmerge();
    }

    /** 入れ替える場所が狭いとき、前兆を延ばして待つ長さ(tick)と回数【仮】。それでも空かなければ、やめて予算を戻す。 */
    private static final int NO_ROOM_WAIT = 20;
    private static final int NO_ROOM_TRIES = 5;

    private final AbstractParasitizedMonster mob;
    private Route route = Route.NONE;
    private int omenLeft;
    private int noRoomTries;
    /** 払った額(段の強さで割った値段。変われなかったら、これを戻す。コマンドで決めた道は 0 = 戻さない)。 */
    private double paidPrice;

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

    /** 最後に殴った・殴られたときから、これだけたつまでは「戦っている最中」(tick)【仮】。 */
    public static final int COMBAT_QUIET_TICKS = 200;

    /**
     * 戦っている最中か(11-13 ユーザー決定「戦っている最中は進化しないように」。決め方は【仮】): 生きた標的がいる、または
     * 最後に殴った・殴られたときから {@link #COMBAT_QUIET_TICKS}(10 秒)たっていない。偵察を選ぶとき(BaseWatch#canScout)に見る。
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
        if (!t.enabled) {
            // 司令塔を切って起動し直した: 前兆の途中なら取り消す(固まったままにしない。11-18 見直し ①10)
            if (route != Route.NONE) cancel(level);
            return;
        }
        if (route == Route.EVOLVE) {
            // 震えの音(2.4 秒)は前兆の頭から 10 tick 遅らせると、終わりが前兆(60 tick)に合う。入り切らずに待つ間は鳴らし直さない。音量【仮】
            if (omenLeft == t.evolveOmenTicks - 10 + 1 && noRoomTries == 0) {
                mob.playSound(com.yayuy.overgrownvegetation.registry.ModSounds.G1_EVOLVE_OMEN.get(), 1.0F, 1.0F);
            }
            if (--omenLeft <= 0) finishEvolve(level);
            return;
        }
        if (scout.active()) scout.tick(level);
    }

    /** 司令塔が予算を払って決めた道にする(一斉進化。やめたら予算を戻す)。 */
    void markPaid(double price) {
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
        if (mob.isPassenger()) mob.stopRiding(); // 背負われたまま進化すると背の高さに出る(11-18 見直し ①3)。降ろしてからその場で
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
        // 印(ov_ で始まる物)は引き継ぐ(11-23 片付け E: 縁・地下の湧きの印は無くなった)
        for (String tag : mob.getTags()) {
            if (tag.startsWith("ov_")) next.addTag(tag);
        }
        mob.copyConditionTo(next); // 減っていた体力・効果・火(11-18 見直し ①4)
        if (!level.addFreshEntity(next)) { // 加われなかった: 元を消さずに取り消して予算を返す(①4。前は個体も予算も消えた)
            cancel(level);
            return;
        }
        mob.dropPickedUpForSwap();
        next.startEvolveIn();
        mob.discard();
    }

    /**
     * 殴られ、殴り返している最中か(11-13 ユーザー決定「殴り返すようにしてほしい」)。
     * 決め方は【仮】: 殴ってきた相手が生きていて、最後に殴られてから {@link #COMBAT_QUIET_TICKS}(10 秒)たっていない。
     * 偵察・遠巻きにうろつく G1 が殴り返すかを決める。
     */
    public static boolean retaliating(AbstractParasitizedMonster mob) {
        net.minecraft.world.entity.LivingEntity by = mob.combatBy();
        return by != null && by.isAlive() && mob.tickCount - mob.combatByAt() < COMBAT_QUIET_TICKS;
    }

    /** 変われなかった: 予算を戻す。 */
    private void cancel(ServerLevel level) {
        if (route != Route.NONE && paidPrice > 0.0D) ForestDirector.get(level).refund(level, paidPrice);
        route = Route.NONE;
        omenLeft = 0;
        paidPrice = 0.0D;
        mob.setOmen(OMEN_NONE);
    }

    // ---------------------------------------------------------------- 見る口・確かめ用

    public Route route() {
        return route;
    }

    // ---------------------------------------------------------------- 保存

    public void save(CompoundTag tag) {
        CompoundTag f = new CompoundTag();
        f.putString("Route", route.name());
        f.putInt("OmenLeft", omenLeft);
        f.putDouble("PaidPrice", paidPrice);
        f.putInt("NoRoomTries", noRoomTries); // 入れ直しの回数の上限を読み込みで 0 に戻さない(11-18 見直し ①8)
        scout.save(f);
        tag.put("Fate", f);
    }

    public void load(CompoundTag tag) {
        if (!tag.contains("Fate")) return;
        CompoundTag f = tag.getCompound("Fate");
        route = Route.byName(f.getString("Route"));
        if (route != Route.EVOLVE) route = Route.NONE; // 11-23 片付け E: 前の版の根付き・苗床の途中は取りやめ(払った額は戻さない)
        omenLeft = f.getInt("OmenLeft");
        paidPrice = f.getBoolean("Paid") || !f.contains("Paid") ? f.getDouble("PaidPrice") : 0.0D;
        noRoomTries = f.getInt("NoRoomTries");
        scout.load(f);
        mob.setOmen(route == Route.EVOLVE ? OMEN_EVOLVE : OMEN_NONE);
    }
}
