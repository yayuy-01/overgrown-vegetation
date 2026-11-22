package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * チビゾンビの 1 段目の (c)「頭の袋が胞子を噴く」(2026-11-12。仕様 Vault「G1 チビゾンビ(1 段目)」3 節【決定 11-09 ユーザー「良いです」、
 * 煙の効果は「目くらまし + 暴露量」】)。
 *
 * <ul>
 *   <li>きっかけ: 叩かれたとき(生き物に殴られた・撃たれた)、または取り付いて {@value #LATCH_TRIGGER} tick(1.5 秒)たったとき。
 *   取り付いたままでも噴く(取り付かれたまま煙に包まれると危ない)。</li>
 *   <li>段: spore_puff_windup 16(袋が大きく脈打って膨らみ、口のように開く。シュッという音)→ spore_puff 10(噴く。1 tick 目に煙)。</li>
 *   <li>煙({@link G1SporeCloud}): 半径 {@value #RADIUS}・{@value #CLOUD_TICKS} tick(3 秒)・中にいる間 暴露量 +{@value #DOSE_PER_SECOND} / 秒・
 *   視界が緑に霞む(出ればすぐ戻る)。防護マスクで暴露量は防げる。</li>
 *   <li>冷却 {@value #COOLDOWN} tick(6 秒)。</li>
 * </ul>
 * 数値はすべて【仮】(このクラスの定数)。
 */
public class G1SporePuffGoal extends G1TechniqueGoal {

    static final int LATCH_TRIGGER = 30;
    static final float RADIUS = 2.0F;
    static final int CLOUD_TICKS = 60;
    static final double DOSE_PER_SECOND = 100.0D;
    static final int COOLDOWN = 120;

    public G1SporePuffGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.SPORE_PUFF_WINDUP, G1Move.SPORE_PUFF}, new int[]{G1SkillTiming.SPORE_PUFF_WINDUP, 10});
    }

    @Override
    protected boolean ready() {
        G1Latch latch = mob.latch();
        return mob.takePuffTrigger() || latch != null && latch.ticks() >= LATCH_TRIGGER;
    }

    @Override
    protected boolean canStartFrom(int action) {
        return action == AbstractParasitizedMonster.ACTION_NONE || action == G1Move.LATCH.id() || action == G1Move.SQUEEZE_UNDER.id();
    }

    @Override
    protected boolean lockFacing() {
        return false;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (stageIndex == 0 && tickInStage == 1) {
            mob.level().playSound(null, mob.getX(), mob.getY() + 0.8D, mob.getZ(), SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.HOSTILE, 0.8F, 0.7F);
        }
        if (stageIndex == 1 && tickInStage == 1 && mob.level() instanceof ServerLevel level) {
            G1SporeCloud.spawn(level, mob.position(), RADIUS, CLOUD_TICKS, DOSE_PER_SECOND, -1, 0, true, mob);
        }
    }
}
