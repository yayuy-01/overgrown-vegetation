package com.yayuy.overgrownvegetation.raid;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov smallraid start} — 打った本人を狙う小規模レイドをいますぐ出す(OP)。
 * {@code /ov smallraid status} — 次の予約時刻を表示。テスト用。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SmallRaidCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("smallraid")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start").executes(SmallRaidCommand::start))
                        .then(Commands.literal("status").executes(SmallRaidCommand::status))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("commands.overgrown_vegetation.small_raid.need_player"));
            return 0;
        }
        int phase = Math.max(1, PhaseData.get(overworld).getPhase());
        if (SmallRaidController.start(player.serverLevel(), player, phase)) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.small_raid.started"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.small_raid.start_failed"));
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        long next = SmallRaidData.get(overworld).getNextGameTime();
        long now = overworld.getGameTime();
        if (next < 0L) {
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.small_raid.not_scheduled"), false);
            return 0;
        }
        long remaining = Math.max(0L, next - now);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.small_raid.next_in",
                remaining / 20L), false);
        return 1;
    }

    private SmallRaidCommand() {
    }
}
