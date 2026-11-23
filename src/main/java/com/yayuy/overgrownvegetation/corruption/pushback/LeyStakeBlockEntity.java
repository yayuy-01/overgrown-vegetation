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
 *
 * <p>留守の間(11-23 夕方 ユーザー決定): 状態を {@link AbsentWards} に書き写し、チャンクが外れても覚えで範囲を守る。留守の間に使う FE/t は
 * 「立っているだけの分 + 最後に読み込まれていた間に止めて払った FE の平均」。読み込まれて最初に回ったとき、覚えから出した今の FE に揃える({@link #awayTick})。
 */
public class LeyStakeBlockEntity extends BlockEntity {

    private int energy;
    /** 使う FE の端数(MOD の設定「押し返す道具の効き」で小数になる分)。 */
    private double feCarry;
    /** 留守の見積もり(11-23 AbsentWards): 受け取った FE・発電機のつながり。 */
    private final AwayMeter meter = new AwayMeter();
    /** 最後に覚えへ書き写した tick(読み込み直すと最初から)。 */
    private long lastMirror = Long.MIN_VALUE;
    /** この読み込みの間に止めて払った FE と、その数え始め。 */
    private long paidSession;
    private long sessionStart = Long.MIN_VALUE;
    /** 止めた分の平均(FE/t。最後に読み込まれていた間)。保存する。 */
    private double payAvg;

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int n = Math.max(0, Math.min(toReceive, RingWards.table().stakeBuffer - energy));
            if (!simulate && n > 0) {
                energy += n;
                meter.received(n);
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
        int before = energy;
        energy = Math.max(0, energy - scaled(fe));
        paidSession += before - energy;
        setChanged();
        sync();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyStakeBlockEntity be) {
        if (level instanceof ServerLevel server) be.awayTick(server);
        int use = RingWards.table().stakeFePerTick;
        if (be.energy > 0 && use > 0) {
            be.energy = Math.max(0, be.energy - be.scaled(use));
            be.setChanged();
        }
        if (level.getGameTime() % 10 == 0) be.sync();
    }

    // ---------------------------------------------------------------- 留守(11-23)

    /**
     * 毎 tick の最初: 留守だった(覚えが留守の印)なら精算し、{@link AbsentWards#MIRROR_TICKS} ごとに覚えへ書き写す。精算したら true。
     * 精算は「今の FE と覚えから出した FE の小さい方」に揃えるだけなので、何度呼んでも二重には減らない。
     */
    public boolean awayTick(ServerLevel level) {
        long now = level.getGameTime();
        meter.tick(level, worldPosition);
        AbsentWards.Machine m = AbsentWards.touch(level, worldPosition, false);
        boolean settled = false;
        if (m.isAway(now)) {
            int at = m.energyAt(now);
            if (at < energy) {
                energy = at;
                setChanged();
            }
            if (m.drainPerTick > 0.0D) payAvg = Math.max(0.0D, m.drainPerTick - baseDrain());
            meter.reset();
            sessionStart = now;
            paidSession = 0;
            settled = true;
            sync();
        }
        if (sessionStart == Long.MIN_VALUE) sessionStart = now;
        if (settled || lastMirror == Long.MIN_VALUE || now - lastMirror >= AbsentWards.MIRROR_TICKS) mirror(level, m, now);
        return settled;
    }

    private void mirror(ServerLevel level, AbsentWards.Machine m, long now) {
        long span = now - sessionStart;
        if (span > 0 && span >= com.yayuy.overgrownvegetation.data.DesignData.pushBack().absent.stakePayMinSeconds * 20L) {
            payAvg = paidSession / (double) span;
        }
        m.energy = energy;
        m.drainPerTick = baseDrain() + payAvg;
        m.inflowPerTick = meter.inflow(now);
        m.producerLinked = meter.linked();
        AbsentWards.mirrored(level, m);
        lastMirror = now;
    }

    /** 立っているだけで使う FE/t(効きの設定で割った値)。 */
    private static double baseDrain() {
        return RingWards.table().stakeFePerTick / com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.pushbackEfficiency();
    }

    /** テスト用: 今すぐ覚えへ書き写す。 */
    public void mirrorForTests() {
        if (level instanceof ServerLevel server) mirror(server, AbsentWards.touch(server, worldPosition, false), server.getGameTime());
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
        if (level instanceof ServerLevel server) {
            AbsentWards.Machine m = AbsentWards.find(server, worldPosition, false);
            if (m != null && !m.isAway(server.getGameTime())) mirror(server, m, server.getGameTime()); // 外れる直前の FE を覚えて留守へ
            AbsentWards.markAway(server, worldPosition, false);
            RingWards.setStakePowered(server, worldPosition, false);
        }
    }

    public Component info(ServerLevel level) {
        return Component.translatable(powered() ? "message.overgrown_vegetation.ley_stake.on" : "message.overgrown_vegetation.ley_stake.off",
                (int) RingWards.table().stakeRadius, energy, RingWards.table().stakeBuffer);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        tag.putDouble("payAvg", payAvg);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("energy"));
        payAvg = Math.max(0.0D, tag.getDouble("payAvg"));
    }
}
