package com.yayuy.overgrownvegetation.corruption.fissure;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.LeyLines;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * 地下の亀裂を確かめるコマンド(2026-11-20)。
 * <ul>
 *     <li>{@code /ov perf fissure list} — 亀裂の一覧</li>
 *     <li>{@code /ov perf fissure plan} — 司令塔の場所選びを 1 回(くじ・予算・上限・フェーズを見ない)</li>
 *     <li>{@code /ov perf fissure here} — 今いる列の地下に開く(洞窟が無ければ言う)</li>
 *     <li>{@code /ov perf fissure grow <id> <hours>} — 時計を進めたことにして根を伸ばす(ゲーム内の時間)</li>
 *     <li>{@code /ov perf fissure tp <id> [mouth]} — 亀裂(か口)へ飛ぶ</li>
 *     <li>{@code /ov perf fissure kill <id>}・{@code remove <id>} — 枯らす・記録から消す(置いたブロックは残る)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FissureCommand {
    private FissureCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("fissure")
                                .then(Commands.literal("list").executes(FissureCommand::list))
                                .then(Commands.literal("plan").executes(FissureCommand::plan))
                                .then(Commands.literal("here").executes(FissureCommand::here))
                                .then(Commands.literal("grow").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.0D, 24.0D * 30.0D)).executes(FissureCommand::grow))))
                                .then(Commands.literal("pend").executes(FissureCommand::pend))
                                .then(Commands.literal("shift").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("hours", DoubleArgumentType.doubleArg(0.0D, 24.0D * 30.0D)).executes(FissureCommand::shift))))
                                .then(Commands.literal("tp").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(c -> tp(c, false))
                                        .then(Commands.literal("mouth").executes(c -> tp(c, true)))
                                        .then(Commands.literal("borer").executes(FissureCommand::tpBorer))))
                                .then(Commands.literal("kill").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(FissureCommand::kill)))
                                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(FissureCommand::remove))))));
    }

    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        return c.getSource().getServer().overworld();
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        FissureData data = FissureData.get(level);
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "亀裂 %d 個(生きている %d)・覚えた汲み上げ器 %d",
                data.fissures().size(), data.aliveCount(), data.pumps().size())), false);
        for (FissureData.Fissure f : data.fissures()) c.getSource().sendSuccess(() -> Component.literal(f.describe()), false);
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "重さ: 働いた tick %d・平均 %.3f ms・最大 %.2f ms・洞窟探し 最後 %.1f ms・最大 %.1f ms(床を集める %.1f ms・道を探す 1 回 %.1f ms・巣を置く %.1f ms)",
                FissureWorld.statTicks, FissureWorld.statTicks == 0 ? 0.0D : FissureWorld.statTickSumNs / 1e6 / FissureWorld.statTicks, FissureWorld.statTickMaxNs / 1e6,
                FissureWorld.statResolveLastNs / 1e6, FissureWorld.statResolveMaxNs / 1e6, FissureWorld.statScanMaxNs / 1e6, FissureWorld.statSearchMaxNs / 1e6, FissureWorld.statOpenNs / 1e6)), false);
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "台帳: マス %d・見立て %d・前線の計算 最大 %.2f ms・測る 最大 %.2f ms(1 ms 超 %d 回・10 ms 超 %d 回)・追いつき 最大 %.2f ms",
                data.ledger().size(), data.knownCount(), FissureWorld.statUpdateMaxNs / 1e6, FissureWorld.statMeasureMaxNs / 1e6, FissureWorld.statMeasureOver1, FissureWorld.statMeasureOver10, FissureWorld.statApplyMaxNs / 1e6)), false);
        return data.fissures().size();
    }

    private static int plan(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        String r = FissurePlanner.tryPlan(level, level.getRandom(), 99, true);
        c.getSource().sendSuccess(() -> Component.literal(r == null ? "見送った" : r), false);
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        BlockPos p = BlockPos.containing(c.getSource().getPosition());
        FissureData data = FissureData.get(level);
        // 今いるチャンクから外へ 4 チャンクまで。根の道が洞窟の口に届く所を先に(撮影用)
        BlockPos floor = null, fallback = null;
        int pcx = p.getX() >> 4, pcz = p.getZ() >> 4;
        search:
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (!FissureWorld.neighbourhoodReady(level, pcx + dx, pcz + dz)) continue;
                    BlockPos fl = FissureWorld.findCaveFloor(level, pcx + dx, pcz + dz, level.getRandom().nextInt());
                    if (fl == null) continue;
                    if (fallback == null) fallback = fl;
                    FissureData.Fissure tmp = new FissureData.Fissure(0, fl.getX(), fl.getZ(), 0, 0L);
                    tmp.floor = fl;
                    FissureWorld.buildRoute(level, tmp);
                    if (tmp.routeHitsMouth) {
                        floor = fl;
                        break search;
                    }
                }
            }
        }
        if (floor == null) floor = fallback;
        if (floor == null) {
            double d = LeyLines.distanceToLine(level.getSeed(), p.getX(), p.getZ());
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "このチャンクに開ける洞窟が無い(地脈の線まで %.0f。/ov perf ley near で線の近くへ)", d)));
            return 0;
        }
        FissureData.Fissure f = data.add(floor.getX(), floor.getZ(), level.getRandom().nextInt(), PhaseData.clockNow(level), 0.0D);
        FissureWorld.open(level, data, f, floor);
        c.getSource().sendSuccess(() -> Component.literal("開いた: " + f.describe()), false);
        return 1;
    }

    private static int grow(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        FissureData data = FissureData.get(level);
        FissureData.Fissure f = data.byId(IntegerArgumentType.getInteger(c, "id"));
        if (f == null || f.state != FissureData.State.OPEN) {
            c.getSource().sendFailure(Component.literal("開いている亀裂ではない"));
            return 0;
        }
        long ticks = (long) (DoubleArgumentType.getDouble(c, "hours") * 1000.0D);
        data.shiftBack(f, ticks);
        long now = PhaseData.clockNow(level);
        int n = 0;
        for (int i = 0; i < 400; i++) {
            int a = FissureWorld.grow(level, data, f, now, 64) + FissureLedger.update(level, data, f, now);
            int b = f.reachedMouth() ? FissureWorld.overflow(level, data, f, now, 64) : 0;
            n += a + b;
            if (a + b == 0) break;
        }
        data.changed();
        int total = n;
        c.getSource().sendSuccess(() -> Component.literal("進めた(" + total + "): " + f.describe()), false);
        return 1;
    }

    /** 今いる列に洞窟待ちの亀裂を置く(毎 tick の働きが洞窟を探して開く。重さを測る)。 */
    private static int pend(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        BlockPos p = BlockPos.containing(c.getSource().getPosition());
        FissureData.Fissure f = FissureData.get(level).add(p.getX(), p.getZ(), level.getRandom().nextInt(), PhaseData.clockNow(level), 0.0D);
        c.getSource().sendSuccess(() -> Component.literal("洞窟待ち: " + f.describe()), false);
        return 1;
    }

    /** 時計だけずらす(根は毎 tick の働きが追いつく。重さを測る)。 */
    private static int shift(CommandContext<CommandSourceStack> c) {
        FissureData data = FissureData.get(overworld(c));
        FissureData.Fissure f = data.byId(IntegerArgumentType.getInteger(c, "id"));
        if (f == null) return 0;
        long ticks = (long) (DoubleArgumentType.getDouble(c, "hours") * 1000.0D);
        data.shiftBack(f, ticks);
        FissureWorld.statTickMaxNs = 0L;
        FissureWorld.statTickSumNs = 0L;
        FissureWorld.statTicks = 0L;
        FissureWorld.statResolveMaxNs = 0L;
        FissureWorld.statScanMaxNs = 0L;
        FissureWorld.statUpdateMaxNs = 0L;
        FissureWorld.statMeasureMaxNs = 0L;
        FissureWorld.statApplyMaxNs = 0L;
        FissureWorld.statMeasureOver10 = 0L;
        FissureWorld.statMeasureOver1 = 0L;
        data.changed();
        c.getSource().sendSuccess(() -> Component.literal("ずらした: " + f.describe()), false);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> c, boolean mouth) {
        ServerLevel level = overworld(c);
        FissureData.Fissure f = FissureData.get(level).byId(IntegerArgumentType.getInteger(c, "id"));
        BlockPos to = f == null ? null : mouth ? f.mouth() : f.floor;
        if (to == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) {
            c.getSource().sendFailure(Component.literal("行き先が無い"));
            return 0;
        }
        p.teleportTo(level, to.getX() + 0.5D, to.getY() + (mouth ? 1.0D : 0.0D), to.getZ() + 0.5D, p.getYRot(), p.getXRot());
        return 1;
    }

    /** 根を送り込む器官の芯の横へ飛ぶ(形になっていれば)。 */
    private static int tpBorer(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        FissureData.Fissure f = FissureData.get(level).byId(IntegerArgumentType.getInteger(c, "id"));
        if (f == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        for (FissureData.Borer b : f.borers) {
            if (b.core == null) continue;
            p.teleportTo(level, b.core.getX() + 3.5D, b.core.getY() + 3.0D, b.core.getZ() + 3.5D, 135.0F, 35.0F);
            c.getSource().sendSuccess(() -> Component.literal("送り込む器官の芯 " + b.core.toShortString() + (b.alive ? "" : "(枯れた)") + (b.done ? "(役目を終えた)" : "")), false);
            return 1;
        }
        c.getSource().sendFailure(Component.literal("形になった送り込む器官が無い"));
        return 0;
    }

    private static int kill(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        FissureData data = FissureData.get(level);
        FissureData.Fissure f = data.byId(IntegerArgumentType.getInteger(c, "id"));
        if (f == null) return 0;
        FissureWorld.kill(level, data, f, "コマンド");
        c.getSource().sendSuccess(() -> Component.literal("枯らした: " + f.describe()), false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        boolean r = FissureData.get(level).remove(IntegerArgumentType.getInteger(c, "id"));
        c.getSource().sendSuccess(() -> Component.literal(r ? "消した" : "無い"), false);
        return r ? 1 : 0;
    }
}
