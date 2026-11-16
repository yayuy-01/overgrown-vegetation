package com.yayuy.overgrownvegetation.ley;

import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.LeySpec;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * <b>地脈の汲み上げ器</b>の中身(2026-11-13 サバイバルの背骨 段 2b)。どこにでも置け、地脈の線に近いほど多く汲む(決定 20。効率は {@link LeyLines#efficiency})。
 *
 * <p>毎 tick {@code pumpFePerTick × 効率} FE を中にため({@code pumpBuffer} まで、端数は持ち越す)、次の順に出す(隣・線を合わせて 1 tick {@code pumpMaxOutput} まで):
 * <ol>
 *     <li>隣の 6 面の FE を受け取る口(2c の換気の機械・ほかの MOD の機械)へ押し出す。</li>
 *     <li>柱から張った線は {@link LeyGrid} が取り出しの口({@link #energyPort()})から引く。</li>
 *     <li>差した電池は、中が半分を越えた余りで充電する(機械・線が先、電池は余りで。【仮】)。</li>
 * </ol>
 * 効率はシードと座標だけで決まるので覚えておき、200 tick ごとに測り直す(設計値を変えたとき用)。中の FE と電池は保存する。
 */
public class LeyPumpBlockEntity extends BlockEntity implements LeyBatteryHolder {

    private int energy;
    private double carry;
    private double efficiency = Double.NaN;
    private double distance = Double.NaN;
    private ItemStack battery = ItemStack.EMPTY;
    /** この tick に出した量(隣・線・電池を合わせて {@code pumpMaxOutput} まで)。 */
    private long outTick = Long.MIN_VALUE;
    private int outThisTick;

    /** FE の取り出しの口(受け取りはしない)。 */
    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int toExtract, boolean simulate) {
            int n = Math.max(0, Math.min(energy, Math.min(toExtract, outputLeft())));
            if (!simulate && n > 0) {
                energy -= n;
                outThisTick += n;
                setChanged();
            }
            return n;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return spec().pumpBuffer();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    public LeyPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModLey.PUMP_BE.get(), pos, state);
    }

    private static LeySpec spec() {
        return DesignData.sporeField().ley();
    }

    /** この tick にまだ出せる量。 */
    private int outputLeft() {
        long now = level != null ? level.getGameTime() : 0L;
        if (now != outTick) {
            outTick = now;
            outThisTick = 0;
        }
        return Math.max(0, spec().pumpMaxOutput() - outThisTick);
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    public int energy() {
        return energy;
    }

    /** 効率 0〜1(サーバーだけ。クライアントでは NaN)。 */
    public double efficiency() {
        if (Double.isNaN(efficiency) || (level != null && level.getGameTime() % 200L == 0L)) {
            measure();
        }
        return efficiency;
    }

    /** いちばん近い線までの距離(ブロック)。 */
    public double distance() {
        efficiency();
        return distance;
    }

    private void measure() {
        if (level instanceof ServerLevel server) {
            long seed = server.getSeed();
            distance = LeyLines.distanceToLine(seed, worldPosition.getX(), worldPosition.getZ());
            efficiency = LeyLines.efficiency(seed, worldPosition.getX(), worldPosition.getZ());
        }
    }

    /** 1 tick ぶん汲む(ためるだけ)。汲んだ量を返す。GameTest からも呼ぶ。 */
    public int generate() {
        LeySpec spec = spec();
        double eff = efficiency();
        if (Double.isNaN(eff)) {
            return 0;
        }
        carry += spec.pumpFePerTick() * eff * com.yayuy.overgrownvegetation.worldevent.WorldEvents.pumpMultiplier(level); // 地脈の大潮の間は少し速く(11-16 world-events)
        int gen = (int) carry;
        carry -= gen;
        int before = energy;
        energy = Math.min(spec.pumpBuffer(), energy + gen);
        return energy - before;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyPumpBlockEntity be) {
        int before = be.energy;
        be.generate();
        be.pushToNeighbours();
        be.chargeBattery();
        if (be.energy != before) {
            be.setChanged();
        }
    }

    private void pushToNeighbours() {
        for (Direction d : ModLey.DIRECTIONS) {
            int budget = outputLeft();
            if (energy <= 0 || budget <= 0) {
                return;
            }
            IEnergyStorage target = level.getCapability(Capabilities.EnergyStorage.BLOCK, worldPosition.relative(d), d.getOpposite());
            if (target == null || !target.canReceive() || target == port) {
                continue;
            }
            int given = target.receiveEnergy(Math.min(energy, budget), false);
            energy -= given;
            outThisTick += given;
        }
    }

    /** 中が半分を越えた余りで電池を充電する。 */
    private void chargeBattery() {
        int half = spec().pumpBuffer() / 2;
        if (energy <= half) {
            return;
        }
        IEnergyStorage b = batteryStorage();
        if (b == null) {
            return;
        }
        int accepted = b.receiveEnergy(energy - half, false);
        if (accepted > 0) {
            energy -= accepted;
            setChanged();
        }
    }

    /** 右クリック・置いたときの表示。 */
    public Component info() {
        LeySpec spec = spec();
        double eff = efficiency();
        Component line = Component.translatable("message.overgrown_vegetation.ley_pump.info",
                LeyText.percent(eff), LeyText.one(distance()), LeyText.one(spec.pumpFePerTick() * eff),
                LeyText.fe(energy), LeyText.fe(spec.pumpBuffer()));
        if (!battery.isEmpty()) {
            line = line.copy().append(Component.translatable("message.overgrown_vegetation.ley.battery_part",
                    LeyText.percent(LeyBatteryItem.stored(battery) / (double) LeyBatteryItem.capacity())));
        }
        return line;
    }

    @Override
    public ItemStack battery() {
        return battery;
    }

    @Override
    public void setBatteryRaw(ItemStack stack) {
        battery = stack;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        LeyBatteryHolder.save(tag, registries, battery);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("energy"));
        battery = LeyBatteryHolder.load(tag, registries);
    }
}
