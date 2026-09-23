package com.yayuy.overgrownvegetation.phase;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /ov phase}(引数なし)— 現在のフェーズ/ポイント/凍結状態を表示する。
 * {@code /ov phase set <0-10>} — フェーズを直接進める/戻す(ポイントは 0 にリセット)。
 * {@code /ov phase freeze <true|false>} — フェーズの自動進行を凍結/解除する。
 * フェーズ0のまま凍結すれば、既定 3 日後に始まるフェーズ1(地割れ演出を含む)が起きなくなる。
 * {@code freeze}(引数なし)は現在の凍結状態を表示するだけ。
 *
 * <p>コマンド全体が OP 専用({@code hasPermission(2)})。</p>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PhaseCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("phase")
                        .requires(source -> source.hasPermission(2))
                        .executes(PhaseCommand::showStatus)
                        .then(Commands.literal("set")
                                .then(Commands.argument("phase", IntegerArgumentType.integer(0, 10))
                                        .executes(PhaseCommand::setPhase)))
                        .then(Commands.literal("freeze")
                                .executes(PhaseCommand::showFreeze)
                                .then(Commands.argument("frozen", BoolArgumentType.bool())
                                        .executes(PhaseCommand::setFreeze)))));
    }

    private static int showStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        PhaseData data = PhaseData.get(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.phase.status",
                data.getPhase(), data.getPoints(),
                data.getOvergrownChunks(), data.getCorruptedBlocks()), false);
        ctx.getSource().sendSuccess(() -> nextPhaseRequirement(overworld, data), false);
        if (data.isFrozen()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "commands.overgrown_vegetation.phase.freeze.status_frozen"), false);
        }
        return data.getPhase();
    }

    /**
     * 次のフェーズへの昇格条件を 1 行で表す。条件が 1 つも無いフェーズ(既定ではフェーズ4以降)は
     * 「これ以上は自動で進まない」と伝える。
     */
    private static Component nextPhaseRequirement(ServerLevel overworld, PhaseData data) {
        int phase = data.getPhase();
        int next = phase + 1;
        PhaseTable phases = DesignData.phases();
        if (phase < 1) {
            // フェーズ0→1 は「ワールド経過時刻」と「解除した実績の種類数」の両方が条件。
            return Component.translatable("commands.overgrown_vegetation.phase.requirement.phase1",
                    overworld.getGameTime(), phases.startWorldAgeTicks(),
                    data.getStartAdvancementCount(), phases.startAdvancements());
        }
        long needPoints = phases.pointsToAdvance(next);
        return needPoints == Long.MAX_VALUE
                ? Component.translatable("commands.overgrown_vegetation.phase.requirement.none", phase)
                : Component.translatable("commands.overgrown_vegetation.phase.requirement.points", next, needPoints);
    }

    /** {@code phase freeze}(引数なし)— 現在の凍結状態を表示する。 */
    private static int showFreeze(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        boolean frozen = PhaseData.get(overworld).isFrozen();
        ctx.getSource().sendSuccess(() -> Component.translatable(frozen
                ? "commands.overgrown_vegetation.phase.freeze.status_frozen"
                : "commands.overgrown_vegetation.phase.freeze.status_running"), false);
        return frozen ? 1 : 0;
    }

    /** {@code phase freeze <true|false>} — フェーズの自動進行を凍結/解除する。 */
    private static int setFreeze(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        boolean frozen = BoolArgumentType.getBool(ctx, "frozen");
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        PhaseData data = PhaseData.get(overworld);
        data.setFrozen(overworld, frozen);
        ctx.getSource().sendSuccess(() -> Component.translatable(frozen
                        ? "commands.overgrown_vegetation.phase.freeze.on"
                        : "commands.overgrown_vegetation.phase.freeze.off",
                data.getPhase()), true);
        return frozen ? 1 : 0;
    }

    private static int setPhase(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        int newPhase = IntegerArgumentType.getInteger(ctx, "phase");
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        PhaseData.get(overworld).setPhase(overworld, newPhase);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.overgrown_vegetation.phase.set", newPhase), true);
        return newPhase;
    }

    private PhaseCommand() {
    }
}
