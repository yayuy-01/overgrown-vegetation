package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 空の巨大生物の大きな粒(2026-10-18 夜、仮)。体が 約 2100 ブロックあり、バニラの爆発(2 ブロック)・煙は数百ブロック先で点にもならないので、
 * 大きさを持たせた粒を足した。絵はバニラの爆発・焚き火の煙のものをそのまま使う(色を足さない)。
 *
 * <p>サーバーは {@code sendParticles} を数 0 で送り、速さの x = 大きさ(ブロック)、y = 現れるまでの遅れ(tick)として渡す
 * (数 0 のときバニラは速さをそのまま粒へ渡す)。技とエフェクトの段で作り直すまでの印。
 *
 * <p><b>霧を掛けない</b>: ボスは霧の外(描画距離より遠く)まで見えるように霧を切って描いている。粒もふつうに描くと霧の色に塗られて空に溶けるので
 * ({@code /ov sky} の確かめで、160 ブロック先の煙が空色の塊になった)、自分の描き方 {@link #NO_FOG} で霧を切り、粒を描き終えた所
 * ({@link RenderLevelStageEvent.Stage#AFTER_PARTICLES}。半透明の粒の後にだけ来る → この描き方は半透明)で戻す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SkyBossParticles {

    private static boolean fogOff;
    private static float fogStart;
    private static float fogEnd;

    /** 半透明の粒の描き方(バニラの PARTICLE_SHEET_TRANSLUCENT と同じ)+ 霧を切る。 */
    static final ParticleRenderType NO_FOG = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            if (!fogOff) {
                fogStart = RenderSystem.getShaderFogStart();
                fogEnd = RenderSystem.getShaderFogEnd();
                fogOff = true;
            }
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            RenderSystem.depthMask(true);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "OV_SKY_BOSS_NO_FOG";
        }
    };

    /** 粒を描き終えたら霧を戻す。 */
    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES && fogOff) {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            fogOff = false;
        }
    }

    /** 爆発: バニラの大きな爆発と同じ絵・明るさ(いつも明るい)で、大きさと遅れを持つ。 */
    static final class Blast extends TextureSheetParticle {
        private final SpriteSet sprites;
        private final int delay;

        Blast(ClientLevel level, double x, double y, double z, double size, double delay, SpriteSet sprites) {
            super(level, x, y, z, 0.0, 0.0, 0.0);
            this.sprites = sprites;
            this.delay = Math.max(0, (int) delay);
            this.lifetime = this.delay + 8 + this.random.nextInt(5);
            // バニラの爆発と同じ明るさのばらつき
            float f = this.random.nextFloat() * 0.6F + 0.4F;
            this.rCol = f;
            this.gCol = f;
            this.bCol = f;
            this.quadSize = (float) Math.max(1.0, size) * (0.8F + 0.4F * this.random.nextFloat());
            this.hasPhysics = false;
            this.gravity = 0.0F;
            this.setSprite(sprites.get(0, 1));
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            if (this.age++ >= this.lifetime) {
                this.remove();
            } else if (this.age >= this.delay) {
                this.setSprite(this.sprites.get(this.age - this.delay, this.lifetime - this.delay));
            }
        }

        @Override
        public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
            if (this.age >= this.delay) {
                super.render(buffer, camera, partialTicks);
            }
        }

        @Override
        public int getLightColor(float partialTick) {
            return 15728880;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return NO_FOG;
        }
    }

    /** 煙: 焚き火の煙の絵で、大きく・ゆっくり昇って広がり、終わりに薄れる。 */
    static final class Smoke extends TextureSheetParticle {
        private final int delay;
        private final float grow;

        Smoke(ClientLevel level, double x, double y, double z, double size, double delay, SpriteSet sprites) {
            super(level, x, y, z, 0.0, 0.0, 0.0);
            this.delay = Math.max(0, (int) delay);
            this.lifetime = this.delay + 100 + this.random.nextInt(60);
            this.quadSize = (float) Math.max(1.0, size) * (0.7F + 0.6F * this.random.nextFloat());
            this.grow = 1.004F + 0.004F * this.random.nextFloat();
            double s = Math.max(1.0, size) * 0.004;
            this.xd = (this.random.nextDouble() - 0.5) * s;
            this.yd = s * (0.5 + this.random.nextDouble());
            this.zd = (this.random.nextDouble() - 0.5) * s;
            this.hasPhysics = false;
            this.gravity = 0.0F;
            this.friction = 1.0F;
            this.alpha = 0.85F;
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
            if (this.age < this.delay) {
                return;
            }
            this.move(this.xd, this.yd, this.zd);
            this.quadSize *= this.grow;
            int left = this.lifetime - this.age;
            if (left < 50) {
                this.alpha = 0.85F * left / 50.0F;
            }
        }

        @Override
        public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
            if (this.age >= this.delay) {
                super.render(buffer, camera, partialTicks);
            }
        }

        @Override
        public ParticleRenderType getRenderType() {
            return NO_FOG;
        }
    }

    public static final class BlastProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public BlastProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xs, double ys, double zs) {
            return new Blast(level, x, y, z, xs, ys, this.sprites);
        }
    }

    public static final class SmokeProvider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public SmokeProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double xs, double ys, double zs) {
            return new Smoke(level, x, y, z, xs, ys, this.sprites);
        }
    }

    private SkyBossParticles() {
    }
}
