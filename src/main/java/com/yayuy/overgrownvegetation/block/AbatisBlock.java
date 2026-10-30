package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 逆茂木(2026-10-16、サバイバル用アイテム ㊶)。尖らせた木の杭の山。有刺鉄線({@link BarbedWireBlock})と同じ「当たり判定が無く、中を
 * 通りながら傷つく」作りで、それより弱い。
 * <ul>
 *     <li>ユーザー指定: 動く物すべて(プレイヤー・村人・家畜も)に効く / 向きに関係なく、どの方向から入っても傷つく / スニークで通ると無傷
 *     (遅くはなる) / 有刺鉄線より弱い / 刺すたびに傷み、尽きると壊れる / 傷みは 4 段階で見た目に出す / 棒を持って右クリックで修理し、
 *     1 本で 1 段階戻る。</li>
 *     <li>仮決め: 数値は {@code defense.json} の {@code abatis}(1 秒ごとに 1・速さ 0.6 倍・1 段 16 回)/ 傷つくのは動いている間だけ
 *     (甘い実の茂みと同じ)/ 刺し切ると壊れて棒 1 本 / 斧で壊すと、無傷なら逆茂木、傷んでいたら棒 2 本 / 向き({@link #FACING})は
 *     見た目だけ / 板材と同じく燃える。</li>
 * </ul>
 * 刺した回数は状態 {@link #HITS} に持つが、段({@link #STAGE})が変わるときだけクライアントへ送る(見た目が同じなのに毎回チャンクを
 * 組み立て直させないため)。
 */
public class AbatisBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<AbatisBlock> CODEC = simpleCodec(AbatisBlock::new);

    /** 傷みの段(0 = 新品 〜 3 = ぼろぼろ)。 */
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, 3);
    /** 今の段で刺した回数。 */
    public static final IntegerProperty HITS = IntegerProperty.create("hits", 0, 15);
    public static final int STAGES = 4;

    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 12.0D, 15.0D);
    private static final double VERTICAL_MULTIPLIER = 0.75D;
    /** 甘い実の茂みと同じ「動いている」の閾値(1 tick の水平の移動)。 */
    private static final double MOVING_EPSILON = 0.003D;

    public AbatisBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STAGE, 0).setValue(HITS, 0));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STAGE, HITS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return Block.canSupportCenter(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos,
                                     BlockPos neighborPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        DefenseTable table = DesignData.defense();
        double slow = table.abatisSlowMultiplier();
        if (slow < 1.0D) {
            entity.makeStuckInBlock(state, new Vec3(slow, VERTICAL_MULTIPLIER, slow));
        }
        // 有刺鉄線と同じく、刺す tick を個体ごとに entity id でずらす。
        if (level instanceof ServerLevel serverLevel && isMoving(entity)
                && Math.floorMod(level.getGameTime() + entity.getId(), (long) table.abatisDamageIntervalTicks()) == 0L) {
            poke(serverLevel, pos, state, living);
        }
    }

    private static boolean isMoving(Entity entity) {
        double dx = entity.getX() - entity.xOld;
        double dz = entity.getZ() - entity.zOld;
        return Math.abs(dx) >= MOVING_EPSILON || Math.abs(dz) >= MOVING_EPSILON;
    }

    /**
     * 1 回刺す(間隔の判定の後)。スニーク中のプレイヤーは刺さらない(ユーザー指定)。刺さったら 1 回ぶん傷む。
     *
     * @return 刺さったか
     */
    public static boolean poke(ServerLevel level, BlockPos pos, BlockState state, LivingEntity living) {
        DefenseTable table = DesignData.defense();
        if (table.abatisDamage() <= 0.0D || living.isSteppingCarefully()) {
            return false;
        }
        if (!living.hurt(ModDamageTypes.abatis(level), (float) table.abatisDamage())) {
            return false;
        }
        wear(level, pos, state, 1);
        return true;
    }

    /** 刺した回数の合計(段 × 1 段の回数 + 今の段の回数)。 */
    public static int totalHits(BlockState state) {
        return state.getValue(STAGE) * hitsPerStage() + state.getValue(HITS);
    }

    public static int hitsPerStage() {
        return DesignData.defense().abatisHitsPerStage();
    }

    /** {@code hits} 回ぶん傷める。4 段目を刺し切ったら壊れて棒 1 本を落とす。 */
    public static void wear(ServerLevel level, BlockPos pos, BlockState state, int hits) {
        int per = hitsPerStage();
        int total = totalHits(state) + hits;
        if (total >= per * STAGES) {
            level.destroyBlock(pos, false);
            Block.popResource(level, pos, new ItemStack(Items.STICK));
            return;
        }
        BlockState next = withTotal(state, total);
        boolean stageChanged = next.getValue(STAGE) != state.getValue(STAGE);
        // 段が同じなら見た目は変わらないので、クライアントへ送らない(チャンクの組み立て直しを避ける)。
        level.setBlock(pos, next, stageChanged ? Block.UPDATE_CLIENTS : Block.UPDATE_NONE);
    }

    private static BlockState withTotal(BlockState state, int total) {
        int per = hitsPerStage();
        return state.setValue(STAGE, Math.min(STAGES - 1, total / per)).setValue(HITS, total % per);
    }

    /** 棒を持って右クリックで修理: 1 本で 1 段ぶん(ユーザー指定)。傷んでいなければ何もしない。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.STICK)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        int total = totalHits(state);
        if (total <= 0) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide()) {
            level.setBlock(pos, withTotal(state, Math.max(0, total - hitsPerStage())), Block.UPDATE_CLIENTS);
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BAMBOO_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.abatis.sneak").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.abatis.repair").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 20; // 板材と同じ
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 5;
    }
}
