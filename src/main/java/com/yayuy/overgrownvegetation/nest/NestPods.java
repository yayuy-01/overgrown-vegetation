package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.block.ArboraStoragePodBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 巣の<b>貯蔵胞</b>を探して物を入れる(2026-09-20)。
 *
 * <p>使うのは 2 か所 —— 巣の中で死んだプレイヤーの持ち物({@link NestDeaths})と、
 * アルボラが狩りの戦利品を運び込むとき({@code entity/ArboraHaulGoal})。
 *
 * <p>貯蔵胞の座標は記録していないので、貯蔵室の中心のまわりを見て拾う。
 * 部屋の半径も記録していないため、少し広め({@link #ROOM_SCAN})に見る。
 */
public final class NestPods {

    /** 貯蔵室の中心から貯蔵胞を探す半径。 */
    private static final int ROOM_SCAN = 6;

    /** その巣の貯蔵胞の座標。 */
    public static List<BlockPos> find(ServerLevel level, NestData.Nest nest) {
        List<BlockPos> out = new ArrayList<>();
        for (NestData.ChamberRecord chamber : nest.chambers()) {
            if (chamber.type() != NestChamber.STORAGE) {
                continue;
            }
            collect(level, chamber.pos(), out);
        }
        return out;
    }

    /** {@code from} にいちばん近い貯蔵胞。1 つも無ければ null。 */
    @Nullable
    public static BlockPos nearest(ServerLevel level, NestData.Nest nest, BlockPos from) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pod : find(level, nest)) {
            double sq = pod.distSqr(from);
            if (sq < bestSq) {
                bestSq = sq;
                best = pod;
            }
        }
        return best;
    }

    private static void collect(ServerLevel level, BlockPos centre, List<BlockPos> out) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -ROOM_SCAN; dx <= ROOM_SCAN; dx++) {
            for (int dy = -ROOM_SCAN; dy <= ROOM_SCAN; dy++) {
                for (int dz = -ROOM_SCAN; dz <= ROOM_SCAN; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (level.getBlockEntity(cursor) instanceof ArboraStoragePodBlockEntity) {
                        out.add(cursor.immutable());
                    }
                }
            }
        }
    }

    /** 空きのある貯蔵胞へ順に押し込み、入り切らなかったぶんを返す。 */
    public static ItemStack insert(ServerLevel level, List<BlockPos> pods, ItemStack stack) {
        ItemStack rest = stack;
        for (BlockPos pos : pods) {
            if (rest.isEmpty()) {
                break;
            }
            if (level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity pod && pod.hasRoom()) {
                rest = pod.tryInsert(rest);
            }
        }
        return rest;
    }

    /** 1 つの貯蔵胞へ入れる。入り切らなかったぶんを返す。 */
    public static ItemStack insertAt(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity pod) {
            return pod.tryInsert(stack);
        }
        return stack;
    }

    private NestPods() {
    }
}
