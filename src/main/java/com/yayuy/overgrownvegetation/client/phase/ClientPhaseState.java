package com.yayuy.overgrownvegetation.client.phase;

import com.yayuy.overgrownvegetation.network.PhaseStatePayload;
import com.yayuy.overgrownvegetation.phase.PhaseStage;

/**
 * クライアントが知っている今のフェーズと小さな段(2026-11-14)。サーバーの {@code phase.PhaseSync} が送る
 * {@link PhaseStatePayload} を持つだけ。第 2 波の「フェーズが上がったときの合図」・後で見た目を段で変えるときにここを読む。
 * サーバーにつながる前・送られる前はフェーズ 0・導入。
 */
public final class ClientPhaseState {

    private static volatile int phase;
    private static volatile PhaseStage stage = PhaseStage.INTRO;
    private static volatile float progress = -1.0F;
    private static volatile float strength;

    private ClientPhaseState() {
    }

    public static void accept(PhaseStatePayload payload) {
        phase = payload.phase();
        stage = PhaseStage.byIndex(payload.stage());
        progress = payload.progress();
        strength = payload.strength();
    }

    public static int phase() {
        return phase;
    }

    public static PhaseStage stage() {
        return stage;
    }

    /** このフェーズの進み(0〜1、1% 刻みで届く)。閾値が無いフェーズは -1。 */
    public static float progress() {
        return progress;
    }

    /** 今のフェーズの物の、今の段の倍率。 */
    public static float strength() {
        return strength;
    }
}
