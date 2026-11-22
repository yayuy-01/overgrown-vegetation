package com.yayuy.overgrownvegetation.entity.cling;

import com.yayuy.overgrownvegetation.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 「全面に張り付く移動」(層 1 の移動の型 ①、2026-11-05)の<b>ブロックの決まり</b>。どのマスに体が入れるか・どの面に張り付けるか。
 *
 * <p>引き数は {@link BlockGetter} だけ —— 経路は裏のスレッドで<b>世界の写し</b>({@link WorldSnapshot})の上で引くので、
 * ここから {@code Level} や Mob を読まない。体は<b>1 マスに収まる大きさ</b>(アルボラは 0.98 立方)を前提にしている。
 * 大きい種(ヘイノボリ・スコロムネス)は当たりを小さめにするか節に分ける(設計ノート「全面に張り付く移動」3 節)。
 *
 * <p>前の作り({@code parked/…/entity/SurfaceCrawl})の判定を引き継いだもの:
 * <ul>
 *     <li>{@link #raisedFromBelow} —— 塀・フェンス(当たりの高さ 1.5)の上の 0.5 は、横から見れば張り付く面、上から見れば床(10-02)。</li>
 *     <li>張り付く面 = 隣のブロックに当たりがある({@link #isAnchor})。</li>
 * </ul>
 * 新しく足したもの(11-05 ユーザーの答え):
 * <ul>
 *     <li>{@link ModTags#UNCLINGABLE} の面には壁・天井として張り付かない(床としては乗れる)。今はタグが空。</li>
 *     <li>{@link ModTags#BURROWABLE_FOLIAGE}(葉)のマスは<b>中へ潜れる</b>。葉の外の面には普通に張り付く。</li>
 *     <li>水(どんな液体も)の入ったマスは歩ける場所にしない(水の中では張り付かない)。</li>
 * </ul>
 */
public final class ClingRules {

    /** 床の上のマスとして数える、下のブロックの当たりの高さの下限。半ブロック(下付き)は「そのマスの中の床」として別に数える。 */
    private static final double FLOOR_MIN_TOP = 0.75D;
    /** そのマスの中に低い床(下付きの半ブロック・カーペット・雪 4 枚まで)があっても体が入るとみなす高さ。 */
    private static final double LOW_FLOOR_MAX = 0.5625D;

    private ClingRules() {
    }

    /** 中へ潜れる葉か。 */
    public static boolean isFoliage(BlockState state) {
        return state.is(ModTags.BURROWABLE_FOLIAGE);
    }

    /** 液体が入っている(水の中では張り付かない。溶岩にも入らない)。 */
    public static boolean isWet(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /**
     * 体({@code ≤ 1} マス)が<b>張り付いて</b>居られるマスか。葉のマスは別({@link #isFoliageCell})。
     * 当たりが無いか、下付きの低い床(半ブロック・カーペット)だけで、その上のマスも空いている。
     */
    public static boolean canOccupy(BlockGetter g, BlockPos cell) {
        BlockState s = g.getBlockState(cell);
        if (isWet(s) || isFoliage(s) || isHazard(s)) {
            return false;
        }
        VoxelShape shape = s.getCollisionShape(g, cell);
        if (!shape.isEmpty()) {
            if (shape.min(Direction.Axis.Y) > 1.0E-4D || shape.max(Direction.Axis.Y) > LOW_FLOOR_MAX) {
                return false;
            }
            // 低い床の上の体は上のマスへはみ出す。
            BlockPos up = cell.above();
            BlockState us = g.getBlockState(up);
            if (isWet(us) || (!isFoliage(us) && !us.getCollisionShape(g, up).isEmpty())) {
                return false;
            }
        }
        return !raisedFromBelow(g, cell);
    }

    /** 中へ潜れる葉のマス(液体が入っていない)。 */
    public static boolean isFoliageCell(BlockGetter g, BlockPos cell) {
        BlockState s = g.getBlockState(cell);
        return isFoliage(s) && !isWet(s);
    }

    /** 入ると傷む・絡まるマス(火・甘い実の茂み・クモの巣・粉雪)。経路に入れない。 */
    private static boolean isHazard(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COBWEB) || s.is(Blocks.POWDER_SNOW)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE);
    }

    /**
     * {@code cell} の {@code dir} 側の面に張り付けるか。{@link Direction#DOWN} は「床に立てるか」({@link #hasFloor})。
     * 壁・天井は、隣のブロックに当たりがあり、{@link ModTags#UNCLINGABLE} でないこと。
     * 横の面は、隣のマスの当たりが無くても<b>その下のブロックの当たりが食い込んでいれば</b>数える(塀・フェンスの上の 0.5)。
     */
    public static boolean isAnchor(BlockGetter g, BlockPos cell, Direction dir) {
        if (dir == Direction.DOWN) {
            return hasFloor(g, cell);
        }
        BlockPos side = cell.relative(dir);
        BlockState s = g.getBlockState(side);
        if (!s.getCollisionShape(g, side).isEmpty()) {
            return !s.is(ModTags.UNCLINGABLE);
        }
        if (dir.getAxis().isHorizontal() && raisedFromBelow(g, side)) {
            BlockPos below = side.below();
            return !g.getBlockState(below).is(ModTags.UNCLINGABLE);
        }
        return false;
    }

    /**
     * {@code cell} に<b>床</b>があるか: 下のブロックの当たりが上端近くまである / そのマスの中に下付きの低い床がある /
     * 1 つ下のマスへ塀の当たりが食い込んでいる(塀の上に立つ)。張り付けない面({@link ModTags#UNCLINGABLE})でも床には立てる。
     */
    public static boolean hasFloor(BlockGetter g, BlockPos cell) {
        VoxelShape own = g.getBlockState(cell).getCollisionShape(g, cell);
        if (!own.isEmpty()) {
            return true; // canOccupy を通ったマスなら、これは下付きの低い床
        }
        BlockPos below = cell.below();
        VoxelShape b = g.getBlockState(below).getCollisionShape(g, below);
        if (!b.isEmpty() && b.max(Direction.Axis.Y) >= FLOOR_MIN_TOP) {
            return true;
        }
        return b.isEmpty() && raisedFromBelow(g, below);
    }

    /**
     * {@code cell} のマスに、<b>真下のブロックの当たりが食い込んでいる</b>か(フェンス・塀の当たりの高さは 1.5)。
     * はみ出す当たりを持つブロックはバニラが前もって数えている({@code hasLargeCollisionShape})ので、ほとんどのマスはそこで終わる。
     */
    public static boolean raisedFromBelow(BlockGetter g, BlockPos cell) {
        BlockPos below = cell.below();
        BlockState state = g.getBlockState(below);
        return state.hasLargeCollisionShape() && state.getCollisionShape(g, below).max(Direction.Axis.Y) > 1.0D;
    }

    /** 床の高さ(ワールドの y)。{@link #hasFloor} が真のマスで呼ぶ。 */
    public static double floorY(BlockGetter g, BlockPos cell) {
        VoxelShape own = g.getBlockState(cell).getCollisionShape(g, cell);
        if (!own.isEmpty()) {
            return cell.getY() + own.max(Direction.Axis.Y);
        }
        BlockPos below = cell.below();
        VoxelShape b = g.getBlockState(below).getCollisionShape(g, below);
        if (!b.isEmpty()) {
            return below.getY() + Math.min(1.0D, b.max(Direction.Axis.Y));
        }
        BlockPos below2 = below.below();
        VoxelShape b2 = g.getBlockState(below2).getCollisionShape(g, below2);
        return b2.isEmpty() ? cell.getY() : below2.getY() + b2.max(Direction.Axis.Y);
    }

    /**
     * {@code cell} の {@code dir} 側の面の位置(その軸のワールド座標)。横の面は隣のブロックの当たりの縁(フェンスの柱なら奥まる)、
     * 天井は上のブロックの当たりの下端。{@link #isAnchor} が真の面で呼ぶ。
     */
    public static double facePlane(BlockGetter g, BlockPos cell, Direction dir) {
        BlockPos side = cell.relative(dir);
        VoxelShape s = g.getBlockState(side).getCollisionShape(g, side);
        if (s.isEmpty() && dir.getAxis().isHorizontal()) {
            side = side.below();
            s = g.getBlockState(side).getCollisionShape(g, side);
        }
        Direction.Axis axis = dir.getAxis();
        int base = switch (axis) {
            case X -> side.getX();
            case Y -> side.getY();
            case Z -> side.getZ();
        };
        if (s.isEmpty()) {
            return dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? base : base + 1.0D;
        }
        return dir.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? base + s.min(axis)
                : base + s.max(axis);
    }
}
