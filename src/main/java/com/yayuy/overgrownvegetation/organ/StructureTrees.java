package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.worldgen.fusion.FusionTable;
import com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 構造物の上の苗の木(段 2b)を置く手伝いと、段 2〜2d の構造物の木の置き残し。
 *
 * <p>2026-11-19 夜 段 3「乗っ取り」への作り直しで、構造物の木(中から/てっぺんから)・中くらいの木・構造物に植える器官は消した
 * (ユーザー「構造物専用の動かし方はうまくいかない気がする」)。保存してある置きかけの木だけは、最後まで置く(新しくは積まない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StructureTrees extends SavedData {
    private static final String ID = OvergrownVegetation.MODID + "_structure_trees";
    private static final Factory<StructureTrees> FACTORY = new Factory<>(StructureTrees::new, (tag, registries) -> load(tag), null);

    /** 置いている途中の木(段 2〜2d の置き残し)。 */
    private final List<Job> jobs = new ArrayList<>();

    static final class Job {
        final long[] pos;
        final byte[] mat;
        int cursor;

        Job(long[] pos, byte[] mat, int cursor) {
            this.pos = pos;
            this.mat = mat;
            this.cursor = cursor;
        }
    }

    public static StructureTrees get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public int pendingJobs() {
        return jobs.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        StructureTrees data = get(level);
        if (!data.jobs.isEmpty()) data.placeSome(level, StructureFusion.table());
    }

    private void placeSome(ServerLevel level, FusionTable t) {
        if (!TickBudget.mayStart(PerfJob.GRAMMAR_GROWTH)) return;
        long start = TickBudget.begin();
        int placed = 0, budget = t.treeBlocksPerTick;
        Job j = jobs.get(0);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (j.cursor < j.pos.length && budget > 0) {
            m.set(j.pos[j.cursor]);
            if (level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4) == null) break; // 読み込まれるまで待つ
            j.cursor++;
            budget--;
            if (placeOne(level, m, j.mat[j.cursor - 1])) placed++;
        }
        if (j.cursor >= j.pos.length) jobs.remove(0);
        setDirty();
        TickBudget.end(PerfJob.GRAMMAR_GROWTH, start, placed);
    }

    /** 1 マス置く(空気・置き換えられる草花・水なしのときだけ)。 */
    static boolean placeOne(ServerLevel level, BlockPos p, byte mat) {
        BlockState cur = level.getBlockState(p);
        boolean open = (cur.isAir() || cur.canBeReplaced()) && cur.getFluidState().isEmpty();
        // 幹が突き破るマス(段 3 追記 11-20): 人工のブロック(中身の無い物)だけを置き換える
        boolean breakThrough = mat == StructureTree.TRUNK_BREAK && !cur.hasBlockEntity() && Takeover.isArtificial(level, p, cur);
        if (!open && !breakThrough) return false;
        BlockState s = stateOf(mat);
        if (s == null) return false;
        return level.setBlock(p, s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** 幹・根・枝は樹皮だけの過繁茂した木、葉は過繁茂の葉。 */
    @Nullable
    static BlockState stateOf(byte mat) {
        return switch (mat) {
            case StructureTree.TRUNK, StructureTree.TRUNK_BREAK, StructureTree.ROOT, StructureTree.BRANCH -> ModBlocks.OVERGROWN_WOOD.get().defaultBlockState();
            case StructureTree.LEAF -> ModBlocks.OVERGROWN_LEAVES.get().defaultBlockState();
            case StructureTree.GIANT_ROOT -> ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            default -> null;
        };
    }

    /** 計画を今すぐ全部置く(構造物の上の苗の木・テスト)。置いた数を返す。 */
    public static int placeAllNow(ServerLevel level, StructureTree.Plan plan) {
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < plan.pos().length; i++) {
            if (placeOne(level, m.set(plan.pos()[i]), plan.mat()[i])) n++;
        }
        return n;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Job j : jobs) {
            CompoundTag c = new CompoundTag();
            c.put("pos", new LongArrayTag(j.pos));
            c.putByteArray("mat", j.mat);
            c.putInt("cursor", j.cursor);
            list.add(c);
        }
        tag.put("jobs", list);
        return tag;
    }

    private static StructureTrees load(CompoundTag tag) {
        StructureTrees d = new StructureTrees();
        ListTag list = tag.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            long[] pos = c.getLongArray("pos");
            byte[] mat = c.getByteArray("mat");
            if (pos.length != mat.length) continue;
            d.jobs.add(new Job(pos, mat, c.getInt("cursor")));
        }
        return d;
    }
}
