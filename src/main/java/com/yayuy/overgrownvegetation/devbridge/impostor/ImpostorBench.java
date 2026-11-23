package com.yayuy.overgrownvegetation.devbridge.impostor;

import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteQuads;
import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteRasterizer;
import com.yayuy.overgrownvegetation.client.crowd.sprite.SpriteSheet;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.crowd.CrowdDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 遠景の身代わりの重さ比べ(2026-11-18 大群の軽量化 C の (d)。ユーザー「絵と粗いモデルでの負荷の違いを見てから決めよう」)。
 * 本物のエンティティを作らず、カメラの前の {@code near}〜{@code far} ブロックに偽の Mob を N 体並べ、同じ場面を次の 2 通りで描く:
 * <ul>
 *   <li>{@code sprite}: 前の遠景の身代わり(parked/client/horde)と同じ 8 方向の 2 次元の絵の板(CPU で 4 頂点ずつ)。</li>
 *   <li>{@code model}: 粗いモデル = まとめ描き({@link CrowdDraw})。動きは全員で 1 つの姿勢を分け合う(1 フレームに 1 回だけ骨を組む)。
 *       1 体ぶんは向き・位置の行列を骨の数だけ掛けて送る。</li>
 * </ul>
 * 明るさは両方ともその場所のブロックの明るさ。devbridge の {@code impostor <sprite|model|off> <種> <数> <近> <遠>}。
 */
public final class ImpostorBench {

    private enum Mode { OFF, SPRITE, MODEL, COMBO, FAST }

    private static Mode mode = Mode.OFF;
    private static EntityType<?> type;
    private static float[] xs = new float[0], ys = new float[0], zs = new float[0], yaws = new float[0];
    private static int[] lights = new int[0];
    private static int count;
    private static boolean registered;
    private static net.minecraft.world.entity.Entity dummy;

    private static SpriteSheet sheet;
    private static ResourceLocation sheetId;
    private static RenderType spriteType;
    private static EntityType<?> sheetFor;

    private ImpostorBench() {
    }

