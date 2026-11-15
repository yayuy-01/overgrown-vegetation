package com.yayuy.overgrownvegetation.client.infect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

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
 *   <li>まだ塗り替えていない絵が出てきたら、次のフレームでその 1 体の頂点の流れを覗き({@link QuadTap})、
 *   絵のどの画素が体のどこ(位置・面の向き)かを知る。箱や骨の名前は見ない。絵ごとなので、毛色違い・職業の服・毛も後から拾う。</li>
 * </ul>
 */
public final class InfectRender {

    /** RenderType → 絵(工場の差し込みが覚える)。覗いた頂点がどの絵の物かを引く。 */
    static final Map<RenderType, ResourceLocation> TYPE_TEXTURE = new IdentityHashMap<>();
    /** まだ塗り替えていない絵を使っていた個体(次のフレームで覗く)。 */
    private static final IntSet PENDING = new IntOpenHashSet();
    /** 覗いているフレームで、工場に入ってきた絵(頂点が 1 つも来なかった絵を数えるため)。 */
    private static final Set<ResourceLocation> REQUESTED = new HashSet<>();

    @Nullable
    private static GenericInfectionClient.State current;
    private static int currentId;
    @Nullable
    private static QuadTap tap;
    @Nullable
    private static InfectParts.AnchorTap anchorTap;
    @Nullable
    private static MultiBufferSource plainBuffers;
    /** この 1 体で最初に工場へ入った絵 = 体の絵(重ね着の層より先に描かれる)。部品はこの絵の面にだけ付ける。 */
    @Nullable
    private static ResourceLocation bodyTexture;
    /** 部品を描く遠さ(ブロック)。これより遠い個体は塗りだけ(頂点を覗かない)。【仮】 */
    private static final double PART_RANGE = 32.0D;
    /** 今描いている 1 体の、変わる動きの進み(0〜1。1 = 変わり終えた)。 */
    private static float currentProgress = 1.0F;
    private static boolean shaking;
    /**
     * RenderType を作り置きして使う描き方の種(エンダードラゴン: 絵が工場を通らないので差し替えが効かない)。
     * 1 回描いて工場に 1 度も絵が入らなかった種をここに覚え、次からは器の側で RenderType を作り直す({@link Remap})。
     */
    private static final Set<net.minecraft.world.entity.EntityType<?>> PREBUILT_TYPES = new HashSet<>();
    /** 作り置きの RenderType → 作り直し方(名前と絵が読めた物だけ。読めない物・エンティティ用でない物は empty)。 */
    private static final Map<RenderType, java.util.Optional<Remake>> REMAKES = new IdentityHashMap<>();
    private static final java.util.regex.Pattern TYPE_NAME = java.util.regex.Pattern.compile("^RenderType\\[([a-z_]+):");
    private static final java.util.regex.Pattern TYPE_TEXTURE_TEXT = java.util.regex.Pattern.compile("texture\\[Optional\\[([a-z0-9_.-]+:[a-z0-9_./-]+)\\]");

    private record Remake(java.util.function.Function<ResourceLocation, RenderType> factory, ResourceLocation texture) {
    }

    /** 作り置きの RenderType の名前と絵を文字から読み、同じ工場で作り直せる形にする(RenderType 1 つにつき 1 回)。 */
    private static java.util.Optional<Remake> remakeOf(RenderType type) {
        String text = type.toString();
        java.util.regex.Matcher name = TYPE_NAME.matcher(text), tex = TYPE_TEXTURE_TEXT.matcher(text);
        if (!name.find() || !tex.find()) {
            return java.util.Optional.empty();
        }
        java.util.function.Function<ResourceLocation, RenderType> factory = switch (name.group(1)) {
            case "entity_solid" -> RenderType::entitySolid;
            case "entity_cutout" -> RenderType::entityCutout;
            case "entity_cutout_no_cull" -> RenderType::entityCutoutNoCull;
            case "entity_cutout_no_cull_z_offset" -> RenderType::entityCutoutNoCullZOffset;
            case "entity_translucent_cull" -> RenderType::entityTranslucentCull;
            case "entity_translucent" -> RenderType::entityTranslucent;
            case "entity_smooth_cutout" -> RenderType::entitySmoothCutout;
            case "entity_decal" -> RenderType::entityDecal;
            case "entity_no_outline" -> RenderType::entityNoOutline;
            default -> null;
        };
        ResourceLocation texture = ResourceLocation.tryParse(tex.group(1));
        return factory == null || texture == null ? java.util.Optional.empty() : java.util.Optional.of(new Remake(factory, texture));
    }

