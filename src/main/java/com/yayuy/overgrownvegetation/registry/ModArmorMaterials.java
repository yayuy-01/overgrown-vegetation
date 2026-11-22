package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * MOD の防具素材。2026-11-04 に<b>防護マスクだけ</b>で戻した(10-30 に退避した前の版
 * {@code parked/.../registry/ModArmorMaterials.java} は gear.json から数値を読み、外骨格・クロロフィライトなども持っていた。
 * 戻すときはこの版に足す)。
 */
public final class ModArmorMaterials {

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, OvergrownVegetation.MODID);

    /**
     * 「防護マスク」({@link com.yayuy.overgrownvegetation.item.ProtectiveMaskItem})専用。数値は前の gear.json の
     * {@code armor.protective_mask} と同じ(防御 1・強靭 0・エンチャント 10)を書き込みにした(gear.json は退避中)。修理素材は革。
     * 描画は GeckoLib の立体モデルなので、層の絵は 1 枚(染めない)。
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> PROTECTIVE_MASK =
            ARMOR_MATERIALS.register("protective_mask", () -> {
                EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
                for (ArmorItem.Type type : ArmorItem.Type.values()) {
                    defense.put(type, type == ArmorItem.Type.HELMET ? 1 : 0);
                }
                return new ArmorMaterial(defense, 10, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER),
                        List.of(new ArmorMaterial.Layer(OvergrownVegetation.id("protective_mask"))), 0.0F, 0.0F);
            });

    public static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
    }

    private ModArmorMaterials() {
    }
}
