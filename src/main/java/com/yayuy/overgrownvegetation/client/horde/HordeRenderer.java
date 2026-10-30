package com.yayuy.overgrownvegetation.client.horde;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 遠くて描かれない Mob の<b>身代わり</b>を描く(2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」の 1 段目)。
 *
 * <h2>なぜ要るか</h2>
 * バニラは Mob を「当たり判定の大きさ(3 辺の平均)× 64 × エンティティ描画距離の設定」より先で描かない
 * ({@code Entity#shouldRenderAtSqrDistance})。アルボラ(0.98)は約 63 ブロック先で消えるので、本物の大群が遠くを歩いていても見えない。
 *
 * <h2>何を描くか</h2>
 * <ul>
 *     <li>G4〜G9 の Mob のうち、バニラが距離で描かない個体(読み込まれていて、描画距離の霧の手前まで)。</li>
 *     <li>TD の行進中の群れ(エンティティを持たないデータだけの群れ)の一員({@link ClientHorde}。2 段目)。</li>
 *     <li>描画距離の霧の外の、TD の空の群れの一員を<b>空の影</b>として(4 段目。霧を切って、暗く・霞ませて・半透明で。{@link #addFar})。
 *     地上の群れは {@link HordeStormRenderer} が地平線の胞子の嵐で描く。</li>
 * </ul>
 * 1 体につき、その Mob のモデルから作った 8 方向の絵({@link HordeSprites})のうち見る向きに合う 1 コマを、足元に立てた縦の板
 * (カメラの方へ鉛直軸まわりに回す)に貼る。明るさはその場所の明るさ(夜は暗い)、霧はバニラの Mob と同じ。全部を種ごとにまとめて
 * 描くので、何百体でも描画の呼び出しは種の数だけ。
 *
 * <p>描くのは {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES}(このときの座標は「ワールド座標 − カメラ座標」、カメラの回転は
 * 描画の行列の側に入っている。{@code client/SiegeBossRenderer} と同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class HordeRenderer {

    /** 1 体ぶん(使い回す)。 */
    static final class Instance {
        HordeSprites.Sprite sprite;
        double x;
        double y;
        double z;
        float yaw;
        float scale;
        double distanceSq;
        /** 頂点の色(ARGB)。近い側は白、遠い側(空の影)は霞と濃さ。 */
        int color;
    }

    private static final List<Instance> POOL = new ArrayList<>();
    private static int used;
    /**
     * 描く奥行きの外の<b>空の群れの影</b>(4 段目。{@link ClientHorde#collect})。霧を切って、半透明で描く({@link #farType})。
     * 近い側とは別に溜める(描き方が違う)。
     */
    private static final List<Instance> FAR_POOL = new ArrayList<>();
    private static int farUsed;
    /** 遠い側の型(絵のテクスチャ → 型)。 */
    private static final java.util.Map<net.minecraft.resources.ResourceLocation, net.minecraft.client.renderer.RenderType> FAR_TYPES =
            new java.util.HashMap<>();
    /** 投影の奥の面のどこまでに収めるか(攻城戦ボスの身代わりと同じ 0.45)。 */
    private static final double DRAW_DISTANCE_FRACTION = 0.45D;

    private static final int WHITE = 0xFFFFFFFF;
    /** 身代わりにする群(G4〜G9)。 */
    private static final int MIN_GROUP = 4;
    private static final int MAX_GROUP = 9;

    /**
     * 描画中に例外が出たら、このセッションの間は描くのをやめる(新しい描画で、ゲームを落とさないための保険。ログに 1 回だけ出す)。
     * 頂点は計算を全部済ませてから 4 つまとめて書くので、途中で止まっても書きかけの頂点は残らない。
     */
    private static boolean broken;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || broken) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.hordeSpritesEnabled.get()) {
            return;
        }
        try {
            render(event, cfg);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("遠景の身代わりの描画で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void render(RenderLevelStageEvent event, OvergrownVegetationConfig.Common cfg) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        HordeSprites.pollUploads();
        used = 0;
        farUsed = 0;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        double limit = drawLimit(minecraft);
        if (!(limit > 0.0D)) {
            return;
        }
        Frustum frustum = event.getFrustum();
        collectFarEntities(level, camera, partialTick, limit * limit, frustum);
        ClientHorde.collect(level, camera, partialTick, limit, frustum, cfg.hordeStormsEnabled.get());
        if (used > 0) {
            List<Instance> list = POOL.subList(0, used);
            int max = cfg.hordeSpriteMaxCount.get();
            if (list.size() > max) {
                list.sort(Comparator.comparingDouble(i -> i.distanceSq));
                list = list.subList(0, max);
            }
            list.sort(Comparator.comparingInt(i -> i.sprite.id()));
            draw(minecraft, level, camera, list, false);
        }
        if (farUsed > 0) {
            List<Instance> far = FAR_POOL.subList(0, farUsed);
            far.sort(Comparator.comparingInt(i -> i.sprite.id()));
            // 描く奥行きの外なので、シェーダーの霧を切って流す(遠さは霞として頂点の色に入れてある)。
            float fogStart = RenderSystem.getShaderFogStart();
            float fogEnd = RenderSystem.getShaderFogEnd();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            try {
                draw(minecraft, level, camera, far, true);
            } finally {
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
            }
        }
    }

    /** 描く奥行き: 描画距離と、いまの地形の霧の終わり(過繁茂の霧は短い)の近い方。その先は霧で塗り潰される。 */
    static double drawLimit(Minecraft minecraft) {
        return Math.min(minecraft.options.getEffectiveRenderDistance() * 16.0D, RenderSystem.getShaderFogEnd());
    }

    /** 投影の奥の面に収まる遠さ(これより遠いものは、同じ視角のまま近くへ寄せて縮めて描く。攻城戦ボスの身代わりと同じ手)。 */
    static double maxDrawDistance(Minecraft minecraft) {
        return minecraft.gameRenderer.getDepthFar() * DRAW_DISTANCE_FRACTION;
    }

    /** バニラが距離で描かない G4〜G9 の Mob を集める。 */
    private static void collectFarEntities(ClientLevel level, Vec3 camera, float partialTick, double limitSq, Frustum frustum) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity.isRemoved() || entity.isInvisible() || living.deathTime > 0) {
                continue;
            }
            MobGroup group = MobGroup.of(entity.getType());
            if (group == null || group.number() < MIN_GROUP || group.number() > MAX_GROUP || !moves(living)
                    || entity instanceof PediculaEntity) {
                continue; // 動かない種(汚染シダ・レイドビーコンなど)は遠くの飾りになるだけ。ペディキュラは半透明で、体が大きくバニラが遠くまで描く
            }
            double distanceSq = entity.distanceToSqr(camera);
            if (distanceSq > limitSq || entity.shouldRenderAtSqrDistance(distanceSq)) {
                continue; // 霧の向こう、またはバニラが本物を描く
            }
            HordeSprites.Sprite sprite = HordeSprites.forEntity(entity);
            if (sprite == null) {
                continue; // 絵がまだ無い(作っている最中)・GeckoLib でない種
            }
            Vec3 at = entity.getPosition(partialTick);
            float scale = living.getScale();
            if (!visible(frustum, sprite.sheet(), at.x, at.y, at.z, scale)) {
                continue;
            }
            add(sprite, at.x, at.y, at.z, Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot), scale, distanceSq);
        }
    }

    /** 動ける種か(移動速度が 0 より大きい。亜種の抽選 {@code MobVariants} と同じ見分け方)。 */
    private static boolean moves(LivingEntity living) {
        AttributeInstance speed = living.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.getBaseValue() > 0.0D;
    }

    /** 1 体足す。 */
    static void add(HordeSprites.Sprite sprite, double x, double y, double z, float yaw, float scale, double distanceSq) {
        Instance instance;
        if (used < POOL.size()) {
            instance = POOL.get(used);
        } else {
            instance = new Instance();
            POOL.add(instance);
        }
        used++;
        fill(instance, sprite, x, y, z, yaw, scale, distanceSq, WHITE);
    }

    /**
     * 描く奥行きの外の空の群れの一員を 1 体足す(霧を切って半透明で描く。{@code color} = 霞と濃さ。位置・大きさは投影の奥の面へ
     * 収めたもの)。
     */
    static void addFar(HordeSprites.Sprite sprite, double x, double y, double z, float yaw, float scale, int color) {
        Instance instance;
        if (farUsed < FAR_POOL.size()) {
            instance = FAR_POOL.get(farUsed);
        } else {
            instance = new Instance();
            FAR_POOL.add(instance);
        }
        farUsed++;
        fill(instance, sprite, x, y, z, yaw, scale, 0.0D, color);
    }

    private static void fill(Instance instance, HordeSprites.Sprite sprite, double x, double y, double z, float yaw, float scale,
                             double distanceSq, int color) {
        instance.sprite = sprite;
        instance.x = x;
        instance.y = y;
        instance.z = z;
        instance.yaw = yaw;
        instance.scale = scale;
        instance.distanceSq = distanceSq;
        instance.color = color;
    }

    /** 板が画面に入るか(板はカメラの方へ回るので、足元を中心に絵の横幅いっぱいの箱で見る)。 */
    static boolean visible(Frustum frustum, SpriteSheet sheet, double x, double y, double z, float scale) {
        float px = scale / sheet.pixelsPerBlock();
        float reach = Math.max(sheet.originX(), sheet.frameWidth() - sheet.originX()) * px;
        float top = sheet.originY() * px;
        float bottom = (sheet.originY() - sheet.frameHeight()) * px;
        return frustum.isVisible(new AABB(x - reach, y + bottom, z - reach, x + reach, y + top, z + reach));
    }

    private static void draw(Minecraft minecraft, ClientLevel level, Vec3 camera, List<Instance> list, boolean far) {
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        BlockPos.MutableBlockPos light = new BlockPos.MutableBlockPos();
        int i = 0;
        int n = list.size();
        while (i < n) {
            HordeSprites.Sprite sprite = list.get(i).sprite;
            net.minecraft.client.renderer.RenderType type = far ? farType(sprite) : sprite.renderType();
            VertexConsumer consumer = buffers.getBuffer(type);
            int j = i;
            for (; j < n && list.get(j).sprite == sprite; j++) {
                emit(consumer, level, camera, list.get(j), light, far);
            }
            buffers.endBatch(type);
            i = j;
        }
    }

    /**
     * 空の影を描く型: 身代わりと同じテクスチャ(線形補間 + ミップマップ)を<b>半透明</b>で(描く奥行きのすぐ外で薄れて消えるように)、
     * 深さは読むが書かない、遠い順に並べる。
     */
    private static net.minecraft.client.renderer.RenderType farType(HordeSprites.Sprite sprite) {
        return FAR_TYPES.computeIfAbsent(sprite.texture(), texture -> net.minecraft.client.renderer.RenderType.create(
                OvergrownVegetation.MODID + "_horde_far_sprite",
                com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                net.minecraft.client.renderer.RenderType.CompositeState.builder()
                        .setShaderState(net.minecraft.client.renderer.RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new net.minecraft.client.renderer.RenderStateShard.TextureStateShard(texture, true, true))
                        .setTransparencyState(net.minecraft.client.renderer.RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setCullState(net.minecraft.client.renderer.RenderStateShard.NO_CULL)
                        .setLightmapState(net.minecraft.client.renderer.RenderStateShard.LIGHTMAP)
                        .setOverlayState(net.minecraft.client.renderer.RenderStateShard.OVERLAY)
                        .setWriteMaskState(net.minecraft.client.renderer.RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false)));
    }

    /** 絵を捨てたとき(資源の読み直し)に、その絵の遠い側の型も捨てる。 */
    static void clearFarTypes() {
        FAR_TYPES.clear();
    }

    /**
     * 足元に立てた縦の板(カメラの方へ鉛直軸まわりに回す)に、見る向きのコマを貼る。遠い側(空の影)は、その場所の明るさを引けない
     * (チャンクが無い)ので最大の明るさで描き、昼夜の暗さは頂点の色に入れてある。
     */
    private static void emit(VertexConsumer consumer, ClientLevel level, Vec3 camera, Instance in, BlockPos.MutableBlockPos lightPos,
                             boolean far) {
        SpriteSheet sheet = in.sprite.sheet();
        double dx = in.x - camera.x;
        double dz = in.z - camera.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4D) {
            return;
        }
        // 画像の右 = 視線(カメラ → Mob)× 上 = (−dz, 0, dx)。絵を描いたときと同じ式なので、左右が揃う。
        float rx = (float) (-dz / horizontal);
        float rz = (float) (dx / horizontal);
        int view = SpriteSheet.viewIndex(SpriteSheet.relativeAngle(in.yaw, -dx, -dz));
        float px = in.scale / sheet.pixelsPerBlock();
        float left = -sheet.originX() * px;
        float right = (sheet.frameWidth() - sheet.originX()) * px;
        float top = sheet.originY() * px;
        float bottom = (sheet.originY() - sheet.frameHeight()) * px;
        float u0 = view / (float) SpriteSheet.VIEWS;
        float u1 = (view + 1) / (float) SpriteSheet.VIEWS;
        float bx = (float) dx;
        float by = (float) (in.y - camera.y);
        float bz = (float) dz;
        int light;
        if (far) {
            light = LightTexture.FULL_BRIGHT;
        } else {
            lightPos.set(in.x, in.y + top * 0.5D, in.z);
            light = level.hasChunkAt(lightPos) ? LevelRenderer.getLightColor(level, lightPos) : LightTexture.pack(0, 15);
        }
        int overlay = OverlayTexture.NO_OVERLAY;
        int color = in.color;
        // 法線は上向き(絵に陰影を焼いてあるので、Mob の 2 灯の陰影を掛け直さない)。
        consumer.addVertex(bx + rx * left, by + bottom, bz + rz * left, color, u0, 1.0F, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(bx + rx * right, by + bottom, bz + rz * right, color, u1, 1.0F, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(bx + rx * right, by + top, bz + rz * right, color, u1, 0.0F, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(bx + rx * left, by + top, bz + rz * left, color, u0, 0.0F, overlay, light, 0.0F, 1.0F, 0.0F);
    }

    private HordeRenderer() {
    }
}
