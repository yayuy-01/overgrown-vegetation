package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteQuads;
import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteRasterizer;
import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteSheet;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 遠くの群れ(本物の Mob になる前の仮想の群れ)の身代わりを描く(2026-11-18 大群の軽量化 C。ユーザー決定「組み合わせを既定に、絵だけ・
 * モデルだけにもいつでも切り替えられるように」)。1 フレームぶんの一員を {@link #add} で受け取り、{@link #draw} で描く。
 *
 * <ul>
 *   <li>粗いモデル: 種ごとの形({@link CrowdMesh})と、{@link HordePoses} が 1 フレームに 1 回組む共有の姿勢を GPU に置き、1 体ぶんは位置・向き・
 *       大きさ・姿勢の番号・明るさ(32 バイト)だけ送って、まとめて 1 回で描く(シェーダー {@code crowd_shared})。</li>
 *   <li>絵の板: 8 方向の 2 次元の絵(parked の遠景の身代わりと同じ絵の作り方 {@link SpriteSheet})を、CPU で 4 頂点ずつ。</li>
 * </ul>
 * どちらで描くかは画面側の設定 {@code hordeLook}(組み合わせ = カメラから {@code hordeModelFarBlocks} までは粗いモデル、その先は絵)。
 * 粗いモデルが使えない時(大群をまとめて描くが切・シェーダーパック使用中・GeckoLib でない種)は絵にする。
 */
public final class HordeImpostors {

    private static final int POSE_UNIT = 5, INSTANCE_UNIT = 4;

    /** 種ごとの描き方と、このフレームの一員。 */
    private static final class Species {
        final EntityType<?> type;
        @Nullable Entity dummy;
        @Nullable GeoEntityRenderer<?> renderer;
        @Nullable BakedGeoModel baked;
        @Nullable ResourceLocation texture;
        float scale = 1.0F;
        @Nullable HordePoses poses;
        @Nullable SpriteSheet sheet;
        @Nullable RenderType spriteType;
        boolean broken;
        // このフレームの一員: x, y, z(カメラからの差), 向き, 姿勢の番号, 明るさ u, v
        float[] m = new float[8 * 256];
        int models;
        float[] s = new float[6 * 256];
        int sprites;

        Species(EntityType<?> type) {
            this.type = type;
        }
    }

    private static final Map<EntityType<?>, Species> SPECIES = new IdentityHashMap<>();
    private static int poseBuffer, poseTexture, instanceBuffer, instanceTexture;
    @Nullable private static FloatBuffer upload;
    private static Vec3 camera = Vec3.ZERO;
    private static double splitSq;
    private static boolean modelsOk;
    private static HordeLook look = HordeLook.COMBINED;

    // 確かめ用の数え
    static int lastModels, lastSprites, lastDraws;

    private HordeImpostors() {
    }

    /** このフレームの受け付けを始める(カメラの位置と、設定から決まる振り分け)。 */
    public static void begin(Vec3 cam) {
        camera = cam;
        for (Species sp : SPECIES.values()) {
            sp.models = 0;
            sp.sprites = 0;
        }
        look = HordeLook.COMBINED;
        double far = 128.0D;
        try {
            look = OvergrownClientConfig.CLIENT.hordeLook.get();
            far = OvergrownClientConfig.CLIENT.hordeModelFarBlocks.get();
        } catch (IllegalStateException ignored) {
            // 設定を読む前
        }
        splitSq = look == HordeLook.SPRITE ? -1.0D : look == HordeLook.MODEL ? Double.MAX_VALUE : far * far;
        modelsOk = CrowdDraw.on() && CrowdShader.shared() != null && !ShaderPacks.active();
    }

    /**
     * 一員を 1 体足す。位置はワールドの座標、{@code yaw} は Minecraft の向き(度)、{@code slot} は {@link HordePoses#slot}、{@code light} は明るさ
     * ({@code LightTexture.pack})。
     */
    public static void add(EntityType<?> type, double x, double y, double z, float yaw, int slot, int light) {
        Species sp = SPECIES.computeIfAbsent(type, Species::new);
        if (sp.broken) return;
        double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (modelsOk && d2 < splitSq) {
            int at = sp.models * 8;
            if (at + 8 > sp.m.length) sp.m = java.util.Arrays.copyOf(sp.m, sp.m.length * 2);
            sp.m[at] = (float) dx;
            sp.m[at + 1] = (float) dy;
            sp.m[at + 2] = (float) dz;
            sp.m[at + 3] = yaw;
            sp.m[at + 4] = Float.NaN;   // 大きさは描くときに種から
            sp.m[at + 5] = slot;
            sp.m[at + 6] = light & 0xFFFF;
            sp.m[at + 7] = light >> 16 & 0xFFFF;
            sp.models++;
        } else {
            int at = sp.sprites * 6;
            if (at + 6 > sp.s.length) sp.s = java.util.Arrays.copyOf(sp.s, sp.s.length * 2);
            sp.s[at] = (float) dx;
            sp.s[at + 1] = (float) dy;
            sp.s[at + 2] = (float) dz;
            sp.s[at + 3] = yaw;
            sp.s[at + 4] = Float.intBitsToFloat(light);
            sp.sprites++;
        }
    }

    /** 受け付けた一員を描く。AFTER_ENTITIES(まとめ描き {@link CrowdDraw} の後)で呼ぶ。 */
    public static void draw(Matrix4f modelView, Matrix4f projection, double ticks) {
        int models = 0, sprites = 0, draws = 0;
        for (Species sp : SPECIES.values()) {
            if (sp.models + sp.sprites == 0 || !prepare(sp)) continue;
            if (sp.models > 0) {
                if (sp.poses != null && drawModels(sp, modelView, projection, ticks)) {
                    models += sp.models;
                    draws++;
                } else {
                    // 粗いモデルで描けなかった分は絵へ回す
                    for (int i = 0; i < sp.models; i++) {
                        int at = i * 8;
                        int light = (int) sp.m[at + 6] | (int) sp.m[at + 7] << 16;
                        int s = sp.sprites * 6;
                        if (s + 6 > sp.s.length) sp.s = java.util.Arrays.copyOf(sp.s, sp.s.length * 2);
                        System.arraycopy(sp.m, at, sp.s, s, 4);
                        sp.s[s + 4] = Float.intBitsToFloat(light);
                        sp.sprites++;
                    }
                }
            }
            if (sp.sprites > 0 && drawSprites(sp)) {
                sprites += sp.sprites;
                draws++;
            }
        }
        lastModels = models;
        lastSprites = sprites;
        lastDraws = draws;
    }

    /** 種の描き方を用意する(描き手・形・絵)。GeckoLib でない種や絵の作れない種は以後描かない。 */
    private static boolean prepare(Species sp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        try {
            if (sp.dummy == null || sp.dummy.level() != mc.level) sp.dummy = sp.type.create(mc.level);
            if (sp.dummy == null) {
                sp.broken = true;
                return false;
            }
            EntityRenderer<?> er = mc.getEntityRenderDispatcher().getRenderer(sp.dummy);
            if (!((Object) er instanceof GeoEntityRenderer<?> geo)) {
                sp.broken = true;
                return false;
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            GeoModel model = geo.getGeoModel();
            @SuppressWarnings("unchecked")
            ResourceLocation modelRes = model.getModelResource((software.bernie.geckolib.animatable.GeoAnimatable) (Object) sp.dummy);
            BakedGeoModel baked = model.getBakedModel(modelRes);
            if (baked != sp.baked) {
                sp.baked = baked;
                @SuppressWarnings("unchecked")
                ResourceLocation tex = model.getTextureResource((software.bernie.geckolib.animatable.GeoAnimatable) (Object) sp.dummy);
                sp.texture = tex;
                Entity dummy = sp.dummy;
                @SuppressWarnings("unchecked")
                java.util.function.Function<String, software.bernie.geckolib.animation.Animation> anims =
                        name -> model.getAnimation((software.bernie.geckolib.animatable.GeoAnimatable) (Object) dummy, name);
                sp.poses = new HordePoses(baked, anims);
                sp.sheet = null;
                sp.spriteType = null;
            }
            sp.renderer = geo;
            sp.scale = geo instanceof CrowdGeoEntityRenderer<?> c ? c.crowdScale() : 1.0F;
            return true;
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("遠くの群れ: {} の描き方を用意できなかったので描かない", BuiltInRegistries.ENTITY_TYPE.getKey(sp.type), e);
            sp.broken = true;
            return false;
        }
    }

    // ------------------------------------------------------------------ 粗いモデル

    private static boolean drawModels(Species sp, Matrix4f modelView, Matrix4f projection, double ticks) {
        ShaderInstance shader = CrowdShader.shared();
        CrowdShader.SharedLocations loc = CrowdShader.sharedLocations();
        CrowdMesh mesh = sp.baked == null ? null : CrowdDraw.mesh(sp.baked);
        if (shader == null || loc == null || mesh == null || sp.poses == null || sp.texture == null) return false;
        HordePoses poses = sp.poses;
        if (poses.boneCount() != mesh.boneCount) return false;
        poses.update(ticks);
        if (poseBuffer == 0) {
            poseBuffer = GL15.glGenBuffers();
            poseTexture = GL11.glGenTextures();
            instanceBuffer = GL15.glGenBuffers();
            instanceTexture = GL11.glGenTextures();
        }
        int n = sp.models;
        for (int i = 0; i < n; i++) sp.m[i * 8 + 4] = sp.scale;
        FloatBuffer buf = buffer(Math.max(poses.data.length, n * 8));
        RenderType type = RenderType.entityCutoutNoCull(sp.texture);
        try {
            type.setupRenderState();
            shader.setDefaultUniforms(VertexFormat.Mode.TRIANGLES, modelView, projection, Minecraft.getInstance().getWindow());
            shader.apply();
            buf.clear();
            buf.put(poses.data).flip();
            bindBuffer(POSE_UNIT, poseBuffer, poseTexture, buf);
            buf.clear();
            buf.put(sp.m, 0, n * 8).flip();
            bindBuffer(INSTANCE_UNIT, instanceBuffer, instanceTexture, buf);
            GL20.glUniform1i(loc.poses(), POSE_UNIT);
            GL20.glUniform1i(loc.instances(), INSTANCE_UNIT);
            GL20.glUniform1i(loc.bones(), poses.boneCount());
            GL20.glUniform1i(loc.base(), 0);
            mesh.bind();
            GL31.glDrawElementsInstanced(GL11.GL_TRIANGLES, mesh.indexCount, GL11.GL_UNSIGNED_INT, 0L, n);
        } finally {
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + POSE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + INSTANCE_UNIT);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            shader.clear();
            type.clearRenderState();
            BufferUploader.invalidate();
        }
        return true;
    }

    private static void bindBuffer(int unit, int buffer, int texture, FloatBuffer data) {
        GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, data, GL15.GL_STREAM_DRAW);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
    }

    private static FloatBuffer buffer(int floats) {
        if (upload == null || upload.capacity() < floats) {
            if (upload != null) MemoryUtil.memFree(upload);
            upload = MemoryUtil.memAllocFloat(Math.max(floats, 4096) * 2);
        }
        return upload;
    }

    // ------------------------------------------------------------------ 絵の板

    private static boolean drawSprites(Species sp) {
        if (sp.sheet == null && !buildSheet(sp)) return false;
        SpriteSheet sheet = sp.sheet;
        MultiBufferSource.BufferSource src = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = src.getBuffer(sp.spriteType);
        float px = sp.scale / sheet.pixelsPerBlock();
        float left = -sheet.originX() * px, right = (sheet.frameWidth() - sheet.originX()) * px;
        float top = sheet.originY() * px, bottom = (sheet.originY() - sheet.frameHeight()) * px;
        int ov = OverlayTexture.NO_OVERLAY, c = 0xFFFFFFFF;
        for (int i = 0; i < sp.sprites; i++) {
            int at = i * 6;
            float bx = sp.s[at], by = sp.s[at + 1], bz = sp.s[at + 2];
            double h = Math.sqrt(bx * bx + bz * bz);
            if (h < 1.0E-4D) continue;
            float rx = (float) (-bz / h), rz = (float) (bx / h);
            int view = SpriteSheet.viewIndex(SpriteSheet.relativeAngle(sp.s[at + 3], -bx, -bz));
            float u0 = view / (float) SpriteSheet.VIEWS, u1 = (view + 1) / (float) SpriteSheet.VIEWS;
            int light = Float.floatToRawIntBits(sp.s[at + 4]);
            // 法線は上向き(絵に陰影を焼いてあるので、Mob の 2 灯の陰影を掛け直さない。parked の遠景の身代わりと同じ)
            vc.addVertex(bx + rx * left, by + bottom, bz + rz * left, c, u0, 1.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.addVertex(bx + rx * right, by + bottom, bz + rz * right, c, u1, 1.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.addVertex(bx + rx * right, by + top, bz + rz * right, c, u1, 0.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.addVertex(bx + rx * left, by + top, bz + rz * left, c, u0, 0.0F, ov, light, 0.0F, 1.0F, 0.0F);
        }
        src.endBatch(sp.spriteType);
        return true;
    }

    /** その種の 8 方向の絵を、レストポーズの形とテクスチャから CPU で描いて GPU へ上げる(種ごとに 1 回)。 */
    private static boolean buildSheet(Species sp) {
        Minecraft mc = Minecraft.getInstance();
        if (sp.baked == null || sp.texture == null) return false;
        try (NativeImage img = NativeImage.read(mc.getResourceManager().getResource(sp.texture).orElseThrow().open())) {
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = img.getPixelRGBA(x, y);
                    argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr >> 16) & 0xFF) | ((abgr & 0xFF) << 16);
                }
            }
            sp.sheet = SpriteSheet.build(SpriteQuads.of(sp.baked), new SpriteRasterizer.Texture(w, h, argb), 1.0F);
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("遠くの群れ: {} の絵を作れなかったので描かない", sp.texture, e);
            sp.broken = true;
            return false;
        }
        if (sp.sheet == null) {
            sp.broken = true;
            return false;
        }
        ResourceLocation id = OvergrownVegetation.id("horde_sprite/" + BuiltInRegistries.ENTITY_TYPE.getKey(sp.type).getPath());
        mc.getTextureManager().register(id, new SheetTexture(sp.sheet));
        sp.spriteType = RenderType.create("overgrown_vegetation_horde_sprite", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                true, false, RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(id, true, true))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .createCompositeState(true));
        return true;
    }

    /** 資源の読み直しのとき: 形・絵を作り直させる。 */
    static void clear() {
        SPECIES.clear();
    }

    /** devbridge の crowd status 用。 */
    public static String status() {
        return "遠くの群れ " + look + "・直前のフレーム 粗いモデル " + lastModels + " 体・絵 " + lastSprites + " 体・描画 " + lastDraws + " 回";
    }

    /** {@link SpriteSheet} を GPU へ上げたテクスチャ(ミップマップの段ごとに上げる。parked の HordeSpriteTexture と同じ)。 */
    private static final class SheetTexture extends AbstractTexture {
        SheetTexture(SpriteSheet s) {
            RenderSystem.assertOnRenderThreadOrInit();
            TextureUtil.prepareImage(this.getId(), SpriteSheet.MIP_LEVELS, s.width(), s.height());
            for (int level = 0; level <= SpriteSheet.MIP_LEVELS; level++) {
                int w = s.width() >> level, h = s.height() >> level;
                int[] argb = s.levels()[level];
                try (NativeImage image = new NativeImage(w, h, false)) {
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int c = argb[y * w + x];
                            image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
                        }
                    }
                    this.bind();
                    image.upload(level, 0, 0, false);
                }
            }
        }

        @Override
        public void load(ResourceManager manager) {
        }
    }
}
