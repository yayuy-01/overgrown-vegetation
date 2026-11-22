package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * 歩ける場所 1 つ =「空いたマス」+「そのマスで張り付いている面の向き」(2026-11-05、設計ノート「全面に張り付く移動」1 節)。
 *
 * <p>床・壁・天井の区別は無い —— 床は「下向きの面({@link Direction#DOWN})」に張り付いているだけ。
 * ほかに<b>葉の中</b>({@link #FOLIAGE})がある: 葉のマスの中に潜っていて、どの面にも張り付いていない(3 次元に動ける)。
 *
 * @param cell   体が居るマス
 * @param anchor 張り付いている面の向き({@link Direction#get3DDataValue()}、0〜5)か {@link #FOLIAGE}
 */
public record SurfaceNode(BlockPos cell, int anchor) {

    /** 葉の中に潜っている。 */
    public static final int FOLIAGE = 6;
    /** どこにも付いていない(宙・水の中)。経路の点には出てこない。同期の値にだけ使う。 */
    public static final int NONE = 7;

    public SurfaceNode {
        cell = cell.immutable();
    }

    public static SurfaceNode of(BlockPos cell, Direction anchor) {
        return new SurfaceNode(cell, anchor.get3DDataValue());
    }

    public static SurfaceNode foliage(BlockPos cell) {
        return new SurfaceNode(cell, FOLIAGE);
    }

    /** 張り付いている面の向き。葉の中なら null。 */
    @Nullable
    public Direction face() {
        return anchor < 6 ? Direction.from3DDataValue(anchor) : null;
    }

    public boolean isFloor() {
        return anchor == Direction.DOWN.get3DDataValue();
    }

    public boolean isFoliage() {
        return anchor == FOLIAGE;
    }

    @Override
    public String toString() {
        Direction f = face();
        return cell.getX() + "," + cell.getY() + "," + cell.getZ() + ":" + (f != null ? f.getName() : "foliage");
    }
}
