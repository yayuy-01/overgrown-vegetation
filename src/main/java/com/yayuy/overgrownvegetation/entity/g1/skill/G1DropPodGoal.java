package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * クリーパーの 1 段目の (c)「莢を落とす」(2026-11-13、能力の第 3 回。仕様 Vault「G1 クリーパー(1 段目)」3 節【決定 11-09・数は仮】)。
 * 体を揺すって、背の熟した莢を 1 つ後ろへ落とす({@link G1SeedPod}: 10 秒で膨らんで割れ、小さく撒く。叩けば割れずに消える)。
 *
 * <ul>
 *   <li>使う条件: 莢が満ちている(空の間は落とさない)・前に落としてから {@value #INTERVAL} tick(仕様「30 秒に 1 つ」)・
 *   標的が {@value #TARGET_RANGE} ブロック以内にいる【仮: 獲物のそばでだけ落とす。誰もいない所では落とさない】・
 *   まわり {@value #NEAR_RADIUS} ブロックの莢が {@value #NEAR_MAX} つ未満(仕様「近くに 3 つまで」)。</li>
 *   <li>段: drop_pod 12(6 tick 目に落とす)。向きは追わない。</li>
 * </ul>
 */
public class G1DropPodGoal extends G1TechniqueGoal {

    public static final int INTERVAL = 600;
    /** 湧いてから最初に落とすまで(tick の幅)。【仮】 */
    public static final int FIRST_MIN = 200, FIRST_MAX = 600;
    static final double TARGET_RANGE = 16.0D;
    static final double NEAR_RADIUS = 16.0D;
    public static final int NEAR_MAX = 3;

    public G1DropPodGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.DROP_POD}, new int[]{12});
    }

    @Override
    protected boolean ready() {
        if (!mob.podsFull() || !mob.onGround() || mob.podDropReadyAt() < 0L || mob.level().getGameTime() < mob.podDropReadyAt()) return false;
        if (target == null || !target.isAlive() || mob.distanceTo(target) > TARGET_RANGE) return false;
        return G1SeedPod.near(mob.level(), mob.position(), NEAR_RADIUS).size() < NEAR_MAX;
    }

    @Override
    protected int aimUntil() {
        return 0;
    }

    @Override
    protected int cooldown() {
        return 0;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (tickInStage == 6 && mob.level() instanceof ServerLevel level) {
            Vec3 back = Vec3.directionFromRotation(0.0F, lockedYaw).scale(-1.0D);
            Vec3 at = mob.position().add(back.x * 0.45D, mob.getBbHeight() * 0.6D, back.z * 0.45D);
            G1SeedPod.drop(level, at, new Vec3(back.x * 0.08D + (mob.getRandom().nextDouble() - 0.5D) * 0.04D, 0.05D,
                    back.z * 0.08D + (mob.getRandom().nextDouble() - 0.5D) * 0.04D));
            mob.setPodDropReadyAt(level.getGameTime() + INTERVAL);
        }
    }
}
