package com.yayuy.overgrownvegetation.organ;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 森の器官の確かめ用コマンド(2026-11-17。{@code /ov} の下の決めた一覧を増やさないよう {@code /ov perf organ} に置く)。
 * <ul>
 *   <li>{@code /ov perf organ list} — 器官の一覧・値段・嚢の上限・根のステータス。</li>
 *   <li>{@code /ov perf organ plant <種類> [形]} / {@code plantsmall <種類> [形]} — 足元に芽吹かせる(予算は払わない)。</li>
 *   <li>{@code /ov perf organ build <id>} — 育ち具合を無視してその場で全部置く(育ち切った扱いにする)。</li>
 *   <li>{@code /ov perf organ wither <id>} — 芯を壊したのと同じに枯らす(ポイント・予算も引く)。</li>
 *   <li>{@code /ov perf organ remove <id>} — 記録から消す(ブロックは残る)。</li>
 *   <li>{@code /ov perf organ plan [種類]} / {@code plansmall [種類]} — 司令塔に 1 回考えさせる(種類を付けると予算・数を見ない)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OrganCommand {
    private OrganCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> organ = Commands.literal("organ")
                .then(Commands.literal("list").executes(OrganCommand::list))
                .then(plantNode("plant", false))
                .then(plantNode("plantsmall", true))
                .then(Commands.literal("build").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(OrganCommand::build)))
                .then(Commands.literal("wither").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(OrganCommand::wither)))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(OrganCommand::remove)))
                .then(planNode("plan", false))
                .then(planNode("plansmall", true))
                // 重さの測り(11-18): 足元のまわりにまとめて芽吹かせる・育ち具合を進める・器官の処理の時間
                .then(Commands.literal("scatter")
                        .then(Commands.argument("big", IntegerArgumentType.integer(0, 200))
                                .then(Commands.argument("small", IntegerArgumentType.integer(0, 1000))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000))
                                                .executes(OrganCommand::scatter)))))
                .then(Commands.literal("age")
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100)).executes(OrganCommand::age)))
                .then(Commands.literal("stats")
                        .executes(c -> {
                            c.getSource().sendSuccess(() -> Component.literal(OrganMeter.summary()), false);
                            for (String s : OrganMeter.slowLines()) c.getSource().sendSuccess(() -> Component.literal("  遅い: " + s), false);
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(c -> {
                            OrganMeter.reset();
                            c.getSource().sendSuccess(() -> Component.literal("器官の処理の物差しを空にした"), false);
                            return 1;
                        })));
        event.getDispatcher().register(Commands.literal("ov").then(Commands.literal("perf").requires(s -> s.hasPermission(2)).then(organ)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> plantNode(String name, boolean small) {
        return Commands.literal(name).then(Commands.argument("kind", StringArgumentType.word())
                .suggests((c, b) -> {
                    for (OrganKind k : OrganKind.values()) b.suggest(k.id);
                    return b.buildFuture();
                })
                .executes(c -> plant(c, -1, small))
                .then(Commands.argument("variant", IntegerArgumentType.integer(0, 3))
                        .executes(c -> plant(c, IntegerArgumentType.getInteger(c, "variant"), small))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> planNode(String name, boolean small) {
        return Commands.literal(name)
                .executes(c -> plan(c, null, small))
                .then(Commands.argument("kind", StringArgumentType.word())
                        .executes(c -> plan(c, OrganKind.byId(StringArgumentType.getString(c, "kind")), small)));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        ForestOrgans data = ForestOrgans.get(level);
        long now = PhaseData.clockNow(level);
        double[] buffs = LeyBuffs.totals(level);
        StringBuilder b = new StringBuilder();
        for (LeyBuffs.Stat s : LeyBuffs.Stat.values()) {
            if (buffs[s.ordinal()] > 0.0D) b.append(String.format(Locale.ROOT, " %s +%.2f", s.label, buffs[s.ordinal()]));
        }
        String buffLine = b.length() == 0 ? " なし" : b.toString();
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "森の器官 %d(生きている・育っている 大 %d・小 %d)・値段 大 %.0f・小 %.0f・嚢で上限 +ハイヴノード %.1f 個分・根のステータス%s",
                data.organs().size(), data.livingCount(false), data.livingCount(true), OrganPlanner.price(level, false),
                OrganPlanner.price(level, true), OrganWorld.sacHiveEquivalent(level), buffLine)), false);
        for (ForestOrgans.Organ o : data.organs()) {
            LeyBuffs.Stat s = o.kind == OrganKind.LEY ? LeyBuffs.statOf(level, o) : null;
            c.getSource().sendSuccess(() -> Component.literal(o.describe(now) + (s != null ? " 上げる: " + s.label : "")), false);
        }
        return data.organs().size();
    }

    private static int plant(CommandContext<CommandSourceStack> c, int variant, boolean small) {
        OrganKind kind = OrganKind.byId(StringArgumentType.getString(c, "kind"));
        if (kind == null) {
            c.getSource().sendFailure(Component.literal("種類は phase / tower / sac / nursery / ley"));
            return 0;
        }
        ServerLevel level = c.getSource().getServer().overworld();
        int n = small ? OrganShapes.SMALL_VARIANTS : OrganShapes.variants(kind);
        int v = variant < 0 ? level.getRandom().nextInt(n) : Math.min(variant, n - 1);
        var pos = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
        ForestOrgans.Organ o = ForestOrgans.get(level).plant(level, kind, small, v, level.getRandom().nextInt(), level.getRandom().nextInt(4),
                pos.getX(), pos.getZ());
        c.getSource().sendSuccess(() -> Component.literal("芽吹かせた: " + o.describe(PhaseData.clockNow(level))), true);
        return o.id;
    }

    private static int build(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        ForestOrgans data = ForestOrgans.get(level);
        ForestOrgans.Organ o = data.byId(IntegerArgumentType.getInteger(c, "id"));
        if (o == null) return missing(c);
        long t0 = System.nanoTime();
        int n = OrganWorld.buildNow(level, data, o);
        if (o.state == ForestOrgans.State.GROWING) {
            o.state = ForestOrgans.State.ALIVE;
            o.roleAt = PhaseData.clockNow(level);
            OrganWorld.invalidateCaches();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "置いた %d(%d ms): %s", n, ms,
                o.describe(PhaseData.clockNow(level)))), true);
        return n;
    }

    private static int wither(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        ForestOrgans data = ForestOrgans.get(level);
        ForestOrgans.Organ o = data.byId(IntegerArgumentType.getInteger(c, "id"));
        if (o == null) return missing(c);
        ForestOrgans.Penalty p = data.wither(level, o, "コマンド");
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "枯らした(ポイント −%d・予算 −%.0f): %s", p.points(),
                p.budget(), o.describe(PhaseData.clockNow(level)))), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        boolean r = ForestOrgans.get(level).remove(IntegerArgumentType.getInteger(c, "id"));
        if (!r) return missing(c);
        c.getSource().sendSuccess(() -> Component.literal("記録から消した(ブロックは残る)"), true);
        return 1;
    }

    private static int plan(CommandContext<CommandSourceStack> c, OrganKind kind, boolean small) {
        ServerLevel level = c.getSource().getServer().overworld();
        String r = OrganPlanner.tryPlan(level, level.getRandom(), kind, small);
        c.getSource().sendSuccess(() -> Component.literal(r == null ? "建てない(フェーズが早い・数が上限・ピースフル)" : r), true);
        return r == null ? 0 : 1;
    }

    /** 足元のまわり(半径 radius の中、互いに少し離して)に大きい版 big・小さい版 small を芽吹かせる。種類と形はくじ。予算は払わない。 */
    private static int scatter(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        int big = IntegerArgumentType.getInteger(c, "big"), small = IntegerArgumentType.getInteger(c, "small");
        int radius = IntegerArgumentType.getInteger(c, "radius");
        var at = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
        net.minecraft.util.RandomSource r = level.getRandom();
        ForestOrgans data = ForestOrgans.get(level);
        java.util.List<long[]> used = new java.util.ArrayList<>();
        int made = 0;
        for (int i = 0; i < big + small; i++) {
            boolean isSmall = i >= big;
            int minGap = isSmall ? 24 : 96;
            for (int tries = 0; tries < 200; tries++) {
                double ang = r.nextDouble() * Math.PI * 2.0D, d = Math.sqrt(r.nextDouble()) * radius;
                int x = at.getX() + (int) (Math.cos(ang) * d), z = at.getZ() + (int) (Math.sin(ang) * d);
                boolean ok = true;
                for (long[] u : used) {
                    double dx = u[0] - x, dz = u[1] - z, need = Math.max(minGap, u[2] == 1 ? 24 : 96);
                    if (dx * dx + dz * dz < need * need) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                OrganKind kind = OrganKind.values()[r.nextInt(OrganKind.values().length)];
                int v = r.nextInt(isSmall ? OrganShapes.SMALL_VARIANTS : OrganShapes.variants(kind));
                data.plant(level, kind, isSmall, v, r.nextInt(), r.nextInt(4), x, z);
                used.add(new long[]{x, z, isSmall ? 1 : 0});
                made++;
                break;
            }
        }
        int n = made;
        c.getSource().sendSuccess(() -> Component.literal("芽吹かせた " + n), true);
        return n;
    }

    /** 枯れていない器官の育ち具合を percent % にする(芽吹いた時刻を戻す。100 で育ち切る)。 */
    private static int age(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().overworld();
        int pct = IntegerArgumentType.getInteger(c, "percent");
        long now = PhaseData.clockNow(level);
        int n = 0;
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            if (o.state == ForestOrgans.State.WITHERED) continue;
            o.plantedAt = now - (long) (com.yayuy.overgrownvegetation.data.DesignData.organs().growDays(o.small) * 24000.0D * pct / 100.0D);
            n++;
        }
        ForestOrgans.get(level).changed();
        int m = n;
        c.getSource().sendSuccess(() -> Component.literal("育ち具合を " + pct + "% にした: " + m), true);
        return n;
    }

    private static int missing(CommandContext<CommandSourceStack> c) {
        c.getSource().sendFailure(Component.literal("その番号の器官は無い"));
        return 0;
    }
}
