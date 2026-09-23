package com.yayuy.overgrownvegetation.siege;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 超大型レイドボス(攻城戦)の<b>飛行軌道</b>。開始時のプレイヤー位置を中心とする半径 {@code radius} の
 * 円周上をゆっくり回り続ける。
 *
 * <h2>なぜ「位置」ではなく「軌道パラメータ」を持つのか</h2>
 * <p>このボスはチャンク読み込み範囲のはるか外(既定 1000 ブロック)を飛ぶため、通常の
 * {@link net.minecraft.world.entity.Entity} にはできない — エンティティはチャンクがティックされて
 * いなければ動かず、さらに {@code ChunkMap} のトラッキング距離(サーバーの view-distance が上限)を
 * 超えるとそもそもクライアントへ送られないため。
 *
 * <p>そこでボスの実体は {@link SiegeData} が持つ「中心・半径・高度・速さ・開始時刻」だけにして、
 * 位置はいつでもこのクラスの式から求める。サーバーもクライアントも<b>同じ式</b>を使うので、
 * ネットワークには軌道パラメータと経過 tick を(取りこぼしに強いよう)定期的に投げるだけでよく、
 * 毎 tick の座標同期は要らない({@link com.yayuy.overgrownvegetation.network.SiegeBossStatePayload})。
 *
 * <p>「大体円状に動けばよい」という要件なので、真円ではなく半径・高度に緩やかな揺らぎを乗せてある
 * (ただし {@code t} だけから決まる決定論的な式なので、両サイドで必ず同じ値になる)。
 *
 * <h2>螺旋 —— 回りながら中心へ寄ってくる(2026-09-13 追加)</h2>
 * <p>半径は時間とともに {@code approach} ブロック/tick ずつ縮み、{@code minRadius} で止まる。
 * <b>接線速度({@code speed})は保つ</b>ので、寄るほど角速度が上がって回りが速くなる。
 * 角度は積分の閉じた形 <code>θ(t) = (v/k)·ln(r0 / (r0 − k·t))</code> で求まるので、
 * <b>{@code t} だけから決まる</b>というこの設計の前提(両サイドで必ず同じ値)は崩れない。
 *
 * <p>縮める速さ {@code approach} は「<b>何周ぶんで寄り切るか</b>」({@code siegeApproachLaps}、既定
 * <b>2.5 周</b>)から {@link #approachFor} が逆算する。<b>周回数は速さに依らない</b>ので、
 * {@code siegeSpeed} を上げても螺旋の形は変わらず、掛かる時間だけが短くなる。
 *
 * <p>目安: 既定(r0 = 1025、v = 0.08、rMin = 128、2.5 周)なら k ≒ 0.0106 ブロック/tick で、
 * <b>半径 512(= 近距離モードに入る距離)まで約 40 分・0.83 周</b>、下限まで<b>約 70 分・2.5 周</b>。
 * 速さに反比例するので、{@code siegeSpeed} を 4 倍(0.32)にすれば<b>約 18 分</b>で同じ 2.5 周を回る。
 *
 * @param centerX   円の中心 X(レイド開始時のプレイヤー位置)
 * @param centerY   円の中心 Y(演出・距離表示用。高度には使わない)
 * @param centerZ   円の中心 Z
 * @param radius    <b>開始時の</b>円の半径(ブロック)
 * @param altitude  ボスが飛ぶ絶対 Y 座標の基準値(地形は参照しない — 遠方のチャンクは未ロードのため)
 * @param speed     周回の速さ(ブロック/tick)。半径が縮んでもこれは変わらない
 * @param approach  1 tick に縮める半径(ブロック)。0 でただの周回(従来の挙動)
 * @param minRadius これ以上は寄らない半径(ブロック)。0 にすると中心で角速度が発散するので下限がある
 */
public record SiegeBossMotion(double centerX, double centerY, double centerZ,
                              double radius, double altitude, double speed,
                              double approach, double minRadius) {

    /**
     * <b>「開始半径から下限まで何周ぶんで寄るか」から、1 tick あたりに縮める幅を出す</b>。
     * config はこちら({@code siegeApproachLaps})で持っていて、式が使う {@code approach} はここで作る。
     *
     * <p>螺旋の全回転角は <code>Θ = (v/k)·ln(r0/rMin)</code> なので、これを <code>2π·laps</code> と
     * 置いて <code>k</code> について解くだけ。<b>周回数は速さに依らない</b>ので、
     * {@code siegeSpeed} を上げても「2 周半で寄り切る」形は変わらず、掛かる時間だけが短くなる。
     *
     * @param laps 開始半径から {@code minRadius} まで寄り切るまでに回る周回数。0 で寄ってこない
     */
    public static double approachFor(double radius, double minRadius, double speed, double laps) {
        if (laps <= 0.0D || speed <= 0.0D || radius <= 0.0D) {
            return 0.0D;
        }
        double floor = Math.max(1.0D, Math.min(minRadius, radius));
        if (floor >= radius) {
            return 0.0D;
        }
        return speed * Math.log(radius / floor) / (Math.PI * 2.0D * laps);
    }

    /** これ以上は寄らない半径(開始半径を超えないよう丸めたもの)。 */
    public double floorRadius() {
        return Math.max(1.0D, Math.min(minRadius, radius));
    }

    /** 開始から {@code t} tick 後の周回半径。 */
    public double radiusAt(double t) {
        if (approach <= 0.0D) {
            return radius;
        }
        return Math.max(floorRadius(), radius - approach * Math.max(0.0D, t));
    }

    /** 半径が下限に届くまでの tick 数(寄ってこない設定なら無限大)。 */
    public double closeInTicks() {
        return approach <= 0.0D ? Double.MAX_VALUE : Math.max(0.0D, (radius - floorRadius()) / approach);
    }

    /**
     * 開始から {@code t} tick 後の回転角(rad)。接線速度を {@code speed} に保つので、
     * 半径が縮むぶんだけ角速度が上がる。下限に着いたあとは普通の等速円運動。
     */
    public double angleAt(double t) {
        double time = Math.max(0.0D, t);
        if (speed <= 0.0D) {
            return 0.0D;
        }
        if (approach <= 0.0D) {
            return radius <= 0.0D ? 0.0D : speed / radius * time;
        }
        double floor = floorRadius();
        double reached = closeInTicks();
        if (time <= reached) {
            // θ = ∫₀ᵗ v/(r0 − k·s) ds = (v/k)·ln(r0 / (r0 − k·t))
            return speed / approach * Math.log(radius / Math.max(1.0E-6D, radius - approach * time));
        }
        return speed / approach * Math.log(radius / floor) + speed / floor * (time - reached);
    }

    /** 開始から {@code t} tick 後の角速度(rad/tick)。 */
    public double angularSpeedAt(double t) {
        double r = radiusAt(t);
        return r <= 0.0D ? 0.0D : speed / r;
    }

    /** 開始から {@code t} tick 後の「1周に要する tick 数」(0 なら周回しない)。 */
    public double lapTicksAt(double t) {
        double w = angularSpeedAt(t);
        return w <= 0.0D ? 0.0D : (Math.PI * 2.0D) / w;
    }

    /** 開始から {@code t} tick 後のボスの位置。{@code t} は小数可(描画のサブ tick 補間に使う)。 */
    public Vec3 positionAt(double t) {
        double theta = angleAt(t);
        // 真円だと機械的に見えるので、半径と高度に低周波の揺らぎを乗せる(「大体円状」)。
        double r = radiusAt(t) * (1.0D
                + 0.060D * Math.sin(theta * 3.0D + 0.7D)
                + 0.030D * Math.sin(theta * 7.3D + 2.1D));
        double x = centerX + r * Math.cos(theta);
        double z = centerZ + r * Math.sin(theta);
        double y = altitude
                + 10.0D * Math.sin(theta * 5.0D + 1.3D)
                + 5.0D * Math.sin(theta * 11.0D);
        return new Vec3(x, y, z);
    }

    /** {@code t} における進行方向の単位ベクトル(前後 0.5 tick の差分から数値微分する)。 */
    public Vec3 headingAt(double t) {
        Vec3 d = positionAt(t + 0.5D).subtract(positionAt(t - 0.5D));
        return d.lengthSqr() < 1.0E-9D ? new Vec3(0.0D, 0.0D, 1.0D) : d.normalize();
    }

    /** 進行方向のヨー(Minecraft の規約: 0 = +Z(南)、-90 = +X(東))。 */
    public float yawAt(double t) {
        Vec3 d = headingAt(t);
        return (float) (Mth.atan2(d.z, d.x) * (180.0D / Math.PI)) - 90.0F;
    }

    /** 進行方向のピッチ(Minecraft の規約: 正で下向き)。 */
    public float pitchAt(double t) {
        Vec3 d = headingAt(t);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return (float) (-(Mth.atan2(d.y, horizontal) * (180.0D / Math.PI)));
    }
}
