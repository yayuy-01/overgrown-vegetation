package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.ToDoubleFunction;

/**
 * エンダーマンの 1 段目の転移の道具(2026-11-13、能力の第 4 回。仕様 Vault「G1 エンダーマン(1 段目)」3 節 (b)【決定 11-09・数は仮】)。
 * 行き先を選ぶ・立てる所を探す・渡る(両端で胞子が噴く)。溜めは使う側の技({@link G1TeleportGoal}・{@link G1GrabGoal})が持つ。
 *
 * <ul>
 *   <li>距離は {@value #MIN}〜{@value #MAX} ブロック(元のエンダーマンと同じ近さ。遠くへ運ぶのは 2 段目)。</li>
 *   <li>立てる所: 下が動きを止めるブロックで水でなく、体の箱がどこにも当たらず液体に浸からない所(バニラの転移と同じ条件)。</li>
 *   <li>渡ると、<b>出た所と着いた所に胞子が噴く</b>(植物が空間を無理に渡るので体から漏れる): 半径 {@value #PUFF_RADIUS} へ暴露量
 *   {@value #PUFF_DOSE_CENTER} → {@value #PUFF_DOSE_EDGE}【仮】({@link G1SporeBlast}。ダメージ・汚染・升の浮遊は無し)。音は元の転移の音を低く。</li>
 * </ul>
 */
public final class G1Blink {

    public static final double MIN = 8.0D;
    public static final double MAX = 16.0D;
    /** 標的のそばへ着くときの、標的からの距離。 */
    static final double NEAR_MIN = 2.5D, NEAR_MAX = 3.5D;
    static final int TRIES = 16;
    static final double PUFF_RADIUS = 2.0D;
    public static final double PUFF_DOSE_CENTER = 150.0D;
    static final double PUFF_DOSE_EDGE = 50.0D;

    private G1Blink() {
    }

    /** ({@code x}, {@code z}) の列で、{@code yStart} の少し上から下へ探して立てる所(足元の座標)。無ければ null。 */
    @Nullable
    public static Vec3 standAt(DevelopedParasitized mob, double x, double yStart, double z) {
        var level = mob.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int top = Mth.floor(yStart) + 3;
        float w = mob.getBbWidth() * 0.5F, h = mob.getBbHeight();
        for (int y = top; y >= top - 10; y--) {
            p.set(Mth.floor(x), y - 1, Mth.floor(z));
            if (!level.isLoaded(p)) return null;
            BlockState below = level.getBlockState(p);
            if (!below.blocksMotion()) continue;
            if (below.getFluidState().is(FluidTags.WATER)) return null;
            AABB box = new AABB(x - w, y, z - w, x + w, y + h, z + w);
            if (level.noCollision(mob, box) && !level.containsAnyLiquid(box)) {
                return new Vec3(x, y, z);
            }
            return null; // いちばん上の地面に立てなければ、その列は使わない(天井の上・地中へは渡らない)
        }
        return null;
    }

