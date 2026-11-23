package com.yayuy.overgrownvegetation.difficulty;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ov experimental}(2026-11-12 ユーザー決定「おすすめ通り」、11-23 夜に要素ごとへ)。ワールドの実験的要素を見る・切り替える。op(権限 2)だけ。
 * <ul>
 *     <li>{@code /ov experimental} — 全部の今の値</li>
 *     <li>{@code /ov experimental <nether|events|wall|research|attachments> [on|off]} — 1 つを見る・切り替える</li>
 *     <li>{@code /ov experimental on|off} — 全部をまとめて切り替える(前の 1 つのスイッチの口を残した)</li>
 * </ul>
 * 設定の画面(実験的要素の節)でも同じ値を切り替えられる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class ExperimentalCommand {

    private static final String KEY = "commands.overgrown_vegetation.experimental.";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("experimental")
                .requires(source -> source.hasPermission(2))
                .executes(ExperimentalCommand::showAll)
                .then(Commands.literal("on").executes(ctx -> setAll(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setAll(ctx, false)));
        for (ExperimentalFeatures.Feature f : ExperimentalFeatures.Feature.values()) {
            root.then(Commands.literal(f.id())
                    .executes(ctx -> show(ctx, f))
                    .then(Commands.literal("on").executes(ctx -> set(ctx, f, true)))
                    .then(Commands.literal("off").executes(ctx -> set(ctx, f, false))));
        }
        event.getDispatcher().register(Commands.literal("ov").then(root));
    }

    private static Component name(ExperimentalFeatures.Feature f) {
        return Component.translatable(f.setting().translationKey());
    }

    private static Component onOff(boolean on) {
        return Component.translatable(KEY + (on ? "on" : "off"));
    }

    private static int showAll(CommandContext<CommandSourceStack> ctx) {
        int count = 0;
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "header"), false);
        for (ExperimentalFeatures.Feature f : ExperimentalFeatures.Feature.values()) {
            boolean on = ExperimentalFeatures.enabled(ctx.getSource().getServer(), f);
            if (on) count++;
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "line", name(f), onOff(on), f.id()), false);
        }
        return count;
    }

    private static int show(CommandContext<CommandSourceStack> ctx, ExperimentalFeatures.Feature f) {
        boolean on = ExperimentalFeatures.enabled(ctx.getSource().getServer(), f);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "status", name(f), onOff(on)), false);
        return on ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, ExperimentalFeatures.Feature f, boolean on) {
        ExperimentalFeatures.set(ctx.getSource().getServer(), f, on);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "set", name(f), onOff(on)), true);
        return on ? 1 : 0;
    }

    private static int setAll(CommandContext<CommandSourceStack> ctx, boolean on) {
        ExperimentalFeatures.setAll(ctx.getSource().getServer(), on);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + (on ? "set_all_on" : "set_all_off")), true);
        return on ? 1 : 0;
    }

    private ExperimentalCommand() {
    }
}
