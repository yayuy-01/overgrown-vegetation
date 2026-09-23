package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
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
        event.registerLayerDefinition(OvergrownVegetationModelLayers.SIEGE_BOMB, SiegeBombModel::createBodyLayer);
        event.registerLayerDefinition(OvergrownVegetationModelLayers.EROSION_DEFAULT,
                () -> LayerDefinition.create(
                        PlayerModel.createMesh(new CubeDeformation(ErosionPlayerLayer.INFLATION), false),
                        64, 64));
        event.registerLayerDefinition(OvergrownVegetationModelLayers.EROSION_SLIM,
                () -> LayerDefinition.create(
                        PlayerModel.createMesh(new CubeDeformation(ErosionPlayerLayer.INFLATION), true),
                        64, 64));
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
     * <p>ビデオ設定の「シダをすべて表示する」({@link CurledFernOption})から切り替えたときは
     * そちらが直接 {@code applyDetailChange()} を呼ぶ({@code ConfigValue#set} は
     * イベントを投げないため)。二重に呼ばれても、値が変わっていなければ何もしない。
     */
    @SubscribeEvent
    public static void reloadOnCurledFernDetailChange(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == OvergrownVegetationConfig.SPEC) {
            GeoStaticGeometry.applyDetailChange();
        }
    }

    /**
     * PAL(Player Animation Library)のレイヤー登録。PAL の決まりで {@code FMLClientSetupEvent#enqueueWork} の中で行う。
     * 儀式({@link HiveRitualClient})でプレイヤー本体を動かすためのもの。
     */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(HiveRitualClient::registerPlayerAnimationLayer);
        // 機関砲台がクライアントでも「いま飛んでいる胞子弾」を狙えるようにする(向きの同期を省くため)。
        event.enqueueWork(() -> com.yayuy.overgrownvegetation.block.GunTurretBlockEntity
                .setClientShellLocator(ClientShells::position));
        // 撃った弾を見えるようにする(見た目だけ。当たり判定は即着弾)。
        event.enqueueWork(() -> com.yayuy.overgrownvegetation.block.GunTurretBlockEntity
                .setClientTracerSink(GunTracerRenderer::add));
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RESEARCH_TABLE.get(), ResearchTableScreen::new);
        event.register(ModMenus.SPORE_DEVICE.get(), SporeDeviceScreen::new);
        event.register(ModMenus.DECONTAMINATOR.get(), DecontaminatorScreen::new);
        event.register(ModMenus.SIEGE_CANNON.get(), SiegeCannonScreen::new);
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.OVERGROWN_SPORE.get(), OvergrownSporeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.FINE_SPORE.get(), FineSporeParticle.Provider::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BLIGHT_FERN.get(), BlightFernRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_NODE.get(), HiveNodeRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_VINE.get(), HiveVineRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_RITUAL.get(), HiveRitualRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_RITUAL_SEAT.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.BLIGHT_WATER_GRASS.get(), BlightWaterGrassRenderer::new);
        event.registerEntityRenderer(ModEntities.OVERGROWN_SHIELDER.get(), OvergrownShielderRenderer::new);
        event.registerEntityRenderer(ModEntities.FALCIMNES.get(), FalcimnesRenderer::new);
        event.registerEntityRenderer(ModEntities.PHALANGIOTARBUS.get(), PhalangiotarbusRenderer::new);
        event.registerEntityRenderer(ModEntities.PROTOPHASMA.get(), ProtophasmaRenderer::new);
        event.registerEntityRenderer(ModEntities.ARTHROPLEURA.get(), ArthropleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.SPELAEOPLEURA.get(), SpelaeopleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.COELUROSAURAVUS.get(), CoelurosauravusRenderer::new);
        event.registerEntityRenderer(ModEntities.LITHOPLEURA.get(), LithopleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.STENODICTYA_LOBATA.get(), StenodictyaLobataRenderer::new);
        event.registerEntityRenderer(ModEntities.MEGANEURA.get(), MeganeuraRenderer::new);
        event.registerEntityRenderer(ModEntities.ARBORA.get(), ArboraRenderer::new);
        event.registerEntityRenderer(ModEntities.ARBORA_QUEEN.get(), ArboraQueenRenderer::new);
        event.registerEntityRenderer(ModEntities.NEST_CORE.get(), NestCoreRenderer::new);
        event.registerEntityRenderer(ModEntities.PHYLLOSENS.get(), PhyllosensRenderer::new);
        event.registerEntityRenderer(ModEntities.VELOPHYLL.get(), VelophyllRenderer::new);
        event.registerEntityRenderer(ModEntities.VELOPHYLL_BOMB.get(), VelophyllBombRenderer::new);
        event.registerEntityRenderer(ModEntities.SPOROPHYLL.get(), SporophyllRenderer::new);
        event.registerEntityRenderer(ModEntities.PETROLACOSAURUS.get(), PetrolacosaurusRenderer::new);
        event.registerEntityRenderer(ModEntities.IANTHASAURUS.get(), IanthasaurusRenderer::new);
        event.registerEntityRenderer(ModEntities.HYLONOMUS.get(), HylonomusRenderer::new);
        event.registerEntityRenderer(ModEntities.RHIZODUS.get(), RhizodusRenderer::new);
        event.registerEntityRenderer(ModEntities.XENACANTHUS.get(), XenacanthusRenderer::new);
        event.registerEntityRenderer(ModEntities.ORTHACANTHUS.get(), OrthacanthusRenderer::new);
        event.registerEntityRenderer(ModEntities.MEGARACHNE.get(), MegarachneRenderer::new);
        event.registerEntityRenderer(ModEntities.OVERGROWN_NEXUS.get(), OvergrownNexusRenderer::new);
        event.registerEntityRenderer(ModEntities.DORGA_ARCHELAUS_TITANICA.get(), DorgaArchelausTitanicaRenderer::new);
        // 当たり判定の箱は見えない(F3+B の当たり判定表示でだけ見える)。
        event.registerEntityRenderer(ModEntities.TITANICA_HITBOX.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.SIEGE_BOMB.get(), SiegeBombRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.OVERGROWN_LIANA.get(), context -> new CorruptedLianaRenderer());
        // 羊・豚・クリーパーの死体は専用の GeckoLib 立体モデルで描画(近づかないと描画されない)。
        // 牛・ゾンビ・クモの死体は通常のブロックモデルのままで、この BER では描画しない。
        event.registerBlockEntityRenderer(ModBlockEntities.CORRUPTED_CORPSE.get(), context -> new CorruptedCorpseRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.LITHOPLEURA_HUSK.get(), context -> new LithopleuraHuskRenderer());
        // 過繫茂したチェスト(バニラのチェストモデル + 緑の色味 + 蔦・コケのオーバーレイ)。
        event.registerBlockEntityRenderer(ModBlockEntities.OVERGROWN_CHEST.get(), OvergrownChestRenderer::new);
        // 砲台(GeckoLib。台座が旋回し砲身が俯仰する。ブロックモデル自体は描画しない)。
        event.registerBlockEntityRenderer(ModBlockEntities.SIEGE_CANNON.get(), SiegeCannonRenderer::new);
        // 遮蔽盾（ハンドルで旋回・ボタンで展開）。
        event.registerBlockEntityRenderer(ModBlockEntities.BEAM_SHIELD.get(), BeamShieldRenderer::new);
        // 機関砲台(GeckoLib。台座が旋回し銃身が俯仰する。汚染されるとテクスチャだけ差し替わる)。
        event.registerBlockEntityRenderer(ModBlockEntities.GUN_TURRET.get(), GunTurretRenderer::new);
        // 砲台の座席は見えないエンティティ。
        event.registerEntityRenderer(ModEntities.SIEGE_CANNON_SEAT.get(), NoopRenderer::new);

        // 「過繁茂した牛/豚/羊/ゾンビ/クリーパー/クモ」は専用の GeckoLib モデルを使う(見た目のみ差し替え、AI/繁殖等はバニラを流用)。
        event.registerEntityRenderer(ModEntities.PARASITIZED_COW.get(), ParasitizedCowRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_PIG.get(), ParasitizedPigRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_SHEEP.get(), ParasitizedSheepRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_ZOMBIE.get(), ParasitizedZombieRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_CREEPER.get(), ParasitizedCreeperRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_SPIDER.get(), ParasitizedSpiderRenderer::new);

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

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> 0xFF000000 | OVERGROWN_CONTAINER_TINT,
                ModItems.OVERGROWN_BARREL_ITEM.get());
    }


    /**
     * 極相({@code engulfed})バイオームの中では、過繫茂した草ブロック・下草・茂みを少し暗く描く
     * (2026-09-22、{@code climax.json} の {@code biome.plantTint})。林冠が閉じて日が差さない
     * 林床の草、という絵をブロック側からも作るため。極相の外では {@code 0xFFFFFF}(変化なし)。
     *
     * <p>この 3 つのモデルには {@code tintindex: 0} を入れてある(草ブロックは専用の親
     * {@code block/cube_bottom_top_tinted}、下草はバニラの {@code block/tinted_cross}、
     * 茂みは 10 面すべて)。<b>tintindex の無い面にはこのハンドラは効かない</b>ので、
     * 対象を増やすときはモデル側も一緒に直すこと。
     *
     * <p>色は<b>チャンクのメッシュに焼き込まれる</b>(毎フレーム呼ばれるわけではない)。
     * バイオームが書き換わると {@code ChunkMap#resendBiomesForChunks} の再送でクライアントが
     * セクションを描き直すので、極相になった瞬間にちゃんと色が変わる。
     *
     * <p>境目はぼかしていない(バニラの草の色のような近傍の混色をしていない)。過繫茂バイオームの
     * 縁はもともと quart 単位で波打たせてあるので直線にはならず、さらに極相の中は霧が 48 ブロックなので
     * 遠景では見えない。気になるようなら近傍の平均を取る形に変えられる。
     */
    @SubscribeEvent
    public static void registerClimaxPlantColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> climaxPlantTint(pos),
                ModBlocks.OVERGROWN_GRASS_BLOCK.get(),
                ModBlocks.OVERGROWN_GROWTH.get(),
                ModBlocks.OVERGROWN_BUSH.get());
    }

    /** その位置が極相バイオームなら {@code biome.plantTint}、そうでなければ白(変化なし)。 */
    private static int climaxPlantTint(@Nullable BlockPos pos) {
        if (pos == null) {
            return 0xFFFFFF; // 手持ちのアイテムなど、位置が無いところでは色を変えない。
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 0xFFFFFF;
        }
        return ModBiomes.isClimax(mc.level.getBiome(pos))
                ? DesignData.climax().plantTint()
                : 0xFFFFFF;
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
                renderer.addLayer(new ErosionPlayerLayer(
                        renderer, event.getEntityModels(), model == PlayerSkin.Model.SLIM));
            }
        }
    }
    private OvergrownVegetationClient() {
    }
}
