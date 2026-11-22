package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
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
 * 監視塔フィロセンス(G4「森の目」、2026-11-14 に竹馬型から作り直し)の登録。共有の登録クラスは並行するセッションが触るので、
 * ModStilt・ModG1 と同じくここへ寄せた。{@link #init()} を MOD の構築子から呼ぶ。
 * 自然湧きは無い(植えるのは司令塔の持ち場。まだつないでいない)。湧くのはスポーンエッグ・コマンドだけ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModPhyllosens {

    private ModPhyllosens() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせる + 植物側を呼ぶ口を登録する)。 */
    public static void init() {
        com.yayuy.overgrownvegetation.entity.PhyllosensCall.register();
    }

    public static final DeferredHolder<EntityType<?>, EntityType<PhyllosensEntity>> PHYLLOSENS =
            ModEntities.ENTITY_TYPES.register(PhyllosensEntity.ID, () -> OvEntityType.Builder
                    .<PhyllosensEntity>of(PhyllosensEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(PhyllosensEntity.ID, 2.6F), MobStats.height(PhyllosensEntity.ID, 13.0F))
                    .clientTrackingRange(12)
                    .build(PhyllosensEntity.ID));

    /** フィロセンスの視線の印(能力値は変えない。PhyllosensWatchEffect)。色は花の芯の黄緑。11-14 に ModStilt から移した(id は同じ)。 */
    public static final DeferredHolder<MobEffect, PhyllosensWatchEffect> PHYLLOSENS_WATCH =
            ModMobEffects.MOB_EFFECTS.register("phyllosens_watch", () -> new PhyllosensWatchEffect(MobEffectCategory.HARMFUL, 0xACE300));

    /** スポーンエッグ(地は樹皮の茶、点は花の芯の黄緑)。竹馬の卵と同じ id(古いワールドの卵は監視塔の卵になる)。 */
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
