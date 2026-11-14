package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.block.CorruptedEggBlock;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * 鶏の 1 段目の (b)「汚染された卵を産む」(2026-11-13、能力の第 2 回。仕様 Vault「G1 鶏(1 段目)」3 節【決定 11-09】。卵の効果は後で決める)。
 * 元の鶏の「卵を産む」を植物が使う: 数分おきにうずくまって、足元に汚染された卵({@link CorruptedEggBlock})を産む。
 *
 * <ul>
 *   <li>産む間隔: {@value #INTERVAL_MIN}〜{@value #INTERVAL_MAX} tick(3〜5 分【仮】。時計は Mob が持ち、保存する)。</li>
 *   <li>産む条件: {@value #CALM_RADIUS} ブロック以内に獲物がいない(殴り合いの最中は産まない。G1 は 35 ブロック以内の生き物を必ず標的にするので、
 *   「標的がいない」ではなく「近くにいない」で見る)・地面の上・足元に置ける(空いた升で下が固い、または卵が 4 個未満)。</li>
 *   <li>数の上限: まわり {@value #LIMIT_RADIUS} ブロック(上下 {@value #LIMIT_HEIGHT})に卵が {@value #LIMIT} 個あれば産まない(仕様「1 羽が周り 16 ブロックに 6 個まで」
 *   【仮】。誰が産んだかは分けず、まわりの卵の数で見る)。上限のときは {@value #RETRY} tick 後に確かめ直す。</li>
 *   <li>段: lay_windup 40(うずくまり、尻が震えてそ嚢が脈打つ = 産む前に倒す好機)→ lay 10(1 tick 目に産む)→ lay_recover 20。</li>
 * </ul>
 */
public class G1LayEggGoal extends G1TechniqueGoal {

    public static final int INTERVAL_MIN = 3600;
    public static final int INTERVAL_MAX = 6000;
    static final double CALM_RADIUS = 6.0D;
    public static final int LIMIT = 6;
    public static final int LIMIT_RADIUS = 16;
    static final int LIMIT_HEIGHT = 4;
    static final int RETRY = 600;

    public G1LayEggGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.LAY_WINDUP, G1Move.LAY, G1Move.LAY_RECOVER}, new int[]{40, 10, 20});
    }

    @Override
    protected boolean ready() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        if (level.getGameTime() < mob.layReadyAt() || !mob.onGround() || !mob.calm(CALM_RADIUS)) return false;
        BlockPos at = mob.blockPosition();
        if (!CorruptedEggBlock.canLayAt(level, at)) return false;
        if (CorruptedEggBlock.countAround(level, at, LIMIT_RADIUS, LIMIT_HEIGHT) >= LIMIT) {
            mob.setLayReadyAt(level.getGameTime() + RETRY); // まわりの卵の数えは重いので、しばらく確かめ直さない
            return false;
        }
        return true;
    }

    /** 向きは追わない(標的に向かう技ではない)。 */
    @Override
    protected int aimUntil() {
        return 0;
    }

    @Override
    protected int cooldown() {
        return 0;
    }

    @Override
    public void stop() {
        super.stop();
        mob.scheduleNextLay();
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0 && tickInStage % 12 == 6) {
            level.playSound(null, mob.getX(), mob.getY() + 0.3D, mob.getZ(), ModSounds.G1_SAC_SWELL.get(), SoundSource.HOSTILE, 0.5F, 0.8F);
        }
        if (stageIndex == 1 && tickInStage == 1) {
            if (CorruptedEggBlock.layAt(level, mob.blockPosition(), ModG1.CORRUPTED_EGG.get())) {
                mob.countEggLaid();
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TURTLE_LAY_EGG, SoundSource.HOSTILE, 0.8F, 0.6F);
            }
        }
    }
}
