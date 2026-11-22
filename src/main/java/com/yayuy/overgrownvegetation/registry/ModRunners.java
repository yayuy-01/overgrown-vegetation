package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.ClipeimnesEntity;
import com.yayuy.overgrownvegetation.entity.VenamnesEntity;
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

    /** 当たりは胴の芯だけ(幅 1.4 × 高さ 2.6【仮】)。盾の腕・脚・尾ははみ出す(全体 4.45 × 3.43 × 8.58)。2026-11-15 段 1。 */
    public static final DeferredHolder<EntityType<?>, EntityType<ClipeimnesEntity>> CLIPEIMNES =
            ModEntities.ENTITY_TYPES.register(ClipeimnesEntity.ID, () -> EntityType.Builder
                    .<ClipeimnesEntity>of(ClipeimnesEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(ClipeimnesEntity.ID, 1.4F), MobStats.height(ClipeimnesEntity.ID, 2.6F))
                    .spawnDimensionsScale(MobStats.spawnScale(ClipeimnesEntity.ID))
                    .eyeHeight(2.4F)
                    .clientTrackingRange(10)
                    .build(ClipeimnesEntity.ID));

    /** スポーンエッグ(地は樹皮の暗い緑、点は構えで光る葉脈の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> CLIPEIMNES_SPAWN_EGG = ModItems.ITEMS.registerItem(ClipeimnesEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(CLIPEIMNES, 0x3A4220, 0xB8D838, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(VENAMNES_SPAWN_EGG.get());
        output.accept(CLIPEIMNES_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(VENAMNES.get(), VenamnesEntity.createAttributes().build());
        event.put(CLIPEIMNES.get(), ClipeimnesEntity.createAttributes().build());
    }
}
