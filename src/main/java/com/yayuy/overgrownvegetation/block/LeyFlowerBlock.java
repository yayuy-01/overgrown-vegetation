package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.LeyOmen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 地脈の花(2026-10-22、序盤の作り直し。ユーザー指定「地脈に沿って生える花」「夜に淡く光る」)。
 * 世界生成で地脈の線の上に点々と生える({@link com.yayuy.overgrownvegetation.worldgen.LeyVeinPlantsFeature})。
 * 光って見えるのは模型の自己発光だけで、光源レベルは 0(周りは照らさない。{@code ley_vein} と同じ考え方)。
 * 研究ポイントの素材で、地脈探しの材料。土のほか砂の上にも根付く(砂漠でも線を追えるように。仮決め)。
 *
 * <p>しおれる(2026-10-24 地脈技術の 4 段目): F1 の前の前兆({@link LeyOmen})の間、ランダム tick ごとに {@code omen.flowerWiltChance}
 * × 線の強さ(線の真ん中ほどしおれやすい)で {@link #WILTED} になる。前兆が終わると(F1 に入ると)ランダム tick ごとに
 * {@code omen.flowerRecoverChance}(既定 0.15 ≒ 1 日で 9 割)で元に戻る。しおれた花は光らない模型。
 * 壊すと花のアイテムに状態が残り(ルート表の {@code copy_state})、研究台では半分のポイント。
 */
public class LeyFlowerBlock extends BushBlock {

    public static final MapCodec<LeyFlowerBlock> CODEC = simpleCodec(LeyFlowerBlock::new);
    public static final BooleanProperty WILTED = BooleanProperty.create("wilted");
    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 13.0D, 13.0D);

    public LeyFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WILTED, false));
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WILTED);
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return super.mayPlaceOn(state, level, pos) || state.is(BlockTags.SAND);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Vec3 offset = state.getOffset(level, pos);
        return SHAPE.move(offset.x, offset.y, offset.z);
    }

    /** しおれた花から取れた品か(ルート表の {@code copy_state} が残した {@code block_state} の wilted)。 */
    public static boolean isWilted(net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.item.component.BlockItemStateProperties props =
                stack.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
        return props != null && Boolean.TRUE.equals(props.get(WILTED));
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState next = wiltStep(state, LeyOmen.isActive(), LeyVeinField.strengthAt(level.getSeed(), pos.getX(), pos.getZ()), random);
        if (next != state) {
            level.setBlock(pos, next, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * ランダム tick 1 回ぶんの移り変わり。前兆の間はしおれ(線の真ん中ほどしおれやすい。線の外に置かれた花も少しは = 強さの下限 0.25)、
     * 前兆でなければ戻る。
     */
    public static BlockState wiltStep(BlockState state, boolean omen, double lineStrength, RandomSource random) {
        boolean wilted = state.getValue(WILTED);
        if (omen) {
            double strength = Math.max(0.25D, lineStrength);
            if (!wilted && random.nextDouble() < DesignData.phases().omen().flowerWiltChance() * strength) {
                return state.setValue(WILTED, true);
            }
        } else if (wilted && random.nextDouble() < DesignData.phases().omen().flowerRecoverChance()) {
            return state.setValue(WILTED, false);
        }
        return state;
    }
}
