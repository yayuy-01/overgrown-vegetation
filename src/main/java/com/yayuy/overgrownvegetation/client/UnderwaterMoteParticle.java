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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * <b>水中の浮遊粒</b>(W6、2026-11-01 夕方。ユーザー「水中のバニラの青紫の泡の粒は、黄緑の微粒(胞子・デトリタス)に」)。
 *
 * <p>バニラの {@code minecraft:underwater}(水源の水の animateTick が出す浮遊粒)の作り手をこれで置き換える。過繁茂の海・川・森の池
 * (6 バイオーム)では色を黄緑の {@link #MOTE}(パレットの #ace300 を薄めた #8FB040)・大きさ × {@link #MOTE_SIZE} に、ほかはバニラと同じ青紫。
 * 数・寿命・動きはバニラのまま(絵もバニラの underwater の粒)。色と大きさは仮決め。
 */
@OnlyIn(Dist.CLIENT)
public class UnderwaterMoteParticle extends SuspendedParticle {

    /** #8FB040(胞子)。 */
    private static final float[] MOTE = {0.561F, 0.690F, 0.251F};
    /**
     * 11-13(水まわり ⑤ ユーザー選択「漂う粒: バニラの水は白い粒が少し、汚染の水は胞子・泥炭・藻の粒が多く」): 汚染の水は胞子の黄緑に、
     * 泥炭の茶 {@link #PEAT}・藻の白 {@link #ALGAE} を混ぜる(割合は {@link #PEAT_SHARE}・{@link #ALGAE_SHARE})。バニラの水は青紫をやめて白 {@link #CLEAR}。
     * 数は {@code client.atmosphere.UnderwaterMotes} がカメラのまわりに足す。色と割合は仮決め。
     */
    private static final float[] PEAT = {0.42F, 0.32F, 0.20F};
    private static final float[] ALGAE = {0.80F, 0.86F, 0.74F};
    private static final float[] CLEAR = {0.78F, 0.84F, 0.90F};
    private static final float PEAT_SHARE = 0.3F;
    private static final float ALGAE_SHARE = 0.15F;
    private static final float MOTE_SIZE = 0.75F;

    UnderwaterMoteParticle(ClientLevel level, SpriteSet sprites, double x, double y, double z) {
        super(level, sprites, x, y, z);
        if (overgrownWater(level.getBiome(BlockPos.containing(x, y, z)))) {
            float u = random.nextFloat();
            float[] c = u < PEAT_SHARE ? PEAT : (u < PEAT_SHARE + ALGAE_SHARE ? ALGAE : MOTE);
            setColor(c[0], c[1], c[2]);
            quadSize *= MOTE_SIZE;
        } else {
            setColor(CLEAR[0], CLEAR[1], CLEAR[2]);
        }
    }

    /** 水の中の共通の流れに流される(11-13 水まわり ⑥ の直し「共通の流れ」)。 */
    @Override
    public void tick() {
        super.tick();
        if (this.isAlive()) {
            this.move(com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent.x(), 0.0, com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent.z());
        }
    }

    /** 過繁茂・繁茂の水か(粒を足す数を決める {@code UnderwaterMotes} も使う)。 */
    public static boolean overgrownWater(Holder<Biome> b) {
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
            // 11-13(ユーザー「バニラの水中の粒も消して、陸の胞子みたいにがっつり改変」): 水中の浮遊物が動いている間はバニラの粒を出さない。
            if (com.yayuy.overgrownvegetation.client.atmosphere.WaterMotes.active()) {
                return null;
            }
            return new UnderwaterMoteParticle(level, sprites, x, y, z);
        }
    }
}
