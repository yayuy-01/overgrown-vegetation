package com.yayuy.overgrownvegetation.data;

/**
 * 熱水の森(ネザーの汚染地)の大きな植物の設計値({@code heat_forest.json}、2026-11-10 追記 14)。
 *
 * <p>普通の木 B の小・中・大(割合・高さ・幹の太さ・傘の半径)と、管の樹の高さ。ユーザーが写真を見て変える見込みがあるので、
 * Java から移した(値は移す前と同じ)。読めない・節が無い・項目が無いときは、ここに書いた既定値(= 移す前の値)を使う。
 * サーバー側({@code corruption.HeatForestTrees})が木を置くときに読む。反映は再起動。
 *
 * <p>傘の半径は {@code radius + (高さ − minHeight) × radiusPerBlock}、上の段の半径はその {@code topRadiusRatio} 倍。
 * 高さは {@code min + random.nextInt(max − min + 1)}(移す前と同じ引き方)。
 */
public final class HeatForestTable {

    public static final HeatForestTable DEFAULT = new HeatForestTable(null);

    /** Gson の受け皿。null は既定値。 */
    public static final class Json {
        public GroundTree groundTree;
        public TubeTree tubeTree;
    }

    public static final class GroundTree {
        public Double smallShare;
        public Double largeShare;
        public Double topRadiusRatio;
        public SizeJson small;
        public SizeJson medium;
        public SizeJson large;
    }

    public static final class SizeJson {
        public Integer minHeight;
        public Integer maxHeight;
        public Integer trunk;
        public Double radius;
        public Double radiusPerBlock;
    }

    public static final class TubeTree {
        public TubeJson thin;
        public TubeJson thick;
    }

    public static final class TubeJson {
        public Integer middleMin;
        public Integer middleMax;
        public Integer sideMin;
        public Integer sideMax;
    }

    /** 普通の木の 1 つの大きさ。 */
    public record TreeSize(int minHeight, int maxHeight, int trunk, double radius, double radiusPerBlock) {
        static TreeSize of(SizeJson j, TreeSize d) {
            if (j == null) return d;
            int min = j.minHeight != null ? Math.max(1, Math.min(40, j.minHeight)) : d.minHeight;
            int max = j.maxHeight != null ? Math.max(min, Math.min(40, j.maxHeight)) : Math.max(min, d.maxHeight);
            int trunk = j.trunk != null ? Math.max(1, Math.min(3, j.trunk)) : d.trunk;
            double radius = j.radius != null ? Math.max(0.5D, Math.min(12.0D, j.radius)) : d.radius;
            double per = j.radiusPerBlock != null ? Math.max(0.0D, Math.min(2.0D, j.radiusPerBlock)) : d.radiusPerBlock;
            return new TreeSize(min, max, trunk, radius, per);
        }
    }

    /** 管の樹の 1 つの形(真ん中の管とまわりの管の高さ)。 */
    public record TubeHeights(int middleMin, int middleMax, int sideMin, int sideMax) {
        static TubeHeights of(TubeJson j, TubeHeights d) {
            if (j == null) return d;
            int mMin = j.middleMin != null ? Math.max(1, Math.min(40, j.middleMin)) : d.middleMin;
            int mMax = j.middleMax != null ? Math.max(mMin, Math.min(40, j.middleMax)) : Math.max(mMin, d.middleMax);
            int sMin = j.sideMin != null ? Math.max(1, Math.min(40, j.sideMin)) : d.sideMin;
            int sMax = j.sideMax != null ? Math.max(sMin, Math.min(40, j.sideMax)) : Math.max(sMin, d.sideMax);
            return new TubeHeights(mMin, mMax, sMin, sMax);
        }
    }

    // 既定値 = 移す前の Java の値(11-10 ユーザー決定・異論なし。上限 9 は仮)
    private static final TreeSize SMALL = new TreeSize(3, 5, 1, 2.2D, 0.2D);
    private static final TreeSize MEDIUM = new TreeSize(4, 7, 2, 4.0D, 0.2D);
    private static final TreeSize LARGE = new TreeSize(8, 11, 2, 5.0D, 0.2D);
    private static final TubeHeights THIN = new TubeHeights(4, 9, 3, 8);
    private static final TubeHeights THICK = new TubeHeights(9, 13, 7, 11);

    private final double smallShare;
    private final double largeShare;
    private final double topRadiusRatio;
    private final TreeSize small, medium, large;
    private final TubeHeights thin, thick;

    private HeatForestTable(Json j) {
        Json src = j != null ? j : new Json();
        GroundTree g = src.groundTree != null ? src.groundTree : new GroundTree();
        double s = g.smallShare != null ? Math.max(0.0D, Math.min(1.0D, g.smallShare)) : 0.35D;
        double l = g.largeShare != null ? Math.max(0.0D, Math.min(1.0D - s, g.largeShare)) : Math.min(1.0D - s, 0.15D);
        this.smallShare = s;
        this.largeShare = l;
        this.topRadiusRatio = g.topRadiusRatio != null ? Math.max(0.1D, Math.min(1.0D, g.topRadiusRatio)) : 0.6D;
        this.small = TreeSize.of(g.small, SMALL);
        this.medium = TreeSize.of(g.medium, MEDIUM);
        this.large = TreeSize.of(g.large, LARGE);
        TubeTree t = src.tubeTree != null ? src.tubeTree : new TubeTree();
        this.thin = TubeHeights.of(t.thin, THIN);
        this.thick = TubeHeights.of(t.thick, THICK);
    }

    public static HeatForestTable fromJson(Json j) {
        return j == null ? DEFAULT : new HeatForestTable(j);
    }

    /** 小の割合(0〜1)。 */
    public double smallShare() {
        return smallShare;
    }

    /** 大の割合(0〜1)。中は残り。 */
    public double largeShare() {
        return largeShare;
    }

    /** 上の段の傘の半径 = 下の段 × これ。 */
    public double topRadiusRatio() {
        return topRadiusRatio;
    }

    public TreeSize small() {
        return small;
    }

    public TreeSize medium() {
        return medium;
    }

    public TreeSize large() {
        return large;
    }

    /** 細い管の樹(A)。 */
    public TubeHeights thinTube() {
        return thin;
    }

    /** 太い管の樹(B)。 */
    public TubeHeights thickTube() {
        return thick;
    }
}
