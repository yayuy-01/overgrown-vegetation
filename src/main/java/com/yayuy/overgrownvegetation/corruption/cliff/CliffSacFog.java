package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 崖の嚢が破裂した後に残る胞子の霧(2026-11-20。ユーザー決定「壊したら破裂し、まわりの胞子濃度と暴露量を上げる霧を出す」)。
 * 霧は {@code burst.durationTicks} の間、{@code burst.pulseTicks} ごとに:
 * <ul>
 *   <li>升の浮遊へ {@code airPercent} を脈の数で割った分を放ち、降り積もりへも {@code deposit} を割った分を足す(胞子の場は升 = チャンク単位)。</li>
 *   <li>半径 {@code radius} の中の植物側でない生き物に {@code dosePerPulse} の暴露(息で浴びる = 防護マスクで防げる)。</li>
 *   <li>胞子の煙の粒子。</li>
 * </ul>
 * <b>保存しない</b>(霧は 10 秒ほどで消える。ワールドを閉じると残りは消える)。量はどれも【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CliffSacFog {
    private CliffSacFog() {
    }

    /** 1 つの霧。left = 残りの tick。 */
    static final class Fog {
        final BlockPos pos;
        int left;
        int age;

        Fog(BlockPos pos, int left) {
            this.pos = pos;
            this.left = left;
        }
    }

    private static final Map<ServerLevel, List<Fog>> FOGS = new WeakHashMap<>();

    /** 破裂させた所に霧を 1 つ出す(破裂の音と最初の煙はその場で)。 */
    public static void start(ServerLevel level, BlockPos pos) {
        CliffTable.Burst b = DesignData.cliff().burst;
        level.playSound(null, pos, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F);
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 24,
                0.9D, 0.7D, 0.9D, 0.02D);
        Fog f = new Fog(pos.immutable(), Math.max(1, b.durationTicks));
        FOGS.computeIfAbsent(level, k -> new ArrayList<>()).add(f);
        pulse(level, f, b); // 割った瞬間にも 1 回
    }

    /** 今出ている霧の数(テスト・調べ用)。 */
    public static int active(ServerLevel level) {
        List<Fog> l = FOGS.get(level);
        return l == null ? 0 : l.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<Fog> list = FOGS.get(level);
        if (list == null || list.isEmpty()) return;
        CliffTable.Burst b = DesignData.cliff().burst;
        int every = Math.max(1, b.pulseTicks);
        for (Iterator<Fog> it = list.iterator(); it.hasNext(); ) {
            Fog f = it.next();
            f.age++;
            if (--f.left <= 0) {
                it.remove();
                continue;
            }
            if (f.age % every == 0) pulse(level, f, b);
        }
    }

    private static void pulse(ServerLevel level, Fog f, CliffTable.Burst b) {
        int pulses = Math.max(1, b.durationTicks / Math.max(1, b.pulseTicks) + 1);
        BlockPos p = f.pos;
        if (b.airPercent > 0) {
            SporeFieldServer.emitPoint(level, p, b.airPercent / 100.0D * DesignData.sporeField().fullAt() / pulses);
        }
        if (b.deposit > 0) SporeFieldServer.depositPoint(level, p, b.deposit / pulses);
        level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), p.getX() + 0.5D, p.getY() + 0.3D, p.getZ() + 0.5D, 6,
                b.radius * 0.35D, 0.6D, b.radius * 0.35D, 0.005D);
        double r = b.radius;
        AABB box = new AABB(p).inflate(r);
        double r2 = r * r;
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (PlantSideRules.isPlantSide(le)) continue;
            if (le.distanceToSqr(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D) > r2) continue;
            SporeExposureTracker.addExposure(le, b.dosePerPulse, true);
        }
    }
}
