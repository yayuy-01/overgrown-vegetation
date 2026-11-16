package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.PilimnesEntity;
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
 * 「トカゲ型」の Mob の登録(2026-11-15。1 体目はピリムネス。次はシメリカワ(モデルの作り直しの後))。
 * 共有の登録クラスは並行するセッションが同時に触るので、ModRunners と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧くのはスポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModLizards {

    private ModLizards() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯だけ(幅 1.3 × 高さ 1.6【仮】)。首・頭・尾・横へ張った脚・背の棘ははみ出す(全体 約 3.7 × 3.4 × 9.5)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<PilimnesEntity>> PILIMNES =
            ModEntities.ENTITY_TYPES.register(PilimnesEntity.ID, () -> EntityType.Builder
                    .<PilimnesEntity>of(PilimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(PilimnesEntity.ID, 1.3F), MobStats.height(PilimnesEntity.ID, 1.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(PilimnesEntity.ID))
                    .eyeHeight(1.5F)
                    .clientTrackingRange(10)
                    .build(PilimnesEntity.ID));

    /** ピリムネスが弾き飛ばす棘(能力の段 11-16)。小さな当たり・毎 tick 送る(速い弾)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PilimnesSpine>> PILIMNES_SPINE =
            ModEntities.ENTITY_TYPES.register("pilimnes_spine", () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.PilimnesSpine>of(com.yayuy.overgrownvegetation.entity.PilimnesSpine::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .build("pilimnes_spine"));

    /** スポーンエッグ(地は苔の暗い緑、点は棘の付け根の脈の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PILIMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(PilimnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PILIMNES, 0x33401E, 0xB8D82A, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(PILIMNES_SPAWN_EGG.get());
        output.accept(PETROLACOSAURUS_SPAWN_EGG.get());
        output.accept(IANTHASAURUS_SPAWN_EGG.get());
    }

    // --- G3 のトカゲ 2 種(2026-11-16 段 1。トカゲ型を当てた。自然には湧かない = 生態系の台帳につなぐ予定)

    /** ペトロラコサウルス: 当たりは 0.6 × 0.4(体格 2 が掛かって 1.2 × 0.8。10-10 のまま)。尾ははみ出す(全長 4.70)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity>> PETROLACOSAURUS =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity>of(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID, 0.6F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID, 0.4F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(9)
                    .build(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID));

    /** イアンタサウルス: 当たりは 0.7 × 0.6(帆込み。体格 2 が掛かって 1.4 × 1.2。10-10 のまま)。尾ははみ出す(全長 4.21)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.IanthasaurusEntity>> IANTHASAURUS =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.IanthasaurusEntity>of(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID, 0.7F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID, 0.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(9)
                    .build(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID));

    /** 卵(地は樹皮の茶、点は腹の明るい色)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PETROLACOSAURUS_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PETROLACOSAURUS, 0x5A4A30, 0xA08A5A, props));
    public static final DeferredItem<DeferredSpawnEggItem> IANTHASAURUS_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(IANTHASAURUS, 0x4A5A36, 0xA0583A, props));

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PILIMNES.get(), PilimnesEntity.createAttributes().build());
        event.put(PETROLACOSAURUS.get(), com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.createAttributes().build());
        event.put(IANTHASAURUS.get(), com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.createAttributes().build());
    }
}
