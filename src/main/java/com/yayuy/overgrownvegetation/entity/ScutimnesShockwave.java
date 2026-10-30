package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.EarthquakeShakePayload;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.quake.EarthquakeData;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * スクティムネス({@code ScutimnesEntity})の<b>叩きつけの衝撃波</b>(2026-09-23)。
 *
 * <p>ユーザー指定: ガードが終わると同時に両盾を振り上げて地面を叩き、<b>叩いた点から前方へ扇状に</b>衝撃波を出す。
 * ウォーデンのビームではなく、<b>L_Ender's Cataclysm のボスのようにブロックが少し持ち上がり、自身を起点に波打っていく</b>もの。
 * 波には<b>ガードでカットした分のダメージ</b>が乗り、<b>6 マスほどまで届き、遠いほどダメージの総量とブロックの持ち上がりが減る</b>。
 *
 * <h2>作り</h2>
 * <p>軽いエンティティ 1 体。位置 = 起点(盾が地面を叩いた点)。向き・溜めた量を同期する。
 * <ul>
 *     <li><b>サーバー</b>({@link #strike}): 毎 tick、輪の半径 {@code r = }{@link #SPEED}{@code  × 経過 tick} で、
 *     扇(正面 ±{@link #HALF_ANGLE_DEG}°、半径 {@link #RANGE})のうち輪が通り過ぎたばかりの帯に居る相手へ 1 回だけ当てる。</li>
 *     <li><b>クライアント</b>: 扇の中の列(1×1)ごとに地表のブロックを 1 回だけ探して覚え({@link #columns})、
 *     {@code client/ScutimnesShockwaveRenderer} が波の届いた列から {@link #RISE_TICKS} tick かけて持ち上げて戻す。
 *     破片の粒子も列ごとに、波が届いた tick に出す({@link #clientTick})。</li>
 * </ul>
 * <b>地形は一切変えない</b>(描画だけ)。
 * 保存もしない({@link #shouldBeSaved})—— 1 秒で消える演出なので、読み込み直して波がもう一度走るほうが困る。
 *
 * <h2>数値(仮、2026-09-23)</h2>
 * <ul>
 *     <li>扇 ±60°、半径 6(起点から)、波の速さ 0.5 ブロック/tick(12 tick で端まで)。</li>
 *     <li>ダメージ = 溜めた量 × {@link #falloff}(起点から 1 ブロックまで 1.0、6 ブロックで 0.3 へ直線)。
 *     距離は<b>相手の体の縁まで</b>で測る(輪が体に触れた瞬間に当たる)。</li>
 *     <li>波が足元を通る瞬間に<b>地面から 0.5 ブロック以上浮いていれば当たらない</b>(跳んで避けられる)。</li>
 *     <li>当たったら上へ 0.5 + 外向き 0.6(ノックバック耐性で目減り)。溜めた量 0 なら当てずに半分の力で押すだけ。</li>
 *     <li>持ち上げは近くで 1.0 ブロック → 端で 0.2 ブロック、溜めた量で 0.6〜1.0 倍。</li>
 * </ul>
 *
 * <h2>ダメージの種類</h2>
 * <p>専用の {@code overgrown_vegetation:scutimnes_shockwave}({@link #DAMAGE_TYPE})。盾で防げず({@code bypasses_shield}
 * —— 足元から突き上げる波なので、構えた盾で受けるものではない。対処は「ガード中は殴らない」か「跳ぶ」)、
 * 押し出しはこちらで付けるのでバニラのノックバックは切り({@code no_knockback})、難易度で増減しない({@code scaling: never}
 * —— 「カットした分」をそのまま返す)。json とタグが無い環境(統合前)では持ち主の近接攻撃 {@code mobAttack} に落とす。
 */
public class ScutimnesShockwave extends Entity {

    // ------------------------------------------------------------------ 形と速さ

    /** 扇の半径(ブロック、起点から)。ユーザー指定「大体 6 マス分まで届く」。 */
    public static final double RANGE = 6.0D;
    /** 扇の片側の開き(度)。正面 ±60° = 計 120°(仮)。 */
    public static final double HALF_ANGLE_DEG = 60.0D;
    private static final double COS_HALF_ANGLE = Math.cos(Math.toRadians(HALF_ANGLE_DEG));
    /** 波の速さ(ブロック/tick)。12 tick で端まで(仮)。 */
    public static final double SPEED = 0.5D;
    /** 列が持ち上がって戻るまで(tick)。 */
    public static final int RISE_TICKS = 8;
    /** 寿命(tick)。端の列が戻りきるまで + クライアントの遅れのゆとり 3 tick。 */
    public static final int LIFETIME = Mth.ceil(RANGE / SPEED) + RISE_TICKS + 3;

    // ------------------------------------------------------------------ 当たり

    /** 溜められる量の上限。仕様の「上限 60」(TaCZ の連射で一撃死を作らないため)と同じ。 */
    public static final float MAX_STORED = 60.0F;
    /** ここまでは減衰しない(ブロック)。 */
    public static final double FULL_RADIUS = 1.0D;
    /** 端({@link #RANGE})でのダメージの割合。 */
    public static final float FAR_FACTOR = 0.3F;
    /**
     * 帯の内側へのゆとり(ブロック)。輪の 1 tick の進み({@link #SPEED})に足す。
     * 輪へ向かって走ってくる相手は、tick の間で輪を飛び越えて帯に一度も入らないことがある(全力疾走で 0.28/tick)。
     * 同じ相手には 1 回しか当てないので、帯を広げても二度は当たらない。
     */
    private static final double INWARD_SLACK = 0.5D;
    /** 起点からの高さの差がこれ以内の相手にだけ当たる(地表の探索 {@link #SURFACE_SEARCH} と同じ)。 */
    private static final double VERTICAL_REACH = 3.0D;
    /** 体がここまで起点に掛かっていれば、扇の角度に関係なく当たる(叩いた真上に居る)。 */
    private static final double APEX_RADIUS = 0.3D;
    /** これ以上浮いていれば当たらない(ブロック)。 */
    public static final double AIRBORNE_GAP = 0.5D;
    private static final double PUSH_UP = 0.5D;
    private static final double PUSH_OUT = 0.6D;
    /** 溜めた量が 0 のときの押し出し(通常の何倍)。仕様「当てずに軽く押すだけ」。 */
    private static final double EMPTY_PUSH = 0.5D;

    /** 専用のダメージの種類(json・タグ・死亡メッセージは本体の担当の持ち場)。 */
    public static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("scutimnes_shockwave"));

    // ------------------------------------------------------------------ 見た目

    /** 地表を探す高さの幅(起点の下のブロックから上下、ブロック)。 */
    public static final int SURFACE_SEARCH = 3;
    /** 持ち上げの最大(ブロック)。起点の近く({@link #FULL_RADIUS} まで)。 */
    public static final float LIFT_NEAR = 1.0F;
    /** 持ち上げの最大(ブロック)。端({@link #RANGE})。 */
    public static final float LIFT_FAR = 0.2F;
    /** 溜めた量 0 のときの持ち上げの倍率(上限まで溜めると 1.0)。 */
    private static final float LIFT_EMPTY_SCALE = 0.6F;
    /** 列ごとに波の届く時刻をずらす幅(tick)。輪がきれいな円弧のままだと作り物に見える。 */
    private static final float ARRIVE_JITTER = 0.8F;
    /** 列ごとの持ち上げのばらつき(±)。 */
    private static final float LIFT_JITTER = 0.1F;

    // ------------------------------------------------------------------ 画面の揺れ

    /** 画面を揺らすプレイヤーまでの距離(起点から、ブロック)。 */
    private static final double SHAKE_REACH = 24.0D;
    /** {@link com.yayuy.overgrownvegetation.client.ClientEarthquake} の減衰半径(ここで 30% まで落ちる)。 */
    private static final int SHAKE_RADIUS = 16;
    private static final int SHAKE_RISE_TICKS = 3;
    private static final int SHAKE_FADE_TICKS = 14;
    /** 揺れの強さ(溜めた量 0 → 上限)。地割れ(既定 1.0)より弱い。 */
    private static final float SHAKE_MIN = 0.35F;
    private static final float SHAKE_MAX = 0.9F;

    /** 向き(度、Minecraft の yaw。前 = (-sin, 0, cos))。 */
    private static final EntityDataAccessor<Float> DATA_YAW =
            SynchedEntityData.defineId(ScutimnesShockwave.class, EntityDataSerializers.FLOAT);
    /** 溜めた量(0〜{@link #MAX_STORED})。 */
    private static final EntityDataAccessor<Float> DATA_STORED =
            SynchedEntityData.defineId(ScutimnesShockwave.class, EntityDataSerializers.FLOAT);

    private static boolean warnedMissingDamageType;

    /** 叩いた本人。当たりから外し、ダメージの加害者にする(消えていても波は進む)。保存しない。 */
    @Nullable
    private LivingEntity owner;
    /** もう当てた相手(エンティティ id)。1 体に 1 回だけ。 */
    private final IntSet struck = new IntOpenHashSet();
    /** 扇の中の列(クライアントで 1 回だけ探す)。向き・溜めた量が同期で変われば作り直す。 */
    @Nullable
    private List<Column> columns;

    public ScutimnesShockwave(EntityType<? extends ScutimnesShockwave> type, Level level) {
        super(type, level);
        // 動かない(move を呼ばない)ので重力も当たり判定も要らない。
        this.noPhysics = true;
        this.blocksBuilding = false;
    }

    /**
     * 衝撃波を出す。本体の {@code slam} で盾が地面を叩く tick に呼ぶ。
     *
     * @param origin        起点(盾が地面を叩いた点。y は立っている地面の高さ)
     * @param yawDegrees    扇の向き(本体の {@code getYRot()})
     * @param storedDamage  ガードで溜めた量。0〜{@link #MAX_STORED} に収める。0 なら当てずに押すだけ
     */
    public static void spawn(ServerLevel level, LivingEntity owner, Vec3 origin, float yawDegrees, float storedDamage) {
        float stored = Float.isNaN(storedDamage) ? 0.0F : Mth.clamp(storedDamage, 0.0F, MAX_STORED);
        float yaw = Mth.wrapDegrees(yawDegrees);
        ScutimnesShockwave wave = new ScutimnesShockwave(ModEntities.SCUTIMNES_SHOCKWAVE.get(), level);
        wave.owner = owner;
        wave.moveTo(origin.x, origin.y, origin.z, yaw, 0.0F);
        wave.entityData.set(DATA_YAW, yaw);
        wave.entityData.set(DATA_STORED, stored);
        level.addFreshEntity(wave);
        shake(level, origin, stored);
    }

    /**
     * 近くのプレイヤーの画面を揺らす。地割れの揺れ({@link EarthquakeShakePayload})をそのまま借りる ——
     * 強さ・立ち上がり・収まりを 1 パケットで渡せて、カメラを揺らす mixin も既にある。
     *
     * <p>クライアントの揺れの状態は 1 つしか無いので、<b>地割れの最中は送らない</b>(送ると地割れの揺れを
     * 次の再送(10 tick 後)まで小さな揺れで上書きしてしまう。地割れのほうがずっと大きい)。
     */
    private static void shake(ServerLevel level, Vec3 origin, float stored) {
        if (level.dimension() == Level.OVERWORLD && EarthquakeData.get(level).hasActive()) {
            return;
        }
        float intensity = Mth.lerp(stored / MAX_STORED, SHAKE_MIN, SHAKE_MAX);
        EarthquakeShakePayload payload = new EarthquakeShakePayload(origin.x, origin.y, origin.z, 0,
                0, SHAKE_RISE_TICKS, SHAKE_FADE_TICKS, intensity, SHAKE_RADIUS);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(origin) <= SHAKE_REACH * SHAKE_REACH) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    // ------------------------------------------------------------------ 同期

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_YAW, 0.0F);
        builder.define(DATA_STORED, 0.0F);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_YAW.equals(key) || DATA_STORED.equals(key)) {
            this.columns = null;
        }
    }

    /** 扇の向き(度)。 */
    public float yawDegrees() {
        return this.entityData.get(DATA_YAW);
    }

    /** 溜めた量(0〜{@link #MAX_STORED})。 */
    public float storedDamage() {
        return this.entityData.get(DATA_STORED);
    }

    // ------------------------------------------------------------------ tick

    /**
     * {@code super.tick()} は呼ばない(火・水の押し流し・ポータルの処理は要らない。バニラの {@code Marker} と同じ)。
     * 経過 tick は両側とも {@link #tickCount}(レベルが {@code tick()} の前に 1 増やす)。クライアントは
     * 受け取った時点から数えるので、サーバーより通信の遅れぶん後ろへずれる —— 当たりのパケットも同じだけ遅れて
     * 届くので、見た目と当たりはむしろ揃う。
     */
    @Override
    public void tick() {
        if (this.level().isClientSide) {
            clientTick();
            return;
        }
        if (this.tickCount > LIFETIME) {
            this.discard();
            return;
        }
        strike((ServerLevel) this.level());
    }

    /** 輪が通り過ぎたばかりの帯に居る相手へ当てる。 */
    private void strike(ServerLevel level) {
        double front = SPEED * this.tickCount;
        double back = front - SPEED - INWARD_SLACK;
        if (back > RANGE) {
            return;
        }
        Vec3 o = this.position();
        double reach = Math.min(front, RANGE) + 3.0D;
        AABB box = new AABB(o.x - reach, o.y - VERTICAL_REACH, o.z - reach,
                o.x + reach, o.y + VERTICAL_REACH + 1.0D, o.z + reach);
        DamageSource source = null;
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, box, this::isFoe)) {
            if (this.struck.contains(victim.getId())) {
                continue;
            }
            double dx = victim.getX() - o.x;
            double dz = victim.getZ() - o.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double half = victim.getBbWidth() * 0.5D;
            double edge = Math.max(0.0D, dist - half);
            if (edge > RANGE || edge > front || edge < back) {
                continue;
            }
            if (Math.abs(victim.getY() - o.y) > VERTICAL_REACH || !inFan(dx, dz, dist, half)) {
                continue;
            }
            // 浮いている相手は、次の tick も帯の中に居れば(着地していれば)その時に当たる。印は付けない。
            if (!isGrounded(level, victim)) {
                continue;
            }
            this.struck.add(victim.getId());
            if (source == null) {
                source = damageSource(level);
            }
            hit(victim, source, edge, dx, dz, dist);
        }
    }

    private void hit(LivingEntity victim, DamageSource source, double edge, double dx, double dz, double dist) {
        float stored = storedDamage();
        double push = Mth.clamp(1.0D - victim.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
        if (stored > 0.0F) {
            victim.hurt(source, stored * falloff(edge));
        } else {
            push *= EMPTY_PUSH;
        }
        if (push <= 0.0D) {
            return;
        }
        double ox;
        double oz;
        if (dist > 1.0E-3D) {
            ox = dx / dist;
            oz = dz / dist;
        } else {
            Vec3 f = forward();
            ox = f.x;
            oz = f.z;
        }
        victim.push(ox * PUSH_OUT * push, PUSH_UP * push, oz * PUSH_OUT * push);
        // プレイヤーの速度はクライアントが持っている。これを立てないと押し出しが届かない。
        victim.hurtMarked = true;
    }

    /** 扇の中か。体の幅のぶんだけ角度にゆとりを持たせる(扇の縁に半身が掛かっていれば当たる)。 */
    private boolean inFan(double dx, double dz, double dist, double half) {
        if (dist <= half + APEX_RADIUS) {
            return true;
        }
        Vec3 f = forward();
        double cos = Mth.clamp((dx * f.x + dz * f.z) / dist, -1.0D, 1.0D);
        double slack = Math.asin(Math.min(1.0D, half / dist));
        return Math.acos(cos) <= Math.toRadians(HALF_ANGLE_DEG) + slack;
    }

    /**
     * 足元から {@link #AIRBORNE_GAP} 以内に地面(当たり判定のあるブロック、または上に立てるエンティティ)があるか。
     * 足の下の薄い箱で調べる —— {@code onGround()} だと段差を降りる途中の 1 tick でも外れてしまう。
     * 横は少し細らせる(壁に張り付いているとき、壁の面に触れただけで「足場あり」にならないように)。
     *
     * <p>乗り物に乗っている相手は、いちばん下の乗り物が地面に居るかで決める。上に立てるエンティティ(ボート・シュルカーなど)も地面に数える
     * (2026-09-24、レビューの指摘)。初版はブロックだけを見ていたので、馬に乗った相手(足が地面の 0.84 上)や地面に置いたボートの上に
     * 立つ相手(0.56 上)は毎 tick「浮いている」扱いで、波に一度も当たらなかった —— 跳ばずに騎乗やボート 1 つで溜めた量を丸ごと
     * 無効にできた(ユーザー指定の対処は「ガード中は殴らない」か「跳ぶ」)。
     */
    public static boolean isGrounded(Level level, Entity entity) {
        if (entity.isPassenger()) {
            return isGrounded(level, entity.getRootVehicle());
        }
        AABB bb = entity.getBoundingBox();
        AABB probe = new AABB(bb.minX + 1.0E-3D, bb.minY - AIRBORNE_GAP, bb.minZ + 1.0E-3D,
                bb.maxX - 1.0E-3D, bb.minY, bb.maxZ - 1.0E-3D);
        return !level.noBlockCollision(entity, probe) || !level.getEntityCollisions(entity, probe).isEmpty();
    }

    /**
     * 当ててよい相手か。ファルキムネスの {@code isFoe} と同じ線引き: 自分(持ち主)・乗客・クリエイティブ/観戦者は除き、
     * 持ち主の標的なら当て、それ以外は<b>汚染側のグループに属していない者</b>だけ(仲間は巻き込まない)。
     */
    private boolean isFoe(LivingEntity e) {
        if (e == this.owner || !e.isAlive() || e.isSpectator()) {
            return false;
        }
        if (this.owner != null && (e.isPassengerOfSameVehicle(this.owner) || e.getVehicle() == this.owner)) {
            return false;
        }
        if (e instanceof Player p && p.isCreative()) {
            return false;
        }
        if (this.owner instanceof Mob mob && e == mob.getTarget()) {
            return true;
        }
        return MobGroup.of(e) == null;
    }

    /**
     * 加害者は持ち主、直接の出どころはこの波(死亡メッセージは「〜は(持ち主)に…」、持ち主が消えていれば波の名前)。
     * 専用のダメージの種類が登録されていなければ(統合前)、持ち主の近接攻撃に落とす。
     */
    private DamageSource damageSource(ServerLevel level) {
        LivingEntity attacker = this.owner != null && !this.owner.isRemoved() ? this.owner : null;
        Optional<Holder.Reference<DamageType>> type =
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(DAMAGE_TYPE);
        if (type.isPresent()) {
            return new DamageSource(type.get(), this, attacker);
        }
        if (!warnedMissingDamageType) {
            warnedMissingDamageType = true;
            OvergrownVegetation.LOGGER.warn("ダメージの種類 {} が登録されていない。衝撃波は持ち主の近接攻撃として当てる。",
                    DAMAGE_TYPE.location());
        }
        return attacker != null ? level.damageSources().mobAttack(attacker) : level.damageSources().generic();
    }

    /**
     * 距離による減衰。起点から {@link #FULL_RADIUS} までは 1.0、{@link #RANGE} で {@link #FAR_FACTOR} へ直線(その外も 0.3 で止める
     * —— 範囲の外は当たり自体を出さない)。
     */
    public static float falloff(double distance) {
        double t = Mth.clamp((distance - FULL_RADIUS) / (RANGE - FULL_RADIUS), 0.0D, 1.0D);
        return (float) (1.0D - (1.0D - FAR_FACTOR) * t);
    }

    /** 扇の向き(水平の単位ベクトル)。 */
    public Vec3 forward() {
        float yaw = yawDegrees() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0.0D, Mth.cos(yaw));
    }

    // ------------------------------------------------------------------ 見た目(クライアント)

    /**
     * 扇の中の 1 列。{@code arriveTick} に波が届き、そこから {@link #RISE_TICKS} tick かけて {@code lift} ブロックまで上がって戻る。
     *
     * @param pos        持ち上げる地表のブロック
     * @param state      探した時点のそのブロック(描く前に今の状態と比べ、変わっていれば描かない)
     * @param distance   起点から列の中心まで(水平、ブロック)
     * @param outX       起点から外向きの単位ベクトル(傾ける向き)の x
     * @param outZ       同じく z
     * @param arriveTick 波が届く経過 tick(ばらつき込み)
     * @param lift       持ち上げの最大(ブロック。距離・溜めた量・ばらつき込み)
     * @param inset      横の縮め(隣の列と面が重なってちらつかないよう、列ごとに少しずつ違う)
     */
    public record Column(BlockPos pos, BlockState state, float distance, float outX, float outZ,
                         float arriveTick, float lift, float inset) {
    }

    /** 扇の中の列(初めて呼ばれたときに地表を探す。毎フレームは探さない)。 */
    public List<Column> columns() {
        if (this.columns == null) {
            this.columns = buildColumns(this.level(), this.position(), yawDegrees(), storedDamage());
        }
        return this.columns;
    }

    /**
     * 扇の中の列を並べる。列の中心が扇の中(正面 ±{@link #HALF_ANGLE_DEG}°、半径 {@link #RANGE})にあるものと、
     * 起点のある列。地表が見つからない列({@link #findSurface})は飛ばす。
     */
    public static List<Column> buildColumns(BlockGetter level, Vec3 origin, float yawDegrees, float stored) {
        float yaw = yawDegrees * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        int baseY = Mth.floor(origin.y + 1.0E-3D) - 1;
        int ox = Mth.floor(origin.x);
        int oz = Mth.floor(origin.z);
        int r = Mth.ceil(RANGE) + 1;
        float scale = Mth.lerp(Mth.clamp(stored / MAX_STORED, 0.0F, 1.0F), LIFT_EMPTY_SCALE, 1.0F);
        List<Column> out = new ArrayList<>();
        for (int x = ox - r; x <= ox + r; x++) {
            for (int z = oz - r; z <= oz + r; z++) {
                double cx = x + 0.5D - origin.x;
                double cz = z + 0.5D - origin.z;
                double d = Math.sqrt(cx * cx + cz * cz);
                boolean apex = x == ox && z == oz;
                if (!apex && (d > RANGE || (cx * fx + cz * fz) / d < COS_HALF_ANGLE)) {
                    continue;
                }
                BlockPos ground = findSurface(level, x, baseY, z);
                if (ground == null) {
                    continue;
                }
                long seed = hash(x, ground.getY(), z);
                float j1 = (seed & 0xFFFFL) / 65535.0F;
                float j2 = ((seed >>> 16) & 0xFFFFL) / 65535.0F;
                double ux = d > 1.0E-4D ? cx / d : fx;
                double uz = d > 1.0E-4D ? cz / d : fz;
                float arrive = Math.max(0.0F, (float) (d / SPEED) + (j1 - 0.5F) * ARRIVE_JITTER);
                float lift = liftAt(d) * scale * (1.0F + (j2 * 2.0F - 1.0F) * LIFT_JITTER);
                float inset = 0.985F + 0.01F * j1;
                out.add(new Column(ground, level.getBlockState(ground), (float) d, (float) ux, (float) uz,
                        arrive, lift, inset));
            }
        }
        return out;
    }

    /** 列ごとのばらつきの種(位置だけで決まる。同じ場所で叩けば同じ崩れ方)。 */
    private static long hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 32);
    }

    /** 持ち上げの最大(ブロック)。起点から {@link #FULL_RADIUS} まで {@link #LIFT_NEAR}、{@link #RANGE} で {@link #LIFT_FAR} へ直線。 */
    public static float liftAt(double distance) {
        double t = Mth.clamp((distance - FULL_RADIUS) / (RANGE - FULL_RADIUS), 0.0D, 1.0D);
        return (float) (LIFT_NEAR - (LIFT_NEAR - LIFT_FAR) * t);
    }

    /**
     * 列 ({@code x}, {@code z}) の地表のブロック。{@code baseY}(起点の下のブロック)から<b>近い順</b>に
     * 0, +1, −1, +2, −2, +3, −3 と見て、「地面(当たり判定がある)で、真上が地面でない」最初のものを返す。
     *
     * <p>空気・液体・草花のような当たり判定の無いものは地面に数えない(その下の地面を使う)。
     * <b>上から探さない</b>のは、木の下で叩いたとき、±3 の中にある葉(当たり判定がある)を先に拾って
     * 地面ではなく葉を持ち上げてしまうため。
     */
    @Nullable
    public static BlockPos findSurface(BlockGetter level, int x, int baseY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= SURFACE_SEARCH * 2; i++) {
            int dy = (i + 1) / 2 * ((i & 1) == 1 ? 1 : -1);
            pos.set(x, baseY + dy, z);
            if (isGround(level, pos) && !isGround(level, pos.above())) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static boolean isGround(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * 叩いた点の土煙と、波が届いた列の破片。列を知っているクライアントで出す(サーバーから
     * {@code sendParticles} すると、輪の 1 tick ごとに弧の点の数だけパケットが飛ぶ。持ち上がる列ともずれる)。
     */
    private void clientTick() {
        if (this.tickCount > LIFETIME) {
            return;
        }
        List<Column> cols = columns();
        float now = this.tickCount;
        // 最初の tick は 0 秒目ちょうどに届く列(叩いた点)も拾う。
        float prev = this.tickCount == 1 ? -1.0F : now - 1.0F;
        for (Column c : cols) {
            if (this.tickCount == 1 && c.distance() < 0.75F) {
                burst(c, 8);
            }
            if (c.arriveTick() > prev && c.arriveTick() <= now) {
                debris(c);
            }
        }
    }

    /** 叩いた点。破片を周りへ散らす。 */
    private void burst(Column c, int count) {
        BlockParticleOption block = new BlockParticleOption(ParticleTypes.BLOCK, c.state());
        double x = c.pos().getX() + 0.5D;
        double y = c.pos().getY() + 1.05D;
        double z = c.pos().getZ() + 0.5D;
        for (int i = 0; i < count; i++) {
            this.level().addParticle(block, x + (this.random.nextDouble() - 0.5D) * 1.2D, y,
                    z + (this.random.nextDouble() - 0.5D) * 1.2D, 0.0D, 0.25D, 0.0D);
        }
    }

    /** 波が届いた列。土が噴き上がる柱({@code DUST_PILLAR}、メイスの叩きつけと同じもの)と破片。高く上がる列ほど多い。 */
    private void debris(Column c) {
        BlockParticleOption pillar = new BlockParticleOption(ParticleTypes.DUST_PILLAR, c.state());
        BlockParticleOption block = new BlockParticleOption(ParticleTypes.BLOCK, c.state());
        double x = c.pos().getX() + 0.5D;
        double y = c.pos().getY() + 1.02D;
        double z = c.pos().getZ() + 0.5D;
        int pillars = c.lift() > 0.55F ? 2 : 1;
        for (int i = 0; i < pillars; i++) {
            this.level().addParticle(pillar, x + (this.random.nextDouble() - 0.5D) * 0.7D, y,
                    z + (this.random.nextDouble() - 0.5D) * 0.7D, 0.0D, 0.1D + 0.3D * c.lift(), 0.0D);
        }
        this.level().addParticle(block, x, y, z, c.outX() * 0.2D, 0.2D, c.outZ() * 0.2D);
    }

    /** 描画の打ち切りの箱。扇全体と、持ち上がるブロックの高さまで({@code EntityRenderer#shouldRender} が使う)。 */
    @Override
    public AABB getBoundingBoxForCulling() {
        double r = RANGE + 1.0D;
        return new AABB(this.getX() - r, this.getY() - SURFACE_SEARCH - 1.5D, this.getZ() - r,
                this.getX() + r, this.getY() + SURFACE_SEARCH + 2.5D, this.getZ() + r);
    }

    /** 体は 0.5 角しかないので、バニラの既定(箱の大きさ × 64)だと 32 ブロック先で消える。波は遠くからも見えてほしい。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        double d = 96.0D * getViewScale();
        return distanceSq < d * d;
    }

    // ------------------------------------------------------------------ 触れない・残らない

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_YAW, tag.getFloat("Yaw"));
        this.entityData.set(DATA_STORED, Mth.clamp(tag.getFloat("Stored"), 0.0F, MAX_STORED));
    }

    /** 保存はしない({@link #shouldBeSaved})が、{@code /data get} で中身が見えるように書いておく。 */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Yaw", yawDegrees());
        tag.putFloat("Stored", storedDamage());
    }
}
