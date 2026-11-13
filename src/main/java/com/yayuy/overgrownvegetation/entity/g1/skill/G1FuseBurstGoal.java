package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * クリーパーの 1 段目の (b)「胞子の爆ぜ」(2026-11-13、能力の第 3 回。仕様 Vault「G1 クリーパー(1 段目)」3 節【決定 11-09・数は仮】)。
 * 相手に近づくと導火の音が戻り、低く構えて背の莢が脈打って膨らみ(体は膨らまない・白い点滅なし)、莢の房が一斉に割れて胞子を撒く。
 * <b>ブロックは壊さない・自分は死なない</b>。爆ぜた後は莢が空になり(育ち直すまで使えない。{@link DevelopedParasitized#burstPods})、その間は蔦で戦う。
 *
 * <ul>
 *   <li>使う条件: 莢が満ちている・解禁されている(交戦 6 秒 + 薙ぎ払い 1 回の後【仮】。{@link DevelopedParasitized#fuseUnlocked})・
 *   標的が {@value #START_RANGE} ブロック以内で見えている。</li>
 *   <li>段: fuse {@value #FUSE_TICKS}(導火。標的が {@value #STOP_RANGE} ブロックより離れる・死ぬと導火が止まる = バニラと同じ対処)→ burst 10
 *   (2 tick 目に爆ぜる)。導火の途中で倒されたら爆ぜは起きない(death_burst だけ)。</li>
 *   <li>爆ぜ({@link G1SporeBlast}): 周り半径 {@value #CORRUPT_RADIUS} の地面を汚染ブロックで覆う・升の浮遊 +{@value #AIR_PERCENT}%・
 *   半径 {@value #DOSE_RADIUS} の相手へ暴露量 中心 +{@value #DOSE_CENTER} → 縁 +{@value #DOSE_EDGE}・ダメージ {@value #DAMAGE}(範囲の中どこでも【決定】)・
 *   押し返し {@value #KNOCKBACK}。</li>
 *   <li>導火が止まったら {@value #RETRY_COOLDOWN} tick 後にまた点けられる(莢は満ちたまま)。</li>
 * </ul>
 */
public class G1FuseBurstGoal extends G1TechniqueGoal {

    static final double START_RANGE = 3.0D;
    static final double STOP_RANGE = 5.0D;
    public static final int FUSE_TICKS = 40;
    public static final int CORRUPT_RADIUS = 3;
    static final double CORRUPT_NOISE = 1.0D;
    /** 升の浮遊の上げ幅(%)。仕様の「+4.5(0.5 × 半径²)」は 11-12 の「G1 だけ小さく」より前の数なので使わず、破裂の大きい体(+40%)と同じに。【仮】 */
    static final double AIR_PERCENT = 40.0D;
    public static final double DOSE_CENTER = 600.0D;
    public static final double DOSE_EDGE = 200.0D;
    public static final double DOSE_RADIUS = 4.0D;
    public static final float DAMAGE = 4.0F;
    static final double KNOCKBACK = 0.6D;
    static final int RETRY_COOLDOWN = 40;
    /** 導火の間、莢が脈打つ tick(アニメ fuse の膨らみの山)。 */
    private static final int[] PULSES = {9, 19, 26, 32, 37};

    private boolean fusing;

    public G1FuseBurstGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.FUSE, G1Move.BURST}, new int[]{FUSE_TICKS, 10});
    }

    @Override
    protected boolean ready() {
        return mob.podsFull() && mob.fuseUnlocked() && target != null && target.isAlive() && mob.canAttack(target)
                && mob.distanceTo(target) <= START_RANGE && mob.getSensing().hasLineOfSight(target);
    }

    @Override
    public void start() {
        fusing = true;
        super.start();
    }

    /** 導火の間に標的が離れた・いなくなったら止まる(爆ぜの段に入ったら止まらない)。 */
    @Override
    protected boolean interrupted() {
        return fusing && (target == null || !target.isAlive() || mob.distanceTo(target) > STOP_RANGE);
    }

    @Override
    protected int aimUntil() {
        return FUSE_TICKS;
    }

    @Override
    protected int cooldown() {
        return RETRY_COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (stageIndex == 0) {
            if (tickInStage == 1) {
                // 導火の音が戻る【決定 11-09】
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0F, 0.5F);
            }
            for (int p : PULSES) {
                if (tickInStage == p) {
                    level.playSound(null, mob.getX(), mob.getY() + 1.2D, mob.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.HOSTILE,
                            0.6F, 0.6F + 0.4F * p / FUSE_TICKS);
                }
            }
            if (tickInStage == FUSE_TICKS) {
                fusing = false; // ここから先は止まらない
            }
        } else if (tickInStage == 2) {
            G1SporeBlast.blast(level, mob, mob.position(), CORRUPT_RADIUS, CORRUPT_NOISE, AIR_PERCENT, DOSE_CENTER, DOSE_EDGE, DOSE_RADIUS,
                    DAMAGE, KNOCKBACK);
            level.playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.SLIME_BLOCK_BREAK, SoundSource.HOSTILE, 1.3F, 0.6F);
            level.playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.MUD_BREAK, SoundSource.HOSTILE, 1.1F, 0.6F);
            level.playSound(null, mob.getX(), mob.getY() + 1.0D, mob.getZ(), SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, 0.9F, 0.7F);
            mob.burstPods();
        }
    }
}
