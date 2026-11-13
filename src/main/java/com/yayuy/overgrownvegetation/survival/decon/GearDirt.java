package com.yayuy.overgrownvegetation.survival.decon;

import net.minecraft.world.item.ItemStack;

/**
 * 装備の汚れ(サバイバルの背骨 段 3b で中身を入れる。段 3a の間は汚れが無いものとして扱う)。
 */
public final class GearDirt {

    private GearDirt() {
    }

    /** 汚れの量。 */
    public static int dirt(ItemStack stack) {
        return 0;
    }

    /** 手洗い 1 回で落とす汚れ。 */
    public static int handStep() {
        return 0;
    }

    /** 洗い場 1 回で落とす汚れ。 */
    public static int machineStep() {
        return 0;
    }

    /** 汚れを {@code amount} 落とす。 */
    public static void wash(ItemStack stack, int amount) {
    }
}
