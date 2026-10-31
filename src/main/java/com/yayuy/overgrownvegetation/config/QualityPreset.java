package com.yayuy.overgrownvegetation.config;

/**
 * 演出の詳細設定の「品質の段階」(2026-10-23、ユーザー決定: 手動で選ぶだけ・自動調整は無し・既定は高)。
 *
 * <p>段階そのものは保存しない: 画面は下の各項目の値がどの段階と全部一致するかを見て「低・中・高」を出し、
 * どれとも違えば「カスタム」と出す(バニラの「グラフィック」とビデオ設定の関係と同じ考え。保存した段階と値が食い違うことが無い)。
 * 段階を選ぶと、ここの値が各項目へ一式書かれる。<b>高 = 各項目の既定値</b>(今の見た目)にしておくこと —— でないと新しく入れた人が「カスタム」から始まる。
 *
 * <p>2026-11-01 に parked から戻した。前の列(解像度・ミップ・下草・粒・光の筋・霧の板・雲の遠景・動く光)は読み手ごと退避中なので、
 * 今あるのは巻きシダと雰囲気の重さ({@link AtmosphereQuality} = 前の「粒」「光の筋」の列)だけ。演出のオンオフは好みなので段階に入れない(仮決め)。
 * 目安の GPU: 高 = RTX 4060 級、中 = GTX 1650 級、低 = 内蔵 GPU。
 */
public enum QualityPreset {
    //     巻きシダ                     雰囲気の重さ
    LOW(CurledFernDetail.SIMPLE, AtmosphereQuality.LOW),
    MEDIUM(CurledFernDetail.REDUCED, AtmosphereQuality.MEDIUM),
    HIGH(CurledFernDetail.REDUCED, AtmosphereQuality.HIGH);

    public final CurledFernDetail curledFern;
    public final AtmosphereQuality atmosphere;

    QualityPreset(CurledFernDetail curledFern, AtmosphereQuality atmosphere) {
        this.curledFern = curledFern;
        this.atmosphere = atmosphere;
    }
}
