package com.yayuy.overgrownvegetation.difficulty;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ov experimental [on|off]}(2026-11-12 ユーザー決定「おすすめ通り」)。ワールドの「実験的要素」({@link DifficultySetting#EXPERIMENTAL})を
 * 見る・切り替える。op(権限 2)だけ。設定の画面が parked にあって、今はほかにオンにする手段が無いため。画面は 0.2.0 の設定の画面を作るときに戻す。
 * 今の実験的要素 = 新しいネザーの汚染({@link ExperimentalFeatures})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ExperimentalCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("experimental")
                        .requires(source -> source.hasPermission(2))
                        .executes(ExperimentalCommand::show)
                        .then(Commands.literal("on").executes(ctx -> set(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> set(ctx, false)))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        boolean on = ExperimentalFeatures.enabled(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.experimental.status",
                Component.translatable(on ? "commands.overgrown_vegetation.experimental.on" : "commands.overgrown_vegetation.experimental.off")), false);
        return on ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean on) {
        ExperimentalFeatures.set(ctx.getSource().getServer(), on);
        ctx.getSource().sendSuccess(() -> Component.translatable(on
                ? "commands.overgrown_vegetation.experimental.set_on" : "commands.overgrown_vegetation.experimental.set_off"), true);
        return on ? 1 : 0;
    }

    private ExperimentalCommand() {
    }
}
