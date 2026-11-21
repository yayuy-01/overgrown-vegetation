package com.yayuy.overgrownvegetation.gear;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * <b>導晶の道具</b>(グレード 1。2026-11-21)。つるはし・斧・シャベル・クワの 4 本。形と使い方はバニラと同じで、
 * 地脈の力をためると掘る速さが上がる({@link LeyCharge})。硬さはダイヤと同じ(黒曜石を掘れる)。数は {@code gear_tiers.json} の {@code conduit}【仮】。
 *
 * <p>バーは壊れにくさのまま(バニラと同じ)。力の残りは説明に出す。
 */
public final class ConduitTools {

    private ConduitTools() {
    }

    /** 4 本で同じ働き(バニラの道具の親が別々なので、ここから呼ぶ)。 */
    static float speed(ItemStack stack, float base) {
        return LeyCharge.boost(stack, base);
    }

    static void mined(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner, float base) {
        if (!level.isClientSide && base > 1.0F && state.getDestroySpeed(level, pos) != 0.0F) {
            LeyCharge.spendForBlock(stack, miner instanceof Player p ? p : null);
        }
    }

    public static final class Pickaxe extends PickaxeItem implements LeyCharge.Chargeable {
        public Pickaxe(Tier tier, Properties p) {
            super(tier, p);
        }

        @Override
        public float getDestroySpeed(ItemStack stack, BlockState state) {
            return speed(stack, super.getDestroySpeed(stack, state));
        }

        @Override
        public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
            mined(stack, level, state, pos, miner, super.getDestroySpeed(stack, state));
            return super.mineBlock(stack, level, state, pos, miner);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
            return slotChanged || !LeyCharge.onlyChargeChanged(oldStack, newStack);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }

    public static final class Axe extends AxeItem implements LeyCharge.Chargeable {
        public Axe(Tier tier, Properties p) {
            super(tier, p);
        }

        @Override
        public float getDestroySpeed(ItemStack stack, BlockState state) {
            return speed(stack, super.getDestroySpeed(stack, state));
        }

        @Override
        public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
            mined(stack, level, state, pos, miner, super.getDestroySpeed(stack, state));
            return super.mineBlock(stack, level, state, pos, miner);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
            return slotChanged || !LeyCharge.onlyChargeChanged(oldStack, newStack);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }

    public static final class Shovel extends ShovelItem implements LeyCharge.Chargeable {
        public Shovel(Tier tier, Properties p) {
            super(tier, p);
        }

        @Override
        public float getDestroySpeed(ItemStack stack, BlockState state) {
            return speed(stack, super.getDestroySpeed(stack, state));
        }

        @Override
        public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
            mined(stack, level, state, pos, miner, super.getDestroySpeed(stack, state));
            return super.mineBlock(stack, level, state, pos, miner);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
            return slotChanged || !LeyCharge.onlyChargeChanged(oldStack, newStack);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }

    public static final class Hoe extends HoeItem implements LeyCharge.Chargeable {
        public Hoe(Tier tier, Properties p) {
            super(tier, p);
        }

        @Override
        public float getDestroySpeed(ItemStack stack, BlockState state) {
            return speed(stack, super.getDestroySpeed(stack, state));
        }

        @Override
        public boolean mineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
            mined(stack, level, state, pos, miner, super.getDestroySpeed(stack, state));
            return super.mineBlock(stack, level, state, pos, miner);
        }

        @Override
        public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess access) {
            return LeyCharge.onStackedOn(stack, other, action, player) || super.overrideOtherStackedOnMe(stack, other, slot, action, player, access);
        }

        @Override
        public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
            return slotChanged || !LeyCharge.onlyChargeChanged(oldStack, newStack);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
            LeyCharge.tooltip(stack, tooltip);
        }
    }
}
