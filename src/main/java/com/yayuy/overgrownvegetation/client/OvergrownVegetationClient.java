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
        for (com.yayuy.overgrownvegetation.item.ExtraArmor.Part part : com.yayuy.overgrownvegetation.item.ExtraArmor.Part.values()) {
            float inflation = OvergrownVegetationModelLayers.extraArmorInflation(part);
            event.registerLayerDefinition(OvergrownVegetationModelLayers.extraArmor(part),
                    () -> LayerDefinition.create(net.minecraft.client.model.HumanoidModel.createMesh(new CubeDeformation(inflation), 0.0F), 64, 32));
        }
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
        event.enqueueWork(HiveRitualClient::registerPlayerAnimationLayer);
        // 機関砲台がクライアントでも「いま飛んでいる胞子弾」を狙えるようにする(向きの同期を省くため)。
        event.enqueueWork(() -> com.yayuy.overgrownvegetation.block.GunTurretBlockEntity
                .setClientShellLocator(ClientShells::position));
        // 撃った弾を見えるようにする(見た目だけ。当たり判定は即着弾)。
        event.enqueueWork(() -> com.yayuy.overgrownvegetation.block.GunTurretBlockEntity
                .setClientTracerSink(GunTracerRenderer::add));
        // カートリッジ: 因子が 1 個以上入っていると「充填済み」の絵(アイテムモデルの overrides で切り替える。着手順 8)。
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                com.yayuy.overgrownvegetation.registry.ModItems.CARTRIDGE.get(),
                com.yayuy.overgrownvegetation.OvergrownVegetation.id("filled"),
                (stack, level, entity, seed) ->
                        com.yayuy.overgrownvegetation.item.CartridgeItem.contents(stack).isEmpty() ? 0.0F : 1.0F));
        // パチンコの引き(弓と同じ pull / pulling。2026-10-16)と、地脈の羅針盤の針(コンパスと同じ angle)。
        event.enqueueWork(() -> {
            var slingshot = com.yayuy.overgrownvegetation.registry.ModItems.SLINGSHOT.get();
            net.minecraft.client.renderer.item.ItemProperties.register(slingshot, net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"),
                    (stack, level, entity, seed) -> entity == null || entity.getUseItem() != stack ? 0.0F
                            : (stack.getUseDuration(entity) - entity.getUseItemRemainingTicks()) / 20.0F);
            net.minecraft.client.renderer.item.ItemProperties.register(slingshot, net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"),
                    (stack, level, entity, seed) -> entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F);
            net.minecraft.client.renderer.item.ItemProperties.register(com.yayuy.overgrownvegetation.registry.ModItems.LEY_COMPASS.get(),
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("angle"),
                    new net.minecraft.client.renderer.item.CompassItemPropertyFunction((level, stack, entity) ->
                            stack.get(com.yayuy.overgrownvegetation.registry.ModDataComponents.LEY_COMPASS_TARGET.get())));
        });
        // 布のグライダー: 開いている間は三角の布の模型(2026-10-22)。手元のプレイヤーは本当の判定、ほかのプレイヤーは「利き手に持って空中」で見る
        // (落下の距離などは他人の分が届かないため)。
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                com.yayuy.overgrownvegetation.registry.ModItems.GLIDER.get(),
                com.yayuy.overgrownvegetation.OvergrownVegetation.id("open"),
                (stack, level, entity, seed) -> {
                    if (!(entity instanceof net.minecraft.world.entity.player.Player p) || !p.getMainHandItem().is(stack.getItem())) { // 一人称の手元の絵は持ち物の写しなので、同じ物かは種類で見る
                        return 0.0F;
                    }
                    if (p == net.minecraft.client.Minecraft.getInstance().player) {
                        return com.yayuy.overgrownvegetation.item.GliderItem.isOpen(p) ? 1.0F : 0.0F;
                    }
                    return !p.onGround() && !p.isInWater() && !p.isPassenger() && !p.getAbilities().flying
                            && com.yayuy.overgrownvegetation.item.GliderItem.usable(stack) ? 1.0F : 0.0F;
                }));
        // しおれた地脈の花(2026-10-24): 壊したときの状態(block_state の wilted)が残っていれば褪せた絵。
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                com.yayuy.overgrownvegetation.registry.ModItems.LEY_FLOWER.get(),
                com.yayuy.overgrownvegetation.OvergrownVegetation.id("wilted"),
                (stack, level, entity, seed) -> com.yayuy.overgrownvegetation.block.LeyFlowerBlock.isWilted(stack) ? 1.0F : 0.0F));
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.RESEARCH_TABLE.get(), ResearchTableScreen::new);
        event.register(ModMenus.SPORE_DEVICE.get(), SporeDeviceScreen::new);
        event.register(ModMenus.DECONTAMINATOR.get(), DecontaminatorScreen::new);
        event.register(ModMenus.SIEGE_CANNON.get(), SiegeCannonScreen::new);
        event.register(ModMenus.TARGET_STORAGE.get(), TargetStorageScreen::new);
        event.register(ModMenus.POWERED_ARMOR_TABLE.get(), PoweredArmorTableScreen::new);
        event.register(ModMenus.FACTOR_EXTRACTOR.get(), FactorExtractorScreen::new);
        event.register(ModMenus.BASE_TERMINAL.get(), BaseTerminalScreen::new);
        event.register(ModMenus.LEY_FURNACE.get(), LeyFurnaceScreen::new);
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.OVERGROWN_SPORE.get(), OvergrownSporeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.OVERGROWN_SPORE_SHORT.get(), OvergrownSporeParticle.Provider::shortLived);
        event.registerSpriteSet(ModParticles.FINE_SPORE.get(), FineSporeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.SPORE_JET.get(), SporeJetParticle.Provider::new);
        event.registerSpriteSet(ModParticles.DRIFTING_SPORE.get(), DriftingSporeParticle.Provider::new);
        // 空の巨大生物の大きな爆発・煙(2026-10-18 夜)
        event.registerSpriteSet(ModParticles.SKY_BOSS_BLAST.get(), com.yayuy.overgrownvegetation.client.skyboss.SkyBossParticles.BlastProvider::new);
        event.registerSpriteSet(ModParticles.SKY_BOSS_SMOKE.get(), com.yayuy.overgrownvegetation.client.skyboss.SkyBossParticles.SmokeProvider::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SLINGSHOT_SHOT.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.STICKY_NET.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.ASH_SMOKE_BOMB.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.INCENDIARY_FLASK.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.BLIGHT_FERN.get(), BlightFernRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_NODE.get(), HiveNodeRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_RITUAL.get(), HiveRitualRenderer::new);
        event.registerEntityRenderer(ModEntities.HIVE_RITUAL_SEAT.get(), NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.OVERGROWN_SHIELDER.get(), OvergrownShielderRenderer::new);
        event.registerEntityRenderer(ModEntities.FALCIMNES.get(), FalcimnesRenderer::new);
        event.registerEntityRenderer(ModEntities.SCUTIMNES.get(), ScutimnesRenderer::new);
        event.registerEntityRenderer(ModEntities.SCUTIMNES_SHOCKWAVE.get(), ScutimnesShockwaveRenderer::new);
        event.registerEntityRenderer(ModEntities.ACUMNES.get(), AcumnesRenderer::new);
        event.registerEntityRenderer(ModEntities.ACUMNES_SPINE.get(), AcumnesSpineRenderer::new);
        event.registerEntityRenderer(ModEntities.BATHYMNES.get(), BathymnesRenderer::new);
        event.registerEntityRenderer(ModEntities.EREBOMNES.get(), ErebomnesRenderer::new);
        event.registerEntityRenderer(ModEntities.VORAMNES.get(), VoramnesRenderer::new);
        event.registerEntityRenderer(ModEntities.FODIMNES.get(), FodimnesRenderer::new);
        event.registerEntityRenderer(ModEntities.PHALANGIOTARBUS.get(), PhalangiotarbusRenderer::new);
        event.registerEntityRenderer(ModEntities.PROTOPHASMA.get(), ProtophasmaRenderer::new);
        event.registerEntityRenderer(ModEntities.ARTHROPLEURA.get(), ArthropleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.SPELAEOPLEURA.get(), SpelaeopleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.COELUROSAURAVUS.get(), CoelurosauravusRenderer::new);
        event.registerEntityRenderer(ModEntities.LITHOPLEURA.get(), LithopleuraRenderer::new);
        event.registerEntityRenderer(ModEntities.STENODICTYA_LOBATA.get(), StenodictyaLobataRenderer::new);
        event.registerEntityRenderer(ModEntities.MEGANEURA.get(), MeganeuraRenderer::new);
        event.registerEntityRenderer(ModEntities.ARBORA.get(), ArboraRenderer::new);
        event.registerEntityRenderer(ModEntities.PHYLLOSENS.get(), PhyllosensRenderer::new);
        event.registerEntityRenderer(ModEntities.VELOPHYLL.get(), VelophyllRenderer::new);
        event.registerEntityRenderer(ModEntities.VELOPHYLL_BOMB.get(), VelophyllBombRenderer::new);
        event.registerEntityRenderer(ModEntities.PEDICULA.get(), PediculaRenderer::new);
        event.registerEntityRenderer(ModEntities.SPOROPHYLL.get(), SporophyllRenderer::new);
        event.registerEntityRenderer(ModEntities.PETROLACOSAURUS.get(), PetrolacosaurusRenderer::new);
        event.registerEntityRenderer(ModEntities.IANTHASAURUS.get(), IanthasaurusRenderer::new);
        event.registerEntityRenderer(ModEntities.HYLONOMUS.get(), HylonomusRenderer::new);
        event.registerEntityRenderer(ModEntities.RHIZODUS.get(), RhizodusRenderer::new);
        event.registerEntityRenderer(ModEntities.XENACANTHUS.get(), XenacanthusRenderer::new);
        event.registerEntityRenderer(ModEntities.ORTHACANTHUS.get(), OrthacanthusRenderer::new);
        event.registerEntityRenderer(ModEntities.MEGARACHNE.get(), MegarachneRenderer::new);
        // G2・G3 の新しい 8 種(2026-10-22)
        event.registerEntityRenderer(ModEntities.DIPLOCAULUS.get(), DiplocaulusRenderer::new);
        event.registerEntityRenderer(ModEntities.LUNGFISH.get(), LungfishRenderer::new);
        event.registerEntityRenderer(ModEntities.HIBBERTOPTERUS.get(), HibbertopterusRenderer::new);
        event.registerEntityRenderer(ModEntities.GIANT_SNAIL.get(), GiantSnailRenderer::new);
        event.registerEntityRenderer(ModEntities.STENODICTYA_NYMPH.get(), StenodictyaNymphRenderer::new);
        event.registerEntityRenderer(ModEntities.ERYOPS.get(), EryopsRenderer::new);
        event.registerEntityRenderer(ModEntities.PULMONOSCORPIUS.get(), PulmonoscorpiusRenderer::new);
        event.registerEntityRenderer(ModEntities.CRASSIGYRINUS.get(), CrassigyrinusRenderer::new);
        event.registerEntityRenderer(ModEntities.OVERGROWN_NEXUS.get(), OvergrownNexusRenderer::new);
        event.registerEntityRenderer(ModEntities.DORGA_ARCHELAUS_TITANICA.get(), DorgaArchelausTitanicaRenderer::new);
        // 当たり判定の箱は見えない(F3+B の当たり判定表示でだけ見える)。
        event.registerEntityRenderer(ModEntities.TITANICA_HITBOX.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.SKY_BOSS_HITBOX.get(),
                net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntities.SIEGE_BOMB.get(), SiegeBombRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.OVERGROWN_LIANA.get(), context -> new CorruptedLianaRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.CLAPPER_ROPE.get(), ClapperRopeRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MORTAR.get(), MortarRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.WIND_VANE.get(), WindVaneRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.LEY_METER.get(), LeyMeterRenderer::new);
        // 羊・豚・クリーパーの死体は専用の GeckoLib 立体モデルで描画(近づかないと描画されない)。
        // 牛・ゾンビ・クモの死体は通常のブロックモデルのままで、この BER では描画しない。
        event.registerBlockEntityRenderer(ModBlockEntities.CORRUPTED_CORPSE.get(), context -> new CorruptedCorpseRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.LITHOPLEURA_HUSK.get(), context -> new LithopleuraHuskRenderer());
        event.registerBlockEntityRenderer(ModBlockEntities.BLIGHT_FERN_ROOTED.get(), context -> new BlightFernRootedRenderer());
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
        event.registerEntityRenderer(ModEntities.PARASITIZED_VILLAGER.get(), ParasitizedVillagerRenderer::new);
        event.registerEntityRenderer(ModEntities.PARASITIZED_CHICKEN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_chicken", 1.0F, 0.3F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_SKELETON.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_skeleton", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_ENDERMAN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_enderman", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_BABY_ZOMBIE.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_baby_zombie", 1.0F, 0.3F));
        // ネザーの媒介種(2026-10-09)。大きさ・影はバニラのレンダラーと同じ(ウィザースケルトン 1.2 倍、ガスト 4.5 倍)。
        event.registerEntityRenderer(ModEntities.PARASITIZED_PIGLIN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_piglin", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_PIGLIN_BRUTE.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_piglin_brute", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_ZOMBIFIED_PIGLIN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_zombified_piglin", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_WITHER_SKELETON.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_wither_skeleton", 1.2F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_HOGLIN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_hoglin", 1.0F, 0.7F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_ZOGLIN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_zoglin", 1.0F, 0.7F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_STRIDER.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_strider", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_GHAST.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_ghast", 4.5F, 1.5F, true));
        event.registerEntityRenderer(ModEntities.PARASITIZED_BLAZE.get(), ctx -> new G1GeoRenderer<>(ctx, new ParasitizedBlazeModel(), 1.0F, 0.5F));
        // 2026-10-10: アイアンゴーレム・ウォーデン(影はバニラのレンダラーと同じ 0.7・0.9)
        event.registerEntityRenderer(ModEntities.PARASITIZED_IRON_GOLEM.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_iron_golem", 1.0F, 0.7F));
        event.registerEntityRenderer(ModEntities.PARASITIZED_WARDEN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_warden", 1.0F, 0.9F));
        // G1 の作り直し 第 1 段(2026-10-22): 進化体 8 種。絵・geo・アニメは生成器 tools/g5-models/mobs/G1<名前>.java(2 段目はラフ)
        event.registerEntityRenderer(ModEntities.VINEARM_ZOMBIE.get(), ctx -> new G1GeoRenderer<>(ctx, "vinearm_zombie", 1.0F, 0.5F));
        event.registerEntityRenderer(ModEntities.HOLLOW_VILLAGER.get(), ctx -> new G1GeoRenderer<>(ctx, "hollow_villager", 0.9375F, 0.5F));
        // 胞子袋の牛は胞子嚢の中身だけ光る(sporesac_cow_glowmask.png、2026-10-23)。莢持ちは導火の膨張・白フラッシュ付き
        event.registerEntityRenderer(ModEntities.SPORESAC_COW.get(), ctx -> {
            G1GeoRenderer<com.yayuy.overgrownvegetation.entity.SporesacCow> r = new G1GeoRenderer<>(ctx, new OrganicMotionModel<>("sporesac_cow", new String[]{"belly_sacs"}, 0.03F, false, new String[0], 0), 1.0F, 0.7F);   // 10-24: 腹の袋が薄く脈打つ
            r.addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(r));
            return r;
        });
        event.registerEntityRenderer(ModEntities.PODDED_CREEPER.get(), PoddedCreeperRenderer::new);
        event.registerEntityRenderer(ModEntities.VINE_EXECUTIONER.get(), VineCastRenderer::new);   // 10-23: 引き寄せで左肩の束から蔦が伸びて相手に届く描画
        event.registerEntityRenderer(ModEntities.ROOT_STRIKER.get(), ctx -> new G1GeoRenderer<>(ctx, "root_striker", 1.0F, 0.6F));
        // 歩く胞子嚢(10-23 の仕上げ): 嚢の中身だけ光る・死ぬと横倒しにせず破裂のアニメ(burst)で崩れる
        event.registerEntityRenderer(ModEntities.WALKING_SPORANGIUM.get(), ctx -> {
            G1GeoRenderer<com.yayuy.overgrownvegetation.entity.WalkingSporangium> r = new G1GeoRenderer<>(ctx, new OrganicMotionModel<>("walking_sporangium",
                    new String[]{"crown_sac1", "crown_sac2", "crown_sac3", "crown_sac4", "crown_sac5", "crown_sac6", "back_sac1", "back_sac2", "belly_sac1"},
                    0.06F, true, new String[0], 0), 1.0F, 1.1F) {   // 10-24: 嚢が 1 つずつずれて脈打つ(傷が深いほど速く大きく)
                @Override
                protected float getDeathMaxRotation(com.yayuy.overgrownvegetation.entity.WalkingSporangium animatable) {
                    return 0;
                }

                // 破裂(死んだ姿)の間は被弾の赤を出さない(嚢が膨らんで弾けるのが赤で見えなくなる)
                @Override
                public int getPackedOverlay(com.yayuy.overgrownvegetation.entity.WalkingSporangium animatable, float u, float partialTick) {
                    return animatable.isDeadOrDying() ? net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY : super.getPackedOverlay(animatable, u, partialTick);
                }
            };
            r.addRenderLayer(new software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer<>(r));
            return r;
        });
        event.registerEntityRenderer(ModEntities.BURST_POD.get(), ctx -> new G1GeoRenderer<>(ctx, "burst_pod", 1.0F, 0.6F));
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

        // 布のグライダー: 開いている間は両腕を前に構えた形(クロスボウを構える形)に固める。歩きの腕の振りで手が動くと、
        // 手に付いた布(models/item/glider_open)が毎フレーム揺れて定まらないため(2026-10-22)。
        event.registerItem(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity,
                    net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack stack) {
                if (hand == net.minecraft.world.InteractionHand.MAIN_HAND && !entity.onGround() && !entity.isInWater()
                        && com.yayuy.overgrownvegetation.item.GliderItem.usable(stack)) {
                    return net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }
                return null;
            }
        }, ModItems.GLIDER.get());

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
        event.register((state, level, pos, tintIndex) -> climaxPlantTint(level, pos),
                ModBlocks.OVERGROWN_GRASS_BLOCK.get(),
                ModBlocks.OVERGROWN_GROWTH.get(),
                ModBlocks.OVERGROWN_BUSH.get());
    }

    /**
     * チャンクのメッシュを組むスレッドごとの「直前に色を出したブロック」(1 件だけ)。
     *
     * <p>2026-09-24 軽量化(監査 1428): 色のハンドラは tintindex の付いた<b>面ごと</b>に呼ばれる
     * (茂みは 10 面、下草は 4 面)ので、同じブロックで {@code getBiome} が面の数だけ走っていた。
     * 同じメッシュ組み(同じ {@code RenderChunkRegion})の同じ座標なら直前の色を返す。
     * RenderChunkRegion は組み直しのたびに新しく作られるので、バイオームが変わった後の組み直しで
     * 古い色を返すことは無い(同じブロックの面は元から同じ色)。メッシュ以外(崩れるブロック・破片など、
     * level が ClientLevel のもの)は覚えずに毎回引く。region は弱参照で持つ(ワーカースレッドに
     * 古いワールドを握らせない)。
     */
    private static final class ClimaxTintMemo {
        java.lang.ref.WeakReference<net.minecraft.world.level.BlockAndTintGetter> region =
                new java.lang.ref.WeakReference<>(null);
        long pos;
        int color;
    }

    private static final ThreadLocal<ClimaxTintMemo> CLIMAX_TINT_MEMO = ThreadLocal.withInitial(ClimaxTintMemo::new);

    private static int climaxPlantTint(@Nullable net.minecraft.world.level.BlockAndTintGetter level,
                                       @Nullable BlockPos pos) {
        if (pos == null || !(level instanceof net.minecraft.client.renderer.chunk.RenderChunkRegion)) {
            return climaxPlantTint(pos);
        }
        ClimaxTintMemo memo = CLIMAX_TINT_MEMO.get();
        long key = pos.asLong();
        if (memo.region.get() == level && memo.pos == key) {
            return memo.color;
        }
        int color = climaxPlantTint(pos);
        if (memo.region.get() != level) {
            memo.region = new java.lang.ref.WeakReference<>(level);
        }
        memo.pos = key;
        memo.color = color;
        return color;
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
                // 追加防具の見た目(2026-10-15)。
                renderer.addLayer(new ExtraArmorLayer(renderer, event.getEntityModels()));
            }
        }
    }
    private OvergrownVegetationClient() {
    }
}
