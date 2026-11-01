package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * <b>地下モードの試作用の仮の光源</b>(2026-11-01、仕様「地下モード(洞窟のシェーダーと光)」の試作)。地下の植生(U1〜U8)の実装で本物の
 * 光るブロック(房の先・狐火茸・地脈の根…)に置き換えて消す。見た目の光はシェーダーの点光源({@code client/atmosphere/CaveLights})が出し、
 * このブロックの本物の光量は 2(湧きと植物の判定にだけ効く。仕様の「0〜3 に抑える」)。
 * 性質 {@code kind} で光源の種類(半径・色・強さは {@code CaveLights} の表)を選ぶ。アイテムは持たない({@code /setblock} で置く)。
 */
public class CaveLightProbeBlock extends Block {

    public static final MapCodec<CaveLightProbeBlock> CODEC = simpleCodec(CaveLightProbeBlock::new);

    /** 光源の種類(下絵の「光を出すブロック」の表から)。 */
    public enum Kind implements StringRepresentable {
        /** 天井の光源 A: 房の先(光る玉)。 */
        TASSEL("tassel"),
        /** 狐火茸。 */
        FOXFIRE("foxfire"),
        /** 地脈の根(地脈に近い)。 */
        LEY_NEAR("ley_near"),
        /** 地脈の根(地脈から遠い)。 */
        LEY_FAR("ley_far"),
        /** 光る蔓の実。 */
        VINE("vine");

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);

    public CaveLightProbeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(KIND, Kind.TASSEL));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(KIND);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }
}
