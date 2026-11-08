package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 呼吸根(ヌマスギの膝根のような、湿った地面に群れる短く太い根。2026-11-07、Vault「巨大樹の根と根のアーチの作り直し」決定 6)。
 * 平地から斜めに突き出していた根の先端({@link CorruptionRootTip} の地上の形)の代わり。
 * 形はここで決め、置き方は呼び出し元が持つ(巨大樹は計画 {@code Plan} へ、汚染の広がりはワールドへ直接 {@link #place})。
 *
 * <p>数はすべて Claude の仮決め: 1 群れ 3〜7 本・広がり 半径 4・高さ 1〜3・高さ 2 以上の半分は 2×2 の太い膝(上の 1 段は 1 マスで丸める)。
 * 湿った地面 = 水平 2 マス以内の、地面と同じ高さか 1 つ上に水がある。
 */
public final class Pneumatophores {

    private Pneumatophores() {
    }

    /** 1 本の膝: 列 {@code (x, z)}・地面から上の高さ・太い(2×2)か。 */
    public interface Sink {
        void knee(int x, int z, int height, boolean wide);
    }

    /** {@code (cx, cz)} のまわり半径 {@code spread} に {@code count} 本の膝を散らす。 */
    public static void cluster(RandomSource r, int cx, int cz, int count, int spread, Sink sink) {
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            double d = Math.sqrt(r.nextDouble()) * spread;
            int x = cx + (int) Math.round(Math.cos(a) * d);
            int z = cz + (int) Math.round(Math.sin(a) * d);
            int h = 1 + r.nextInt(3);
            boolean wide = h >= 2 && r.nextBoolean();
            sink.knee(x, z, h, wide);
        }
    }

    /** 群れの本数(3〜7)。 */
    public static int rollCount(RandomSource r) {
        return 3 + r.nextInt(5);
    }

    /** 地面 {@code (x, gy, z)} が湿っているか: 水平 2 マス以内の高さ gy か gy+1 に水。 */
    public static boolean isWet(BlockGetter level, int x, int gy, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (level.getFluidState(p.set(x + dx, gy + dy, z + dz)).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 膝 1 本のマス: 地面の 1 段(置き換え)から上へ {@code height} 段。太い膝は 2×2 で、上の 1 段だけ 1 マス。
     * {@code ground} はその列の地面(固い面)の Y。
     */
    public static void kneeCells(int x, int ground, int z, int height, boolean wide, CellSink out) {
        for (int y = ground; y <= ground + height; y++) {
            out.cell(x, y, z);
            if (wide && y < ground + height) {
                out.cell(x + 1, y, z);
                out.cell(x, y, z + 1);
                out.cell(x + 1, y, z + 1);
            }
        }
    }

    public interface CellSink {
        void cell(int x, int y, int z);
    }

    /**
     * 汚染の広がりから: 地面 {@code ground}(地表のブロック)のまわりに呼吸根の群れをワールドへ直接置く。
     * 各膝の列は地面の高さを ±2 で探し、空気・水・置き換え可能なマスだけを根にする(家・木は壊さない)。
     */
    public static boolean place(ServerLevel level, RandomSource r, BlockPos ground, BlockState log,
            LongOpenHashSet touchedChunks, List<BlockPos> shellOut) {
        boolean[] any = {false};
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        cluster(r, ground.getX(), ground.getZ(), rollCount(r), 4, (x, z, h, wide) -> {
            int g = groundNear(level, x, ground.getY(), z);
            if (g == Integer.MIN_VALUE) {
                return;
            }
            kneeCells(x, g, z, h, wide, (cx, cy, cz) -> {
                p.set(cx, cy, cz);
                if (!level.isLoaded(p)) {
                    return;
                }
                BlockState s = level.getBlockState(p);
                // 地面の 1 段は自然の地面のときだけ置き換える。それ以外は空気・水・置き換え可能なマスだけ(構造物は壊さない)。
                if (!(s.isAir() || s.canBeReplaced())
                        && !(cy == g && com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature.isRootTerrain(s))) {
                    return;
                }
                if (s.getBlock().defaultDestroyTime() < 0.0F) {
                    return;
                }
                BlockPos at = p.immutable();
                level.setBlock(at, log, Block.UPDATE_ALL);
                touchedChunks.add(new ChunkPos(at).toLong());
                shellOut.add(at);
                any[0] = true;
            });
        });
        return any[0];
    }

    /** 列 {@code (x, z)} の、{@code y} の上下 2 段で「固くて真上が空気か水」のブロックの Y。無ければ MIN_VALUE。 */
    private static int groundNear(ServerLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int yy = y + 2; yy >= y - 2; yy--) {
            p.set(x, yy, z);
            if (!level.isLoaded(p)) {
                return Integer.MIN_VALUE;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced()) {
                BlockState up = level.getBlockState(p.set(x, yy + 1, z));
                return (up.isAir() || up.canBeReplaced()) ? yy : Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;
    }
}
