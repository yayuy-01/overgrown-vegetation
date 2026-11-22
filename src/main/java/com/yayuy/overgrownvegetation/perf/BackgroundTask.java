package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * <b>重い計算だけを別スレッドで</b>回す仕組み(2026-10-06、ユーザー指定の ④)。
 *
 * <h2>何を別スレッドへ出してよいか</h2>
 * <p><b>ワールドを読まない計算だけ</b>。台帳(過繁茂のチャンクの一覧)全体を舐める計算 —— 台帳の縁・極相の判定・生態系の収入 —— は、
 * 汚染が広がるほど(台帳のチャンク数に比例して)重くなるので、ここへ出す。ワールドのブロック・エンティティ・SavedData は
 * サーバースレッドしか触ってはいけない(Minecraft のデータは同時に書かれることを考えていない)ので、渡すのは
 * サーバースレッドで作った<b>写し</b>だけ({@code PhaseData#snapshotLedger} は中の配列ごと写すので 10 万件でも 1 ms 未満)。
 *
 * <h2>流れ</h2>
 * <ol>
 *     <li>サーバースレッドで写しを作り、{@link #start} に「写しから答えを出す計算」と「答えを当てはめる処理」を渡す</li>
 *     <li>計算は専用のスレッド(1 本、{@value #THREAD_NAME})で走る。その間もゲームは進む</li>
 *     <li>答えは次の tick の頭({@link #onServerTickPre})にサーバースレッドで当てはめる。写しを作ってから当てはめるまでに
 *     台帳が変わっていることがあるので、当てはめる側で今の状態と照らし合わせる(既に印の付いたチャンクを外すなど)</li>
 * </ol>
 * 走っている間は同じ仕事を重ねて始めない({@link #running})。ワールドを閉じたら、走っていた計算の答えは捨てる。
 *
 * <p>GameTest のサーバーでは<b>その場で</b>計算して当てはめる(別スレッドを待つとテストの判定がずれる)。
 * 別スレッドの道そのものは {@code PerfGameTests} が {@link #asyncEvenInTests} を付けた仕事で確かめる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class BackgroundTask<R> {

    static final String THREAD_NAME = "Overgrown Vegetation background";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, THREAD_NAME);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    /** 計算を終えて、サーバースレッドで当てはめるのを待っているもの。 */
    private static final ConcurrentLinkedQueue<Runnable> DONE = new ConcurrentLinkedQueue<>();
    /** ワールドごとの番号。閉じたら進め、前のワールドで始めた計算の答えを捨てる。 */
    private static volatile int generation;
    private static final List<BackgroundTask<?>> ALL = new ArrayList<>();
    /** GameTest のサーバーでも別スレッドで回す(別スレッドの道を確かめるテストだけが立てる)。 */
    private boolean asyncInTests;

    /** {@code /ov perf} に出す名前。 */
    public final String label;
    private boolean running;
    private volatile long lastNanos;
    private volatile long lastWaitTicks;
    private volatile int runs;

    public BackgroundTask(String label) {
        this.label = label;
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    /** GameTest のサーバーでもその場で計算せず、別スレッドで回す(別スレッドの道を確かめるテストの仕事だけに付ける)。 */
    public BackgroundTask<R> asyncEvenInTests() {
        this.asyncInTests = true;
        return this;
    }

    /** 計算が走っているか(答えをまだ当てはめていないか)。サーバースレッドから。 */
    public boolean running() {
        return running;
    }

    /**
     * {@code compute} を別スレッドで回し、答えを次の tick の頭にサーバースレッドで {@code apply} に渡す。サーバースレッドから呼ぶ。
     * {@code compute} は写しだけを読むこと。失敗したら(例外)答えは当てはめず、次にまた始められる。
     */
    public void start(MinecraftServer server, Supplier<R> compute, Consumer<R> apply) {
        if (running) {
            return;
        }
        if (server instanceof GameTestServer && !asyncInTests) {
            long t0 = System.nanoTime();
            R result = compute.get();
            lastNanos = System.nanoTime() - t0;
            runs++;
            apply.accept(result);
            return;
        }
        running = true;
        int startedIn = generation;
        int startedTick = server.getTickCount();
        WORKER.execute(() -> {
            long t0 = System.nanoTime();
            R result;
            try {
                result = compute.get();
            } catch (Throwable e) {
                OvergrownVegetation.LOGGER.error("別スレッドの計算に失敗: {}", label, e);
                DONE.add(() -> running = false);
                return;
            }
            lastNanos = System.nanoTime() - t0;
            DONE.add(() -> {
                running = false;
                if (startedIn != generation) {
                    return; // 計算している間にワールドが閉じられた
                }
                runs++;
                lastWaitTicks = server.getTickCount() - startedTick;
                apply.accept(result);
            });
        });
    }

    /** 終わった計算の答えを当てはめる(tick の頭、ほかの処理より先に)。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        Runnable done;
        while ((done = DONE.poll()) != null) {
            done.run();
        }
    }

    /** ワールドを閉じたら、走っている計算の答えを捨てる(走っている印は、答えが戻ったときに下ろす)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        generation++;
    }

    // ---------------------------------------------------------------- 表示用

    /** 直近の 1 回の計算にかかった時間(ns。別スレッドの時間で、サーバーの 1 tick には乗らない)。 */
    public long lastNanos() {
        return lastNanos;
    }

    /** 直近の 1 回、始めてから当てはめるまでに経った tick。 */
    public long lastWaitTicks() {
        return lastWaitTicks;
    }

    /** これまでに当てはめた回数。 */
    public int runs() {
        return runs;
    }

    /** 作られた全部の仕事({@code /ov perf} の表)。 */
    public static List<BackgroundTask<?>> all() {
        synchronized (ALL) {
            return List.copyOf(ALL);
        }
    }
}
