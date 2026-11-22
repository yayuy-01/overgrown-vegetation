package com.yayuy.overgrownvegetation.corruption.pushback;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * <b>燻る灰ブロック</b>(id は {@code burnt_soil} のまま。10-08〜09 のユーザー指定を 11-18 夜に胞子焼きの筒へ引き継いだ。前の版は parked/purify/BurntSoilBlock)。
 * 汚染された土を焼いた跡。砂のように重力で落ちる・8 枚のずれた明滅の絵・ときどき煙と火の粉。
 *
 * <p><b>11-18 夜 ユーザー</b>「残る長さは散布と同じように 1 日」: {@code fire.burntDays}【仮 1 日】たつと、枯れた土と同じく見直す
 * —— チャンクがまだ台帳に載っていれば元の汚染の地面へ、外れていればふつうの土・草へ({@link WitheredSoilBlock#reviveTo})。
 */
public class SmolderingAshBlock extends FallingBlock {

    public static final MapCodec<SmolderingAshBlock> CODEC = simpleCodec(SmolderingAshBlock::new);
    private static final int DUST_COLOR = 0xFF34352F;

    public SmolderingAshBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(WitheredSoilBlock.ORIGIN, 0));
    }

    @Override
    protected MapCodec<? extends FallingBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WitheredSoilBlock.ORIGIN);
    }

    /** 汚染の地面を焼いた灰(元の地面を覚える)。 */
    public static BlockState from(BlockState ground) {
        return com.yayuy.overgrownvegetation.registry.ModPushBack.SMOLDERING_ASH.get().defaultBlockState()
                .setValue(WitheredSoilBlock.ORIGIN, WitheredSoilBlock.from(ground).getValue(WitheredSoilBlock.ORIGIN));
    }

    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) {
        return DUST_COLOR;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved); // 落ちる見直しの予定もここ
        if (!old.is(this) && level instanceof ServerLevel server) {
            ReviveTimers.schedule(server, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
        if (!now.is(this) && level instanceof ServerLevel server) ReviveTimers.forget(server, pos);
        super.onRemove(state, level, pos, now, moved);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (ReviveTimers.due(level, pos)) {
            level.setBlock(pos, WitheredSoilBlock.reviveTo(level, pos, state), Block.UPDATE_ALL);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random);
        if (!level.getBlockState(pos.above()).isAir()) {
            return;
        }
        double x = pos.getX() + random.nextDouble();
        double z = pos.getZ() + random.nextDouble();
        double y = pos.getY() + 1.02D;
        if (random.nextInt(14) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0D, 0.015D, 0.0D);
        }
        if (random.nextInt(90) == 0) {
            level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0.0D, 0.01D, 0.0D);
        }
    }
}
