package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.OvergrownVegetation;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 空の巨大生物の当たり判定の形の元(2026-10-18)。モデルの道具 {@code tools/g5-models/mobs/SkyGlaucusHitShapes.java} が
 * モデルとアニメから測って書き出した {@link #RESOURCE} を読む(素材と同じく非公開のフォルダ。無ければ {@link #get()} が null = 当たり判定なし)。
 * サーバーとクライアントの両方が同じものを読み、{@link SkyBossPose} が今の姿勢での世界の形に直す。
 *
 * <p>座標はすべて<b>モデルの座標</b>(geo.json と同じ。単位はモデルの 1 単位、x + が生き物の左)。
 * アニメの点は体の骨を静止のまま測ってあるので、持ち主の体の骨の今の変換を掛ければ世界の点になる。
 */
public final class SkyBossShapes {

    public static final String RESOURCE = "/assets/overgrown_vegetation/textures/entity/sky_glaucus_hit.bin";
    private static final int MAGIC = 0x534B5948;
    /**
     * 版 3(10-18 夜): 蕾ごとの骨の名前を足した(壊れた蕾を隠すため)。版 4(10-19 の技): 扇に downdraft・brace、終わりに体の技のアニメ(spit_body)と
     * 触手の技のアニメ(tentacle_lash・tentacle_grab)。版 5: 技の出どころの点(樹液の腺・腹の袋・口の奥)。版 3 も読む(技のアニメ・点は無いだけ)。
     */
    private static final int VERSION = 5;
    private static final int OLDEST = 3;

    // ------------------------------------------------------------------ 体の骨(根の body・頭・b01〜b18・尾 16)
    public final int bodyCount;
    public final String[] bodyName;
    /** 親の体の骨の添字(-1 = 体の根)。 */
    public final int[] bodyParent;
    public final float[][] bodyPivot;
    /** 輪切りの楕円 {z0, z1, 中心の y, 横の半径, 縦の半径}。 */
    public final float[][][] bodySlices;

    /** うねり(undulate): 長さ(秒)・コマの数(0〜n で n+1 コマ)・コマごと骨ごとの回転(モデルの度)と位置(モデルの単位)。 */
    public final float undulateLength;
    public final int undulateFrames;
    public final float[][][] undulateRot;
    public final float[][][] undulatePos;

    // ------------------------------------------------------------------ 腕と扇
    public final int armCount;
    public final String[] armName;
    public final int[] armOwner;
    /** 腕の軸の点の数(付け根 → 節 → 扇の付け根)と、点ごとの太さ(半径)。 */
    public final int armPoints;
    public final float[][] armRadius;
    /** 扇の刃の数(刃 1 本 = 根元・中ほど・先の 3 点)と、刃の板の厚みの半分。 */
    public final int[] bladeCount;
    public final float[] sheetHalf;
    /** 扇の層のアニメ(glide・stroke・turn_left・turn_right)。 */
    public final Map<String, FanAnim> fanAnims = new LinkedHashMap<>();

    // ------------------------------------------------------------------ 腹の触手
    public final float driftLength;
    public final int driftFrames;
    public final int tentCount;
    public final int[] tentOwner;
    public final int tentPoints;
    public final float[][] tentRadius;
    /** [コマ][触手] = 点 {x, y, z, x, y, z, ...}。 */
    public final float[][][] tentPts;

    // ------------------------------------------------------------------ 技のアニメ(版 4 から)
    /** 体の技のアニメ(spit_body)。並びはうねりと同じ。 */
    public final Map<String, BodyAnim> bodyAnims = new LinkedHashMap<>();
    /** 触手の技のアニメ(tentacle_lash・tentacle_grab)。並びはたなびきと同じ。 */
    public final Map<String, TentAnim> tentAnims = new LinkedHashMap<>();
    /** 技の出どころの点(版 5 から): 名前 → {持ち主の体の骨, x, y, z}(静止の形のモデルの座標)。gland1_l〜・sac1_l〜・maw。 */
    public final Map<String, float[]> spots = new LinkedHashMap<>();

    // ------------------------------------------------------------------ 避雷針・蕾(ふだんは動かない)
    public final int rodOwner;
    public final float[][] rodPts;
    public final float[] rodRadius;
    public final int budCount;
    /** 蕾の骨の名前(budNN_l / budNN_r)。 */
    public final String[] budName;
    public final int[] budOwner;
    public final float[][] budCenter;
    public final float[] budRadius;
    /** 蕾の静止の z のいちばん頭の側・尾の側(転がりのビームで頭から尾へ順に開く割合)。 */
    public final float budZMin;
    public final float budZMax;

    /** 扇の層のアニメ 1 本: [コマ][腕] = 腕の軸の点 → 刃の点(刃ごとに 3 点)の順の {x, y, z, ...}。 */
    public static final class FanAnim {
        public final String name;
        public final float length;
        public final int frames;
        public final float[][][] pts;

        FanAnim(String name, float length, int frames, float[][][] pts) {
            this.name = name;
            this.length = length;
            this.frames = frames;
            this.pts = pts;
        }
    }

    /** 体の技のアニメ 1 本: [コマ][体の骨] = 回転・位置(道具の値。度とモデルの単位)。 */
    public static final class BodyAnim {
        public final String name;
        public final float length;
        public final int frames;
        public final float[][][] rot;
        public final float[][][] pos;

        BodyAnim(String name, float length, int frames, float[][][] rot, float[][][] pos) {
            this.name = name;
            this.length = length;
            this.frames = frames;
            this.rot = rot;
            this.pos = pos;
        }
    }

    /** 触手の技のアニメ 1 本: [コマ][触手] = 点 {x, y, z, ...}(たなびきと同じ)。 */
    public static final class TentAnim {
        public final String name;
        public final float length;
        public final int frames;
        public final float[][][] pts;

        TentAnim(String name, float length, int frames, float[][][] pts) {
            this.name = name;
            this.length = length;
            this.frames = frames;
            this.pts = pts;
        }
    }

    private static SkyBossShapes instance;
    private static boolean tried;

    /** 読み込んだ形(無い・壊れているときは null。1 回だけ読む)。 */
    public static synchronized SkyBossShapes get() {
        if (!tried) {
            tried = true;
            try (InputStream in = SkyBossShapes.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    OvergrownVegetation.LOGGER.warn("空の巨大生物の当たり判定の形 {} が無いので、当たり判定なしで動かす", RESOURCE);
                } else {
                    instance = new SkyBossShapes(new DataInputStream(new BufferedInputStream(in)));
                }
            } catch (Exception e) {
                OvergrownVegetation.LOGGER.error("空の巨大生物の当たり判定の形 {} が読めない", RESOURCE, e);
                instance = null;
            }
        }
        return instance;
    }

    private SkyBossShapes(DataInputStream in) throws Exception {
        if (in.readInt() != MAGIC) {
            throw new IllegalStateException("形式が違う(モデルの道具で書き出し直す)");
        }
        int version = in.readInt();
        if (version < OLDEST || version > VERSION) {
            throw new IllegalStateException("形の版 " + version + " は読めない(" + OLDEST + "〜" + VERSION + "。モデルの道具で書き出し直す)");
        }
        bodyCount = in.readInt();
        bodyName = new String[bodyCount];
        bodyParent = new int[bodyCount];
        bodyPivot = new float[bodyCount][];
        bodySlices = new float[bodyCount][][];
        for (int i = 0; i < bodyCount; i++) {
            bodyName[i] = in.readUTF();
            bodyParent[i] = in.readInt();
            bodyPivot[i] = vec(in);
            int n = in.readInt();
            bodySlices[i] = new float[n][5];
            for (int s = 0; s < n; s++) {
                for (int k = 0; k < 5; k++) {
                    bodySlices[i][s][k] = in.readFloat();
                }
            }
        }
        undulateLength = in.readFloat();
        undulateFrames = in.readInt();
        undulateRot = new float[undulateFrames + 1][bodyCount][];
        undulatePos = new float[undulateFrames + 1][bodyCount][];
        for (int f = 0; f <= undulateFrames; f++) {
            for (int i = 0; i < bodyCount; i++) {
                undulateRot[f][i] = vec(in);
                undulatePos[f][i] = vec(in);
            }
        }
        armCount = in.readInt();
        armName = new String[armCount];
        armOwner = new int[armCount];
        armRadius = new float[armCount][];
        bladeCount = new int[armCount];
        sheetHalf = new float[armCount];
        int ap = 0;
        for (int a = 0; a < armCount; a++) {
            armName[a] = in.readUTF();
            armOwner[a] = in.readInt();
            ap = in.readInt();
            armRadius[a] = new float[ap];
            for (int k = 0; k < ap; k++) {
                armRadius[a][k] = in.readFloat();
            }
            bladeCount[a] = in.readInt();
            sheetHalf[a] = in.readFloat();
        }
        armPoints = ap;
        int nAnims = in.readInt();
        for (int k = 0; k < nAnims; k++) {
            String name = in.readUTF();
            float len = in.readFloat();
            int frames = in.readInt();
            float[][][] pts = new float[frames + 1][armCount][];
            for (int f = 0; f <= frames; f++) {
                for (int a = 0; a < armCount; a++) {
                    int n = (armPoints + bladeCount[a] * 3) * 3;
                    float[] p = new float[n];
                    for (int i = 0; i < n; i++) {
                        p[i] = in.readFloat();
                    }
                    pts[f][a] = p;
                }
            }
            fanAnims.put(name, new FanAnim(name, len, frames, pts));
        }
        driftLength = in.readFloat();
        driftFrames = in.readInt();
        tentCount = in.readInt();
        tentOwner = new int[tentCount];
        tentRadius = new float[tentCount][];
        int tp = 0;
        for (int t = 0; t < tentCount; t++) {
            tentOwner[t] = in.readInt();
            tp = in.readInt();
            tentRadius[t] = new float[tp];
            for (int k = 0; k < tp; k++) {
                tentRadius[t][k] = in.readFloat();
            }
        }
        tentPoints = tp;
        tentPts = new float[driftFrames + 1][tentCount][];
        for (int f = 0; f <= driftFrames; f++) {
            for (int t = 0; t < tentCount; t++) {
                float[] p = new float[tentPoints * 3];
                for (int i = 0; i < p.length; i++) {
                    p[i] = in.readFloat();
                }
                tentPts[f][t] = p;
            }
        }
        rodOwner = in.readInt();
        int rp = in.readInt();
        rodPts = new float[rp][];
        rodRadius = new float[rp];
        for (int i = 0; i < rp; i++) {
            rodPts[i] = vec(in);
            rodRadius[i] = in.readFloat();
        }
        budCount = in.readInt();
        budName = new String[budCount];
        budOwner = new int[budCount];
        budCenter = new float[budCount][];
        budRadius = new float[budCount];
        for (int i = 0; i < budCount; i++) {
            budName[i] = in.readUTF();
            budOwner[i] = in.readInt();
            budCenter[i] = vec(in);
            budRadius[i] = in.readFloat();
        }
        float zMin = Float.MAX_VALUE;
        float zMax = -Float.MAX_VALUE;
        for (float[] c : budCenter) {
            zMin = Math.min(zMin, c[2]);
            zMax = Math.max(zMax, c[2]);
        }
        budZMin = zMin;
        budZMax = zMax;
        if (version >= 4) {
            int nb = in.readInt();
            for (int k = 0; k < nb; k++) {
                String name = in.readUTF();
                float len = in.readFloat();
                int frames = in.readInt();
                float[][][] rot = new float[frames + 1][bodyCount][];
                float[][][] pos = new float[frames + 1][bodyCount][];
                for (int f = 0; f <= frames; f++) {
                    for (int i = 0; i < bodyCount; i++) {
                        rot[f][i] = vec(in);
                        pos[f][i] = vec(in);
                    }
                }
                bodyAnims.put(name, new BodyAnim(name, len, frames, rot, pos));
            }
            int nt = in.readInt();
            for (int k = 0; k < nt; k++) {
                String name = in.readUTF();
                float len = in.readFloat();
                int frames = in.readInt();
                float[][][] pts = new float[frames + 1][tentCount][];
                for (int f = 0; f <= frames; f++) {
                    for (int t = 0; t < tentCount; t++) {
                        float[] p = new float[tentPoints * 3];
                        for (int i = 0; i < p.length; i++) {
                            p[i] = in.readFloat();
                        }
                        pts[f][t] = p;
                    }
                }
                tentAnims.put(name, new TentAnim(name, len, frames, pts));
            }
        }
        if (version >= 5) {
            int ns = in.readInt();
            for (int k = 0; k < ns; k++) {
                String name = in.readUTF();
                int owner = in.readInt();
                float[] c = vec(in);
                spots.put(name, new float[]{owner, c[0], c[1], c[2]});
            }
        }
    }

    private static float[] vec(DataInputStream in) throws Exception {
        return new float[]{in.readFloat(), in.readFloat(), in.readFloat()};
    }

    /** 蕾の添字(無ければ -1)。 */
    public int budIndex(String name) {
        for (int i = 0; i < budCount; i++) {
            if (budName[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** 体の骨の添字(無ければ -1)。 */
    public int bodyIndex(String name) {
        for (int i = 0; i < bodyCount; i++) {
            if (bodyName[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
