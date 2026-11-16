package com.yayuy.overgrownvegetation.wall;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 蠢く壁を確かめるコマンド(OP 専用。2026-11-16 試作。手触り・速さ・枝分かれを見るため)。
 * <ul>
 *     <li>{@code /ov wall start [距離]} — 実行した所から洞窟をたどって約 {@code 距離}(既定 48)ブロック先の升から起こす</li>
 *     <li>{@code /ov wall at <座標>} — その升から起こす</li>
 *     <li>{@code /ov wall stop} — 全部止める(その場で塊になり、跡は置き続ける)</li>
 *     <li>{@code /ov wall status} — 升の数・残り時間・飲んだ数・重さ(ms/tick)・送った量</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CreepingWallCommand {

    private CreepingWallCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("wall")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start")
                                .executes(ctx -> start(ctx, 48))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(4, 256))
                                        .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "distance")))))
                        .then(Commands.literal("at")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> at(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                        .then(Commands.literal("stop").executes(CreepingWallCommand::stop))
                        .then(Commands.literal("status").executes(CreepingWallCommand::status))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, int distance) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos from = BlockPos.containing(ctx.getSource().getPosition());
        BlockPos origin = farCell(level, from, distance);
        if (origin == null) {
            ctx.getSource().sendFailure(Component.literal("洞窟の中(空の見えない空き)にいない"));
            return 0;
        }
        CreepingWall w = CreepingWalls.start(level, origin);
        ctx.getSource().sendSuccess(() -> Component.literal("蠢く壁 #" + w.id() + " を " + origin.toShortString() + " から起こした(洞窟の道のりで最大 "
                + distance + "、直線で " + (int) Math.round(Math.sqrt(from.distSqr(origin))) + " ブロック先)"), true);
        return 1;
    }

    private static int at(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ServerLevel level = ctx.getSource().getLevel();
        CreepingWall w = CreepingWalls.start(level, pos);
        ctx.getSource().sendSuccess(() -> Component.literal("蠢く壁 #" + w.id() + " を " + pos.toShortString() + " から起こした"), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        int n = CreepingWalls.stopAll(ctx.getSource().getLevel());
        ctx.getSource().sendSuccess(() -> Component.literal("蠢く壁を " + n + " つ止めた"), true);
        return n;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        long now = level.getGameTime();
        var walls = CreepingWalls.walls(level);
        if (walls.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("動いている蠢く壁は無い"), false);
        }
        for (CreepingWall w : walls) {
            String line = String.format("#%d %s 升 %d(最大 %d)先頭 %d 壊し中 %d 飲んだ %d 残り %.1f 秒 跡の待ち %d 置いた跡 %d / 広がり %.2f ms(最大 %.2f・平均 %.2f)",
                    w.id(), w.isStopped() ? "止まった" : "進行中", w.filledCount(), w.maxFilled, w.front.size(), w.chewing.size(), w.swallowed(),
                    Math.max(0, w.stopTick() - now) / 20.0D, w.trailPending(), w.trailPlaced,
                    w.lastFillNanos / 1e6, w.maxFillNanos / 1e6, w.fillTicks == 0 ? 0.0D : w.totalFillNanos / 1e6 / w.fillTicks);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            if (!w.chewing.isEmpty()) {
                // 壊している物の種類(多い順に 5 つ)
                java.util.Map<String, Integer> kinds = new java.util.HashMap<>();
                for (long p : w.chewing.keySet()) {
                    kinds.merge(level.getBlockState(BlockPos.of(p)).getBlock().getDescriptionId().replace("block.minecraft.", ""), 1, Integer::sum);
                }
                String top = kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(5)
                        .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining("・"));
                ctx.getSource().sendSuccess(() -> Component.literal("  壊している物: " + top), false);
            }
        }
        String net = String.format("跡 %.2f ms(最大 %.2f)飲み込み %.2f ms(最大 %.2f)送った量 %.1f KB/秒(最大 %.1f)累計 %.1f KB",
                CreepingWalls.lastTrailNanos / 1e6, CreepingWalls.maxTrailNanos / 1e6, CreepingWalls.lastSwallowNanos / 1e6, CreepingWalls.maxSwallowNanos / 1e6,
                CreepingWalls.sentBytesPerSecond / 1024.0D, CreepingWalls.maxSentBytesPerSecond / 1024.0D, CreepingWalls.sentBytesTotal / 1024.0D);
        ctx.getSource().sendSuccess(() -> Component.literal(net), false);
        return walls.size();
    }

    /** {@code from} から通れる升(空の下を除く)を 6 方向にたどり、道のり {@code distance} の升(届かなければいちばん遠い升)。 */
    static BlockPos farCell(ServerLevel level, BlockPos from, int distance) {
        BlockPos start = null;
        for (BlockPos p : BlockPos.withinManhattan(from, 3, 3, 3)) { // 足元が空きでなければ、近くの洞窟の空きから
            if (level.isLoaded(p) && WallCells.classify(level, p) == WallCells.Kind.PASS) {
                start = p.immutable();
                break;
            }
        }
        if (start == null) {
            return null;
        }
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        Long2IntOpenHashMap roofs = new Long2IntOpenHashMap();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        seen.put(start.asLong(), 0);
        q.enqueue(start.asLong());
        long best = start.asLong();
        int bestD = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        while (!q.isEmpty()) {
            long p = q.dequeueLong();
            int d = seen.get(p);
            if (d > bestD) {
                best = p;
                bestD = d;
            }
            if (d >= distance || seen.size() > 200_000) {
                continue;
            }
            for (Direction dir : Direction.values()) {
                n.set(p).move(dir);
                long np = n.asLong();
                if (seen.containsKey(np) || !level.isLoaded(n) || WallCells.classify(level, n, roofs) != WallCells.Kind.PASS) {
                    continue;
                }
                seen.put(np, d + 1);
                q.enqueue(np);
            }
        }
        return BlockPos.of(best);
    }
}
