package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 *   <li><b>根</b>(2026-11-07 作り直し): 幹の裾が広がり({@link #flareAt})、{@code rootCount} を真ん中にばらつく本数の
 *       <b>板根</b>({@link #growButtress}。薄く背の高い板が外ほど低くなる)が出て、板の先から<b>地表の根</b>
 *       ({@link #growSurfaceRoot})が蛇行し、細くなり、だんだん埋もれて背中だけ出ては潜る。斜面では下り側へ長く・多く。
 *       面に沿って這い、縁では真下へ下り、空中では真下へ垂らす。構造物は壊さず上を覆う({@link Plan#root})。
 *       生えきったあと、根の頂上には汚染時と同じ下草が生える
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

    /** 根の枝分かれ: 各段が分岐させる子根の最大本数(段が深いほど少なく)。 */
    private static final int[] ROOT_MAX_FORKS = {2, 1, 0};

    // ---- 根の作り直し(2026-11-07)。どれも Claude の仮決め ----
    /** 幹の裾の広がり: 地面での半径の増え = 裾の半径 × これ。 */
    private static final double FLARE_RATIO = 0.12D; // 11-07 第 1 段の写真の後 0.3 → 0.12(板を見せるため控えめに)
    /** 裾が広がる高さ = 裾の半径 × これ(上ほど細く 2 乗で戻る)。 */
    private static final double FLARE_HEIGHT_RATIO = 0.6D;
    /** 坂の向きを見る方向の数と、幹の縁からの距離。 */
    private static final int SLOPE_SAMPLES = 16;
    private static final double SLOPE_PROBE = 14.0D;
    /** 板根の長さ(幹の縁から)= 幹の半径 × 0.8〜1.6、根元での高さ = 幹の半径 × 0.5〜0.9(11-07)。 */
    /** 板を立てる根の数(11-07 第 1 段の後: 全部の根 → 3〜5 枚)。 */
    private static final int BUTTRESS_COUNT_MIN = 3;
    private static final int BUTTRESS_COUNT_MAX = 5;
    private static final double BUTTRESS_LEN_MIN = 0.9D;
    private static final double BUTTRESS_LEN_MAX = 1.7D;
    private static final double BUTTRESS_H_MIN = 0.5D; // 11-07 ユーザー「高さを下げる」: × 1.0〜1.7 → 0.5〜0.9(仮決め)
    private static final double BUTTRESS_H_MAX = 0.9D;
    /** 板根の反り(外の端での向きの変わり、rad)の最大。 */
    private static final double BUTTRESS_CURL = 0.35D;
    /** 板根が 1 列で下がってよい段数(これより下がる縁で板を止める)。 */
    private static final int BUTTRESS_MAX_DROP = 3;
    /** 構造物の壁を面に沿って登る高さの上限(これより高ければ回り込む)。 */
    private static final int ROOT_CLIMB_MAX = 6;
    /** 縁から真下へ地面を探す深さ / 地面が無いとき垂らす長さ。 */
    private static final int ROOT_DROP_MAX = 160;
    private static final int ROOT_HANG_MAX = 12;
    /** 高い構造物を回り込むとき 1 回に曲げる角度(rad)と回数の上限。 */
    private static final double ROOT_TURN_STEP = 0.6D;
    private static final int ROOT_TURN_MAX = 6;
    /** 蛇行: 曲がり具合の揺れの強さ / 幹から外へ引く強さ。 */
    private static final double MEANDER = 0.07D;
    private static final double OUTWARD_PULL = 0.04D;
    /** 下り側へ寄せる: 左右の地面の差 1 段あたりの曲げ(rad)と 1 回の上限。 */
    private static final double DOWNHILL_GAIN = 0.05D;
    private static final double DOWNHILL_MAX = 0.2D;
    /** 埋もれ: 露出 = START − BURY × (根元からの割合) + WAVE × sin(出ては潜る波)。波の速さは 1 歩あたり FREQ。 */
    private static final double EXP_START = 1.0D;
    private static final double EXP_BURY = 0.9D;
    private static final double EXP_WAVE = 0.45D;
    private static final double EXP_FREQ_MIN = 0.05D;
    private static final double EXP_FREQ_MAX = 0.11D;

    // ---- 第 2 段(2026-11-07)。どれも Claude の仮決め ----
    /** アーチ: 根元の太さがこれ以上の根の {@value #ARCH_CHANCE} が、途中の一区間で背中を持ち上げる。 */
    private static final double ARCH_MIN_RAD = 1.2D;
    private static final float ARCH_CHANCE = 0.45F;
    /** アーチの長さ(歩)・持ち上げ(段。さらに根の太さ × 0.5 を足す)・前後で一度潜る歩数。 */
    private static final int ARCH_LEN_MIN = 9;
    private static final int ARCH_LEN_MAX = 16;
    private static final double ARCH_H_MIN = 2.5D;
    private static final double ARCH_H_MAX = 5.0D;
    private static final int ARCH_DIVE = 3;
    /** 先の細い根(半径これ未満)が露出これ未満なら置かない(地面と同じ高さの 1 マスの帯の段々を出さない)。 */
    private static final double THIN_FLUSH_RAD = 0.9D;
    private static final double THIN_FLUSH_EXP = 0.35D;
    /** 川・渓谷の渡り: 渡る確率、渓谷とみなす落差、川のたわみの上限、渓谷のたわみ(距離 × 比・上限)。 */
    private static final float SPAN_RIVER_CHANCE = 0.85F;
    private static final float SPAN_RAVINE_CHANCE = 0.6F;
    private static final int SPAN_RAVINE_MIN_DROP = 6;
    private static final double SPAN_RIVER_SAG_MAX = 2.0D;
    private static final double SPAN_RAVINE_SAG_RATIO = 0.3D;
    private static final double SPAN_RAVINE_SAG_MAX = 8.0D;
    /** 呼吸根: 湿った地面を通る 1 歩ごとの確率と、群れの間の歩数。 */
    private static final float KNEE_CHANCE = 0.25F;
    private static final int KNEE_COOLDOWN = 10;
    /** 地下の根: 地表の根の先・壁から続く長さ、見せる回数(浅い/深い)。 */
    private static final int UNDER_TAIL = 40;
    private static final int UNDER_EXPOSURES_SHALLOW = 2;
    private static final int UNDER_EXPOSURES_DEEP = 4;
    /** 深い根: 本数・長さ・傾き(始め → 終わり、rad。負 = 下り)。 */
    private static final int DEEP_COUNT_MIN = 5;
    private static final int DEEP_COUNT_MAX = 8;
    private static final int DEEP_LEN_MIN = 90;
    private static final int DEEP_LEN_MAX = 150;
    private static final double DEEP_PITCH_START = -0.6D;
    private static final double DEEP_PITCH_END = -0.15D;
    /** 洞窟: 天井から床までの柱にする高さの上限 / 壁から壁へ横切る長さの上限。 */
    private static final int CAVE_PILLAR_MAX = 40;
    // 洞窟の柱の形(2026-11-07 直し。数はすべて仮決め): 天井の出口と床の入口を水平にずらし(傾き)、途中をゆるい S 字にうねらせ、
    // 付け根を太らせて途中を細く・節で揺らす。高い柱はときどき途中で 2 本に分かれ、まわりに細い垂れ根を出す。
    private static final int PILLAR_LEAN_MIN = 2;
    private static final int PILLAR_LEAN_MAX = 6;
    /** 傾きの上限 = 高さ × これ(低い柱を寝かせすぎない)。 */
    private static final double PILLAR_LEAN_PER_HEIGHT = 0.6D;
    private static final double PILLAR_S_AMP_MIN = 0.6D;
    private static final double PILLAR_S_AMP_MAX = 1.8D;
    /** 付け根の張り(半径に足す)の幅と、張る範囲(高さの割合)。 */
    private static final double PILLAR_FLARE_MIN = 1.2D;
    private static final double PILLAR_FLARE_MAX = 2.4D;
    private static final double PILLAR_FLARE_SPAN = 0.22D;
    /** 節: この間隔(段)ごとに半径 +{@value #PILLAR_KNOT_BULGE}。 */
    private static final int PILLAR_KNOT_GAP_MIN = 4;
    private static final int PILLAR_KNOT_GAP_MAX = 7;
    private static final double PILLAR_KNOT_BULGE = 0.7D;
    /** 途中の太さ = 根の太さ − これ(1 未満にはしない)。 */
    private static final double PILLAR_MID_THIN = 0.6D;
    private static final double PILLAR_FORK_CHANCE = 0.35D;
    private static final int PILLAR_FORK_MIN_HEIGHT = 9;
    private static final double PILLAR_DRIP_CHANCE = 0.5D;
    // 相談 6 件の直し(2026-11-07。数はすべて仮決め。ユーザーが決めたのは「Fable のおすすめどおりに直す」こと)。
    /** 崖の切り口の根の束: 本数・太さ(半径)・左右の揺れ(S 字の振れ幅)。 */
    private static final int DRAPE_STRANDS_MIN = 2;
    private static final int DRAPE_STRANDS_MAX = 3;
    private static final double DRAPE_RAD_MIN = 1.2D;
    private static final double DRAPE_RAD_MAX = 1.6D;
    private static final double DRAPE_SWAY_MIN = 0.5D;
    private static final double DRAPE_SWAY_MAX = 1.2D;
    /** 空中に垂れる根: 先までに横へ寄る量(マス)と、付け根の太さに足す分。 */
    private static final double HANG_BEND_MIN = 1.5D;
    private static final double HANG_BEND_MAX = 3.5D;
    private static final double HANG_RAD_EXTRA = 0.6D;
    /** 洞窟の柱の付け根の天井から垂らす dangling_roots の房: 柱 1 本あたりの数と長さ。 */
    private static final int PILLAR_TUFTS_MIN = 2;
    private static final int PILLAR_TUFTS_MAX = 5;
    private static final int PILLAR_TUFT_LEN_MIN = 1;
    private static final int PILLAR_TUFT_LEN_MAX = 4;
    /** 小さな水(幅がこれ以下)を根が渡る確率。渡らない根は岸に呼吸根を出して岸沿いへ曲がる。 */
    private static final int SMALL_WATER_MAX = 6;
    private static final float SPAN_SMALL_WATER_CHANCE = 0.25F;
    private static final double SHORE_TURN = 0.9D;
    /** 板根: 上の縁の波(段)・波の長さ(マス)・通り道の蛇行(マス)・上 1/3 の欠け(縁の切れ込みの割合)。 */
    private static final double PLATE_WAVE_MIN = 1.0D;
    private static final double PLATE_WAVE_MAX = 2.0D;
    private static final double PLATE_WAVE_LEN_MIN = 5.0D;
    private static final double PLATE_WAVE_LEN_MAX = 9.0D;
    private static final double PLATE_WIGGLE_MIN = 0.8D;
    private static final double PLATE_WIGGLE_MAX = 2.0D;
    private static final float PLATE_NOTCH_CHANCE = 0.25F;
    private static final int CAVE_CROSS_MAX = 28;

    /** 幹の高さ {@code dy} での裾の広がり(半径への足し)。 */
    private static double flareAt(int dy, double baseRadius) {
        double h = baseRadius * FLARE_HEIGHT_RATIO;
        if (dy >= h) {
            return 0.0D;
        }
        double f = 1.0D - dy / h;
        return baseRadius * FLARE_RATIO * f * f;
    }

    /** 幹の裾の真下を埋める最大の深さ(崖際で無限に潜らないための安全上限)。 */
    private static final int FOOTING_MAX_DEPTH = 40; // 2026-11-07: 16 → 40(幹を地面まで降ろす。仮決め)

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
        return false; // TD は parked/ へ退避(2026-10-30)。戻すときは TdEvents.activeTarget(level) != null
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
                double radius = radiusAt(dy, height, config) + flareAt(dy, baseRadius); // 裾は広がる(板根の土台、2026-11-07)
                fillDisc(plan, origin.getX(), origin.getY() + dy, origin.getZ(), radius);
            }
            // 幹の裾の真下を地面まで埋める。
            fillTrunkFooting(plan, origin, baseRadius + flareAt(0, baseRadius));
            int trunkCells = plan.trunk.size();
            // 地表を這う太い根。
            placeRoots(plan, random, origin, originGroundY, baseRadius, config, true);
            plan.rootCells = plan.trunk.size() - trunkCells;
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
            // 巣の球は「自然の地面」の高さに置く(2026-11-07)。空中の足場・建物の屋根の上に生えた木では、
            // その下の地面まで下ろす(足場の高さに置くと下半分が宙に浮く)。
            buildHiveChamber(plan, random, level, origin, terrainGroundBelow(level, origin, originGroundY), baseRadius);
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
        OvergrownVegetation.LOGGER.info("GiantTree at {}: 根 {} マス(構造物を避けたマス {})/ 渡り {}・呼吸根 {}・洞窟の柱 {}・横切り {}・垂れ {}・切り口 {}",
                origin, plan.rootCells, plan.rootKept, plan.spans, plan.kneeClusters, plan.cavePillars,
                plan.caveCrossings, plan.caveHangs, plan.faceDrapes);
        OvergrownVegetation.LOGGER.info("GiantTree at {}: 根の見本 {}", origin, plan.samples);
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
        // 2026-11-18 大群の軽量化 B: 本物の巨大樹は幹 13 万・葉 20 万マスほどになり、小さく始めると何度も作り直し(再ハッシュ)が起きていた。
        // 初めから 26 万件ぶん取る(中身は出し入れだけで順には回さないので、形は変わらない)
        private final LongOpenHashSet trunkSet = new LongOpenHashSet(1 << 18);
        private final LongOpenHashSet leafSet = new LongOpenHashSet(1 << 18);
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
        /**
         * 洞窟の柱の付け根の天井から垂らす {@code dangling_roots} の房(2026-11-07 相談 6): 天井の真下の空気のマスと長さ。
         * 原木の並びとは別。{@link GiantTreeGrowthQueue} が全部置き終えた後に {@code CaveFlora.hangRoots} で垂らす。
         */
        final LongArrayList danglingTops = new LongArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList danglingLens = new it.unimi.dsi.fastutil.ints.IntArrayList();

        Plan(WorldGenLevel level, BlockState trunkState, BlockState leafState) {
            this.level = level;
            this.trunkState = trunkState;
            this.cambiumState = ModBlocks.GIANT_OVERGROWN_CAMBIUM.get().defaultBlockState();
            // 幹の中の鉱石(クロロフィライト・硬化樹液結晶)は装備の素材として 2026-10-30 に parked/ へ。その場所は形成層になる(ユーザー決定)
            this.chlorophylliteOreState = this.cambiumState;
            this.hardenedSapCrystalOreState = this.cambiumState;
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

        /** 根の数(計画の時点)と、構造物だったので置かなかったマスの数(重さ・壊さないの確かめ用)。 */
        int rootCells;
        int rootKept;
        /** 第 2 段の数(ログ・GameTest 用): 川・渓谷の渡り / 呼吸根の群れ / 洞窟の柱 / 洞窟の横切り / 垂れ(床が遠い)/ 切り口の垂れ。 */
        int spans;
        int kneeClusters;
        int cavePillars;
        int caveCrossings;
        int caveHangs;
        int faceDrapes;
        /** 種類ごとに最初の 3 か所の座標(撮影で狙うためのログ)。 */
        final java.util.Map<String, StringBuilder> samples = new java.util.TreeMap<>();

        void sample(String kind, int x, int y, int z) {
            StringBuilder b = samples.computeIfAbsent(kind, k -> new StringBuilder());
            if (b.length() < 60) {
                b.append(" (").append(x).append(' ').append(y).append(' ').append(z).append(')');
            }
        }
        private final BlockPos.MutableBlockPos rootCursor = new BlockPos.MutableBlockPos();

        /**
         * 根のマス(2026-11-07)。空気・置き換え可能・自然の地面({@link #isRootTerrain})・木の原木と葉の所だけ足す。
         * 構造物(それ以外の固いブロック)は壊さない(ユーザー決定 5「構造物は壊さず覆う」)。
         */
        void root(int x, int y, int z) {
            if (level.isOutsideBuildHeight(y) || trunkSet.contains(BlockPos.asLong(x, y, z))) {
                return;
            }
            BlockState s = level.getBlockState(rootCursor.set(x, y, z));
            if (s.isAir() || s.canBeReplaced() || isRootTerrain(s) || rootPassesThrough(s, trunkState.getBlock())) {
                trunk(x, y, z);
            } else {
                rootKept++;
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
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int keptStructure = 0;
        for (int dx = -o; dx <= o; dx++) {
            for (int dy = -o; dy <= o; dy++) {
                for (int dz = -o; dz <= o; dz++) {
                    byte kind = layout.get(dx, dy, dz);
                    if (kind == HiveChamberLayout.OUTSIDE) {
                        continue;
                    }
                    // 構造物は上書きも掘りもしない(根の関所 Plan.root と同じ線引き。2026-11-07)。
                    if (isStructure(level.getBlockState(probe.set(cx + dx, centerY + dy, cz + dz)),
                            plan.trunkState.getBlock())) {
                        keptStructure++;
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
        OvergrownVegetation.LOGGER.info("GiantTree at {}: 巣の球 半径 {} 中心 {} / 儀式の広間 {} (yaw {}) / 通路 {} 歩 / 出口 {} / 内張り {} / 避けた構造物 {}",
                origin, radius, BlockPos.of(plan.hiveCenter), BlockPos.of(plan.hiveRitualCenter), plan.hiveRitualYaw,
                layout.tunnelSteps, BlockPos.of(plan.hiveEntrance), layout.liningCount, keptStructure);
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

    // ======================= 根(2026-11-07 作り直し・第 1 段) =======================
    // Vault「巨大樹の根と根のアーチの作り直し」の決定 1〜5(ユーザー 11-06 夜)。
    // 1 板根(カポックの姿)/ 2 地表の根が蛇行し細くなり埋もれて背中だけ出す / 3 本数・長さ・向きのばらつき・斜面は下り側へ /
    // 4 固い面の上を這い、縁で折れて真下へ下り、空中では真下へ垂らす / 5 構造物は壊さず覆う(土・岩には潜ってよい)。
    // 数はすべて Claude の仮決め(ユーザー未確認)。アーチ・川の橋・呼吸根・洞窟と崖の根は第 2 段。

    /** 根の歩みの状態(1 本の木で共通)。 */
    private record RootCtx(Plan plan, RandomSource random, BlockPos origin, Block trunkBlock, double trunkR,
            int originGy, boolean stage2) {
    }

    /**
     * 幹の裾まわりの根をすべて計画する: 板根(各板の先が地表の根になる)・板の無い細い根・下り側へ足す根。
     * 本数は {@code rootCount} を真ん中に −3〜+1、長さは {@code rootLength} の 0.35〜1.1 倍に坂の向きの倍率を掛ける。
     */
    private static void placeRoots(Plan plan, RandomSource random, BlockPos origin, int originGroundY,
            double baseRadius, Config config, boolean stage2) {
        int base = Math.max(0, config.rootCount());
        if (base <= 0) {
            return;
        }
        Block trunkBlock = config.trunkState().getBlock();
        double trunkR = baseRadius + flareAt(0, baseRadius);
        RootCtx c = new RootCtx(plan, random, origin, trunkBlock, trunkR, originGroundY, stage2);
        int length = Math.max(1, config.rootLength());
        double thick = Math.max(1, config.rootThickness());

        // 坂の向き: 幹のまわり 16 方向の地面の高さ(+ = 下り)。地面が見つからない向き(崖・空中)は大きな下りとみなす。
        double ocx = origin.getX() + 0.5D;
        double ocz = origin.getZ() + 0.5D;
        double[] slope = new double[SLOPE_SAMPLES];
        int steepest = 0;
        for (int j = 0; j < SLOPE_SAMPLES; j++) {
            double a = TAU * j / SLOPE_SAMPLES;
            int sx = Mth.floor(ocx + Math.cos(a) * (trunkR + SLOPE_PROBE));
            int sz = Mth.floor(ocz + Math.sin(a) * (trunkR + SLOPE_PROBE));
            int h = chunkLoaded(plan.level, new BlockPos(sx, originGroundY, sz))
                    ? scanGroundY(plan.level, sx, originGroundY, sz, 12, 40, trunkBlock) : Integer.MIN_VALUE;
            slope[j] = h == Integer.MIN_VALUE ? 12.0D : Mth.clamp(originGroundY - h, -12, 12);
            if (slope[j] > slope[steepest]) {
                steepest = j;
            }
        }

        int count = Math.max(3, base - 3 + random.nextInt(5));
        // 板を立てる根は一部だけ(少なく・高く。11-07 第 1 段の写真の後、Fable・ユーザー)。残りは幹の裾から直に這う。
        int plates = Math.min(count, BUTTRESS_COUNT_MIN + random.nextInt(BUTTRESS_COUNT_MAX - BUTTRESS_COUNT_MIN + 1));
        int platesMade = 0;
        double baseAngle = random.nextDouble() * TAU;
        for (int i = 0; i < count; i++) {
            double jitter = (random.nextDouble() - 0.5D) * (TAU / count) * 0.8D;
            double angle = baseAngle + TAU * i / count + jitter;
            double lenMul = Mth.clamp(1.0D + 0.05D * slopeToward(slope, angle), 0.55D, 1.6D);
            int len = (int) Math.round(length * (0.35D + random.nextDouble() * 0.75D) * lenMul);
            double rad = thick * (0.8D + random.nextDouble() * 0.45D);
            // 板は根の並びに散らす(残りの本数 ÷ 残りの板の数で抽選)。
            boolean plate = platesMade < plates && random.nextInt(count - i) < plates - platesMade;
            if (plate) {
                platesMade++;
                growButtress(c, angle, rad, len);
            } else {
                growSurfaceRoot(c, ocx + Math.cos(angle) * (trunkR - 0.5D), ocz + Math.sin(angle) * (trunkR - 0.5D),
                        originGroundY, angle, len, rad, 0);
            }
        }
        // 板の無い細い根(板根の間から直接)。
        int thin = 1 + random.nextInt(3);
        for (int i = 0; i < thin; i++) {
            double angle = random.nextDouble() * TAU;
            double lenMul = Mth.clamp(1.0D + 0.05D * slopeToward(slope, angle), 0.55D, 1.6D);
            int len = (int) Math.round(length * (0.25D + random.nextDouble() * 0.4D) * lenMul);
            growSurfaceRoot(c, ocx + Math.cos(angle) * (trunkR - 0.5D), ocz + Math.sin(angle) * (trunkR - 0.5D),
                    originGroundY, angle, len, thick * (0.4D + random.nextDouble() * 0.25D), 1);
        }
        // 斜面: 下り側へ根を足して地面をつかむ。
        double down = slope[steepest];
        int extra = down >= 3.0D ? Math.min(3, (int) (down / 4.0D) + 1) : 0;
        double downAngle = TAU * steepest / SLOPE_SAMPLES;
        for (int i = 0; i < extra; i++) {
            double angle = downAngle + (random.nextDouble() - 0.5D) * 0.9D;
            int len = (int) Math.round(length * (0.6D + random.nextDouble() * 0.5D));
            growButtress(c, angle, thick * (0.7D + random.nextDouble() * 0.3D), len);
        }
        // 深い根(第 2 段): 幹の下から斜めに地下へ。洞窟・渓谷の壁に出た所だけ見える(洞窟を貫く根)。
        if (stage2 && isGroundForRoots(plan.level, origin.getX(), originGroundY, origin.getZ())) { // 構造物の上の木は深い根を出さない(11-09)
            int deep = DEEP_COUNT_MIN + random.nextInt(DEEP_COUNT_MAX - DEEP_COUNT_MIN + 1);
            for (int i = 0; i < deep; i++) {
                double angle = random.nextDouble() * TAU;
                double sx = ocx + Math.cos(angle) * (trunkR * 0.7D);
                double sz = ocz + Math.sin(angle) * (trunkR * 0.7D);
                int len = DEEP_LEN_MIN + random.nextInt(DEEP_LEN_MAX - DEEP_LEN_MIN + 1);
                growUnderground(c, sx, originGroundY - 3.5D, sz, angle, DEEP_PITCH_START, DEEP_PITCH_END, len,
                        thick * (0.6D + random.nextDouble() * 0.4D), UNDER_EXPOSURES_DEEP);
            }
        }
    }

    /** 16 方向の坂の配列を角度で線形補間する。 */
    private static double slopeToward(double[] slope, double angle) {
        double f = (angle / TAU) * slope.length;
        f = ((f % slope.length) + slope.length) % slope.length;
        int i0 = (int) Math.floor(f);
        int i1 = (i0 + 1) % slope.length;
        double w = f - i0;
        return slope[i0] * (1.0D - w) + slope[i1] * w;
    }

    /**
     * 板根 1 枚: 幹の中から外へ、薄く(根元だけ 2 枚の厚さ)背の高い板を立て、外ほど低くして地表の根の太さへつなぐ。
     * 板は外へ行くほど少し反る({@value #BUTTRESS_CURL} rad まで)。板の下は地面の高さに沿う。
     * 地面が {@value #BUTTRESS_MAX_DROP} 段より下がる所(縁)・登れない段差では板を止め、そこから地表の根が引き継ぐ。
     */
    private static void growButtress(RootCtx c, double angle, double rootRad, int rootLen) {
        Plan plan = c.plan();
        RandomSource r = c.random();
        double ocx = c.origin().getX() + 0.5D;
        double ocz = c.origin().getZ() + 0.5D;
        double trunkR = c.trunkR();
        double len = trunkR * (BUTTRESS_LEN_MIN + r.nextDouble() * (BUTTRESS_LEN_MAX - BUTTRESS_LEN_MIN));
        double height = trunkR * (BUTTRESS_H_MIN + r.nextDouble() * (BUTTRESS_H_MAX - BUTTRESS_H_MIN));
        double curl = (r.nextDouble() - 0.5D) * 2.0D * BUTTRESS_CURL;
        int gPrev = c.originGy();
        int px = Integer.MIN_VALUE;
        int pz = Integer.MIN_VALUE;
        double endX = ocx + Math.cos(angle) * trunkR;
        double endZ = ocz + Math.sin(angle) * trunkR;
        double endHeading = angle;
        int endG = gPrev;
        // 相談 3(2026-11-07): 上の縁を波打たせ、通り道を少し蛇行させ、下 2/3 は 2 枚・上 1/3 は 1 枚でところどころ欠く。
        double waveAmp = PLATE_WAVE_MIN + r.nextDouble() * (PLATE_WAVE_MAX - PLATE_WAVE_MIN);
        double waveLen = PLATE_WAVE_LEN_MIN + r.nextDouble() * (PLATE_WAVE_LEN_MAX - PLATE_WAVE_LEN_MIN);
        double wavePh = r.nextDouble() * TAU;
        double wigAmp = PLATE_WIGGLE_MIN + r.nextDouble() * (PLATE_WIGGLE_MAX - PLATE_WIGGLE_MIN);
        double wigCycles = 0.8D + r.nextDouble() * 0.7D;
        double wigPh = r.nextDouble() * TAU;
        double side = r.nextBoolean() ? 1.0D : -1.0D;
        boolean sampledMid = false;
        for (double s = trunkR * 0.55D; s <= trunkR + len; s += 0.5D) {
            double u = Mth.clamp((s - trunkR) / len, 0.0D, 1.0D);
            double a = angle + curl * u * u;
            double wig = wigAmp * Math.sin(TAU * wigCycles * u + wigPh) - wigAmp * Math.sin(wigPh); // 幹の所では 0
            double x = ocx + Math.cos(a) * s - Math.sin(a) * wig * Math.min(1.0D, u * 4.0D);
            double z = ocz + Math.sin(a) * s + Math.cos(a) * wig * Math.min(1.0D, u * 4.0D);
            int ix = Mth.floor(x);
            int iz = Mth.floor(z);
            if (ix == px && iz == pz) {
                continue;
            }
            if (!chunkLoaded(plan.level, new BlockPos(ix, gPrev, iz))) {
                break;
            }
            int g = surfaceStep(plan, ix, gPrev, iz, c.trunkBlock(), BUTTRESS_MAX_DROP);
            if (g == STEP_WALL || g == STEP_NONE) {
                break;
            }
            double h = height * Math.pow(1.0D - u, 2.0D) + rootRad * 0.9D;
            double wave = waveAmp * Math.sin(TAU * s / waveLen + wavePh) * Math.min(1.0D, h / 4.0D);
            int top = g + Math.max(1, (int) Math.round(h + wave));
            if (px != Integer.MIN_VALUE && ix != px && iz != pz) {
                plateColumn(plan, ix, pz, g, top, c.trunkBlock(), r, true); // 角だけで接しないように 1 軸ずつ寄せる
            }
            plateColumn(plan, ix, iz, g, top, c.trunkBlock(), r, true);
            if (top - g >= 3) {
                // 下 2/3 だけ 2 枚の厚さ(上ほど薄い)。
                int lowTop = g + (int) Math.round((top - g) * 2.0D / 3.0D);
                plateColumn(plan, Mth.floor(x - Math.sin(a) * side), Mth.floor(z + Math.cos(a) * side), g, lowTop,
                        c.trunkBlock(), r, false);
            }
            if (!sampledMid && u >= 0.3D) {
                plan.sample("板根の中ほど", ix, g, iz);
                sampledMid = true;
            }
            px = ix;
            pz = iz;
            gPrev = g;
            endX = x;
            endZ = z;
            endHeading = a;
            endG = g;
        }
        plan.sample("板根", Mth.floor(endX), endG, Mth.floor(endZ));
        growSurfaceRoot(c, endX, endZ, endG, endHeading, rootLen, rootRad, 0);
    }

    /** 板根の 1 列: その列の地面の 1 段(土・岩なら置き換え)から {@code top} まで。列に近い地面が無ければ置かない(縁で浮かない)。 */
    private static void plateColumn(Plan plan, int x, int z, int gRef, int top, Block trunkBlock, RandomSource r,
            boolean holes) {
        int g = surfaceStep(plan, x, gRef, z, trunkBlock, BUTTRESS_MAX_DROP);
        if (g == STEP_WALL || g == STEP_NONE || g > top) {
            return;
        }
        // 上 1/3(1 枚の所)をところどころ欠く(相談 3): 列のてっぺんを 1〜2 段下げた切れ込み。中に穴を開けると、穴の上のマスの
        // 下が空いて浮く(第 1 段の決まり「下が空いた根を作らない」)ので、縁から欠く。
        int notchMax = (int) Math.round((top - g) / 3.0D);
        if (holes && top - g >= 4 && notchMax >= 1 && r.nextFloat() < PLATE_NOTCH_CHANCE) {
            top -= Math.min(notchMax, 1 + r.nextInt(2));
        }
        for (int y = isGroundForRoots(plan.level, x, g, z) ? g : g + 1; y <= top; y++) { // 構造物の床は置き換えない(11-09)
            plan.root(x, y, z);
        }
    }

    /**
     * 地表の根 1 本。蛇行(曲がり具合がゆっくり揺れる)しながら外へ這い、細くなり、だんだん土に埋もれる(背中だけ出ては潜る)。
     * <ul>
     *   <li>這う: 足元から上 {@value #ROOT_MAX_RISE_PER_STEP}・下 {@value #ROOT_MAX_DROP_PER_STEP} 段の面へ移る。</li>
     *   <li>壁: 土・岩なら潜り、地下の根({@link #growUnderground})として続く。構造物なら {@value #ROOT_CLIMB_MAX} 段まで
     *       面に沿って登り、それより高ければ脇へ回り込む(壊さない)。</li>
     *   <li>縁・空中: 川・渓谷で同じくらいの高さの対岸があれば渡る({@link #spanAcross})。無ければ、斜めに沈まず、その場で
     *       真下の地面まで垂直に下り、着いたらまた這う。{@value #ROOT_DROP_MAX} 段下にも地面が無ければ
     *       {@value #ROOT_HANG_MAX} 段垂らして終わる。</li>
     *   <li>アーチ(第 2 段): 根の途中が一度潜ってから背中を持ち上げ、また潜る。</li>
     *   <li>呼吸根(第 2 段): 湿った地面を通ると、まわりに短く太い膝根が群れる。</li>
     *   <li>構造物の上では埋もれない(上に載って覆う)。</li>
     * </ul>
     */
    private static void growSurfaceRoot(RootCtx c, double sx, double sz, int startGy, double heading, int length,
            double rad0, int depth) {
        if (length < 3 || rad0 <= 0.0D) {
            return;
        }
        Plan plan = c.plan();
        RandomSource r = c.random();
        double ocx = c.origin().getX() + 0.5D;
        double ocz = c.origin().getZ() + 0.5D;
        double curX = sx;
        double curZ = sz;
        double h = heading;
        double curv = 0.0D;
        int gy = startGy;
        int pix = Mth.floor(curX);
        int piz = Mth.floor(curZ);
        int pgy = gy;
        double pcy = Double.NaN;
        boolean havePrev = false;
        double expPhase = r.nextDouble() * TAU;
        double expFreq = EXP_FREQ_MIN + r.nextDouble() * (EXP_FREQ_MAX - EXP_FREQ_MIN);
        int maxForks = depth < ROOT_MAX_FORKS.length ? ROOT_MAX_FORKS[depth] : 0;
        int forks = 0;
        int lastFork = -100;
        int lastKnees = -100;
        int turns = 0;
        double turnSign = r.nextBoolean() ? 1.0D : -1.0D;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // アーチ: 根の途中の一区間で背中を持ち上げる(前後は一度潜る)。
        int archStart = Integer.MAX_VALUE;
        int archLen = 0;
        double archH = 0.0D;
        if (c.stage2() && depth <= 1 && rad0 >= ARCH_MIN_RAD && r.nextFloat() < ARCH_CHANCE) {
            archLen = ARCH_LEN_MIN + r.nextInt(ARCH_LEN_MAX - ARCH_LEN_MIN + 1);
            archH = ARCH_H_MIN + r.nextDouble() * (ARCH_H_MAX - ARCH_H_MIN) + rad0 * 0.5D;
            int lo = (int) Math.round(length * 0.15D);
            int hi = Math.max(lo + 1, (int) Math.round(length * 0.6D) - archLen);
            archStart = lo + r.nextInt(Math.max(1, hi - lo));
        }

        int step = 0;
        boolean ended = false;
        for (; step < length; step++) {
            double t = (double) step / length;
            // 蛇行: 曲がり具合がゆっくり揺れる + 幹から外へ弱く引く + 3 歩ごとに下り側へ寄る。
            boolean inArch = step >= archStart && step < archStart + archLen;
            curv = curv * 0.9D + (r.nextDouble() - 0.5D) * MEANDER * (inArch ? 0.4D : 1.0D);
            h += curv;
            double radialOut = Math.atan2(curZ - ocz, curX - ocx);
            h += OUTWARD_PULL * Math.atan2(Math.sin(radialOut - h), Math.cos(radialOut - h));
            if (step % 3 == 0 && !inArch) {
                h += downhillTurn(c, curX, curZ, h, gy);
            }
            double dirX = Math.cos(h);
            double dirZ = Math.sin(h);
            double nx = curX + dirX;
            double nz = curZ + dirZ;
            int ix = Mth.floor(nx);
            int iz = Mth.floor(nz);
            if (!chunkLoaded(plan.level, cursor.set(ix, gy, iz))) {
                ended = true;
                break;
            }
            double dist = Math.sqrt((ix + 0.5D - ocx) * (ix + 0.5D - ocx) + (iz + 0.5D - ocz) * (iz + 0.5D - ocz));
            if (dist <= c.trunkR() || (havePrev && ix == pix && iz == piz)) {
                curX = nx;
                curZ = nz;
                continue;
            }
            double rad = rad0 * Math.pow(1.0D - t, 0.7D);

            // 川: 次の列の、足元と同じ高さか 1 つ下に水がある(岸に来た)。対岸があれば渡る(第 2 段)。
            if (c.stage2() && havePrev && !inArch
                    && (plan.level.getFluidState(cursor.set(ix, gy, iz)).is(net.minecraft.tags.FluidTags.WATER)
                        || plan.level.getFluidState(cursor.set(ix, gy - 1, iz)).is(net.minecraft.tags.FluidTags.WATER))
                    && !plan.level.getFluidState(cursor.set(pix, gy, piz)).is(net.minecraft.tags.FluidTags.WATER)) {
                BankHit bank = findDryBank(plan.level, c.trunkBlock(), curX, curZ, dirX, dirZ, gy, 2);
                if (bank != null && r.nextFloat() >= spanChance(bank, true)) {
                    if (bank.dist() - 1 <= SMALL_WATER_MAX) {
                        // 小さな池を渡らない: 岸に呼吸根を出し、岸沿いへ曲がる(相談 2)。
                        if (step - lastKnees >= KNEE_COOLDOWN) {
                            kneeCluster(c, pix, gy, piz);
                            lastKnees = step;
                        }
                        h += turnSign * SHORE_TURN;
                        continue;
                    }
                    bank = null;
                }
                if (bank != null) {
                    spanAcross(c, curX, curZ, gy, dirX, dirZ, bank, rad, true);
                    curX += dirX * bank.dist();
                    curZ += dirZ * bank.dist();
                    gy = bank.groundY();
                    pix = Mth.floor(curX);
                    piz = Mth.floor(curZ);
                    pgy = gy;
                    pcy = Double.NaN;
                    step += bank.dist();
                    continue;
                }
            }

            int g = surfaceStep(plan, ix, gy, iz, c.trunkBlock(), ROOT_MAX_DROP_PER_STEP);
            if (g == STEP_WALL) {
                BlockState wall = plan.level.getBlockState(cursor.set(ix, gy + 1, iz));
                if (isRootTerrain(wall) && isGroundForRoots(plan.level, ix, gy + 1, iz)) { // 汚染の皮をかぶった構造物の壁には潜らない(11-09)
                    // 土・岩の壁: 潜って地下の根として続く(崖の切り口・洞窟に出ればそこで見える)。
                    if (c.stage2()) {
                        growUnderground(c, nx, gy + 0.5D, nz, h, -0.08D, -0.08D, (length - step) + UNDER_TAIL,
                                Math.max(0.6D, rad), UNDER_EXPOSURES_SHALLOW);
                    }
                    ended = true;
                    break;
                }
                int wallTop = climbTop(plan, ix, gy, iz, c.trunkBlock());
                if (wallTop != Integer.MIN_VALUE && havePrev) {
                    // 構造物の低い壁: 手前のマスで面に沿って登り、上に載る。
                    rootColumn(plan, pix, piz, gy + 1, wallTop + 1, Math.max(0, (int) Math.round(rad - 0.5D)));
                    g = wallTop;
                } else {
                    // 高い構造物: 壊さず脇へ回り込む。
                    if (++turns > ROOT_TURN_MAX) {
                        ended = true;
                        break;
                    }
                    h += turnSign * ROOT_TURN_STEP;
                    continue;
                }
            } else if (g == STEP_NONE) {
                int far = scanGroundY(plan.level, ix, gy - ROOT_MAX_DROP_PER_STEP - 1, iz, 0, ROOT_DROP_MAX,
                        c.trunkBlock());
                int rr = Math.max(0, (int) Math.round(rad - 0.5D));
                // 構造物の上(2026-11-09): 狭い開口(吹き抜け・階段口)は、向こうに同じ高さの床があれば低く渡る(中へ落ちて消えない)。
                boolean standOnStructure = havePrev && !isGroundForRoots(plan.level, pix, gy, piz);
                if (standOnStructure) {
                    BankHit open = findOpeningFloor(plan, c.trunkBlock(), curX, curZ, dirX, dirZ, gy);
                    if (open != null) {
                        spanAcross(c, curX, curZ, gy, dirX, dirZ, open, rad, true);
                        curX += dirX * open.dist();
                        curZ += dirZ * open.dist();
                        gy = open.groundY();
                        pix = Mth.floor(curX);
                        piz = Mth.floor(curZ);
                        pgy = gy;
                        pcy = Double.NaN;
                        step += open.dist();
                        continue;
                    }
                }
                // 川・渓谷: 同じくらいの高さの対岸があれば渡る(第 2 段)。
                if (c.stage2() && havePrev) {
                    boolean river = far != Integer.MIN_VALUE
                            && plan.level.getFluidState(cursor.set(ix, far + 1, iz)).is(net.minecraft.tags.FluidTags.WATER);
                    boolean ravine = far == Integer.MIN_VALUE || far <= gy - SPAN_RAVINE_MIN_DROP;
                    if (river || ravine) {
                        BankHit bank = findDryBank(plan.level, c.trunkBlock(), curX, curZ, dirX, dirZ, gy, ROOT_SPAN_MIN_DIST);
                        if (bank != null && r.nextFloat() >= (river ? spanChance(bank, true) : SPAN_RAVINE_CHANCE)) {
                            if (river && bank.dist() - 1 <= SMALL_WATER_MAX) {
                                // 小さな池を渡らない: 岸に呼吸根を出し、岸沿いへ曲がる(相談 2)。
                                if (step - lastKnees >= KNEE_COOLDOWN) {
                                    kneeCluster(c, pix, gy, piz);
                                    lastKnees = step;
                                }
                                h += turnSign * SHORE_TURN;
                                continue;
                            }
                            bank = null;
                        }
                        if (bank != null) {
                            spanAcross(c, curX, curZ, gy, dirX, dirZ, bank, rad, river);
                            curX += dirX * bank.dist();
                            curZ += dirZ * bank.dist();
                            gy = bank.groundY();
                            pix = Mth.floor(curX);
                            piz = Mth.floor(curZ);
                            pgy = gy;
                            pcy = Double.NaN;
                            step += bank.dist();
                            continue;
                        }
                    }
                }
                // 縁・空中: 真下の地面まで垂直に下りる(斜めに沈まない)。
                if (far == Integer.MIN_VALUE) {
                    // 空中: ゆるく曲がりながら先へ細って垂れる(2026-11-07 相談 5)。
                    hangRoot(plan, r, ix + 0.5D, gy + 1, iz + 0.5D, ROOT_HANG_MAX, rr + HANG_RAD_EXTRA, h);
                    plan.caveHangs++;
                    plan.sample("垂れ", ix, gy, iz);
                    ended = true;
                    break;
                }
                if (standOnStructure) {
                    // 構造物の縁から外へ: 洞窟の垂れと同じく、うねりながら下りる(11-09)。地面の木の崖は前のまま真っすぐ。
                    dropRoot(plan, r, ix, iz, gy + 1, far + 1, rr, h);
                    plan.sample("縁の垂れ", ix, gy, iz);
                } else {
                    rootColumn(plan, ix, iz, far + 1, gy + 1, rr);
                }
                g = far;
                pcy = Double.NaN;
            }
            turns = 0;
            gy = g;

            BlockState surface = plan.level.getBlockState(cursor.set(ix, gy, iz));
            boolean onStructure = !isGroundForRoots(plan.level, ix, gy, iz); // 構造物の上の汚染の皮・薄い床では埋もれない(11-09)
            double e = onStructure ? 1.0D
                    : EXP_START - EXP_BURY * t + EXP_WAVE * Math.sin(expPhase + step * expFreq);
            double lift = 0.0D;
            if (!onStructure && archLen > 0) {
                if (inArch) {
                    double u = (step - archStart + 0.5D) / archLen;
                    lift = archH * Math.sin(Math.PI * u);
                    e = 1.0D;
                    if (step - archStart == archLen / 2) {
                        plan.sample("アーチ", ix, gy, iz);
                    }
                } else if (step >= archStart - ARCH_DIVE && step < archStart + archLen + ARCH_DIVE) {
                    e = Math.min(e, 0.15D); // アーチの足: 一度潜ってから背中が出る
                }
            }
            // 細い根が地面と同じ高さの帯になる所は置かない(上から見て段々の帯になるため。第 2 段)。
            boolean thinFlush = c.stage2() && lift <= 0.0D && rad < THIN_FLUSH_RAD && e < THIN_FLUSH_EXP;
            boolean visible = !thinFlush && crawlSection(plan, ix, gy, iz, rad, e, lift);
            if (visible) {
                double cyNow = sectionCenter(gy, rad, e) + lift;
                if (havePrev && !Double.isNaN(pcy) && Math.abs(cyNow - pcy) >= 1.0D) {
                    // アーチの上り下りのつなぎ(芯を縦に結ぶ)。
                    rootColumn(plan, ix, iz, (int) Math.floor(Math.min(cyNow, pcy)), (int) Math.floor(Math.max(cyNow, pcy)),
                            Math.max(0, (int) Math.round(rad - 1.0D)));
                } else if (havePrev && gy != pgy && lift <= 0.0D) {
                    // 段の上下のつなぎ(1 マスの線が角だけで接しないように)。
                    rootColumn(plan, ix, iz, Math.min(gy, pgy) + 1, Math.max(gy, pgy) + 1, 0);
                }
                if (havePrev && ix != pix && iz != piz && rad < 1.5D) {
                    // 斜めの 1 歩を 1 軸ずつ寄せる(寄せる列の面が同じ高さの方だけ)。
                    if (surfaceStep(plan, ix, gy, piz, c.trunkBlock(), 1) == gy) {
                        crawlSection(plan, ix, gy, piz, rad, e, lift);
                    } else if (surfaceStep(plan, pix, gy, iz, c.trunkBlock(), 1) == gy) {
                        crawlSection(plan, pix, gy, iz, rad, e, lift);
                    }
                }
                if (!onStructure && lift <= 0.0D) {
                    plan.rootColumns.add(BlockPos.asLong(ix, gy, iz));
                }
                pcy = cyNow;
            } else {
                pcy = Double.NaN;
            }

            // 呼吸根: 湿った地面を通ると膝根が群れる。
            if (c.stage2() && !onStructure && lift <= 0.0D && step - lastKnees >= KNEE_COOLDOWN
                    && r.nextFloat() < KNEE_CHANCE
                    && com.yayuy.overgrownvegetation.corruption.Pneumatophores.isWet(plan.level, ix, gy, iz)) {
                lastKnees = step;
                kneeCluster(c, ix, gy, iz);
            }

            // 枝分かれ: 幹に近い側〜中ほどで、間を空けて。
            if (forks < maxForks && visible && !inArch && t >= 0.12D && t <= 0.65D && step - lastFork >= 14
                    && r.nextFloat() < 0.18F) {
                forks++;
                lastFork = step;
                double sign = r.nextBoolean() ? 1.0D : -1.0D;
                double turn = sign * (0.45D + r.nextDouble() * 0.7D);
                int childLen = (int) Math.round((length - step) * (0.5D + r.nextDouble() * 0.45D));
                growSurfaceRoot(c, nx, nz, gy, h + turn, childLen, rad * 0.65D, depth + 1);
                h -= sign * 0.2D;
            }

            pix = ix;
            piz = iz;
            pgy = gy;
            havePrev = true;
            curX = nx;
            curZ = nz;
        }
        // 先: 湿った地面なら呼吸根で終わる。根は浅く地下へ続く(崖の切り口に出れば見える)。
        if (c.stage2() && !ended && havePrev) {
            if (com.yayuy.overgrownvegetation.corruption.Pneumatophores.isWet(plan.level, pix, gy, piz)) {
                kneeCluster(c, pix, gy, piz);
            }
            if (depth == 0 && isGroundForRoots(plan.level, pix, gy, piz)) { // 構造物の上では潜らせない(11-09)
                growUnderground(c, curX, gy - 2.5D, curZ, h, -0.05D, -0.05D, UNDER_TAIL, 0.8D, UNDER_EXPOSURES_SHALLOW);
            }
        }
    }

    /** 断面の芯の高さ({@link #crawlSection} と同じ式)。 */
    private static double sectionCenter(int gy, double rad, double e) {
        double hv = Math.max(0.5D, rad * 0.8D);
        return gy + 1.0D + 2.0D * hv * e - hv;
    }

    /**
     * 川・渓谷を渡る根(第 2 段): 今の足元 {@code (sx, gy, sz)} から向き {@code (dirX, dirZ)} の対岸 {@code bank} まで、
     * 両岸の高さを結ぶ線から垂れる曲線で渡す。川では水面の 1 段上より下へ垂れない。渡り切った所の地面へ潜って続く。
     */
    private static void spanAcross(RootCtx c, double sx, double sz, int gy, double dirX, double dirZ, BankHit bank,
            double rad, boolean river) {
        Plan plan = c.plan();
        int dist = bank.dist();
        double sag = river ? Math.min(SPAN_RIVER_SAG_MAX, dist * 0.12D)
                : Mth.clamp(dist * SPAN_RAVINE_SAG_RATIO, 2.0D, SPAN_RAVINE_SAG_MAX);
        int rr = Math.max(1, (int) Math.round(rad - 0.2D));
        int waterTop = Integer.MIN_VALUE;
        if (river) {
            int mx = Mth.floor(sx + dirX * dist * 0.5D);
            int mz = Mth.floor(sz + dirZ * dist * 0.5D);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(mx, gy + 2, mz);
            for (int y = gy + 2; y > gy - 24; y--) {
                if (plan.level.getFluidState(p.set(mx, y, mz)).is(net.minecraft.tags.FluidTags.WATER)) {
                    waterTop = y;
                    break;
                }
            }
        }
        double y0 = gy + 0.5D + rr * 0.5D;
        double y1 = bank.groundY() + 0.5D + rr * 0.5D;
        int prevY = Integer.MIN_VALUE;
        for (int k = 0; k <= dist; k++) {
            double u = (double) k / dist;
            double y = Mth.lerp(u, y0, y1) - sag * Math.sin(Math.PI * u);
            if (waterTop != Integer.MIN_VALUE) {
                y = Math.max(y, waterTop + 1.0D + rr * 0.5D);
            }
            int ix = Mth.floor(sx + dirX * k);
            int iz = Mth.floor(sz + dirZ * k);
            int iy = (int) Math.floor(y);
            rootBlob(plan, ix, iy, iz, rr);
            if (prevY != Integer.MIN_VALUE && Math.abs(iy - prevY) >= 2) {
                rootColumn(plan, ix, iz, Math.min(iy, prevY), Math.max(iy, prevY), Math.max(0, rr - 1));
            }
            prevY = iy;
        }
        plan.spans++;
        plan.sample("渡り", Mth.floor(sx + dirX * dist * 0.5D), gy, Mth.floor(sz + dirZ * dist * 0.5D));
    }

    /** {@code (cx, cy, cz)} を中心とした半径 {@code r}(+0.35)の丸い塊を、構造物を避けて根にする。 */
    private static void rootBlob(Plan plan, int cx, int cy, int cz, int r) {
        double edgeSq = (r + 0.35D) * (r + 0.35D);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dy * dy + dz * dz) <= edgeSq) {
                        plan.root(cx + dx, cy + dy, cz + dz);
                    }
                }
            }
        }
    }

    /** 呼吸根の群れ({@link com.yayuy.overgrownvegetation.corruption.Pneumatophores})を計画へ足す。 */
    private static void kneeCluster(RootCtx c, int cx, int gy, int cz) {
        Plan plan = c.plan();
        RandomSource r = c.random();
        com.yayuy.overgrownvegetation.corruption.Pneumatophores.cluster(r, cx, cz,
                com.yayuy.overgrownvegetation.corruption.Pneumatophores.rollCount(r), 4, (x, z, height, wide) -> {
                    int g = surfaceStep(plan, x, gy, z, c.trunkBlock(), 2);
                    if (g == STEP_WALL || g == STEP_NONE || !isRootTerrain(plan.level.getBlockState(new BlockPos(x, g, z)))) {
                        return;
                    }
                    com.yayuy.overgrownvegetation.corruption.Pneumatophores.kneeCells(x, g, z, height, wide, plan::root);
                });
        plan.kneeClusters++;
        plan.sample("呼吸根", cx, gy, cz);
    }

    /**
     * 地下の根(第 2 段): 岩・土の中を 1 マスずつ進み、中では何も置かない。洞窟・崖の切り口・渓谷の壁に出た所だけ根を見せる。
     * <ul>
     *   <li>天井から出た: 床まで {@value #CAVE_PILLAR_MAX} 段以内なら床まで下りる柱(天井と床に 1 段ずつ食い込む)にして、床の下へ潜って続く。
     *       床が遠ければ {@value #ROOT_HANG_MAX} 段垂らして終わる。</li>
     *   <li>壁から出た: 向きの先 {@value #CAVE_CROSS_MAX} マス以内に向かいの壁があれば斜めに横切って向こうの壁へ潜る。
     *       無ければ(崖の切り口・広い空間)面に沿って真下へ垂れる(地面まで {@value #CAVE_PILLAR_MAX} 段以内なら地面まで)。</li>
     *   <li>構造物(廃坑の板など)に当たったら止まる。見せる回数は {@code exposures} まで。</li>
     * </ul>
     */
    private static void growUnderground(RootCtx c, double x, double y, double z, double h, double pitch,
            double pitchTarget, int length, double rad, int exposures) {
        Plan plan = c.plan();
        RandomSource r = c.random();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double curv = 0.0D;
        boolean prevSolid = true;
        int shown = 0;
        int minY = plan.level.getMinBuildHeight() + 4;
        for (int step = 0; step < length; step++) {
            double t = (double) step / length;
            curv = curv * 0.9D + (r.nextDouble() - 0.5D) * MEANDER;
            h += curv;
            pitch += (pitchTarget - pitch) * 0.05D + (r.nextDouble() - 0.5D) * 0.03D;
            x += Math.cos(h) * Math.cos(pitch);
            y += Math.sin(pitch);
            z += Math.sin(h) * Math.cos(pitch);
            int ix = Mth.floor(x);
            int iy = Mth.floor(y);
            int iz = Mth.floor(z);
            if (iy < minY || !chunkLoaded(plan.level, p.set(ix, iy, iz))) {
                return;
            }
            BlockState s = plan.level.getBlockState(p);
            if (isStructure(s, c.trunkBlock())) {
                return;
            }
            if (isRootSolid(s, c.trunkBlock())) {
                prevSolid = true;
                continue;
            }
            if (!prevSolid) {
                continue;
            }
            // 開けた所に出た。
            if (++shown > exposures) {
                return;
            }
            int rr = Math.max(0, (int) Math.round(rad * (1.0D - 0.5D * t) - 0.3D));
            boolean fromAbove = isRootSolid(plan.level.getBlockState(p.set(ix, iy + 1, iz)), c.trunkBlock());
            if (fromAbove) {
                int floor = scanGroundY(plan.level, ix, iy, iz, 0, CAVE_PILLAR_MAX, c.trunkBlock());
                if (floor == Integer.MIN_VALUE) {
                    hangRoot(plan, r, ix + 0.5D, iy + 1, iz + 0.5D, ROOT_HANG_MAX, rr + HANG_RAD_EXTRA, h);
                    plan.caveHangs++;
                    plan.sample("垂れ", ix, iy, iz);
                    return;
                }
                int[] bottom = cavePillar(c, ix, iy, iz, floor, h, rr);
                plan.cavePillars++;
                plan.sample("洞窟の柱", ix, iy, iz);
                x = bottom[0] + 0.5D;
                z = bottom[2] + 0.5D;
                y = bottom[1] - 1.5D; // 床の下へ潜って続く
                pitch = Math.min(pitch, -0.1D);
                prevSolid = true;
                continue;
            }
            // 壁から出た: 向かいの壁を探す(少し下りながら)。
            double slope = Math.tan(pitch);
            int hit = -1;
            for (int k = 1; k <= CAVE_CROSS_MAX; k++) {
                int qx = Mth.floor(x + Math.cos(h) * k);
                int qy = Mth.floor(y + slope * k);
                int qz = Mth.floor(z + Math.sin(h) * k);
                if (!chunkLoaded(plan.level, p.set(qx, qy, qz))) {
                    break;
                }
                BlockState q = plan.level.getBlockState(p);
                if (isStructure(q, c.trunkBlock())) {
                    break;
                }
                if (isRootSolid(q, c.trunkBlock())) {
                    hit = k;
                    break;
                }
            }
            if (hit > 0) {
                double sag = hit * 0.12D;
                int prevY = Integer.MIN_VALUE;
                for (int k = 0; k <= hit; k++) {
                    double u = (double) k / hit;
                    int qx = Mth.floor(x + Math.cos(h) * k);
                    int qy = (int) Math.floor(y + slope * k - sag * Math.sin(Math.PI * u));
                    int qz = Mth.floor(z + Math.sin(h) * k);
                    rootBlob(plan, qx, qy, qz, rr);
                    if (prevY != Integer.MIN_VALUE && Math.abs(qy - prevY) >= 2) {
                        rootColumn(plan, qx, qz, Math.min(qy, prevY), Math.max(qy, prevY), Math.max(0, rr - 1));
                    }
                    prevY = qy;
                }
                plan.caveCrossings++;
                plan.sample("横切り", ix, iy, iz);
                x += Math.cos(h) * (hit + 0.5D);
                y += slope * (hit + 0.5D);
                z += Math.sin(h) * (hit + 0.5D);
                prevSolid = true;
                continue;
            }
            // 崖の切り口・広い空間: 2〜3 本の束にして、面に沿って揺れながら垂れる(2026-11-07 相談 1)。
            faceDrape(c, ix, iy, iz, h, rr);
            plan.faceDrapes++;
            plan.sample("切り口", ix, iy, iz);
            return;
        }
    }

    /**
     * 崖の切り口の根(2026-11-07 相談 1): 面から出た所 {@code (ix, iy, iz)} から、{@value #DRAPE_STRANDS_MIN}〜
     * {@value #DRAPE_STRANDS_MAX} 本の束(太さ {@value #DRAPE_RAD_MIN}〜{@value #DRAPE_RAD_MAX}・間 0〜1 マス)を面に沿って並べ、
     * 左右へ揺らしながら床まで垂らす({@link #stampLeg} の S 字)。床が {@value #CAVE_PILLAR_MAX} 段以内に無い糸は
     * {@link #hangRoot} で垂らして終わる。芯が構造物に当たる糸は置かない。1 本も置けなければ前の 1 本の柱にする。
     */
    private static void faceDrape(RootCtx c, int ix, int iy, int iz, double h, int rr) {
        Plan plan = c.plan();
        RandomSource r = c.random();
        int n = DRAPE_STRANDS_MIN + r.nextInt(DRAPE_STRANDS_MAX - DRAPE_STRANDS_MIN + 1);
        double[] rads = new double[n];
        double[] offs = new double[n];
        double pos = 0.0D;
        for (int i = 0; i < n; i++) {
            rads[i] = Math.max(rr * 0.8D, DRAPE_RAD_MIN + r.nextDouble() * (DRAPE_RAD_MAX - DRAPE_RAD_MIN));
            if (i > 0) {
                pos += rads[i - 1] + rads[i] + 0.7D + r.nextInt(2); // 間 0〜1 マス
            }
            offs[i] = pos;
        }
        double perpX = -Math.sin(h);
        double perpZ = Math.cos(h);
        int top = iy + 1;
        int placed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            double o = offs[i] - pos * 0.5D;
            // 面へ少し寄せる(束が面から浮いて見えないように)。
            double tx = ix + 0.5D + perpX * o - Math.cos(h) * 0.4D;
            double tz = iz + 0.5D + perpZ * o - Math.sin(h) * 0.4D;
            int cx = Mth.floor(tx);
            int cz = Mth.floor(tz);
            if (!chunkLoaded(plan.level, p.set(cx, iy, cz)) || isStructure(plan.level.getBlockState(p), c.trunkBlock())) {
                continue;
            }
            int floor = scanGroundY(plan.level, cx, iy, cz, 0, CAVE_PILLAR_MAX, c.trunkBlock());
            double sway = (DRAPE_SWAY_MIN + r.nextDouble() * (DRAPE_SWAY_MAX - DRAPE_SWAY_MIN)) * (r.nextBoolean() ? 1.0D : -1.0D);
            if (floor == Integer.MIN_VALUE || top - floor < 2) {
                if (floor == Integer.MIN_VALUE) {
                    hangRoot(plan, r, tx, top, tz, ROOT_HANG_MAX - r.nextInt(4), rads[i], h);
                    placed++;
                }
                continue;
            }
            double bx = tx + Math.cos(h) * r.nextDouble() * 0.8D; // 下ほどわずかに面から離れてもよい
            double bz = tz + Math.sin(h) * r.nextDouble() * 0.8D;
            if (!legClear(c, tx, top, tz, bx, bz, sway, h, floor)) {
                continue;
            }
            stampLeg(plan, tx, top, tz, bx, floor, bz, sway, h, rads[i], 0.3D, 0.8D, 5 + r.nextInt(4), r.nextInt(5));
            placed++;
        }
        if (placed == 0) {
            int floor = scanGroundY(plan.level, ix, iy, iz, 0, CAVE_PILLAR_MAX, c.trunkBlock());
            rootColumn(plan, ix, iz, floor == Integer.MIN_VALUE ? iy - ROOT_HANG_MAX + 1 : floor, iy + 1, rr);
        }
    }

    /**
     * 空中に垂れる根(2026-11-07 相談 5): {@code (tx, ty, tz)} から {@code len} 段、ゆるく曲がりながら(向き {@code dir} の
     * 横へ 2 乗で寄る + 弱い揺れ)、先へ向かって細って垂れる。半径 {@code rad} → 先で 0(1 マス)。
     */
    private static void hangRoot(Plan plan, RandomSource r, double tx, int ty, double tz, int len, double rad,
            double dir) {
        if (len < 1) {
            return;
        }
        double bendDir = dir + (r.nextDouble() - 0.5D) * 2.0D;
        double bend = HANG_BEND_MIN + r.nextDouble() * (HANG_BEND_MAX - HANG_BEND_MIN);
        double swayAmp = 0.3D + r.nextDouble() * 0.5D;
        double ph = r.nextDouble() * TAU;
        double perpX = -Math.sin(bendDir);
        double perpZ = Math.cos(bendDir);
        for (int k = 0; k <= len; k++) {
            for (double half : new double[] {0.0D, 0.5D}) {
                double u = Math.min(1.0D, (k + half) / len);
                double b = bend * u * u;
                double s = swayAmp * Math.sin(ph + u * Math.PI * 1.5D) * u;
                double qx = tx + Math.cos(bendDir) * b + perpX * s;
                double qz = tz + Math.sin(bendDir) * b + perpZ * s;
                double rk = rad * Math.pow(1.0D - u, 0.8D);
                stampDisc(plan, qx, ty - k, qz, rk);
            }
        }
    }

    /** {@link #stampDisc} と同じだが、空気・置き換え可能なマスにだけ置く(構造物の縁から垂れる根。2026-11-09)。 */
    private static void stampDiscIntoAir(Plan plan, double qx, int y, double qz, double rad) {
        int ri = (int) Math.ceil(rad) + 1;
        int cx = Mth.floor(qx);
        int cz = Mth.floor(qz);
        double edgeSq = (rad + 0.35D) * (rad + 0.35D);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double ox = cx + dx + 0.5D - qx;
                double oz = cz + dz + 0.5D - qz;
                if ((dx == 0 && dz == 0) || ox * ox + oz * oz <= edgeSq) {
                    BlockState s = plan.level.getBlockState(p.set(cx + dx, y, cz + dz));
                    if (s.isAir() || s.canBeReplaced()) {
                        plan.root(cx + dx, y, cz + dz);
                    }
                }
            }
        }
    }

    /** 段 {@code y} に、芯 {@code (qx, qz)} のまわり半径 {@code rad}(+0.35)の丸い断面を置く(芯のマスは必ず置く)。 */
    private static void stampDisc(Plan plan, double qx, int y, double qz, double rad) {
        int ri = (int) Math.ceil(rad) + 1;
        int cx = Mth.floor(qx);
        int cz = Mth.floor(qz);
        double edgeSq = (rad + 0.35D) * (rad + 0.35D);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double ox = cx + dx + 0.5D - qx;
                double oz = cz + dz + 0.5D - qz;
                if ((dx == 0 && dz == 0) || ox * ox + oz * oz <= edgeSq) {
                    plan.root(cx + dx, y, cz + dz);
                }
            }
        }
    }

    /**
     * 洞窟の柱(2026-11-07 直し): 天井 {@code (ix, iy+1, iz)} から床へ、傾き・S 字・付け根の張り・節のある根の柱を計画する。
     * 床の入口は水平に {@value #PILLAR_LEAN_MIN}〜{@value #PILLAR_LEAN_MAX} マスずらす(根の向き寄り)。入口の列の床が無い・
     * 自然の地面でない・途中の芯が構造物に当たるなら、ずらしを縮めて試し、最後は真下。返り値は床の入口 {x, 床の Y, z}。
     */
    private static int[] cavePillar(RootCtx c, int ix, int iy, int iz, int floor, double h, int rr) {
        Plan plan = c.plan();
        RandomSource r = c.random();
        int top = iy + 1;
        int height = top - floor;
        double leanDir = h + (r.nextDouble() - 0.5D) * 1.6D;
        double lean = Math.min(PILLAR_LEAN_MIN + r.nextInt(PILLAR_LEAN_MAX - PILLAR_LEAN_MIN + 1),
                Math.max(0.0D, height * PILLAR_LEAN_PER_HEIGHT));
        double amp = (PILLAR_S_AMP_MIN + r.nextDouble() * (PILLAR_S_AMP_MAX - PILLAR_S_AMP_MIN))
                * Math.min(1.0D, height / 12.0D) * (r.nextBoolean() ? 1.0D : -1.0D);
        double tx = ix + 0.5D;
        double tz = iz + 0.5D;
        int bx = ix;
        int bz = iz;
        int by = floor;
        for (double f : new double[] {1.0D, 0.6D, 0.3D, 0.0D}) {
            int cx = Mth.floor(tx + Math.cos(leanDir) * lean * f);
            int cz = Mth.floor(tz + Math.sin(leanDir) * lean * f);
            if (f > 0.0D) {
                if (!chunkLoaded(plan.level, new BlockPos(cx, iy, cz))) {
                    continue;
                }
                int fy = scanGroundY(plan.level, cx, iy, cz, 0, CAVE_PILLAR_MAX, c.trunkBlock());
                if (fy == Integer.MIN_VALUE || !isRootTerrain(plan.level.getBlockState(new BlockPos(cx, fy, cz)))
                        || !legClear(c, tx, top, tz, cx + 0.5D, cz + 0.5D, amp, leanDir, fy)) {
                    continue;
                }
                bx = cx;
                bz = cz;
                by = fy;
                break;
            }
            // 真下(元の柱の位置)。S 字だけ弱めて残す。
            amp = legClear(c, tx, top, tz, tx, tz, amp * 0.5D, leanDir, floor) ? amp * 0.5D : 0.0D;
        }
        double mid = height >= 6 ? Math.max(1.0D, rr - PILLAR_MID_THIN) : rr; // 途中は細く(付け根の張りとの差を出す)
        double topFlare = PILLAR_FLARE_MIN + r.nextDouble() * (PILLAR_FLARE_MAX - PILLAR_FLARE_MIN);
        double botFlare = PILLAR_FLARE_MIN + r.nextDouble() * (PILLAR_FLARE_MAX - PILLAR_FLARE_MIN);
        int knotGap = PILLAR_KNOT_GAP_MIN + r.nextInt(PILLAR_KNOT_GAP_MAX - PILLAR_KNOT_GAP_MIN + 1);
        int knotPhase = r.nextInt(knotGap);
        stampLeg(plan, tx, top, tz, bx + 0.5D, by, bz + 0.5D, amp, leanDir, mid, topFlare, botFlare, knotGap, knotPhase);
        // 途中で 2 本に分かれる(高い柱だけ)。
        if (height >= PILLAR_FORK_MIN_HEIGHT && r.nextDouble() < PILLAR_FORK_CHANCE) {
            double us = 0.35D + r.nextDouble() * 0.25D;
            double[] sp = legPoint(tx, top, tz, bx + 0.5D, by, bz + 0.5D, amp, leanDir, us);
            double fd = leanDir + (r.nextBoolean() ? 1.0D : -1.0D) * (1.2D + r.nextDouble() * 0.8D);
            double fl = 2.0D + r.nextDouble() * 2.0D;
            int fx = Mth.floor(sp[0] + Math.cos(fd) * fl);
            int fz = Mth.floor(sp[2] + Math.sin(fd) * fl);
            int fy = chunkLoaded(plan.level, new BlockPos(fx, by, fz))
                    ? scanGroundY(plan.level, fx, Mth.floor(sp[1]), fz, 0, CAVE_PILLAR_MAX, c.trunkBlock())
                    : Integer.MIN_VALUE;
            if (fy != Integer.MIN_VALUE && isRootTerrain(plan.level.getBlockState(new BlockPos(fx, fy, fz)))
                    && legClear(c, sp[0], Mth.floor(sp[1]), sp[2], fx + 0.5D, fz + 0.5D, -amp * 0.5D, fd, fy)) {
                stampLeg(plan, sp[0], Mth.floor(sp[1]), sp[2], fx + 0.5D, fy, fz + 0.5D, -amp * 0.5D, fd,
                        Math.max(0.0D, mid - 1.0D), 0.0D, botFlare * 0.6D, knotGap, knotPhase);
                plan.sample("柱の分かれ", fx, fy, fz);
            }
        }
        // まわりの天井から細い垂れ根。
        if (r.nextDouble() < PILLAR_DRIP_CHANCE) {
            int n = 1 + r.nextInt(2);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * TAU;
                double d = mid + topFlare + 1.0D + r.nextDouble() * 2.0D;
                int dx = Mth.floor(tx + Math.cos(a) * d);
                int dz = Mth.floor(tz + Math.sin(a) * d);
                if (!chunkLoaded(plan.level, p.set(dx, top, dz))
                        || !isRootTerrain(plan.level.getBlockState(p.set(dx, top, dz)))) {
                    continue;
                }
                int len = 2 + r.nextInt(4);
                for (int k = 0; k <= len; k++) {
                    BlockState s = plan.level.getBlockState(p.set(dx, top - k, dz));
                    if (k > 0 && isRootSolid(s, c.trunkBlock())) {
                        break;
                    }
                    plan.root(dx, top - k, dz);
                }
            }
        }
        // 付け根の天井から dangling_roots の房(相談 6)。張りの外の、天井が自然の岩・土で真下が空気の所。
        int tufts = PILLAR_TUFTS_MIN + r.nextInt(PILLAR_TUFTS_MAX - PILLAR_TUFTS_MIN + 1);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0, tries = 0; i < tufts && tries < tufts * 4; tries++) {
            double a = r.nextDouble() * TAU;
            double d = mid + topFlare * 0.6D + 0.8D + r.nextDouble() * 2.0D;
            int dx = Mth.floor(tx + Math.cos(a) * d);
            int dz = Mth.floor(tz + Math.sin(a) * d);
            if (!chunkLoaded(plan.level, q.set(dx, top, dz)) || plan.isTrunk(dx, top - 1, dz)
                    || !plan.level.getBlockState(q.set(dx, top - 1, dz)).isAir()) {
                continue;
            }
            if (!plan.isTrunk(dx, top, dz) && !isRootTerrain(plan.level.getBlockState(q.set(dx, top, dz)))) {
                continue;
            }
            plan.danglingTops.add(BlockPos.asLong(dx, top - 1, dz));
            plan.danglingLens.add(PILLAR_TUFT_LEN_MIN + r.nextInt(PILLAR_TUFT_LEN_MAX - PILLAR_TUFT_LEN_MIN + 1));
            i++;
        }
        return new int[] {bx, by, bz};
    }

    /** 柱の芯の、上からの割合 {@code u}(0 = 天井・1 = 床)での位置 {x, y, z}: 天井と床を滑らかにつなぎ、横へ S 字に振る。 */
    private static double[] legPoint(double tx, int ty, double tz, double bx, int by, double bz, double amp,
            double dir, double u) {
        double s = u * u * (3.0D - 2.0D * u);
        double off = amp * Math.sin(TAU * u);
        double px = -Math.sin(dir);
        double pz = Math.cos(dir);
        return new double[] {tx + (bx - tx) * s + px * off, ty + (by - ty) * u, tz + (bz - tz) * s + pz * off};
    }

    /** 芯の通り道(天井 {@code ty} 〜 床 {@code by})のどの段にも構造物が無く、どの段も読み込み済みか。 */
    private static boolean legClear(RootCtx c, double tx, int ty, double tz, double bx, double bz, double amp,
            double dir, int by) {
        Plan plan = c.plan();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int h = Math.max(1, ty - by);
        for (int y = by; y <= ty; y++) {
            double[] q = legPoint(tx, ty, tz, bx, by, bz, amp, dir, (double) (ty - y) / h);
            p.set(Mth.floor(q[0]), y, Mth.floor(q[2]));
            if (!chunkLoaded(plan.level, p) || isStructure(plan.level.getBlockState(p), c.trunkBlock())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 柱の 1 本(脚)を置く: 各段に、芯のまわり半径 = 途中の太さ {@code mid} + 付け根の張り(天井側・床側)+ 節、の丸い断面。
     * 段の間は半段ずつ芯を取ってつなぐ(傾いても斜めの角だけでつながらない)。床側の張りの外の列は、その列の床まで下ろす。
     */
    private static void stampLeg(Plan plan, double tx, int ty, double tz, double bx, int by, double bz, double amp,
            double dir, double mid, double topFlare, double botFlare, int knotGap, int knotPhase) {
        int h = Math.max(1, ty - by);
        for (int y = by; y <= ty; y++) {
            for (double half : new double[] {0.0D, 0.5D}) {
                double u = Math.min(1.0D, Math.max(0.0D, (ty - y - half) / h));
                double[] q = legPoint(tx, ty, tz, bx, by, bz, amp, dir, u);
                double ft = Math.max(0.0D, 1.0D - u / PILLAR_FLARE_SPAN);
                double fb = Math.max(0.0D, 1.0D - (1.0D - u) / PILLAR_FLARE_SPAN);
                double rad = mid + topFlare * ft * ft + botFlare * fb * fb
                        + (((ty - y) + knotPhase) % knotGap == 0 ? PILLAR_KNOT_BULGE : 0.0D);
                int ri = (int) Math.ceil(rad) + 1;
                int cx = Mth.floor(q[0]);
                int cz = Mth.floor(q[2]);
                double edgeSq = (rad + 0.35D) * (rad + 0.35D);
                for (int dx = -ri; dx <= ri; dx++) {
                    for (int dz = -ri; dz <= ri; dz++) {
                        double ox = cx + dx + 0.5D - q[0];
                        double oz = cz + dz + 0.5D - q[2];
                        if ((dx == 0 && dz == 0) || ox * ox + oz * oz <= edgeSq) {
                            plan.root(cx + dx, y, cz + dz);
                            if (y <= by + 2) {
                                extendDown(plan, cx + dx, y - 1, cz + dz, 8);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 川を渡る確率: 水の幅({@code 対岸までの距離 − 1})が {@value #SMALL_WATER_MAX} 以下なら小さな池として低くする(相談 2)。 */
    private static float spanChance(BankHit bank, boolean river) {
        return river && bank.dist() - 1 <= SMALL_WATER_MAX ? SPAN_SMALL_WATER_CHANCE : SPAN_RIVER_CHANCE;
    }

    // 構造物の上に立った巨大樹(2026-11-09 ユーザー報告「根が潜って短く見える・垂れが垂直」。数はすべて仮決め)。
    /** 「地面」と認める深さ: 面のマスと、その下この段数がすべて自然の地面({@link #isRootTerrain})。 */
    private static final int GROUND_DEPTH = 3;
    /** 構造物の上の開口を渡る最大の幅(列)。 */
    private static final int OPENING_MAX = 8;
    /** 構造物の縁から外へ垂れる根: この段数より短い落差は真っすぐのまま。揺れの振れ幅・波長・外への膨らみ。 */
    private static final int DROP_SWAY_MIN_LEN = 6;
    private static final double DROP_SWAY_MIN = 0.6D;
    private static final double DROP_SWAY_MAX = 1.4D;
    private static final int DROP_PERIOD_MIN = 14;
    private static final int DROP_PERIOD_MAX = 26;
    private static final double DROP_BULGE_MAX = 1.0D;

    /**
     * 根が埋もれ・潜ってよい「地面」か(2026-11-09): 面 {@code (x, y, z)} とその下 {@value #GROUND_DEPTH} 段がすべて自然の地面。
     * 構造物の上の汚染の皮(汚染されたブロックは {@link #isRootTerrain} に入る)・下が空いた薄い床・洞窟のすぐ上の薄い天井は
     * 地面にしない(根は上に載る)。
     */
    public static boolean isGroundForRoots(net.minecraft.world.level.BlockGetter level, int x, int y, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= GROUND_DEPTH; k++) {
            if (!isRootTerrain(level.getBlockState(p.set(x, y - k, z)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 構造物の上の開口の向こうの床(2026-11-09): 向き {@code (dirX, dirZ)} の 2〜{@value #OPENING_MAX} 列先に、足元
     * {@code gy} の上下 2 段の面がある最初の列。間の列はどれも面が無いこと。登れない壁に当たれば null。
     */
    private static BankHit findOpeningFloor(Plan plan, Block trunkBlock, double px, double pz, double dirX, double dirZ,
            int gy) {
        int gap = 0;
        for (int d = 1; d <= OPENING_MAX; d++) {
            int x = Mth.floor(px + dirX * d);
            int z = Mth.floor(pz + dirZ * d);
            if (!chunkLoaded(plan.level, new BlockPos(x, gy, z))) {
                return null;
            }
            int s = surfaceStep(plan, x, gy, z, trunkBlock, 2);
            if (s == STEP_WALL) {
                return null;
            }
            if (s == STEP_NONE) {
                gap++;
                continue;
            }
            return gap > 0 && d >= 2 ? new BankHit(d, s) : null;
        }
        return null;
    }

    /**
     * 構造物の縁から外へ垂れる根(2026-11-09): 列 {@code (x, z)} の {@code topY} から {@code bottomY} まで、縁に沿う向き
     * (根の向き {@code h} の横)へゆるく揺れ({@value #DROP_SWAY_MIN}〜{@value #DROP_SWAY_MAX} マス・波長
     * {@value #DROP_PERIOD_MIN}〜{@value #DROP_PERIOD_MAX} 段)、途中は外へ少し膨らむ(壁の側へは寄らない)。上下の端では
     * 揺れを 0 に戻す。芯の通り道に固いブロックがあれば揺れを半分 → 0 にする(壁の出っ張りで途切れない)。下の 2 段は各マスを床まで下ろす。
     */
    private static void dropRoot(Plan plan, RandomSource r, int x, int z, int topY, int bottomY, int rr, double h) {
        int len = topY - bottomY;
        if (len < DROP_SWAY_MIN_LEN) {
            rootColumn(plan, x, z, bottomY, topY, rr);
            return;
        }
        double amp = (DROP_SWAY_MIN + r.nextDouble() * (DROP_SWAY_MAX - DROP_SWAY_MIN)) * (r.nextBoolean() ? 1.0D : -1.0D);
        double period = DROP_PERIOD_MIN + r.nextInt(DROP_PERIOD_MAX - DROP_PERIOD_MIN + 1);
        double ph = r.nextDouble() * TAU;
        double bulge = r.nextDouble() * DROP_BULGE_MAX;
        double perpX = -Math.sin(h);
        double perpZ = Math.cos(h);
        double outX = Math.cos(h);
        double outZ = Math.sin(h);
        double cx0 = x + 0.5D;
        double cz0 = z + 0.5D;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        double scale = 1.0D;
        for (double s : new double[] {1.0D, 0.5D, 0.0D}) {
            scale = s;
            boolean clear = true;
            for (int k = 0; k <= len && clear; k++) {
                double[] q = dropPoint(cx0, cz0, perpX, perpZ, outX, outZ, amp * s, period, ph, bulge * s, k, len);
                BlockState st = plan.level.getBlockState(p.set(Mth.floor(q[0]), topY - k, Mth.floor(q[1])));
                clear = st.isAir() || st.canBeReplaced() || plan.isTrunk(p.getX(), p.getY(), p.getZ());
            }
            if (clear) {
                break;
            }
        }
        if (scale == 0.0D) {
            rootColumn(plan, x, z, bottomY, topY, rr);
            return;
        }
        for (int k = 0; k <= len; k++) {
            for (double half : new double[] {0.0D, 0.5D}) {
                double[] q = dropPoint(cx0, cz0, perpX, perpZ, outX, outZ, amp * scale, period, ph, bulge * scale,
                        Math.min(len, k + half), len);
                int y = topY - k;
                stampDiscIntoAir(plan, q[0], y, q[1], rr); // 空いたマスだけ(縁の汚染の皮・壁に食い込まない)
                if (k >= len - 1) {
                    // 下の 2 段: 外側のマスもその列の床まで下ろす(床が平らでなくても浮かない)。
                    int ri = rr + 1;
                    for (int dx = -ri; dx <= ri; dx++) {
                        for (int dz = -ri; dz <= ri; dz++) {
                            int ax = Mth.floor(q[0]) + dx;
                            int az = Mth.floor(q[1]) + dz;
                            if (plan.isTrunk(ax, y, az)) {
                                extendDown(plan, ax, y - 1, az, 8);
                            }
                        }
                    }
                }
            }
        }
    }

    /** {@link #dropRoot} の芯: 上から {@code k} 段目の {x, z}。揺れは上下の端の 4 段で 0 へ戻り、外への膨らみは真ん中で最大。 */
    private static double[] dropPoint(double cx, double cz, double perpX, double perpZ, double outX, double outZ,
            double amp, double period, double ph, double bulge, double k, int len) {
        double env = Math.min(1.0D, Math.min(k / 4.0D, (len - k) / 4.0D));
        double sway = amp * Math.sin(TAU * k / period + ph) * Math.max(0.0D, env);
        double out = bulge * Math.sin(Math.PI * k / len);
        return new double[] {cx + perpX * sway + outX * out, cz + perpZ * sway + outZ * out};
    }

    /** 構造物: 固く、自然の地面でも木でもないブロック(根も巣の球も壊さない)。 */
    static boolean isStructure(BlockState s, Block ownTrunk) {
        return isRootSolid(s, ownTrunk) && !isRootTerrain(s);
    }

    /**
     * 巣の球を置く地面の高さ: {@code gy}(木の根元の面)から下へ、空気・水・構造物・木を読み飛ばして最初の自然の地面。
     * 200 段下まで無ければ {@code gy} のまま。
     */
    private static int terrainGroundBelow(WorldGenLevel level, BlockPos origin, int gy) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bottom = Math.max(level.getMinBuildHeight(), gy - 200);
        for (int y = gy; y >= bottom; y--) {
            if (isGroundForRoots(level, origin.getX(), y, origin.getZ())) { // 構造物の上の汚染の皮・薄い床は地面にしない(11-09)
                return y;
            }
        }
        return gy;
    }

    /**
     * 対岸を探す(第 2 段): 向きの先 {@code minDist}〜{@value #ROOT_SPAN_MAX_DIST} マスで、足元の上下
     * {@value #ROOT_SPAN_BANK_UP} 段に固い面があり、その真上が水でない最初の列。川底・水没した段は対岸にしない。
     */
    private static BankHit findDryBank(WorldGenLevel level, Block trunkBlock, double px, double pz, double dirX,
            double dirZ, int gy, int minDist) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int gap = 0; // 間にあった「水の上・地面の無い」列の数
        for (int d = 1; d <= ROOT_SPAN_MAX_DIST; d++) {
            int x = Mth.floor(px + dirX * d);
            int z = Mth.floor(pz + dirZ * d);
            if (!chunkLoaded(level, p.set(x, gy, z))) {
                return null;
            }
            int y = scanGroundY(level, x, gy, z, ROOT_SPAN_BANK_UP, ROOT_SPAN_BANK_DOWN, trunkBlock);
            boolean dry = y != Integer.MIN_VALUE
                    && !level.getFluidState(p.set(x, y + 1, z)).is(net.minecraft.tags.FluidTags.WATER);
            if (!dry) {
                gap++;
                continue;
            }
            if (gap > 0) {
                return d >= minDist ? new BankHit(d, y) : null;
            }
        }
        return null;
    }

    /**
     * 根の断面 1 つ。横長の楕円(半幅 rad+0.5・半高さ rad×0.8)を、露出 {@code e}(1 = 地面の上に載る、0 = 上端が地面と同じ、
     * 負 = 潜る)の高さに置く。地面より 2 段下は置かない。全部が地面の下なら何も置かず false。
     * 地面から浮いた列は下の固い面まで埋める(縁で浮かないように)。
     */
    private static boolean crawlSection(Plan plan, int cx, int gy, int cz, double rad, double e, double lift) {
        double w = rad + 0.5D;
        double hv = Math.max(0.5D, rad * 0.8D);
        double surf = gy + 1.0D;
        double cy = surf + 2.0D * hv * e - hv + lift; // lift: アーチの持ち上げ(第 2 段)
        if (lift <= 0.0D && cy + hv < surf - 0.5D) {
            return false;
        }
        int ri = (int) Math.ceil(w);
        boolean any = false;
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 > w * w) {
                    continue;
                }
                double half = hv * Math.sqrt(Math.max(0.0D, 1.0D - d2 / (w * w)));
                int yLo = Math.max((int) Math.ceil(cy - half - 0.5D), gy - 1);
                int yHi = (int) Math.floor(cy + half - 0.5D);
                if (yHi < yLo) {
                    continue;
                }
                if (lift > 0.5D) {
                    // アーチの背: 支えを足さない(足は前後の歩が地面に着いている)。
                    for (int y = yLo; y <= yHi; y++) {
                        plan.root(cx + dx, y, cz + dz);
                    }
                    any = true;
                    continue;
                }
                // 縁から張り出す列(下 3 段に固い面も根も無い)は置かない。浮いた縁を残さない。
                int gap = supportGap(plan, cx + dx, yLo - 1, cz + dz, 3);
                if (gap < 0) {
                    continue;
                }
                for (int y = yLo - gap; y <= yHi; y++) {
                    plan.root(cx + dx, y, cz + dz);
                }
                any = true;
            }
        }
        return any;
    }

    /**
     * {@code (x, y, z)} から下へ、固い面か計画済みの根に当たるまでの空きの段数(0 = すぐ下が支え)。
     * {@code max} 段の中に支えが無ければ -1。
     */
    private static int supportGap(Plan plan, int x, int y, int z, int max) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= max; k++) {
            int yy = y - k;
            if (plan.isTrunk(x, yy, z) || isRootSolid(plan.level.getBlockState(p.set(x, yy, z)), plan.trunkState.getBlock())) {
                return k;
            }
        }
        return -1;
    }

    /** {@code (x, y, z)} から下へ、支え(固い面か根)に当たるまで最大 {@code max} 段埋める。届かなければ埋めない。 */
    private static void extendDown(Plan plan, int x, int y, int z, int max) {
        int gap = supportGap(plan, x, y, z, max);
        for (int k = 0; k < gap; k++) {
            plan.root(x, y - k, z);
        }
    }

    /** {@code (cx, cz)} の列を {@code fromY}〜{@code toY} まで、水平半径 {@code radius} の丸い断面で(構造物は避けて)埋める。 */
    private static void rootColumn(Plan plan, int cx, int cz, int fromY, int toY, int radius) {
        int r = Math.max(0, radius);
        double edgeSq = (r + 0.35D) * (r + 0.35D);
        for (int y = fromY; y <= toY; y++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dz * dz) <= edgeSq) {
                        plan.root(cx + dx, y, cz + dz);
                    }
                }
            }
        }
        if (r > 0) {
            // 太い柱の外側の列は、その列の地面まで下ろす(地面が平らでなくても浮かない)。
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((double) (dx * dx + dz * dz) <= edgeSq) {
                        extendDown(plan, cx + dx, fromY - 1, cz + dz, 8);
                    }
                }
            }
        }
    }

    /** {@link #surfaceStep} の結果: 登れない壁。 */
    private static final int STEP_WALL = Integer.MAX_VALUE;
    /** {@link #surfaceStep} の結果: 近くに面が無い(縁・空中)。 */
    private static final int STEP_NONE = Integer.MIN_VALUE;

    /**
     * 足元 {@code gy} から隣の列 {@code (x, z)} へ 1 歩進むときの次の面。上 {@value #ROOT_MAX_RISE_PER_STEP}・下 {@code maxDrop}
     * 段の範囲で「固く、真上が固くない」いちばん高いブロックの Y。上の 3 段が全部固ければ {@link #STEP_WALL}、
     * 範囲に面が無ければ {@link #STEP_NONE}。
     */
    private static int surfaceStep(Plan plan, int x, int gy, int z, Block trunkBlock, int maxDrop) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean above = isRootSolid(plan.level.getBlockState(p.set(x, gy + ROOT_MAX_RISE_PER_STEP + 1, z)), trunkBlock);
        for (int y = gy + ROOT_MAX_RISE_PER_STEP; y >= gy - maxDrop; y--) {
            boolean solid = isRootSolid(plan.level.getBlockState(p.set(x, y, z)), trunkBlock);
            if (solid && !above) {
                return y;
            }
            above = solid;
        }
        boolean wall = true;
        for (int k = 1; k <= ROOT_MAX_RISE_PER_STEP + 1; k++) {
            if (!isRootSolid(plan.level.getBlockState(p.set(x, gy + k, z)), trunkBlock)) {
                wall = false;
                break;
            }
        }
        return wall ? STEP_WALL : STEP_NONE;
    }

    /** 構造物の壁の上端: {@code gy+1} から上へ固いブロックが続く最後の Y。{@value #ROOT_CLIMB_MAX} 段を超えれば MIN_VALUE。 */
    private static int climbTop(Plan plan, int x, int gy, int z, Block trunkBlock) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = gy + 1; y <= gy + ROOT_CLIMB_MAX; y++) {
            if (!isRootSolid(plan.level.getBlockState(p.set(x, y + 1, z)), trunkBlock)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 左右 0.6 rad・4 歩先の地面を比べ、低い方へ曲げる量。 */
    private static double downhillTurn(RootCtx c, double x, double z, double h, int gy) {
        int g1 = probeGround(c, x + Math.cos(h - 0.6D) * 4.0D, z + Math.sin(h - 0.6D) * 4.0D, gy);
        int g2 = probeGround(c, x + Math.cos(h + 0.6D) * 4.0D, z + Math.sin(h + 0.6D) * 4.0D, gy);
        if (g1 == Integer.MIN_VALUE || g2 == Integer.MIN_VALUE) {
            return 0.0D;
        }
        return Mth.clamp((g1 - g2) * DOWNHILL_GAIN, -DOWNHILL_MAX, DOWNHILL_MAX);
    }

    private static int probeGround(RootCtx c, double x, double z, int gy) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        if (!chunkLoaded(c.plan().level, new BlockPos(ix, gy, iz))) {
            return Integer.MIN_VALUE;
        }
        return scanGroundY(c.plan().level, ix, gy, iz, 6, 10, c.trunkBlock());
    }

    /** 根にとっての「固い」ブロック(面として這い、壁として当たる)。空気・置き換え可能・木の原木と葉は固くない。 */
    private static boolean isRootSolid(BlockState state, Block ownTrunk) {
        return !state.isAir() && !state.canBeReplaced() && !rootPassesThrough(state, ownTrunk);
    }

    /**
     * 根が潜って(置き換えて)よい自然の地面: 土・草・砂・砂利・粘土・石(花崗岩などの石の仲間・深層岩・凝灰岩)・ネザーの石・
     * テラコッタ・鉱石・砂岩・方解石・鍾乳石・雪・氷・汚染されたブロック。これ以外の固いブロックは構造物として扱い、
     * 根は上を這って覆うだけで壊さない(丸石・石レンガ・板・ガラスなど。仮決め 2026-11-07)。
     */
    public static boolean isRootTerrain(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.NYLIUM)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.GRAVELS)
                || s.is(BlockTags.ICE) || s.is(BlockTags.SNOW)
                || s.is(Blocks.CLAY) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED);
    }

    /**
     * 確かめ用(2026-11-18 大群の軽量化 B: 計画を速くしても形が 1 ブロックも変わらないか): 本物と同じ道({@link #buildPlan})で
     * 種 {@code seed} の計画を作り、計画のすべての一覧(LongArrayList の欄。名前順)と long の欄を順番どおりに混ぜた指紋・かかった ns・
     * ブロックの数を返す。ワールドには置かない(地形は読む)。
     */
    public static long[] planDigestForTests(WorldGenLevel level, BlockPos origin, Config config, long seed) {
        long t0 = System.nanoTime();
        Plan plan = buildPlan(level, RandomSource.create(seed), config, origin);
        long nanos = System.nanoTime() - t0;
        long h = 1125899906842597L;
        long blocks = 0;
        java.lang.reflect.Field[] fields = Plan.class.getDeclaredFields();
        java.util.Arrays.sort(fields, java.util.Comparator.comparing(java.lang.reflect.Field::getName));
        try {
            for (java.lang.reflect.Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == LongArrayList.class) {
                    LongArrayList list = (LongArrayList) f.get(plan);
                    h = 31 * h + f.getName().hashCode();
                    for (int i = 0; i < list.size(); i++) h = 31 * h + list.getLong(i);
                    blocks += list.size();
                } else if (f.getType() == long.class || f.getType() == int.class) {
                    h = 31 * h + ((Number) f.get(plan)).longValue();
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return new long[]{h, nanos, blocks};
    }

    /**
     * GameTest 用: 巣の球だけを計画する(ワールドには触らない)。返すのは置く・掘るマスの集合と、球の中心
     * ({@code centerOut[0]}、作らなければ {@link Long#MIN_VALUE})。
     */
    public static LongOpenHashSet planHiveForTests(net.minecraft.server.level.ServerLevel level, BlockPos origin,
            Config config, long seed, long[] centerOut) {
        Plan plan = new Plan(level, config.trunkState(), config.leavesState());
        double baseRadius = baseRadiusFor(Math.max(16, config.height()), config);
        int gy = findGroundY(level, origin.getX(), origin.getY(), origin.getZ(), config.trunkState().getBlock());
        buildHiveChamber(plan, RandomSource.create(seed), level, origin, terrainGroundBelow(level, origin, gy), baseRadius);
        LongOpenHashSet all = new LongOpenHashSet();
        all.addAll(plan.hiveBark);
        all.addAll(plan.hiveShell);
        all.addAll(plan.hiveFill);
        all.addAll(plan.hiveLining);
        all.addAll(plan.hiveHollow);
        if (centerOut != null && centerOut.length > 0) {
            centerOut[0] = plan.hiveCenter;
        }
        return all;
    }

    /**
     * GameTest 用: 幹の裾の埋め・板根・地表の根だけを計画し、置くマスの集合を返す(ワールドには触らない)。
     */
    public static LongOpenHashSet planRootsForTests(net.minecraft.server.level.ServerLevel level, BlockPos origin,
            Config config, long seed) {
        return planRootsForTests(level, origin, config, seed, false, null);
    }

    /**
     * GameTest 用(第 2 段): {@code stage2} でアーチ・渡り・呼吸根・地下の根も計画する。{@code countsOut} が非 null なら
     * [渡り, 呼吸根の群れ, 洞窟の柱, 洞窟の横切り, 垂れ, 切り口の垂れ] の数を書く。
     */
    public static LongOpenHashSet planRootsForTests(net.minecraft.server.level.ServerLevel level, BlockPos origin,
            Config config, long seed, boolean stage2, int[] countsOut) {
        Plan plan = new Plan(level, config.trunkState(), config.leavesState());
        RandomSource random = RandomSource.create(seed);
        double baseRadius = baseRadiusFor(Math.max(16, config.height()), config);
        int gy = findGroundY(level, origin.getX(), origin.getY(), origin.getZ(), config.trunkState().getBlock());
        fillTrunkFooting(plan, origin, baseRadius + flareAt(0, baseRadius));
        placeRoots(plan, random, origin, gy, baseRadius, config, stage2);
        if (countsOut != null && countsOut.length >= 6) {
            countsOut[0] = plan.spans;
            countsOut[1] = plan.kneeClusters;
            countsOut[2] = plan.cavePillars;
            countsOut[3] = plan.caveCrossings;
            countsOut[4] = plan.caveHangs;
            countsOut[5] = plan.faceDrapes;
        }
        return new LongOpenHashSet(plan.trunkSet);
    }

    /**
     * 根の地表探索が「地面ではない」とみなして貫通するブロック。幹自身の原木({@code ownTrunk})に
     * 加えて、<b>既存の木の原木・葉</b>(過繁茂した木・バニラの木の両方)を含める → 根が近くの木を
     * 乗り越えたり葉に潜り込んだりせず、無視してその下の地面を這う。
     */
    private static boolean rootPassesThrough(BlockState state, Block ownTrunk) {
        return state.is(ownTrunk)
                || state.is(ModBlocks.OVERGROWN_LOG.get())
                || state.is(com.yayuy.overgrownvegetation.ModTags.GIANT_WOOD) // 巨大樹の木部・汚染の根(11-16)
                || state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock // 封印木の葉も
                || state.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock // 木生シダの幹(#logs でない)
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
