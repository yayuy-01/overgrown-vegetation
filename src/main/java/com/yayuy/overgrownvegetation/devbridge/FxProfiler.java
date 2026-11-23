package com.yayuy.overgrownvegetation.devbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import java.lang.annotation.ElementType;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * 演出の重さを測る道具(2026-10-18、ユーザー「軽量化の作業。ideas.lowspec_visuals の測る道具から」)。テストクライアント専用
 * ({@code -Dov.devbridge=true} のときだけ動く。配布 jar には入らない)。使い方は {@code tools/claude-client/README.md} の「重さを測る」。
 *
 * <ul>
 *     <li><b>演出ごとの CPU と GPU の時間</b>({@code fxprof on}): この MOD の画面側({@code .client.} のパッケージ)で
 *     {@link EventBusSubscriber} が登録した受け手を、いったん外して「時間を測る包み」で登録し直す。演出の 36 のファイルには触らない
 *     (雰囲気づくりのセッションが並行して直している)。CPU は {@link System#nanoTime}、GPU は GL のタイムスタンプ({@code GL_TIMESTAMP})。
 *     GPU を測るのは、前のフレームで CPU を {@link #GPU_GATE_NS} 以上使った(= 実際に何か描いた)受け手と段階だけ(全部に打つと
 *     1 フレーム数百個になり、測る側の重さで結果が狂う)。結果は {@link #SLOTS} フレーム遅れで読む(GPU を待たない)。</li>
 *     <li><b>見えないもの</b>: バニラのシェーダーを差し替えて地形の描画の中で効く演出(葉の揺れ・林冠の影など)や、uniform を渡すだけ・
 *     VBO を作るだけの受け手は、GPU の重さが地形の描画(バニラの残り)に入る。それは設定で切って {@code bench} を撮り比べる。
 *     手で {@code NeoForge.EVENT_BUS.addListener} した受け手・mixin も包まない。</li>
 *     <li><b>決まった振り回し</b>({@code bench}): 時計で決めた向き(毎秒 150° で回りながら上下に ±30°)に毎フレーム視点を動かし、
 *     フレーム時間の平均・中央値・99 パーセンタイル・1% の遅い値(遅い方から 1% のフレームの平均)・最悪の 10 フレーム(その
 *     フレームでいちばん CPU を使った受け手つき)・GC の回数と時間・描画のスレッドが確保したメモリを {@code devbridge/bench-<名前>.txt} に書く。</li>
 *     <li><b>画面の表</b>({@code fxprof hud}): FPS・フレーム時間・直近の 1% の遅い値・GPU のフレーム時間と、重い順の受け手。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FxProfiler {
    private static final boolean ENABLED = Boolean.getBoolean("ov.devbridge");

    // 描画の中の細かい区切り(mixin.client.LevelRendererPhaseMarkMixin → client.RenderPhaseHook)は 2026-10-30 に parked/ へ(Mixin は 10-30 の昼に退避済み)

    private static void phaseMark(String name) {
        if (segPrev != null && (on || benchState != BenchState.IDLE) && !benchNoGpu) {
            mark(name);
        }
    }

    /** GPU を測る受け手の目安: 前のフレームでこれ以上 CPU を使った(すぐ return した受け手は 1 マイクロ秒も使わない)。 */
    private static final long GPU_GATE_NS = 5_000L;
    /** GPU の結果を何フレーム遅れで読むか(待たずに済むように)。 */
    private static final int SLOTS = 4;
    /** 1 フレームに打てるタイムスタンプの数。足りなくなったらそのフレームの残りは GPU を測らない。 */
    private static final int MAX_QUERIES = 512;
    /** 画面の表の 1% の遅い値を出す直近のフレーム数。 */
    private static final int RECENT = 600;

    // ---------- 包んだ受け手 ----------

    /** 受け手 1 つ × 段階(描画の段階・イベントの種類)1 つ。 */
    static final class Stat {
        final String label;
        long cpuFrame;
        int callsFrame;
        long lastFrameCpu;
        long gpuFrameNs;
        /** 塗った画素の数(GL_SAMPLES_PASSED。奥行きの試しを通った断片)。重ね回数 = これ ÷ 画面の画素(2026-10-24)。 */
        long samplesFrame;
        long benchSamples;
        double emaCpu;
        double emaGpu;
        // bench の間の合計
        long benchCpu;
        long benchCalls;
        long benchMaxCpu;
        long benchGpu;
        /** 描画の段階の区間(受け手ではない)。この MOD の合計・受け手の表には入れない。 */
        boolean segment;

        Stat(String label) {
            this.label = label;
        }
    }

    // ---------- 描画の段階の区間(2026-10-18) ----------
    // RenderLevelStageEvent をいちばん先(HIGHEST)に受けて時刻と GPU のタイムスタンプを打ち、前の段階からの差を区間の時間にする。
    // 「after_solid_blocks → after_cutout_mipped_blocks」なら葉・草など cutout_mipped の地形の描画(と、その間の受け手)。受け手の包みと違い、
    // バニラの地形・エンティティの描画の CPU(GL の呼び出し)と GPU が分かる。

    private static final java.util.Map<String, Stat> SEGMENTS = new java.util.LinkedHashMap<>();
    private static String segPrev;
    private static long segPrevNs;
    private static int segPrevQ = -1;

    // ---------- depthat(2026-10-20、Distant Horizons と並べたときの奥行きの確かめ) ----------
    private static volatile int depthX = -1;
    private static volatile int depthY = -1;
    private static volatile String depthResult;

    /**
     * {@code depthat <x> <y>}: 画面の画素(スクショと同じ、左上が 0)の、粒の後の時点のふつうの描画の奥行き(0〜1。1 = 何も描いていない)と色を読む。
     * 読めたら {@code DEPTHAT …} を書いて次の命令へ。
     */
    static String depthAt(String arg, java.util.function.Consumer<String> log) {
        String result = depthResult;
        if (result != null) {
            log.accept("DEPTHAT " + result);
            depthResult = null;
            depthX = -1;
            return null;
        }
        if (depthX < 0) {
            String[] p = arg.trim().split("\\s+");
            depthY = Integer.parseInt(p[1]);
            depthX = Integer.parseInt(p[0]);
        }
        return "奥行きの読み取り";
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onStageDepth(RenderLevelStageEvent event) {
        if (!ENABLED || depthX < 0 || depthResult != null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        int h = Minecraft.getInstance().getWindow().getHeight();
        int x = depthX;
        int y = h - 1 - depthY;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.FloatBuffer d = stack.mallocFloat(1);
            java.nio.ByteBuffer c = stack.malloc(4);
            org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, d);
            org.lwjgl.opengl.GL11.glReadPixels(x, y, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, c);
            depthResult = String.format(Locale.ROOT, "(%d, %d) 奥行き %.6f・色 %d %d %d・奥のクリップ面 %.0f", depthX, depthY, d.get(0),
                    c.get(0) & 0xFF, c.get(1) & 0xFF, c.get(2) & 0xFF, Minecraft.getInstance().gameRenderer.getDepthFar());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onStage(RenderLevelStageEvent event) {
        if (!ENABLED || (!on && benchState == BenchState.IDLE) || benchNoGpu && !on) return;
        mark(stageName(event));
    }

    private static void mark(String name) {
        long now = System.nanoTime();
        int q = stamp();
        if (segPrev != null) {
            String key = segPrev + " → " + name;
            Stat s = SEGMENTS.get(key);
            if (s == null) {
                s = new Stat("区間 " + key);
                s.segment = true;
                SEGMENTS.put(key, s);
                STATS.add(s);
            }
            s.cpuFrame += now - segPrevNs;
            s.callsFrame++;
            if (segPrevQ >= 0 && q >= 0 && pendCount[slot] < pendStat[slot].length) {
                int i = pendCount[slot]++;
                pendStat[slot][i] = s;
                pendQ0[slot][i] = segPrevQ;
                pendQ1[slot][i] = q;
                pendS[slot][i] = -1;
            }
        }
        segPrev = name;
        segPrevNs = now;
        segPrevQ = q;
    }

    private static final class Listener {
        final String name;
        final MethodHandle handle;
        final IdentityHashMap<Object, Stat> byKey = new IdentityHashMap<>();

        Listener(String name, MethodHandle handle) {
            this.name = name;
            this.handle = handle;
        }
    }

    private static final List<Stat> STATS = new ArrayList<>();
    private static final List<Class<?>> WRAPPED_CLASSES = new ArrayList<>();
    private static final List<Consumer<? extends Event>> ADDED = new ArrayList<>();
    private static boolean on;
    private static boolean hud;
    /** 包みの中で使った時間(測る側の重さ)。1 フレーム分。 */
    private static long overheadFrame;
    private static double emaOverhead;

    // ---------- GPU のタイムスタンプ ----------

    private static boolean gpuChecked;
    private static boolean gpuOk;
    private static int[][] queries;
    private static final int[] used = new int[SLOTS];
    private static final Stat[][] pendStat = new Stat[SLOTS][MAX_QUERIES / 2];
    private static final int[][] pendQ0 = new int[SLOTS][MAX_QUERIES / 2];
    private static final int[][] pendQ1 = new int[SLOTS][MAX_QUERIES / 2];
    private static final int[] pendCount = new int[SLOTS];
    /** 受け手ごとの塗った画素の問い合わせ(-1 = なし)。区間には付けない。 */
    private static final int[][] pendS = new int[SLOTS][MAX_QUERIES / 2];
    private static int[][] sampleQueries;
    private static final int[] frameQ0 = new int[SLOTS];
    private static final int[] frameQ1 = new int[SLOTS];
    /** そのスロットのフレームの番号(bench の何フレーム目か。-1 = bench の外)。 */
    private static final int[] slotBenchFrame = new int[SLOTS];
    private static int slot;
    private static double emaGpuFrame;
    private static double emaGpuMod;

    static {
        Arrays.fill(slotBenchFrame, -1);
        Arrays.fill(frameQ0, -1);
        Arrays.fill(frameQ1, -1);
    }

    // ---------- フレーム ----------

    private static long lastPre;
    private static double emaFrame;
    private static final long[] recent = new long[RECENT];
    private static int recentCount;
    private static int recentNext;
    private static double recentLow1;
    private static int framesSinceLow;

    // ---------- bench ----------

    private enum BenchState { IDLE, WARMUP, RUN }

    private static BenchState benchState = BenchState.IDLE;
    private static String benchName;
    private static String benchArg;
    private static long benchStart;
    private static long benchRunStart;
    private static double benchSeconds;
    private static double benchWarmup;
    private static float benchYaw0;
    private static long[] benchFrames = new long[0];
    /** 計っている間の各フレームの終わりの時刻(紀元からのマイクロ秒)。遅いフレームの窓に入った JFR の標本を集める(2026-10-20)。 */
    private static long[] benchEndMicros = new long[0];
    /** 各フレームでいちばん長かった区間(描画の段階・フレームの間)。最悪の 10 フレームの表に出す(2026-10-20)。 */
    private static Stat[] benchTopSeg = new Stat[0];
    private static long[] benchTopSegCpu = new long[0];
    private static long[] benchModCpu = new long[0];
    private static long[] benchGpuFrame = new long[0];
    private static Stat[] benchTop = new Stat[0];
    private static long[] benchTopCpu = new long[0];
    private static int benchCount;
    private static long benchGcCount;
    private static long benchGcTime;
    private static long benchAlloc;
    private static long benchGpuFrames;
    private static long benchGpuModTotal;
    private static long benchOverhead;
    private static String benchResult;
    /** {@code bench … jfr}: 計っている間の描画のスレッドの JFR を取って集計する({@link RenderJfr})。 */
    private static boolean benchJfr;
    /** {@code bench … still}: 振り回さず、今の向きのまま測る。 */
    private static boolean benchStill;
    /** jfralloc: JFR に確保の記録も入れる(重い)。 */
    private static boolean benchJfrAlloc;
    /** fly / flyfast: 最初の向きへ毎秒この速さ(ブロック)でまっすぐ進みながら測る(クリエイティブの飛行 11・走って飛ぶ 22。2026-10-20)。0 なら動かない。 */
    private static double benchFly;
    private static double benchX0;
    private static double benchY0;
    private static double benchZ0;
    /** {@code bench … nogpu}: GPU のタイムスタンプを打たない(打つこと自体の重さを確かめる、2026-10-19)。 */
    private static boolean benchNoGpu;
    /** 計っている間の毎フレームの描いた区画の数の合計(平均を出す、2026-10-19)。 */
    private static long benchSectionSum;
    /** 区画を数えたフレームの数(8 フレームに 1 回だけ数える。数えるのも全部の見える区画を回るので、毎フレームだと測る側が重い、2026-10-20)。 */
    private static long benchSectionSamples;
    private static String benchJfrError;

    private FxProfiler() {
    }

    // ================= 命令 =================

    /** {@code fxprof on|off|hud on|hud off|reset}。返事の 1 行。 */
    static String command(String arg) {
        String a = arg.toLowerCase(Locale.ROOT).trim();
        if (a.startsWith("mute ")) {
            // 切り分け用(2026-10-23): 名前(クラス.メソッド @段階)にどれかの語を含む受け手を呼ばない。on のときだけ効く。
            mute = Arrays.asList(arg.trim().substring(5).trim().split("\\s+"));
            return "FXPROF mute " + mute + (on ? "" : "(on にしないと効かない)");
        }
        switch (a) {
            case "on" -> {
                return "FXPROF " + enable();
            }
            case "off" -> {
                return "FXPROF " + disable();
            }
            case "hud", "hud on" -> {
                hud = true;
                return "FXPROF 表を出す";
            }
            case "hud off" -> {
                hud = false;
                return "FXPROF 表を消す";
            }
            case "unmute" -> {
                mute = List.of();
                return "FXPROF mute 無し";
            }
            case "reset" -> {
                for (Stat s : STATS) {
                    s.emaCpu = 0;
                    s.emaGpu = 0;
                }
                return "FXPROF 平均を消した";
            }
            default -> {
                return "ERR fxprof on|off|hud on|hud off|reset";
            }
        }
    }

    /** {@code winsize <幅> <高さ>}: 窓の大きさ(画面の画素の数で GPU の塗る量が変わる。1280×720 は 1080p の半分以下)。 */
    static String winsize(Minecraft mc, String arg) {
        String[] p = arg.trim().split("\\s+");
        if (p.length != 2) return "ERR winsize <幅> <高さ>";
        int w = Integer.parseInt(p[0]);
        int h = Integer.parseInt(p[1]);
        GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), w, h);
        return "WINSIZE " + w + "x" + h + "(反映は次のフレームから。今は " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + ")";
    }

    /**
     * {@code bench <名前> <秒> [慣らしの秒]}。終わるまで null 以外(待ち)を返し、終わった呼び出しで結果の 1 行を {@code log} へ書いて null を返す。
     * DevBridge は待ちのあいだ同じ行を毎 tick 呼ぶ。
     */
    static String bench(Minecraft mc, String arg, Consumer<String> log, Path dir) {
        if (benchResult != null && arg.equals(benchArg)) {
            log.accept(benchResult);
            benchResult = null;
            benchArg = null;
            return null;
        }
        if (benchState != BenchState.IDLE) {
            return "bench の終わり";
        }
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return "ワールドの中(画面なし)";
        }
        String[] p = arg.trim().split("\\s+");
        if (p.length < 2) {
            log.accept("ERR bench <名前> <秒> [慣らしの秒]");
            return null;
        }
        benchName = p[0].replaceAll("[^A-Za-z0-9_.-]", "_");
        benchSeconds = Double.parseDouble(p[1]);
        benchWarmup = p.length > 2 && p[2].matches("[0-9.]+") ? Double.parseDouble(p[2]) : 3.0;
        benchJfrAlloc = Arrays.asList(p).contains("jfralloc");
        benchJfr = Arrays.asList(p).contains("jfr") || benchJfrAlloc;
        benchStill = Arrays.asList(p).contains("still");
        benchFly = Arrays.asList(p).contains("flyfast") ? 22.0 : Arrays.asList(p).contains("fly") ? 11.0 : 0.0;
        benchX0 = mc.player.getX();
        benchY0 = mc.player.getY();
        benchZ0 = mc.player.getZ();
        benchNoGpu = Arrays.asList(p).contains("nogpu");
        benchArg = arg;
        benchDir = dir;
        int cap = (int) Math.min(2_000_000, Math.ceil(benchSeconds * 1000));
        benchFrames = new long[cap];
        benchEndMicros = new long[cap];
        benchModCpu = new long[cap];
        benchGpuFrame = new long[cap];
        benchTop = new Stat[cap];
        benchTopCpu = new long[cap];
        benchTopSeg = new Stat[cap];
        benchTopSegCpu = new long[cap];
        benchCount = 0;
        benchYaw0 = mc.player.getYRot();
        benchStart = System.nanoTime();
        benchState = BenchState.WARMUP;
        log.accept(String.format(Locale.ROOT, "BENCH %s 開始(慣らし %.1f 秒 + %.1f 秒。計測 %s)", benchName, benchWarmup, benchSeconds,
                on ? "on" : "off"));
        return "bench の終わり";
    }

    private static Path benchDir;

    // ================= 受け手を包む =================

    private static String enable() {
        if (on) return "もう on";
        int classes = 0;
        int methods = 0;
        List<String> names = ModList.get().getModFileById(OvergrownVegetation.MODID).getFile().getScanResult()
                .getAnnotatedBy(EventBusSubscriber.class, ElementType.TYPE)
                .map(a -> a.clazz().getClassName())
                .filter(n -> n.contains(".client.") && !n.contains(".devbridge."))
                .toList();
        for (String name : names) {
            Class<?> c;
            try {
                c = Class.forName(name, false, FxProfiler.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            EventBusSubscriber ann = c.getAnnotation(EventBusSubscriber.class);
            if (ann == null || ann.bus() != EventBusSubscriber.Bus.GAME || !Arrays.asList(ann.value()).contains(Dist.CLIENT)) {
                continue;
            }
            List<Method> subs = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(SubscribeEvent.class) && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && Event.class.isAssignableFrom(m.getParameterTypes()[0])
                        && !Modifier.isAbstract(m.getParameterTypes()[0].getModifiers())) {
                    subs.add(m);
                }
            }
            if (subs.isEmpty()) continue;
            List<Consumer<? extends Event>> mine = new ArrayList<>();
            List<Object[]> plan = new ArrayList<>();
            try {
                for (Method m : subs) {
                    m.setAccessible(true);
                    MethodHandle mh = MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(void.class, Event.class));
                    plan.add(new Object[] {m, new Listener(c.getSimpleName() + "." + m.getName(), mh)});
                }
            } catch (IllegalAccessException | RuntimeException e) {
                continue; // 包めないクラスは元のまま
            }
            NeoForge.EVENT_BUS.unregister(c);
            for (Object[] entry : plan) {
                Method m = (Method) entry[0];
                Listener l = (Listener) entry[1];
                SubscribeEvent se = m.getAnnotation(SubscribeEvent.class);
                Consumer<Event> consumer = e -> around(l, e);
                addListener(se.priority(), se.receiveCanceled(), m.getParameterTypes()[0], consumer);
                mine.add(consumer);
                methods++;
            }
            WRAPPED_CLASSES.add(c);
            ADDED.addAll(mine);
            classes++;
        }
        on = true;
        return "on(包んだクラス " + classes + "・受け手 " + methods + "。GPU の時間 " + (gpuAvailable() ? "あり" : "なし(GL_TIMESTAMP が無い)") + ")";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addListener(EventPriority priority, boolean receiveCanceled, Class<?> type, Consumer<Event> consumer) {
        NeoForge.EVENT_BUS.addListener(priority, receiveCanceled, (Class) type, (Consumer) consumer);
    }

    private static String disable() {
        if (!on) return "もう off";
        for (Consumer<? extends Event> c : ADDED) {
            NeoForge.EVENT_BUS.unregister(c);
        }
        for (Class<?> c : WRAPPED_CLASSES) {
            NeoForge.EVENT_BUS.register(c);
        }
        int n = WRAPPED_CLASSES.size();
        ADDED.clear();
        WRAPPED_CLASSES.clear();
        on = false;
        return "off(" + n + " クラスを元に戻した)";
    }

    private static List<String> mute = List.of();

    private static void around(Listener l, Event e) {
        if (!mute.isEmpty() && e instanceof RenderLevelStageEvent r) {
            String n = l.name + " @" + stageName(r);
            for (String m : mute) {
                if (m.equals("*") || n.contains(m)) return;
            }
        }
        if (!RenderSystem.isOnRenderThread()) {
            invoke(l, e); // サーバーのスレッドなどから来たものは測らない(表を 1 つのスレッドだけで触る)
            return;
        }
        long t0 = System.nanoTime();
        Object key = e instanceof RenderLevelStageEvent r ? r.getStage() : e.getClass();
        Stat s = l.byKey.get(key);
        if (s == null) {
            s = new Stat(l.name + (e instanceof RenderLevelStageEvent r ? " @" + stageName(r) : " (" + e.getClass().getSimpleName() + ")"));
            l.byKey.put(key, s);
            STATS.add(s);
        }
        int q0 = s.lastFrameCpu >= GPU_GATE_NS ? stamp() : -1;
        int sq = q0 >= 0 && pendCount[slot] < pendStat[slot].length ? sampleQueries[slot][pendCount[slot]] : -1;
        if (sq >= 0) GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, sq);
        long t1 = System.nanoTime();
        try {
            invoke(l, e);
        } finally {
            long t2 = System.nanoTime();
            long dt = t2 - t1;
            s.cpuFrame += dt;
            s.callsFrame++;
            if (sq >= 0) GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            if (q0 >= 0) {
                int q1 = stamp();
                if (q1 >= 0 && pendCount[slot] < pendStat[slot].length) {
                    int i = pendCount[slot]++;
                    pendStat[slot][i] = s;
                    pendQ0[slot][i] = q0;
                    pendQ1[slot][i] = q1;
                    pendS[slot][i] = sq;
                }
            }
            overheadFrame += (t1 - t0) + (System.nanoTime() - t2);
        }
    }

    private static void invoke(Listener l, Event e) {
        try {
            l.handle.invokeExact(e);
        } catch (RuntimeException | Error ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String stageName(RenderLevelStageEvent r) {
        String s = r.getStage().toString();
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    // ================= GPU =================

    private static boolean gpuAvailable() {
        if (!gpuChecked) {
            gpuChecked = true;
            GLCapabilities caps = GL.getCapabilities();
            gpuOk = caps.OpenGL33 || caps.GL_ARB_timer_query;
        }
        return gpuOk;
    }

    /** 今のスロットでタイムスタンプを 1 つ打つ。打てなければ -1。 */
    private static int stamp() {
        if (!gpuAvailable()) return -1;
        if (queries == null) {
            queries = new int[SLOTS][MAX_QUERIES];
            for (int i = 0; i < SLOTS; i++) GL15.glGenQueries(queries[i]);
            sampleQueries = new int[SLOTS][MAX_QUERIES / 2];
            for (int i = 0; i < SLOTS; i++) GL15.glGenQueries(sampleQueries[i]);
        }
        if (used[slot] >= MAX_QUERIES) return -1;
        int id = queries[slot][used[slot]++];
        GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
        return id;
    }

    /** {@code s} のスロットの結果を読んで表へ足し、スロットを空ける。まだ出ていなければ読まずに捨てる(待たない)。 */
    private static void collect(int s) {
        if (queries != null && used[s] > 0) {
            int last = queries[s][used[s] - 1];
            if (GL15.glGetQueryObjecti(last, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long mod = 0;
                for (int i = 0; i < pendCount[s]; i++) {
                    long ns = GL33.glGetQueryObjecti64(pendQ1[s][i], GL15.GL_QUERY_RESULT)
                            - GL33.glGetQueryObjecti64(pendQ0[s][i], GL15.GL_QUERY_RESULT);
                    pendStat[s][i].gpuFrameNs += Math.max(0, ns);
                    if (!pendStat[s][i].segment) mod += Math.max(0, ns);
                    int sq = pendS[s][i];
                    if (sq >= 0 && GL15.glGetQueryObjecti(sq, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                        pendStat[s][i].samplesFrame += Integer.toUnsignedLong(GL15.glGetQueryObjecti(sq, GL15.GL_QUERY_RESULT));
                    }
                }
                long frame = frameQ0[s] >= 0 && frameQ1[s] >= 0
                        ? GL33.glGetQueryObjecti64(frameQ1[s], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(frameQ0[s], GL15.GL_QUERY_RESULT)
                        : -1;
                for (Stat st : STATS) {
                    st.emaGpu = st.emaGpu * 0.95 + st.gpuFrameNs * 0.05;
                    if (slotBenchFrame[s] >= 0) {
                        st.benchGpu += st.gpuFrameNs;
                        st.benchSamples += st.samplesFrame;
                    }
                    st.gpuFrameNs = 0;
                    st.samplesFrame = 0;
                }
                emaGpuMod = emaGpuMod * 0.95 + mod * 0.05;
                if (frame >= 0) {
                    emaGpuFrame = emaGpuFrame * 0.95 + frame * 0.05;
                    int bf = slotBenchFrame[s];
                    if (bf >= 0 && bf < benchGpuFrame.length) {
                        benchGpuFrame[bf] = frame;
                        benchGpuFrames++;
                        benchGpuModTotal += mod;
                    }
                }
            }
        }
        used[s] = 0;
        pendCount[s] = 0;
        frameQ0[s] = -1;
        frameQ1[s] = -1;
        slotBenchFrame[s] = -1;
    }

    // ================= フレーム =================

    /**
     * フレームとフレームの間(前のフレームの {@code RenderFrameEvent.Post} から次の Pre まで)も区間として数える(2026-10-20)。
     * ここに画面の入れ替え・届いた通信(チャンク)の処理・クライアントの tick が入る。描画の段階の区間だけでは、飛行中の遅いフレームの元が見えなかった。
     */
    private static long gapPostNs;
    private static long gapTickStartNs;
    private static long gapTickEndNs;
    private static long gapTickSum;
    private static long gapTickPreNs;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickPreGap(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        if (!ENABLED || gapPostNs == 0) return;
        long now = System.nanoTime();
        if (gapTickStartNs == 0) gapTickStartNs = now;
        gapTickPreNs = now;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickPostGap(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!ENABLED || gapTickPreNs == 0) return;
        long now = System.nanoTime();
        gapTickSum += now - gapTickPreNs;
        gapTickEndNs = now;
        gapTickPreNs = 0;
    }

    private static void addGap(String key, long ns) {
        if (ns <= 0) return;
        Stat s = SEGMENTS.get(key);
        if (s == null) {
            s = new Stat("区間 " + key);
            s.segment = true;
            SEGMENTS.put(key, s);
            STATS.add(s);
        }
        s.cpuFrame += ns;
        s.callsFrame++;
    }

    private static void closeGap(long now) {
        if (gapPostNs != 0 && (on || benchState != BenchState.IDLE)) {
            if (gapTickStartNs != 0) {
                addGap("フレームの終わり → tick の始め(画面の入れ替え・届いた通信)", gapTickStartNs - gapPostNs);
                addGap("tick(クライアント。この MOD の tick の受け手も)", gapTickSum);
                addGap("tick の終わり → フレームの始め", now - gapTickEndNs);
            } else {
                addGap("フレームの終わり → フレームの始め(tick なし。画面の入れ替え・届いた通信)", now - gapPostNs);
            }
        }
        gapPostNs = 0;
        gapTickStartNs = 0;
        gapTickEndNs = 0;
        gapTickSum = 0;
        gapTickPreNs = 0;
    }

    @SubscribeEvent
    public static void onFramePre(RenderFrameEvent.Pre event) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        closeGap(now);
        if (lastPre != 0) {
            endFrame(now - lastPre);
        }
        lastPre = now;
        // 次のスロットへ。そこに残っている SLOTS フレーム前の結果を読む。
        slot = (slot + 1) % SLOTS;
        collect(slot);
        if (on || benchState != BenchState.IDLE && !benchNoGpu) {
            frameQ0[slot] = stamp();
            segPrev = "フレームの始め";
            segPrevNs = now;
            segPrevQ = frameQ0[slot];
        } else {
            segPrev = null;
        }
        if (benchState == BenchState.RUN) slotBenchFrame[slot] = benchCount;
        if (benchState == BenchState.RUN && (benchCount & 7) == 0 && Minecraft.getInstance().levelRenderer != null) {
            benchSectionSamples++;
            benchSectionSum += Minecraft.getInstance().levelRenderer.countRenderedSections();
        }
        swing(now);
    }

    @SubscribeEvent
    public static void onFramePost(RenderFrameEvent.Post event) {
        if (!ENABLED) return;
        if (segPrev != null) {
            mark("フレームの終わり");
            segPrev = null;
        }
        if (frameQ0[slot] >= 0) frameQ1[slot] = stamp();
        gapPostNs = System.nanoTime();
    }

    private static void endFrame(long frameNs) {
        long modCpu = 0;
        Stat top = null;
        long topCpu = 0;
        Stat topSeg = null;
        long topSegCpu = 0;
        for (Stat s : STATS) {
            s.lastFrameCpu = s.cpuFrame;
            s.emaCpu = s.emaCpu * 0.95 + s.cpuFrame * 0.05;
            if (!s.segment) modCpu += s.cpuFrame;
            if (s.segment && s.cpuFrame > topSegCpu) {
                topSegCpu = s.cpuFrame;
                topSeg = s;
            }
            if (!s.segment && s.cpuFrame > topCpu) {
                topCpu = s.cpuFrame;
                top = s;
            }
            if (benchState == BenchState.RUN) {
                s.benchCpu += s.cpuFrame;
                s.benchCalls += s.callsFrame;
                s.benchMaxCpu = Math.max(s.benchMaxCpu, s.cpuFrame);
            }
            s.cpuFrame = 0;
            s.callsFrame = 0;
        }
        emaOverhead = emaOverhead * 0.95 + overheadFrame * 0.05;
        emaFrame = emaFrame == 0 ? frameNs : emaFrame * 0.95 + frameNs * 0.05;
        recent[recentNext] = frameNs;
        recentNext = (recentNext + 1) % RECENT;
        recentCount = Math.min(RECENT, recentCount + 1);
        if (++framesSinceLow >= 30) {
            framesSinceLow = 0;
            recentLow1 = low1(Arrays.copyOf(recent, recentCount), recentCount);
        }
        if (benchState == BenchState.RUN && benchCount < benchFrames.length) {
            benchFrames[benchCount] = frameNs;
            java.time.Instant endAt = java.time.Instant.now();
            benchEndMicros[benchCount] = endAt.getEpochSecond() * 1_000_000L + endAt.getNano() / 1000L;
            benchModCpu[benchCount] = modCpu;
            benchTop[benchCount] = top;
            benchTopCpu[benchCount] = topCpu;
            benchTopSeg[benchCount] = topSeg;
            benchTopSegCpu[benchCount] = topSegCpu;
            benchOverhead += overheadFrame;
            benchCount++;
        }
        overheadFrame = 0;
    }

    /** 遅い方から 1% のフレームの平均(ナノ秒)。{@code a} は並べ替えてよい写し。 */
    private static double low1(long[] a, int n) {
        if (n == 0) return 0;
        Arrays.sort(a, 0, n);
        int k = Math.max(1, n / 100);
        double sum = 0;
        for (int i = n - k; i < n; i++) sum += a[i];
        return sum / k;
    }

    // ================= 振り回し =================

    private static void swing(long now) {
        if (benchState == BenchState.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        double t = (now - benchStart) / 1e9;
        if (player == null || mc.level == null) {
            finishBench(mc, "ワールドから出た");
            return;
        }
        // 毎秒 150° で回りながら、4 秒で上下に ±30°(水平の少し下を中心に)。時計で決めるので FPS によらず同じ見え方の列になる。
        // still のときは向きを変えない(ある方向を見ているときの重さ。2026-10-18)。
        if (!benchStill) {
            float yaw = benchYaw0 + (float) (150.0 * t);
            float pitch = (float) (5.0 + 30.0 * Math.sin(2.0 * Math.PI * t / 4.0));
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yRotO = yaw;
            player.xRotO = pitch;
            player.setYHeadRot(yaw);
            player.yHeadRotO = yaw;
        }
        if (benchFly > 0.0) {
            double yawRad = Math.toRadians(benchYaw0);
            double d = benchFly * t;
            double x = benchX0 - Math.sin(yawRad) * d;
            double z = benchZ0 + Math.cos(yawRad) * d;
            player.setPos(x, benchY0, z);
            player.xo = x;
            player.yo = benchY0;
            player.zo = z;
            player.xOld = x;
            player.yOld = benchY0;
            player.zOld = z;
            player.setDeltaMovement(0.0, 0.0, 0.0);
        }
        if (benchState == BenchState.WARMUP && t >= benchWarmup) {
            startRecording();
        } else if (benchState == BenchState.RUN && ((now - benchRunStart) / 1e9 >= benchSeconds || benchCount >= benchFrames.length)) {
            finishBench(mc, null);
        }
    }

    private static void startRecording() {
        benchState = BenchState.RUN;
        benchRunStart = System.nanoTime();
        benchCount = 0;
        benchGpuFrames = 0;
        benchGpuModTotal = 0;
        benchOverhead = 0;
        Arrays.fill(benchGpuFrame, -1);
        benchSectionSum = 0;
        benchSectionSamples = 0;
        for (Stat s : STATS) {
            s.benchCpu = 0;
            s.benchCalls = 0;
            s.benchMaxCpu = 0;
            s.benchGpu = 0;
            s.benchSamples = 0;
        }
        benchGcCount = gcCount();
        benchGcTime = gcTime();
        benchAlloc = allocated();
        benchJfrError = benchJfr ? RenderJfr.start(benchJfrAlloc) : null;
    }

    private static void finishBench(Minecraft mc, String aborted) {
        double seconds = (System.nanoTime() - benchRunStart) / 1e9;
        long gcCount = gcCount() - benchGcCount;
        long gcTime = gcTime() - benchGcTime;
        long alloc = allocated() - benchAlloc;
        benchState = BenchState.IDLE;
        int n = benchCount;
        StringBuilder out = new StringBuilder();
        String head;
        if (aborted != null || n == 0) {
            head = "BENCH " + benchName + " 中止: " + (aborted != null ? aborted : "フレームが取れなかった");
            benchResult = head;
            return;
        }
        long[] sorted = Arrays.copyOf(benchFrames, n);
        double avg = 0;
        for (int i = 0; i < n; i++) avg += benchFrames[i];
        avg /= n;
        double low1 = low1(Arrays.copyOf(benchFrames, n), n);
        Arrays.sort(sorted);
        double p50 = sorted[n / 2];
        double p99 = sorted[Math.min(n - 1, (int) Math.floor(n * 0.99))];
        double low01 = 0;
        {
            int k = Math.max(1, n / 1000);
            for (int i = n - k; i < n; i++) low01 += sorted[i];
            low01 /= k;
        }
        long gpuSum = 0;
        int gpuN = 0;
        for (int i = 0; i < n; i++) {
            if (benchGpuFrame[i] >= 0) {
                gpuSum += benchGpuFrame[i];
                gpuN++;
            }
        }
        double modCpuAvg = 0;
        for (int i = 0; i < n; i++) modCpuAvg += benchModCpu[i];
        modCpuAvg /= n;
        head = String.format(Locale.ROOT, "BENCH %s %d フレーム %.1f 秒: [%s・平均 %.0f 区画] 平均 %.1f FPS(%.2f ms)・中央 %.2f ms・99%% %.2f ms・1%% の遅い値 %.2f ms(%.1f FPS)・0.1%% %.2f ms・最悪 %.2f ms / GPU のフレーム %s / この MOD の受け手 CPU %.2f ms/フレーム(計測 %s)/ GC %d 回 %d ms",
                benchName, n, seconds, sectionsShort(mc), (double) benchSectionSum / Math.max(1, benchSectionSamples), 1e9 / avg, avg / 1e6, p50 / 1e6, p99 / 1e6, low1 / 1e6, 1e9 / low1, low01 / 1e6, sorted[n - 1] / 1e6,
                gpuN > 0 ? String.format(Locale.ROOT, "%.2f ms", gpuSum / 1e6 / gpuN) : "-",
                modCpuAvg / 1e6, on ? "on" : "off", gcCount, gcTime);
        // 2026-11-23(軽量化 GPU): 速い方の分位。同じ GPU をほかのクライアントが使っていると平均・中央が揺れるので、揺れに強い比べ用に。
        head += String.format(Locale.ROOT, " / 速い方 10%% %.2f ms・25%% %.2f ms", sorted[n / 10] / 1e6, sorted[n / 4] / 1e6);
        out.append(head).append('\n').append('\n');
        out.append("引数: ").append(benchArg).append('\n');
        out.append("区画(F3 の C: 描いた区画/全部): ").append(mc.levelRenderer.getSectionStatistics()).append('\n');
        out.append(String.format(Locale.ROOT, "GL: デバッグの文脈 %s・デバッグ出力 %s・glDebugVerbosity %d%n",
                (GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_CONTEXT_FLAGS) & org.lwjgl.opengl.GL43.GL_CONTEXT_FLAG_DEBUG_BIT) != 0,
                GL11.glIsEnabled(org.lwjgl.opengl.GL43.GL_DEBUG_OUTPUT), mc.options.glDebugVerbosity));
        out.append(String.format(Locale.ROOT, "窓 %dx%d・描画距離 %d・グラフィック %s・FPS の上限 %d・垂直同期 %s・雲 %s・GPU %s%n",
                mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.renderDistance().get(), mc.options.graphicsMode().get(),
                mc.options.framerateLimit().get(), mc.options.enableVsync().get(), mc.options.cloudStatus().get(),
                GL11.glGetString(GL11.GL_RENDERER)));
        if (mc.player != null) {
            out.append(String.format(Locale.ROOT, "場所 %.1f %.1f %.1f・次元 %s・時刻 %d%n", mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    mc.level.dimension().location(), mc.level.getDayTime()));
        }
        out.append(String.format(Locale.ROOT, "描画のスレッドが確保したメモリ %.1f MB(%.1f MB/秒)・GC %d 回 %d ms・測る側の重さ %.3f ms/フレーム%n",
                alloc / 1e6, alloc / 1e6 / seconds, gcCount, gcTime, benchOverhead / 1e6 / n));
        out.append("1% の遅い値 = 遅い方から 1% のフレームの平均。GPU = フレームの始めから終わりまでのタイムスタンプの差(GUI を含む)。\n");
        if (on) {
            out.append(String.format(Locale.ROOT, "この MOD の受け手の GPU 合計 %.3f ms/フレーム(GPU を測れたフレーム %d)%n",
                    benchGpuFrames > 0 ? benchGpuModTotal / 1e6 / benchGpuFrames : 0.0, benchGpuFrames));
            out.append("見えないもの: バニラのシェーダーの差し替え(葉の揺れ・林冠の影など)は地形の描画の中に入る。設定で切って撮り比べる。\n");
            double screen = (double) mc.getMainRenderTarget().width * mc.getMainRenderTarget().height;
            out.append("塗った画素/画面 = 奥行きの試しを通った断片(GL_SAMPLES_PASSED)÷ 主画面の画素(" + mc.getMainRenderTarget().width + "×" + mc.getMainRenderTarget().height
                    + ")。半透明の重ね塗りなら、画面の平均で何回重ねたか。\n");
            out.append('\n').append("受け手(重い順) | CPU 平均 ms/フレーム | CPU 最大 ms | GPU 平均 ms/フレーム | 呼ばれた回数/フレーム | 塗った画素/画面\n");
            List<Stat> rows = new ArrayList<>(STATS);
            rows.sort(Comparator.comparingDouble((Stat s) -> -(s.benchCpu + s.benchGpu)));
            for (Stat s : rows) {
                if (s.segment || s.benchCpu == 0 && s.benchGpu == 0) continue;
                out.append(String.format(Locale.ROOT, "%s | %.3f | %.2f | %s | %.1f | %s%n", s.label, s.benchCpu / 1e6 / n, s.benchMaxCpu / 1e6,
                        benchGpuFrames > 0 ? String.format(Locale.ROOT, "%.3f", s.benchGpu / 1e6 / benchGpuFrames) : "-",
                        (double) s.benchCalls / n,
                        benchGpuFrames > 0 && screen > 0 ? String.format(Locale.ROOT, "%.3f", s.benchSamples / screen / benchGpuFrames) : "-"));
            }
        }
        if (!SEGMENTS.isEmpty()) {
            out.append('\n').append("描画の段階の区間(フレームの順) | CPU 平均 ms/フレーム | CPU 最大 ms | GPU 平均 ms/フレーム\n");
            for (Stat s : SEGMENTS.values()) {
                if (s.benchCalls == 0) continue;
                out.append(String.format(Locale.ROOT, "%s | %.3f | %.2f | %s%n", s.label, s.benchCpu / 1e6 / n, s.benchMaxCpu / 1e6,
                        benchGpuFrames > 0 ? String.format(Locale.ROOT, "%.3f", s.benchGpu / 1e6 / benchGpuFrames) : "-"));
            }
        }
        if (benchJfr) {
            if (benchJfrError != null) {
                out.append("JFR を始められなかった: ").append(benchJfrError).append('\n');
            }
            // 遅い方から 1% のフレームの時間の窓(始め・終わり、マイクロ秒)
            int slowN = Math.max(1, n / 100);
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> Long.compare(benchFrames[y], benchFrames[x]));
            long[][] windows = new long[Math.min(slowN, n)][2];
            for (int k = 0; k < windows.length; k++) {
                int i = order[k];
                windows[k][1] = benchEndMicros[i];
                windows[k][0] = benchEndMicros[i] - benchFrames[i] / 1000L;
            }
            for (String line : RenderJfr.stop(benchDir.resolve("bench-" + benchName + ".jfr"), windows)) {
                out.append(line).append('\n');
            }
        }
        out.append('\n').append("最悪の 10 フレーム | フレーム ms | GPU ms | この MOD の CPU ms | いちばん CPU を使った受け手(ms) | いちばん長かった区間(ms)\n");
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingLong((Integer i) -> -benchFrames[i]));
        for (int j = 0; j < Math.min(10, n); j++) {
            int i = idx[j];
            out.append(String.format(Locale.ROOT, "#%d(%.2f 秒目) | %.2f | %s | %.2f | %s%n", i, sumSeconds(i), benchFrames[i] / 1e6,
                    benchGpuFrame[i] >= 0 ? String.format(Locale.ROOT, "%.2f", benchGpuFrame[i] / 1e6) : "-", benchModCpu[i] / 1e6,
                    (benchTop[i] == null ? "-" : benchTop[i].label + String.format(Locale.ROOT, " (%.2f)", benchTopCpu[i] / 1e6)) + " | " + (benchTopSeg[i] == null ? "-" : benchTopSeg[i].label.replace("区間 ", "") + String.format(Locale.ROOT, " (%.2f)", benchTopSegCpu[i] / 1e6))));
        }
        try {
            Files.writeString(benchDir.resolve("bench-" + benchName + ".txt"), out.toString(), StandardCharsets.UTF_8);
            benchResult = head + " → " + benchDir.resolve("bench-" + benchName + ".txt").toAbsolutePath();
        } catch (Exception e) {
            benchResult = head + "(ファイルに書けない: " + e + ")";
        }
    }

    /** F3 の「C: 描いた/全部」の部分だけ(描いた区画が多いほど、区画ごとの GL の呼び出しが増える)。 */
    private static String sectionsShort(Minecraft mc) {
        String s = mc.levelRenderer.getSectionStatistics();
        int slash = s.indexOf('/');
        int sp = slash < 0 ? -1 : s.indexOf(' ', slash);
        return sp > 0 ? s.substring(0, sp) : s;
    }

    private static double sumSeconds(int frame) {
        double s = 0;
        for (int i = 0; i < frame; i++) s += benchFrames[i];
        return s / 1e9;
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, b.getCollectionCount());
        return c;
    }

    private static long gcTime() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, b.getCollectionTime());
        return c;
    }

    private static long allocated() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t) {
            return t.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return 0;
    }

    // ================= 画面の表 =================

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (!ENABLED || !hud) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "FPS %d  フレーム %.2f ms  1%%low %.2f ms (%.0f FPS)", mc.getFps(), emaFrame / 1e6, recentLow1 / 1e6,
                recentLow1 > 0 ? 1e9 / recentLow1 : 0));
        lines.add(String.format(Locale.ROOT, "GPU フレーム %.2f ms  MOD の GPU %.2f ms  測る側 %.3f ms%s", emaGpuFrame / 1e6, emaGpuMod / 1e6,
                emaOverhead / 1e6, on ? "" : "  (fxprof off)"));
        if (on) {
            List<Stat> rows = new ArrayList<>(STATS);
            rows.removeIf(s -> s.segment);
            rows.sort(Comparator.comparingDouble((Stat s) -> -(s.emaCpu + s.emaGpu)));
            for (int i = 0; i < Math.min(12, rows.size()); i++) {
                Stat s = rows.get(i);
                if (s.emaCpu + s.emaGpu < 1_000) break;
                lines.add(String.format(Locale.ROOT, "%5.2f / %5.2f  %s", s.emaCpu / 1e6, s.emaGpu / 1e6, s.label));
            }
        }
        int y = 4;
        int w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        g.fill(2, 2, 8 + w, 6 + lines.size() * 10, 0x90000000);
        for (String l : lines) {
            g.drawString(mc.font, l, 5, y, 0xFFE0E0E0, false);
            y += 10;
        }
    }
}
