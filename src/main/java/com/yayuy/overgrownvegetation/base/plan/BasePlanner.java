package com.yayuy.overgrownvegetation.base.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * 基地の配置計画を作る(2026-10-01、10-02 に浄化圏をつなぐ形へ作り直し)。同じ種・レベル・地形なら毎回同じ計画になる。
 *
 * <p><b>形(ユーザー指定 2026-10-02「案 E(浄化圏がつながる)を元に」「古い外周は残さない」)</b>:
 * レベル 1 は中心の八角形の浄化圏だけ。レベルを上げるたびに、今の基地の縁へ八角形の浄化圏({@link Lobe})を重ねて継ぎ足す。
 * どこへ継ぐかは区割りの好む向きと種の乱数で決め、水の標本にかかる場所・起伏のきつい場所・正門の前の通りには置かない。
 * レベル L の浄化圏はレベル L−1 の浄化圏に足すだけなので、内側は動かない。古い外周の帯と巡回路は、ふつうの中のマスになる。
 *
 * <p><b>中の組み立て(ユーザー指定 2026-10-02「中の組み立ては直した後を基準に」。古い外周の跡を通りにする所だけは外した)</b>
 * <ul>
 *     <li>道は 1 つの網: 正門 → 閲兵場の大通り、閲兵場から東西の通り、浄化圏ごとに長い向きの通り 1 本、
 *     それらをつなぐ道(曲がりの少ない最短の道)、外周の内側の巡回路。浄化圏ごとに一周する道は作らない。</li>
 *     <li>中心: 閲兵場(芝と旗竿)、その奥に管理棟、さらに奥に大除去塔の広場。浄化圏の真ん中は除去塔の広場。</li>
 *     <li>建物は通りに面した列(背中合わせの 2 列ごとに路地)。居住区はテント 2 張りと小屋の組、補給・整備は通りとのあいだに
 *     前庭(荷さばき場・整備場)、弾薬庫は通りとのあいだに防爆壁。</li>
 *     <li>外周の角(八角形の頂点)と辺(およそ {@link #TOWER_SPACING} マスおき)に見張り台。</li>
 * </ul>
 *
 * <p>レベルの順に組み立てる: レベル k の建物と道は、レベル k の外周(その時の巡回路を避ける)で決める。だからレベル L の計画は
 * レベル L−1 の計画の建物と道を全部含む(上のレベルを作るときも、下のレベルを同じように作り直してから足す)。
 */
public final class BasePlanner {

    // ---------- 浄化圏 ----------

    /** 中心の浄化圏の半幅(八角形の中心から辺まで)。 */
    public static final int CORE_R = 36;
    /** 継ぎ足す浄化圏の半幅の候補(仮)。支援区(小物だけ)はこれ。 */
    private static final int[] SIZES = {26, 30, 34};
    /**
     * 居住区・補給・整備・弾薬は大きめ(仮)。半幅 26 だと、真ん中の広場の脇から八角形の斜めの辺までに奥行 11 の建物が入る長さが残らず
     * (斜めの辺の近くはチェビシェフ距離で 1.4 倍空く)、居住区の兵舎が 0〜2 張り(ユーザー指摘 2026-10-02「兵舎の数が少なすぎる」)、
     * 弾薬区(半幅 24〜26)の弾薬庫は 9 つのうち 8 つで 0 棟、補給・整備の倉庫・工廠も 0〜2 棟だった(同日「では直しましょう」)。
     */
    static final int[] LARGE_SIZES = {30, 34};
    /** 中心どうしの距離 = 向きの方向の広がりの和 × これ(1 より小さいほど深く重なる)。 */
    static final double[] REACH = {0.74, 0.84};
    private static final int DIRS = 16;
    /** 今の基地に重なるマスの数の下限(太くつながる)。 */
    private static final int MIN_OVERLAP = 220;
    /** 新しく増えるマスの割合の下限。 */
    private static final double MIN_GAIN = 0.45;
    /** 中心の浄化圏の正門の前の通り(|x| ≤ これ、z ≥ CORE_R − 1)には浄化圏を置かない。 */
    private static final int CORRIDOR = 13;
    /** 浄化圏の中の起伏の上限(なだらかな面の高低差)。 */
    static final int STEEP = 14;
    /**
     * 決まりどおりの場所が 1 つも無いときに緩めた起伏の上限(仮)。ユーザー指摘 2026-10-02「レベル 4 で基地が拡張されない」:
     * 実際の地形では浄化圏 1 つぶんの範囲の起伏が 14 を超える所ばかりで(試した 2 基地で 19〜49)、継ぎ足せる場所が無かった。
     */
    private static final int STEEP_RELAXED = 30;
    /** 重ならない浄化圏どうしの間の下限(外に細い路地を作らない)。 */
    static final int MIN_APART = 6;
    /** 浄化圏を水の標本から離す幅。 */
    private static final int WATER_MARGIN = 6;
    /** 除去塔の広場の半幅(13 × 13)。新しい浄化圏の広場(+1)は古い浄化圏の外に置く。 */
    private static final int PLAZA_HALF = 6;

    /** 各レベルで足す区割り(この順に、置けるものから)。居住区はレベル 2〜5 に 1 つずつ(定員が増えるレベルごとに兵舎も増える)。 */
    private static final Zone[][] WISH = {
            {}, {},
            {Zone.LSA, Zone.LOGISTICS, Zone.MAINTENANCE},
            {Zone.SUPPORT, Zone.AMMO, Zone.LSA},
            {Zone.LSA, Zone.MAINTENANCE, Zone.SUPPORT},
            {Zone.LOGISTICS, Zone.LSA, Zone.MAINTENANCE},
    };
    /** 目安の面積に届かないときに足す区割り(居住区は最後。ユーザー指摘 2026-10-02「兵舎の割合が多すぎる」)。 */
    private static final Zone[] FILL = {Zone.LOGISTICS, Zone.MAINTENANCE, Zone.SUPPORT, Zone.LSA};
    /** 目安の面積(レベルごとの基地全体、外周の帯を含む。仮)。 */
    static final int[] AREA = {0, 0, 10000, 16500, 23000, 29000};

    // ---------- 中身 ----------

    /** 建物どうしの間(防火の空き)。小物は {@link #SMALL_GAP}、弾薬庫は {@link #AMMO_GAP}。 */
    private static final int GAP = 3;
    private static final int SMALL_GAP = 2;
    private static final int AMMO_GAP = 6;
    /** 建物の列の奥行。 */
    private static final int ROW = 11;
    /** 通りの幅。 */
    private static final int STREET = 5;
    /** 背中合わせの列のあいだの路地の幅。 */
    private static final int LANE = 3;
    /** 補給・整備の前庭の奥行(通りから建物の正面まで)。 */
    private static final int APRON = 5;
    /** 巡回路(外までの距離 3..5)。 */
    private static final int PATROL_IN = 3;
    private static final int PATROL_OUT = 5;
    /** 建物の足元の、外までの距離の下限(巡回路と空き 1 の内)。 */
    private static final int BUILD_DEPTH = PATROL_OUT + 2;
    /** 見張り台の間隔の下限(角はこれより詰めてよい)。 */
    private static final int TOWER_SPACING = 32;
    private static final int TOWER_CORNER_SPACING = 14;
    /** 空き地に置く照明塔どうしの間の下限(仮。前は空き地の小物の中で 3 × 3 の照明塔がいちばん入りやすく、レベル 5 で 40〜60 本並んだ)。 */
    private static final int LIGHT_SPACING = 16;

    /** {@link #coreCheck} の答え: 置いてよい / 中心か周り {@link #WATER_MARGIN} に水 / 中心が荒い。 */
    public static final int CORE_OK = 0;
    public static final int CORE_WATER = 1;
    public static final int CORE_STEEP = 2;

    /**
     * 世界生成の基地の中心(半幅 {@link #CORE_R} の浄化圏)を置いてよい地形か(2026-10-04、順 2 の着手順 2)。中心はどんな地形でも必ず置かれる
     * ({@link #lobes} は中心の地形を見ない —— {@code /ov base place} は足元に建てる)ので、生成では先にここで確かめる:
     * 中心の八角形を {@link #WATER_MARGIN} 広げた中に水の標本が無く(継ぎ足しの緩めの見方 {@link #terrainOk} と同じ。八角形の欠けた角の外の水は
     * 計画の外なので触らない —— 初めは外接の四角で見ていて、角の外の川でも断っていた)、なだらかな面の高低差が {@code maxRelief} 以下。
     */
    public static int coreCheck(BaseTerrain terrain, int maxRelief) {
        Lobe core = new Lobe(Zone.CORE, 1, 0, 0, CORE_R, CORE_R, -1);
        if (terrain.anyWater(core, WATER_MARGIN)) {
            return CORE_WATER;
        }
        return terrain.relief(core.x0(), core.z0(), core.x1(), core.z1()) <= maxRelief ? CORE_OK : CORE_STEEP;
    }

    /**
     * {@link #coreCheck} が水を見る標本の点(中心の八角形を {@link #WATER_MARGIN} 広げた中の、標本の格子の点 —— {@link BaseTerrain#anyWater(Lobe, int)} と
     * 同じ八角形・同じ点)のうち、座標が {@code stride} の倍数のもの(標本の間隔 8 なら全部、16 なら粗い 1/4)。2026-10-04、{@code /locate} を軽くするため:
     * 水は起伏より先に決まるので、この点のどれかが水なら {@code coreCheck} も {@link #CORE_WATER} —— 粗い点から先に見て早く断れる。
     */
    public static List<int[]> coreWaterPoints(int stride) {
        Lobe core = new Lobe(Zone.CORE, 1, 0, 0, CORE_R, CORE_R, -1);
        Lobe g = new Lobe(core.zone(), core.level(), core.cx(), core.cz(), core.rx() + WATER_MARGIN, core.rz() + WATER_MARGIN, core.parent(), core.box());
        int step = BaseTerrain.STEP;
        List<int[]> out = new ArrayList<>();
        for (int x = Math.floorDiv(g.x0() + step - 1, step) * step; x <= g.x1(); x += step) {
            for (int z = Math.floorDiv(g.z0() + step - 1, step) * step; z <= g.z1(); z += step) {
                if (Math.floorMod(x, stride) == 0 && Math.floorMod(z, stride) == 0 && g.contains(x, z)) {
                    out.add(new int[]{x, z});
                }
            }
        }
        return out;
    }

    /** 地表 {@code sampler} の点 {@code points} のどれかが水か(水面が地面より上)。 */
    public static boolean anyWater(BaseTerrain.Sampler sampler, List<int[]> points) {
        for (int[] p : points) {
            if (sampler.surfaceY(p[0], p[1]) > sampler.groundY(p[0], p[1])) {
                return true;
            }
        }
        return false;
    }

    /** 浄化基地(浄化圏がつながる形)の計画。 */
    public static BasePlan plan(long seed, int level, BaseTerrain terrain) {
        return plan(seed, level, terrain, BaseType.PURIFICATION);
    }

    /** 種類 {@code type} の基地の計画。種類で変わるのは浄化圏の継ぎ足し方と区割りの順・面積の目安だけ(中心と中身の組み立ては同じ)。 */
    public static BasePlan plan(long seed, int level, BaseTerrain terrain, BaseType type) {
        int lv = Math.max(1, Math.min(BasePlan.MAX_LEVEL, level));
        List<Lobe> all = lobes(seed, lv, terrain, type);
        State st = new State(seed);
        st.terrain = terrain;
        for (int k = 1; k <= lv; k++) {
            List<Lobe> upto = new ArrayList<>();
            for (Lobe l : all) {
                if (l.level() <= k) {
                    upto.add(l);
                }
            }
            st.field = BasePlan.distanceField(BasePlan.mask(upto));
            if (k == 1) {
                core(st);
                System.arraycopy(st.road, 0, st.net, 0, st.net.length);
            }
            // 同じレベルの浄化圏の広場と除去塔を先に取っておく(ほかの浄化圏の列やつなぎの道がかからないように)。
            // 出城の通路は道だけ(広場・除去塔・建物を置かない。道は出城の中身を組むときに引く)。研究の出城は真ん中に研究所を建て、
            // 除去塔は前庭の脇に置くので、ここでは取らない(researchContent)
            for (int i = 1; i < all.size(); i++) {
                Zone z = all.get(i).zone();
                if (all.get(i).level() == k && z != Zone.CORRIDOR && z != Zone.RESEARCH) {
                    plaza(st, all.get(i));
                }
            }
            for (int i = 1; i < all.size(); i++) {
                Lobe l = all.get(i);
                if (l.level() != k || l.zone() == Zone.CORRIDOR) {
                    continue;
                }
                if (BaseType.outpostZone(l.zone())) {
                    outpostContent(st, i, l, all);
                } else {
                    lobeContent(st, i, l);
                }
            }
        }
        // 今のレベルの外周に合わせるもの: 巡回路・正門・見張り台
        for (int z = -BasePlan.GRID; z <= BasePlan.GRID; z++) {
            for (int x = -BasePlan.GRID; x <= BasePlan.GRID; x++) {
                int d = st.in(x, z);
                if (d >= PATROL_IN && d <= PATROL_OUT) {
                    st.paint(Surface.PATH, x, z);
                }
            }
        }
        gate(st, all, lv);
        towers(st, all, lv);
        return new BasePlan(seed, lv, type, all, st.plots, st.surface, st.field);
    }

    // ---------- 浄化圏の継ぎ足し ----------

    /** 浄化基地のレベル 1〜{@code level} の浄化圏。 */
    static List<Lobe> lobes(long seed, int level, BaseTerrain terrain) {
        return lobes(seed, level, terrain, BaseType.PURIFICATION);
    }

    /**
     * レベル 1〜{@code level} の浄化圏(中心が先頭)。レベル L の並びはレベル L+1 の並びの頭と同じ。
     * 浄化圏の形以外の広げ方({@link LobeGrowth})は、決まった場所に置けないとき(水・急な斜面・届く範囲)に浄化圏の形の継ぎ足しへ戻る
     * (地形で詰まって広がらなくならないよう)。
     */
    static List<Lobe> lobes(long seed, int level, BaseTerrain terrain, BaseType type) {
        List<Lobe> ls = new ArrayList<>();
        ls.add(new Lobe(Zone.CORE, 1, 0, 0, CORE_R, CORE_R, -1));
        boolean[] mask = BasePlan.mask(ls);
        Random rng = new Random(seed ^ 0x51A3F0B27C9D4E1L);
        LobeGrowth growth = type.growth == BaseType.Growth.LOBES ? null : new LobeGrowth(type, seed);
        int area = count(mask);
        for (int lv = 2; lv <= level; lv++) {
            Zone[] wish = wish(type, lv);
            int fills = 0;
            for (int i = 0; i < wish.length + FILL.length; i++) {
                if (i >= wish.length && area >= area(type, lv)) {
                    break;
                }
                Zone zone = i < wish.length ? wish[i] : FILL[fills++ % FILL.length];
                if (growth != null && i < wish.length && growth.expansionSlot(lv, zone)) {
                    // 研究の出城の増築(予定の場所に置けなければ足さない —— 研究の浄化圏は研究所の敷地だけ)
                    Lobe e = growth.researchExpansion(ls, mask, lv, terrain);
                    if (e != null) {
                        ls.add(e);
                        BasePlan.paint(mask, e);
                        area = count(mask);
                    }
                    continue;
                }
                if (growth != null && growth.outpostSlot(lv, i)) {
                    // 出城と通路を組で。1 つ目(レベル 2)を置けない地形なら、下のふつうの継ぎ足しで施設の浄化圏だけでも(離れないが施設は建つ)。
                    // 2 つ目(レベル 4)を置けないときは足さない(施設は離れた出城に置く、ユーザー指定)
                    Lobe[] pair = growth.outpost(rng, ls, mask, zone, lv, terrain);
                    if (pair != null) {
                        for (Lobe l : pair) {
                            ls.add(l);
                            BasePlan.paint(mask, l);
                        }
                        area = count(mask);
                        continue;
                    }
                    if (lv > 2) {
                        continue;
                    }
                }
                Lobe b;
                if (growth == null) {
                    b = place(rng, ls, mask, zone, lv, terrain);
                } else {
                    b = growth.place(rng, ls, mask, zone, lv, terrain, i < wish.length ? i : -1);
                    for (int k = 0; b == null && k < growth.keepLevels(); k++) {
                        b = place(rng, ls, mask, zone, lv, terrain, LobeGrowth.WATER_MARGIN, growth.keep(ls, k));
                    }
                }
                if (b == null) {
                    continue;
                }
                ls.add(b);
                BasePlan.paint(mask, b);
                area = count(mask);
            }
        }
        return ls;
    }

    /**
     * 種類ごとの、各レベルで足す区割り(仮)。浄化は {@link #WISH}。大型防衛は兵舎と弾薬を多め、
     * 補給は補給区を大通りへ、工廠は整備区(工廠)を多め、野戦病院は医療区を大通りへ、観測・研究はレベル 2・4 の 1 番目が出城。
     */
    private static Zone[] wish(BaseType type, int lv) {
        return switch (type) {
            case DEFENSE -> new Zone[][]{{}, {},
                    {Zone.LSA, Zone.AMMO, Zone.MAINTENANCE},
                    {Zone.SUPPORT, Zone.LSA, Zone.LOGISTICS},
                    {Zone.AMMO, Zone.LSA, Zone.MAINTENANCE},
                    {Zone.LSA, Zone.SUPPORT, Zone.LOGISTICS}}[lv];
            // 補給: レベル 2 の大通りの 2 つは補給区と居住区(大通りの後に居住区を回すと狭い所しか残らず兵舎が足りず、
            // 居住区を先にすると大通りの場所を取った)
            case SUPPLY -> new Zone[][]{{}, {},
                    {Zone.LOGISTICS, Zone.LSA, Zone.LOGISTICS},
                    {Zone.MAINTENANCE, Zone.LOGISTICS, Zone.LSA},
                    {Zone.LSA, Zone.AMMO, Zone.SUPPORT},
                    {Zone.LOGISTICS, Zone.LSA, Zone.MAINTENANCE}}[lv];
            // 工廠: 居住区は格子の内の輪(広い四角)に入るよう早めに(外の輪の四角は狭く、テントが少ししか建たない)
            case ARSENAL -> new Zone[][]{{}, {},
                    {Zone.LSA, Zone.MAINTENANCE, Zone.LOGISTICS},
                    {Zone.MAINTENANCE, Zone.LSA, Zone.AMMO},
                    {Zone.MAINTENANCE, Zone.LSA, Zone.SUPPORT},
                    {Zone.MAINTENANCE, Zone.LOGISTICS, Zone.LSA}}[lv];
            // 野戦病院(大通り沿い、2026-10-02 夜): 補給の大通りの補給区を医療区に(医療テント)。居住区は大通りのもう片側(兵舎のベッド)
            case HOSPITAL -> new Zone[][]{{}, {},
                    {Zone.MEDICAL, Zone.LSA, Zone.LOGISTICS},
                    {Zone.MAINTENANCE, Zone.MEDICAL, Zone.LSA},
                    {Zone.LSA, Zone.AMMO, Zone.SUPPORT},
                    {Zone.LOGISTICS, Zone.LSA, Zone.MEDICAL}}[lv];
            // 観測(出城、同日): レベル 2 と 4 の 1 番目が出城(観測施設)、ほかは本拠の継ぎ足し
            case OBSERVATION -> new Zone[][]{{}, {},
                    {type.special, Zone.LSA, Zone.LOGISTICS},
                    {Zone.LSA, Zone.SUPPORT, Zone.AMMO},
                    {type.special, Zone.LSA, Zone.MAINTENANCE},
                    {Zone.LOGISTICS, Zone.LSA, Zone.MAINTENANCE}}[lv];
            // 研究(出城、同日): 出城はレベル 2 の 1 つだけ(大きな研究所 1 棟。ユーザー指定「一棟だけ大きな施設」)
            // レベル 3〜5 の 1 番目は研究の出城の増築(研究所が広がる。同日その 3)
            case RESEARCH -> new Zone[][]{{}, {},
                    {type.special, Zone.LSA, Zone.LOGISTICS},
                    {type.special, Zone.LSA, Zone.SUPPORT, Zone.AMMO},
                    {type.special, Zone.LSA, Zone.MAINTENANCE, Zone.SUPPORT},
                    {type.special, Zone.LOGISTICS, Zone.LSA, Zone.MAINTENANCE}}[lv];
            default -> WISH[lv];
        };
    }

    /** 種類ごとの目安の面積(大型防衛は 1.25 倍。ユーザー指定「ほかの基地より大きく」、倍率は仮)。 */
    private static int area(BaseType type, int lv) {
        return type == BaseType.DEFENSE ? AREA[lv] * 5 / 4 : AREA[lv];
    }

    static int count(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /**
     * 区割り {@code zone} の浄化圏を 1 つ継ぐ場所を選ぶ。置ける場所が無ければ null。
     * 決まりどおりの場所が無いときだけ、地形の決まりを緩めてもう一度探す(水は八角形の形で避け、起伏は {@link #STEEP_RELAXED} まで)。
     * 1 回目で見つかる地形の計画は前と同じ。
     */
    private static Lobe place(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain) {
        return place(rng, ls, mask, zone, lv, terrain, WATER_MARGIN);
    }

    /** {@code waterMargin} = 水の標本から離す幅(浄化は {@link #WATER_MARGIN}、ほかの種類は標本の間隔ぶん広く {@link LobeGrowth#WATER_MARGIN})。 */
    static Lobe place(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, int waterMargin) {
        return place(rng, ls, mask, zone, lv, terrain, waterMargin, null);
    }

    /** {@code keep} = 候補をさらに絞る決まり(出城の基地: 通路・出城から離す。null なら絞らない = 前と同じ)。 */
    static Lobe place(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, int waterMargin, Predicate<Lobe> keep) {
        Lobe b = place(rng, ls, mask, zone, lv, terrain, false, waterMargin, keep);
        return b != null ? b : place(rng, ls, mask, zone, lv, terrain, true, waterMargin, keep);
    }

    private static Lobe place(Random rng, List<Lobe> ls, boolean[] mask, Zone zone, int lv, BaseTerrain terrain, boolean relaxed, int waterMargin,
                              Predicate<Lobe> keep) {
        List<Lobe> cands = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        // 支援区(小物だけ)と、出城を離して置けなかったときの施設の浄化圏(広場の脇に施設が 1 つ入れば足りる。台帳の地形で大きめが入らず施設が無くなった)は小さめも
        int[] sizes = zone == Zone.SUPPORT || BaseType.outpostZone(zone) ? SIZES : LARGE_SIZES;
        for (int pi = 0; pi < ls.size(); pi++) {
            Lobe p = ls.get(pi);
            for (int k = 0; k < DIRS; k++) {
                double th = 2 * Math.PI * k / DIRS;
                double ux = Math.cos(th);
                double uz = Math.sin(th);
                for (int r : sizes) {
                    int[][] shapes = zone == Zone.AMMO ? new int[][]{{r, r}} : new int[][]{{r, r}, {r + 6, r - 4}, {r - 4, r + 6}};
                    for (int[] s : shapes) {
                        for (double f : REACH) {
                            double reach = f * (extent(p.rx(), p.rz(), p.cut(), ux, uz)
                                    + extent(s[0], s[1], (int) Math.round(0.71 * (s[0] + s[1])), ux, uz));
                            int cx = p.cx() + (int) Math.round(ux * reach);
                            int cz = p.cz() + (int) Math.round(uz * reach);
                            Lobe b = new Lobe(zone, lv, cx, cz, s[0], s[1], pi);
                            int over = quickValid(b, ls, mask);
                            if (over < 0 || !terrainOk(b, terrain, relaxed, waterMargin) || keep != null && !keep.test(b)) {
                                continue;
                            }
                            cands.add(b);
                            scores.add(score(b, zone, ls, over, terrain, rng));
                        }
                    }
                }
            }
        }
        // 点の高い順に、中に空き地を閉じ込めないものを選ぶ
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
            if (!hasHole(next)) {
                return b;
            }
        }
        return null;
    }

    /** 八角形の、向き (ux, uz) への中心からの広がり。 */
    static double extent(int rx, int rz, int cut, double ux, double uz) {
        int a = cut - rz;
        int b = cut - rx;
        double[][] v = {{a, rz}, {rx, b}};
        double m = 0;
        for (double[] p : v) {
            m = Math.max(m, Math.abs(p[0] * ux) + Math.abs(p[1] * uz));
        }
        return m;
    }

    /**
     * 形だけの決まり(届く範囲・正門の前の通り・広場の空き・重なり具合・細い隙間)。満たせば重なる浄化圏の数(1 以上)、満たさなければ −1。
     */
    static int quickValid(Lobe b, List<Lobe> ls, boolean[] mask) {
        int reach = Math.max(Math.max(Math.abs(b.x0()), Math.abs(b.x1())), Math.max(Math.abs(b.z0()), Math.abs(b.z1())));
        if (reach > BasePlan.MAX_REACH) {
            return -1;
        }
        // 正門の前の通り
        for (int z = Math.max(CORE_R - 1, b.z0()); z <= b.z1(); z++) {
            for (int x = Math.max(-CORRIDOR, b.x0()); x <= Math.min(CORRIDOR, b.x1()); x++) {
                if (b.contains(x, z)) {
                    return -1;
                }
            }
        }
        // 新しい広場(と 1 マスの縁)は今の基地の外
        int h = PLAZA_HALF + 1;
        for (int z = b.cz() - h; z <= b.cz() + h; z++) {
            for (int x = b.cx() - h; x <= b.cx() + h; x++) {
                if (!BasePlan.onGrid(x, z) || mask[BasePlan.idx(x, z)]) {
                    return -1;
                }
            }
        }
        // 重ならない浄化圏とは十分に離す
        int overlapping = 0;
        for (Lobe c : ls) {
            int s = b.separation(c);
            if (s > 0) {
                overlapping++;
            } else if (-s < MIN_APART) {
                return -1;
            }
        }
        if (overlapping == 0) {
            return -1;
        }
        // 今の基地に太く重なり、十分に広がる(2 マスおきに数える)
        int over = 0;
        int gain = 0;
        for (int z = b.z0(); z <= b.z1(); z += 2) {
            for (int x = b.x0(); x <= b.x1(); x += 2) {
                if (!b.contains(x, z)) {
                    continue;
                }
                if (mask[BasePlan.idx(x, z)]) {
                    over++;
                } else {
                    gain++;
                }
            }
        }
        if (over * 4 < MIN_OVERLAP || gain < MIN_GAIN * (over + gain)) {
            return -1;
        }
        return overlapping;
    }

    /** 浄化圏 {@code b} が中心の浄化圏の正門の前の通り(|x| ≤ {@link #CORRIDOR}、z ≥ CORE_R − 1)にかかるか({@link #quickValid} と同じ決まり。出城の組が使う)。 */
    static boolean frontOfGate(Lobe b) {
        for (int z = Math.max(CORE_R - 1, b.z0()); z <= b.z1(); z++) {
            for (int x = Math.max(-CORRIDOR, b.x0()); x <= Math.min(CORRIDOR, b.x1()); x++) {
                if (b.contains(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 水の標本から離れていて、起伏が上限以下か。{@code relaxed} = 水は八角形の形で見て、起伏は {@link #STEEP_RELAXED} まで。 */
    static boolean terrainOk(Lobe b, BaseTerrain terrain, boolean relaxed) {
        return terrainOk(b, terrain, relaxed, WATER_MARGIN);
    }

    static boolean terrainOk(Lobe b, BaseTerrain terrain, boolean relaxed, int m) {
        if (relaxed ? terrain.anyWater(b, m) : terrain.anyWater(b.x0() - m, b.z0() - m, b.x1() + m, b.z1() + m)) {
            return false;
        }
        return terrain.relief(b.x0(), b.z0(), b.x1(), b.z1()) <= (relaxed ? STEEP_RELAXED : STEEP);
    }

    /** 区割りの好む向き(x = 東、z = 南。正門は南)。 */
    static double[] preferred(Zone zone) {
        return switch (zone) {
            case LSA -> new double[]{-0.6, -0.8};
            case SUPPORT -> new double[]{0.6, -0.8};
            case AMMO -> new double[]{0.8, -0.6};
            case LOGISTICS -> new double[]{-0.9, 0.4};
            case MAINTENANCE -> new double[]{0.9, 0.4};
            default -> new double[]{0, -1};
        };
    }

    private static double score(Lobe b, Zone zone, List<Lobe> ls, int overlapping, BaseTerrain terrain, Random rng) {
        double cx = b.cx();
        double cz = b.cz();
        double r = Math.max(1, Math.hypot(cx, cz));
        double[] p = preferred(zone);
        double s = 2.0 * (cx * p[0] + cz * p[1]) / r - (r / 50.0) * (r / 50.0);
        s -= terrain.relief(b.x0(), b.z0(), b.x1(), b.z1()) / 20.0;
        s += 0.6 * (overlapping - 1); // くぼみに収まる(まとまった形)
        double nearLsa = Double.MAX_VALUE;
        for (Lobe c : ls) {
            boolean adj = b.separation(c) > 0;
            if (zone == Zone.AMMO && c.zone() == Zone.LSA) {
                nearLsa = Math.min(nearLsa, Math.hypot(cx - c.cx(), cz - c.cz()));
                if (adj) {
                    s -= 2.0;
                }
            }
            if (zone == Zone.LSA && c.zone() == Zone.AMMO && adj) {
                s -= 2.0;
            }
        }
        if (zone == Zone.AMMO) {
            s += r / 40.0 + (nearLsa == Double.MAX_VALUE ? 1.0 : Math.min(1.5, nearLsa / 60.0));
        }
        return s + rng.nextDouble() * 0.9;
    }

    /** 浄化圏の和が、外とつながらない空き地を囲んでいるか。 */
    static boolean hasHole(boolean[] mask) {
        int n = BasePlan.SIZE;
        boolean[] seen = new boolean[mask.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(0);
        seen[0] = true;
        int outside = 0;
        while (!q.isEmpty()) {
            int i = q.poll();
            outside++;
            int x = i % n;
            int z = i / n;
            int[][] nb = {{x - 1, z}, {x + 1, z}, {x, z - 1}, {x, z + 1}};
            for (int[] c : nb) {
                if (c[0] < 0 || c[1] < 0 || c[0] >= n || c[1] >= n) {
                    continue;
                }
                int j = c[1] * n + c[0];
                if (!seen[j] && !mask[j]) {
                    seen[j] = true;
                    q.add(j);
                }
            }
        }
        int total = 0;
        for (boolean b : mask) {
            if (!b) {
                total++;
            }
        }
        return outside != total;
    }

    // ---------- 組み立ての途中の地図 ----------

    /** 組み立ての途中の地図(地面の塗り・残る道・建物の足元)。 */
    private static final class State {
        final long seed;
        /** 今組み立てているレベルの距離の地図。 */
        byte[] field;
        final byte[] surface = new byte[BasePlan.SIZE * BasePlan.SIZE];
        /** 残る道(つなぎの道の行き先になる): 道と広場。巡回路は入れない(上のレベルで消える)。 */
        final boolean[] road = new boolean[BasePlan.SIZE * BasePlan.SIZE];
        /** 建物の足元と、そのまわり 1 マス。 */
        final boolean[] occ = new boolean[BasePlan.SIZE * BasePlan.SIZE];
        /** 建物の足元。 */
        final boolean[] foot = new boolean[BasePlan.SIZE * BasePlan.SIZE];
        /** 今組み立てている浄化圏の広場と通り(つなぎの道の出発点)。 */
        final boolean[] mine = new boolean[BasePlan.SIZE * BasePlan.SIZE];
        /** 正門につながった道の網(つなぎの道の行き先。まだつながっていない広場は行き先にも通り道にもしない)。 */
        final boolean[] net = new boolean[BasePlan.SIZE * BasePlan.SIZE];
        final List<Plot> plots = new ArrayList<>();
        boolean helipad;
        /** 地形(研究所の増築で、連絡通路で上り下りできる高さの差かを見る)。 */
        BaseTerrain terrain;
        /** 今組み立てている浄化圏(建物の列はこの中だけ。はみ出すと、後から組み立てる隣の浄化圏の場所を取った)。 */
        Lobe lobe;

        State(long seed) {
            this.seed = seed;
        }

        void beginLobe() {
            java.util.Arrays.fill(mine, false);
        }

        /** この浄化圏の広場・通りのマスとして残る道に入れる。 */
        void mark(int x, int z) {
            int k = BasePlan.idx(x, z);
            road[k] = true;
            mine[k] = true;
        }

        int in(int x, int z) {
            return Math.max(0, BasePlan.fieldAt(field, x, z));
        }

        void paint(Surface s, int x, int z) {
            if (BasePlan.onGrid(x, z)) {
                int i = BasePlan.idx(x, z);
                if (s.ordinal() > surface[i]) {
                    surface[i] = (byte) s.ordinal();
                }
            }
        }

        /** 長方形を塗る(外までの距離 {@code min} 以上のマスだけ。0 なら外も)。道か広場なら残る道にも入れる。 */
        void rect(Surface s, int xa, int za, int xb, int zb, int min) {
            for (int z = Math.min(za, zb); z <= Math.max(za, zb); z++) {
                for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
                    if (!BasePlan.onGrid(x, z) || (min > 0 && in(x, z) < min) || foot[BasePlan.idx(x, z)]) {
                        continue;
                    }
                    paint(s, x, z);
                    if (s == Surface.ROAD || s == Surface.ROAD_LINE || s == Surface.PLAZA) {
                        road[BasePlan.idx(x, z)] = true;
                    }
                }
            }
        }

        boolean painted(int x, int z) {
            return BasePlan.onGrid(x, z) && surface[BasePlan.idx(x, z)] != 0;
        }

        /** 建物を置けるか: 足元が全部、巡回路と空きより内で、塗られておらず、ほかの建物とそのまわりにかからない。 */
        boolean free(Plot p) {
            for (int z = p.z0(); z <= p.z1(); z++) {
                for (int x = p.x0(); x <= p.x1(); x++) {
                    if (!BasePlan.onGrid(x, z) || in(x, z) < BUILD_DEPTH) {
                        return false;
                    }
                    int i = BasePlan.idx(x, z);
                    if (occ[i] || surface[i] != 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** 建物の足元が今の浄化圏の中に収まるか(八角形は凸なので四隅を見ればよい)。 */
        boolean within(Plot p) {
            return lobe == null || (lobe.contains(p.x0(), p.z0()) && lobe.contains(p.x1(), p.z0())
                    && lobe.contains(p.x0(), p.z1()) && lobe.contains(p.x1(), p.z1()));
        }

        void add(Plot p) {
            plots.add(p);
            for (int z = p.z0() - 1; z <= p.z1() + 1; z++) {
                for (int x = p.x0() - 1; x <= p.x1() + 1; x++) {
                    if (BasePlan.onGrid(x, z)) {
                        occ[BasePlan.idx(x, z)] = true;
                        if (p.contains(x, z)) {
                            foot[BasePlan.idx(x, z)] = true;
                        }
                    }
                }
            }
        }
    }

    // ---------- 中心(レベル 1) ----------

    /**
     * 中心の浄化圏: 正門からの大通りの先に閲兵場(真ん中に大除去塔、両脇に芝、管理棟の前に旗竿 2 本)、その奥に管理棟。
     * 閲兵場から東西へ通り、管理棟の裏に東西の裏通り(管理棟の両脇の路地で閲兵場とつなぐ)。
     * 裏通りの北 = 兵舎の列、東西の通りの北の両脇 = 支援(東の頭に弾薬庫 1 棟)、南西 = 補給、南東 = 整備。
     */
    private static void core(State st) {
        st.lobe = new Lobe(Zone.CORE, 1, 0, 0, CORE_R, CORE_R, -1);
        st.rect(Surface.PLAZA, -12, 1, 12, 12, 1);
        st.rect(Surface.LAWN, -11, 4, -6, 11, 1);
        st.rect(Surface.LAWN, 6, 4, 11, 11, 1);
        st.add(plot(BuildingKind.PURIFIER_TOWER, Zone.CORE, 1, -3, 4, Facing.SOUTH, st.seed, 0, 0));
        st.add(plot(BuildingKind.FLAGPOLE, Zone.CORE, 1, -10, 1, Facing.SOUTH, st.seed, 0, 0));
        st.add(plot(BuildingKind.FLAGPOLE, Zone.CORE, 1, 8, 1, Facing.SOUTH, st.seed, 0, 0));
        st.add(plot(BuildingKind.HQ, Zone.CORE, 1, -7, -11, Facing.SOUTH, st.seed, 0, 0));
        st.rect(Surface.ROAD, -11, -12, -9, 0, 1);
        st.rect(Surface.ROAD, 9, -12, 11, 0, 1);
        st.rect(Surface.ROAD, -CORE_R, -16, CORE_R, -12, BUILD_DEPTH - 1);
        // 大通り(閲兵場 → 正門の手前。外は gate で)と中央線
        st.rect(Surface.ROAD, -3, 13, 3, CORE_R - 3, 1);
        for (int z = 14; z <= CORE_R - 5; z++) {
            if (((z >> 1) & 1) == 0) {
                st.rect(Surface.ROAD_LINE, 0, z, 0, z, 1);
            }
        }
        // 東西の通り(閲兵場から巡回路の手前まで)
        st.rect(Surface.ROAD, 13, 4, CORE_R, 8, BUILD_DEPTH - 1);
        st.rect(Surface.ROAD, -CORE_R, 4, -13, 8, BUILD_DEPTH - 1);
        Random rng = new Random(st.seed * 31L + 17L);
        rows(st, rng, Zone.LSA, 1, true, -17, -1, -CORE_R, CORE_R, false, false);
        rows(st, rng, Zone.SUPPORT, 1, true, 3, -1, -CORE_R, -13, false, false);
        rows(st, rng, Zone.SUPPORT, 1, true, 3, -1, 13, CORE_R, false, true);
        rows(st, rng, Zone.LOGISTICS, 1, true, 9, 1, -CORE_R, -5, false, false);
        rows(st, rng, Zone.MAINTENANCE, 1, true, 9, 1, 5, CORE_R, false, false);
        fill(st, rng, Zone.SUPPORT, 1, st.lobe);
    }

    /**
     * 列で埋まらなかった空き地に小物(コンテナ・発電機・退避壕・仮設トイレ・照明塔・給水塔)を置く。まわり 1 マスも空いている所だけ
     * (道や建物にくっつけない)。浄化圏 {@code l} の中だけ。居住区は、テントが入る所ではテントを先に。
     */
    private static void fill(State st, Random rng, Zone zone, int band, Lobe l) {
        BuildingKind[] pool = {BuildingKind.CONTAINER, BuildingKind.CONTAINER, BuildingKind.CONTAINER, BuildingKind.GENERATOR,
                BuildingKind.SHELTER, BuildingKind.LATRINE, BuildingKind.WATER_TANK, BuildingKind.LIGHT_TOWER};
        for (int z = l.z0(); z <= l.z1(); z++) {
            for (int x = l.x0(); x <= l.x1(); x++) {
                if (!l.contains(x, z) || st.in(x, z) < BUILD_DEPTH + 1 || st.occ[BasePlan.idx(x, z)] || st.painted(x, z)) {
                    continue;
                }
                if (zone == Zone.LSA && tentAt(st, band, l, x, z)) {
                    continue;
                }
                BuildingKind kind = pool[rng.nextInt(pool.length)];
                Facing facing = z < l.cz() ? Facing.SOUTH : Facing.NORTH;
                Plot p = plot(kind, zone, band, x, z, facing, st.seed, 0, 0);
                Plot around = new Plot(kind, zone, band, p.x0() - 1, p.z0() - 1, p.x1() + 1, p.z1() + 1, facing, 0);
                if (l.contains(p.x1(), p.z1()) && st.free(around) && (kind != BuildingKind.LIGHT_TOWER || lightFar(st, p))) {
                    st.add(p);
                }
            }
        }
    }

    /**
     * 居住区の空き地に、(x, z) を北西の角にしてテントを 1 張り(入口は浄化圏の通りの側、まわり 1 マスも空き)。置けたら真。
     * レベル 4〜5 の浄化圏は大型テント、入らなければ小型。
     */
    private static boolean tentAt(State st, int band, Lobe l, int x, int z) {
        BuildingKind[] kinds = band <= 3 ? new BuildingKind[]{BuildingKind.BARRACKS_TENT}
                : new BuildingKind[]{BuildingKind.BARRACKS_TENT_LARGE, BuildingKind.BARRACKS_TENT};
        Facing facing = l.alongX() ? (z < l.cz() ? Facing.SOUTH : Facing.NORTH) : (x < l.cx() ? Facing.EAST : Facing.WEST);
        for (BuildingKind kind : kinds) {
            Plot p = plot(kind, Zone.LSA, band, x, z, facing, st.seed, 0, 0);
            Plot around = new Plot(kind, Zone.LSA, band, p.x0() - 1, p.z0() - 1, p.x1() + 1, p.z1() + 1, facing, 0);
            if (st.within(p) && st.free(around)) {
                st.add(p);
                return true;
            }
        }
        return false;
    }

    /** 照明塔 {@code p} のまわり {@link #LIGHT_SPACING} マスに、ほかの照明塔が無いか。 */
    private static boolean lightFar(State st, Plot p) {
        for (Plot q : st.plots) {
            if (q.kind() == BuildingKind.LIGHT_TOWER
                    && Math.max(Math.abs(q.x0() - p.x0()), Math.abs(q.z0() - p.z0())) < LIGHT_SPACING) {
                return false;
            }
        }
        return true;
    }

    // ---------- 継ぎ足した浄化圏の中 ----------

    /**
     * 浄化圏の中: 真ん中に除去塔の広場、長い向きに通り 1 本(広場から両側へ、巡回路の手前・古い建物・ほかの道まで)、
     * ほかの道へのつなぎの道、通りの両側に建物の列。
     */
    private static void lobeContent(State st, int index, Lobe l) {
        Random rng = new Random(st.seed * 31L + index * 1_000_003L);
        st.beginLobe();
        st.lobe = l;
        int h = PLAZA_HALF;
        for (int z = l.cz() - h; z <= l.cz() + h; z++) {
            for (int x = l.cx() - h; x <= l.cx() + h; x++) {
                if (BasePlan.onGrid(x, z) && st.surface[BasePlan.idx(x, z)] == Surface.PLAZA.ordinal()) {
                    st.mark(x, z);
                }
            }
        }
        boolean ax = l.alongX();
        if (ax) {
            street(st, l.cx() + h + 1, l.cz(), 1, 0);
            street(st, l.cx() - h - 1, l.cz(), -1, 0);
        } else {
            street(st, l.cx(), l.cz() + h + 1, 0, 1);
            street(st, l.cx(), l.cz() - h - 1, 0, -1);
        }
        connect(st);
        // ヘリパッド: 支援区(レベル 3〜)か、野戦病院の医療区(負傷者を運ぶヘリ。2026-10-02 夜、仮)の最初の 1 つ
        boolean pad = !st.helipad && (l.zone() == Zone.SUPPORT && l.level() >= 3 || l.zone() == Zone.MEDICAL);
        st.helipad |= pad;
        int c = ax ? l.cz() : l.cx();
        int u0 = ax ? l.x0() : l.z0();
        int u1 = ax ? l.x1() : l.z1();
        rows(st, rng, l.zone(), l.level(), ax, c - STREET / 2 - 1, -1, u0, u1, pad, false);
        rows(st, rng, l.zone(), l.level(), ax, c + STREET / 2 + 1, 1, u0, u1, false, false);
        if (l.zone() != Zone.AMMO) {
            fill(st, rng, l.zone(), l.level(), l);
        }
    }

    /** 浄化圏の真ん中の除去塔の広場(13 × 13)と中継の除去塔。広場は新しい浄化圏の中(古い浄化圏の外)にある。 */
    private static void plaza(State st, Lobe l) {
        int h = PLAZA_HALF;
        for (int z = l.cz() - h; z <= l.cz() + h; z++) {
            for (int x = l.cx() - h; x <= l.cx() + h; x++) {
                if (st.in(x, z) >= PATROL_IN && !st.occ[BasePlan.idx(x, z)]) {
                    st.paint(Surface.PLAZA, x, z);
                    st.road[BasePlan.idx(x, z)] = true;
                }
            }
        }
        st.add(plot(BuildingKind.PURIFIER_MAST, l.zone(), l.level(), l.cx() - 2, l.cz() - 2, Facing.SOUTH, st.seed, 0, 0));
    }

    // ---------- 出城の中 ----------

    /**
     * 出城の中(2026-10-02 夜): 真ん中の除去塔の広場、その先(本拠と反対の側)に特別な施設(観測施設・野外研究施設。入口は広場へ)、
     * 通路の真ん中を本拠まで通す道(幅 5)、空き地に小物。通路の無い出城(地形で離して置けず、ふつうに継ぎ足した)は道を {@link #connect} だけで。
     */
    private static void outpostContent(State st, int index, Lobe o, List<Lobe> all) {
        Random rng = new Random(st.seed * 31L + index * 1_000_003L);
        st.beginLobe();
        st.lobe = o;
        if (o.zone() == Zone.RESEARCH && o.parent() >= 0 && all.get(o.parent()).zone() == Zone.RESEARCH) {
            researchAnnexContent(st, o, all);
            return;
        }
        if (o.zone() == Zone.RESEARCH && researchContent(st, o, all)) {
            return;
        }
        if (o.zone() == Zone.RESEARCH) {
            plaza(st, o); // 研究所を真ん中に置けなかった(ふつうに継ぎ足した狭い浄化圏): ほかの出城と同じ組み立てに戻る
        }
        int h = PLAZA_HALF;
        for (int z = o.cz() - h; z <= o.cz() + h; z++) {
            for (int x = o.cx() - h; x <= o.cx() + h; x++) {
                if (BasePlan.onGrid(x, z) && st.surface[BasePlan.idx(x, z)] == Surface.PLAZA.ordinal()) {
                    st.mark(x, z);
                }
            }
        }
        Lobe c = o.parent() > 0 && all.get(o.parent()).zone() == Zone.CORRIDOR ? all.get(o.parent()) : null;
        Facing back = c != null ? toward(o, c.cx(), c.cz()) : toward(o, 0, 0);
        facility(st, o, back);
        if (c != null) {
            corridorRoad(st, o, c, back, PLAZA_HALF + 1);
        }
        connect(st);
        fill(st, rng, o.zone(), o.level(), o);
    }

    /**
     * 研究の出城(2026-10-02 夜、ユーザー指定「即席のようなものではなくガッツリそこに構えて研究している感じ」「何棟も置くものではなく一棟だけ大きな施設」):
     * 真ん中に大きな研究所(入口は本拠の側)、入口の前に前庭と、その脇に除去塔、通路の道は前庭まで、まわりは芝(小物は置かない —— 1 棟だけが目立つように。仮)。
     * 研究所が真ん中に入らなければ(ふつうに継ぎ足した狭い浄化圏)、本拠と反対の側へずらして試し、それでも入らなければ偽(呼ぶ側がほかの出城の組み立てに戻る)。
     */
    private static boolean researchContent(State st, Lobe o, List<Lobe> all) {
        Lobe c = o.parent() > 0 && all.get(o.parent()).zone() == Zone.CORRIDOR ? all.get(o.parent()) : null;
        Facing back = c != null ? toward(o, c.cx(), c.cz()) : toward(o, 0, 0);
        BuildingKind kind = BuildingKind.FIELD_LAB;
        int ax = back.dx; // 本拠の側への向き(a = 真ん中からの距離)と、それに直角の向き(l)
        int az = back.dz;
        int lx = -back.dz;
        int lz = back.dx;
        int sx = back.dx == 0 ? kind.width : kind.depth;
        int sz = back.dx == 0 ? kind.depth : kind.width;
        int halfDepth = kind.depth / 2;
        int halfWidth = kind.width / 2;
        Plot lab = null;
        int shift = 0;
        for (int s = 0; s <= 8 && lab == null; s += 2) {
            int cx = o.cx() - ax * s;
            int cz = o.cz() - az * s;
            Plot p = plot(kind, o.zone(), o.level(), cx - sx / 2, cz - sz / 2, back, st.seed, 0, 0);
            if (st.free(p) && st.within(p)) {
                lab = p;
                shift = s;
            }
        }
        if (lab == null) {
            return false;
        }
        st.add(lab);
        int front = halfDepth + 1 - shift; // 研究所の正面の前の列(真ん中から本拠の側へ)
        // 除去塔: 前庭の脇(入口の前の道を空ける)
        int ma = front + 2;
        int ml = 5;
        int mx = o.cx() + ax * ma + lx * ml;
        int mz = o.cz() + az * ma + lz * ml;
        int mx2 = o.cx() + ax * (ma + 4) + lx * (ml + 4);
        int mz2 = o.cz() + az * (ma + 4) + lz * (ml + 4);
        Plot mast = plot(BuildingKind.PURIFIER_MAST, o.zone(), o.level(), Math.min(mx, mx2), Math.min(mz, mz2), Facing.SOUTH, st.seed, 0, 0);
        boolean mastOk = true;
        for (int z = mast.z0(); z <= mast.z1(); z++) {
            for (int x = mast.x0(); x <= mast.x1(); x++) {
                mastOk &= o.contains(x, z) && st.in(x, z) >= BUILD_DEPTH && !st.occ[BasePlan.idx(x, z)];
            }
        }
        if (mastOk) {
            st.add(mast);
        }
        // 前庭: 研究所の正面から本拠の側へ、研究所の幅で、外まで 7 以上のマス(内側の柵の列は残す)
        for (int a = front; a <= front + 12; a++) {
            for (int l = -halfWidth; l <= halfWidth; l++) {
                int x = o.cx() + ax * a + lx * l;
                int z = o.cz() + az * a + lz * l;
                if (BasePlan.onGrid(x, z) && o.contains(x, z) && st.in(x, z) >= BUILD_DEPTH && !st.foot[BasePlan.idx(x, z)]) {
                    st.paint(Surface.PLAZA, x, z);
                    st.mark(x, z);
                }
            }
        }
        if (!mastOk) {
            plazaMastNear(st, o, front, ax, az, lx, lz); // 前庭の脇に入らなければ、前庭の反対の脇か真ん中の前へ
        }
        if (c != null) {
            corridorRoad(st, o, c, back, front + 1); // 正面の前の列は研究所のまわり 1 マス(ほかの建物の空き)に入るので、その外から
        }
        connect(st);
        // まわりの芝(建物の際の 1 マスは砂利のまま)
        for (int z = o.z0(); z <= o.z1(); z++) {
            for (int x = o.x0(); x <= o.x1(); x++) {
                if (o.contains(x, z) && st.in(x, z) >= BUILD_DEPTH && !st.occ[BasePlan.idx(x, z)] && !st.painted(x, z)) {
                    st.paint(Surface.LAWN, x, z);
                }
            }
        }
        return true;
    }

    /**
     * 研究の出城の増築(2026-10-02 夜その 3、ユーザー指定「出城が拡張される際、研究施設も一緒に拡張される。縦に伸ばすのではなく、拡張された場所も使った
     * 巨大な施設群」): 浄化圏の真ん中に増築の棟(レベル 4 は大型試験棟、ほかは第 2 研究棟。正面は本館と同じ向き、真ん中の線は本館とそろう)、
     * つなぐ先の棟(本館か内側の増築)とのあいだに連絡通路、棟の正面の前に前庭と外側の脇に除去塔、まわりは芝。
     */
    private static void researchAnnexContent(State st, Lobe e, List<Lobe> all) {
        Lobe p = all.get(e.parent());
        Lobe root = p;
        while (root.parent() >= 0 && all.get(root.parent()).zone() == Zone.RESEARCH) {
            root = all.get(root.parent());
        }
        Lobe c = root.parent() >= 0 && all.get(root.parent()).zone() == Zone.CORRIDOR ? all.get(root.parent()) : null;
        Facing back = c != null ? toward(root, c.cx(), c.cz()) : toward(root, 0, 0);
        Facing out = toward(p, e.cx(), e.cz());
        BuildingKind kind = e.level() == 4 ? BuildingKind.TEST_HALL : BuildingKind.ANNEX_LAB;
        int sx = back.dx == 0 ? kind.width : kind.depth;
        int sz = back.dx == 0 ? kind.depth : kind.width;
        // 棟は浄化圏の真ん中に。つなぐ先の棟との高さの差が大きく、連絡通路で上り下りできなければ、外へずらして通路を長くする(3 マスまで。
        // LobeGrowth#annexShift と同じ決まりで、予定を立てたときにずらせば届くと分かっている)。どこでも通路が置けなければ真ん中に棟だけ
        Plot annex = null;
        Plot walk = null;
        int shift = 0;
        for (int s = 0; s <= LobeGrowth.ANNEX_SHIFT_MAX && walk == null; s++) {
            Plot q = plot(kind, e.zone(), e.level(), e.cx() + out.dx * s - sx / 2, e.cz() + out.dz * s - sz / 2, back, st.seed, 0, 0);
            if (!(st.free(q) && st.within(q))) {
                continue;
            }
            Plot w = walkwayTo(st, q, out);
            if (w != null || annex == null) {
                annex = q;
                walk = w;
                shift = s;
            }
        }
        if (annex == null) {
            return;
        }
        st.add(annex);
        if (walk != null) {
            st.add(walk);
        }
        int acx = e.cx() + out.dx * shift; // 棟の真ん中
        int acz = e.cz() + out.dz * shift;
        // 前庭(正面の前、棟の幅)と、外側の脇の除去塔
        int ax = back.dx;
        int az = back.dz;
        int lx = -back.dz;
        int lz = back.dx;
        int side = lx * out.dx + lz * out.dz >= 0 ? 1 : -1;
        int front = kind.depth / 2 + 1;
        int halfWidth = kind.width / 2;
        boolean mast = false;
        for (int[] sp : new int[][]{{front + 2, 5 * side}, {front + 2, -9 * side}, {-front - 6, 5 * side}}) {
            int x1 = acx + ax * sp[0] + lx * sp[1];
            int z1 = acz + az * sp[0] + lz * sp[1];
            int x2 = acx + ax * (sp[0] + 4) + lx * (sp[1] + 4);
            int z2 = acz + az * (sp[0] + 4) + lz * (sp[1] + 4);
            Plot m = plot(BuildingKind.PURIFIER_MAST, e.zone(), e.level(), Math.min(x1, x2), Math.min(z1, z2), Facing.SOUTH, st.seed, 0, 0);
            if (st.free(m) && st.within(m)) {
                st.add(m);
                mast = true;
                break;
            }
        }
        for (int a = front; a <= front + 8; a++) {
            for (int l = -halfWidth; l <= halfWidth; l++) {
                int x = acx + ax * a + lx * l;
                int z = acz + az * a + lz * l;
                if (BasePlan.onGrid(x, z) && e.contains(x, z) && st.in(x, z) >= BUILD_DEPTH && !st.foot[BasePlan.idx(x, z)]) {
                    st.paint(Surface.PLAZA, x, z);
                }
            }
        }
        if (!mast) {
            plazaMastNear(st, e, front, ax, az, lx, lz);
        }
        for (int z = e.z0(); z <= e.z1(); z++) {
            for (int x = e.x0(); x <= e.x1(); x++) {
                if (e.contains(x, z) && st.in(x, z) >= BUILD_DEPTH && !st.occ[BasePlan.idx(x, z)] && !st.painted(x, z)) {
                    st.paint(Surface.LAWN, x, z);
                }
            }
        }
    }

    /**
     * 増築の棟 {@code annex} から内側({@code out} の逆)へ、真ん中の線の上をたどって最初に当たった研究所の棟までの、幅 5 の連絡通路(置けなければ null。
     * 置くのは呼ぶ側)。置けるのは、通路のマスが全部ほかの建物の足元でなく外まで 7 以上で、道・巡回路・前庭の上でなく、両端の棟の土台の差が
     * 通路の長さ − 1 以下(1 マスに 1 段まで上り下りする)のときだけ。通路の向き = 内側の棟から増築の棟へ。
     */
    private static Plot walkwayTo(State st, Plot annex, Facing out) {
        Facing in = out.opposite();
        int cx = (annex.x0() + annex.x1()) / 2;
        int cz = (annex.z0() + annex.z1()) / 2;
        int x = in.dx != 0 ? (in.dx < 0 ? annex.x0() - 1 : annex.x1() + 1) : cx;
        int z = in.dz != 0 ? (in.dz < 0 ? annex.z0() - 1 : annex.z1() + 1) : cz;
        Plot inner = null;
        int len = 0;
        for (int i = 0; i < 48 && inner == null; i++) {
            int px = x + in.dx * i;
            int pz = z + in.dz * i;
            for (Plot q : st.plots) {
                if (q.contains(px, pz)) {
                    inner = q;
                    len = i;
                    break;
                }
            }
        }
        if (inner == null || !BaseTerrain.researchBuilding(inner.kind()) || len < 3) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int i = 0; i < len; i++) {
            for (int j = -2; j <= 2; j++) {
                int px = x + in.dx * i + (in.dx == 0 ? j : 0);
                int pz = z + in.dz * i + (in.dz == 0 ? j : 0);
                if (!BasePlan.onGrid(px, pz) || st.in(px, pz) < BUILD_DEPTH || st.foot[BasePlan.idx(px, pz)]) {
                    return null;
                }
                int s = st.surface[BasePlan.idx(px, pz)];
                if (s == Surface.ROAD.ordinal() || s == Surface.ROAD_LINE.ordinal() || s == Surface.PATH.ordinal()
                        || s == Surface.APRON.ordinal() || s == Surface.PLAZA.ordinal()) {
                    return null;
                }
                minX = Math.min(minX, px);
                minZ = Math.min(minZ, pz);
            }
        }
        if (Math.abs(st.terrain.pad(inner) - st.terrain.pad(annex)) > len - 1) {
            return null;
        }
        return plot(BuildingKind.RESEARCH_WALKWAY, annex.zone(), annex.band(), minX, minZ, out, st.seed, 5, len);
    }

    /** 除去塔を前庭の脇に置けなかったときの置き場所: 前庭の反対の脇、それも駄目なら入口の正面の奥(道の先)。前庭の上でも置いてよい。 */
    private static void plazaMastNear(State st, Lobe o, int front, int ax, int az, int lx, int lz) {
        int[][] spots = {{front + 2, -9}, {front + 7, -2}, {front + 2, 5}};
        for (int[] sp : spots) {
            int x1 = o.cx() + ax * sp[0] + lx * sp[1];
            int z1 = o.cz() + az * sp[0] + lz * sp[1];
            int x2 = o.cx() + ax * (sp[0] + 4) + lx * (sp[1] + 4);
            int z2 = o.cz() + az * (sp[0] + 4) + lz * (sp[1] + 4);
            Plot mast = plot(BuildingKind.PURIFIER_MAST, o.zone(), o.level(), Math.min(x1, x2), Math.min(z1, z2), Facing.SOUTH, st.seed, 0, 0);
            boolean ok = true;
            for (int z = mast.z0(); z <= mast.z1() && ok; z++) {
                for (int x = mast.x0(); x <= mast.x1(); x++) {
                    if (!o.contains(x, z) || st.in(x, z) < BUILD_DEPTH || st.occ[BasePlan.idx(x, z)]) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                st.add(mast);
                return;
            }
        }
    }

    /** 出城 {@code o} の真ん中から見て (x, z) のある向き(東西南北のうち近いもの)。 */
    private static Facing toward(Lobe o, int x, int z) {
        int dx = x - o.cx();
        int dz = z - o.cz();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Facing.EAST : Facing.WEST;
        }
        return dz >= 0 ? Facing.SOUTH : Facing.NORTH;
    }

    /**
     * 特別な施設を、広場の先(本拠と反対の側)に、入口を広場へ向けて置く。入らなければ広場の左右、それでも入らなければ浄化圏の中の空いている所。
     * 本拠の側(通路の道が来る側)には置かない。
     */
    private static void facility(State st, Lobe o, Facing back) {
        BuildingKind kind = BaseType.facility(o.zone());
        if (kind == null) {
            return;
        }
        int h = PLAZA_HALF;
        int w = kind.width;
        int d = kind.depth;
        for (Facing f : new Facing[]{back.opposite(), back.rotateCw(1), back.rotateCw(3)}) {
            int x0 = switch (f) {
                case WEST -> o.cx() - h - d;
                case EAST -> o.cx() + h + 1;
                default -> o.cx() - w / 2;
            };
            int z0 = switch (f) {
                case NORTH -> o.cz() - h - d;
                case SOUTH -> o.cz() + h + 1;
                default -> o.cz() - w / 2;
            };
            Plot p = plot(kind, o.zone(), o.level(), x0, z0, f.opposite(), st.seed, 0, 0);
            if (st.free(p) && st.within(p)) {
                st.add(p);
                return;
            }
        }
        for (int z = o.z0(); z <= o.z1(); z++) {
            for (int x = o.x0(); x <= o.x1(); x++) {
                Facing facing = toward(o, x, z).opposite(); // 広場の側へ入口
                Plot p = plot(kind, o.zone(), o.level(), x, z, facing, st.seed, 0, 0);
                if (st.free(p) && st.within(p)) {
                    st.add(p);
                    return;
                }
            }
        }
    }

    /**
     * 通路の真ん中に幅 5 の道を、出城の広場の本拠の側の縁から本拠へ向けて引く。通路の外に出た所は必ず塗る(真ん中の列は外まで 6 = 内側の柵の列なので、
     * 砂利のままだと道の真ん中に柵が立つ)。本拠の中では、ほかの道に着いたら止め、建物に当たるか通路の端まで来たら止める(残りは {@link #connect})。
     * {@code from} = 出城の真ん中から引き始める距離(広場の縁、研究の出城は研究所の正面の前)。
     */
    private static void corridorRoad(State st, Lobe o, Lobe c, Facing back, int from) {
        boolean alongX = back.dx != 0;
        int u = alongX ? back.dx : back.dz;
        int line = alongX ? c.cz() : c.cx();
        int start = (alongX ? o.cx() : o.cz()) + u * from;
        int end = alongX ? (u > 0 ? c.x1() : c.x0()) : (u > 0 ? c.z1() : c.z0());
        for (int a = start; (end - a) * u >= 0; a += u) {
            boolean joined = false;
            for (int j = -STREET / 2; j <= STREET / 2; j++) {
                int x = alongX ? a : line + j;
                int z = alongX ? line + j : a;
                if (!BasePlan.onGrid(x, z) || st.in(x, z) < PATROL_IN || st.occ[BasePlan.idx(x, z)]) {
                    return;
                }
                int k = BasePlan.idx(x, z);
                joined |= st.road[k] && !st.mine[k];
            }
            for (int j = -STREET / 2; j <= STREET / 2; j++) {
                int x = alongX ? a : line + j;
                int z = alongX ? line + j : a;
                if (st.surface[BasePlan.idx(x, z)] <= Surface.PATH.ordinal()) {
                    st.paint(Surface.ROAD, x, z);
                }
                st.mark(x, z);
            }
            if (joined) {
                return;
            }
        }
    }

    /**
     * 幅 {@link #STREET} の通りを (x, z) から (dx, dz) の向きへ引く。巡回路の手前(外まで {@link #BUILD_DEPTH} − 1 未満)・
     * 建物とそのまわりに当たったら手前で止まり、ほかの道に当たったらそこでつないで止まる。
     */
    private static void street(State st, int x, int z, int dx, int dz) {
        int half = STREET / 2;
        for (int i = 0; i < 2 * BasePlan.GRID; i++) {
            int cx = x + dx * i;
            int cz = z + dz * i;
            boolean joined = false;
            for (int j = -half; j <= half; j++) {
                int px = dx != 0 ? cx : cx + j;
                int pz = dx != 0 ? cz + j : cz;
                if (!BasePlan.onGrid(px, pz) || st.in(px, pz) < BUILD_DEPTH - 1 || st.occ[BasePlan.idx(px, pz)]) {
                    return;
                }
                int k = BasePlan.idx(px, pz);
                joined |= st.road[k] && !st.mine[k];
            }
            for (int j = -half; j <= half; j++) {
                int px = dx != 0 ? cx : cx + j;
                int pz = dx != 0 ? cz + j : cz;
                st.paint(Surface.ROAD, px, pz);
                st.mark(px, pz);
            }
            if (joined) {
                return;
            }
        }
    }

    /**
     * この浄化圏の広場と通りから、ほかの残る道(道・広場)までのつなぎの道を引く。建物とそのまわりは通らない。
     * 曲がるたびに手間を足し(曲がりの少ない道)、外周の近く(巡回路と空き)は手間を増やす(壁沿いに道を作らない)。
     */
    private static void connect(State st) {
        int n = BasePlan.SIZE;
        int[] cost = new int[n * n * 4];
        int[] from = new int[n * n * 4];
        java.util.Arrays.fill(cost, Integer.MAX_VALUE);
        final int turn = 8;
        final int near = 3;
        final int maxCost = 6 * n;
        List<ArrayDeque<Integer>> buckets = new ArrayList<>();
        for (int c = 0; c <= maxCost + turn + near + 1; c++) {
            buckets.add(new ArrayDeque<>());
        }
        int[][] dir = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean any = false;
        for (int k = 0; k < n * n; k++) {
            if (st.mine[k]) {
                any = true;
                for (int d = 0; d < 4; d++) {
                    cost[k * 4 + d] = 0;
                    from[k * 4 + d] = -1;
                    buckets.get(0).add(k * 4 + d);
                }
            }
        }
        if (!any) {
            return;
        }
        int hit = -1;
        search:
        for (int c = 0; c <= maxCost; c++) {
            ArrayDeque<Integer> q = buckets.get(c);
            while (!q.isEmpty()) {
                int s = q.poll();
                if (cost[s] != c) {
                    continue;
                }
                int k = s / 4;
                int d0 = s % 4;
                int x = k % n;
                int z = k / n;
                for (int d = 0; d < 4; d++) {
                    int nx = x + dir[d][0];
                    int nz = z + dir[d][1];
                    if (nx < 0 || nz < 0 || nx >= n || nz >= n) {
                        continue;
                    }
                    int nk = nz * n + nx;
                    int depth = st.in(nx - BasePlan.GRID, nz - BasePlan.GRID);
                    if (depth < PATROL_IN || st.mine[nk]) {
                        continue;
                    }
                    if (st.net[nk]) {
                        from[nk * 4 + d] = s;
                        hit = nk * 4 + d;
                        break search;
                    }
                    if (st.occ[nk] || st.road[nk]) {
                        continue;
                    }
                    int nc = c + 1 + (from[s] != -1 && d != d0 ? turn : 0) + (depth < BUILD_DEPTH ? near : 0);
                    int ns = nk * 4 + d;
                    if (nc < cost[ns] && nc <= maxCost) {
                        cost[ns] = nc;
                        from[ns] = s;
                        buckets.get(nc).add(ns);
                    }
                }
            }
        }
        if (hit < 0) {
            return;
        }
        for (int k = 0; k < n * n; k++) {
            st.net[k] |= st.mine[k];
        }
        int half = STREET / 2;
        List<int[]> painted = new ArrayList<>(); // {マス, 前の塗り}
        List<Integer> line = new ArrayList<>();
        for (int s = from[hit]; s >= 0 && !st.mine[s / 4]; s = from[s]) {
            int k = s / 4;
            line.add(k);
            int x = k % n - BasePlan.GRID;
            int z = k / n - BasePlan.GRID;
            for (int dz = -half; dz <= half; dz++) {
                for (int dx = -half; dx <= half; dx++) {
                    int px = x + dx;
                    int pz = z + dz;
                    if (BasePlan.onGrid(px, pz) && st.in(px, pz) >= PATROL_IN && !st.occ[BasePlan.idx(px, pz)]
                            && st.surface[BasePlan.idx(px, pz)] <= Surface.PATH.ordinal()) {
                        painted.add(new int[]{BasePlan.idx(px, pz), st.surface[BasePlan.idx(px, pz)]});
                        st.paint(Surface.ROAD, px, pz);
                        st.road[BasePlan.idx(px, pz)] = true;
                        st.net[BasePlan.idx(px, pz)] = true;
                    }
                }
            }
        }
        dropStubs(st, line, painted);
    }

    /**
     * つなぎの道を幅 5 の筆で塗ったあと、道の筋からも、ほかの道・広場からも縦横につながらない切れ端を塗る前に戻す。建物のまわり 1 マスを避けて塗るので、
     * 筆の角が斜めにだけ触れた 2 マスが道の網から離れて残った(2026-10-02 夜、研究基地の起伏の地形・種 1)。つながっている所は変えない
     * (切れ端の無い計画は前と 1 マスも同じ)。
     */
    private static void dropStubs(State st, List<Integer> line, List<int[]> painted) {
        if (painted.isEmpty()) {
            return;
        }
        int n = BasePlan.SIZE;
        java.util.Set<Integer> fresh = new java.util.HashSet<>();
        for (int[] p : painted) {
            fresh.add(p[0]);
        }
        // 出発点: 道の筋のマスと、前から通れる地面(道・広場・前庭・駐車場・芝)に縦横で触れている塗ったマス
        java.util.Set<Integer> seen = new java.util.HashSet<>(line);
        ArrayDeque<Integer> q = new ArrayDeque<>(line);
        for (int k : fresh) {
            int x = k % n;
            int z = k / n;
            int[][] nb = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
            for (int[] c : nb) {
                if (c[0] < 0 || c[1] < 0 || c[0] >= n || c[1] >= n) {
                    continue;
                }
                int j = c[1] * n + c[0];
                int s = st.surface[j];
                boolean passable = s == Surface.ROAD.ordinal() || s == Surface.ROAD_LINE.ordinal() || s == Surface.PLAZA.ordinal()
                        || s == Surface.APRON.ordinal() || s == Surface.PARKING.ordinal() || s == Surface.LAWN.ordinal();
                if (!fresh.contains(j) && passable && !st.foot[j] && seen.add(k)) {
                    q.add(k);
                    break;
                }
            }
        }
        while (!q.isEmpty()) {
            int k = q.poll();
            int x = k % n;
            int z = k / n;
            int[][] nb = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
            for (int[] c : nb) {
                if (c[0] < 0 || c[1] < 0 || c[0] >= n || c[1] >= n) {
                    continue;
                }
                int j = c[1] * n + c[0];
                if (fresh.contains(j) && seen.add(j)) {
                    q.add(j);
                }
            }
        }
        // 塗ったマスのうち、筋からも前からの通れる地面からもたどれない塊だけ戻す
        for (int i = painted.size() - 1; i >= 0; i--) {
            int[] p = painted.get(i);
            if (!seen.contains(p[0])) {
                st.surface[p[0]] = (byte) p[1];
                st.road[p[0]] = false;
                st.net[p[0]] = false;
            }
        }
    }

    /**
     * 通りに面した建物の列を、通りの外の最初の線 {@code edge} から {@code dir} の向き(−1 = 北/西、+1 = 南/東)へ詰める。
     * 1 列目は通りへ正面を向け、背中合わせの 2 列目は外へ向ける。その外に路地を挟んで、路地へ正面を向けた列と背中合わせの列 ……
     * を置けなくなるまで。{@code alongX} = 列が x の向き(通りが東西)、{@code u0..u1} = 列の長さの範囲。
     * 補給・整備は 1 列目と通りのあいだを前庭、弾薬は 1 列目の前に防爆壁。{@code ammo} = 1 列目の頭に弾薬庫を 1 棟(中心の北東)。
     */
    private static void rows(State st, Random rng, Zone zone, int band, boolean alongX, int edge, int dir, int u0, int u1,
                             boolean helipad, boolean ammo) {
        int setback = zone == Zone.LOGISTICS || zone == Zone.MAINTENANCE ? APRON : zone == Zone.AMMO ? 2 : 1;
        int nearA = edge + dir * setback;
        List<Plot> prevB = null;
        for (int pair = 0; pair < 4; pair++) {
            boolean first = pair == 0;
            List<Plot> a = row(st, rng, zone, band, alongX, nearA, dir, false, u0, u1, first, helipad && first, ammo && first);
            if (first) {
                if (setback == APRON && !a.isEmpty()) {
                    strip(st, Surface.APRON, alongX, edge, nearA - dir, a);
                }
                if (zone == Zone.AMMO) {
                    blastWalls(st, alongX, edge + dir, dir, a);
                }
            } else {
                if (a.isEmpty() && (prevB == null || prevB.isEmpty())) {
                    return;
                }
                List<Plot> both = new ArrayList<>(a);
                both.addAll(prevB);
                strip(st, Surface.PATH, alongX, nearA - dir * LANE, nearA - dir, both);
            }
            int nearB = nearA + dir * (ROW + 2);
            List<Plot> b = row(st, rng, zone, band, alongX, nearB, dir, true, u0, u1, false, false, false);
            if (a.isEmpty() && b.isEmpty()) {
                return;
            }
            prevB = b;
            nearA = nearB + dir * (ROW + LANE);
        }
    }

    /**
     * 建物の列 1 本。通りに近い側の線 {@code near} から {@code dir} の向きへ奥行 {@link #ROW} の帯に置く。
     * {@code out} なら正面は外(帯の外側の縁にそろえる)、偽なら通り(内側の縁にそろえる)。
     * 並びの順に入るものから詰める(入らなければ 1 マスずつずらして試す)。置いた建物を返す。
     */
    private static List<Plot> row(State st, Random rng, Zone zone, int band, boolean alongX, int near, int dir, boolean out,
                                  int u0, int u1, boolean first, boolean helipad, boolean ammo) {
        List<BuildingKind> seq = sequence(rng, zone, band, first, helipad);
        if (ammo) {
            seq.add(0, BuildingKind.AMMO_BUNKER);
        }
        int facingDir = out ? dir : -dir;
        Facing facing = alongX ? (facingDir < 0 ? Facing.NORTH : Facing.SOUTH) : (facingDir < 0 ? Facing.WEST : Facing.EAST);
        List<Plot> placed = new ArrayList<>();
        int u = Math.min(u0, u1);
        int end = Math.max(u0, u1);
        int misses = 0;
        int count = 0;
        for (int i = 0; i < seq.size() && u <= end && misses < 6; i++) {
            BuildingKind kind = seq.get(i);
            Plot ok = fit(st, kind, zone, band, alongX, near, dir, out, u, end, facing);
            if (ok == null && kind == BuildingKind.BARRACKS_TENT_LARGE) {
                // 大型テントが入らない短い列(広場の脇・八角形の斜めの辺の近く)は小型のテントで
                kind = BuildingKind.BARRACKS_TENT;
                ok = fit(st, kind, zone, band, alongX, near, dir, out, u, end, facing);
            }
            if (ok == null) {
                misses++;
                continue;
            }
            st.add(ok);
            placed.add(ok);
            count++;
            int w = alongX ? ok.sizeX() : ok.sizeZ();
            int gap = kind == BuildingKind.AMMO_BUNKER ? AMMO_GAP : w <= 5 ? SMALL_GAP : GAP;
            if (zone == Zone.LSA && band <= 3 && first) {
                gap = count % 3 == 0 ? 4 : 1; // テント 2 張りと小屋で 1 組(組のあいだは広く)
            }
            u = (alongX ? ok.x1() : ok.z1()) + gap + 1;
        }
        return placed;
    }

    /**
     * 種類 {@code kind} を列の {@code u}..{@code end} のうち、いちばん手前の空いている所に置いた区画(置けなければ null)。
     * 足元は今の浄化圏の中だけ。
     */
    private static Plot fit(State st, BuildingKind kind, Zone zone, int band, boolean alongX, int near, int dir, boolean out,
                            int u, int end, Facing facing) {
        boolean flex = kind.width == 0;
        int w = flex ? 10 : kind.width;
        int d = flex ? ROW - 2 : kind.depth;
        Zone z = kind == BuildingKind.HELIPAD ? Zone.AIR : kind == BuildingKind.AMMO_BUNKER ? Zone.AMMO : zone;
        int v0 = out ? (dir > 0 ? near + ROW - d : near - ROW + 1) : (dir > 0 ? near : near - d + 1);
        for (int a = u; a + w - 1 <= end; a++) {
            Plot p = plot(kind, z, band, alongX ? a : v0, alongX ? v0 : a, facing, st.seed, w, d);
            if (st.free(p) && st.within(p)) {
                return p;
            }
        }
        return null;
    }

    /** 列の前の帯(前庭・路地)を、線 {@code a} から {@code b} まで、列の建物の幅だけ塗る。 */
    private static void strip(State st, Surface s, boolean alongX, int a, int b, List<Plot> placed) {
        int u0 = Integer.MAX_VALUE;
        int u1 = Integer.MIN_VALUE;
        for (Plot p : placed) {
            u0 = Math.min(u0, alongX ? p.x0() : p.z0());
            u1 = Math.max(u1, alongX ? p.x1() : p.z1());
        }
        for (int u = u0; u <= u1; u++) {
            for (int v = Math.min(a, b); v <= Math.max(a, b); v++) {
                int x = alongX ? u : v;
                int z = alongX ? v : u;
                if (st.in(x, z) >= PATROL_OUT + 1 && !st.foot[BasePlan.idx(x, z)] && !st.painted(x, z)) {
                    st.paint(s, x, z);
                }
            }
        }
    }

    /** 弾薬庫の前(通りとのあいだ、線 {@code v})に防爆壁を 1 枚ずつ。 */
    private static void blastWalls(State st, boolean alongX, int v, int dir, List<Plot> bunkers) {
        Facing facing = alongX ? (dir > 0 ? Facing.NORTH : Facing.SOUTH) : (dir > 0 ? Facing.WEST : Facing.EAST);
        for (Plot b : bunkers) {
            if (b.kind() != BuildingKind.AMMO_BUNKER) {
                continue;
            }
            int mid = alongX ? (b.x0() + b.x1()) / 2 : (b.z0() + b.z1()) / 2;
            Plot wall = plot(BuildingKind.BARRIER, Zone.AMMO, b.band(), alongX ? mid - 2 : v, alongX ? v : mid - 2, facing, st.seed, 0, 0);
            boolean ok = true;
            for (int z = wall.z0(); z <= wall.z1() && ok; z++) {
                for (int x = wall.x0(); x <= wall.x1(); x++) {
                    if (st.in(x, z) < BUILD_DEPTH || st.foot[BasePlan.idx(x, z)] || st.painted(x, z)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                st.add(wall);
            }
        }
    }

    /** 区割りごとの建物の並び(この順に、入るものから詰める)。{@code first} = 通りに面した 1 列目。 */
    private static List<BuildingKind> sequence(Random rng, Zone zone, int band, boolean first, boolean helipad) {
        List<BuildingKind> out = new ArrayList<>();
        if (helipad) {
            out.add(BuildingKind.HELIPAD);
        }
        BuildingKind[] life = {BuildingKind.LATRINE, BuildingKind.SHELTER, BuildingKind.WATER_TANK,
                BuildingKind.GENERATOR, BuildingKind.CONTAINER, BuildingKind.LIGHT_TOWER};
        for (int i = 0; i < 24; i++) {
            switch (zone) {
                case LSA -> {
                    // 通りに面した列: テント 2 張りのあいだに暮らしの小屋(仮設トイレ・退避壕・給水塔・発電機・コンテナ・照明塔)。
                    // 奥の列は小屋が主で、テントはときどき(ユーザー指摘 2026-10-02「兵舎の割合が多すぎる」)。
                    // 列の後の空き地はテントを先に埋める(fill。同日の指摘「兵舎の数が少なすぎる」)
                    if (!first) {
                        out.add(life[rng.nextInt(life.length)]);
                        out.add(i % 3 == 1 ? (band <= 3 ? BuildingKind.BARRACKS_TENT : BuildingKind.BARRACKS_TENT_LARGE)
                                : life[rng.nextInt(life.length)]);
                    } else if (band <= 3) {
                        out.add(BuildingKind.BARRACKS_TENT);
                        out.add(life[rng.nextInt(life.length)]);
                        out.add(BuildingKind.BARRACKS_TENT);
                    } else {
                        out.add(BuildingKind.BARRACKS_TENT_LARGE);
                        out.add(life[rng.nextInt(life.length)]);
                    }
                }
                case SUPPORT -> {
                    BuildingKind[] pool = {BuildingKind.GENERATOR, BuildingKind.WATER_TANK, BuildingKind.SHELTER,
                            BuildingKind.GENERATOR, BuildingKind.LATRINE, BuildingKind.CONTAINER, BuildingKind.CONTAINER};
                    out.add(pool[rng.nextInt(pool.length)]);
                }
                case LOGISTICS -> {
                    out.add(band <= 2 ? BuildingKind.WAREHOUSE_TENT : BuildingKind.WAREHOUSE);
                    out.add(i % 2 == 0 ? BuildingKind.CONTAINER_YARD : BuildingKind.CONTAINER);
                }
                case MAINTENANCE -> {
                    out.add(i == 0 && first ? BuildingKind.WORKSHOP : BuildingKind.VEHICLE_SHED);
                    out.add(i % 3 == 2 ? BuildingKind.GENERATOR : BuildingKind.PARKING);
                }
                case AMMO -> out.add(BuildingKind.AMMO_BUNKER);
                case MEDICAL -> {
                    // 野戦病院の医療区(2026-10-02 夜): 通りに面した列は医療テントのあいだに暮らしの小屋、奥の列は小屋が主で医療テントはときどき(仮)
                    if (first) {
                        out.add(BuildingKind.MEDICAL_TENT);
                        out.add(life[rng.nextInt(life.length)]);
                    } else {
                        out.add(life[rng.nextInt(life.length)]);
                        out.add(i % 3 == 1 ? BuildingKind.MEDICAL_TENT : life[rng.nextInt(life.length)]);
                    }
                }
                default -> out.add(BuildingKind.SHELTER);
            }
        }
        return out;
    }

    // ---------- 正門 ----------

    /** 正門(中心の浄化圏の南の辺の真ん中)と外の検問所、正門から外への道(両脇の浄化圏の外まで)。 */
    private static void gate(State st, List<Lobe> all, int lv) {
        st.add(plot(BuildingKind.GATEHOUSE, Zone.DEFENSE, lv, -8, CORE_R - 2, Facing.SOUTH, st.seed, 0, 0));
        st.add(plot(BuildingKind.CHECKPOINT, Zone.DEFENSE, lv, -7, CORE_R + 3, Facing.SOUTH, st.seed, 0, 0));
        int zEnd = gateRoadEnd(all);
        for (int z = GATE_ROAD_START; z <= zEnd; z++) {
            for (int x = -GATE_ROAD_HALF; x <= GATE_ROAD_HALF; x++) {
                st.paint(Surface.ROAD, x, z);
            }
        }
    }

    /** 正門から外への道の始まり(局所の z。正門の内側の端)。 */
    public static final int GATE_ROAD_START = CORE_R - 2;
    /** 正門から外への道の幅の半分(局所の x は −3〜3 の 7 マス)。 */
    public static final int GATE_ROAD_HALF = 3;

    /**
     * 正門から外への道の先の端(局所の z、端を含む)。両脇の浄化圏の外まで伸ばし、{@link BasePlan#GRID} の手前で止める。
     * 道の台帳が基地の口を求めるのにも使う(順 2 の着手順 7、2026-10-08。{@link #gate} と同じ求め方を 1 か所に)。
     */
    public static int gateRoadEnd(List<Lobe> lobes) {
        int zOut = CORE_R + BasePlan.CLEAR_MARGIN + 6;
        for (Lobe l : lobes) {
            if (l.zone() != Zone.CORE && l.x0() <= 60 && l.x1() >= -60 && l.z1() > CORE_R) {
                zOut = Math.max(zOut, l.z1() + BasePlan.CLEAR_MARGIN + 1);
            }
        }
        return Math.min(zOut, BasePlan.GRID - 1);
    }

    // ---------- 見張り台 ----------

    /**
     * 見張り台: 外周に出ている八角形の角を先に({@link #TOWER_CORNER_SPACING} マス以上離して)、それから外周の外側の列を
     * 基地の中心から見た向きの順に、ほかの塔から {@link #TOWER_SPACING} マス以上離れた所へ。塔の中心は外側の列で、
     * 足元 5 × 5 は外へ 2 マス・内へ 2 マス(帯と巡回路)にかかる。入口は基地の内側。正門の周りには置かない。
     */
    private static void towers(State st, List<Lobe> all, int lv) {
        List<int[]> placed = new ArrayList<>();
        for (Lobe l : all) {
            for (int[] v : l.vertices()) {
                if (st.in(v[0], v[1]) != 1 || addTower(st, placed, lv, v[0], v[1], TOWER_CORNER_SPACING)) {
                    continue;
                }
                // 角のマスでは入口が壁に向くことがある → 近くの外側の列で、入口が中へ開く所へずらす
                outer:
                for (int r = 1; r <= 3; r++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) == r && st.in(v[0] + dx, v[1] + dz) == 1
                                    && addTower(st, placed, lv, v[0] + dx, v[1] + dz, TOWER_CORNER_SPACING)) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        double sx = 0;
        double sz = 0;
        int n = 0;
        List<int[]> ring = new ArrayList<>();
        for (int z = -BasePlan.GRID; z <= BasePlan.GRID; z++) {
            for (int x = -BasePlan.GRID; x <= BasePlan.GRID; x++) {
                int d = st.in(x, z);
                if (d > 0) {
                    sx += x;
                    sz += z;
                    n++;
                }
                if (d == 1) {
                    ring.add(new int[]{x, z});
                }
            }
        }
        double cx = sx / Math.max(1, n);
        double cz = sz / Math.max(1, n);
        ring.sort((a, b) -> Double.compare(Math.atan2(a[1] - cz, a[0] - cx), Math.atan2(b[1] - cz, b[0] - cx)));
        for (int[] c : ring) {
            addTower(st, placed, lv, c[0], c[1], TOWER_SPACING);
        }
    }

    /** 見張り台を (x, z) を中心に置く。置けたら真。 */
    private static boolean addTower(State st, List<int[]> placed, int lv, int x, int z, int spacing) {
        // 正門(x −8..8、z CORE_R − 2..CORE_R + 2)とその脇には置かない
        if (Math.abs(x) <= 14 && z >= CORE_R - 6 && z <= CORE_R + 5) {
            return false;
        }
        for (int[] p : placed) {
            if (Math.max(Math.abs(p[0] - x), Math.abs(p[1] - z)) < spacing) {
                return false;
            }
        }
        // 入口は中の多い向き。ただし扉の前の 2 マスが外周の帯(壁の時期は壁)の内側にある向きだけ
        // (八角形の角では、中の多い向きの扉の前が壁の内の列になり、壁に向かって開いていた。2026-10-02 の歩ける点検で見つけた)
        int bestCount = -1;
        Facing facing = null;
        for (Facing f : Facing.values()) {
            if (st.in(x + 3 * f.dx, z + 3 * f.dz) <= BasePlan.BAND || st.in(x + 4 * f.dx, z + 4 * f.dz) <= BasePlan.BAND) {
                continue;
            }
            int c = 0;
            for (int s = 1; s <= 4; s++) {
                for (int w = -2; w <= 2; w++) {
                    int px = x + f.dx * s + (f.dx == 0 ? w : 0);
                    int pz = z + f.dz * s + (f.dz == 0 ? w : 0);
                    if (st.in(px, pz) > 0) {
                        c++;
                    }
                }
            }
            if (c > bestCount) {
                bestCount = c;
                facing = f;
            }
        }
        if (facing == null) {
            return false;
        }
        Plot t = plot(BuildingKind.WATCHTOWER, Zone.DEFENSE, lv, x - 2, z - 2, facing, st.seed, 0, 0);
        for (Plot p : st.plots) {
            if (p.overlaps(t, 0)) {
                return false;
            }
        }
        placed.add(new int[]{x, z});
        st.add(t);
        return true;
    }

    // ---------- 道具 ----------

    /**
     * 西北の角 (x0, z0) と向きから区画を作る。大きさは種類の既定(正面の幅 × 奥行)。
     * {@code w}・{@code d} が 0 より大きければそれを使う(大きさが決まっていない種類)。
     */
    static Plot plot(BuildingKind kind, Zone zone, int band, int x0, int z0, Facing facing, long seed, int w, int d) {
        int width = w > 0 ? w : kind.width;
        int depth = d > 0 ? d : kind.depth;
        int sx = facing.dx == 0 ? width : depth;
        int sz = facing.dx == 0 ? depth : width;
        long s = seed * 6364136223846793005L + (x0 * 73856093L) ^ (z0 * 19349663L) ^ kind.ordinal();
        return new Plot(kind, zone, band, x0, z0, x0 + sx - 1, z0 + sz - 1, facing, s);
    }

    private BasePlanner() {
    }
}
