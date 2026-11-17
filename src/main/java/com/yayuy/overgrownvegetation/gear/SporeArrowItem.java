package com.yayuy.overgrownvegetation.gear;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * <b>胞子弾の矢</b>(矢に焼いた胞子を付けた物。2026-11-17)。どの弓・クロスボウでも撃てて当たると燃える。胞子弾の弓で撃つと強い({@link SporeShots})。
 * 飛ぶのはバニラの矢(印だけ付ける)。拾うとこの矢に戻る。
 */
public class SporeArrowItem extends ArrowItem {

    public SporeArrowItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter, @Nullable ItemStack weapon) {
        AbstractArrow arrow = super.createArrow(level, ammo, shooter, weapon);
        SporeShots.markSpore(arrow);
        return arrow;
    }
}
