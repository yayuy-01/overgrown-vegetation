package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere;
import com.yayuy.overgrownvegetation.client.atmosphere.Wind;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 「噴き出す胞子」(2026-10-23、萌芽体の胞子嚢。ユーザー「大きな胞子 1 粒がポッと出るのは嫌。小さな粒子が一点から勢いよく噴き出す形に」)。
 *
 * <p>大きさは細かい胞子({@code FineSporeParticle}、parked)と同じ小ささ。最初は与えた速さ(毎 tick 0.15〜0.3 ブロック)で吹き上がり、
 * 空気の抵抗で毎 tick 速さが落ち、重力でわずかに沈みながら、だんだん周りの空気の流れに乗って流され、2〜4 秒で薄れて消える。数値はすべて仮決め。
 *
 * <p>空気の流れは {@link #drift}。2026-10-31 に parked/ から戻したとき、前の風({@code CanopyWind}・{@code SporeDrift})は退避したままにし、
 * 作り直した風 {@link Wind#current()}(突風の前線 {@code GustField})を読むようにした(ユーザー「新しい風に乗せる」)。
 * 流される速さと渦の式は前の {@code SporeDrift} を写した。
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
    /** 速さを空気へ寄せる割合(毎 tick。約 0.5 秒で追いつく)。 */
    private static final double FOLLOW = 0.09D;
    /** 空気に乗った粒の沈む速さ(ブロック/tick)。 */
    private static final double SETTLE = -0.0012D;
    /** {@link Wind#velocity} の受け皿(粒の tick は描画スレッドだけ)。 */
    private static final double[] WIND = new double[2];

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
            drift(this.x, this.z, this.velocity, Atmosphere.time(0.0F), this.phase, this.random);
        }
        this.move(this.velocity[0], this.velocity[1], this.velocity[2]);
        // 後半をなだらかに薄く(点滅して消えないように)
        float fadeFrom = this.lifetime * 0.5F;
        this.alpha = this.age > fadeFrom ? Math.max(0.0F, 1.0F - (this.age - fadeFrom) / (this.lifetime - fadeFrom)) : 1.0F;
    }

    /**
     * 速度 {@code v} = {x, y, z}(ブロック/tick)を (x, z) の空気の速さへ 1 tick 寄せる。空気 = 風に流される速さ + 渦 + 沈み。
     * 流される速さは凪(強さ 0.2)で毎秒 0.3 ブロック、突風のさなか(1.0)で 3.3、いちばん強い所(1.2)で 5 ブロック。
     */
    private static void drift(double x, double z, double[] v, double time, double phase, RandomSource random) {
        Wind.current().velocity(x, z, time, WIND);
        double wx = WIND[0];
        double wz = WIND[1];
        double w = Math.sqrt(wx * wx + wz * wz);
        double wc = Math.min(w, 1.2D);
        double speed = 0.004D + 0.05D * wc + 0.11D * wc * wc * wc;
        double ux = w > 1.0E-6D ? wx / w * speed : 0.0D;
        double uz = w > 1.0E-6D ? wz / w * speed : 0.0D;
        // 渦: 近くの粒が揃って回るゆっくりした流れと、突風のときだけ目立つ小さい渦(粒ごとに少しずらす)
        double a = 0.003D + 0.03D * wc;
        double b = 0.012D * wc;
        double ax = a * Math.sin(0.23D * z + 0.043D * time + 1.3D) * Math.cos(0.19D * x - 0.031D * time)
                + b * Math.sin(0.9D * z + 0.21D * time + phase);
        double ay = 0.6D * a * Math.sin(0.29D * x + 0.17D * z - 0.037D * time + 2.1D)
                + 0.7D * b * Math.sin(0.95D * x + 1.2D * z - 0.19D * time + phase * 1.3D);
        double az = a * Math.sin(0.31D * x - 0.039D * time + 0.7D) * Math.cos(0.23D * z + 0.033D * time)
                + b * Math.sin(1.05D * x - 0.23D * time + phase * 0.7D);
        double jitter = 0.0009D * (1.0D + 2.0D * wc);
        v[0] += (ux + ax - v[0]) * FOLLOW + (random.nextDouble() - 0.5D) * jitter;
        v[1] += (SETTLE + ay - v[1]) * FOLLOW + (random.nextDouble() - 0.5D) * jitter * 0.6D;
        v[2] += (uz + az - v[2]) * FOLLOW + (random.nextDouble() - 0.5D) * jitter;
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
