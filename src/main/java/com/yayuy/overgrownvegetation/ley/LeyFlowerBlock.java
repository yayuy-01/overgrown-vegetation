package com.yayuy.overgrownvegetation.ley;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>地脈の花</b>(2026-11-13 サバイバルの背骨 段 2b)。地脈の線の目印(決定 14)。線に沿って生える: 世界を作るとき({@link LeyFlowerFeature})と、
 * 遊んでいる間にも少しずつ({@link LeyFlowerSpread})(決定 25)。草・土の上に生える小さな花。光らない(夜の光などはユーザーに聞いてから)。
 * 汲み上げ器の材料にする【仮】ので、「まず線を見つける」が序盤の最初の仕事になる。
 *
 * <p>見た目【仮】: 淡い黄緑の花びらに、地脈の根と同じ黄緑(#ACE300)の芯。後でユーザーが描き直す。
 */
public class LeyFlowerBlock extends BushBlock {

    public static final MapCodec<LeyFlowerBlock> CODEC = simpleCodec(LeyFlowerBlock::new);
    private static final VoxelShape SHAPE = box(5.0D, 0.0D, 5.0D, 11.0D, 10.0D, 11.0D);

    public LeyFlowerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }
}
