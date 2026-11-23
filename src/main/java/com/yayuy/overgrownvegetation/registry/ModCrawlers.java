package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.registries.DeferredSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;
import com.yayuy.overgrownvegetation.port.entity.OvEntityType;

/**
 * 「小さな地這いの虫」の型の Mob の登録(2026-11-15。1 体目はグレガリア。次はストロビラ・ヴェシカリア・トリツキの予定)。
 * 共有の登録クラスは並行するセッションが同時に触るので、ModRunners と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧くのはスポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCrawlers {

    private ModCrawlers() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /**
     * ヴェシカリア(11-15)。当たりは腹の袋の芯だけ(幅 0.8 × 高さ 0.6【仮】)。脚・前脚・鋏角ははみ出す(全体 1.1 × 0.73 × 2.8)。
     * 1 マス幅・高さ 1 の隙間をくぐれる。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.VesicariaEntity>> VESICARIA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.VesicariaEntity>of(com.yayuy.overgrownvegetation.entity.VesicariaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID, 0.8F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID, 0.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID))
                    .eyeHeight(0.35F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID));

    /** ヴェシカリアのスポーンエッグ(地は鱗の暗い樹皮、点は袋の脈の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> VESICARIA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.VesicariaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(VESICARIA, 0x3A2E1E, 0xA4E100, props));

    /** 当たりは胴の芯だけ(幅 0.7 × 高さ 0.45【仮】)。脚・触角ははみ出す(全体 1.0 × 0.43 × 2.2)。根の下・1 マス幅の隙間をくぐれる。 */
    public static final DeferredHolder<EntityType<?>, EntityType<GregariaEntity>> GREGARIA =
            ModEntities.ENTITY_TYPES.register(GregariaEntity.ID, () -> OvEntityType.Builder
                    .<GregariaEntity>of(GregariaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(GregariaEntity.ID, 0.7F), MobStats.height(GregariaEntity.ID, 0.45F))
                    .spawnDimensionsScale(MobStats.spawnScale(GregariaEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(8)
                    .build(GregariaEntity.ID));

    /** スポーンエッグ(地は腹の鱗の暗い緑、点は鱗の筋の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> GREGARIA_SPAWN_EGG = ModItems.ITEMS.registerItem(GregariaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(GREGARIA, 0x2E3A16, 0x9AC81E, props));

    /**
     * ストロビラ(11-15)。当たりは胴と背板の芯だけ(幅 0.95 × 高さ 1.2【仮】)。脚・触角・尾ははみ出す(全体 3.6 × 2.8 × 5.5)。
     * 1 マス幅の通路は通れるが、高さ 1 の隙間はくぐらない(尾が天井を突き抜けないように)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StrobilaEntity>> STROBILA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StrobilaEntity>of(com.yayuy.overgrownvegetation.entity.StrobilaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, 0.95F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, 1.2F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID))
                    .eyeHeight(0.7F)
                    .clientTrackingRange(10)
                    .build(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID));

    /** ストロビラのスポーンエッグ(地は背板の暗いオリーブ、点は球果の黄土)。 */
    public static final DeferredItem<DeferredSpawnEggItem> STROBILA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(STROBILA, 0x3A3F14, 0x8C7A2E, props));

    /** ストロビラが山なりに投げる球果(11-15 段 2)。{@link com.yayuy.overgrownvegetation.entity.StrobilaCone}。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StrobilaCone>> STROBILA_CONE =
            ModEntities.ENTITY_TYPES.register("strobila_cone", () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StrobilaCone>of(com.yayuy.overgrownvegetation.entity.StrobilaCone::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .noSummon()
                    .clientTrackingRange(6)
                    .updateInterval(10)
                    .build("strobila_cone"));

    /**
     * ゲスタトリクス(G4・歩く苗床。2026-11-17)。当たりは胴と背の嚢の芯 1.6 × 1.0【仮】(大きさは G3・G4 の格)。脚ははみ出す。
     * モデル制作(Fable)の形が来るまで、描画はグレガリアの形を大きくした仮のモデル。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.GestatrixEntity>> GESTATRIX =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.GestatrixEntity>of(com.yayuy.overgrownvegetation.entity.GestatrixEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID, 1.6F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID, 1.0F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID))
                    .eyeHeight(0.7F)
                    .clientTrackingRange(10)
                    .build(com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID));

    /** ゲスタトリクスのスポーンエッグ(地は樹皮の焦げ茶、点は背の嚢の黄緑)【仮】。 */
    public static final DeferredItem<DeferredSpawnEggItem> GESTATRIX_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.GestatrixEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(GESTATRIX, 0x3A2A1C, 0xB4D23C, props));

    // --- 環境生物の作り直し 段 1(2026-11-16): 小さな地這いの虫の型で歩くだけ。湧きは生態系の台帳(ecosystem/life/EcoDens)へ後でつなぐ。区分は CREATURE ---

    /** プルモノスコルピウス(狩る側の環境生物)。当たりは胴の芯 1.0 × 0.6(尾・脚・鋏ははみ出す。全体 2.46 × 1.11 × 1.04)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity>> PULMONOSCORPIUS =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity>of(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID, 1.0F), MobStats.height(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID, 0.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID))
                    .eyeHeight(0.4F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID));
    /** スポーンエッグ(地は樹皮の焦げ茶、点は形成層の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PULMONOSCORPIUS_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PULMONOSCORPIUS, 0x3A2E1C, 0x6B6A2A, props));

    /** プロトファスマ(獲物の環境生物)。当たりは胴の芯 0.5 × 0.5(脚・触角ははみ出す。全体 1.91 × 0.77 × 0.46)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ProtophasmaEntity>> PROTOPHASMA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.ProtophasmaEntity>of(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID, 0.5F), MobStats.height(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID, 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID));
    /** スポーンエッグ(地は汚染シダの暗い緑、点は脚の茶)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PROTOPHASMA_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PROTOPHASMA, 0x2F4A1C, 0x7A5C34, props));

    /** ステノディクティアの幼虫(獲物の環境生物、2026-11-17 段 1。梢を歩く)。当たりは胴の芯 0.45 × 0.3(0.7 倍に作り直した形。全長 1.11)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity>> STENODICTYA_NYMPH =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity>of(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID, 0.45F), MobStats.height(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID, 0.3F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID))
                    .eyeHeight(0.2F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID));
    /** スポーンエッグ(地は汚染シダの暗い緑、点は翅芽の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> STENODICTYA_NYMPH_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(STENODICTYA_NYMPH, 0x2A4418, 0x8EA834, props));

    /** 陸生カタツムリ(獲物の環境生物。id は giant_snail のまま)。当たりは 0.7 × 0.9(MobStats の値)。全体 1.34 × 0.69 × 0.89。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.GiantSnailEntity>> GIANT_SNAIL =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.GiantSnailEntity>of(com.yayuy.overgrownvegetation.entity.GiantSnailEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID, 0.7F), MobStats.height(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID, 0.9F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID))
                    .eyeHeight(0.7F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID));
    /** スポーンエッグ(地は樹皮の焦げ茶、点は髄の淡い色)。 */
    public static final DeferredItem<DeferredSpawnEggItem> GIANT_SNAIL_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.GiantSnailEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(GIANT_SNAIL, 0x3A2E1C, 0x8C8250, props));

    /** アースロプレウラ(獲物の環境生物)。当たりは胴の芯 1.1 × 0.55(MobStats の値。頭・脚・後ろの節ははみ出す。全体 3.62 × 1.24 × 0.40)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.ArthropleuraEntity>> ARTHROPLEURA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID, () -> OvEntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.ArthropleuraEntity>of(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID, 1.1F), MobStats.height(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID, 0.55F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID))
                    .eyeHeight(0.35F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID));
    /** スポーンエッグ(地は樹皮の焦げ茶、点は形成層の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> ARTHROPLEURA_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(ARTHROPLEURA, 0x33281A, 0x7E8A2C, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(VESICARIA_SPAWN_EGG.get());
        output.accept(GREGARIA_SPAWN_EGG.get());
        output.accept(STROBILA_SPAWN_EGG.get());
        output.accept(GESTATRIX_SPAWN_EGG.get());
        output.accept(PULMONOSCORPIUS_SPAWN_EGG.get());
        output.accept(PROTOPHASMA_SPAWN_EGG.get());
        output.accept(STENODICTYA_NYMPH_SPAWN_EGG.get());
        output.accept(GIANT_SNAIL_SPAWN_EGG.get());
        output.accept(ARTHROPLEURA_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(VESICARIA.get(), com.yayuy.overgrownvegetation.entity.VesicariaEntity.createAttributes().build());
        event.put(GREGARIA.get(), GregariaEntity.createAttributes().build());
        event.put(STROBILA.get(), com.yayuy.overgrownvegetation.entity.StrobilaEntity.createAttributes().build());
        event.put(GESTATRIX.get(), com.yayuy.overgrownvegetation.entity.GestatrixEntity.createAttributes().build());
        event.put(PULMONOSCORPIUS.get(), com.yayuy.overgrownvegetation.entity.PulmonoscorpiusEntity.createAttributes().build());
        event.put(PROTOPHASMA.get(), com.yayuy.overgrownvegetation.entity.ProtophasmaEntity.createAttributes().build());
        event.put(STENODICTYA_NYMPH.get(), com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.createAttributes().build());
        event.put(GIANT_SNAIL.get(), com.yayuy.overgrownvegetation.entity.GiantSnailEntity.createAttributes().build());
        event.put(ARTHROPLEURA.get(), com.yayuy.overgrownvegetation.entity.ArthropleuraEntity.createAttributes().build());
    }
}
