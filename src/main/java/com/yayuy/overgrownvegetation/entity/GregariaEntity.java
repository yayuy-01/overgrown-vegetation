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
 * グレガリア {@code gregaria}(G4・大群の先触れ。11-14 に本名。仮名ムラガリ、仮の id {@code g4_swarm})。
 * 群れで地面を走り回り、通った所に胞子を降らせる「地面を覆う胞子の潮」(Vault {@code Mob の能力/グレガリア.md})。
 *
 * <h2>段 1(2026-11-15): 歩く・走るところまで</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。能力(胞子を降らせる・足首を噛む)・構え kamae・発光・群れはまだ。
 * 形は練習モデル {@code prac2_g4_swarm_r8}(正本 Blockbench の {@code gregaria.bbmodel})。アニメはそのまま(idle・walk)。
 *
 * <h3>歩きの速さ合わせ</h3>
 * walk(0.5 秒の三脚歩行)を 1 倍で流したとき、中脚・後脚が地面を後ろへ送る速さは FK で約 {@link #WALK_REF} ブロック/tick(11-15 測定。
 * 前脚だけ約 0.02 で揃っていない = 前脚は少し滑る)。再生は実際の速さ ÷ {@link #WALK_REF}、上限 {@link #WALK_SPEED_MAX} 倍
 * (それより速いと脚が震えて見える)。急ぎの最高 0.24 は 3.4 倍にあたるので、上限を超えた分は足が滑る → 走り専用のアニメが要る。
 */
public class GregariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "gregaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk を 1 倍で流したときの地上速度(ブロック/tick、FK で測った中脚・後脚の平均)。 */
    public static final double WALK_REF = 0.07D;
    /** 再生速度の範囲(上限より速いと脚が震えて見える)【仮】。 */
    static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.5D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.008D;

    /** 回る速さ(度/tick)【仮】。 */
    static final float TURN_PER_TICK = 30.0F;
    /** うろつき(小走り)の速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.07 ブロック/tick(walk 1 倍)を的に。 */
    static final double STROLL_SPEED = 0.55D;   // 地面の速さ ≈ 2.16 × (0.33 × 0.55)² ≈ 0.07

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");

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
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 3, this::mainState));
    }

    private PlayState mainState(AnimationState<GregariaEntity> state) {
        AnimationController<GregariaEntity> c = state.getController();
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
