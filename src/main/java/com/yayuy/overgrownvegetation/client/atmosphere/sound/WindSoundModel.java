package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.client.atmosphere.Wind;

/**
 * 風の場({@link Wind})から、<b>風の音の 2 つの源</b>の強さと向きを見積もる(2026-10-30 夜 第 2 段「動き」。元は parked の同名、
 * MC の型を使わないので素の java の波形の検算でも同じものを使う)。
 *
 * <ul>
 *     <li><b>まわり</b>(near): 聞く人のまわりの葉擦れ。左右に広がって包む。足元と半径 {@value #INNER} ブロックの輪の風で決める。</li>
 *     <li><b>遠く</b>(far): 林を渡ってくる突風の前線の音。半径 {@value #MID}・{@value #OUTER} ブロックの輪の葉擦れのうち、<b>片寄った分</b>だけ
 *     (四方から同じに鳴っている分は「まわり」と変わらない)。前線は風上で鳴り始めて近づき、頭の上を通り、風下へ遠ざかって聞こえる。</li>
 * </ul>
 * 音の大きさは、凪の空気の動き(強さ {@code calm}、既定 0.22 まで)では 0、それを超えた分の 1.1 乗(突風の盛りで 0.9)。凪の間は無音
 * (2026-10-15 夜、ユーザー「風は常にあるものではなく、時折発生する感じに」)。雷雨では凪の強さ自体が上がるので鳴り続ける。
 * 植物の揺れ({@link com.yayuy.overgrownvegetation.client.atmosphere.WindGate})もこの大きさで出入りする。
 */
public final class WindSoundModel {

    public static final double INNER = 8.0;
    public static final double MID = 16.0;
    public static final double OUTER = 30.0;
    /** 遠くの突風の音の大きさの倍率。 */
    private static final double FAR_GAIN = 0.6;
    private static final int DIRS = 12;
    private static final double[] COS = new double[DIRS];
    private static final double[] SIN = new double[DIRS];

    static {
        for (int i = 0; i < DIRS; i++) {
            COS[i] = Math.cos(i * Math.PI * 2.0 / DIRS);
            SIN[i] = Math.sin(i * Math.PI * 2.0 / DIRS);
        }
    }

    /** これより弱い風では鳴らさない(設計値 {@code sound.calmThreshold})。 */
    private final double calm;

    /** まわりの葉擦れの具合(風の強さ)と大きさ(0〜1)。 */
    public double nearW;
    public double nearLevel;
    /** 遠くの突風の音の具合と大きさ、来る向き(聞く人から見た水平の単位ベクトル。鳴っていなければ 0)。 */
    public double farW;
    public double farLevel;
    public double dirX;
    public double dirZ;

    public WindSoundModel(double calm) {
        this.calm = calm;
    }

    /** (px, pz) で聞いたとき、時刻 {@code time}(tick)の見積もり。 */
    public void compute(Wind wind, double px, double pz, double time) {
        double w0 = wind.strength(px, pz, time);
        double inner = 0.0;
        for (int i = 0; i < DIRS; i++) {
            inner += wind.strength(px + COS[i] * INNER, pz + SIN[i] * INNER, time);
        }
        inner /= DIRS;
        this.nearW = 0.5 * w0 + 0.5 * inner;
        this.nearLevel = amplitude(this.nearW);

        // 遠くの輪: 音の力を距離で弱め(16 ブロックで 1、30 ブロックで 0.45)、向きごとに足す。
        double sum = 0.0;
        double vx = 0.0;
        double vz = 0.0;
        double wSum = 0.0;
        for (int r = 0; r < 2; r++) {
            double radius = r == 0 ? MID : OUTER;
            double weight = r == 0 ? 1.0 : 0.45;
            for (int i = 0; i < DIRS; i++) {
                double w = wind.strength(px + COS[i] * radius, pz + SIN[i] * radius, time);
                double a = amplitude(w);
                double power = a * a * weight;
                sum += power;
                vx += power * COS[i];
                vz += power * SIN[i];
                wSum += power * w;
            }
        }
        double total = sum / (DIRS * 1.45);
        double len = Math.sqrt(vx * vx + vz * vz);
        double asym = sum > 1.0E-9 ? len / sum : 0.0;
        // 片寄った分の音の力。四方が同じなら 0、全部が片側なら輪の平均の 1.5 倍。遠くの音はまわりの葉擦れより小さく。
        double farPower = total * Math.min(1.0, asym * 1.5);
        this.farLevel = Math.sqrt(farPower) * FAR_GAIN;
        this.farW = sum > 1.0E-9 ? wSum / sum : 0.0;
        if (len > 1.0E-9) {
            this.dirX = vx / len;
            this.dirZ = vz / len;
        } else {
            this.dirX = 0.0;
            this.dirZ = 0.0;
        }
    }

    /** 風の音の大きさ(振幅、0〜1)。凪の空気の動き(強さ {@code calm} まで)では鳴らさず、それを超えた分で鳴る(強さ 1.1 で 1)。 */
    public double amplitude(double w) {
        double a = Math.max(0.0, w - this.calm) / (1.1 - this.calm);
        return Math.min(1.0, Math.pow(a, 1.1));
    }
}