    /**
     * 標的のそばへ: 届くなら標的から {@value #NEAR_MIN}〜{@value #NEAR_MAX} ブロック(自分の側)、遠ければ標的の方へ {@code min}〜{@code max} ブロック。
     */
    @Nullable
    public static Vec3 pickNear(DevelopedParasitized mob, Vec3 target, double min, double max) {
        RandomSource r = mob.getRandom();
        double dx = target.x - mob.getX(), dz = target.z - mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0E-3D) return null;
        double toward = Math.atan2(dz, dx);
        for (int i = 0; i < TRIES; i++) {
            double x, z;
            if (dist - NEAR_MAX <= max) {
                // 標的のそば(自分の側の半円)
                double a = toward + Math.PI + (r.nextDouble() - 0.5D) * Math.PI;
                double d = NEAR_MIN + r.nextDouble() * (NEAR_MAX - NEAR_MIN);
                x = target.x + Math.cos(a) * d;
                z = target.z + Math.sin(a) * d;
            } else {
                double a = toward + (r.nextDouble() - 0.5D) * 0.7D;
                double d = min + r.nextDouble() * (max - min);
                x = mob.getX() + Math.cos(a) * d;
                z = mob.getZ() + Math.sin(a) * d;
            }
            Vec3 at = standAt(mob, x, Math.max(mob.getY(), target.y), z);
            if (at != null) return at;
        }
        return null;
    }

    /**
     * どの向きでも {@code min}〜{@code max} ブロック先。{@code score} があれば、立てる候補を {@code samples} 個まで集めて点の高い所を選ぶ
     * (足元の地面のマスで採点。汚染の濃い方・汚染していない方など)。無ければ最初に立てた所。
     */
    @Nullable
    public static Vec3 pickAround(DevelopedParasitized mob, double min, double max, @Nullable ToDoubleFunction<BlockPos> score, int samples) {
        RandomSource r = mob.getRandom();
        Vec3 best = null;
        double bestScore = -Double.MAX_VALUE;
        int found = 0;
        for (int i = 0; i < TRIES * 2 && found < samples; i++) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            double d = min + r.nextDouble() * (max - min);
            Vec3 at = standAt(mob, mob.getX() + Math.cos(a) * d, mob.getY(), mob.getZ() + Math.sin(a) * d);
            if (at == null) continue;
            if (score == null) return at;
            found++;
            double s = score.applyAsDouble(BlockPos.containing(at.x, at.y - 0.5D, at.z));
            if (s > bestScore) {
                bestScore = s;
                best = at;
            }
        }
        return best;
    }

    /** 足元の地面のまわり 5 × 5(上下 1)にある汚染したマスの数(「汚染の濃い方」の採点)。 */
    public static int corruptionAround(ServerLevel level, BlockPos ground) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    p.set(ground.getX() + dx, ground.getY() + dy, ground.getZ() + dz);
                    if (!level.isLoaded(p)) continue;
                    if (OvergrownLayerData.isCorruptedCell(level, p, level.getBlockState(p))) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** 渡る(サーバー)。出た所と着いた所に胞子が噴き、低い転移の音が鳴る。乗り物・乗り手は外す。 */
    public static void go(DevelopedParasitized mob, Vec3 to) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        Vec3 from = mob.position();
        mob.stopRiding();
        mob.ejectPassengers();
        mob.getNavigation().stop();
        mob.teleportTo(to.x, to.y, to.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0.0F;
        level.broadcastEntityEvent(mob, (byte) 46); // バニラの転移の紫の粒(出た所から着いた所へ)
        level.gameEvent(GameEvent.TELEPORT, from, GameEvent.Context.of(mob));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.6F);
        level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.6F);
        puff(level, mob, from);
        puff(level, mob, to);
        mob.countTeleport(from, to);
    }

    /** 連れて行かれる相手を {@code to} のそばへ移す(立てる所が無ければ同じ所)。 */
    public static void bring(DevelopedParasitized mob, Entity victim, Vec3 to, float facingYaw) {
        Vec3 face = Vec3.directionFromRotation(0.0F, facingYaw);
        double x = to.x + face.x * 1.2D, z = to.z + face.z * 1.2D;
        float w = victim.getBbWidth() * 0.5F;
        AABB box = new AABB(x - w, to.y, z - w, x + w, to.y + victim.getBbHeight(), z + w);
        if (!mob.level().noCollision(victim, box) || mob.level().containsAnyLiquid(box)) {
            x = to.x;
            z = to.z;
        }
        victim.stopRiding();
        victim.teleportTo(x, to.y, z);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.fallDistance = 0.0F;
        victim.hurtMarked = true;
    }

    private static void puff(ServerLevel level, DevelopedParasitized mob, Vec3 at) {
        G1SporeBlast.blast(level, mob, at, 0, 0.0D, 0.0D, PUFF_DOSE_CENTER, PUFF_DOSE_EDGE, PUFF_RADIUS, 0.0F, 0.0D);
    }
}
