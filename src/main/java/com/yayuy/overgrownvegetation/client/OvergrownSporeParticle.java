package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 「過繁茂の胞子」パーティクル。過繁茂バイオームの緑の胞子と同系色だが、
 * {@code spore_blossom_air}(速度が内部で固定)と違って与えた初速でそのまま飛び、
 * 毎 tick の摩擦({@code quakeSporeFriction})で減速し、わずかな重力
 * ({@code quakeSporeGravity})で落ちてくる。地形は貫通する(裂け目の中で使うため)。
 *
 * <p>= 花火の火花のような動き: 噴出点で一気に加速 → 徐々に減速 → 広範囲に散る。
 */
@OnlyIn(Dist.CLIENT)
public class OvergrownSporeParticle extends TextureSheetParticle {

    /** 噴出点(生成時)の Y。ここからの上昇量で薄くなっていく。 */
    private final double startY;
    private final float riseFade;
    /** これまでの最大上昇量。一度薄くなったら落下しても濃く戻らないように。 */
    private float maxRise;

    protected OvergrownSporeParticle(ClientLevel level, double x, double y, double z,
                                     double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        this.friction = OvergrownVegetationConfig.COMMON.quakeSporeFriction.get().floatValue();
        // Particle.tick は毎 tick yd -= 0.04 * gravity。設定値(ブロック/tick^2)を 0.04 で割る。
        this.gravity = (float) (OvergrownVegetationConfig.COMMON.quakeSporeGravity.get() / 0.04D);
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.startY = y;
        this.riseFade = OvergrownVegetationConfig.COMMON.quakeSporeRiseFade.get().floatValue();
        this.lifetime = Mth.randomBetweenInclusive(this.random, 90, 200);
        this.quadSize = OvergrownVegetationConfig.COMMON.quakeSporeSize.get().floatValue()
                * (0.6F + this.random.nextFloat() * 0.8F);
        this.setColor(0.34F, 0.72F, 0.27F);
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        // 噴出点からの上昇量で薄くなる(上に行くほど透明 → 消える)。落下しても戻らない。
        this.maxRise = Math.max(this.maxRise, (float) (this.y - this.startY));
        float byHeight = riseFade <= 0.0F ? 1.0F : Mth.clamp(1.0F - this.maxRise / riseFade, 0.0F, 1.0F);
        // 寿命の終わり際にも短くフェード(唐突に消えないように)。
        float end = this.lifetime * 0.8F;
        float byLife = this.age > end
                ? Math.max(0.0F, 1.0F - (this.age - end) / (this.lifetime - end))
                : 1.0F;
        this.alpha = byHeight * byLife;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @OnlyIn(Dist.CLIENT)
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new OvergrownSporeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
