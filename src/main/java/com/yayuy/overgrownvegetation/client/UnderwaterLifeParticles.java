package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 水中の生き物と落ち物の粒(2026-11-13、水まわり ⑥ の直し。ユーザー「ドットが浮いているだけに見える」→ 選択「形を付ける・水に溶かす・共通の流れ」)。
 * 前は {@code client.atmosphere.UnderwaterCritters} が色の点を直接描いていて、形が無く、水の霧と光を受けずに浮いて見えた。
 * 今は絵の付いた粒(絵は {@code textures/particle/water_flea_*}・{@code sinking_leaf_*}・{@code spore_fluff_*}。Claude の仮の絵で、ユーザーが描き直せる)
 * なので、バニラの粒と同じく水の霧に溶け、その場の光の明るさで暗くなる。どれも水の中の共通の流れ({@link WaterCurrent})に流される。
 * 出す側は {@code client.atmosphere.UnderwaterCritters}。数値はすべて仮決め。
 */
@OnlyIn(Dist.CLIENT)
public final class UnderwaterLifeParticles {

    private UnderwaterLifeParticles() {
    }

    /** 水から出たら消す(10 tick おきに確かめる)。 */
    static boolean outOfWater(Particle p, ClientLevel level, double x, double y, double z, int age) {
        return age % 10 == 0 && !level.getFluidState(BlockPos.containing(x, y, z)).is(FluidTags.WATER);
    }

    // ---------------------------------------------------------------- ミジンコ

    /**
     * ミジンコ(水生の虫の群れの 1 匹)。速さの引数で群れの真ん中 (cx, cy, cz) をもらい、そのまわりを小さな輪を描いてぴょこぴょこ跳ねる。
     * 触角の 2 コマを 3 tick ごとに替える。カメラが {@link #FLEE} ブロックより近いと、自分の真ん中をカメラの反対へずらして離れる。
     * 群れの真ん中は流れに少しだけ流される(泳いで逆らう)。
     */
    public static final class WaterFlea extends TextureSheetParticle {
        static final double FLEE = 2.0;
        private final SpriteSet sprites;
        private double cx;
        private double cy;
        private double cz;
        private final double radius;
        private final double speed;
        private double phase;

