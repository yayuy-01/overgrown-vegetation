package com.yayuy.overgrownvegetation.raid;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /ov raid start [near|far]} — レイドを即座に発生させる
 * (near = 実行者付近、far = 通常の抽選)。
 * {@code /ov raid stop} — 進行中の全レイドを終了する。
 * {@code /ov raid list} — 進行中のレイド一覧を表示する。
 *
 * <p>{@code PhaseCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途 register するが、
 * Brigadier は同名リテラルの子ノードをマージするので共存する。全体が OP 専用。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RaidCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("raid")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start")
                                .executes(ctx -> start(ctx, false))
                                .then(Commands.literal("near").executes(ctx -> start(ctx, true)))
                                .then(Commands.literal("far").executes(ctx -> start(ctx, false))))
                        .then(Commands.literal("stop").executes(RaidCommand::stop))
                        .then(Commands.literal("list").executes(RaidCommand::list))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean near) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();

        BlockPos forced = null;
        if (near && source.getEntity() != null) {
            BlockPos p = source.getEntity().blockPosition();
            int surfaceY = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    p.getX() + 24, p.getZ());
            forced = new BlockPos(p.getX() + 24, surfaceY, p.getZ());
        }

        boolean ok = RaidController.tryStartRaid(overworld, forced);
        if (ok) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.raid.started"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.raid.start_failed"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        int count = RaidData.get(overworld).getActiveRaidCount();
        RaidData.get(overworld).stopAllRaids(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.raid.stopped", count), true);
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        List<RaidData.ActiveRaid> raids = RaidData.get(overworld).getActiveRaids();
        if (raids.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.raid.list_empty"), false);
            return 0;
        }
        long now = overworld.getGameTime();
        for (RaidData.ActiveRaid raid : raids) {
            BlockPos o = raid.origin();
            long ageTicks = now - raid.startGameTime();
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "commands.overgrown_vegetation.raid.list_entry",
                    o.getX(), o.getY(), o.getZ(), ageTicks / 20L), false);
        }
        return raids.size();
    }

    private RaidCommand() {
    }
}
