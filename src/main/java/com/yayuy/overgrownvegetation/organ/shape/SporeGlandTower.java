package com.yayuy.overgrownvegetation.organ.shape;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import com.yayuy.overgrownvegetation.organ.OrganCell;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>胞子腺の姿 —— 三つ編みの塔</b>(2026-09-23、要望)。
 *
 * <p>器官の蔦と汚染ブロックを混ぜた筋が<b>3 本</b>、互いに編み込まれながら立ち上がる。
 * 先端は細く絞られ、そこから胞子が噴き出す。表面には腺が点在していて、
 * 不用意に削ると毒を浴びる。
 *
 * <p><b>この形は胞子腺だけのもの。</b> 器官の姿は種類ごとに違う(要望)ので、
 * ここに一般化を持ち込まないこと —— 次の器官は塔ですらない。
 *
 * <h2>どうやって「編んで」いるか</h2>
 * <p>{@code weaveDegrees}(角度)と {@code braidAmplitude}(半径)を<b>同じ位相で</b>振る。
 * 詳しくは {@link #fillBraid}。<b>角度を振らないと筋同士が一度も触れない</b>。
 *
 * <p>材質も編み目の一部。<b>筋ごとに蔦と汚染ブロックを塗り分ける</b>({@link #part}) ——
 * 座標の雑音で混ぜると、筋が触れ合った瞬間に境目が消えて 1 本の柱に見える。
 *
 * <h2>根と先</h2>
 * <ul>
 *     <li><b>根</b>({@code rootHeight}) —— 下端は編まずに<b>塊</b>にして、節を中へ埋める。
 *     節がむき出しだと近寄って殴るだけで終わってしまうので、
 *     <b>掘って辿り着くもの</b>にしてある(掘る途中で腺に触る)。</li>
 *     <li><b>先</b> —— {@code taper} で細く絞り、いちばん上のマスは必ず腺にする。
 *     ここが胞子の噴き出し口({@code SporeGlandBlock} が自分で粒を出す)。</li>
 * </ul>
 *
 * <h2>形は保存しない</h2>
 * <p>{@link #build} は<b>節の位置と種だけ</b>から決まる純粋な関数。
 * だから枯らすとき({@code OrganWither})も同じ関数を呼び直すだけでよく、
 * 組み上がったブロック一覧をセーブに抱えなくて済む。
 *
 * <p>形のつまみは {@code tools/organ-preview/organ-preview.html} で確かめられる
 * (同じ手順を JavaScript で書き写したもの)。値を変えても<b>既に建っている塔は変わらない</b> ——
 * 枯らすときに形が食い違うので、建てたあとに大きく動かさないこと。
 */
public final class SporeGlandTower {

    /** 根の塊の印({@code solid} の中身)。筋の番号(1 以上)と区別するため。 */
    private static final byte ROOT = -1;

    private SporeGlandTower() {
    }

    /**
     * 塔を組み立てる。座標は<b>節からの相対</b>({@code y = 0} が節の高さ)。
     *
     * @param seed ワールドの種。混ぜ方と腺の位置がこれで決まる
     */
    public static List<OrganCell> build(long seed, int nodeX, int nodeY, int nodeZ) {
        return build(seed, nodeX, nodeY, nodeZ, DesignData.organs().sporeGland().tower());
    }

    /**
     * 形の値を外から渡す版。{@code tools/organ-preview} の答え合わせと、
     * つまみを振って見比べるのに使う(ゲームを起動せずに同じ形が出せる)。
     */
    public static List<OrganCell> build(long seed, int nodeX, int nodeY, int nodeZ, OrganTable.Tower spec) {
        int height = spec.height();
        int reach = (int) Math.ceil(Math.max(spec.rootRadius(),
                spec.radius() + spec.braidAmplitude() + spec.strandThickness())) + 1;
        int width = reach * 2 + 1;

        // 0 = 空。1 以上 = その番号の筋。ROOT = 根の塊。
        // どの筋のものかを覚えておくのが肝 —— 材質を筋ごとに変えないと、
        // 筋が触れ合った瞬間に<b>見分けが付かなくなって 1 本の柱に見える</b>(報告)。
        byte[] solid = new byte[width * height * width];
        fillBraid(solid, spec, reach, width, height);
        fillRoot(solid, spec, reach, width, height);

        List<OrganCell> out = new ArrayList<>();
        int topY = -1;
        int topX = 0;
        int topZ = 0;
        for (int y = 0; y < height; y++) {
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    if (solid[index(x, y, z, reach, width)] == 0) {
                        continue;
                    }
                    boolean surface = isSurface(solid, x, y, z, reach, width, height);
                    OrganCell.Part part = part(seed, nodeX + x, nodeY + y, nodeZ + z, spec, surface,
                            solid[index(x, y, z, reach, width)]);
                    out.add(new OrganCell(x, y, z, part));
                    if (y > topY) {
                        topY = y;
                        topX = x;
                        topZ = z;
                    }
                }
            }
        }
        if (topY >= 0) {
            // いちばん上は必ず腺にする —— ここが噴き出し口。
            OrganCell tip = new OrganCell(topX, topY, topZ, OrganCell.Part.GLAND);
            out.removeIf(c -> c.x() == tip.x() && c.y() == tip.y() && c.z() == tip.z());
            out.add(tip);
        }
        return out;
    }

    /** 下端の塊。節を埋めるためのもの。上へ行くほど細くして編み目へ繋ぐ。 */
    private static void fillRoot(byte[] solid, OrganTable.Tower spec, int reach, int width, int height) {
        int rootHeight = Math.min(spec.rootHeight(), height);
        for (int y = 0; y < rootHeight; y++) {
            double t = rootHeight <= 1 ? 1.0D : (double) y / (rootHeight - 1);
            double r = spec.rootRadius() + (spec.radius() - spec.rootRadius()) * t;
            disc(solid, 0.0D, 0.0D, r, y, reach, width, height, ROOT);
        }
    }

    /**
     * 3 本の筋。ねじりながら、<b>角度と半径を同じ位相で揺らして</b>編む。
     *
     * <h3>半径だけ揺らしても編めない</h3>
     * <p>最初は半径だけを内外に振っていたが、それでは<b>独立した蔦がうねりながら立っているだけ</b>に見えた
     * (報告)。理由は幾何で決まっている —— 筋の<b>角度が 120 度で固定</b>だと、
     * 半径をどう振っても隣の筋との距離が縮まらない。
     * 半径 4.2 なら隣とは常に 7 ブロック以上離れていて、<b>一度も触れない</b>。
     *
     * <h3>角度も振ると「すれ違い」になる</h3>
     * <p>{@code weaveDegrees} で角度を、{@code braidAmplitude} で半径を、<b>同じ位相で</b>振る。
     * 隣り合う筋は位相が 120 度ずれているので、両者の差はどちらも
     * {@code √3 · cos(…)} に比例する —— つまり
     * <b>角度の隙間がいちばん狭まる高さで、半径の差もいちばん大きい</b>。
     * 片方が内側、片方が外側を通ってすれ違う。これが編み目。
     *
     * <p><b>位相を 90 度ずらしてはいけない</b>(sin と cos に分けるなど)。
     * 角度が縮まる瞬間に半径が揃ってしまい、すれ違いではなく<b>ぶつかって団子</b>になる。
     */
    private static void fillBraid(byte[] solid, OrganTable.Tower spec, int reach, int width, int height) {
        int strands = Math.max(1, spec.strands());
        double twist = Math.toRadians(spec.twistDegrees());
        double weave = Math.toRadians(spec.weaveDegrees());
        for (int y = 0; y < height; y++) {
            double t = height <= 1 ? 0.0D : (double) y / (height - 1);
            double axisRadius = spec.radius() * (1.0D - spec.taper() * t);
            // 先端では筋も細くする(絞りきったところで太いままだと団子になる)。
            double thickness = spec.strandThickness() * (1.0D - 0.45D * t);
            for (int i = 0; i < strands; i++) {
                double phase = 2.0D * Math.PI * i / strands;
                double wobble = Math.sin(2.0D * Math.PI * y / Math.max(1.0D, spec.braidPeriod()) + phase);
                double angle = phase + twist * y + weave * wobble;
                double r = Math.max(0.0D, axisRadius + spec.braidAmplitude() * wobble);
                disc(solid, r * Math.cos(angle), r * Math.sin(angle), thickness, y, reach, width, height,
                        (byte) (i + 1));
            }
        }
    }

    private static void disc(byte[] solid, double cx, double cz, double radius,
                             int y, int reach, int width, int height, byte owner) {
        if (y < 0 || y >= height || radius <= 0.0D) {
            return;
        }
        int lo = (int) Math.floor(-radius);
        int hi = (int) Math.ceil(radius);
        double r2 = radius * radius;
        for (int dx = lo; dx <= hi; dx++) {
            for (int dz = lo; dz <= hi; dz++) {
                int x = (int) Math.round(cx) + dx;
                int z = (int) Math.round(cz) + dz;
                if (x < -reach || x > reach || z < -reach || z > reach) {
                    continue;
                }
                double ddx = x - cx;
                double ddz = z - cz;
                if (ddx * ddx + ddz * ddz <= r2) {
                    solid[index(x, y, z, reach, width)] = owner;
                }
            }
        }
    }

    /** 隣に空きがあるか。腺は<b>表面にしか</b>出さない(中に埋めても見えない)。 */
    private static boolean isSurface(byte[] solid, int x, int y, int z,
                                     int reach, int width, int height) {
        return open(solid, x + 1, y, z, reach, width, height)
                || open(solid, x - 1, y, z, reach, width, height)
                || open(solid, x, y, z + 1, reach, width, height)
                || open(solid, x, y, z - 1, reach, width, height)
                || open(solid, x, y + 1, z, reach, width, height)
                || open(solid, x, y - 1, z, reach, width, height);
    }

    private static boolean open(byte[] solid, int x, int y, int z,
                                int reach, int width, int height) {
        if (x < -reach || x > reach || z < -reach || z > reach || y < 0 || y >= height) {
            return true;
        }
        return solid[index(x, y, z, reach, width)] == 0;
    }

    /**
     * そのマスに何を置くか。
     *
     * <h3>材質は「筋ごと」に決める</h3>
     * <p>最初は蔦と汚染ブロックを<b>座標の雑音</b>で混ぜていたが、それだと
     * <b>どの筋も同じまだら模様</b>になり、筋が触れ合った瞬間に境目が消えて
     * 1 本の太い柱に見えた(報告)。ブロックの粗さでは<b>形だけで「上を通った」を表せない</b>ので、
     * <b>材質で筋を見分けさせる</b>しかない。
     *
     * <p>だから隣り合う筋は必ず違う材質にする(偶数番は蔦、奇数番は汚染ブロック)。
     * 本数が奇数だと一周したところで同じ材質が隣り合うが、
     * そこは編み目の 1 か所だけなので目立たない。
     *
     * <p>{@code vineRatio} は<b>まだらの強さ</b>に変わった —— その筋の材質のまま通すか、
     * たまに相手側の材質を混ぜるか。1.0 でくっきり、0.5 で元のまだらに近づく。
     *
     * <p>根の塊({@link #ROOT})だけは筋を持たないので、今までどおり座標の雑音で混ぜる。
     */
    private static OrganCell.Part part(long seed, int worldX, int worldY, int worldZ,
                                       OrganTable.Tower spec, boolean surface, byte owner) {
        if (surface && noise(seed, worldX, worldY, worldZ, 7) < spec.glandChance()) {
            return OrganCell.Part.GLAND;
        }
        boolean vine;
        if (owner == ROOT) {
            vine = noise(seed, worldX, worldY, worldZ, 1) < 0.5D;
        } else {
            vine = (owner - 1) % 2 == 0;
            // たまに相手側を混ぜて、のっぺりした単色の筋にしない。
            if (noise(seed, worldX, worldY, worldZ, 1) > spec.vineRatio()) {
                vine = !vine;
            }
        }
        return vine ? OrganCell.Part.VINE : OrganCell.Part.OVERGROWN;
    }

    private static int index(int x, int y, int z, int reach, int width) {
        return (y * width + (z + reach)) * width + (x + reach);
    }

    /** 0〜1 の決まった雑音。{@code tools/organ-preview} に同じ式がある。 */
    public static double noise(long seed, int x, int y, int z, int salt) {
        long h = seed ^ (salt * 0x9E3779B97F4A7C15L);
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h ^= y * 0x165667B19E3779F9L;
        h ^= z * 0x27D4EB2F165667C5L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }
}
