package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 小葉類の低い株(イワヒバ {@code selaginella}・ヒカゲノカズラ {@code clubmoss}。2026-11-01)。
 * 敷物 {@code lycopsid_carpet} の上に生えた株は 1 マスに 2 つ置けないので、{@link #CARPET} = true の模型に敷物の面を同梱して 1 ブロックで描く。
 * 敷物の面がずれないよう XZ のずらしは付けず、株のずれは模型の変種(blockstate の乱数)で出す。
 * 壊すと株と(敷物付きなら)敷物を落とす(loot 表)。
 */
public class LycopsidPlantBlock extends CorruptedGroundGrowthBlock {

    public static final MapCodec<LycopsidPlantBlock> CODEC = simpleCodec(LycopsidPlantBlock::new);
    public static final BooleanProperty CARPET = BooleanProperty.create("carpet");

    public LycopsidPlantBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(CARPET, false));
    }

    @Override
    protected MapCodec<LycopsidPlantBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CARPET);
    }
}
