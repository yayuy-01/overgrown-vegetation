package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.ley.LeyText;
import com.yayuy.overgrownvegetation.registry.ModVent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.energy.IEnergyStorage;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * <b>地脈の換気機</b>の中身(2026-11-14 サバイバルの背骨 段 2c)。
 *
 * <ul>
 *     <li>FE の口は受け取りだけ(汲み上げ器の隣・柱からの線・ほかの MOD の発電機)。中に {@code vent.energyBuffer} までためる。</li>
 *     <li>詰まっていないフィルターが入っていて、FE が {@code vent.fePerTick} 以上あれば、毎 tick その分を使って動く。</li>
 *     <li>動いている機械は {@link Ventilators} に載り、胞子の場の更新ごとにまわりの場から上限つきで抜く。抜いた量だけフィルターが詰まる
 *     ({@link #onExtracted})。</li>
 * </ul>
 * 中の FE・フィルターは保存する。
 */
public class VentBlockEntity extends BlockEntity implements GeoBlockEntity {

    /** ふいごが動く(2.4 秒の繰り返し)。Blockbench の正本 zyokyosouti_kai_B_yayuy の run。 */
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.ley_ventilator.run");
    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    /** 最後に動いた tick からこの間は「動いている」とみなす(FE が途切れ途切れでも見た目と吸い込みが揺れないように)。 */
    static final int RUNNING_GRACE_TICKS = 20;

    private int energy;
    private ItemStack filter = ItemStack.EMPTY;
    private long lastRunTick = Long.MIN_VALUE;
    /** フィルターへの詰まりの端数(%秒)。 */
    private double loadCarry;
    /** 直近の場の更新 1 回あたりに抜いた量(濃度 % に直した値。表示用)。 */
    private double lastPulledPercent = Double.NaN;
    /** 上 2 つの部位を確かめたか(読み込みごとに 1 回。保存しない)。 */
    private boolean partsChecked;

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int n = Math.max(0, Math.min(toReceive, spec().energyBuffer() - energy));
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
            return spec().energyBuffer();
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

    public VentBlockEntity(BlockPos pos, BlockState state) {
        super(ModVent.VENTILATOR_BE.get(), pos, state);
    }

    static VentSpec spec() {
        return DesignData.sporeField().vent();
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    public int energy() {
        return energy;
    }

    public ItemStack filter() {
        return filter;
    }

    /** フィルターを入れ替える(GameTest・ブロックの右クリックから)。 */
    public void setFilter(ItemStack stack) {
        filter = stack;
        loadCarry = 0.0D;
        setChanged();
    }

    /** 動ける状態か(詰まっていないフィルター・FE)。 */
    public boolean canRun() {
        return !filter.isEmpty() && !AirFilters.clogged(filter) && energy >= spec().fePerTick();
    }

    /** 動いているか(直近 {@link #RUNNING_GRACE_TICKS} tick 以内に FE を使って回った)。 */
    public boolean running() {
        return level != null && lastRunTick != Long.MIN_VALUE && level.getGameTime() - lastRunTick < RUNNING_GRACE_TICKS
                && !filter.isEmpty() && !AirFilters.clogged(filter);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            Ventilators.add(server, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            Ventilators.remove(server, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel server) {
            Ventilators.remove(server, this);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, VentBlockEntity be) {
        if (!be.partsChecked) {
            be.partsChecked = true;
            VentBlock.repairParts(level, pos, state); // 3 ブロックになる前に置いた換気機の上 2 つを付ける(11-22 見直し 15)
        }
        be.tickOnce();
    }

    /** 1 tick ぶん(GameTest からも呼ぶ)。FE を使って回れたら true。 */
    public boolean tickOnce() {
        if (level instanceof ServerLevel server) {
            Ventilators.add(server, this); // onLoad は次の tick に回されるので、置いた直後から一覧に載せる(同じ物は 1 つ)
        }
        boolean ran = false;
        if (canRun()) {
            energy -= spec().fePerTick();
            lastRunTick = level.getGameTime();
            ran = true;
            setChanged();
        }
        boolean run = running();
        BlockState state = getBlockState();
        if (state.hasProperty(VentBlock.RUNNING) && state.getValue(VentBlock.RUNNING) != run) {
            level.setBlock(worldPosition, state.setValue(VentBlock.RUNNING, run), Block.UPDATE_CLIENTS);
        }
        return ran;
    }

    /** 1 回分の場の更新で抜ける量(場の単位)を、フィルターの残りで切った値。{@code steps} = 進める更新の回数。 */
    public double budgetUnits(int steps, SporeFieldTable table) {
        if (filter.isEmpty()) return 0.0D;
        double perUnit = loadPerUnit(table);
        double byFilter = perUnit <= 0.0D ? Double.POSITIVE_INFINITY : AirFilters.remaining(filter) / perUnit;
        return Math.min(table.vent().capPerUpdate() * steps, byFilter);
    }

    /** 場の単位 1 を抜いたときのフィルターの詰まり(%秒)。 */
    public static double loadPerUnit(SporeFieldTable table) {
        return 100.0D / table.fullAt() * (table.updateTicks() / 20.0D) * table.vent().filterLoadFactor()
                / com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.pushbackEfficiency(); // MOD の設定「押し返す道具の効き」(11-19)
    }

    /**
     * 場の計算が抜いた量を受け取る(サーバースレッド)。フィルターを詰まらせ、1 回あたりに抜いた量を表示用に覚える。
     * {@code units} = 抜いた量(場の単位)、{@code steps} = 進んだ更新の回数。
     */
    public void onExtracted(double units, int steps, SporeFieldTable table) {
        if (steps <= 0) return;
        lastPulledPercent = units / steps / table.fullAt() * 100.0D;
        if (!filter.isEmpty() && units > 0.0D) {
            loadCarry += units * loadPerUnit(table);
            int whole = (int) loadCarry;
            loadCarry -= whole;
            AirFilters.absorb(filter, whole);
        }
        setChanged();
    }

    /** 右クリック・置いたときの表示。 */
    public Component info() {
        String state = running() ? "running" : filter.isEmpty() ? "no_filter" : AirFilters.clogged(filter) ? "clogged"
                : energy < spec().fePerTick() ? "no_power" : "idle";
        Component head = Component.translatable("message.overgrown_vegetation.ley_ventilator." + state);
        String filterPart = filter.isEmpty() ? "-" : String.valueOf(AirFilters.percentLeft(filter));
        String pulled = Double.isNaN(lastPulledPercent) ? "-" : LeyText.one(lastPulledPercent);
        return head.copy().append(Component.translatable("message.overgrown_vegetation.ley_ventilator.info",
                filterPart, LeyText.fe(energy), LeyText.fe(spec().energyBuffer()), pulled));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("energy", energy);
        if (!filter.isEmpty()) {
            tag.put("filter", filter.save(new CompoundTag()));
        }
        tag.putDouble("loadCarry", loadCarry);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        energy = Math.max(0, tag.getInt("energy"));
        filter = tag.contains("filter") ? ItemStack.of(tag.getCompound("filter")) : ItemStack.EMPTY;
        loadCarry = tag.getDouble("loadCarry");
    }

    // ---------------------------------------------------------------- 見た目(GeckoLib)

    /**
     * 働いている間だけふいごを動かす。クライアントはブロックの状態の {@link VentBlock#RUNNING}(サーバーが {@link #tickOnce} で切り替えて送る)を読む。
     * 止まると置いた姿(ふいごが少し開いた形)に戻る。アニメの最初のコマは置いた姿と同じなので、動き出しに跳びは出ない。
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "bellows", 0, state -> {
            BlockState bs = getBlockState();
            if (bs.hasProperty(VentBlock.RUNNING) && bs.getValue(VentBlock.RUNNING)) {
                return state.setAndContinue(RUN);
            }
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
