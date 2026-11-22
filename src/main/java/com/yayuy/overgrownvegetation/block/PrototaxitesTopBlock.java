package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 巨大菌の柱(プロトタキシーテス)の<b>天辺</b>(2026-11-04、ユーザー決定「瘤と傘を混ぜる」。絵は下絵 a・c のまま)。
 * {@code knob} = 丸い瘤(2 段の箱)、{@code cap} = 平たい傘(厚さ 4 px・直径 3 ブロックの円盤。模型はブロックの外へはみ出す、
 * 当たり判定は自分のマスの円盤だけ)。2×2 の柱も列ごとに 1×1 の天辺を載せる(11-04 ユーザー指定「列ごとに高さをばらばらに」)。光らない。
 */
public class PrototaxitesTopBlock extends Block {

    public static final MapCodec<PrototaxitesTopBlock> KNOB_CODEC = simpleCodec(p -> new PrototaxitesTopBlock(p, false));
    public static final MapCodec<PrototaxitesTopBlock> CAP_CODEC = simpleCodec(p -> new PrototaxitesTopBlock(p, true));
    private static final VoxelShape KNOB_SHAPE = Shapes.or(Block.box(2, 0, 2, 14, 6, 14), Block.box(4, 6, 4, 12, 9, 12));
    /** 自分のマスの円盤だけ(はみ出した所は当たらない)。 */
    private static final VoxelShape CAP_SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    private final boolean cap;

    public PrototaxitesTopBlock(BlockBehaviour.Properties properties, boolean cap) {
        super(properties);
        this.cap = cap;
    }

    /** 傘か(瘤でなく)。 */
    public boolean isCap() {
        return cap;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return cap ? CAP_CODEC : KNOB_CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return cap ? CAP_SHAPE : KNOB_SHAPE;
    }
}
