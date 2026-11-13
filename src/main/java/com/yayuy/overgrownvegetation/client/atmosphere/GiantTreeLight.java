package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * <b>森の外から見た巨大樹の明るさ</b>(2026-11-13。ユーザー決定「巨大樹だけ」。見え方の点検 B「離れて見ると幹が真っ黒な塊」)。
 *
 * <p>巨大樹の樹冠は数十ブロックの幅があるので、その下の幹・枝・枝の葉は空の光が 0 になる。森の中では林床の持ち上げ({@link CanopyLift}、
 * ライトマップ)で見えるが、あれはカメラの頭上の覆いに付いているので、森の外の開けた所から見ると効かず、明るい空を背に目印の木が真っ黒だった。
 * ここは分かっている巨大樹({@link GiantTrees}。形を写し終えた木)の近い順 {@value #MAX} 本の「軸・傘の半径・根元の高さ」を地形のシェーダーへ渡し、
 * その円筒の中で根元から {@value #FROM}〜{@value #FULL} 段より上の頂点だけ、ライトマップを空の光の下限 {@code light.giantOutsideSky} 以上で読ませる
 * ({@code ov_giant.glsl})。
 *
 * <p>木だけ: 樹冠の下の林床は樹冠の影のまま(高さで外す)、洞窟・岩の張り出しには触らない。カメラが覆いの下にいる間は下限を 0 へ
 * (ライトマップの持ち上げに任せる = 二重に持ち上げない。{@link ForestPresence#openCover} は時間でならしてあるので森の縁で跳ばない)、
 * 夜も 0 へ(夜空を背にした暗い影のまま)。形は霧の品質({@link VisualQuality#fog})が切れていると入らないので、そのときは効かない。値は仮決め。
 */
final class GiantTreeLight {

    static final int MAX = 4;
    /** 根元からこの高さで効き始め、{@link #FULL} で全部効く(下の森の林冠より上。仮決め)。 */
    static final float FROM = 24.0F;
    static final float FULL = 40.0F;
    /** これより遠い木は渡さない(ブロック。描画距離 32 チャンクの少し先まで)。 */
    private static final double REACH = 640.0;

    private static final float[][] TREES = new float[MAX][4];
    private static float floor;

    private GiantTreeLight() {
    }

    /** フレームに 1 回({@link CanopySunMap#upload} から)。{@code night} = 夜の度合い 0〜1。 */
    static void prepare(ClientLevel level, Vec3 cam, boolean enabled, float night) {
        floor = 0.0F;
        for (float[] t : TREES) {
            t[2] = 0.0F;
        }
        if (!enabled || level == null || !VisualQuality.canopyLight() || !level.dimensionType().hasSkyLight()) {
            return;
        }
        float k = DesignData.atmosphere().giantOutsideSky() * (1.0F - night) * (1.0F - ForestPresence.openCover());
        if (k <= 0.001F) {
            return;
        }
        List<GiantTrees.Info> all = GiantTrees.all();
        double[] dist = new double[MAX];
        int n = 0;
        for (GiantTrees.Info t : all) {
            if (!t.scanned()) {
                continue;
            }
            double dx = t.axisX() - cam.x;
            double dz = t.axisZ() - cam.z;
            double d = dx * dx + dz * dz;
            if (d > REACH * REACH) {
                continue;
            }
            // 近い順に差し込む。
            int at = n < MAX ? n : MAX - 1;
            if (n >= MAX && d >= dist[MAX - 1]) {
                continue;
            }
            while (at > 0 && dist[at - 1] > d) {
                dist[at] = dist[at - 1];
                System.arraycopy(TREES[at - 1], 0, TREES[at], 0, 4);
                at--;
            }
            dist[at] = d;
            TREES[at][0] = (float) dx;
            TREES[at][1] = (float) dz;
            TREES[at][2] = (float) t.crownRadius();
            TREES[at][3] = (float) (t.baseY() - cam.y);
            if (n < MAX) {
                n++;
            }
        }
        floor = n > 0 ? k : 0.0F;
    }

    static void set(ShaderInstance shader) {
        for (int i = 0; i < MAX; i++) {
            float[] t = TREES[i];
            shader.safeGetUniform("OvGiant" + i).set(t[0], t[1], t[2], t[3]);
        }
        shader.safeGetUniform("OvGiantK").set(floor, FROM, FULL, 0.0F);
    }

    /** 確かめ用。 */
    static String describe() {
        int n = 0;
        for (float[] t : TREES) {
            if (t[2] > 0.0F) {
                n++;
            }
        }
        return String.format("巨大樹の下限 %.2f(%d 本・一番近い木 半径 %.0f 根元 %+.0f)覆い %.2f 森の霧 %.2f", floor, n, TREES[0][2], TREES[0][3],
                ForestPresence.openCover(), ForestPresence.fogForest());
    }
}
