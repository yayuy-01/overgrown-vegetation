package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「砲台」— 攻城戦ボス(ドルガ=アーケロウス・ティタニカ)を 1000 ブロック先から撃つ大砲。
 *
 * <h2>操作</h2>
 * <ul>
 *   <li><b>右クリック</b> — 乗って照準する({@link SiegeCannonSeatEntity})。一人称が照準器になり、
 *       視線がそのまま砲の方位・仰角になる。攻撃キーで発射、ホイールで装薬、スニークで降りる。</li>
 *   <li><b>砲弾・火薬を持って右クリック</b> — その場で装填する。</li>
 *   <li><b>スニーク + 右クリック</b> — 弾薬庫の GUI({@link com.yayuy.overgrownvegetation.menu.SiegeCannonMenu})。</li>
 *   <li><b>レッドストーン信号の立ち上がり</b> — そのときの向きのまま発射(自動化用)。</li>
 * </ul>
 *
 * <p>砲弾の飛行・命中判定は {@link com.yayuy.overgrownvegetation.siege.ShellTracker}、弾道の式は
 * {@link com.yayuy.overgrownvegetation.siege.CannonBallistics}、モデルの寸法は
 * {@link com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry}。
 *
 * <p>見た目は丸ごと {@link com.yayuy.overgrownvegetation.client.SiegeCannonRenderer}(GeckoLib)が描くので、
 * ブロックモデル自体は描画しない({@link RenderShape#INVISIBLE})。当たり判定は
 * {@link SiegeStructurePartBlock}(見えない部品ブロック)をモデルの占める範囲へ敷き詰めて作り、
 * 旋回に合わせて形も回る。置いた向きが最初の方位になる。
 */
public class SiegeCannonBlock extends BaseEntityBlock {

    public static final MapCodec<SiegeCannonBlock> CODEC = simpleCodec(SiegeCannonBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public SiegeCannonBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** 砲口はプレイヤーの向いている方(= プレイヤーから離れる向き)。 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    /** 方角 → 方位(度、0 = 北)。{@link Direction#toYRot()} は南 = 0 なので 180 ずらす。 */
    public static double azimuthOf(Direction facing) {
        return (facing.toYRot() + 180.0D) % 360.0D;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        SiegeStructurePartBlock.place(level, pos, SiegeCannonGeometry.template(),
                (SiegeStructurePartBlock) ModBlocks.SIEGE_STRUCTURE_PART.get());
        if (level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon) {
            cannon.setAzimuth(azimuthOf(state.getValue(FACING)));
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon
                ? cannon.structureShapes(BlockPos.ZERO)
                : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    /** 砲台は丸ごと {@code SiegeCannonRenderer}(GeckoLib)が描くので、ブロックモデルは描かない。 */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SiegeCannonBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // クライアントでも tick する: 旋回・俯仰をサーバーとまったく同じ式で進めるため
        // (そのおかげで砲の向きの同期パケットが要らない)。
        return createTickerHelper(type, ModBlockEntities.SIEGE_CANNON.get(),
                level.isClientSide ? SiegeCannonBlockEntity::clientTick : SiegeCannonBlockEntity::serverTick);
    }

    /** 砲弾・火薬を持って右クリック: その場で装填する。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        int slot;
        if (stack.is(ModItems.SIEGE_SHELL.get())) {
            slot = SiegeCannonBlockEntity.SLOT_SHELL;
        } else if (stack.is(Items.GUNPOWDER)) {
            slot = SiegeCannonBlockEntity.SLOT_POWDER;
        } else {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        ItemStack held = cannon.getItem(slot);
        int space = (held.isEmpty() ? stack.getMaxStackSize() : held.getMaxStackSize()) - held.getCount();
        if (space <= 0) {
            return ItemInteractionResult.CONSUME;
        }
        int moved = Math.min(space, stack.getCount());
        if (held.isEmpty()) {
            cannon.setItem(slot, stack.split(moved));
        } else {
            held.grow(moved);
            stack.shrink(moved);
        }
        cannon.setChanged();
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8F, 1.2F);
        return ItemInteractionResult.SUCCESS;
    }

    /** 素手で右クリック: 乗って照準する。スニークしていれば弾薬庫の GUI。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player.isSecondaryUseActive()) {
            player.openMenu(cannon, buf -> buf.writeBlockPos(pos));
            return InteractionResult.CONSUME;
        }
        if (player.isPassenger()) {
            return InteractionResult.CONSUME;
        }
        if (!(level instanceof ServerLevel serverLevel) || !SiegeCannonSeatEntity.mount(serverLevel, pos, cannon, player)) {
            player.displayClientMessage(Component.translatable("gui.overgrown_vegetation.cannon.occupied"), true);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.CONSUME;
    }

    /** レッドストーン信号の立ち上がりで発射(自動化用)。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon) {
            cannon.setPowered(serverLevel, level.hasNeighborSignal(pos));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            SiegeStructurePartBlock.clear(level, pos, SiegeCannonGeometry.template());
        }
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SiegeCannonBlockEntity cannon) {
            Containers.dropContents(level, pos, cannon);
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
