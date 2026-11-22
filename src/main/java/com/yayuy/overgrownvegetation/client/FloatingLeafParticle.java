package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.atmosphere.WaterCurrent;
import net.minecraft.client.Camera;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * <b>水面に浮く落ち葉</b>(2026-11-13、密林の水辺 ②。ユーザー選択「水面に浮く落ち葉: 水面にちらほら浮かぶ葉。流れにゆっくり運ばれる」。
 * 理由: 林冠から落ちた葉が水面に浮き、水の流れ({@link WaterCurrent})が目に見える)。
 * 見た目だけの粒。水面に平らに寝かせた 1 枚の板(上下どちらから見ても見える)で、ゆっくり回りながら流れに運ばれ、岸やブロックに当たると止まる。
 * 絵は沈む葉と同じ({@code sinking_leaf_0}・{@code _3} = 表・裏)、色は緑の葉と枯れ葉。出す側は {@code client.atmosphere.FloatingLeaves}。
 * 速さの引数の 1 つ目で葉の種類(0 = 緑・1 = 枯れ葉)、2 つ目で水面の高さ(ワールドの y)を受け取る。数値はすべて仮決め。
 */
@OnlyIn(Dist.CLIENT)
public class FloatingLeafParticle extends TextureSheetParticle {

    private static final int FADE_IN = 40;
    private static final int FADE_OUT = 60;

    private final double surface;
    /** 前に膜の筋を足した所(11-13)。 */
    private double wakeX = Double.NaN;
    private double wakeZ;
    private final double speed;
    private final float spin;
    private final double bobPhase;
    private float yaw;
    private float yawO;
    private int stuck;

    protected FloatingLeafParticle(ClientLevel level, double x, double z, double kind, double surface, SpriteSet sprites) {
        super(level, x, surface + 0.02, z);
        this.surface = surface;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.lifetime = Mth.randomBetweenInclusive(this.random, 900, 1800);
        this.quadSize = 0.1F + this.random.nextFloat() * 0.05F;
        this.speed = 0.7 + this.random.nextDouble() * 0.6;
        this.spin = (this.random.nextFloat() - 0.5F) * 0.012F;
        this.bobPhase = this.random.nextDouble() * Math.PI * 2.0;
        this.yaw = this.random.nextFloat() * Mth.TWO_PI;
        this.yawO = this.yaw;
        float k = 0.85F + this.random.nextFloat() * 0.2F;
        if (kind < 0.5) {
            this.setColor(0.40F * k, 0.55F * k, 0.22F * k); // 緑の葉
        } else {
            this.setColor(0.52F * k, 0.38F * k, 0.22F * k); // 枯れ葉
        }
        this.alpha = 0.0F;
        this.setSprite(sprites.get(this.random.nextFloat() < 0.7F ? 0 : 3, 3));
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.yawO = this.yaw;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (this.age % 10 == 0 && !floats(this.level, this.x, this.z, this.surface, pos)) {
            this.remove();
            return;
        }
        double nx = this.x + WaterCurrent.x() * this.speed;
        double nz = this.z + WaterCurrent.z() * this.speed;
        if (floats(this.level, nx, nz, this.surface, pos)) {
            this.yaw += this.spin;
        } else {
            nx = this.x;
            nz = this.z;
            this.stuck++;
        }
        double bob = Math.sin(this.age * 0.05 + this.bobPhase) * 0.008;
        this.setPos(nx, this.surface + 0.02 + bob, nz);
        // 浮く葉は膜を細く弱くかき分ける(11-13 ユーザー「流木・浮く落ち葉も跡を付ける」。流木は同日に消した)。
        if (Double.isNaN(this.wakeX) || Mth.lengthSquared(nx - this.wakeX, nz - this.wakeZ) >= 0.16 || this.age % 40 == 0) {
            double fx = Double.isNaN(this.wakeX) ? nx : this.wakeX;
            double fz = Double.isNaN(this.wakeX) ? nz : this.wakeZ;
            com.yayuy.overgrownvegetation.client.atmosphere.FilmWakes.stampLocal(fx, fz, nx, nz, 0.14F, 0.35F);
            this.wakeX = nx;
            this.wakeZ = nz;
        }
        float in = Math.min(1.0F, this.age / (float) FADE_IN);
        float out = Math.min(1.0F, (this.lifetime - this.age) / (float) FADE_OUT);
        // 岸に引っかかったら 600 tick ほどで沈んで消える(薄れる)。
        float hold = this.stuck > 600 ? Math.max(0.0F, 1.0F - (this.stuck - 600) / 60.0F) : 1.0F;
        if (hold <= 0.0F) {
            this.remove();
            return;
        }
        this.alpha = in * out * hold;
    }

    /** (x, z) の列が水面の高さ surface で浮いていられるか: 水面のブロックが水で、その上に当たり判定が無い。 */
    public static boolean floats(ClientLevel level, double x, double z, double surface, BlockPos.MutableBlockPos pos) {
        int by = Mth.floor(surface - 0.5);
        pos.set(Mth.floor(x), by, Mth.floor(z));
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            return false;
        }
        pos.move(0, 1, 0);
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && !level.getFluidState(pos).is(FluidTags.WATER);
    }

    /** 水面に平らに寝かせた板を、上向きと下向きの 2 枚で描く。 */
    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cam.x);
        float py = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cam.y);
        float pz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cam.z);
        float a = Mth.lerp(partialTicks, this.yawO, this.yaw);
        float s = this.getQuadSize(partialTicks);
        float c = Mth.cos(a) * s;
        float n = Mth.sin(a) * s;
        // 角: (+c,+n) を u 方向、(-n,+c) を v 方向に。
        float x0 = -c + n, z0 = -n - c;
        float x1 = -c - n, z1 = -n + c;
        float x2 = c - n, z2 = n + c;
        float x3 = c + n, z3 = n - c;
        float u0 = this.getU0();
        float u1 = this.getU1();
        float v0 = this.getV0();
        float v1 = this.getV1();
        int light = this.getLightColor(partialTicks);
        // 上向き
        vertex(buffer, px + x0, py, pz + z0, u0, v0, light);
        vertex(buffer, px + x1, py, pz + z1, u0, v1, light);
        vertex(buffer, px + x2, py, pz + z2, u1, v1, light);
        vertex(buffer, px + x3, py, pz + z3, u1, v0, light);
        // 下向き(水中から見上げたとき)
        vertex(buffer, px + x3, py, pz + z3, u1, v0, light);
        vertex(buffer, px + x2, py, pz + z2, u1, v1, light);
        vertex(buffer, px + x1, py, pz + z1, u0, v1, light);
        vertex(buffer, px + x0, py, pz + z0, u0, v0, light);
    }

    private void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(this.rCol, this.gCol, this.bCol, this.alpha).setLight(light);
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
                                       double kind, double surface, double unused) {
            return new FloatingLeafParticle(level, x, z, kind, surface, this.sprites);
        }
    }
}
