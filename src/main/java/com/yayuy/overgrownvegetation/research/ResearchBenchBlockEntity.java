package com.yayuy.overgrownvegetation.research;

import com.yayuy.overgrownvegetation.registry.ModResearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * <b>研究台</b>の中身(2026-11-17 決定 20・23)。
 *
 * <ul>
 *     <li>FE の口は受け取りだけ(換気機・洗い場と同じ。汲み上げ器の隣・柱からの線・ほかの MOD の発電機)。中に {@code bench_buffer} までためる。</li>
 *     <li>世界の研究({@link ResearchData})がこの研究台で走っていれば、1 tick に {@code fe_per_tick} を食って 1 進む。力が足りない tick は進まない。</li>
 *     <li>離れていた(チャンクが読まれていなかった)間も進み続ける: 戻ったときに、経った tick の分だけ、ためた力で払える分まで進む(決定 23)。</li>
 *     <li>段 2・段 3 は器具がそばに無いと止まる(決定 23。調べ直しは 20 tick ごと)。</li>
 * </ul>
 */
public class ResearchBenchBlockEntity extends BlockEntity {

    private int energy;
    private long lastTick = Long.MIN_VALUE;
    private boolean instrumentsOk = true;
    private long instrumentsCheckedAt = Long.MIN_VALUE;

    private final IEnergyStorage port = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int toReceive, boolean simulate) {
            ResearchTree tree = ResearchTree.get();
            int n = Math.max(0, Math.min(Math.min(toReceive, tree.benchMaxReceive()), tree.benchBuffer() - energy));
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
            return ResearchTree.get().benchBuffer();
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

    public ResearchBenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModResearch.BENCH_BE.get(), pos, state);
    }

    public IEnergyStorage energyPort() {
        return port;
    }

    public int energy() {
        return energy;
    }

    /** GameTest・開発用。 */
    public void setEnergy(int e) {
        energy = Math.max(0, e);
        setChanged();
    }

    /** 研究を始めたとき: 経った時間を今から数え直す(始める前の時間で進まないように)。 */
    void resetClock() {
        lastTick = level != null ? level.getGameTime() : Long.MIN_VALUE;
        instrumentsCheckedAt = Long.MIN_VALUE;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ResearchBenchBlockEntity be) {
        be.tickOnce();
    }

    /** 1 回ぶん(GameTest からも呼ぶ)。進んだ tick 数を返す。 */
    public int tickOnce() {
        if (!(level instanceof ServerLevel server)) return 0;
        long now = server.getGameTime();
        long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(0, now - lastTick);
        lastTick = now;
        ResearchData data = ResearchData.get(server.getServer());
        ResearchData.Active a = data.active();
        if (a == null || !a.bench.equals(GlobalPos.of(server.dimension(), worldPosition))) return 0;
        ResearchTree.Node node = ResearchTree.get().node(a.node);
        if (node == null) {
            Research.cancel(server.getServer(), null); // 設計を変えて節が消えた: 標本を落として終わる
            return 0;
        }
        if (instrumentsCheckedAt == Long.MIN_VALUE || now - instrumentsCheckedAt >= 20) {
            instrumentsOk = Research.instrumentsPresent(server, worldPosition, node.tier());
            instrumentsCheckedAt = now;
        }
        if (!instrumentsOk) return 0;
        int remaining = Math.max(0, node.ticks() - a.progress);
        long affordable = node.fePerTick() <= 0 ? Long.MAX_VALUE : energy / node.fePerTick();
        int step = (int) Math.min(Math.min(elapsed, remaining), affordable);
        if (step > 0) {
            a.progress += step;
            energy -= step * node.fePerTick();
            data.setDirty();
            setChanged();
        }
        if (a.progress >= node.ticks()) {
            Research.complete(server.getServer());
        }
        return step;
    }

    /** GameTest 用: 前の tick を {@code ticksAgo} tick 前にずらす(離れていた間の分を試す)。 */
    public void pretendAway(long ticksAgo) {
        if (level != null) lastTick = level.getGameTime() - ticksAgo;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("energy", energy);
        if (lastTick != Long.MIN_VALUE) tag.putLong("last_tick", lastTick);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = tag.getInt("energy");
        lastTick = tag.contains("last_tick") ? tag.getLong("last_tick") : Long.MIN_VALUE;
    }
}
