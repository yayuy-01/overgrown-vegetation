package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OvergrownVegetation.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + OvergrownVegetation.MODID + ".main"))
                    .icon(() -> new ItemStack(ModBlocks.OVERGROWN_BLOCK.get()))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .displayItems((params, output) -> {
                        ModBlocks.displayOrder().forEach(block -> output.accept(block.get()));
                        output.accept(ModItems.RESEARCH_TABLE_ITEM.get());
                        output.accept(ModItems.GIANT_OVERGROWN_SAPLING_ITEM.get());
                        output.accept(ModItems.HIVE_NODE_SPAWN_EGG.get());
                        output.accept(ModItems.BLIGHT_FERN_SPAWN_EGG.get());
                        output.accept(ModItems.BLIGHT_WATER_GRASS_SPAWN_EGG.get());
                        output.accept(ModItems.OVERGROWN_SHIELDER_SPAWN_EGG.get());
                        output.accept(ModItems.FALCIMNES_SPAWN_EGG.get());
                        output.accept(ModItems.PHALANGIOTARBUS_SPAWN_EGG.get());
                        output.accept(ModItems.PROTOPHASMA_SPAWN_EGG.get());
                        output.accept(ModItems.ARTHROPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.SPELAEOPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.COELUROSAURAVUS_SPAWN_EGG.get());
                        output.accept(ModItems.LITHOPLEURA_SPAWN_EGG.get());
                        output.accept(ModItems.STENODICTYA_LOBATA_SPAWN_EGG.get());
                        output.accept(ModItems.MEGANEURA_SPAWN_EGG.get());
                        output.accept(ModItems.ARBORA_SPAWN_EGG.get());
                        output.accept(ModItems.PHYLLOSENS_SPAWN_EGG.get());
                        output.accept(ModItems.VELOPHYLL_SPAWN_EGG.get());
                        output.accept(ModItems.SPOROPHYLL_SPAWN_EGG.get());
                        output.accept(ModItems.PETROLACOSAURUS_SPAWN_EGG.get());
                        output.accept(ModItems.IANTHASAURUS_SPAWN_EGG.get());
                        output.accept(ModItems.HYLONOMUS_SPAWN_EGG.get());
                        output.accept(ModItems.RHIZODUS_SPAWN_EGG.get());
                        output.accept(ModItems.XENACANTHUS_SPAWN_EGG.get());
                        output.accept(ModItems.ORTHACANTHUS_SPAWN_EGG.get());
                        output.accept(ModItems.MEGARACHNE_SPAWN_EGG.get());
                        output.accept(ModItems.OVERGROWN_NEXUS_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_COW_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_SHEEP_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_PIG_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_ZOMBIE_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_CREEPER_SPAWN_EGG.get());
                        output.accept(ModItems.PARASITIZED_SPIDER_SPAWN_EGG.get());
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_1.get());
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_2.get());
                        output.accept(ModItems.SPORE_INFECTION_SYRINGE_3.get());
                        output.accept(ModItems.SPORE_METER.get());
                        output.accept(ModItems.PHASE_METER.get());
                        output.accept(ModItems.PACK_SCANNER.get());
                        output.accept(ModItems.SPORE.get());
                        output.accept(ModItems.SPORE_FILTER.get());
                        output.accept(ModItems.PROTECTIVE_MASK.get());
                        output.accept(ModItems.SPORE_PURIFIER_ITEM.get());
                        output.accept(ModItems.SPORE_DIFFUSER_ITEM.get());
                        output.accept(ModItems.DECONTAMINATOR_ITEM.get());
                        output.accept(ModItems.REPELLENT_LAMP_ITEM.get());
                        output.accept(ModItems.BARBED_WIRE_ITEM.get());
                        output.accept(ModItems.PHASE_RECORDER_ITEM.get());
                        output.accept(ModItems.SPORE_ALARM_ITEM.get());
                        output.accept(ModItems.GUN_TURRET_ITEM.get());
                        output.accept(ModItems.AUTOCANNON_ROUND.get());
                        output.accept(ModItems.ARBORA_EGG_ITEM.get());
                        output.accept(ModItems.ARBORA_STORAGE_POD_ITEM.get());
                        output.accept(ModItems.SPORE_NODE_ITEM.get());
                        output.accept(ModItems.SPORE_VENT_ITEM.get());
                        output.accept(ModItems.SIEGE_CANNON_ITEM.get());
                        output.accept(ModItems.BEAM_SHIELD_ITEM.get());
                        output.accept(ModItems.SIEGE_SHELL.get());
                        output.accept(ModItems.OVERGROWN_DOOR_ITEM.get());
                        output.accept(ModItems.OVERGROWN_CHEST_ITEM.get());
                        output.accept(ModItems.OVERGROWN_BARREL_ITEM.get());
                        output.accept(ModItems.OVERGROWN_GLOWBERRY.get());
                        output.accept(ModItems.VINE_FRUIT.get());
                        output.accept(ModItems.UNDEVELOPED_SEED.get());
                        output.accept(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM.get());
                        output.accept(ModItems.PLANT_FIBER.get());
                        output.accept(ModItems.HARDENED_BARK.get());
                        output.accept(ModItems.ARTHROPOD_LIMB.get());
                        output.accept(ModItems.OVERGROWN_EXOSKELETON.get());
                        output.accept(ModItems.SCATURIUM_EXOSKELETON.get());
                        output.accept(ModItems.PHALANGIOTARBUS_FANG.get());
                        output.accept(ModItems.MEGANEURA_COMPOUND_EYE.get());
                        output.accept(ModItems.MEGANEURA_WING.get());
                        output.accept(ModItems.PROTOPHASMA_ANTENNA.get());
                        output.accept(ModItems.REPTILE_SCUTE.get());
                        output.accept(ModItems.SAIL_MEMBRANE.get());
                        output.accept(ModItems.FISH_SCALE.get());
                        output.accept(ModItems.MEGARACHNE_PINCER.get());
                        output.accept(ModItems.RAW_CHLOROPHYLLITE.get());
                        output.accept(ModItems.RAW_HARDENED_SAP_CRYSTAL.get());
                        output.accept(ModItems.CHLOROPHYLLITE.get());
                        output.accept(ModItems.SAP_CRYSTAL.get());
                        output.accept(ModItems.CHLOROPHYLLITE_BLOCK_ITEM.get());
                        output.accept(ModItems.SAP_CRYSTAL_BLOCK_ITEM.get());

                        // 加工素材
                        output.accept(ModItems.REPTILE_LEATHER.get());
                        output.accept(ModItems.COMPOSITE_FIBER.get());
                        output.accept(ModItems.SAP_CRYSTAL_SOFTENER.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_BLOCK_ITEM.get());

                        // 防具一式
                        output.accept(ModItems.EXOSKELETON_HELMET.get());
                        output.accept(ModItems.EXOSKELETON_CHESTPLATE.get());
                        output.accept(ModItems.EXOSKELETON_LEGGINGS.get());
                        output.accept(ModItems.PLANT_ARMOR.get());
                        output.accept(ModItems.CHLOROPHYLLITE_HELMET.get());
                        output.accept(ModItems.CHLOROPHYLLITE_CHESTPLATE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_LEGGINGS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_BOOTS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_HELMET.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_CHESTPLATE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_LEGGINGS.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_BOOTS.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_HELMET.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_CHESTPLATE.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_LEGGINGS.get());
                        output.accept(ModItems.REINFORCED_CHLOROPHYLLITE_ALLOY_BOOTS.get());

                        // ツール一式
                        output.accept(ModItems.CHLOROPHYLLITE_AXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_PICKAXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_SHOVEL.get());
                        output.accept(ModItems.CHLOROPHYLLITE_HOE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_SWORD.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_AXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_PICKAXE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_SHOVEL.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_HOE.get());
                        output.accept(ModItems.CHLOROPHYLLITE_ALLOY_SWORD.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
