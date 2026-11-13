package com.yayuy.overgrownvegetation.ley;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 開発用(2026-11-13 段 2b): {@code /ov perf ley} = 立っている所から地脈の線までの距離・汲み上げ器の効率・花の帯の中か。
 * {@code /ov perf ley near} = いちばん近い花の帯の中の地表へ飛ぶ(探すのは 256 ブロックまで、2 おきの輪)。プレイヤー向けの探し物の道具ではない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyCommand {

    private LeyCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("ley")
                                .executes(LeyCommand::show)
                                .then(Commands.literal("near").executes(LeyCommand::near)))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
        long seed = level.getSeed();
        double d = LeyLines.distanceToLine(seed, p.getX(), p.getZ());
        double eff = LeyLines.efficiency(seed, p.getX(), p.getZ());
        boolean band = LeyLines.inFlowerBand(seed, p.getX(), p.getZ());
        ctx.getSource().sendSuccess(() -> Component.literal("地脈: 線まで " + LeyText.one(d) + " ブロック・効率 " + LeyText.percent(eff)
                + "%・花の帯 " + (band ? "中" : "外")), false);
        return 1;
    }

    private static int near(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos o = player.blockPosition();
        long seed = level.getSeed();
        for (int r = 0; r <= 256; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = o.getX() + dx;
                    int z = o.getZ() + dz;
                    if (LeyLines.inFlowerBand(seed, x, z)) {
                        level.getChunk(x >> 4, z >> 4);
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        player.teleportTo(x + 0.5, y, z + 0.5);
                        ctx.getSource().sendSuccess(() -> Component.literal("地脈の線へ: " + x + " " + y + " " + z), false);
                        return 1;
                    }
                }
            }
        }
        ctx.getSource().sendFailure(Component.literal("256 ブロックの中に線が無い"));
        return 0;
    }
}
