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

    /** スポーンエッグ(地は苔の暗い緑、点は棘の付け根の脈の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PILIMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(PilimnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PILIMNES, 0x33401E, 0xB8D82A, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(PILIMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PILIMNES.get(), PilimnesEntity.createAttributes().build());
    }
}
