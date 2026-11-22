package com.yayuy.overgrownvegetation.organ;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * {@code /ov forest} — 森の様子を 1 か所で見る(2026-11-19 ユーザー決定。フェーズが上がったときのチャットの 1 行は 11-16 に消し、
 * 合図は現象だけにした。数字はこのコマンドで見る)。OP 専用(ほかの {@code /ov} と同じ)。
 * <ul>
 *   <li>今のフェーズ・段・次のフェーズまでの進み</li>
 *   <li>森のバイオマス(司令塔の予算: 今の量・上限・1 日に貯まる量)</li>
 *   <li>森の器官の種類ごとの数(働いている大・小 / 育っている)と、それで何がどれだけ強まっているか</li>
 * </ul>
 * フェーズの操作(set・止める・段)は {@code /ov phase} のまま。数字は役割の計算と同じ口({@link OrganRoles}・{@link LeyBuffs}・
 * {@link OrganWorld#sacHiveEquivalent})から読む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ForestCommand {
    private static final String K = "commands.overgrown_vegetation.forest.";

    private ForestCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("forest").requires(s -> s.hasPermission(2)).executes(ForestCommand::show)));
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getServer().overworld();
        for (Component line : lines(level)) src.sendSuccess(() -> line, false);
        return PhaseData.get(level).getPhase();
    }

    /** 出す行(GameTest からも読む)。 */
    static java.util.List<Component> lines(ServerLevel level) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        out.add(Component.translatable(K + "header"));

        // フェーズ
        PhaseData pd = PhaseData.get(level);
        int phase = pd.getPhase();
        PhaseTable table = DesignData.phases();
        long span = table.stageSpanPoints(phase);
        if (phase < 1) {
            out.add(Component.translatable(K + "phase_none"));
        } else if (span == Long.MAX_VALUE) {
            out.add(Component.translatable(K + "phase_nonext", phase, pd.getStage().displayName()));
        } else {
            out.add(Component.translatable(K + "phase", phase, pd.getStage().displayName(),
                    f1(100.0D * Math.min(1.0D, pd.getPoints() / (double) span))));
        }

        // バイオマス(司令塔の予算)
        ForestDirector dir = ForestDirector.get(level);
        out.add(Component.translatable(K + "biomass", f0(dir.available(level)), f0(ForestDirector.cap(level)),
                f0(ForestDirector.incomePerDay(level))));

        // 器官
        ForestOrgans data = ForestOrgans.get(level);
        int kinds = OrganKind.values().length;
        int[] big = new int[kinds], small = new int[kinds], growing = new int[kinds];
        int withered = 0;
        double phasePerDay = 0.0D, sporePerDay = 0.0D;
        int nurseryMax = 0;
        for (ForestOrgans.Organ o : data.organs()) {
            int k = o.kind.ordinal();
            switch (o.state) {
                case ALIVE -> {
                    if (o.small) small[k]++;
                    else big[k]++;
                    if (o.kind == OrganKind.PHASE) phasePerDay += OrganRoles.phasePointsPerDay(phase, o);
                    if (o.kind == OrganKind.TOWER) sporePerDay += OrganRoles.towerSporePerDay(o);
                    if (o.kind == OrganKind.NURSERY) nurseryMax = Math.max(nurseryMax, OrganRoles.nurseryMaxNear(o));
                }
                case GROWING -> growing[k]++;
                case WITHERED -> withered++;
            }
        }
        int allBig = 0, allSmall = 0, allGrowing = 0;
        for (int k = 0; k < kinds; k++) {
            allBig += big[k];
            allSmall += small[k];
            allGrowing += growing[k];
        }
        if (allBig + allSmall + allGrowing + withered == 0) {
            out.add(Component.translatable(K + "organs_none"));
            return out;
        }
        out.add(Component.translatable(K + "organs", allBig, allSmall, allGrowing, withered));
        for (OrganKind kind : OrganKind.values()) {
            int k = kind.ordinal();
            if (big[k] + small[k] + growing[k] == 0) continue;
            Component effect = big[k] + small[k] == 0
                    ? Component.translatable(K + "effect.inactive")
                    : effect(level, kind, phase, span, phasePerDay, sporePerDay, nurseryMax);
            out.add(Component.translatable(K + "kind_line", Component.translatable(K + "kind." + kind.id), big[k], small[k], growing[k], effect));
        }
        if (DirectorGates.peaceful(level)) out.add(Component.translatable(K + "peaceful"));
        return out;
    }

    private static Component effect(ServerLevel level, OrganKind kind, int phase, long span, double phasePerDay, double sporePerDay, int nurseryMax) {
        switch (kind) {
            case PHASE -> {
                if (span == Long.MAX_VALUE || phasePerDay <= 0.0D) return Component.translatable(K + "effect.phase_none");
                double mult = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.phasePointMultiplier(level.getServer());
                return Component.translatable(K + "effect.phase", f1(100.0D * phasePerDay * mult / span));
            }
            case TOWER -> {
                return Component.translatable(K + "effect.tower", f0(sporePerDay));
            }
            case SAC -> {
                double hives = OrganWorld.sacHiveEquivalent(level);
                double base = DesignData.director().cap(com.yayuy.overgrownvegetation.front.FrontTrees.livingTreeCount(level)); // 11-22: 基礎値 + 生きている巨大樹(= ハイヴノード)
                double add = DesignData.director().capPerHiveNode * hives;
                String pct = base > 0.0D ? f0(100.0D * add / base) : "?";
                return Component.translatable(K + "effect.sac", pct, f1(hives));
            }
            case NURSERY -> {
                return Component.translatable(K + "effect.nursery", nurseryMax);
            }
            case LEY -> {
                double[] t = LeyBuffs.totals(level);
                MutableComponent parts = Component.empty();
                boolean first = true;
                for (LeyBuffs.Stat s : LeyBuffs.Stat.values()) {
                    double v = t[s.ordinal()];
                    if (v <= 0.0D) continue;
                    if (!first) parts.append(Component.translatable(K + "sep"));
                    first = false;
                    Component name = Component.translatable(K + "stat." + s.name().toLowerCase(Locale.ROOT));
                    parts.append(Component.translatable(K + (s.percent ? "stat_pct" : "stat_add"), name, s.percent ? f0(v) : f1(v)));
                }
                return first ? Component.translatable(K + "effect.inactive") : Component.translatable(K + "effect.ley", parts);
            }
        }
        return Component.empty();
    }
}
