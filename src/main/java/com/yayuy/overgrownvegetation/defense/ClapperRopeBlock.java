package com.yayuy.overgrownvegetation.defense;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * 鳴子の紐の目印のブロック(2026-10-15)。始点の柱のすぐ隣(空いたマス)に置かれる、見えない小さな箱。中身と描画は
 * {@link ClapperRopeBlockEntity}・{@code client/ClapperRopeRenderer}。当たり判定は無く、この小さな箱を壊すと紐を切ったのと同じ
 * (紐と吊っていた板を落とす)。紐はハサミの右クリックでも切れる({@link ClapperInteractions})。
 */
public class ClapperRopeBlock extends BaseEntityBlock {

    public static final MapCodec<ClapperRopeBlock> CODEC = simpleCodec(ClapperRopeBlock::new);
    private static final VoxelShape MARKER = Block.box(6.0D, 6.0D, 6.0D, 10.0D, 10.0D, 10.0D);

    public ClapperRopeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return MARKER;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ClapperRopeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.CLAPPER_ROPE.get(),
                level.isClientSide() ? ClapperRopeBlockEntity::clientTick : ClapperRopeBlockEntity::serverTick);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (player.getAbilities().instabuild && level.getBlockEntity(pos) instanceof ClapperRopeBlockEntity rope) {
            rope.dropOnRemove = false;
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof ClapperRopeBlockEntity rope && rope.dropOnRemove) {
            Vec3 mid = rope.middle();
            for (ItemStack stack : rope.drops()) {
                Containers.dropItemStack(server, mid.x, mid.y, mid.z, stack);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 紐を切る(目印のブロックを消す)。{@code drop} なら紐と板を真ん中に落とす。 */
    public static void cut(ServerLevel level, BlockPos pos, boolean drop) {
        if (level.getBlockEntity(pos) instanceof ClapperRopeBlockEntity rope) {
            rope.dropOnRemove = drop;
            Vec3 mid = rope.middle();
            level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8F, 1.2F);
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
    }
}
