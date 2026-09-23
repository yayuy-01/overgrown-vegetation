package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov corruption speed}(引数なし)— 汚染シダ(blight_fern)・汚染死体ブロック共通の
 * 侵食速度倍率({@code corruptionSpeedMultiplier})の現在値を表示する。
 * {@code /ov corruption speed <倍率>} — その倍率に変更する
 * ({@link CorruptionSpreader#effectiveBlocksPerCycle} 参照)。
 *
 * <p>コマンド全体が OP 専用({@code hasPermission(2)})。
 *
 * <p>{@code PhaseCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途
 * {@code register} しているが、Brigadier は同名リテラルの子ノードをマージするため
 * {@code /ov phase} と {@code /ov corruption} は問題なく共存する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CorruptionSpeedCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("corruption")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("speed")
                                .executes(CorruptionSpeedCommand::showStatus)
                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01D, 100.0D))
                                        .executes(CorruptionSpeedCommand::setSpeed)))));
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        double multiplier = OvergrownVegetationConfig.COMMON.corruptionSpeedMultiplier.get();
        double baseline = baselineBlocksPerSecond();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.corruption.speed.status",
                format(multiplier), format(baseline), format(baseline * multiplier)), false);
        return (int) Math.round(multiplier * 100);
    }

    private static int setSpeed(CommandContext<CommandSourceStack> ctx) {
        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        OvergrownVegetationConfig.COMMON.corruptionSpeedMultiplier.set(multiplier);
        OvergrownVegetationConfig.SPEC.save();

        double baseline = baselineBlocksPerSecond();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.corruption.speed.set",
                format(multiplier), format(baseline * multiplier)), true);
        return (int) Math.round(multiplier * 100);
    }

    /** 倍率1.0のときの「基準速度」(ブロック/秒) = blocksPerCycle(開始値) / tickInterval * 20。 */
    private static double baselineBlocksPerSecond() {
        double blocksPerCycle = OvergrownVegetationConfig.COMMON.blocksPerCycle.get();
        double tickInterval = OvergrownVegetationConfig.COMMON.tickInterval.get();
        return blocksPerCycle / tickInterval * 20.0D;
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    private CorruptionSpeedCommand() {
    }
}
