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
                                .executes(ctx -> start(ctx, false)))
                        .then(Commands.literal("stop").executes(RaidCommand::stop))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean near) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.peaceful_blocked"));
            return 0;
        }

        BlockPos forced = null;
        if (near && source.getEntity() != null) {
            BlockPos p = source.getEntity().blockPosition();
            forced = RaidLocationPicker.groundAt(overworld, p.getX() + 24, p.getZ());
            if (forced == null) {
                source.sendFailure(Component.translatable("commands.overgrown_vegetation.raid.start_failed"));
                return 0;
            }
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

    private RaidCommand() {
    }
}
