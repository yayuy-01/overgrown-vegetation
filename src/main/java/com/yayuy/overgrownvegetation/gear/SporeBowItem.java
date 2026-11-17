package com.yayuy.overgrownvegetation.gear;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * <b>胞子弾の弓</b>(段 1 の遠距離。2026-11-17 決定 17)。引き・飛びはバニラの弓と同じ。胞子弾の矢を撃つと、燃え・燻りが強く、植物側に余計に効く
 * ({@link SporeShots})。ふつうの矢はふつうの弓と同じ。
 */
public class SporeBowItem extends BowItem {

    public SporeBowItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractArrow customArrow(AbstractArrow arrow, ItemStack projectileStack, ItemStack weaponStack) {
        if (SporeShots.isSpore(arrow)) SporeShots.markBow(arrow);
        return arrow;
    }
}
