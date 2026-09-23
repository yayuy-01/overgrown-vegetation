package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 砲台の砲弾の描画(ワールド描画の {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES} に自前で描く)。
 * 砲弾はエンティティではないので({@link ShellTracker})、{@link ClientShells} の「発射位置・初速・発射時刻」から
 * 毎フレームの位置を式で出して、
 * <ul>
 *   <li><b>弾体</b>: 小さな砲弾のモデル(進行方向を向く)。</li>
 *   <li><b>煙の尾</b>: 直前 {@value #TRAIL_TICKS} tick ぶんの軌跡に沿った細くなる円錐(半透明)。</li>
 *   <li><b>着弾の閃光</b>: カメラを向いた板(ボスへの命中は大きく、地形・生物は小さく)。</li>
 * </ul>
 * 遠く(far クリップ面の外)を飛ぶ砲弾は {@link SiegeBossRenderer#impostorShrink} でボスと同じ縮尺を掛けて
 * 見かけの距離を詰める(視角は不変)。霧は描画の瞬間だけ切る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CannonShellRenderer {

    private static final ResourceLocation SHELL_TEXTURE = OvergrownVegetation.id("textures/entity/cannon_shell.png");
    /** ボスが曲射してくる胞子爆弾は、落としてくる爆弾とまったく同じ見た目。 */
    private static final ResourceLocation SPORE_TEXTURE = OvergrownVegetation.id("textures/entity/siege_bomb.png");
    private static final ResourceLocation TRAIL_TEXTURE = OvergrownVegetation.id("textures/entity/cannon_trail.png");
    private static final ResourceLocation FLASH_TEXTURE = OvergrownVegetation.id("textures/entity/cannon_flash.png");

    /** 煙の尾の長さ(tick)。 */
    private static final int TRAIL_TICKS = 24;
    private static final int TRAIL_SIDES = 8;
    private static final float TRAIL_RADIUS = 0.28F;
    private static final float[] TRAIL_COLOR = {0.85F, 0.85F, 0.82F};
    /** 胞子爆弾の尾は黄緑の胞子の帯。 */
    private static final float[] SPORE_TRAIL_COLOR = {0.62F, 0.85F, 0.35F};
    private static final float[] FLASH_BOSS = {0.78F, 1.0F, 0.55F};
    private static final float[] FLASH_GROUND = {1.0F, 0.75F, 0.35F};
    /** 胞子爆弾の着弾は黄緑の胞子煙。 */
    private static final float[] FLASH_SPORE = {0.70F, 0.95F, 0.40F};

    private static ModelPart shellModel;
    private static ModelPart sporeModel;

    /** 砲弾のモデル(作り置き)。機関砲台の曳光弾({@link GunTracerRenderer})が小さくして使い回す。 */
    static ModelPart shellModel() {
        if (shellModel == null) {
            shellModel = createShellModel();
        }
        return shellModel;
    }

    /** 砲弾のテクスチャ。 */
    static ResourceLocation shellTexture() {
        return SHELL_TEXTURE;
    }

    /** 砲弾のモデル: 先の尖った円筒(−Z が前)。テクスチャ 32×32。 */
    private static ModelPart createShellModel() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-1.5F, -1.5F, -5.0F, 3.0F, 3.0F, 10.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("tip",
                CubeListBuilder.create().texOffs(0, 14).addBox(-1.0F, -1.0F, -7.0F, 2.0F, 2.0F, 2.0F),
                PartPose.ZERO);
        root.addOrReplaceChild("tail",
                CubeListBuilder.create().texOffs(10, 14).addBox(-2.0F, -2.0F, 4.0F, 4.0F, 4.0F, 1.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32).bakeRoot();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (ClientShells.shells().isEmpty() && ClientShells.flashes().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        shellModel();
        if (sporeModel == null) {
            sporeModel = SiegeBombModel.createBodyLayer().bakeRoot();
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long gameTime = minecraft.level.getGameTime();
        Vec3 camera = event.getCamera().getPosition();
        float shellScale = OvergrownVegetationConfig.COMMON.cannonShellScale.get().floatValue();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        // --- 弾体(砲弾と胞子爆弾でモデル・テクスチャが違う) ---
        float sporeScale = OvergrownVegetationConfig.COMMON.siegeMortarScale.get().floatValue();
        for (int pass = 0; pass < 2; pass++) {
            boolean spore = pass == 1;
            RenderType type = RenderType.entityCutoutNoCull(spore ? SPORE_TEXTURE : SHELL_TEXTURE);
            VertexConsumer buffer = bufferSource.getBuffer(type);
            for (ClientShells.Shell shell : ClientShells.shells()) {
                if ((shell.kind() == ShellTracker.KIND_SPORE) != spore) {
                    continue;
                }
                double t = (gameTime - shell.startGameTime()) + partialTick;
                if (t < 0.0D) {
                    continue;
                }
                Vec3 pos = shell.positionAt(t);
                Vec3 velocity = shell.velocityAt(t);
                Vec3 offset = pos.subtract(camera);
                double distance = offset.length();
                double shrink = SiegeBossRenderer.impostorShrink(distance);
                Vec3 drawAt = offset.scale(shrink);
                float scale = (float) ((spore ? sporeScale : shellScale) * shrink);

                int light = LightTexture.FULL_BRIGHT;
                BlockPos blockPos = BlockPos.containing(pos);
                if (minecraft.level.hasChunkAt(blockPos)) {
                    light = LevelRenderer.getLightColor(minecraft.level, blockPos);
                }

                poseStack.pushPose();
                poseStack.translate(drawAt.x, drawAt.y, drawAt.z);
                if (spore) {
                    // 胞子の塊は向きを持たないので、飛びながらゆっくり回す。
                    poseStack.mulPose(Axis.YP.rotationDegrees((float) (t * 2.3D + shell.id() * 37)));
                    poseStack.mulPose(Axis.XP.rotationDegrees((float) (t * 1.7D + shell.id() * 53)));
                } else {
                    poseStack.mulPose(Axis.YP.rotationDegrees((float) -CannonBallistics.azimuthOf(velocity.x, velocity.z)));
                    poseStack.mulPose(Axis.XP.rotationDegrees((float) CannonBallistics.elevationOf(velocity)));
                }
                poseStack.scale(scale, scale, scale);
                if (spore) {
                    sporeModel.render(poseStack, buffer, light, OverlayTexture.NO_OVERLAY);
                } else {
                    shellModel.render(poseStack, buffer, light, OverlayTexture.NO_OVERLAY);
                }
                poseStack.popPose();
            }
            bufferSource.endBatch(type);
        }

        // --- 煙の尾・閃光(半透明) ---
        poseStack.pushPose();
        Matrix4f matrix = poseStack.last().pose();
        RenderType trailType = RenderType.entityTranslucent(TRAIL_TEXTURE);
        VertexConsumer trailBuffer = bufferSource.getBuffer(trailType);
        for (ClientShells.Shell shell : ClientShells.shells()) {
            double t = (gameTime - shell.startGameTime()) + partialTick;
            if (t < 0.0D) {
                continue;
            }
            boolean spore = shell.kind() == ShellTracker.KIND_SPORE;
            drawTrail(trailBuffer, matrix, shell, t, camera, spore ? sporeScale : shellScale,
                    spore ? SPORE_TRAIL_COLOR : TRAIL_COLOR, spore ? 0.8F : 0.55F);
        }
        bufferSource.endBatch(trailType);

        RenderType flashType = RenderType.entityTranslucent(FLASH_TEXTURE);
        VertexConsumer flashBuffer = bufferSource.getBuffer(flashType);
        for (ClientShells.Flash flash : ClientShells.flashes()) {
            double age = (gameTime - flash.startGameTime()) + partialTick;
            drawFlash(flashBuffer, event, flash, age, camera);
        }
        bufferSource.endBatch(flashType);
        poseStack.popPose();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    /** 煙の尾: 直前 {@value #TRAIL_TICKS} tick の軌跡を、弾体から離れるほど細く薄くなる円錐台でつなぐ。 */
    private static void drawTrail(VertexConsumer buffer, Matrix4f matrix, ClientShells.Shell shell, double t,
                                  Vec3 camera, float shellScale, float[] color, float maxAlpha) {
        int steps = (int) Math.min(TRAIL_TICKS, Math.floor(t));
        if (steps < 1) {
            return;
        }
        Vec3 previous = drawSpace(shell.positionAt(t), camera);
        float previousRadius = TRAIL_RADIUS * shellScale;
        for (int k = 1; k <= steps; k++) {
            double back = t - k;
            Vec3 point = drawSpace(shell.positionAt(back), camera);
            float fade = 1.0F - (float) k / (TRAIL_TICKS + 1);
            float radius = TRAIL_RADIUS * shellScale * (0.35F + 0.65F * fade) * (float) shrinkAt(shell.positionAt(back), camera);
            float alpha = maxAlpha * fade * fade;
            cone(buffer, matrix, previous, point, previousRadius, radius, color, alpha, (float) k / TRAIL_TICKS);
            previous = point;
            previousRadius = radius;
        }
    }

    /** 着弾の閃光: カメラを向いた板。ボスへの命中は大きく緑がかった光、地形・生物は橙。 */
    private static void drawFlash(VertexConsumer buffer, RenderLevelStageEvent event, ClientShells.Flash flash,
                                  double age, Vec3 camera) {
        float progress = (float) Mth.clamp(age / ClientShells.FLASH_TICKS, 0.0D, 1.0D);
        boolean boss = flash.kind() == ShellTracker.IMPACT_BOSS;
        boolean spore = flash.shellKind() == ShellTracker.KIND_SPORE;
        float[] color = boss ? FLASH_BOSS : spore ? FLASH_SPORE : FLASH_GROUND;
        float grow = boss ? Mth.lerp(progress, 10.0F, 34.0F)
                : Mth.lerp(progress, 2.5F, spore ? 14.0F : 8.0F);
        float alpha = (1.0F - progress) * (1.0F - progress) * 0.95F;
        if (alpha <= 0.01F) {
            return;
        }
        Vec3 world = flash.pos();
        double shrink = shrinkAt(world, camera);
        Vec3 drawAt = world.subtract(camera).scale(shrink);
        float size = (float) (grow * shrink);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(drawAt.x, drawAt.y, drawAt.z);
        poseStack.mulPose(event.getCamera().rotation());
        Matrix4f m = poseStack.last().pose();
        vertex(buffer, m, new Vec3(-size, -size, 0.0D), color, alpha, 0.0F, 1.0F);
        vertex(buffer, m, new Vec3(size, -size, 0.0D), color, alpha, 1.0F, 1.0F);
        vertex(buffer, m, new Vec3(size, size, 0.0D), color, alpha, 1.0F, 0.0F);
        vertex(buffer, m, new Vec3(-size, size, 0.0D), color, alpha, 0.0F, 0.0F);
        poseStack.popPose();
    }

    private static double shrinkAt(Vec3 world, Vec3 camera) {
        return SiegeBossRenderer.impostorShrink(world.distanceTo(camera));
    }

    /** ワールド座標 → インポスタの縮尺を掛けたカメラ相対座標。 */
    private static Vec3 drawSpace(Vec3 world, Vec3 camera) {
        return world.subtract(camera).scale(shrinkAt(world, camera));
    }

    /** 線分 {@code a}-{@code b} に沿った円錐台(両面)。座標はカメラ相対。 */
    private static void cone(VertexConsumer buffer, Matrix4f matrix, Vec3 a, Vec3 b, float ra, float rb,
                             float[] rgb, float alpha, float v0) {
        Vec3 axis = b.subtract(a);
        double length = axis.length();
        if (length < 1.0E-5D) {
            return;
        }
        axis = axis.scale(1.0D / length);
        Vec3 helper = Math.abs(axis.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 u = axis.cross(helper).normalize();
        Vec3 v = axis.cross(u).normalize();
        float v1 = v0 + 1.0F / TRAIL_TICKS;
        for (int i = 0; i < TRAIL_SIDES; i++) {
            double t0 = (Math.PI * 2.0D) * i / TRAIL_SIDES;
            double t1 = (Math.PI * 2.0D) * (i + 1) / TRAIL_SIDES;
            Vec3 d0 = u.scale(Math.cos(t0)).add(v.scale(Math.sin(t0)));
            Vec3 d1 = u.scale(Math.cos(t1)).add(v.scale(Math.sin(t1)));
            float u0 = (float) i / TRAIL_SIDES;
            float u1 = (float) (i + 1) / TRAIL_SIDES;
            vertex(buffer, matrix, a.add(d0.scale(ra)), rgb, alpha, u0, v0);
            vertex(buffer, matrix, a.add(d1.scale(ra)), rgb, alpha, u1, v0);
            vertex(buffer, matrix, b.add(d1.scale(rb)), rgb, alpha, u1, v1);
            vertex(buffer, matrix, b.add(d0.scale(rb)), rgb, alpha, u0, v1);
        }
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 p, float[] rgb, float a, float u, float v) {
        buffer.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(rgb[0], rgb[1], rgb[2], a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0.0F, 1.0F, 0.0F);
    }

    private CannonShellRenderer() {
    }
}
