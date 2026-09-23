package com.yayuy.overgrownvegetation.quake;

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

/**
 * {@code /ov quake start [near]} — 地割れ演出を即座に発生させる
 * (near = 実行者の少し先を震央にする / 省略時は通常の抽選)。
 * {@code /ov quake stop} — 進行中の全演出を打ち切る。
 *
 * <p>{@code PhaseCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途 register するが、
 * Brigadier が同名リテラルの子ノードをマージするので共存する。全体が OP 専用。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EarthquakeCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("quake")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("start")
                                .executes(ctx -> start(ctx, false))
                                .then(Commands.literal("near").executes(ctx -> start(ctx, true))))
                        .then(Commands.literal("stop").executes(EarthquakeCommand::stop))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean near) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();

        BlockPos forced = null;
        if (near && source.getEntity() != null) {
            BlockPos p = source.getEntity().blockPosition();
            forced = new BlockPos(p.getX() + 40, overworld.getSeaLevel(), p.getZ() + 40);
        }

        boolean ok = EarthquakeEvent.trigger(overworld, forced);
        if (ok) {
            source.sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.quake.started"), true);
            return 1;
        }
        source.sendFailure(Component.translatable("commands.overgrown_vegetation.quake.start_failed"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        int n = EarthquakeData.get(overworld).stopAll(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.quake.stopped", n), true);
        return n;
    }

    private EarthquakeCommand() {
    }
}
