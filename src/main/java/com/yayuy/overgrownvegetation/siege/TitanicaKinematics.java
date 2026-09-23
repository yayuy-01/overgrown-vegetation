package com.yayuy.overgrownvegetation.siege;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * ドルガ=アーケロウス・ティタニカ(攻城戦ボス)の<b>体の各部位のワールド座標</b>を、
 * モデル({@code geo/dorga_archelaus_titanica.geo.json})の寸法から求める共通の計算。
 *
 * <p>サーバー(爆弾の投下点・触手ビームの当たり判定)とクライアント(ビームの描画始点・首の向き)の
 * 両方が同じ式を使うので、描画上の「花」から出たビームがサーバーの判定線と一致する。
 *
 * <h2>モデルの寸法(モデル単位 = 1/16 ブロック)</h2>
 * <ul>
 *   <li>全長およそ {@value #MODEL_LENGTH_UNITS}(z -209.5 〜 +184)、胴の中心線は y = {@value #BODY_LINE_Y}</li>
 *   <li>首の付け根は z = {@value #NECK_BASE_Z}。首は {@value #NECK_LINKS} 節 × {@value #NECK_LINK_LENGTH} で、
 *       先端(z = -208)の頭に花が付く</li>
 *   <li>触手の先端は骨の連鎖を順運動学で辿った表 {@link #TENTACLE_TIPS} で持つ</li>
 * </ul>
 * ボスの「位置」({@link SiegeBossMotion#positionAt})は<b>胴の中心線上の点(モデル原点の高さ y=17)</b>
 * として扱う。描画側もエンティティ側もそこへ合わせている。
 */
public final class TitanicaKinematics {

    public static final double MODEL_LENGTH_UNITS = 394.0D;
    public static final double BODY_LINE_Y = 17.0D;
    public static final double NECK_BASE_Z = -112.0D;
    public static final int NECK_LINKS = 6;
    public static final double NECK_LINK_LENGTH = 16.0D;
    /** 首の先端(z = -208)から、開いた花の中心(ビームの発射点)までの前方距離。 */
    public static final double FLOWER_FORWARD = 8.0D;

    /**
     * 触手の先端の位置(GeckoLib の描画空間のモデル単位、y は胴の中心線 y=17 基準、頭が -Z)。
     *
     * <p>各鎖(根元 → 7 節目)の骨の pivot と回転を、GeckoLib と同じ変換(X 鏡映、回転は Z・-Y・-X の順)で
     * 順運動学的に辿り、最終節の先 8 単位の点を求めたもの。腹側の触手は根元から真下ではなく
     * 大きく扇状に広がって垂れているので、根元の位置から下へずらす近似では合わない
     * (以前はそうしていて、ビームが何も無い所から出ていた)。
     * 先頭 {@value #VENTRAL_TENTACLES} 本が腹側(根元の骨 bone98, 106, 113, 120, 127, 134, 141, 148, 155, 162,
     * 169, 176, 183, 190, 197, 204, 211 の順)、最後の 1 本が背中側(bone104)。
     * モデルを変えたら再計算が要る。
     */
    public static final double[][] TENTACLE_TIPS = {
            {-0.5D, 3.64D, -31.62D},
            {22.14D, -25.32D, -14.68D},
            {-12.91D, -30.65D, -5.68D},
            {3.5D, -37.95D, 5.76D},
            {-12.31D, -57.17D, -17.87D},
            {32.39D, -43.18D, -12.73D},
            {42.9D, -12.2D, 82.59D},
            {-40.16D, -12.2D, 63.49D},
            {11.88D, -30.96D, 15.32D},
            {-3.35D, -52.45D, 17.01D},
            {1.65D, -37.27D, 38.93D},
            {-44.68D, -29.98D, 6.01D},
            {-32.97D, -43.23D, 28.01D},
            {-33.44D, -36.34D, -32.99D},
            {32.97D, -43.23D, 28.01D},
            {33.44D, -36.34D, -32.99D},
            {44.68D, -29.98D, 6.01D},
            {-0.5D, 48.28D, 60.73D}
    };
    /** 腹側の触手の本数(攻撃に使うのはこれだけ。背中側の 1 本は上を向いているので使わない)。 */
    public static final int VENTRAL_TENTACLES = 17;

    /**
     * 当たり判定パーツ 1 個の配置。{@code z} は体軸上の位置(モデル単位、頭が負)、
     * {@code size} は幅 = 高さ(モデル単位、胴の太さ 16 が基準)。
     */
    public record PartSpec(String name, double z, double size) {
    }

    /**
     * 胴と尾の当たり判定パーツ。胴は bone28・bone8〜bone17 の各節の中心、尾は bone18〜bone27 の各節の中心。
     * 1 節 16 単位ごとに 1 個置くので、パーツが隙間なく並んで体軸を覆う。首と頭は別途
     * ({@link #neckChain} の各節の中点と花の位置に、{@link #NECK_PART_SIZES}/{@link #HEAD_PART_SIZE} で置く)。
     */
    public static final PartSpec[] BODY_PARTS = {
            new PartSpec("body0", -104.5D, 18.0D), new PartSpec("body1", -89.0D, 17.0D), new PartSpec("body2", -72.0D, 17.0D),
            new PartSpec("body3", -56.0D, 16.0D), new PartSpec("body4", -40.0D, 16.0D), new PartSpec("body5", -24.0D, 16.0D),
            new PartSpec("body6", -8.0D, 16.0D), new PartSpec("body7", 8.0D, 16.0D), new PartSpec("body8", 24.0D, 16.0D),
            new PartSpec("body9", 40.0D, 15.0D), new PartSpec("body10", 56.0D, 14.0D),
            new PartSpec("tail0", 72.0D, 13.0D), new PartSpec("tail1", 88.0D, 12.0D), new PartSpec("tail2", 104.0D, 11.0D),
            new PartSpec("tail3", 120.0D, 10.0D), new PartSpec("tail4", 136.0D, 10.0D), new PartSpec("tail5", 152.0D, 9.0D),
            new PartSpec("tail6", 168.0D, 8.0D), new PartSpec("tail7", 184.0D, 8.0D), new PartSpec("tail8", 200.0D, 7.0D),
            new PartSpec("tail9", 216.0D, 6.0D)
    };
    /** 首の各節(付け根→先端)の当たり判定パーツの太さ(モデル単位)。 */
    public static final double[] NECK_PART_SIZES = {12.0D, 10.0D, 9.0D, 8.0D, 7.0D, 5.0D};
    /** 頭(花)の当たり判定パーツの大きさ(モデル単位)。花弁が開いたときも収まる程度。 */
    public static final double HEAD_PART_SIZE = 40.0D;

    /** 首を左右に曲げられる最大角(度)。 */
    public static final float NECK_MAX_YAW = 110.0F;
    /** 首を下へ曲げられる最大角(度)。 */
    public static final float NECK_MAX_PITCH_DOWN = 70.0F;
    /** 首を上へ曲げられる最大角(度)。 */
    public static final float NECK_MAX_PITCH_UP = 40.0F;

    /** Minecraft 規約のヨー(0 = +Z, -90 = +X)・ピッチ(正で下)から単位方向ベクトルを作る。 */
    public static Vec3 direction(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double c = Math.cos(pitch);
        return new Vec3(-Math.sin(yaw) * c, -Math.sin(pitch), Math.cos(yaw) * c);
    }

    public static float yawOf(Vec3 d) {
        return (float) (Mth.atan2(d.z, d.x) * (180.0D / Math.PI)) - 90.0F;
    }

    public static float pitchOf(Vec3 d) {
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        return (float) (-(Mth.atan2(d.y, horizontal) * (180.0D / Math.PI)));
    }

    /** 首の付け根のワールド座標。 */
    public static Vec3 neckBase(Vec3 bodyCenter, float bodyYaw, double scale) {
        return bodyCenter.add(direction(bodyYaw, 0.0F).scale(-NECK_BASE_Z / 16.0D * scale));
    }

    /**
     * 首全体を目標へ向けるためのヨー/ピッチの差分(胴基準・度)。{@code [0]} = ヨー、{@code [1]} = ピッチ。
     * 可動域でクランプ済み。
     */
    public static float[] neckOffsets(Vec3 bodyCenter, float bodyYaw, float bodyPitch, double scale, Vec3 target) {
        Vec3 d = target.subtract(neckBase(bodyCenter, bodyYaw, scale));
        if (d.lengthSqr() < 1.0E-6D) {
            return new float[]{0.0F, 0.0F};
        }
        float yaw = Mth.clamp(Mth.wrapDegrees(yawOf(d) - bodyYaw), -NECK_MAX_YAW, NECK_MAX_YAW);
        float pitch = Mth.clamp(pitchOf(d) - bodyPitch, -NECK_MAX_PITCH_UP, NECK_MAX_PITCH_DOWN);
        return new float[]{yaw, pitch};
    }

    /**
     * 首の {@value #NECK_LINKS} 節にヨー/ピッチを等分に振り分けたときの、各節の<b>中点</b>(添字 0〜5)と
     * 花 = ビーム発射点(添字 {@value #NECK_LINKS})のワールド座標。
     * クライアントの {@code DorgaArchelausTitanicaModel} が首の各骨に与える回転と同じ分配なので、
     * ここで求めた点は描画上の首・花の位置と一致する。
     */
    public static Vec3[] neckChain(Vec3 bodyCenter, float bodyYaw, float bodyPitch, double scale,
                                   float neckYawOffset, float neckPitchOffset) {
        Vec3[] points = new Vec3[NECK_LINKS + 1];
        Vec3 p = neckBase(bodyCenter, bodyYaw, scale);
        double link = NECK_LINK_LENGTH / 16.0D * scale;
        Vec3 d = direction(bodyYaw, bodyPitch);
        for (int k = 1; k <= NECK_LINKS; k++) {
            float yaw = bodyYaw + neckYawOffset * k / NECK_LINKS;
            float pitch = bodyPitch + neckPitchOffset * k / NECK_LINKS;
            d = direction(yaw, pitch);
            points[k - 1] = p.add(d.scale(link * 0.5D));
            p = p.add(d.scale(link));
        }
        points[NECK_LINKS] = p.add(d.scale(FLOWER_FORWARD / 16.0D * scale));
        return points;
    }

    /** 花(ビーム発射点)のワールド座標。{@link #neckChain} の最後の点。 */
    public static Vec3 flowerPosition(Vec3 bodyCenter, float bodyYaw, float bodyPitch, double scale,
                                      float neckYawOffset, float neckPitchOffset) {
        return neckChain(bodyCenter, bodyYaw, bodyPitch, scale, neckYawOffset, neckPitchOffset)[NECK_LINKS];
    }

    /** 体軸上の点(モデル単位 {@code zUnits}、頭が負)のワールド座標。胴・尾のパーツ配置に使う。 */
    public static Vec3 bodyPoint(Vec3 bodyCenter, float bodyYaw, float bodyPitch, double scale, double zUnits) {
        return bodyCenter.add(direction(bodyYaw, bodyPitch).scale(-zUnits / 16.0D * scale));
    }

    /** 当たり判定パーツの総数(頭 1 + 首 {@value #NECK_LINKS} + 胴・尾 21 = 28)。 */
    public static int partCount() {
        return 1 + NECK_LINKS + BODY_PARTS.length;
    }

    /**
     * 当たり判定パーツ 28 個の<b>中心</b>のワールド座標。並びは
     * {@link com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity#getParts()} と同じく
     * 頭 / 首 0〜5(付け根→先端)/ 胴・尾({@link #BODY_PARTS} の順)。
     *
     * <p>実エンティティはこれでパーツを毎 tick 並べ、砲弾の当たり判定
     * ({@link ShellTracker})は実エンティティが<b>居ないときも</b>これと {@link #partSizes} から
     * 同じ箱を数値的に再現して線分交差を取る。どちらも同じ式なので、遠距離で当たる場所と
     * 近距離で当たる場所が一致する。
     */
    public static Vec3[] partCenters(Vec3 bodyCenter, float bodyYaw, float bodyPitch, double scale,
                                     float neckYawOffset, float neckPitchOffset) {
        Vec3[] neck = neckChain(bodyCenter, bodyYaw, bodyPitch, scale, neckYawOffset, neckPitchOffset);
        Vec3[] centers = new Vec3[partCount()];
        centers[0] = neck[NECK_LINKS];
        for (int k = 0; k < NECK_LINKS; k++) {
            centers[1 + k] = neck[k];
        }
        for (int i = 0; i < BODY_PARTS.length; i++) {
            centers[1 + NECK_LINKS + i] = bodyPoint(bodyCenter, bodyYaw, bodyPitch, scale, BODY_PARTS[i].z());
        }
        return centers;
    }

    /** 当たり判定パーツ 28 個の 1 辺(ブロック、幅 = 高さ)。並びは {@link #partCenters} と同じ。 */
    public static double[] partSizes(double scale) {
        double unit = scale / 16.0D;
        double[] sizes = new double[partCount()];
        sizes[0] = HEAD_PART_SIZE * unit;
        for (int k = 0; k < NECK_LINKS; k++) {
            sizes[1 + k] = NECK_PART_SIZES[k] * unit;
        }
        for (int i = 0; i < BODY_PARTS.length; i++) {
            sizes[1 + NECK_LINKS + i] = BODY_PARTS[i].size() * unit;
        }
        return sizes;
    }

    /** 当たり判定パーツ {@code index} 番の名前(頭 / neck0〜5 / body0〜10 / tail0〜9)。演出・ログ用。 */
    public static String partName(int index) {
        if (index == 0) {
            return "head";
        }
        if (index <= NECK_LINKS) {
            return "neck" + (index - 1);
        }
        return BODY_PARTS[index - 1 - NECK_LINKS].name();
    }

    public static int tentacleCount() {
        return VENTRAL_TENTACLES;
    }

    /**
     * モデル空間の点(GeckoLib 描画空間のモデル単位、胴の中心線基準、頭が -Z)をワールド座標へ。
     * 描画側が掛ける {@code YP.rotationDegrees(180 - bodyYaw)} と同じ回転。
     */
    public static Vec3 modelToWorld(Vec3 bodyCenter, float bodyYaw, double scale, double ux, double uy, double uz) {
        double a = Math.toRadians(180.0F - bodyYaw);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double x = ux * cos + uz * sin;
        double z = -ux * sin + uz * cos;
        return bodyCenter.add(x / 16.0D * scale, uy / 16.0D * scale, z / 16.0D * scale);
    }

    /** {@code index} 本目の腹側の触手の先端のワールド座標({@link #TENTACLE_TIPS} 参照)。 */
    public static Vec3 tentacleTip(Vec3 bodyCenter, float bodyYaw, double scale, int index) {
        double[] tip = TENTACLE_TIPS[Math.floorMod(index, VENTRAL_TENTACLES)];
        return modelToWorld(bodyCenter, bodyYaw, scale, tip[0], tip[1], tip[2]);
    }

    /** 点 {@code p} から線分 {@code a}-{@code b} までの距離。 */
    public static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0E-9D) {
            return p.distanceTo(a);
        }
        double t = Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0D, 1.0D);
        return p.distanceTo(a.add(ab.scale(t)));
    }

    private TitanicaKinematics() {
    }
}
