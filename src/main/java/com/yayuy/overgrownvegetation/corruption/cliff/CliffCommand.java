package com.yayuy.overgrownvegetation.corruption.cliff;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.util.LoadedChunks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov perf cliff grow <半径チャンク> [ropes|sacs|both]} — 汚染の段に関係なく、まわりのチャンクの崖・急斜面に今すぐ生やす
 * (2026-11-20 崖の汚染の撮影・確認用。OP 専用)。生やしたチャンクには巡回の済みの印も付ける。
 * {@code /ov perf cliff info} — 今いるチャンクの段・印と、巡回の直近の結果。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CliffCommand {
    private CliffCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("cliff")
                                .then(Commands.literal("info").executes(CliffCommand::info))
                                .then(Commands.literal("grow")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                                .executes(c -> grow(c, "both"))
                                                .then(Commands.argument("what", StringArgumentType.word())
                                                        .executes(c -> grow(c, StringArgumentType.getString(c, "what")))))))));
    }

    private static int grow(CommandContext<CommandSourceStack> c, String what) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        boolean ropes = !"sacs".equals(what), sacs = !"ropes".equals(what);
        int r = IntegerArgumentType.getInteger(c, "radius");
        ChunkPos center = new ChunkPos(BlockPos.containing(src.getPosition()));
        CliffCorruption.Data data = CliffCorruption.Data.get(level);
        CliffShapes.Result sum = new CliffShapes.Result();
        int chunks = 0, skipped = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                if (!LoadedChunks.neighbourhoodLoaded(level, cp.x, cp.z)) {
                    skipped++;
                    continue;
                }
                CliffShapes.Result res = CliffShapes.decorateChunk(level, cp, ropes, sacs);
                data.mark(cp, (byte) (data.get(cp) | (ropes ? CliffCorruption.ROPES : 0) | (sacs ? CliffCorruption.SACS : 0)));
                add(sum, res);
                chunks++;
            }
        }
        String msg = "崖の汚染: " + chunks + " チャンク(読み込み待ちで飛ばした " + skipped + ")/ " + sum;
        src.sendSuccess(() -> Component.literal(msg), true);
        return chunks;
    }

    private static void add(CliffShapes.Result s, CliffShapes.Result r) {
        s.cliffEdges += r.cliffEdges;
        s.slopeEdges += r.slopeEdges;
        s.ropes += r.ropes;
        s.thin += r.thin;
        s.cracks += r.cracks;
        s.roots += r.roots;
        s.sacs += r.sacs;
        s.flesh += r.flesh;
        s.nanos += r.nanos;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ChunkPos cp = new ChunkPos(BlockPos.containing(src.getPosition()));
        int st = CliffCorruption.stage(level, cp);
        byte bits = CliffCorruption.Data.get(level).get(cp);
        CliffShapes.Result last = CliffCorruption.last;
        String msg = "崖の汚染: このチャンクの段 " + st + " / 済みの印 " + bits + "(1 = 縄・2 = 嚢)/ 霧 " + CliffSacFog.active(level)
                + " / 巡回の直近: " + (last == null ? "まだ" : last.toString());
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
