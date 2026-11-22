package com.yayuy.overgrownvegetation.devbridge;

import com.yayuy.overgrownvegetation.client.PhyllosensGazeEdge;
import com.yayuy.overgrownvegetation.client.PhyllosensGazeEyes;
import com.yayuy.overgrownvegetation.network.PhyllosensGazePayload;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * テストクライアントの「見られているときの画面の縁」の命令(2026-11-14)。塔を立てずに、サーバーの知らせと同じ物を画面側へ流す
 * (見え始め → 5 tick ごとに見えている → 40 tick で印 → 切れた)。{@link DevBridge} から(クライアントのスレッドで)。
 * <ul>
 *   <li>{@code gazedev watch [tick]}: 見られ始める(tick を付けるとその長さで切れる)。</li>
 *   <li>{@code gazedev hide}: 視線が切れる。</li>
 *   <li>{@code gazedev perf}: 描画 1 回の平均の時間を返して数え直す。</li>
 * </ul>
 */
final class GazeDev {

    private static final int TOWER = -777, MARK_TICKS = 40;
    private static int age = -1, until = -1;

    private GazeDev() {
    }

    static void run(String[] p, Consumer<String> log) {
        switch (p[0]) {
            case "watch" -> {
                age = 0;
                until = p.length > 1 ? Integer.parseInt(p[1]) : -1;
                PhyllosensGazeEdge.receive(new PhyllosensGazePayload(PhyllosensGazePayload.START, TOWER, 0.0F));
                log.accept("GAZEDEV 見られ始め");
            }
            case "hide" -> {
                if (age >= 0) PhyllosensGazeEdge.receive(new PhyllosensGazePayload(PhyllosensGazePayload.LOST, TOWER, 0.0F));
                age = -1;
                log.accept("GAZEDEV 切れた");
            }
            case "perf" -> {
                long n = PhyllosensGazeEyes.perfFrames, ns = PhyllosensGazeEyes.perfNanos;
                PhyllosensGazeEyes.perfFrames = 0L;
                PhyllosensGazeEyes.perfNanos = 0L;
                log.accept(String.format(Locale.ROOT, "GAZEDEV perf %d フレーム 平均 %.1f µs", n, n == 0L ? 0.0 : ns / 1000.0 / n));
            }
            default -> log.accept("GAZEDEV ? " + p[0]);
        }
    }

    static void tick() {
        if (age < 0) return;
        age++;
        if (until >= 0 && age >= until) {
            PhyllosensGazeEdge.receive(new PhyllosensGazePayload(PhyllosensGazePayload.LOST, TOWER, 0.0F));
            age = -1;
            return;
        }
        if (age == MARK_TICKS) PhyllosensGazeEdge.receive(new PhyllosensGazePayload(PhyllosensGazePayload.MARK, TOWER, 1.0F));
        else if (age % 5 == 0) PhyllosensGazeEdge.receive(new PhyllosensGazePayload(PhyllosensGazePayload.SEEN, TOWER, Math.min(1.0F, age / (float) MARK_TICKS)));
    }
}
