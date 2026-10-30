package com.yayuy.overgrownvegetation.ally;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 兵士の銃(2026-10-01、仮)。弾は飛ばさず、銃口から狙いの方向へ線を引いて最初に当たったブロックか生き物で止める。
 * 見た目は銃口の煙・弾道の筋(粒子)・当たった所の破片、音はバニラの花火の破裂音を高めに(銃の音はまだ無い)。
 *
 * <p>味方(兵士・プレイヤー)が線の上にいたら撃たない(誤射しない)。連射で傷が無効時間に吸われないよう、当てる直前に相手の無効時間を消す
 * (銃の MOD の定番のやり方)。
 */
public final class SoldierGun {

    /** 弾道の筋の粒子の間隔(マス)。 */
    private static final double TRACER_STEP = 1.6;

    /**
     * {@code shooter} から {@code target} へ 1 発撃つ。撃ったら真(味方が線の上にいて撃たなかったら偽)。
     */
    public static boolean fire(SoldierEntity shooter, LivingEntity target, SoldierKind kind) {
        if (!(shooter.level() instanceof ServerLevel level)) {
            return false;
        }
        Vec3 from = muzzle(shooter);
        Vec3 aim = target.getBoundingBox().getCenter().subtract(from);
        double dist = aim.length();
        if (dist < 1.0E-3 || dist > kind.range + 2) {
            return false;
        }
        // 味方を線の上に挟んでいたら撃たない(ぶれる前のまっすぐな線で見る)
        if (friendlyInLine(level, shooter, from, from.add(aim))) {
            return false;
        }
        Vec3 dir = spread(aim.normalize(), kind.spread, shooter);
        Vec3 to = from.add(dir.scale(kind.range));
        BlockHitResult block = SoldierSight.clip(level, from, to, shooter); // 金網・鉄格子・柵・葉は抜ける
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, shooter, from, end,
                new AABB(from, end).inflate(1.0), e -> e instanceof LivingEntity && e.isAlive() && e != shooter && !(e instanceof SoldierEntity));
        if (hit != null && hit.getEntity() instanceof LivingEntity victim && !(victim instanceof Player)) {
            end = hit.getLocation();
            victim.invulnerableTime = 0;
            victim.hurt(bullet(level, shooter), kind.damage);
            level.sendParticles(ParticleTypes.CRIT, end.x, end.y, end.z, 4, 0.1, 0.1, 0.1, 0.2);
        } else if (block.getType() == HitResult.Type.BLOCK) {
            BlockState state = level.getBlockState(block.getBlockPos());
            if (!state.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), end.x, end.y, end.z, 3, 0.05, 0.05, 0.05, 0.1);
            }
        }
        tracer(level, from, end);
        level.sendParticles(ParticleTypes.SMOKE, from.x, from.y, from.z, 1, 0.02, 0.02, 0.02, 0.0);
        level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST,
                SoundSource.HOSTILE, 1.6F, 1.5F + shooter.getRandom().nextFloat() * 0.2F);
        return true;
    }

    /** 兵士の弾の傷(撃った兵士が倒した相手の手柄になり、撃たれた相手は兵士へ撃ち返しに来る)。 */
    static DamageSource bullet(ServerLevel level, SoldierEntity shooter) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(AllyEntities.BULLET), shooter);
    }

    /** 銃口のおよその位置(目の高さの少し下、体の前、右手の側)。 */
    static Vec3 muzzle(SoldierEntity shooter) {
        Vec3 look = Vec3.directionFromRotation(shooter.getXRot(), shooter.yHeadRot);
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
        return shooter.getEyePosition().add(0, -0.25, 0).add(look.scale(0.9)).add(right.scale(0.25));
    }

    /** 方向 {@code dir} を、標準偏差 {@code degrees} 度の正規分布でぶらす。 */
    private static Vec3 spread(Vec3 dir, float degrees, Entity shooter) {
        double s = Math.toRadians(degrees);
        double yaw = Math.atan2(-dir.x, dir.z) + shooter.getRandom().nextGaussian() * s;
        double pitch = Math.asin(Mth.clamp(-dir.y, -1.0, 1.0)) + shooter.getRandom().nextGaussian() * s;
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /** 線の上(太さ 0.3)に、撃ち手以外の兵士かプレイヤーがいるか。 */
    static boolean friendlyInLine(ServerLevel level, SoldierEntity shooter, Vec3 from, Vec3 to) {
        AABB box = new AABB(from, to).inflate(1.0);
        for (Entity e : level.getEntities(shooter, box, e -> e instanceof SoldierEntity || e instanceof Player && !((Player) e).isSpectator())) {
            if (e.getBoundingBox().inflate(0.3).clip(from, to).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 弾の軌跡の火花。バニラの {@code sendParticles} と同じく、プレイヤーごとに 32 ブロック以内の点だけを送るが、
     * 1 発ぶんを 1 つの束(バンドル)にまとめる(前は点ごとに 1 パケット = 1 発で最大 25。2026-10-10、0.1.9 の前の軽量化。見た目は同じ)。
     */
    private static void tracer(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        int n = (int) (len / TRACER_STEP);
        if (n <= 0) {
            return;
        }
        java.util.List<ClientboundLevelParticlesPacket> packets = new java.util.ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            Vec3 p = from.add(d.scale(i / (double) (n + 1)));
            packets.add(new ClientboundLevelParticlesPacket(ParticleTypes.ELECTRIC_SPARK, false, p.x, p.y, p.z, 0.0F, 0.0F, 0.0F, 0.0F, 1));
        }
        for (ServerPlayer player : level.players()) {
            java.util.List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> near = new java.util.ArrayList<>();
            BlockPos at = player.blockPosition();
            for (ClientboundLevelParticlesPacket packet : packets) {
                if (at.closerToCenterThan(new Vec3(packet.getX(), packet.getY(), packet.getZ()), 32.0D)) {
                    near.add(packet);
                }
            }
            if (near.size() == 1) {
                player.connection.send(near.get(0));
            } else if (!near.isEmpty()) {
                player.connection.send(new ClientboundBundlePacket(near));
            }
        }
    }

    private SoldierGun() {
    }
}
