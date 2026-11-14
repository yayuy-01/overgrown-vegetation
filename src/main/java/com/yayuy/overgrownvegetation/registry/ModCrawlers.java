package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.GregariaEntity;
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
 * 「小さな地這いの虫」の型の Mob の登録(2026-11-15。1 体目はグレガリア。次はストロビラ・ヴェシカリア・トリツキの予定)。
 * 共有の登録クラスは並行するセッションが同時に触るので、ModRunners と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧くのはスポーンエッグ・コマンドだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModCrawlers {

    private ModCrawlers() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 当たりは胴の芯だけ(幅 0.7 × 高さ 0.45【仮】)。脚・触角ははみ出す(全体 1.0 × 0.43 × 2.2)。根の下・1 マス幅の隙間をくぐれる。 */
    public static final DeferredHolder<EntityType<?>, EntityType<GregariaEntity>> GREGARIA =
            ModEntities.ENTITY_TYPES.register(GregariaEntity.ID, () -> EntityType.Builder
                    .<GregariaEntity>of(GregariaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(GregariaEntity.ID, 0.7F), MobStats.height(GregariaEntity.ID, 0.45F))
                    .spawnDimensionsScale(MobStats.spawnScale(GregariaEntity.ID))
                    .eyeHeight(0.3F)
                    .clientTrackingRange(8)
                    .build(GregariaEntity.ID));

    /** スポーンエッグ(地は腹の鱗の暗い緑、点は鱗の筋の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> GREGARIA_SPAWN_EGG = ModItems.ITEMS.registerItem(GregariaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(GREGARIA, 0x2E3A16, 0x9AC81E, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(GREGARIA_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(GREGARIA.get(), GregariaEntity.createAttributes().build());
    }
}
    /**
     * ストロビラ(11-15)。当たりは胴と背板の芯だけ(幅 0.95 × 高さ 1.2【仮】)。脚・触角・尾ははみ出す(全体 3.6 × 2.8 × 5.5)。
     * 1 マス幅の通路は通れるが、高さ 1 の隙間はくぐらない(尾が天井を突き抜けないように)。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.yayuy.overgrownvegetation.entity.StrobilaEntity>> STROBILA =
            ModEntities.ENTITY_TYPES.register(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, () -> EntityType.Builder
                    .<com.yayuy.overgrownvegetation.entity.StrobilaEntity>of(com.yayuy.overgrownvegetation.entity.StrobilaEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, 0.95F),
                            MobStats.height(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID, 1.2F))
                    .spawnDimensionsScale(MobStats.spawnScale(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID))
                    .eyeHeight(0.7F)
                    .clientTrackingRange(10)
                    .build(com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID));

    /** ストロビラのスポーンエッグ(地は背板の暗いオリーブ、点は球果の黄土)。 */
    public static final DeferredItem<DeferredSpawnEggItem> STROBILA_SPAWN_EGG = ModItems.ITEMS.registerItem(
            com.yayuy.overgrownvegetation.entity.StrobilaEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(STROBILA, 0x3A3F14, 0x8C7A2E, props));

        output.accept(STROBILA_SPAWN_EGG.get());
        event.put(STROBILA.get(), com.yayuy.overgrownvegetation.entity.StrobilaEntity.createAttributes().build());
