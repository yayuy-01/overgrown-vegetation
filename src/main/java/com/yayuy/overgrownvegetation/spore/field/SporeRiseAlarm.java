package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.defense.SporeAlarms;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 胞子警報器は濃度の「上がり方」で鳴る(2026-10-23 胞子の場と風 第 2 段 H。仕様は Vault「胞子濃度と暴露量(作り直し)」の【案】
 * 「警報器は上がり方で鳴る(嵐の前に鳴る)」)。10 秒ごとに読み込まれている警報器の所の濃度を測り、1 分前より
 * {@code alarm.risePerMinute} 以上上がっていれば({@code alarm.minPercent} 以上のときだけ)鳴らす。続けて鳴らすのは間を空ける。
 * フェーズ移行・レイドの警報は今までどおり。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeRiseAlarm {

    private static final int INTERVAL = 200;
    private static final int SAMPLES = 6; // 1 分ぶん

    private static final class History {
        final double[] values = new double[SAMPLES];
        int filled, next;
        long quietUntil;
    }

    private static final Long2ObjectOpenHashMap<History> HISTORY = new Long2ObjectOpenHashMap<>();

    private SporeRiseAlarm() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % INTERVAL != 0 || !SporeConcentration.useField(level)) {
            return;
        }
        SporeFieldTable t = DesignData.sporeField();
        long now = level.getGameTime();
        for (BlockPos pos : SporeAlarms.alarms(level)) {
            if (!level.isLoaded(pos)) continue;
            double c = SporeConcentration.percentAt(level, pos.above());
            History h = HISTORY.computeIfAbsent(pos.asLong(), k -> new History());
            double oldest = h.filled >= SAMPLES ? h.values[h.next] : Double.NaN;
            h.values[h.next] = c;
            h.next = (h.next + 1) % SAMPLES;
            h.filled = Math.min(SAMPLES, h.filled + 1);
            if (Double.isNaN(oldest) || now < h.quietUntil) continue;
            double rise = c - oldest;
            if (rise >= t.alarmRise() && c >= t.alarmMin()) {
                h.quietUntil = now + t.alarmCooldown();
                SporeAlarms.warn(level, Component.translatable("message.overgrown_vegetation.spore_alarm.rising",
                        String.format("%.0f", Math.min(999, c)), String.format("%.0f", rise)), Vec3.atCenterOf(pos));
            }
        }
        if (HISTORY.size() > 4096) HISTORY.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HISTORY.clear();
    }
}
