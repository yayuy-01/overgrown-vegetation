package com.yayuy.overgrownvegetation.client.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 雲の海の下の霞の床(2026-11-08 雲の段階 1 の改善 ①、ユーザー決定 Q4「FarGround を戻すか簡単な霞の床か。安い方で、夕方に橙一色にならないこと」)。
 *
 * <p>雲の上(y 300 より上)から見ると、雲の切れ目の下で、描画距離の外は地面が無く、霧の色(夕方は橙一色)が見えていた。前の版の FarGround
 * (遠くの地面の高さの地図を引いて描く)は重く、地図と一緒に戻す部品が多いので、ここでは<b>平らな霞の床を 1 枚</b>だけ描く(安い方)。
 * 高さは雲の底の {@link #BELOW} ブロック下、雲と同じ丸み(遠いほど下げる)で、空を描いた後・遠くの雲と地形より先に、奥行きを使わずに描く。
 * 地形はこの後に描くので、地面のある所(描画距離の中)は地形が上に乗り、地面の無い所だけに霞が見える。
 * 色 = 霧の色と「霞んだ遠い陸」の色({@link #OPEN}・{@link #FOREST} × 霧の明るさ)を混ぜたもの。夕方の橙も半分ほどは陸の暗い緑灰に置き換わる。
 * 遠い縁は霧の色へ溶かす。数値は仮決め。
 */
final class HazeFloor {
    /** 雲の底からの下がり(ブロック)。 */
    static final float BELOW = 60.0F;
    /**
     * 霞んだ遠い陸の色(開けた所・森)と、霧の色に混ぜる割合。11-08 仕上げ 1: 平らな灰緑の板に見えたので、世界の座標で決まる大きな升のゆるいムラ
     * (森の濃い緑と開けた所の淡い色)と、真上の雲の影({@link #CLOUD_SHADE})を足し、遠いほど霧の色へ溶かす({@link #FOG_FROM} から)。仮決め。
     */
    static final float[] OPEN = {0.64F, 0.61F, 0.47F};
    static final float[] FOREST = {0.20F, 0.31F, 0.20F};
    /** 11-18 ユーザー「雲より上にいる時、雲の下が緑に見える機能をなくしたい」で、陸の色は混ぜない(SkyDev.landMix = 0。床は霧の色と雲の影だけ)。前の値は 0.7。 */
    static final float LAND_MIX = 0.7F;
    static final float CLOUD_SHADE = 0.35F;
    static final float FOG_FROM = 2500.0F;
    /** 輪の半径(ブロック)。最後の輪で霧の色に溶ける(透明)。 */
    static final float[] RINGS = {0.0F, 400.0F, 900.0F, 1500.0F, 2300.0F, 3300.0F, 4600.0F, 6200.0F, 8200.0F, 10600.0F, 13500.0F, 19000.0F};
    static final int SEGMENTS = 64;
    /** 出始める・出きる高さ(カメラ)。 */
    static final double FROM_Y = CloudField.LOW_BASE;
    static final double TO_Y = CloudField.LOW_BASE + 60.0;

    private static VertexBuffer vbo;

    /** 空を描いた後({@link VoxelClouds#onRenderLevel} から)。 */
    static void draw(net.minecraft.client.multiplayer.ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection,
                     double camX, double camY, double camZ) {
        float show = (float) Mth.clamp((camY - FROM_Y) / (TO_Y - FROM_Y), 0.0, 1.0) * (1.0F - CloudInterior.amount(1.0F));
        if (show <= 0.0F) {
            return;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        float lum = 0.3F * fog[0] + 0.59F * fog[1] + 0.11F * fog[2];
        float bright = Mth.clamp(lum * 1.1F, 0.05F, 1.0F);
        float curve = DesignData.sky().curvature;
        float y0 = (float) (CloudField.LOW_BASE - BELOW - camY);
        // 雲の影(真上の雲の柱。11-08 仕上げ 1)を引く準備。
        long gt = level.getGameTime();
        float coverage = com.yayuy.overgrownvegetation.sky.CloudSky.coverage(level, partialTick);
        float threshold = CloudField.threshold(coverage);
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        int nr = RINGS.length;
        int ns = SEGMENTS + 1;
        float[][] col = new float[nr * ns][];
        for (int r = 0; r < nr; r++) {
            float rad = RINGS[r];
            float fogT = com.yayuy.overgrownvegetation.client.atmosphere.SunLight.smooth(Mth.clamp((rad - FOG_FROM) / (RINGS[nr - 1] - FOG_FROM), 0.0F, 1.0F));
            for (int s = 0; s < ns; s++) {
                double t = Math.PI * 2.0 * s / SEGMENTS;
                double wx = camX + Math.cos(t) * rad;
                double wz = camZ + Math.sin(t) * rad;
                // 地面らしさ(11-08 仕上げ 1): 大きな升のゆるいムラ(森の濃い緑と開けた所の淡い色)。世界の座標で決まるので、カメラと一緒に動かない。
                float n = 0.65F * noise(wx / 1400.0, wz / 1400.0) + 0.35F * noise(wx / 480.0 + 31.0, wz / 480.0 - 17.0);
                n = com.yayuy.overgrownvegetation.client.atmosphere.SunLight.smooth(Mth.clamp((n - 0.3F) / 0.4F, 0.0F, 1.0F)); // 2 つの和は 0.5 に寄るので広げる
                float[] c = new float[3];
                float shade = 1.0F;
                if (coverage > 0.0F) {
                    CloudField.Column cc = com.yayuy.overgrownvegetation.sky.CloudSky.columnRendered(CloudField.fieldX(wx, gt, partialTick),
                            CloudField.fieldZ(wz, gt, partialTick), threshold, coverage, style);
                    if (cc.present()) {
                        shade = 1.0F - CLOUD_SHADE * (1.0F - fogT);
                    }
                }
                for (int i = 0; i < 3; i++) {
                    float land = Mth.lerp(n, OPEN[i], FOREST[i]) * bright * shade;
                    float haze = Mth.lerp(SkyDev.landMix, fog[i], land);
                    c[i] = Mth.lerp(fogT, haze, fog[i]);
                }
                col[r * ns + s] = c;
            }
        }
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int r = 0; r + 1 < nr; r++) {
            float r0 = RINGS[r];
            float r1 = RINGS[r + 1];
            float a0 = show;
            float a1 = show * (r + 1 == nr - 1 ? 0.0F : 1.0F);
            float yy0 = y0 - sink(r0, curve);
            float yy1 = y0 - sink(r1, curve);
            for (int s = 0; s < SEGMENTS; s++) {
                double t0 = Math.PI * 2.0 * s / SEGMENTS;
                double t1 = Math.PI * 2.0 * (s + 1) / SEGMENTS;
                float x00 = (float) (Math.cos(t0) * r0), z00 = (float) (Math.sin(t0) * r0);
                float x01 = (float) (Math.cos(t1) * r0), z01 = (float) (Math.sin(t1) * r0);
                float x10 = (float) (Math.cos(t0) * r1), z10 = (float) (Math.sin(t0) * r1);
                float x11 = (float) (Math.cos(t1) * r1), z11 = (float) (Math.sin(t1) * r1);
                float[] c00 = col[r * ns + s], c01 = col[r * ns + s + 1], c10 = col[(r + 1) * ns + s], c11 = col[(r + 1) * ns + s + 1];
                b.addVertex(x00, yy0, z00).setColor(c00[0], c00[1], c00[2], a0);
                b.addVertex(x10, yy1, z10).setColor(c10[0], c10[1], c10[2], a1);
                b.addVertex(x11, yy1, z11).setColor(c11[0], c11[1], c11[2], a1);
                b.addVertex(x01, yy0, z01).setColor(c01[0], c01[1], c01[2], a0);
            }
        }
        MeshData mesh = b.build();
        if (mesh == null) {
            return;
        }
        if (vbo == null) {
            vbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        vbo.bind();
        vbo.upload(mesh);
        vbo.drawWithShader(modelView, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 雲と同じ丸み(voxel_clouds.vsh の Curve)。 */
    private static float sink(float d, float curve) {
        float past = Math.max(d - VoxelClouds.CURVE_FROM, 0.0F);
        return curve * past * past * (0.5F / SkyDev.curveRadius());
    }

    /** なめらかな値の雑音(0〜1)。 */
    private static float noise(double x, double z) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        float fx = (float) (x - ix);
        float fz = (float) (z - iz);
        fx = fx * fx * (3.0F - 2.0F * fx);
        fz = fz * fz * (3.0F - 2.0F * fz);
        float a = hash(ix, iz);
        float b = hash(ix + 1, iz);
        float c = hash(ix, iz + 1);
        float d = hash(ix + 1, iz + 1);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + 0x2545F4914F6CDD1DL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1 << 24);
    }

    private HazeFloor() {
    }
}
