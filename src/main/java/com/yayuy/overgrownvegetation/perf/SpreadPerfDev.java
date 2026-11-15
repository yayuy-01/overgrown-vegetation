package com.yayuy.overgrownvegetation.perf;

import com.mojang.brigadier.context.CommandContext;
import com.yayuy.overgrownvegetation.corruption.LedgerSpreadData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.DevStats;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 胞子の場の重さの計測(2026-11-14。{@code /ov perf spread fill|stat|savetest}。開発用)。
 * 汚染が 100 万〜530 万チャンクまで広がったときの場の計算・保存・メモリを測るために、台帳と場を一気に広げる。
 */
final class SpreadPerfDev {

    /** 円の中の穴の割合。0 = 穴なし(穴があると区画が「本体の奥」にならず、本物より重く出る)。 */
    static final double HOLES = 0.0;

    private SpreadPerfDev() {
    }

    /**
     * 実行した所を中心に、台帳と場を約 {@code target} チャンクにする。形は本物に寄せる: 縁をでこぼこにした円(3・7・13 山の波)、
     * 中に 2% の穴(発芽していない升)、外側に飛び地(半径 3〜9 の塊)を面積の約 1.5%。同じ中心で大きい数を呼び直すと外へ足すだけ。
     * 載った時刻は 3 日前(過繁茂の放出になる)。
     */
    static int fill(CommandContext<CommandSourceStack> ctx, int target) {
        ServerLevel level = ctx.getSource().getServer().overworld();
        BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
        int ox = p.getX() >> 4, oz = p.getZ() >> 4;
        PhaseData pd = PhaseData.get(level);
        long since = pd.clock(level.getServer()) - 3L * PrevailingWind.DAY;
        double r0 = Math.sqrt(target / (Math.PI * (1 - HOLES) * 1.016));
        int rMax = (int) Math.ceil(r0 * 1.25) + 1;
        LongArrayList keys = new LongArrayList(target + target / 8);
        for (int dz = -rMax; dz <= rMax; dz++) {
            for (int dx = -rMax; dx <= rMax; dx++) {
                double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                double a = Math.atan2(dz, dx);
                double edge = r0 * (1 + 0.10 * Math.sin(3 * a + 0.7) + 0.05 * Math.sin(7 * a + 2.1) + 0.03 * Math.sin(13 * a));
                if (d > edge) continue;
                long cell = ((long) (ox + dx) << 32) ^ ((oz + dz) & 0xFFFFFFFFL);
                if (HOLES > 0 && PrevailingWind.unit(PrevailingWind.hash(0x5EEDF111L, cell)) < HOLES) continue;
                keys.add(ChunkPos.asLong(ox + dx, oz + dz));
            }
        }
        // 飛び地: 縁の外 1.15〜1.35 倍の輪に、面積の約 1.5% ぶん
        int enclaveCells = (int) (target * 0.015);
        int n = 0;
        for (int j = 0; n < enclaveCells && j < 100000; j++) {
            double a = 2 * Math.PI * PrevailingWind.unit(PrevailingWind.hash(0xE1C1A7EL, j * 3L));
            double d = r0 * (1.15 + 0.2 * PrevailingWind.unit(PrevailingWind.hash(0xE1C1A7EL, j * 3L + 1)));
            int br = 3 + (int) (6 * PrevailingWind.unit(PrevailingWind.hash(0xE1C1A7EL, j * 3L + 2)));
            int bx = ox + (int) (Math.cos(a) * d), bz = oz + (int) (Math.sin(a) * d);
            for (int dz = -br; dz <= br; dz++) {
                for (int dx = -br; dx <= br; dx++) {
                    if (dx * dx + dz * dz <= br * br) {
                        keys.add(ChunkPos.asLong(bx + dx, bz + dz));
                        n++;
                    }
                }
            }
        }
        long[] arr = keys.toLongArray();
        long t0 = System.nanoTime();
        int added = pd.devBulkMark(arr, since);
        SporeFieldServer.devBulkCorrupt(arr, since);
        String line = String.format("SPREADFILL target=%d r0=%.0f keys=%d added=%d ledger=%d ms=%.0f center=%d,%d",
                target, r0, arr.length, added, pd.overgrownLedgerSize(), (System.nanoTime() - t0) / 1e6, ox, oz);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return added;
    }

