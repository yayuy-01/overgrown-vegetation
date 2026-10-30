package com.yayuy.overgrownvegetation.skyboss;

import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 背の垂れ草を重力で垂らす(決定。2026-10-19 ユーザー「垂らしましょう」)。
 * <p>
 * 垂れ草は背の外皮から生えた厚みゼロの板 74 枚(骨 {@code hangNNN_[lr]}、1 枚 1 骨。どのアニメもキーしておらず、当たり判定にも入っていない = 描画だけ)。
 * 形は腹が上のときに真下へ垂れる向きで、背が上の静止の形では上へ立つ。ここでは板ごとに、付け根の線(板の幅の軸)を蝶番にして、重力の向きへ倒す角度を決める。
 * <ul>
 *   <li>腹が上で傾きが無ければ角度 0 = 今までの形のまま。旋回で傾けば、その分だけ横へ振れて真下へ垂れる。</li>
 *   <li>真下が体の中を向く(背が上)ときは、体の外向きの面から {@link SkyBossTuning#HANG_MAX_DEG} まで倒れて背に寝る。
 *       どちらへ寝るかは今いる側のまま(外皮に押し付けられて運ばれ、真上を向いた所で倒れ越す)。背がほぼ真上なら外(体の脇)へ
 *       ({@link SkyBossTuning#HANG_OUTBOARD_DEG})= 留まる間は、左の板は左、右の板は右の脇へ垂れる。</li>
 *   <li>転がる間、体の上の側の板は背を越えて寝た後、転がり終える少し前に自分の側へ倒れ直す。この跳び・倒れ越しと旋回の傾きの変わり目は、
 *       振り子のような追いつき({@link #follow})で 2〜3 秒かけて動かす(状態は描画の側だけが持つ)。</li>
 *   <li>付け根は外皮の 2〜3 単位内側に埋めてある(絵の上の縁の苔の帯が埋まる)。付け根のまま回すと寝た板が外皮に埋まるので、
 *       外皮の所を蝶番にする(骨の位置をずらして回る点を移す)。</li>
 * </ul>
 * 角度・向きは GeckoLib の骨の空間(道具の x を裏返した空間。{@link SkyBossPose} の体の骨と同じ)。MC のクラスを使わない(GameTest で素で叩く)。
 */
public final class SkyBossHang {

    /** 輪の背の側の半分の高さ ÷ 半幅(胴・頭・尾とも 約 0.6。道具の SkyGlaucus.bodyAt・頭の輪・尾の輪)。 */
    static final double RING_RATIO = 0.6;
    /** 板の付け根の置き方(道具の SkyGlaucus.hang: 付け根の高さ = 軸 + 背の側の半分の高さ × √(1 − xf²) × 0.9 − 1.2)。 */
    static final double ROOT_SINK_SCALE = 0.9;
    static final double ROOT_SINK = 1.2;
    static final double MAX = Math.toRadians(SkyBossTuning.HANG_MAX_DEG);
    static final double OUTBOARD = Math.toRadians(SkyBossTuning.HANG_OUTBOARD_DEG);
    /** 追いつきの刻み(秒)。 */
    static final double STEP = 1.0 / 120.0;

    /** 板 1 枚の決まった値。 */
    public static final class Plate {
        /** 板の幅の軸 w(= 蝶番)と、w のまわりに +y を正に回すと向かう向き q = w × y(どちらも水平)。 */
        public final double wx;
        public final double wz;
        public final double qx;
        public final double qz;
        /** 外(体の脇)へ倒れる向きの符号: 外へ a = w のまわりに sigma × a。 */
        public final double sigma;
        /** 体の外向きの面の向き(振れる面の中の角度。+y から外へ +、ラジアン)。 */
        public final double normal;
        /** 付け根から蝶番(外皮の所)までの長さ(モデルの単位、+y の向き)。 */
        public final double depth;

        /**
         * @param pivotX   骨の付け根の x(GeckoLib の空間 = 道具の x の裏返し。外はこの符号の向き)
         * @param pivotY   骨の付け根の y(モデルの単位)
         * @param cubeRotY 板の箱の y の回転(GeckoLib の空間、ラジアン)
         */
        public Plate(double pivotX, double pivotY, double cubeRotY) {
            wx = Math.sin(cubeRotY);
            wz = Math.cos(cubeRotY);
            qx = -wz;
            qz = wx;
            double side = pivotX >= 0.0 ? 1.0 : -1.0;
            sigma = qx * side >= 0.0 ? 1.0 : -1.0;
            // 付け根の真上の外皮の高さ(軸から)と、輪の楕円のその点の面の向き
            double h0 = pivotY - SkyBossBody.Y0;
            double top = Math.max(0.5, (h0 + ROOT_SINK) / ROOT_SINK_SCALE);
            double k = RING_RATIO;
            double b = Math.sqrt(top * top + k * k * pivotX * pivotX);
            double s = top / b;
            double xf = Math.sqrt(Math.max(0.0, 1.0 - s * s));
            double beta = Math.atan2(k * xf, s);
            normal = Math.atan2(Math.abs(qx) * Math.sin(beta), Math.cos(beta));
            depth = Math.max(0.0, top - h0) + SkyBossTuning.HANG_LIFT;
        }

        /** 内(背骨の向こう)へいちばん倒れた角度。 */
        public double min() {
            return normal - MAX;
        }

        /** 外(体の脇)へいちばん倒れた角度。 */
        public double max() {
            return normal + MAX;
        }
    }

    /** 骨の空間での真下(世界の (0, −1, 0) を骨の行列 {@code m} の向きで戻した単位ベクトル)→ {@code out}。 */
    public static void localDown(Matrix4d m, double[] out) {
        double x = -m.m01();
        double y = -m.m11();
        double z = -m.m21();
        double l = Math.sqrt(x * x + y * y + z * z);
        if (l < 1.0E-12) {
            out[0] = 0.0;
            out[1] = 1.0;
            out[2] = 0.0;
            return;
        }
        out[0] = x / l;
        out[1] = y / l;
        out[2] = z / l;
    }

    /**
     * 骨の空間の真下 {@code (gx, gy, gz)} に対して、板が倒れたい角度(外へ +、ラジアン)。{@code current} = 今の角度。
     * <ul>
     *   <li>振れる面に映した真下へ倒せる(体に入らない)なら、その向きへ垂れる。</li>
     *   <li>真下が体の中を向くなら外皮に押し付けられて寝る。どちら側に寝るかは<b>今いる側のまま</b>(外皮に押し付けられた板は、
     *       体が転がって板が真上を向くまで運ばれ、そこで反対側へ倒れ越す。早く起き上がらない)。</li>
     *   <li>ただし体がほぼ真上を向く(背が上)ときは外(体の脇)へ({@link SkyBossTuning#HANG_OUTBOARD_DEG} の中)= 留まる間は、左の板は左、右の板は右の脇へ。
     *       転がってきた側へ背を越えて寝ていた板は、ここで自分の側へ倒れ直す。</li>
     * </ul>
     */
    public static double target(Plate p, double gx, double gy, double gz, double current) {
        double sq = p.sigma * (gx * p.qx + gz * p.qz);
        if (gy * gy + sq * sq < 1.0E-6) {
            return p.normal; // 真下が幅の軸に沿う(体が縦になるときだけ。起きない)。面の向きに立てておく
        }
        double a = Math.atan2(sq, gy);
        double d = Math.IEEEremainder(a - p.normal, 2.0 * Math.PI);
        if (Math.abs(d) <= MAX) {
            return p.normal + d;
        }
        if (Math.abs(a) > Math.PI - OUTBOARD) {
            return p.max();
        }
        return current >= p.normal ? p.max() : p.min();
    }

    /**
     * 板 i の角度 {@code phi[i]}・速さ {@code vel[i]}(ラジアン・毎秒)を、{@code target} へ {@code dt} 秒ぶん追いつかせる
     * (振り子のような 2 次の追いつき: 周期 {@link SkyBossTuning#HANG_PERIOD_S}・減衰 {@link SkyBossTuning#HANG_DAMPING})。体に入る角度へは行かない。
     */
    public static void follow(Plate p, double[] phi, double[] vel, int i, double target, double dt) {
        double w0 = 2.0 * Math.PI / SkyBossTuning.HANG_PERIOD_S;
        double c = 2.0 * SkyBossTuning.HANG_DAMPING * w0;
        int n = Math.max(1, (int) Math.ceil(dt / STEP));
        double h = dt / n;
        double a = phi[i];
        double v = vel[i];
        for (int k = 0; k < n; k++) {
            v += (w0 * w0 * (target - a) - c * v) * h;
            a += v * h;
            if (a > p.max()) {
                a = p.max();
                v = Math.min(0.0, v);
            } else if (a < p.min()) {
                a = p.min();
                v = Math.max(0.0, v);
            }
        }
        phi[i] = a;
        vel[i] = v;
    }

    /**
     * 外へ {@code out}(ラジアン)倒した板の骨の回転 {@code rot} = {x, y, z}(GeckoLib の Z·Y·X、ラジアン)と位置 {@code pos} = {x, y, z}
     * (GeckoLib の setPos の値。静止の値に足す前)。蝶番(付け根から +y へ {@link Plate#depth})が動かないよう位置をずらす。
     */
    public static void pose(Plate p, double out, float[] rot, float[] pos) {
        pose(p, out, rot, pos, new Quaternionf(), new Matrix3f(), new Vector3f());
    }

    /** {@link #pose(Plate, double, float[], float[])} の、作業用の入れ物を使い回す形(描画で毎フレーム 74 枚ぶん呼ぶ)。 */
    public static void pose(Plate p, double out, float[] rot, float[] pos, Quaternionf q, Matrix3f m, Vector3f e) {
        double a = p.sigma * out;
        q.rotationAxis((float) a, (float) p.wx, 0.0F, (float) p.wz);
        m.set(q).getEulerAnglesZYX(e);
        rot[0] = e.x;
        rot[1] = e.y;
        rot[2] = e.z;
        // 回した板の向き = cos a · y + sin a · q。蝶番を止める位置 = depth × (y − 回した向き)。GeckoLib は位置の x を裏返して当てる
        double s = Math.sin(a);
        double c = Math.cos(a);
        pos[0] = (float) (p.depth * s * p.qx);
        pos[1] = (float) (p.depth * (1.0 - c));
        pos[2] = (float) (-p.depth * s * p.qz);
    }

    /** 外へ {@code out} 倒した板の長さの向き(骨の空間の単位ベクトル)→ {@code dir}。 */
    public static void direction(Plate p, double out, double[] dir) {
        double a = p.sigma * out;
        double s = Math.sin(a);
        dir[0] = s * p.qx;
        dir[1] = Math.cos(a);
        dir[2] = s * p.qz;
    }

    private SkyBossHang() {
    }
}
