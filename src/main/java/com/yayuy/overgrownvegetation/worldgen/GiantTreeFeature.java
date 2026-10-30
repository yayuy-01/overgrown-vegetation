package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.td.TdEvents;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * 低確率({@code data/overgrown_vegetation/worldgen/configured_feature/overgrown_tree.json} の
 * {@code random_selector})、または専用の「巨大樹の苗木」からのみ生える巨大樹。
 *
 * <p><b>生成は2段階</b>:
 * <ol>
 *   <li>{@link #place} が呼ばれた瞬間、木の全ブロックの座標(約5万)を計算して
 *       {@link Plan} に貯め、{@link GiantTreeGrowthQueue} に登録するだけ。ここではブロックを1つも
 *       置かない(座標計算だけなので数十ミリ秒)。</li>
 *   <li>{@link GiantTreeGrowthQueue} が毎ゲームティック、設定 {@code giantTreeBlocksPerTick} 個ずつ
 *       設置していく。約5万ブロックを一度に置くとサーバーが数百ミリ秒固まるため、複数ティックに
 *       分散して「ゆっくり生える」ようにしている。</li>
 * </ol>
 *
 * <p>形:
 * <ul>
 *   <li><b>幹</b>: 全高 {@code height}(既定120)。全体が直線的な円錐(裾 {@code baseRadius} =直径23 →
 *       先端 {@code topRadius} =直径5)。段差でも浮かないよう裾の真下は地面まで埋める。</li>
 *   <li><b>根</b>: {@code rootCount}(既定8)本・長さ {@code rootLength}(既定75)。幹の裾から放射状。
 *       {@link #growRoot} — 正弦カーブで<b>うねり</b>、1歩ごとに地表を探して下降/上昇を制限しつつ
 *       縦の隙間を埋めて<b>崖でも面を這う</b>(宙に浮かない)。登れない壁は登らずそのまま潜って前進し、
 *       谷や川に<b>対岸</b>があれば{@code 上下逆のアーチ}(たわんだ吊り橋)で渡る。<b>かまぼこ状</b>({@link #fillRootMound})で、
 *       <b>幹に近いほど太い</b>(バットレス状の張り出し)。{@value #ROOT_ARCH_CHANCE} の確率で途中が
 *       アーチ(土台は地表に沿い、地面が無い区間だけまたぐ)。中盤で {@link #ROOT_MAX_FORKS} 本まで<b>子根に枝分かれ</b>し再帰する
 *       (2段・低確率)。生えきったあと、根の盛り上がりの頂上には汚染時と同じ下草が生える
 *       ({@code giantTreeRootPlantChance}、{@link GiantTreeGrowthQueue} の装飾フェーズ)。</li>
 *   <li><b>細い枝</b>: {@code branchMinCount}〜{@code branchMaxCount}(既定26〜40)本。地面から
 *       {@value #BRANCH_MIN_DY} マス以上のランダムな高さ(幹の全高にわたって)から外へ緩やかに
 *       立ち上がる弧。下の枝ほど長い。中盤以降は途中からも小さな葉の房を生やし、先端にも房。</li>
 *   <li><b>傘を支える太い枝</b>: {@link #placeCanopyBranches} — 幹の上部から {@code crownRadius} の
 *       0.55〜1.0 倍まで外へ伸び、傘の内側の輪郭に沿って少し反りながら立ち上がる。中盤以降と枝先に
 *       葉のポッド。</li>
 *   <li><b>頂上の傘</b>: {@link #placeUmbrella} — 幹の先端より {@value #UMBRELLA_LIFT} マス上をピークに
 *       {@code crownRadius}(既定28)まで外周へなだらかに下がる浅いドーム面に沿って、平たい葉のポッド
 *       (縦横 ≒ {@code 2·}{@value #POD_RADIUS_H}、高さ ≒ 3)を数十個散らす。ポッドを上下に散らして
 *       重ねるので傘に厚みが出て、1つの滑らかな曲面にならない。露出しがちな幹の先端は
 *       {@link #capTrunkTop} で葉のキャップを被せて隠す。</li>
 * </ul>
 *
 * <p>幹・枝・根は {@code trunkState}(既定 {@code giant_overgrown_log} — 蔦がつながらないように
 * {@code overgrown_log} とは別ブロック)を使う。
 *
 * <p><b>TD の侵攻の間は生やさない</b>({@link #pausedByTd}。ユーザー指定 2026-09-26「TD 時のみ苗木が巨大樹にならないように」)。
 */
public class GiantTreeFeature extends Feature<GiantTreeFeature.Config> {

    private static final double TAU = Math.PI * 2.0D;

    /**
     * 根の1歩ごとの地表探索範囲。登りは緩やかに、下りは崖でも追従できるよう大きく取る。
     * 範囲内に地面が無ければ根は1歩あたり {@link #ROOT_MAX_DROP_PER_STEP} だけ沈んで地形を探し続け、
     * 前の位置との縦の隙間は縦のつなぎ({@link #fillRootColumn})で埋めるので宙に浮かない。
     */
    private static final int ROOT_SEARCH_UP = 3;
    private static final int ROOT_SEARCH_DOWN = 28;

    /** 根が地面を見失ったとき1歩あたり沈む量(崖の途中を這い下りる速さ)。 */
    private static final int ROOT_MAX_DROP_PER_STEP = 2;

    /**
     * 1歩で登れる段差の上限。これを超える段差(={@link #ROOT_SEARCH_UP} まで探しても
     * 足元よりこれ以上高い面しか無い)は「壁」とみなし、<b>登らずに高さを保ったまま前進</b>する
     * (崖下まで垂れた根が壁に当たって急に上を向くのを防ぐ)。
     */
    private static final int ROOT_MAX_RISE_PER_STEP = 2;

    /** 壁の中を水平に潜り続ける最大歩数。薄い壁は貫けるが、山の中を延々掘らないための上限。 */
    private static final int ROOT_WALL_BURROW_MAX = 24;

    /** 「崖の縁」とみなす落差。1歩でこれ以上下がる(または地面が見つからない)と対岸を探す。 */
    private static final int ROOT_SPAN_MIN_DROP = 5;

    /** 対岸を探す水平距離の範囲。この範囲に足元と同じくらいの高さの地面があれば吊り橋で渡る。 */
    private static final int ROOT_SPAN_MIN_DIST = 4;
    private static final int ROOT_SPAN_MAX_DIST = 28;

    /** 対岸とみなす高さの許容差(足元から上/下に何マスまで)。 */
    private static final int ROOT_SPAN_BANK_UP = 4;
    private static final int ROOT_SPAN_BANK_DOWN = 4;

    /** 吊り橋のたわみ = 渡る距離 × これ(最小2・最大8マス)。 */
    private static final double ROOT_SPAN_SAG_RATIO = 0.35D;
    private static final int ROOT_SPAN_SAG_MIN = 2;
    private static final int ROOT_SPAN_SAG_MAX = 8;

    /** 根1本がどこかでアーチ(持ち上げた橋)状になる確率。土台は地表に沿い、地面が無い区間だけ本当にまたぐ。 */
    private static final float ROOT_ARCH_CHANCE = 0.4F;

    /** 根の枝分かれ: 各段が分岐させる子根の最大本数(段が深いほど少なく)。 */
    private static final int[] ROOT_MAX_FORKS = {2, 1, 0};

    /** 幹の裾の真下を埋める最大の深さ(崖際で無限に潜らないための安全上限)。 */
    private static final int FOOTING_MAX_DEPTH = 16;

    /** 枝が生え始める最低の高さ(地面から)。 */
    private static final int BRANCH_MIN_DY = 10;

    /** 頂上の傘のピーク(=幹の先端)から縁までの高さの落差 ÷ crownRadius。 */
    private static final double UMBRELLA_DROP_RATIO = 0.45D;

    /** 傘のピークを幹の先端より何マス上に置くか(幹の露出を葉で隠すため)。 */
    private static final int UMBRELLA_LIFT = 3;

    /** 葉のポッド1個の水平半径(≒直径8)。 */
    private static final int POD_RADIUS_H = 4;

    /** 葉のポッド1個の垂直半径(≒高さ3)。 */
    private static final int POD_RADIUS_V = 1;

    /** 枝分かれの最大再帰段数(0=元の枝、1=子、2=孫)。 */
    private static final int BRANCH_MAX_DEPTH = 2;

    /** 各段の枝が分岐させる子枝の最大本数(段が深いほど少なく)。 */
    private static final int[] BRANCH_MAX_FORKS = {2, 1, 0};

    /**
     * 「巨大樹の苗木」({@link com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock})から
     * 育てている最中か(そのサーバースレッドについて)。true の間は {@link #place} の
     * 「既存の巨大樹との間隔」「洞窟内か」の判定を飛ばし、必ず生やす。
     *
     * <p>クリエイティブ専用の苗木は「好きな場所に置いて試せる」のが存在意義なのに、これらの判定は
     * 何も言わずに生成を中止するので、一度巨大樹を生やした場所の近く(既定 384 ブロック以内)や
     * 屋根の下では骨粉を何個使っても苗木のままになっていた。config
     * {@code giantTreeSaplingForceGrowth} を false にすると従来どおり判定する。
     *
     * <p>苗木の成長はサーバースレッドから同期的に呼ばれるので {@link ThreadLocal} で十分。
     * ワールド生成の並列スレッドから来る自然生成には影響しない。
     */
    private static final ThreadLocal<Boolean> SAPLING_GROWTH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 苗木からの成長を開始する(必ず {@link #endSaplingGrowth()} と対にして呼ぶこと)。 */
    public static void beginSaplingGrowth() {
        SAPLING_GROWTH.set(Boolean.TRUE);
    }

    /** 苗木からの成長を終える。 */
    public static void endSaplingGrowth() {
        SAPLING_GROWTH.set(Boolean.FALSE);
    }

    /** 今、苗木からの成長として呼ばれていて、かつ config で強制成長が有効か。 */
    private static boolean forcedBySapling() {
        return SAPLING_GROWTH.get()
                && com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON
                        .giantTreeSaplingForceGrowth.get();
    }

    /**
     * そのワールドで TD の侵攻が進行中(予告中・侵攻中)で、巨大樹を生やさないか(ユーザー指定 2026-09-26
     * 「TD 時のみ苗木が巨大樹にならないように」。予告中も含めるのは仮決め —— 予告の間に育つと、侵攻が始まる前に拠点が幹と根に埋まる)。
     * 汚染された苗木の抽選で巨大樹の目(0.25%)が出ても {@link #place} が false を返すので、苗木は苗木のまま残り
     * (バニラの {@code TreeGrower#growTree} が戻す)、次の成長の抽選でふつうの木になる。「巨大樹の苗木」は侵攻が終わるまで苗木のまま待つ
     * ({@link com.yayuy.overgrownvegetation.block.GiantOvergrownSaplingBlock#advanceTree})。サーバースレッドから呼ぶこと。
     */
    public static boolean pausedByTd(ServerLevel level) {
        return TdEvents.activeTarget(level) != null;
    }

    public GiantTreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        Config config = context.config();
        BlockPos origin = context.origin();

        // TD の侵攻の間は生やさない(苗木は苗木のまま次の抽選へ。ユーザー指定 2026-09-26)。見るのは生きたワールドへ直接置くとき
        // (苗木・/place)だけ —— ワールド生成の並列スレッド(WorldGenRegion)からは TD の台帳を読まない。
        if (level instanceof ServerLevel serverLevel && pausedByTd(serverLevel)) {
            OvergrownVegetation.LOGGER.info("GiantTree at {}: 生成中止 —— TD の侵攻が進行中です。", origin);
            return false;
        }

        // 巨大樹同士が近すぎないようにする。既存の巨大樹(このワールドでこれまでに生成された
        // 古代樹の核の位置)が giantTreeMinSpacing の水平距離以内にあれば、ここには生やさない。
        com.yayuy.overgrownvegetation.phase.PhaseData phaseData =
                com.yayuy.overgrownvegetation.phase.PhaseData.get(level.getLevel());
        int minSpacing = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON
                .giantTreeMinSpacing.get();
        // ただし「巨大樹の苗木」から育てるときは、どちらの判定も飛ばして必ず生やす
        // (黙って中止すると「骨粉を使っても苗木のまま」になり、原因も分からないため)。
        boolean forced = forcedBySapling();
        if (!forced && phaseData.hasGiantTreeOriginWithin(origin.getX(), origin.getZ(), minSpacing)) {
            OvergrownVegetation.LOGGER.info("GiantTree at {}: 生成中止 —— 既存の巨大樹が giantTreeMinSpacing({}) "
                    + "ブロック以内にあります。", origin, minSpacing);
            return false;
        }

        // 巨大樹は洞窟内(空が見えない場所)には生やさない。
        if (!forced && TreePlacementRules.isEnclosed(level, origin)) {
            OvergrownVegetation.LOGGER.info("GiantTree at {}: 生成中止 —— 空が見えない場所(洞窟・厚い天井の下)です。",
                    origin);
            return false;
        }

        Plan plan = buildPlan(level, context.random(), config, origin);
        if (plan.trunk.isEmpty() && plan.cambium.isEmpty() && plan.leaves.isEmpty()) {
            return false;
        }
        GiantTreeGrowthQueue.enqueue(level.getLevel(), plan);
        phaseData.recordGiantTreeOrigin(origin.getX(), origin.getZ());
        phaseData.recordGiantTreeBorn(origin.getX(), origin.getZ(), level.getLevel().getGameTime()); // 梢の胞子の育ち具合(胞子の場 D)
        if (plan.hiveCenter != Long.MIN_VALUE) {
            // 幹の真下の「巣の広間」の中心。ここに湧く「ハイヴノード」が旧「古代樹の核」の役割
            // (胞子感染3 の強制ゾーンの中心)を引き継ぐ。ゾーンの登録は mob 自身が行う。
            phaseData.recordHiveChamber(BlockPos.of(plan.hiveCenter));
        }
        return true;
    }

    /** 木の全ブロック座標を計算して {@link Plan} に貯める(この時点ではブロックは置かない)。 */
    private static Plan buildPlan(WorldGenLevel level, RandomSource random, Config config, BlockPos origin) {
        long startNanos = System.nanoTime();
        int height = chooseHeight(level, random, config, origin);
        double baseRadius = baseRadiusFor(height, config);
        Plan plan = new Plan(level, config.trunkState(), config.leavesState());

        // 木を建てる「前」の地面の高さ(根の根本側の基準)。
        int originGroundY = findGroundY(level, origin.getX(), origin.getY(), origin.getZ(),
                config.trunkState().getBlock());

        try {
            // 幹: 直線的な円錐。
            for (int dy = 0; dy < height; dy++) {
                double radius = radiusAt(dy, height, config);
                fillDisc(plan, origin.getX(), origin.getY() + dy, origin.getZ(), radius);
            }
            // 幹の裾の真下を地面まで埋める。
            fillTrunkFooting(plan, origin, baseRadius);
            // 地表を這う太い根。
            placeRoots(plan, random, origin, originGroundY, baseRadius, config);
            // 全高にわたる枝 + 頂上の半楕円体の葉。
            placeCrown(plan, random, origin, height, config);
            // 幹リストを「外殻(原木)/内側(形成層)」に分ける。
            plan.partitionTrunk();
            // 形成層で埋まった空間のランダムな位置に、地中の鉱脈のような鉱石ノードを埋め込む。
            // 木が大きいほど形成層の体積(≒ 高さ × 裾半径²)が増えるので、鉱石ノードの数も
            // 拡大係数の2乗ぶん増やして密度を保つ(基準の高さ → ×1.0、2 倍の高さ → ×2.56)。
            double oreNodeScale = Math.pow(heightScaleFactor(height, config), 2.0D);
            placeOreNodes(plan, random, oreNodeScale);
            // 幹の真下に「巣の球」(中まで詰まった球。中心にハイヴノードの小部屋、隣に儀式の広間、
            // うねる通路で球面の出口へつながる)を計画する。
            buildHiveChamber(plan, random, level, origin, originGroundY, baseRadius);
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("GiantTree plan failed partway at {} ({} blocks so far)",
                    origin, plan.trunk.size() + plan.cambium.size() + plan.leaves.size(), e);
        }

        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        long total = (long) plan.trunk.size() + plan.cambium.size()
                + plan.chlorophylliteOre.size() + plan.hardenedSapCrystalOre.size()
                + plan.hiveBark.size() + plan.hiveShell.size() + plan.hiveFill.size() + plan.hiveLining.size()
                + plan.hiveHollow.size()
                + plan.leaves.size();
        OvergrownVegetation.LOGGER.info("GiantTree at {}: planned {} blocks (shell {}, cambium {}, "
                        + "chlorophyllite {}, hardened_sap_crystal {}, hive_bark {}, hive_shell {}, hive_fill {}, "
                        + "hive_lining {}, hive_carved {}, leaves {}) in {} ms; will place over multiple ticks ({}/tick).",
                origin, total, plan.trunk.size(), plan.cambium.size(),
                plan.chlorophylliteOre.size(), plan.hardenedSapCrystalOre.size(),
                plan.hiveBark.size(), plan.hiveShell.size(), plan.hiveFill.size(), plan.hiveLining.size(),
                plan.hiveHollow.size(),
                plan.leaves.size(), ms,
                com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.giantTreeBlocksPerTick.get());
        return plan;
    }

    /**
     * 生成予定のブロック座標をためる作業用オブジェクト。追加中は幹・枝・根をすべて {@code trunk} に
     * 入れ、{@link #partitionTrunk} で最後に<b>外殻(原木そのまま)/内側(形成層)</b>に分ける。
     * 葉は別リスト。実際の設置は {@link GiantTreeGrowthQueue} が複数ティックに分けて行う。
     */
    static final class Plan {
        final WorldGenLevel level;                       // 地形の読み取り専用
        final BlockState trunkState;
        final BlockState cambiumState;
        final BlockState chlorophylliteOreState;
        final BlockState hardenedSapCrystalOreState;
        final BlockState hiveShellState;
        final BlockState hiveBarkState;
        final BlockState leafState;
        private final LongOpenHashSet trunkSet = new LongOpenHashSet(1 << 15);
        private final LongOpenHashSet leafSet = new LongOpenHashSet(1 << 14);
        /** 追加中はすべての幹ブロック。{@link #partitionTrunk} 後は「外殻(原木)」だけになる。 */
        final LongArrayList trunk = new LongArrayList();
        /** {@link #partitionTrunk} 後、空気に触れない内側の幹ブロック。 */
        final LongArrayList cambium = new LongArrayList();
        /** {@link #placeOreNodes} 後、「クロロフィライトの鉱石」に置き換わった形成層ブロック。 */
        final LongArrayList chlorophylliteOre = new LongArrayList();
        /** {@link #placeOreNodes} 後、「硬化樹液結晶の鉱石」に置き換わった形成層ブロック。 */
        final LongArrayList hardenedSapCrystalOre = new LongArrayList();
        /** 「巣の広間」の殻のさらに外側を包む樹皮。巨大樹の原木({@link ModBlocks#GIANT_OVERGROWN_LOG})。 */
        final LongArrayList hiveBark = new LongArrayList();
        /**
         * 幹の真下の「巣の広間」({@code buildHiveChamber})の殻。「硬化した巨大樹の幹」で埋める。
         * 中身({@link #hiveHollow})より先に置いて、空洞を掘る間に水や溶岩が流れ込まないようにする。
         */
        final LongArrayList hiveShell = new LongArrayList();
        /** 「巣の球」の中で掘った空気(ハイヴノードの小部屋・儀式の広間・通路)。何が入っていても空気にする。 */
        final LongArrayList hiveHollow = new LongArrayList();
        /** 「巣の球」の詰め物(巨大樹の形成層)。殻の内側で掘られなかったマス。 */
        final LongArrayList hiveFill = new LongArrayList();
        /** 掘った空気に面した詰め物 = 通路・広間の壁面。巨大樹の幹(原木)にして、面に下草・蔦を生やす。 */
        final LongArrayList hiveLining = new LongArrayList();
        /** 儀式の広間の床の中心({@link BlockPos#asLong})。作らなかった場合は {@link Long#MIN_VALUE}。 */
        long hiveRitualCenter = Long.MIN_VALUE;
        /** 広間からハイヴノードの小部屋を見る向き(Minecraft の yaw、度)= 儀式中のプレイヤーの向き。 */
        float hiveRitualYaw;
        /** ハイヴノードが向く方角(度)= 広間の方({@code hiveRitualYaw + 180})。 */
        float hiveNodeYaw;
        /** 通路の出口(球面上、{@link BlockPos#asLong})。作らなかった場合は {@link Long#MIN_VALUE}。 */
        long hiveEntrance = Long.MIN_VALUE;
        /** 「巣の広間」の中心({@link BlockPos#asLong})。作らなかった場合は {@link Long#MIN_VALUE}。 */
        long hiveCenter = Long.MIN_VALUE;
        /** 「巣の球」(殻まで)の半径。 */
        int hiveRadius;
        final LongArrayList leaves = new LongArrayList();
        /**
         * 根を1歩這わせるごとに記録する、その足元(地表)の座標。{@link GiantTreeGrowthQueue} の
         * 装飾フェーズが、ここから上へ根の盛り上がりの頂上を探して「汚染時と同じ下草」を生やす
         * (根が地面と一体化して苔むして見えるようにするため)。設置対象ではないので
         * {@code trunkSet} には入れない。重複は許容(下草生成側で「真上が空気の一番上の根」1つに絞る)。
         */
        final LongArrayList rootColumns = new LongArrayList();

        Plan(WorldGenLevel level, BlockState trunkState, BlockState leafState) {
            this.level = level;
            this.trunkState = trunkState;
            this.cambiumState = ModBlocks.GIANT_OVERGROWN_CAMBIUM.get().defaultBlockState();
            this.chlorophylliteOreState = ModBlocks.CHLOROPHYLLITE_ORE.get().defaultBlockState();
            this.hardenedSapCrystalOreState = ModBlocks.HARDENED_SAP_CRYSTAL_ORE.get().defaultBlockState();
            this.hiveShellState = ModBlocks.HARDENED_GIANT_TRUNK.get().defaultBlockState();
            this.hiveBarkState = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            this.leafState = leafState;
        }

        void trunk(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            if (trunkSet.add(k)) {
                trunk.add(k);
            }
        }

        void leaf(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            if (!trunkSet.contains(k) && leafSet.add(k)) {
                leaves.add(k);
            }
        }

        boolean isTrunk(int x, int y, int z) {
            return trunkSet.contains(BlockPos.asLong(x, y, z));
        }

        /**
         * {@code buildPlan} の最後に一度だけ呼ぶ。{@code trunk} を「外殻(原木そのまま)/内側(形成層)」に
         * 振り分ける。内側 = 直交6方向すべてが幹のブロック。
         */
        void partitionTrunk() {
            LongArrayList shell = new LongArrayList(trunk.size());
            for (int i = 0; i < trunk.size(); i++) {
                long k = trunk.getLong(i);
                int x = BlockPos.getX(k);
                int y = BlockPos.getY(k);
                int z = BlockPos.getZ(k);
                if (trunkSet.contains(BlockPos.asLong(x + 1, y, z))
                        && trunkSet.contains(BlockPos.asLong(x - 1, y, z))
                        && trunkSet.contains(BlockPos.asLong(x, y + 1, z))
                        && trunkSet.contains(BlockPos.asLong(x, y - 1, z))
                        && trunkSet.contains(BlockPos.asLong(x, y, z + 1))
                        && trunkSet.contains(BlockPos.asLong(x, y, z - 1))) {
                    cambium.add(k);
                } else {
                    shell.add(k);
                }
            }
            trunk.clear();
            trunk.addAll(shell);
        }
    }

    /**
     * {@link Plan#partitionTrunk} で確定した形成層({@link Plan#cambium})のブロックのうち、
     * ランダムに選んだ中心のまわりの球状の塊を「鉱石ノード」として鉱石ブロックに置き換える。
     * クロロフィライトは多め、硬化樹液結晶は config の確率を引いたときだけ少数。置き換えた分は
     * {@code cambium} から取り除いて {@link Plan#chlorophylliteOre} / {@link Plan#hardenedSapCrystalOre}
     * へ移す。地中の鉱脈のように「形成層で埋まった空間のランダムな位置」に埋まる。
     *
     * @param nodeScale 鉱石ノードの本数(min/max)に掛ける倍率。木の大きさに応じて 1.0 以上
     *                  ({@code buildPlan} が {@link #heightScaleFactor} の2乗を渡す)。
     */
    private static void placeOreNodes(Plan plan, RandomSource random, double nodeScale) {
        if (plan.cambium.isEmpty()) {
            return;
        }
        var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
        double chloroRadius = Math.max(0.5D, cfg.giantTreeChlorophylliteNodeRadius.get());
        double sapRadius = Math.max(0.5D, cfg.giantTreeHardenedSapCrystalNodeRadius.get());
        double scale = Math.max(1.0D, nodeScale);

        it.unimi.dsi.fastutil.longs.LongOpenHashSet cambiumSet =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(plan.cambium);
        it.unimi.dsi.fastutil.longs.LongOpenHashSet claimed =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        // レアな硬化樹液結晶を先に確保してから、余った形成層をクロロフィライトで埋める
        // (クロロフィライトが多すぎて形成層をほぼ埋め尽くしても、樹液結晶の枠が消えないように)。
        it.unimi.dsi.fastutil.longs.LongOpenHashSet sapSet =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (random.nextDouble() < cfg.giantTreeHardenedSapCrystalChance.get()) {
            int sapNodes = randomCount(random,
                    scaleCount(cfg.giantTreeHardenedSapCrystalNodesMin.get(), scale),
                    scaleCount(cfg.giantTreeHardenedSapCrystalNodesMax.get(), scale));
            sapSet = growOreNodes(plan.cambium, cambiumSet, claimed, random, sapNodes, sapRadius);
        }

        int chloroNodes = randomCount(random,
                scaleCount(cfg.giantTreeChlorophylliteNodesMin.get(), scale),
                scaleCount(cfg.giantTreeChlorophylliteNodesMax.get(), scale));
        it.unimi.dsi.fastutil.longs.LongOpenHashSet chloroSet =
                growOreNodes(plan.cambium, cambiumSet, claimed, random, chloroNodes, chloroRadius);

        if (chloroSet.isEmpty() && sapSet.isEmpty()) {
            return;
        }
        LongArrayList remaining = new LongArrayList(plan.cambium.size());
        for (int i = 0; i < plan.cambium.size(); i++) {
            long k = plan.cambium.getLong(i);
            if (chloroSet.contains(k)) {
                plan.chlorophylliteOre.add(k);
            } else if (sapSet.contains(k)) {
                plan.hardenedSapCrystalOre.add(k);
            } else {
                remaining.add(k);
            }
        }
        plan.cambium.clear();
        plan.cambium.addAll(remaining);
    }


    /**
     * 巨大樹の幹の<b>真下</b>に「巣の球」を計画する。ハイヴノードの住処。
     *
     * <p>形は半径 {@code giantTreeHiveChamberRadius}(既定 12)の球ひとつ。<b>2026-09-18 から球は中まで
     * 詰まっている</b>(以前は空洞だった)。球の表面 1 層が「硬化した巨大樹の幹」
     * ({@link ModBlocks#HARDENED_GIANT_TRUNK})、内側は「巨大樹の形成層」で詰め、その中に
     * <b>中心のハイヴノードの小部屋・隣の儀式の広間・球面の出口へうねる通路</b>だけを掘る
     * (形の計算は {@link HiveChamberLayout})。球の中身が何であっても(石・鉱石・水・溶岩・洞窟・建築物でも)
     * すべて置き換える。殻は「中心からの距離が半径以内で、直交6方向のどれかが半径の外」のマスと
     * 定義しているので、通路の出口以外に水や溶岩が漏れ込む隙間はない。
     *
     * <p>半径は {@code giantTreeHiveChamberRadius}(既定 12)だが、幹の裾より細いと球が幹の中に
     * 完全に埋もれて外から見えないので、{@code giantTreeHiveChamberTrunkMargin}(既定 4)ぶん
     * <b>必ず幹の裾より太くする</b>(幹の裾は木の大きさで半径 11〜18 まで変わる)。
     *
     * <p>中心は幹の中心の真下、地面から {@code giantTreeHiveChamberDepth}(既定 0)ブロック下。
     * 既定では<b>球の上半分が地上に出て</b>、その天辺から幹が生えているように見える。depth を
     * 大きくするほど地中に潜り、半径と同じ値で球のてっぺんがちょうど地面の高さになる。
     * 球の底が岩盤に届く場合は自動で下限に収め、その余裕も無ければ広間を作らない。
     *
     * <p>さらに殻の外側を { giantTreeHiveChamberBarkThickness}(既定 2)層ぶん巨大樹の原木
     * ({ ModBlocks#GIANT_OVERGROWN_LOG})で覆うので、地上に出た部分は「硬化した幹」がむき出しでは
     * なく<b>樹皮に包まれた球</b>に見える。
     */
    private static void buildHiveChamber(Plan plan, RandomSource random, WorldGenLevel level, BlockPos origin,
            int groundY, double trunkBaseRadius) {
        var cfg = com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON;
        if (!cfg.giantTreeHiveChamber.get()) {
            return;
        }
        int radius = cfg.giantTreeHiveChamberRadius.get();
        // 球が幹より細いと幹の中に完全に埋もれて外から見えない(幹の裾の半径は木の大きさで 11〜18 まで
        // 変わる)。幹の裾より margin ブロック太くして、地上に出た部分が必ずドームとして見えるようにする。
        int margin = cfg.giantTreeHiveChamberTrunkMargin.get();
        if (margin > 0) {
            radius = Math.max(radius, Mth.ceil(trunkBaseRadius) + margin);
        }
        // 球の底が岩盤に届かない最低の中心高さ / 球が地上へ出すぎない(上半分まで)最高の中心高さ。
        int lowest = level.getMinBuildHeight() + 2 + radius;
        int highest = groundY;
        if (highest < lowest) {
            OvergrownVegetation.LOGGER.info("GiantTree at {}: 巣の広間(半径 {})を作る余裕が縦方向にありません。",
                    origin, radius);
            return;
        }
        int centerY = Mth.clamp(groundY - cfg.giantTreeHiveChamberDepth.get(), lowest, highest);
        int cx = origin.getX();
        int cz = origin.getZ();
        plan.hiveRadius = radius;
        plan.hiveCenter = BlockPos.asLong(cx, centerY, cz);

        // 形は Minecraft 非依存の HiveChamberLayout に任せる(tools の試作プレビューと同じコード)。
        HiveChamberLayout layout = new HiveChamberLayout(new HiveChamberLayout.Params(radius,
                cfg.giantTreeHiveChamberBarkThickness.get(), cfg.hiveNodeCavityRadius.get(),
                cfg.hiveRitualChamberHalfWidth.get(), cfg.hiveRitualChamberHeight.get(),
                cfg.hiveTunnelRadius.get(), cfg.hiveTunnelWinding.get(), cfg.hiveTunnelTurns.get(),
                cfg.hiveTunnelEntranceElevationMin.get(), cfg.hiveTunnelEntranceElevationMax.get()),
                random::nextDouble);
        int o = layout.outer;
        for (int dx = -o; dx <= o; dx++) {
            for (int dy = -o; dy <= o; dy++) {
                for (int dz = -o; dz <= o; dz++) {
                    byte kind = layout.get(dx, dy, dz);
                    if (kind == HiveChamberLayout.OUTSIDE) {
                        continue;
                    }
                    long k = BlockPos.asLong(cx + dx, centerY + dy, cz + dz);
                    switch (kind) {
                        case HiveChamberLayout.BARK -> plan.hiveBark.add(k);
                        case HiveChamberLayout.SHELL -> plan.hiveShell.add(k);
                        case HiveChamberLayout.FILL -> plan.hiveFill.add(k);
                        case HiveChamberLayout.LINING -> plan.hiveLining.add(k);
                        default -> plan.hiveHollow.add(k);
                    }
                }
            }
        }
        plan.hiveRitualCenter = BlockPos.asLong(cx + layout.chamberCenter[0], centerY + layout.chamberCenter[1],
                cz + layout.chamberCenter[2]);
        // atan2(z, x) の向き → Minecraft の yaw(0 = +Z、90 = -X)。
        double f = layout.chamberFacing;
        plan.hiveRitualYaw = (float) Math.toDegrees(Math.atan2(-Math.cos(f), Math.sin(f)));
        // ノードは広間の方を向く(= 儀式中のプレイヤーと向かい合う)。
        plan.hiveNodeYaw = Mth.wrapDegrees(plan.hiveRitualYaw + 180.0F);
        plan.hiveEntrance = BlockPos.asLong(cx + layout.entrance[0], centerY + layout.entrance[1],
                cz + layout.entrance[2]);
        OvergrownVegetation.LOGGER.info("GiantTree at {}: 巣の球 半径 {} / 儀式の広間 {} (yaw {}) / 通路 {} 歩 / 出口 {} / 内張り {}",
                origin, radius, BlockPos.of(plan.hiveRitualCenter), plan.hiveRitualYaw, layout.tunnelSteps,
                BlockPos.of(plan.hiveEntrance), layout.liningCount);
    }

    /** 設定値 {@code base} に木の大きさ由来の倍率 {@code scale}(≥1)を掛けて丸める。 */
    private static int scaleCount(int base, double scale) {
        return base <= 0 ? base : (int) Math.round(base * scale);
    }

    private static int randomCount(RandomSource random, int min, int max) {
        int lo = Math.max(0, Math.min(min, max));
        int hi = Math.max(lo, Math.max(min, max));
        return lo + (hi > lo ? random.nextInt(hi - lo + 1) : 0);
    }

    /**
     * {@code nodeCount} 個の鉱石ノードを育てる。各ノードは {@code cambium} からランダムに選んだ中心の
     * まわり半径 {@code radius} の(縁をランダムに欠けさせた)球で、形成層({@code cambiumSet})に属し
     * かつ未取得({@code claimed})のセルだけを結果へ加える。取得済みセルは {@code claimed} に記録して
     * 鉱石同士が重ならないようにする。
     */
    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet growOreNodes(LongArrayList cambium,
            it.unimi.dsi.fastutil.longs.LongOpenHashSet cambiumSet,
            it.unimi.dsi.fastutil.longs.LongOpenHashSet claimed, RandomSource random, int nodeCount,
            double radius) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet result =
                new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (nodeCount <= 0 || cambium.isEmpty()) {
            return result;
        }
        double edgeSq = (radius + 0.5D) * (radius + 0.5D);
        // 球に入りうる最大のオフセット(半径は小数なので毎回求める)。
        int ir = Mth.floor(radius + 0.5D);
        for (int n = 0; n < nodeCount; n++) {
            long center = cambium.getLong(random.nextInt(cambium.size()));
            int cx = BlockPos.getX(center);
            int cy = BlockPos.getY(center);
            int cz = BlockPos.getZ(center);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dy = -ir; dy <= ir; dy++) {
                    for (int dz = -ir; dz <= ir; dz++) {
                        double dSq = dx * dx + dy * dy + dz * dz;
                        if (dSq > edgeSq) {
                            continue;
                        }
                        // 最外殻だけを軽く欠けさせて塊を不定形にする(中身はぎっしり詰める)。
                        if (dSq > edgeSq * 0.72D && random.nextFloat() < 0.3F) {
                            continue;
                        }
                        long k = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
                        if (cambiumSet.contains(k) && claimed.add(k)) {
                            result.add(k);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 巨大樹の全高を決める。{@code config.height()}(既定 120)を下限、その 2 倍(既定 240)を上限に
     * ランダムに選ぶ。ただし木のてっぺん(頂上の傘)がワールドの高度上限を突き破らないよう、
     * {@code origin} からの余裕でクランプする(高い山の上に生えると自動的に低くなる)。
     */
    private static int chooseHeight(WorldGenLevel level, RandomSource random, Config config, BlockPos origin) {
        int min = Math.max(16, config.height());
        int max = Math.max(min, min * 2);
        int chosen = max > min ? min + random.nextInt(max - min + 1) : min;
        // 頂上の傘のポッド上端は概ね origin.y + height - 1 + UMBRELLA_LIFT + ポッドの余白。
        int headroom = level.getMaxBuildHeight() - origin.getY() - UMBRELLA_LIFT - 8;
        return Math.min(chosen, Math.max(16, headroom));
    }

    /**
     * 幹の裾(地面)の半径。<b>木が高いほど根元を太くする</b>: {@code config.baseRadius()}(既定 11)を
     * 基準に、高さが {@code config.height()} の何倍かに応じて 1.0〜1.6 倍へ広げる
     * (既定の高さ → ×1.0、2 倍の高さ → ×1.6)。先端の半径 {@code topRadius} は変えないので、
     * 高い木ほど幹が下すぼまりの急なテーパーになる。
     */
    private static double baseRadiusFor(int height, Config config) {
        double raw = Math.max(config.topRadius() + 1, config.baseRadius());
        return raw * heightScaleFactor(height, config);
    }

    /**
     * 頂上の傘の横半径。幹の裾と同じ比率({@link #heightScaleFactor})で高さに比例させるので、
     * 高い木ほど傘も大きく広がる(細長いロリポップにならない)。傘を支える枝の到達距離・傘の縦の
     * 落差({@value #UMBRELLA_DROP_RATIO} 倍)・散らすポッドの数(半径の2乗に比例)がすべてこれに従う。
     */
    private static int crownRadiusFor(int height, Config config) {
        int raw = Math.max(8, config.crownRadius());
        return Math.max(8, (int) Math.round(raw * heightScaleFactor(height, config)));
    }

    /**
     * 高さ由来の拡大係数。高さが {@code config.height()}(基準)なら ×1.0、その 2 倍なら ×1.6。
     * 幹の裾の半径と傘の半径の両方に掛ける。
     */
    private static double heightScaleFactor(int height, Config config) {
        int baseline = Math.max(16, config.height());
        double heightScale = (double) height / baseline;
        return 0.4D + 0.6D * heightScale;
    }

    /** 地面からの高さ {@code dy} における幹の半径(円錐の線形補間)。 */
    private static double radiusAt(int dy, int height, Config config) {
        double baseR = baseRadiusFor(height, config);
        double topR = Math.max(1, config.topRadius());
        double t = height <= 1 ? 1.0D : Mth.clamp((double) dy / (height - 1.0D), 0.0D, 1.0D);
        return baseR + (topR - baseR) * t;
    }

    /** {@code (cx, cz)} を中心に半径 {@code radius}(+0.5)の円で切り出した水平な円盤(幹の1層)。 */
    private static void fillDisc(Plan plan, int cx, int y, int cz, double radius) {
        int r = (int) Math.ceil(radius);
        double edgeSq = (radius + 0.5D) * (radius + 0.5D);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((double) (dx * dx + dz * dz) > edgeSq) {
                    continue;
                }
                plan.trunk(cx + dx, y, cz + dz);
            }
        }
    }

    /**
     * 幹の裾(半径 {@code baseRadius} の円盤)の各列について、{@code origin} の1つ下から下方向へ
     * 「空気・置き換え可能ブロック」を埋め、最初の固体ブロックで止める(段差で幹が浮かないように)。
     */
    private static void fillTrunkFooting(Plan plan, BlockPos origin, double baseRadius) {
        int r = (int) Math.ceil(baseRadius);
        double edgeSq = (baseRadius + 0.5D) * (baseRadius + 0.5D);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((double) (dx * dx + dz * dz) > edgeSq) {
                    continue;
                }
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                for (int d = 1; d <= FOOTING_MAX_DEPTH; d++) {
                    int y = origin.getY() - d;
                    cursor.set(x, y, z);
                    BlockState below = plan.level.getBlockState(cursor);
                    if (!below.isAir() && !below.canBeReplaced()) {
                        break;
                    }
                    plan.trunk(x, y, z);
                }
            }
        }
    }

    /**
     * 幹の裾からほぼ等間隔の角度で {@code rootCount} 本の根を放射状に伸ばす。各根は
     * {@link #growRoot} が計算する(地表追従・うねり・アーチ・枝分かれ)。
     */
    private static void placeRoots(Plan plan, RandomSource random, BlockPos origin, int originGroundY,
            double baseRadius, Config config) {
        int count = Math.max(0, config.rootCount());
        if (count <= 0) {
            return;
        }
        int length = Math.max(1, config.rootLength());
        int maxThick = Math.max(1, config.rootThickness());
        Block trunkBlock = config.trunkState().getBlock();

        double innerStart = Math.max(1.0D, baseRadius - 3.0D);
        double baseAngle = random.nextDouble() * TAU;
        for (int i = 0; i < count; i++) {
            double jitter = (random.nextDouble() - 0.5D) * (TAU / count) * 0.55D;
            double angle = baseAngle + TAU * i / count + jitter;
            double fx = origin.getX() + 0.5D + Math.cos(angle) * innerStart;
            double fz = origin.getZ() + 0.5D + Math.sin(angle) * innerStart;
            growRoot(plan, random, origin, trunkBlock, baseRadius, fx, fz, originGroundY, angle,
                    length, maxThick, 0);
        }
    }

    /**
     * 1本の根を、幹から外向きに {@code length} 歩ぶん這わせる。
     *
     * <ul>
     *   <li><b>進行方向</b>: 毎歩ゆるやかに「幹の中心から見た外向き」へ寄せ直しつつ 1 マスずつ進む
     *       ので、枝分かれで大きく振られても最後は必ず幹から遠ざかる方向へ戻り、length ぶんの距離
     *       まで届く。加えて<b>横方向へ正弦波で揺らす</b>のでうねって見える。</li>
     *   <li><b>地表追従</b>: 毎歩 {@link #scanGroundY} で地面を探し、1歩あたりの下降
     *       {@value #ROOT_MAX_DROP_PER_STEP}・上昇 {@value #ROOT_MAX_RISE_PER_STEP} マスに制限。前の位置との
     *       縦の隙間(段差・崖)は {@link #fillRootColumn} で必ず埋める → 崖でも宙に浮かない。</li>
     *   <li><b>壁</b>: 登れない段差({@value #ROOT_MAX_RISE_PER_STEP} マス超)に当たったら<b>登らない</b>。
     *       高さを保ったまま前進して壁に潜り込む(薄ければ向こう側に出る)。崖下まで垂れた根が
     *       壁づたいに急に上を向くのを防ぐ。{@value #ROOT_WALL_BURROW_MAX} 歩潜っても抜けなければそこで終わる。</li>
     *   <li><b>吊り橋(上下逆のアーチ)</b>: 崖の縁({@value #ROOT_SPAN_MIN_DROP} マス以上の落差)で
     *       {@link #findOppositeBank} が進行方向 {@value #ROOT_SPAN_MAX_DIST} マス以内に同じくらいの高さの
     *       対岸を見つけたら、下まで這い下りずに<b>たわんだ曲線で一気に渡る</b>(両岸を結ぶ直線から
     *       sin カーブぶん垂れ下がる)。渡り切った1歩だけ対岸に接地させる。</li>
     *   <li><b>太さ</b>: 全長にわたり {@code maxThick·(1-t²)} でテーパー。幹の裾のすぐ外だけ
     *       控えめにバットレス状へ太くする(8 本が板状に固まらない範囲に抑える)。</li>
     *   <li><b>アーチ</b>: {@value #ROOT_ARCH_CHANCE} の確率で途中の一区間が正弦カーブで持ち上がる。
     *       土台は地表(gy)に沿わせるので坂の上でも浮かず、地面が無い区間だけ本当に橋としてまたぐ。</li>
     *   <li><b>枝分かれ</b>: 幹に近い側〜中盤({@code t} が 0.1〜0.72)で間隔を空けつつ、
     *       {@code ROOT_MAX_FORKS[depth]}({@code {2,1,0}}) 本まで・1歩ごと 25% で子根を分岐。分岐点から見た外向きを
     *       基準に大きく(±26〜77°)振って<b>横へ広げ</b>、親の進行方向も子と反対へ少し折って V 字に開く。
     *       長さ×0.55〜0.9・太さ-1 で {@code depth+1} で再帰。</li>
     * </ul>
     */
    private static void growRoot(Plan plan, RandomSource random, BlockPos origin, Block trunkBlock,
            double baseRadius, double fx, double fz, int startGy, double angle, int length, int maxThick,
            int depth) {
        int totalSteps = length + 12;
        int gy = startGy;
        int prevGy = gy;
        int visibleStep = 0;

        double ocx = origin.getX() + 0.5D;
        double ocz = origin.getZ() + 0.5D;

        // 進行方向は歩きながら変わる。毎歩「幹の中心から見た外向き」へ少しずつ寄せ直すので
        // (下の heading の補正)、枝分かれで大きく振られても幹から遠ざかる向きへ戻っていく。
        double heading = angle;
        double curX = fx;
        double curZ = fz;

        // 横揺れ: 速い波 + 遅いドリフトの重ね合わせ(周期的すぎないように)。単位はマス。
        double waveAmp = 1.2D + random.nextDouble() * 2.2D;
        double waveFreq = 0.09D + random.nextDouble() * 0.12D;
        double wavePhase = random.nextDouble() * TAU;
        double driftAmp = random.nextDouble() * 2.0D;
        double driftFreq = 0.02D + random.nextDouble() * 0.04D;

        boolean archy = random.nextFloat() < ROOT_ARCH_CHANCE;
        int archStart = 0;
        int archLen = 0;
        int archHeight = 0;
        // アーチの土台の高さ。地面が見つかる区間では地表(gy)に沿わせ、坂の上でも浮かないようにする。
        // 地面が見つからない区間(谷・空洞)だけこの値を保持して、本当の「橋」としてまたぐ。
        int archFloorGy = gy;
        if (archy) {
            archLen = 9 + random.nextInt(9);
            archHeight = 4 + random.nextInt(5);
            int lo = Math.max(1, (int) Math.round(length * 0.28D));
            int hi = Math.max(lo + 1, (int) Math.round(length * 0.82D) - archLen);
            archStart = lo + random.nextInt(Math.max(1, hi - lo));
        }

        int maxForks = depth < ROOT_MAX_FORKS.length ? ROOT_MAX_FORKS[depth] : 0;
        int forks = 0;
        int lastForkStep = -100;

        // 壁の中を水平に進んでいる連続歩数(ROOT_WALL_BURROW_MAX で打ち切る)。
        int wallSteps = 0;

        // 対岸へ渡る吊り橋(上下逆のアーチ)。spanLeft > 0 の間は地表追従をやめて曲線に乗る。
        int spanLeft = 0;
        int spanTotal = 0;
        int spanStartY = gy;
        int spanEndY = gy;
        int spanSag = 0;

        for (int step = 0; step < totalSteps; step++) {
            // 進行方向を「外向き」へゆるく寄せ直してから1マス進める。
            double radialOut = Math.atan2(curZ - ocz, curX - ocx);
            double toOut = Math.atan2(Math.sin(radialOut - heading), Math.cos(radialOut - heading));
            heading += 0.06D * toOut;
            double dirX = Math.cos(heading);
            double dirZ = Math.sin(heading);
            curX += dirX;
            curZ += dirZ;

            // 進行方向に対する横方向へ正弦波オフセット(うねり)。
            double off = waveAmp * Math.sin(wavePhase + step * waveFreq)
                    + driftAmp * Math.sin(step * driftFreq);
            if (spanLeft > 0) {
                // 吊り橋の区間は横揺れを抑える(狙った対岸から横に逸れて着地しないように)。
                off *= 0.3D;
            }
            double px = curX + (-dirZ) * off;
            double pz = curZ + dirX * off;
            int ix = Mth.floor(px);
            int iz = Mth.floor(pz);

            double distFromCenter = Math.sqrt(
                    (ix - origin.getX()) * (double) (ix - origin.getX())
                            + (iz - origin.getZ()) * (double) (iz - origin.getZ()));

            if (!chunkLoaded(plan.level, new BlockPos(ix, gy, iz))) {
                break;
            }
            if (distFromCenter <= baseRadius) {
                continue;
            }

            double t = (double) visibleStep / length;
            if (t > 1.0D) {
                break;
            }

            int rad = Math.max(0, (int) Math.round(maxThick * (1.0D - t * t) + 0.15D));
            // 幹の裾のすぐ外だけ、控えめにバットレス状へ太くする。8 本が幹まわりで板状に
            // 固まらないよう、旧実装より張り出しの範囲・量ともに大きく抑えている。
            double flareDist = baseRadius * 2.2D;
            if (distFromCenter < flareDist) {
                double f = 1.0D - distFromCenter / flareDist;
                rad += (int) Math.round(maxThick * 1.5D * Math.pow(f, 1.3D));
            }

            boolean inArch = archy && visibleStep >= archStart && visibleStep <= archStart + archLen;

            // 地表追従(見つからなければ少しずつ沈む)。1歩あたりの下降は制限、登れない段差は「壁」。
            int found = Integer.MIN_VALUE;
            if (spanLeft <= 0) {
                found = scanGroundY(plan.level, ix, gy, iz, trunkBlock);

                // 崖の縁に来た。進行方向に「対岸」があれば、下まで這い下りずに
                // 上下逆のアーチ(たわんだ吊り橋)で一気に渡る。
                if (!inArch && (found == Integer.MIN_VALUE || found <= gy - ROOT_SPAN_MIN_DROP)) {
                    BankHit bank = findOppositeBank(plan.level, trunkBlock, px, pz, dirX, dirZ, gy);
                    if (bank != null && step + bank.dist() < totalSteps
                            && visibleStep + bank.dist() <= length) {
                        spanTotal = bank.dist();
                        spanLeft = spanTotal;
                        spanStartY = gy;
                        spanEndY = bank.groundY();
                        spanSag = Mth.clamp((int) Math.round(spanTotal * ROOT_SPAN_SAG_RATIO),
                                ROOT_SPAN_SAG_MIN, ROOT_SPAN_SAG_MAX);
                        // 吊り橋の区間は通常の(持ち上げる)アーチとは重ねない。
                        archy = false;
                        wallSteps = 0;
                    }
                }
            }

            if (spanLeft > 0) {
                // 吊り橋: 両岸の高さを結ぶ直線から sin カーブぶん<b>垂れ下がる</b>。
                // 途中は地面が無いので丸い断面(fillBlob)で、最後の1歩だけ対岸に接地させる。
                int done = spanTotal - spanLeft + 1;
                double u = (double) done / spanTotal;
                int y = (int) Math.round(Mth.lerp(u, spanStartY, spanEndY)
                        - spanSag * Math.sin(Math.PI * u));
                int lo = Math.min(prevGy, y);
                int hi = Math.max(prevGy, y);
                if (hi - lo >= 2) {
                    fillRootColumn(plan, ix, iz, lo, hi, Math.max(1, rad - 1));
                }
                spanLeft--;
                if (spanLeft <= 0) {
                    gy = spanEndY;
                    fillRootMound(plan, ix, y, iz, Math.max(1, rad));
                } else {
                    fillBlob(plan, ix, y, iz, Math.max(1, rad));
                }
                prevGy = y;
                visibleStep++;
                continue;
            }

            boolean wall = false;
            if (found == Integer.MIN_VALUE) {
                gy -= ROOT_MAX_DROP_PER_STEP;
            } else if (found > gy + ROOT_MAX_RISE_PER_STEP) {
                // 壁にぶつかった。登ると「急に上向き」になってしまうので、高さを保ったまま
                // そのまま前進する(薄い壁なら貫いて向こう側に出る)。
                wall = true;
            } else if (found < gy - ROOT_MAX_DROP_PER_STEP) {
                gy -= ROOT_MAX_DROP_PER_STEP;
            } else {
                gy = found;
            }

            if (!wall) {
                wallSteps = 0;
            } else if (++wallSteps > ROOT_WALL_BURROW_MAX) {
                // 山の中を延々掘らない(薄い壁を貫けなかった場合はここで終わり)。
                break;
            }

            if (wall) {
                // 壁の中・壁沿いを水平に進む区間。かまぼこ状だと地中で角張るので丸い断面で。
                int lo = Math.min(prevGy, gy);
                int hi = Math.max(prevGy, gy);
                if (hi - lo >= 2) {
                    fillRootColumn(plan, ix, iz, lo, hi, Math.max(1, rad - 1));
                }
                fillBlob(plan, ix, gy, iz, Math.max(1, rad));
                prevGy = gy;
            } else if (inArch) {
                // アーチ: 正弦カーブで持ち上げた橋。土台の高さ(archFloorGy)は地面があれば地表に
                // 沿わせるので、坂の上に架けても浮かず「地面と平行にアーチする」。地面が無い区間
                // (谷・空洞)だけ土台を保持して本当の橋としてまたぐ。
                if (visibleStep == archStart) {
                    archFloorGy = prevGy;
                }
                if (found != Integer.MIN_VALUE) {
                    archFloorGy = gy;
                }
                int lift = (int) Math.round(archHeight
                        * Math.sin(Math.PI * (visibleStep - archStart) / archLen));
                int y = archFloorGy + lift;
                // 急な坂で前のアーチ位置と縦に離れたら、丸い断面のつなぎで結んで途切れさせない。
                if (Math.abs(y - prevGy) >= 2) {
                    int lo = Math.min(prevGy, y);
                    int hi = Math.max(prevGy, y);
                    fillRootColumn(plan, ix, iz, lo, hi, Math.max(1, rad - 1));
                }
                if (lift <= 1 && found != Integer.MIN_VALUE) {
                    // 端(持ち上げが小さい)は地面に接地させる。
                    fillRootMound(plan, ix, y, iz, Math.max(1, rad));
                } else {
                    fillBlob(plan, ix, y, iz, Math.max(1, rad));
                }
                prevGy = y;
            } else if (found == Integer.MIN_VALUE) {
                // 地面が無い(崖・空洞)。垂れ下がる部分は<b>角のない球</b>で下り、前の位置とは
                // 丸い断面の縦つなぎで結ぶ(かまぼこの積み重ねだと真四角の柱になってしまうため)。
                int lo = Math.min(prevGy, gy);
                int hi = Math.max(prevGy, gy);
                if (hi - lo >= 2) {
                    fillRootColumn(plan, ix, iz, lo, hi, Math.max(1, rad - 1));
                }
                fillBlob(plan, ix, gy, iz, Math.max(1, rad));
                prevGy = gy;
            } else {
                // 段差・崖の着地: 前の位置との縦の隙間(丸い断面)を埋めてから、かまぼこ状の盛り上がり。
                int lo = Math.min(prevGy, gy);
                int hi = Math.max(prevGy, gy);
                if (hi - lo >= 2) {
                    fillRootColumn(plan, ix, iz, lo - 1, hi, Math.max(1, rad - 1));
                }
                // 横揺れで1歩の移動が1マスを少し超えるので、断面は最低でも半径1にして連続させる。
                fillRootMound(plan, ix, gy, iz, Math.max(1, rad));
                prevGy = gy;
            }

            // この歩の足元(地表)を記録しておく。地面のあるかまぼこ区間だけ — アーチや谷を
            // またぐ区間には「根の上の地面」に相当するものが無い。あとで装飾フェーズが
            // ここから上へ盛り上がりの頂上を探して下草を生やす。
            if (!inArch && !wall && found != Integer.MIN_VALUE) {
                plan.rootColumns.add(BlockPos.asLong(ix, gy, iz));
            }

            // 枝分かれ(アーチ中は分岐しない)。幹に近い側〜中盤で、間隔を空けて分岐する
            // (先端に寄りすぎないように)。子根は分岐点から見た外向きを基準に大きく振って横へ広げ、
            // 親も子と反対へ少し折れて V 字に開く(その後は heading の外向き補正で徐々に外を向く)。
            if (forks < maxForks && !inArch && !wall && t >= 0.1D && t <= 0.72D
                    && step - lastForkStep >= 16 && random.nextFloat() < 0.25F) {
                forks++;
                lastForkStep = step;
                double sign = random.nextBoolean() ? 1.0D : -1.0D;
                double turn = sign * (Math.PI / 7.0D + random.nextDouble() * (Math.PI / 3.5D));
                double outAngle = Math.atan2(pz - ocz, px - ocx);
                int childLen = Math.max(6, (int) Math.round(length * (0.55D + random.nextDouble() * 0.35D)));
                int childThick = Math.max(1, maxThick - 1);
                growRoot(plan, random, origin, trunkBlock, baseRadius, px, pz, gy, outAngle + turn,
                        childLen, childThick, depth + 1);
                heading -= sign * (Math.abs(turn) * 0.45D);
            }

            visibleStep++;
        }
    }

    /**
     * 根の地表探索が「地面ではない」とみなして貫通するブロック。幹自身の原木({@code ownTrunk})に
     * 加えて、<b>既存の木の原木・葉</b>(過繁茂した木・バニラの木の両方)を含める → 根が近くの木を
     * 乗り越えたり葉に潜り込んだりせず、無視してその下の地面を這う。
     */
    private static boolean rootPassesThrough(BlockState state, Block ownTrunk) {
        return state.is(ownTrunk)
                || state.is(ModBlocks.OVERGROWN_LOG.get())
                || state.is(ModBlocks.GIANT_OVERGROWN_LOG.get())
                || state.is(ModBlocks.GIANT_OVERGROWN_CAMBIUM.get())
                || state.is(ModBlocks.OVERGROWN_LEAVES.get())
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES);
    }

    /** {@link #findOppositeBank} の結果: 対岸までの水平距離と、その対岸の地表の高さ。 */
    private record BankHit(int dist, int groundY) {
    }

    /**
     * 崖の縁に来た根が「対岸」を探す。今いる場所({@code px, pz})から進行方向
     * ({@code dirX, dirZ})へ {@value #ROOT_SPAN_MIN_DIST}〜{@value #ROOT_SPAN_MAX_DIST} マス先までを
     * 1マスずつ調べ、足元({@code gy})から上下 {@value #ROOT_SPAN_BANK_UP}/{@value #ROOT_SPAN_BANK_DOWN}
     * マス以内に地表がある最初の列を対岸として返す。
     *
     * <p>手前の列は「その高さに地面が無い」ことが確認できた列なので、返ってくる距離は
     * そのまま<b>谷・川・裂け目の幅</b>になる。見つからなければ {@code null}(=従来どおり崖を這い下りる)。
     * 未ロードのチャンクに当たった時点でも {@code null}(橋の途中で生成が切れないように)。
     */
    private static BankHit findOppositeBank(WorldGenLevel level, Block trunkBlock,
            double px, double pz, double dirX, double dirZ, int gy) {
        for (int d = ROOT_SPAN_MIN_DIST; d <= ROOT_SPAN_MAX_DIST; d++) {
            int x = Mth.floor(px + dirX * d);
            int z = Mth.floor(pz + dirZ * d);
            if (!chunkLoaded(level, new BlockPos(x, gy, z))) {
                return null;
            }
            int y = scanGroundY(level, x, gy, z, ROOT_SPAN_BANK_UP, ROOT_SPAN_BANK_DOWN, trunkBlock);
            if (y != Integer.MIN_VALUE) {
                return new BankHit(d, y);
            }
        }
        return null;
    }

    /**
     * {@code (x, z)} の列で、{@code centerY}(上 {@value #ROOT_SEARCH_UP} / 下
     * {@value #ROOT_SEARCH_DOWN})の範囲のうち一番高い「空気でも置き換え可能でもなく、かつ
     * {@link #rootPassesThrough}(=幹自身や既存の木の原木・葉)でもないブロック」の Y を返す。
     * 見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int scanGroundY(WorldGenLevel level, int x, int centerY, int z, Block ignore) {
        return scanGroundY(level, x, centerY, z, ROOT_SEARCH_UP, ROOT_SEARCH_DOWN, ignore);
    }

    /** {@link #scanGroundY(WorldGenLevel, int, int, int, Block)} の探索範囲を指定できる版(対岸探し用)。 */
    private static int scanGroundY(WorldGenLevel level, int x, int centerY, int z, int up, int down,
            Block ignore) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int top = centerY + up;
        int bottom = centerY - down;
        for (int y = top; y >= bottom; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.isAir() && !state.canBeReplaced() && !rootPassesThrough(state, ignore)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** {@link #scanGroundY} と同じだが、見つからなければ {@code centerY} をそのまま返す(木の根本の基準用)。 */
    private static int findGroundY(WorldGenLevel level, int x, int centerY, int z, Block ignore) {
        int r = scanGroundY(level, x, centerY, z, ignore);
        return r != Integer.MIN_VALUE ? r : centerY;
    }

    /**
     * {@code (cx, cz)} の列を {@code fromY}〜{@code toY} まで、水平半径 {@code radius} の<b>角を落とした
     * 円形断面</b>({@link #fillBlob} と同じ {@code (r+0.35)²} 判定)で埋める(段差・崖・垂れ下がりのつなぎ)。
     */
    private static void fillRootColumn(Plan plan, int cx, int cz, int fromY, int toY, int radius) {
        int r = Math.max(0, radius);
        double edgeSq = (r + 0.35D) * (r + 0.35D);
        for (int y = fromY; y <= toY; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dz * dz) > edgeSq) {
                        continue;
                    }
                    plan.trunk(cx + dx, y, cz + dz);
                }
            }
        }
    }

    /**
     * 頂上まわりを計算する:
     * <ol>
     *   <li>幹の全高にわたる細い枝({@link #growBranch} — ランダムな高さから外へ立ち上がる弧。
     *       中盤以降で {@value #BRANCH_MAX_DEPTH} 段まで再帰的に枝分かれし、途中・枝先に葉の房)。</li>
     *   <li>{@link #placeCanopyBranches} — 傘を下から支える太い枝(こちらも中盤で枝分かれ)。</li>
     *   <li>{@link #placeUmbrella} — 平たい葉のポッドを数十個散らした頂上の傘 + 幹の先端の葉キャップ。</li>
     * </ol>
     */
    private static void placeCrown(Plan plan, RandomSource random, BlockPos origin, int height, Config config) {
        int leafR = Math.max(2, config.foliageRadius());
        int branchThick = Math.max(1, config.branchThickness());

        int minCount = Math.max(1, Math.min(config.branchMinCount(), config.branchMaxCount()));
        int maxCount = Math.max(minCount, Math.max(config.branchMinCount(), config.branchMaxCount()));
        int count = minCount >= maxCount ? minCount : minCount + random.nextInt(maxCount - minCount + 1);

        // 細い枝は傘の領域には食い込ませない(傘は placeUmbrella が作る)。
        int minDy = Math.min(BRANCH_MIN_DY, height - 6);
        int maxDy = Math.max(minDy + 1, height - 4 - (int) Math.round(crownRadiusFor(height, config) * UMBRELLA_DROP_RATIO));

        for (int i = 0; i < count; i++) {
            int startDy = minDy + random.nextInt(maxDy - minDy + 1);
            double sy = origin.getY() + startDy;
            double sTrunkR = radiusAt(startDy, height, config);
            double angle = random.nextDouble() * TAU;

            double heightFrac = Mth.clamp((double) startDy / height, 0.0D, 1.0D);
            int length = (int) Math.round(Mth.lerp(heightFrac, 16.0D, 7.0D)) + random.nextInt(5);
            double rise = 0.7D + random.nextDouble() * 0.6D;

            double fx = origin.getX() + 0.5D + Math.cos(angle) * sTrunkR;
            double fz = origin.getZ() + 0.5D + Math.sin(angle) * sTrunkR;
            growBranch(plan, random, leafR, fx, sy, fz, angle, rise, length, branchThick, 0);
        }

        placeCanopyBranches(plan, random, origin, height, config, branchThick);
        placeUmbrella(plan, random, origin, height, config);
    }

    /**
     * 1本の細い枝を、水平角 {@code ang}・1歩あたりの上昇 {@code rise}(1歩ごとに 0.78 倍に減衰)で
     * {@code length} 歩ぶん外へ立ち上げる。歩きながら:
     * <ul>
     *   <li>{@link #fillBlob} で枝の原木(半径は {@code thickness} から先端で1までテーパー)。</li>
     *   <li>中盤(p≥0.3)以降 {@code step%2} ごとに小さな葉の房。</li>
     *   <li>{@code depth < }{@value #BRANCH_MAX_DEPTH} なら、中盤で {@link #BRANCH_MAX_FORKS} 本まで
     *       子枝を分岐({@code ang} を ±36〜81°振り、長さ・太さを縮め、上昇を強めて {@code depth+1} で
     *       再帰)。</li>
     *   <li>先端に葉の房。</li>
     * </ul>
     * ワールドのブロック状態は一切読まない({@link Plan} のメモリ上集合だけで重なり回避)。
     */
    private static void growBranch(Plan plan, RandomSource random, int leafR, double fx, double fy, double fz,
            double ang, double rise, double length, double thickness, int depth) {
        int steps = Math.max(2, (int) Math.round(length));
        double dirX = Math.cos(ang);
        double dirZ = Math.sin(ang);
        int maxForks = depth < BRANCH_MAX_FORKS.length ? BRANCH_MAX_FORKS[depth] : 0;
        int forks = 0;

        int lastX = Mth.floor(fx);
        int lastY = Mth.floor(fy);
        int lastZ = Mth.floor(fz);
        for (int step = 1; step <= steps; step++) {
            fx += dirX;
            fz += dirZ;
            fy += rise;
            rise *= 0.78D;
            int x = Mth.floor(fx);
            int y = Mth.floor(fy);
            int z = Mth.floor(fz);
            double p = (double) step / steps;
            int rad = Math.max(1, (int) Math.round(thickness * Math.pow(1.0D - p, 0.5D) + 0.2D));
            fillBlob(plan, x, y, z, rad);
            if (p >= 0.3D && step % 2 == 0) {
                int mrh = 2 + random.nextInt(2);
                flatLeafBlob(plan, random, x, y, z, mrh, Math.max(1, mrh - 1));
            }
            lastX = x;
            lastY = y;
            lastZ = z;

            if (forks < maxForks && p >= 0.32D && p <= 0.82D && step % 3 == 0
                    && random.nextFloat() < 0.6F) {
                forks++;
                double turn = (random.nextBoolean() ? 1.0D : -1.0D)
                        * (Math.PI / 5.0D + random.nextDouble() * (Math.PI / 4.0D));
                double childRise = Math.max(0.2D, rise + 0.3D + random.nextDouble() * 0.35D);
                double childLen = length * (0.45D + random.nextDouble() * 0.22D);
                double childThick = Math.max(1.0D, thickness * 0.62D);
                growBranch(plan, random, leafR, fx, fy, fz, ang + turn, childRise, childLen,
                        childThick, depth + 1);
            }
        }

        int rh = Math.max(3, leafR - 2 + random.nextInt(3));
        int rv = Math.max(2, (int) Math.round(rh * 0.4D));
        flatLeafBlob(plan, random, lastX, lastY + rv - 1, lastZ, rh, rv);
    }

    /**
     * 頂上の傘を下から支える太い枝。幹の上部(先端から高さの約30%以内)を起点に、{@code crownRadius}
     * の 0.55〜1.0 倍まで外へ伸び、傘の内側の輪郭(ピークから縁へ {@link #UMBRELLA_DROP_RATIO} で
     * 下がるドーム)に沿って少し反りながら立ち上がる。枝の中盤以降と枝先に葉のポッドを付けて、
     * 傘の縁の葉と枝がつながって見えるようにする。
     */
    private static void placeCanopyBranches(Plan plan, RandomSource random, BlockPos origin, int height,
            Config config, int branchThick) {
        int rh = crownRadiusFor(height, config);
        int leafR = Math.max(2, config.foliageRadius());
        int cx = origin.getX();
        int cz = origin.getZ();
        int peakY = origin.getY() + height - 1 + UMBRELLA_LIFT;
        double domeDrop = rh * UMBRELLA_DROP_RATIO;

        int count = 14 + (int) Math.round(rh * 0.42D) + random.nextInt(8);
        double baseAngle = random.nextDouble() * TAU;
        int upperSpan = Math.max(1, (int) Math.round(height * 0.30D));

        for (int i = 0; i < count; i++) {
            double ang = baseAngle + TAU * i / count + (random.nextDouble() - 0.5D) * (TAU / count) * 0.7D;
            double dirX = Math.cos(ang);
            double dirZ = Math.sin(ang);
            double reach = rh * (0.55D + random.nextDouble() * 0.45D);

            int startDy = Math.max(1, height - 4 - random.nextInt(upperSpan));
            double sy = origin.getY() + startDy;
            double sr = radiusAt(startDy, height, config);

            double frac = Mth.clamp(reach / rh, 0.0D, 1.0D);
            double endY = peakY - domeDrop * Math.pow(frac, 1.6D) - 2.0D;

            int steps = Math.max(4, (int) Math.round(reach));
            int forks = 0;
            for (int step = 1; step <= steps; step++) {
                double t = (double) step / steps;
                double horiz = Mth.lerp(t, sr, reach);
                double ease = t * t * (3.0D - 2.0D * t);
                double y = Mth.lerp(ease, sy, endY) + Math.sin(Math.PI * t) * 2.0D;
                int x = Mth.floor(cx + 0.5D + dirX * horiz);
                int z = Mth.floor(cz + 0.5D + dirZ * horiz);
                int rad = Math.max(1, (int) Math.round(branchThick * (1.0D - 0.6D * t)));
                fillBlob(plan, x, Mth.floor(y), z, rad);
                // 枝の中盤以降は、途中からも葉のポッドを生やす(先端だけにしない)。
                if (t >= 0.45D && step % 6 == 0) {
                    leafPod(plan, random, x, Mth.floor(y) + 1, z);
                }
                // 中盤で子枝を分岐させる(外へ振って、傘の下面へ向けて立ち上げる)。
                if (forks < 2 && t >= 0.4D && t <= 0.8D && step % 4 == 0 && random.nextFloat() < 0.55F) {
                    forks++;
                    double turn = (random.nextBoolean() ? 1.0D : -1.0D)
                            * (Math.PI / 6.0D + random.nextDouble() * (Math.PI / 5.0D));
                    double childLen = (steps - step) * (0.55D + random.nextDouble() * 0.3D);
                    double childRise = 0.6D + random.nextDouble() * 0.5D;
                    double childThick = Math.max(1.0D, branchThick * 0.6D);
                    growBranch(plan, random, leafR, cx + 0.5D + dirX * horiz, y, cz + 0.5D + dirZ * horiz,
                            ang + turn, childRise, childLen, childThick, 1);
                }
            }

            int ex = Mth.floor(cx + 0.5D + dirX * reach);
            int ez = Mth.floor(cz + 0.5D + dirZ * reach);
            int ey = (int) Math.round(endY);
            leafPod(plan, random, ex, ey + 1, ez);
            leafPod(plan, random, ex + random.nextInt(3) - 1, ey + 2, ez + random.nextInt(3) - 1);
        }
    }

    /**
     * 頂上の傘本体。幹の先端より {@value #UMBRELLA_LIFT} マス上をピークに、{@code crownRadius} まで
     * 外周へ向かってなだらかに下がる浅いドーム面に沿って、平たい葉のポッド
     * ({@link #leafPod} — 縦横 ≒ {@code 2·}{@value #POD_RADIUS_H}、高さ ≒ 3)を数十個散らす。
     *
     * <p>1つの滑らかな曲面ではなく多数のポッドの重なりなので、外縁部が「円柱をすぱっと切ったよう」に
     * ならない。ポッドの中心 Y を上下に散らして重ねることで傘に厚み(見た目 5〜8マス)が出る。
     * 最後に {@link #capTrunkTop} で露出しがちな幹の先端に葉のキャップを被せる。
     */
    private static void placeUmbrella(Plan plan, RandomSource random, BlockPos origin, int height, Config config) {
        int rh = crownRadiusFor(height, config);
        int cx = origin.getX();
        int cz = origin.getZ();
        int trunkTopY = origin.getY() + height - 1;
        int peakY = trunkTopY + UMBRELLA_LIFT;
        double domeDrop = rh * UMBRELLA_DROP_RATIO;

        // ポッド数は面積(rh²)に比例させる。crown_radius を大きくしても密度が薄まって穴が開かない。
        int podCount = (int) Math.round(rh * rh * 0.17D)
                + random.nextInt((int) Math.round(rh * rh * 0.06D) + 1);
        for (int i = 0; i < podCount; i++) {
            double along = Math.pow(random.nextDouble(), 0.55D);
            double horiz = rh * along * (0.9D + random.nextDouble() * 0.28D);
            double ang = random.nextDouble() * TAU;
            int px = Mth.floor(cx + 0.5D + Math.cos(ang) * horiz);
            int pz = Mth.floor(cz + 0.5D + Math.sin(ang) * horiz);

            double frac = Mth.clamp(horiz / rh, 0.0D, 1.0D);
            double baseY = peakY - domeDrop * Math.pow(frac, 1.6D);
            int py = (int) Math.round(baseY + (random.nextDouble() - 0.5D) * 4.4D);
            leafPod(plan, random, px, py, pz);
        }

        // 頂点付近をさらに厚く(密度アップ + 幹の露出隠しの二重掛け)。
        int apexPods = 8 + (int) Math.round(rh * 0.9D);
        for (int i = 0; i < apexPods; i++) {
            double ang = random.nextDouble() * TAU;
            double d = Math.sqrt(random.nextDouble()) * rh * 0.42D;
            int px = Mth.floor(cx + 0.5D + Math.cos(ang) * d);
            int pz = Mth.floor(cz + 0.5D + Math.sin(ang) * d);
            double frac = d / rh;
            double baseY = peakY - domeDrop * Math.pow(frac, 1.6D);
            int py = (int) Math.round(baseY + (random.nextDouble() - 0.2D) * 3.5D);
            leafPod(plan, random, px, py, pz);
        }

        capTrunkTop(plan, random, cx, cz, trunkTopY - 3, peakY + 2, 6);
    }

    /**
     * 平たい葉のポッド1個(縦横 ≒ {@code 2·}{@value #POD_RADIUS_H}、高さ ≒ {@code 2·}{@value #POD_RADIUS_V}
     * {@code +1} = 3)。{@link #flatLeafBlob} を小さめ固定サイズ(水平だけ少しランダム)で呼ぶだけ。
     */
    private static void leafPod(Plan plan, RandomSource random, int cx, int cy, int cz) {
        int ph = POD_RADIUS_H + random.nextInt(2);
        flatLeafBlob(plan, random, cx, cy, cz, ph, POD_RADIUS_V);
    }

    /**
     * 幹の先端まわりを葉で塞ぐ小さな柱状のキャップ。半径 {@code radius}(既定5 — 傘全体より十分
     * 小さいので「円柱の切り口」には見えない)の円柱に、縁を軽く揺らしながら葉を詰める。幹が傘の
     * 上に飛び出して見えるのを防ぐためだけの補助。
     */
    private static void capTrunkTop(Plan plan, RandomSource random, int cx, int cz, int fromY, int toY,
            int radius) {
        double edgeSq = (radius + 0.5D) * (radius + 0.5D);
        for (int y = fromY; y <= toY; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = dx * dx + dz * dz;
                    if (d > edgeSq) {
                        continue;
                    }
                    if (d > edgeSq * 0.5D && random.nextFloat() < 0.4F) {
                        continue;
                    }
                    plan.leaf(cx + dx, y, cz + dz);
                }
            }
        }
    }

    /**
     * 水平半径 {@code rh} / 垂直半径 {@code rv} の扁平な回転楕円体(葉の房)。縁付近をランダムに
     * 間引いて輪郭をギザギザにする。幹・枝には上書きしない({@link Plan#leaf} が内部で判定)。
     */
    private static void flatLeafBlob(Plan plan, RandomSource random, int cx, int cy, int cz, int rh, int rv) {
        int h = Math.max(1, rh);
        int v = Math.max(1, rv);
        double hSq = (h + 0.5D) * (h + 0.5D);
        double vSq = (v + 0.5D) * (v + 0.5D);
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                for (int dy = -v; dy <= v; dy++) {
                    double e = (dx * dx + dz * dz) / hSq + (dy * (double) dy) / vSq;
                    if (e > 1.0D) {
                        continue;
                    }
                    if (e > 0.55D && random.nextFloat() < 0.45D) {
                        continue;
                    }
                    plan.leaf(cx + dx, cy + dy, cz + dz);
                }
            }
        }
    }

    /** {@code (cx, cy, cz)} を中心とした半径 {@code radius}(+0.35)の球(幹・枝)。 */
    private static void fillBlob(Plan plan, int cx, int cy, int cz, int radius) {
        if (radius <= 0) {
            plan.trunk(cx, cy, cz);
            return;
        }
        double edgeSq = (radius + 0.35D) * (radius + 0.35D);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if ((double) (dx * dx + dy * dy + dz * dz) > edgeSq) {
                        continue;
                    }
                    plan.trunk(cx + dx, cy + dy, cz + dz);
                }
            }
        }
    }

    /**
     * 地表({@code gy})に沿ったかまぼこ状の根の断面。水平半径 {@code radius} の円内で、下端は
     * {@code gy - 1}(地中1マスで固定 = アンカー)、上端は円弧({@code gy + √(radius² - 水平距離²)})まで。
     * 地中に潜り込まず地上の表面を這う形になる。
     */
    private static void fillRootMound(Plan plan, int cx, int gy, int cz, int radius) {
        if (radius <= 0) {
            plan.trunk(cx, gy, cz);
            plan.trunk(cx, gy - 1, cz);
            return;
        }
        double edgeSq = (radius + 0.5D) * (radius + 0.5D);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizSq = dx * dx + dz * dz;
                if (horizSq > edgeSq) {
                    continue;
                }
                int topDy = (int) Math.round(Math.sqrt(Math.max(0.0D, edgeSq - horizSq)));
                for (int dy = -1; dy <= topDy; dy++) {
                    plan.trunk(cx + dx, gy + dy, cz + dz);
                }
            }
        }
    }

    /** 苗木のランダムティック経由で呼ばれるので {@code level} は実際は ServerLevel。未ロード判定に使う。 */
    private static boolean chunkLoaded(WorldGenLevel level, BlockPos pos) {
        return level.getLevel().isLoaded(pos);
    }

    /**
     * @param trunkState       幹・枝・根に使うブロック状態(既定 {@code giant_overgrown_log[axis=y]})
     * @param leavesState      葉に使うブロック状態
     * @param height           木全体の高さ(ブロック数)
     * @param baseRadius       幹の裾(地面)の半径。直径は {@code baseRadius}*2+1
     * @param topRadius        幹の先端の半径。直径は {@code topRadius}*2+1
     * @param rootCount        幹の裾から放射状に伸ばす根の本数
     * @param rootLength       根1本あたりの長さ(ブロック数)
     * @param rootThickness    根のかまぼこ状断面の半径の最大値。中盤まで太いまま先端で細くなる。
     *                         下端は地表の1マス下で固定 = 地中に潜らず地表を這う
     * @param branchMinCount   細い枝の本数の最小値。地面から {@value #BRANCH_MIN_DY} マス以上の
     *                         ランダムな高さに散らす(傘の領域は避ける)
     * @param branchMaxCount   枝の本数の最大値(この範囲でランダムに決まる)
     * @param branchThickness  枝の断面(球)の半径の最大値。根本で最大、先端で1までテーパー。
     *                         傘を支える太い枝({@link #placeCanopyBranches})にも使う
     * @param crownDrop        現状未使用(旧・葉ドームの平らな底の位置。傘の落差は
     *                         {@link #UMBRELLA_DROP_RATIO} × {@code crownRadius} で決まる)
     * @param crownRadius      頂上の傘の横半径。葉のポッドを散らす範囲 + 傘を支える枝の到達距離 +
     *                         傘の縦の落差({@value #UMBRELLA_DROP_RATIO} 倍)がすべてこれに比例する
     * @param foliageRadius    枝先の小さな葉の房のおおよその大きさ
     * @param foliageHeight    現状未使用(将来の葉の形調整用に残してある)
     */
    public record Config(
            BlockState trunkState,
            BlockState leavesState,
            int height,
            int baseRadius,
            int topRadius,
            int rootCount,
            int rootLength,
            int rootThickness,
            int branchMinCount,
            int branchMaxCount,
            int branchThickness,
            int crownDrop,
            int crownRadius,
            int foliageRadius,
            int foliageHeight
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockState.CODEC.fieldOf("trunk_state").forGetter(Config::trunkState),
                        BlockState.CODEC.fieldOf("leaves_state").forGetter(Config::leavesState),
                        Codec.INT.optionalFieldOf("height", 120).forGetter(Config::height),
                        Codec.INT.optionalFieldOf("base_radius", 11).forGetter(Config::baseRadius),
                        Codec.INT.optionalFieldOf("top_radius", 2).forGetter(Config::topRadius),
                        Codec.INT.optionalFieldOf("root_count", 5).forGetter(Config::rootCount),
                        Codec.INT.optionalFieldOf("root_length", 35).forGetter(Config::rootLength),
                        Codec.INT.optionalFieldOf("root_thickness", 2).forGetter(Config::rootThickness),
                        Codec.INT.optionalFieldOf("branch_min_count", 26).forGetter(Config::branchMinCount),
                        Codec.INT.optionalFieldOf("branch_max_count", 40).forGetter(Config::branchMaxCount),
                        Codec.INT.optionalFieldOf("branch_thickness", 2).forGetter(Config::branchThickness),
                        Codec.INT.optionalFieldOf("crown_drop", 18).forGetter(Config::crownDrop),
                        Codec.INT.optionalFieldOf("crown_radius", 28).forGetter(Config::crownRadius),
                        Codec.INT.optionalFieldOf("foliage_radius", 7).forGetter(Config::foliageRadius),
                        Codec.INT.optionalFieldOf("foliage_height", 5).forGetter(Config::foliageHeight))
                .apply(instance, Config::new));
    }
}
