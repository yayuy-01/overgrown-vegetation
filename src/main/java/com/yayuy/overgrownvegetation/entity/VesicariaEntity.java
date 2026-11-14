package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ヴェシカリア {@code vesicaria}(G4・自爆特攻。11-14 に本名。練習名ハゼブクロ {@code g4_burster})。
 * 突撃中に熱で袋を膨らませて黄緑に光り、触れた瞬間に破裂する。矢で射るとその場で破裂し、近くの仲間も連鎖する
 * (Vault {@code Mob の能力/G4〜G6 の能力の決定(11-14).md}・{@code Mob の能力/ヴェシカリア.md})。
 *
 * <h2>段 1(2026-11-15): うろつく・急ぐところまで</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。突撃・破裂・連鎖・構え kamae・発光はまだ。
 * 形は練習モデル {@code prac2_g4_burster_r7}(正本 Blockbench の {@code vesicaria.bbmodel})。アニメはそのまま(idle・walk・kamae)。
 *
 * <h3>袋が重い = 少しもたつく【仮・Claude 11-15】</h3>
 * グレガリアと比べて: 回るのが遅い(30 → {@link #TURN_PER_TICK} 度/tick)・一時停止が長い・うろつきも急ぎも遅い。
 *
 * <h3>歩きの速さ合わせ</h3>
 * r7 の walk(1 秒の三脚歩行)を 1 倍で流したとき、足先が地面を送る速さは FK で約 {@link #WALK_REF} ブロック/tick(11-15 測定。
 * 6 本とも 0.024〜0.031)。<b>ただし向きが逆</b>: 地面に着いている間に足先が前(頭の側)へ動く = 後ずさりの walk。
 * 段 1 はアニメを直さない決まりなのでそのまま流し、前へ歩く walk を足りないアニメとして頼む。再生は実際の速さ ÷ {@link #WALK_REF}、
 * 上限 {@link #WALK_SPEED_MAX} 倍(グレガリアにならう)。急ぎは walk 2.5 倍 = 約 0.075 で止めた。
 */
public class VesicariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "vesicaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk を 1 倍で流したときの地上速度(ブロック/tick、FK で測った 6 本の足先の平均)。 */
    public static final double WALK_REF = 0.03D;
    /** 再生速度の範囲【仮】(上限はグレガリアと同じ 2.5 倍。震え始める所は見ていない)。 */
    public static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.5D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.006D;

    /** 回る速さ(度/tick)【仮】。袋が重いぶんグレガリア(30)より遅い。 */
    static final float TURN_PER_TICK = 20.0F;
    /** うろつき(小走り)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.045 ブロック/tick(walk 1.5 倍)を的に。 */
    public static final double STROLL_SPEED = 0.78D;
    /**
     * 急ぎの速さ: 移動の速さの属性に掛ける【仮】。段 1 は 1.0(地面で約 0.075 = walk 2.5 倍)。
     * 段 2 の突撃はこれより速くしてよい(走り・突撃のアニメができてから)。
     */
    public static final double RUSH_SPEED = 1.0D;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public VesicariaEntity(EntityType<? extends VesicariaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    public double rushSpeed() {
        return RUSH_SPEED;
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{12, 40};   // 一時停止はグレガリア(6〜24)より長い = もたつく【仮】
    }

    @Override
    protected double bodyHalfLength() {
        return 0.7D;   // 前脚の先から後脚の先まで約 1.5 ブロック
    }

    @Override
    public void tick() {
        super.tick();
        if (DEV_HOOKS && !this.level().isClientSide() && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /** テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで歩き続け、…trace… は 10 tick ごとに速さを書く。 */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, this.rushSpeed());
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VESI_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s", name, this.getX(), this.getY(), this.getZ(),
                    Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<VesicariaEntity> state) {
        AnimationController<VesicariaEntity> c = state.getController();
        double v = clientMoveSpeed(state.getPartialTick());
        if (!this.isDeadOrDying() && v >= WALK_MIN_SPEED) {
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * this.getScale()), WALK_SPEED_MIN, WALK_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        c.setAnimationSpeed(1.0D);
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
