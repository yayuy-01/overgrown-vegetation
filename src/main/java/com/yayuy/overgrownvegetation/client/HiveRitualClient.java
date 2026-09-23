package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.HiveRitualEntity;
import com.yayuy.overgrownvegetation.entity.HiveRitualPath;
import com.yayuy.overgrownvegetation.entity.HiveRitualSeatEntity;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 儀式({@link HiveRitualEntity})のクライアント側。
 * <ul>
 *   <li><b>PAL</b>: 「儀式エンティティに乗っているプレイヤー」を毎 tick 見て、乗った瞬間にそのプレイヤーの
 *   PAL レイヤーで {@code overgrown_vegetation:hive_ritual}({@code player_animations/hive_ritual.json})を再生し、
 *   降りたら止める。自分でも他人でも同じ(同期パケット不要)。</li>
 *   <li><b>視点</b>: 自分が乗っている間は三人称に切り替え(自分の体が描かれるように)、視点の位置・向きは
 *   蔦モデルの {@code camera} 骨({@link HiveRitualRenderer} が毎フレーム渡す)に固定する({@code HiveRitualCameraMixin})。
 *   降りたら元の視点に戻す。</li>
 *   <li><b>暗転</b>: 胸を刺されて気を失う 17.6 秒から目を覚ます 30 秒まで画面を黒くする(乗っている本人だけ)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class HiveRitualClient {

    /** PAL のアニメーションレイヤー ID(プレイヤーごとに 1 つ持つコントローラーの名前)。 */
    public static final ResourceLocation LAYER = OvergrownVegetation.id("hive_ritual_layer");
    /** 再生するプレイヤーアニメの ID = ResourceLocation(modid, JSON の animations のキー)。 */
    public static final ResourceLocation ANIMATION = OvergrownVegetation.id(HiveRitualEntity.ANIMATION_NAME);

    /** 暗転の時間(秒)。刺されて 0.4 秒後から 1.2 秒で真っ暗、目を覚ます直前から 1.5 秒で明ける。節目はアニメから焼いた HiveRitualPath。 */
    private static float fadeInStart() {
        return (float) HiveRitualPath.get().strikeSeconds() + 0.4F;
    }

    private static float fadeInEnd() {
        return fadeInStart() + 1.2F;
    }

    private static float fadeOutStart() {
        return (float) HiveRitualPath.get().wakeSeconds() - 0.2F;
    }

    private static float fadeOutEnd() {
        return fadeOutStart() + 1.5F;
    }

    /** いま儀式アニメを再生させているプレイヤー。 */
    /** 儀式のアニメを再生中のプレイヤー → 乗っている座席のエンティティ id。座席が変わったら最初から掛け直す。 */
    private static final java.util.Map<UUID, Integer> PLAYING = new java.util.HashMap<>();

    private static CameraType savedCameraType;
    private static boolean cameraTypeSaved;

    /** {@link HiveRitualRenderer} が最後に渡した camera 骨の位置・向き。 */
    private static int cameraEntityId = -1;
    private static double cameraX, cameraY, cameraZ;
    private static float cameraYaw, cameraPitch;
    /** camera 骨の座標系で「前」にあたる軸(儀式の最初の数フレームで校正する)。 */
    private static Vector4f forwardAxis;
    private static final Vector4f[] AXES = {
            new Vector4f(0, 0, -1, 0), new Vector4f(0, 0, 1, 0),
            new Vector4f(-1, 0, 0, 0), new Vector4f(1, 0, 0, 0),
            new Vector4f(0, 1, 0, 0), new Vector4f(0, -1, 0, 0)};

    private HiveRitualClient() {
    }

    /** {@code FMLClientSetupEvent#enqueueWork} から呼ぶ。全プレイヤーに儀式用の PAL レイヤーを持たせる。 */
    public static void registerPlayerAnimationLayer() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000,
                player -> new PlayerAnimationController(player, (controller, state, animSetter) -> PlayState.STOP));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            PLAYING.clear();
            restoreCamera(mc);
            return;
        }
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player.getVehicle() instanceof HiveRitualSeatEntity seat) {
                Integer was = PLAYING.put(player.getUUID(), seat.getId());
                if (was == null || was != seat.getId()) {
                    if (was != null) {
                        stop(player);
                    }
                    play(player);
                }
            } else if (PLAYING.remove(player.getUUID()) != null) {
                stop(player);
            }
        }
        PLAYING.keySet().removeIf(id -> mc.level.getPlayerByUUID(id) == null);

        boolean local = mc.player != null && mc.player.getVehicle() instanceof HiveRitualSeatEntity;
        if (local && !cameraTypeSaved) {
            savedCameraType = mc.options.getCameraType();
            cameraTypeSaved = true;
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (!local) {
            restoreCamera(mc);
        }
    }

    private static void restoreCamera(Minecraft mc) {
        if (cameraTypeSaved) {
            mc.options.setCameraType(savedCameraType);
            cameraTypeSaved = false;
        }
        cameraEntityId = -1;
        forwardAxis = null;
    }

    private static PlayerAnimationController controller(AbstractClientPlayer player) {
        IAnimation layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        return layer instanceof PlayerAnimationController c ? c : null;
    }

    private static void play(AbstractClientPlayer player) {
        PlayerAnimationController c = controller(player);
        if (c == null) {
            OvergrownVegetation.LOGGER.warn("HiveRitual: {} に PAL レイヤーがありません", player.getName().getString());
            return;
        }
        if (!c.triggerAnimation(ANIMATION)) {
            OvergrownVegetation.LOGGER.warn("HiveRitual: プレイヤーアニメ {} が見つかりません(player_animations/)", ANIMATION);
        }
    }

    private static void stop(AbstractClientPlayer player) {
        PlayerAnimationController c = controller(player);
        if (c != null) {
            c.stop();
        }
    }

    /**
     * {@link HiveRitualRenderer} から毎フレーム。camera 骨のワールド行列(GeckoLib の {@code getWorldSpaceMatrix})から
     * 骨の原点(= ピボット、目の位置)と前向き(モデル座標の -Z)を取り出す。
     */
    /**
     * @param world camera 骨(位置と向き。カット割りは CameraCompose が書く)
     * @param ref   cam_ref 骨(一度も動かない基準。モデル座標のどの軸がエンティティの前かを測る)
     */
    public static void updateCamera(HiveRitualEntity entity, Matrix4f world, Matrix4f ref) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof HiveRitualSeatEntity seat) || seat.anchorId() != entity.getId()) {
            return;
        }
        Vector4f p = world.transform(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
        if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) {
            return;
        }
        if (cameraEntityId != entity.getId() || forwardAxis == null) {
            // 基準の骨(回転しない)で、モデル座標のどの軸がエンティティの前方(yaw)に一番近いかを測り、
            // 以後その軸を「カメラの前」として使う。GeckoLib の座標系(x 反転・180° 回転)を手で追うと
            // 符号を間違えやすく、2026-09-18 に真逆を向いた。
            Vec3 fwd = HiveRitualEntity.forward(entity.getYRot());
            Vector4f best = null;
            double bestDot = -2.0;
            for (Vector4f axis : AXES) {
                Vector4f w = ref.transform(new Vector4f(axis));
                double len = Math.sqrt(w.x * w.x + w.y * w.y + w.z * w.z);
                if (len < 1.0e-4) {
                    continue;
                }
                double dot = (w.x * fwd.x + w.z * fwd.z) / len;
                if (dot > bestDot) {
                    bestDot = dot;
                    best = axis;
                }
            }
            if (best == null || bestDot < 0.9) {
                return; // まだ行列が入っていない(最初のフレーム)か、基準の骨がおかしい。
            }
            forwardAxis = best;
            OvergrownVegetation.LOGGER.info("HiveRitual: camera 骨の前方軸を ({}, {}, {}) に校正(一致 {})",
                    best.x, best.y, best.z, String.format("%.2f", bestDot));
        }
        Vector4f f = world.transform(new Vector4f(forwardAxis));
        float len = (float) Math.sqrt(f.x * f.x + f.y * f.y + f.z * f.z);
        if (len < 1.0e-4F) {
            return;
        }
        cameraEntityId = entity.getId();
        cameraX = p.x;
        cameraY = p.y;
        cameraZ = p.z;
        cameraYaw = (float) Math.toDegrees(Math.atan2(-f.x, f.z));
        cameraPitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(f.y / len, -1.0F, 1.0F)));
    }

    /** {@code Camera#setup} の最後で、この視点(乗っている本人)を蔦モデルの camera 骨に置き換えるべきか。 */
    public static boolean wantsCamera(Entity cameraEntity) {
        Minecraft mc = Minecraft.getInstance();
        return cameraEntity != null && cameraEntity == mc.player && cameraEntity.getVehicle() instanceof HiveRitualSeatEntity seat
                && !seat.inIntro() && seat.anchorId() == cameraEntityId;
    }

    public static double cameraX() {
        return cameraX;
    }

    public static double cameraY() {
        return cameraY;
    }

    public static double cameraZ() {
        return cameraZ;
    }

    public static float cameraYaw() {
        return cameraYaw;
    }

    public static float cameraPitch() {
        return cameraPitch;
    }

    /** 気を失っている間の暗転(乗っている本人だけ)。 */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof HiveRitualSeatEntity ritual)) {
            return;
        }
        float t = ritual.seconds(event.getPartialTick().getGameTimeDeltaPartialTick(true));
        float alpha;
        if (t < fadeInStart() || t > fadeOutEnd()) {
            return;
        } else if (t < fadeInEnd()) {
            alpha = (t - fadeInStart()) / (fadeInEnd() - fadeInStart());
        } else if (t < fadeOutStart()) {
            alpha = 1.0F;
        } else {
            alpha = 1.0F - (t - fadeOutStart()) / (fadeOutEnd() - fadeOutStart());
        }
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        GuiGraphics g = event.getGuiGraphics();
        // HUD は層ごとに z を 200 ずつ手前に重ねて描かれる(LayeredDraw)。z=0 で塗るとチャット欄など後の層が
        // 手前に残るので、全部の層より手前(z=4000)で塗る。
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 4000.0F);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24);
        g.pose().popPose();
    }
}
