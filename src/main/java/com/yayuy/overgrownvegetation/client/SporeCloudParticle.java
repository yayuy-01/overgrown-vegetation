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
 * 破裂の胞子の雲(2026-11-12、G1 進化体の破裂 {@link com.yayuy.overgrownvegetation.corpse.G1DeathBurst})。
 * ユーザー決定「ゆっくり膨らんで薄れる大きめの粒を足し、吸うと危ない所が雲として見えるようにする(絵はバニラのもの)」。
 *
 * <p>絵はバニラの焚き火の煙({@code big_smoke_0〜5} のどれか。後ろの方の崩れた絵は使わない)を濁った黄緑に染める。
 * 出た勢いは空気の抵抗ですぐ抜け、その場にほぼ留まってわずかに昇りながら、大きさが 1.9 倍ほどまでゆっくり膨らむ。
 * 最初の {@link #FADE_IN} tick で濃くなり、寿命の {@link #FADE_FROM} から薄れて消える(2.5〜3.5 秒)。
 * 濃さ(どれだけ重なるか)は出す側が粒の数で決める。数値はすべて【仮】。
 */
@OnlyIn(Dist.CLIENT)
public class SporeCloudParticle extends TextureSheetParticle {

    /** 寿命(tick)。【仮】 */
    static final int MIN_LIFETIME = 50;
    static final int MAX_LIFETIME = 70;
    /** 出たときの大きさ(半幅、ブロック)と、膨らみきったときの倍率。【仮】 */
    private static final float MIN_SIZE = 0.45F;
    private static final float MAX_SIZE = 0.75F;
    private static final float GROW = 0.9F;
    /** いちばん濃いときの不透明度。【仮】 */
    private static final float MAX_ALPHA = 0.38F;
    /** 濃くなるまで(tick)・薄れ始める割合。【仮】 */
    private static final int FADE_IN = 8;
    private static final float FADE_FROM = 0.4F;
    /** 空気の抵抗(毎 tick 速さに掛ける)と昇る速さ(ブロック/tick)。【仮】 */
    private static final double DRAG = 0.88D;
    private static final double RISE = 0.0025D;

    private final float baseSize;

    protected SporeCloudParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
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
        // 胞子の濁った黄緑(少しばらす)
        float k = 0.9F + this.random.nextFloat() * 0.15F;
        this.setColor(0.62F * k, 0.70F * k, 0.42F * k);
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
        this.move(this.xd, this.yd + RISE, this.zd);
        float t = (float) this.age / this.lifetime;
        // 膨らみは初めが速く、だんだんゆっくり
        float ease = 1.0F - (1.0F - t) * (1.0F - t);
        this.quadSize = this.baseSize * (1.0F + GROW * ease);
        this.roll += 0.004F;
        float in = Math.min(1.0F, (float) this.age / FADE_IN);
        float out = t < FADE_FROM ? 1.0F : Math.max(0.0F, 1.0F - (t - FADE_FROM) / (1.0F - FADE_FROM));
        this.alpha = MAX_ALPHA * in * out * out;
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
            return new SporeCloudParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
