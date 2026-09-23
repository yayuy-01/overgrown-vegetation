package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「アルボラの貯蔵胞」({@code arbora_storage_pod}、2026-09-20)。巣の貯蔵室に並ぶ 27 スロットの入れ物。
 *
 * <p>中身は 3 通り:
 * <ul>
 *     <li>生成時に入る戦利品(過繫茂 Mob の素材と、村人から奪った設定の傷んだ道具・防具)</li>
 *     <li><b>巣の中で死んだプレイヤーの持ち物</b>({@code nest/NestDeaths})。落ちるのではなく
 *     <b>移される</b>ので複製はしない(ユーザー指定 2026-09-19)</li>
 * </ul>
 *
 * <p>壊すと中身をその場にぶちまける。
 */
public class ArboraStoragePodBlock extends BaseEntityBlock {

    public static final MapCodec<ArboraStoragePodBlock> CODEC = simpleCodec(ArboraStoragePodBlock::new);

    private static final VoxelShape SHAPE = Block.box(2.0D, 0.0D, 2.0D, 14.0D, 15.0D, 14.0D);

    public ArboraStoragePodBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArboraStoragePodBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity pod) {
            player.openMenu(pod);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity pod) {
                Containers.dropContents(level, pos, pod);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ArboraStoragePodBlockEntity pod
                ? net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromContainer(pod)
                : 0;
    }
}
