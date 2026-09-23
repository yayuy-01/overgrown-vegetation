package com.yayuy.overgrownvegetation.client;

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
 * 「細かい胞子」パーティクル。色は {@link OvergrownSporeParticle}(過繁茂の胞子)と同じだが、
 * <b>粒がずっと小さく・寿命が短く・ほとんど流されない</b>。
 *
 * <p>ヴェロフィルが飛行中に翼の後ろへ曳く航跡に使う。最初は過繁茂の胞子をそのまま使っていたが、
 * あちらは地割れの噴出用に作られていて 1 粒が大きく(quadSize 0.2〜0.5)、
 * 「粒が大きすぎる」という指摘を受けた(2026-09-14)。こちらは 1/5 ほどの大きさで、
 * 数を出して<b>細かい粉が筋を引く</b>見え方を狙っている。
 */
@OnlyIn(Dist.CLIENT)
public class FineSporeParticle extends TextureSheetParticle {

    /** 粒の大きさの下限・上限。過繁茂の胞子(0.21〜0.49)の 1/5 程度。 */
    private static final float MIN_SIZE = 0.035F;
    private static final float MAX_SIZE = 0.075F;

    protected FineSporeParticle(ClientLevel level, double x, double y, double z,
                                double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        // ほとんど止まったまま、ゆっくり沈んで消える(曳いた線がその場に残って見えるように)。
        this.friction = 0.96F;
        this.gravity = 0.12F;
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.lifetime = Mth.randomBetweenInclusive(this.random, 16, 34);
        this.quadSize = Mth.lerp(this.random.nextFloat(), MIN_SIZE, MAX_SIZE);
        this.setColor(0.34F, 0.72F, 0.27F);
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        // 最後まで濃いまま消えると点滅して見えるので、後半をなだらかに薄くする。
        float half = this.lifetime * 0.45F;
        this.alpha = this.age > half
                ? Math.max(0.0F, 1.0F - (this.age - half) / (this.lifetime - half))
                : 1.0F;
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
            return new FineSporeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
