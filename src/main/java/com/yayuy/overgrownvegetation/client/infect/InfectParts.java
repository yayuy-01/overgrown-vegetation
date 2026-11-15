package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汎用の感染体の、体から生える部品。<b>G1 段階 0 を作った生成器の部品</b>を使う(2026-11-15 ユーザー: 11-04 の 36 個は「少し微妙」)
 * = 巻きシダの株・胞子の袋・裂けた肋骨・背骨の突起・めくれた皮({@code tools/parasite-parts/G1Parts.java} → {@code G1PartsExport} で書き出し。形は {@link PartModels})。
 * <p>骨の名前を知らずに手足へ付いて行かせるため、感染体を描くたびに頂点の流れを覗き、付け根に選んだ四角(流れの中の順番で覚える)の
 * 今の位置を拾って、その上に部品を描く。付け方は G1 の決まり({@code tools/parasite-parts/README.md} の「部品の集まり」)に寄せる:</p>
 * <ul>
 *   <li><b>主役は巻きシダの株</b>: 上を向く高い面に 1 株。大きさは体の大きさで 1・2・3。大きな体にはもう 1 株(小さめ)。</li>
 *   <li><b>胞子の袋は 1 つ</b>: 横の面(無ければ上の面)。</li>
 *   <li><b>骨</b>: 上の面に背骨の突起、無ければ横の面に裂けた肋骨。小さな体には付けない。</li>
 *   <li><b>めくれた皮</b>: 横の面に 1 枚。小さな体には付けない。</li>
 *   <li>部品の +Y を面の外へ、−Z を面に沿った重力の向きへ(上下の面では向きは乱数)。付け根同士は離す。小さな面には付けない。</li>
 * </ul>
 */
final class InfectParts {

    /** 確かめ用の切り替え(重さの比べ)。 */
    static boolean enabled = true;

    private static final int UP = 1, SIDE = 2;

    private InfectParts() {
    }

    /**
     * 付け根 1 つ: 四角の順番(その RenderType の流れの中で)・確かめ用の絵の上の真ん中・四角の中の位置(4 頂点の重み)・部品・
     * 四角の 1 辺目の長さ(1/16 ブロック単位。今の辺の長さと比べて部品の大きさを決める)・上下の面での向き(ラジアン)。
     */
    record Anchor(int quad, float uc, float vc, float w0, float w1, float w2, float w3, String part, float edgeUnits, float yaw) {
    }

    /** 覗いた形から付け根を選ぶ。 */
    static List<Anchor> choose(InfectSkins.FaceMap m, List<InfectRender.Quad> quads, long seed) {
        List<Anchor> out = new ArrayList<>();
        List<float[]> placed = new ArrayList<>();
        boolean[] taken = new boolean[quads.size()];
        // 体の大きさ: 小(鶏)= 1、中(ゾンビ・村人)= 2、大(牛・白熊)= 3
        int size = m.extent < 0.8F ? 1 : m.extent < 1.5F ? 2 : 3;
        place(out, placed, taken, m, quads, seed + 1, "curled_fern_" + size, UP, 0.5F, 4.0F);
        if (!place(out, placed, taken, m, quads, seed + 2, "g1_sac_" + size, SIDE, 0.45F, size + 2.0F)) {
            place(out, placed, taken, m, quads, seed + 3, "g1_sac_" + size, UP, 0.45F, size + 2.0F);
        }
        if (size >= 2) {
            if (!place(out, placed, taken, m, quads, seed + 4, "g1_spine", UP, 0.45F, 6.0F)) {
                place(out, placed, taken, m, quads, seed + 5, "g1_ribs", SIDE, 0.45F, 5.0F);
            }
            place(out, placed, taken, m, quads, seed + 6, "g1_flap", SIDE, 0.4F, 5.0F);
        }
        if (size >= 3) {
            place(out, placed, taken, m, quads, seed + 7, "curled_fern_2", UP, 0.5F, 4.0F);
        }
        return out;
    }

