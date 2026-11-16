package com.yayuy.overgrownvegetation.entity.lizard;

import com.yayuy.overgrownvegetation.entity.OvergrownNodeEvaluator;
import com.yayuy.overgrownvegetation.entity.PhaseKeepingAnimationController;
import com.yayuy.overgrownvegetation.entity.legs.FootPlanter;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.entity.EntityType;
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
 * 「トカゲ型」の小さな生き物の土台(2026-11-16、G3 のペトロラコサウルス・イアンタサウルスを入れるときに作った。ヒロノムスなどにも使い回す)。
 * 移動は {@link LizardRunner}(走っては止まって見回す・物陰の脇で止まる・2 段は跳ぶ・背骨のくねり・横へ張った脚の IK)のまま。ここで足すのは
 * 小さな体の足の運びの値と、アニメの選び方だけ。行動(棲み処・食べる/狩る/休む・群れ)は後の段。
 *
 * <h2>アニメ(idle・walk・run の 3 本を使う。ほかの eat・rest・flee・bask などは行動の段で)</h2>
 * 近く(足の置き場が効く)では idle を流し、脚・胴の高さ・くねり・見回しはコード。遠くで進む間は走り出すときに決めた 1 本
 * ({@link #isDashRun()} なら run、でなければ walk)を、実際の速さ ÷ 基準の速さ({@link #walkRef()}・{@link #runRef()}。FK で測った値)で流す。
 * 見回しはコード({@link SpineWave})。この種たちには look_around のアニメが無い。
 */
public abstract class SimpleLizard extends LizardRunner implements GeoEntity, ParasitizedMob {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");

    static final double LOOP_SPEED_MIN = 0.3D, LOOP_SPEED_MAX = 3.0D, WALK_MIN_SPEED = 0.01D;
    static final float TURN_ANIM_MIN = 1.5F;
    /** 小さな体の経路の重み: 幹を嫌わない(長い体の重みはほぼ要らない)【仮】。 */
    public static final OvergrownNodeEvaluator.LongBody LIGHT_BODY = new OvergrownNodeEvaluator.LongBody(1.0F, 0.3F, 0.15F, 0.6F);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    protected SimpleLizard(EntityType<? extends SimpleLizard> type, Level level) {
        super(type, level);
    }

    // ------------------------------------------------------------------ 種ごとに決める

    /** walk・run を 1.0 倍で流したときの地上速度(ブロック/tick、体格 1)。 */
    protected abstract double walkRef();

    protected abstract double runRef();

    /** 着いている足が流れる距離の下限・上限(ブロック)と、速さに掛ける係数【仮】。脚の届く前後の幅に合わせる。 */
    protected abstract double[] stepRange();

    /** 宙の足を上げる高さ・地面を探す上・下(ブロック)。 */
    protected abstract double[] footProbe();

    @Override
    protected OvergrownNodeEvaluator.LongBody longBodyWeights() {
        return LIGHT_BODY;
    }

    /** 動く間に胴を下げる量(体格 1 のブロック)。小さな体なので 0.05(体格 2 で 0.1)【仮】。ピリムネスの 0.3 のままだと体格が掛かって胴が地面に付く。 */
    @Override
    protected double crouchDepth() {
        return 0.05D;
    }

    @Override
    protected float maxTurnPerTick() {
        return 20.0F;   // 小さく軽い体はその場で素早く向きを変える【仮】
    }

    @Override
    protected float maxMoveOff() {
        return 70.0F;
    }

    // ------------------------------------------------------------------ 脚

    @Override
    protected void configurePlanter(FootPlanter fp) {
        double[] p = footProbe();
        fp.lift = p[0];
        fp.probeUp = p[1];
        fp.probeDown = p[2];
    }

    @Override
    protected void tunePlanter(FootPlanter fp) {
        double v = Math.sqrt(sq(this.getX() - this.xo) + sq(this.getZ() - this.zo)) / Math.max(this.getScale(), 0.05F);
        boolean turning = Math.abs(getTurnRate()) > 3.0F;
        double[] r = stepRange();
        double step = Mth.clamp(r[0] + v * r[2], r[0], r[1]);
        double stance = v > 0.01D ? step * 2.0D / v : 40.0D;
        fp.stepAt = step;
        fp.swingTicks = Mth.clamp((int) Math.round(stance * 0.6D), 2, turning ? 2 : 5);
        fp.lead = Mth.clamp(step / Math.max(v, 0.01D), 0.0D, turning ? 2.0D : 5.0D);
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 4, this::mainState));
    }

    /**
     * 種の技・移動の形(登る・滑空するなど)のアニメ。null なら歩きの決まり(idle・walk・run)。速さは {@code c} に種が決める。
     */
    @Nullable
    protected RawAnimation actAnimation(AnimationController<SimpleLizard> c) {
        return null;
    }

    /** 描画: 手続きの背骨のくねり・脚の IK・曲げを止めるか(体を立てる・宙を滑るなど、アニメに全部任せる間)。 */
    public boolean rigsOff() {
        return false;
    }

    private PlayState mainState(AnimationState<SimpleLizard> state) {
        AnimationController<SimpleLizard> c = state.getController();
        c.setAnimationSpeed(1.0D);
        RawAnimation act = actAnimation(c);
        if (act != null) return state.setAndContinue(act);
        double ahead = clientForwardSpeed();
        boolean turning = Math.abs(getTurnRate()) >= TURN_ANIM_MIN;
        double s = Math.max(this.getScale(), 0.05F);
        if (!this.isDeadOrDying() && !proceduralLegs() && (ahead >= WALK_MIN_SPEED || turning)) {
            if (isDashRun() && ahead >= WALK_MIN_SPEED) {
                c.setAnimationSpeed(Mth.clamp(ahead / (runRef() * s), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
                return state.setAndContinue(RUN);
            }
            double v = Math.max(ahead, turning ? walkRef() * 0.5D : 0.0D);
            c.setAnimationSpeed(Mth.clamp(v / (walkRef() * s), LOOP_SPEED_MIN, LOOP_SPEED_MAX));
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
