package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 「飛ぶ型」の Mob の登録(2026-11-15。1 体目はヴェロフィル。後でセミナリア・アクムネス・ペディキュラ)。
 * 共有の登録クラスは並行するセッションが同時に触るので、ModRunners と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧くのはスポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModFlyers {

    private ModFlyers() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯だけ(幅 1.2 × 高さ 0.8【仮】)。翼(翼幅 5.8)と頭は叩ける部位。空を飛ぶので遠くまで送る。 */
    public static final DeferredHolder<EntityType<?>, EntityType<VelophyllEntity>> VELOPHYLL =
            ModEntities.ENTITY_TYPES.register(VelophyllEntity.ID, () -> EntityType.Builder
                    .<VelophyllEntity>of(VelophyllEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(VelophyllEntity.ID, 1.2F), MobStats.height(VelophyllEntity.ID, 0.8F))
                    .spawnDimensionsScale(MobStats.spawnScale(VelophyllEntity.ID))
                    .eyeHeight(0.5F)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build(VelophyllEntity.ID));

    /** スポーンエッグ(地は翼膜の暗い緑、点は眼の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> VELOPHYLL_SPAWN_EGG = ModItems.ITEMS.registerItem(VelophyllEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(VELOPHYLL, 0x2E3A1C, 0xC8E03A, props));

    /** セミナリア(2026-11-15 段 1)。当たりは胸の芯だけ(幅 0.8 × 高さ 1.0【仮】)。翅(幅 4.7)と腹は叩ける部位。 */
    /** ヴェロフィルの胞子爆弾(11-15 能力の段)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.VelophyllBomb>> VELOPHYLL_BOMB =
            ModEntities.ENTITY_TYPES.register("velophyll_bomb", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.VelophyllBomb>of(com.yayuy.overgrownvegetation.entity.VelophyllBomb::new, MobCategory.MISC)
                    .sized(0.4F, 0.4F)
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(5)
                    .build("velophyll_bomb"));

    /** セミナリアの種(11-16 能力の段)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.SeminariaSeed>> SEMINARIA_SEED =
            ModEntities.ENTITY_TYPES.register("seminaria_seed", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.SeminariaSeed>of(com.yayuy.overgrownvegetation.entity.SeminariaSeed::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(5)
                    .build("seminaria_seed"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.SeminariaEntity>> SEMINARIA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.SeminariaEntity>of(com.yayuy.overgrownvegetation.entity.SeminariaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID, 0.8F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID, 1.0F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID))
                    .eyeHeight(0.6F)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build(com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID));

    /** スポーンエッグ(地は樹皮の暗い緑、点は松かさの隙間の赤紫)。 */
    public static final DeferredItem<DeferredSpawnEggItem> SEMINARIA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.SeminariaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(SEMINARIA, 0x3A4A1E, 0x5A1838, props));

    /** ステノディクティア(G2 成虫、2026-11-16 段 1)。当たりは胴の芯 0.6 × 0.4(翅・脚ははみ出す)。生息しているだけの生き物なので CREATURE。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity>> STENODICTYA_LOBATA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity>of(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID, 0.6F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID, 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID))
                    .eyeHeight(0.25F)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID));

    /** スポーンエッグ(地は汚染シダの暗い緑、点は翅の脈の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> STENODICTYA_LOBATA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(STENODICTYA_LOBATA, 0x2E4A1A, 0x9CB83A, props));

    /** ペディキュラ(2026-11-15 段 1、漂う型)。当たりは塊の真ん中(3.6 × 3.6、旧版の値)。高い空に浮くので遠くまで送る。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PediculaEntity>> PEDICULA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.PediculaEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.PediculaEntity>of(com.yayuy.overgrownvegetation.entity.PediculaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.PediculaEntity.ID, 3.6F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.PediculaEntity.ID, 3.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.PediculaEntity.ID))
                    .eyeHeight(1.8F)
                    .clientTrackingRange(16)
                    .updateInterval(4)
                    .build(com.yayuy.overgrownvegetation.entity.PediculaEntity.ID));

    /** スポーンエッグ(地は膜の灰がかった緑、点は芯の赤紫)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PEDICULA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.PediculaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PEDICULA, 0x8A9A78, 0x6A2848, props));

    /** アクムネス(2026-11-15 段 1、止まっては飛ぶトンボ型)。当たりは胸と垂らした脚の芯(1.2 × 3.0【仮】)。翅・頭・腹は叩ける部位。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.AcumnesEntity>> ACUMNES =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.AcumnesEntity>of(com.yayuy.overgrownvegetation.entity.AcumnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID, 1.2F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID, 3.0F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID))
                    .eyeHeight(2.5F)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build(com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID));

    /** スポーンエッグ(地は体の暗い緑、点は眼の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> ACUMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.AcumnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(ACUMNES, 0x2A3418, 0xB8D838, props));

    /** アクムネスの棘(11-15 能力の段。狙撃・縫い止め・散弾で共用)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.AcumnesSpine>> ACUMNES_SPINE =
            ModEntities.ENTITY_TYPES.register("acumnes_spine", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.AcumnesSpine>of(com.yayuy.overgrownvegetation.entity.AcumnesSpine::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("acumnes_spine"));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(VELOPHYLL_SPAWN_EGG.get());
        output.accept(SEMINARIA_SPAWN_EGG.get());
        output.accept(STENODICTYA_LOBATA_SPAWN_EGG.get());
        output.accept(PEDICULA_SPAWN_EGG.get());
        output.accept(ACUMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(VELOPHYLL.get(), VelophyllEntity.createAttributes().build());
        event.put(SEMINARIA.get(), com.yayuy.overgrownvegetation.entity.SeminariaEntity.createAttributes().build());
        event.put(STENODICTYA_LOBATA.get(), com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity.createAttributes().build());
        event.put(PEDICULA.get(), com.yayuy.overgrownvegetation.entity.PediculaEntity.createAttributes().build());
        event.put(ACUMNES.get(), com.yayuy.overgrownvegetation.entity.AcumnesEntity.createAttributes().build());
    }
}