    /**
     * 部品 1 つの付け根を探して足す。{@code faces} = 付けてよい面の向き、{@code minY} = 高さの下限(0 = 足元〜1 = てっぺん)、
     * {@code minEdge} = 面の 2 辺の下限(1/16 ブロック)。見つからなければ false。
     */
    private static boolean place(List<Anchor> out, List<float[]> placed, boolean[] taken, InfectSkins.FaceMap m, List<InfectRender.Quad> quads,
                                 long seed, String part, int faces, float minY, float minEdge) {
        int n = m.w * m.h;
        float minGap = 0.16F * m.extent;
        for (int t = 0; t < 500; t++) {
            int i = (int) (InfectPaint.hash(t, 31, seed) * n);
            if (i >= n || m.quad[i] < 0 || taken[m.quad[i]] || (m.argb[i] >>> 24) < 16) {
                continue;
            }
            float ny = m.ny[i];
            int face = ny > 0.5F ? UP : ny < -0.5F ? 0 : SIDE;
            if ((face & faces) == 0 || m.yRel(i) < minY) {
                continue;
            }
            int q = m.quad[i];
            float[] p = quads.get(q).pos(), uv = quads.get(q).uv();
            float du = (uv[2] - uv[0]) * m.w, dv = (uv[3] - uv[1]) * m.h;
            float edgeUnits = (float) Math.sqrt(du * du + dv * dv) / m.k;
            float du2 = (uv[6] - uv[0]) * m.w, dv2 = (uv[7] - uv[1]) * m.h;
            float edge2 = (float) Math.sqrt(du2 * du2 + dv2 * dv2) / m.k;
            if (edgeUnits < minEdge || edge2 < minEdge) {
                continue;
            }
            float s = 0.3F + 0.4F * (float) InfectPaint.hash(t, 32, seed), u = 0.3F + 0.4F * (float) InfectPaint.hash(t, 33, seed);
            float w0 = (1 - s) * (1 - u), w1 = s * (1 - u), w2 = s * u, w3 = (1 - s) * u;
            float x = w0 * p[0] + w1 * p[3] + w2 * p[6] + w3 * p[9];
            float y = w0 * p[1] + w1 * p[4] + w2 * p[7] + w3 * p[10];
            float z = w0 * p[2] + w1 * p[5] + w2 * p[8] + w3 * p[11];
            boolean close = false;
            for (float[] o : placed) {
                float dx = o[0] - x, dy = o[1] - y, dz = o[2] - z;
                if (dx * dx + dy * dy + dz * dz < minGap * minGap) {
                    close = true;
                    break;
                }
            }
            if (close) {
                continue;
            }
            taken[q] = true;
            placed.add(new float[]{x, y, z});
            float uc = (uv[0] + uv[2] + uv[4] + uv[6]) / 4.0F, vc = (uv[1] + uv[3] + uv[5] + uv[7]) / 4.0F;
            float yaw = (float) (InfectPaint.hash(t, 37, seed) * Math.PI * 2.0);
            out.add(new Anchor(q, uc, vc, w0, w1, w2, w3, part, edgeUnits, yaw));
            return true;
        }
        return false;
    }

    /** 器を包み、付け根の四角の今の位置を拾う(頂点はそのまま元の器へ流す)。 */
    static final class AnchorTap implements MultiBufferSource {
        private final MultiBufferSource inner;
        private final Map<RenderType, Catcher> catchers = new IdentityHashMap<>();

        AnchorTap(MultiBufferSource inner) {
            this.inner = inner;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer out = this.inner.getBuffer(type);
            Catcher c = this.catchers.get(type);
            if (c == null) {
                ResourceLocation texture = InfectRender.TYPE_TEXTURE.get(type);
                List<Anchor> anchors = texture == null ? null : InfectSkins.anchorsFor(texture);
                if (anchors == null || anchors.isEmpty()) {
                    return out;
                }
                c = new Catcher(anchors);
                this.catchers.put(type, c);
            }
            c.out = out;
            return c;
        }
    }

    private static final class Catcher implements VertexConsumer {
        private final List<Anchor> anchors;
        /** 拾った位置(付け根ごとに 4 頂点 × xyz + 面の向き xyz)。{@code found[i]} が立った分だけ有効。 */
        private final float[] caught;
        private final boolean[] found;
        VertexConsumer out;
        private int quad;
        private int n;
        private final float[] pos = new float[12];
        private float us, vs, nx, ny, nz;

        Catcher(List<Anchor> anchors) {
            this.anchors = anchors;
            this.caught = new float[anchors.size() * 15];
            this.found = new boolean[anchors.size()];
        }

        private void vertex(float x, float y, float z) {
            if (this.n == 4) {
                finish();
            }
            int i = this.n * 3;
            this.pos[i] = x;
            this.pos[i + 1] = y;
            this.pos[i + 2] = z;
            this.n++;
        }

