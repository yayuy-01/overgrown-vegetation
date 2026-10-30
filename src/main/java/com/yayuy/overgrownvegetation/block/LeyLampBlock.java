package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.Nullable;

/**
 * 地脈灯(2026-10-22、地脈技術の 2 段目。ユーザー指定)。地脈電池で点く灯り。バニラのランタンと同じく床に立てても天井から吊ってもよい。
 * <ul>
 *     <li>電池が残っている間は明るさ 15({@link #LIT})。周り 8 ブロックで敵対 Mob が自然に湧かない({@code defense/LeyLamps})。</li>
 *     <li>電池が切れると消えて、湧き止めも止まる。電池は持って右クリックで入れ替える({@code defense/MachineBattery})。</li>
 *     <li>品は腰の枠に付けられる({@code item/LeyLampItem})。電池の残りは置いても壊しても品と行き来する。</li>
 * </ul>
 */
public class LeyLampBlock extends LanternBlock implements EntityBlock {

    public static final MapCodec<LeyLampBlock> CODEC = simpleCodec(LeyLampBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public LeyLampBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LIT, false));
    }

    @Override
    public MapCodec<LanternBlock> codec() {
        return (MapCodec<LanternBlock>) (MapCodec<?>) CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LIT);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyLampBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.LEY_LAMP.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<LeyLampBlockEntity>) LeyLampBlockEntity::serverTick;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT) && random.nextInt(6) == 0) {
            double y = pos.getY() + (state.getValue(HANGING) ? 0.35D : 0.3D);
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5D + (random.nextDouble() - 0.5D) * 0.25D, y,
                    pos.getZ() + 0.5D + (random.nextDouble() - 0.5D) * 0.25D, 0.0D, 0.01D, 0.0D);
        }
    }
}
