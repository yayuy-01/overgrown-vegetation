package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.SaltimnesEntity;
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

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(SALTIMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(SALTIMNES.get(), SaltimnesEntity.createAttributes().build());
    }
}
