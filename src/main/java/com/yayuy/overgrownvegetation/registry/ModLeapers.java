package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.registries.DeferredSpawnEggItem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredItem;

/**
 * 「跳ぶ型」の Mob の登録(2026-11-14。1 体目はサルティムネス)。共有の登録クラスは並行するセッションが同時に触るので、
 * ModRunners と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。自然湧きは無い(スポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModLeapers {

    private ModLeapers() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯だけ(幅 1.8 × 高さ 1.0【仮】)。脚・翅・頭・腹ははみ出す(伏せた姿の全体 4.5 × 2.3 × 7.2)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<SaltimnesEntity>> SALTIMNES =
            ModEntities.ENTITY_TYPES.register(SaltimnesEntity.ID, () -> EntityType.Builder
                    .<SaltimnesEntity>of(SaltimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(SaltimnesEntity.ID, 1.8F), MobStats.height(SaltimnesEntity.ID, 1.0F))
                    .spawnDimensionsScale(MobStats.spawnScale(SaltimnesEntity.ID))
                    .eyeHeight(0.8F)
                    .clientTrackingRange(10)
                    .build(SaltimnesEntity.ID));

    /** スポーンエッグ(地は苔の暗い緑、点は口の奥の赤紫)。 */
    public static final DeferredItem<DeferredSpawnEggItem> SALTIMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(SaltimnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(SALTIMNES, 0x3A4220, 0x6A2450, props));

    /** ファランギオタルブス(狩る側の環境生物、11-16 段 1)。当たりは胴の芯 1.0 × 0.8【仮】(脚ははみ出す。全体 2.28 × 1.55 × 0.80)。地上は小さな地這いの虫の型。 */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity>> PHALANGIOTARBUS =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity>of(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity::new, MobCategory.CREATURE)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID, 1.0F), MobStats.height(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID, 0.8F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID))
                    .eyeHeight(0.5F)
                    .clientTrackingRange(8)
                    .build(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID));

    /** スポーンエッグ(地は樹皮の暗い緑、点は形成層の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PHALANGIOTARBUS_SPAWN_EGG = ModItems.ITEMS.registerItem(com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PHALANGIOTARBUS, 0x2F3A1C, 0x8A9A3A, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(SALTIMNES_SPAWN_EGG.get());
        output.accept(PHALANGIOTARBUS_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(SALTIMNES.get(), SaltimnesEntity.createAttributes().build());
        event.put(PHALANGIOTARBUS.get(), com.yayuy.overgrownvegetation.entity.PhalangiotarbusEntity.createAttributes().build());
    }
}
