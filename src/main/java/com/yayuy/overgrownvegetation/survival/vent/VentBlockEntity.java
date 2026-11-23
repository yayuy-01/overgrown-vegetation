package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards;
import com.yayuy.overgrownvegetation.corruption.pushback.AwayMeter;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.data.VentSpec;
import com.yayuy.overgrownvegetation.ley.LeyText;
import com.yayuy.overgrownvegetation.registry.ModVent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * <b>地脈の換気機</b>の中身(2026-11-14 サバイバルの背骨 段 2c)。
 *
 * <ul>
 *     <li>FE の口は受け取りだけ(汲み上げ器の隣・柱からの線・ほかの MOD の発電機)。中に {@code vent.energyBuffer} までためる。</li>
 *     <li>詰まっていないフィルターが入っていて、FE が {@code vent.fePerTick} 以上あれば、毎 tick その分を使って動く。</li>
 *     <li>動いている機械は {@link Ventilators} に載り、胞子の場の更新ごとにまわりの場から上限つきで抜く。抜いた量だけフィルターが詰まる
 *     ({@link #onExtracted})。</li>
 *     <li>フィルターは差してある 1 枚 + 予備 + 詰まった物の置き場({@link VentStock}。11-23)。詰まったら置き場へ移り、予備が差さる。
 *     物の口: 上と横から予備を入れる・下から詰まった物を出す({@link #itemPort})。</li>
 *     <li>留守の間(11-23): 状態を {@link AbsentWards} に書き写し、読み込まれていない間も覚えで範囲を守る。読み込まれて最初に回ったとき精算する({@link #settleIfAway})。</li>
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
    private VentStock stock = new VentStock();
    private long lastRunTick = Long.MIN_VALUE;
    /** フィルターへの詰まりの端数(%秒)。 */
    private double loadCarry;
    /** 直近の場の更新 1 回あたりに抜いた量(濃度 % に直した値。表示用)。 */
    private double lastPulledPercent = Double.NaN;
    /** 上 2 つの部位を確かめたか(読み込みごとに 1 回。保存しない)。 */
    private boolean partsChecked;
    /** 留守の見積もり(11-23)。 */
    private final AwayMeter meter = new AwayMeter();
    private long lastMirror = Long.MIN_VALUE;
    /** 覚えのフィルターのどの版まで受け取ったか(保存する。留守の間に覚えが変わったら精算で置き換える)。 */
    private int seenRevision;

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            int n = Math.max(0, Math.min(toReceive, spec().energyBuffer() - energy));
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

    private final ItemPort insertPort = new ItemPort(true, false);
    private final ItemPort extractPort = new ItemPort(false, true);
    private final ItemPort allPort = new ItemPort(true, true);

    public VentBlockEntity(BlockPos pos, BlockState state) {
        super(ModVent.VENTILATOR_BE.get(), pos, state);
    }

    static VentSpec spec() {
        return DesignData.sporeField().vent();
    }

    /** 動くのに要る FE/t(留守の覚えからも見る)。 */
    public static int feNeeded() {
        return spec().fePerTick();
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    /**
     * 物の口(11-23 ユーザー決定): 下からは詰まったフィルターを出すだけ、上と横からは詰まっていないフィルターを予備へ入れるだけ。
     * 向きの無い問い合わせ(null)には両方。差してある 1 枚は口に出さない(詰まると自動で置き場へ移る)。
     */
    public IItemHandler itemPort(@Nullable Direction side) {
        if (side == null) return allPort;
        return side == Direction.DOWN ? extractPort : insertPort;
    }

    public int energy() {
        return energy;
    }

    public ItemStack filter() {
        return stock.filter;
    }

    public VentStock stock() {
        return stock;
    }

    /** フィルターを入れ替える(GameTest・ブロックの右クリックから)。 */
    public void setFilter(ItemStack stack) {
        stock.filter = stack;
        loadCarry = 0.0D;
        stockChanged();
    }

    /** 手で予備へ 1 枚入れる(右クリック。11-23)。入ったら true。 */
    public boolean addSpareByHand(ItemStack one) {
        if (!stock.addSpare(one)) return false;
        stockChanged();
        return true;
    }

    /**
     * しゃがんで素手の右クリック(11-23): 詰まった物があればすべて、無ければ差してある 1 枚、それも無ければ最後の予備を 1 枚取り出す。
     */
    public java.util.List<ItemStack> takeByHand() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (int i = 0; i < stock.clogged.length; i++) {
            if (!stock.clogged[i].isEmpty()) {
                out.add(stock.clogged[i]);
                stock.clogged[i] = ItemStack.EMPTY;
            }
        }
        if (out.isEmpty() && !stock.filter.isEmpty()) {
            out.add(stock.filter);
            stock.filter = ItemStack.EMPTY;
            loadCarry = 0.0D;
        }
        if (out.isEmpty()) {
            for (int i = stock.spares.length - 1; i >= 0; i--) {
                if (!stock.spares[i].isEmpty()) {
                    out.add(stock.spares[i]);
                    stock.spares[i] = ItemStack.EMPTY;
                    break;
                }
            }
        }
        if (!out.isEmpty()) stockChanged();
        return out;
    }

    /** 動ける状態か(詰まっていないフィルター・FE)。 */
    public boolean canRun() {
        return !stock.filter.isEmpty() && !AirFilters.clogged(stock.filter) && energy >= spec().fePerTick();
    }

    /** 動いているか(直近 {@link #RUNNING_GRACE_TICKS} tick 以内に FE を使って回った)。 */
    public boolean running() {
        return level != null && lastRunTick != Long.MIN_VALUE && level.getGameTime() - lastRunTick < RUNNING_GRACE_TICKS
                && !stock.filter.isEmpty() && !AirFilters.clogged(stock.filter);
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
            AbsentWards.Machine m = AbsentWards.find(server, worldPosition, true);
            if (m != null && !m.isAway(server.getGameTime())) mirror(server, m, server.getGameTime()); // 外れる直前の状態を覚えて留守へ
            AbsentWards.markAway(server, worldPosition, true);
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
            awayTick(server);
        }
        if (stock.rotate()) stockChanged(); // 詰まった 1 枚を置き場へ・予備を差す(11-23)
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

    // ---------------------------------------------------------------- 留守(11-23)

    private void awayTick(ServerLevel level) {
        long now = level.getGameTime();
        meter.tick(level, energy, spec().energyBuffer(), 2 * spec().fePerTick(), worldPosition, worldPosition.above(), worldPosition.above(2));
        AbsentWards.Machine m = AbsentWards.touch(level, worldPosition, true);
        boolean settled = settle(level, m, now);
        if (settled || lastMirror == Long.MIN_VALUE || now - lastMirror >= AbsentWards.MIRROR_TICKS) mirror(level, m, now);
    }

    /** 留守だったなら今すぐ精算する(物の口・右クリックで中を触る前に。留守の間に変わったフィルターを古い中身で上書きしないように)。 */
    public void settleIfAway() {
        if (!(level instanceof ServerLevel server)) return;
        AbsentWards.Machine m = AbsentWards.find(server, worldPosition, true);
        if (m != null && settle(server, m, server.getGameTime())) mirror(server, m, server.getGameTime());
    }

    /**
     * 精算: FE は今の値と覚えから出した値の小さい方へ、フィルターは留守の間に覚えが変わっていれば覚えの物へ。精算したら true。
     * 何度呼んでも二重には減らない(覚えはすぐ書き写し直すので、次は留守でない)。
     */
    private boolean settle(ServerLevel level, AbsentWards.Machine m, long now) {
        if (!m.isAway(now)) return false;
        int at = m.energyAt(now);
        if (at < energy) energy = at;
        if (m.revision != seenRevision && m.stock != null) {
            stock = m.stock.copy();
            loadCarry = m.loadCarry;
            seenRevision = m.revision;
        }
        meter.reset();
        setChanged();
        return true;
    }

    private void mirror(ServerLevel level, AbsentWards.Machine m, long now) {
        m.energy = energy;
        m.drainPerTick = spec().fePerTick();
        m.inflowPerTick = meter.inflow(now);
        m.producerLinked = meter.linked();
        m.supplied = meter.supplied(energy, spec().energyBuffer(), 2 * spec().fePerTick());
        m.stock = stock.copy();
        m.loadCarry = loadCarry;
        m.revision = seenRevision;
        AbsentWards.mirrored(level, m);
        lastMirror = now;
    }

    /** フィルターが変わった: すぐ覚えへ書き写す(留守になる直前の物を取りこぼさない)。 */
    private void stockChanged() {
        setChanged();
        if (level instanceof ServerLevel server) {
            AbsentWards.Machine m = AbsentWards.touch(server, worldPosition, true);
            if (!m.isAway(server.getGameTime())) mirror(server, m, server.getGameTime());
        }
    }

    /** テスト用: 今すぐ覚えへ書き写す。 */
    public void mirrorForTests() {
        if (level instanceof ServerLevel server) mirror(server, AbsentWards.touch(server, worldPosition, true), server.getGameTime());
    }

    // ---------------------------------------------------------------- 場とフィルター

    /** 1 回分の場の更新で抜ける量(場の単位)を、フィルターの残りで切った値。{@code steps} = 進める更新の回数。 */
    public double budgetUnits(int steps, SporeFieldTable table) {
        if (stock.filter.isEmpty()) return 0.0D;
        double perUnit = loadPerUnit(table);
        double byFilter = perUnit <= 0.0D ? Double.POSITIVE_INFINITY : AirFilters.remaining(stock.filter) / perUnit;
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
        if (!stock.filter.isEmpty() && units > 0.0D) {
            loadCarry += units * loadPerUnit(table);
            int whole = (int) loadCarry;
            loadCarry -= whole;
            AirFilters.absorb(stock.filter, whole);
            stock.rotate();
            stockChanged();
        }
        setChanged();
    }

    /** 右クリック・置いたときの表示。 */
    public Component info() {
        ItemStack filter = stock.filter;
        String state = running() ? "running" : filter.isEmpty() ? "no_filter" : AirFilters.clogged(filter) ? "clogged"
                : energy < spec().fePerTick() ? "no_power" : "idle";
        Component head = Component.translatable("message.overgrown_vegetation.ley_ventilator." + state);
        String filterPart = filter.isEmpty() ? "-" : String.valueOf(AirFilters.percentLeft(filter));
        String pulled = Double.isNaN(lastPulledPercent) ? "-" : LeyText.one(lastPulledPercent);
        return head.copy().append(Component.translatable("message.overgrown_vegetation.ley_ventilator.info",
                        filterPart, LeyText.fe(energy), LeyText.fe(spec().energyBuffer()), pulled))
                .append(Component.translatable("message.overgrown_vegetation.ley_ventilator.stock",
                        stock.spareFilled(), stock.spares.length, stock.cloggedFilled(), stock.clogged.length));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        stock.save(tag, registries); // 差してある 1 枚は前と同じ "filter"
        tag.putDouble("loadCarry", loadCarry);
        tag.putInt("seenRevision", seenRevision);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = Math.max(0, tag.getInt("energy"));
        stock = new VentStock();
        stock.load(tag, registries);
        loadCarry = tag.getDouble("loadCarry");
        seenRevision = tag.getInt("seenRevision");
    }

    // ---------------------------------------------------------------- 物の口(11-23)

    /**
     * 予備({@code ins})と詰まった物の置き場({@code ext})を並べた口。予備へは詰まっていないフィルターを 1 枚ずつ入れるだけ、
     * 置き場からは出すだけ。
     */
    private final class ItemPort implements IItemHandler {
        private final boolean ins;
        private final boolean ext;

        ItemPort(boolean ins, boolean ext) {
            this.ins = ins;
            this.ext = ext;
        }

        private int spareSlots() {
            return ins ? stock.spares.length : 0;
        }

        @Override
        public int getSlots() {
            return spareSlots() + (ext ? stock.clogged.length : 0);
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            int s = spareSlots();
            if (slot < 0) return ItemStack.EMPTY;
            if (slot < s) return stock.spares[slot];
            if (ext && slot - s < stock.clogged.length) return stock.clogged[slot - s];
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
            if (!simulate) settleIfAway();
            if (!stock.spares[slot].isEmpty()) return stack;
            if (!simulate) {
                stock.spares[slot] = stack.copyWithCount(1);
                stockChanged();
            }
            return stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int s = spareSlots();
            if (amount <= 0 || !ext || slot < s || slot - s >= stock.clogged.length) return ItemStack.EMPTY;
            if (!simulate) settleIfAway();
            ItemStack in = stock.clogged[slot - s];
            if (in.isEmpty()) return ItemStack.EMPTY;
            if (simulate) return in.copy();
            stock.clogged[slot - s] = ItemStack.EMPTY;
            stockChanged();
            return in;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot >= 0 && slot < spareSlots() && AirFilters.isFilter(stack) && !AirFilters.clogged(stack);
        }
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
