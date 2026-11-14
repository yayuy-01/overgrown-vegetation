package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.EnumSet;

/**
 * 村人の 1 段目の (b) の夜「眠って嚢を開く」(2026-11-13 ユーザー決定「1 と 2 の組み合わせ」。仕様 Vault「G1 村人(1 段目)」3 節 (b)、数は【仮】)。
 * 元の村人の「夜は眠る」を植物が使う: 眠っている体の背の嚢を開いて、周りの空気へ胞子を出す。
 *
 * <ul>
 *   <li>眠る: 夜・{@value #SLEEP_RADIUS} ブロック以内に獲物がいない → その場にうずくまる(動かない)。G1 は 35 ブロック以内の生き物を
 *   必ず標的にするので、遠くに標的がいても眠る(3 段の 1 撃より優先度を高くしてある。眠っている間は追わない)。</li>
 *   <li>眠っている間: 周り半径 {@value #AIR_RADIUS} ブロックにかかる升(チャンク)の浮遊を毎秒 +{@value #AIR_PERCENT_PER_SECOND} ポイント、
 *   {@value #AIR_CAP_PERCENT}% まで。開いた嚢への攻撃は 2 倍(どこに当たったかは分けないので、眠っている間の被弾をすべて 2 倍。
 *   {@link DevelopedParasitized#hurt})。</li>
 *   <li>目を覚ます: 殴られた(その後 {@value #RESLEEP_AFTER_HURT} tick は眠り直さない)・獲物が {@value #WAKE_RADIUS} ブロック以内に来た・朝になった。</li>
 *   <li>アニメ: sleep_windup {@value #WINDUP} tick(座り込み → 嚢がゆっくり開く)→ sleep(4 秒の繰り返し)→ 起きるとき sleep_recover {@value #RECOVER} tick
 *   (その間は動かない = 起き抜けの隙)。浮遊を上げるのは嚢が開ききってから。被弾 2 倍は座り込みの間も({@link G1Move#SLEEP})。</li>
 * </ul>
 * 「獲物」= 植物側以外の Mob と、創造・観戦でないプレイヤー。眠りに入る距離(8)と目を覚ます距離(4)を分けたのは、境目で寝起きを繰り返さないため。
 */
public class G1SleepGoal extends Goal {

    static final double SLEEP_RADIUS = 8.0D;
    static final double WAKE_RADIUS = 4.0D;
    static final int AIR_RADIUS = 6;
    static final double AIR_PERCENT_PER_SECOND = 1.0D;
    static final double AIR_CAP_PERCENT = 60.0D;
    static final int RESLEEP_AFTER_HURT = 200;
    /** アニメ sleep_windup(座り込み → 嚢がゆっくり開く、2 秒)と sleep_recover(0.8 秒)の長さ(tick)。 */
    static final int WINDUP = 40;
    static final int RECOVER = 16;
    /** 眠りに入ってこの tick より前に起こされたら、起き上がりを流さずに立ち姿へ戻す(まだ座り込んでいないので)。【仮】 */
    static final int RECOVER_FROM = 12;
    /** 嚢の開く音を鳴らす tick(sleep_windup で蓋が動き出す 1.0 秒)。 */
    static final int SAC_OPEN_AT = 20;

    private final DevelopedParasitized mob;
    private int t;
    private int hurtAtStart;
    private long notBefore;

    public G1SleepGoal(DevelopedParasitized mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    /** {@code radius} ブロック以内に獲物(植物側以外の Mob・創造でないプレイヤー)がいるか。 */
    public static boolean preyNear(DevelopedParasitized mob, double radius) {
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(radius))) {
            if (e == mob || !e.isAlive() || mob.distanceTo(e) > radius) continue;
            if (e instanceof Player p ? !p.isCreative() && !p.isSpectator() : SporeExposureTracker.isMeasuredMob(e)) return true;
        }
        return false;
    }

    @Override
    public boolean canUse() {
        return !mob.isDayFor() && mob.action() == AbstractParasitizedMonster.ACTION_NONE && !mob.isStunned() && !mob.isPassenger()
                && mob.level().getGameTime() >= notBefore && !preyNear(mob, SLEEP_RADIUS);
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.isDayFor() && mob.hurtCount() == hurtAtStart && (t % 5 != 0 || !preyNear(mob, WAKE_RADIUS));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        t = 0;
        hurtAtStart = mob.hurtCount();
        mob.getNavigation().stop();
        mob.setAction(G1Move.SLEEP_WINDUP.id());
    }

    @Override
    public void stop() {
        if (mob.hurtCount() != hurtAtStart) {
            notBefore = mob.level().getGameTime() + RESLEEP_AFTER_HURT;
        }
        if (t >= RECOVER_FROM) {
            mob.holdAction(G1Move.SLEEP_RECOVER, RECOVER);
        } else {
            mob.setAction(AbstractParasitizedMonster.ACTION_NONE);
        }
        t = 0;
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
        t++;
        if (t == SAC_OPEN_AT) {
            // 嚢が開く音(1.3 秒)。sleep_windup の 1.0 秒で蓋が動き出し、1.2〜1.8 秒で横と上が開ききる。音量【仮】
            mob.playSound(ModSounds.G1_SAC_OPEN.get(), 0.6F, 1.0F);
        }
        if (t == WINDUP + 1) {
            mob.setAction(G1Move.SLEEP.id());
        }
        if (t > WINDUP && t % 20 == 0 && mob.level() instanceof ServerLevel level) {
            breathe(level);
        }
    }

    /** 周りの升の浮遊を 1 秒ぶん上げる。 */
    private void breathe(ServerLevel level) {
        double fullAt = DesignData.sporeField().fullAt();
        BlockPos c = mob.blockPosition();
        LongOpenHashSet done = new LongOpenHashSet();
        for (int dx = -AIR_RADIUS; dx <= AIR_RADIUS; dx += AIR_RADIUS) {
            for (int dz = -AIR_RADIUS; dz <= AIR_RADIUS; dz += AIR_RADIUS) {
                BlockPos p = c.offset(dx, 0, dz);
                if (!done.add(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4))) continue;
                double air = SporeFieldServer.airAt(level, p.getX() >> 4, p.getZ() >> 4);
                if (Double.isNaN(air) || air / fullAt * 100.0D < AIR_CAP_PERCENT) {
                    SporeFieldServer.emitPoint(level, p, AIR_PERCENT_PER_SECOND / 100.0D * fullAt);
                }
            }
        }
        level.sendParticles(ModParticles.SPORE_JET.get(), mob.getX(), mob.getY() + 1.2D, mob.getZ(), 6, 0.3D, 0.3D, 0.3D, 0.02D);
    }
}
