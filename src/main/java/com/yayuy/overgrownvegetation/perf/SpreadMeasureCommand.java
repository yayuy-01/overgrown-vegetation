package com.yayuy.overgrownvegetation.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /ov perf spread [cx cz]}(2026-11-14、汚染の広がりの速さの計測用。OP)。広がりの元(既定は実行した所のチャンク)から見た
 * 台帳(フェーズに数える過繁茂のチャンク)の広がりを 1 行で出す。
 *
 * <ul>
 *     <li>{@code ledger} = 台帳のチャンク数(フェーズの時間ポイントに効く数)。{@code main} = 元から 4 方向につながった本体、
 *     {@code enclaves} = それ以外(嵐の飛び地など)。</li>
 *     <li>{@code sec} = 本体の、元から 8 方位(東から時計回り。0 = 東・2 = 南)それぞれの扇(±22.5 度)でいちばん遠いチャンクまでの距離(チャンク)。</li>
 *     <li>{@code wind} = 今の卓越風の吹いていく向き(度。0 = 東へ・90 = 南へ)。風下 = その向きの扇、風上 = 反対。</li>
 *     <li>{@code cum} = F1 からの累計ポイント(越えたフェーズの {@code pointsToAdvance} の和 + 今のポイント)。</li>
 *     <li>{@code overgrown} = フェーズが数える過繁茂の累計(時間ポイントの式が読む数)。{@code dump} で台帳の全チャンクと載った時刻をファイルへ。</li>
 *     <li>{@code lag} = 胞子の場の暦の遅れ(tick。早送りで場の計算が追いついているかの確かめ)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SpreadMeasureCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ov")
                .then(Commands.literal("perf")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spread")
                                .executes(ctx -> {
                                    BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
                                    return measure(ctx, p.getX() >> 4, p.getZ() >> 4);
                                })
                                // 台帳の全チャンクと載った時刻を <ゲームフォルダ>/ov-ledger-<gt>.tsv へ(あとで日ごとの広がりを組み直す)
                                .then(Commands.literal("dump").executes(SpreadMeasureCommand::dump))
                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                .executes(ctx -> measure(ctx, IntegerArgumentType.getInteger(ctx, "cx"),
                                                        IntegerArgumentType.getInteger(ctx, "cz"))))))));
    }

    private static int measure(CommandContext<CommandSourceStack> ctx, int ox, int oz) {
        ServerLevel level = ctx.getSource().getServer().overworld();
        PhaseData pd = PhaseData.get(level);
        Long2LongOpenHashMap ledger = pd.snapshotLedger();
        // 本体: 元(か、元が台帳に無ければいちばん近い台帳のチャンク)から 4 方向につながったもの
        long start = ChunkPos.asLong(ox, oz);
        if (!ledger.containsKey(start)) {
            long best = Long.MIN_VALUE;
            long bestD = Long.MAX_VALUE;
            for (long k : ledger.keySet()) {
                long dx = ChunkPos.getX(k) - ox, dz = ChunkPos.getZ(k) - oz;
                long d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            start = best;
        }
        LongOpenHashSet main = new LongOpenHashSet();
        if (start != Long.MIN_VALUE) {
            LongArrayFIFOQueue q = new LongArrayFIFOQueue();
            q.enqueue(start);
            main.add(start);
            while (!q.isEmpty()) {
                long k = q.dequeueLong();
                int x = ChunkPos.getX(k), z = ChunkPos.getZ(k);
                long[] n = {ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1)};
                for (long m : n) {
                    if (ledger.containsKey(m) && main.add(m)) q.enqueue(m);
                }
            }
        }
        double[] sec = new double[8];
        for (long k : main) {
            double dx = ChunkPos.getX(k) - ox, dz = ChunkPos.getZ(k) - oz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d == 0) continue;
            double a = Math.toDegrees(Math.atan2(dz, dx));
            if (a < 0) a += 360;
            int s = (int) Math.round(a / 45.0D) & 7;
            sec[s] = Math.max(sec[s], d);
        }
        // 累計ポイント
        PhaseTable table = DesignData.phases();
        long cum = pd.getPoints();
        for (int n = 2; n <= pd.getPhase(); n++) {
            long need = table.pointsToAdvance(n);
            if (need != Long.MAX_VALUE) cum += need;
        }
        PrevailingWind.Sample w = SporeFieldServer.windNow(level);
        String kind = w == null ? "-" : w.storm ? "storm" : w.strongDay ? "strong" : level.isRaining() ? "rain" : "clear";
        long clock = pd.clock(level.getServer());
        long lag = SporeFieldServer.fieldClockForTests() == Long.MIN_VALUE ? -1 : clock - SporeFieldServer.fieldClockForTests();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SPREAD gt=%d clock=%d day=%.3f phase=%d points=%d cum=%d ledger=%d overgrown=%d main=%d enclaves=%d meanR=%.2f",
                level.getGameTime(), clock, clock / 24000.0D, pd.getPhase(), pd.getPoints(), cum, ledger.size(), pd.getOvergrownChunks(), main.size(),
                ledger.size() - main.size(), Math.sqrt(main.size() / Math.PI)));
        sb.append(" sec=");
        for (int i = 0; i < 8; i++) sb.append(i == 0 ? "" : ",").append(String.format("%.1f", sec[i]));
        sb.append(String.format(" wind=%.0f kind=%s str=%.2f lag=%d mult=%.2f unloaded=%s thunder=%s",
                w == null ? Double.NaN : w.angleDeg(), kind, w == null ? Double.NaN : w.strength, lag,
                WorldDifficultyData.phasePointMultiplier(level.getServer()), WorldDifficultyData.unloadedSpread(level.getServer()),
                level.isThundering()));
        String line = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        if (level.dimension() == Level.OVERWORLD) {
            List<String> d = SporeFieldServer.describe(level, BlockPos.containing(ctx.getSource().getPosition()));
            if (!d.isEmpty()) {
                String last = d.get(d.size() - 1);
                ctx.getSource().sendSuccess(() -> Component.literal("SPREADFIELD " + last), false);
            }
        }
        return ledger.size();
    }

    private static int dump(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().overworld();
        Long2LongOpenHashMap ledger = PhaseData.get(level).snapshotLedger();
        java.nio.file.Path out = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("ov-ledger-" + level.getGameTime() + ".tsv");
        StringBuilder sb = new StringBuilder("cx\tcz\tsince\n");
        for (Long2LongOpenHashMap.Entry e : ledger.long2LongEntrySet()) {
            sb.append(ChunkPos.getX(e.getLongKey())).append('\t').append(ChunkPos.getZ(e.getLongKey())).append('\t').append(e.getLongValue()).append('\n');
        }
        try {
            java.nio.file.Files.writeString(out, sb.toString());
        } catch (java.io.IOException ex) {
            ctx.getSource().sendFailure(Component.literal("書けない: " + ex));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("SPREADDUMP " + ledger.size() + " " + out), false);
        return ledger.size();
    }

    private SpreadMeasureCommand() {
    }
}
