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
 * 泥煙(2026-11-13、水まわり ⑥ ユーザー選択「泥煙: 水底を歩く・泳ぐ・Mob が動くと、底の泥が舞い上がってしばらく濁る」)。
 * 絵と動きは破裂の胞子の雲({@link SporeCloudParticle}。バニラの焚き火の煙)と同じ作りで、色は泥の茶、小さめ・ゆっくり膨らみ・長く残る。
 * 水の中でほとんど動かず、わずかに舞い上がって薄れる。出す側は {@code client.atmosphere.UnderwaterCritters}。数値はすべて仮決め。
 */
@OnlyIn(Dist.CLIENT)
public class MudCloudParticle extends TextureSheetParticle {

    static final int MIN_LIFETIME = 60;
    static final int MAX_LIFETIME = 110;
    private static final float MIN_SIZE = 0.25F;
    private static final float MAX_SIZE = 0.45F;
    private static final float GROW = 1.2F;
    private static final float MAX_ALPHA = 0.45F;
    private static final int FADE_IN = 6;
    private static final float FADE_FROM = 0.35F;
    private static final double DRAG = 0.85D;
    private static final double RISE = 0.0015D;

    private final float baseSize;

    protected MudCloudParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.lifetime = Mth.randomBetweenInclusive(this.random, MIN_LIFETIME, MAX_LIFETIME);
        this.baseSize = Mth.lerp(this.random.nextFloat(), MIN_SIZE, MAX_SIZE);
        this.quadSize = this.baseSize;
        // 泥の茶(少しばらす)
        float k = 0.85F + this.random.nextFloat() * 0.2F;
        this.setColor(0.42F * k, 0.34F * k, 0.24F * k);
        this.alpha = 0.0F;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.setSprite(sprites.get(this.random.nextInt(6), 11));
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        this.xd *= DRAG;
        this.yd *= DRAG;
        this.zd *= DRAG;
        // 水の中の共通の流れに流される(11-13 水まわり ⑥ の直し)。
        this.move(this.xd + com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent.x(), this.yd + RISE,
                this.zd + com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent.z());
        float t = (float) this.age / this.lifetime;
        float ease = 1.0F - (1.0F - t) * (1.0F - t);
        this.quadSize = this.baseSize * (1.0F + GROW * ease);
        this.roll += 0.003F;
        float in = Math.min(1.0F, (float) this.age / FADE_IN);
        float out = t < FADE_FROM ? 1.0F : Math.max(0.0F, 1.0F - (t - FADE_FROM) / (1.0F - FADE_FROM));
        this.alpha = MAX_ALPHA * in * out;
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
            return new MudCloudParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
