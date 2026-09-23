package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 忌避灯(2026-09-19)。点いているあいだ、半径 {@code defense.json} の {@code repellentLamp.radius} に
 * グループ1〜4 の Mob が入ってこない({@code entity/RepellentAvoidGoal})。
 *
 * <p>弱点はヴェロフィル。{@code velophyllDetectRange} 以内の灯りを見つけると爆撃しに来て
 * ({@code entity/VelophyllLampBombGoal})、爆弾が {@code coverRadius} 以内で炸裂すると
 * 灯りは胞子と蔦に覆われて止まる({@link #COVERED})。右クリックで蔦を剥がせば復活する。
 *
 * <p>点いている灯りの一覧は {@link RepellentLamps}(ブロックエンティティがロード/アンロードで登録・解除)。
 */
public class RepellentLampBlock extends BaseEntityBlock {

    public static final MapCodec<RepellentLampBlock> CODEC = simpleCodec(RepellentLampBlock::new);

    /** 蔦と胞子に覆われて止まっているか。 */
    public static final BooleanProperty COVERED = BooleanProperty.create("covered");

    private static final VoxelShape SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 12.0D, 12.0D);

    public RepellentLampBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(COVERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COVERED);
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
        return new RepellentLampBlockEntity(pos, state);
    }

    /** 点いているか(蔦に覆われていない)。 */
    public static boolean isActive(BlockState state) {
        return state.is(state.getBlock()) && state.hasProperty(COVERED) && !state.getValue(COVERED);
    }

    /** 覆う/剥がす。台帳({@link RepellentLamps})も同時に更新する。 */
    public void setCovered(Level level, BlockPos pos, BlockState state, boolean covered) {
        if (state.getValue(COVERED) == covered) {
            return;
        }
        level.setBlock(pos, state.setValue(COVERED, covered), Block.UPDATE_ALL);
        if (covered) {
            RepellentLamps.unregister(level, pos);
            level.playSound(null, pos, SoundEvents.SPORE_BLOSSOM_PLACE, SoundSource.BLOCKS, 1.0F, 0.6F);
        } else {
            RepellentLamps.register(level, pos);
            level.playSound(null, pos, SoundEvents.VINE_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!state.getValue(COVERED)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            setCovered(level, pos, state, false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            RepellentLamps.unregister(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
