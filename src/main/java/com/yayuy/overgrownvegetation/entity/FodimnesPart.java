package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Set;

/**
 * フォディムネスの<b>節</b>(番号 1〜16)と<b>尾</b>(番号 17)の当たり判定 + 描くときのアニメの持ち主。
 *
 * <h2>ユーザー指定</h2>
 * 「すべての節に攻撃することが可能で、節ごとに体力は持たせずすべて共通」「本体 1 体 + 節の当たり判定は PartEntity(エンダードラゴンと同じ)」。
 * 部位は体力を持たず、{@link #hurt} を本体へ回すだけ(多重ヒットの間引きは本体の {@link FodimnesEntity#hurt})。
 *
 * <h2>作り</h2>
 * <ul>
 *   <li>位置・箱は本体が毎 tick(サーバーでもクライアントでも)同じ式から置く({@link FodimnesEntity}。部位はワールドに入らないので自分では tick しない)。
 *       箱は部位の向きに合わせた軸平行の箱で、長さ 0.8(間隔)× 太さ(先細りの倍率)の筒を覆う —— 隣の箱とすき間なく並ぶ。</li>
 *   <li>位置は「箱の底の真ん中」(実体の約束。{@code HiveVinePart} の x・z から幅の半分を引く書き方は真似しない)。</li>
 *   <li>描くときの GeckoLib の持ち主でもある(節ごとに蠕動の位相をずらすため、節ごとに別のアニメの状態が要る)。
 *       アニメは {@link FodimnesPhaseController} で「節 i は (i−1)×5 tick 先」。尾は 16×5。</li>
 * </ul>
 */
public class FodimnesPart extends PartEntity<FodimnesEntity> implements GeoAnimatable {

    private static final RawAnimation CRAWL = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation FLARE = RawAnimation.begin().thenPlayAndHold("flare");

    /** 1 = 節 1(頭のすぐ後ろ)… 16 = 節 16、17 = 尾。 */
    public final int index;
    private EntityDimensions size;
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public FodimnesPart(FodimnesEntity parent, int index, float width, float height) {
        super(parent);
        this.index = index;
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    public boolean isTail() {
        return this.index > FodimnesEntity.SEGMENTS;
    }

    /** 本体が置く: 軸の点 (cx, cy, cz) を中心に、半分の大きさ (hx, hy, hz) の箱。位置は箱の底の真ん中。 */
    void place(double cx, double cy, double cz, double hx, double hy, double hz) {
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.xOld = this.xo;
        this.yOld = this.yo;
        this.zOld = this.zo;
        this.setPos(cx, cy - hy, cz);
        this.setBoundingBox(new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    /** どの部位を殴っても本体の共通の体力が減る(ユーザー指定)。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isInvulnerableTo(source) && this.getParent().hurtFromPart(this, source, amount);
    }

    /** 部位と本体を同一視する(同じ体を二重に狙わない・自分の部位を押さない)。 */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    // ------------------------------------------------------------------ 描くときのアニメ(クライアント)

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 節 i は (i−1)×5 tick、尾は 16×5 tick 先(頭と節 1 は同じ時刻。NOTES の「位相の約束」)
        double offset = (this.index - 1) * FodimnesEntity.CRAWL_PHASE_TICKS;
        controllers.add(new FodimnesPhaseController<>(this, "main", 4, this::animState, offset, Set.of("crawl", "idle")));
    }

    private PlayState animState(AnimationState<FodimnesPart> state) {
        FodimnesEntity owner = this.getParent();
        if (owner.isDeadOrDying()) {
            return state.setAndContinue(IDLE);
        }
        // flare(節だけ): 頭が飛び出している間の前の数節・殴られた直後の全部
        if (!isTail() && (owner.isFlaring(this.index))) {
            return state.setAndContinue(FLARE);
        }
        return state.setAndContinue(owner.isCrawlingClient() ? CRAWL : IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    public double getTick(Object object) {
        return this.getParent().tickCount;
    }
}
