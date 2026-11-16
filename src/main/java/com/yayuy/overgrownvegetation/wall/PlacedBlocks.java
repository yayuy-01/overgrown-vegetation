package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * プレイヤーが置いたブロックの覚え(蠢く壁の「置いたブロックは壊して進む」のため。決定 その 7)。
 * 天然の地形(タグ {@code cave_rock})の土・石でも、プレイヤーが置いたものは壁が壊せる。
 * メモリの上だけ(世界ごとに {@link WallTuning#placedMemory} 個まで、古いものから忘れる)。再起動より前に置いた土・石は天然の壁の扱いになる(了承済み)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PlacedBlocks {

    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> PLACED = new HashMap<>();

    private PlacedBlocks() {
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Player) {
            mark(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LongLinkedOpenHashSet set = PLACED.get(level.dimension());
            if (set != null) {
                set.remove(event.getPos().asLong());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLACED.clear();
    }

    /** プレイヤーが置いたと覚える(テストからも呼ぶ)。 */
    public static void mark(ServerLevel level, BlockPos pos) {
        LongLinkedOpenHashSet set = PLACED.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet());
        set.addAndMoveToLast(pos.asLong());
        while (set.size() > WallTuning.placedMemory) {
            set.removeFirstLong();
        }
    }

    public static boolean isPlaced(ServerLevel level, long pos) {
        LongLinkedOpenHashSet set = PLACED.get(level.dimension());
        return set != null && set.contains(pos);
    }
}
