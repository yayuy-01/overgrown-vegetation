package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.AtmosphereTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * <b>林の風の場</b>(2026-10-30 夜 作り直しの第 2 段「動き」。元は parked の {@code CanopyWind}、2026-10-15 夜 ユーザー
 * 「葉が風に揺られるのが無いので、木漏れ日や差し込む光がずっと同じ場所にい続けておかしく見える」→ 案 A「風の場で光と粒を一緒に動かす」)。
 * {@link Wind#use} で差し込み、光の筋・木漏れ日・光る胞子・環境の胞子・落ち葉・葉の揺れ・風の音が<b>みな同じ風</b>を読む。
 *
 * <ul>
 *     <li><b>凪の空気の動き</b>: 強さ {@code calmMin}〜{@code calmMax}(0.07〜0.17)。数十秒おきに行き先を決め直してゆっくり寄せる。
 *     向きはサーバーの卓越風({@link PrevailingWindClient})に毎 tick 2% ずつ寄せ、届いていなければゆっくりさまよう。
 *     この強さでは風の音は鳴らず葉も揺れない(凪は無音。2026-10-15 夜・10-16 ユーザー)。</li>
 *     <li><b>突風の前線</b>(提案 6、ユーザー採用): 時折({@code gustIntervalMin}〜{@code gustIntervalMax} 秒おき。了承済みの 60〜120 秒)、
 *     カメラの風上 {@code spawnDistance} ブロックで横に広く(半幅 {@code frontHalfWidth})前後に薄い帯として生まれ、毎秒
 *     {@code frontSpeed} ブロックで林を渡る。来る側は短く速く強まり、去る側は長くゆっくり弱まる。強さは位置ごとに決まるので、
 *     遠くの葉から順に揺れ、音は近づいて遠ざかる。生まれてから 3 秒かけて強まる(遠くの音がぷつっと鳴り出さないように)。</li>
 *     <li><b>雷雨</b>: 卓越風が嵐の強さ(3)のとき、凪の強さを {@code stormCalm}(0.45。風の音の鳴り出し 0.22 より上)へ寄せる ——
 *     雷雨の間だけは風の音と葉の揺れが鳴り続ける(「凪は無音」の唯一の例外)。突風の間隔は {@code stormIntervalScale} 倍、少し強い。</li>
 * </ul>
 * 強さは時刻({@link Atmosphere#time})で連続に決まるので、描画のたびに読んでもかくつかない。ほかのクライアントとは揃えない(見た目だけ)。
 *
 * <p><b>Minecraft の型を使わない</b>(素の java で数値と風の音を検算できるように。memory client-code-check-without-minecraft)。
 * 前線の形(半幅・前後の長さ・生まれる所)・雷雨の凪の強さは仮決め。突風の間隔・凪は無音は了承済み。
 */
public final class GustField implements Wind {

    /** 同時に吹いている突風の上限(葉の揺れのシェーダーへ渡す数と同じ)。 */
    public static final int MAX_GUSTS = 4;
    /** 強さの上限(凪と突風が重なっても、ここで頭打ち)。 */
    public static final double MAX_STRENGTH = 1.2;

    public static final GustField INSTANCE = new GustField(new Random());

    private final Random random;
    private final List<Gust> gusts = new ArrayList<>();
    private AtmosphereTable.WindParams params = AtmosphereTable.DEFAULT.wind();

    /** 凪の向き(ラジアン。風が流れていく向き)と、そのさまよう速さ。 */
    private double baseAngle;
    private double baseAngleVel;
    /** 卓越風の向き(NaN = 届いていない)。 */
    private double prevailingAngle = Double.NaN;
    /** 嵐の度合い 0〜1(卓越風の強さから)。 */
    private double storm;
    private double baseStrength = 0.12;
    private double baseTarget = 0.12;
    private int baseRetargetIn = 400;
    private int nextGustIn = 600;
    private double now;

    /** 1 つの突風の前線。生まれた所から向きへ一定の速さで進む。 */
    static final class Gust {
        final double startX;
        final double startZ;
        final double dirX;
        final double dirZ;
        /** 毎 tick 進むブロック数。 */
        final double speed;
        final double strength;
        final double lenAhead;
        final double lenBehind;
        final double halfWidth;
        final double born;
        /** G4 胞子の風の前線か(2026-11-08。黄緑の胞子の波・霧・雲の影の前触れを連れてくる。{@link SporeGale})。 */
        final boolean spore;

        Gust(double startX, double startZ, double dirX, double dirZ, double speed, double strength,
             double lenAhead, double lenBehind, double halfWidth, double born) {
            this(startX, startZ, dirX, dirZ, speed, strength, lenAhead, lenBehind, halfWidth, born, false);
        }

        Gust(double startX, double startZ, double dirX, double dirZ, double speed, double strength,
             double lenAhead, double lenBehind, double halfWidth, double born, boolean spore) {
            this.spore = spore;
            this.startX = startX;
            this.startZ = startZ;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.speed = speed;
            this.strength = strength;
            this.lenAhead = lenAhead;
            this.lenBehind = lenBehind;
            this.halfWidth = halfWidth;
            this.born = born;
        }

        double centerX(double time) {
            return this.startX + this.dirX * this.speed * (time - this.born);
        }

        double centerZ(double time) {
            return this.startZ + this.dirZ * this.speed * (time - this.born);
        }

        /** 生まれてからの強まり(3 秒でなめらかに 0 → 1)を掛けた強さ。 */
        double grownStrength(double time) {
            double grow = Math.min(1.0, Math.max(0.0, (time - this.born) / 60.0));
            return this.strength * grow * grow * (3.0 - 2.0 * grow);
        }

        /** (x, z) での強さ。来る側は短く、去る側は長い山。横は広い(前線)。 */
        double at(double x, double z, double time) {
            double rx = x - centerX(time);
            double rz = z - centerZ(time);
            double along = rx * this.dirX + rz * this.dirZ;
            double side = -rx * this.dirZ + rz * this.dirX;
            double len = along > 0.0 ? this.lenAhead : this.lenBehind;
            double a = along / len;
            double s = side / this.halfWidth;
            double exponent = a * a + s * s;
            if (exponent > 12.0) {
                return 0.0;
            }
            return grownStrength(time) * Math.exp(-exponent);
        }
    }

    GustField(Random random) {
        this.random = random;
        this.baseAngle = random.nextDouble() * Math.PI * 2.0;
    }

    /** 数値を差し替える(設計値の読み直し)。 */
    public void setParams(AtmosphereTable.WindParams params) {
        this.params = params;
    }

    /**
     * 卓越風を渡す。{@code angle} は風が吹いていく向き(ラジアン、NaN = 無し)、{@code strength} は卓越風の強さ
     * (夜 0.15・午後 1・雨 0.6・嵐 3)。嵐の度合いは強さ 2.2 → 3 で 0 → 1(風の強い日 ≒ 1.95 では鳴り続けない)。
     */
    public void setPrevailing(double angle, double strength) {
        if (Double.isNaN(this.prevailingAngle) && !Double.isNaN(angle)) {
            this.baseAngle = angle;
        }
        this.prevailingAngle = angle;
        double s = Double.isNaN(angle) ? 0.0 : Math.max(0.0, Math.min(1.0, (strength - 2.2) / 0.8));
        this.storm = s * s * (3.0 - 2.0 * s);
    }

    /** 1 tick 進める。{@code time} は {@link Atmosphere#time}、{@code camX}・{@code camZ} はカメラの位置(突風はその風上で生まれる)。 */
    public void tick(double time, double camX, double camZ) {
        this.now = time;
        AtmosphereTable.WindParams p = this.params;
        if (--this.baseRetargetIn <= 0) {
            this.baseTarget = p.calmMin() + this.random.nextDouble() * (p.calmMax() - p.calmMin());
            this.baseRetargetIn = 400 + this.random.nextInt(500);
        }
        double target = this.baseTarget + (p.stormCalm() - this.baseTarget) * this.storm;
        // 天気(11-16): 凪の日は空気の動きも弱め、強風の日は突風の合間も風を残す(雷雨の値より下には下げない)。
        target = Math.max(target * (1.0 - (1.0 - this.calmBreath) * this.calmDay) * (1.0 - this.storm) + target * this.storm,
                this.galeBetween * this.galeDay);
        this.baseStrength += (target - this.baseStrength) * 0.004;
        if (Double.isNaN(this.prevailingAngle)) {
            this.baseAngleVel = this.baseAngleVel * 0.985 + this.random.nextGaussian() * 0.00025;
            this.baseAngle += this.baseAngleVel;
        } else {
            this.baseAngle += Math.IEEEremainder(this.prevailingAngle - this.baseAngle, Math.PI * 2.0) * 0.02;
            this.baseAngleVel = 0.0;
        }
        if (--this.nextGustIn <= 0) {
            // 凪の日(11-16): 突風を生まない(割合 calmGustStop × 凪の度合い)。
            if (this.random.nextDouble() >= this.calmGustStop * this.calmDay) {
                spawnGust(camX, camZ, time);
            }
            this.nextGustIn = nextInterval();
        }
        // 凪になったら、吹いている突風もしぼませる。強風の日になったら、次の突風を待たせない。
        this.gustScale += ((1.0 - this.calmGustStop * this.calmDay) - this.gustScale) * 0.01;
        if (this.galeDay > 0.5) {
            this.nextGustIn = Math.min(this.nextGustIn, (int) (p.gustIntervalMax() * 20.0 * this.galeInterval));
        }
        for (Iterator<Gust> it = this.gusts.iterator(); it.hasNext(); ) {
            Gust g = it.next();
            double ahead = (camX - g.centerX(time)) * g.dirX + (camZ - g.centerZ(time)) * g.dirZ;
            if (ahead < -(g.lenBehind * 3.0 + 20.0) || time - g.born > 2400) {
                it.remove();
            }
        }
    }

    private int nextInterval() {
        AtmosphereTable.WindParams p = this.params;
        double seconds = p.gustIntervalMin() + this.random.nextDouble() * (p.gustIntervalMax() - p.gustIntervalMin());
        return (int) (seconds * 20.0 * (1.0 - (1.0 - p.stormIntervalScale()) * this.storm) * (1.0 - (1.0 - this.galeInterval) * this.galeDay));
    }

    /**
     * 天気(2026-11-16。凪・強風の日)を渡す。{@code calm}・{@code gale} = 度合い 0〜1。{@code gustStop} = 凪で突風を止める割合、
     * {@code breath} = 凪の空気の動きの倍率、{@code interval} = 強風の日の突風の間隔の倍率、{@code bonus} = 強風の日の突風に足す強さ、
     * {@code between} = 強風の日の突風の合間の風の強さ。渡さなければ今まで通り。
     */
    public void setWeather(double calm, double gale, double gustStop, double breath, double interval, double bonus, double between) {
        this.calmDay = Math.max(0.0, Math.min(1.0, calm));
        this.galeDay = Math.max(0.0, Math.min(1.0, gale));
        this.calmGustStop = gustStop;
        this.calmBreath = breath;
        this.galeInterval = interval;
        this.galeBonus = bonus;
        this.galeBetween = between;
    }

    private double calmDay;
    private double galeDay;
    private double calmGustStop = 1.0;
    private double calmBreath = 1.0;
    private double galeInterval = 1.0;
    private double galeBonus;
    private double galeBetween;
    /** 突風の強さに掛ける倍率(凪の日に 0 へ寄る)。 */
    private double gustScale = 1.0;

    /** 強風の日の度合い(葉の揺れ・音が読む)。 */
    public double galeDay() {
        return this.galeDay;
    }

    private double between(double min, double max) {
        return min + this.random.nextDouble() * (max - min);
    }

    private void spawnGust(double camX, double camZ, double time) {
        if (this.gusts.size() >= MAX_GUSTS) {
            return;
        }
        AtmosphereTable.WindParams p = this.params;
        double angle = this.baseAngle + (this.random.nextDouble() - 0.5) * 0.7;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        double upwind = between(p.spawnDistanceMin(), p.spawnDistanceMax());
        double lateral = (this.random.nextDouble() - 0.5) * 16.0;
        // G4 胞子の風(11-08): 突風のうち sporeChance の割合(過繁茂の近く・雨でない日だけ、呼び手 SporeGale が決める)。少し強く吹く。
        boolean spore = this.random.nextDouble() < this.sporeChance;
        this.gusts.add(new Gust(camX - dx * upwind - dz * lateral, camZ - dz * upwind + dx * lateral, dx, dz,
                between(p.frontSpeedMin(), p.frontSpeedMax()) / 20.0,
                between(p.gustStrengthMin(), p.gustStrengthMax()) + 0.2 * this.storm + this.galeBonus * this.galeDay + (spore ? this.sporeBonus : 0.0),
                between(p.frontAheadMin(), p.frontAheadMax()), between(p.frontBehindMin(), p.frontBehindMax()),
                between(p.frontHalfWidthMin(), p.frontHalfWidthMax()), time, spore));
    }

    /** G4 胞子の風: 次の突風が胞子の風になる割合と、そのときに足す強さ(毎 tick {@link SporeGale} が渡す。0 = 胞子の風にしない)。 */
    public void setSporeGale(double chance, double bonus) {
        this.sporeChance = chance;
        this.sporeBonus = bonus;
    }

    private double sporeChance;
    private double sporeBonus;

    /** 開発用: カメラの風上に強めの突風を 1 つ生む(テストクライアントの {@code wind gust})。 */
    public void forceGust(double camX, double camZ) {
        forceGust(camX, camZ, false);
    }

    /** 開発用: カメラの風上に強めの突風を 1 つ生む。spore = 胞子の風にする({@code /ov fx gale})。 */
    public void forceGust(double camX, double camZ, boolean spore) {
        AtmosphereTable.WindParams p = this.params;
        double dx = Math.cos(this.baseAngle);
        double dz = Math.sin(this.baseAngle);
        double upwind = p.spawnDistanceMin();
        this.gusts.add(new Gust(camX - dx * upwind, camZ - dz * upwind, dx, dz, 4.5 / 20.0, 0.9 + (spore ? this.sporeBonus : 0.0),
                p.frontAheadMax(), p.frontBehindMax(), p.frontHalfWidthMax(), this.now, spore));
        while (this.gusts.size() > MAX_GUSTS) {
            this.gusts.remove(0);
        }
        if (spore) {
            // 自然の突風がすぐ後ろに続いて胞子の波と重ならないように。
            this.nextGustIn = Math.max(this.nextGustIn, 600);
        }
    }

    /** 今吹いている突風(G4 胞子の風の粒・霧・前触れが読む。書き換えない)。 */
    List<Gust> gusts() {
        return this.gusts;
    }

    /** 今の時刻({@link Atmosphere#time}。最後の {@link #tick} の値)。 */
    double now() {
        return this.now;
    }

    /** 開発用: 突風を消して、しばらく凪にする(テストクライアントの {@code wind calm})。 */
    public void calm() {
        this.gusts.clear();
        this.nextGustIn = 1200;
    }

    /** ワールドを出た・入り直したとき(入ってすぐには吹かせない。20〜60 秒後に最初の突風)。 */
    public void reset() {
        this.gusts.clear();
        this.prevailingAngle = Double.NaN;
        this.storm = 0.0;
        this.nextGustIn = 400 + this.random.nextInt(800);
    }

    private double calmAt(double x, double z, double time) {
        return this.baseStrength * (1.0 + 0.25 * Math.sin(x * 0.13 + time * 0.031) * Math.sin(z * 0.11 - time * 0.027));
    }

    @Override
    public double strength(double x, double z, double time) {
        double w = calmAt(x, z, time);
        for (Gust g : this.gusts) {
            w += g.at(x, z, time) * this.gustScale;
        }
        return Math.min(MAX_STRENGTH, w) * this.params.strength();
    }

    /** 弱い風は今の向き、突風はそれぞれ自分の向きへ(胞子は突風ごとに少し違う向きへ一斉に流される。2026-10-15 夜 ユーザー)。 */
    @Override
    public void velocity(double x, double z, double time, double[] out) {
        double base = calmAt(x, z, time);
        double vx = base * Math.cos(this.baseAngle);
        double vz = base * Math.sin(this.baseAngle);
        for (Gust g : this.gusts) {
            double a = g.at(x, z, time) * this.gustScale;
            vx += a * g.dirX;
            vz += a * g.dirZ;
        }
        double len = Math.sqrt(vx * vx + vz * vz);
        double k = (len > MAX_STRENGTH ? MAX_STRENGTH / len : 1.0) * this.params.strength();
        out[0] = vx * k;
        out[1] = vz * k;
    }

    @Override
    public double dirX() {
        return Math.cos(this.baseAngle);
    }

    @Override
    public double dirZ() {
        return Math.sin(this.baseAngle);
    }

    /** 強さの上限(凪と突風が重なったときの頭打ち × 全体の倍率)。雨の傾きの「最も強い風」(11-06 雨の見え方)。 */
    public double maxStrength() {
        return MAX_STRENGTH * this.params.strength();
    }

    /** 凪の空気の動きの強さ(場所ごとのむらの前、全体の倍率込み)。葉の揺れのシェーダーへ渡す。 */
    public double calmStrength() {
        return this.baseStrength * this.params.strength();
    }

    /** 嵐の度合い 0〜1。 */
    public double storm() {
        return this.storm;
    }

    public int gustCount() {
        return this.gusts.size();
    }

    /**
     * 葉の揺れのシェーダーへ渡す突風。1 つにつき 8 個 —— (中心 x・z(カメラからの差)、向き x・z、強さ(強まりと全体の倍率込み)、
     * 前の長さ・後ろの長さ・横の半幅)を {@code out} へ。最大 {@value #MAX_GUSTS} 個、足りない分は強さ 0。
     */
    public void fillGusts(double time, double camX, double camZ, float[] out) {
        Arrays.fill(out, 0.0F);
        int i = 0;
        for (Gust g : this.gusts) {
            if (i >= MAX_GUSTS || out.length < (i + 1) * 8) {
                break;
            }
            int at = i * 8;
            out[at] = (float) (g.centerX(time) - camX);
            out[at + 1] = (float) (g.centerZ(time) - camZ);
            out[at + 2] = (float) g.dirX;
            out[at + 3] = (float) g.dirZ;
            out[at + 4] = (float) (g.grownStrength(time) * this.params.strength() * this.gustScale);
            out[at + 5] = (float) g.lenAhead;
            out[at + 6] = (float) g.lenBehind;
            out[at + 7] = (float) g.halfWidth;
            i++;
        }
    }

    /** 開発用のログに出す要約。 */
    public String describe(double x, double z) {
        return String.format("風 %.2f(凪 %.2f・突風 %d・嵐 %.2f)向き %.0f°", strength(x, z, this.now), calmStrength(), this.gusts.size(),
                this.storm, (double) Math.floorMod(Math.round(Math.toDegrees(this.baseAngle)), 360L));
    }
}
