package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汎用の感染体の描画の束ね役(描画スレッドだけ)。
 * <ul>
 *   <li>{@link #begin}〜{@link #end}: 今描いているエンティティが感染体かどうか(EntityRenderDispatcher の差し込みが呼ぶ)。</li>
 *   <li>{@link #swap}: 感染体を描いている間、エンティティ用の RenderType の絵を、塗り替えた絵に差し替える
 *   (RenderType の工場の差し込みが呼ぶ。バニラ・GeckoLib・ほかの MOD の描き方のどれも、絵はここを通る)。</li>
 *   <li>最初の 1 回は頂点の流れを覗き({@link QuadTap})、絵のどの画素がどの向きの面かを知る。箱や骨の名前は見ない。</li>
 * </ul>
 */
public final class InfectRender {

    /** RenderType → 絵(工場の差し込みが覚える)。覗いた頂点がどの絵の物かを引く。 */
    static final Map<RenderType, ResourceLocation> TYPE_TEXTURE = new IdentityHashMap<>();
    private static final Set<EntityType<?>> CAPTURED = new HashSet<>();

    @Nullable
    private static GenericInfectionClient.State current;
    @Nullable
    private static QuadTap tap;
    private static double baseY;

    private InfectRender() {
    }

    /** 感染体なら、その 1 体を描く間だけ差し替えを有効にする。最初の 1 回は器を覗き用に包んで返す。 */
    public static MultiBufferSource begin(Entity entity, double camRelY, MultiBufferSource buffers) {
        current = GenericInfectionClient.of(entity);
        tap = null;
        if (current == null) {
            return buffers;
        }
        if (CAPTURED.add(entity.getType())) {
            baseY = camRelY;
            tap = new QuadTap(buffers);
            return tap;
        }
        return buffers;
    }

    public static void end(Entity entity) {
        QuadTap t = tap;
        tap = null;
        boolean was = current != null;
        current = null;
        if (was && t != null) {
            InfectSkins.learn(entity, t, baseY);
        }
    }

    /** 工場に入る絵を差し替える。感染体を描いていなければそのまま。 */
    public static ResourceLocation swap(ResourceLocation texture) {
        if (current == null) {
            return texture;
        }
        return InfectSkins.skinFor(texture, current);
    }

    public static void remember(RenderType type, ResourceLocation texture) {
        if (current != null) {
            TYPE_TEXTURE.put(type, texture);
        }
    }

    /** 資源の読み込み直し・確かめ用。 */
    public static void reset() {
        CAPTURED.clear();
        TYPE_TEXTURE.clear();
        InfectSkins.clear();
    }

    /** 覗いた四角 1 枚: 絵の上の範囲(0〜1)と、面の向き(世界の上向き成分)と、足元からの高さ。 */
    record Quad(float u0, float v0, float u1, float v1, float ny, float y) {
    }

    /** 器を包み、通った頂点を RenderType ごとに四角として覚える(頂点はそのまま元の器へ流す)。 */
    static final class QuadTap implements MultiBufferSource {
        private final MultiBufferSource inner;
        final Map<RenderType, List<Quad>> quads = new IdentityHashMap<>();

        QuadTap(MultiBufferSource inner) {
            this.inner = inner;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer out = this.inner.getBuffer(type);
            if (!TYPE_TEXTURE.containsKey(type)) {
                return out;
            }
            return new Recorder(out, this.quads.computeIfAbsent(type, k -> new ArrayList<>()));
        }
    }

    private static final class Recorder implements VertexConsumer {
        private final VertexConsumer out;
        private final List<Quad> list;
        private int n;
        private float u0, v0, u1, v1, ny, y;

        Recorder(VertexConsumer out, List<Quad> list) {
            this.out = out;
            this.list = list;
        }

        private void begin(float py) {
            if (this.n == 4) {
                flush();
            }
            if (this.n == 0) {
                this.u0 = this.v0 = Float.POSITIVE_INFINITY;
                this.u1 = this.v1 = Float.NEGATIVE_INFINITY;
                this.ny = 0.0F;
                this.y = 0.0F;
            }
            this.n++;
            this.y += py / 4.0F;
        }

        private void uv(float u, float v) {
            this.u0 = Math.min(this.u0, u);
            this.v0 = Math.min(this.v0, v);
            this.u1 = Math.max(this.u1, u);
            this.v1 = Math.max(this.v1, v);
        }

        private void flush() {
            if (this.n == 4 && this.u1 > this.u0 && this.v1 > this.v0) {
                this.list.add(new Quad(this.u0, this.v0, this.u1, this.v1, this.ny, this.y));
            }
            this.n = 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            begin(y);
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
            uv(u, v);
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
            this.ny = ny;
            this.out.setNormal(nx, ny, nz);
            if (this.n == 4) {
                flush();
            }
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay, int packedLight,
                              float normalX, float normalY, float normalZ) {
            begin(y);
            uv(u, v);
            this.ny = normalY;
            this.out.addVertex(x, y, z, color, u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
            if (this.n == 4) {
                flush();
            }
        }
    }
}
