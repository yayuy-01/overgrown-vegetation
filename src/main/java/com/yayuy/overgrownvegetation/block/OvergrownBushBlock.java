package com.yayuy.overgrownvegetation.block;

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
 * ただし植物側({@link com.yayuy.overgrownvegetation.spore.PlantSideRules#isPlantSide}。G1〜G8・環境生物・萌芽体など)は茂み(=侵食植物)に
 * 適応済みという扱いで減速しない(ユーザー要望。11-16 にこの判定を戻した = 一時は植物側も遅くなっていた)。プレイヤーは植物側に付いても今は遅くなる。
 *
 * <p>{@code noCollission()} だと本来「支持面」も空になり、上に植物などを置けなくなる。
 * このブロックは {@link com.yayuy.overgrownvegetation.corruption.LeafClumpGrowth} が茂みの上に
 * 下草を生やすので、{@link #getBlockSupportShape} をフルキューブに上書きして支持面だけは
 * 復活させている(当たり判定・遮蔽は無いまま)。
 */
public class OvergrownBushBlock extends Block {


    /** 縦方向の減速率(バニラのスイートベリーの茂みと同じ 0.75 固定)。 */
    private static final double VERTICAL_MULTIPLIER = 0.75D;

    public OvergrownBushBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        // noCollission() で空になる支持面を復活させ、茂みの上に下草が置けるようにする。
        return Shapes.block();
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        // 植物側(G1〜G8・環境生物・萌芽体など)は茂みに慣れていて遅くならない(11-16 ユーザー)。プレイヤーは植物側に付いても今は遅くなる(0.3.0 で見直す)
        if (!(living instanceof net.minecraft.world.entity.player.Player)
                && com.yayuy.overgrownvegetation.spore.PlantSideRules.isPlantSide(living)) {
            return;
        }
        double horizontal = OvergrownVegetationConfig.COMMON.bushSlowMultiplier.get();
        if (horizontal >= 1.0D) {
            return;
        }
        entity.makeStuckInBlock(state, new Vec3(horizontal, VERTICAL_MULTIPLIER, horizontal));
    }
}
