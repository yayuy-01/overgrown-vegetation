package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.ParticleGroup;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Optional;

/**
 * 汚染地域のバイオームに漂う<b>環境の胞子</b>({@code overgrown_vegetation:drifting_spore}。バイオームの {@code effects.particle})。
 *
 * <p>前はバニラの {@code spore_blossom_air}(胞子の花の粒)をそのまま使っていて、まっすぐ落ちながらだんだん速くなるので、
 * 光の筋の中でふわふわ漂う光る胞子({@link CanopyLight})と動きが違いすぎた(2026-10-15 夜、ユーザー指摘)。
 * 今は見た目(絵・色・大きさ・寿命・数)はバニラのまま、動きだけ光る胞子と同じ式({@link SporeDrift})にした —— 風下へ漂い、
 * 突風が来ると周りの胞子と一斉にその突風の向きへ流される。光の筋に入ると、緑のまま明るく照らされて見える。
 * 数の上限もバニラと同じ組({@link ParticleGroup#SPORE_BLOSSOM}、1000 粒)。
 */
@OnlyIn(Dist.CLIENT)
public class DriftingSporeParticle extends TextureSheetParticle {

    /** 光っていないときの色(バニラの spore_blossom_air と同じ)。 */
    private static final float BASE_R = 0.32F;
    private static final float BASE_G = 0.5F;
    private static final float BASE_B = 0.22F;
    /**
     * 光の筋の中の色(緑のまま明るく、少し金を帯びる)。最初は光る胞子と同じ淡い金にして大きくもしたが、この粒の絵は四角いので、
     * カメラの近くを通ると白い四角が浮いて見えた(2026-10-15 夜の撮影)。光って見える丸い粒は光の筋の側({@link CanopyLight})が描く。
     */
    private static final float LIT_R = 0.62F;
    private static final float LIT_G = 0.78F;
    private static final float LIT_B = 0.36F;

    private final double[] velocity = new double[3];
    private final float phase;
    private float lit;
    private float litO;

    protected DriftingSporeParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y - 0.125, z);
        this.setSize(0.01F, 0.01F);
        this.pickSprite(sprites);
        // 大きさ・寿命はバニラの spore_blossom_air と同じ。
        this.quadSize = this.quadSize * (this.random.nextFloat() * 0.6F + 0.6F);
        this.lifetime = Mth.randomBetweenInclusive(this.random, 500, 1000);
        this.hasPhysics = false;
        this.friction = 1.0F;
        this.gravity = 0.0F;
        this.phase = this.random.nextFloat() * Mth.TWO_PI;
        this.setColor(BASE_R, BASE_G, BASE_B);
        this.alpha = 0.0F;
        SporeDrift.matchAir(this.x, this.y, this.z, this.velocity, CanopyWind.time(0.0F), this.phase);
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
        SporeDrift.step(this.x, this.y, this.z, this.velocity, CanopyWind.time(0.0F), this.phase, this.random);
        this.move(this.velocity[0], this.velocity[1], this.velocity[2]);
        this.litO = this.lit;
        this.lit += (CanopyLight.litAtAny(this.x, this.y, this.z) - this.lit) * 0.25F;
        // 湧き始めと消え際はゆっくり(パッと出て消えない)。
        float in = Math.min(1.0F, this.age / 20.0F);
        float out = Math.min(1.0F, (this.lifetime - this.age) / 40.0F);
        this.alpha = in * out;
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        float l = Mth.lerp(partialTick, this.litO, this.lit);
        this.rCol = Mth.lerp(l, BASE_R, LIT_R);
        this.gCol = Mth.lerp(l, BASE_G, LIT_G);
        this.bCol = Mth.lerp(l, BASE_B, LIT_B);
        // 地下では光の届かない粒を見せない(2026-10-16): 暗い洞窟の中の粒は真っ黒になり、地下の霧({@link CaveMist})の明るい面に
        // 黒い四角の影絵になって浮いた。明かり(光る植物・松明・洞窟の口)の近くの粒だけ見える。
        float underground = ForestPresence.underground();
        if (underground > 0.0F) {
            int packed = this.getLightColor(partialTick);
            float seen = Mth.clamp(Math.max(LightTexture.block(packed), LightTexture.sky(packed)) / DARK_LIGHT, 0.0F, 1.0F);
            float keep = this.alpha;
            this.alpha = keep * Mth.lerp(underground, 1.0F, seen);
            super.render(buffer, camera, partialTick);
            this.alpha = keep;
            return;
        }
        super.render(buffer, camera, partialTick);
    }

    /** 地下で粒がはっきり見えるようになる明るさ(ブロックの光か空の光。これより暗いほど薄い)。 */
    private static final float DARK_LIGHT = 11.0F;

    @Override
    protected int getLightColor(float partialTick) {
        int base = super.getLightColor(partialTick);
        float l = Mth.lerp(partialTick, this.litO, this.lit);
        if (l <= 0.01F) {
            return base;
        }
        int glow = Math.round(12.0F * l);
        return LightTexture.pack(Math.max(LightTexture.block(base), glow), Math.max(LightTexture.sky(base), glow));
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public Optional<ParticleGroup> getParticleGroup() {
        return Optional.of(ParticleGroup.SPORE_BLOSSOM);
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
            // 束で描いている間は粒を作らない(GpuSpores、2026-10-23。Iris のパック使用中などは今までの粒)。
            if (GpuSpores.replacesParticles()) {
                return null;
            }
            // 高空の晴天(HighSky、2026-10-17): 晴天の高さより上では漂う胞子を出さない(少しばらつかせて境目を見せない)。
            if (y > ForestPresence.highSkyFrom() + level.random.nextFloat() * 30.0F) {
                return null;
            }
            return new DriftingSporeParticle(level, x, y, z, this.sprites);
        }
    }
}
