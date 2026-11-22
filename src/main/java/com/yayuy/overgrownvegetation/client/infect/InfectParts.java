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
    /** 光る胞子の明るさ(白 = いちばん明るい)。【仮】 */
    private static final int GLOW_TINT = 0xFF8C8C8C;

    private InfectParts() {
    }

    /**
     * 付け根 1 つ: 四角の順番(その RenderType の流れの中で)・確かめ用の絵の上の真ん中・四角の中の位置(4 頂点の重み)・部品・
     * 四角の 1 辺目の長さ(1/16 ブロック単位。今の辺の長さと比べて部品の大きさを決める)・上下の面での向き(ラジアン)。
     */
    record Anchor(int quad, float uc, float vc, float w0, float w1, float w2, float w3, String part, float edgeUnits, float yaw,
                  float cx, float cy, float cz,
                  @javax.annotation.Nullable net.minecraft.client.model.geom.ModelPart owner, @javax.annotation.Nullable float[] local) {
    }

    /**
     * 塗りの裂け目の置き場 = 胞子の袋の付け根(覗いたときの体の位置 x y z と、裂け目の中の半径。4 つ組で並べる)。
     * {@code unit} = 1 単位の長さ(ブロック)。
     */
    static float[] tears(List<Anchor> anchors, float unit) {
        List<Anchor> sacs = new ArrayList<>();
        for (Anchor an : anchors) {
            if (an.part.startsWith("g1_sac")) {
                sacs.add(an);
            }
        }
        float[] out = new float[sacs.size() * 4];
        for (int i = 0; i < sacs.size(); i++) {
            Anchor an = sacs.get(i);
            // 袋の大きさ 1〜3 → 裂け目の中の半径 2.2〜3.4 単位
            float size = an.part.charAt(an.part.length() - 1) - '0';
            out[i * 4] = an.cx;
            out[i * 4 + 1] = an.cy;
            out[i * 4 + 2] = an.cz;
            out[i * 4 + 3] = (1.6F + 0.6F * size) * unit;
        }
        return out;
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
            out.add(new Anchor(q, uc, vc, w0, w1, w2, w3, part, edgeUnits, yaw, x, y, z, quads.get(q).part(), quads.get(q).local()));
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
    /**
     * 頂点の流れから拾った付け根の上に部品を描く(バニラ式でない描き方の Mob 用)。
     * {@code progress} = 変わる動きの進み。植物が突き出る瞬間({@link InfectSkins#STAGE1_AT})から、部品が 1 つずつ少しずれて、
     * 大きさ 0 から行き過ぎて戻る(突き出る)。
     */
    static void draw(AnchorTap tap, MultiBufferSource buffers, int packedLight, float progress) {
        int index = 0;
        for (Catcher c : tap.catchers.values()) {
            for (int a = 0; a < c.anchors.size(); a++) {
                if (c.found[a]) {
                    emit(c.anchors.get(a), c.caught, a * 15, buffers, packedLight, progress, index++);
                }
            }
        }
    }

    /** 部位の姿勢から付け根の今の位置を出して部品を描く(バニラ式の模型の Mob 用。頂点の流れは包まない)。 */
    static void drawPosed(List<Anchor> anchors, Map<net.minecraft.client.model.geom.ModelPart, InfectRender.PoseSlot> slots, int stamp,
                          MultiBufferSource buffers, int packedLight, float progress) {
        float[] p = new float[15];
        org.joml.Vector3f v = new org.joml.Vector3f();
        int index = 0;
        for (Anchor an : anchors) {
            if (an.owner == null || an.local == null) {
                continue;
            }
            InfectRender.PoseSlot slot = slots.get(an.owner);
            if (slot == null || slot.stamp != stamp) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                slot.m.transformPosition(an.local[i * 3], an.local[i * 3 + 1], an.local[i * 3 + 2], v);
                p[i * 3] = v.x;
                p[i * 3 + 1] = v.y;
                p[i * 3 + 2] = v.z;
            }
            slot.m.transformDirection(an.local[12], an.local[13], an.local[14], v);
            if (v.lengthSquared() < 1.0E-12F) {
                continue;
            }
            v.normalize();
            p[12] = v.x;
            p[13] = v.y;
            p[14] = v.z;
            emit(an, p, 0, buffers, packedLight, progress, index++);
        }
    }

    /** 後でまとめて描く部品 1 つぶん(ワールドの中の Mob を描いている間にためる)。 */
    private record Queued(Anchor an, float[] p, int light, float progress, int index) {
    }

    private static final List<Queued> QUEUE = new ArrayList<>();
    /** true の間は、部品をその場で描かずにためる(ワールドの Mob を描く段の間だけ {@link InfectClientEvents} が立てる)。 */
    static boolean deferring;

    /**
     * ためた部品を、種類ごとにまとめて描く(Mob 1 体ごとに描画の設定を切り替えると、そのたびに描画の命令が切れて重い:
     * 馬 60 頭で 1 フレーム約 +4 ms だった)。Mob を描き終えた所で 1 回呼ぶ。
     */
    static void flush(MultiBufferSource.BufferSource buffers) {
        if (QUEUE.isEmpty()) {
            return;
        }
        QUEUE.sort(java.util.Comparator.comparing(q -> q.an().part()));
        String last = null;
        for (Queued q : QUEUE) {
            if (last != null && !last.equals(q.an().part())) {
                endBatch(buffers, last);
            }
            last = q.an().part();
            emitNow(q.an(), q.p(), 0, buffers, q.light(), q.progress(), q.index(), false);
        }
        if (last != null) {
            endBatch(buffers, last);
        }
        // 2 巡目: 光る胞子(袋だけ)。体と部品を描き終えてから重ねる
        last = null;
        for (Queued q : QUEUE) {
            PartModels.Model model = PartModels.get(q.an().part());
            if (model == null || model.glow() == null) {
                continue;
            }
            if (last != null && !last.equals(q.an().part())) {
                endGlow(buffers, last);
            }
            last = q.an().part();
            emitNow(q.an(), q.p(), 0, buffers, q.light(), q.progress(), q.index(), true);
        }
        if (last != null) {
            endGlow(buffers, last);
        }
        QUEUE.clear();
    }

    private static void endBatch(MultiBufferSource.BufferSource buffers, String part) {
        PartModels.Model model = PartModels.get(part);
        if (model != null) {
            buffers.endBatch(RenderType.entityCutoutNoCull(model.texture()));
        }
    }

    private static void endGlow(MultiBufferSource.BufferSource buffers, String part) {
        PartModels.Model model = PartModels.get(part);
        if (model != null && model.glow() != null) {
            buffers.endBatch(RenderType.eyes(model.glow()));
        }
    }

    static void discardQueue() {
        QUEUE.clear();
    }

    private static void emit(Anchor an, float[] p, int o, MultiBufferSource buffers, int packedLight, float progress, int index) {
        if (deferring) {
            float[] copy = new float[15];
            System.arraycopy(p, o, copy, 0, 15);
            QUEUE.add(new Queued(an, copy, packedLight, progress, index));
            return;
        }
        emitNow(an, p, o, buffers, packedLight, progress, index, false);
        PartModels.Model model = PartModels.get(an.part);
        if (model != null && model.glow() != null) {
            emitNow(an, p, o, buffers, packedLight, progress, index, true);
        }
    }

    /** 付け根 1 つぶん。{@code p[o..]} = 付け根の四角の 4 頂点(カメラからの世界の位置)と面の向き。 */
    private static void emitNow(Anchor an, float[] p, int o, MultiBufferSource buffers, int packedLight, float progress, int index, boolean glow) {
        PartModels.Model model = PartModels.get(an.part);
        if (model == null) {
            return;
        }
        float x = an.w0 * p[o] + an.w1 * p[o + 3] + an.w2 * p[o + 6] + an.w3 * p[o + 9];
        float y = an.w0 * p[o + 1] + an.w1 * p[o + 4] + an.w2 * p[o + 7] + an.w3 * p[o + 10];
        float z = an.w0 * p[o + 2] + an.w1 * p[o + 5] + an.w2 * p[o + 8] + an.w3 * p[o + 11];
        // 部品の +Y = 面の外
        float yx = p[o + 12], yy = p[o + 13], yz = p[o + 14];
        // 面に沿った向き = 四角の 1 辺。その今の長さから、1 単位(1/16 ブロック)の大きさも出す
        float tx = p[o + 3] - p[o], ty = p[o + 4] - p[o + 1], tz = p[o + 5] - p[o + 2];
        float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (tl < 1.0E-6F) {
            return;
        }
        float grow = 1.0F;
        if (progress < 1.0F) {
            float g = (progress - InfectSkins.STAGE1_AT - index * 0.04F) / 0.22F;
            if (g <= 0.0F) {
                return;
            }
            if (g < 1.0F) {
                float k = g - 1.0F;
                grow = 1.0F + 2.70158F * k * k * k + 1.70158F * k * k;
            }
        }
        float unit = tl / an.edgeUnits * grow;
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
        VertexConsumer vc = buffers.getBuffer(glow ? RenderType.eyes(model.glow()) : RenderType.entityCutoutNoCull(model.texture()));
        // 光る胞子は微光(段階 0 の決まり): 明るさを落として重ねる【仮】
        int tint = glow ? GLOW_TINT : -1;
        int ov = OverlayTexture.NO_OVERLAY;
        float[] mp = model.pos(), mu = model.uv(), mn = model.normal();
        for (int q = 0; q < model.quads(); q++) {
            float nx0 = mn[q * 3], ny0 = mn[q * 3 + 1], nz0 = mn[q * 3 + 2];
            float nx = nx0 * xx + ny0 * yx + nz0 * zx, ny = nx0 * xy + ny0 * yy + nz0 * zy, nz = nx0 * xz + ny0 * yz + nz0 * zz;
            for (int v = 0; v < 4; v++) {
                float px = mp[q * 12 + v * 3] * unit, py = mp[q * 12 + v * 3 + 1] * unit, pz = mp[q * 12 + v * 3 + 2] * unit;
                vc.addVertex(x + px * xx + py * yx + pz * zx, y + px * xy + py * yy + pz * zy, z + px * xz + py * yz + pz * zz,
                        tint, mu[q * 8 + v * 2], mu[q * 8 + v * 2 + 1], ov, packedLight, nx, ny, nz);
            }
        }
    }
}