    /** 器を包み、作り置きの RenderType を、今の個体のための物(絵を差し替えた物)に作り直してから下の器へ渡す。 */
    private static final class Remap implements MultiBufferSource {
        private final MultiBufferSource inner;

        Remap(MultiBufferSource inner) {
            this.inner = inner;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (current != null) {
                java.util.Optional<Remake> remake = REMAKES.computeIfAbsent(type, InfectRender::remakeOf);
                if (remake.isPresent()) {
                    // 工場を通す = 絵の差し替え・覚え・覗きの予約が、ほかの Mob と同じ道で働く
                    return this.inner.getBuffer(remake.get().factory().apply(remake.get().texture()));
                }
            }
            return this.inner.getBuffer(type);
        }
    }

    private InfectRender() {
    }

    /** 感染体なら、その 1 体を描く間だけ差し替えを有効にする。覗く番のフレームは器を包んで返す。 */
    public static MultiBufferSource begin(Entity entity, double camRelX, double camRelY, double camRelZ, float partialTick, PoseStack poseStack,
                                          MultiBufferSource buffers) {
        current = GenericInfectionClient.of(entity);
        tap = null;
        anchorTap = null;
        shaking = false;
        if (current == null) {
            return buffers;
        }
        currentProgress = current.progress(entity.level().getGameTime(), partialTick);
        if (currentProgress < 1.0F) {
            // 変わる動き: 立ち止まって震える(だんだん強く)→ 植物が突き出る瞬間にいちばん強く → 収まる
            float amp = shakeAmp(currentProgress) * Math.max(0.4F, Math.min(2.0F, entity.getBbWidth()));
            if (amp > 0.0F) {
                float t = (entity.level().getGameTime() % 1000L) + partialTick + entity.getId() * 0.37F;
                poseStack.pushPose();
                poseStack.translate(amp * Math.sin(t * 2.9F), amp * 0.35F * Math.sin(t * 4.3F), amp * Math.cos(t * 3.7F));
                shaking = true;
            }
        }
        plainBuffers = buffers;
        bodyTexture = null;
        currentId = entity.getId();
        if (PENDING.contains(currentId) && canCapture(entity, camRelX, camRelY, camRelZ)) {
            PENDING.remove(currentId);
            REQUESTED.clear();
            tap = new QuadTap(buffers, (float) camRelX, (float) camRelY, (float) camRelZ);
            return prebuilt(entity, tap);
        }
        if (InfectParts.enabled && camRelX * camRelX + camRelY * camRelY + camRelZ * camRelZ <= PART_RANGE * PART_RANGE) {
            anchorTap = new InfectParts.AnchorTap(buffers);
            return prebuilt(entity, anchorTap);
        }
        return prebuilt(entity, buffers);
    }

    private static MultiBufferSource prebuilt(Entity entity, MultiBufferSource use) {
        return PREBUILT_TYPES.contains(entity.getType()) ? new Remap(use) : use;
    }

    /** 死にかけ(倒れる回転)・透明・画面の中の見本(位置が 0)は、面の向きが当てにならないので覗かない。 */
    private static boolean canCapture(Entity entity, double x, double y, double z) {
        if (entity.isInvisible() || (x == 0.0D && y == 0.0D && z == 0.0D)) {
            return false;
        }
        return !(entity instanceof LivingEntity living) || living.deathTime <= 0;
    }

    /** 震えの幅(ブロック。体の幅 1 のとき)。【仮】 */
    private static float shakeAmp(float p) {
        float burst = InfectSkins.STAGE1_AT;
        if (p < burst) {
            return 0.008F + 0.03F * (p / burst);
        }
        if (p < burst + 0.1F) {
            return 0.06F;
        }
        return Math.max(0.0F, 0.06F * (1.0F - (p - burst - 0.1F) / 0.25F));
    }

