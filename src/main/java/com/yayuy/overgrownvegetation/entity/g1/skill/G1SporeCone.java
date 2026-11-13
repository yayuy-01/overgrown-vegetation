package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 前への短い胞子の噴流(円錐)を 1 回吐く(2026-11-12、能力の第 1 回。共通の部品 —— 豚の喉袋の (c) のほか、後の回の種も使える)。
 *
 * <ul>
 *   <li>当たり: 口(目の高さより少し下)から {@code yaw} の向きへ長さ {@code length}・開き ±{@code halfAngleDeg}° の円錐の中にいる相手
 *   (植物側・吐いた本人を除く。間に壁があれば当たらない)へ暴露量 {@code dose} を 1 回({@link G1SporeDose}。マスクで防げる)。</li>
 *   <li>降り積もり: 円錐の先の升へ {@code deposit}(場の降り積もりの単位。発芽のしきい値は 0.05)を足す(仕様「地面に少し降り積もる」)。</li>
 *   <li>見た目: 胞子の粒を円錐の向きへ速さを付けて吹き出す(数 0 の sendParticles = 粒ごとの速度)。音はえずくような湿った音。</li>
 * </ul>
 */
public final class G1SporeCone {

    private G1SporeCone() {
    }

    /**
     * 円錐を吐く(サーバー)。
     *
     * @return 当たった相手(暴露量が足されたかどうかに関わらず、円錐の中にいた相手)
     */
    public static List<LivingEntity> puff(ServerLevel level, LivingEntity source, float yaw, double length, double halfAngleDeg,
                                          double dose, double deposit) {
        Vec3 mouth = source.position().add(0.0D, source.getBbHeight() * 0.55D, 0.0D);
        Vec3 dir = Vec3.directionFromRotation(0.0F, yaw);
        List<LivingEntity> hit = new ArrayList<>();
        AABB box = new AABB(mouth, mouth).inflate(length + 1.0D);
        double cos = Math.cos(Math.toRadians(halfAngleDeg));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == source || SporeExposureTracker.isPlantSide(e) || !e.isAlive()) continue;
            Vec3 to = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D).subtract(mouth);
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            double d = flat.length();
            if (d > length + e.getBbWidth() * 0.5D || Math.abs(to.y) > length) continue;
            if (d > 0.3D && flat.normalize().dot(dir) < cos) continue;
            if (level.clip(new ClipContext(mouth, mouth.add(to), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)).getType()
                    == HitResult.Type.BLOCK) continue;
            hit.add(e);
            G1SporeDose.add(e, dose);
        }
        if (deposit > 0.0D) {
            SporeFieldServer.depositPoint(level, BlockPos.containing(mouth.add(dir.scale(length))), deposit);
        }
        // 見た目: 円錐の向きへ吹き出す粒
        RandomSource random = level.random;
        int dots = (int) Math.round(30 * length);
        for (int i = 0; i < dots; i++) {
            float a = yaw + (float) ((random.nextDouble() * 2.0D - 1.0D) * halfAngleDeg);
            Vec3 v = Vec3.directionFromRotation((float) ((random.nextDouble() - 0.3D) * 20.0D), a);
            double speed = Mth.lerp(random.nextDouble(), 0.12D, 0.32D) * length / 3.0D;
            level.sendParticles(ModParticles.SPORE_JET.get(), mouth.x, mouth.y, mouth.z, 0, v.x * speed, v.y * speed + 0.01D, v.z * speed, 1.0D);
        }
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), mouth.x + dir.x * length * 0.6D, mouth.y, mouth.z + dir.z * length * 0.6D,
                4, length * 0.2D, 0.2D, length * 0.2D, 0.01D);
        level.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 0.9F, 0.55F);
        level.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 0.7F, 0.6F);
        return hit;
    }
}
