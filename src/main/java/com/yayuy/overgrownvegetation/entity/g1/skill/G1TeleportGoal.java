package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * エンダーマンの 1 段目の (b)「転移(植物の都合)」(2026-11-13、能力の第 4 回。仕様 Vault「G1 エンダーマン(1 段目)」3 節【決定 11-09: 戻す・溜めを入れる】)。
 * 元の転移が戻るが、<b>溜め {@value #WINDUP} tick が要る</b>(体の輪郭がちらつき紫の粒がにじむ・後頭部の袋が縮む。溜めの間は矢も当たる)。
 * 溜めが終わると {@link G1Blink} で {@value G1Blink#MIN}〜{@value G1Blink#MAX} ブロック先へ渡り、着いた所で崩れて立て直す(teleport_in 5 tick)。
 *
 * <p>きっかけ(どれか 1 つ。見る順):
 * <ol>
 *   <li><b>目が合った</b>(目を合わせる版 {@code gaze = true} だけ。段は gaze 10 → 溜め → teleport_in): プレイヤーと目が合うと首がこちらを向き、
 *   溜めの後そのプレイヤーのそばへ渡る。<b>1 回だけ</b>(標的を見失うまで繰り返さない。{@link DevelopedParasitized#takeGazer})【決定 11-09】。</li>
 *   <li><b>土を抱えている</b>: 抱えて {@value #CARRY_BEFORE_PLANT} tick 経ったら渡り、着いた所に置く({@link DevelopedParasitized#plantSoil})。
 *   行き先は汚染していない地面を好む(前線を数ブロック押す)。</li>
 *   <li><b>被弾した</b>: 標的が {@value #HURT_NEAR} ブロックより遠ければそのそばへ、近ければどこかへ渡り直す【仮】。</li>
 *   <li><b>標的が {@value #FAR_RANGE} ブロックより遠い</b>: 標的の方へ(届くならそばへ)。</li>
 * </ol>
 * 使ったら {@value #COOLDOWN} tick 休む(目を合わせる版・掴みの転移と共通 = {@link DevelopedParasitized#teleportReady})。雨・水では渡らない【決定 11-09】。
 */
public class G1TeleportGoal extends G1TechniqueGoal {

    public static final int WINDUP = 15;
    public static final int GAZE = 10;
    public static final int ARRIVE = 5;
    public static final int COOLDOWN = 120;
    public static final double FAR_RANGE = 12.0D;
    static final double HURT_NEAR = 6.0D;
    public static final int CARRY_BEFORE_PLANT = 100;
    /** 土を置きに渡れなかったとき、次に試すまで(tick)。 */
    static final int PLANT_RETRY = 300;

    private enum Why { GAZE, PLANT, HURT, FAR }

    private final boolean gaze;
    @Nullable
    private Why why;
    private boolean moved;

    public G1TeleportGoal(DevelopedParasitized mob, boolean gaze) {
        super(mob, gaze ? new G1Move[]{G1Move.GAZE, G1Move.TELEPORT_WINDUP, G1Move.TELEPORT_IN} : new G1Move[]{G1Move.TELEPORT_WINDUP, G1Move.TELEPORT_IN},
                gaze ? new int[]{GAZE, WINDUP, ARRIVE} : new int[]{WINDUP, ARRIVE});
        this.gaze = gaze;
    }

    @Override
    protected boolean ready() {
        if (!mob.teleportReady()) return false;
        if (gaze) {
            if (mob.gazer() == null) return false;
            why = Why.GAZE;
            return true;
        }
        if (mob.carried() != null && mob.carriedTicks() >= CARRY_BEFORE_PLANT && !com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            why = Why.PLANT;
        } else if (mob.peekTeleportTrigger()) {
            why = Why.HURT;
        } else if (target != null && target.isAlive() && mob.canAttack(target) && mob.distanceTo(target) > mob.farRange()) {
            why = Why.FAR;
        } else {
            return false;
        }
        return true;
    }

    @Override
    protected int aimUntil() {
        return gaze ? GAZE + WINDUP : WINDUP;
    }

    @Override
    protected int cooldown() {
        return 0; // 休みは mob が持つ(目を合わせる版・掴みと共通)
    }

    @Override
    public void start() {
        super.start();
        moved = false;
        mob.takeTeleportTrigger();
        if (why == Why.GAZE) {
            LivingEntity gazer = mob.takeGazer();
            if (gazer != null) {
                target = gazer;
                if (mob.canAttack(gazer)) mob.setTarget(gazer);
            }
        }
        mob.markTeleportUsed(COOLDOWN + (gaze ? GAZE : 0) + WINDUP);
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        int windupStage = gaze ? 1 : 0;
        if (gaze && stageIndex == 0 && tickInStage == 1) {
            // 目が合った: 首ごと頭がこちらを向く
            level.playSound(null, mob.getX(), mob.getEyeY(), mob.getZ(), SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 0.8F, 0.6F);
        }
        if (stageIndex == windupStage) {
            if (tickInStage == 1) {
                level.playSound(null, mob.getX(), mob.getY() + 1.5D, mob.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.5F, 0.5F);
            }
            // 体からにじむ紫の粒(光らない。溜めの間だけ)
            level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5D, mob.getZ(), 5, 0.25D, 0.9D, 0.25D, 0.4D);
            if (tickInStage == WINDUP) {
                Vec3 to = destination(level);
                if (to != null) {
                    G1Blink.go(mob, to);
                    moved = true;
                } else if (why == Why.PLANT) {
                    mob.delayPlant(PLANT_RETRY); // 行き先が無い(狭い所など): しばらく待ってからまた試す
                }
            }
        } else if (stageIndex == windupStage + 1 && tickInStage == 3 && moved && why == Why.PLANT) {
            mob.plantSoil();
        }
    }

    @Nullable
    private Vec3 destination(ServerLevel level) {
        double min = mob.teleportMin(), max = mob.teleportMax();
        Why w = why == null ? Why.HURT : why;
        if (w == Why.PLANT) {
            // 汚染していない地面を好む(汚染の穴を埋める・前線を押す)
            return G1Blink.pickAround(mob, min, max, ground -> OvergrownLayerData.isCorruptedCell(level, ground, level.getBlockState(ground)) ? 0.0D : 1.0D, 6);
        }
        boolean hasTarget = target != null && target.isAlive();
        if (hasTarget && (w == Why.GAZE || w == Why.FAR || mob.distanceTo(target) > HURT_NEAR)) {
            Vec3 near = G1Blink.pickNear(mob, target.position(), min, max);
            if (near != null) return near;
        }
        return G1Blink.pickAround(mob, min, max, null, 1);
    }

    /** この版が目を合わせる版か。 */
    public boolean gaze() {
        return gaze;
    }

    static boolean corruptedGround(ServerLevel level, BlockPos ground) {
        return OvergrownLayerData.isCorruptedCell(level, ground, level.getBlockState(ground));
    }
}
