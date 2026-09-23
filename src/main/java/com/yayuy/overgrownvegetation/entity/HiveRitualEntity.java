package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 寄生植物装備を受け取る<b>儀式</b>の蔦とカメラ(仮実装、2026-09-18)。ハイヴノードを右クリックすると
 * 儀式の広間の決まった位置に湧き、蔦のアニメーション({@code animations/hive_ritual_vines.animation.json}、
 * Blockbench 製 37 秒 + 導入 5 秒)を 1 回再生して消える。
 *
 * <h2>仕組み</h2>
 * <ul>
 *   <li>位置 = アニメーションの原点(Blockbench の (0,0,0))。向き {@link #getYRot()} = プレイヤーがノードを見る向き。
 *       このエンティティは広間に固定で、プレイヤーは別の座席({@link HiveRitualSeatEntity})に乗る。
 *       座席はアニメの body の道筋どおりに動くので、実際の位置と見た目が一致する。</li>
 *   <li>プレイヤー本体の動きは同じアニメの人体の骨だけを抜いた {@code player_animations/hive_ritual.json} を
 *       PAL で再生する(クライアント側 {@code HiveRitualClient})。</li>
 *   <li>視点は蔦モデルの中の空の {@code camera} 骨(カット割り。{@code tools/ritual-assets/CameraCompose})に追従する
 *       ({@code HiveRitualCameraMixin})。導入の間は普通の三人称。</li>
 *   <li>時間の管理は座席側。ここは座席が消えたら自分も消える。</li>
 * </ul>
 *
 * <p>装備そのものはまだ無い。{@link HiveRitualSeatEntity} の {@code reward} を差し替えるだけで本実装にできる。
 */
public class HiveRitualEntity extends Entity implements GeoEntity {

    /** 再生するアニメーション名(JSON の animations のキー)。PAL 側の ID も同じ名前。 */
    public static final String ANIMATION_NAME = "hive_ritual";
    /** 胞子の塊が胸に刺さる瞬間(tick)。ここで装備を受け取る。節目の時刻はアニメから焼いた {@link HiveRitualPath} が持つ。 */
    public static int strikeTick() {
        return (int) (HiveRitualPath.get().strikeSeconds() * 20.0);
    }

    /** 演出の終わり(tick)。ここで降ろす。 */
    public static int endTick() {
        return (int) (HiveRitualPath.get().lengthSeconds() * 20.0);
    }

    private static final RawAnimation RITUAL = RawAnimation.begin().thenPlayAndHold(ANIMATION_NAME);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 座席(サーバーだけ)。消えていたら自分も消える。 */
    private HiveRitualSeatEntity seat;

    public HiveRitualEntity(EntityType<? extends HiveRitualEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.blocksBuilding = false;
        setNoGravity(true);
    }

    /** Minecraft の yaw(度)から前方の単位ベクトル。 */
    public static Vec3 forward(float yawDeg) {
        float r = yawDeg * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0.0D, Mth.cos(r));
    }

    /**
     * ハイヴノードを右クリックしたプレイヤーの儀式を始める。
     * ノードが儀式の広間の位置を知っていれば(巨大樹の生成で置かれたノード)そこで、
     * 知らなければ(スポーンエッグなど)ノードの手前 7 ブロックを原点にして、ノードの方を向いて始める。
     * プレイヤーは今いる場所から歩き始める(導入)。
     *
     * @return 始められたか(既に誰かの儀式が進行中なら false)
     */
    public static boolean start(ServerLevel level, HiveNodeEntity node, ServerPlayer player) {
        if (player.getVehicle() instanceof HiveRitualSeatEntity) {
            // 儀式の最中(導入で歩いてノードに触れる瞬間など)にもう一度右クリックが通ると、座席と儀式が作り直されて
            // クライアントのプレイヤーアニメとカメラの基準がずれる(2026-09-19 のログで 5〜6 秒おきの二重開始)。無視する。
            return false;
        }
        BlockPos center = node.ritualCenter();
        float yaw;
        Vec3 origin;
        if (center != null) {
            yaw = node.ritualYaw();
            // 演出の蔦はファイル座標で z ≈ -10〜76 px に広がる(原点の後ろ寄り)。広間の中心に収めるため、
            // 原点を中心からノード側へ 2 ブロック寄せる。立ち位置はその 1 ブロック後ろ。
            origin = new Vec3(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D).add(forward(yaw).scale(2.0D));
        } else {
            Vec3 toNode = node.position().subtract(player.position());
            yaw = (float) Math.toDegrees(Math.atan2(-toNode.x, toNode.z));
            // ノード中心の 7 ブロック手前(導入で 3.5 ブロック手前まで歩いて触れる)。高さはプレイヤーの足元。
            origin = new Vec3(node.getX(), player.getY(), node.getZ()).subtract(forward(yaw).scale(7.0D));
        }
        if (!level.getEntitiesOfClass(HiveRitualEntity.class, new AABB(origin, origin).inflate(12.0D)).isEmpty()) {
            return false;
        }
        HiveRitualEntity ritual = ModEntities.HIVE_RITUAL.get().create(level);
        HiveRitualSeatEntity seat = ModEntities.HIVE_RITUAL_SEAT.get().create(level);
        if (ritual == null || seat == null) {
            return false;
        }
        ritual.moveTo(origin.x, origin.y, origin.z, yaw, 0.0F);
        level.addFreshEntity(ritual);
        seat.setup(ritual, player.position());
        level.addFreshEntity(seat);
        ritual.seat = seat;
        // force = true: 降車クールダウンを無視する。
        if (!player.startRiding(seat, true)) {
            seat.discard();
            ritual.discard();
            return false;
        }
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.hive_ritual.begin"), true);
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2.0F, 0.5F);
        OvergrownVegetation.LOGGER.info("HiveRitual: {} の儀式を {} (yaw {}) で開始、歩き始め {}", player.getName().getString(),
                BlockPos.containing(origin), yaw, player.blockPosition());
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "ritual", 0, state -> state.setAndContinue(RITUAL)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        if (seat == null || !seat.isAlive()) {
            discard();
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    /** ワールドを閉じたら儀式は残さない。 */
    @Override
    public boolean shouldBeSaved() {
        return false;
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
}
