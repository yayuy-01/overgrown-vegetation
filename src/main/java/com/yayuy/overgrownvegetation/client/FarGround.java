package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 雲の海の切れ目の下の地面(2026-10-18。遠景 {@link VoxelClouds} の残り (1)「雲の海の切れ目の下は描画距離の外で地面が無く、霧の色が見える
 * (夕方は太陽の方を見ると橙の平らな面で溶岩のよう)」)。
 *
 * <p>雲の上で、カメラのまわり半径 {@link #RADIUS} の平らな円盤を y {@link #GROUND_Y} に描く(描画距離の外の地面の身代わり)。空と地平線の書き割り
 * ({@link SkyDome})の後・遠くの雲の前に、奥行きを見ず・書かずに背景として塗る({@link VoxelClouds#onRenderLevel} から)。雲は後から上に描かれ、
 * 本物の地形もその後のいつもの奥行きで上に描かれるので、描画距離の中は本物が見える。
 * <ul>
 *   <li>丸み: 雲の海と同じだけ遠くを下げる(平らなままだと、丸めた遠くの雲より上に出て雲を隠す)。</li>
 *   <li>色: 暗い森の緑に、森の濃淡と開けた所を雑音で(世界に貼りつく)。日の光(朝夕は橙で弱く)・空の光・月明かりの強さを CPU で決め、色はシェーダーで付ける。
 *       遠いほど(目からの距離で)遠くの雲と同じ空の色の霞へ沈み、端の手前で霞だけになって消える(端だけ霧の色 = 地平の書き割り・背景の色)。
 *       霧の色で霞ませたら、夕方に太陽の方を向くと霧の色が画面全体で橙になり、切れ目が茶色の平面・橙の水たまりに見えた。</li>
 *   <li>現れる高さ: 雲の天辺(350)のすぐ下 {@link #FROM_Y} 〜 {@link #TO_Y}。雲の下では霧の壁と継ぎ目が出るので描かない。雲の中・水の中でも描かない。</li>
 * </ul>
 * ボクセルの雲を描いているとき(雲の設定がオフでない)だけ。設定 {@code farGround}。数値はすべて仮決め。
 */
public final class FarGround {
    /** 地面の高さ(海面のすぐ上)。 */
    static final float GROUND_Y = 64.0F;
    /** 円盤の半径(ブロック)。端の 1 割で消える。10-18 夜に遠景の外の輪(18 km)まで届くよう 10000 → 19000。 */
    static final float RADIUS = 19000.0F;
    /** 霞の距離(目からこれだけ離れると 63% が空気の色)。 */
    static final float HAZE = 3000.0F;
    /** 現れ始め・現れ終わりの高さ。 */
    static final float FROM_Y = 340.0F;
    static final float TO_Y = 400.0F;
    /** 雑音の折り返し(ブロック。雑音の升 640・160・48 のどれでも割り切れる)。 */
    static final double NOISE_PERIOD = 15360.0;
    private static final int RINGS = 48;
    private static final int SEGMENTS = 96;

    private static VertexBuffer disk;
    private static boolean broken;

    static float setting() {
        return OvergrownVegetationConfig.COMMON.farGround.get().floatValue();
    }

    /** 空の書き割りの後・遠くの雲の前({@link VoxelClouds#onRenderLevel} から)。例外が出たらこのセッションでは描かない。 */
    static void draw(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, Camera camera) {
        if (broken || ModShaders.farGround == null) {
            return;
        }
        try {
            render(level, partialTick, modelView, projection, camera);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("雲の下の地面で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void render(ClientLevel level, float pt, Matrix4f modelView, Matrix4f projection, Camera camera) {
        Vec3 cam = camera.getPosition();
        float strength = setting() * CanopyLight.smooth(Mth.clamp((float) ((cam.y - FROM_Y) / (TO_Y - FROM_Y)), 0.0F, 1.0F))
                * (1.0F - Mth.clamp(CloudInterior.amount(pt) * CloudInterior.setting() * 2.0F, 0.0F, 1.0F));
        if (strength <= 0.002F || CloudInterior.hideSky() || camera.getFluidInCamera() != FogType.NONE
                || !level.dimensionType().hasSkyLight() || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) {
            return;
        }
        if (disk == null) {
            build();
        }
        float[] light = light(level, pt);
        float[] fog = RenderSystem.getShaderFogColor();
        Vec3 sky = level.getSkyColor(cam, pt);
        ShaderInstance shader = ModShaders.farGround;
        shader.safeGetUniform("Ground").set((float) (GROUND_Y - cam.y), RADIUS, HAZE, strength);
        shader.safeGetUniform("Curve").set(OvergrownVegetationConfig.COMMON.cloudCurvature.get().floatValue(), VoxelClouds.CURVE_FROM,
                0.5F / VoxelClouds.CURVE_RADIUS, 0.0F);
        shader.safeGetUniform("Split").set(VoxelClouds.FAR_NEAR, VoxelClouds.FAR_FAR, 0.0F, 0.0F);
        shader.safeGetUniform("Light").set(light[0], light[1], light[2], light[3]);
        shader.safeGetUniform("Haze").set(fog[0], fog[1], fog[2], 0.0F);
        shader.safeGetUniform("Sky").set((float) sky.x, (float) sky.y, (float) sky.z, 0.0F);
        shader.safeGetUniform("Cam").set((float) Mth.positiveModulo(cam.x, NOISE_PERIOD), (float) Mth.positiveModulo(cam.z, NOISE_PERIOD),
                (float) NOISE_PERIOD, 0.0F);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        disk.bind();
        disk.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /**
     * 地面に当たる光の材料(日の光・空の光・月明かり・朝夕の暖かさ。色はシェーダーで付ける): 日の光は太陽が低いほど斜めで弱く、空の光は昼だけ、
     * 月明かりは月の高さ・満ち欠け・雨で({@link CanopyLight#lightStrength})。雨・雷雨は雲で日を弱める。
     */
    private static float[] light(ClientLevel level, float pt) {
        Vec3 sun = CanopyLight.sunDirection(level, pt);
        float rain = CloudSky.rawRain(level, pt);
        float thunder = CloudSky.rawThunder(level, pt);
        float dim = (1.0F - 0.7F * rain) * (1.0F - 0.3F * thunder);
        float warm = 1.0F - CanopyLight.smooth(Mth.clamp((float) sun.y / 0.35F, 0.0F, 1.0F));
        float direct = CanopyLight.smooth(Mth.clamp((float) sun.y / 0.6F, 0.0F, 1.0F)) * dim;
        float sky = CanopyLight.smooth(Mth.clamp(((float) sun.y + 0.12F) / 0.35F, 0.0F, 1.0F)) * (1.0F - 0.4F * rain);
        float moon = sun.y < 0.0 ? CanopyLight.lightStrength(level, sun.scale(-1.0), pt) / CanopyLight.MOON_STRENGTH : 0.0F;
        return new float[]{direct, sky, moon, warm};
    }

    /** 円盤(カメラのまわり。輪の間は外ほど広く、端で 330 ブロックほど = 丸みの折れ目が 1 ブロックに収まる)。 */
    private static void build() {
        ByteBufferBuilder bytes = new ByteBufferBuilder(RINGS * SEGMENTS * 4 * 12 + 64);
        try {
            BufferBuilder b = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (int i = 0; i < RINGS; i++) {
                float r0 = RADIUS * (float) Math.pow((double) i / RINGS, 1.6);
                float r1 = RADIUS * (float) Math.pow((double) (i + 1) / RINGS, 1.6);
                for (int j = 0; j < SEGMENTS; j++) {
                    double a0 = Math.PI * 2.0 * j / SEGMENTS;
                    double a1 = Math.PI * 2.0 * (j + 1) / SEGMENTS;
                    b.addVertex((float) Math.cos(a0) * r0, 0.0F, (float) Math.sin(a0) * r0);
                    b.addVertex((float) Math.cos(a0) * r1, 0.0F, (float) Math.sin(a0) * r1);
                    b.addVertex((float) Math.cos(a1) * r1, 0.0F, (float) Math.sin(a1) * r1);
                    b.addVertex((float) Math.cos(a1) * r0, 0.0F, (float) Math.sin(a1) * r0);
                }
            }
            MeshData mesh = b.build();
            disk = new VertexBuffer(VertexBuffer.Usage.STATIC);
            disk.bind();
            disk.upload(mesh);
            VertexBuffer.unbind();
        } finally {
            bytes.close();
        }
    }

    private FarGround() {
    }
}
