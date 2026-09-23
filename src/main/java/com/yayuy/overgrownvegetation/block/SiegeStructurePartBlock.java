package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 砲台・遮蔽盾の<b>見えない部品ブロック</b>。モデルが 1 ブロックに収まらないので、構造物が占める範囲へ
 * これを敷き詰めて当たり判定と右クリックを担当させる。
 *
 * <ul>
 *   <li><b>形</b>は本体({@link SiegeStructureCore})に訊く。本体が旋回すれば形も一緒に回る
 *       (部品ブロックを置き直す必要がない)。{@code dynamicShape} なので状態キャッシュされない。</li>
 *   <li><b>右クリック</b>は本体へそのまま転送する。当たった<b>座標</b>はそのままなので、
 *       盾のハンドル・ボタンのように「モデルのどこを触ったか」で分岐できる。</li>
 *   <li><b>壊す</b>と本体が壊れ、本体の {@code onRemove} が部品を片付ける。</li>
 * </ul>
 *
 * <p>本体の位置はブロック状態に相対座標で持つ({@link #OFFSET_X} 他)。ブロックエンティティを
 * 部品ごとに持たせると、砲台 1 台で 100 個以上になってしまうため。
 */
public class SiegeStructurePartBlock extends Block {

    public static final MapCodec<SiegeStructurePartBlock> CODEC = simpleCodec(SiegeStructurePartBlock::new);

    /** 本体からの相対座標。X/Z は {@link #OFFSET_ORIGIN} を引いて −4〜+4、Y は 0〜5。 */
    public static final int OFFSET_ORIGIN = 4;
    public static final int MAX_HORIZONTAL = 4;
    public static final int MAX_UP = 5;
    public static final IntegerProperty OFFSET_X = IntegerProperty.create("offset_x", 0, MAX_HORIZONTAL * 2);
    public static final IntegerProperty OFFSET_Y = IntegerProperty.create("offset_y", 0, MAX_UP);
    public static final IntegerProperty OFFSET_Z = IntegerProperty.create("offset_z", 0, MAX_HORIZONTAL * 2);

    public SiegeStructurePartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(OFFSET_X, OFFSET_ORIGIN)
                .setValue(OFFSET_Y, 0)
                .setValue(OFFSET_Z, OFFSET_ORIGIN));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OFFSET_X, OFFSET_Y, OFFSET_Z);
    }

    /** この相対座標を表すブロック状態。範囲外なら null(敷けない)。 */
    @Nullable
    public BlockState stateFor(BlockPos offset) {
        if (Math.abs(offset.getX()) > MAX_HORIZONTAL || Math.abs(offset.getZ()) > MAX_HORIZONTAL
                || offset.getY() < 0 || offset.getY() > MAX_UP) {
            return null;
        }
        return defaultBlockState()
                .setValue(OFFSET_X, offset.getX() + OFFSET_ORIGIN)
                .setValue(OFFSET_Y, offset.getY())
                .setValue(OFFSET_Z, offset.getZ() + OFFSET_ORIGIN);
    }

    /** この部品が属する本体ブロックの位置。 */
    public static BlockPos corePos(BlockState state, BlockPos pos) {
        return pos.offset(
                -(state.getValue(OFFSET_X) - OFFSET_ORIGIN),
                -state.getValue(OFFSET_Y),
                -(state.getValue(OFFSET_Z) - OFFSET_ORIGIN));
    }

    @Nullable
    private static SiegeStructureCore core(BlockGetter level, BlockState state, BlockPos pos) {
        return level.getBlockEntity(corePos(state, pos)) instanceof SiegeStructureCore core ? core : null;
    }

    // ------------------------------------------------------------------ 形(本体に訊く)

    /**
     * 選択(輪郭)の形。本体が見つからないときは 1 ブロックまるごとにして、<b>壊せる</b>ようにしておく
     * — 本体だけ {@code /setblock} で消されたときに、触れない部品が残り続けないため。
     */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        SiegeStructureCore core = core(level, state, pos);
        return core == null ? Shapes.block() : core.structureShapes(pos.subtract(core.structurePos()));
    }

    /**
     * 当たり判定。本体が見つからないときは<b>空</b>。本体のチャンクだけ読み込まれていない瞬間に
     * 部品が突然かたまりになって通せんぼするのを避ける。
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        SiegeStructureCore core = core(level, state, pos);
        return core == null ? Shapes.empty() : core.structureShapes(pos.subtract(core.structurePos()));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    // ------------------------------------------------------------------ 操作は本体へ転送

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos core = corePos(state, pos);
        BlockState coreState = level.getBlockState(core);
        if (coreState.isAir()) {
            return InteractionResult.PASS;
        }
        // 当たった座標はそのまま渡す(盾のハンドル・ボタンの判定に使う)。
        return coreState.useWithoutItem(level, player, hitResult.withPosition(core));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockPos core = corePos(state, pos);
        BlockState coreState = level.getBlockState(core);
        if (coreState.isAir()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return coreState.useItemOn(stack, level, player, hand, hitResult.withPosition(core));
    }

    /** 部品を殴ったら本体が壊れる。本体の {@code onRemove} が残りの部品を片付ける。 */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos core = corePos(state, pos);
        if (!level.isClientSide && level.getBlockEntity(core) instanceof SiegeStructureCore) {
            level.destroyBlock(core, !player.isCreative(), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 部品が何かの拍子に消えたときも本体を道連れにする(半端な残骸を残さない)。 */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos core = corePos(state, pos);
            if (level.getBlockEntity(core) instanceof SiegeStructureCore) {
                level.destroyBlock(core, true);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        BlockPos core = corePos(state, pos);
        BlockState coreState = level.getBlockState(core);
        return coreState.isAir() ? ItemStack.EMPTY : coreState.getBlock().asItem().getDefaultInstance();
    }

    /** 敷き詰め・片付けの実処理。クライアントでは触らない(サーバーのブロック更新で届く)。 */
    public static void place(Level level, BlockPos corePos, List<BlockPos> template, SiegeStructurePartBlock part) {
        if (level.isClientSide) {
            return;
        }
        for (BlockPos offset : template) {
            if (offset.equals(BlockPos.ZERO)) {
                continue;
            }
            BlockPos at = corePos.offset(offset);
            BlockState state = part.stateFor(offset);
            if (state != null && level.getBlockState(at).canBeReplaced()) {
                level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    public static void clear(Level level, BlockPos corePos, List<BlockPos> template) {
        if (level.isClientSide) {
            return;
        }
        for (BlockPos offset : template) {
            if (offset.equals(BlockPos.ZERO)) {
                continue;
            }
            BlockPos at = corePos.offset(offset);
            BlockState state = level.getBlockState(at);
            if (state.getBlock() instanceof SiegeStructurePartBlock && corePos(state, at).equals(corePos)) {
                level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

}
