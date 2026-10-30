package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

import javax.annotation.Nullable;

@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OvergrownVegetationClient {

    /**
     * まだ GeckoLib 化されていない「過繁茂した(MOB名)」(現状ニワトリのみ)の仮モデル
     * ({@code Parasitized*Model})のレイヤー定義を登録する。バニラの {@code ModelLayers} 定数の
     * 代わりに {@link OvergrownVegetationModelLayers} を使うため必須
     * ({@link EntityRendererProvider.Context#bakeLayer} がこの登録を要求する)。
     */
    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
    }

    /**
     * geo.json をそのまま静的なブロックモデルとして焼き込む読み込み器({@link GeoStaticGeometry})。
     * モデル JSON の {@code "loader": "overgrown_vegetation:geo_static"} で使う(丸まったシダ)。
     */
    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(GeoStaticGeometry.LOADER_ID, GeoStaticGeometry.Loader.INSTANCE);
    }

    /**
     * 「覆われて動かなくなったブロック」({@code overgrown_husk})の模型を差し替える(2026-09-22)。
     *
     * <p>焼き上がった模型の表を後から書き換えるやり方にしてある —— 読み込み器
     * ({@code RegisterGeometryLoaders})でもできるが、このブロックは blockstate に候補が 1 つしかなく、
     * モデル JSON 側に書くことが何も無いので、差し替えるだけで足りる。
     * 差し替え元の模型は<b>予備</b>として持たせる(化ける先の MOD を外したときの見た目)。
     */
    @SubscribeEvent
    public static void replaceHuskModel(ModelEvent.ModifyBakingResult event) {
        // 模型を焼き直した。「どのブロックが面を持っているか」の覚えは古くなっているので捨てる。
        OvergrownHuskModel.forget();
        for (net.minecraft.world.level.block.state.BlockState state
                : ModBlocks.OVERGROWN_HUSK.get().getStateDefinition().getPossibleStates()) {
            net.minecraft.client.resources.model.ModelResourceLocation key =
                    net.minecraft.client.renderer.block.BlockModelShaper.stateToModelLocation(state);
            net.minecraft.client.resources.model.BakedModel baked = event.getModels().get(key);
            if (baked != null && !(baked instanceof OvergrownHuskModel)) {
                event.getModels().put(key, new OvergrownHuskModel(baked));
            }
        }
    }

    /**
     * 「覆われて動かなくなったブロック」の色付け。元のブロックの面をそのまま借りるので、
     * 元が色付け({@code tintindex})を使っていた場合は<b>元のブロックの色を引き直す</b>
     * 必要がある。引けなければ -1(色付けなし)。
     *
     * <p>他 MOD の色解決がこの座標の中身を自分のものと思い込んで落ちうるので握り潰す ——
     * 色が 1 つ白くなるより、ワールドが開けなくなるほうがずっと困る。
     */
    @SubscribeEvent
    public static void registerHuskColor(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> {
            if (level == null || pos == null) {
                return -1;
            }
            net.minecraft.world.level.block.state.BlockState mimic =
                    com.yayuy.overgrownvegetation.block.OvergrownHuskBlock.mimicAt(level, pos);
            if (mimic == null || mimic.isAir()) {
                return -1;
            }
            try {
                return Minecraft.getInstance().getBlockColors().getColor(mimic, level, pos, tintIndex);
            } catch (Throwable ignored) {
                return -1;
            }
        }, ModBlocks.OVERGROWN_HUSK.get());
    }

    /**
     * 巻きシダの精細さ({@code curledFernDetail})を config ファイルで直接書き換えたぶんを反映する。
     * 何をするか(チャンクの積み直しか、リソース再読み込みか)は
     * {@link GeoStaticGeometry#applyDetailChange()} が変更の種類を見て決める。
     *
     * <p>ビデオ設定の「シダの葉を立体で描く」({@link CurledFernOption})から切り替えたときは
     * そちらが直接 {@code applyDetailChange()} を呼ぶ({@code ConfigValue#set} は
     * イベントを投げないため)。二重に呼ばれても、値が変わっていなければ何もしない。
     */
    @SubscribeEvent
    public static void reloadOnCurledFernDetailChange(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == com.yayuy.overgrownvegetation.config.OvergrownClientConfig.SPEC) { // 10-23 にクライアント設定へ移した
            GeoStaticGeometry.applyDetailChange();
        }
    }

    /**
     * PAL(Player Animation Library)のレイヤー登録。PAL の決まりで {@code FMLClientSetupEvent#enqueueWork} の中で行う。
     * 儀式({@link HiveRitualClient})でプレイヤー本体を動かすためのもの。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.OVERGROWN_SPORE.get(), OvergrownSporeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.OVERGROWN_SPORE_SHORT.get(), OvergrownSporeParticle.Provider::shortLived);
        event.registerSpriteSet(ModParticles.FINE_SPORE.get(), FineSporeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.SPORE_JET.get(), SporeJetParticle.Provider::new);
        event.registerSpriteSet(ModParticles.DRIFTING_SPORE.get(), DriftingSporeParticle.Provider::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BLIGHT_FERN.get(), BlightFernRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_NODE.get(), HiveNodeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.OVERGROWN_LIANA.get(), context -> new CorruptedLianaRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.BLIGHT_FERN_ROOTED.get(), context -> new BlightFernRootedRenderer());
        // 過繫茂したチェスト(バニラのチェストモデル + 緑の色味 + 蔦・コケのオーバーレイ)。
        event.registerBlockEntityRenderer(ModBlockEntities.OVERGROWN_CHEST.get(), OvergrownChestRenderer::new);

        // 他の「過繁茂した(MOB名)」— バニラの対応 Mob と同じ骨格/アニメーションに、シダ/コケの
        // 成長物キューブを足しただけの専用モデル(仮のシルエット、Parasitized*Model 参照)。
    }

    /**
     * スポーンエッグを「卵の絵」ではなく、その Mob 本体の立体モデルで描画するようにする
     * ({@link SpawnEggEntityItemRenderer})。{@code ModItems} に登録された全スポーンエッグが対象。
     *
     * <p>{@code BlockEntityWithoutLevelRenderer} は Minecraft の初期化が終わってからでないと
     * 作れないので、最初に描画されるまで生成を遅らせる。
     */
    @SubscribeEvent
    public static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions spawnEggExtensions = new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    this.renderer = new SpawnEggEntityItemRenderer();
                }
                return this.renderer;
            }
        };

        Item[] spawnEggs = ModItems.ITEMS.getEntries().stream()
                .map(DeferredHolder::get)
                .filter(item -> item instanceof SpawnEggItem)
                .toArray(Item[]::new);
        event.registerItem(spawnEggExtensions, spawnEggs);

        // 過繫茂したチェストのアイテムはブロックと同じ立体モデル(OvergrownChestItemRenderer)で描く。
        event.registerItem(new IClientItemExtensions() {
            private BlockEntityWithoutLevelRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.renderer == null) {
                    this.renderer = new OvergrownChestItemRenderer();
                }
                return this.renderer;
            }
        }, ModItems.OVERGROWN_CHEST_ITEM.get());
    }

    /** 過繫茂したドア/樽の本体色(バニラのオークのドア/樽のテクスチャに乗算する緑がかった色)。 */
    private static final int OVERGROWN_CONTAINER_TINT = 0x8FB77A;

    /**
     * 過繫茂したドア/樽のブロックモデルは {@code tintindex: 0} を持つ(バニラのテクスチャを流用しつつ
     * 緑がかった色にするため)。ここでその色を返す。アイテム(樽のアイテムはブロックモデルそのもの)も同じ色。
     */
    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> OVERGROWN_CONTAINER_TINT,
                ModBlocks.OVERGROWN_DOOR.get(), ModBlocks.OVERGROWN_BARREL.get());
    }

    /** 過繁茂の葉の色の揺らぎ・赤い若葉(色の番号を色付けの色の比に書く。{@link LeafColors}、2026-10-17)。 */
    @SubscribeEvent
    public static void registerLeafColors(RegisterColorHandlersEvent.Block event) {
        event.register(LeafColors::color, ModBlocks.OVERGROWN_LEAVES.get());
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> 0xFF000000 | OVERGROWN_CONTAINER_TINT,
                ModItems.OVERGROWN_BARREL_ITEM.get());
    }

    /**
     * プレイヤーのレンダラーへ浸食度の見た目({@link ErosionPlayerLayer})を差し込む(2026-09-22)。
     *
     * <p>プレイヤーのレンダラーは <b>wide と slim の 2 つ</b>が別々に登録されている
     * ({@code "default"} / {@code "slim"})。片方だけに足すと、そのスキンの人にだけ苔が出ない。
     */
    @SubscribeEvent
    public static void registerPlayerLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model model : PlayerSkin.Model.values()) {
            PlayerRenderer renderer = event.getSkin(model);
            if (renderer != null) {
            }
        }
    }
    private OvergrownVegetationClient() {
    }
}
