package com.yayuy.overgrownvegetation.drift;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 開発用 {@code /ov perf drift ...}(2026-11-20):
 * <ul>
 *   <li>{@code here} — 足元(または視線の先 16 ブロックまで)の水面に 1 体出す(払わない)。</li>
 *   <li>{@code launch} — フェーズ・間を無視して流す岸を探し、払って流す。</li>
 *   <li>{@code grow} — 帳面の腫れた島を全部、今すぐ育ち切らせる。</li>
 *   <li>{@code list} — 腫れた島と帳面の数。</li>
 *   <li>{@code stats} / {@code reset} — 重さと数え。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DriftCommand {

    private DriftCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("drift")
                                .then(Commands.literal("here").executes(DriftCommand::here))
                                .then(Commands.literal("launch").executes(DriftCommand::launch))
                                .then(Commands.literal("grow").executes(DriftCommand::grow))
                                .then(Commands.literal("list").executes(DriftCommand::list))
                                .then(Commands.literal("stats").executes(c -> say(c, DriftStats.line())))
                                .then(Commands.literal("reset").executes(c -> {
                                    DriftStats.reset();
                                    return say(c, "数えを空にした");
                                })))));
    }

    private static int say(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = c.getSource().getRotation() == null ? Vec3.ZERO : Vec3.directionFromRotation(c.getSource().getRotation());
        for (int r = 0; r <= 16; r++) {
            Vec3 p = eye.add(look.scale(r));
            for (int dy = 2; dy >= -24; dy--) {
                BlockPos b = BlockPos.containing(p.x, p.y + dy, p.z);
                int wy = DriftShore.waterSurfaceY(level, b);
                if (wy != Integer.MIN_VALUE) {
                    DriftMimicEntity e = DriftLaunches.spawnAt(level, new BlockPos(b.getX(), wy, b.getZ()));
                    return say(c, e == null ? "出せなかった" : "出した: " + b.getX() + " " + wy + " " + b.getZ());
                }
            }
        }
        return say(c, "近くに水面が無い");
    }

    private static int launch(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        int n = DriftLaunches.tryLaunch(level, level.getRandom());
        return say(c, n > 0 ? "流した: " + n + " 体" : "流せなかった(汚染された岸・風下の陸・上限・払いのどれか)");
    }

    private static int grow(CommandContext<CommandSourceStack> c) {
        int n = SwollenIslands.growAllNow(c.getSource().getLevel());
        return say(c, "腫れた島を育てた: " + n + " マス");
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        StringBuilder sb = new StringBuilder("腫れた島 " + SwollenIslands.islands(level).size() + " / 帳面の上 " + DriftLedger.count(level)
                + " / 海の上 " + DriftLaunches.alive(level));
        for (SwollenIslands.Island i : SwollenIslands.islands(level)) {
            sb.append("\n  ").append(i.cx).append(' ').append(i.wy).append(' ').append(i.cz).append(" 半径 ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", i.radius)).append(" 置いた ").append(i.done).append('/').append(i.planned());
        }
        return say(c, sb.toString());
    }
}