        WaterFlea(ClientLevel level, double x, double y, double z, double cx, double cy, double cz, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.hasPhysics = false;
            this.gravity = 0.0F;
            this.lifetime = Mth.randomBetweenInclusive(this.random, 400, 700);
            this.quadSize = 0.12F;
            this.radius = 0.25 + this.random.nextDouble() * 0.5;
            this.speed = (this.random.nextBoolean() ? 1.0 : -1.0) * (0.08 + this.random.nextDouble() * 0.1);
            this.phase = this.random.nextDouble() * Math.PI * 2.0;
            this.cy += (this.random.nextDouble() - 0.5) * 0.6;
            this.alpha = 0.0F;
            this.setSprite(sprites.get(0, 1));
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            if (this.age++ >= this.lifetime || outOfWater(this, this.level, this.x, this.y, this.z, this.age)) {
                this.remove();
                return;
            }
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double dx = this.x - cam.x;
            double dy = this.y - cam.y;
            double dz = this.z - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < FLEE * FLEE) {
                double len = Math.max(0.2, Math.sqrt(d2));
                this.cx += dx / len * 0.18;
                this.cy += dy / len * 0.06;
                this.cz += dz / len * 0.18;
            }
            this.cx += WaterCurrent.x() * 0.3;
            this.cz += WaterCurrent.z() * 0.3;
            this.phase += this.speed;
            double hop = Math.abs(Math.sin(this.age * 0.35 + this.radius * 10.0)) * 0.12;
            double tx = this.cx + Math.cos(this.phase) * this.radius;
            double ty = this.cy + hop;
            double tz = this.cz + Math.sin(this.phase) * this.radius;
            this.setPos(Mth.lerp(0.35, this.x, tx), Mth.lerp(0.35, this.y, ty), Mth.lerp(0.35, this.z, tz));
            this.setSprite(this.sprites.get((this.age / 3) % 2, 1));
            float in = Math.min(1.0F, this.age / 20.0F);
            float out = Math.min(1.0F, (this.lifetime - this.age) / 20.0F);
            this.alpha = in * out;
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
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double cx, double cy, double cz) {
                return new WaterFlea(level, x, y, z, cx, cy, cz, this.sprites);
            }
        }
    }

    // ---------------------------------------------------------------- 沈む葉・胞子の綿

    /**
     * 沈んでいく落ち物。葉のかけら(速さの引数 x が 0 = 緑の葉・1 = 枯れ葉)は表・斜め・横向き・裏の 4 コマで回りながら、胞子の綿(x が 2)は
     * 2 コマでゆっくり回りながら、左右に揺れて沈む。流れに流される。水底に着いたら止まって薄れる。
     */
    public static final class Sinking extends TextureSheetParticle {
        private static final int[] TUMBLE = {0, 1, 2, 3, 2, 1};
        private final SpriteSet sprites;
        private final boolean fluff;
        private final double sink;
        private final double swayPhase;
        private final int frameTicks;
        private int settled;

        Sinking(ClientLevel level, double x, double y, double z, double kind, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.fluff = kind > 1.5;
            this.hasPhysics = true;
            this.gravity = 0.0F;
            this.friction = 1.0F;
            this.lifetime = Mth.randomBetweenInclusive(this.random, 300, 520);
            this.quadSize = this.fluff ? 0.07F : 0.1F;
            this.sink = this.fluff ? 0.002 + this.random.nextDouble() * 0.002 : 0.006 + this.random.nextDouble() * 0.006;
            this.swayPhase = this.random.nextDouble() * Math.PI * 2.0;
            this.frameTicks = this.fluff ? 20 + this.random.nextInt(20) : 5 + this.random.nextInt(6);
            float k = 0.85F + this.random.nextFloat() * 0.2F;
            if (this.fluff) {
                this.setColor(1.0F, 0.98F, 0.92F);
            } else if (kind < 0.5) {
                this.setColor(0.40F * k, 0.55F * k, 0.22F * k); // 緑の葉
            } else {
                this.setColor(0.52F * k, 0.38F * k, 0.22F * k); // 枯れ葉
            }
            this.alpha = 0.0F;
            this.setSprite(sprites.get(0, 1));
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            if (this.age++ >= this.lifetime || outOfWater(this, this.level, this.x, this.y, this.z, this.age)) {
                this.remove();
                return;
            }
            float in = Math.min(1.0F, this.age / 15.0F);
            if (this.onGround) {
                // 水底に着いた: 止まって 40 tick で薄れる。
                this.settled++;
                this.alpha = in * Math.max(0.0F, 1.0F - this.settled / 40.0F);
                if (this.settled >= 40) {
                    this.remove();
                }
                return;
            }
            double sway = Math.sin(this.age * 0.08 + this.swayPhase) * (this.fluff ? 0.004 : 0.012);
            this.move(WaterCurrent.x() + sway, -this.sink, WaterCurrent.z() + Math.cos(this.age * 0.07 + this.swayPhase) * (this.fluff ? 0.003 : 0.008));
            int n = this.fluff ? 2 : TUMBLE.length;
            int step = (this.age / this.frameTicks) % n;
            int frame = this.fluff ? step : TUMBLE[step];
            this.setSprite(this.sprites.get(frame, this.fluff ? 1 : 3));
            float out = Math.min(1.0F, (this.lifetime - this.age) / 30.0F);
            this.alpha = in * out;
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
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double kind, double unusedY, double unusedZ) {
                return new Sinking(level, x, y, z, kind, this.sprites);
            }
        }
    }
}
