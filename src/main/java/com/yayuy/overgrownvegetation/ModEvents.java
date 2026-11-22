package com.yayuy.overgrownvegetation;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
public final class ModEvents {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.BLIGHT_FERN.get(), BlightFernEntity.createAttributes().build());
        event.put(ModEntities.HIVE_NODE.get(), HiveNodeEntity.createAttributes().build());
        event.put(ModEntities.FALCIMNES.get(), FalcimnesEntity.createAttributes().build());
        event.put(ModEntities.ARBORA.get(), com.yayuy.overgrownvegetation.entity.ArboraEntity.createAttributes().build());

    }

    private ModEvents() {
    }
}
