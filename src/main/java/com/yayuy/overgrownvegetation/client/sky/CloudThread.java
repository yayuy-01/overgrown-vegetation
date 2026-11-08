package com.yayuy.overgrownvegetation.client.sky;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 雲の形・影の組み立て専用の作業スレッド(2026-10-19 軽量化。2026-11-08 雲の段階 1 で parked から戻した)。
 * バニラの作業スレッド(チャンクの生成と描画の準備と共用)と CPU を取り合わないよう、1 本だけ・優先度を下げて動かす。
 */
public final class CloudThread {
    public static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OV-Clouds");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private CloudThread() {
    }
}
