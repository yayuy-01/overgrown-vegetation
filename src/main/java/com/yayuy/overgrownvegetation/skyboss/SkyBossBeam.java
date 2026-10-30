package com.yayuy.overgrownvegetation.skyboss;

/**
 * 大技 1「転がりながらビーム」の時刻表(2026-10-19)。サーバー(当たり)とクライアント(体の転がり・蕾のアニメ・ビームの描画)が同じ式で出す。
 * 決定「光を溜めきると、蕾が頭から尾へ波のように順に開き、体の長さの軸で転がりながら全部の蕾からビームを出し続ける(体のまわりを 360° 掃く。地上まで届く)」、
 * 案「転がりはゆっくり始まってだんだん速く。終わると腹を上へ戻り、蕾が閉じて熱を逃がす雲を噴く」。時間・回数はすべて仮({@link SkyBossTuning} の大技 1)。
 *
 * <pre>
 * beamAt ─ 芯が光る(AA_GLOW_TICKS)─ 頭から尾へ蕾が開く(buds_wave_open)。開いた蕾からビーム
 *        └ BEAM_ROLL_DELAY から BEAM_ROLL_TICKS かけて beamTurns 回転がる(だんだん速く、最後に止まる)→ ビームが止む = rollEnd
 * rollEnd ─ 蕾が閉じる(buds_close)・熱を逃がす雲 ─ beamEnd
 * </pre>
 * 蕾は一斉射と同じ値({@link SkyBossFlight#budsAt}・{@code budsKind} = {@link #BUDS_KIND}・{@code budsEnd})で動かすので、
 * 蕾のアニメ・芯の光は一斉射と同じ区切り({@link SkyBossFlight#budsPhase})。転がりは腹 ⇄ 背の向き({@link SkyBossFlight#rollAt})に
 * <b>足す</b>(N × 360° なので、終わると元の腹が上へ戻る。雷雲・放電の転がりの決め方に触らない)。
 */
public final class SkyBossBeam {

    /** 蕾の値の種類(0 = 腹の側の一斉射、1 = 全部の一斉射、2 = 転がりのビーム)。 */
    public static final int BUDS_KIND = 2;

    /** 転がりが終わる(ビームが止む)tick。 */
    public static long rollEnd(SkyBossFlight f) {
        return f.beamAt + SkyBossTuning.BEAM_ROLL_DELAY + SkyBossTuning.BEAM_ROLL_TICKS;
    }

    /** 技が終わる tick(蕾が閉じ終わる)。 */
    public static long end(long beamAt) {
        return beamAt + SkyBossTuning.BEAM_ROLL_DELAY + SkyBossTuning.BEAM_ROLL_TICKS + SkyBossTuning.BUDS_CLOSE_TICKS;
    }

    /** ビームを始める(蕾の値も)。 */
    public static void start(SkyBossFlight f, long now, int turns) {
        f.beamAt = now;
        f.beamEnd = end(now);
        f.beamTurns = turns;
        f.budsAt = now;
        f.budsKind = BUDS_KIND;
        f.budsEnd = f.beamEnd;
    }

    /**
     * 時刻 t に足す転がり(度)。転がりの外は 0(終わりは beamTurns × 360° = 0 と同じ向きなので、跳ばない)。
     * 回る速さは 0 から {@link SkyBossTuning#BEAM_PEAK} まで一定の割合で速くなり、そこから 0 へ落ちる(なめらかに始まって、最後に止まる)。
     */
    public static double roll(SkyBossFlight f, double t) {
        if (f.beamAt == SkyBossFlight.NONE) {
            return 0.0;
        }
        double u = (t - f.beamAt - SkyBossTuning.BEAM_ROLL_DELAY) / SkyBossTuning.BEAM_ROLL_TICKS;
        if (u <= 0.0 || u >= 1.0) {
            return 0.0;
        }
        return f.beamTurns * 360.0 * shape(u);
    }

    /** 転がりの進み(0 → 1)。速さが 3 角形(0 → 頂点 {@link SkyBossTuning#BEAM_PEAK} → 1)の積分を 1 にそろえたもの。 */
    public static double shape(double u) {
        double a = SkyBossTuning.BEAM_PEAK;
        double v = u <= a ? u * u / (2.0 * a) : a / 2.0 + (u - a) - (u - a) * (u - a) / (2.0 * (1.0 - a));
        return v / 0.5;
    }

    /** 時刻 t の転がりの速さ(度 / tick。確かめ用)。 */
    public static double rate(SkyBossFlight f, double t) {
        return roll(f, t + 0.5) - roll(f, t - 0.5);
    }

    /** 蕾 i が頭から尾のどこか(0 = いちばん頭の蕾、1 = いちばん尾。道具の buds_wave_open と同じ、静止の z の割合)。 */
    public static double wave(SkyBossShapes s, int i) {
        if (s.budZMax <= s.budZMin) {
            return 0.0;
        }
        return (s.budCenter[i][2] - s.budZMin) / (s.budZMax - s.budZMin);
    }

