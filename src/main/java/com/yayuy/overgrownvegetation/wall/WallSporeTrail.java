package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 蠢く壁が通った後は、しばらく胞子が濃い(決定 その 3)。通った区画(16×16×16)ごとに「濃さが切れる tick」を覚え、
 * {@code SporeConcentration#reading} が {@link #percentAt} を足す。濃さは {@link WallTuning#sporeTrailPercent} からだんだん 0 へ。
 * メモリの上だけ(再起動で消える)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WallSporeTrail {

    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> UNTIL = new HashMap<>();

    private WallSporeTrail() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        UNTIL.clear();
    }

    static void mark(ServerLevel level, BlockPos pos, long now) {
        Long2LongOpenHashMap map = UNTIL.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        long key = SectionPos.asLong(pos);
        long until = now + WallTuning.sporeTrailTicks;
        if (map.get(key) < until - 100) {
            map.put(key, until);
        }
    }

    /** {@code pos} の上乗せ(%)。通っていない所は 0。 */
    public static double percentAt(ServerLevel level, BlockPos pos) {
        Long2LongOpenHashMap map = UNTIL.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return 0.0D;
        }
        long until = map.get(SectionPos.asLong(pos));
        long left = until - level.getGameTime();
        if (left <= 0) {
            return 0.0D;
        }
        return WallTuning.sporeTrailPercent * Math.min(1.0D, left / (double) WallTuning.sporeTrailTicks);
    }

    /** 切れたものを捨てる(1 分ごとに {@link CreepingWalls} から)。 */
    static void prune(ServerLevel level) {
        Long2LongOpenHashMap map = UNTIL.get(level.dimension());
        if (map != null) {
            long now = level.getGameTime();
            map.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
        }
    }
}
