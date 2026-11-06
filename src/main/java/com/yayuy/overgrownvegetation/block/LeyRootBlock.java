package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * 地脈の根(2026-11-03 地下の植生 1、U3): 洞窟の壁を這う根。地脈に触れた根が淡く光り、地脈に近いほど明るい(ユーザー指定 11-01)。
 * {@link #GLOW} = 地脈からの近さ(none / far / near)で絵と光量を変える(光量は {@code ModBlocks} の lightLevel)。見た目の光は地下モードの点光源
 * ({@code CaveLights} の LEY_NEAR / LEY_FAR)。置き方は {@code corruption/CaveFlora}。
 */
public class LeyRootBlock extends CaveSheetBlock {

    public static final MapCodec<LeyRootBlock> CODEC = simpleCodec(LeyRootBlock::new);

    /** 地脈からの近さ。 */
    public enum Glow implements StringRepresentable {
        NONE("none"),
        FAR("far"),
        NEAR("near");

        private final String name;

        Glow(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static final EnumProperty<Glow> GLOW = EnumProperty.create("glow", Glow.class);

    public LeyRootBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(GLOW, Glow.NONE));
    }

    @Override
    protected MapCodec<? extends LeyRootBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GLOW);
    }

    /** 光量(仮決め 11-03: near 2・far 1・none 0。湧きと植物の判定にだけ効く)。 */
    public static int emission(BlockState state) {
        return switch (state.getValue(GLOW)) {
            case NEAR -> 2;
            case FAR -> 1;
            case NONE -> 0;
        };
    }
}
