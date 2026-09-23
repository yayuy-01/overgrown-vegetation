package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.function.ToIntFunction;

/**
 * バニラの {@link net.minecraft.world.level.block.CaveVines}(グロウベリー)相当のふるまいを、
 * このMOD独自の「光るツタ」({@link OvergrownGlowVinesBlock} / {@link OvergrownGlowVinesPlantBlock})に
 * 与えるための共通ロジック。
 *
 * <p>バニラとの違いは収穫物が {@link ModItems#OVERGROWN_GLOWBERRY} になっている点だけ。
 */
public interface OvergrownGlowVines {

    /** 実が付いているか(バニラの {@code CaveVines.BERRIES} をそのまま流用)。 */
    BooleanProperty BERRIES = BlockStateProperties.BERRIES;

    /** 当たり判定・見た目の形(バニラの {@code CaveVines.SHAPE} と同じ)。 */
    VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0);

    /**
     * 右クリック時の処理。実が付いていれば {@link ModItems#OVERGROWN_GLOWBERRY} を1個ドロップして
     * {@code BERRIES=false} にする。バニラの {@code CaveVines.use} と同じ流れ。
     */
    static InteractionResult use(@Nullable Entity entity, BlockState state, Level level, BlockPos pos) {
        if (state.getValue(BERRIES)) {
            Block.popResource(level, pos, new ItemStack(ModItems.OVERGROWN_GLOWBERRY.get(), 1));
            float pitch = Mth.randomBetween(level.random, 0.8F, 1.2F);
            level.playSound(null, pos, SoundEvents.CAVE_VINES_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, pitch);
            BlockState picked = state.setValue(BERRIES, Boolean.FALSE);
            level.setBlock(pos, picked, Block.UPDATE_CLIENTS);
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(entity, picked));
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    static boolean hasGlowBerries(BlockState state) {
        return state.hasProperty(BERRIES) && state.getValue(BERRIES);
    }

    /** {@code BERRIES=true} のとき {@code berries} の光量を放つ発光関数。 */
    static ToIntFunction<BlockState> emission(int berries) {
        return state -> state.getValue(BlockStateProperties.BERRIES) ? berries : 0;
    }
}
