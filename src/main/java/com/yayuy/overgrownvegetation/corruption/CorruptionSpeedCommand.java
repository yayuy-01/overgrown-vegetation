package com.yayuy.overgrownvegetation.corruption;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * {@code /ov corruption} — 汚染のオン・オフ({@link CorruptionSwitch})と侵食速度を表示する。
 * {@code /ov corruption true|false} — 汚染を再開する / すべて止める(2026-10-10 ユーザー指定)。
 * {@code /ov corruption speed}(引数なし)— 汚染シダ(blight_fern)・汚染死体ブロック共通の侵食速度倍率の現在値を表示する。
 * {@code /ov corruption speed <倍率>} — その倍率に変更する
 * ({@link CorruptionSpreader#effectiveBlocksPerCycle} 参照)。
 *
 * <p>2026-09-25 から<b>このワールドだけ</b>の値(ワールドの難易度「汚染シダの汚染速度」、
 * 葉っぱのボタンの画面と同じ)。それまでは config の {@code corruptionSpeedMultiplier} を
 * 書き換えていて全ワールドに効いていた。config の値は「ワールドの値が無いときの既定値」として残してある。
 *
 * <p>コマンド全体が OP 専用({@code hasPermission(2)})。
 *
 * <p>{@code PhaseCommand} と同じく最上位の {@code overgrownvegetation} リテラルを別途
 * {@code register} しているが、Brigadier は同名リテラルの子ノードをマージするため
 * {@code /ov phase} と {@code /ov corruption} は問題なく共存する。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CorruptionSpeedCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                // 2026-10-10 ユーザー指定: /ov corruption speed [倍率] と /ov corruption true|false(false で全ての汚染を止める。CorruptionSwitch)。
                // 同じ日の朝に一度 /ov corruptionspeed へまとめたのを、この形へ戻した。
                .then(Commands.literal("corruption")
                        .requires(source -> source.hasPermission(2))
                        .executes(CorruptionSpeedCommand::showAll)
                        .then(Commands.literal("speed")
                                .executes(CorruptionSpeedCommand::showStatus)
                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.01D, 100.0D))
                                        .executes(CorruptionSpeedCommand::setSpeed)))
                        .then(Commands.literal("true").executes(ctx -> setSwitch(ctx, true)))
                        .then(Commands.literal("false").executes(ctx -> setSwitch(ctx, false)))));
    }

    /** {@code /ov corruption}: オン・オフと侵食速度。 */
    private static int showAll(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = CorruptionSwitch.enabled(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.corruption.switch.status",
                Component.translatable(enabled ? "commands.overgrown_vegetation.corruption.switch.on"
                        : "commands.overgrown_vegetation.corruption.switch.off")), false);
        showStatus(ctx);
        return enabled ? 1 : 0;
    }

    /** {@code /ov corruption true|false}。 */
    private static int setSwitch(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        CorruptionSwitch.set(ctx.getSource().getServer(), enabled);
        ctx.getSource().sendSuccess(() -> Component.translatable(enabled
                ? "commands.overgrown_vegetation.corruption.switch.set_on"
                : "commands.overgrown_vegetation.corruption.switch.set_off"), true);
        return enabled ? 1 : 0;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        double multiplier = WorldDifficultyData.corruptionSpeed(ctx.getSource().getServer());
        double baseline = baselineBlocksPerSecond();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.corruption.speed.status",
                format(multiplier), format(baseline), format(baseline * multiplier)), false);
        return (int) Math.round(multiplier * 100);
    }

    private static int setSpeed(CommandContext<CommandSourceStack> ctx) {
        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        WorldDifficultyData.get(ctx.getSource().getServer()).set(DifficultySetting.CORRUPTION_SPEED, multiplier);

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
