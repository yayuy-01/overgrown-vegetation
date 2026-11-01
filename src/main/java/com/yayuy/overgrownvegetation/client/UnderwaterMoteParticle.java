package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.SuspendedParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * <b>水中の浮遊粒</b>(W6、2026-11-01 夕方。ユーザー「水中のバニラの青紫の泡の粒は、黄緑の微粒(胞子・デトリタス)に」)。
 *
 * <p>バニラの {@code minecraft:underwater}(水源の水の animateTick が出す浮遊粒)の作り手をこれで置き換える。過繁茂の海・川・森の池
 * (6 バイオーム)では色を黄緑の {@link #MOTE}(パレットの #ace300 を薄めた #8FB040)・大きさ × {@link #MOTE_SIZE} に、ほかはバニラと同じ青紫。
 * 数・寿命・動きはバニラのまま(絵もバニラの underwater の粒)。色と大きさは仮決め。
 */
@OnlyIn(Dist.CLIENT)
public class UnderwaterMoteParticle extends SuspendedParticle {

    /** #8FB040。 */
    private static final float[] MOTE = {0.561F, 0.690F, 0.251F};
    private static final float MOTE_SIZE = 0.75F;

    UnderwaterMoteParticle(ClientLevel level, SpriteSet sprites, double x, double y, double z) {
        super(level, sprites, x, y, z);
        if (overgrownWater(level.getBiome(BlockPos.containing(x, y, z)))) {
            setColor(MOTE[0], MOTE[1], MOTE[2]);
            quadSize *= MOTE_SIZE;
        } else {
            setColor(0.4F, 0.4F, 0.7F); // バニラの UnderwaterProvider と同じ
        }
    }

    private static boolean overgrownWater(Holder<Biome> b) {
        return b.is(ModBiomes.OVERGROWN_OCEAN) || b.is(ModBiomes.OVERGROWTH_OCEAN) || b.is(ModBiomes.OVERGROWN_RIVER)
                || b.is(ModBiomes.OVERGROWTH_RIVER) || b.is(ModBiomes.OVERGROWN) || b.is(ModBiomes.OVERGROWTH);
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new UnderwaterMoteParticle(level, sprites, x, y, z);
        }
    }
}
