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
 * 風に乗る綿毛の粒(2026-11-13、能力の第 2 回。羊の 1 段目の (c)。出す側は {@code entity/g1/skill/G1FluffDrift})。
 * タンポポ・ガマの穂の綿のような白い小さなかたまり。出たときの速さ(= 風の向き)をほぼ保ったまま流れ、ゆっくり回り、ふわふわと上下に揺れながら
 * わずかに沈む。地面・壁に当たると止まって薄れる。光らない(光るのは胞子の袋の中身だけ、共通の決まり)。数値はすべて【仮】。
 */
@OnlyIn(Dist.CLIENT)
public class G1FluffParticle extends TextureSheetParticle {

    private static final int MIN_LIFETIME = 70;
    private static final int MAX_LIFETIME = 120;
    private static final float MIN_SIZE = 0.09F;
    private static final float MAX_SIZE = 0.16F;
    /** 空気の抵抗(毎 tick 水平の速さに掛ける)と沈む速さ(ブロック / tick²)。 */
    private static final double DRAG = 0.985D;
    private static final double SINK = 0.0006D;
    private static final int FADE_IN = 6;
    private static final int FADE_OUT = 20;

    private final double bobPhase;
    private final float spin;

    protected G1FluffParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        this.hasPhysics = true;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.lifetime = Mth.randomBetweenInclusive(this.random, MIN_LIFETIME, MAX_LIFETIME);
        this.quadSize = Mth.lerp(this.random.nextFloat(), MIN_SIZE, MAX_SIZE);
        // 胞子の白〜くすんだ黄緑(仕様: 綿毛は元の羊の色を引き継がない)
        float k = 0.88F + this.random.nextFloat() * 0.12F;
        this.setColor(k, k, 0.86F * k + this.random.nextFloat() * 0.06F);
        this.alpha = 0.0F;
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.spin = (this.random.nextFloat() - 0.5F) * 0.08F;
        this.bobPhase = this.random.nextDouble() * Math.PI * 2.0D;
        this.pickSprite(sprites);
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
        if (this.onGround) {
            // 着いたら止まって薄れる
            this.xd = 0.0D;
            this.zd = 0.0D;
            this.age = Math.max(this.age, this.lifetime - FADE_OUT);
        } else {
            this.roll += this.spin;
            this.xd *= DRAG;
            this.zd *= DRAG;
            this.yd = this.yd * 0.96D - SINK;
        }
        double bob = Math.sin(this.age * 0.15D + this.bobPhase) * 0.004D;
        this.move(this.xd, this.yd + bob, this.zd);
        int left = this.lifetime - this.age;
        this.alpha = Mth.clamp(Math.min(this.age / (float) FADE_IN, left / (float) FADE_OUT), 0.0F, 1.0F) * 0.95F;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xd, double yd, double zd) {
            return new G1FluffParticle(level, x, y, z, xd, yd, zd, this.sprites);
        }
    }
}
