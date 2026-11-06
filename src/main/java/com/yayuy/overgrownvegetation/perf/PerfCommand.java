package com.yayuy.overgrownvegetation.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.EndOvergrowth;
import com.yayuy.overgrownvegetation.corruption.LedgerSpread;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue;
import com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /ov perf …} —— いまの重さを見る(OP、2026-10-06、ユーザー指定の ①「計測の常設」)。
 * <ul>
 *     <li>(引数なし)… 直近 5 秒の 1 tick の重さ、予算の番人の状態、mod の周期処理ごとの時間、待ち行列、エンティティの数</li>
 *     <li>{@code record [秒]} … JFR で記録して、重い処理・エンティティ・同期読み込みの犯人を集計する({@link PerfRecorder})</li>
 * </ul>
 * 出力は開発・調査用なので {@code Component.literal}(翻訳キーを作らない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PerfCommand {

    /** {@code record} の既定の長さ(秒)。 */
    static final int DEFAULT_RECORD_SECONDS = 30;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .executes(PerfCommand::status)
                        .then(Commands.literal("bench").executes(PerfCommand::bench))
                        // 胞子の場(2026-10-23 胞子の場と風 第 2 段): 足元のチャンクの浮遊・林床・降り積もり・風の向きと強さ・日の種類
                        .then(Commands.literal("spore").executes(PerfCommand::spore)
                                // 開発用(2026-10-24): 足元のまわり 3×3 チャンクの降り積もりを発芽のしきい値の percent % にする(粉の見た目の確かめ)
                                .then(Commands.literal("deposit").then(Commands.argument("percent", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0D, 10000.0D))
                                        .executes(ctx -> {
                                            double pct = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "percent");
                                            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
                                            com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.setDepositForCommand(ctx.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, pct);
                                            ctx.getSource().sendSuccess(() -> Component.literal("降り積もり " + pct + "%(次の場の更新で)"), false);
                                            return 1;
                                        })))
                                // 暴露量(2026-11-04 感染システムの第 1 段): 自分とまわり 32 ブロックのバニラの Mob の暴露量 / 0 に戻す / 自分の値を決める
                                .then(Commands.literal("exposure").executes(PerfCommand::exposure)
                                        .then(Commands.literal("reset").executes(ctx -> {
                                            int n = forExposureTargets(ctx.getSource(), e -> com.yayuy.overgrownvegetation.spore.SporeExposureTracker.setDose(e, 0.0D, 0L));
                                            ctx.getSource().sendSuccess(() -> Component.literal("暴露量を 0 に戻した: " + n + " 体(自分とまわり 32 ブロック)"), false);
                                            return n;
                                        }))
                                        .then(Commands.literal("set").then(Commands.argument("dose", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.0D, 100000.0D))
                                                .executes(ctx -> {
                                                    double dose = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "dose");
                                                    net.minecraft.world.entity.Entity self = ctx.getSource().getEntity();
                                                    if (!(self instanceof net.minecraft.world.entity.LivingEntity living)) {
                                                        ctx.getSource().sendFailure(Component.literal("生き物として実行して"));
                                                        return 0;
                                                    }
                                                    com.yayuy.overgrownvegetation.spore.SporeExposureTracker.setDose(living, dose, ctx.getSource().getLevel().getGameTime());
                                                    return exposure(ctx);
                                                })))))
                        .then(Commands.literal("fluid").executes(PerfCommand::fluid)
                                .then(Commands.literal("on").executes(ctx -> {
                                    FluidSpreadShortcut.setEnabled(true);
                                    return fluid(ctx);
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    FluidSpreadShortcut.setEnabled(false);
                                    return fluid(ctx);
                                }))
                                .then(Commands.literal("check").executes(ctx -> {
                                    FluidSpreadShortcut.setChecking(!FluidSpreadShortcut.checking());
                                    return fluid(ctx);
                                }))
                                .then(Commands.literal("reset").executes(ctx -> {
                                    FluidSpreadShortcut.resetCounts();
                                    return fluid(ctx);
                                })))
                        .then(Commands.literal("record")
                                .executes(ctx -> record(ctx, DEFAULT_RECORD_SECONDS))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 300))
                                        .executes(ctx -> record(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))));
    }

    private static int spore(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(source.getPosition());
        // ネザーにも自分の場がある(2026-10-23 F)。エンドは場に載らない
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD && level.dimension() != net.minecraft.world.level.Level.NETHER) {
            source.sendSuccess(() -> Component.literal("胞子の場はオーバーワールドとネザーだけ"), false);
            return 0;
        }
        for (String line : com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.describe(level, pos)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 自分(生き物なら)と、まわり 32 ブロックの暴露量を測るバニラの Mob に {@code action}。数を返す。 */
    private static int forExposureTargets(CommandSourceStack source, java.util.function.Consumer<net.minecraft.world.entity.LivingEntity> action) {
        int n = 0;
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity self) {
            action.accept(self);
            n++;
        }
        net.minecraft.world.phys.AABB box = net.minecraft.world.phys.AABB.ofSize(source.getPosition(), 64, 64, 64);
        for (net.minecraft.world.entity.LivingEntity e : source.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                com.yayuy.overgrownvegetation.spore.SporeExposureTracker::isMeasuredMob)) {
            action.accept(e);
            n++;
        }
        return n;
    }

    private static int exposure(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        com.yayuy.overgrownvegetation.data.SporeFieldTable t = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        double here = com.yayuy.overgrownvegetation.spore.SporeConcentration.percentAt(level, net.minecraft.core.BlockPos.containing(source.getPosition()));
        source.sendSuccess(() -> Component.literal(String.format("暴露量: 感染の量 %.0f %%秒・%.0f%% 未満は積まない・半減期 %.0f 秒(今ここの濃度 %.1f%%)",
                t.infectDose(), t.exposureMinPercent(), t.exposureHalfLifeSeconds(), here)), false);
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity self) {
            source.sendSuccess(() -> Component.literal(describeExposure("自分", self, t)), false);
        }
        net.minecraft.world.phys.AABB box = net.minecraft.world.phys.AABB.ofSize(source.getPosition(), 64, 64, 64);
        java.util.List<net.minecraft.world.entity.LivingEntity> mobs = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                com.yayuy.overgrownvegetation.spore.SporeExposureTracker::isMeasuredMob);
        int exposed = 0, reached = 0;
        net.minecraft.world.entity.LivingEntity top = null;
        for (net.minecraft.world.entity.LivingEntity e : mobs) {
            double d = com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(e);
            if (d > 0.0D) exposed++;
            if (e.hasData(com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_EXPOSURE)
                    && e.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_EXPOSURE).reachedCount > 0) reached++;
            if (top == null || d > com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(top)) top = e;
        }
        int fExposed = exposed, fReached = reached;
        source.sendSuccess(() -> Component.literal("まわり 32 ブロックの Mob: " + mobs.size() + " 体・暴露あり " + fExposed + "・感染の量に届いたことがある " + fReached
                + "(届くたびに防御無視の 20 ダメージ)"), false);
        if (top != null && com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(top) > 0.0D) {
            net.minecraft.world.entity.LivingEntity fTop = top;
            source.sendSuccess(() -> Component.literal(describeExposure("いちばん多い " + fTop.getType().getDescription().getString(), fTop, t)), false);
        }
        return 1;
    }

    private static String describeExposure(String who, net.minecraft.world.entity.LivingEntity e, com.yayuy.overgrownvegetation.data.SporeFieldTable t) {
        double d = com.yayuy.overgrownvegetation.spore.SporeExposureTracker.dose(e);
        int reached = e.hasData(com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_EXPOSURE)
                ? e.getData(com.yayuy.overgrownvegetation.registry.ModAttachments.SPORE_EXPOSURE).reachedCount : 0;
        return String.format("%s: %.0f / %.0f %%秒(%.0f%%)・届いた回数 %d", who, d, t.infectDose(), d / t.infectDose() * 100.0D, reached);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        long[] times = server.getTickTimesNanos();
        int count = Math.min(TickBudget.WINDOW, Math.max(0, server.getTickCount() - 1));
        long[] recent = new long[count];
        for (int i = 0; i < count; i++) {
            recent[i] = times[Math.floorMod(server.getTickCount() - 1 - i, TickBudget.WINDOW)];
        }
        long[] sorted = recent.clone();
        Arrays.sort(sorted);
        double avg = count == 0 ? 0.0D : Arrays.stream(recent).average().orElse(0.0D) / 1e6;
        double p95 = count == 0 ? 0.0D : sorted[Math.min(count - 1, (int) (count * 0.95D))] / 1e6;
        double max = count == 0 ? 0.0D : sorted[count - 1] / 1e6;
        double tps = Math.min(server.tickRateManager().tickrate(), avg <= 0.0D ? 20.0D : 1000.0D / avg);
        send(source, String.format(Locale.ROOT, "§a[性能]§r 直近 %d tick の 1 tick: 平均 %.1f ms / P95 %.1f ms / 最大 %.1f ms → TPS %.1f",
                count, avg, p95, max, tps));

        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (TickBudget.active()) {
            send(source, String.format(Locale.ROOT, "  予算の番人: 目標 %.0f ms − ほかの処理 %.1f ms = 後回しにできる処理の予算 %s",
                    cfg.tickBudgetTargetMspt.get(), TickBudget.baseNanos() / 1e6,
                    TickBudget.headroomNanos() > TickBudget.MIN_CAP_NANOS
                            ? String.format(Locale.ROOT, "%.1f ms", TickBudget.headroomNanos() / 1e6)
                            : "§c無し(最小 0.5 ms と、2 秒に 1 回の 1 単位だけ)§r"));
        } else {
            send(source, "  予算の番人: " + (cfg.tickBudgetEnabled.get() ? "GameTest のサーバーでは働かない" : "§7切ってある(tickBudgetEnabled)§r"));
        }

        send(source, String.format(Locale.ROOT, "  mod の周期処理(1 tick あたり 平均 / 最大、直近 %d tick の仕事の数、番人が止めた回数): 合計 %.2f ms",
                TickBudget.WINDOW, TickBudget.averageDeferrableNanos() / 1e6));
        boolean any = false;
        for (PerfJob job : PerfJob.values()) {
            double avgJob = TickBudget.averageNanos(job);
            int held = TickBudget.held(job);
            if (avgJob <= 0.0D && held == 0) {
                continue;
            }
            any = true;
            send(source, String.format(Locale.ROOT, "    %s  %.2f / %.1f ms  仕事 %,d%s", job.label, avgJob / 1e6,
                    TickBudget.maxNanos(job) / 1e6, TickBudget.units(job), held > 0 ? "  §e止めた " + held + "§r" : ""));
        }
        if (!any) {
            send(source, "    (直近 5 秒は何も動いていない)");
        }
        StringBuilder background = new StringBuilder("  別スレッド(前回の計算時間・始めてから当てはめるまでの tick・回数。サーバーの 1 tick には乗らない):");
        for (BackgroundTask<?> task : BackgroundTask.all()) {
            if (task.runs() > 0) {
                background.append(String.format(Locale.ROOT, " %s %.1f ms・%d tick・%d 回 /", task.label, task.lastNanos() / 1e6,
                        task.lastWaitTicks(), task.runs()));
            }
        }
        send(source, background.toString());
        fluidLine(source);

        LedgerSpreadData ledger = LedgerSpreadData.get(server);
        ServerLevel overworld = server.overworld();

        int all = 0;
        int modMobs = 0;
        int otherMobs = 0;
        int items = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                all++;
                if (entity instanceof ItemEntity) {
                    items++;
                } else if (entity instanceof Mob) {
                }
            }
        }
        send(source, String.format(Locale.ROOT, "  エンティティ %,d(この mod の Mob %,d / ほかの Mob %,d / 落ちているアイテム %,d)/ 遠くの Mob の思考の間引き %s(直前の tick に %d 体)",
                all, modMobs, otherMobs, items,
                cfg.mobThinkThrottleEnabled.get() ? cfg.mobThinkThrottleDistance.get() + " ブロックより遠く・"
                        + cfg.mobThinkThrottleInterval.get() + " tick に 1 回" : "§7切ってある§r",
                MobThinkThrottle.heldLastTick()));
        if (PerfRecorder.recording()) {
            send(source, "  §e記録中(残り " + PerfRecorder.secondsLeft() + " 秒)§r");
        } else {
            send(source, "  §7どの処理が重いかを詳しく見る: /ov perf record [秒]§r");
        }
        return 1;
    }

    /**
     * {@code /ov perf bench} —— 台帳全体を舐める計算を、いまの台帳で 1 回ずつサーバースレッドで回して時間を出す(2026-10-06、④の効果を見る)。
     * 常駐の処理ではこれらは別スレッドで走り、サーバースレッドに残るのは写しを作る時間だけ。世界は変えない(極相の判定も印を付けない)。
     */
    private static int bench(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel overworld = source.getServer().overworld();
        PhaseData phase = PhaseData.get(overworld);
        long t0 = System.nanoTime();
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap snapshot = phase.snapshotLedger();
        long tSnapshot = System.nanoTime() - t0;
        t0 = System.nanoTime();
        int frontier = LedgerSpread.frontier(snapshot).size();
        long tFrontier = System.nanoTime() - t0;
        t0 = System.nanoTime();
        long tIncome = System.nanoTime() - t0;
        return 1;
    }

    private static int record(CommandContext<CommandSourceStack> ctx, int seconds) {
        CommandSourceStack source = ctx.getSource();
        String error = PerfRecorder.start(source, seconds);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        send(source, "§a[性能]§r " + seconds + " 秒記録します。重い場面をそのまま続けてください(終わったら集計を出します)。");
        return 1;
    }

    /**
     * {@code /ov perf fluid [on|off|check|reset]} —— 水・溶岩の広がりの近道({@link FluidSpreadShortcut}、2026-10-06 夜)。
     * {@code on/off} は保存しない(次の起動で on に戻る)。{@code check} は検算の切り替え(省く判定のマスでも本来の計算をして確かめる)。
     */
    private static int fluid(CommandContext<CommandSourceStack> ctx) {
        fluidLine(ctx.getSource());
        return 1;
    }

    private static void fluidLine(CommandSourceStack source) {
        long seen = FluidSpreadShortcut.neighbours();
        send(source, String.format(Locale.ROOT, "  水の広がりの近道: %s%s / 横のマス %,d 中 計算を省いた %,d(%.0f%%)%s",
                FluidSpreadShortcut.enabled() ? "有効" : "§7切ってある§r",
                FluidSpreadShortcut.checking() ? "・検算中" : "",
                seen, FluidSpreadShortcut.skipped(), seen == 0 ? 0.0D : 100.0D * FluidSpreadShortcut.skipped() / seen,
                FluidSpreadShortcut.checked() > 0 ? String.format(Locale.ROOT, " / 検算 %,d 件・食い違い %d",
                        FluidSpreadShortcut.checked(), FluidSpreadShortcut.mismatches()) : ""));
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private PerfCommand() {
    }
}
