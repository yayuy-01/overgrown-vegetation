package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.SmallCrawler;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ストロビラ {@code strobila}(G4・軽い投手。11-14 に本名。仮名タマナゲ、練習の id {@code g4_lobber})。
 * アルボラの後ろから、動きながら尾の先の球果を山なりに投げる(Vault {@code Mob の能力/ストロビラ.md})。G4 の中で少し丈夫だが、詰められると弱い。
 * エネルギーは運動(尾の弾性。膨圧で張って一気に放す)。
 *
 * <h2>段 1(2026-11-15): うろつく・急ぐところまで</h2>
 * 移動は「小さな地這いの虫」の型({@link SmallCrawler})。投げる技・構え kamae・throw・光・群れはまだ。
 * 形は練習モデル {@code prac2_g4_lobber_r9}(正本 Blockbench の {@code strobila.bbmodel})。アニメはそのまま(idle・walk)。
 * 群れの後ろに付いて歩くのは群れ v2 が戻ってから({@link #isSteered()} は型のまま)。
 *
 * <h3>歩きの速さ合わせ</h3>
 * walk(1.0 秒の 4 対の交互歩行)を 1 倍で流したとき、接地している足が地面を後ろへ送る速さは FK で
 * 前・中の 3 対が約 0.028〜0.033、<b>後ろの 1 対だけ約 0.056</b>(11-15 測定。揃っていない = 後ろ脚は滑る)。
 * {@link #WALK_REF} は前の 3 対に合わせた。再生は実際の速さ ÷ {@link #WALK_REF}、上限 {@link #WALK_SPEED_MAX} 倍(グレガリアと同じ)。
 * 歩幅が体に比べて短く、急ぎは 2.5 倍 = 約 0.075 ブロック/tick で止まる(走り・大股のアニメができたら上げる)。
 */
public class StrobilaEntity extends SmallCrawler implements GeoEntity, ParasitizedMob {

    public static final String ID = "strobila";

    /** 描画倍率(テクスチャは 1 単位 1 テクセル = ブロックの 1 画素)。 */
    public static final float RENDER_SCALE = 1.0F;

    /** walk を 1 倍で流したときの地上速度(ブロック/tick、FK で測った前・中の 3 対の平均)。 */
    public static final double WALK_REF = 0.03D;
    /** 再生速度の範囲(グレガリアと同じ)【仮】。 */
    static final double WALK_SPEED_MIN = 0.4D, WALK_SPEED_MAX = 2.5D;
    /** これ(ブロック/tick)以上進んだら歩き【仮】。 */
    static final double WALK_MIN_SPEED = 0.006D;

    /** 回る速さ(度/tick)【仮】。体が長く重い背板を負うので、グレガリア(30)の半分。 */
    static final float TURN_PER_TICK = 15.0F;
    /** うろつきの速さ: 移動の速さの属性に掛ける【仮】。地面で約 0.03 ブロック/tick(walk 1 倍)を的に。 */
    static final double STROLL_SPEED = 0.63D;   // 地面の速さ ≈ 2.16 × (0.19 × 0.63)² ≈ 0.031

    /** 描画の外接箱を芯の箱から広げる量(ブロック): 脚・触角・尾は芯からはみ出す(全体 3.6 × 2.8 × 5.5)。 */
    static final double CULL_SIDE = 2.4D, CULL_UP = 1.8D;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public StrobilaEntity(EntityType<? extends StrobilaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 3);
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

    /** 小走りは長め・止まるのも長め(グレガリアより落ち着いた虫)【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{20, 50};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{10, 40};
    }

    @Override
    protected double bodyHalfLength() {
        return 1.0D;   // 頭の先から胴の後ろまで約 2.2 ブロック(尾は上へ立つので数えない)
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        AABB b = this.getBoundingBox();
        return new AABB(b.minX - CULL_SIDE, b.minY - 0.5D, b.minZ - CULL_SIDE, b.maxX + CULL_SIDE, b.maxY + CULL_UP, b.maxZ + CULL_SIDE);
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
            if (p != null && p.distanceToSqr(this.position()) > 10.0D * 10.0D) this.getNavigation().moveTo(p.x, p.y, p.z, 1.0D);
        }
        if (name.contains("trace") && this.tickCount % 10 == 0) {
            double dx = this.getX() - this.xo, dz = this.getZ() - this.zo;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "STROB_GAIT %s pos %.1f %.1f %.1f 速さ %.3f 経路 %s", name, this.getX(), this.getY(), this.getZ(),
                    Math.sqrt(dx * dx + dz * dz), this.getNavigation().isDone() ? "なし" : "あり"));
        }
    }

    // ------------------------------------------------------------------ アニメ

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new PhaseKeepingAnimationController<>(this, "main", 3, this::mainState));
    }

    private PlayState mainState(AnimationState<StrobilaEntity> state) {
        AnimationController<StrobilaEntity> c = state.getController();
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
