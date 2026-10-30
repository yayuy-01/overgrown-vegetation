package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * <b>風を差し込む口</b>(2026-10-30 作り直しの第 1 段)。光の筋の揺れ・木漏れ日の明滅とずれ・光る胞子の流れ・林冠の日向の斑点の揺れは、
 * どれも {@link #current()} の風だけを読む。第 1 段は {@link #STILL}(風なし。弱いゆらぎだけ)で、第 2 段で時折の突風の風の場
 * (元は parked の {@code CanopyWind}・{@code CanopyWindDriver})を作ったら {@link #use} で差し替える(落ち葉・葉の揺れ・風の音・環境の胞子も同じ風を読む)。
 *
 * <p>時刻 {@code time} は {@link Atmosphere#time}(ワールドに入ってからの tick + 部分 tick)。強さの尺度は前の風と同じ
 * (凪 0.1〜0.3・突風のさなか 0.6〜1・いちばん強い所 1.2)。
 */
public interface Wind {

    /** (x, z) の風の強さ(0〜1.2)。 */
    double strength(double x, double z, double time);

    /** (x, z) の風を向きつきで {@code out} = {x, z} へ(大きさは {@link #strength} と同じ尺度)。 */
    void velocity(double x, double z, double time, double[] out);

    /** 今の卓越風の水平の向き(単位ベクトルの x・z)。 */
    double dirX();

    double dirZ();

    /** 風なし: 強さ 0、向きは東。木漏れ日はかすかにきらめくだけ、筋と斑点は枝のわずかな揺れだけ、胞子は渦だけで漂う。 */
    Wind STILL = new Wind() {
        @Override
        public double strength(double x, double z, double time) {
            return 0.0;
        }

        @Override
        public void velocity(double x, double z, double time, double[] out) {
            out[0] = 0.0;
            out[1] = 0.0;
        }

        @Override
        public double dirX() {
            return 1.0;
        }

        @Override
        public double dirZ() {
            return 0.0;
        }
    };

    /** 今の風。 */
    static Wind current() {
        return Holder.wind;
    }

    /** 風を差し替える(null で風なしに戻す)。 */
    static void use(Wind wind) {
        Holder.wind = wind != null ? wind : STILL;
    }

    /**
     * 枝の揺れ(−1〜1)。毎秒 0.25 回を中心に、少し速い揺れを重ねる。{@code phase} は物ごとのずらし。
     * 斑点・光の筋の行き来に使う(強さは呼び手が掛ける)。
     */
    static double branch(double time, double phase) {
        double s = time / 20.0;
        return 0.7 * Math.sin(2.0 * Math.PI * 0.25 * s + phase) + 0.3 * Math.sin(2.0 * Math.PI * 0.61 * s + phase * 1.7);
    }

    /**
     * 葉のはためきによる明るさ(0〜1)。凪では 1 近くでかすかにきらめき、風が強いほど速く(毎秒 1.5〜4 回)深く明滅し、
     * 強い突風のさなかには一瞬消える。{@code w} は {@link #strength} の値。
     */
    static double flutter(double time, double phase, double w) {
        double s = time / 20.0;
        double amount = Math.min(1.0, w);
        double hz = 1.5 + 2.5 * amount;
        double fast = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * hz * s + phase * 7.0);
        double slow = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 0.53 * s + phase * 3.0);
        double shimmer = 0.9 + 0.1 * Math.sin(2.0 * Math.PI * 0.2 * s + phase);
        double blink = Math.pow(fast * (0.4 + 0.6 * slow), 0.7);
        return shimmer * (1.0 - amount * 0.85) + blink * amount * 0.85;
    }

    /** 今の風の置き場(インターフェースは可変の静的な値を持てないので)。 */
    final class Holder {
        private static volatile Wind wind = STILL;

        private Holder() {
        }
    }
}