        private void finish() {
            if (this.n == 4) {
                float uc = this.us / 4.0F, vc = this.vs / 4.0F;
                for (int a = 0; a < this.anchors.size(); a++) {
                    Anchor an = this.anchors.get(a);
                    if (an.quad == this.quad && Math.abs(an.uc - uc) < 1.0E-3F && Math.abs(an.vc - vc) < 1.0E-3F) {
                        System.arraycopy(this.pos, 0, this.caught, a * 15, 12);
                        this.caught[a * 15 + 12] = this.nx;
                        this.caught[a * 15 + 13] = this.ny;
                        this.caught[a * 15 + 14] = this.nz;
                        this.found[a] = true;
                    }
                }
                this.quad++;
            }
            this.n = 0;
            this.us = this.vs = 0.0F;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vertex(x, y, z);
            this.out.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.out.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.us += u;
            this.vs += v;
            this.out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.out.setNormal(nx, ny, nz);
            if (this.n == 4) {
                finish();
            }
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay, int packedLight,
                              float normalX, float normalY, float normalZ) {
            vertex(x, y, z);
            this.us += u;
            this.vs += v;
            this.nx = normalX;
            this.ny = normalY;
            this.nz = normalZ;
            this.out.addVertex(x, y, z, color, u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
            if (this.n == 4) {
                finish();
            }
        }
    }

    /** 拾った付け根の上に部品を描く(位置はカメラからの世界の位置そのまま)。 */
    static void draw(AnchorTap tap, MultiBufferSource buffers, int packedLight) {
        for (Catcher c : tap.catchers.values()) {
            for (int a = 0; a < c.anchors.size(); a++) {
                if (!c.found[a]) {
                    continue;
                }
                Anchor an = c.anchors.get(a);
                PartModels.Model model = PartModels.get(an.part);
                if (model == null) {
                    continue;
                }
                float[] p = c.caught;
                int o = a * 15;
                float x = an.w0 * p[o] + an.w1 * p[o + 3] + an.w2 * p[o + 6] + an.w3 * p[o + 9];
                float y = an.w0 * p[o + 1] + an.w1 * p[o + 4] + an.w2 * p[o + 7] + an.w3 * p[o + 10];
                float z = an.w0 * p[o + 2] + an.w1 * p[o + 5] + an.w2 * p[o + 8] + an.w3 * p[o + 11];
                // 部品の +Y = 面の外
                float yx = p[o + 12], yy = p[o + 13], yz = p[o + 14];
                // 面に沿った向き = 四角の 1 辺。その今の長さから、1 単位(1/16 ブロック)の大きさも出す
                float tx = p[o + 3] - p[o], ty = p[o + 4] - p[o + 1], tz = p[o + 5] - p[o + 2];
                float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl < 1.0E-6F) {
                    continue;
                }
                float unit = tl / an.edgeUnits;
                tx /= tl;
                ty /= tl;
                tz /= tl;
                // 部品の −Z = 面に沿った重力の向き(世界の下を面へ落とした向き)。水平な面では乱数の向き
                float gx = yx * yy, gy = yy * yy - 1.0F, gz = yz * yy;
                float gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
                float zx, zy, zz;
                if (gl > 0.3F) {
                    zx = -gx / gl;
                    zy = -gy / gl;
                    zz = -gz / gl;
                } else {
                    float bx = yy * tz - yz * ty, by = yz * tx - yx * tz, bz = yx * ty - yy * tx;
                    float cs = (float) Math.cos(an.yaw), sn = (float) Math.sin(an.yaw);
                    zx = cs * tx + sn * bx;
                    zy = cs * ty + sn * by;
                    zz = cs * tz + sn * bz;
                }
                // X = Y × Z
                float xx = yy * zz - yz * zy, xy = yz * zx - yx * zz, xz = yx * zy - yy * zx;
                VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(model.texture()));
                int ov = OverlayTexture.NO_OVERLAY;
                float[] mp = model.pos(), mu = model.uv(), mn = model.normal();
                for (int q = 0; q < model.quads(); q++) {
                    float nx0 = mn[q * 3], ny0 = mn[q * 3 + 1], nz0 = mn[q * 3 + 2];
                    float nx = nx0 * xx + ny0 * yx + nz0 * zx, ny = nx0 * xy + ny0 * yy + nz0 * zy, nz = nx0 * xz + ny0 * yz + nz0 * zz;
                    for (int v = 0; v < 4; v++) {
                        float px = mp[q * 12 + v * 3] * unit, py = mp[q * 12 + v * 3 + 1] * unit, pz = mp[q * 12 + v * 3 + 2] * unit;
                        vc.addVertex(x + px * xx + py * yx + pz * zx, y + px * xy + py * yy + pz * zy, z + px * xz + py * yz + pz * zz,
                                -1, mu[q * 8 + v * 2], mu[q * 8 + v * 2 + 1], ov, packedLight, nx, ny, nz);
                    }
                }
            }
        }
    }
}
