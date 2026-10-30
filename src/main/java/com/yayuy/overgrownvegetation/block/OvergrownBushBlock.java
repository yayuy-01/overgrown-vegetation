package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 過繁茂した茂み(当たり判定なしの葉ブロック)。
 *
 * <p>当たり判定は無いので通り抜けられるが、中に入っている間は
 * {@link net.minecraft.world.level.block.SweetBerryBushBlock} と同じ要領で移動速度が下がる
 * (ダメージは無い)。減速の強さは {@code bushSlowMultiplier}(0.0〜1.0、1.0 で無効)で調整できる。
 * ただし「過繁茂した(MOB名)」({@link ParasitizedMob})は茂み(=侵食植物)に適応済みという扱いで
 * 減速しない(ユーザー要望)。
 *
 * <p>{@code noCollission()} だと本来「支持面」も空になり、上に植物などを置けなくなる。
 * このブロックは {@link com.yayuy.overgrownvegetation.corruption.LeafClumpGrowth} が茂みの上に
 * 下草を生やすので、{@link #getBlockSupportShape} をフルキューブに上書きして支持面だけは
 * 復活させている(当たり判定・遮蔽は無いまま)。
 */
public class OvergrownBushBlock extends Block {

    public static final MapCodec<OvergrownBushBlock> CODEC = simpleCodec(OvergrownBushBlock::new);

    /** 縦方向の減速率(バニラのスイートベリーの茂みと同じ 0.75 固定)。 */
    private static final double VERTICAL_MULTIPLIER = 0.75D;

    public OvergrownBushBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        // noCollission() で空になる支持面を復活させ、茂みの上に下草が置けるようにする。
        return Shapes.block();
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        double horizontal = OvergrownVegetationConfig.COMMON.bushSlowMultiplier.get();
        if (horizontal >= 1.0D) {
            return;
        }
        entity.makeStuckInBlock(state, new Vec3(horizontal, VERTICAL_MULTIPLIER, horizontal));
    }
}
