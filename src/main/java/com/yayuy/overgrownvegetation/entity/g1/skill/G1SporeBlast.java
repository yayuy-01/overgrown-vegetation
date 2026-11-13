package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 胞子が四方へ爆ぜる(2026-11-13、能力の第 3 回。共通の部品 —— クリーパーの 1 段目の (b)「胞子の爆ぜ」と (c) 落ちた莢の破裂が使う)。
 * <b>ブロックは壊さない</b>(仕様 Vault「G1 クリーパー(1 段目)」【決定 11-09】)。その場で 1 回だけ次を起こす。
 *
 * <ul>
 *   <li>周りの地面を汚染ブロックで覆う: {@link NoisyCorruptionBurst#deathSphere}(1 tick の上限つき・球の放出は使わない)。
 *   汚染が止まっている・胞子除去装置の中では覆わない({@code corpse/G1DeathBurst} と同じ守り)。</li>
 *   <li>升の浮遊を {@code airPercent} %(100% = {@code spore_field.json} の fullAt)上げる。11-12 ユーザー決定「G1 だけ小さく」に合わせ、
 *   球の式(0.5 × 半径²。半径 3 で fullAt の約 8 倍)は使わず、呼ぶ側が % で渡す。</li>
 *   <li>範囲の中の相手(植物側・出した本人を除く。壁の向こうは届かない)へ暴露量(中心 {@code doseCenter} → 縁 {@code doseEdge} の直線。
 *   {@link G1SporeDose} = マスクで防げる)。{@code damage} が 0 より大きければ、範囲の中どこでも同じダメージ(縁で減らさない【決定 11-09】)と押し返し。</li>
 *   <li>見た目: 膨らむ胞子の雲と細かい胞子の粒(破裂と同じ粒)。音は呼ぶ側。</li>
 * </ul>
 */
public final class G1SporeBlast {

    private G1SporeBlast() {
    }

    /**
     * @param owner         出した Mob(この Mob には効かない。ダメージの出どころ。null 可 = ダメージなしで使う)
     * @param feet          中心(足元)
     * @param corruptRadius 汚染ブロックで覆う半径(0 で覆わない)
     * @param noise         覆う球の輪郭の揺らぎ(ブロック、±)
     * @param airPercent    升の浮遊の上げ幅(%)
     * @param doseCenter    中心での暴露量(%秒)
     * @param doseEdge      縁({@code doseRadius})での暴露量
     * @param doseRadius    暴露量・ダメージの届く半径
     * @param damage        ダメージ(0 で無し)
     * @param knockback     押し返しの強さ(0 で無し)
     * @return 範囲の中にいた相手
     */
    public static List<LivingEntity> blast(ServerLevel level, @Nullable LivingEntity owner, Vec3 feet, int corruptRadius, double noise,
                                           double airPercent, double doseCenter, double doseEdge, double doseRadius, float damage,
                                           double knockback) {
        // 1. 範囲の中の相手
        Vec3 mid = feet.add(0.0D, 0.6D, 0.0D);
        List<LivingEntity> hit = new ArrayList<>();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(mid, mid).inflate(doseRadius + 1.0D))) {
            if (e == owner || !e.isAlive() || SporeExposureTracker.isPlantSide(e)) continue;
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Vec3 to = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            double d = Math.max(0.0D, to.distanceTo(mid) - e.getBbWidth() * 0.5D);
            if (d > doseRadius) continue;
            if (level.clip(new ClipContext(mid, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() == HitResult.Type.BLOCK) {
                continue;
            }
            hit.add(e);
            G1SporeDose.add(e, Mth.lerp(doseRadius <= 0.0D ? 0.0D : d / doseRadius, doseCenter, doseEdge));
            if (damage > 0.0F && owner != null) {
                e.hurt(level.damageSources().mobAttack(owner), damage);
            }
            if (knockback > 0.0D) {
                e.knockback(knockback, feet.x - e.getX(), feet.z - e.getZ());
            }
        }

        // 2. 覆う + 浮遊(相手に当てた後。覆った地面にその場で生える植物が、当たりの見通しを塞がないように。2026-11-13 第 4 回で順を入れ替えた)
        boolean corrupts = !CorruptionSwitch.isOff()
                && !SporeDeviceData.inhibits(level, BlockPos.containing(feet), SporeDeviceData.Inhibit.CORRUPTION);
        if (corrupts) {
            if (corruptRadius > 0) {
                NoisyCorruptionBurst.deathSphere(level, feet, corruptRadius, noise, false);
            }
            if (airPercent > 0.0D && (level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER)) {
                SporeFieldServer.emitPoint(level, BlockPos.containing(feet), airPercent / 100.0D * DesignData.sporeField().fullAt());
            }
        }

        // 3. 見た目(量は届く半径に合わせる)
        double r = Math.max(1.0D, doseRadius);
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), mid.x, mid.y + 0.3D, mid.z, (int) Math.round(2.5D * r * r),
                r * 0.4D, 0.4D, r * 0.4D, 0.02D);
        level.sendParticles(ModParticles.SPORE_JET.get(), mid.x, mid.y + 0.3D, mid.z, Math.min(500, (int) Math.round(35.0D * r * r)),
                r * 0.45D, 0.5D, r * 0.45D, 0.04D);
        return hit;
    }
}
