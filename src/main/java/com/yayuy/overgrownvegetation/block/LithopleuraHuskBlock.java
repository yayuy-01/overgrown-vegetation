package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * <b>リトプレウラの抜け殻</b>。脱皮したリトプレウラがその場に残していく
 * (ユーザー指定 2026-09-21)。
 *
 * <p><b>殺さずに採れる素材</b>として置いてある。研究の解析に回せるので、
 * プレイヤーの利益が「生き物を殺すこと」だけに寄らない。
 *
 * <p>見た目はモデルの {@code kara} フォルダだけを抜き出した
 * {@code geo/lithopleura_husk.geo.json} で、テクスチャは本体と共用 ——
 * <b>脱ぐ前と脱いだ後が同じ絵</b>なので「さっきのあれだ」と分かる。
 * 描画は {@code LithopleuraHuskRenderer}(GeoBlockRenderer)が 1 個ずつ行うので、
 * ここでは {@link RenderShape#INVISIBLE} にして通常のブロックモデル描画を止める。
 *
 * <p>当たり判定は<b>殻の実寸ではなく低い板</b>。全長 2.5 ブロックあるモデルの外形をそのまま
 * 当たり判定にすると、隣のブロックまで通れなくなって道を塞ぐ。
 */
public class LithopleuraHuskBlock extends BaseEntityBlock {

    public static final MapCodec<LithopleuraHuskBlock> CODEC = simpleCodec(LithopleuraHuskBlock::new);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    /** 高さ 6/16 の板。またげる高さにしておく。 */
    private static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 6, 16);

    public LithopleuraHuskBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;   // 実際の描画は GeoBlockRenderer
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** 足場が無くなったら落ちる(宙に浮いた殻を残さない)。 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isSolidRender(level, pos.below());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LithopleuraHuskBlockEntity(pos, state);
    }
}
