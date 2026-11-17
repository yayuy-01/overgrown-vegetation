package com.yayuy.overgrownvegetation.gear;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;

import java.util.function.Supplier;

/**
 * 段の防具の胴・脚・足(2026-11-17)。頭は {@link TierHelmetItem}(フィルターを使う)。胞子への強さは {@link GearSpores}。
 */
public class TierArmorItem extends ArmorItem implements GearSpores.TierArmor {

    private final Supplier<GearTierTable.Armor> spec;

    public TierArmorItem(Holder<ArmorMaterial> material, Type type, Supplier<GearTierTable.Armor> spec, Properties properties) {
        super(material, type, properties);
        this.spec = spec;
    }

    @Override
    public GearTierTable.Armor spec() {
        return spec.get();
    }

    @Override
    public boolean slotMatches(EquipmentSlot slot) {
        return getEquipmentSlot() == slot;
    }
}
