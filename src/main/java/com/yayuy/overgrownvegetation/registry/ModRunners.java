package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
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
 * 「② 大型の地上を走る型」の Mob の登録(2026-11-14。1 体目はヴェナムネス。次はクリペイムネス・スクティムネスの予定)。
 * 共有の登録クラスは並行するセッションが同時に触るので、ModStilt と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧くのはスポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModRunners {

    private ModRunners() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯だけ(幅 1.2 × 高さ 2.4【仮】)。首・頭・尾ははみ出す(全体 2.2 × 3.1 × 8.1)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<VenamnesEntity>> VENAMNES =
            ModEntities.ENTITY_TYPES.register(VenamnesEntity.ID, () -> EntityType.Builder
                    .<VenamnesEntity>of(VenamnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(VenamnesEntity.ID, 1.2F), MobStats.height(VenamnesEntity.ID, 2.4F))
                    .spawnDimensionsScale(MobStats.spawnScale(VenamnesEntity.ID))
                    .eyeHeight(2.2F)
                    .clientTrackingRange(10)
                    .build(VenamnesEntity.ID));

    /** スポーンエッグ(地は樹皮の焦げ茶、点は口の中の暗い赤)。 */
    public static final DeferredItem<DeferredSpawnEggItem> VENAMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(VenamnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(VENAMNES, 0x3B2E22, 0x7A1E1A, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(VENAMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(VENAMNES.get(), VenamnesEntity.createAttributes().build());
    }
}
