package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.corruption.HeatForestTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>熱水の森の芽</b>(2026-11-10 ユーザー決定: ネザーの木も芽から時間で育つ。オーバーワールドの苗木と同じ決まり)。
 *
 * <p>{@link CorruptedSaplingBlock} を継ぐので、置いた時刻の記録と、留守のチャンクの追いつき成長
 * ({@code worldgen.SaplingOfflineGrowth})はオーバーワールドの苗木と同じ仕組みがそのまま効く。育ち方も同じ
 * (ランダムティックで 2 段・明るさの条件なし)。育つ先だけが {@link Kind} で違う({@link HeatForestTrees})。
 * 育つ場所がふさがっている・逆さの木の決まりを満たさないときは、芽のまま残る。壊すとドロップなし(ルートが空)。
 */
public class HeatSproutBlock extends CorruptedSaplingBlock {

    /** 育つ先。 */
    public enum Kind { TREE, TUBE, SKY, MOUND }

    public static final MapCodec<HeatSproutBlock> SPROUT_CODEC = simpleCodec(p -> new HeatSproutBlock(Kind.TREE, p));
    private static final VoxelShape GROUND = Block.box(3, 0, 3, 13, 12, 13);
    private static final VoxelShape HANGING = Block.box(3, 4, 3, 13, 16, 13);

    private final Kind kind;

    public HeatSproutBlock(Kind kind, BlockBehaviour.Properties properties) {
        super(TreeGrower.OAK, properties);
        this.kind = kind;
    }

    public static HeatSproutBlock tree(BlockBehaviour.Properties p) { return new HeatSproutBlock(Kind.TREE, p); }
    public static HeatSproutBlock tube(BlockBehaviour.Properties p) { return new HeatSproutBlock(Kind.TUBE, p); }
    public static HeatSproutBlock sky(BlockBehaviour.Properties p) { return new HeatSproutBlock(Kind.SKY, p); }
    public static HeatSproutBlock mound(BlockBehaviour.Properties p) { return new HeatSproutBlock(Kind.MOUND, p); }

    public Kind kind() {
        return kind;
    }

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<? extends SaplingBlock> codec() {
        return SPROUT_CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind == Kind.SKY ? HANGING : GROUND;
    }

    /** 地面の芽は下の面、天井の芽は上の面が塞がっていれば立つ(土でなくてよい)。 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (kind == Kind.SKY) {
            BlockPos above = pos.above();
            return level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN);
        }
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    /** 1 段目は芽が大きくなるだけ(オーバーワールドの苗木と同じ)。2 段目で育つ。育たなければ芽のまま。 */
    @Override
    public void advanceTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            level.setBlock(pos, state.cycle(STAGE), 4);
            return;
        }
        HeatForestTrees.growSprout(level, pos, state, kind, random);
    }
}
