package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.registry.ModPushBack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 地脈の杭の中身: FE をためて、立っているだけで {@code ring.stakeFePerTick} 使う。止めるたびに {@code ring.stakeFePerBlock}({@link #pay})。
 * FE が尽きたら {@link LeyStakeBlock#POWERED} が落ち、範囲の場が消える。
 */
public class LeyStakeBlockEntity extends BlockEntity {

    private int energy;
    /** 使う FE の端数(MOD の設定「押し返す道具の効き」で小数になる分)。 */
    private double feCarry;

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int n = Math.max(0, Math.min(toReceive, RingWards.table().stakeBuffer - energy));
            if (!simulate && n > 0) {
                energy += n;
                setChanged();
            }
            return n;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return RingWards.table().stakeBuffer;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    };

    public LeyStakeBlockEntity(BlockPos pos, BlockState state) {
        super(ModPushBack.LEY_STAKE_BE.get(), pos, state);
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    public int energy() {
        return energy;
    }

    /** 線を張れるか(FE が残っている)。 */
    public boolean powered() {
        return energy > 0;
    }

    /** テスト用・コマンド用: FE を入れる。 */
    public void setEnergy(int value) {
        energy = Math.max(0, Math.min(value, RingWards.table().stakeBuffer));
        setChanged();
        sync();
    }

    /** 止めた分を払う。 */
    void pay(int fe) {
        energy = Math.max(0, energy - scaled(fe));
        setChanged();
        sync();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyStakeBlockEntity be) {
        int use = RingWards.table().stakeFePerTick;
        if (be.energy > 0 && use > 0) {
            be.energy = Math.max(0, be.energy - be.scaled(use));
            be.setChanged();
        }
        if (level.getGameTime() % 10 == 0) be.sync();
    }

    /** MOD の設定「押し返す道具の効き」(11-19): 使う FE ÷ %。小数の端数は次へ持ち越す(保存しない)。 */
    private int scaled(int fe) {
        if (fe <= 0) return 0;
        double exact = fe / com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.pushbackEfficiency() + feCarry;
        int whole = (int) Math.floor(exact);
        feCarry = exact - whole;
        return whole;
    }

    /** 見た目の印と範囲の場を FE に合わせる。 */
    private void sync() {
        if (!(level instanceof ServerLevel server)) return;
        BlockState s = getBlockState();
        if (!(s.getBlock() instanceof LeyStakeBlock)) return;
        boolean on = powered();
        RingWards.setStakePowered(server, worldPosition, on && !isRemoved());
        if (s.getValue(LeyStakeBlock.POWERED) != on) {
            server.setBlock(worldPosition, s.setValue(LeyStakeBlock.POWERED, on), 3);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) RingWards.setStakePowered(server, worldPosition, powered());
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) RingWards.setStakePowered(server, worldPosition, false);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel server) RingWards.setStakePowered(server, worldPosition, false);
    }

    public Component info(ServerLevel level) {
        return Component.translatable(powered() ? "message.overgrown_vegetation.ley_stake.on" : "message.overgrown_vegetation.ley_stake.off",
                (int) RingWards.table().stakeRadius, energy, RingWards.table().stakeBuffer);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("energy"));
    }
}
