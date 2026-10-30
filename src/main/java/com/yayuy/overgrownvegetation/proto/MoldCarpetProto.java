package com.yayuy.overgrownvegetation.proto;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * G6 の B「粘菌の絨毯」の<b>試作</b>(2026-10-08)。ブロックの面に動く網の絵を貼って、核について這わせる見た目だけを確かめる。
 * 既存の登録ファイル(ModEntities など)には手を入れず、ここで {@link RegisterEvent} から直接登録する(試作を消すときはこのパッケージを消すだけ)。
 * 出し方: {@code /summon overgrown_vegetation:mold_carpet_proto}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class MoldCarpetProto {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "mold_carpet_proto");
    /** 網の絵(ブロックのアトラスに入る。.mcmeta で動く)。 */
    public static final ResourceLocation NET_SPRITE = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "block/mold_net_proto");

    public static EntityType<MoldCarpetProtoEntity> TYPE;

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, helper -> {
            // 当たり判定は核だけ(網は描くだけで判定を持たない)
            TYPE = EntityType.Builder.of(MoldCarpetProtoEntity::new, MobCategory.MISC)
                    .sized(0.35F, 0.2F)
                    .clientTrackingRange(8)
                    .build(ID.toString());
            helper.register(ID, TYPE);
        });
    }

    @SubscribeEvent
    public static void attributes(EntityAttributeCreationEvent event) {
        event.put(TYPE, MoldCarpetProtoEntity.attributes().build());
    }

    private MoldCarpetProto() {
    }
}
