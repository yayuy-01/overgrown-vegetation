package com.yayuy.overgrownvegetation.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * 汚染地域の濃い霧で描かずにいる区画(霧の打ち切り、{@code LevelRendererFogCullMixin})も、変わったものは 1 フレームに数個ずつ裏で組み立てておく(2026-10-20)。
 *
 * <p>バニラは見える区画の一覧にある区画しか組み立て直さない。霧で一覧から外した区画は、汚染で中身が変わっても、中にいる間ずっと古いまま(汚れたまま)残る。
 * 汚染地域を出ると霧の打ち切りが外れ、溜まっていた何千もの区画を一度に組み立て・送り込むので、出た瞬間に FPS が 60 台まで落ちていた(ユーザー 10-20)。
 * ここで少しずつ片付けておけば、出たときにはもう出来上がっている。打ち切った区画の一覧は描画のスレッドだけで使う。
 */
public final class FogCullBacklog {
    /** devbridge の {@code fogbacklog on|off}。 */
    public static volatile boolean enabled = true;
    /** 1 フレームに組み立てへ回す上限・1 秒の上限・待ち行列がこれより多いときは回さない・1 フレームに汚れを見て回る数(仮決め)。 */
    static final int PER_FRAME = 2;
    static final double PER_SECOND = 40.0;
    static final int QUEUE_LIMIT = 2;
    static final int SCAN_PER_FRAME = 128;
    private static double tokens;
    private static long lastNanos;

    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> CULLED = new ObjectArrayList<>();
    private static int cursor;
    /** 組み立てへ回した数(devbridge の表示用)。 */
    public static long scheduled;

    /** 霧の打ち切りを作り直す前・打ち切らないとき。 */
    public static void clear() {
        CULLED.clear(); // 見回りの位置は戻さない(作り直しは毎フレームのこともあり、戻すと先頭の数百しか見なくなる)
    }

    /** 霧で一覧から外した区画(見える区画の順 = 近い順)。 */
    public static void add(SectionRenderDispatcher.RenderSection section) {
        CULLED.add(section);
    }

    public static int size() {
        return CULLED.size();
    }

    /**
     * {@code compileSections} の頭。外した区画のうち汚れたものを、組み立てる一覧の後ろへ足す。
     * 見える区画の組み立てを待たせないよう、組み立ての待ち行列がほぼ空のときだけ・1 秒に {@link #PER_SECOND} まで
     * (最初は 1 フレームに 3 つにしたら、密な森で毎秒 900 区画を組み立てて作業スレッドと GC を食い、巨大樹の下で 329 → 193 FPS に落ちた)。
     */
    public static void addDirty(List<SectionRenderDispatcher.RenderSection> out, int queuedCompiles) {
        long now = System.nanoTime();
        if (lastNanos != 0L) {
            tokens = Math.min(PER_FRAME, tokens + (now - lastNanos) * 1.0E-9 * PER_SECOND);
        }
        lastNanos = now;
        int n = CULLED.size();
        if (!enabled || n == 0 || queuedCompiles > QUEUE_LIMIT || tokens < 1.0) {
            return;
        }
        int found = 0;
        int scan = Math.min(n, SCAN_PER_FRAME);
        for (int k = 0; k < scan && found < PER_FRAME && tokens >= 1.0; k++) {
            if (cursor >= n) {
                cursor = 0;
            }
            SectionRenderDispatcher.RenderSection section = CULLED.get(cursor++);
            if (section.isDirty()) {
                out.add(section);
                found++;
                tokens -= 1.0;
            }
        }
        scheduled += found;
    }

    private FogCullBacklog() {
    }
}
