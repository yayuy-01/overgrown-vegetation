package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.client.TitanicaAnimatable;
import com.yayuy.overgrownvegetation.client.TitanicaAnimations;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.siege.SiegeBossMotion;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ドルガ=アーケロウス・ティタニカ(Dorga-Archelaus Titanica) — 超大型レイドボス(攻城戦)が
 * <b>プレイヤーの近くに来たときだけ</b>存在する実エンティティ。
 *
 * <p>設定上は、この MOD の植物たちが作り上げた侵略用の生物。木・草・苔で編まれた体に、
 * 頭の先の花からビームを放ち、腹側の触手群でも攻撃する。
 *
 * <h2>役割 — 「遠距離はデータ、近距離はエンティティ」の切り替わり先</h2>
 * <p>このボスの本体は {@link SiegeData} が持つ軌道パラメータと体力であって、遠方ではエンティティは
 * 存在しない(チャンクがティックされず、{@code ChunkMap} の同期距離も超えるため)。
 * プレイヤーが {@code siegeCloseRange} まで近づいたときに限り {@link SiegeData} がこのエンティティを
 * 湧かせ、離れたら discard する。
 *
 * <p>切り替わりが目に見えないよう、このエンティティは AI で飛ぶのではなく
 * <b>遠距離時とまったく同じ {@link SiegeBossMotion} の式</b>で毎 tick 位置を決め打ちする。
 * ボスの「位置」は胴の中心線上の点なので、当たり判定(高さ {@code siegeHitboxHeight})の中心が
 * そこへ来るよう半分だけ下げて置く({@link com.yayuy.overgrownvegetation.client.DorgaArchelausTitanicaRenderer}
 * が描画で同じぶんを戻す)。
 *
 * <h2>当たり判定はマルチパート</h2>
 * <p>本体の AABB は胴の中心 1 節ぶんだけで、頭・首・胴・尾は {@link TitanicaHitboxEntity} 28 個が
 * 体に沿って覆う。1 つの巨大な AABB では「胴の中心から離れた部位に撃っても当たらない」ため。
 * 箱は<b>独立したエンティティ</b>で(マルチパートではない)、読み込まれている範囲にある箱は
 * 常にそこに在る — 理由は {@link TitanicaHitboxEntity} のクラス javadoc にある。
 * 箱の大きさはモデル単位で持ち、{@code siegeScale} に合わせて自動で拡大する。
 *
 * <p>体力の真の持ち主も {@link SiegeData} 側で、こちらは被弾を受け取って向こうへ書き戻すだけ
 * ({@link #hurt})。逆に遠距離で砲弾が当たった場合は {@link SiegeData#hurt} がこのエンティティへ
 * 転送する。どちらの経路でも体力は 1 か所に集約される。
 *
 * <p>描画は GeckoLib。花の開閉はアニメーションファイル、ヒレ・触手・尾・首は
 * {@link com.yayuy.overgrownvegetation.client.DorgaArchelausTitanicaModel} の手続き的な動き。
 * どちらも遠距離用のインポスタ({@link com.yayuy.overgrownvegetation.client.TitanicaImpostor})と
 * 完全に共通なので、切り替わっても見た目は変わらない。
 */
public class DorgaArchelausTitanicaEntity extends Mob implements GeoEntity, TitanicaAnimatable {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * 当たり判定の箱({@link TitanicaHitboxEntity})。頭 1 / 首 6 / 胴・尾 21 = 28 個。
     * 本体の AABB は胴の中心の 1 節ぶんしか無く、モデル全体を覆うのはこの 28 個
     * ({@link TitanicaHitboxEntity} のクラス javadoc に、独立したエンティティである理由がある)。
     *
     * <p>保存も同期もしない参照なので、ワールドを読み直したあとは空から作り直す({@link #positionHitboxes})。
     */
    private final TitanicaHitboxEntity[] hitboxes = new TitanicaHitboxEntity[TitanicaKinematics.partCount()];

    /**
     * 体軸のうち当たり判定が存在する範囲(モデル単位、中心からの片側)。
     * 頭(花)が −216 あたり、尾の先が +216 あたり({@link TitanicaKinematics#BODY_PARTS})。
     */
    private static final double HITBOX_HALF_UNITS = 216.0D;

    /** 本当の胴の中心({@link SiegeBossMotion} の式)。サーバーで毎 tick 更新。 */
    @javax.annotation.Nullable
    private Vec3 trueCenter;

    /** 直前に通した被弾。同じ tick に同じダメージ源が来たら 1 回にまとめる({@link #hurtFromHitbox})。 */
    @javax.annotation.Nullable
    private DamageSource lastHitSource;
    private long lastHitGameTime = Long.MIN_VALUE;

    public DorgaArchelausTitanicaEntity(EntityType<? extends DorgaArchelausTitanicaEntity> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setNoGravity(true);
        this.noPhysics = true;
        this.setPersistenceRequired();
        this.xpReward = 0;
    }

    /**
     * 当たり判定の箱への被弾。箱は体力を持たないので、そのまま本体の {@link #hurt} へ流す。
     *
     * <p>同じ tick に<b>同じダメージ源</b>から来た 2 発目以降は捨てる。爆発は 1 つの
     * {@code DamageSource} で範囲内の全エンティティを殴るので、これが無いと 28 個の箱すべてに
     * 当たって 28 倍のダメージが入る。砲弾や矢は 1 発ごとに別の {@code DamageSource} なので影響しない。
     */
    public boolean hurtFromHitbox(DamageSource source, float amount) {
        long now = this.level().getGameTime();
        if (now == this.lastHitGameTime && this.lastHitSource == source) {
            return false;
        }
        this.lastHitGameTime = now;
        this.lastHitSource = source;
        return this.hurt(source, amount);
    }

    /**
     * 当たり判定の箱 28 個の<b>中心</b>のワールド座標。サーバー・クライアントで同じ式を使う
     * (サーバーは {@link SiegeData}、クライアントは {@code ClientSiegeBoss} から軌道を引く。中身は同一)。
     * 胴・尾は体軸上、首はビームの標的へ曲げた首の各節の中点、頭は花の位置。
     *
     * <p>砲弾の当たり判定({@code ShellTracker})が実エンティティ無しで再現する箱とも同じ式なので、
     * 遠距離の砲撃と近距離の当たり判定がずれない。
     *
     * @return 28 個の中心。攻城戦が動いていなければ null
     */
    @javax.annotation.Nullable
    public static Vec3[] currentPartCenters(Level level) {
        if (level.isClientSide) {
            // クライアントでは箱 28 個が<b>それぞれ</b>自分の tick で呼ぶので、そのままだと
            // 同じ計算を 1 tick に 28 回することになる。tick 単位で結果を使い回す。
            long now = level.getGameTime();
            if (clientCentersTick == now && clientCenters != null) {
                return clientCenters;
            }
            clientCenters = computePartCenters(level);
            clientCentersTick = now;
            return clientCenters;
        }
        return computePartCenters(level);
    }

    /** {@link #currentPartCenters} のクライアント側キャッシュ(1 tick ぶん)。 */
    @javax.annotation.Nullable
    private static Vec3[] clientCenters;
    private static long clientCentersTick = Long.MIN_VALUE;

    @javax.annotation.Nullable
    private static Vec3[] computePartCenters(Level level) {
        double scale = OvergrownVegetationConfig.COMMON.siegeScale.get();
        Vec3 center;
        float yaw;
        float pitch;
        float neckYaw = 0.0F;
        float neckPitch = 0.0F;
        if (level.isClientSide) {
            if (!com.yayuy.overgrownvegetation.client.ClientSiegeBoss.isActive()) {
                return null;
            }
            center = com.yayuy.overgrownvegetation.client.ClientSiegeBoss.position(1.0F);
            yaw = com.yayuy.overgrownvegetation.client.ClientSiegeBoss.yaw(1.0F);
            pitch = com.yayuy.overgrownvegetation.client.ClientSiegeBoss.pitch(1.0F);
            neckYaw = com.yayuy.overgrownvegetation.client.ClientSiegeBoss.neckYaw(1.0F);
            neckPitch = com.yayuy.overgrownvegetation.client.ClientSiegeBoss.neckPitch(1.0F);
        } else if (level instanceof ServerLevel serverLevel) {
            SiegeData data = SiegeData.get(serverLevel);
            if (!data.isActive()) {
                return null;
            }
            double elapsed = data.elapsed(serverLevel);
            // 周回中か落下中かの分岐は SiegeData が持っている(クライアント側は ClientSiegeBoss が同じ分岐)。
            center = data.positionAt(elapsed);
            yaw = data.yawAt(elapsed);
            pitch = data.pitchAt(elapsed);
            if (data.beamPhase() != SiegeData.BEAM_IDLE) {
                float[] offsets = TitanicaKinematics.neckOffsets(center, yaw, pitch, scale, data.beamTarget());
                neckYaw = offsets[0];
                neckPitch = offsets[1];
            }
        } else {
            return null;
        }
        return TitanicaKinematics.partCenters(center, yaw, pitch, scale, neckYaw, neckPitch);
    }

    /**
     * 当たり判定の箱を 28 個そろえて、体に沿って並べ直す(サーバーのみ)。
     *
     * <p>並べるのを<b>親のここでやる</b>のが肝心 — 親はプレイヤーの近く(= 必ずロード済みのチャンク)に
     * 居るので毎 tick 確実に回る。箱に自分で動かせると、体の遠い端の箱が未ロードでティックされず、
     * 古い位置に取り残されて「そこだけ当たり判定がずれている」ことになる。
     */
    private void positionHitboxes(ServerLevel level) {
        Vec3[] centers = currentPartCenters(level);
        if (centers == null) {
            return;
        }
        for (int i = 0; i < this.hitboxes.length && i < centers.length; i++) {
            TitanicaHitboxEntity box = this.hitboxes[i];
            if (box == null || !box.isAlive() || box.level() != level) {
                box = TitanicaHitboxEntity.create(level, i);
                if (box == null) {
                    continue;
                }
                box.setOwner(this);
                box.placeAt(centers[i]);
                if (!level.addFreshEntity(box)) {
                    continue;
                }
                this.hitboxes[i] = box;
            }
            box.placeAt(centers[i]);
        }
    }

    /** 本体が消えるときは当たり判定の箱も片付ける(残すと見えない壁になる)。 */
    private void removeHitboxes() {
        for (int i = 0; i < this.hitboxes.length; i++) {
            if (this.hitboxes[i] != null) {
                this.hitboxes[i].discard();
                this.hitboxes[i] = null;
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        removeHitboxes();
        super.remove(reason);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // MAX_HEALTH の実値は湧かせるときに SiegeData から setBaseValue で上書きする
        // (config はエンティティ属性の登録時点ではまだ読めないため、ここでは仮の値)。
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1024.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 0.0D);
    }

    /**
     * 本体の当たり判定 = 胴の中心の 1 節ぶん。{@code siegeHitboxWidth}/{@code siegeHitboxHeight} は
     * <b>モデル単位</b>(胴の太さ 16 が基準)で、{@code siegeScale} に合わせて自動で拡大する。
     * モデル全体(頭・首・胴・尾)は {@link TitanicaHitboxEntity} 28 個が覆う。
     */
    @Override
    public EntityDimensions getDefaultDimensions(Pose pose) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float unit = (float) (cfg.siegeScale.get() / 16.0D);
        return EntityDimensions.scalable(cfg.siegeHitboxWidth.get().floatValue() * unit,
                cfg.siegeHitboxHeight.get().floatValue() * unit);
    }

    /**
     * 描画のカリング用の箱はモデルの実寸に合わせる。当たり判定(小さい)で判定すると、
     * 当たり判定が画面外なのにヒレや尾が画面内にある、という状況で丸ごと消えてしまうため。
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        double s = OvergrownVegetationConfig.COMMON.siegeScale.get();
        double half = TitanicaKinematics.MODEL_LENGTH_UNITS / 16.0D * s * 0.6D;
        double up = 110.0D / 16.0D * s;
        double down = 90.0D / 16.0D * s;
        // 本体の位置ではなく<b>描画される場所</b>(本当の胴の中心)を囲む —
        // 本体はプレイヤーの近くへ寄せてあるので、そこを中心にするとモデルの遠い側がカリングで消える。
        Vec3 c = this.trueBodyCenter(1.0F);
        return new AABB(c.x - half, c.y - down, c.z - half, c.x + half, c.y + up, c.z + half);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return true;
    }

    /**
     * 本体そのものは<b>殴れないし撃てない</b> — 当たり判定は {@link TitanicaHitboxEntity} 28 個が担当する。
     *
     * <h2>なぜ本体を的から外すのか</h2>
     * <p>本体はモデルを描くためにプレイヤーの近くへ寄せてある({@link #anchorTo})ので、その AABB は
     * 「体のどこか」ではあっても箱と重なった余分な的になる。外しておけば
     * <b>当たり判定は 28 個の箱だけ</b>になり、爆発の多重ヒット除け({@link #hurtFromHitbox})も
     * すり抜けようが無くなる。砲弾は {@link SiegeData#hurt} から直接 {@link #hurt} を呼ぶので影響しない。
     */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /** 爆発も箱のほうで受ける(本体でも受けると 1 回の爆発が 2 回入る)。 */
    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    /** {@link SiegeData} が湧かせた直後に呼ぶ。最大体力と現在体力を攻城戦の状態へ合わせる。 */
    public void syncFrom(SiegeData data) {
        var attribute = this.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(Math.max(1.0F, data.getMaxHealth()));
        }
        this.setHealth(Math.max(1.0F, data.getHealth()));
    }

    /** このエンティティの当たり判定(小さい箱)の中心。{@link #trueBodyCenter} とは別物。 */
    public Vec3 bodyCenter() {
        return this.position().add(0.0D, this.getBbHeight() * 0.5D, 0.0D);
    }

    /**
     * <b>本当の</b>胴の中心線上の点(= {@link SiegeBossMotion} の式そのもの)。当たり判定パーツと描画の基準。
     *
     * <p>このエンティティの {@code position()} は<b>そこには無い</b> — {@link #anchorTo プレイヤーの
     * 近くへ寄せてある}ので、両者は最大で全長の半分(300 ブロック超)離れる。サーバーでは毎 tick
     * 計算した値を持ち、クライアントでは {@link ClientSiegeBoss}(軌道の式)から引く。どちらも同じ式。
     */
    public Vec3 trueBodyCenter(float partialTick) {
        if (this.level().isClientSide) {
            // 攻城戦の状態がまだ届いていない一瞬だけ、本体の位置で代用する。
            return com.yayuy.overgrownvegetation.client.ClientSiegeBoss.isActive()
                    ? com.yayuy.overgrownvegetation.client.ClientSiegeBoss.position(partialTick)
                    : this.bodyCenter();
        }
        return this.trueCenter != null ? this.trueCenter : this.bodyCenter();
    }

    /**
     * 描画をずらす量(ワールド座標)= 本当の胴の中心 − このエンティティの当たり判定の中心。
     * {@link com.yayuy.overgrownvegetation.client.DorgaArchelausTitanicaRenderer} が最初に掛ける。
     */
    public Vec3 renderOffset(float partialTick) {
        double x = Mth.lerp(partialTick, this.xOld, this.getX());
        double y = Mth.lerp(partialTick, this.yOld, this.getY()) + this.getBbHeight() * 0.5D;
        double z = Mth.lerp(partialTick, this.zOld, this.getZ());
        return this.trueBodyCenter(partialTick).subtract(x, y, z);
    }

    /**
     * <b>体軸のうちプレイヤーにいちばん近い点</b>へエンティティ本体を寄せる。
     *
     * <h2>なぜこんなことをするのか</h2>
     * <p>マルチパートの当たり判定パーツは体の端から端まで並んでいるのに、<b>実際に殴れるのは胴の中央
     * 付近だけ</b>という症状が出ていた。原因は当たり判定ではなく<b>エンティティの追跡距離</b>:
     * {@code ChunkMap} はプレイヤーと<b>エンティティの位置</b>の水平距離が
     * {@code min(clientTrackingRange, プレイヤーの描画距離) × 16} を超えるとクライアントへ送らない。
     * 描画距離 12 なら 192 ブロックで、全長 615 ブロックのボスでは胴の中心の周りしか届かず、
     * 頭や尾の側ではクライアントにエンティティが存在せず、パーツも当たり判定も無かった
     * (「しっぽの付け根あたりにしか判定が無い」の正体)。
     *
     * <p>追跡距離の上限は<b>プレイヤーの描画距離</b>なので、エンティティタイプ側では広げられない。
     * そこで逆に、<b>エンティティのほうをプレイヤーへ寄せる</b>。描画は {@link #trueBodyCenter}
     * (軌道の式)から作るので、寄せても見た目は動かない。
     *
     * <p><b>当たり判定はもうこれに依存していない</b> — 箱は {@link TitanicaHitboxEntity} という
     * 独立したエンティティになったので、箱ごとに近くのプレイヤーへ届く。ここで寄せているのは
     * <b>モデルを描くため</b>だけ(届かなかったクライアントは遠距離用のインポスタが同じ絵を描く)。
     */
    private Vec3 anchorTo(ServerLevel level, Vec3 center, Vec3 forward) {
        double reach = HITBOX_HALF_UNITS / 16.0D * OvergrownVegetationConfig.COMMON.siegeScale.get();
        // 体のいちばん近くに居るプレイヤーへ寄せる(胴の中心にいちばん近い人、ではない)。
        Vec3 best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            Vec3 relative = player.position().subtract(center);
            double along = Mth.clamp(relative.dot(forward), -reach, reach);
            Vec3 onAxis = center.add(forward.scale(along));
            double distance = player.position().distanceToSqr(onAxis);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = onAxis;
            }
        }
        return best != null ? best : center;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            SiegeData data = SiegeData.get(serverLevel);
            if (!data.isActive()) {
                this.discard();
                return;
            }

            // 遠距離時とまったく同じ式で位置を決める(AI も物理も使わない)。撃破後は落下の式。
            double elapsed = data.elapsed(serverLevel);
            Vec3 center = data.positionAt(elapsed);
            this.trueCenter = center;
            float bodyYaw = data.yawAt(elapsed);
            // モデルを描かせるため、本体は体軸上のプレイヤーに近い点へ置く(anchorTo の javadoc に理由がある)。
            // 描画も当たり判定の箱も trueCenter から作るので、寄せても見た目と当たりは動かない。
            Vec3 anchor = anchorTo(serverLevel, center,
                    TitanicaKinematics.direction(bodyYaw, data.pitchAt(elapsed)).scale(-1.0D));
            this.setPos(anchor.x, anchor.y - this.getBbHeight() * 0.5D, anchor.z);
            this.setDeltaMovement(Vec3.ZERO);

            float yaw = bodyYaw;
            this.setYRot(yaw);
            this.yBodyRot = yaw;
            this.yHeadRot = yaw;
            this.setXRot(data.pitchAt(elapsed));
            // 当たり判定の箱 28 個をそろえて体に沿わせる(クライアント側は箱が自分で追従する)。
            positionHitboxes(serverLevel);
        }
    }

    /**
     * 被弾。防具値は「しきい値」として働く: 防具貫通でないダメージは {@code siegeArmor} を差し引いた残りだけが
     * 通る(バニラの防具属性は 30 = 80% 軽減が上限で、10000 のような値は表せないため)。
     * 砲弾を連射しても取りこぼさないよう、バニラの無敵時間(直後 10 tick は超過分しか入らない)は使わない。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level() instanceof ServerLevel serverLevel && SiegeData.get(serverLevel).isDying()) {
            return false;  // 落ちている最中はもう何をしても効かない。
        }
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            amount -= OvergrownVegetationConfig.COMMON.siegeArmor.get().floatValue();
            if (amount <= 0.0F) {
                return false;
            }
        }
        this.invulnerableTime = 0;
        boolean hit = super.hurt(source, amount);
        if (hit && this.level() instanceof ServerLevel serverLevel) {
            SiegeData data = SiegeData.get(serverLevel);
            // 落下中はもう体力のやり取りをしない(die() が体力を 1 に戻しているので、
            // ここで書き戻すと攻城戦の体力まで 1 に復活してしまう)。
            if (!data.isDying()) {
                // 体力の一元管理: エンティティ側で減った分を攻城戦の状態へ書き戻す。
                data.setHealthFromEntity(serverLevel, this.getHealth());
            }
        }
        return hit;
    }

    /**
     * 体力が尽きた。<b>ここでエンティティを死なせてはいけない</b> —— 撃破後は
     * {@link com.yayuy.overgrownvegetation.siege.SiegeDeathFall 地面へ落ちていく演出}があり、
     * そのあいだもモデルと当たり判定の箱はこのエンティティが運んでいる。
     *
     * <p>そこで {@code super.die} を呼ばず、体力を 1 に戻して生かしておく
     * (戻さないと {@code isDeadOrDying()} が true のままになり、バニラが 20 tick 後に消してしまう)。
     * 演出が終わったら {@link SiegeData#stop} が discard する。
     */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            SiegeData.get(serverLevel).beginDeath(serverLevel);
        }
        this.setHealth(1.0F);
    }

    /** 近づいたときだけ存在するエンティティなので、バニラの距離デスポーンには任せない。 */
    @Override
    public void checkDespawn() {
    }

    /**
     * 当たり判定は 1 辺 25 ブロック以上あるので、バニラが毎 tick 行う「AABB 内の全ブロック走査」
     * (中に居るブロックの効果・水/溶岩の判定、合わせて 1 万〜数万ブロック/tick、サーバーとクライアントの両方)
     * を止める。空を飛ぶ植物の巨体にはどちらも要らない。
     */
    @Override
    protected void checkInsideBlocks() {
    }

    @Override
    protected boolean updateInWaterStateAndDoFluidPushing() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void doPush(Entity entity) {
    }

    /**
     * バニラの「周りのエンティティを押しのける」処理を止める(軽量化、2026-09-13)。
     *
     * <p>{@code LivingEntity.aiStep} は毎 tick <b>自分の AABB ぶんのエンティティ検索</b>を掛ける。
     * このボスの AABB は 1 辺 25 ブロックあるうえ、押す({@link #doPush})ことも
     * 押される({@link #isPushable})こともしないので、結果は必ず捨てられる。
     * サーバーでもクライアントでも毎 tick 走っていた完全な無駄。
     */
    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean isAffectedByFluids() {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    protected void addPassenger(Entity passenger) {
    }

    // --- GeckoLib ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        TitanicaAnimations.register(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public double titanicaAge(float partialTick) {
        return this.tickCount + partialTick;
    }
}
