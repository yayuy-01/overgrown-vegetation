package com.yayuy.overgrownvegetation.purify;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 焼けた土(id は {@code burnt_soil} のまま。表示名は 2026-10-09 にユーザー指定で「燻る灰ブロック」)。順 2 の着手順 9(2026-10-08)。
 * 火が汚染された土を焦がした跡。<b>ユーザー指定(10-01)</b>「焼けた荒地: 火がくすぶり、ところどころ小さな煙、灰が積もる」。
 *
 * <p><b>ユーザー指定(10-09)</b>: 名前を燻る灰ブロックに / 絵はユーザー製(灰だけの絵と、灰に火の粉を重ねた絵の 2 コマを行き来するアニメーション。
 * 1 コマ 1 秒で混ぜながら切り替える —— Claude 仮決め、{@code burnt_soil.png.mcmeta})/ 砂などと同様に重力で落ちる({@link FallingBlock})。
 * 6 面とも同じ絵(Claude 仮決め。前は横が焦げた土・下がバニラの土だった)。ときどき煙と火の粉を出す(Claude 仮決め)。
 *
 * <p>汚染されない(タグ {@code corruption_immune})。焼けた荒地のバイオームが元に戻った後は、乱択の tick ごとに 3 分の 1 で土に戻る
 * (平均で数分。その後は草が隣から広がる)。焼けた荒地にならなかったチャンクで焦げた土も同じく土に戻る。
 */
public class BurntSoilBlock extends FallingBlock {

    public static final MapCodec<BurntSoilBlock> CODEC = simpleCodec(BurntSoilBlock::new);
    /** 下が空いているときに落ちる粉の色(灰の地の色)。 */
    private static final int DUST_COLOR = 0xFF34352F;

    public BurntSoilBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends FallingBlock> codec() {
        return CODEC;
    }

    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) {
        return DUST_COLOR;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Holder<Biome> biome = level.getBiome(pos);
        if (biome.is(ModBiomes.BURNT_WASTELAND) || ModBiomes.isOvergrownLike(biome)) {
            // 焼けた荒地の間と、まだ森のチャンクの間は土に戻らない(森の中で焼いた所が数分で土に戻り、また汚染されていた。2026-10-08 クライアント)。
            return;
        }
        if (random.nextInt(3) == 0) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        super.animateTick(state, level, pos, random); // 下が空いていれば粉が落ちる(砂と同じ)
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
