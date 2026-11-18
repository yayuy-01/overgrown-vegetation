package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;
import software.bernie.geckolib.cache.object.BakedGeoModel;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大群のまとめ描き = GPU スキニング + インスタンス描画(2026-11-18 大群の軽量化 C。ユーザーの名前は「GPU スキニング」)。
 *
 * <h2>何が軽くなるか</h2>
 * <p>GeckoLib はふだん 1 体ずつ、CPU で全部の頂点に骨の行列を掛けて(グレガリア 1 体 約 900 頂点)GPU へ送る。
 * 1024 体で 1 フレーム 33 MB になり、頂点の計算 約 30 µs/体・送り込み 約 10 µs/体がかかっていた(共有ノート horde-gpu-list.md)。
 * ここでは形({@link CrowdMesh})を種ごとに 1 回だけ GPU に置き、1 体ぶんは骨の行列(グレガリアなら 36 本 × 96 バイト)だけを集めて、
 * Mob の段の終わり({@code AFTER_ENTITIES})に同じ種・同じ絵をまとめて 1 回で描く。アニメの計算はいつも通り CPU。</p>
 *
 * <h2>見た目</h2>
 * <p>光・霧・傷の赤み・明るさはバニラの {@code rendertype_entity_cutout_no_cull} と同じ式(シェーダー {@code crowd_entity})。
 * 当てはまらない個体はいつもの描き方のまま({@link CrowdGeoEntityRenderer#crowdEligible})。</p>
 *
 * <h2>集める間</h2>
 * <p>{@code AFTER_CUTOUT_BLOCKS} から {@code AFTER_ENTITIES} の間だけ集める(ワールドの Mob の段。持ち物の画面などで描く Mob は集めない)。
 * 片方の段が来ない描き方(ほかの MOD が段を飛ばす)では集めないので、いつもの描き方になる。</p>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class CrowdDraw {

    /** 確かめ用・設定の切り替え(devbridge の {@code crowd gpu off|on})。 */
    public static volatile boolean enabled = true;

    /** 骨の行列の器に使う texture unit(自前の samplerBuffer)。バニラは 0〜2、この MOD の粒は 6・7 を使う。 */
    private static final int BONE_UNIT = 5;

    private static boolean capturing;
    private static final Map<BakedGeoModel, CrowdMesh> MESHES = new IdentityHashMap<>();
    private static final Map<CrowdMesh, Map<RenderType, Batch>> BATCHES = new IdentityHashMap<>();
    private static final List<Batch> ACTIVE = new ArrayList<>();

    private static int boneBuffer;
    private static int boneTexture;
    private static int maxTexels = -1;

    // 確かめ用の数え(devbridge の crowd status)
    static int lastInstances;
    static int lastDraws;
    static int lastMeshes;

    private CrowdDraw() {
    }

    /** 今、集めてよいか(ワールドの Mob の段の中・シェーダーがある・シェーダーパックを使っていない)。 */
    static boolean capturing() {
        return capturing;
    }

    /** その形・その描き方の溜め場所。形を作れないときは null(いつもの描き方へ)。 */
    @Nullable
    static Batch batch(BakedGeoModel model, RenderType type) {
        CrowdMesh mesh = MESHES.get(model);
        if (mesh == null) {
            CrowdShader.Locations loc = CrowdShader.locations();
            if (loc == null || loc.boneInfo() < 0) return null;
            if (MESHES.size() > 64) clearMeshes();   // 資源の読み直しで古い形が残らないように(念のため)
            mesh = CrowdMesh.build(model, loc);
            MESHES.put(model, mesh);
        }
        Map<RenderType, Batch> byType = BATCHES.computeIfAbsent(mesh, m -> new IdentityHashMap<>());
        Batch b = byType.get(type);
        if (b == null) {
            b = new Batch(mesh, type);
            byType.put(type, b);
        }
        return b;
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            for (Batch b : ACTIVE) b.reset();   // 前のフレームで描けなかった分は捨てる
            ACTIVE.clear();
            capturing = enabled && CrowdShader.shader() != null && !ShaderPacks.active();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            capturing = false;
            drawAll(event.getModelViewMatrix(), event.getProjectionMatrix());
        }
    }

    private static void drawAll(Matrix4f modelView, Matrix4f projection) {
        int instances = 0, draws = 0;
        if (ACTIVE.isEmpty()) {
            lastInstances = 0;
            lastDraws = 0;
            lastMeshes = MESHES.size();
            return;
        }
        ShaderInstance shader = CrowdShader.shader();
        CrowdShader.Locations loc = CrowdShader.locations();
        if (shader == null || loc == null) {
            for (Batch b : ACTIVE) b.reset();
            ACTIVE.clear();
            return;
        }
        if (boneBuffer == 0) {
            boneBuffer = GL15.glGenBuffers();
            boneTexture = GL11.glGenTextures();
            maxTexels = GL11.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        }
        try {
            for (Batch b : ACTIVE) {
                if (b.count == 0) continue;
                b.type.setupRenderState();
                shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
                shader.apply();
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + BONE_UNIT);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, boneTexture);
                GL20.glUniform1i(loc.bones(), BONE_UNIT);
                GL20.glUniform1i(loc.stride(), b.stride);
                b.mesh.bind();
                // 1 回に送れる texel の数に収まるだけずつ(4060 は 1 億 3 千万。小さい環境でも分けて描く)
                int perChunk = Math.max(1, Math.min(b.count, maxTexels / b.stride));
                for (int start = 0; start < b.count; start += perChunk) {
                    int n = Math.min(perChunk, b.count - start);
                    FloatBuffer slice = MemoryUtil.memSlice(b.data, start * b.stride * 4, n * b.stride * 4);
                    GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, boneBuffer);
                    GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, slice, GL15.GL_STREAM_DRAW);
                    GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, boneBuffer);
                    GL20.glUniform1i(loc.base(), 0);
                    GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, b.mesh.indexCount, GL11.GL_UNSIGNED_INT, 0L, n);
                    draws++;
                }
                instances += b.count;
                GL30.glBindVertexArray(0);
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                shader.clear();
                b.type.clearRenderState();
                b.reset();
            }
        } finally {
            BufferUploader.invalidate();   // バニラが次に自分の器を結び直すように
            ACTIVE.clear();
        }
        lastInstances = instances;
        lastDraws = draws;
        lastMeshes = MESHES.size();
    }

    static void onShaderReloaded() {
        clearMeshes();
    }

    private static void clearMeshes() {
        for (Batch b : ACTIVE) b.reset();
        ACTIVE.clear();
        for (CrowdMesh mesh : MESHES.values()) mesh.delete();
        MESHES.clear();
        for (Map<RenderType, Batch> m : BATCHES.values()) for (Batch b : m.values()) b.free();
        BATCHES.clear();
    }

    /** devbridge の {@code crowd status} の返事。 */
    public static String status() {
        return "まとめ描き " + (enabled ? "入" : "切") + "・シェーダー " + (CrowdShader.shader() != null ? "あり" : "なし")
                + "・アニメ 1 tick 1 回 " + (TickRatePose.enabled ? "入" : "切") + "・直前のフレーム " + lastInstances + " 体 / 描画 " + lastDraws + " 回・形 " + lastMeshes;
    }

    /** 同じ形・同じ描き方(= 同じ絵)の 1 フレームぶんの溜め場所。1 体 = 頭 2 texel + 骨ごとに 6 texel(RGBA32F)。 */
    static final class Batch {

        final CrowdMesh mesh;
        final RenderType type;
        final int stride;
        FloatBuffer data;
        int count;
        /** 書きかけの 1 体の頭の位置(float)。 */
        private int writing = -1;
        private int nextBone;
        private final Matrix4f zero = new Matrix4f().zero();
        private final Matrix3f zero3 = new Matrix3f().zero();

        Batch(CrowdMesh mesh, RenderType type) {
            this.mesh = mesh;
            this.type = type;
            this.stride = 2 + 6 * mesh.boneCount;
            this.data = MemoryUtil.memAllocFloat(this.stride * 4 * 64);
        }

        /** 1 体を書き始める。骨は {@link #bone} で描く順に全部書き、{@link #commit} で数に入れる。 */
        void begin(int packedLight, int packedOverlay, int colour) {
            int need = (this.count + 1) * this.stride * 4;
            if (need > this.data.capacity()) {
                this.data = MemoryUtil.memRealloc(this.data, Math.max(need, this.data.capacity() * 2));
            }
            int at = this.count * this.stride * 4;
            this.writing = at;
            this.nextBone = 0;
            this.data.put(at, packedLight & 0xFFFF).put(at + 1, packedLight >> 16 & 0xFFFF)
                    .put(at + 2, packedOverlay & 0xFFFF).put(at + 3, packedOverlay >> 16 & 0xFFFF)
                    .put(at + 4, (colour >> 16 & 0xFF) / 255.0F).put(at + 5, (colour >> 8 & 0xFF) / 255.0F)
                    .put(at + 6, (colour & 0xFF) / 255.0F).put(at + 7, (colour >>> 24) / 255.0F);
        }

        /** 次の骨(描く順)の行列。見えない骨は {@code visible = false}(箱が点に潰れる)。 */
        void bone(PoseStack.Pose pose, boolean visible) {
            if (this.writing < 0 || this.nextBone >= this.mesh.boneCount) {
                this.nextBone++;
                return;
            }
            Matrix4f m = visible ? pose.pose() : this.zero;
            Matrix3f nm = visible ? pose.normal() : this.zero3;
            int at = this.writing + (2 + this.nextBone * 6) * 4;
            FloatBuffer d = this.data;
            d.put(at, m.m00()).put(at + 1, m.m10()).put(at + 2, m.m20()).put(at + 3, m.m30())
                    .put(at + 4, m.m01()).put(at + 5, m.m11()).put(at + 6, m.m21()).put(at + 7, m.m31())
                    .put(at + 8, m.m02()).put(at + 9, m.m12()).put(at + 10, m.m22()).put(at + 11, m.m32())
                    .put(at + 12, nm.m00()).put(at + 13, nm.m01()).put(at + 14, nm.m02()).put(at + 15, 0.0F)
                    .put(at + 16, nm.m10()).put(at + 17, nm.m11()).put(at + 18, nm.m12()).put(at + 19, 0.0F)
                    .put(at + 20, nm.m20()).put(at + 21, nm.m21()).put(at + 22, nm.m22()).put(at + 23, 0.0F);
            this.nextBone++;
        }

        /** 骨 {@code from} から {@code count} 本を見えない骨として書く(子を隠す骨の下)。 */
        void hideBones(int count) {
            for (int i = 0; i < count; i++) bone(null, false);
        }

        /** 次に書く骨の番号(描く順)。 */
        int nextBone() {
            return this.nextBone;
        }

        /** 書き終えた 1 体を数に入れる。骨の数が形と合わなければ捨てて false。 */
        boolean commit() {
            boolean ok = this.writing >= 0 && this.nextBone == this.mesh.boneCount;
            if (ok) {
                if (this.count == 0) ACTIVE.add(this);
                this.count++;
            }
            this.writing = -1;
            return ok;
        }

        void reset() {
            this.count = 0;
            this.writing = -1;
        }

        void free() {
            if (this.data != null) MemoryUtil.memFree(this.data);
            this.data = null;
            this.count = 0;
        }
    }
}
