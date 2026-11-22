package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppingEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code /ov perf record <秒>} —— ゲームの中で JFR(Java Flight Recorder)を回し、<b>何が重いか</b>をその場で集計する(2026-10-06、①)。
 *
 * <p>2026-10-06 の調査で使った {@code tools/perf} の集計(サーバースレッドのサンプルを、mod のクラス・エンティティ・チャンクの同期読み込みの
 * 犯人ごとに数える)を mod に入れたもの。プレイヤーの重いワールドでそのまま測れるように。JFR はバニラの {@code /jfr} も使っている
 * JDK の機能で、測っている間の重さは数 % 増える程度。
 *
 * <p>結果はチャットに要約、{@code <ゲームのフォルダ>/overgrown_vegetation/perf/} に詳しい表(.txt)と記録そのもの(.jfr、
 * {@code tools/perf} で掘れる)を置く。古いものは {@value #KEEP_FILES} 組まで残して消す。集計は別のスレッドで行い、
 * 結果だけサーバースレッドへ戻す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PerfRecorder {

    /** 残す記録の組数(.jfr と .txt)。 */
    static final int KEEP_FILES = 5;
    /** チャットに出す行数(表ごと)。 */
    static final int CHAT_ROWS = 6;
    private static final String MOD_PACKAGE = "com.yayuy.overgrownvegetation.";

    private static Recording recording;
    private static int ticksLeft;
    private static CommandSourceStack requester;
    private static Path jfrFile;
    private static long serverThreadId;
    private static final LongArrayList TICK_TIMES = new LongArrayList();
    private static volatile boolean analysing;

    private PerfRecorder() {
    }

    /** 記録を始める。始められなければ理由を返す(始めたら null)。 */
    static String start(CommandSourceStack source, int seconds) {
        if (recording != null || analysing) {
            return "前の記録がまだ終わっていません";
        }
        MinecraftServer server = source.getServer();
        try {
            Path dir = server.getServerDirectory().toPath().resolve(OvergrownVegetation.MODID).resolve("perf");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
            jfrFile = dir.resolve("perf-" + stamp + ".jfr");
            Recording r = new Recording(Configuration.getConfiguration("profile"));
            // JFR は 1 回の標本で数本のスレッドしか取らないので、スレッドの多いクライアントではサーバースレッドの標本が少ない
            // (10 ms 間隔で 20 秒 → 200 弱)。2 ms にして 5 倍に。測っている間だけ少し重くなる。
            r.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2));
            r.setName(OvergrownVegetation.MODID + "-perf");
            r.setToDisk(true);
            r.start();
            recording = r;
        } catch (Throwable e) {
            // JFR が使えない Java(ごく一部の配布)・書き込めないフォルダ。
            recording = null;
            OvergrownVegetation.LOGGER.warn("/ov perf record を始められなかった", e);
            return "記録を始められませんでした(" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
        requester = source;
        ticksLeft = seconds * 20;
        serverThreadId = server.getRunningThread().getId();
        TICK_TIMES.clear();
        return null;
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        if (recording == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        TICK_TIMES.add(server.tickTimes /* 1.20.1: getTickTimesNanos が無い */[Math.floorMod(server.getTickCount(), TickBudget.WINDOW)]);
        if (--ticksLeft > 0) {
            return;
        }
        Recording r = recording;
        recording = null;
        CommandSourceStack source = requester;
        requester = null;
        long[] ticks = TICK_TIMES.toLongArray();
        Path file = jfrFile;
        long threadId = serverThreadId;
        try {
            r.stop();
            r.dump(file);
        } catch (IOException e) {
            r.close();
            send(server, source, "§c[性能]§r 記録を書き出せませんでした: " + e.getMessage());
            return;
        }
        r.close();
        analysing = true;
        send(server, source, "§a[性能]§r 記録を止めた。集計しています…");
        Thread worker = new Thread(() -> {
            try {
                List<String> lines = analyse(file, threadId, ticks);
                Path txt = file.resolveSibling(file.getFileName().toString().replace(".jfr", ".txt"));
                Files.write(txt, lines, StandardCharsets.UTF_8);
                prune(file.getParent());
                List<String> chat = chatLines(lines, txt);
                server.execute(() -> chat.forEach(line -> sendNow(source, line)));
            } catch (Throwable e) {
                OvergrownVegetation.LOGGER.warn("/ov perf record の集計に失敗", e);
                send(server, source, "§c[性能]§r 集計に失敗しました: " + e);
            } finally {
                analysing = false;
            }
        }, "Overgrown perf analysis");
        worker.setDaemon(true);
        worker.start();
    }

    /** ワールドを閉じたら記録を捨てる。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (recording != null) {
            recording.close();
            recording = null;
            requester = null;
        }
    }

    // ---------------------------------------------------------------- 集計(別スレッド)

    /** 記録を読んで表にする。1 行目から順に「見出し」「表」…。チャットにはその一部を出す({@link #chatLines})。 */
    static List<String> analyse(Path file, long threadId, long[] ticks) throws IOException {
        int total = 0;
        int modSamples = 0;
        int syncLoad = 0;
        int entitySamples = 0;
        long gcNanos = 0L;
        int gcCount = 0;
        Map<String, Integer> modClasses = new HashMap<>();
        Map<String, Integer> modLeaves = new HashMap<>();
        Map<String, Integer> entities = new HashMap<>();
        Map<String, Integer> blockers = new HashMap<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.GarbageCollection")) {
                    gcNanos += e.getDuration("sumOfPauses").toNanos();
                    gcCount++;
                    continue;
                }
                if (!type.equals("jdk.ExecutionSample")) {
                    continue;
                }
                RecordedThread thread = e.getThread("sampledThread");
                RecordedStackTrace stack = e.getStackTrace();
                if (thread == null || thread.getJavaThreadId() != threadId || stack == null) {
                    continue;
                }
                total++;
                List<RecordedFrame> frames = stack.getFrames();
                // mod のクラス(同じサンプルで同じクラスは 1 回)と、葉からいちばん近い mod のメソッド
                Set<String> seen = new HashSet<>();
                String leaf = null;
                int tickIndex = -1;
                int blockIndex = -1;
                for (int i = 0; i < frames.size(); i++) {
                    RecordedFrame f = frames.get(i);
                    if (!f.isJavaFrame()) {
                        continue;
                    }
                    String cls = f.getMethod().getType().getName();
                    String method = f.getMethod().getName();
                    if (method.equals("tickNonPassenger") && cls.endsWith(".ServerLevel")) {
                        tickIndex = i;
                    }
                    if (method.equals("managedBlock") && cls.endsWith("ServerChunkCache$MainThreadExecutor")) {
                        blockIndex = i;
                    }
                    if (cls.startsWith(MOD_PACKAGE) && !isOwnTooling(cls)) {
                        String simple = simple(cls);
                        if (seen.add(simple)) {
                            modClasses.merge(simple, 1, Integer::sum);
                        }
                        if (leaf == null) {
                            leaf = simple + "." + method;
                        }
                    }
                }
                if (leaf != null) {
                    modSamples++;
                    modLeaves.merge(leaf, 1, Integer::sum);
                }
                if (tickIndex > 0) {
                    entitySamples++;
                    entities.merge(entityOf(frames, tickIndex), 1, Integer::sum);
                }
                if (blockIndex >= 0) {
                    syncLoad++;
                    blockers.merge(blockerOf(frames, blockIndex), 1, Integer::sum);
                }
            }
        }

        List<String> out = new ArrayList<>();
        long[] sorted = ticks.clone();
        Arrays.sort(sorted);
        double avg = Arrays.stream(ticks).average().orElse(0.0D) / 1e6;
        double p95 = sorted.length == 0 ? 0.0D : sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95D))] / 1e6;
        double max = sorted.length == 0 ? 0.0D : sorted[sorted.length - 1] / 1e6;
        out.add(String.format(Locale.ROOT, "記録 %.0f 秒(%d tick): 1 tick 平均 %.1f ms / P95 %.1f ms / 最大 %.1f ms",
                ticks.length / 20.0D, ticks.length, avg, p95, max));
        out.add(String.format(Locale.ROOT, "サーバースレッドのサンプル %d(うち mod の処理を通るもの %s、エンティティの処理 %s、チャンクの同期読み込み待ち %s)",
                total, pct(modSamples, total), pct(entitySamples, total), pct(syncLoad, total)));
        out.add(String.format(Locale.ROOT, "GC %d 回、止まった時間の合計 %.0f ms", gcCount, gcNanos / 1e6));
        table(out, "■ mod のクラス(そのクラスを通るサンプルの割合。入れ子は重なって数える)", modClasses, total, 25);
        table(out, "■ mod の中でいちばん葉に近いメソッド(そこから先はバニラの処理)", modLeaves, total, 25);
        table(out, "■ エンティティ(tick の中身。mod のクラスがあればそれ、無ければバニラの tick)", entities, total, 25);
        table(out, "■ チャンクの同期読み込み待ちの呼び出し元(0 が正常)", blockers, total, 10);
        return out;
    }

    /** {@code perf/}・Mixin の入口・開発用の道具は数えない(測っている側)。 */
    private static boolean isOwnTooling(String cls) {
        return cls.startsWith(MOD_PACKAGE + "perf.") || cls.startsWith(MOD_PACKAGE + "devbridge.");
    }

    /** エンティティの tick の中身: 葉の側へ向かって最初の mod のクラス、無ければ tickNonPassenger の直下(バニラ)。 */
    private static String entityOf(List<RecordedFrame> frames, int tickIndex) {
        for (int i = tickIndex - 1; i >= 0; i--) {
            RecordedFrame f = frames.get(i);
            if (f.isJavaFrame() && f.getMethod().getType().getName().startsWith(MOD_PACKAGE)) {
                return simple(f.getMethod().getType().getName());
            }
        }
        RecordedFrame f = frames.get(tickIndex - 1);
        return "(バニラ) " + simple(f.getMethod().getType().getName()) + "." + f.getMethod().getName();
    }

    /** 同期読み込み待ちの呼び出し元: 根元の側へ向かって最初の mod のメソッド(行番号付き)。無ければバニラ。 */
    private static String blockerOf(List<RecordedFrame> frames, int blockIndex) {
        for (int i = blockIndex + 1; i < frames.size(); i++) {
            RecordedFrame f = frames.get(i);
            if (f.isJavaFrame() && f.getMethod().getType().getName().startsWith(MOD_PACKAGE)) {
                return simple(f.getMethod().getType().getName()) + "." + f.getMethod().getName() + ":" + f.getLineNumber();
            }
        }
        return "(バニラ)";
    }

    private static void table(List<String> out, String title, Map<String, Integer> counts, int total, int rows) {
        out.add(title);
        if (counts.isEmpty()) {
            out.add("  (なし)");
            return;
        }
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(rows)
                .forEach(e -> out.add(String.format(Locale.ROOT, "  %5.1f%%  %s", 100.0D * e.getValue() / Math.max(1, total), e.getKey())));
    }

    /** チャットに出す分: 見出し 3 行と、各表の上位 {@value #CHAT_ROWS} 行。 */
    private static List<String> chatLines(List<String> lines, Path txt) {
        List<String> chat = new ArrayList<>();
        chat.add("§a[性能]§r " + lines.get(0));
        chat.add("  " + lines.get(1));
        chat.add("  " + lines.get(2));
        int rows = 0;
        for (int i = 3; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("■")) {
                chat.add("§e" + line + "§r");
                rows = 0;
            } else if (rows++ < CHAT_ROWS) {
                chat.add(line);
            }
        }
        chat.add("§7詳しい表: " + txt.toAbsolutePath() + "§r");
        return chat;
    }

    /** 古い記録を消す(新しい {@value #KEEP_FILES} 組だけ残す)。 */
    private static void prune(Path dir) throws IOException {
        for (String ext : new String[]{".jfr", ".txt"}) {
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().startsWith("perf-") && p.getFileName().toString().endsWith(ext))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
            for (int i = 0; i < files.size() - KEEP_FILES; i++) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    /** クラス名の最後の部分。ラムダ({@code X$$Lambda.0x…})は「X(ラムダ)」にまとめる。 */
    private static String simple(String cls) {
        int lambda = cls.indexOf("$$Lambda");
        if (lambda >= 0) {
            String owner = cls.substring(0, lambda);
            return owner.substring(owner.lastIndexOf('.') + 1) + "(ラムダ)";
        }
        return cls.substring(cls.lastIndexOf('.') + 1);
    }

    private static String pct(int n, int total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0D * n / Math.max(1, total));
    }

    private static void send(MinecraftServer server, CommandSourceStack source, String line) {
        server.execute(() -> sendNow(source, line));
    }

    private static void sendNow(CommandSourceStack source, String line) {
        if (source != null) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    /** 記録中か(表示用)。 */
    static boolean recording() {
        return recording != null;
    }

    /** 記録の残り秒(表示用)。 */
    static int secondsLeft() {
        return Math.max(0, ticksLeft / 20);
    }
}
