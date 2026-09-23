package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 「胞子除去装置」/「胞子拡散装置」({@link SporeDeviceKind})。周囲の胞子濃度
 * ({@link SporeConcentration})を装置を中心に水平の円形(半径 {@code radiusBlocks} ブロック、既定 48)で
 * 下げる / 上げる(装置のあるチャンクは距離に関係なく必ず {@code centerPercent})。稼働にはレッドストーンダスト(拡散装置はさらに「胞子」)が必要で、燃料・稼働時間・
 * 胞子の産出は {@link SporeDeviceBlockEntity} が管理する。右クリックで GUI
 * ({@link com.yayuy.overgrownvegetation.menu.SporeDeviceMenu})。
 *
 * <p>設置位置は次元ごとの {@link SporeDeviceData} に登録し、胞子濃度の計算がそこから稼働中の装置を探す。
 * 見た目は仮(色付きの立方体、稼働中は {@code lit=true})。
 */
public class SporeDeviceBlock extends BaseEntityBlock {

    public static final MapCodec<SporeDeviceBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("kind").forGetter(block -> block.kind.ordinal()),
            propertiesCodec()
    ).apply(instance, (kind, properties) -> new SporeDeviceBlock(SporeDeviceKind.byOrdinal(kind), properties)));

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    private final SporeDeviceKind kind;

    public SporeDeviceBlock(SporeDeviceKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public SporeDeviceKind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SporeDeviceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.SPORE_DEVICE.get(), SporeDeviceBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SporeDeviceBlockEntity device) {
                player.openMenu(device, buf -> buf.writeVarInt(kind.ordinal()));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this) && level instanceof ServerLevel serverLevel) {
            SporeDeviceData.get(serverLevel).add(pos, kind);
            SporeConcentration.invalidateAround(serverLevel, new ChunkPos(pos), kind.spec().reachChunks());
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SporeDeviceBlockEntity device) {
                Containers.dropContents(level, pos, device);
            }
            if (level instanceof ServerLevel serverLevel) {
                SporeDeviceData.get(serverLevel).remove(pos);
                SporeConcentration.invalidateAround(serverLevel, new ChunkPos(pos), kind.spec().reachChunks());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
