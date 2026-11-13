package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
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
 * 竹馬型の Mob の登録(2026-11-10。1 体目はフィロセンス)。共有の登録クラス(ModEntities・ModItems・ModEvents)は並行するセッションが
 * 同時に触るので、ModG1 と同じくここへ寄せた(入れ物の {@code DeferredRegister} はそれぞれのもの)。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(湧き方は司令塔が決める予定で未決、11-10)。湧くのはスポーンエッグ・コマンドだけ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModStilt {

    private ModStilt() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    public static final DeferredHolder<EntityType<?>, EntityType<PhyllosensEntity>> PHYLLOSENS =
            ModEntities.ENTITY_TYPES.register(PhyllosensEntity.ID, () -> EntityType.Builder
                    .<PhyllosensEntity>of(PhyllosensEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(PhyllosensEntity.ID, 1.5F), MobStats.height(PhyllosensEntity.ID, 1.5F))
                    .clientTrackingRange(10)
                    .build(PhyllosensEntity.ID));

    /** 段 2(11-14): フィロセンスの視線の印(能力値は変えない。PhyllosensWatchEffect)。色は花の芯の黄緑。 */
    public static final DeferredHolder<net.minecraft.world.effect.MobEffect, com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect> PHYLLOSENS_WATCH =
            ModMobEffects.MOB_EFFECTS.register("phyllosens_watch",
                    () -> new com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect(net.minecraft.world.effect.MobEffectCategory.HARMFUL, 0xACE300));

    /** スポーンエッグ(地は樹皮の茶、点は花の芯の黄緑)。 */
    public static final DeferredItem<DeferredSpawnEggItem> PHYLLOSENS_SPAWN_EGG = ModItems.ITEMS.registerItem(PhyllosensEntity.ID + "_spawn_egg",
            props -> new DeferredSpawnEggItem(PHYLLOSENS, 0x4A3B2A, 0xACE300, props));

    /** 植物側の Mob のタブへ卵を並べる({@code ModCreativeTabs} から)。 */
    public static void acceptEggs(CreativeModeTab.Output output) {
        output.accept(PHYLLOSENS_SPAWN_EGG.get());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PHYLLOSENS.get(), PhyllosensEntity.createAttributes().build());
    }
}
