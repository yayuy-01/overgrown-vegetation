package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 見え方の段(2026-10-31)の小さな形(湯気・水面の輪と泡・羽虫。幹を伝う水は 2026-11-01 に退避)を 1 回の描画にまとめる。
 * 形は頂点の色だけ(テクスチャ無し、{@code position_color})で、ふちの透明度を 0 にした多角形でぼかす。数は全部で数百の三角形なので CPU で組む。
 * 描く段階は AFTER_PARTICLES(粒の後、加算の光の前)。各演出は {@link #begin} と {@link #end} の間に形を足す。
 */
final class LifeSprites {

    private static final int RIM = 8;
    private static BufferBuilder builder;
    private static Matrix4f modelView;
    private static Matrix4f projection;
    private static Vec3 cam;
    private static boolean any;
    private static boolean additive;
    /** 霧で薄めない(洞窟の土ボタルの光: 地下の黒い霧の中でも光る画素と同じく沈めない。11-07)。end で戻る。 */
    private static boolean noFog;

    static void begin(RenderLevelStageEvent event) {
        begin(event, false);
    }

    /** additive = 足し算で重ねる(光る粒。夜の光る藻 {@link GlowAlgae})。 */
    static void begin(RenderLevelStageEvent event, boolean add) {
        additive = add;
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        modelView = new Matrix4f(event.getModelViewMatrix());
        projection = new Matrix4f(event.getProjectionMatrix());
        cam = event.getCamera().getPosition();
        any = false;
    }

    /** 霧で薄めない描き方で始める(足し算)。 */
    static void beginGlowNoFog(RenderLevelStageEvent event) {
        begin(event, true);
        noFog = true;
    }

    static void end() {
        boolean wasNoFog = noFog;
        noFog = false;
        if (wasNoFog && builder == null) {
            return;
        }
        if (builder == null) {
            return;
        }
        MeshData mesh = builder.build();
        builder = null;
        if (mesh == null || !any) {
            if (mesh != null) {
                mesh.close();
            }
            return;
        }
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            mesh.close();
        } else {
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void planeVertex(double x, double y, double z, float ux, float uy, float uz, float vx, float vy, float vz, float a, float c,
                                    float r, float g, float b, float alpha) {
        vertex(x + ux * a + vx * c, y + uy * a + vy * c, z + uz * a + vz * c, r, g, b, alpha);
    }

    private static void vertex(double x, double y, double z, float r, float g, float b, float a) {
        float dx = (float) (x - cam.x);
        float dy = (float) (y - cam.y);
        float dz = (float) (z - cam.z);
        // この描き方(POSITION_COLOR)は霧を受けないので、森の中の霞の分だけ薄める(霞の中で粒だけ浮かないように、11-01)。
        float hazed = noFog ? a : a * (1.0F - MistView.fogAmount(Mth.sqrt(dx * dx + dy * dy + dz * dz)));
        builder.addVertex(dx, dy, dz).setColor(r, g, b, hazed);
    }

    /** カメラを向いたぼけた円(真ん中が {@code alpha}、ふちが 0)。left・up はカメラの左と上の向き。 */
    static void blob(double x, double y, double z, float radius, float stretchUp, Vector3f left, Vector3f up, float r, float g, float b,
                     float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        any = true;
        for (int i = 0; i < RIM; i++) {
            float a0 = i * Mth.TWO_PI / RIM;
            float a1 = (i + 1) * Mth.TWO_PI / RIM;
            vertex(x, y, z, r, g, b, alpha);
            vertex(x + (left.x() * Mth.cos(a0) + up.x() * Mth.sin(a0) * stretchUp) * radius,
                    y + (left.y() * Mth.cos(a0) + up.y() * Mth.sin(a0) * stretchUp) * radius,
                    z + (left.z() * Mth.cos(a0) + up.z() * Mth.sin(a0) * stretchUp) * radius, r, g, b, 0.0F);
            vertex(x + (left.x() * Mth.cos(a1) + up.x() * Mth.sin(a1) * stretchUp) * radius,
                    y + (left.y() * Mth.cos(a1) + up.y() * Mth.sin(a1) * stretchUp) * radius,
                    z + (left.z() * Mth.cos(a1) + up.z() * Mth.sin(a1) * stretchUp) * radius, r, g, b, 0.0F);
        }
    }

    /** 水平なぼけた円盤(真ん中が {@code alpha}、ふちが 0。{@link #RIM} 角形で軽い)。胞子の溜まりの霧の層({@link SporePools})が使う(11-03)。 */
    static void disc(double x, double y, double z, float radius, float r, float g, float b, float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        any = true;
        for (int i = 0; i < RIM; i++) {
            float a0 = i * Mth.TWO_PI / RIM;
            float a1 = (i + 1) * Mth.TWO_PI / RIM;
            vertex(x, y, z, r, g, b, alpha);
            vertex(x + Mth.cos(a0) * radius, y, z + Mth.sin(a0) * radius, r, g, b, 0.0F);
            vertex(x + Mth.cos(a1) * radius, y, z + Mth.sin(a1) * radius, r, g, b, 0.0F);
        }
    }

    /** カメラを向いた小さな四角(羽虫の点)。 */
    static void dot(double x, double y, double z, float half, Vector3f left, Vector3f up, float r, float g, float b, float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        any = true;
        double lx = left.x() * half;
        double ly = left.y() * half;
        double lz = left.z() * half;
        double ux = up.x() * half;
        double uy = up.y() * half;
        double uz = up.z() * half;
        vertex(x - lx - ux, y - ly - uy, z - lz - uz, r, g, b, alpha);
        vertex(x + lx - ux, y + ly - uy, z + lz - uz, r, g, b, alpha);
        vertex(x + lx + ux, y + ly + uy, z + lz + uz, r, g, b, alpha);
        vertex(x - lx - ux, y - ly - uy, z - lz - uz, r, g, b, alpha);
        vertex(x + lx + ux, y + ly + uy, z + lz + uz, r, g, b, alpha);
        vertex(x - lx + ux, y - ly + uy, z - lz + uz, r, g, b, alpha);
    }

    /** 塗りつぶした四角(4 つの角を順に。裏表なし)。幹のトカゲ({@link TrunkLizards})の体の部品(11-06)。 */
    static void quad(double x0, double y0, double z0, double x1, double y1, double z1, double x2, double y2, double z2,
                     double x3, double y3, double z3, float r, float g, float b, float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        any = true;
        vertex(x0, y0, z0, r, g, b, alpha);
        vertex(x1, y1, z1, r, g, b, alpha);
        vertex(x2, y2, z2, r, g, b, alpha);
        vertex(x0, y0, z0, r, g, b, alpha);
        vertex(x2, y2, z2, r, g, b, alpha);
        vertex(x3, y3, z3, r, g, b, alpha);
    }

    /** 水平な輪(水面の波紋)。半径 radius、幅 width。真ん中の線が alpha、内と外のふちが 0。 */
    static void ring(double x, double y, double z, float radius, float width, float r, float g, float b, float alpha) {
        ring(x, y, z, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, radius, width, r, g, b, alpha);
    }

    /**
     * 面 (u, v) の上のぼけた輪(半径 radius の所が {@code alpha}、内外 width で 0)。u・v は面の中の直交する 2 本の単位ベクトル
     * (水平なら (1,0,0)・(0,0,1))。radius 0 なら丸い面(真ん中が alpha)。波紋の部品 {@link Ripples} が使う(2026-10-31)。
     */
    static void ring(double x, double y, double z, float ux, float uy, float uz, float vx, float vy, float vz, float radius, float width,
                     float r, float g, float b, float alpha) {
        if (builder == null || alpha <= 0.002F || width <= 0.0F) {
            return;
        }
        any = true;
        int seg = radius + width > 0.6F ? 32 : 20;
        float in = Math.max(0.0F, radius - width);
        float out = radius + width;
        for (int i = 0; i < seg; i++) {
            float a0 = i * Mth.TWO_PI / seg;
            float a1 = (i + 1) * Mth.TWO_PI / seg;
            float c0 = Mth.cos(a0);
            float s0 = Mth.sin(a0);
            float c1 = Mth.cos(a1);
            float s1 = Mth.sin(a1);
            for (int band = 0; band < 2; band++) {
                float ra = band == 0 ? in : radius;
                float rb = band == 0 ? radius : out;
                float aa = band == 0 ? 0.0F : alpha;
                float ab = band == 0 ? alpha : 0.0F;
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c0 * ra, s0 * ra, r, g, b, aa);
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c0 * rb, s0 * rb, r, g, b, ab);
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c1 * rb, s1 * rb, r, g, b, ab);
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c0 * ra, s0 * ra, r, g, b, aa);
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c1 * rb, s1 * rb, r, g, b, ab);
                planeVertex(x, y, z, ux, uy, uz, vx, vy, vz, c1 * ra, s1 * ra, r, g, b, aa);
            }
        }
    }

    private LifeSprites() {
    }
}
