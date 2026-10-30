package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 機関砲台({@code block/GunTurretBlockEntity})の<b>見える弾</b>(ユーザー要望 2026-09-21
 * 「大砲と同じように弾が見えるように」)。砲台の砲弾({@link CannonShellRenderer})と同じモデル・
 * テクスチャを小さくして、銃口から着弾点まで直線で飛ばす。
 *
 * <h2>これは見た目だけ</h2>
 * 機関砲は<b>即着弾</b>(撃った瞬間に当たり判定が済む)なので、ここを飛んでいる弾は
 * ダメージとは関係がない。<b>パケットも飛ばない</b> —— 各クライアントが自分の予測した発砲
 * ({@code GunTurretBlockEntity#tickClient})からその場で足すので、見ている全員に同じ弾が見える。
 *
 * <p>速さは {@code defense.json} の {@code gunTurret.tracerSpeed}(ブロック/tick、既定 3.0)。
 * 着弾点に着いた弾はその場で消える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GunTracerRenderer {

    /** 弾体の大きさ(砲台の砲弾に対する倍率)。機関砲弾なので小さい。 */
    private static final float SCALE = 0.35F;
    /** 念のための寿命(tick)。着弾点に着けばそれより早く消える。 */
    private static final int MAX_TICKS = 60;

    /**
     * 飛んでいる 1 発。{@code from} から {@code to} へ一直線。
     *
     * <p>2026-09-24 軽量化(監査 1395): {@code from} と {@code to} は変わらないので、長さ・向き・角度は
     * {@link #of} で 1 回だけ出しておく(以前は全弾について毎フレーム・毎 tick 出し直していた)。
     * 式は以前の描画と同じなので値も同じ。{@code direction} は長さがほぼ 0 の弾(描かない)だけ {@code null}。
     */
    public record Tracer(Vec3 from, Vec3 to, long startGameTime, double length, @Nullable Vec3 direction,
                         float yawDegrees, float pitchDegrees) {

        static Tracer of(Vec3 from, Vec3 to, long startGameTime) {
            Vec3 path = to.subtract(from);
            double length = path.length();
            if (length < 1.0E-4D) {
                return new Tracer(from, to, startGameTime, length, null, 0.0F, 0.0F);
            }
            Vec3 direction = path.scale(1.0D / length);
            return new Tracer(from, to, startGameTime, length, direction,
                    (float) -CannonBallistics.azimuthOf(direction.x, direction.z),
                    (float) CannonBallistics.elevationOf(direction));
        }
    }

    /** 古い順。上限を超えたら先頭から捨てる(ArrayList#remove(0) の詰め直しをしない。2026-09-24、監査 1395)。 */
    private static final ArrayDeque<Tracer> TRACERS = new ArrayDeque<>();

    /** 機関砲台が 1 発撃った(クライアントの予測)。 */
    public static void add(Vec3 from, Vec3 to, long gameTime) {
        if (TRACERS.size() > 512) {
            // 砲台が何十台も並んでいるとき用の歯止め。古いものから捨てる。
            TRACERS.pollFirst();
        }
        TRACERS.addLast(Tracer.of(from, to, gameTime));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            TRACERS.clear();
            return;
        }
        if (TRACERS.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        double speed = DesignData.defense().turretTracerSpeed();
        Iterator<Tracer> iterator = TRACERS.iterator();
        while (iterator.hasNext()) {
            Tracer tracer = iterator.next();
            long age = now - tracer.startGameTime();
            // length は from.distanceTo(to) と同じ値(Tracer#of で作り置き)。
            if (age > MAX_TICKS || age * speed >= tracer.length()) {
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACERS.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || TRACERS.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = minecraft.level.getGameTime();
        double speed = DesignData.defense().turretTracerSpeed();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        RenderType type = RenderType.entityCutoutNoCull(CannonShellRenderer.shellTexture());
        VertexConsumer buffer = bufferSource.getBuffer(type);

        for (Tracer tracer : TRACERS) {
            double age = (gameTime - tracer.startGameTime()) + partialTick;
            if (age < 0.0D) {
                continue;
            }
            double length = tracer.length();
            double travelled = age * speed;
            if (length < 1.0E-4D || travelled >= length) {
                continue;
            }
            // 位置は from + direction * travelled をカメラ相対にしたもの(以前の Vec3 の演算と同じ順番)。
            // 画面外の弾を視錐台で飛ばす案は入れていない: 被弾の揺れ・視点の揺れは描画の射影にだけ掛かり、
            // 判定用の視錐台には掛からないので、画面の隅で弾が 1 フレーム消えることがある。
            Vec3 from = tracer.from();
            Vec3 direction = tracer.direction();
            double drawX = (from.x + direction.x * travelled) - camera.x;
            double drawY = (from.y + direction.y * travelled) - camera.y;
            double drawZ = (from.z + direction.z * travelled) - camera.z;

            poseStack.pushPose();
            poseStack.translate(drawX, drawY, drawZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(tracer.yawDegrees()));
            poseStack.mulPose(Axis.XP.rotationDegrees(tracer.pitchDegrees()));
            poseStack.scale(SCALE, SCALE, SCALE);
            // 曳光弾なので自分で光る(暗がりでも筋が見える)。
            CannonShellRenderer.shellModel().render(poseStack, buffer, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        bufferSource.endBatch(type);
    }

    private GunTracerRenderer() {
    }
}
