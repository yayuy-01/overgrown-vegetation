package com.yayuy.overgrownvegetation.survival.expedition;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * <b>胞子喰い茸</b>(2026-11-16 サバイバルの背骨 段 4。決定 39 の「採取の道」)。アルケフィタの胞子を食べて育つ白い菌で、
 * 胞子の多い所(過繁茂の森)でしか増えない。増えるのは {@link SporeEaterSpread}(過繁茂は遊んでいる間にできるので世界生成では置かない)。
 * 採って植え直してもよいが、植えた所では増えない。壊すと 1〜2 個(ルートテーブル)【仮】。
 *
 * <p>見た目【仮】: 白い傘に薄い黄緑のひだ。後でユーザーが描き直す。
 */
public class SporeEaterMushroomBlock extends BushBlock {

    private static final VoxelShape SHAPE = box(4.0D, 0.0D, 4.0D, 12.0D, 8.0D, 12.0D);

    public SporeEaterMushroomBlock(Properties properties) {
        super(properties);
    }

    /** 上の面がしっかりしたブロックなら何の上でも(覆われた土・苔・石など、汚染の地面は種類が多い)。 */
    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }
}
