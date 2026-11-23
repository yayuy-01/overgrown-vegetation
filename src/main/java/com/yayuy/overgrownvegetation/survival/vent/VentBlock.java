package com.yayuy.overgrownvegetation.survival.vent;

import com.yayuy.overgrownvegetation.registry.ModLey;
import com.yayuy.overgrownvegetation.registry.ModVent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * <b>地脈の換気機</b>(2026-11-14 サバイバルの背骨 段 2c)。設定: 鉱山や工房の粉塵・煙・坑内の悪い空気を吸うために、人々が昔から使ってきた
 * 地脈技術の換気の機械(決定 16)。胞子のために作られた物ではないが胞子にも効く。汚染が始まる前から作れる(決定 9)。
 * 働きは {@link VentBlockEntity}・{@link Ventilators}。
 *
 * <ul>
 *     <li>フィルターを持って右クリック: 入れる(入っていた物は手元へ)。素手でしゃがんで右クリック: 取り出す。</li>
 *     <li>置いたとき・素手で右クリック: 動いているか・フィルターの残り・中の FE・直近に抜いた量。</li>
 * </ul>
 *
 * <p><b>3 ブロックの機械</b>(2026-11-22 ユーザー決定。見た目は Blockbench の {@code zyokyosouti_kai_B_yayuy.bbmodel}: 吸気の台 → フィルターの胴 →
 * 革のふいご)。ドアと同じく 1 つのブロックを部位 {@link #PART} で分け、下({@link Part#LOWER})が本体(中身・働きの中心・GeckoLib の描画)、
 * 上 2 つは付き物(描かない・当たり判定だけ)。
 * <ul>
 *     <li>上に 2 マス空き(置き換えられるブロック・エンティティが邪魔しない・高さの上限の内)がないと置けない(アイテムは減らない。訳は
 *     {@link VentBlockItem})。</li>
 *     <li>下を置くと上 2 つが付く({@link #onPlace}。{@code /setblock}・GameTest の {@code setBlock} でも同じ)。</li>
 *     <li>どこを壊しても、ほかの部位は落とし物なしで消える({@link #onRemove})。落とすのは壊した部位のルート(どの部位も換気機 1 つ)なので、
 *     道具の決まり(石のつるはし以上)はどこを掘っても同じ。爆発でも 1 つ。</li>
 *     <li>向き {@link #FACING} = 正面(フィルターの窓・吸気の格子)の向き。置いた人の方を向く。蝶番と排気の筒は背中側。</li>
 * </ul>
 */
public class VentBlock extends BaseEntityBlock {

    public static final BooleanProperty RUNNING = BooleanProperty.create("running");
    /** 正面の向き。GeckoLib の {@code GeoBlockRenderer#getFacing} がこの性質を読んで模型を回す。 */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** 機械の部位。{@link #offset} = 本体からの高さ。 */
    public enum Part implements StringRepresentable {
        LOWER("lower", 0), MIDDLE("middle", 1), UPPER("upper", 2);

        private final String name;
        public final int offset;

        Part(String name, int offset) {
            this.name = name;
            this.offset = offset;
        }

        static Part at(int offset) {
            return offset == 0 ? LOWER : offset == 1 ? MIDDLE : UPPER;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * 当たり判定(北向き = 正面が -Z の形。模型の箱から取った大まかな形)。下: 吸気の台(16 角・高さ 14)と胴の根元。中: フィルターの胴(14 角)と
     * つばの板。上: ふいご(開いた前の端まで高さ 9)と背中の排気の筒。
     */
    private static final Map<Part, Map<Direction, VoxelShape>> SHAPES = new EnumMap<>(Part.class);

    static {
        SHAPES.put(Part.LOWER, rotations(new double[][]{{0, 0, 0, 16, 14, 16}, {2, 14, 2, 14, 16, 14}}));
        SHAPES.put(Part.MIDDLE, rotations(new double[][]{{1, 0, 1, 15, 14, 15}, {0, 14, 0, 16, 16, 16}}));
        SHAPES.put(Part.UPPER, rotations(new double[][]{{0, 0, 0, 16, 9, 16}, {6, 9, 11, 10, 16, 16}}));
    }

    /** 北向きの箱({@code x0, y0, z0, x1, y1, z1}、画素)を 4 つの向きへ回す。GeckoLib の回し方(南 180°・西 90°・東 270°)と同じ。 */
    private static Map<Direction, VoxelShape> rotations(double[][] boxes) {
        Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape s = Shapes.empty();
            for (double[] b : boxes) {
                double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
                double nx0, nz0, nx1, nz1;
                switch (d) {
                    case SOUTH -> { nx0 = 16 - x1; nx1 = 16 - x0; nz0 = 16 - z1; nz1 = 16 - z0; }
                    case EAST -> { nx0 = 16 - z1; nx1 = 16 - z0; nz0 = x0; nz1 = x1; }
                    case WEST -> { nx0 = z0; nx1 = z1; nz0 = 16 - x1; nz1 = 16 - x0; }
                    default -> { nx0 = x0; nx1 = x1; nz0 = z0; nz1 = z1; }
                }
                s = Shapes.or(s, Block.box(nx0, b[1], nz0, nx1, b[4], nz1));
            }
            out.put(d, s.optimize());
        }
        return out;
    }

    public VentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(RUNNING, false).setValue(FACING, Direction.NORTH).setValue(PART, Part.LOWER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RUNNING, FACING, PART);
    }

    /** 本体(下)の位置。 */
    public static BlockPos basePos(BlockPos pos, BlockState state) {
        return state.hasProperty(PART) ? pos.below(state.getValue(PART).offset) : pos;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return state.getValue(PART) == Part.LOWER ? RenderShape.ENTITYBLOCK_ANIMATED : RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(PART)).get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.LOWER ? new VentBlockEntity(pos, state) : null;
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || state.getValue(PART) != Part.LOWER ? null
                : createTickerHelper(type, ModVent.VENTILATOR_BE.get(), VentBlockEntity::serverTick);
    }

    // ---------------------------------------------------------------- 置く・壊す

    /** 本体を {@code pos} に置いたとき、上 2 マスが空いているか(置き換えられる・高さの上限の内・エンティティが邪魔しない)。 */
    public static boolean hasRoom(LevelReader level, BlockPos pos, BlockPlaceContext ctx, BlockState lower) {
        for (int i = 1; i < 3; i++) {
            BlockPos p = pos.above(i);
            if (p.getY() >= level.getMaxBuildHeight() || !level.getBlockState(p).canBeReplaced(ctx)
                    || !level.isUnobstructed(lower.setValue(PART, Part.at(i)), p, CollisionContext.empty())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
        return hasRoom(ctx.getLevel(), ctx.getClickedPos(), ctx, state) ? state : null;
    }

    /** 本体が置かれたら上 2 つを付ける(サーバー側。走っている印の切り替えでは何もしない)。 */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (oldState.is(this) || state.getValue(PART) != Part.LOWER) {
            return;
        }
        for (int i = 1; i < 3; i++) {
            BlockPos p = pos.above(i);
            if (p.getY() >= level.getMaxBuildHeight()) {
                break;
            }
            level.setBlock(p, state.setValue(PART, Part.at(i)).setValue(RUNNING, false), Block.UPDATE_ALL);
        }
    }

    /**
     * 上 2 つの部位が欠けていたら付け直す(11-22 見直し 15)。3 ブロックになる前(2b925dad より前)に置いた換気機は、読み込むと
     * 本体だけになる({@link #onPlace} は読み込みでは呼ばれない)ので、本体の最初の tick に呼ぶ。欠けた所のどれかが置き換えられない
     * ブロックでふさがっていれば何も付けない(働きは変わらず、形だけ重なる。壊して置き直せば直る)。
     *
     * @return 付けた部位の数
     */
    public static int repairParts(Level level, BlockPos pos, BlockState lower) {
        if (!lower.hasProperty(PART) || lower.getValue(PART) != Part.LOWER) {
            return 0;
        }
        boolean[] missing = new boolean[3];
        for (int i = 1; i < 3; i++) {
            BlockPos p = pos.above(i);
            if (p.getY() >= level.getMaxBuildHeight()) {
                return 0;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(lower.getBlock()) && s.getValue(PART).offset == i && s.getValue(FACING) == lower.getValue(FACING)) {
                continue;
            }
            if (!s.canBeReplaced()) {
                return 0; // 一部だけ付けると宙に浮いた部位になるので、全部空いているときだけ付ける
            }
            missing[i] = true;
        }
        int added = 0;
        for (int i = 1; i < 3; i++) {
            if (!missing[i]) {
                continue;
            }
            BlockPos p = pos.above(i);
            level.setBlock(p, lower.setValue(PART, Part.at(i)).setValue(RUNNING, false), Block.UPDATE_ALL);
            added++;
        }
        return added;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (state.getValue(PART) == Part.LOWER) {
                if (level.getBlockEntity(pos) instanceof VentBlockEntity be) {
                    be.settleIfAway();
                    for (ItemStack s : be.stock().all()) { // 差してある 1 枚・予備・詰まった物(11-23)
                        Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, s);
                    }
                    be.stock().clear();
                }
                if (level instanceof net.minecraft.server.level.ServerLevel server) {
                    com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards.forget(server, pos, true); // 留守の覚えを忘れる(11-23)
                }
            }
            removeOtherParts(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 同じ機械のほかの部位を、落とし物なしで消す(壊れる粒は出す)。 */
    private void removeOtherParts(Level level, BlockPos pos, BlockState state) {
        BlockPos base = basePos(pos, state);
        Direction facing = state.getValue(FACING);
        for (int i = 0; i < 3; i++) {
            BlockPos p = base.above(i);
            if (p.equals(pos)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(this) && s.getValue(PART).offset == i && s.getValue(FACING) == facing) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(2001, p, Block.getId(s));
            }
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    // ---------------------------------------------------------------- さわる(どの部位からでも本体へ)

    @Nullable
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return InteractionResult.PASS; // 線の巻きの側で扱う
        }
        BlockPos base = basePos(pos, state);
        if (!AirFilters.isFilter(stack) || !(level.getBlockEntity(base) instanceof VentBlockEntity be)) {
            return null;
        }
        if (AirFilters.clogged(stack)) {
            if (!level.isClientSide) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "message.overgrown_vegetation.ley_ventilator.filter_clogged"), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            be.settleIfAway();
            ItemStack in = stack.copyWithCount(1);
            ItemStack old = ItemStack.EMPTY;
            if (be.filter().isEmpty() || AirFilters.clogged(be.filter())) {
                old = be.filter(); // 空き・詰まった物は差し替える
                be.setFilter(in);
            } else if (!be.addSpareByHand(in)) { // 差してある 1 枚が使えるなら予備へ(11-23)。予備がいっぱいなら前と同じく差し替える
                old = be.filter();
                be.setFilter(in);
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            if (!old.isEmpty() && !player.getInventory().add(old)) {
                player.drop(old, false);
            }
            level.playSound(null, base, SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.BLOCKS, 0.8F, 1.1F);
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos base = basePos(pos, state);
        if (!(level.getBlockEntity(base) instanceof VentBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            be.settleIfAway();
            java.util.List<ItemStack> taken = player.isShiftKeyDown() ? be.takeByHand() : java.util.List.of();
            if (!taken.isEmpty()) {
                for (ItemStack old : taken) {
                    if (!player.getInventory().add(old)) {
                        player.drop(old, false);
                    }
                }
                level.playSound(null, base, SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.BLOCKS, 0.8F, 0.9F);
            }
            player.displayClientMessage(be.info(), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof VentBlockEntity be) {
            player.displayClientMessage(be.info(), true);
        }
    }

    /** 1.20.1: 1.21 の useItemOn → useWithoutItem(主の手だけ)の順を use 1 つで呼ぶ。useItemOn の null = 1.21 の PASS_TO_DEFAULT_BLOCK_INTERACTION。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = useItemOn(player.getItemInHand(hand), state, level, pos, player, hand, hitResult);
        if (r != null) {
            return r;
        }
        return hand == InteractionHand.MAIN_HAND ? useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
    }
}
