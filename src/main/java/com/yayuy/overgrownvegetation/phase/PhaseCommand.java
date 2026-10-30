package com.yayuy.overgrownvegetation.phase;

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
 * {@code /ov phase true|false} — フェーズの自動進行を再開・凍結する(2026-10-10 から。旧 {@code freeze <bool>})。
 * {@code /ov phase omen [true|false|auto]} — F1 の前の前兆({@link LeyOmen})の様子・開発用の強制(2026-10-24)。
 * フェーズ0のまま凍結すれば、既定 3 日後に始まるフェーズ1(地割れ演出を含む)が起きなくなる。
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
                        // 自動進行の有効・無効(2026-10-10 ユーザー指定: true = 進む、false = 止める。旧 freeze <bool> は逆向きだった)。
                        .then(Commands.literal("true").executes(ctx -> setFreeze(ctx, false)))
                        .then(Commands.literal("false").executes(ctx -> setFreeze(ctx, true)))
                        // F1 の前の前兆を開発用に強制する(2026-10-24 地脈技術の 4 段目): true = 強制オン、false = 強制オフ、auto = 経過とフェーズで決める。
                        .then(Commands.literal("omen")
                                .executes(PhaseCommand::showOmen)
                                .then(Commands.literal("true").executes(ctx -> setOmen(ctx, LeyOmen.Mode.ON)))
                                .then(Commands.literal("false").executes(ctx -> setOmen(ctx, LeyOmen.Mode.OFF)))
                                .then(Commands.literal("auto").executes(ctx -> setOmen(ctx, LeyOmen.Mode.AUTO)))
                                // 軽い地鳴りを今すぐ(撮影・確かめ用)
                                .then(Commands.literal("quake").executes(PhaseCommand::omenQuake)))));
    }

    private static int omenQuake(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        int n = LeyOmen.lightQuake(overworld, DesignData.phases().omen());
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.phase.omen.quake", n), true);
        return n;
    }

    private static int showOmen(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        boolean active = LeyOmen.computeActive(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.phase.omen.status",
                Component.translatable(active ? "commands.overgrown_vegetation.phase.omen.on" : "commands.overgrown_vegetation.phase.omen.off"),
                LeyOmen.override(overworld).name().toLowerCase(java.util.Locale.ROOT),
                LeyOmen.startTicks(overworld.getServer()), LeyOmen.sampleStartTicks(overworld.getServer()),
                overworld.getGameTime()), false);
        return active ? 1 : 0;
    }

    private static int setOmen(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, LeyOmen.Mode mode) {
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        LeyOmen.setOverride(overworld, mode);
        boolean active = LeyOmen.computeActive(overworld);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.overgrown_vegetation.phase.omen.set",
                mode.name().toLowerCase(java.util.Locale.ROOT),
                Component.translatable(active ? "commands.overgrown_vegetation.phase.omen.on" : "commands.overgrown_vegetation.phase.omen.off")), true);
        return active ? 1 : 0;
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
            // 時刻はワールドの難易度「地割れの発生日時」(2026-09-25。ワールドの値が無ければ startWorldAgeTicks)。
            return Component.translatable("commands.overgrown_vegetation.phase.requirement.phase1",
                    overworld.getGameTime(),
                    com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.phase1StartTicks(overworld.getServer()),
                    data.getStartAdvancementCount(),
                    com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.phase1Advancements(overworld.getServer()));
        }
        long needPoints = phases.pointsToAdvance(next);
        return needPoints == Long.MAX_VALUE
                ? Component.translatable("commands.overgrown_vegetation.phase.requirement.none", phase)
                : Component.translatable("commands.overgrown_vegetation.phase.requirement.points", next, needPoints);
    }

    /** {@code phase true|false} — フェーズの自動進行を再開(true)・凍結(false)する。 */
    private static int setFreeze(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean frozen) {
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
