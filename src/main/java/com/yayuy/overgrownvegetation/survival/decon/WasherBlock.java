package com.yayuy.overgrownvegetation.survival.decon;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * <b>地脈の洗い場</b>(2026-11-14 サバイバルの背骨 段 3a、決定 30)。設定【仮】: 鉱山の坑口や工房で、坑夫や職人が仕事の終わりに道具と作業着の
 * 粉塵・油を洗い流してきた地脈技術の洗い場(換気の機械と同じ「昔からある機械」の系統)。地下水を地脈の力で汲み上げて吹き付けるので、水は要らない。
 * 汚染の前から作れる。玄関に置くのが拠点づくりの目標になる。働きは {@link WasherBlockEntity}。
 *
 * <ul>
 *     <li>洗える物(覆われた○○・汚れた装備・詰まったフィルター)を持って右クリック: 手の山ごと入れる。上に落としても吸い込む。ホッパーでもよい。</li>
 *     <li>素手でしゃがんで右クリック: 洗い上がりを全部取り出す(無ければ洗う前の物)。素手の右クリック・置いたとき: 状態の表示。</li>
 *     <li>上に立つと着ている装備を洗う(段 3b)。</li>
 * </ul>
 * 見た目(2026-11-22): ユーザーの手直し版 {@code デスクトップ\blockbench\ley_washer_yayuy.bbmodel} を GeckoLib で描く(描画 {@code client/decon/WasherRenderer})。
 * 暗い木の槽を鉄の角金で留め、上は木のすのこ(高さ 12/16・すき間 1 画素)。前(扉)は置いた人の方を向く({@link #FACING})。
 * 槽の中の水だけはブロックの地形の模型 {@code models/block/ley_washer.json}(本物の水と同じ {@code block/water_still} を半透明で、
 * バイオームの水の色で染める)で描き、Sodium・自前のシェーダーを本物の水と同じように通す。
 */
public class WasherBlock extends BaseEntityBlock {

    public static final MapCodec<WasherBlock> CODEC = simpleCodec(WasherBlock::new);
    public static final BooleanProperty RUNNING = BooleanProperty.create("running");
    /** 前(扉 = 模型の北の面)の向き。置いた人の方を向く(2026-11-22)。 */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    // 形(北向き。模型の箱から): 足板 16 角 + 槽(すのこの上 12/16 まで)+ 後ろの立て管。外形にはすのこの上へ渡した噴き出し管も入れる
    private static final VoxelShape[] COLLISION = rotations(Shapes.or(
            box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D), box(1.0D, 2.0D, 1.0D, 15.0D, 12.0D, 15.0D), box(7.0D, 2.0D, 15.0D, 9.0D, 16.0D, 16.0D)));
    private static final VoxelShape[] OUTLINE = rotations(Shapes.or(
            box(0.0D, 0.0D, 0.0D, 16.0D, 2.0D, 16.0D), box(1.0D, 2.0D, 1.0D, 15.0D, 12.0D, 15.0D), box(7.0D, 2.0D, 15.0D, 9.0D, 16.0D, 16.0D),
            box(2.0D, 13.0D, 13.0D, 14.0D, 15.0D, 16.0D)));

    public WasherBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(RUNNING, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RUNNING, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE[state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION[state.getValue(FACING).get2DDataValue()];
    }

    /** 北向きの形を 4 つの向きへ回す(添え字は {@link Direction#get2DDataValue}: 南・西・北・東。GeckoLib の模型の回し方と同じ)。 */
    static VoxelShape[] rotations(VoxelShape north) {
        VoxelShape[] out = new VoxelShape[4];
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape[] acc = {Shapes.empty()};
            north.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                double ax = x0, az = z0, bx = x1, bz = z1;
                switch (d) {
                    case SOUTH -> { ax = 1 - x1; bx = 1 - x0; az = 1 - z1; bz = 1 - z0; }
                    case WEST -> { ax = z0; bx = z1; az = 1 - x1; bz = 1 - x0; }
                    case EAST -> { ax = 1 - z1; bx = 1 - z0; az = x0; bz = x1; }
                    default -> { }
                }
                acc[0] = Shapes.or(acc[0], Shapes.box(ax, y0, az, bx, y1, bz));
            });
            out[d.get2DDataValue()] = acc[0].optimize();
        }
        return out;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL; // 地形の模型は槽の水の面だけ。槽そのものは GeckoLib(WasherRenderer)が描く
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WasherBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModDecon.WASHER_BE.get(), WasherBlockEntity::serverTick);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // 線の巻きの側で扱う
        }
        if (!(level.getBlockEntity(pos) instanceof WasherBlockEntity be)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (Decon.kind(level, stack) == Decon.Kind.NONE) {
            if (Decon.filterWornOut(stack)) {
                if (!level.isClientSide) {
                    player.displayClientMessage(Component.translatable("message.overgrown_vegetation.decon.filter_worn_out"), true);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            ItemStack rest = be.insert(stack.copy());
            if (!player.getAbilities().instabuild) {
                player.setItemInHand(hand, rest);
            }
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.5F, 1.2F);
            player.displayClientMessage(be.info(), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WasherBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                for (ItemStack s : be.takeAll()) {
                    if (!player.getInventory().add(s)) {
                        player.drop(s, false);
                    }
                }
                level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
            }
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof WasherBlockEntity be) {
            player.displayClientMessage(be.info(), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof WasherBlockEntity be) {
            for (int i = 0; i < be.items().getSlots(); i++) {
                ItemStack s = be.items().getStackInSlot(i);
                if (!s.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, s);
                    be.items().setStackInSlot(i, ItemStack.EMPTY);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