    static int stat(CommandContext<CommandSourceStack> ctx, boolean reset, boolean keep) {
        if (reset && keep) {
            com.yayuy.overgrownvegetation.spore.field.SporeField.devKeepEmptyTiles = !com.yayuy.overgrownvegetation.spore.field.SporeField.devKeepEmptyTiles;
            boolean k = com.yayuy.overgrownvegetation.spore.field.SporeField.devKeepEmptyTiles;
            ctx.getSource().sendSuccess(() -> Component.literal("SPREADSTAT keepEmptyTiles=" + k), false);
            return 1;
        }
        if (reset) {
            DevStats.reset();
            ctx.getSource().sendSuccess(() -> Component.literal("SPREADSTAT reset"), false);
            return 1;
        }
        ServerLevel level = ctx.getSource().getServer().overworld();
        PhaseData pd = PhaseData.get(level);
        long clock = pd.clock(level.getServer());
        long fc = SporeFieldServer.fieldClockForTests();
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long used = rt.totalMemory() - rt.freeMemory();
        long runs = Math.max(1, DevStats.runs);
        Path data = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        long[] dir = dirSize(data.resolve("overgrown_vegetation_spore_field"));
        String a = String.format("SPREADSTAT ledger=%d phase=%d lag=%d tiles=%d parcels=%d pending=%d | field(bg) runs=%d steps=%d avg=%.1fms max=%.1fms last=%.1fms perStep=%.1fms"
                        + " | serverThread gather avg=%.3fms max=%.3fms apply avg=%.3fms max=%.3fms | load=%.0fms | " + SporeFieldServer.devLive() + " | " + SporeFieldServer.devShape(),
                pd.overgrownLedgerSize(), pd.getPhase(), fc == Long.MIN_VALUE ? -1 : clock - fc, DevStats.lastTiles, DevStats.lastParcels,
                LedgerSpreadData.get(level.getServer()).pendingCount(),
                DevStats.runs, DevStats.steps, DevStats.computeNanos / 1e6 / runs, DevStats.computeMaxNanos / 1e6, DevStats.lastComputeNanos / 1e6,
                DevStats.computeNanos / 1e6 / Math.max(1, DevStats.steps),
                DevStats.gatherNanos / 1e6 / runs, DevStats.gatherMaxNanos / 1e6, DevStats.applyNanos / 1e6 / runs, DevStats.applyMaxNanos / 1e6,
                DevStats.loadNanos / 1e6);
        long saves = Math.max(1, DevStats.saves + DevStats.savesSkipped);
        StringBuilder files = new StringBuilder();
        try (Stream<Path> s = Files.list(data)) {
            s.filter(f -> f.getFileName().toString().startsWith("overgrown_vegetation") && Files.isRegularFile(f))
                    .forEach(f -> files.append(' ').append(f.getFileName()).append('=').append(String.format("%.2fMB", fileSize(f) / 1048576.0)));
        } catch (IOException ignored) {
        }
        String b = String.format("SPREADSAVE fieldSave saves=%d skipped=%d lockWait avg=%.1fms max=%.1fms snapshot(serverThread) avg=%.1fms max=%.1fms tiles=%d changed=%d write(IO)=%.0fms"
                        + " dir=%d files %.1fMB | heap used=%dMB max=%dMB |%s",
                DevStats.saves, DevStats.savesSkipped, DevStats.saveLockNanos / 1e6 / saves, DevStats.saveLockMaxNanos / 1e6,
                DevStats.saveSnapNanos / 1e6 / Math.max(1, DevStats.saves), DevStats.saveSnapMaxNanos / 1e6,
                DevStats.lastSaveTiles, DevStats.lastSaveChanged, DevStats.lastWriteNanos / 1e6,
                dir[0], dir[1] / 1048576.0, used >> 20, rt.maxMemory() >> 20, files);
        ctx.getSource().sendSuccess(() -> Component.literal(a), false);
        ctx.getSource().sendSuccess(() -> Component.literal(b), false);
        return 1;
    }

    /** 台帳(PhaseData)の保存の形を作り、圧縮して仮のファイルへ書く時間と大きさ(バニラの保存がサーバースレッドでやる仕事と同じ)。 */
    /** 計測用(軽量化 D): 深い奥の区画を何回の更新に 1 回進めるかを、この起動の間だけ変える(1 = 切)。 */
    static int deep(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, int every) {
        SporeFieldServer.devDeepEvery(every);
        ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("SPREADDEEP every=" + every), false);
        return 1;
    }

    public static int saveTest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().overworld();
        PhaseData pd = PhaseData.get(level);
        long t0 = System.nanoTime();
        CompoundTag tag = pd.save(new CompoundTag(), level.registryAccess());
        long t1 = System.nanoTime();
        long bytes = -1;
        try {
            Path tmp = Files.createTempFile("ov-phase-savetest", ".dat");
            CompoundTag root = new CompoundTag();
            root.put("data", tag);
            NbtIo.writeCompressed(root, tmp);
            bytes = Files.size(tmp);
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("write failed: " + e));
        }
        long t2 = System.nanoTime();
        int n = pd.snapshotLedger().size();
        // バニラの保存と同じ道: 汚れた SavedData を全部書く(サーバースレッド)+ 胞子の場の保存(写しはサーバースレッド・書き出しは IO スレッド)
        long s0 = System.nanoTime();
        pd.setDirty();
        level.getDataStorage().save();
        long s1 = System.nanoTime();
        SporeFieldServer.devSave(level.getServer());
        long s2 = System.nanoTime();
        String line2 = String.format("SPREADSAVETEST2 dataStorage.save=%.1fms fieldSave(serverThread)=%.1fms", (s1 - s0) / 1e6, (s2 - s1) / 1e6);
        ctx.getSource().sendSuccess(() -> Component.literal(line2), false);
        long t3 = System.nanoTime();
        String line = String.format("SPREADSAVETEST ledger=%d buildTag=%.1fms compressWrite=%.1fms size=%.1fMB cloneLedger=%.1fms",
                n, (t1 - t0) / 1e6, (t2 - t1) / 1e6, bytes / 1048576.0, (t3 - t2) / 1e6);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static long fileSize(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static long[] dirSize(Path dir) {
        long[] out = new long[2];
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(f -> {
                out[0]++;
                out[1] += fileSize(f);
            });
        } catch (IOException ignored) {
        }
        return out;
    }
}
