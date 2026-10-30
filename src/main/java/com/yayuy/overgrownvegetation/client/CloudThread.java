package com.yayuy.overgrownvegetation.client;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 雲の形・影の組み立て専用の作業スレッド(2026-10-19 軽量化)。
 *
 * <p>それまでは {@code Util.backgroundExecutor()}(= {@code Worker-Main}。チャンクの生成と描画の準備と共用)に投げていた。雲は風で流れるので
 * 止まっていても数十秒ごとに作り直し、積乱雲の組み立て・雲の自分の影でその作業スレッドを使い続け、チャンクの準備と CPU を取り合っていた
 * (描画距離 16 で、素の NeoForge より作業スレッドの標本が 56% 多く、増えた分のほとんどが雲。6 コアの CPU では描画のスレッドも押される)。
 * ここでは 1 本だけ・優先度を下げて動かす(雲の形が届くのが少し遅れることはあるが、地形の準備を待たせない)。
 */
final class CloudThread {
    static final Executor EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OV-Clouds");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private CloudThread() {
    }
}
