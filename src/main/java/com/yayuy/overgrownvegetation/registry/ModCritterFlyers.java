package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.MeganeuraEntity;
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
 * 環境生物の飛ぶ種の登録(2026-11-16。1 体目はメガネウラ)。「飛ぶ型」の土台は {@code entity/flyer/}。
 * 共有の {@code ModFlyers} はほかのセッション(ステノディクティア)が書いている途中だったので、ぶつからないよう分けた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(生態系の台帳 {@code ecosystem/EcoSpawns} へ後でつなぐ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCritterFlyers {

    private ModCritterFlyers() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯(幅 1.0 × 高さ 0.5【仮】。退避前と同じ)。翅(翼幅 約 2.2)は厚み 0 の板で、叩ける部位は無い。 */
    public static final DeferredHolder<EntityType<?>, EntityType<MeganeuraEntity>> MEGANEURA =
            ModEntities.ENTITY_TYPES.register(MeganeuraEntity.ID, () -> OvEntityType.Builder
                    .<MeganeuraEntity>of(MeganeuraEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(MeganeuraEntity.ID, 1.0F), MobStats.height(MeganeuraEntity.ID, 0.5F))
                    .spawnDimensionsScale(MobStats.spawnScale(MeganeuraEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(MeganeuraEntity.ID));

    /** スポーンエッグ(地は胴の暗い緑、点は翅の黄色)。 */
    public static final DeferredItem<DeferredSpawnEggItem> MEGANEURA_SPAWN_EGG = ModItems.ITEMS.registerItem(MeganeuraEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(MEGANEURA, 0x2F3A1A, 0xD8B23A, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(MEGANEURA_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(MEGANEURA.get(), MeganeuraEntity.createAttributes().build());
    }
}
