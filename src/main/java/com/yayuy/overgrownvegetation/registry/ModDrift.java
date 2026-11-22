package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.drift.DriftMimicEntity;
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
 * 漂流する擬態 Mob の登録(2026-11-20)。共有の登録クラスは並行するセッションが触るので、ModFlyers と同じくここへ寄せた。
 * {@link #init()} を MOD の構築子から呼ぶ。自然湧きは無い(森が汚染された岸から流す・卵・コマンド)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModDrift {

    private ModDrift() {
    }

    public static void init() {
    }

    /** 当たりは真ん中の胞子嚢の塊だけ(幅 4.5 × 高さ 1.2。ユーザー決定: 塊が弱点・塊を壊す = 倒す。形は幅 約 15 で、傘の縁・水中の房ははみ出す)。海の上を遠くまで流れるので遠くまで送る。 */
    public static final DeferredHolder<EntityType<?>, EntityType<DriftMimicEntity>> DRIFT_MIMIC =
            ModEntities.ENTITY_TYPES.register(DriftMimicEntity.ID, () -> EntityType.Builder
                    .<DriftMimicEntity>of(DriftMimicEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(DriftMimicEntity.ID, 4.5F), MobStats.height(DriftMimicEntity.ID, 1.2F))
                    .spawnDimensionsScale(MobStats.spawnScale(DriftMimicEntity.ID))
                    .eyeHeight(0.9F)
                    .clientTrackingRange(12)
                    .updateInterval(3)
                    .build(DriftMimicEntity.ID));

    /** スポーンエッグ(地は流木の灰茶、点は藻の暗い緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> DRIFT_MIMIC_SPAWN_EGG = ModItems.ITEMS.registerItem(DriftMimicEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(DRIFT_MIMIC, 0x6E6250, 0x2E4A22, props));

    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(DRIFT_MIMIC_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(DRIFT_MIMIC.get(), DriftMimicEntity.createAttributes().build());
    }
}
