package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.EnumSet;

/**
 * 前兆の間、家畜が地脈の線から離れる(2026-10-24、地脈技術の 4 段目)。線の帯(幅 5)の縁から {@code omen.animalAvoidExtra}(6)
 * ブロック以内 = 線の真ん中から 2.5 + 6 = 8.5 ブロック以内にいる {@link Animal} が、線から遠ざかる向きへふつうの速さで歩く。
 * 優先度は低い(逃げる・繁殖・誘われる・親について行くより後、ぶらつきと同じ 5)。前兆でなければ何もしない。
 * 遠ざかる向きは、8 方向の 4 ブロック先で線までの距離がいちばん大きくなる向き(重い {@code nearestLinePoint} は使わない)。
 */
public class LeyOmenAvoidVeinGoal extends Goal {

    private static final int PRIORITY = 5;
    private final Animal animal;
    private int cooldown;

    public LeyOmenAvoidVeinGoal(Animal animal) {
        this.animal = animal;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** 線の真ん中からこの距離以内なら離れる。 */
    public static double avoidDistance() {
        return DesignData.climax().leyVein().width() * 0.5D + DesignData.phases().omen().animalAvoidExtra();
    }

    @Override
    public boolean canUse() {
        if (!LeyOmen.isActive() || !(animal.level() instanceof ServerLevel level)) {
            return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        cooldown = reducedTickDelay(40 + animal.getRandom().nextInt(40));
        if (animal.isLeashed() || animal.isVehicle() || animal.isPassenger()) {
            return false;
        }
        Vec3 away = awayFromLine(level.getSeed(), animal.getBlockX(), animal.getBlockZ());
        if (away == null) {
            return false;
        }
        Vec3 goal = animal.position().add(away.scale(8.0D));
        Vec3 pos = DefaultRandomPos.getPosTowards(animal, 10, 5, goal, Math.PI / 2.0D);
        if (pos == null) {
            return false;
        }
        return animal.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.0D);
    }

    /** 線に近ければ、線から遠ざかる水平の向き(長さ 1)。遠いか向きが出なければ null。 */
    public static Vec3 awayFromLine(long seed, int x, int z) {
        double d = LeyVeinField.distanceToLine(seed, x, z);
        if (d == Double.MAX_VALUE || d > avoidDistance()) {
            return null;
        }
        // 8 方向に 4 ブロック先を測り、線からいちばん遠くなる向き(線の交わる所でも傾きより確か)
        Vec3 best = null;
        double bestD = d;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0D;
            double cx = Math.cos(a), cz = Math.sin(a);
            double di = LeyVeinField.distanceToLine(seed, x + (int) Math.round(cx * STEP), z + (int) Math.round(cz * STEP));
            if (di > bestD) {
                bestD = di;
                best = new Vec3(cx, 0.0D, cz);
            }
        }
        return best;
    }

    /** 離れる向きを測る距離(ブロック)。 */
    public static final int STEP = 4;

    @Override
    public boolean canContinueToUse() {
        return LeyOmen.isActive() && !animal.getNavigation().isDone();
    }

    @Override
    public void stop() {
        animal.getNavigation().stop();
    }

    /** 全ての家畜に仕込む(前兆でない間は {@link #canUse} がすぐ false を返すだけ)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Injector {
        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof Animal animal) {
                animal.goalSelector.addGoal(PRIORITY, new LeyOmenAvoidVeinGoal(animal));
            }
        }

        private Injector() {
        }
    }
}
