package com.yayuy.overgrownvegetation.client;

import java.util.Queue;

/**
 * 組み立て終わったチャンクの区画を GPU へ送るのを、1 フレームに {@link #BUDGET_NANOS} までにする(2026-10-20)。
 *
 * <p>バニラ({@code SectionRenderDispatcher#uploadAllPendingUploads}、毎フレームの {@code compileSections} から)は溜まった送り込みを一度に全部流す。
 * 飛んでいて新しいチャンクが次々届くと、1 フレームに何十もの区画をまとめて送り、そのフレームだけ長くなる(垂直同期 100 Hz では 10 ms を超えた
 * フレームの間 50 FPS 台に見える)。残りは次のフレームへ回すだけなので、区画が出るのが数フレーム遅れることはあるが、出る物は同じ。
 * 少なくとも 1 つは毎フレーム送る。ほかの所(ワールドを閉じる・描画距離の変更)の全部流すのはバニラのまま。描画のスレッドだけ。
 */
public final class SectionUploadBudget {
    /** devbridge の {@code uploadbudget on|off}。 */
    public static volatile boolean enabled = true;
    /** 1 フレームに送り込みへ使う時間(仮決め 2 ms)。 */
    static final long BUDGET_NANOS = 2_000_000L;
    /** 次のフレームへ回した数(devbridge の表示用)。 */
    public static long deferred;

    /** @return false ならバニラのまま全部流す */
    public static boolean uploadSome(Queue<Runnable> queue) {
        if (!enabled) {
            return false;
        }
        long start = System.nanoTime();
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
            if (System.nanoTime() - start > BUDGET_NANOS) {
                if (!queue.isEmpty()) {
                    deferred++;
                }
                break;
            }
        }
        return true;
    }

    private SectionUploadBudget() {
    }
}
