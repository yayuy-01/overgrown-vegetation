package com.yayuy.overgrownvegetation.client;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * クライアントのワールドを読んで作る地図(林冠の日向の地図など)専用の作業スレッド(2026-10-20 軽量化)。
 * 描画のスレッドで 1 tick に少しずつ作っていたものを、まとめてここで作る(読むだけ・書かない)。1 本だけ・優先度を下げて、
 * チャンクの準備({@code Worker-Main})と雲({@link CloudThread})を待たせない。
 */
final class ScanThread {
    static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OV-Scan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private ScanThread() {
    }
}
