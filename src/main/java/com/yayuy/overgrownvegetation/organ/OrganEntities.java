package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 森の器官の実体(2026-11-17)。共有の ModEntities を太らせないよう、ここに自分の登録の口を持つ({@link OrganBlocks#register} から)。 */
public final class OrganEntities {
    private OrganEntities() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, OvergrownVegetation.MODID);

    /** 動く蔦の触手。 */
    public static final DeferredHolder<EntityType<?>, EntityType<OrganTentacle>> TENTACLE = ENTITY_TYPES.register("organ_tentacle",
            () -> EntityType.Builder.<OrganTentacle>of(OrganTentacle::new, MobCategory.MISC)
                    .sized(0.8F, 0.8F)
                    .clientTrackingRange(10)
                    .updateInterval(20)
                    .fireImmune()
                    .build("organ_tentacle"));

    static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
