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
 * 形は練習モデル {@code prac2_g4_burster_r7}(正本 Blockbench の {@code vesicaria.bbmodel})。アニメは段 2 の分まで入っている(idle・walk・run・burst_windup 系・burst)が、袋の層と破裂はまだ呼ばない。
 *
 * <h3>袋が重い = 少しもたつく【仮・Claude 11-15】</h3>
 * グレガリアと比べて: 回るのが遅い(30 → {@link #TURN_PER_TICK} 度/tick)・一時停止が長い・うろつきも急ぎも遅い。
 *
 * <h3>歩き・走りの速さ合わせ(11-15 段 2 のアニメに合わせて)</h3>
 * walk(24 tick で 1 巡)は 1 倍で地面 {@link #WALK_REF}、run(5 tick で 1 巡)は {@link #RUN_REF} ブロック/tick で足が滑らない
 * (モデル制作のセッション vesicaria-anim が IK で焼いた確定値。段 1 の後ずさりの walk は焼き直された)。
 * 再生は実際の速さ ÷ 基準の速さ、walk は {@link #WALK_SPEED_MIN}〜{@link #WALK_SPEED_MAX} 倍、run は {@link #RUN_SPEED_MIN}〜{@link #RUN_SPEED_MAX} 倍【仮】。
 * {@link #RUN_ON} 以上で run、{@link #RUN_OFF} 以下で walk【仮】: walk が覆う上限 約 0.11 と run の下限 約 0.17 の間に置いた。
 * うろつき(約 0.045)・急ぎ(約 0.075)は walk、突撃(約 0.28 = 走るプレイヤーくらい。11-15 ユーザー)は run になる。
 * walk と run は同じ三脚の並び(0 で同じ組が着く)なので、切り替えは 1 巡の中の位置を保って移る({@link GaitAnimationController})。
 */
public class VesicariaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "vesicaria";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk・run を 1 倍で流したときの地上速度(ブロック/tick。アニメの確定値)。 */
    public static final double WALK_REF = 0.045D, RUN_REF = 0.28D;
    /** 再生速度の範囲【仮】(モデル制作の見立て: walk 0.018〜0.11・run 約 0.17〜0.35 を覆う)。 */
    public static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.5D, RUN_SPEED_MIN = 0.6D, RUN_SPEED_MAX = 1.25D;
    /** これ以上で run、これ以下で walk(ブロック/tick。行き来でばたつかないよう差を付ける)【仮】。 */
    static final double RUN_ON = 0.14D, RUN_OFF = 0.13D;
    /** 段 1 で測った急ぎ・うろつきの地面の速さ(ブロック/tick。テストの的)。 */
    public static final double RUSH_GROUND = 0.075D, STROLL_GROUND = 0.045D;
    /** 突撃の地面の速さ(ブロック/tick。11-15 ユーザー「走るプレイヤーくらい」)。 */
    public static final double CHARGE_GROUND = 0.28D;
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
    /**
     * 突撃の速さ: 移動の速さの属性に掛ける【仮】。地面で約 {@link #CHARGE_GROUND} を的に
     * (地面の速さ ≈ 2.16 × (0.186 × 1.94)² ≈ 0.28)。突撃の技はまだ無く、今はテストとテストクライアントだけが使う。
     */
    public static final double CHARGE_SPEED = 1.94D;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");

    /** クライアント: 走りにしているか(速さのヒステリシス)。 */
    private boolean running;

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

    /**
     * テストクライアント用: 名前 nostroll_run… は 10〜20 ブロック先へ急ぎで歩き続け、nostroll_charge… は突撃の速さで同じことをする。
     * …trace… は 10 tick ごとに速さを書く。
     */
    private void devHooks(String name) {
        boolean charge = name.startsWith("nostroll_charge");
        if ((charge || name.startsWith("nostroll_run")) && this.getNavigation().isDone()) {
            net.minecraft.world.phys.Vec3 p = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(this, 20, 4);
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D)
                this.getNavigation().moveTo(p.x, p.y, p.z, charge ? CHARGE_SPEED : this.rushSpeed());
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
        controllers.add(new GaitAnimationController<>(this, "main", 4, this::mainState));
    }

    private PlayState mainState(AnimationState<VesicariaEntity> state) {
        GaitAnimationController<VesicariaEntity> c = (GaitAnimationController<VesicariaEntity>) state.getController();
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
    private void devGait(GaitAnimationController<VesicariaEntity> c, double v) {
        if (DEV_HOOKS && this.hasCustomName() && this.getCustomName().getString().contains("gait")) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "VESI_ANIM t %d %s 位置 %.3f 速さ %.3f", this.tickCount, c.currentName(), c.fraction(), v));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
