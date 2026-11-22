package com.yayuy.overgrownvegetation.devbridge;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.util.SortedArraySet;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 統合サーバーのチャンクの読み込み解除の中身を書き出す(2026-10-18、ワールドを閉じると「Saving worlds」で固まった件の調べ)。
 *
 * <p>{@code chunkdump} はその場で 1 回、{@code chunkwatch} は見張りのスレッドを立て、サーバーの停止が始まってから
 * 20・60・120 秒たっても止まり終わっていなければ書き出す(固まっている間はクライアントの tick が回らず、
 * devbridge の命令が進まないので)。書き出し先は {@code devbridge/chunkdump-<n>.txt}。
 *
 * <p>バニラの {@code ChunkMap} の中を反射で覗く。サーバーのスレッドが動いている間に読むと食い違うことがある(調べもの用)。
 */
final class ChunkDump {
    private static MinecraftServer last;
    private static int count;

    private ChunkDump() {
    }

    /** その場で 1 回書き出す。返り値は out.log に書く 1 行。 */
    static String dump(Minecraft mc, Path dir, String label) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) last = server;
        else server = last;
        if (server == null) return "CHUNKDUMP サーバーが無い";
        return write(server, dir, label);
    }

    /** 停止の見張りを立てる。leave の前に流す。 */
    static String watch(Minecraft mc, Path dir) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return "CHUNKWATCH サーバーが無い";
        last = server;
        Thread thread = new Thread(() -> {
            long[] marks = {20_000L, 60_000L, 120_000L};
            long stopSeen = -1L;
            int n = 0;
            while (!server.isShutdown()) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    return;
                }
                if (server.isRunning()) continue;
                long now = System.currentTimeMillis();
                if (stopSeen < 0L) stopSeen = now;
                if (n < marks.length && now - stopSeen >= marks[n]) {
                    write(server, dir, "停止から " + marks[n] / 1000 + " 秒");
                    n++;
                }
            }
            appendLine(dir, "CHUNKWATCH 止まり終わった(停止から " + (stopSeen < 0L ? "?" : (System.currentTimeMillis() - stopSeen) / 1000.0) + " 秒)");
            if (last == server) last = null; // 止まったサーバーを掴み続けない
        }, "devbridge-chunkwatch");
        thread.setDaemon(true);
        thread.start();
        return "CHUNKWATCH 見張りを立てた";
    }

    private static synchronized String write(MinecraftServer server, Path dir, String label) {
        int n = ++count;
        Path file = dir.resolve("chunkdump-" + n + ".txt");
        String summary = "?";
        StringBuilder out = new StringBuilder();
        for (int attempt = 0; attempt < 3; attempt++) {
            out.setLength(0);
            try {
                summary = body(server, label, out);
                break;
            } catch (Exception e) {
                summary = "読めなかった " + e;
            }
        }
        try {
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "CHUNKDUMP 書けない " + e;
        }
        String line = "CHUNKDUMP " + n + " (" + label + ") " + summary + " → " + file.getFileName();
        appendLine(dir, line);
        return line;
    }

    private static void appendLine(Path dir, String line) {
        try {
            Files.writeString(dir.resolve("chunkdump.log"), "[" + LocalTime.now() + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 調べもの用
        }
    }

    @SuppressWarnings("unchecked")
    private static String body(MinecraftServer server, String label, StringBuilder out) throws ReflectiveOperationException {
        out.append("# chunkdump ").append(label).append(' ').append(LocalTime.now())
                .append(" running=").append(server.isRunning()).append(" shutdown=").append(server.isShutdown())
                .append(" serverTasks=").append(server.getPendingTasksCount()).append('\n');
        StringBuilder summary = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache cache = level.getChunkSource();
            ChunkMap map = cache.chunkMap;
            Long2ObjectMap<ChunkHolder> updating = (Long2ObjectMap<ChunkHolder>) get(ChunkMap.class, map, "updatingChunkMap");
            Long2ObjectMap<ChunkHolder> visible = (Long2ObjectMap<ChunkHolder>) get(ChunkMap.class, map, "visibleChunkMap");
            Long2ObjectMap<ChunkHolder> pending = (Long2ObjectMap<ChunkHolder>) get(ChunkMap.class, map, "pendingUnloads");
            LongSet toDrop = (LongSet) get(ChunkMap.class, map, "toDrop");
            Queue<Runnable> unloadQueue = (Queue<Runnable>) get(ChunkMap.class, map, "unloadQueue");
            List<ChunkGenerationTask> pendingTasks = (List<ChunkGenerationTask>) get(ChunkMap.class, map, "pendingGenerationTasks");
            DistanceManager distance = (DistanceManager) get(ServerChunkCache.class, cache, "distanceManager");
            Map<Long, SortedArraySet<Ticket<?>>> tickets = (Map<Long, SortedArraySet<Ticket<?>>>) get(DistanceManager.class, distance, "tickets");
            BlockableEventLoop<?> mainExec = (BlockableEventLoop<?>) get(ServerChunkCache.class, cache, "mainThreadProcessor");

            // 写しを取ってから見る(サーバーが動いていると途中で変わる)。
            Map<ChunkHolder, String> where = new IdentityHashMap<>();
            for (ChunkHolder h : new ArrayList<>(updating.values())) where.merge(h, "U", String::concat);
            for (ChunkHolder h : new ArrayList<>(pending.values())) where.merge(h, "P", String::concat);
            for (ChunkHolder h : new ArrayList<>(visible.values())) where.merge(h, "V", String::concat);

            int queueMin = Integer.MAX_VALUE, queueMax = 0;
            for (int i = 0; i < 200; i++) {
                int s = unloadQueue.size();
                queueMin = Math.min(queueMin, s);
                queueMax = Math.max(queueMax, s);
            }
            List<String> queueKinds = new ArrayList<>();
            int k = 0;
            for (Runnable r : unloadQueue) {
                if (k++ >= 5) break;
                queueKinds.add(r.getClass().getName());
            }

            // 仕事中の生成タスク(チャンクの持つもの + まだ流していないもの)
            Map<ChunkGenerationTask, Boolean> tasks = new IdentityHashMap<>();
            for (ChunkHolder h : where.keySet()) {
                ChunkGenerationTask t = ((AtomicReference<ChunkGenerationTask>) get(GenerationChunkHolder.class, h, "task")).get();
                if (t != null) tasks.put(t, Boolean.TRUE);
            }
            for (ChunkGenerationTask t : new ArrayList<>(pendingTasks)) tasks.put(t, Boolean.TRUE);

            int refHolders = 0, pendingRef = 0, pendingNotSynced = 0, notSynced = 0;
            List<ChunkHolder> interesting = new ArrayList<>();
            for (Map.Entry<ChunkHolder, String> e : where.entrySet()) {
                ChunkHolder h = e.getKey();
                boolean ref = h.getGenerationRefCount() != 0;
                boolean synced = h.getSaveSyncFuture().isDone();
                boolean inPending = e.getValue().contains("P");
                if (ref) refHolders++;
                if (!synced) notSynced++;
                if (inPending && ref) pendingRef++;
                if (inPending && !synced) pendingNotSynced++;
                if (ref || !synced) interesting.add(h);
            }

            String dim = level.dimension().location().toString();
            String s = String.format(Locale.ROOT,
                    "%s: updating=%d visible=%d pending=%d toDrop=%d unloadQueue=%d..%d tasks=%d(未流し %d) 参照あり=%d 保存待ち=%d pending中の参照あり=%d pending中の保存待ち=%d mainTasks=%d",
                    dim, updating.size(), visible.size(), pending.size(), toDrop.size(), queueMin, queueMax, tasks.size(), pendingTasks.size(),
                    refHolders, notSynced, pendingRef, pendingNotSynced, mainExec.getPendingTasksCount());
            out.append("\n## ").append(s).append('\n');
            out.append("unloadQueue の先頭: ").append(queueKinds).append('\n');
            if (summary.length() > 0) summary.append(" | ");
            summary.append(s);

            out.append("\n### 参照あり・保存待ちのチャンク(最大 80)\n");
            out.append("pos  where(U=updating P=pending V=visible D=toDrop)  level  queueLevel  ref  saveSyncDone  readyForSaving  latest  persisted  futures  tickets  heldBy\n");
            int shown = 0;
            for (ChunkHolder h : interesting) {
                if (shown++ >= 80) break;
                ChunkPos pos = h.getPos();
                String w = where.get(h) + (toDrop.contains(pos.toLong()) ? "D" : "");
                List<String> heldBy = new ArrayList<>();
                for (ChunkGenerationTask t : tasks.keySet()) {
                    StaticCache2D<GenerationChunkHolder> c = (StaticCache2D<GenerationChunkHolder>) get(ChunkGenerationTask.class, t, "cache");
                    if (c.contains(pos.x, pos.z) && c.get(pos.x, pos.z) == h) heldBy.add(taskPos(t));
                }
                out.append(String.format(Locale.ROOT, "%d,%d  %s  %d  %d  %d  %s  %s  %s  %s  %s  %s  %s%n",
                        pos.x, pos.z, w, h.getTicketLevel(), h.getQueueLevel(), h.getGenerationRefCount(), h.getSaveSyncFuture().isDone(),
                        h.isReadyForSaving(), h.getLatestStatus(), h.getPersistedStatus(), futures(h), tickets(tickets.get(pos.toLong())), heldBy));
            }

            out.append("\n### 生成タスク(最大 60)\n");
            out.append("pos  target  scheduled  cancelled  needsGen  layer(待ち/全部)  radius\n");
            int shownT = 0;
            for (ChunkGenerationTask t : tasks.keySet()) {
                if (shownT++ >= 60) break;
                List<CompletableFuture<?>> layer = (List<CompletableFuture<?>>) get(ChunkGenerationTask.class, t, "scheduledLayer");
                int waiting = 0;
                for (CompletableFuture<?> f : new ArrayList<>(layer)) if (!f.isDone()) waiting++;
                out.append(String.format(Locale.ROOT, "%s  %s  %s  %s  %s  %d/%d%n",
                        taskPos(t), t.targetStatus, get(ChunkGenerationTask.class, t, "scheduledStatus"),
                        get(ChunkGenerationTask.class, t, "markedForCancellation"), get(ChunkGenerationTask.class, t, "needsGeneration"),
                        waiting, layer.size()));
            }
        }
        return summary.toString();
    }

    private static String taskPos(ChunkGenerationTask t) throws ReflectiveOperationException {
        ChunkPos p = (ChunkPos) get(ChunkGenerationTask.class, t, "pos");
        return p.x + "," + p.z + "→" + t.targetStatus;
    }

    /** 状態ごとの future: d = 済み・p = 待ち・. = 無し。 */
    @SuppressWarnings("unchecked")
    private static String futures(ChunkHolder h) throws ReflectiveOperationException {
        AtomicReferenceArray<CompletableFuture<?>> f = (AtomicReferenceArray<CompletableFuture<?>>) get(GenerationChunkHolder.class, h, "futures");
        StringBuilder b = new StringBuilder();
        List<ChunkStatus> statuses = ChunkStatus.getStatusList();
        for (int i = 0; i < f.length(); i++) {
            CompletableFuture<?> x = f.get(i);
            b.append(x == null ? '.' : x.isDone() ? 'd' : 'p');
        }
        return b + "(" + statuses.size() + ")";
    }

    private static String tickets(Collection<Ticket<?>> set) {
        if (set == null) return "-";
        List<String> list = new ArrayList<>();
        for (Ticket<?> t : new ArrayList<>(set)) list.add(t.getType() + ":" + t.getTicketLevel());
        return list.toString();
    }

    private static Object get(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
