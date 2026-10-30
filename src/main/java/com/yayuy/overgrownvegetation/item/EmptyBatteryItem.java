package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 空の電池(2026-10-16、㉔)。雷雨の中、空の見える所に落としておくと {@link #CHARGE_TICKS} で満タンの電池になる
 * (植物が電気を吸う理屈を人が逆に使う。仮決め 30 秒)。ふつうは地脈の汲み上げ器に入れて充電する(2026-10-22。蓄電の実での充電は蓄電の実ごと削除)。
 */
public class EmptyBatteryItem extends Item {

    public static final int CHARGE_TICKS = 600;

    public EmptyBatteryItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        if (entity.level() instanceof ServerLevel level && level.isThundering() && level.canSeeSky(entity.blockPosition())) {
            var data = entity.getPersistentData();
            int t = data.getInt("OvBatteryCharge") + 1;
            data.putInt("OvBatteryCharge", t);
            if (t % 20 == 0) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + 0.2D, entity.getZ(), 3, 0.1D, 0.1D, 0.1D, 0.05D);
            }
            if (t >= CHARGE_TICKS) {
                // 電池は耐久を持つので 1 個ずつしか重ならない。重ねて置いた空の電池は 1 個ずつの落とし物に分ける。
                int count = stack.getCount();
                entity.setItem(new ItemStack(ModItems.BATTERY.get()));
                for (int i = 1; i < count; i++) {
                    level.addFreshEntity(new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), new ItemStack(ModItems.BATTERY.get())));
                }
                data.remove("OvBatteryCharge");
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.empty_battery").withStyle(ChatFormatting.GRAY));
    }
}
