package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/**
 * 薬草(2026-10-16、サバイバル用アイテム。ユーザー指定「薬草の花は 4 つ・種を耕した畑に蒔く・バニラの野外に生える」)。
 * <ul>
 *     <li>4 段(0〜3)で育つ。採れるのは育ちきってから(薬草 + 種)、育つ前は種だけ(ルートテーブル)。</li>
 *     <li>{@link #WILD 野生}: 世界生成で草の上に置かれる育ちきった株。耕した土でなくても枯れない。種から植えた株は耕した土の上だけ。</li>
 *     <li>月光草 {@link Kind#MOONLIGHT}: 夜だけ花を開く({@link #BLOOM})。開いている間だけ薬草が採れる(ユーザー指定)。</li>
 *     <li>暖花 {@link Kind#WARMBLOOM}: 空の見える所でだけ育つ(日の当たる所に咲く花)。</li>
 * </ul>
 */
public class HerbCropBlock extends CropBlock {

    public enum Kind { MOONLIGHT, PUNGENT, BITTER_ROOT, WARMBLOOM, FIBER }

    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;
    public static final BooleanProperty WILD = BooleanProperty.create("wild");
    public static final BooleanProperty BLOOM = BooleanProperty.create("bloom");
    public static final int MAX_AGE = 3;

    private static final VoxelShape[] SHAPES = {
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 3.0D, 16.0D),
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 6.0D, 16.0D),
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 10.0D, 16.0D),
            Block.box(0.0D, 0.0D, 0.0D, 16.0D, 13.0D, 16.0D)};

    private final Kind kind;
    private final Supplier<? extends ItemLike> seed;

    public HerbCropBlock(Kind kind, Supplier<? extends ItemLike> seed, BlockBehaviour.Properties properties) {
        super(properties);
        this.kind = kind;
        this.seed = seed;
        registerDefaultState(stateDefinition.any().setValue(AGE, 0).setValue(WILD, false).setValue(BLOOM, false));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public MapCodec<? extends CropBlock> codec() {
        return MapCodec.unit(this); // 種類と種は登録で決まるので、この 1 つを指す
    }

    @Override
    protected IntegerProperty getAgeProperty() {
        return AGE;
    }

    @Override
    public int getMaxAge() {
        return MAX_AGE;
    }

    @Override
    protected ItemLike getBaseSeedId() {
        return seed.get();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE, WILD, BLOOM);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(AGE)];
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(Blocks.FARMLAND);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(WILD)) {
            return level.getBlockState(pos.below()).is(BlockTags.DIRT);
        }
        return super.canSurvive(state, level, pos);
    }

    /** 月光草は育ちきっても花の開閉のために動き続ける。野生の株も同じ。 */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return kind == Kind.MOONLIGHT || !isMaxAge(state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (kind == Kind.MOONLIGHT) {
            boolean open = isMaxAge(state) && isNight(level);
            if (state.getValue(BLOOM) != open) {
                state = state.setValue(BLOOM, open);
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }
        }
        if (state.getValue(WILD) || isMaxAge(state)) {
            return;
        }
        if (kind == Kind.WARMBLOOM && !level.canSeeSky(pos.above())) {
            return; // 日の当たらない所では育たない
        }
        super.randomTick(state, level, pos, random);
    }

    @Override
    public void growCrops(Level level, BlockPos pos, BlockState state) {
        if (state.getValue(WILD)) {
            return;
        }
        int age = Math.min(MAX_AGE, getAge(state) + getBonemealAgeIncrease(level));
        BlockState next = state.setValue(AGE, age);
        if (kind == Kind.MOONLIGHT) {
            next = next.setValue(BLOOM, age == MAX_AGE && isNight(level));
        }
        level.setBlock(pos, next, Block.UPDATE_CLIENTS);
    }

    /** 月が出ている間(13000〜23000 tick)。 */
    public static boolean isNight(Level level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    /** 世界生成で置く野生の株(育ちきり)。 */
    public BlockState wildState() {
        return defaultBlockState().setValue(AGE, MAX_AGE).setValue(WILD, true);
    }
}
