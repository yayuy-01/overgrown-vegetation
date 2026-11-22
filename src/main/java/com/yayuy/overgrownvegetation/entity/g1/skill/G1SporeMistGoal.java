package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 村人の 1 段目の (c)「背の瘤の胞子の吹き出し」(2026-11-12。仕様 Vault「G1 村人(1 段目)」3 節【決定 11-09、霧の効果は答え A「イ」= 暴露量 + 鈍足 I】)。
 *
 * <ul>
 *   <li>きっかけ: 近く({@value #PREY_RADIUS} ブロック)に獲物が 2 体以上いるとき、または殴られて体力が半分を切ったとき(1 回)。</li>
 *   <li>段: spore_windup 20(瘤がぎゅっと膨らみ、巻きシダが逆立つ)→ spore 4(一気に縮んで霧。1 tick 目)→ spore_recover 12。</li>
 *   <li>霧({@link G1SporeCloud}): 足元に半径 {@value #RADIUS}・{@value #CLOUD_TICKS} tick(3 秒)・中にいる間 暴露量 +{@value #DOSE_PER_SECOND} / 秒
 *   (3 秒で 1 撃ぶんの +300)・鈍足 I(中にいる間 + 出てから {@value #SLOW_LINGER} tick)。地形は壊さない。</li>
 *   <li>間隔 {@value #COOLDOWN} tick(30 秒に 1 回)。</li>
 * </ul>
 * 「獲物」= 植物側以外の生き物で、暴露量を測る相手(創造・観戦のプレイヤーは数えない)。数値はすべて【仮】(このクラスの定数)。
 */
public class G1SporeMistGoal extends G1TechniqueGoal {

    static final double PREY_RADIUS = 4.0D;
    static final int PREY_COUNT = 2;
    static final float RADIUS = 2.5F;
    static final int CLOUD_TICKS = 60;
    static final double DOSE_PER_SECOND = 100.0D;
    static final int SLOW_LINGER = 40;
    static final int COOLDOWN = 600;

    private long scanAt;
    private boolean enough;

    public G1SporeMistGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SPORE_WINDUP, G1Move.SPORE, G1Move.SPORE_RECOVER}, new int[]{20, 4, 12});
    }

    @Override
    protected boolean ready() {
        if (mob.takeMistTrigger()) return true;
        // 数え直すのは 10 tick に 1 回(間は前の答えを使う。殴り合いの合間の短い窓でも答えられるように)
        long now = mob.level().getGameTime();
        if (now < scanAt) return enough;
        scanAt = now + 10;
        int n = 0;
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(PREY_RADIUS))) {
            if (e != mob && e.isAlive() && !SporeExposureTracker.isPlantSide(e) && G1SporeDose.canDose(e)
                    && (e instanceof Player || SporeExposureTracker.isMeasuredMob(e)) && mob.distanceTo(e) <= PREY_RADIUS) {
                n++;
            }
        }
        enough = n >= PREY_COUNT;
        return enough;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 0 && tickInStage == 1) {
            mob.level().playSound(null, mob.getX(), mob.getY() + 1.4D, mob.getZ(), SoundEvents.SNIFFER_SNIFFING, SoundSource.HOSTILE, 0.9F, 0.5F);
        }
        if (stageIndex == 1 && tickInStage == 1 && mob.level() instanceof ServerLevel level) {
            G1SporeCloud.spawn(level, mob.position(), RADIUS, CLOUD_TICKS, DOSE_PER_SECOND, 0, SLOW_LINGER, false, mob);
        }
    }
}
