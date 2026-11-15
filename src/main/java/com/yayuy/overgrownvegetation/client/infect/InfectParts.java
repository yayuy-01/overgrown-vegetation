package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汎用の感染体の、体から生える部品(<b>試作 2026-11-15</b>: シダ 1 種を十字の板で。重さを測るための物)。
 * <p>骨の名前を知らずに手足へ付いて行かせるため、感染体を描くたびに頂点の流れを覗き、付け根に選んだ四角(流れの中の順番で覚える)の
 * 今の位置を拾って、その上に部品を描く。</p>
 */
final class InfectParts {

    /** 確かめ用の切り替え(重さの比べ)。 */
    static boolean enabled = true;

    private static final ResourceLocation FERN = OvergrownVegetation.id("textures/block/overgrown_tall_fern_top.png");
    /** 1 枚の絵に付ける付け根の数(試作の値)。 */
    private static final int MAX_ANCHORS = 6;

    private InfectParts() {
    }

    /** 付け根 1 つ: 四角の順番(その RenderType の流れの中で)・四角の中の位置(4 頂点の重み)・確かめ用の絵の上の真ん中・大きさ(ブロック)。 */
    record Anchor(int quad, float uc, float vc, float w0, float w1, float w2, float w3, float size) {
    }

    /** 覗いた形から付け根を選ぶ(上を向く・高い所の面から。同じ四角には 1 つ)。 */
    static List<Anchor> choose(InfectSkins.FaceMap m, List<InfectRender.Quad> quads, long seed) {
        List<Anchor> out = new ArrayList<>();
        int n = m.w * m.h;
        boolean[] taken = new boolean[quads.size()];
        for (int t = 0; t < 600 && out.size() < MAX_ANCHORS; t++) {
            int i = (int) (InfectPaint.hash(t, 31, seed) * n);
            if (i >= n || m.quad[i] < 0 || taken[m.quad[i]] || (m.argb[i] >>> 24) < 16) {
                continue;
            }
            boolean top = m.ny[i] > 0.4F && m.yRel(i) > 0.5F;
            boolean side = Math.abs(m.ny[i]) <= 0.4F && m.yRel(i) > 0.6F && out.size() >= MAX_ANCHORS - 2;
            if (!top && !side) {
                continue;
            }
            int q = m.quad[i];
            taken[q] = true;
            float[] uv = quads.get(q).uv();
            float uc = (uv[0] + uv[2] + uv[4] + uv[6]) / 4.0F, vc = (uv[1] + uv[3] + uv[5] + uv[7]) / 4.0F;
            float s = 0.25F + 0.5F * (float) InfectPaint.hash(t, 32, seed), u = 0.25F + 0.5F * (float) InfectPaint.hash(t, 33, seed);
            float size = (5.0F + 4.0F * (float) InfectPaint.hash(t, 34, seed)) / 16.0F;
            out.add(new Anchor(q, uc, vc, (1 - s) * (1 - u), s * (1 - u), s * u, (1 - s) * u, size));
        }
        return out;
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
        VertexConsumer vc = null;
        for (Catcher c : tap.catchers.values()) {
            for (int a = 0; a < c.anchors.size(); a++) {
                if (!c.found[a]) {
                    continue;
                }
                if (vc == null) {
                    vc = buffers.getBuffer(RenderType.entityCutoutNoCull(FERN));
                }
                Anchor an = c.anchors.get(a);
                float[] p = c.caught;
                int o = a * 15;
                float x = an.w0 * p[o] + an.w1 * p[o + 3] + an.w2 * p[o + 6] + an.w3 * p[o + 9];
                float y = an.w0 * p[o + 1] + an.w1 * p[o + 4] + an.w2 * p[o + 7] + an.w3 * p[o + 10];
                float z = an.w0 * p[o + 2] + an.w1 * p[o + 5] + an.w2 * p[o + 8] + an.w3 * p[o + 11];
                float nx = p[o + 12], ny = p[o + 13], nz = p[o + 14];
                // 面に沿った向き = 四角の 1 辺
                float tx = p[o + 3] - p[o], ty = p[o + 4] - p[o + 1], tz = p[o + 5] - p[o + 2];
                float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl < 1.0E-6F) {
                    continue;
                }
                tx /= tl;
                ty /= tl;
                tz /= tl;
                float bx = ny * tz - nz * ty, by = nz * tx - nx * tz, bz = nx * ty - ny * tx;
                float h = an.size, w = an.size * 0.5F;
                plane(vc, x, y, z, tx * w, ty * w, tz * w, nx * h, ny * h, nz * h, packedLight, nx, ny, nz);
                plane(vc, x, y, z, bx * w, by * w, bz * w, nx * h, ny * h, nz * h, packedLight, nx, ny, nz);
            }
        }
    }

    private static void plane(VertexConsumer vc, float x, float y, float z, float sx, float sy, float sz, float ux, float uy, float uz,
                              int light, float nx, float ny, float nz) {
        int ov = OverlayTexture.NO_OVERLAY;
        vc.addVertex(x - sx, y - sy, z - sz, -1, 0.0F, 1.0F, ov, light, nx, ny, nz);
        vc.addVertex(x + sx, y + sy, z + sz, -1, 1.0F, 1.0F, ov, light, nx, ny, nz);
        vc.addVertex(x + sx + ux, y + sy + uy, z + sz + uz, -1, 1.0F, 0.0F, ov, light, nx, ny, nz);
        vc.addVertex(x - sx + ux, y - sy + uy, z - sz + uz, -1, 0.0F, 0.0F, ov, light, nx, ny, nz);
    }
}
