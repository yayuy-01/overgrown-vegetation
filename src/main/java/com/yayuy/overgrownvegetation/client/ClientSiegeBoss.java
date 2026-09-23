package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload.BeamState;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload.TentacleVolley;
import com.yayuy.overgrownvegetation.siege.SiegeBossMotion;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.siege.SiegeDeathFall;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * 超大型レイドボス(攻城戦)のクライアント側の状態。
 *
 * <p>{@link SiegeBossStatePayload} を受け取るたびに軌道パラメータ・経過 tick・ビームの段階を丸ごと
 * 差し替え(パケット取りこぼしに強い)、あいだのクライアント tick では経過 tick を自前で進める。
 * 位置はサーバーと同じ {@link SiegeBossMotion} の式から毎フレーム計算するので、
 * 座標の同期パケットは一切要らない。
 *
 * <p>首の向き(花のビームの標的へ向ける角度)もここで tick ごとになめらかに追従させ、
 * {@link DorgaArchelausTitanicaModel} が首の骨に、{@link SiegeBeamRenderer} がビームの始点に使う。
 * 実際の描画は {@link SiegeBossRenderer}(遠距離のインポスタ)、
 * {@link DorgaArchelausTitanicaRenderer}(近距離の実エンティティ)、{@link SiegeBeamRenderer}(ビーム)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientSiegeBoss {

    /**
     * サーバーから届いた首の向きと自前の追従がこれ以上ずれていたら、合わせ直す(度)。
     * 通常は同じ式・同じ速さで追従しているので発動しない — 途中ログインや長いラグのときだけ。
     */
    private static final float NECK_RESYNC_THRESHOLD = 12.0F;

    private static boolean active;
    private static SiegeBossMotion motion = new SiegeBossMotion(0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 0.0D, 1.0D);
    private static double elapsed;
    private static float scale = 1.0F;
    private static float health;
    private static float maxHealth;

    private static BeamState flower = BeamState.IDLE;
    private static double flowerElapsed;
    private static BeamState tentacle = BeamState.IDLE;
    private static double tentacleElapsed;
    /** 触手ビーム 1 斉射ぶんの「どの触手から・どこへ」(既定 8 本)。 */
    private static TentacleVolley volley = TentacleVolley.EMPTY;

    /**
     * 撃破された瞬間の経過 tick。<b>0 以上なら落下中</b>で、位置・向きは周回の式ではなく
     * {@link SiegeDeathFall} から引く(サーバーの {@code SiegeData} とまったく同じ分岐)。
     */
    private static float deathAt = -1.0F;
    /** 落ちて着地する Y。 */
    private static float deathGroundY;

    private static float neckYaw;
    private static float neckYawO;
    private static float neckPitch;
    private static float neckPitchO;

    /**
     * このクライアントが読み込んでいる近距離用の実エンティティの数(通常は 0 か 1)。
     * 1 以上のあいだ {@link SiegeBossRenderer} は遠距離用のインポスタを描かない — 二重に見えるため。
     *
     * <p>「実エンティティが居るか」をサーバーから送られるフラグで判断してはいけない: 近くにいる
     * プレイヤーと 1000 ブロック離れたプレイヤーが同時に居るとき、前者にはエンティティ、後者には
     * インポスタを見せる必要があり、正解がクライアントごとに違うため。
     */
    private static int loadedEntities;

    public static void onPayload(SiegeBossStatePayload payload) {
        if (!payload.active()) {
            clear();
            return;
        }
        motion = new SiegeBossMotion(payload.centerX(), payload.centerY(), payload.centerZ(),
                payload.radius(), payload.altitude(), payload.speed(),
                payload.approach(), payload.minRadius());
        elapsed = payload.elapsed();
        scale = payload.scale();
        health = payload.health();
        maxHealth = payload.maxHealth();
        flower = payload.flower();
        flowerElapsed = payload.flower().elapsed();
        tentacle = payload.tentacle();
        tentacleElapsed = payload.tentacle().elapsed();
        volley = payload.volley();
        deathAt = payload.deathAt();
        deathGroundY = payload.deathGroundY();
        // 首は毎 tick 自前で追従させるが、大きくずれていたら(途中ログイン等)サーバーの値へ合わせ直す。
        if (!active || Math.abs(payload.neckYaw() - neckYaw) > NECK_RESYNC_THRESHOLD
                || Math.abs(payload.neckPitch() - neckPitch) > NECK_RESYNC_THRESHOLD) {
            neckYaw = neckYawO = payload.neckYaw();
            neckPitch = neckPitchO = payload.neckPitch();
        }
        active = true;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            clear();
            return;
        }
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        elapsed += 1.0D;
        flowerElapsed += 1.0D;
        tentacleElapsed += 1.0D;

        // 首の追従: ビーム中は標的へ速く、撃ち終わったあとは正面へ<b>ゆっくり</b>戻る。
        // 速さは config(サーバーの SiegeData.tickNeck と同じ値)なので、両側が同じ角度に落ち着く。
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        boolean idle = flower.phase() == SiegeData.BEAM_IDLE;
        float[] desired = idle
                ? new float[]{0.0F, 0.0F}
                : TitanicaKinematics.neckOffsets(position(0.0F), yaw(0.0F), pitch(0.0F), scale, flower.target());
        float rate = (float) (idle ? cfg.siegeNeckReturnSpeed.get().doubleValue() : cfg.siegeNeckFollowSpeed.get().doubleValue());
        neckYawO = neckYaw;
        neckPitchO = neckPitch;
        neckYaw += (desired[0] - neckYaw) * rate;
        neckPitch += (desired[1] - neckPitch) * rate;
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() instanceof DorgaArchelausTitanicaEntity) {
            loadedEntities++;
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() instanceof DorgaArchelausTitanicaEntity) {
            loadedEntities = Math.max(0, loadedEntities - 1);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        loadedEntities = 0;
    }

    /** 近距離用の実エンティティがこのクライアントに読み込まれているか。 */
    public static boolean realEntityLoaded() {
        return loadedEntities > 0;
    }

    public static void clear() {
        active = false;
        flower = BeamState.IDLE;
        tentacle = BeamState.IDLE;
        volley = TentacleVolley.EMPTY;
        deathAt = -1.0F;
        neckYaw = neckYawO = 0.0F;
        neckPitch = neckPitchO = 0.0F;
    }

    public static boolean isActive() {
        return active;
    }

    /** 現在の軌道(砲台の照準補助が偏差射撃の解を求めるのに使う)。 */
    public static SiegeBossMotion motion() {
        return motion;
    }

    public static float scale() {
        return scale;
    }

    public static float health() {
        return health;
    }

    public static float maxHealth() {
        return maxHealth;
    }

    /** サブ tick 補間込みの、攻城戦開始からの経過 tick。 */
    public static double elapsed(float partialTick) {
        return elapsed + partialTick;
    }

    /** 撃破されて落下中か。 */
    public static boolean isDying() {
        return deathAt >= 0.0F;
    }

    /**
     * ボスの位置(胴の中心線上の点)。<b>周回の式と落下の式のどちらを使うかはここで決まる</b> —
     * サーバーの {@code SiegeData.positionAt} とまったく同じ分岐で、同じ値になる。
     */
    public static Vec3 position(float partialTick) {
        double t = elapsed(partialTick);
        return isDying() ? SiegeDeathFall.positionAt(motion, deathAt, deathGroundY, t) : motion.positionAt(t);
    }

    public static float yaw(float partialTick) {
        return isDying() ? SiegeDeathFall.yawAt(motion, deathAt) : motion.yawAt(elapsed(partialTick));
    }

    public static float pitch(float partialTick) {
        double t = elapsed(partialTick);
        return isDying() ? SiegeDeathFall.pitchAt(motion, deathAt, deathGroundY, t) : motion.pitchAt(t);
    }

    // --- 首 ---

    /** 首のヨー差分(胴基準・度)。 */
    public static float neckYaw(float partialTick) {
        return Mth.lerp(partialTick, neckYawO, neckYaw);
    }

    /** 首のピッチ差分(胴基準・度)。 */
    public static float neckPitch(float partialTick) {
        return Mth.lerp(partialTick, neckPitchO, neckPitch);
    }

    /** 花(ビームの発射点)のワールド座標。 */
    public static Vec3 flowerPosition(float partialTick) {
        return TitanicaKinematics.flowerPosition(position(partialTick), yaw(partialTick), pitch(partialTick),
                scale, neckYaw(partialTick), neckPitch(partialTick));
    }

    // --- 花のビーム ---

    public static int flowerPhase() {
        return flower.phase();
    }

    public static BeamState flower() {
        return flower;
    }

    /** 現在の段階の進み具合(0〜1)。 */
    public static float flowerProgress(float partialTick) {
        return progress(flower, flowerElapsed + partialTick);
    }

    // --- 触手ビーム ---

    public static int tentaclePhase() {
        return tentacle.phase();
    }

    public static BeamState tentacle() {
        return tentacle;
    }

    /** 触手ビーム 1 斉射ぶんの「どの触手から・どこへ」。 */
    public static TentacleVolley volley() {
        return volley;
    }

    public static float tentacleProgress(float partialTick) {
        return progress(tentacle, tentacleElapsed + partialTick);
    }

    /**
     * 触手ビームが今の段階に入ってからの経過 tick(サブ tick 補間込み)。
     * 斉射は 1 本ずつ時間差で走るので、描くほうは「何 tick 経ったか」から
     * どの本がいま見えているかを自分で求める({@link SiegeBeamRenderer})。
     */
    public static double tentacleElapsed(float partialTick) {
        return tentacleElapsed + partialTick;
    }

    private static float progress(BeamState state, double elapsedTicks) {
        int length = switch (state.phase()) {
            case SiegeData.BEAM_CHARGE -> state.chargeTicks();
            case SiegeData.BEAM_FIRE -> state.fireTicks();
            case SiegeData.BEAM_RECOVER -> state.recoverTicks();
            default -> 1;
        };
        return (float) Mth.clamp(elapsedTicks / Math.max(1, length), 0.0D, 1.0D);
    }

    private ClientSiegeBoss() {
    }
}
