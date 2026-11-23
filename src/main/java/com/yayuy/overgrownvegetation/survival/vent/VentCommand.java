package com.yayuy.overgrownvegetation.survival.vent;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 開発用(2026-11-14 段 2c): {@code /ov perf vent} = 動いている換気機の数・ここが範囲の中か・ここの濃度と部屋のたまり。
 * {@code /ov perf vent forest <半径>} = 立っているチャンクから半径(チャンク)のチャンクを過繁茂の台帳に載せる(バイオームは変えない。
 * 胞子の場でも汚染済みになり放出が始まる。換気機の確かめ用。元に戻す命令は無い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class VentCommand {

    private VentCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("vent")
                                .executes(VentCommand::show)
                                .then(Commands.literal("forest")
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                                .executes(VentCommand::forest))))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
        int running = Ventilators.running(level).size();
        boolean covered = Ventilators.covers(level, p.getX() >> 4, p.getZ() >> 4, DesignData.sporeField().vent());
        SporeConcentration.Reading r = SporeConcentration.reading(level, p);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "換気機: 動いている %d 台・ここは範囲%s・濃度 %.1f%%・部屋のたまり %.3f %%秒/秒",
                running, covered ? "の中" : "の外", r.percent(), r.roomSeepPerSecond()) + "\n" + com.yayuy.overgrownvegetation.corruption.pushback.AbsentWards.describe(level, p, true)), false); // 留守の換気機(11-23)
        return running;
    }

    private static int forest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        int r = IntegerArgumentType.getInteger(ctx, "radius");
        ChunkPos c = new ChunkPos(BlockPos.containing(ctx.getSource().getPosition()));
        List<ChunkPos> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) list.add(new ChunkPos(c.x + dx, c.z + dz));
        int n = PhaseData.get(level).markOvergrownChunks(level, list);
        SporeConcentration.clearCacheForTests();
        ctx.getSource().sendSuccess(() -> Component.literal("過繁茂の台帳に載せた: " + n + " チャンク(半径 " + r + ")"), true);
        return n;
    }
}
