package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.GaitAnimationController;
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
 * グレガリア {@code gregaria}(G4・大群の先触れ。11-14 に本名。仮名ムラガリ、仮の id {@code g4_swarm})。
 * 群れで地面を走り回り、通った所に胞子を降らせる「地面を覆う胞子の潮」(Vault {@code Mob の能力/グレガリア.md})。
 *
 * <h2>段 1(2026-11-15): 歩く・走るところまで</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。能力(胞子を降らせる・足首を噛む)・構え kamae・発光・群れはまだ。
 * 形は練習モデル {@code prac2_g4_swarm_r8}(正本 Blockbench の {@code gregaria.bbmodel})。アニメはそのまま(idle・walk)。
 *
 * <h3>歩き・走りの速さ合わせ(11-15 段 2 のアニメに合わせて)</h3>
 * walk(10 tick で 1 巡)は 1 倍で地面 {@link #WALK_REF}、run(5 tick で 1 巡)は {@link #RUN_REF} ブロック/tick で足が滑らない
 * (モデル制作のセッションが IK で焼いた確定値)。再生は実際の速さ ÷ 基準の速さ、walk は {@link #WALK_SPEED_MIN}〜{@link #WALK_SPEED_MAX} 倍、
 * run は {@link #RUN_SPEED_MIN}〜{@link #RUN_SPEED_MAX} 倍【仮】。{@link #RUN_ON} 以上で run、{@link #RUN_OFF} 以下で walk【仮】。
 * walk と run は同じ脚の並び(0 で同じ三脚が着く)なので、切り替えは 1 巡の中の位置を保って補間なしで移る({@link GaitAnimationController})。
 * 急ぎは地面で約 0.24(11-15 ユーザー「歩きより少し早いくらい」)、うろつきは約 0.07。
 */
public class GregariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "gregaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk・run を 1 倍で流したときの地上速度(ブロック/tick。アニメの確定値)。 */
    public static final double WALK_REF = 0.07D, RUN_REF = 0.24D;
    /** 再生速度の範囲(関節の動きが約 100 度/tick を超えない所)【仮】。 */
    static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.2D, RUN_SPEED_MIN = 0.6D, RUN_SPEED_MAX = 1.25D;
    /** これ以上で run、これ以下で walk(ブロック/tick。行き来でばたつかないよう差を付ける)【仮】。 */
    static final double RUN_ON = 0.153D, RUN_OFF = 0.145D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.008D;

    /** 回る速さ(度/tick)【仮】。 */
    static final float TURN_PER_TICK = 30.0F;
    /** うろつき(小走り)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.07 ブロック/tick(walk 1 倍)を的に。 */
    static final double STROLL_SPEED = 0.545D;   // 地面の速さ ≈ 2.16 × (0.33 × 0.545)² ≈ 0.07

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");

    /** クライアント: 走りにしているか(速さのヒステリシス)。 */
    private boolean running;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public GregariaEntity(EntityType<? extends GregariaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 1);
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
    protected double bodyHalfLength() {
        return 0.55D;   // 胸の前から腹の後ろまで約 1.1 ブロック
    }

    @Override
    public void tick() {
        super.tick();
        if (DEV_HOOKS && !this.level().isClientSide() && this.hasCustomName()) devHooks(this.getCustomName().getString());
    }

    /** テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで走り続け、…trace… は 10 tick ごとに速さを書く。 */
    private void devHooks(String name) {
        if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "GREG_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s", name, this.getX(), this.getY(), this.getZ(),
                    Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 3, this::mainState));
    }

    private PlayState mainState(AnimationState<GregariaEntity> state) {
        GaitAnimationController<GregariaEntity> c = (GaitAnimationController<GregariaEntity>) state.getController();
        double v = clientMoveSpeed(state.getPartialTick());
        double s = this.getScale();
        if (!this.isDeadOrDying() && v >= WALK_MIN_SPEED) {
            if (this.running ? v > RUN_OFF * s : v >= RUN_ON * s) {
                this.running = true;
                c.setAnimationSpeed(Mth.clamp(v / (RUN_REF * s), RUN_SPEED_MIN, RUN_SPEED_MAX));
                c.switchGait(RUN, "run", "walk");
                devGait(c, v);
                return state.setAndContinue(RUN);
            }
            this.running = false;
            c.setAnimationSpeed(Mth.clamp(v / (WALK_REF * s), WALK_SPEED_MIN, WALK_SPEED_MAX));
            c.switchGait(WALK, "walk", "run");
            devGait(c, v);
            return state.setAndContinue(WALK);
        }
        this.running = false;
        c.setAnimationSpeed(1.0D);
        c.switchGait(IDLE, "idle", "-");
        return state.setAndContinue(IDLE);
    }

    /** テストクライアント用: 名前に gait を含む個体の、フレームごとの歩き方と 1 巡の中の位置。 */
    private void devGait(GaitAnimationController<GregariaEntity> c, double v) {
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "GREG_ANIM t %d %s 位置 %.3f 速さ %.3f", this.tickCount, c.currentName(), c.fraction(), v));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