    public static String command(String arg, Consumer<String> log) {
        String[] p = arg.trim().split("\\s+");
        Minecraft mc = Minecraft.getInstance();
        if (p.length == 0 || p[0].isEmpty() || p[0].equalsIgnoreCase("off")) {
            mode = Mode.OFF;
            return "IMPOSTOR 切";
        }
        if (p.length < 5 || mc.level == null || mc.player == null) return "IMPOSTOR 使い方: impostor <sprite|model|off> <種> <数> <近> <遠>";
        ResourceLocation id = p[1].contains(":") ? ResourceLocation.parse(p[1]) : OvergrownVegetation.id(p[1]);
        type = BuiltInRegistries.ENTITY_TYPE.get(id);
        int n = Integer.parseInt(p[2]);
        float near = Float.parseFloat(p[3]), far = Float.parseFloat(p[4]);
        mode = p[0].equalsIgnoreCase("sprite") ? Mode.SPRITE : p[0].equalsIgnoreCase("combo") ? Mode.COMBO : p[0].equalsIgnoreCase("fast") ? Mode.FAST : Mode.MODEL;
        place(mc.level, mc.player.position(), mc.player.getYRot(), n, near, far);
        if (!registered) {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, ImpostorBench::onStage);
            registered = true;
        }
        return "IMPOSTOR " + mode + " " + id + " " + count + " 体 " + near + "〜" + far;
    }

    /** カメラの向きの ±50° の扇に、近〜遠の距離で一様に置く(同じ種なら毎回同じ場所)。 */
    private static void place(ClientLevel level, Vec3 at, float yawDeg, int n, float near, float far) {
        xs = new float[n];
        ys = new float[n];
        zs = new float[n];
        yaws = new float[n];
        lights = new int[n];
        RandomSource r = RandomSource.create(1118L);
        double face = Math.toRadians(yawDeg);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            double d = Math.sqrt(near * near + r.nextDouble() * (far * far - near * near));
            double a = face + (r.nextDouble() * 2.0D - 1.0D) * Math.toRadians(50.0D);
            double x = at.x - Math.sin(a) * d, z = at.z + Math.cos(a) * d;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            int y = level.getChunkSource().hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) : 64;
            xs[i] = (float) x;
            ys[i] = y;
            zs[i] = (float) z;
            // カメラ(置いた時のプレイヤー)の方を向いて迫ってくる向き ± 25°
            yaws[i] = (float) Math.toDegrees(Math.atan2(-(at.x - x), at.z - z)) + (r.nextFloat() * 2.0F - 1.0F) * 25.0F;
            pos.set(bx, y, bz);
            lights[i] = LevelRenderer.getLightColor(level, pos);
        }
        count = n;
    }

    private static void onStage(RenderLevelStageEvent event) {
        if (mode == Mode.OFF || count == 0) return;
        if (mode == Mode.FAST) {
            // 本番の描き方(client/crowd/HordeImpostors: 共有の姿勢の粗いモデル + 絵の板。振り分けは画面側の設定 hordeLook)
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
                Vec3 c = event.getCamera().getPosition();
                com.yayuy.overgrownvegetation.client.crowd.HordeImpostors.begin(c);
                for (int i = 0; i < count; i++) {
                    com.yayuy.overgrownvegetation.client.crowd.HordeImpostors.add(type, xs[i], ys[i], zs[i], yaws[i],
                            com.yayuy.overgrownvegetation.client.crowd.HordePoses.slot(true, 1118L, i), lights[i]);
                }
                Minecraft m = Minecraft.getInstance();
                com.yayuy.overgrownvegetation.client.crowd.HordeImpostors.draw(com.yayuy.overgrownvegetation.client.shader.PortRender.modelView(event), event.getProjectionMatrix(),
                        m.level == null ? 0.0D : m.level.getGameTime() + event.getPartialTick());
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (dummy == null || dummy.getType() != type) dummy = type.create(mc.level);
        if (dummy == null) return;
        EntityRenderer<?> er = mc.getEntityRenderDispatcher().getRenderer(dummy);
        if (!((Object) er instanceof com.yayuy.overgrownvegetation.client.crowd.CrowdGeoEntityRenderer<?> geo)) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        GeoModel model = geo.getGeoModel();
        ResourceLocation modelRes = model.getModelResource(null);
        ResourceLocation tex = model.getTextureResource(null);
        BakedGeoModel baked = model.getBakedModel(modelRes);
        Vec3 cam = event.getCamera().getPosition();
        float scale = geo.crowdScale();
        // 組み合わせ: カメラから hordeModelFarBlocks(画面側の設定【仮】128)より近い身代わりは粗いモデル、遠いのは絵の板
        double split = mode == Mode.COMBO ? farBlocks() : mode == Mode.MODEL ? Double.MAX_VALUE : -1.0D;
        if (mode != Mode.SPRITE && event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            submitModels(baked, RenderType.entityCutoutNoCull(tex), cam, scale, split, com.yayuy.overgrownvegetation.client.shader.PortRender.modelView(event));
        } else if (mode != Mode.MODEL && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // 1.20.1: この段の RenderSystem の視点行列は単位行列(1.21 はカメラの回転)。1.21 と同じにして描く
            boolean pushed = com.yayuy.overgrownvegetation.client.shader.PortRender.pushStageModelView(event);
            drawSprites(baked, tex, cam, scale, split);
            com.yayuy.overgrownvegetation.client.shader.PortRender.popStageModelView(pushed);
        }
    }

    // ------------------------------------------------------------------ 粗いモデル(まとめ描き)

    private static final List<Matrix4f> BONE_POSE = new ArrayList<>();
    private static final List<Matrix3f> BONE_NORMAL = new ArrayList<>();
    private static final List<Boolean> BONE_VISIBLE = new ArrayList<>();

    private static double farBlocks() {
        try {
            return com.yayuy.overgrownvegetation.config.OvergrownClientConfig.CLIENT.hordeModelFarBlocks.get();
        } catch (IllegalStateException e) {
            return 128.0D;
        }
    }

    private static boolean nearer(int i, Vec3 cam, double split) {
        double dx = xs[i] - cam.x, dy = ys[i] - cam.y, dz = zs[i] - cam.z;
        return dx * dx + dy * dy + dz * dz < split * split;
    }

    private static void submitModels(BakedGeoModel baked, RenderType rt, Vec3 cam, float scale, double split, Matrix4f view) {
        if (!CrowdDraw.capturing()) return;
        CrowdDraw.Batch batch = CrowdDraw.batch(baked, rt);
        if (batch == null) return;
        // 全員で分け合う姿勢を 1 回だけ組む(骨の今の値 = 近くの本物が最後に描かれた姿勢。GeckoLib の描き方と同じ積み方)
        BONE_POSE.clear();
        BONE_NORMAL.clear();
        BONE_VISIBLE.clear();
        PoseStack ps = new PoseStack();
        for (GeoBone b : baked.topLevelBones()) walk(ps, b, true);
        PoseStack inst = new PoseStack();
        PoseStack out = new PoseStack();
        for (int i = 0; i < count; i++) {
            if (!nearer(i, cam, split)) continue;
            inst.setIdentity();
            // 1.20.1: まとめ描きの骨の行列にはカメラの回転を入れる(バニラの Mob の PoseStack と同じ。client/crowd/CrowdDraw)
            inst.last().pose().set(view);
            inst.last().normal().set(new Matrix3f(view));
            inst.translate(xs[i] - cam.x, ys[i] - cam.y, zs[i] - cam.z);
            inst.scale(scale, scale, scale);
            inst.mulPose(Axis.YP.rotationDegrees(180.0F - yaws[i]));
            inst.translate(0.0F, 0.01F, 0.0F);
            Matrix4f m = inst.last().pose();
            Matrix3f nm = inst.last().normal();
            batch.begin(lights[i], OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
            for (int b = 0; b < BONE_POSE.size(); b++) {
                out.last().pose().set(m).mul(BONE_POSE.get(b));
                out.last().normal().set(nm).mul(BONE_NORMAL.get(b));
                batch.bone(out.last(), BONE_VISIBLE.get(b));
            }
            batch.commit();
        }
    }

    private static void walk(PoseStack ps, GeoBone bone, boolean parentShown) {
        ps.pushPose();
        RenderUtils.translateMatrixToBone(ps, bone);
        RenderUtils.translateToPivotPoint(ps, bone);
        RenderUtils.rotateMatrixAroundBone(ps, bone);
        RenderUtils.scaleMatrixForBone(ps, bone);
        RenderUtils.translateAwayFromPivotPoint(ps, bone);
        BONE_POSE.add(new Matrix4f(ps.last().pose()));
        BONE_NORMAL.add(new Matrix3f(ps.last().normal()));
        BONE_VISIBLE.add(parentShown && !bone.isHidden());
        for (GeoBone c : bone.getChildBones()) walk(ps, c, parentShown && !bone.isHidingChildren());
        ps.popPose();
    }

    // ------------------------------------------------------------------ 2 次元の絵

    private static void drawSprites(BakedGeoModel baked, ResourceLocation tex, Vec3 cam, float scale, double split) {
        Minecraft mc = Minecraft.getInstance();
        if (sheet == null || sheetFor != type) buildSheet(baked, tex);
        if (sheet == null) return;
        MultiBufferSource.BufferSource src = mc.renderBuffers().bufferSource();
        VertexConsumer vc = src.getBuffer(spriteType);
        float px = scale / sheet.pixelsPerBlock();
        float left = -sheet.originX() * px, right = (sheet.frameWidth() - sheet.originX()) * px;
        float top = sheet.originY() * px, bottom = (sheet.originY() - sheet.frameHeight()) * px;
        for (int i = 0; i < count; i++) {
            if (split > 0.0D && nearer(i, cam, split)) continue;
            double dx = xs[i] - cam.x, dz = zs[i] - cam.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            if (h < 1.0E-4D) continue;
            float rx = (float) (-dz / h), rz = (float) (dx / h);
            int view = SpriteSheet.viewIndex(SpriteSheet.relativeAngle(yaws[i], -dx, -dz));
            float u0 = view / (float) SpriteSheet.VIEWS, u1 = (view + 1) / (float) SpriteSheet.VIEWS;
            float bx = (float) dx, by = (float) (ys[i] - cam.y), bz = (float) dz;
            int light = lights[i], ov = OverlayTexture.NO_OVERLAY;
            vc.vertex(bx + rx * left, by + bottom, bz + rz * left, 1.0F, 1.0F, 1.0F, 1.0F, u0, 1.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.vertex(bx + rx * right, by + bottom, bz + rz * right, 1.0F, 1.0F, 1.0F, 1.0F, u1, 1.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.vertex(bx + rx * right, by + top, bz + rz * right, 1.0F, 1.0F, 1.0F, 1.0F, u1, 0.0F, ov, light, 0.0F, 1.0F, 0.0F);
            vc.vertex(bx + rx * left, by + top, bz + rz * left, 1.0F, 1.0F, 1.0F, 1.0F, u0, 0.0F, ov, light, 0.0F, 1.0F, 0.0F);
        }
        src.endBatch(spriteType);
    }

    private static void buildSheet(BakedGeoModel baked, ResourceLocation tex) {
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage img = NativeImage.read(mc.getResourceManager().getResource(tex).orElseThrow().open())) {
            int w = img.getWidth(), h = img.getHeight();
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = img.getPixelRGBA(x, y);
                    argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr >> 16) & 0xFF) | ((abgr & 0xFF) << 16);
                }
            }
            sheet = SpriteSheet.build(SpriteQuads.of(baked), new SpriteRasterizer.Texture(w, h, argb), 1.0F);
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("IMPOSTOR 絵を作れなかった", e);
            sheet = null;
            return;
        }
        sheetFor = type;
        sheetId = OvergrownVegetation.id("devbridge_impostor_" + BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
        mc.getTextureManager().register(sheetId, new SheetTexture(sheet));
        spriteType = RenderType.create("overgrown_vegetation_impostor_sprite", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                true, false, RenderType.CompositeState.builder()
                        .setShaderState(com.yayuy.overgrownvegetation.client.PortRenderStates.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(sheetId, true, true))
                        .setTransparencyState(com.yayuy.overgrownvegetation.client.PortRenderStates.NO_TRANSPARENCY)
                        .setCullState(com.yayuy.overgrownvegetation.client.PortRenderStates.NO_CULL)
                        .setLightmapState(com.yayuy.overgrownvegetation.client.PortRenderStates.LIGHTMAP)
                        .setOverlayState(com.yayuy.overgrownvegetation.client.PortRenderStates.OVERLAY)
                        .createCompositeState(true));
    }

    /** parked の HordeSpriteTexture と同じ(ミップマップの段ごとに上げる)。 */
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
