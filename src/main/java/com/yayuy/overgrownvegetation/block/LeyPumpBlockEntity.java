package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.phase.LeyOmen;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.world.level.ChunkPos;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地脈の汲み上げ器の中身(2026-10-16、㉙)。地脈の雫を少しずつためる。
 * <ul>
 *     <li>ユーザー指定: 地脈が生まれる場所に近いほど効率が良い。汚染地域の外にも置けるが、遠い所では効率が最悪になる。</li>
 *     <li>仮決め: 効率 = 1 − (いちばん近い地脈の線までの距離 ÷ (セルの大きさ ÷ 2))、下限 {@link #MIN_EFFICIENCY}。
 *     線の上で 100%、セルの真ん中(既定 96 ブロック離れた所)で最低。効率 100% で {@link #TICKS_PER_DROP} に 1 つ、中に {@link #MAX_STORED} まで。</li>
 * </ul>
 * <p>地脈電池(2026-10-22、ユーザー指定「汲み上げ器に電池を入れると、雫の代わりに充電される」): 電池が入っていて満タンでない間は雫をためずに
 * 電池を充電する。速さは効率 100% で {@link #CHARGE_PER_TICK}(空から満タンまで 20 分。仮決め)。満タンになったら雫に戻る。
 * 場はシードと座標だけで決まるので、効率は最初に 1 回測って覚える(保存はしない。設計データを変えたら読み込み直しで測り直す)。
 */
public class LeyPumpBlockEntity extends BlockEntity {

    /** 効率 100% で雫 1 つにかかる tick(2 分。仮決め)。 */
    public static final int TICKS_PER_DROP = 2400;
    /** 中にためられる数(仮決め)。 */
    public static final int MAX_STORED = 8;
    /** 地脈から遠い所の効率(仮決め 5%)。 */
    public static final double MIN_EFFICIENCY = 0.05D;
    /** 進める間隔(tick)。 */
    private static final int STEP = 20;

    /** 効率 100% で 1 tick に充電する量(満タン 72000 を 20 分 = 24000 tick で。仮決め)。 */
    public static final double CHARGE_PER_TICK = 3.0D;

    private int stored;
    /** 雫の代わりに出来た未知の試料(2026-10-24 地脈技術の 4 段目)。雫と合わせて {@link #MAX_STORED} まで。 */
    private int samples;
    private double progress;
    private double efficiency = -1.0D;
    private boolean hasBattery;
    private int batteryCharge;
    private double chargeProgress;

    public LeyPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEY_PUMP.get(), pos, state);
    }

    /** その場所の効率(0.05〜1)。 */
    public static double efficiencyAt(ServerLevel level, BlockPos pos) {
        double half = DesignData.climax().leyVein().cellSize() * 0.5D;
        double d = LeyVeinField.distanceToLine(level.getSeed(), pos.getX(), pos.getZ());
        if (!(half > 0.0D) || d == Double.MAX_VALUE) {
            return MIN_EFFICIENCY;
        }
        return Mth.clamp(1.0D - d / half, MIN_EFFICIENCY, 1.0D);
    }

    public double efficiency() {
        if (efficiency < 0.0D && level instanceof ServerLevel server) {
            efficiency = efficiencyAt(server, worldPosition);
        }
        return Math.max(efficiency, MIN_EFFICIENCY);
    }

    /**
     * 今の効率(2026-10-24): ふだんは {@link #efficiency()}。F1 の前の前兆の間は 5 秒ごとに ±40% 揺れる({@link LeyOmen#pumpJitter}。
     * 上は 100% を越えてもよい = 平均は変わらず、雫の間隔だけ不規則になる)。
     */
    public double currentEfficiency() {
        double e = efficiency();
        if (LeyOmen.isActive() && level != null) {
            e *= LeyOmen.pumpJitter(worldPosition, level.getGameTime());
        }
        return Math.max(e, MIN_EFFICIENCY);
    }

    public int stored() {
        return stored + samples;
    }

    public int samples() {
        return samples;
    }

    /** 動いている(雫がたまる途中か、電池を充電中)。前兆の知らせを出す汲み上げ器。 */
    public boolean isRunning() {
        return (hasBattery && batteryCharge < BatteryItem.CAPACITY) || stored() < MAX_STORED;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LeyPumpBlockEntity be) {
        if (Math.floorMod(level.getGameTime() + pos.asLong(), STEP) != 0) {
            return;
        }
        if (be.hasBattery && be.batteryCharge < BatteryItem.CAPACITY) {
            be.chargeProgress += STEP * be.currentEfficiency() * CHARGE_PER_TICK;
            int add = (int) be.chargeProgress;
            be.chargeProgress -= add;
            be.batteryCharge = Math.min(BatteryItem.CAPACITY, be.batteryCharge + add);
            be.setChanged();
            return;
        }
        if (be.stored() >= MAX_STORED) {
            return;
        }
        be.progress += STEP * be.currentEfficiency();
        if (be.progress >= TICKS_PER_DROP) {
            be.progress -= TICKS_PER_DROP;
            be.addDrop((ServerLevel) level);
            if (be.stored() >= MAX_STORED) {
                be.progress = 0.0D;
            }
            be.updateFilled(state);
        }
        be.setChanged();
    }

    /**
     * 雫を 1 つ作る。ワールド 2 日目(F1 の時刻の条件の 1 日前)からは {@code omen.sampleChance}(5%)で代わりに未知の試料
     * (2026-10-24)。F1 の後も、この汲み上げ器のチャンクが汚染される(台帳に載る)までは混じる。
     */
    public void addDrop(ServerLevel level) {
        if (LeyOmen.samplesOpen()
                && !PhaseData.get(level.getServer().overworld()).isOvergrownChunk(new ChunkPos(worldPosition))
                && level.getRandom().nextDouble() < DesignData.phases().omen().sampleChance()) {
            samples++;
        } else {
            stored++;
        }
    }

    /** ためた雫を全部取り出す(数を返す)。試料は {@link #takeSamples}。 */
    public int takeAll() {
        int n = stored;
        if (n > 0) {
            stored = 0;
            updateFilled(getBlockState());
            setChanged();
        }
        return n;
    }

    /** たまった未知の試料を全部取り出す(数を返す)。 */
    public int takeSamples() {
        int n = samples;
        if (n > 0) {
            samples = 0;
            updateFilled(getBlockState());
            setChanged();
        }
        return n;
    }

    public boolean hasBattery() {
        return hasBattery;
    }

    public int batteryCharge() {
        return batteryCharge;
    }

    /** 電池を入れ替える({@code battery} は満タンか空の地脈電池 1 個)。前に入っていた電池を返す(無ければ空)。 */
    public ItemStack swapBattery(ItemStack battery) {
        ItemStack back = takeBattery();
        hasBattery = true;
        batteryCharge = BatteryItem.charge(battery);
        chargeProgress = 0.0D;
        setChanged();
        return back;
    }

    /** 入っている電池を取り出す(無ければ空)。 */
    public ItemStack takeBattery() {
        if (!hasBattery) {
            return ItemStack.EMPTY;
        }
        ItemStack back = BatteryItem.stackFor(batteryCharge);
        hasBattery = false;
        batteryCharge = 0;
        chargeProgress = 0.0D;
        setChanged();
        return back;
    }

    /** GameTest 用: 中身の数を直に決める。 */
    public void setStored(int n) {
        stored = Mth.clamp(n, 0, MAX_STORED);
        updateFilled(getBlockState());
        setChanged();
    }

    private void updateFilled(BlockState state) {
        if (level == null || !state.hasProperty(LeyPumpBlock.FILLED)) {
            return;
        }
        boolean filled = stored() > 0;
        if (state.getValue(LeyPumpBlock.FILLED) != filled) {
            level.setBlock(worldPosition, state.setValue(LeyPumpBlock.FILLED, filled), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Stored", stored);
        tag.putInt("Samples", samples);
        tag.putDouble("Progress", progress);
        tag.putBoolean("HasBattery", hasBattery);
        tag.putInt("BatteryCharge", batteryCharge);
        tag.putDouble("ChargeProgress", chargeProgress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored = Mth.clamp(tag.getInt("Stored"), 0, MAX_STORED);
        samples = Mth.clamp(tag.getInt("Samples"), 0, MAX_STORED - stored);
        progress = tag.getDouble("Progress");
        hasBattery = tag.getBoolean("HasBattery");
        batteryCharge = Mth.clamp(tag.getInt("BatteryCharge"), 0, BatteryItem.CAPACITY);
        chargeProgress = tag.getDouble("ChargeProgress");
    }
}
