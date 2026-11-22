package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import com.yayuy.overgrownvegetation.port.capabilities.Capabilities;
import net.minecraftforge.energy.IEnergyStorage;
import com.yayuy.overgrownvegetation.port.capabilities.OvCaps;

/**
 * 電池を 1 つ差しておける中身(汲み上げ器・柱)の共通の口。差す = 電池を持って右クリック、抜く = 素手でしゃがんで右クリック。
 * 差している間はブロックの状態 {@link #BATTERY} が true(見た目に電池が出る)。
 */
public interface LeyBatteryHolder {

    BooleanProperty BATTERY = BooleanProperty.create("battery");

    ItemStack battery();

    /** 中身だけを入れ替える(ブロックの状態は {@link #insert}・{@link #takeOut} が変える)。 */
    void setBatteryRaw(ItemStack stack);

    /** 差している電池の FE の口(無ければ null)。中身を変えたら呼び手が {@code setChanged} する。 */
    default IEnergyStorage batteryStorage() {
        ItemStack b = battery();
        return b.isEmpty() ? null : OvCaps.getCapability(b, Capabilities.EnergyStorage.ITEM);
    }

    /** 差している物(電池・地脈の力をためる道具)のたまり具合 0〜1。 */
    static double fraction(ItemStack stack) {
        IEnergyStorage s = stack.isEmpty() ? null : OvCaps.getCapability(stack, Capabilities.EnergyStorage.ITEM);
        return s == null || s.getMaxEnergyStored() <= 0 ? 0.0D : s.getEnergyStored() / (double) s.getMaxEnergyStored();
    }

    /** 持っている電池(か地脈の力をためる道具 = 2026-11-21 グレード 1)を差す。差せたら true。 */
    static boolean insert(Level level, BlockPos pos, BlockState state, Player player, ItemStack held, LeyBatteryHolder holder) {
        if (!(held.is(ModLey.BATTERY.get()) || com.yayuy.overgrownvegetation.gear.LeyCharge.has(held)) || !holder.battery().isEmpty()) {
            return false;
        }
        if (!level.isClientSide) {
            ItemStack one = held.copyWithCount(1); // 1.20.1: consumeAndReturn は無い(創造では減らさない)
            if (!player.getAbilities().instabuild) held.shrink(1);
            holder.setBatteryRaw(one);
            level.setBlock(pos, state.setValue(BATTERY, true), 3);
            level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 0.8F, 1.3F);
        }
        return true;
    }

    /** 差している電池を抜いて手に渡す(入らなければ足元へ)。抜けたら true。 */
    static boolean takeOut(Level level, BlockPos pos, BlockState state, Player player, LeyBatteryHolder holder) {
        if (holder.battery().isEmpty()) {
            return false;
        }
        if (!level.isClientSide) {
            ItemStack b = holder.battery();
            holder.setBatteryRaw(ItemStack.EMPTY);
            if (!player.getInventory().add(b)) {
                player.drop(b, false);
            }
            level.setBlock(pos, state.setValue(BATTERY, false), 3);
            level.playSound(null, pos, SoundEvents.COPPER_BREAK, SoundSource.BLOCKS, 0.8F, 1.3F);
        }
        return true;
    }

    /** 壊れたときに電池を落とす。 */
    static void drop(Level level, BlockPos pos, LeyBatteryHolder holder) {
        if (!holder.battery().isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, holder.battery());
            holder.setBatteryRaw(ItemStack.EMPTY);
        }
    }

    static void save(CompoundTag tag, ItemStack battery) {
        if (!battery.isEmpty()) {
            tag.put("battery", battery.save(new CompoundTag()));
        }
    }

    static ItemStack load(CompoundTag tag) {
        return tag.contains("battery") ? ItemStack.of(tag.getCompound("battery")) : ItemStack.EMPTY;
    }
}
