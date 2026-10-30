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
 * 「噴き出す胞子」(2026-10-23、萌芽体の胞子嚢。ユーザー「大きな胞子 1 粒がポッと出るのは嫌。小さな粒子が一点から勢いよく噴き出す形に」)。
 *
 * <p>大きさは細かい胞子({@link FineSporeParticle})と同じ小ささ。最初は与えた速さ(毎 tick 0.15〜0.3 ブロック)で吹き上がり、
 * 空気の抵抗で毎 tick 速さが落ち、重力でわずかに沈みながら、だんだん周りの空気の流れ(卓越風 + 突風、{@link SporeDrift} と同じ式)に
 * 乗って流され、2〜4 秒で薄れて消える。数値はすべて仮決め。
 */
@OnlyIn(Dist.CLIENT)
public class SporeJetParticle extends TextureSheetParticle {

    private static final float MIN_SIZE = 0.03F;
    private static final float MAX_SIZE = 0.065F;
    /** 寿命(tick)。2〜4 秒。 */
    static final int MIN_LIFETIME = 40;
    static final int MAX_LIFETIME = 80;
    /** 空気の抵抗(毎 tick 速さに掛ける)。0.25 で出た粒は 1.5〜2 ブロック吹き上がって止まる。 */
    private static final double DRAG = 0.86D;
    /** 重力(ブロック/tick²)。 */
    private static final double GRAVITY = 0.0035D;
    /** 噴いた勢いが抜けてから空気に乗るまで(tick)。 */
    private static final int JET_TICKS = 8;

    private final double[] velocity = new double[3];
    private final float phase;

    protected SporeJetParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        this.setSize(0.01F, 0.01F);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.velocity[0] = xd;
        this.velocity[1] = yd;
        this.velocity[2] = zd;
        this.lifetime = Mth.randomBetweenInclusive(this.random, MIN_LIFETIME, MAX_LIFETIME);
        this.quadSize = Mth.lerp(this.random.nextFloat(), MIN_SIZE, MAX_SIZE);
        // 細かい胞子と同じ緑(少しばらす)
        float k = 0.9F + this.random.nextFloat() * 0.2F;
        this.setColor(0.34F * k, 0.72F * k, 0.27F * k);
        this.phase = this.random.nextFloat() * Mth.TWO_PI;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        this.velocity[0] *= DRAG;
        this.velocity[1] = this.velocity[1] * DRAG - GRAVITY;
        this.velocity[2] *= DRAG;
        if (this.age > JET_TICKS) {
            // 勢いが抜けたら、周りの胞子と同じに風へ寄っていく(毎 tick 9%)
            SporeDrift.step(this.x, this.y, this.z, this.velocity, CanopyWind.time(0.0F), this.phase, this.random);
        }
        this.move(this.velocity[0], this.velocity[1], this.velocity[2]);
        // 後半をなだらかに薄く(点滅して消えないように)
        float fadeFrom = this.lifetime * 0.5F;
        this.alpha = this.age > fadeFrom ? Math.max(0.0F, 1.0F - (this.age - fadeFrom) / (this.lifetime - fadeFrom)) : 1.0F;
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
            return new SporeJetParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
