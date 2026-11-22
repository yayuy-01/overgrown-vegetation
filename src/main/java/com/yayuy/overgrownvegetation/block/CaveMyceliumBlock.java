package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 洞窟の菌糸(2026-11-03 地下の植生 1 の直し。ユーザー指定「ブロックに描くものではなく、薄い苔のようにブロックの上に置く植物」「出すのは小さな洞窟だけ」)。
 * バニラのヒカリゴケと同じ作り(1 ブロックで複数の面に貼れる・当たり判定なし・光らない)。{@link #GROUND} = 貼った面の下地(石・深層岩・土)で絵を選ぶ。
 * 骨粉では広がらない(広がりは汚染が置く。置き方は {@code corruption/CaveFlora})。
 */
public class CaveMyceliumBlock extends MultifaceBlock {


    /** 下地(絵の組)。 */
    public enum Ground implements StringRepresentable {
        STONE("stone"),
        DEEPSLATE("deepslate"),
        DIRT("dirt");

        private final String name;

        Ground(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static final EnumProperty<Ground> GROUND = EnumProperty.create("ground", Ground.class);

    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public CaveMyceliumBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(GROUND, Ground.STONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GROUND);
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return this.spreader;
    }

    /** 新しく置くときは、貼る面の下地から絵の組を選ぶ(前からあるマスに面を足すときは変えない)。 */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockState currentState, BlockGetter level, BlockPos pos, Direction lookingDirection) {
        BlockState state = super.getStateForPlacement(currentState, level, pos, lookingDirection);
        if (state != null && !currentState.is(this)) {
            state = state.setValue(GROUND, groundOf(level.getBlockState(pos.relative(lookingDirection))));
        }
        return state;
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.getItemInHand().is(this.asItem()) || super.canBeReplaced(state, context);
    }

    /** 下地の見分け: 名前に deepslate・tuff = 深層岩、土の仲間かシャベルで掘るもの = 土、ほか = 石(仮決め 11-03)。 */
    public static Ground groundOf(BlockState support) {
        String path = BuiltInRegistries.BLOCK.getKey(support.getBlock()).getPath();
        if (path.contains("deepslate") || path.contains("tuff")) {
            return Ground.DEEPSLATE;
        }
        if (support.is(BlockTags.DIRT) || support.is(BlockTags.MINEABLE_WITH_SHOVEL) || path.contains("dirt") || path.contains("soil")) {
            return Ground.DIRT;
        }
        return Ground.STONE;
    }
}
