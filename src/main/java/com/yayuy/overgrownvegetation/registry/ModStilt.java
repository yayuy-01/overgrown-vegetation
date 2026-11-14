package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.StiltPhyllosensEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * 竹馬型の Mob の登録(2026-11-10。1 体目はフィロセンスの竹馬の体)。共有の登録クラス(ModEntities・ModItems・ModEvents)は並行するセッションが
 * 同時に触るので、ModG1 と同じくここへ寄せた(入れ物の {@code DeferredRegister} はそれぞれのもの)。{@link #init()} を MOD の構築子から呼ぶ。
 *
 * <p><b>11-14 から内部用</b>: フィロセンスは「監視塔」に作り直した(id {@code phyllosens} は監視塔 = {@link ModPhyllosens})。竹馬の体のコード
 * ({@code entity/stilt/}・{@code StiltPhyllosens*})は後で竹馬型の Mob を足すときに使う(ユーザー決定)ので、GameTest が通り続けるように
 * 内部用の id {@code phyllosens_stilt} で登録だけ残す。スポーンエッグ無し・グループのタグ無し・自然湧き無し・クリエイティブのタブに出ない。
 * 出せるのは {@code /summon overgrown_vegetation:phyllosens_stilt} と GameTest だけ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModStilt {

    private ModStilt() {
    }

    /** MOD の構築子から呼ぶ(このクラスの static の登録を走らせるだけ)。 */
    public static void init() {
    }

    /** 竹馬の体(内部用。11-14 まではこれが {@code phyllosens} だった)。 */
    public static final DeferredHolder<EntityType<?>, EntityType<StiltPhyllosensEntity>> PHYLLOSENS_STILT =
            ModEntities.ENTITY_TYPES.register(StiltPhyllosensEntity.ID, () -> EntityType.Builder
                    .<StiltPhyllosensEntity>of(StiltPhyllosensEntity::new, MobCategory.MONSTER)
                    .sized(MobStats.width(StiltPhyllosensEntity.ID, 1.5F), MobStats.height(StiltPhyllosensEntity.ID, 1.5F))
                    .clientTrackingRange(10)
                    .build(StiltPhyllosensEntity.ID));

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PHYLLOSENS_STILT.get(), StiltPhyllosensEntity.createAttributes().build());
    }
}
