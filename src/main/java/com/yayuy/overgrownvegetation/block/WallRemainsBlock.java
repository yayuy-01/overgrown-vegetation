package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 蠢く壁に飲まれたプレイヤーの<b>成れの果て</b>(2026-11-16 試作。決定 その 2・7)。植物に覆われた人の形で、持ち物が入っている。
 * <b>消えない・右クリックでは開かない・壊すと中身が全部出る</b>(ユーザー決定)。ブロックそのものは何も落とさない(アイテムは無い)。
 * 見た目は仮(苔の箱)。本当の形は後で Fable のセッションが作る。
 */
public class WallRemainsBlock extends Block implements EntityBlock {

    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 4.0D, 13.0D, 15.0D, 12.0D);

    public WallRemainsBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WallRemainsBlockEntity(pos, state);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof WallRemainsBlockEntity remains) {
            for (ItemStack stack : remains.items()) {
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
            remains.items().clear();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
