package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.shader.PortRender;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 天気の見た目の残り(2026-11-16)が使う形の部品。座標はどれも<b>カメラからの差</b>。
 * <ul>
 *     <li>加算の光(四角。{@link AddLight} の器へ): {@link #glowLine}(カメラを向く帯。両端で色と太さを変えられる)・{@link #glowDisc}(真ん中が明るい丸)。</li>
 *     <li>ふつうの混ぜ方のやわらかい形(三角。{@link #begin} 〜 {@link #end}): {@link #softEllipse}(水平な楕円。真ん中が濃く縁が 0)・
 *     {@link #softStreak}(カメラを向く短い帯)。霧を受けない描き方(色だけの頂点)なので、薄め方は呼び手が決める。</li>
 * </ul>
 */
final class WeatherShapes {

    private WeatherShapes() {
    }

    // ------------------------------------------------------------------ 加算の光(QUADS)

    /** a → b の帯。{@code wa}・{@code wb} = 両端の太さ、色は両端で別々({r, g, b, a})。 */
    static void glowLine(VertexConsumer c, float ax, float ay, float az, float bx, float by, float bz, float wa, float wb, float[] ca, float[] cb) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float mx = (ax + bx) * 0.5F, my = (ay + by) * 0.5F, mz = (az + bz) * 0.5F;
        // 帯の横の向き = 線の向き × 視線。
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float len = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-6F) {
            return;
        }
        float ka = wa * 0.5F / len;
        float kb = wb * 0.5F / len;
        c.vertex(ax - sx * ka, ay - sy * ka, az - sz * ka).color(ca[0], ca[1], ca[2], ca[3]).endVertex();
        c.vertex(ax + sx * ka, ay + sy * ka, az + sz * ka).color(ca[0], ca[1], ca[2], ca[3]).endVertex();
        c.vertex(bx + sx * kb, by + sy * kb, bz + sz * kb).color(cb[0], cb[1], cb[2], cb[3]).endVertex();
        c.vertex(bx - sx * kb, by - sy * kb, bz - sz * kb).color(cb[0], cb[1], cb[2], cb[3]).endVertex();
    }

    /** カメラを向く丸い光(真ん中が {@code alpha}、縁が 0。8 角形)。{@code stretchUp} = 縦の伸び。 */
    static void glowDisc(VertexConsumer c, float x, float y, float z, float radius, float stretchUp, Vector3f left, Vector3f up, float r, float g, float b,
                         float alpha) {
        if (alpha <= 0.002F) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            float a0 = i * Mth.TWO_PI / 8.0F;
            float a1 = (i + 1) * Mth.TWO_PI / 8.0F;
            float c0 = Mth.cos(a0) * radius, s0 = Mth.sin(a0) * radius * stretchUp;
            float c1 = Mth.cos(a1) * radius, s1 = Mth.sin(a1) * radius * stretchUp;
            c.vertex(x, y, z).color(r, g, b, alpha).endVertex();
            c.vertex(x + left.x() * c0 + up.x() * s0, y + left.y() * c0 + up.y() * s0, z + left.z() * c0 + up.z() * s0).color(r, g, b, 0.0F).endVertex();
            c.vertex(x + left.x() * c1 + up.x() * s1, y + left.y() * c1 + up.y() * s1, z + left.z() * c1 + up.z() * s1).color(r, g, b, 0.0F).endVertex();
            c.vertex(x, y, z).color(r, g, b, alpha).endVertex();
        }
    }

    // ------------------------------------------------------------------ ふつうの混ぜ方(TRIANGLES)

    private static BufferBuilder builder;
    /** 1.20.1: 自分だけの器(Tesselator の 1 つの器は begin から end まで他の演出と取り合えない。1.21 は毎回新しい器)。 */
    private static BufferBuilder own;

    private static BufferBuilder own() {
        if (own == null) {
            own = new BufferBuilder(1 << 15);
        }
        return own;
    }
    private static Matrix4f modelView;
    private static Matrix4f projection;
    private static boolean any;

    static void begin(RenderLevelStageEvent event) {
        builder = PortRender.begin(own(), VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        modelView = new Matrix4f(PortRender.modelView(event));
        projection = new Matrix4f(event.getProjectionMatrix());
        any = false;
    }

    static void end() {
        if (builder == null) {
            return;
        }
        BufferBuilder.RenderedBuffer mesh = builder.endOrDiscardIfEmpty();
        builder = null;
        if (mesh == null || !any) {
            if (mesh != null) {
                mesh.release();
            }
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        if (shader == null) {
            mesh.release();
        } else {
            PortRender.setDefaultUniforms(shader, VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void v(float x, float y, float z, float r, float g, float b, float a) {
        builder.vertex(x, y, z).color(r, g, b, a).endVertex();
    }

    /**
     * 水平な楕円(真ん中が {@code alpha}、縁が 0。12 角形)。長い軸の向き (dirX, dirZ)、半分の長さ {@code halfLen}・半分の幅 {@code halfWid}。
     * {@code scale} = 遠い物を寄せて描く倍率(位置と大きさの両方に掛かる)。
     */
    static void softEllipse(float x, float y, float z, float dirX, float dirZ, float halfLen, float halfWid, float scale, float r, float g, float b,
                            float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        any = true;
        float cx = x * scale, cy = y * scale, cz = z * scale;
        float lx = dirX * halfLen * scale, lz = dirZ * halfLen * scale;
        float wx = -dirZ * halfWid * scale, wz = dirX * halfWid * scale;
        for (int i = 0; i < 12; i++) {
            float a0 = i * Mth.TWO_PI / 12.0F;
            float a1 = (i + 1) * Mth.TWO_PI / 12.0F;
            v(cx, cy, cz, r, g, b, alpha);
            v(cx + lx * Mth.cos(a0) + wx * Mth.sin(a0), cy, cz + lz * Mth.cos(a0) + wz * Mth.sin(a0), r, g, b, 0.0F);
            v(cx + lx * Mth.cos(a1) + wx * Mth.sin(a1), cy, cz + lz * Mth.cos(a1) + wz * Mth.sin(a1), r, g, b, 0.0F);
        }
    }

    /** カメラを向く短い帯(a が頭で濃く、b が尾で 0)。横の縁も 0 にぼかす。 */
    static void softStreak(float ax, float ay, float az, float bx, float by, float bz, float width, float r, float g, float b, float alpha) {
        if (builder == null || alpha <= 0.002F) {
            return;
        }
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float sx = dy * az - dz * ay;
        float sy = dz * ax - dx * az;
        float sz = dx * ay - dy * ax;
        float len = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-6F) {
            return;
        }
        any = true;
        float k = width * 0.5F / len;
        sx *= k;
        sy *= k;
        sz *= k;
        // 真ん中の線(濃い)と左右の縁(0)の 2 枚。
        for (int side = -1; side <= 1; side += 2) {
            float ex = sx * side, ey = sy * side, ez = sz * side;
            v(ax, ay, az, r, g, b, alpha);
            v(ax + ex, ay + ey, az + ez, r, g, b, 0.0F);
            v(bx + ex, by + ey, bz + ez, r, g, b, 0.0F);
            v(ax, ay, az, r, g, b, alpha);
            v(bx + ex, by + ey, bz + ez, r, g, b, 0.0F);
            v(bx, by, bz, r, g, b, 0.0F);
        }
    }

    /** 塗りつぶした四角(裏表なし)。4 つの角を順に、角ごとの濃さで。 */
    static void quad(float[] p0, float[] p1, float[] p2, float[] p3, float r, float g, float b, float a0, float a1, float a2, float a3) {
        if (builder == null) {
            return;
        }
        any = true;
        v(p0[0], p0[1], p0[2], r, g, b, a0);
        v(p1[0], p1[1], p1[2], r, g, b, a1);
        v(p2[0], p2[1], p2[2], r, g, b, a2);
        v(p0[0], p0[1], p0[2], r, g, b, a0);
        v(p2[0], p2[1], p2[2], r, g, b, a2);
        v(p3[0], p3[1], p3[2], r, g, b, a3);
    }
}
