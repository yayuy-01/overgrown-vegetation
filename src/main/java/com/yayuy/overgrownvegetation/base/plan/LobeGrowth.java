package com.yayuy.overgrownvegetation.base.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * 浄化圏の形以外の広げ方(2026-10-02、ユーザー指定「大型防衛 = 輪、補給 = 大通り沿い、工廠 = 格子」)。変えるのは候補の作り方と点の付け方だけで、
 * 決まりの確かめ(届く範囲・正門の前・広場の空き・重なり・地形・中の空き地)は浄化圏の形と同じ({@link BasePlanner})。
 * 置けなければ null を返し、呼ぶ側が浄化圏の形の継ぎ足しへ戻る(水・急な斜面・届く範囲 108 で詰まって広がらなくならないよう)。
 *
 * <ul>
 *     <li>輪: 候補は浄化圏の形と同じ(全部の浄化圏から 16 方向)で正方形の八角形だけ。今の外形が中心からいちばん近い向きほど点が高く、
 *     外形の一番遠い所より外へ出るほど点が低い → 丸く一回りずつ育つ。区割りの好む向きは見ない。</li>
 *     <li>格子: 四角の浄化圏を決まった行と列の場所へ。内の輪(中心から 48、半幅 27)を先に、外の輪(83、半幅 19)を後に。
 *     届く範囲 108 に収まる大きさ。中心の浄化圏は八角形のまま。</li>
 *     <li>大通り沿い: レベル 2 で中心の東西の通り(z = 6)の延長に東西へ 1 つずつ(東西に長い形 → 通りがつながって大通り)、
 *     レベル 3 でその端から北か南へ曲がる(南北に長い形)、レベル 4 で中心か大通りから枝(南北)。それ以外の区割りはふつうの継ぎ足し。</li>
 *     <li>出城(2026-10-02 夜、ユーザー指定「観測 = 出城」「研究は観測と同じ出城で」「離れた出城の場所にその特別な施設を置く」):
 *     レベル 2 と 4 の 1 番目に、本拠から間を空けた出城の浄化圏と、それをつなぐ壁で挟んだ通路({@link Zone#CORRIDOR}、幅 11 の四角)を組で置く。
 *     本拠の継ぎ足しはふつうの浄化圏の形で、通路・出城とその予定地から離す({@link #keep})。決まりは浄化圏の形と別({@link #pairOk})
 *     —— 出城はどの浄化圏にも重ならないので、重なりの決まり(MIN_OVERLAP)は使えない。</li>
 * </ul>
 */
final class LobeGrowth {

    /** 中心の浄化圏の東西の通り(z 4..8)の真ん中。大通りの浄化圏はこの線に中心をそろえる。 */
    static final int AVENUE_Z = 6;
    /**
     * 水の標本から離す幅。地形の標本は 8 マスおきなので、浄化圏の形の 6 マスでは標本のあいだの水(湖の縁)を見落とし、
     * 浄化圏が水にかかった(2026-10-02、補給・川・種 2)。浄化の形の計画は変えない(建った基地と合わなくなる)ので、ほかの種類だけ広く。
     */
    static final int WATER_MARGIN = 8;
    /**
     * 格子の場所 {中心の x, 中心の z, 半幅}(正方形)。行と列は x・z = 0、±44、±85 にそろう(同じ行の浄化圏の東西の通りがつながる)。
     * 内の輪(44、半幅 24 = 49 角)が先、外の輪(85、半幅 23 = 47 角、外の端が届く範囲 108)が後。南の真ん中は正門の前なので無い。
     * 外の輪も、通りの両側に奥行 11 の列が 1 本ずつ入る広さ(前の 83・半幅 19 の細い四角には兵舎・弾薬庫が建たなかった)。
     */
    private static final int[][] GRID = {
            {44, 0, 24}, {-44, 0, 24}, {0, -44, 24}, {44, -44, 24}, {-44, -44, 24}, {44, 44, 24}, {-44, 44, 24},
            {85, 0, 23}, {-85, 0, 23}, {0, -85, 23}, {44, -85, 23}, {-44, -85, 23}, {85, -44, 23}, {-85, -44, 23},
            {85, 44, 23}, {-85, 44, 23}, {85, -85, 23}, {-85, -85, 23}, {85, 85, 23}, {-85, 85, 23}, {44, 85, 23}, {-44, 85, 23}};
    private static final int GRID_INNER = 24;

    // ---------- 出城(2026-10-02 夜。数はどれも仮) ----------

    /**
     * 出城の浄化圏の半幅(正方形の八角形)。真ん中の広場(13 × 13)の先に奥行 11・幅 15 の施設が、外周から 7 マス以上離れて入る大きさ
     * (斜めの辺の近くはチェビシェフ距離で 1.4 倍空くので、半幅 22 では入らない)。
     */
    static final int OUTPOST_R = 26;
    /** 通路の半幅(幅 11 = 壁 2・巡回路 1・道 5・巡回路 1・壁 2。真ん中の列が内側の柵の列(外まで 6)になるので、そこは必ず道で塗る)。 */
    static final int CORRIDOR_HALF = 5;
    /** 通路の両端を、本拠と出城の中へ差し込む深さ(四角の角の落とし 4 より深く、縁で通路の幅がそろうように)。 */
    private static final int CORRIDOR_IN = 10;
    /**
     * 本拠の縁と出城の縁のあいだ(通路が外に出ている長さ)の候補。届く範囲 108 の中では、中心の浄化圏(半幅 36)から出せるのは
     * 36 + 間 + 1 + 2 × 26 ≤ 108 → 19 まで。
     */
    private static final int[] OUTPOST_GAPS = {13, 16, 19};
    /** 通路の坂の上限(なだらかな面の 1 マスあたりの高低差)。1 未満なら道の段差は 1 段まで(丸めても 2 段にならない)。 */
    private static final double CORRIDOR_SLOPE = 0.8;
    /**
     * 通路の線を通りからずらす幅の候補(東西なら z、南北なら x)。辺から直角に出られる範囲だけ通る(中心の浄化圏は線が |z − 0| ≤ 10)。
     * 通りの線だけでは、実際の地形(台帳の 5 基地)で 1 基地しか出城を離して置けなかった(起伏と水)。
     */
    private static final int[] LINE_OFFSETS = {0, -4, 4, -8, 8, -12, 12, -16};
    /** 決まりどおりの場所が無いときの、出城だけの起伏の上限(浄化圏の形の緩めは 30。仮)。 */
    private static final int OUTPOST_STEEP = 40;

    private final BaseType type;
    /** 大通り沿い: 先に伸ばす側(+1 = 東)と、東の端・西の端で曲がる向き(−1 = 北、+1 = 南)。種で決める。 */
    private final int firstSide;
    private final int bendEast;
    private final int bendWest;
    /** 大通りの東の端・西の端の浄化圏の番号(置けなかったら −1)。 */
    private int east = -1;
    private int west = -1;
    /**
     * 出城: 2 つ目の出城の予定地 {通路, 出城}(1 つ目を置いたとき、ほかの向きでいちばん点の高い所)。本拠の継ぎ足しはここから離す
     * (レベル 2〜3 の継ぎ足しが通路の向きをふさいで、レベル 4 で置けなくならないよう)。無ければ null。
     */
    private Lobe[] reserved;

    LobeGrowth(BaseType type, long seed) {
        this.type = type;
        Random r = new Random(seed * 0x2545F4914F6CDD1DL + 0x9E3779B97F4A7C15L);
        firstSide = r.nextBoolean() ? 1 : -1;
        bendEast = r.nextBoolean() ? 1 : -1;
        bendWest = r.nextBoolean() ? 1 : -1;
    }

    /** 区割り {@code zone} の浄化圏の場所(置けなければ null)。{@code wish} = そのレベルの足す区割りの何番目か(目安の面積の埋め足しは −1)。 */
    Lobe place(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, int wish) {
        if (type.growth == BaseType.Growth.SPINE && !spineSlot(lv, wish) || type.growth == BaseType.Growth.OUTPOST) {
            return null; // 出城の基地の本拠は浄化圏の形の継ぎ足し(通路・出城から離す。keep)。出城そのものは outpost
        }
        for (boolean relaxed : new boolean[]{false, true}) {
            List<Lobe> cands = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            switch (type.growth) {
                case RING -> ring(rng, ls, mask, zone, lv, terrain, relaxed, cands, scores);
                case GRID -> grid(rng, ls, mask, zone, lv, terrain, relaxed, cands, scores);
                case SPINE -> spine(rng, ls, mask, zone, lv, terrain, relaxed, wish, cands, scores);
                default -> {
                    return null;
                }
            }
            Lobe b = best(cands, scores, mask);
            if (b != null) {
                if (type.growth == BaseType.Growth.SPINE && lv == 2 && b.cz() == AVENUE_Z && b.parent() == 0) {
                    if (b.cx() > 0) {
                        east = ls.size();
                    } else {
                        west = ls.size();
                    }
                }
                return b;
            }
        }
        return null;
    }

    /** 点の高い順に、中に空き地を閉じ込めないものを選ぶ。 */
    private static Lobe best(List<Lobe> cands, List<Double> scores, boolean[] mask) {
        for (int tries = 0; tries < 64 && !cands.isEmpty(); tries++) {
            int best = 0;
            for (int i = 1; i < cands.size(); i++) {
                if (scores.get(i) > scores.get(best)) {
                    best = i;
                }
            }
            Lobe b = cands.remove(best);
            scores.remove(best);
            boolean[] next = mask.clone();
            BasePlan.paint(next, b);
            if (!BasePlanner.hasHole(next)) {
                return b;
            }
        }
        return null;
    }

    /** 決まりを満たせば重なる浄化圏の数、満たさなければ −1。 */
    private static int valid(Lobe b, List<Lobe> ls, boolean[] mask, BaseTerrain terrain, boolean relaxed) {
        for (Lobe c : ls) {
            if (c.cx() == b.cx() && c.cz() == b.cz()) {
                return -1;
            }
        }
        int over = BasePlanner.quickValid(b, ls, mask);
        return over < 0 || !BasePlanner.terrainOk(b, terrain, relaxed, WATER_MARGIN) ? -1 : over;
    }

    // ---------- 輪 ----------

    private void ring(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, boolean relaxed,
                      List<Lobe> cands, List<Double> scores) {
        double rmax = 0;
        for (int k = 0; k < 32; k++) {
            rmax = Math.max(rmax, rayRadius(mask, 2 * Math.PI * k / 32));
        }
        for (int pi = 0; pi < ls.size(); pi++) {
            Lobe p = ls.get(pi);
            for (int k = 0; k < 16; k++) {
                double th = 2 * Math.PI * k / 16;
                double ux = Math.cos(th);
                double uz = Math.sin(th);
                for (int r : BasePlanner.LARGE_SIZES) {
                    for (double f : BasePlanner.REACH) {
                        double reach = f * (BasePlanner.extent(p.rx(), p.rz(), p.cut(), ux, uz)
                                + BasePlanner.extent(r, r, (int) Math.round(0.71 * (2 * r)), ux, uz));
                        int cx = p.cx() + (int) Math.round(ux * reach);
                        int cz = p.cz() + (int) Math.round(uz * reach);
                        Lobe b = new Lobe(zone, lv, cx, cz, r, r, pi);
                        int over = valid(b, ls, mask, terrain, relaxed);
                        if (over < 0) {
                            continue;
                        }
                        double toward = rayRadius(mask, Math.atan2(cz, cx));
                        double far = Math.hypot(cx, cz) + r;
                        double s = (rmax - toward) / 8.0 - Math.max(0, far - rmax) / 12.0 + 0.6 * (over - 1)
                                - terrain.relief(b.x0(), b.z0(), b.x1(), b.z1()) / 20.0 + rng.nextDouble() * 0.9;
                        cands.add(b);
                        scores.add(s);
                    }
                }
            }
        }
    }

    /** 中心から向き {@code th} へ、今の基地の中のいちばん遠いマスまでの距離。 */
    private static double rayRadius(boolean[] mask, double th) {
        double ux = Math.cos(th);
        double uz = Math.sin(th);
        double last = 0;
        for (double d = 0; d < BasePlan.GRID; d += 1) {
            int x = (int) Math.round(ux * d);
            int z = (int) Math.round(uz * d);
            if (BasePlan.onGrid(x, z) && mask[BasePlan.idx(x, z)]) {
                last = d;
            }
        }
        return last;
    }

    // ---------- 格子 ----------

    private void grid(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, boolean relaxed,
                      List<Lobe> cands, List<Double> scores) {
        double[] pref = BasePlanner.preferred(zone);
        for (int[] c : GRID) {
            int parent = nearest(ls, c[0], c[1]);
            Lobe b = new Lobe(zone, lv, c[0], c[1], c[2], c[2], parent, true);
            int over = valid(b, ls, mask, terrain, relaxed);
            if (over < 0) {
                continue;
            }
            double len = Math.max(1, Math.hypot(c[0], c[1]));
            double s = (c[2] == GRID_INNER ? 6.0 : 0.0) + 1.5 * (over - 1) + 2.0 * (c[0] * pref[0] + c[1] * pref[1]) / len
                    - terrain.relief(b.x0(), b.z0(), b.x1(), b.z1()) / 20.0 + rng.nextDouble() * 0.9;
            cands.add(b);
            scores.add(s);
        }
    }

    private static int nearest(List<Lobe> ls, int x, int z) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < ls.size(); i++) {
            double d = Math.hypot(ls.get(i).cx() - x, ls.get(i).cz() - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    // ---------- 大通り沿い ----------

    /** 大通りの形で置く枠か(レベル 2 の 1〜2 番目 = 大通り、レベル 3 の 1〜2 番目 = 曲がり、レベル 4 の 1 番目 = 枝)。ほかはふつうの継ぎ足し。 */
    private static boolean spineSlot(int lv, int wish) {
        return (lv == 2 || lv == 3) && (wish == 0 || wish == 1) || lv == 4 && wish == 0;
    }

    private void spine(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, boolean relaxed, int wish,
                       List<Lobe> cands, List<Double> scores) {
        if (lv == 2) {
            // 中心の東西の通りの延長(東西に長い形)。1 本目は両側を候補に(種の側を先に)、2 本目は残った側だけ ——
            // 1 本目の側が水などで置けないと、代わりのふつうの継ぎ足しが反対側の大通りの場所を先に取ってしまった
            int[] sides = wish == 0 ? new int[]{firstSide, -firstSide} : east >= 0 && west >= 0 ? new int[0]
                    : new int[]{east >= 0 ? -1 : west >= 0 ? 1 : -firstSide};
            Lobe core = ls.get(0);
            for (int side : sides) {
                for (int r : BasePlanner.LARGE_SIZES) {
                    int rx = r + 6;
                    int rz = r - 4;
                    for (double f : BasePlanner.REACH) {
                        double reach = f * (BasePlanner.extent(core.rx(), core.rz(), core.cut(), 1, 0)
                                + BasePlanner.extent(rx, rz, (int) Math.round(0.71 * (rx + rz)), 1, 0));
                        Lobe b = new Lobe(zone, lv, side * (int) Math.round(reach), AVENUE_Z, rx, rz, 0);
                        add(rng, ls, mask, terrain, relaxed, b, side == firstSide ? 1.0 : 0.0, cands, scores);
                    }
                }
            }
        } else if (lv == 3) {
            // 大通りの端から北か南へ曲がる(南北に長い形)。種の向きを先に、だめなら逆へ。1 本目 = 先に伸ばした側の端、2 本目 = もう一方の端
            List<Integer> ends = new ArrayList<>();
            for (int e : firstSide > 0 ? new int[]{east, west} : new int[]{west, east}) {
                if (e >= 0) {
                    ends.add(e);
                }
            }
            if (wish >= ends.size()) {
                return;
            }
            int end = ends.get(wish);
            Lobe e = ls.get(end);
            int bend = e.cx() > 0 ? bendEast : bendWest;
            for (int dir : new int[]{bend, -bend}) {
                for (int r : BasePlanner.LARGE_SIZES) {
                    int rx = r - 4;
                    int rz = r + 6;
                    for (double f : BasePlanner.REACH) {
                        double reach = f * (BasePlanner.extent(e.rx(), e.rz(), e.cut(), 0, 1)
                                + BasePlanner.extent(rx, rz, (int) Math.round(0.71 * (rx + rz)), 0, 1));
                        Lobe b = new Lobe(zone, lv, e.cx(), e.cz() + dir * (int) Math.round(reach), rx, rz, end);
                        add(rng, ls, mask, terrain, relaxed, b, dir == bend ? 1.0 : 0.0, cands, scores);
                    }
                }
            }
        } else {
            // 枝: 中心(北へ)か大通りの浄化圏(北か南へ)から、南北に長い形
            int[] anchors = {0, east, west};
            for (int a : anchors) {
                if (a < 0) {
                    continue;
                }
                Lobe p = ls.get(a);
                for (int dir : a == 0 ? new int[]{-1} : new int[]{-1, 1}) {
                    for (int r : BasePlanner.LARGE_SIZES) {
                        int rx = r - 4;
                        int rz = r + 6;
                        for (double f : BasePlanner.REACH) {
                            double reach = f * (BasePlanner.extent(p.rx(), p.rz(), p.cut(), 0, 1)
                                    + BasePlanner.extent(rx, rz, (int) Math.round(0.71 * (rx + rz)), 0, 1));
                            Lobe b = new Lobe(zone, lv, p.cx(), p.cz() + dir * (int) Math.round(reach), rx, rz, a);
                            add(rng, ls, mask, terrain, relaxed, b, a == 0 ? 1.0 : 0.0, cands, scores);
                        }
                    }
                }
            }
        }
    }

    private static void add(Random rng, List<Lobe> ls, boolean[] mask, BaseTerrain terrain, boolean relaxed, Lobe b, double bonus,
                            List<Lobe> cands, List<Double> scores) {
        int over = valid(b, ls, mask, terrain, relaxed);
        if (over < 0) {
            return;
        }
        cands.add(b);
        scores.add(bonus - terrain.relief(b.x0(), b.z0(), b.x1(), b.z1()) / 20.0 + rng.nextDouble() * 0.3);
    }

    // ---------- 出城 ----------

    /**
     * 出城を置く枠か(出城の基地のレベル 2 の 1 番目 = 1 つ目の出城、観測はレベル 4 の 1 番目 = 2 つ目も)。研究は出城 1 つに大きな研究所 1 棟だけ
     * (ユーザー指定 2026-10-02 夜「何棟も置くものではなく一棟だけ大きな施設」)。
     */
    boolean outpostSlot(int lv, int wish) {
        return type.growth == BaseType.Growth.OUTPOST && wish == 0 && (lv == 2 || lv == 4 && secondOutpost());
    }

    /** 2 つ目の出城を置く種類か(観測だけ)。 */
    private boolean secondOutpost() {
        return type == BaseType.OBSERVATION;
    }

    /** {@link #keep} の段階の数(出城の基地は 3 段、ほかの広げ方は 1 段 = 絞らない)。 */
    int keepLevels() {
        return type.growth == BaseType.Growth.OUTPOST ? 3 : 1;
    }

    /**
     * 本拠の継ぎ足しの候補を使ってよいか: 出城の基地では通路・出城から {@link BasePlanner#MIN_APART} 以上離す
     * (くっつくと通路が本拠に呑まれて、離れた出城に見えない)。段階 {@code level}: 0 = 通路・出城・2 つ目の出城の予定地から離す、
     * 1 = 出城に継ぎ足してよい(出城が育つ)、2 = 予定地も譲る(通路からだけ離す)。本拠が東西の出城・南の正門・北の届く範囲に囲まれて
     * 広がらなくなった(2026-10-02、レベル 5 は全部・川の地形はレベル 4 も)ので、前の段階で置けないときだけ次の段階へ緩める
     * (予定地を先に譲ると、レベル 3 の弾薬区が予定地を取って 2 つ目の出城が置けなくなった)。
     * 継ぎ足しは本拠か 1 つの出城の、どちらか一方の塊にだけ付ける(両方に付くと出城と本拠を橋渡しする。川の地形で、出城に付けた弾薬区が
     * 斜めの辺の切れ目から本拠の角にも届き、閉じた空き地の決まりをすり抜けて出城が離れて見えなくなった)。ほかの広げ方は null(調べない = 前と同じ)。
     */
    Predicate<Lobe> keep(List<Lobe> ls, int level) {
        if (type.growth != BaseType.Growth.OUTPOST) {
            return null;
        }
        int[] cluster = clusters(ls);
        return b -> {
            int joined = Integer.MIN_VALUE;
            for (int i = 0; i < ls.size(); i++) {
                Lobe c = ls.get(i);
                if (b.separation(c) <= -BasePlanner.MIN_APART) {
                    continue;
                }
                // 研究の出城(とその増築)には本拠の浄化圏を付けない(広がった所は研究所の増築に使う。ユーザー指定 2026-10-02 夜その 3)。
                // 出城を離して置けず本拠に付けた研究の浄化圏(塊 0)は本拠の一部なので、ほかと同じに隣へ継ぎ足してよい
                if (c.zone() == Zone.CORRIDOR || c.zone() == Zone.RESEARCH && cluster[i] != 0 || level == 0 && cluster[i] != 0) {
                    return false;
                }
                if (joined == Integer.MIN_VALUE) {
                    joined = cluster[i];
                } else if (joined != cluster[i]) {
                    return false;
                }
            }
            if (level < 2 && reserved != null) {
                for (Lobe c : reserved) {
                    if (b.separation(c) > -BasePlanner.MIN_APART) {
                        return false;
                    }
                }
            }
            if (level < 2) {
                for (int[] e : researchPlan) {
                    if (b.separation(expansionLobe(e, 0, -1)) > -BasePlanner.MIN_APART) {
                        return false;
                    }
                }
            }
            return true;
        };
    }

    // ---------- 研究の出城の増築(2026-10-02 夜その 3、ユーザー指定「出城が拡張される際、研究施設も一緒に拡張される。縦に伸ばすのではなく、
    // 拡張された場所も使った巨大な施設群」) ----------

    /** 増築の浄化圏の中心どうしの間(出城の真ん中から横へ。半幅 26 の八角形どうしがおよそ 0.79 倍の深さで重なる)。 */
    static final int EXPANSION_STEP = 41;
    /** 連絡通路の長さ(棟の壁のあいだ。本館 25 幅・増築 27 幅を浄化圏の真ん中に置いたとき): 1 つ目の輪 15、2 つ目の輪 14。 */
    private static final int[] WALKWAY_LEN = {0, EXPANSION_STEP - 12 - 13 - 1, EXPANSION_STEP - 13 - 13 - 1};

    /**
     * 研究の出城を広げる予定 {中心の x, 中心の z, 横の向き(+1 / −1), 輪(1 / 2)}(レベル 2 で出城を置いたときに決め、レベル 3〜5 の 1 番目に順に置く)。
     * 出城の横(通路と直角)にしか広がれない —— 出城の先は届く範囲 108 の端。
     */
    private final List<int[]> researchPlan = new ArrayList<>();
    /** 研究の出城の番号と、横の向きごとの 1 つ目の輪の増築の番号(置いていなければ −1)。 */
    private int researchO = -1;
    private final int[] ring1 = {-1, -1};

    /** 研究の出城の増築を置く枠か(研究の基地のレベル 3〜5 の、区割り = 研究の枠)。 */
    boolean expansionSlot(int lv, Zone zone) {
        return type == BaseType.RESEARCH && zone == Zone.RESEARCH && lv >= 3;
    }

    /** 予定 {@code e} の浄化圏(半幅 26 の正方形の八角形)。 */
    private static Lobe expansionLobe(int[] e, int lv, int parent) {
        return new Lobe(Zone.RESEARCH, lv, e[0], e[1], OUTPOST_R, OUTPOST_R, parent);
    }

    /**
     * 研究の出城 {@code o}(通路 {@code c} の先)の増築の予定を決める: 横の両側の 1 つ目の輪(真ん中から 41)、その外の 2 つ目の輪(82)。
     * 届く範囲・正門の前の通り・地形(水・起伏)と、連絡通路で上り下りできる高さの差(棟の土台の差 ≤ 通路の長さ − 1)を満たすものだけ。
     * 順は 1 つ目の輪(土台の差の小さい側が先)→ 2 つ目の輪。
     */
    private void planResearch(Lobe c, Lobe o, BaseTerrain terrain, int index) {
        researchPlan.clear();
        researchO = index;
        boolean alongX = c.rx() > c.rz(); // 通路が東西なら、横は南北
        int lx = alongX ? 0 : 1;
        int lz = alongX ? 1 : 0;
        Facing back = alongX ? (c.cx() < o.cx() ? Facing.WEST : Facing.EAST) : (c.cz() < o.cz() ? Facing.NORTH : Facing.SOUTH);
        int main = terrain.pad(building(BuildingKind.FIELD_LAB, o.cx(), o.cz(), back));
        List<int[]> first = new ArrayList<>();
        List<int[]> second = new ArrayList<>();
        for (int s : new int[]{1, -1}) {
            int prevPad = main;
            int prevWall = BuildingKind.FIELD_LAB.width / 2; // 内側の棟の外向きの壁(真ん中からの横の距離)
            for (int k = 1; k <= 2; k++) {
                int cx = o.cx() + lx * s * EXPANSION_STEP * k;
                int cz = o.cz() + lz * s * EXPANSION_STEP * k;
                Lobe e = new Lobe(Zone.RESEARCH, 3, cx, cz, OUTPOST_R, OUTPOST_R, -1);
                int reach = Math.max(Math.max(Math.abs(e.x0()), Math.abs(e.x1())), Math.max(Math.abs(e.z0()), Math.abs(e.z1())));
                if (reach > BasePlan.MAX_REACH || BasePlanner.frontOfGate(e) || !expansionTerrainOk(e, terrain)) {
                    break;
                }
                int shift = annexShift(terrain, cx, cz, lx * s, lz * s, back, prevPad, EXPANSION_STEP * k - prevWall);
                if (shift < 0) {
                    break; // 連絡通路で上り下りできない(1 マスに 1 段まで)
                }
                int pad = terrain.pad(building(BuildingKind.ANNEX_LAB, cx + lx * s * shift, cz + lz * s * shift, back));
                (k == 1 ? first : second).add(new int[]{cx, cz, s, k, Math.abs(pad - prevPad)});
                prevPad = pad;
                prevWall = EXPANSION_STEP * k + shift + BuildingKind.ANNEX_LAB.width / 2;
            }
        }
        first.sort((a, b) -> a[4] != b[4] ? Integer.compare(a[4], b[4]) : Integer.compare(b[2], a[2]));
        second.sort((a, b) -> a[4] != b[4] ? Integer.compare(a[4], b[4]) : Integer.compare(b[2], a[2]));
        researchPlan.addAll(first);
        researchPlan.addAll(second);
    }

    /**
     * 増築の浄化圏の地形の決まり: 水は緩めの見方(八角形を広げた形)、起伏はふつうの上限 {@link BasePlanner#STEEP}(緩めの 30 は使わない。仮)。
     * 27 幅の大きな棟は土台が足元の平均なので、急な斜面だと低い側の盛り土の縁(法面 6 マスの先)が 5〜7 段の崖になり、壁沿いの巡回路と見張り台が
     * 切り離された(2026-10-02 夜、台帳の基地 #1 の起伏 30 の増築で見張り台 2 本・壁の除去装置に人が届かなかった)。
     */
    private static boolean expansionTerrainOk(Lobe e, BaseTerrain terrain) {
        return !terrain.anyWater(e, WATER_MARGIN) && terrain.relief(e.x0(), e.z0(), e.x1(), e.z1()) <= BasePlanner.STEEP;
    }

    /** 増築の棟を浄化圏の真ん中から外へずらしてよい幅(27 幅の棟が半幅 26 の八角形の、外まで 7 以上のマスに収まる限り)。 */
    static final int ANNEX_SHIFT_MAX = 3;

    /**
     * 増築の棟を浄化圏の真ん中 (cx, cz) から外(ux, uz)へ何マスずらせば、内側の棟(土台 {@code innerPad}、真ん中からの横の距離 {@code gap} に外向きの壁)と
     * 連絡通路で上り下りできるか(土台の差 ≤ 通路の長さ − 1)。0 から {@link #ANNEX_SHIFT_MAX} まで小さい順に試し、駄目なら −1。
     * {@code gap} = 浄化圏の中心どうしの横の距離 − 内側の棟の半幅。
     */
    static int annexShift(BaseTerrain terrain, int cx, int cz, int ux, int uz, Facing back, int innerPad, int gap) {
        for (int shift = 0; shift <= ANNEX_SHIFT_MAX; shift++) {
            int len = gap + shift - BuildingKind.ANNEX_LAB.width / 2 - 1;
            int pad = terrain.pad(building(BuildingKind.ANNEX_LAB, cx + ux * shift, cz + uz * shift, back));
            if (len >= 3 && Math.abs(pad - innerPad) <= len - 1) {
                return shift;
            }
        }
        return -1;
    }

    /** 種類 {@code kind} の棟を (cx, cz) を真ん中に、正面を {@code back} へ向けて置いた区画(土台の高さを測るだけ)。 */
    private static Plot building(BuildingKind kind, int cx, int cz, Facing back) {
        int sx = back.dx == 0 ? kind.width : kind.depth;
        int sz = back.dx == 0 ? kind.depth : kind.width;
        return new Plot(kind, Zone.RESEARCH, 0, cx - sx / 2, cz - sz / 2, cx - sx / 2 + sx - 1, cz - sz / 2 + sz - 1, back, 0L);
    }

    /**
     * 研究の出城の次の増築(置けなければ null)。予定の順に、つなぐ先(1 つ目の輪 = 出城、2 つ目の輪 = 同じ側の 1 つ目の輪)があって、
     * 浄化圏の形の決まり(重なり・届く範囲・広場の空き・地形)を満たし、研究の浄化圏にだけ触れる(本拠・通路から 6 以上離れる)ものを置く。
     */
    Lobe researchExpansion(List<Lobe> ls, boolean[] mask, int lv, BaseTerrain terrain) {
        while (!researchPlan.isEmpty()) {
            int[] e = researchPlan.remove(0);
            int side = e[2] > 0 ? 0 : 1;
            int parent = e[3] == 1 ? researchO : ring1[side];
            if (parent < 0 || parent >= ls.size()) {
                continue;
            }
            Lobe b = expansionLobe(e, lv, parent);
            if (BasePlanner.quickValid(b, ls, mask) < 0 || !expansionTerrainOk(b, terrain)) {
                continue;
            }
            boolean ok = true;
            for (Lobe c : ls) {
                if (b.separation(c) > -BasePlanner.MIN_APART && c.zone() != Zone.RESEARCH) {
                    ok = false;
                    break;
                }
            }
            if (!ok || BasePlanner.hasHole(painted(mask, new Lobe[]{b}))) {
                continue;
            }
            if (e[3] == 1) {
                ring1[side] = ls.size();
            }
            return b;
        }
        return null;
    }

    /** 浄化圏ごとの塊の番号(本拠 = 0、出城とそれに継ぎ足したもの = 1, 2, …、通路 = −1)。継ぎ足した先(parent)をたどる。 */
    private static int[] clusters(List<Lobe> ls) {
        int[] k = new int[ls.size()];
        int next = 1;
        for (int i = 0; i < ls.size(); i++) {
            Lobe l = ls.get(i);
            if (l.zone() == Zone.CORRIDOR) {
                k[i] = -1;
            } else if (l.parent() < 0) {
                k[i] = 0;
            } else if (ls.get(l.parent()).zone() == Zone.CORRIDOR) {
                k[i] = next++;
            } else {
                k[i] = k[l.parent()];
            }
        }
        return k;
    }

    /**
     * 出城の組 {通路, 出城}(置けなければ null)。出城は本拠の浄化圏の通りの延長の先(中心の浄化圏なら東西の通り z = 6 の東か西、
     * または北の x = 0。通りからずらした線も、辺から直角に出られる範囲で)に、間を空けて置き、壁で挟んだ通路でつなぐ。
     * 観測は高台を、研究は平らな所を好む(仮)。地形の決まりは 3 段(決まりどおり → 浄化圏の形と同じ緩め → 出城だけ起伏 {@link #OUTPOST_STEEP} まで)。
     * レベル 2 で 1 つ目を置いたとき、ほかの向きの一番を 2 つ目の予定地に取っておき、レベル 4 でそこへ置く。
     */
    Lobe[] outpost(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain) {
        if (reserved != null) {
            Lobe[] r = reserved;
            reserved = null;
            Lobe[] pair = {at(r[0], lv, r[0].parent()), at(r[1], lv, ls.size())};
            if (pairOk(pair, ls, mask, terrain, 2) && !BasePlanner.hasHole(painted(mask, pair))) {
                return pair;
            }
        }
        for (int mode = 0; mode <= 2; mode++) {
            List<Lobe[]> cands = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            List<Integer> keys = new ArrayList<>();
            gather(rng, ls, mask, zone, lv, terrain, mode, cands, scores, keys);
            int best = pick(cands, scores, mask, i -> true);
            if (best < 0) {
                continue;
            }
            Lobe[] b = cands.get(best);
            if (zone == Zone.RESEARCH) {
                planResearch(b[0], b[1], terrain, ls.size() + 1); // 出城は通路の次に足される
            }
            if (lv == 2 && secondOutpost()) {
                boolean[] after = painted(mask, b);
                int second = pick(cands, scores, after, i -> !keys.get(i).equals(keys.get(best)) && apart(cands.get(i), b));
                if (second >= 0) {
                    reserved = cands.get(second);
                }
            }
            return b;
        }
        return null;
    }

    /** 出城の組の候補を集める。向きの見分け {@code keys} = 浄化圏の番号 × 4 + 向き(線のずらしは同じ向き)。 */
    private static void gather(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, int mode,
                               List<Lobe[]> cands, List<Double> scores, List<Integer> keys) {
        double home = terrain.design(0, 0);
        for (int pi = 0; pi < ls.size(); pi++) {
            Lobe p = ls.get(pi);
            if (p.zone() == Zone.CORRIDOR || BaseType.outpostZone(p.zone())) {
                continue;
            }
            // {東西の向き, 南北の向き, 通りの線}: 中心は東西の通り(z = 6)の東・西と北(南は正門)、ほかは浄化圏の長い向きの通りの両方
            int[][] dirs = pi == 0 ? new int[][]{{1, 0, AVENUE_Z}, {-1, 0, AVENUE_Z}, {0, -1, 0}}
                    : p.alongX() ? new int[][]{{1, 0, p.cz()}, {-1, 0, p.cz()}} : new int[][]{{0, -1, p.cx()}, {0, 1, p.cx()}};
            for (int di = 0; di < dirs.length; di++) {
                int[] d = dirs[di];
                for (int off : LINE_OFFSETS) {
                    for (int gap : OUTPOST_GAPS) {
                        Lobe[] pair = pair(p, pi, d[0], d[1], d[2] + off, gap, zone, lv, ls.size());
                        if (!pairOk(pair, ls, mask, terrain, mode)) {
                            continue;
                        }
                        Lobe o = pair[1];
                        double s = gap / 6.0 - terrain.relief(o.x0(), o.z0(), o.x1(), o.z1()) / (zone == Zone.RESEARCH ? 10.0 : 20.0)
                                + (off == 0 ? 0.6 : 0.0) + rng.nextDouble() * 0.6;
                        if (zone == Zone.OBSERVATION) {
                            s += (terrain.design(o.cx(), o.cz()) - home) / 4.0; // 見晴らしのよい高台
                        }
                        if (pi == 0 && d[1] < 0) {
                            s -= 0.5; // 北: 通路の道が中心の居住区の列のあいだを抜ける(東西は中心の東西の通りへそのままつながる)
                        }
                        cands.add(pair);
                        scores.add(s);
                        keys.add(pi * 4 + di);
                    }
                }
            }
        }
    }

    /** 浄化圏 {@code p} の縁から向き (ux, uz) へ、線 {@code line}(東西なら z、南北なら x)の上に間 {@code gap} を空けて出城、そのあいだに通路。 */
    private static Lobe[] pair(Lobe p, int pi, int ux, int uz, int line, int gap, Zone zone, int lv, int corridorIndex) {
        int u = ux != 0 ? ux : uz;
        int edge = ux > 0 ? p.x1() : ux < 0 ? p.x0() : uz > 0 ? p.z1() : p.z0();
        int near = edge + u * (gap + 1); // 出城の手前の辺
        int oc = near + u * OUTPOST_R;
        int a = edge - u * CORRIDOR_IN;
        int b = near + u * CORRIDOR_IN;
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        if (((hi - lo) & 1) == 1) {
            hi++; // 中心を整数に(端は浄化圏の中なので 1 マス延ばしてよい)
        }
        int cc = (lo + hi) / 2;
        int half = (hi - lo) / 2;
        Lobe corridor = ux != 0 ? new Lobe(Zone.CORRIDOR, lv, cc, line, half, CORRIDOR_HALF, pi, true)
                : new Lobe(Zone.CORRIDOR, lv, line, cc, CORRIDOR_HALF, half, pi, true);
        Lobe outpost = ux != 0 ? new Lobe(zone, lv, oc, line, OUTPOST_R, OUTPOST_R, corridorIndex)
                : new Lobe(zone, lv, line, oc, OUTPOST_R, OUTPOST_R, corridorIndex);
        return new Lobe[]{corridor, outpost};
    }

    /**
     * 出城の組の決まり: 通路は元の浄化圏の平らな辺から直角に出る / 届く範囲・正門の前の通り / 出城はどの浄化圏からも {@link BasePlanner#MIN_APART}
     * 以上離れる / 通路の外に出た所の両脇 MIN_APART までに今の基地のマスが無い(ほかの浄化圏に沿わない)/ 水から離れる / 出城の起伏・通路の坂。
     * {@code mode}: 0 = 浄化圏の形と同じ決まり、1 = 同じく緩め(水は八角形で見る・起伏 30 まで)、2 = 出城の起伏を {@link #OUTPOST_STEEP} まで。
     */
    private static boolean pairOk(Lobe[] pair, List<Lobe> ls, boolean[] mask, BaseTerrain terrain, int mode) {
        Lobe c = pair[0];
        Lobe o = pair[1];
        Lobe p = ls.get(c.parent());
        boolean alongX = c.rx() > c.rz();
        int u = alongX ? Integer.signum(o.cx() - p.cx()) : Integer.signum(o.cz() - p.cz());
        int line = alongX ? c.cz() : c.cx();
        int edge = alongX ? (u > 0 ? p.x1() : p.x0()) : (u > 0 ? p.z1() : p.z0());
        for (int j : new int[]{-CORRIDOR_HALF, CORRIDOR_HALF}) {
            if (!(alongX ? p.contains(edge, line + j) : p.contains(line + j, edge))) {
                return false;
            }
        }
        for (Lobe l : pair) {
            int reach = Math.max(Math.max(Math.abs(l.x0()), Math.abs(l.x1())), Math.max(Math.abs(l.z0()), Math.abs(l.z1())));
            if (reach > BasePlan.MAX_REACH || BasePlanner.frontOfGate(l)) {
                return false;
            }
        }
        for (Lobe q : ls) {
            if (o.separation(q) > -BasePlanner.MIN_APART) {
                return false;
            }
        }
        int near = alongX ? (u > 0 ? o.x0() : o.x1()) : (u > 0 ? o.z0() : o.z1());
        int side = CORRIDOR_HALF + BasePlanner.MIN_APART;
        for (int a = edge + u; a != near; a += u) {
            for (int v = -side; v <= side; v++) {
                int x = alongX ? a : line + v;
                int z = alongX ? line + v : a;
                if (BasePlan.onGrid(x, z) && mask[BasePlan.idx(x, z)]) {
                    return false;
                }
            }
        }
        int m = WATER_MARGIN;
        boolean land = mode < 2 ? BasePlanner.terrainOk(o, terrain, mode == 1, m)
                : !terrain.anyWater(o, m) && terrain.relief(o.x0(), o.z0(), o.x1(), o.z1()) <= OUTPOST_STEEP;
        if (!land || terrain.anyWater(c.x0() - m, c.z0() - m, c.x1() + m, c.z1() + m)) {
            return false;
        }
        for (int a = edge - 4 * u; (a - near) * u <= 4; a += 2 * u) {
            double h0 = alongX ? terrain.design(a, line) : terrain.design(line, a);
            double h1 = alongX ? terrain.design(a + 2 * u, line) : terrain.design(line, a + 2 * u);
            if (Math.abs(h1 - h0) > 2 * CORRIDOR_SLOPE) {
                return false;
            }
        }
        return true;
    }

    /** 点の高い順に({@code allowed} のものだけ)、中に空き地を閉じ込めない組の番号。無ければ −1。 */
    private static int pick(List<Lobe[]> cands, List<Double> scores, boolean[] mask, IntPredicate allowed) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> scores.get(a).equals(scores.get(b)) ? Integer.compare(a, b) : Double.compare(scores.get(b), scores.get(a)));
        int tries = 0;
        for (int i : order) {
            if (!allowed.test(i)) {
                continue;
            }
            if (++tries > 64) {
                break;
            }
            if (!BasePlanner.hasHole(painted(mask, cands.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    /** 2 つの組が {@link BasePlanner#MIN_APART} 以上離れているか。 */
    private static boolean apart(Lobe[] a, Lobe[] b) {
        for (Lobe x : a) {
            for (Lobe y : b) {
                if (x.separation(y) > -BasePlanner.MIN_APART) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean[] painted(boolean[] mask, Lobe[] pair) {
        boolean[] next = mask.clone();
        for (Lobe l : pair) {
            BasePlan.paint(next, l);
        }
        return next;
    }

    /** 浄化圏 {@code l} のレベルと継ぎ足した先の番号を替えたもの。 */
    private static Lobe at(Lobe l, int lv, int parent) {
        return new Lobe(l.zone(), lv, l.cx(), l.cz(), l.rx(), l.rz(), parent, l.box());
    }
}
