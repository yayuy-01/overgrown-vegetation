package com.yayuy.overgrownvegetation.entity.cling;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * 経路を裏のスレッドで引くための<b>世界の写し</b>(2026-11-05)。箱にかかるチャンクの区画(16³)の中身を、サーバーのスレッドで
 * {@link PalettedContainer#copy()} して持つ。写した後はサーバーがブロックを書き換えても影響を受けない
 * (バニラの {@code PathNavigationRegion} は区画そのものを指すだけなので、別のスレッドから読むと書き換えの途中を見うる)。
 *
 * <p>箱の外は空気・読み込まれていないチャンクは岩盤(固い)と答える。経路は箱の内側({@link #inside})だけで引くこと。
 * ブロックエンティティは持たない(当たりの形を見るだけなので要らない)。
 */
public final class WorldSnapshot implements BlockGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState UNLOADED = Blocks.BEDROCK.defaultBlockState();

    private final int minY;
    private final int height;
    private final BlockPos min;
    private final BlockPos max;
    private final int sx0, sy0, sz0, nsx, nsy, nsz;
    /** 区画ごとの写し。null = 空気だけの区画。{@link #unloaded} が真の区画は読み込まれていない。 */
    private final PalettedContainer<BlockState>[] sections;
    private final boolean[] unloaded;

    @SuppressWarnings("unchecked")
    private WorldSnapshot(ServerLevel level, BlockPos min, BlockPos max) {
        this.minY = level.getMinBuildHeight();
        this.height = level.getHeight();
        this.min = min.immutable();
        this.max = max.immutable();
        this.sx0 = SectionPos.blockToSectionCoord(min.getX());
        this.sy0 = SectionPos.blockToSectionCoord(min.getY());
        this.sz0 = SectionPos.blockToSectionCoord(min.getZ());
        this.nsx = SectionPos.blockToSectionCoord(max.getX()) - sx0 + 1;
        this.nsy = SectionPos.blockToSectionCoord(max.getY()) - sy0 + 1;
        this.nsz = SectionPos.blockToSectionCoord(max.getZ()) - sz0 + 1;
        this.sections = new PalettedContainer[nsx * nsy * nsz];
        this.unloaded = new boolean[nsx * nsy * nsz];
        for (int ix = 0; ix < nsx; ix++) {
            for (int iz = 0; iz < nsz; iz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sx0 + ix, sz0 + iz);
                for (int iy = 0; iy < nsy; iy++) {
                    int idx = index(ix, iy, iz);
                    if (chunk == null) {
                        this.unloaded[idx] = true;
                        continue;
                    }
                    int sy = sy0 + iy;
                    int si = chunk.getSectionIndexFromSectionY(sy);
                    if (si < 0 || si >= chunk.getSectionsCount()) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(si);
                    if (!section.hasOnlyAir()) {
                        this.sections[idx] = section.getStates().copy();
                    }
                }
            }
        }
    }

    /** サーバーのスレッドで {@code min}〜{@code max}(両端を含む)を写す。 */
    public static WorldSnapshot capture(ServerLevel level, BlockPos min, BlockPos max) {
        return new WorldSnapshot(level, min, max);
    }

    private int index(int ix, int iy, int iz) {
        return (ix * nsz + iz) * nsy + iy;
    }

    /** 経路の点に使ってよいマス(箱の縁の 1 マスは隣を読むので除く)。 */
    public boolean inside(BlockPos p) {
        return p.getX() > min.getX() && p.getX() < max.getX()
                && p.getY() > min.getY() && p.getY() < max.getY()
                && p.getZ() > min.getZ() && p.getZ() < max.getZ();
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int y = pos.getY();
        if (y < minY || y >= minY + height) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        int ix = SectionPos.blockToSectionCoord(pos.getX()) - sx0;
        int iy = SectionPos.blockToSectionCoord(y) - sy0;
        int iz = SectionPos.blockToSectionCoord(pos.getZ()) - sz0;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= nsx || iy >= nsy || iz >= nsz) {
            return AIR;
        }
        int idx = index(ix, iy, iz);
        if (unloaded[idx]) {
            return UNLOADED;
        }
        PalettedContainer<BlockState> states = sections[idx];
        return states == null ? AIR : states.get(pos.getX() & 15, y & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }
}
