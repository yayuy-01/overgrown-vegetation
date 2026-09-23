package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 「未発達の茂み」— 汚染が広がるときに地面から生える下草の一種。
 * 苗木({@link CorruptedSaplingBlock})より少し低い確率で、他の下草の代わりに生える
 * ({@code CorruptionSpreader#growUp}、確率は {@code undevelopedBushChance})。
 *
 * <p>他の下草({@link CorruptedGroundGrowthBlock})と違うのは<b>実が付く</b>こと。
 * {@code berries=true} のときに素手/道具を問わず右クリックすると
 * {@link ModItems#UNDEVELOPED_SEED 「未発達な種子」}を 1 個落として実の無い状態に戻り、
 * しばらくするとまた実を付ける —— 光るツタ({@link OvergrownGlowVines})と同じ流れで、
 * 収穫しても茂み自体は壊れない。
 *
 * <p>実の付き直しはランダムティック任せ({@value #BERRY_GROWTH_CHANCE} の確率)。バニラの
 * 甘いベリーの茂みのように「育つ段階」は持たず、実が有るか無いかの 2 状態だけ。
 */
public class UndevelopedBushBlock extends BushBlock {

    public static final MapCodec<UndevelopedBushBlock> CODEC = simpleCodec(UndevelopedBushBlock::new);

    /** 実が付いているか(バニラの甘いベリー等と同じ {@code berries} プロパティを流用)。 */
    public static final BooleanProperty BERRIES = BlockStateProperties.BERRIES;

    /** ランダムティック 1 回あたり、実の無い茂みに実が付く確率。 */
    private static final float BERRY_GROWTH_CHANCE = 0.11F;

    /** 当たり判定なしの下草なので、形は他の下草({@link CorruptedGroundGrowthBlock})と同じ。 */
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE =
            Block.box(2.0, 0.0, 2.0, 14.0, 14.0, 14.0);

    public UndevelopedBushBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(BERRIES, Boolean.FALSE));
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BERRIES);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP);
    }

    /** 実が無いあいだだけランダムティックを待つ(付いていれば何もしない)。 */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return !state.getValue(BERRIES);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(BERRIES) && random.nextFloat() < BERRY_GROWTH_CHANCE) {
            level.setBlock(pos, state.setValue(BERRIES, Boolean.TRUE), Block.UPDATE_CLIENTS);
        }
    }

    /** 実が付いていれば 1 個収穫して実の無い状態に戻す(光るツタの収穫と同じ流れ)。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!state.getValue(BERRIES)) {
            return InteractionResult.PASS;
        }
        // 寄生植物装備の 2 段階目以降は実が倍。
        Block.popResource(level, pos, new ItemStack(ModItems.UNDEVELOPED_SEED.get(),
                com.yayuy.overgrownvegetation.item.PlantArmor.fruitCount(player, 1)));
        float pitch = Mth.randomBetween(level.random, 0.8F, 1.2F);
        level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, pitch);
        BlockState picked = state.setValue(BERRIES, Boolean.FALSE);
        level.setBlock(pos, picked, Block.UPDATE_CLIENTS);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, picked));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** ピック(中クリック)では実ではなく茂みそのものを取る。 */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(this);
    }
}
