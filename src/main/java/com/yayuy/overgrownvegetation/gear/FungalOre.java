package com.yayuy.overgrownvegetation.gear;

import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>菌が集めた金属</b>の鉱石のでき方(2026-11-17 決定 18)。岩の中に入り込んだ菌糸が、地脈の力で金属を吸い集めて固めた塊。
 * 汚染が来て初めてできるので、世界生成では置かない: <b>汚染が岩に来て、過繁茂の石・深層岩になったとき</b>、そこが地脈の線から {@code leyReach} 以内なら、
 * 確率 {@code chance} で鉱脈の芯になり、まわりの同じ岩へ {@code veinMin}〜{@code veinMax} 個に広がる。洞窟の壁・崖の両方【案・全体管理へ 11-17】。
 *
 * <p>呼ぶのは {@code CorruptionSpreader#growSurfacePlantsInScope} の頭(汚染したブロックの装飾の口)。植物の設定・ネザーの早い戻りより前なので、
 * 植物を生やさない設定でも鉱石はできる。地上の世界(オーバーワールド)だけ。既に汚染し終えた岩には後から出ない。
 */
public final class FungalOre {

    private FungalOre() {
    }

    /** 汚染の骨粉で装飾し直している間は真(同じ岩を何度もくじにかけない。サーバーの 1 本の流れの中だけで立てる)。 */
    public static boolean suppressed;

    /** 汚染したブロック {@code pos}(今 {@code hostState})で鉱脈を試す。置いた数を返す。 */
    public static int tryForm(ServerLevel level, BlockPos pos, BlockState hostState, RandomSource random) {
        if (suppressed || level.dimension() != Level.OVERWORLD) return 0;
        Block ore = oreFor(hostState);
        if (ore == null) return 0;
        GearTierTable.Ore o = DesignData.gearTiers().ore;
        if (o.chance <= 0.0D || random.nextDouble() >= o.chance) return 0;
        if (!nearLey(level, pos)) return 0;
        return grow(level, pos, random);
    }

    /** 地脈の線から {@code leyReach} 以内か。 */
    public static boolean nearLey(ServerLevel level, BlockPos pos) {
        return LeyLines.distanceToLine(level.getSeed(), pos.getX(), pos.getZ()) <= DesignData.gearTiers().ore.leyReach;
    }

    /** 汚染された岩なら、それが変わる鉱石。そうでなければ null。 */
    public static Block oreFor(BlockState state) {
        if (state.is(ModBlocks.OVERGROWN_STONE.get())) return ModGear.FUNGAL_ORE.get();
        if (state.is(ModBlocks.OVERGROWN_DEEPSLATE.get())) return ModGear.DEEPSLATE_FUNGAL_ORE.get();
        return null;
    }

    /** {@code core} から鉱脈を広げる(地脈・確率は見ない。GameTest からも)。芯が汚染された岩でなければ 0。 */
    public static int grow(ServerLevel level, BlockPos core, RandomSource random) {
        GearTierTable.Ore o = DesignData.gearTiers().ore;
        int size = Math.max(1, o.veinMin + (o.veinMax > o.veinMin ? random.nextInt(o.veinMax - o.veinMin + 1) : 0));
        int placed = 0;
        BlockPos at = core;
        for (int i = 0; i < size * 4 && placed < size; i++) {
            Block ore = oreFor(level.getBlockState(at));
            if (ore != null && level.setBlock(at, ore.defaultBlockState(), 2)) placed++;
            else if (i == 0) return 0;
            at = at.relative(Direction.getRandom(random));
            if (at.distManhattan(core) > 2) at = core;
        }
        return placed;
    }
}
