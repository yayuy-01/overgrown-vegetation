package com.yayuy.overgrownvegetation.item;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 追加防具の 1 枠。その枠のタグ({@link ExtraArmor.Part#tag})が付いた物だけ、1 つだけ入る。 */
public final class ExtraArmorSlot extends Slot {

    private final ExtraArmor.Part part;

    public ExtraArmorSlot(ExtraArmorContainer container, ExtraArmor.Part part, int x, int y) {
        super(container, part.ordinal(), x, y);
        this.part = part;
    }

    public ExtraArmor.Part part() {
        return part;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return stack.is(part.tag());
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
        return Pair.of(InventoryMenu.BLOCK_ATLAS, part.emptyIcon());
    }
}
