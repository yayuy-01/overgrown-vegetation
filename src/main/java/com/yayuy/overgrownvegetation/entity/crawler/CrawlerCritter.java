package com.yayuy.overgrownvegetation.entity.crawler;

import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * 「小さな地這いの虫」の型で歩くだけの生き物の土台(2026-11-16、G2・G3 の作り直しの段 1。1 体目はプルモノスコルピウス・プロトファスマ)。
 * 待機 idle・歩き walk・(あれば)走り run を、実際の速さに合わせて流す。walk ⇄ run は 1 巡の中の位置を引き継ぐ({@link GaitAnimationController})。
 * 行動(棲み処・食べる/狩る/休む・群れ)は後の段。種は速さの基準と範囲を返すだけ。
 * G2・G3 は植物側({@link ParasitizedMob}・グループのタグ)= すべての Mob から敵対される。胞子は運ばない。
 */
public abstract class CrawlerCritter extends SmallCrawler implements GeoEntity, ParasitizedMob {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    /** これ(ブロック/tick)以上進んだら歩き。 */
    static final double WALK_MIN_SPEED = 0.006D;

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    /** クライアント: 走りにしているか(速さのヒステリシス)。 */
    private boolean running;

    protected CrawlerCritter(EntityType<? extends CrawlerCritter> type, Level level) {
        super(type, level);
    }

    /** walk を 1 倍で流したとき足が滑らない地面の速さ(ブロック/tick、大きさ 1)。 */
    protected abstract double walkRef();

    /** walk の再生倍率の範囲 {最小, 最大}。 */
    protected double[] walkRange() {
        return new double[]{0.4D, 2.2D};
    }

    /** run の基準の速さ(無ければ NaN = 走りは walk を流す)。 */
    protected double runRef() {
        return Double.NaN;
    }

    /** run の再生倍率の範囲・切り替えの境 {最小倍率, 最大倍率, run へ, walk へ}(ブロック/tick)。 */
    protected double[] runRange() {
        return new double[]{0.6D, 1.25D, 0.153D, 0.145D};
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new GaitAnimationController<>(this, "main", 3, this::mainState));
    }

    private PlayState mainState(AnimationState<CrawlerCritter> state) {
        GaitAnimationController<CrawlerCritter> c = (GaitAnimationController<CrawlerCritter>) state.getController();
        double v = clientMoveSpeed(state.getPartialTick());
        double s = this.getScale();
        if (!this.isDeadOrDying() && v >= WALK_MIN_SPEED) {
            double runRef = runRef();
            if (!Double.isNaN(runRef)) {
                double[] r = runRange();
                if (this.running ? v > r[3] * s : v >= r[2] * s) {
                    this.running = true;
                    c.setAnimationSpeed(Mth.clamp(v / (runRef * s), r[0], r[1]));
                    c.switchGait(RUN, "run", "walk");
                    return state.setAndContinue(RUN);
                }
            }
            this.running = false;
            double[] w = walkRange();
            c.setAnimationSpeed(Mth.clamp(v / (walkRef() * s), w[0], w[1]));
            c.switchGait(WALK, "walk", "run");
            return state.setAndContinue(WALK);
        }
        this.running = false;
        c.setAnimationSpeed(1.0D);
        c.switchGait(IDLE, "idle", "-");
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    /** テストクライアント用: 名前に trace を含む個体は 10 tick ごとに速さを書く(-Dov.devbridge のときだけ)。 */
    @Override
    public void tick() {
        super.tick();
        if (DEV_HOOKS && !this.level().isClientSide() && this.hasCustomName() && this.tickCount % 10 == 0) {
            String name = this.getCustomName().getString();
            if (name.startsWith("nostroll_run") && this.getNavigation().isDone()) {
                @Nullable net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
                if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, rushSpeed());
            }
            if (name.contains("trace")) {
                double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
                com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                        "CRITTER_GAIT %s %s 速さ %.3f 向き %.0f 進む向き %.0f 経路 %s", this.getType().toShortString(), name, Math.sqrt(dx * dx + dz * dz), net.minecraft.util.Mth.wrapDegrees(this.getYRot()), net.minecraft.util.Mth.wrapDegrees((float) (Math.atan2(dz, dx) * 57.29577951308232D) - 90.0F),
                        this.getNavigation().isDone() ? "なし" : "あり"));
            }
        }
    }
}
