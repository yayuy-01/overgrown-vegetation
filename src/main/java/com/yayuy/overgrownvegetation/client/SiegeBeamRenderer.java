package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.SiegeBossStatePayload.BeamState;
import com.yayuy.overgrownvegetation.siege.SiegeData;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 攻城戦ボスのビームの描画。
 * <ul>
 *   <li><b>花のビーム(遠距離砲撃)</b> — 溜め中は何も描かない(予告は「首がこちらを向き、花が開く」動き
 *       そのものだけ)。照射: 花から着弾点を<b>通り越して</b> {@code siegeBeamOvershoot} 先まで伸びる太い光線
 *       (地面に刺さった先は地形に隠れる)+ 着弾の閃光。復帰: 光線が細く薄くなって消える。</li>
 *   <li><b>触手ビーム(近距離)</b> — 触手の先端からの細い光線。こちらも溜め中は描かない。</li>
 *   <li><b>触手の小ビームの乱射</b> — 1 発ずつ向きが違うので段階を持たない。撃つたびに届く
 *       {@link com.yayuy.overgrownvegetation.network.SiegeBarragePayload} を数 tick だけ描いて消す
 *       ({@link ClientSiegeBarrage})。撃った瞬間の先端の座標をそのまま使う。</li>
 * </ul>
 *
 * <p>始点(花・触手の先端)は {@link TitanicaKinematics} でワールド座標を求めたうえで、
 * {@link SiegeBossRenderer#toDrawSpace} でインポスタと同じ縮尺を掛ける — ボス本体は far クリップ面の
 * 内側へ「見かけの距離を詰めて」描かれているので、光線もその描画上の花から出さないと繋がらない。
 * 終点(着弾点)はプレイヤーの近くの実座標のまま。
 *
 * <h2>見た目はテクスチャで決まる</h2>
 * <p>{@code textures/entity/siege_beam.png}(32×64、上下左右ともタイル可能)を、u = 周方向・v = 長さ方向に
 * 貼って {@link RenderType#entityTranslucent}(アルファ合成・両面)で描く。v は時間で流れる(光が飛んでいく
 * 見た目)。差し替えるときは同じ形式で描き直すだけでよい(アルファも効く。長さ方向 1 タイル =
 * {@value BeamGeometry#TEXTURE_TILE_LENGTH} ブロック)。光線は 12 角の円錐台 3 重 — 明るい芯 → 暗い緑の殻 → 深緑の縁 —
 * で、頂点色でそれぞれ色味を掛けている({@link #CORE}/{@link #SHELL}/{@link #RIM})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SiegeBeamRenderer {


    /** 芯: 明るい緑。 */
    private static final float[] CORE = {0.72F, 1.0F, 0.62F, 0.92F};
    /** 殻: 暗い緑。 */
    private static final float[] SHELL = {0.16F, 0.55F, 0.18F, 0.75F};
    /** 縁: 深い緑。 */
    private static final float[] RIM = {0.03F, 0.16F, 0.05F, 0.40F};
    /** 着弾の閃光。 */
    private static final float[] FLASH = {0.40F, 0.90F, 0.35F, 0.55F};

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!ClientSiegeBoss.isActive()) {
            return;
        }
        BeamState flower = ClientSiegeBoss.flower();
        BeamState tentacle = ClientSiegeBoss.tentacle();
        boolean flowerVisible = flower.phase() == SiegeData.BEAM_FIRE || flower.phase() == SiegeData.BEAM_RECOVER;
        boolean tentacleVisible = tentacle.phase() == SiegeData.BEAM_FIRE;
        boolean barrageVisible = !ClientSiegeBarrage.shots().isEmpty();
        if (!flowerVisible && !tentacleVisible && !barrageVisible) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Vec3 bodyCenter = ClientSiegeBoss.position(partialTick);
        float bodyYaw = ClientSiegeBoss.yaw(partialTick);
        double scale = ClientSiegeBoss.scale();
        // 本体を描いているのがインポスタなら、ビームの始点にも同じ縮尺を掛ける(でないと根元が外れる)。
        double shrink = SiegeBossRenderer.impostorDrawn(bodyCenter, camera)
                ? SiegeBossRenderer.impostorShrink(bodyCenter.distanceTo(camera))
                : 1.0D;
        float time = (float) ClientSiegeBoss.elapsed(partialTick);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        RenderType renderType = RenderType.entityTranslucent(BeamGeometry.TEXTURE);
        VertexConsumer buffer = bufferSource.getBuffer(renderType);

        if (flowerVisible) {
            Vec3 flowerWorld = ClientSiegeBoss.flowerPosition(partialTick);
            Vec3 start = SiegeBossRenderer.toDrawSpace(flowerWorld, camera, shrink).subtract(camera);
            Vec3 target = flower.target().subtract(camera);
            // 狙った地点で止まらず、その先 siegeBeamOvershoot まで突き進む(円錐なので太くなり続ける)。
            double overshoot = OvergrownVegetationConfig.COMMON.siegeBeamOvershoot.get();
            Vec3 dir = target.subtract(start).normalize();
            Vec3 end = target.add(dir.scale(overshoot));
            float progress = ClientSiegeBoss.flowerProgress(partialTick);
            double realLength = flowerWorld.distanceTo(flower.target()) + overshoot;
            float startRadius = (float) (SiegeData.flowerBeamRadiusAt(0.0D) * shrink);
            float endRadius = SiegeData.flowerBeamRadiusAt(realLength);
            float targetRadius = SiegeData.flowerBeamRadiusAt(flowerWorld.distanceTo(flower.target()));
            drawFlowerBeam(buffer, matrix, start, end, target, flower.phase(), progress, startRadius, endRadius, targetRadius, time);
        }
        if (tentacleVisible) {
            // 1 斉射 = 既定 8 本。別々の触手から別々の相手へ、<b>1 本ずつ時間差で</b>走る
            // (サーバーの SiegeData.tickTentacleBeam と同じ刻みを、経過 tick から自分で割り出す)。
            var volley = ClientSiegeBoss.volley();
            OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
            double elapsed = ClientSiegeBoss.tentacleElapsed(partialTick);
            int shotInterval = Math.max(1, cfg.siegeTentacleBeamShotIntervalTicks.get());
            float life = Math.max(1.0F, cfg.siegeTentacleBeamFireTicks.get());
            float radius = (float) (cfg.siegeTentacleBeamRadius.get() * 0.6D);
            for (int i = 0; i < volley.size(); i++) {
                float age = (float) (elapsed - (double) i * shotInterval);
                if (age < 0.0F || age >= life) {
                    continue;  // まだ撃っていない / もう消えた
                }
                float fade = 1.0F - age / life;
                Vec3 tip = TitanicaKinematics.tentacleTip(bodyCenter, bodyYaw, scale, volley.indices()[i]);
                Vec3 start = SiegeBossRenderer.toDrawSpace(tip, camera, shrink).subtract(camera);
                // 終点は撃つ直前にサーバーが「伸ばし切った先」へ差し替えて送ってくる
                // (SiegeData.beamEnd)。ここで足す必要はもう無い — 足すと当たる線より長く描いてしまう。
                Vec3 end = volley.targets()[i].subtract(camera);
                float r = radius * fade;
                beam(buffer, matrix, start, end, r, r, time);
            }
        }
        if (barrageVisible) {
            drawBarrage(buffer, matrix, camera, shrink, partialTick, time);
        }

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        bufferSource.endBatch(renderType);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        poseStack.popPose();
    }

    /**
     * 乱射の小ビーム。撃った瞬間の触手の先端(ワールド座標)からの細い光線を、撃ってから
     * {@link ClientSiegeBarrage#SHOT_TICKS} tick かけて細く薄くしながら消す。
     * 始点はボス側なので、本体と同じインポスタの縮尺を掛けないと根元が繋がらない。
     */
    private static void drawBarrage(VertexConsumer buffer, Matrix4f matrix, Vec3 camera, double shrink,
                                    float partialTick, float time) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long gameTime = minecraft.level.getGameTime();
        float radius = (float) (OvergrownVegetationConfig.COMMON.siegeBarrageRadius.get() * 0.6D);
        for (ClientSiegeBarrage.Shot shot : ClientSiegeBarrage.shots()) {
            float age = (gameTime - shot.startGameTime()) + partialTick;
            float fade = 1.0F - Mth.clamp(age / ClientSiegeBarrage.SHOT_TICKS, 0.0F, 1.0F);
            if (fade <= 0.02F) {
                continue;
            }
            Vec3 start = SiegeBossRenderer.toDrawSpace(shot.from(), camera, shrink).subtract(camera);
            Vec3 end = shot.to().subtract(camera);
            float r = radius * fade;
            beam(buffer, matrix, start, end, r * (float) shrink, r, time);
        }
    }

    private static void drawFlowerBeam(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end, Vec3 target,
                                       int phase, float progress, float startRadius, float endRadius, float targetRadius, float time) {
        if (phase == SiegeData.BEAM_FIRE) {
            // 太さは<b>照射の頭で開ききったら固定</b>。以前はここに 0.35 秒周期・振幅 12% の脈動を
            // 掛けていたが、1000 ブロック先で直径 130 になる光線ではそれが「うねうねと伸び縮みする」
            // 見え方になっていたのでやめた(ユーザー指示 2026-09-13)。
            // 動きは層ごとのテクスチャの流れと軸回転(cone の scroll / spin)だけで出す。
            float k = Mth.clamp(progress * 6.0F, 0.0F, 1.0F);
            beam(buffer, matrix, start, end, startRadius * k, endRadius * k, time);
            // 着弾の閃光(照射の頭で大きく、あとは残光)。大きさは着弾点での光線の太さに合わせる。
            float flash = 1.0F - Mth.clamp(progress * 2.0F, 0.0F, 1.0F);
            disc(buffer, matrix, target.add(0.0D, 0.2D, 0.0D), targetRadius * (1.2F + 2.0F * flash), FLASH, 0.35F + 0.5F * flash, time);
        } else if (phase == SiegeData.BEAM_RECOVER) {
            float fade = 1.0F - progress;
            if (fade > 0.02F) {
                beam(buffer, matrix, start, end, startRadius * fade, endRadius * fade * 0.85F, time);
            }
        }
    }

    /** 光線本体: 明るい芯 → 暗い緑の殻 → 深い緑の縁。始点 {@code ra} から終点 {@code rb} へ太くなる円錐。 */
    private static void beam(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end, float ra, float rb, float time) {
        if (rb <= 0.0F) {
            return;
        }
        // ビーコンのビームと同じ発想: ノイズ入りのテクスチャを重ねた層ごとに、流れる速さと回転の向きを変える。
        cone(buffer, matrix, start, end, ra * 0.42F, rb * 0.42F, CORE, CORE[3], time, 1.0F, time * 0.030F);
        cone(buffer, matrix, start, end, ra, rb, SHELL, SHELL[3], time, 0.7F, -time * 0.018F);
        cone(buffer, matrix, start, end, ra * 1.12F, rb * 1.12F, RIM, RIM[3], time, 0.45F, time * 0.010F);
    }

    /** 光線の円錐台・着弾の円盤は {@link BeamGeometry} と共通(フィロセンスのビームと同じ形)。 */
    private static void cone(VertexConsumer buffer, Matrix4f matrix, Vec3 a, Vec3 b, float ra, float rb,
                             float[] rgb, float alpha, float time, float scroll, float spin) {
        BeamGeometry.cone(buffer, matrix, a, b, ra, rb, rgb, alpha, time, scroll, spin);
    }

    private static void disc(VertexConsumer buffer, Matrix4f matrix, Vec3 center, float radius,
                             float[] rgb, float alpha, float time) {
        BeamGeometry.disc(buffer, matrix, center, radius, rgb, alpha, time);
    }

    private SiegeBeamRenderer() {
    }
}
