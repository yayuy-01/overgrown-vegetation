package com.yayuy.overgrownvegetation.client.horde;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.ScutimnesRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.loading.FileLoader;
import software.bernie.geckolib.loading.json.raw.Model;
import software.bernie.geckolib.loading.object.BakedModelFactory;
import software.bernie.geckolib.loading.object.GeometryTree;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 遠景の身代わりの<b>絵</b>を、種(モデル + テクスチャ)ごとに初めて要るときに作って持っておく(2026-09-25、TD の「読み込み範囲の外から
 * 迫る大群」の 1 段目)。絵は起動後にその Mob のモデル(geo.json、レストポーズ)とテクスチャから描くので、モデル・テクスチャ・亜種を
 * 変えても自動で追従し、素材ファイルは増えない(公開リポジトリに漏れない)。
 *
 * <ul>
 *     <li>どのモデル・テクスチャかは、その Mob の GeckoLib のレンダラーに聞く(亜種は本物の個体のテクスチャで別の絵になる)。
 *     群れの行進(本物の個体が無い)では、種の見本を 1 体だけ作って聞く(ふつうの亜種の絵)。</li>
 *     <li>重い部分(geo.json の読み込み・{@link SpriteSheet#build} の描画 = 1 種 20〜120 ミリ秒)は裏のスレッドで行い、でき上がったら
 *     描画スレッドで GPU へ上げる({@link #pollUploads}、1 フレームに {@value #UPLOADS_PER_FRAME} 枚まで)。できるまでその種は描かない
 *     (バニラも描いていない遠さなので、見えないだけ)。</li>
 *     <li>資源の読み直し(F3+T・資源パックの変更)で全部捨てて作り直す。</li>
 * </ul>
 */
public final class HordeSprites {

    /** でき上がった絵。{@code id} は描く順をまとめるための番号。 */
    public record Sprite(int id, SpriteSheet sheet, ResourceLocation texture, RenderType renderType) {
    }

    /** 絵の元(モデル・テクスチャ・モデルを前後へずらす量 = レンダラーが原点をずらしている種だけ)。 */
    private record Key(ResourceLocation model, ResourceLocation texture, float shiftZ) {
    }

    /** 1 フレームに GPU へ上げてよい枚数。 */
    private static final int UPLOADS_PER_FRAME = 2;

    private static final Map<Key, Sprite> READY = new HashMap<>();
    private static final Map<Key, CompletableFuture<SpriteSheet>> PENDING = new HashMap<>();
    private static final Set<Key> FAILED = new HashSet<>();
    /** 種 → 見本から聞いた絵の元(群れの行進で使う)。null の値 = 聞けなかった(GeckoLib でない種など)。 */
    private static final Map<EntityType<?>, Key> TYPE_KEYS = new HashMap<>();
    private static int nextId;

    /** 本物の個体の絵(亜種ごと)。まだ無ければ作り始めて null。 */
    @Nullable
    public static Sprite forEntity(Entity entity) {
        Key key = keyOf(entity);
        return key == null ? null : request(key, entity.getType());
    }

    /** 種の絵(ふつうの亜種)。まだ無ければ作り始めて null。 */
    @Nullable
    public static Sprite forType(EntityType<?> type) {
        Key key;
        if (TYPE_KEYS.containsKey(type)) {
            key = TYPE_KEYS.get(type);
        } else {
            key = null;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return null;
            }
            Entity sample = type.create(level);
            if (sample != null) {
                key = keyOf(sample); // 見本は世界に入れていない(消す必要も無い)
            }
            TYPE_KEYS.put(type, key);
        }
        return key == null ? null : request(key, type);
    }

    @Nullable
    private static Sprite request(Key key, EntityType<?> type) {
        Sprite ready = READY.get(key);
        if (ready != null || FAILED.contains(key) || PENDING.containsKey(key)) {
            return ready;
        }
        float baseScale = baseScale(type);
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        PENDING.put(key, CompletableFuture.supplyAsync(() -> bake(resources, key, baseScale), Util.backgroundExecutor()));
        return null;
    }

    /** レンダラーに聞いたモデルとテクスチャ。GeckoLib の Mob でなければ null。 */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Key keyOf(Entity entity) {
        EntityRenderer<? super Entity> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof GeoEntityRenderer geo) || !(entity instanceof GeoAnimatable animatable)) {
            return null;
        }
        GeoModel model = geo.getGeoModel();
        ResourceLocation modelLocation = model.getModelResource(animatable, geo);
        ResourceLocation textureLocation = model.getTextureResource(animatable, geo);
        // スクティムネスのレンダラーは、原点(脚の真ん中)に合わせてモデルを後ろへずらして描く。絵も同じだけずらす。
        float shiftZ = ((Object) renderer) instanceof ScutimnesRenderer ? ScutimnesRenderer.MODEL_SHIFT : 0.0F;
        return modelLocation == null || textureLocation == null ? null : new Key(modelLocation, textureLocation, shiftZ);
    }

    /** その種のふだんの体格(属性 SCALE の基礎値 = mobs.json の scale。個体差を掛ける前)。 */
    @SuppressWarnings("unchecked")
    private static float baseScale(EntityType<?> type) {
        try {
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            return supplier.hasAttribute(Attributes.SCALE) ? (float) supplier.getBaseValue(Attributes.SCALE) : 1.0F;
        } catch (RuntimeException e) {
            return 1.0F; // 生き物でない種(属性が無い)
        }
    }

    /** 裏のスレッド: geo.json を読み直し(キャッシュのモデルはアニメで骨が動いている)、テクスチャを読み、8 方向を描く。 */
    @Nullable
    private static SpriteSheet bake(ResourceManager resources, Key key, float baseScale) {
        try {
            Model raw = FileLoader.loadModelFile(key.model(), resources);
            BakedGeoModel model = BakedModelFactory.getForNamespace(key.model().getNamespace())
                    .constructGeoModel(GeometryTree.fromModel(raw));
            SpriteRasterizer.Texture texture;
            try (InputStream in = resources.getResourceOrThrow(key.texture()).open();
                 NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth();
                int h = image.getHeight();
                int[] argb = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = image.getPixelRGBA(x, y);
                        argb[y * w + x] = (abgr & 0xFF00FF00) | ((abgr >> 16) & 0xFF) | ((abgr & 0xFF) << 16);
                    }
                }
                texture = new SpriteRasterizer.Texture(w, h, argb);
            }
            return SpriteSheet.build(SpriteQuads.shifted(SpriteQuads.of(model), key.shiftZ()), texture, baseScale);
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("遠景の身代わりの絵を作れない: {} / {}", key.model(), key.texture(), e);
            return null;
        }
    }

    /** でき上がった絵を GPU へ上げる(描画スレッド、毎フレーム)。 */
    public static void pollUploads() {
        if (PENDING.isEmpty()) {
            return;
        }
        int uploads = 0;
        Iterator<Map.Entry<Key, CompletableFuture<SpriteSheet>>> it = PENDING.entrySet().iterator();
        while (it.hasNext() && uploads < UPLOADS_PER_FRAME) {
            Map.Entry<Key, CompletableFuture<SpriteSheet>> entry = it.next();
            CompletableFuture<SpriteSheet> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            it.remove();
            SpriteSheet sheet = future.getNow(null);
            if (sheet == null) {
                FAILED.add(entry.getKey());
                continue;
            }
            int id = nextId++;
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "horde_sprite/" + id);
            try {
                Minecraft.getInstance().getTextureManager().register(location, new HordeSpriteTexture(sheet));
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.warn("遠景の身代わりの絵を GPU へ上げられない: {}", entry.getKey(), e);
                FAILED.add(entry.getKey());
                continue;
            }
            READY.put(entry.getKey(), new Sprite(id, sheet, location, renderType(location)));
            uploads++;
        }
    }

    /**
     * 身代わりを描く型: Mob の切り抜き描画(entity_cutout_no_cull)と同じで、テクスチャだけ線形補間 + ミップマップ
     * (遠くで縮んだ絵がちらつかないように)。
     */
    private static RenderType renderType(ResourceLocation texture) {
        return RenderType.create(
                OvergrownVegetation.MODID + "_horde_sprite",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, true, true))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.OVERLAY)
                        .createCompositeState(false));
    }

    /** 全部捨てる(資源の読み直し)。作りかけは結果を捨てる。 */
    public static void clear() {
        List<ResourceLocation> textures = new ArrayList<>();
        for (Sprite sprite : READY.values()) {
            textures.add(sprite.texture());
        }
        READY.clear();
        PENDING.clear();
        FAILED.clear();
        TYPE_KEYS.clear();
        HordeRenderer.clearFarTypes();
        for (ResourceLocation texture : textures) {
            Minecraft.getInstance().getTextureManager().release(texture);
        }
    }

    /** 資源の読み直しで捨てる(MOD バス・クライアント)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Lifecycle {

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> HordeSprites.clear());
        }

        private Lifecycle() {
        }
    }

    private HordeSprites() {
    }
}
