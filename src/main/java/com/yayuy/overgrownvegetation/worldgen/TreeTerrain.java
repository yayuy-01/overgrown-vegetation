package com.yayuy.overgrownvegetation.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import javax.annotation.Nullable;

/**
 * 巨大樹の計画({@link GiantTreeFeature} の buildPlan)が地形を読む窓(2026-11-18 大群の軽量化 B の案 A)。
 * 計画は読むだけなので、読めるのはブロック・流体・高さの範囲と「そのチャンクは読み込まれているか」だけにしてある。
 *
 * <ul>
 *     <li>{@link #live} —— 生きた世界をそのまま読む(世界生成の並列スレッド・テスト)。</li>
 *     <li>{@link TreeTerrainSnapshot} —— 苗木から育つとき、サーバースレッドで周りのチャンクを写しておき、
 *     計画は別スレッドでその写しを読む(別スレッドから生きた世界は読めない: {@code getChunk} はサーバースレッドへ頼んで待ち、
 *     {@code getChunkNow} は null を返す)。</li>
 * </ul>
 */
public interface TreeTerrain extends BlockGetter {

    /** そのマスのチャンクが読み込まれているか(高さの範囲の外は false)。生きた世界の {@code Level#isLoaded} と同じ意味。 */
    boolean isLoaded(BlockPos pos);

    /** 生きた世界をそのまま読む窓。 */
    static TreeTerrain live(WorldGenLevel level) {
        return new TreeTerrain() {
            @Override
            public boolean isLoaded(BlockPos pos) {
                return level.getLevel().isLoaded(pos);
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null; // 計画は使わない
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return level.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return level.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return level.getMinBuildHeight();
            }
        };
    }
}
