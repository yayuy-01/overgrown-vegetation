package com.yayuy.overgrownvegetation.survival;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.FatigueSpec;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 開発用: {@code /ov perf spore fatigue} = 自分の HUD のバーの値・疲労と効き目を見る、{@code /ov perf spore fatigue set <0〜100>} = 疲労を決める
 * (2026-11-13 サバイバルの背骨 段 1。暴露量は隣の {@code /ov perf spore exposure set} で決める。/ov の下の一覧はテスト CommandTreeGameTests で決めてあるので、
 * 新しい枝を作らず perf spore の下に足す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SurvivalCommand {

    private SurvivalCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spore")
                        .then(Commands.literal("fatigue")
                                .executes(SurvivalCommand::show)
                                .then(Commands.literal("set").then(Commands.argument("percent", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                                        .executes(ctx -> {
                                            if (!(ctx.getSource().getEntity() instanceof Player player)) {
                                                ctx.getSource().sendFailure(Component.literal("プレイヤーとして実行して"));
                                                return 0;
                                            }
                                            FatigueTracker.set(player, DoubleArgumentType.getDouble(ctx, "percent"));
                                            SurvivalSync.send(player);
                                            return show(ctx);
                                        })))))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof Player player)) {
            ctx.getSource().sendFailure(Component.literal("プレイヤーとして実行して"));
            return 0;
        }
        FatigueSpec s = FatigueTracker.spec();
        int stage = SporeInfectionEffects.stageOf(player);
        double fatigue = FatigueTracker.value(player);
        String bar = stage > 0
                ? String.format("感染%d・次の段階まで %.0f%%", stage, SurvivalSync.stageProgress(player) * 100.0F)
                : String.format("暴露量 %.0f%%(%.0f %%秒)", SporeExposureTracker.fraction(player) * 100.0D, SporeExposureTracker.dose(player));
        ctx.getSource().sendSuccess(() -> Component.literal("バー: " + bar), false);
        double t = s.effectStrength(fatigue);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "疲労 %.1f%%(効き目 %.0f%%: 移動 −%.0f%%・採掘 −%.0f%%・攻撃速度 −%.0f%%・暴露の半減期 ×%.2f・感染の進み ×%.2f)%s",
                fatigue, t * 100.0D, s.moveSlow() * t * 100.0D, s.mineSlow() * t * 100.0D, s.attackSpeedSlow() * t * 100.0D,
                FatigueTracker.exposureHalfLifeMultiplier(player), FatigueTracker.infectionMultiplier(player),
                s.enabled() ? "" : " ※ 疲労は切ってある")), false);
        return 1;
    }
}
