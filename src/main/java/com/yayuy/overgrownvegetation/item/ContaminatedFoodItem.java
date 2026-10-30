package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 寄生された家畜・ゾンビが落とす「汚染された」食べ物(順 1 の着手順 11、2026-10-04)。
 * <b>食べられるが代償がある</b> —— 食べると {@code infectChance} の確率で胞子感染(段階 1)にかかる(G1 の肝。ユーザー採用)。
 * 感染は {@link SporeInfectionEffects#infect} で掛けるので、防護マスク・効果の長さ・チャンクの胞子濃度の加算がいつもどおり効く。
 * 焼いて汚染を消すレシピは作らない(変換のレシピは順 6.5)。確率は Claude 仮決め(肉 30%・腐った胞子肉 50%)。
 */
public class ContaminatedFoodItem extends Item {

    private final double infectChance;

    public ContaminatedFoodItem(double infectChance, Properties properties) {
        super(properties);
        this.infectChance = infectChance;
    }

    public double infectChance() {
        return infectChance;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide()) {
            infectAfterEating(entity, this.infectChance, entity.getRandom());
        }
        return result;
    }

    /** 食べた後の抽選。{@code chance} の確率で胞子感染(段階 1)を掛け、掛かったら true(GameTest は 1.0 で呼ぶ)。 */
    public static boolean infectAfterEating(LivingEntity entity, double chance, RandomSource random) {
        return random.nextDouble() < chance && SporeInfectionEffects.infect(entity, 0);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.contaminated_food",
                PoweredArmorItem.percent(this.infectChance)).withStyle(ChatFormatting.DARK_GREEN));
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
