package com.yayuy.overgrownvegetation.devbridge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * {@code bench … jfr} のときだけ、計っている間の描画のスレッドの標本(JFR)を取り、どこに時間を使ったかを数える(2026-10-18)。
 * {@link FxProfiler} の受け手の包みでは見えないもの —— 地形・Mob・ブロックエンティティ(蔦など)の描画、mixin、バニラ —— を見るため。
 * サーバー用の {@code perf/PerfRecorder} と同じ作り(JFR の標本は Java を動かしている間だけ。GL のドライバの中は「ネイティブ」に別で数える)。
 */
final class RenderJfr {
    private static final String MOD = "com.yayuy.overgrownvegetation.";
    private static Recording recording;
    private static long threadId;

    private RenderJfr() {
    }

    static String start() {
        return start(false);
    }

    /** {@code alloc} = 確保の記録も取る(重い。`bench … jfralloc`)。2026-10-20 から軽い default の設定で取る(profile だと JFR が遅いフレームを作っていた)。 */
    static String start(boolean alloc) {
        try {
            Recording r = new Recording(Configuration.getConfiguration("default"));
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
            r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(4));
            if (alloc) {
                // どのスレッドの・どこで・何を確保したか(GC の引っかかりの元を探す)
                r.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s");
            } else {
                r.disable("jdk.ObjectAllocationSample");
            }
            r.setToDisk(true);
            r.start();
            recording = r;
            threadId = Thread.currentThread().threadId();
            return null;
        } catch (Throwable e) {
            recording = null;
            return e.toString();
        }
    }

    /** 止めて {@code file} に書き、集計を返す(表の行)。 */
    static List<String> stop(Path file) {
        return stop(file, new long[0][]);
    }

    /** {@code slowWindows} = 遅いフレームの [始め, 終わり](紀元からのマイクロ秒)。その中の描画のスレッドの標本も別に数える(2026-10-20)。 */
    static List<String> stop(Path file, long[][] slowWindows) {
        List<String> out = new ArrayList<>();
        Recording r = recording;
        recording = null;
        if (r == null) return out;
        try {
            r.stop();
            r.dump(file);
            r.close();
            analyse(file, out, slowWindows);
        } catch (Throwable e) {
            out.add("JFR を読めなかった: " + e);
        }
        return out;
    }

    private static void analyse(Path file, List<String> out, long[][] slowWindows) throws Exception {
        long[][] wins = slowWindows.clone();
        java.util.Arrays.sort(wins, (x, y) -> Long.compare(x[0], y[0]));
        Map<String, Integer> slowCategory = new HashMap<>();
        Map<String, Integer> slowChains = new HashMap<>();
        int slowSamples = 0;
        int slowNative = 0;
        List<long[]> gcPauses = new ArrayList<>();
        int total = 0;
        int nativeTotal = 0;
        Map<String, Integer> category = new HashMap<>();
        Map<String, Integer> nativeCategory = new HashMap<>();
        Map<String, Integer> leaves = new HashMap<>();
        Map<String, Integer> modInclusive = new HashMap<>();
        Map<String, Integer> modLeaf = new HashMap<>();
        Map<String, Integer> entityRenderers = new HashMap<>();
        Map<String, Integer> blockEntityRenderers = new HashMap<>();
        Map<String, Long> allocThread = new HashMap<>();
        Map<String, Long> allocSite = new HashMap<>();
        Map<String, Long> allocClass = new HashMap<>();
        long allocTotal = 0;
        Map<String, Map<String, Long>> allocChains = new HashMap<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.GCPhasePause")) {
                    // 全部のスレッドが止まった GC(遅いフレームと重なるかを後で数える)
                    long s = e.getStartTime().getEpochSecond() * 1_000_000L + e.getStartTime().getNano() / 1000L;
                    gcPauses.add(new long[] {s, s + e.getDuration().toNanos() / 1000L});
                    continue;
                }
                if (type.equals("jdk.ObjectAllocationSample")) {
                    long w = e.getLong("weight");
                    RecordedThread at = e.getThread("eventThread");
                    String tn = at == null ? "(不明)" : threadGroup(at.getJavaName());
                    allocTotal += w;
                    allocThread.merge(tn, w, Long::sum);
                    var oc = e.getClass("objectClass");
                    allocClass.merge(oc == null ? "(不明)" : simple(oc.getName()), w, Long::sum);
                    RecordedStackTrace st = e.getStackTrace();
                    String siteKey = tn + " / " + (st == null ? "(なし)" : allocSite(st.getFrames()));
                    allocSite.merge(siteKey, w, Long::sum);
                    if (st != null) allocChains.computeIfAbsent(siteKey, k -> new HashMap<>()).merge(chain(st.getFrames(), 14), w, Long::sum);
                    continue;
                }
                boolean exec = type.equals("jdk.ExecutionSample");
                if (!exec && !type.equals("jdk.NativeMethodSample")) continue;
                RecordedThread thread = e.getThread("sampledThread");
                RecordedStackTrace stack = e.getStackTrace();
                if (thread == null || thread.getJavaThreadId() != threadId || stack == null) continue;
                List<RecordedFrame> frames = stack.getFrames();
                String cat = categorize(frames, exec ? entityRenderers : null, exec ? blockEntityRenderers : null);
                if (wins.length > 0 && inWindow(wins, e.getStartTime())) {
                    if (exec) slowSamples++; else slowNative++;
                    slowCategory.merge((exec ? "" : "ネイティブ: ") + cat, 1, Integer::sum);
                    slowChains.merge((exec ? "" : "ネイティブ: ") + chain(frames, 9), 1, Integer::sum);
                }
                if (!exec) {
                    nativeTotal++;
                    nativeCategory.merge(cat + " / " + topJava(frames), 1, Integer::sum);
                    continue;
                }
                total++;
                category.merge(cat, 1, Integer::sum);
                String leaf = topJava(frames);
                leaves.merge(leaf, 1, Integer::sum);
                Set<String> seen = new HashSet<>();
                String nearest = null;
                for (RecordedFrame f : frames) {
                    if (!f.isJavaFrame()) continue;
                    String cls = f.getMethod().getType().getName();
                    String m = f.getMethod().getName();
                    String label = null;
                    if (cls.startsWith(MOD) && !cls.startsWith(MOD + "devbridge.")) {
                        label = simple(cls);
                    } else if (m.contains("overgrown_vegetation$")) {
                        label = "mixin " + simple(cls) + "." + m.substring(m.indexOf("overgrown_vegetation$") + "overgrown_vegetation$".length());
                    }
                    if (label != null) {
                        if (seen.add(label)) modInclusive.merge(label, 1, Integer::sum);
                        if (nearest == null) nearest = label.startsWith("mixin ") ? label : label + "." + m;
                    }
                }
                if (nearest != null) modLeaf.merge(nearest, 1, Integer::sum);
            }
        }
        out.add("");
        out.add(String.format(Locale.ROOT, "■ JFR: 描画のスレッドの標本 %d(Java を動かしていた間)・ネイティブ(GL のドライバ・窓)の標本 %d。JFR は 1 回に数本のスレッドしか取らないので、数は時間ではない —— 割合で見る",
                total, nativeTotal));
        table(out, "■ どこで(いちばん葉に近い区切り。地形・Mob など)", category, total, 20);
        table(out, "■ この MOD を通る標本(クラス・mixin。入れ子は重ねて数える)", modInclusive, total, 25);
        table(out, "■ この MOD の中でいちばん葉に近い所", modLeaf, total, 20);
        table(out, "■ エンティティの描画(描くクラス)", entityRenderers, total, 15);
        table(out, "■ ブロックエンティティの描画(描くクラス)", blockEntityRenderers, total, 15);
        table(out, "■ いちばん葉の Java のメソッド", leaves, total, 25);
        table(out, "■ ネイティブ(区切り / いちばん葉の Java)", nativeCategory, nativeTotal, 12);
        if (wins.length > 0) {
            int all = slowSamples + slowNative;
            out.add("");
            out.add(String.format(Locale.ROOT, "■ 遅い方から 1%% のフレーム(%d 枚)の中の描画のスレッドの標本: Java %d・ネイティブ %d(少なければ描画のスレッドは止まっていた —— GC・OS・GPU 待ち)",
                    wins.length, slowSamples, slowNative));
            int withGc = 0;
            long gcTotal = 0;
            for (long[] p : gcPauses) {
                gcTotal += p[1] - p[0];
            }
            for (long[] w : wins) {
                for (long[] p : gcPauses) {
                    if (p[0] < w[1] && p[1] > w[0]) {
                        withGc++;
                        break;
                    }
                }
            }
            out.add(String.format(Locale.ROOT, "  GC の止まり(全部のスレッド)%d 回・合計 %.1f ms。遅いフレームのうち GC と重なったもの %d / %d 枚",
                    gcPauses.size(), gcTotal / 1000.0, withGc, wins.length));
            table(out, "■ 遅いフレームの中 —— どこで", slowCategory, all, 15);
            table(out, "■ 遅いフレームの中 —— 呼び元の連なり(葉 → 根、JDK を除く 9 段)", slowChains, all, 20);
        }
        out.add("");
        out.add(String.format(Locale.ROOT, "■ 確保したメモリ(全部のスレッド・JFR の見積もり)%.0f MB", allocTotal / 1048576.0));
        bytes(out, "■ 確保 —— スレッドごと", allocThread, allocTotal, 12);
        bytes(out, "■ 確保 —— スレッド / 確保した所(JDK・fastutil を除いた葉と、この MOD のいちばん近い所)", allocSite, allocTotal, 30);
        bytes(out, "■ 確保 —— 物の種類", allocClass, allocTotal, 15);
        out.add("");
        out.add("■ 確保の上位 6 か所の、いちばん多い呼び元の連なり(葉 → 根、JDK を除く 14 段)");
        allocSite.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(6).forEach(en -> {
            Map<String, Long> chains = allocChains.get(en.getKey());
            if (chains == null) return;
            out.add("  " + en.getKey());
            chains.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(2).forEach(c ->
                    out.add(String.format(Locale.ROOT, "    %5.1f%%  %s", 100.0 * c.getValue() / en.getValue(), c.getKey())));
        });
    }

    /** 葉から根へたどって、最初に当たった区切り。 */
    private static String categorize(List<RecordedFrame> frames, Map<String, Integer> entityRenderers, Map<String, Integer> berRenderers) {
        String renderer = null;
        for (RecordedFrame f : frames) {
            if (!f.isJavaFrame()) continue;
            String cls = simple(f.getMethod().getType().getName());
            String m = f.getMethod().getName();
            if (renderer == null && (cls.endsWith("Renderer") || cls.contains("Renderer$")) && !cls.startsWith("EntityRenderDispatcher")
                    && !cls.startsWith("BlockEntityRenderDispatcher") && !cls.equals("LevelRenderer") && !cls.equals("GameRenderer")) {
                renderer = cls;
            }
            if (cls.startsWith("SectionRenderDispatcher") || cls.startsWith("SectionCompiler")) return "チャンクの区画の組み立て・送り込み";
            if (cls.equals("LevelRenderer") && m.equals("renderSectionLayer")) return "地形の描画";
            if (cls.equals("EntityRenderDispatcher") && m.equals("render")) {
                if (entityRenderers != null) entityRenderers.merge(renderer == null ? "(不明)" : renderer, 1, Integer::sum);
                return "エンティティの描画";
            }
            if (cls.equals("BlockEntityRenderDispatcher")) {
                if (berRenderers != null) berRenderers.merge(renderer == null ? "(不明)" : renderer, 1, Integer::sum);
                return "ブロックエンティティの描画";
            }
            if (cls.equals("ParticleEngine")) return m.equals("render") ? "粒の描画" : "粒の tick";
            if (cls.equals("LevelRenderer") && (m.equals("renderSky") || m.equals("renderClouds"))) return "空・雲(バニラ)";
            if (cls.equals("LevelRenderer") && (m.equals("setupRender") || m.equals("applyFrustum") || m.equals("cullTerrain"))) return "見える区画を決める";
            if (cls.equals("LevelRenderer") && m.equals("renderLevel")) return "その他の描画(renderLevel 直下)";
            if (cls.equals("EventBus") && m.equals("post")) return "イベント(MOD の受け手など)";
            if (cls.equals("Gui")) return "GUI";
            if (cls.equals("GameRenderer") && m.equals("renderItemInHand")) return "手";
            if (cls.equals("Window") && m.equals("updateDisplay")) return "画面の入れ替え(GPU を待つ)";
            if (cls.equals("Minecraft") && m.equals("tick")) return "クライアントの tick";
            if (cls.equals("GameRenderer") && m.equals("render")) return "その他の描画(GameRenderer)";
        }
        return "その他";
    }

    /** 標本の時刻が遅いフレームの窓のどれかに入るか(窓は始めの順に並べてある)。 */
    private static boolean inWindow(long[][] wins, java.time.Instant t) {
        long us = t.getEpochSecond() * 1_000_000L + t.getNano() / 1000L;
        int lo = 0;
        int hi = wins.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (wins[mid][0] > us) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        // hi = 始めが us 以下の最後の窓(フレームは重ならない)
        return hi >= 0 && us <= wins[hi][1];
    }

    /** Worker-Main-3 → Worker-Main-N のように、番号違いのスレッドをまとめる。 */
    private static String threadGroup(String name) {
        return name == null ? "(不明)" : name.replaceAll("-\\d+$", "-N").replaceAll("#\\d+$", "#N");
    }

    /** JDK・fastutil・guava の中を飛ばした葉の所。この MOD を通っていれば、そのいちばん近い所も添える。 */
    private static String allocSite(List<RecordedFrame> frames) {
        String leaf = null;
        String mod = null;
        for (RecordedFrame f : frames) {
            if (!f.isJavaFrame()) continue;
            String cls = f.getMethod().getType().getName();
            String m = f.getMethod().getName();
            if (leaf == null && !cls.startsWith("java.") && !cls.startsWith("jdk.") && !cls.startsWith("sun.") && !cls.startsWith("it.unimi.")
                    && !cls.startsWith("com.google.")) {
                leaf = simple(cls) + "." + m;
            }
            if (mod == null && (cls.startsWith(MOD) && !cls.startsWith(MOD + "devbridge.") || m.contains("overgrown_vegetation$"))) {
                mod = simple(cls) + "." + m;
            }
            if (leaf != null && mod != null) break;
        }
        if (leaf == null) leaf = "(JDK の中だけ)";
        return mod == null || mod.equals(leaf) ? leaf : leaf + " ← " + mod;
    }

    /** 葉から根へ、JDK・fastutil・guava を除いた {@code depth} 段の「クラス.メソッド」を ← でつなぐ。 */
    private static String chain(List<RecordedFrame> frames, int depth) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (RecordedFrame f : frames) {
            if (!f.isJavaFrame()) continue;
            String cls = f.getMethod().getType().getName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("it.unimi.") || cls.startsWith("com.google.")) continue;
            if (k > 0) sb.append(" ← ");
            sb.append(simple(cls)).append('.').append(f.getMethod().getName());
            if (++k >= depth) break;
        }
        return sb.toString();
    }

    private static void bytes(List<String> out, String title, Map<String, Long> counts, long total, int rows) {
        out.add("");
        out.add(title);
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(rows).forEach(en ->
                out.add(String.format(Locale.ROOT, "  %5.1f%%  %7.1f MB  %s", total == 0 ? 0.0 : 100.0 * en.getValue() / total, en.getValue() / 1048576.0, en.getKey())));
    }

    private static String topJava(List<RecordedFrame> frames) {
        for (RecordedFrame f : frames) {
            if (f.isJavaFrame()) return simple(f.getMethod().getType().getName()) + "." + f.getMethod().getName();
        }
        return "(なし)";
    }

    private static String simple(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }

    private static void table(List<String> out, String title, Map<String, Integer> counts, int total, int rows) {
        out.add("");
        out.add(title);
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(rows).forEach(en ->
                out.add(String.format(Locale.ROOT, "  %5.1f%%  %6d  %s", total == 0 ? 0.0 : 100.0 * en.getValue() / total, en.getValue(), en.getKey())));
    }
}