    /** 蕾 i のビームが出る tick(芯が光った後、その蕾が大きく開いた所。道具の (0.7 + 3.2 × 割合) 元の秒 × 時間の倍率 + 蕾の層の補間 4)。 */
    public static double onAt(SkyBossFlight f, SkyBossShapes s, int i) {
        return f.beamAt + SkyBossTuning.AA_GLOW_TICKS + 4.0 + (0.7 + 3.2 * wave(s, i)) * SkyBossTuning.ANIM_SLOW * 20.0;
    }

    /** 時刻 t に蕾 i からビームが出ているか(壊れた蕾・ショートの隙は呼ぶ側が見る)。 */
    public static boolean on(SkyBossFlight f, SkyBossShapes s, int i, double t) {
        return f.beaming(t) && t >= onAt(f, s, i) && t < rollEnd(f);
    }

    /**
     * 蕾 i のビームの向き(世界の単位ベクトル)→ {@code out}。外向き({@link SkyBossPose#budOutward})から、上下左右に少しずつ揺らす
     * (10-19 ユーザー「そのまま垂直に打つのではなく、ある程度上下左右に少しだけ動かしながら」)。揺れは体の軸の向き(前後)と、それに直角な向き(体のまわり)の 2 つ、
     * どちらも長さの違う 2 つの波の和で、蕾ごとに位相をずらす(決まった式なので両側が同じ向き)。大きさ・速さは仮({@link SkyBossTuning#BEAM_SWAY_DEG})。
     * 姿勢の骨を出した後だけ(描画は {@code bodyBones} の後、サーバーは形を出した後)。
     */
    public static void aim(SkyBossPose ps, int i, double t, double[] out) {
        double[] d = SCRATCH_D.get();
        double[] a = SCRATCH_A.get();
        ps.budOutward(i, d);
        ps.budAxis(i, a);
        // 体の軸を外向きに直角へ(前後の揺れの向き)と、体のまわりの向き
        double k = a[0] * d[0] + a[1] * d[1] + a[2] * d[2];
        double fx = a[0] - d[0] * k;
        double fy = a[1] - d[1] * k;
        double fz = a[2] - d[2] * k;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0E-6) {
            System.arraycopy(d, 0, out, 0, 3);
            return;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;
        double sx = d[1] * fz - d[2] * fy;
        double sy = d[2] * fx - d[0] * fz;
        double sz = d[0] * fy - d[1] * fx;
        double amp = Math.toRadians(SkyBossTuning.BEAM_SWAY_DEG);
        double p1 = phase(i, 1);
        double p2 = phase(i, 2);
        double p3 = phase(i, 3);
        double p4 = phase(i, 4);
        double w1 = 2.0 * Math.PI / SkyBossTuning.BEAM_SWAY_TICKS;
        double w2 = w1 * 0.63;
        double along = amp * (0.65 * Math.sin(w1 * t + p1) + 0.35 * Math.sin(w2 * t + p2));
        double around = amp * (0.65 * Math.sin(w1 * 0.81 * t + p3) + 0.35 * Math.sin(w2 * 1.27 * t + p4));
        double ta = Math.tan(along);
        double tr = Math.tan(around);
        double ox = d[0] + fx * ta + sx * tr;
        double oy = d[1] + fy * ta + sy * tr;
        double oz = d[2] + fz * ta + sz * tr;
        double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
        out[0] = ox / ol;
        out[1] = oy / ol;
        out[2] = oz / ol;
    }

    private static final ThreadLocal<double[]> SCRATCH_D = ThreadLocal.withInitial(() -> new double[3]);
    private static final ThreadLocal<double[]> SCRATCH_A = ThreadLocal.withInitial(() -> new double[3]);

    /** 蕾 i の揺れの位相(0〜2π。決まった式)。 */
    private static double phase(int i, int k) {
        long h = (i * 0x9E3779B97F4A7C15L) ^ (k * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 * Math.PI;
    }

    /** ビームの明るさ(0〜1。出始めと止む前の 6 tick でなめらかに)。 */
    public static double strength(SkyBossFlight f, SkyBossShapes s, int i, double t) {
        if (!on(f, s, i, t)) {
            return 0.0;
        }
        double in = (t - onAt(f, s, i)) / 6.0;
        double out = (rollEnd(f) - t) / 6.0;
        return Math.max(0.0, Math.min(1.0, Math.min(in, out)));
    }

    /**
     * 1 tick に溜まる日の光(0〜1 の割合)。晴れの昼で {@link SkyBossTuning#BEAM_LIGHT_CLEAR_SECONDS} 秒。
     * {@code day} = 昼か、{@code rain}・{@code thunder} = 雨・雷雨の強さ(0〜1)、{@code stage} = 体力の段階。
     */
    public static double lightRate(boolean day, float rain, float thunder, int stage) {
        double k = day ? 1.0 : SkyBossTuning.BEAM_LIGHT_NIGHT;
        k *= 1.0 - rain * (1.0 - SkyBossTuning.BEAM_LIGHT_RAIN);
        k *= 1.0 - thunder * (1.0 - SkyBossTuning.BEAM_LIGHT_THUNDER / SkyBossTuning.BEAM_LIGHT_RAIN);
        if (stage >= 3) {
            k *= SkyBossTuning.BEAM_LIGHT_STAGE3;
        }
        return k / (SkyBossTuning.BEAM_LIGHT_CLEAR_SECONDS * 20.0);
    }

    private SkyBossBeam() {
    }
}
