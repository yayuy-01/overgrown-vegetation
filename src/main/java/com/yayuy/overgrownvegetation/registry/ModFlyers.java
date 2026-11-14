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

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(VELOPHYLL_SPAWN_EGG.get());
        output.accept(ACUMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(VELOPHYLL.get(), VelophyllEntity.createAttributes().build());
        event.put(ACUMNES.get(), com.yayuy.overgrownvegetation.entity.AcumnesEntity.createAttributes().build());
    }
}