    public static void end(Entity entity, PoseStack poseStack, int packedLight) {
        if (shaking) {
            shaking = false;
            poseStack.popPose();
        }
        QuadTap t = tap;
        tap = null;
        InfectParts.AnchorTap at = anchorTap;
        anchorTap = null;
        MultiBufferSource plain = plainBuffers;
        plainBuffers = null;
        boolean was = current != null;
        current = null;
        if (was && bodyTexture == null && !entity.isInvisible()) {
            PREBUILT_TYPES.add(entity.getType());
        }
        if (was && t != null) {
            InfectSkins.learn(entity, t, REQUESTED, bodyTexture);
            REQUESTED.clear();
        }
        if (was && at != null && plain != null) {
            // ここでは current はもう空 = 部品の絵は差し替えの外
            InfectParts.draw(at, plain, packedLight, currentProgress);
        }
    }

    /** 工場に入る絵を差し替える。感染体を描いていなければそのまま。 */
    public static ResourceLocation swap(ResourceLocation texture) {
        GenericInfectionClient.State state = current;
        if (state == null || InfectSkins.isSkin(texture)) {
            return texture;
        }
        if (bodyTexture == null) {
            bodyTexture = texture;
        }
        if (!InfectSkins.known(texture)) {
            PENDING.add(currentId);
            if (tap != null) {
                REQUESTED.add(texture);
            }
            return texture;
        }
        return InfectSkins.skinFor(texture, state, currentProgress);
    }

    public static void remember(RenderType type, ResourceLocation texture) {
        if (current != null) {
            TYPE_TEXTURE.put(type, texture);
        }
    }

    /** 資源の読み込み直し・確かめ用。 */
    public static void reset() {
        TYPE_TEXTURE.clear();
        REMAKES.clear();
        PartModels.clear();
        PENDING.clear();
        REQUESTED.clear();
        InfectSkins.clear();
    }

    /**
     * 覗いた四角 1 枚。{@code pos} = 4 頂点の位置(その個体の足元から。ブロック単位、x y z × 4)、{@code uv} = 4 頂点の絵の上の位置(0〜1、u v × 4)、
     * {@code ny} = 面の向きの上向き成分(世界)。
     */
    record Quad(float[] pos, float[] uv, float ny) {
    }

    /** 器を包み、通った頂点を RenderType ごとに四角として覚える(頂点はそのまま元の器へ流す)。 */
    static final class QuadTap implements MultiBufferSource {
        private final MultiBufferSource inner;
        private final float ox, oy, oz;
        final Map<RenderType, List<Quad>> quads = new IdentityHashMap<>();

        QuadTap(MultiBufferSource inner, float ox, float oy, float oz) {
            this.inner = inner;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer out = this.inner.getBuffer(type);
            if (!TYPE_TEXTURE.containsKey(type)) {
                return out;
            }
            return new Recorder(out, this.quads.computeIfAbsent(type, k -> new ArrayList<>()), this.ox, this.oy, this.oz);
        }
    }

    private static final class Recorder implements VertexConsumer {
        private final VertexConsumer out;
        private final List<Quad> list;
        private final float ox, oy, oz;
        private int n;
        private float[] pos = new float[12];
        private float[] uv = new float[8];
        private float ny;

        Recorder(VertexConsumer out, List<Quad> list, float ox, float oy, float oz) {
            this.out = out;
            this.list = list;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        private void begin(float x, float y, float z) {
            if (this.n == 4) {
                flush();
            }
            int i = this.n * 3;
            this.pos[i] = x - this.ox;
            this.pos[i + 1] = y - this.oy;
            this.pos[i + 2] = z - this.oz;
            this.n++;
        }

        private void uv(float u, float v) {
            if (this.n >= 1) {
                this.uv[(this.n - 1) * 2] = u;
                this.uv[(this.n - 1) * 2 + 1] = v;
            }
        }

        private void flush() {
            if (this.n == 4) {
                this.list.add(new Quad(this.pos, this.uv, this.ny));
                this.pos = new float[12];
                this.uv = new float[8];
            }
            this.n = 0;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            begin(x, y, z);
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
            begin(x, y, z);
            uv(u, v);
            this.ny = normalY;
            this.out.addVertex(x, y, z, color, u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
            if (this.n == 4) {
                flush();
            }
        }
    }
}
