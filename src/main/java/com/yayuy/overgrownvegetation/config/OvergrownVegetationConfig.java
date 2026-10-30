package com.yayuy.overgrownvegetation.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * config/overgrown_vegetation-common.toml に出力される設定。
 */
public final class OvergrownVegetationConfig {

    public static final ModConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    public static final class Common {
        /** 汚染が広がる半径(ブロック)。 */
        public final ModConfigSpec.IntValue corruptionRadius;
        /** 1 回の処理で置き換える最大ブロック数(汚染が進むにつれてこの値から増えていく、開始値)。 */
        public final ModConfigSpec.IntValue blocksPerCycle;
        /**
         * {@code blocksPerCycle} が汚染の進行とともに増えていく上限値。
         * {@code blocksPerCycleGrowthPer} が 0 の場合はこの値まで増えず常に {@code blocksPerCycle} のまま。
         */
        public final ModConfigSpec.IntValue blocksPerCycleMax;
        /**
         * この個体(blight_fern・汚染死体ブロックそれぞれ)がこれまでに実際に汚染したブロック数が
         * この値だけ増えるたびに、1サイクルで置き換える最大ブロック数が1増える
         * ({@code blocksPerCycleMax} が上限)。汚染範囲が広がるほど同時に置き換わる数も増えていく効果。
         * 0 にすると無効(常に {@code blocksPerCycle} のまま増えない、旧仕様)。
         */
        public final ModConfigSpec.IntValue blocksPerCycleGrowthPer;
        /**
         * 実効 {@code blocksPerCycle}(上記の伸び分を含めた後)にさらに掛ける倍率の<b>全ワールド共通の既定値</b>。
         * 2026-09-25 から倍率はワールドごと(ワールドの難易度「汚染シダの汚染速度」、
         * {@link com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData})で、この値は<b>ワールドに値が無いときだけ</b>使う。
         * {@code /ov corruption speed <倍率>}・{@code /ov difficulty}・葉っぱのボタンの画面はワールドの値を書き換えるので、
         * この値は変わらない(それまでは {@code /ov corruption speed} がこの値を書き換えていた)。
         * blight_fern・汚染死体ブロックの両方に効く({@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader#effectiveBlocksPerCycle}
         * で一括して掛かる)。1.0 で無効(旧仕様と同じ)。
         */
        public final ModConfigSpec.DoubleValue corruptionSpeedMultiplier;
        /** 1 回の処理で調べる最大ブロック数(重い環境では下げる)。 */
        public final ModConfigSpec.IntValue checksPerCycle;
        /** 何 tick おきに処理するか(20 = 1 秒)。 */
        public final ModConfigSpec.IntValue tickInterval;
        /**
         * 汚染シダ(および汚染水草)が着地して静止している間、重力・摩擦・衝突の計算({@code travel})を
         * 飛ばすか。動かない植物なので結果は変わらず、1 体あたりの tick コストの大半が消える。
         * 足元のブロックが消えた・押された・水流に乗った tick は通常の物理に戻る。
         */
        public final ModConfigSpec.BooleanValue blightFernDormantPhysics;
        /**
         * 露出面のあるブロックを置き換えたあと、そこから何ブロック奥(露出していない内部)まで
         * 置き換えるか(blight_fern が使う値)。0 なら露出面のあるブロックのみになる。
         * 汚染死体ブロック({@code overgrown_corpse}/{@code overgrown_cow_corpse})はこの設定に関わらず
         * 常に 0(露出面のみ)固定 — {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader}
         * 参照。
         */
        public final ModConfigSpec.IntValue blightFernInteriorDepth;
        /**
         * 起点(Mobの足元)を中心に、半径何ブロックを触手ではなく隙間なく塗りつぶすか
         * (木の根元が密集しているイメージ)。0なら無効(従来通り最初から触手だけで広がる)。
         * 汚染死体ブロックはこの設定を使わず常に0固定 — blight_fern だけが使う値。
         */
        public final ModConfigSpec.IntValue tendrilCoreRadius;
        /**
         * コア内部の起点(足元)セルだけが最初に同時分岐する方向の本数。コアの他のマスは
         * 常に1方向だけへ(1ブロックずつランダムな方向へ)進むので、この値がコア全体の
         * 「筋」の本数を事実上決める(値が大きいほど、起点から放射状に伸びる独立した筋が増える)。
         */
        public final ModConfigSpec.IntValue coreInitialBranches;
        /** 起点から最初に伸ばす触手(汚染の筋)の本数。コア(上記)が有効な場合はコアの縁から伸びる。 */
        public final ModConfigSpec.IntValue tendrilInitialCount;
        /** 触手が1ブロック伸びるたびに、その場所から新しい触手が分岐する確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue tendrilBranchChance;
        /** 同時に存在できる触手の最大本数(分岐しすぎて重くなるのを防ぐ上限)。 */
        public final ModConfigSpec.IntValue tendrilMaxActive;
        /** 触手が直前と同じ方向へまっすぐ伸び続けようとする確率(0.0〜1.0)。外れると横向きに曲がる。 */
        public final ModConfigSpec.DoubleValue tendrilStraightChance;
        /**
         * 触手が行き止まりに達したとき、経路を何ブロック分まで後戻りして別方向を試せるか
         * (触手1本あたりの累計予算。後戻り自体は置き換えを起こさない)。0なら後戻りせず
         * 即座に消滅する(旧仕様)。大きすぎるとほぼ全域を塗りつぶすようになるため、
         * 「まばらな筋」を保ちたい場合は小さめの値を推奨。
         */
        public final ModConfigSpec.IntValue tendrilBacktrackBudget;
        /** チェストなどの BlockEntity を持つブロックも壊して汚染するか。 */
        public final ModConfigSpec.BooleanValue corruptBlockEntities;
        /**
         * 過繫茂したドア/チェスト/樽が蔦・コケにびっしり覆われた状態(段階 3)のとき、開けようとして
         * 開けられない確率。段階 1・2 はこの 1/3・2/3。段階 0(ハサミで剥がした直後・置き直した直後)は必ず開く。
         */
        public final ModConfigSpec.DoubleValue overgrownContainerStuckChanceAtMax;
        /** 過繫茂したドア/チェスト/樽の覆われ具合が、ランダムティックごとに 1 段階進む確率。 */
        public final ModConfigSpec.DoubleValue overgrownContainerRegrowChance;
        /** 読み込まれていない間に育つはずだった汚染苗木を、チャンク読み込み時に追いつかせるか({@code worldgen/SaplingOfflineGrowth})。 */
        public final ModConfigSpec.BooleanValue saplingOfflineGrowth;
        /** 追いつき成長: 設置からこの tick 数以上経った苗木を、チャンク読み込み時に育てる。 */
        public final ModConfigSpec.IntValue saplingOfflineGrowthTicks;
        /** 追いつき成長: 1 tick に育てる本数の上限。 */
        public final ModConfigSpec.IntValue saplingOfflineGrowthPerTick;
        /**
         * 汚染が溶岩({@code minecraft:lava}。source / flowing の両方)に達したとき、
         * 玄武岩({@code minecraft:basalt})へ固めるか。false なら溶岩はこれまで通り
         * 汚染できない障害物として扱われ、そこで筋が途切れる。水その他の液体は常に対象外。
         */
        public final ModConfigSpec.BooleanValue convertLavaToBasalt;
        /** 置き換え時にパーティクル/音を出す確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue effectChance;
        /**
         * ブロックを汚染する際、本来の対応表({@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry})の
         * 変換先を無視して、汎用の「汚染ブロック」({@link com.yayuy.overgrownvegetation.registry.ModBlocks#OVERGROWN_BLOCK})に
         * 差し替える確率(0.0〜1.0)。元がどんなブロックであっても対象になる。
         */
        public final ModConfigSpec.DoubleValue randomBlockChance;
        /**
         * 石系ブロック(石・深層岩・安山岩・閃緑岩・花崗岩・丸石・鍾乳石ブロックなど。
         * {@link com.yayuy.overgrownvegetation.corruption.CorruptionRegistry} が {@code overgrown_stone} 系へ
         * 変換するブロック)を汚染する際、その変換先の代わりに {@code overgrown_grass_block}
         * (過繁茂した草ブロック)へ差し替える確率(0.0〜1.0)。差し替わった場合、真上が空気なら
         * 露出面抽選の確率に関係なく必ず上面へ下草類を1つ生やす。
         */
        public final ModConfigSpec.DoubleValue stoneToGrassChance;
        /**
         * 汚染された土({@code overgrown_dirt})が、隣に汚染された草ブロックが無くても、
         * ランダムティックのたびに自分から過繁茂した草ブロックへ変わる確率(0.0〜1.0)。
         * 0.0 にすると自発的な草化は起きず、隣接する草ブロックからの伝染でのみ草になる。
         */
        public final ModConfigSpec.DoubleValue dirtToGrassChance;
        /**
         * 触手が次に進む方向を選ぶ際、隣に既存の木の原木({@code #minecraft:logs})があれば
         * その方向を最優先にするか。true にすると汚染が既存の木を優先的に飲み込んでいく
         * ({@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader} 参照)。
         */
        public final ModConfigSpec.BooleanValue preferExistingTrees;
        /**
         * 触手が既存の木の原木({@code #minecraft:logs})を汚染したとき、その木に繋がっていた
         * 自然生成の葉(手で置いていない葉)も枯らして消すか。true にすると「既存の木を丸ごと
         * 枯らして新しい木の土壌にする」という汚染の見た目になり、宙に浮いた葉が残らない。
         */
        public final ModConfigSpec.BooleanValue decayCorruptedTreeLeaves;
        /**
         * 汚染の筋(触手)が1ブロック汚染するたびに、そこから巨大樹の根と同じ形の「アーチ」
         * ({@code overgrown_log} の正弦カーブの橋)を1本生やす確率(0.0〜1.0)。0.0 で無効。
         * あくまで装飾なので低めに。
         */
        public final ModConfigSpec.DoubleValue corruptionArchChance;
        /** 装飾アーチ / 川の橋の水平方向の長さの最小値(ブロック)。実際の長さはこの範囲でランダムに変動する。 */
        public final ModConfigSpec.IntValue corruptionArchMinLength;
        /** 装飾アーチの水平方向の長さの最大値(ブロック)。 */
        public final ModConfigSpec.IntValue corruptionArchMaxLength;
        /** アーチの各ステップで置く塊の半径の最小値(長さが最小のときの太さ)。0=1マス、1≒十字断面、2〜=太い根。 */
        public final ModConfigSpec.IntValue corruptionArchMinThickness;
        /** アーチの各ステップで置く塊の半径の最大値(長さが最大のときの太さ)。太さはランダムではなくアーチの長さに比例して min〜max を取る。 */
        public final ModConfigSpec.IntValue corruptionArchMaxThickness;
        /** アーチの背を {@code overgrown_grass_block} に変えて草・下草・苗木を生やす、背の列1つあたりの確率。 */
        public final ModConfigSpec.DoubleValue corruptionArchGrowthChance;
        /** アーチの抽選のうち、溝(渓谷)をまたぐ上下逆のアーチを試す割合。溝が無ければ通常のアーチになる。 */
        public final ModConfigSpec.DoubleValue corruptionRavineArchShare;
        /** 上下逆のアーチを架ける「溝」と認める深さ(縁から下へ、地面も液体も無い最小のマス数)。 */
        public final ModConfigSpec.IntValue corruptionRavineArchMinDepth;
        /**
         * 触手が水でブロックされたとき、対岸まで根のアーチと同じ形の橋を架けて渡ろうとする確率
         * (0.0〜1.0)。0.0 で無効(従来どおり水で止まる)。川で汚染が止まらないようにするための機能。
         */
        public final ModConfigSpec.DoubleValue corruptionBridgeChance;
        /** 橋で渡れる水面の最大の幅(ブロック)。これより広い水(海など)は渡らない。 */
        public final ModConfigSpec.IntValue corruptionBridgeMaxLength;
        /** 1 個体(blight_fern・汚染死体ブロック)が架けられる橋の最大本数。水辺に橋が並びすぎないための上限。 */
        public final ModConfigSpec.IntValue corruptionBridgeMaxCount;
        /**
         * 死に由来する汚染シダ(胞子感染死・G1 の過繁茂滞在死・胞子感染3 の期限切れで死んだプレイヤー)の
         * 間隔(2026-09-25、ユーザー承認の密度上限)。死んだ地点からこの距離(ブロック、箱)以内に
         * 汚染シダ / 汚染水草が 1 体でもいれば湧かせない。0 で無効。
         * {@link com.yayuy.overgrownvegetation.entity.BlightFernPlacement#spawnFromDeath} 参照。
         */
        public final ModConfigSpec.IntValue blightFernDeathSpawnMinSeparation;
        /**
         * 死に由来する汚染シダの密度の上限。死んだ地点の周囲 {@link #blightFernDeathSpawnNearbyRadius}
         * 以内に既にこの数以上いれば湧かせない。0 で無効。
         */
        public final ModConfigSpec.IntValue blightFernDeathSpawnMaxNearby;
        /** {@link #blightFernDeathSpawnMaxNearby} の判定に使う、死んだ地点からの半径(ブロック、箱)。 */
        public final ModConfigSpec.IntValue blightFernDeathSpawnNearbyRadius;
        /** 汚染時に露出面へシダ・苔・蔦などの装飾植物を生やすか。 */
        public final ModConfigSpec.BooleanValue spawnGrowthPlants;
        /** 露出面 1 つあたりに装飾植物を生やす確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue growthChance;
        /** 上面に植物を生やすと決まった際、高さ2ブロックの植物にする確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue tallGrowthChance;
        /** 汚染した草・土の露出した上面に、装飾植物の代わりに苗木を植えることがあるか。 */
        public final ModConfigSpec.BooleanValue spawnTrees;
        /** 上面が草・土だった場合に苗木を植える確率(0.0〜1.0)。growthChance にさらに掛かる。 */
        public final ModConfigSpec.DoubleValue treeChance;
        /**
         * 木の間隔(ブロック)。この大きさの格子 1 マスに木は 1 本まで(位置はシードで決まる)。0 で前の挙動(treeChance の確率だけ)。
         * {@link com.yayuy.overgrownvegetation.corruption.TreeSpacing}。
         */
        public final ModConfigSpec.IntValue treeSpacing;
        /** 格子のマスが木の無い空き地になる確率。 */
        public final ModConfigSpec.DoubleValue treeClearingChance;
        /** 林冠の穴(木を植えない輪)を置く確率({@link com.yayuy.overgrownvegetation.corruption.TreeSpacing#inCanopyGap})。 */
        public final ModConfigSpec.DoubleValue treeCanopyGapChance;
        /** 樹冠の譲り合い(クラウン・シャイネス)の隙間(ブロック。0 で無し。{@link com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature}、2026-10-17)。 */
        public final ModConfigSpec.IntValue crownShynessGap;
        public final ModConfigSpec.IntValue crownShynessHeight;
        /** 汚染した上面に「未発達の茂み」({@code undeveloped_bush})を生やすことがあるか。 */
        public final ModConfigSpec.BooleanValue spawnUndevelopedBush;
        /** 上面に「未発達の茂み」を生やす確率(0.0〜1.0)。growthChance にさらに掛かる。 */
        public final ModConfigSpec.DoubleValue undevelopedBushChance;
        /** 過繫茂した木の葉ブロック1個ごとに、真下へ「大きな未発達な果実」をぶら下げる確率。 */
        public final ModConfigSpec.DoubleValue undevelopedFruitChance;
        /** 巨大樹の葉ブロック1個ごとに、真下へ「大きな未発達な果実」をぶら下げる確率。 */
        public final ModConfigSpec.DoubleValue giantTreeUndevelopedFruitChance;
        /** 汚染時に {@code overgrown_bush}(過繁茂した茂み)のいびつな塊を生成するか。 */
        public final ModConfigSpec.BooleanValue spawnLeafClumps;
        /** ブロックを1つ汚染するたびに茂みの塊を1つ生成する確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue leafClumpChance;
        /**
         * 壁面(側面の露出面)に汚染植物が生えるとき、そこに壁から突き出す茂みの塊も生成する確率
         * (0.0〜1.0)。地上の {@link #leafClumpChance} より低めにしておくことが前提
         * (壁面ではさらに growthChance の抽選にも通った面でしか呼ばれない)。
         */
        public final ModConfigSpec.DoubleValue wallLeafClumpChance;
        /**
         * {@code overgrown_bush}(過繁茂した茂み)の中に入っている間の水平移動速度の倍率
         * (0.0〜1.0)。小さいほど強く減速する。1.0 で減速なし。縦方向は 0.75 固定
         * (バニラのスイートベリーの茂みと同じ)。
         */
        public final ModConfigSpec.DoubleValue bushSlowMultiplier;
        /**
         * 生成した茂みの列(X/Z)ごとに、その上面に下草(汚染時の露出上面と同じ抽選、
         * {@link com.yayuy.overgrownvegetation.corruption.CorruptionGrowth#randomTop})を1つ生やす確率
         * (0.0〜1.0)。0.0 で茂みからは下草が生えなくなる。
         */
        public final ModConfigSpec.DoubleValue bushUndergrowthChance;
        /** 汚染で下面(天井)に垂れ植物を生やす際、{@code overgrown_glow_vines}(光るツタ)にすることがあるか。 */
        public final ModConfigSpec.BooleanValue spawnGlowVines;
        /** 下面に垂れ植物を生やす際、通常の垂れ蔦の代わりに光るツタの先端を生やす確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue glowVinesChance;
        /**
         * 地下(洞窟)で下面に垂れ植物を生やす際、{@code glowVinesChance} に掛ける倍率。
         * 光るツタは洞窟の明かりなので、地上よりかなり多くする(1.0 で地上と同じ)。
         */
        public final ModConfigSpec.DoubleValue glowVinesCaveMultiplier;
        /** 汚染時に露出面(上面・側面・下面)へ、薄く広がる苔({@code overgrown_moss_layer})を生やすか。 */
        public final ModConfigSpec.BooleanValue spawnMossLayer;
        /**
         * 汚染された各露出面につき、薄く広がる苔({@code overgrown_moss_layer})を1面ぶん生やす確率
         * (0.0〜1.0)。多面ブロックなので、既に別方向の苔が付いているマスにも面を追加する。
         * 通常の下草・蔦の抽選とは独立(苔が付いても外れた面には別途通常の下草が抽選される)。
         */
        public final ModConfigSpec.DoubleValue mossLayerChance;
        /** 水中を汚染したとき、露出した水の面へ汚染された海草・昆布・スイレンなどの水中植物を生やすか。 */
        public final ModConfigSpec.BooleanValue spawnUnderwaterPlants;
        /** 水に接した露出<b>上面</b> 1 つあたりに水中植物(海草・背高海草・藻柱)を生やす確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue underwaterGrowthChance;
        /** 水に接した露出<b>下面</b>(天井)1 つあたりに水中垂れ蔓を生やす確率(0.0〜1.0)。上面とは別抽選。 */
        public final ModConfigSpec.DoubleValue waterCeilingGrowthChance;
        /** 汚染時に水中の露出<b>側面・上面</b>へ、薄く覆う藻({@code overgrown_water_moss_layer})を生やすか。薄い苔の水中版。 */
        public final ModConfigSpec.BooleanValue spawnWaterMossLayer;
        /**
         * 汚染された水中の露出面(側面・上面)1 面につき、藻({@code overgrown_water_moss_layer})を 1 面ぶん
         * 生やす確率(0.0〜1.0、既定 0.7)。多面ブロックなので、既に別方向の藻が付いているマスにも面を追加する。
         * 上面・下面の水中植物の抽選({@link #underwaterGrowthChance})とは独立(藻が生えた面には海草類は生えない)。
         */
        public final ModConfigSpec.DoubleValue waterMossLayerChance;
        /** 上面(水側)に水中植物を生やすと決まった際、高さ2マスの背高海草にする確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue underwaterTallChance;
        /** 上面(水側)に水中植物を生やすと決まった際、昆布状に縦へ伸びる藻柱にする確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue waterColumnChance;
        /** 藻柱を 1 段積むごとに、その段を「藻柱の花」にして成長を止める確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue waterColumnFlowerChance;
        /** 藻柱の最小/最大の高さ(マス)。 */
        public final ModConfigSpec.IntValue waterColumnMinHeight;
        public final ModConfigSpec.IntValue waterColumnMaxHeight;
        /** 上面(水側)を汚染した際、その真上の水面へスイレンを浮かべる確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue lilyChance;
        /** 汚染した地面から水面までの水深がこのマス数以下のときだけスイレンを浮かべる。 */
        public final ModConfigSpec.IntValue lilyMaxDepth;
        /** チャンクの地表が汚染ブロックだらけになったら、そのチャンクのバイオームを overgrown に書き換えるか。 */
        public final ModConfigSpec.BooleanValue convertToOvergrownBiome;
        /** バイオームを書き換える閾値。チャンク地表(16x16=256列)のうち汚染ブロックが占める割合がこれ以上で書き換える。 */
        public final ModConfigSpec.DoubleValue overgrownBiomeThreshold;
        /**
         * バイオーム書き換えの縁を quart(4 ブロック)単位で塗り分け、隣接する別バイオームとの縫い目を
         * ノイズで波打たせて、チャンク境界に沿った直線・直角の階段を消すか。false ならチャンク単位の一括塗り。
         */
        public final ModConfigSpec.BooleanValue overgrownBiomeEdgeSmoothing;
        /**
         * {@link #overgrownBiomeEdgeSmoothing} の波打ちの最大振幅(ブロック)。縫い目は 0〜この値ぶん
         * チャンクの内側へ後退する。大きいほど境界の凹凸が深くなる(4 で 1 quart 列、8 で 2 quart 列が対象)。
         */
        public final ModConfigSpec.DoubleValue overgrownBiomeEdgeNoise;

        // ---- 環境の地域マップ({@link com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap})----
        /** 地域(密林 / 湿地 / 菌湿地帯のひとかたまり)の 1 辺の目安(ブロック)。 */
        public final ModConfigSpec.IntValue overgrowthRegionSize;
        /** 地域境界のうねりの強さ(地域サイズに対する割合)。 */
        public final ModConfigSpec.DoubleValue overgrowthRegionWarp;
        /** ワールドスポーン周りを必ず密林にする半径(ブロック)。0 で無効。 */
        public final ModConfigSpec.IntValue spawnJungleRadius;
        /** 地域の境目の移行帯の幅(ブロック)。木・泥・植物が両側の環境で混ざり合う範囲。 */
        public final ModConfigSpec.IntValue overgrowthRegionBlend;
        /** 地域が密林になる重み。 */
        public final ModConfigSpec.DoubleValue overgrowthVariantWeightJungle;
        /** 地域が湿地になる重み。 */
        public final ModConfigSpec.DoubleValue overgrowthVariantWeightSwamp;
        /** 地域が菌湿地帯になる重み。 */
        public final ModConfigSpec.DoubleValue overgrowthVariantWeightFungalSwamp;

        // ---- 湿地の浸水・棚田({@link com.yayuy.overgrownvegetation.corruption.SwampFlooder})----
        /** 湿地系バイオームのチャンクに池・水路・水たまり・棚田を作るか。 */
        public final ModConfigSpec.BooleanValue swampFlooding;
        /** 浸水処理を走らせる間隔(tick)。 */
        public final ModConfigSpec.IntValue swampFloodIntervalTicks;
        /** 1 回の処理で扱うチャンク数。 */
        public final ModConfigSpec.IntValue swampFloodChunksPerPass;
        /** 同じチャンクを再処理するまでの間隔(tick)。 */
        public final ModConfigSpec.IntValue swampFloodRescanTicks;
        /** 人工ブロック(自然の土・石・汚染地面以外)が地表の列は浸水させないか。 */
        public final ModConfigSpec.BooleanValue swampFloodSkipArtificial;
        /** 池ノイズの閾値(-1〜1)。下げるほど池が広く多くなる。 */
        public final ModConfigSpec.DoubleValue swampPondThreshold;
        /** 水路ノイズの閾値(0〜1)。下げるほど水路が太く増える。 */
        public final ModConfigSpec.DoubleValue swampCreekThreshold;
        /** 水たまりノイズの閾値(-1〜1)。下げるほど水たまりが増える(繁茂段階から出る)。 */
        public final ModConfigSpec.DoubleValue swampPuddleThreshold;
        /** 棚田 1 段の高さ(ブロック)。 */
        public final ModConfigSpec.IntValue swampTerraceStep;
        /** この傾斜(8 ブロック間の高低差の和)以上の場所を棚田にする。平坦な場所は池・水路。 */
        public final ModConfigSpec.DoubleValue swampTerraceSlope;
        /** 棚田の畦(あぜ)のうち、水を溢れさせて滝にする割合。 */
        public final ModConfigSpec.DoubleValue swampSpillwayChance;
        /** 急斜面での棚田 1 段の最大高さ(ブロック)。傾斜が {@link #swampTerraceSteepSlope} に近づくほどこの値に近づく。 */
        public final ModConfigSpec.IntValue swampTerraceMaxStep;
        /** この傾斜で棚田の段差が {@link #swampTerraceMaxStep} に達する。 */
        public final ModConfigSpec.DoubleValue swampTerraceSteepSlope;
        /** 棚田で 1 列を掘り下げる最大深さ(ブロック)。これより深く掘る必要がある列は崖として残す。 */
        public final ModConfigSpec.IntValue swampTerraceMaxCut;
        /** 棚田で 1 列を盛り上げる最大高さ(ブロック)。泥で地形を足して段を作る。 */
        public final ModConfigSpec.IntValue swampTerraceMaxFill;
        /** 隣の列とこれ以上の落差がある列(大穴・崖の縁と底)は棚田・池にしない。 */
        public final ModConfigSpec.IntValue swampTerraceVoidDrop;
        /** その落差列の周り何列までを同じく触らないか。 */
        public final ModConfigSpec.IntValue swampTerraceVoidMargin;
        /** チャンク地表の汚染列数がこれ以上なら(バイオームが過繁茂でなくても)池・水路・棚田の段階に進む。 */
        public final ModConfigSpec.IntValue swampFloodStage2Columns;
        /** 湿地系の場所での汚染時の装飾植物(下草・蔦)の生成確率に掛ける倍率。 */
        public final ModConfigSpec.DoubleValue swampGrowthChanceMultiplier;
        /** 泥の上面に生える植物のうち薄い苔の割合(残りは胞子キノコ)。 */
        public final ModConfigSpec.DoubleValue swampMudMossShare;
        /** 菌湿地帯での苗木の生成確率(treeChance)に掛ける倍率。 */
        public final ModConfigSpec.DoubleValue fungalSwampTreeChanceMultiplier;
        /** 棚田の水面に泥のハーフブロックを敷いて水深 0.5 にするか。 */
        public final ModConfigSpec.BooleanValue swampTerraceShallow;
        /** 棚田の畦(仕切り)を水面より 1 段高い 2 段の土手にするか。false で水面と同じ高さ。 */
        public final ModConfigSpec.BooleanValue swampTerraceRimAboveWater;
        /** 生成ストラクチャー(村の民家・道など)の範囲を浸水・棚田から守るか。 */
        public final ModConfigSpec.BooleanValue swampFloodProtectStructures;
        /** ストラクチャーの各ピースの箱をこのぶん(ブロック)広げて守る。 */
        public final ModConfigSpec.IntValue swampFloodStructureMargin;
        /** 湿地系の場所で元の森の木(バニラの原木)が汚染されたとき、木ごと消滅 / 倒木にするか。 */
        public final ModConfigSpec.BooleanValue swampTreeFall;
        /** 木を消滅させる代わりに切り株 + 倒木にする確率。 */
        public final ModConfigSpec.DoubleValue swampFallenTreeChance;
        /** 倒木の各原木の上に薄い苔が付く確率。 */
        public final ModConfigSpec.DoubleValue swampFallenTreeMossChance;
        /** 1 tick に処理する木の本数。 */
        public final ModConfigSpec.IntValue swampTreeFallPerTick;
        /** 密林(従来の過繁茂バイオーム)になる場所でも元の木を倒木 / 消滅させるか。 */
        public final ModConfigSpec.BooleanValue jungleTreeFall;
        /** 密林で木を倒木にする確率(残りは消滅)。 */
        public final ModConfigSpec.DoubleValue jungleFallenTreeChance;
        /** 湿地系バイオームの霧の濃さ(密林 = 1.0 に対する割合)。 */
        public final ModConfigSpec.DoubleValue swampFogFactor;
        /** グループ 1 の Mob が繁茂系バイオームに一定時間いると死んで汚染シダになるか。 */
        public final ModConfigSpec.BooleanValue group1OvergrownDeath;
        /** その時間(tick)。 */
        public final ModConfigSpec.IntValue group1OvergrownDeathTicks;
        /** 繁茂系バイオームの外にいる間、滞在時間を減らすか(false なら累計)。 */
        public final ModConfigSpec.BooleanValue group1OvergrownDeathDecay;
        /** 湿地系の場所では茂み(overgrown_bush の塊)を生やさないか。 */
        public final ModConfigSpec.BooleanValue swampNoBushes;
        /** 水中・水面をまたぐ根のアーチの生成確率に掛ける倍率(陸のアーチ = corruptionArchChance に対して)。 */
        public final ModConfigSpec.DoubleValue waterArchChanceMultiplier;

        // --- 洞窟の植物・構造物({@link com.yayuy.overgrownvegetation.corruption.CaveGrowth}) ---
        /** 洞窟専用の植物・構造物を有効にするか。 */
        public final ModConfigSpec.BooleanValue caveGrowth;
        /** 地表(葉を除く)よりこの深さ以上低い場所を「洞窟」とみなす。 */
        public final ModConfigSpec.IntValue caveDepthMargin;
        /** 湿地系の洞窟で、汚染ブロックの露出面 1 つあたりに洞窟植物が生える確率(通常の growthChance の代わり)。 */
        public final ModConfigSpec.DoubleValue caveGrowthChance;
        /** 洞窟植物のうち微光の胞子嚢になる割合(残りは苔の塊)。 */
        public final ModConfigSpec.DoubleValue caveSporeSacShare;
        /** 苔の塊 1 回の生成で面に沿って置く塊の数の下限・上限。 */
        public final ModConfigSpec.IntValue caveMossClumpMinPatch;
        public final ModConfigSpec.IntValue caveMossClumpMaxPatch;
        /** 洞窟の汚染ブロック 1 つあたりに構造物を 1 つ建てようとする確率。 */
        public final ModConfigSpec.DoubleValue caveStructureChance;
        /** 構造物の種類の重み(壁のキノコの傘 / 天井の根のアーチ / 菌の柱)。露出面で候補に入らない種類は無視。 */
        public final ModConfigSpec.IntValue caveCapWeight;
        public final ModConfigSpec.IntValue caveArchWeight;
        public final ModConfigSpec.IntValue cavePillarWeight;
        /** 密林(ジャングル)の洞窟にも天井のアーチ(根 / 蔦)を建てるか(植物・傘・柱は湿地系のみ)。 */
        public final ModConfigSpec.BooleanValue caveRootArchInJungle;
        /** 建てた構造物の各ブロックの各面に何かを添える確率(傘・柱 = 薄い苔・ヒカリゴケ、根のアーチ = 洞窟植物 / 垂れ植物)。 */
        public final ModConfigSpec.DoubleValue caveStructurePlantChance;
        /** 傘・柱の表面に添える多面ブロックのうちヒカリゴケの割合(残りは薄い苔)。 */
        public final ModConfigSpec.DoubleValue caveStructureGlowLichenShare;
        /** 壁のキノコの傘: 半径の下限・上限、中心の厚み(段)、縁の垂れ下がり(マス)。 */
        public final ModConfigSpec.IntValue caveCapMinRadius;
        public final ModConfigSpec.IntValue caveCapMaxRadius;
        public final ModConfigSpec.IntValue caveCapThickness;
        public final ModConfigSpec.DoubleValue caveCapDroop;
        /** 天井の根のアーチ: 水平の長さの下限・上限、垂れ下がり(長さに対する割合)、両端・中央の太さ(塊の半径)。 */
        public final ModConfigSpec.IntValue caveArchMinSpan;
        public final ModConfigSpec.IntValue caveArchMaxSpan;
        public final ModConfigSpec.DoubleValue caveArchDip;
        public final ModConfigSpec.IntValue caveArchMinThickness;
        public final ModConfigSpec.IntValue caveArchMaxThickness;
        /** 天井のアーチが根ではなく巨大な蔦(過繁茂した葉に包まれた蔓)になる割合と、その蔦のアーチの垂れ下がり・葉の厚み・葉の密度。 */
        public final ModConfigSpec.DoubleValue caveVineArchShare;
        public final ModConfigSpec.DoubleValue caveVineArchDip;
        public final ModConfigSpec.IntValue caveVineArchLeafRadius;
        public final ModConfigSpec.DoubleValue caveVineArchLeafChance;
        /** 菌の柱: 立てるのに必要な最低の天井の高さ、根元・先端の半径、裾の段数・追加半径、発光菌糸の割合。 */
        public final ModConfigSpec.IntValue cavePillarMinHeight;
        public final ModConfigSpec.DoubleValue cavePillarBaseRadius;
        public final ModConfigSpec.DoubleValue cavePillarTopRadius;
        public final ModConfigSpec.IntValue cavePillarFlareRows;
        public final ModConfigSpec.DoubleValue cavePillarFlare;
        public final ModConfigSpec.DoubleValue cavePillarGlowChance;
        /**
         * 地表の汚染割合が {@link #overgrownBiomeThreshold} に届く前でも、胞子濃度が 100% に達した
         * チャンクを「繁茂バイオーム」({@link com.yayuy.overgrownvegetation.registry.ModBiomes#OVERGROWTH})
         * へ書き換えるか。繁茂バイオームはゲーム上の挙動(霧・Mob 湧き・胞子濃度・AI・地表浸食など)が
         * すべて過繁茂バイオームと同じで、実際の汚染が進んで {@link #overgrownBiomeThreshold} に達すると
         * 過繁茂バイオームへ昇格する。{@link #convertToOvergrownBiome} が false のときは無効。
         */
        public final ModConfigSpec.BooleanValue convertToOvergrowthBiome;
        /** 繁茂バイオーム化の判定間隔(tick)。 */
        public final ModConfigSpec.IntValue overgrowthBiomeScanIntervalTicks;
        /** 繁茂バイオーム化の判定対象にする、各プレイヤーからのチャンク半径。 */
        public final ModConfigSpec.IntValue overgrowthBiomeScanRadiusChunks;
        /** 繁茂バイオーム化の判定 1 回あたり、実際に書き換えを試みるチャンク数の上限。 */
        public final ModConfigSpec.IntValue overgrowthBiomeMaxConvertsPerScan;
        /** 繁茂 / 過繁茂バイオームになったチャンクを、次に再判定するまでの待ち時間(tick)。 */
        public final ModConfigSpec.IntValue overgrowthBiomeRescanTicks;
        /** まだ繁茂系バイオームにならなかったチャンクを、次に再判定するまでの待ち時間(tick)。 */
        public final ModConfigSpec.IntValue overgrowthBiomeMissRescanTicks;
        /**
         * 過繁茂バイオーム({@link com.yayuy.overgrownvegetation.registry.ModBiomes#OVERGROWN})の中に
         * 残った未汚染ブロックが、汚染シダが近くにいなくても時間経過で汚染されていくか。
         * バイオームの中は「既に汚染されたブロックに隣接する未汚染ブロックだけ」を 1 マスずつ外側へ
         * 汚染する有機的なフロンティア浸食で取りこぼしを潰し
         * ({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep#sweepInterior})、
         * さらにバイオームの縁から {@link #overgrownBiomeCreepMargin} ブロックぶんは
         * バイオームの外(チャンク境界の向こう側を含む)へもにじませる
         * ({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep} 参照)。
         * チャンク単位で地表を一括で塗り潰すことはしないので、汚染が矩形に広がることはない。
         */
        public final ModConfigSpec.BooleanValue overgrownBiomeCreep;
        /** {@link #overgrownBiomeCreep} の判定間隔(tick)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepIntervalTicks;
        /** {@link #overgrownBiomeCreep} の縁のにじみ処理で、1 回あたり各プレイヤーの周囲で汚染を試みる回数。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepAttempts;
        /** {@link #overgrownBiomeCreep} が対象にする、各プレイヤーからの水平半径(ブロック)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepRadius;
        /** 過繁茂バイオームの縁から何ブロックぶんまで、バイオームの外へ汚染をはみ出させるか(チャンク境界を跨ぐ)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepMargin;
        /** バイオーム内のフロンティア浸食で、1 回の判定につき処理するチャンク数の上限(0 で内部浸食無効)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepChunksPerTick;
        /** フロンティア浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepMaxConversionsPerChunk;
        /** フロンティア浸食で、各列の最上部から何ブロック下まで汚染/未汚染ブロックを探すか(汚染植物の下の地面を拾う)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepSurfaceDepth;
        /**
         * フロンティア浸食で、汚染源となる隣接ブロックがちょうど 1 つの未汚染ブロックを、
         * 1 回の走査で汚染する確率。低いほど汚染の縁がゆっくり・不規則に進む。
         */
        public final ModConfigSpec.DoubleValue overgrownBiomeCreepFrontierBaseChance;
        /**
         * 汚染源となる隣接ブロックが 1 つ増えるごとに {@link #overgrownBiomeCreepFrontierBaseChance} へ
         * 加算する値。汚染に囲まれた取りこぼしほど早く潰れ、平らな縁ほどゆっくり不規則に進む。
         */
        public final ModConfigSpec.DoubleValue overgrownBiomeCreepFrontierChancePerNeighbor;
        /** フロンティア浸食で実際に汚染が起きたチャンクを、次にまた処理するまでの間隔(tick)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepRescanTicks;
        /** フロンティア浸食で汚染すべきものが無かった(掃除済みの)チャンクを、次に再処理するまでの間隔(tick)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepCleanRescanTicks;
        /**
         * 過繁茂バイオーム({@link com.yayuy.overgrownvegetation.registry.ModBiomes#isFullyOvergrown})の
         * チャンクを、時間経過で地表から下へ 1 層ずつ「深部浸食」し、最終的にチャンク内の全固体ブロックを
         * (地表より上の建築物・浮遊ブロック、地下深部の露出していない石・鉱石まで)汚染しきるか。
         * 地表のフロンティア浸食({@link #overgrownBiomeCreep})が済んだ列から順に、汚染が真下へ降りていく。
         */
        public final ModConfigSpec.BooleanValue overgrownBiomeCreepDeepCorruption;
        /** 深部浸食で、判定 1 回につき処理するチャンク数の上限。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepDeepChunksPerTick;
        /** 深部浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepDeepBlocksPerChunk;
        /** 深部浸食で、1 チャンクを 1 回処理するときに走査する Y 層数の上限(再起動直後の走査コスト上限)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepDeepLayersPerVisit;
        /** 深部浸食で、条件を満たした 1 ブロックを 1 回の走査で汚染する確率。1.0 で層ごとに一括、下げるほど不規則に降りる。 */
        public final ModConfigSpec.DoubleValue overgrownBiomeCreepDeepChance;
        /** 深部浸食で、あるチャンクを処理してから次に処理するまでの間隔(tick)。 */
        public final ModConfigSpec.IntValue overgrownBiomeCreepDeepRescanTicks;
        /**
         * 建築浸食({@link com.yayuy.overgrownvegetation.corruption.BuildCorrosion}): 繁茂系バイオームの中に
         * 後から置かれたブロック(建築物など)を、既存の汚染に面したものから少しずつ汚染していくか。
         */
        public final ModConfigSpec.BooleanValue buildCorrosion;
        /** 建築浸食の判定間隔(tick)。候補が残っているチャンクはこの間隔で続きを処理する。 */
        public final ModConfigSpec.IntValue buildCorrosionIntervalTicks;
        /** 建築浸食で、判定 1 回につき処理するチャンク数の上限。 */
        public final ModConfigSpec.IntValue buildCorrosionChunksPerPass;
        /** 建築浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。 */
        public final ModConfigSpec.IntValue buildCorrosionBlocksPerChunk;
        /** 建築浸食で、汚染に 1 面だけ接しているブロックを 1 回の判定で汚染する確率。 */
        public final ModConfigSpec.DoubleValue buildCorrosionBaseChance;
        /** 建築浸食で、汚染に接する面が 1 つ増えるごとに {@link #buildCorrosionBaseChance} へ加算する確率。 */
        public final ModConfigSpec.DoubleValue buildCorrosionChancePerNeighbor;
        /** 建築浸食で、汚染にまったく接していない建築(浮遊建築など)に「種」を 1 つ植える確率(チャンク 1 回の処理あたり)。 */
        public final ModConfigSpec.DoubleValue buildCorrosionSeedChance;
        /** 建築浸食で、空気に面していない(完全に埋まった)ブロックを候補にするのに必要な、汚染済み隣接面の数。 */
        public final ModConfigSpec.IntValue buildCorrosionBuriedNeighbors;
        /** 建築浸食で、候補が無かったチャンクを次に再走査するまでの間隔(tick)。 */
        public final ModConfigSpec.IntValue buildCorrosionIdleRescanTicks;
        /** 建築浸食を「繁茂」段階のバイオームでも走らせるか(既定は「過繁茂」のみ)。 */
        public final ModConfigSpec.BooleanValue buildCorrosionInOvergrowthStage;
        /** 木が生えたとき、近くの別の木との間を蔦(リアナ)で繋ぐか。 */
        public final ModConfigSpec.BooleanValue spawnConnectingVines;
        /**
         * 木が1本生えたとき、そもそも連結蔦の生成処理を試みる確率(0.0〜1.0)。
         * これに外れた木からは蔦が一切生えない(vineMinPerTree の下限保証よりも前に判定される)。
         * 全体の蔦の量がおよそこの確率倍に絞られる(既定 0.12 = 木の約12%だけが蔦を生成)。
         * 1.0 にすると従来どおり全ての木で生成を試みる。
         */
        public final ModConfigSpec.DoubleValue vinePerTreeChance;
        /** 繋げる相手の木が見つかったとき、実際に蔦を張る確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue vineChance;
        /**
         * 蔦の終点候補を探す際、見つかった候補ごとに、木の原木の代わりに近くの既存の蔦
         * (別の {@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity} 主アンカー)
         * の曲線上のランダムな1点を終点候補として差し替える確率(0.0〜1.0)。
         */
        public final ModConfigSpec.DoubleValue vineExistingVineTargetChance;
        /**
         * 既存の蔦の曲線上に接続点を取る際、蔦の始点・終点それぞれからこの割合ぶんの区間は
         * 接続点の候補から除外する(0.0〜0.5)。既定 0.25 なら曲線の中央 50%(割合 0.25〜0.75)
         * だけが接続点になり、蔦の端(木の幹の近くや、ぶら下がり蔦の付け根)に新しい蔦が
         * 集中しなくなる。0.0 で従来どおり(端すれすれを除く曲線全体、割合 0.15〜0.85)。
         */
        public final ModConfigSpec.DoubleValue vineExistingVineTargetEndMargin;
        /** 木が1本生えるたびに実際に張る蔦の本数(下限)。vineMaxPerTree との間からランダムに決まる。 */
        public final ModConfigSpec.IntValue vineMinPerTree;
        /** 蔦の最小の長さ(始点〜終点の直線距離、ブロック数)。これより近い木同士は繋がらない。 */
        public final ModConfigSpec.IntValue vineMinLength;
        /** 蔦の最大の長さ(始点〜終点の直線距離、ブロック数)。これより離れた木同士は繋がらない。 */
        public final ModConfigSpec.IntValue vineMaxLength;
        /** プレイヤーが「蔓」アイテムで手で張れる蔦の最大の長さ(始点〜終点の直線距離、ブロック数)。 */
        public final ModConfigSpec.IntValue vinePlaceMaxLength;
        /** 蔦の始点・終点の Y 座標の差の最大値(ブロック数)。これより高低差のある組み合わせは繋がらない。 */
        public final ModConfigSpec.IntValue vineMaxHeightDiff;
        /** 蔦の始点・終点が地面(に類する何か)からこれ未満しか離れていない場合、その候補は使わない。 */
        public final ModConfigSpec.IntValue vineMinGroundClearance;
        /** 蔦の始点・終点が地面(に類する何か)からこれを超えて離れている場合、その候補は使わない。 */
        public final ModConfigSpec.IntValue vineMaxGroundClearance;
        /**
         * 地面からの高さ帯を3段階(低・中・高)に分けて蔦の生える高さに偏りを持たせるための、
         * 「低」帯の上限(ブロック数、地面からの距離)。{@code vineMinGroundClearance}〜この値までが「低」帯。
         */
        public final ModConfigSpec.IntValue vineHeightBandLowMax;
        /** 高さ帯「中」の上限(ブロック数)。「低」帯の上限〜この値までが「中」帯、この値を超え{@code vineMaxGroundClearance}までが「高」帯。 */
        public final ModConfigSpec.IntValue vineHeightBandMidMax;
        /** 蔦の生える場所が高さ帯「低」に入る相対的な重み。「中」「高」との比で抽選される(合計が1.0である必要はない)。 */
        public final ModConfigSpec.DoubleValue vineHeightWeightLow;
        /** 蔦の生える場所が高さ帯「中」に入る相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineHeightWeightMid;
        /** 蔦の生える場所が高さ帯「高」に入る相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineHeightWeightHigh;
        /**
         * 始点・終点の直線距離がこれを超える場合、1本だけでは頼りなく見えるため、
         * 同じ始点・終点の組み合わせに対して蔦を複数本重ねて生成する
         * ({@link #vineStackMinCount}〜{@link #vineStackMaxCount} 本、垂れ具合は必ず異なる値)。
         */
        public final ModConfigSpec.IntValue vineStackLengthThreshold;
        /** 長距離接続(vineStackLengthThreshold 超)で重ねて生成する蔦の最小本数。 */
        public final ModConfigSpec.IntValue vineStackMinCount;
        /** 長距離接続(vineStackLengthThreshold 超)で重ねて生成する蔦の最大本数。 */
        public final ModConfigSpec.IntValue vineStackMaxCount;
        /**
         * 重ねて生成する蔦の本数が {@code vineStackMinCount}(=2本)になる相対的な重み。
         * {@link #vineStackCountWeight3}〜{@link #vineStackCountWeight6} と合わせて
         * 2本:3本:4本:5本:6本の比で本数を抽選する(既定 80:15:5:2:1 = 本数が多いほど
         * 生成されにくくなる単調減少)。vineStackMaxCount を既定の6より大きくした場合、
         * 7本目以降は vineStackCountWeight6 と同じ重みが使い回される。
         */
        public final ModConfigSpec.DoubleValue vineStackCountWeight2;
        /** 重ねる本数が3本になる相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineStackCountWeight3;
        /** 重ねる本数が4本になる相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineStackCountWeight4;
        /** 重ねる本数が5本になる相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineStackCountWeight5;
        /** 重ねる本数が6本(以上)になる相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineStackCountWeight6;
        /**
         * 重複蔦(ぶら下がり蔦)を生成する際、両端を1つの親の曲線上に乗せる代わりに、
         * 既に生成済みの別の重複蔦(または主線)をまたぐように両端をそれぞれ別の曲線上から
         * 選んで生成する確率(0.0〜1.0)。同じ組み合わせに対して重複蔦が2本以上
         * 生成済みの場合にのみ判定される。「またぐ」に該当しなかった重複蔦は
         * {@link #vineSecondTierShareStartWeight} 等の4択で第二/第三の蔦のどちらになるかが
         * 決まる——「またぐ」判定はそれとは独立した別枠(2026-08-25 時点でユーザーの明示的な
         * 選択により両立させている)。
         */
        public final ModConfigSpec.DoubleValue vineNestedCrossChance;
        /**
         * 重複蔦のうち、始点を第一の蔦(主線)の実際の始点と共有する(=第一の蔦の始点から
         * そのまま生える)「第二の蔦」になる相対的な重み。{@link #vineSecondTierShareEndWeight}/
         * {@link #vineSecondTierFloatingWeight}/{@link #vineThirdTierWeight} と合わせて
         * 4択の比率を決める(2026-08-25、既定 15:15:50:20)。
         */
        public final ModConfigSpec.DoubleValue vineSecondTierShareStartWeight;
        /** 終点を第一の蔦の実際の終点と共有する「第二の蔦」になる相対的な重み。 */
        public final ModConfigSpec.DoubleValue vineSecondTierShareEndWeight;
        /**
         * 始点・終点のどちらも第一の蔦の曲線上の点(第一の蔦自身の実際の始点・終点そのものではない)
         * になる「第二の蔦」になる相対的な重み——これが従来からの既定の垂れ下がり方。
         */
        public final ModConfigSpec.DoubleValue vineSecondTierFloatingWeight;
        /**
         * 第一の蔦(主線)からではなく、既に配置済みの「第二の蔦」の曲線上から生える
         * 「第三の蔦」になる相対的な重み。{@link #vineThirdTierMinDuplicateCount} 未満の
         * 場合、またはまだ第二の蔦が1本も配置されていない場合はこの選択肢自体が候補から
         * 除外される(残り3つの重みだけで抽選する)。第三の蔦からさらに孫(第四の蔦)が
         * 生えることはない(第三で打ち止め)。
         */
        public final ModConfigSpec.DoubleValue vineThirdTierWeight;
        /**
         * 第三の蔦が生成候補になるために必要な、この組み合わせ(束)における重複蔦の
         * 総本数(第一の蔦=主線を除く)の最小値。既定4は「重複蔦が3本以下の束では
         * 第三の蔦は生成されない」ことを意味する({@code vineStackMaxCount} が既定の6
         * (=重複蔦最大5本)であれば、5本重ねられた束(重複蔦4本)以上でこの条件を満たしうる)。
         */
        public final ModConfigSpec.IntValue vineThirdTierMinDuplicateCount;
        /** 木が1本生えるたびに実際に張る蔦の本数(上限)。vineMinPerTree との間からランダムに決まる。 */
        public final ModConfigSpec.IntValue vineMaxPerTree;
        /**
         * 垂れ下がりの「一番少ない段階」の量(ブロック数、段階1)。
         * {@link #vineSagMax} と合わせて、垂れ具合の段階数
         * ({@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} の
         * {@code SAG_TIERS}、既定6——{@code vineStackMaxCount} の既定値と同じにしてある。
         * これより多く重ねる場合は重複しない値が足りなくなるため、{@code SAG_TIERS} も
         * 合わせて引き上げる必要がある)ぶんに均等分割した範囲を決める。
         */
        public final ModConfigSpec.IntValue vineSagMin;
        /** 垂れ下がりの「一番多い段階」の量(ブロック数、最終段階)。実際の垂れ具合はこの範囲内の段階数からランダムに決まる。 */
        public final ModConfigSpec.IntValue vineSagMax;
        /**
         * 蔦の経路上に垂れ蔦(droop)が生成される際、通常の見た目(overgrown_liana_droop)の
         * 代わりに発光する見た目(overgrown_liana_droop_light)になる確率(0.0〜1.0)。
         * 発光する垂れ蔦は見た目だけでなく実際に vineDroopLightLevel の明るさの光源として機能する。
         */
        public final ModConfigSpec.DoubleValue vineDroopLightChance;
        /** 発光する垂れ蔦(overgrown_liana_droop_light)が放つ光レベル(0〜15、バニラの光源ブロックと同じ尺度)。 */
        public final ModConfigSpec.IntValue vineDroopLightLevel;
        /**
         * 蔦から鉛直方向に垂れる蔦(droop)のうち発光していないものが、時間経過で発光した(=熟した)
         * 状態へ変わる判定を、蔦1本につき何 tick ごとに行うか(20 tick = 1 秒)。判定のたびに
         * {@link #vineFruitRipenChance} で1本だけ発光させる。発光した垂れ蔦は素手の右クリックで
         * 「蔦の実」を1個収穫でき、収穫すると発光しない状態へ戻る(この繰り返し)。
         */
        public final ModConfigSpec.IntValue vineFruitRipenIntervalTicks;
        /**
         * {@link #vineFruitRipenIntervalTicks} ごとの判定で、発光していない垂れ蔦を1本
         * 発光(=熟した)状態にする確率(0.0〜1.0)。0.0 にすると垂れ蔦は生成時の状態から変化しなくなる
         * (時間経過で実がならなくなる)。
         */
        public final ModConfigSpec.DoubleValue vineFruitRipenChance;

        // 「胞子感染」デバフの設計値は config/overgrown_vegetation/effects.json へ移行
        // (infectionThresholdTicks / infectionDurationTicks / sporeInfectionPlayerDurationTicks /
        //  sporeInfectionLevel2PlayerDurationTicks / sporeInfectionLevel2MobDurationTicks /
        //  sporeInfectionLevel2AttackChance / sporeInfectionLevel2AttackDamageReduction /
        //  level2DetectionRangeBonus / sporeInfectionConcentrationThreshold + 旧ハードコードの
        //  防御力/移動速度低下率)。→ com.yayuy.overgrownvegetation.data.EffectTable

        /**
         * 「過繁茂した(MOB名)」が他の Mob/プレイヤーを追跡・攻撃する際の {@code MeleeAttackGoal} への
         * 指定速度(ブロック/tick、{@code Mob#setSpeed} と同じ単位)。種族ごとに基礎移動速度が違うため、
         * {@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal#install} がこの値を
         * 種族自身の {@code MOVEMENT_SPEED} 属性値で割って {@link net.minecraft.world.entity.ai.goal.MeleeAttackGoal}
         * の速度倍率を逆算する(全種族が同じ指定速度で追いかけてくるようにするため)。
         *
         * <p>目標(プレイヤーのダッシュ速度 0.13 の 0.9 倍 = 0.117)をそのまま入れると、
         * 経路探索(pathfinding)で動く Mob は直接操作のプレイヤーより体感速度が大きく落ちる
         * (追跡目標に向かって毎 tick 経路を引き直す・向きを合わせてから加速する、といった
         * オーバーヘッドがあるため)ため、実際に「プレイヤーの0.9倍」を体感できるようにするには
         * 素の値をかなり高めに設定する必要がある。既定 0.3 は「バニラのゾンビ自身の追跡速度
         * (=移動速度属性 0.23 そのまま)より明確に速い」を目安にした経験的な補正値で、
         * 実測に基づく厳密な値ではない。体感が合わなければこの値を調整してほしい。
         */
        public final ModConfigSpec.DoubleValue parasitizedChaseSpeed;
        /**
         * 「過繁茂した(MOB名)」が新規に索敵する際、まだ「胞子感染」にかかっていないクリーパー
         * (バニラの {@code Creeper})を、距離に関係なく最優先ターゲットにするか。既定 true。
         * 既に索敵範囲内のクリーパーが「胞子感染」にかかっている場合、そのクリーパーは優先されず
         * 通常どおり「最も近い相手」の一体として扱われる。現在ターゲットを追跡中の場合は
         * 見失うまで切り替えない({@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal#findTarget})。
         */
        public final ModConfigSpec.BooleanValue prioritizeUninfectedCreepers;
        /**
         * 「過繁茂した(MOB名)」系(過繁茂したスカトゥリウムも含む)が、茂み
         * ({@link com.yayuy.overgrownvegetation.ModTags#CONCEALING}、既定では
         * {@code overgrown_bush} のみ)の中に入っている相手を見失うか。既定 true。
         * 新規ターゲットとして選ばないだけでなく、追跡中の相手が茂みに逃げ込んだ場合も
         * {@link #bushConcealmentGraceTicks} の猶予後に見失う
         * ({@link com.yayuy.overgrownvegetation.effect.FoliageConcealment} 参照)。
         */
        public final ModConfigSpec.BooleanValue bushConcealment;
        /**
         * 追跡中の相手が茂みに逃げ込んでから、実際に見失うまでの猶予tick。既定 20(=1秒)。
         * 0 にすると茂みに入った瞬間に見失う。
         */
        public final ModConfigSpec.IntValue bushConcealmentGraceTicks;
        /**
         * 「過繁茂した(MOB名)」系(過繁茂したスカトゥリウムも含む)が、追跡中の標的へ実際にたどり着ける
         * 経路が無い状態のまま連続でこの tick 数を超えたら見失い、別の標的に切り替える。既定 60(=3秒)。
         * 壁や水路で完全に隔てられた相手をいつまでも突き崩そうとして壁際に張り付き続ける不具合の対策
         * ({@link com.yayuy.overgrownvegetation.effect.ParasitizedTargetGoal} 参照)。経路探索の
         * 実行自体は毎tickではなく間引いて行うため、値を極端に小さくしても即座には反映されない。
         */
        public final ModConfigSpec.IntValue unreachableTargetGraceTicks;
        /**
         * 「胞子感染」中の Mob が汚染ブロックから最低でも確保しようとする距離(ブロック)。
         * この距離未満(自分自身が汚染ブロックの上に乗っている場合も含む)だと
         * {@link com.yayuy.overgrownvegetation.effect.FleeCorruptionGoal} が逃げ続ける。
         */
        public final ModConfigSpec.IntValue infectionFleeMinDistance;
        /** 汚染死体ブロック({@code overgrown_corpse})が広げる侵食の半径(ブロック)。blight_fern の {@link #corruptionRadius} とは別の、小さめの値。 */
        public final ModConfigSpec.IntValue corpseCorruptionRadius;
        /**
         * 「過繁茂したクリーパー」({@code ParasitizedCreeper})が爆発したとき、周囲の Mob へ「胞子感染1」を
         * 付与する半径(ブロック)。爆発による地形破壊は起こさない。帯電クリーパーはこの 1.5 倍。
         * {@link com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation} 参照。
         */
        public final ModConfigSpec.DoubleValue parasitizedCreeperInfectionRadius;
        /**
         * 「過繁茂したクリーパー」が爆発したとき、その場を汚染ブロックへ変える球のおおよその半径
         * (ブロック)。実際の境界は {@link #parasitizedCreeperCorruptionNoise} 分だけ方向ごとに
         * 波打つので、綺麗な球にはならない。帯電クリーパーはこの 1.5 倍。
         */
        public final ModConfigSpec.IntValue parasitizedCreeperCorruptionRadius;
        /**
         * 「過繁茂したクリーパー」の爆発汚染の境界を方向ごとにどれだけ波打たせるか(ブロック、± の振幅)。
         * 0 にすると {@link #parasitizedCreeperCorruptionRadius} ちょうどの綺麗な球になる。
         */
        public final ModConfigSpec.DoubleValue parasitizedCreeperCorruptionNoise;

        // --- グループ4(拡大種): AbstractOvergrownInvaderEntity / InvaderSporeCrouchGoal ---

        /**
         * うずくまって胞子をばらまいている間に汚染する範囲の半径(ブロック、水平)。
         *
         * <p>グループ4 が汚染を広げるのは<b>この「うずくまり」の最中だけ</b>(ユーザー指示 2026-09-14)。
         * 歩いているだけでは何も汚さない。
         */
        public final ModConfigSpec.IntValue invaderCorruptionRadius;

        /**
         * グループ4 が消えるまでの距離(ブロック)。<b>この内側にプレイヤーが居る限り消えない</b>。
         *
         * <p>バニラの「32 ブロックより遠くで 30 秒何も無ければ抽選で消す」を止めるための値
         * ({@link com.yayuy.overgrownvegetation.entity.InvaderDespawn})。
         * 32 は「少し離れた」でしかないのに対し、グループ4 は離れて働く種なので成り立たない。
         * 小さくすると数は抑えられるが、フィロセンスの索敵・ヴェロフィルの着地汚染といった
         * 「時間が経ってから起きる」行動がまた発動しなくなる。
         */
        public final ModConfigSpec.IntValue invaderDespawnRange;

        // 旧いグループ4 の群れ(InvaderPack 系)の設定 invaderPack* 28 個・invaderPreyIntel* 2 個・velophyllPackLeash は
        // 2026-09-25 に群れ v2(swarm/、数値は design/swarm.json)へ置き換えて消した。toml に残った行は NeoForge が直すときに消える。

        // --- 過繁茂したスカトゥリウム: 迫撃砲と座り込み(ユーザー要望 2026-09-15)---

        /** 迫撃砲を撃ち始める最短距離(ブロック)。これより近ければ殴る/突進する。 */
        public final ModConfigSpec.DoubleValue shielderMortarMinRange;
        /** 迫撃砲の最大射程(ブロック)。実際に届くかは弾道計算で別に判定する。 */
        public final ModConfigSpec.DoubleValue shielderMortarMaxRange;
        /** 撃つ前に構える時間(tick)。 */
        public final ModConfigSpec.IntValue shielderMortarWindupTicks;
        /** 1 回の斉射で撃つ発数。 */
        public final ModConfigSpec.IntValue shielderMortarShots;
        /** 斉射の 1 発ごとの間隔(tick)。 */
        public final ModConfigSpec.IntValue shielderMortarShotIntervalTicks;
        /** 撃ち切ってから次の斉射までの間隔(tick)。 */
        public final ModConfigSpec.IntValue shielderMortarCooldownTicks;
        /** 着弾点の散らばり(ブロック、±)。0 にすると狙いが正確すぎて避けられなくなる。 */
        public final ModConfigSpec.DoubleValue shielderMortarScatter;
        /** 座って汚染を広げるときの水平半径(ブロック)。 */
        public final ModConfigSpec.IntValue shielderCorruptionRadius;

        // --- 経路探索(InvaderNavigation。ユーザー報告 2026-09-17「壁につっかえる」)---

        /** 経路探索にだけ使う射程(ブロック)。索敵距離とは別に持たせる。 */
        public final ModConfigSpec.DoubleValue invaderPathRange;
        /** 経路探索で展開してよいノード数の倍率。 */
        public final ModConfigSpec.DoubleValue invaderPathBudgetMultiplier;
        /** 経路を引けないのがこの回数続いたら「詰まった」扱いにする。 */
        public final ModConfigSpec.IntValue invaderPathFailuresBeforeStuck;
        /** 詰まったときに回り道の行き先を探す半径(ブロック)。 */
        public final ModConfigSpec.IntValue invaderUnstickRadius;

        // --- 水を避ける(LandMobWaterAvoidance。ユーザー要望 2026-09-19)---

        /** 陸のグループ1〜10 に「水を経路に使わない・汀を避ける」を一括で掛けるか。 */
        public final ModConfigSpec.BooleanValue landMobWaterAvoidance;
        /** 水に面したマスの通行コスト。大きいほど岸から離れた道を選ぶ(バニラ既定 8)。 */
        public final ModConfigSpec.DoubleValue landMobWaterBorderMalus;
        /** 自分より地表からの深さがこれ以上深い相手は索敵しない(ブロック)。 */
        public final ModConfigSpec.DoubleValue landMobMaxTargetDepth;
        /** 標的への経路が引けない状態がこれだけ続いたら、水を跳び越えてよい(tick)。 */
        public final ModConfigSpec.IntValue invaderWaterLeapDelayTicks;

        // --- アルボラの坑道掘り(ArboraTunnelGoal。ユーザー要望 2026-09-17)---

        /** アルボラが標的への坑道を掘るか。 */
        public final ModConfigSpec.BooleanValue arboraDigEnabled;
        /** 1 マス抜くのに掛かる時間(tick)。 */
        public final ModConfigSpec.IntValue arboraDigTicksPerBlock;
        /** この距離までの標的にしか掘らない(ブロック)。 */
        public final ModConfigSpec.DoubleValue arboraDigRange;
        /** 1 本の坑道で抜いてよいマスの上限。 */
        public final ModConfigSpec.IntValue arboraDigMaxBlocks;
        /** 抜いたマスのまわりを汚染する半径(ブロック)。 */
        public final ModConfigSpec.IntValue arboraDigCorruptRadius;
        /** 相手がこれ以上高いところに居るあいだは掘らず、壁を登って行く(ブロック)。 */
        public final ModConfigSpec.DoubleValue arboraDigMaxTargetRise;
        /** 螺旋階段の半径(ブロック)。 */
        public final ModConfigSpec.IntValue arboraDigSpiralRadius;
        /** 水平距離がこれ未満で高低差が大きいときは螺旋階段を掘る(ブロック)。 */
        public final ModConfigSpec.DoubleValue arboraDigSpiralThreshold;
        /** 掘れないものに当たったとき、その相手を諦めている時間(tick)。 */
        public final ModConfigSpec.IntValue arboraDigGiveUpTicks;
        /** 掘り始めた高さからこれより下へは掘らない(ブロック)。 */
        public final ModConfigSpec.IntValue arboraDigMaxDescent;
        /** 経路探索で壁を 1 マス登る辺に乗せる重み。大きいほど登らず遠回りする。 */
        public final ModConfigSpec.DoubleValue arboraClimbPathMalus;

        // --- うずくまって胞子を撒く(InvaderSporeCrouchGoal・群れの SPREAD)---

        /** うずくまっている間、何 tick に 1 マス汚染するか。範囲を汚し尽くすまで続く。 */
        public final ModConfigSpec.IntValue invaderSporeCorruptionIntervalTicks;
        /** グループ4 が次にうずくまれるようになるまでの間隔(tick)。実際は ±25% ばらつく。 */
        public final ModConfigSpec.IntValue invaderSporeIntervalTicks;
        /** うずくまっている間に「胞子感染1」を付ける半径(ブロック)。プレイヤーとグループ1〜10 は対象外。 */
        public final ModConfigSpec.DoubleValue invaderSporeInfectRadius;
        /** うずくまり 1 回あたり、終わりぎわに汚染シダが芽吹く確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue invaderFernSpawnChance;
        /**
         * うずくまり 1 回あたり、終わりぎわに過繁茂 Mob が 1 体増える確率(0.0〜1.0)。
         * 増えるのは今のフェーズで過繁茂バイオームに湧ける種のうち、陸生でグループ4 以外のもの。
         */
        public final ModConfigSpec.DoubleValue invaderMobSpawnChance;

        /** 周囲 24 ブロックにグループ所属の Mob がこの数以上いるときは、上の増殖を行わない。 */
        public final ModConfigSpec.IntValue invaderMobSpawnNearbyCap;
        /**
         * グループ4 のとびかかりを盾で弾ける猶予(tick)。<b>盾を構え始めてからこの tick 以内</b>に
         * 当たったときだけ弾ける({@link com.yayuy.overgrownvegetation.entity.ShieldParry})。
         * 既定 10 = 0.5 秒。構えっぱなしでは弾けず、突進に合わせて構え直す操作になる。
         */
        public final ModConfigSpec.IntValue invaderParryWindowTicks;
        // --- フィロセンス(グループ4・索敵役): PhyllosensEntity / PhyllosensScoutGoal / PhyllosensBeamGoal ---

        /** ふだんの索敵距離(ブロック)。{@code mobs.json} の {@code followRange} と揃えておく。 */
        public final ModConfigSpec.DoubleValue phyllosensSightRange;
        /** ふだん「視線」「誘導」を配る半径(ブロック)。索敵に入る判定もこの半径で見る。 */
        public final ModConfigSpec.DoubleValue phyllosensAuraRange;
        /** ふだんの印を配り直す間隔(tick)。 */
        public final ModConfigSpec.IntValue phyllosensAuraIntervalTicks;
        /** この時間プレイヤーが {@code phyllosensAuraRange} に居なければ索敵に入る(tick)。 */
        public final ModConfigSpec.IntValue phyllosensQuietTicks;
        /** 索敵中に「視線」を配る半径(ブロック)。 */
        public final ModConfigSpec.DoubleValue phyllosensScoutWatchRange;
        /** 索敵中に「誘導」を配る半径(ブロック)。 */
        public final ModConfigSpec.DoubleValue phyllosensScoutGuidanceRange;
        /** 索敵中、印を配り直す間隔(tick)。範囲が広いので毎 tick はやらない。 */
        public final ModConfigSpec.IntValue phyllosensScanIntervalTicks;
        /** 1 回の索敵を続ける長さ(tick)。 */
        public final ModConfigSpec.IntValue phyllosensScoutDurationTicks;
        /** 索敵中に汚染を広げる水平半径(ブロック)。アルボラの {@code invaderCorruptionRadius} より広い。 */
        public final ModConfigSpec.IntValue phyllosensCorruptionRadius;
        /** 「視線」の持続(tick)。範囲外へ出てからこれだけで切れる。 */
        /** 「誘導」の持続(tick)。切れた Mob は追跡をやめて元の行動へ戻る。 */
        /** 視線持ちへ向かうとき、この人数の味方が周りに集まるまで待つ。0 で「待たない」。 */
        public final ModConfigSpec.IntValue phyllosensRallyMinAllies;
        /** 合流を待つ上限(tick)。これを過ぎたら人数が足りなくても向かう。 */
        public final ModConfigSpec.IntValue phyllosensRallyMaxTicks;

        /** ビームのダメージ。防具は効く。 */
        /** ビームの射程(ブロック)。これより遠い相手には近づいてから撃つ。 */
        /** ビームの当たり判定の太さ(ブロック)。ティタニカの精密ビームより細い。 */
        /** ビームの見た目の太さ(ブロック)。 */
        /** 溜めの長さ(tick)。この間は頭が相手を追う。既定 60 = 3 秒。 */
        /** 狙点を固定してから発射するまで(tick)。既定 10 = 0.5 秒。 */
        /** 撃ったあと次に撃てるまで(tick)。 */

        // --- ヴェロフィル(グループ4・爆撃役): VelophyllEntity / VelophyllBombGoal / VelophyllGroundSporeGoal ---

        /** 標的を見つける距離(ブロック)。{@code mobs.json} の {@code followRange} と揃えておく。 */
        public final ModConfigSpec.DoubleValue velophyllSightRange;
        /** 地表からこれより深いところにいる相手は狙わない(ブロック)。爆弾が届かないため。 */
        public final ModConfigSpec.IntValue velophyllMaxTargetDepth;

        // --- 噛みつき(空中の相手。ユーザー要望 2026-09-17)---

        /** 地表からこれだけ上に浮いている相手は「空中の相手」= 爆撃ではなく噛みつきで襲う(ブロック)。 */
        public final ModConfigSpec.DoubleValue velophyllBiteMinAltitude;
        /** 噛みつきが届く距離(体の中心からの半径、ブロック)。 */
        public final ModConfigSpec.DoubleValue velophyllBiteReach;
        /** 一度噛んでから次に噛めるまで(tick)。 */
        public final ModConfigSpec.IntValue velophyllBiteCooldownTicks;
        /** 噛んだあと、離脱して向き直すのに使う時間(tick)。 */
        public final ModConfigSpec.IntValue velophyllBiteBreakOffTicks;
        /** ふだん飛ぶ高さ(地表からのブロック数)。 */
        public final ModConfigSpec.IntValue velophyllCruiseAltitude;
        /** 爆弾を落とす高さ(標的の頭上からのブロック数)。 */
        public final ModConfigSpec.IntValue velophyllDropAltitude;
        /** 1 回の爆撃で落とす爆弾の数。1 発ずつ順に落とす。 */
        /** 爆弾 1 発ごとの間隔(tick)。 */
        /** 撃ち切ってから次の爆撃に入れるまで(tick)。 */
        /** 爆弾を落とせない間、標的の周りを旋回する半径(ブロック)。 */
        public final ModConfigSpec.DoubleValue velophyllCircleRadius;
        /** 1 tick に曲げられる向きの上限(度)。小さいほど旋回半径が大きくなる。 */
        public final ModConfigSpec.DoubleValue velophyllTurnRate;
        /** 木の天蓋の上をどれだけ空けて飛ぶか(ブロック)。森の上で水平爆撃になる分かれ目。 */
        public final ModConfigSpec.IntValue velophyllCanopyClearance;
        /** 投下高度を決めるとき、標的の周り何ブロックまでの木の高さを見るか。 */
        public final ModConfigSpec.IntValue velophyllCanopyScanRadius;
        /** 直進しているときの巡航速度の上限。 */
        public final ModConfigSpec.DoubleValue velophyllCruiseSpeed;
        /** この時間プレイヤーを見つけられなければ地上へ降りて胞子をばらまく(tick)。 */
        public final ModConfigSpec.IntValue velophyllQuietTicks;
        /** 地上でばらまくときに汚染を広げる水平半径(ブロック)。 */
        public final ModConfigSpec.IntValue velophyllCorruptionRadius;
        /** 地上に留まっていられる上限(tick)。汚し尽くす前でもこれで切り上げて飛び立つ。 */
        public final ModConfigSpec.IntValue velophyllGroundMaxTicks;

        /** 胞子爆弾の爆風が届く半径(ブロック)。ティタニカの爆弾より広い。 */
        /** 爆風に巻き込まれたときのダメージ。距離では減衰しない。 */
        /** 爆弾が直撃したときのダメージ(爆風とは別に、当たった 1 体だけが受ける)。 */
        /** 着弾点の地表を汚染する半径(ブロック)。 */
        /** 汚染の輪郭を波打たせる振幅(ブロック、±)。0 で綺麗な円。 */
        /** 爆風のノックバックの強さ。 */
        /** 爆風で胞子感染させる半径(ブロック)。0 で無効。 */
        /** 爆発したときに葉を薙ぎ払う半径(ブロック)。0 で無効。 */

        // --- スポロフィル(グループ4・支援役): SporophyllEntity / SporophyllAura ---

        /** 傘の胞子が届く半径(ブロック)。バフ・デバフ・濃度上昇・感染の進行すべてこの半径。 */
        public final ModConfigSpec.DoubleValue sporophyllAuraRange;
        /** 効果を配り直す間隔(tick)。 */
        public final ModConfigSpec.IntValue sporophyllAuraIntervalTicks;
        /** 味方に配るバフの持続(tick)。範囲内に居る限り配り直されるので途切れない。 */
        /** 敵に配るデバフの持続(tick)。 */
        /** 味方バフ(再生・攻撃力上昇・耐性)の強さ。0 = I、1 = II。 */
        /** 敵デバフ「纏わりついた胞子」(移動速度低下・攻撃力低下を含む)の段階。0 = I、1 = II。 */
        /** 装備の胞子汚染を進める間隔(tick)。 */
        public final ModConfigSpec.IntValue sporophyllGearContaminationIntervalTicks;
        /** 1 回あたりに進める装備の胞子汚染(%)。 */
        public final ModConfigSpec.DoubleValue sporophyllGearContaminationPercent;
        /** 足元の胞子濃度に上乗せする値(%)。中心で最大、半径の縁で 0 まで落ちる。 */
        public final ModConfigSpec.DoubleValue sporophyllConcentrationBoostPercent;
        /** 「スポロフィルの胞子」持ちの攻撃が無視する防御力の割合(0〜1)。 */
        /** 「スポロフィルの胞子」持ちの攻撃が防具の耐久値を削る量(与ダメージ × この値)。 */
        /** 「纏わりついた胞子」を持つ相手が、グループ4 の Mob から余計に見つかる距離(ブロック)。 */
        /** 範囲内で胞子感染が満了したとき、1 段階進める(過繫茂バイオームと同じ扱いにする)か。 */
        /** 稼働中の胞子除去装置の圏内でスポロフィルのバフ・デバフが消えるか。 */
        public final ModConfigSpec.BooleanValue sporophyllPurifierClearsAura;
        /** 除去装置の圏内かを見直す間隔(tick)。スポロフィルの効果が付いている個体だけが対象。 */
        public final ModConfigSpec.IntValue sporophyllPurifierCheckIntervalTicks;

        /**
         * スポロフィルが<b>常時</b>汚染し続ける水平半径(ブロック)。0 で汚染しない。
         * ほかのグループ4 と違い、うずくまりではなく立っていても歩いていても撒き続ける。
         */
        public final ModConfigSpec.IntValue sporophyllCorruptionRadius;
        /** 常時汚染で、何 tick に 1 マス汚すか。 */
        public final ModConfigSpec.IntValue sporophyllCorruptionIntervalTicks;
        /** これだけダメージを受けるごとにアルボラを呼ぶ。0 で呼ばない。 */
        public final ModConfigSpec.DoubleValue sporophyllSummonDamage;
        /** 1 回に呼ぶアルボラの数。 */
        public final ModConfigSpec.IntValue sporophyllSummonCount;
        /** 周囲 16 ブロックにグループ所属の Mob がこの数以上いるときは呼ばない。0 で無制限。 */
        public final ModConfigSpec.IntValue sporophyllSummonNearbyCap;

        /** グループ4 が死んだ瞬間に汚染する球のおおよその半径(ブロック)。 */
        public final ModConfigSpec.IntValue invaderDeathCorruptionRadius;
        /**
         * 死亡時汚染の境界を方向ごとに波打たせる振幅(ブロック、±)。
         * 0 にすると {@link #invaderDeathCorruptionRadius} ちょうどの綺麗な球になる。
         */
        public final ModConfigSpec.DoubleValue invaderDeathCorruptionNoise;

        /**
         * 汚染死体ブロックが起点から放射状に伸ばす「根」の本数。blight_fern の
         * {@link #tendrilInitialCount} とは別の専用設定 — blight_fern 側だけ「3本の根+密な
         * 横広がり」に調整したので、汚染死体ブロックの見た目を変えないためにこちらを分けてある。
         */
        public final ModConfigSpec.IntValue corpseTendrilInitialCount;
        /**
         * 汚染死体ブロックの触手が同時に存在できる最大本数。blight_fern の
         * {@link #tendrilMaxActive} とは別の専用設定(理由は {@link #corpseTendrilInitialCount} 参照)。
         */
        public final ModConfigSpec.IntValue corpseTendrilMaxActive;
        /**
         * 死体ブロックが自分の半径を全て汚染し終えたとき、その場を消して代わりに種族対応の
         * 「過繁茂した(MOB名)」を孵化させる確率(0.0〜1.0)。外れた場合はその場に汚染シダが
         * 生える({@link com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity} 参照)。
         */
        public final ModConfigSpec.DoubleValue corpseHatchChance;

        /**
         * 巨大樹({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})を1ゲームティックあたり
         * 何ブロックずつ設置するか。巨大樹は約5万ブロックあり一度に置くとサーバーが固まるため、
         * この数ずつ複数ティックに分けて生成する({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue})。
         * 小さくすると1本あたりの生成が遅く(=ゆっくり生えて)なるが1ティックあたりの負荷は軽くなる。
         * 同時に複数本生成中の場合も、全部でこの数を超えないよう配分される。
         */
        public final ModConfigSpec.IntValue giantTreeBlocksPerTick;

        /**
         * 巨大樹同士がこのブロック数(水平距離)より近くには生えないようにする。新しい巨大樹を生成
         * しようとしたとき、この範囲内に既存の巨大樹(これまでにこのワールドで生成された古代樹の核の
         * 位置、{@link com.yayuy.overgrownvegetation.phase.PhaseData} が記録)があれば生成を中止する。
         * 0 で無効(間隔チェックなし)。苗木から育てる場合も同じチェックが働き、近すぎる場合は苗木の
         * まま育たない。既存ワールドで導入前に生えていた巨大樹は記録されていないので対象外。
         */
        public final ModConfigSpec.IntValue giantTreeMinSpacing;

        /**
         * クリエイティブ専用の「巨大樹の苗木」から育てるときは
         * {@link #giantTreeMinSpacing} の間隔チェックと「洞窟内か」の判定を飛ばして必ず巨大樹を
         * 生やすか。既定 true(好きな場所で試せるように)。false にすると自然生成と同じ扱いになり、
         * 既存の巨大樹の近くや屋根の下では苗木のまま育たなくなる。
         */
        public final ModConfigSpec.BooleanValue giantTreeSaplingForceGrowth;

        /**
         * 巨大樹の幹の真下に、将来の mob「hive node」用の球状の空洞「巣の広間」を作るか
         * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature} の {@code buildHiveChamber})。
         */
        public final ModConfigSpec.BooleanValue giantTreeHiveChamber;

        /** 「巣の広間」の半径(ブロック)。球の表面が殻、内側はすべて空気になる。 */
        public final ModConfigSpec.IntValue giantTreeHiveChamberRadius;

        /**
         * 「巣の広間」の球を、幹の裾の半径よりこのブロック数だけ必ず太くする(0 で無効 = 半径をそのまま使う)。
         * 幹の裾は木の大きさで半径 11〜18 まで変わるので、これが無いと球が幹の中に埋もれて外から見えない。
         */
        public final ModConfigSpec.IntValue giantTreeHiveChamberTrunkMargin;

        /** 「巣の広間」の中心を、巨大樹の根本の地面から何ブロック下に置くか(既定 0 = 球の上半分が地上に出る)。 */
        public final ModConfigSpec.IntValue giantTreeHiveChamberDepth;

        /** 「巣の広間」の球の外側を、巨大樹の原木で何層ぶん覆うか(0 で覆わない)。 */
        public final ModConfigSpec.IntValue giantTreeHiveChamberBarkThickness;

        /** 「ハイヴノード」が鼓動する間隔(tick)。演出だけで、周囲には何の影響も与えない(2026-09-19)。 */
        public final ModConfigSpec.IntValue hiveNodePulseIntervalTicks;

        /** 「ハイヴノード」を球の中心から何ブロック上にずらすか(0 = ちょうど中心)。 */
        public final ModConfigSpec.IntValue hiveNodeHeightOffset;

        /** 「巣の球」の中心に掘る、ハイヴノードの小部屋の半径(ブロック)。 */
        public final ModConfigSpec.IntValue hiveNodeCavityRadius;

        /** 小部屋の隣の「儀式の広間」の半幅(ブロック)。広間の幅は 2×半幅+1。 */
        public final ModConfigSpec.IntValue hiveRitualChamberHalfWidth;

        /** 「儀式の広間」の高さ(ブロック)。演出の蔦は高さ 3 の空間を前提に作ってある。 */
        public final ModConfigSpec.IntValue hiveRitualChamberHeight;

        /** 広間から球面の出口へうねる通路の太さ(半径、ブロック)。 */
        public final ModConfigSpec.DoubleValue hiveTunnelRadius;

        /** 通路のうねりの強さ(0 = ほぼ直線、1 = 大きく蛇行)。半径と高さの揺れになる。 */
        public final ModConfigSpec.DoubleValue hiveTunnelWinding;

        /** 通路がハイヴノードの小部屋の周りを何周らせん状に回ってから出口に抜けるか(既定 1.5 周)。 */
        public final ModConfigSpec.DoubleValue hiveTunnelTurns;

        /** 通路・広間の壁面(巨大樹の幹)に下草・蔦・垂れ植物を生やす 1 面あたりの確率(0 で無し)。 */
        public final ModConfigSpec.DoubleValue hiveTunnelGrassChance;

        // ---- 寄生植物装備(仕様と段階の表は item/PlantArmor)
        /** 2 段階目になるまでの装着時間(分)。 */
        public final ModConfigSpec.IntValue plantArmorStage2Minutes;
        /** 3 段階目になるまでの装着時間(分)。 */
        public final ModConfigSpec.IntValue plantArmorStage3Minutes;
        /** 着けている間、毎秒足す食料の疲労値(4.0 で食料 1 目盛り)。 */
        public final ModConfigSpec.DoubleValue plantArmorExhaustionPerSecond;
        /** 耐久値を 1 回復する間隔(tick)。 */
        public final ModConfigSpec.IntValue plantArmorRepairIntervalTicks;
        /** 2 段階目以降に空腹 II(14 秒)が付く間隔(秒)。 */
        public final ModConfigSpec.IntValue plantArmorHungerEffectIntervalSeconds;
        /** Mob を倒したとき回復する食料。 */
        public final ModConfigSpec.IntValue plantArmorKillFood;
        /** グループ 1〜3 か過繫茂 Mob 以外を倒したとき、装備の成長時間が進む秒数。 */
        public final ModConfigSpec.IntValue plantArmorKillGrowthSeconds;
        /** 「大きな未発達な果実」を食べたとき、装備の成長時間が進む秒数。 */
        public final ModConfigSpec.IntValue plantArmorFruitGrowthSeconds;
        /** 「スポロフィルの胞子」バフ中の成長速度の倍率。 */
        public final ModConfigSpec.DoubleValue plantArmorSporophyllGrowthMultiplier;
        /** 3 段階目: 新しく踏んだマスの真下を汚染する確率。 */
        public final ModConfigSpec.DoubleValue plantArmorWalkCorruptionChance;
        /** 3 段階目の光合成中、周囲に汚染シダが湧く確率の倍率。 */
        public final ModConfigSpec.DoubleValue plantArmorPhotosynthesisFernMultiplier;

        /** 通路の出口を開ける仰角の下限(度、0 = 球の赤道 = 地面の高さ)。 */
        public final ModConfigSpec.IntValue hiveTunnelEntranceElevationMin;

        /** 通路の出口を開ける仰角の上限(度)。 */
        public final ModConfigSpec.IntValue hiveTunnelEntranceElevationMax;


        /**
         * 巨大樹が生えきったあと、その表面(原木)と葉の露出面に、汚染ブロックと同じ装飾植物
         * (シダ・苔・蔦・垂れ植物)を生やす際の 1 面あたりの確率。0 で無効。
         * 通常の汚染({@link #growthChance})より低めにしておかないと 1 本で大量の植物が生えて重くなる。
         */
        public final ModConfigSpec.DoubleValue giantTreePlantChance;

        /**
         * 巨大樹が生えきったあと、地表を這う<b>根のかまぼこ状の盛り上がりの頂上</b>に、汚染時と同じ
         * 下草(シダ・苔・背高植物)を生やす際の確率。0 で無効。根の上だけを狙って
         * {@link #giantTreePlantChance}(表面全体)より高めにできるので、根が地面と一体化して
         * 苔むして見えるようにするための専用ノブ。
         */
        public final ModConfigSpec.DoubleValue giantTreeRootPlantChance;

        /**
         * 巨大樹が生えきったあと、その表面(原木)の露出面にヒカリゴケ({@code minecraft:glow_lichen})を
         * 貼り付ける際の 1 面あたりの確率。0 で無効。ヒカリゴケは光源なので、上げすぎると光更新が
         * 大量に走って重くなる(1本の巨大樹に数百個貼られうる)。
         */
        public final ModConfigSpec.DoubleValue giantTreeGlowLichenChance;

        /**
         * 巨大樹の幹の内側(形成層)に埋め込む「クロロフィライトの鉱石」の<b>鉱石ノード</b>の本数の
         * 最小値。1ノード = {@link #giantTreeChlorophylliteNodeRadius} の球状の塊。地中の鉱脈のように、
         * 形成層で埋まった空間のランダムな位置に配置される。
         */
        public final ModConfigSpec.IntValue giantTreeChlorophylliteNodesMin;

        /** 「クロロフィライトの鉱石」ノードの本数の最大値(最小値との間でランダム)。 */
        public final ModConfigSpec.IntValue giantTreeChlorophylliteNodesMax;

        /**
         * 「クロロフィライトの鉱石」ノード1個の塊の半径(球状)。大きいほど1ノードあたりの鉱石量が増える。
         * 小数で指定できる(整数刻みだと1段変えるだけで体積が倍近く変わってしまうため)。
         */
        public final ModConfigSpec.DoubleValue giantTreeChlorophylliteNodeRadius;

        /**
         * 巨大樹1本に「硬化樹液結晶の鉱石」ノードが<b>まったく生成されない</b>確率の裏返し。
         * この確率を引いたときだけ {@link #giantTreeHardenedSapCrystalNodesMin}〜Max 本の
         * ノードが追加で埋め込まれる(たまにしか出ないレア鉱石)。
         */
        public final ModConfigSpec.DoubleValue giantTreeHardenedSapCrystalChance;

        /** 「硬化樹液結晶の鉱石」ノードが生成される場合の本数の最小値。 */
        public final ModConfigSpec.IntValue giantTreeHardenedSapCrystalNodesMin;

        /** 「硬化樹液結晶の鉱石」ノードが生成される場合の本数の最大値(最小値との間でランダム)。 */
        public final ModConfigSpec.IntValue giantTreeHardenedSapCrystalNodesMax;

        /** 「硬化樹液結晶の鉱石」ノード1個の塊の半径(球状)。小数可。 */
        public final ModConfigSpec.DoubleValue giantTreeHardenedSapCrystalNodeRadius;

        // フェーズの昇格条件・内容は config/overgrown_vegetation/phases.json へ移行
        // (startWorldAgeTicks / startAdvancements / points.* / pointsToAdvance /
        //  phase2Group1HealthMultiplier / phase2Group1AttackMultiplier / phase2Group1FleeCorruption)。
        // → com.yayuy.overgrownvegetation.data.PhaseTable

        /**
         * フェーズ 1・2 で、自然スポーンする対象 Mob({@code #overgrown_vegetation:phase_infectable_animals})が
         * 湧いた瞬間から「胞子感染」を持っている基本確率(0.0〜1.0)。持続時間は {@link #infectionDurationTicks} と共通。
         */
        public final ModConfigSpec.DoubleValue phase1InfectionChance;
        /**
         * フェーズ 1 以降、過繁茂バイオーム({@link com.yayuy.overgrownvegetation.registry.ModBiomes#OVERGROWN})の
         * 中には自然スポーン設定が無いため、{@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner} が
         * プレイヤーの周囲の過繁茂バイオームに直接「過繁茂した Mob」を湧かせる。その判定間隔(tick)。
         */
        public final ModConfigSpec.IntValue overgrownSpawnIntervalTicks;
        /** スポーン判定 1 回あたり、各プレイヤーについて実際に補充を行う確率(0.0〜1.0)。 */
        public final ModConfigSpec.DoubleValue overgrownSpawnChance;
        /** 1 パスで 1 種に許容する上限数(＝群れの大きさの上限)。種ごとの {@code clusterMax} と小さい方が使われる。 */
        public final ModConfigSpec.IntValue overgrownSpawnGroupMax;
        /**
         * 過繁茂バイオームの生態系スポーンで、プレイヤー周辺(半径 {@link #overgrownSpawnRadius} +
         * {@link #overgrownSpawnCountMargin})に維持しようとする「過繁茂した Mob」の総数の目安。
         * 種ごとの目標数は {@code OvergrownSpawnProfile} の weight でこの数を比例配分して決まる
         * (1 種あたり最低 1 体は付くので、実際の合計はこの数より多くなることがある)。
         */
        public final ModConfigSpec.IntValue overgrownSpawnTargetTotal;
        /** 1 パス・1 プレイヤーあたりに新規スポーンさせる「過繁茂した Mob」の最大数(目標数まで数パスかけて埋める)。 */
        public final ModConfigSpec.IntValue overgrownSpawnMaxPerPass;
        /** 実個体数を数える範囲を {@link #overgrownSpawnRadius} からどれだけ広げるか(ブロック)。スポーン範囲の縁での湧き過ぎを防ぐ。 */
        public final ModConfigSpec.IntValue overgrownSpawnCountMargin;
        /** 過繁茂バイオームへの直接スポーンの、プレイヤーからの水平距離(ブロック)。この範囲の過繁茂バイオーム地表に湧かせる。 */
        public final ModConfigSpec.IntValue overgrownSpawnRadius;
        /**
         * 過繁茂バイオーム({@link com.yayuy.overgrownvegetation.registry.ModBiomes#OVERGROWN})の中で、
         * 「過繁茂した(MOB名)」・blight_fern 以外の Mob の自然湧き(自然/巡回隊/増援など)を打ち消すか。
         * true にすると、汚染ブロックの上に湧こうとした通常 Mob は打ち消され、汚染されていない
         * ブロックの上に湧こうとした通常 Mob は {@link #overgrownBiomeWildSpawnInfectChance} で
         * 「胞子感染1」を持って湧く({@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawnFilter} 参照)。
         */
        public final ModConfigSpec.BooleanValue overgrownBiomeBlockWildSpawns;
        /**
         * {@link #overgrownBiomeBlockWildSpawns} が true のとき、過繁茂バイオームの中の
         * 「汚染されていないブロックの上」に湧いた通常 Mob が「胞子感染1」を持って湧く確率(0.0〜1.0)。
         * 既定 1.0(必ず感染して湧く)。
         */
        public final ModConfigSpec.DoubleValue overgrownBiomeWildSpawnInfectChance;

        // --- 胞子濃度 ---
        /**
         * 「胞子濃度」の割合成分を計算する正方領域の半径(チャンク)。既定 3(7x7=49 チャンク)。
         * {@link com.yayuy.overgrownvegetation.spore.SporeConcentration} 参照。
         */
        public final ModConfigSpec.IntValue sporeConcentrationRadiusChunks;
        /**
         * 上記領域のうち過繁茂バイオームが占める割合がこの値に達したら、割合成分を 100% とする
         * (それ以上は上がらない)。既定 0.8 = 8 割。
         */
        public final ModConfigSpec.DoubleValue sporeConcentrationFullRatio;
        /**
         * 最寄りの過繁茂バイオームチャンクと「密接」(隣接・斜め隣接)しているときに、割合に関係なく
         * 胞子濃度へ加算する値(%)。既定 50。
         */
        public final ModConfigSpec.DoubleValue sporeConcentrationBiomeAdjacentPercent;
        /** 最寄りの過繁茂バイオームチャンクとの間に 1 チャンク挟まっているときの加算値(%)。既定 25。 */
        public final ModConfigSpec.DoubleValue sporeConcentrationBiomeGap1Percent;
        /** 最寄りの過繁茂バイオームチャンクとの間に 2 チャンク挟まっているときの加算値(%)。既定 5。 */
        public final ModConfigSpec.DoubleValue sporeConcentrationBiomeGap2Percent;
        /**
         * そのチャンクの 16x16=256 列のうち「地面(またはその上に生えた植物)が汚染ブロック」な列 1 つあたり、
         * 胞子濃度へ加算する値(%)。既定 1.0(100 列で +100% = 上限)。葉・原木・蔦・下草は貫通して
         * 地面を見る。バイオームがまだ書き換わっていない(=過繁茂バイオームではない)チャンクでも、
         * 汚染が進むほど胞子濃度が上がる。0 でこの成分を無効化(走査自体も省略)。
         */
        public final ModConfigSpec.DoubleValue sporeConcentrationPerCorruptedBlockPercent;
        /**
         * 汚染ブロック成分の 256 列走査を、何ブロック間隔のグリッドで間引きサンプリングするか。
         * 既定 2(64 列だけ調べて 256 列全体へ外挿。負荷約 1/4)。1 で全 256 列を厳密に走査。大きいほど軽いが粗い。
         */
        public final ModConfigSpec.IntValue sporeConcentrationCorruptedScanStride;
        /** 胞子濃度の計算結果を次元ごと・チャンクごとにキャッシュする時間(tick)。既定 200(10 秒)。 */
        public final ModConfigSpec.IntValue sporeConcentrationCacheTtlTicks;
        /**
         * 「胞子感染1」の滞在判定を、各エンティティにつき何 tick に 1 回行うか。既定 20(1 秒)。
         * 判定 1 回で滞在カウントをこの間隔ぶん進める/0 に戻すので、閾値の意味は変わらない。
         * 1 にすると毎 tick 判定(重い)。
         */
        public final ModConfigSpec.IntValue sporeInfectionCheckIntervalTicks;
        // sporeInfectionConcentrationThreshold は effects.json(sporeInfection.concentrationThreshold)へ移行。

        // --- レイド(過繫茂の苗床) ---
        /** レイドイベント(「過繫茂の苗床」)全体の有効/無効。発生フェーズは phases.json の raidEnabled。 */
        public final ModConfigSpec.BooleanValue raidEnabled;
        // レイドが発生し始めるフェーズは phases.json の各フェーズ raidEnabled から導出される
        // (PhaseTable.raidMinPhase())。旧 raidMinPhase は廃止。
        /** 周期レイドの最短間隔(tick)。実際の間隔は min〜max でランダムに決まる。 */
        public final ModConfigSpec.IntValue raidPeriodicMinTicks;
        /** 周期レイドの最長間隔(tick)。 */
        public final ModConfigSpec.IntValue raidPeriodicMaxTicks;
        /** 発生条件が揃わなかった(プレイヤー不在・適地なし・同時数上限)ときの再挑戦までの待ち時間(tick)。 */
        public final ModConfigSpec.IntValue raidRetryTicks;
        /** 同時に存在できるレイドの最大数。 */
        public final ModConfigSpec.IntValue raidMaxConcurrent;
        /** レイドの苗床がプレイヤーから最低これだけ離れた地点に出現する(ブロック)。 */
        public final ModConfigSpec.IntValue raidSpawnMinDistance;
        /** レイドの苗床が出現する、プレイヤーからの最大距離(ブロック)。 */
        public final ModConfigSpec.IntValue raidSpawnMaxDistance;
        /** レイドの苗床(ボス)の最大体力。 */
        public final ModConfigSpec.DoubleValue raidNexusHealth;
        /** 苗床のボスバーが表示されるプレイヤーの範囲(ブロック)。 */
        public final ModConfigSpec.IntValue raidBossBarRadius;
        /** レイド発生時に苗床の周囲を常時強制ロードする半径(チャンク)。誰も居なくても苗床が止まらない範囲。 */
        public final ModConfigSpec.IntValue raidForceLoadRadius;
        // --- 徐々に伸びる根 ---
        /** 根の先端を1ブロック進める間隔(tick)。小さいほど根が速く伸びる。 */
        public final ModConfigSpec.IntValue raidRootGrowthIntervalTicks;
        /** 苗床から放射状に伸ばす根の本数。 */
        public final ModConfigSpec.IntValue raidRootCount;
        /** 根の太さの下限(先端は常にこの太さ)。 */
        public final ModConfigSpec.IntValue raidRootThicknessMin;
        /** 根本(苗床付近)が育ちきったときの最大の太さ。 */
        public final ModConfigSpec.IntValue raidRootThicknessMax;
        /** 根がこの長さ(ブロック)以下のうちは、根本もまだ raidRootThicknessMin のまま(育っていない)。 */
        public final ModConfigSpec.IntValue raidRootYoungLength;
        /** 根がこの長さ(ブロック)まで伸びると、根本が raidRootThicknessMax まで育ちきる。 */
        public final ModConfigSpec.IntValue raidRootMatureLength;
        /** 根がレイドビーコンから伸びられる最大距離(水平、ブロック)。ここに達した先端は成長を止める。 */
        public final ModConfigSpec.IntValue raidRootMaxDistance;
        /** レイドの汚染起点が生やす装飾アーチ・川の橋の生成確率にかける倍率(1.0 で通常、0 で無効)。 */
        public final ModConfigSpec.DoubleValue raidCorruptionArchMultiplier;
        /** 根の先端が1歩進むごとに枝分かれする確率。 */
        public final ModConfigSpec.DoubleValue raidRootBranchChance;
        /** 1本の根の先端が枝分かれしてから、また枝分かれできるようになるまでの歩数。 */
        public final ModConfigSpec.IntValue raidRootBranchCooldownSteps;
        /** 同時に伸びていられる根の先端の最大数(枝分かれ込み)。 */
        public final ModConfigSpec.IntValue raidRootMaxTips;
        /** 根に沿って、この間隔(ブロック)ごとに汚染の起点を1つ落とす。 */
        public final ModConfigSpec.IntValue raidRootNodeSpacing;
        // --- 根から広がる汚染 ---
        /** 根に沿って落とした汚染起点1つあたりの汚染半径(ブロック)。 */
        public final ModConfigSpec.IntValue raidCorruptionRadius;
        /** 汚染起点1つあたり、起点周辺を隙間なく塗りつぶす「コア」の半径。根沿いを確実に汚染するための土台。 */
        public final ModConfigSpec.IntValue raidCorruptionCoreRadius;
        /** 汚染サイクルの実行間隔(tick)。 */
        public final ModConfigSpec.IntValue raidCorruptionTickInterval;
        /** 1サイクルで進める汚染起点の数(残りは次サイクル)。 */
        public final ModConfigSpec.IntValue raidCorruptionNodesPerCycle;
        /** 同時に処理し続ける汚染起点の最大数。これを超えると古い(完了済み)起点から処理をやめる。 */
        public final ModConfigSpec.IntValue raidMaxActiveCorruptionNodes;
        /** 汚染起点1つあたり、コアの縁から放射状に伸ばす触手の本数。 */
        public final ModConfigSpec.IntValue raidTendrilInitialCount;
        /** 汚染起点1つあたり、同時に存在できる触手(根+枝)の最大本数。 */
        public final ModConfigSpec.IntValue raidTendrilMaxActive;
        // --- 根が伸びきったあとの「面汚染」 ---
        /** 根が限界距離まで伸びきったあと、レイドビーコンを中心に塗りつぶす球の半径(ブロック)。 */
        public final ModConfigSpec.IntValue raidAreaCorruptionRadius;
        /** 面汚染が球の中心から外周まで塗りきるのにかける時間(分)。速さは距離に応じて自動で上がる。 */
        public final ModConfigSpec.IntValue raidAreaCorruptionSweepMinutes;
        /** 面汚染が 1 tick に調べてよいマス数の上限(重すぎたときの安全弁)。 */
        public final ModConfigSpec.IntValue raidAreaCorruptionMaxCellsPerTick;
        // --- 敵対 Mob のウェーブ ---
        /** 苗床が敵対 Mob の群れを湧かせる間隔(tick)。0 でウェーブ無効。 */
        public final ModConfigSpec.IntValue raidWaveIntervalTicks;
        /** 1ウェーブで湧かせる Mob 数の下限(下限〜上限でランダム)。 */
        public final ModConfigSpec.IntValue raidWaveMinSize;
        /** 1ウェーブで湧かせる Mob 数の上限。 */
        public final ModConfigSpec.IntValue raidWaveMaxSize;
        /** 苗床の周囲の過繫茂した Mob 総数がこれを超えたら、そのウェーブはスキップ。 */
        public final ModConfigSpec.IntValue raidWaveMaxNearby;
        /** ウェーブ Mob を湧かせる、苗床からの水平半径(ブロック)。 */
        public final ModConfigSpec.IntValue raidWaveSpawnRadius;
        /** 苗床からこの距離(ブロック)以内に狙えるプレイヤーが居ない間は、ウェーブの時計を止める。 */
        public final ModConfigSpec.IntValue raidWavePlayerRadius;
        // --- 苗床の攻撃 ---
        /** この距離以内にプレイヤーが入ると苗床が衝撃波(ウォーデンと同じ)を狙い始める(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSonicRange;
        /** これより近いと衝撃波は狙わない(近距離では発動しない、ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSonicMinRange;
        /** 衝撃波の溜め時間(tick)。 */
        public final ModConfigSpec.IntValue raidNexusSonicChargeTicks;
        /** 衝撃波を放ってから次に狙い始めるまでのクールダウン(tick)。 */
        public final ModConfigSpec.IntValue raidNexusSonicCooldownTicks;
        /** 衝撃波1発のダメージ。 */
        public final ModConfigSpec.DoubleValue raidNexusSonicDamage;
        /** この距離以内にプレイヤーが近づくと苗床が薙ぎ払いで引き剥がす(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSweepRange;
        /** 薙ぎ払いが当たる、苗床からの水平半径(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSweepHitRange;
        /** 薙ぎ払いのダメージ。 */
        public final ModConfigSpec.DoubleValue raidNexusSweepDamage;
        /** 薙ぎ払いの水平ノックバックの強さ(とても大きめ)。 */
        public final ModConfigSpec.DoubleValue raidNexusSweepKnockback;
        /** 薙ぎ払いのクールダウン(tick)。 */
        public final ModConfigSpec.IntValue raidNexusSweepCooldownTicks;
        /** この距離以内にプレイヤーが近づくと苗床本体が胞子を撒く(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSporeTriggerRange;
        /** この距離以内にグループ1〜10いずれにも属さない Mob が近づいても苗床本体が胞子を撒く(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSporeMobTriggerRange;
        /** 胞子をばら撒く実際の範囲(ブロック)。 */
        public final ModConfigSpec.IntValue raidNexusSporeBurstRadius;
        /** 胞子ばら撒きのクールダウン(tick)。 */
        public final ModConfigSpec.IntValue raidNexusSporeBurstCooldownTicks;

        // --- 攻城戦(超大型レイドボス) ---
        /** 攻城戦(超大型レイドボス)を有効にするか。 */
        public final ModConfigSpec.BooleanValue siegeEnabled;
        /** ボスが周回する円の半径(ブロック)。中心は開始時のプレイヤー位置で固定。 */
        public final ModConfigSpec.IntValue siegeRadius;
        /** ボスが飛ぶ絶対 Y 座標の基準値。 */
        public final ModConfigSpec.IntValue siegeAltitude;
        /** 周回の速さ(ブロック/tick)。 */
        public final ModConfigSpec.DoubleValue siegeSpeed;
        /** 開始半径から {@link #siegeMinRadius} まで寄り切るのに回る周回数。0 でただの周回。 */
        public final ModConfigSpec.DoubleValue siegeApproachLaps;
        /** これ以上は中心へ寄らない周回半径(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeMinRadius;
        /** ボスの最大体力。 */
        public final ModConfigSpec.DoubleValue siegeHealth;
        /** ボスの防御値。防具貫通でないダメージはこの値ぶん差し引かれる(しきい値)。 */
        public final ModConfigSpec.DoubleValue siegeArmor;
        /** 爆弾の着弾点を汚染する半径(ブロック)。0 で汚染しない。 */
        public final ModConfigSpec.DoubleValue siegeBombCorruptionRadius;
        /** 爆弾の汚染の輪郭の波打ち(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeBombCorruptionNoise;
        /** 描画時のモデル倍率(1 で全長約 8 ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeScale;
        /** ボスの状態を全プレイヤーへ再送する間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeSyncIntervalTicks;
        /** プレイヤーがこの距離まで近づくと、ボスがデータから実エンティティへ置き換わる(ブロック)。 */
        public final ModConfigSpec.IntValue siegeCloseRange;
        /** 近距離用エンティティの当たり判定の幅(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeHitboxWidth;
        /** 近距離用エンティティの当たり判定の高さ(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeHitboxHeight;
        /** 爆撃の間隔(tick)。0 で爆撃しない。 */
        public final ModConfigSpec.IntValue siegeBombIntervalTicks;
        /** 1 回の爆撃で落とす爆弾の数。 */
        public final ModConfigSpec.IntValue siegeBombCount;
        /** 爆弾を落とす位置の、ボス中心からの下方向のオフセット(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeBombDropOffset;
        /** 爆弾の爆発の強さ(TNT = 4.0)。ブロックは壊れない。 */
        public final ModConfigSpec.DoubleValue siegeBombPower;
        /** 爆弾のクレーター半径(ブロック)。0 = 地形破壊なし(既定)。 */
        public final ModConfigSpec.IntValue siegeBombCraterRadius;
        /** クレーターで壊す対象の、爆発耐性の上限。 */
        public final ModConfigSpec.DoubleValue siegeBombCraterMaxResistance;
        /** (描画)爆弾のモデル倍率。 */
        public final ModConfigSpec.DoubleValue siegeBombScale;
        /** 花のビーム(遠距離砲撃)の間隔(tick)。0 で撃たない。 */
        public final ModConfigSpec.IntValue siegeBeamIntervalTicks;
        /** 花のビームの溜め(予告)の長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeBeamChargeTicks;
        /** 花のビームの照射の長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeBeamFireTicks;
        /** 花のビーム後、花が閉じるまでの長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeBeamRecoverTicks;
        /** 花のビームはボスからこの距離以上離れたプレイヤーだけを狙う(ブロック)。 */
        public final ModConfigSpec.IntValue siegeBeamMinRange;
        /** 花のビームの着弾点の、標的プレイヤーからのばらつき(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeBeamScatter;
        /** 花のビームが {@code siegeBeamGrowDistance} 離れた地点で達する半径(ブロック)。ダメージ半径 = 着弾点での太さ。 */
        public final ModConfigSpec.DoubleValue siegeBeamRadius;
        /** 花のビームの発射点(花)での半径(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeBeamStartRadius;
        /** 花のビームが {@code siegeBeamRadius} の太さに達するまでの距離(ブロック)。その先も同じ割合で太くなる。 */
        public final ModConfigSpec.IntValue siegeBeamGrowDistance;
        /** 花のビームが狙った地点を通り越して、さらに突き進む距離(ブロック)。 */
        public final ModConfigSpec.IntValue siegeBeamOvershoot;
        /** 花のビームの着弾の最大ダメージ(中心)。 */
        public final ModConfigSpec.DoubleValue siegeBeamDamage;
        /** 花のビームの着弾の爆発の強さ(演出・ノックバック用。ブロックは壊れない)。 */
        public final ModConfigSpec.DoubleValue siegeBeamPower;
        /** 花のビームのクレーター半径(ブロック)。0 = 地形破壊なし(既定)。 */
        public final ModConfigSpec.IntValue siegeBeamCraterRadius;
        /** 触手ビーム(近距離)の間隔(tick)。0 で撃たない。 */
        public final ModConfigSpec.IntValue siegeTentacleBeamIntervalTicks;
        /** 触手ビーム 1 斉射で撃つ本数。 */
        public final ModConfigSpec.IntValue siegeTentacleBeamCount;
        /** 触手ビームの斉射で 1 本ずつ撃つ間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeTentacleBeamShotIntervalTicks;
        /** 触手ビームの溜めの長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeTentacleBeamChargeTicks;
        /** 触手ビームの照射の長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeTentacleBeamFireTicks;
        /** 触手ビームのダメージ。 */
        public final ModConfigSpec.DoubleValue siegeTentacleBeamDamage;
        /** 触手ビームの太さ = 線からこの距離以内の生物に当たる(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeTentacleBeamRadius;
        public final ModConfigSpec.IntValue siegeTentacleBeamOvershoot;
        /** 首が標的へ向くときの追従の速さ（1 tick あたりに詰める残差の割合）。 */
        public final ModConfigSpec.DoubleValue siegeNeckFollowSpeed;
        /** ビームが終わったあと首が正面へ戻る速さ（1 tick あたりに詰める残差の割合）。小さいほどゆっくり。 */
        public final ModConfigSpec.DoubleValue siegeNeckReturnSpeed;

        // --- 胞子爆弾の曲射(ボス側の砲撃) ---
        /** 胞子爆弾を曲射する間隔(tick)。0 で撃たない。 */
        public final ModConfigSpec.IntValue siegeMortarIntervalTicks;
        /** 1 斉射で撃つ胞子爆弾の数。 */
        public final ModConfigSpec.IntValue siegeMortarCount;
        /** 曲射で狙う相手を探す距離(ブロック)。 */
        public final ModConfigSpec.IntValue siegeMortarRange;
        /** 狙点のばらつき(ブロック)。プレイヤーを中心にこの半径の円のどこかを狙う(既定 45)。 */
        public final ModConfigSpec.DoubleValue siegeMortarScatter;
        /** 胞子爆弾の水平速度(ブロック/tick)。飛翔時間 = 水平距離 / この値。 */
        public final ModConfigSpec.DoubleValue siegeMortarSpeed;
        /** 胞子爆弾の最短の飛翔時間(tick)。近くへ撃つときも山なりにするための下限。 */
        public final ModConfigSpec.IntValue siegeMortarMinFlightTicks;
        /** 胞子爆弾にかかる重力(ブロック/tick²)。大きいほど山なり。 */
        public final ModConfigSpec.DoubleValue siegeMortarGravity;
        /** 初速に乗せる誤差(ブロック/tick)。飛翔時間ぶん増幅されるので、遠いほど散る。 */
        public final ModConfigSpec.DoubleValue siegeMortarJitter;
        /** 胞子爆弾が生物に直撃したときのダメージ(爆発のダメージとは別)。 */
        public final ModConfigSpec.DoubleValue siegeMortarDirectDamage;
        /** 飛んでいる胞子爆弾の描画の大きさ。 */
        public final ModConfigSpec.DoubleValue siegeMortarScale;

        // --- 砲撃への反撃(胞子爆弾の連射 + 増援) ---
        /** 砲弾が命中したときに撃ち返す胞子爆弾の斉射の回数。0 で反撃しない。 */
        public final ModConfigSpec.IntValue siegeRetaliationShots;
        /** 反撃の 1 斉射で撃つ胞子爆弾の数。 */
        public final ModConfigSpec.IntValue siegeRetaliationBombs;
        /** 反撃の斉射と斉射の間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeRetaliationGapTicks;
        /** 反撃の狙点のばらつき(ブロック)。撃ってきたプレイヤーを中心にこの半径の円のどこかへ落ちる。 */
        public final ModConfigSpec.DoubleValue siegeRetaliationScatter;
        /** 反撃の胞子爆弾 1 発の着弾地点に湧くグループ4の数。0 で湧かない。 */
        public final ModConfigSpec.IntValue siegeRetaliationSpawnCount;
        /** 反撃の連射を撃ち終えてから、次の反撃を受け付けるまでの時間(tick)。 */
        public final ModConfigSpec.IntValue siegeRetaliationCooldownTicks;

        // --- 触手の小ビームの乱射 ---
        /** 乱射を始める間隔(tick)。0 で乱射しない。 */
        public final ModConfigSpec.IntValue siegeBarrageIntervalTicks;
        /** 近距離モード(ボスの下や近くにプレイヤーが居る)のときの乱射の間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeBarrageCloseIntervalTicks;
        /** 乱射 1 回の長さ(tick)。 */
        public final ModConfigSpec.IntValue siegeBarrageDurationTicks;
        /** 乱射中、次の 1 発までの間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeBarrageShotIntervalTicks;
        /** 乱射の相手を探す距離(ブロック)。 */
        public final ModConfigSpec.IntValue siegeBarrageRange;
        /** 乱射の 1 回の発射で同時に撃つ本数。 */
        public final ModConfigSpec.IntValue siegeBarrageShotsPerStep;
        /** 乱射の狙いのばらつき(ブロック)。狙点を中心にこの半径の球のどこかへ撃つ。 */
        public final ModConfigSpec.DoubleValue siegeBarrageScatter;
        /** 乱射の小ビーム 1 発のダメージ。 */
        public final ModConfigSpec.DoubleValue siegeBarrageDamage;
        /** 乱射の小ビームの太さ(ブロック)。 */
        public final ModConfigSpec.DoubleValue siegeBarrageRadius;
        /** 乱射の小ビームが狙点を通り越して伸びる長さ(ブロック)。 */
        public final ModConfigSpec.IntValue siegeBarrageOvershoot;
        /** 触手ビーム・乱射が Mob を標的に選ぶときの探索半径(ブロック、プレイヤーの周り)。 */
        public final ModConfigSpec.IntValue siegeTargetMobRadius;
        /** 触手ビーム・乱射がプレイヤーではなく周りの Mob を狙う確率。 */
        public final ModConfigSpec.DoubleValue siegeMobTargetChance;
        /** ボスの悲鳴を鳴らす最短の間隔(tick)。 */
        public final ModConfigSpec.IntValue siegeCryCooldownTicks;
        /** ボスの悲鳴の音量の倍率。 */
        public final ModConfigSpec.DoubleValue siegeCryVolume;
        /** ボスの悲鳴のピッチの倍率(下げるほど低く・長くなる)。 */
        public final ModConfigSpec.DoubleValue siegeCryPitch;
        /** (クライアント描画)ボスのモデルの裏面を描かない(軽量化)。 */
        public final ModConfigSpec.BooleanValue siegeCullBackFaces;

        // --- 砲台(攻城戦ボスを撃つ大砲) ---
        /** 装薬が最大({@code cannonMaxCharge})のときの砲弾の初速(ブロック/tick)。 */
        public final ModConfigSpec.DoubleValue cannonMuzzleVelocity;
        /** 装薬(1 発に使う火薬)の最大数。初速は sqrt(装薬 / 最大) 倍。 */
        public final ModConfigSpec.IntValue cannonMaxCharge;
        /** 砲弾に掛かる重力(ブロック/tick²)。空気抵抗は無し。 */
        public final ModConfigSpec.DoubleValue cannonGravity;
        /** 発射後、次に撃てるまでの装填時間(tick)。 */
        public final ModConfigSpec.IntValue cannonReloadTicks;
        /** 砲弾がボスに命中したときのダメージ({@code siegeArmor} のしきい値を差し引かれる)。 */
        public final ModConfigSpec.DoubleValue cannonShellDamage;
        /** 砲弾がボス以外の生物に直撃したときのダメージ。 */
        public final ModConfigSpec.DoubleValue cannonShellEntityDamage;
        /** 砲弾の着弾の爆発の強さ(ブロックは壊さない)。 */
        public final ModConfigSpec.DoubleValue cannonShellExplosionPower;
        /** 砲弾の着弾でえぐるクレーターの半径(0 = 地形破壊なし)。 */
        public final ModConfigSpec.IntValue cannonShellCraterRadius;
        /** 発射のたびに仰角・方位へ乗る誤差の標準偏差(度)。 */
        public final ModConfigSpec.DoubleValue cannonDispersion;
        /** 照準器にボスへの偏差射撃の解(狙うべき点)を出すか。 */
        public final ModConfigSpec.BooleanValue cannonAutoAim;
        /** 照準補助で高い弾道(山なり)を選ぶか。false = 低い弾道(飛翔時間が短い)。 */
        public final ModConfigSpec.BooleanValue cannonAutoAimHighArc;
        /** 砲弾が何にも当たらないときに消えるまでの tick。 */
        public final ModConfigSpec.IntValue cannonShellLifetimeTicks;
        /** 仰角の下限(度)。 */
        public final ModConfigSpec.IntValue cannonMinElevation;
        /** 仰角の上限(度)。 */
        public final ModConfigSpec.IntValue cannonMaxElevation;
        /** (描画)砲弾のモデル倍率。 */
        public final ModConfigSpec.DoubleValue cannonShellScale;
        /** 砲台のモデル倍率。砲口・座席の位置も同じ倍率で動く。 */
        public final ModConfigSpec.DoubleValue cannonModelScale;
        /** 照準器(騎乗中の一人称)の視野倍率。小さいほど拡大。 */
        public final ModConfigSpec.DoubleValue cannonScopeZoom;
        /** 砲台の旋回速度（度/tick）。視点にすぐ追いつかず、この速さで追従する。 */
        public final ModConfigSpec.DoubleValue cannonTraverseSpeed;
        /** 砲身の俯仰速度（度/tick）。 */
        public final ModConfigSpec.DoubleValue cannonElevationSpeed;
        /** 測距（ホイールクリック）にかかる時間（tick）。 */
        public final ModConfigSpec.IntValue cannonRangingTicks;
        /** 測距の結果が有効な時間（tick）。切れたら測り直し。 */
        public final ModConfigSpec.IntValue cannonRangingValidTicks;
        /** 測距中にボスを視野へ捉えておく必要のある角度（度）。これを超えて外すと中断。 */
        public final ModConfigSpec.DoubleValue cannonRangingConeDegrees;

        // --- 遮蔽盾 ---
        /** 盾が展開しきるまでの時間（tick）。 */
        public final ModConfigSpec.IntValue shieldDeployTicks;
        /** ハンドル 1 回で回る角度（度）。 */
        public final ModConfigSpec.DoubleValue shieldHandleStep;
        /** 盾のモデル倍率。遮蔽面の大きさも同じ倍率で変わる。 */
        public final ModConfigSpec.DoubleValue shieldScale;
        /** ビームのダメージを受ける生物から、この距離までの盾を見る（ブロック）。 */
        public final ModConfigSpec.DoubleValue shieldSearchRadius;
        /** 盾が花のビームを遮るか。 */
        public final ModConfigSpec.BooleanValue shieldBlocksBeam;

        // --- 性能(2026-10-06、perf/) ---
        /** 予算の番人({@code perf/TickBudget})を使うか。 */
        public final ModConfigSpec.BooleanValue tickBudgetEnabled;
        /** 予算の番人が守る 1 tick の目標(ミリ秒)。 */
        public final ModConfigSpec.DoubleValue tickBudgetTargetMspt;
        /** 遠くの Mob の思考の間引き({@code perf/MobThinkThrottle})を使うか。 */
        public final ModConfigSpec.BooleanValue mobThinkThrottleEnabled;
        /** 間引きを始める、いちばん近いプレイヤーからの距離(ブロック)。 */
        public final ModConfigSpec.IntValue mobThinkThrottleDistance;
        /** 遠くの Mob が行動を選び直す間隔(tick)。 */
        public final ModConfigSpec.IntValue mobThinkThrottleInterval;

        // --- 地割れ(フェーズ1開始演出) ---
        /** フェーズ1開始時の「地割れ・胞子噴出」演出を有効にするか。 */
        public final ModConfigSpec.BooleanValue quakeEnabled;
        /** 地割れの発生地点を、全プレイヤーからこの距離以上離す(ブロック)。 */
        public final ModConfigSpec.IntValue quakeDistanceMin;
        /** 地割れの発生地点の、プレイヤーからの目標距離の上限(ブロック)。実際の距離は min〜max。 */
        public final ModConfigSpec.IntValue quakeDistanceMax;
        /** 裂け目の長さ(長軸)の最小値(ブロック)。 */
        public final ModConfigSpec.IntValue quakeFissureMinLength;
        /** 裂け目の長さ(長軸)の最大値(ブロック)。 */
        public final ModConfigSpec.IntValue quakeFissureMaxLength;
        /** 裂け目の幅(短軸)の最小値(ブロック)。 */
        public final ModConfigSpec.IntValue quakeFissureMinWidth;
        /** 裂け目の幅(短軸)の最大値(ブロック)。 */
        public final ModConfigSpec.IntValue quakeFissureMaxWidth;
        /** 裂け目の最深部が到達する Y 座標の「浅い側」(小さい裂け目のとき)。 */
        public final ModConfigSpec.IntValue quakeFissureMinFloorY;
        /** 裂け目の最深部が到達する Y 座標の「深い側」(大きい裂け目のとき)。 */
        public final ModConfigSpec.IntValue quakeFissureMaxFloorY;
        /** 裂け目が完成したとき周囲のチャンクを過繁茂バイオームへ変換するか。既定 false。 */
        public final ModConfigSpec.BooleanValue quakeConvertBiome;
        /** ({@code quakeConvertBiome} が true のとき)胞子噴出点を中心にこのチャンク半径ぶんも変換する。 */
        public final ModConfigSpec.IntValue quakeSporeZoneChunks;
        /** 画面が揺れ始めて(効果音の再生開始)から、地面が裂け始めるまでの予兆(輪郭のみ)の時間(tick)。既定 120 = 6 秒。 */
        public final ModConfigSpec.IntValue quakePreShakeTicks;
        /** 画面の揺れ始め(効果音の再生開始)から裂け目の完成までの合計時間(tick)。既定 312 = 15.6 秒 = 土砂崩れ効果音の実尺。 */
        public final ModConfigSpec.IntValue quakeFormTicks;
        /** 裂け目が走り始めて(予兆の後)から、胞子噴出エフェクトが出現するまでの時間(tick)。既定 120 = 6 秒。 */
        public final ModConfigSpec.IntValue quakeEruptStartTicks;
        /** 裂け目が完成してから、画面の揺れがゆっくり収まるまでの時間(tick)。既定 160 = 8 秒。 */
        public final ModConfigSpec.IntValue quakeFadeOutTicks;
        /** 胞子を噴出し続ける時間(tick)。既定 3600 = 3 分(噴出開始からの時間)。 */
        public final ModConfigSpec.IntValue quakeEruptDurationTicks;
        /** 胞子噴出パーティクルを出す間隔(tick)。小さいほど濃い。既定 3。 */
        public final ModConfigSpec.IntValue quakeEruptIntervalTicks;
        /** 胞子噴出中、噴出点からこのチャンク半径以内の Mob を確定で「胞子感染1」にする + ランダムに汚染シダを湧かせる。0 で無効。 */
        public final ModConfigSpec.IntValue quakeEruptHazardRadiusChunks;
        /** 噴出点周囲の Mob を確定感染させる処理の実行間隔(tick)。 */
        public final ModConfigSpec.IntValue quakeEruptInfectionIntervalTicks;
        /** 噴出点周囲にランダムな汚染シダを湧かせる処理の実行間隔(tick)。 */
        public final ModConfigSpec.IntValue quakeEruptFernIntervalTicks;
        /** 上記1回あたりの汚染シダ出現の試行回数。 */
        public final ModConfigSpec.IntValue quakeEruptFernAttemptsPerInterval;
        /** 噴出点周囲(ハザード半径内)の汚染シダがこの数に達したら、それ以上は湧かせない。 */
        public final ModConfigSpec.IntValue quakeEruptFernMaxNearby;
        /** 胞子噴出の初速(ブロック/tick)。噴出の瞬間の速さ。 */
        public final ModConfigSpec.DoubleValue quakeSporeSpeed;
        /** 胞子パーティクルの毎 tick の速度倍率(摩擦)。1 に近いほど長く伸び、小さいほど急減速。 */
        public final ModConfigSpec.DoubleValue quakeSporeFriction;
        /** 胞子パーティクルに掛かる重力(ブロック/tick^2)。徐々に落ちてくる強さ。 */
        public final ModConfigSpec.DoubleValue quakeSporeGravity;
        /** 胞子噴出の円錐の半角(度)。大きいほど横に広がる。 */
        public final ModConfigSpec.DoubleValue quakeSporeConeDeg;
        /** 胞子パーティクル 1 粒の大きさ(ブロック目安)。大きいほど遠くからでも見える。 */
        public final ModConfigSpec.DoubleValue quakeSporeSize;
        /** 胞子噴出パーティクルの量(密度)の倍率。大きいほど濃くなるが負荷も上がる。 */
        public final ModConfigSpec.DoubleValue quakeSporeDensity;
        /** 胞子パーティクルが噴出点から何ブロック上昇すると完全に透明になるか(上ほど薄くなって消える)。 */
        public final ModConfigSpec.DoubleValue quakeSporeRiseFade;
        /** 胞子噴出エフェクトが出ている間、汚染シダなどによる侵食速度に掛かる倍率。 */
        public final ModConfigSpec.DoubleValue quakeEruptionCorruptionMultiplier;
        /** (クライアント描画)画面の揺れの最大強度。0 で揺れなし、1.0 が既定。 */
        public final ModConfigSpec.DoubleValue quakeShakeIntensity;
        /** (クライアント描画)震央からこの距離(ブロック)離れるほど揺れが弱まる(下限あり)。既定 512。 */
        public final ModConfigSpec.IntValue quakeShakeRadius;

        /**
         * (クライアント描画)この MOD が霧を上書きしているバイオーム(過繁茂・湿地・菌湿地帯)にいる間、
         * 地形霧の終端より奥にあるチャンクセクション(草・葉などの地形)を毎フレームの描画対象から外すか。
         * true にすると、霧で完全に隠れて見えない範囲のジオメトリを描かなくなり GPU 負荷・ドローコールが減る
         * ({@link com.yayuy.overgrownvegetation.mixin.client.LevelRendererFogCullMixin})。
         * チャンク自体はアンロードしないので、バイオームを出入りしてもチャンク再構築のカクつきは起きない。
         * 描画のみに影響する設定で、common config にあるがサーバーには影響しない。
         *
         * <p>2026-09-17 まで「霧の演出が 85% 以上効いているとき」だけ働いていたため、
         * 霧が薄い湿地・菌湿地帯({@code swampFogFactor} 既定 0.45)とバイオーム境界のフェード中
         * ({@code fogFadeSeconds} 既定 10 秒)は一度も効いていなかった。詳しくは
         * {@code CorruptionAtmosphere.CULL_MIN_FOG_FACTOR} の説明を参照。
         */
        public final ModConfigSpec.BooleanValue fogCullEnabled;
        /**
         * 上記スキップの判定を甘くする余裕(ブロック)。地形霧の終端からさらにこの距離ぶんだけ
         * 奥までは描画対象に残す。霧が終端ちょうどで完全な不透明になりきらない分の保険で、
         * 大きくするほどスキップが控えめ(=描画が重いまま)になる。
         */
        public final ModConfigSpec.DoubleValue fogCullExtraMargin;
        /**
         * (クライアント描画)上記のチャンクスキップと同じ判定で、<b>Mob の描画も</b>打ち切るか
         * ({@link com.yayuy.overgrownvegetation.client.EntityFogCull})。
         * 霧で完全に潰れて見えない位置にいる Mob の骨の行列計算・頂点生成をまとめて省く。
         * {@code mobRenderLodEnabled} が止めるのはアニメーションだけで、レストポーズの
         * ジオメトリは作られ続けるので、遠距離ではこちらの方が効く。
         * {@code fogCullEnabled} が false のときはこちらも働かない(判定距離が出ないため)。
         */
        public final ModConfigSpec.BooleanValue fogCullEntities;
        /** 過繁茂バイオームの霧の演出が目標の状態へ到達するまでにかける秒数(入るとき・出るとき共通)。 */
        public final ModConfigSpec.DoubleValue fogFadeSeconds;
        /**
         * (クライアント描画)この MOD の GeckoLib Mob のアニメーションを、遠いときは
         * <b>完全に止める</b>か({@link com.yayuy.overgrownvegetation.client.MobRenderLod})。
         * サーバー側の行動には一切影響しない。
         */
        public final ModConfigSpec.BooleanValue mobRenderLodEnabled;
        /**
         * カメラのいるチャンクから、この半径(チャンク数、チェビシェフ距離)以内のチャンクにいる Mob は動かす。
         * それより外の Mob はレストポーズで固まる。3 = 自分のチャンクを中心に 7×7 チャンク。
         */
        public final ModConfigSpec.IntValue mobRenderLodAnimateChunkRadius;
        /**
         * 上記の半径の中でも、画面に映っている Mob のうち<b>カメラに近い順にこの体数だけ</b>動かす
         * ({@link com.yayuy.overgrownvegetation.client.MobRenderLod} の「動かす体数の予算」)。
         * 半径の外・予算の外のどちらかに当たればレストポーズ。0 で予算なし(半径だけで決める)。
         * 攻城戦ボス・フィロセンス・スクティムネスは予算を使わず常に動く。
         * 大群が近くに集まったときに、全員の骨を毎フレーム計算しないための上限(ユーザー承認 2026-09-24、既定 64 は仮決め 2026-09-25)。
         */
        public final ModConfigSpec.IntValue mobRenderLodAnimateBudget;
        /**
         * (クライアント描画)遠くて描かれない G4〜G9 の Mob(と、TD の行進中の群れ)を、1 枚の絵の<b>身代わり</b>で描くか
         * ({@link com.yayuy.overgrownvegetation.client.horde.HordeRenderer}。2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に」)。
         */
        public final ModConfigSpec.BooleanValue hordeSpritesEnabled;
        /** 身代わりを 1 フレームに描く数の上限(超えたらカメラに近い順)。 */
        public final ModConfigSpec.IntValue hordeSpriteMaxCount;
        /**
         * (クライアント描画)描画距離・霧の外の TD の行進中の群れを、地平線の胞子の嵐(地上の群れ)と空の影(空の群れ)で描くか
         * ({@link com.yayuy.overgrownvegetation.client.horde.HordeStormRenderer}。遠景の身代わりの 4 段目、2026-09-25)。
         */
        public final ModConfigSpec.BooleanValue hordeStormsEnabled;
        /** 地平線の胞子の嵐の濃さ(壁の濃さと群れの柱の濃さに掛ける。1 = 群れが全方位にいれば地平線が隠れる)。 */
        public final ModConfigSpec.DoubleValue hordeStormDensity;
        /**
         * (クライアント描画)汚染地域の林冠の隙間から差す光の筋と、その中で光る胞子を描くか
         * ({@link com.yayuy.overgrownvegetation.client.CanopyLight}、2026-10-15 試作)。シェーダー(Iris)と二重になるときに切る。
         */
        public final ModConfigSpec.BooleanValue canopyLightEnabled;
        /** 光の筋と光る胞子の明るさ(1 = 既定)。 */
        public final ModConfigSpec.DoubleValue canopyLightIntensity;
        /**
         * (クライアント描画)汚染地域の風の強さの倍率({@link com.yayuy.overgrownvegetation.client.CanopyWind}、2026-10-15 夜)。
         * 木漏れ日の明滅とずれ・光の筋の揺れ・胞子の流れ・落ち葉がこの風で動く。0 で無風(前の止まった見た目)。
         */
        public final ModConfigSpec.DoubleValue canopyWindStrength;
        /** (クライアント描画)汚染地域で葉が落ちる粒を出すか({@link com.yayuy.overgrownvegetation.client.CanopyLeaves})。 */
        public final ModConfigSpec.BooleanValue canopyFallingLeaves;
        /**
         * (クライアントの音)汚染地域の風の音の大きさ({@link com.yayuy.overgrownvegetation.client.sound.CanopyWindSound}、2026-10-15 夜)。
         * 葉擦れと、林を渡ってくる突風の音。0 で鳴らさない。バニラの音量の「環境音」も掛かる。
         */
        public final ModConfigSpec.DoubleValue canopyWindSoundVolume;
        /**
         * (クライアント描画)霧の向こうの巨大樹をシルエットで見せるか({@link com.yayuy.overgrownvegetation.client.GiantTreeSilhouettes}、2026-10-15 夜)。
         */
        public final ModConfigSpec.BooleanValue giantTreeSilhouette;
        /**
         * (クライアント描画)過繁茂の葉・茂み・シダ・蔦・垂れ苔・太い蔓を風で揺らす大きさの倍率({@link com.yayuy.overgrownvegetation.client.LeafSway}・
         * {@link com.yayuy.overgrownvegetation.client.SwayModels}、2026-10-15 夜)。0 で揺らさない。揺れるのは風の音が鳴っている間だけ(2026-10-16)。
         */
        public final ModConfigSpec.DoubleValue canopyLeafSway;
        /**
         * (クライアント描画)太陽の方を向いたときに霧を明るく暖かい色へ寄せる強さの倍率
         * ({@link com.yayuy.overgrownvegetation.client.CorruptionAtmosphere}、2026-10-16)。0 で寄せない。
         */
        public final ModConfigSpec.DoubleValue canopyFogGlow;
        /**
         * (クライアント描画)林冠の影を地面以外の面(草・シダ・幹・葉)にも落とす強さ
         * ({@link com.yayuy.overgrownvegetation.client.CanopySunMap}、風の案 C、2026-10-16)。0 で地面の上面だけ。
         */
        public final ModConfigSpec.DoubleValue canopySurfaceShade;
        /** (クライアント描画)林冠の影を青緑に寄せる強さ({@link com.yayuy.overgrownvegetation.client.CanopySunMap#shadeColor}、2026-10-16)。0 で灰色。 */
        public final ModConfigSpec.DoubleValue canopyShadeTint;
        /** (クライアント描画)過繁茂の森の中では日向以外をいつも青緑に寄せる強さ({@link com.yayuy.overgrownvegetation.client.CanopySunMap}、2026-10-16)。0 で林冠の影だけ。 */
        public final ModConfigSpec.DoubleValue canopyForestTint;
        /** (クライアント描画)過繁茂の森の中で、葉に削られた空の光を持ち上げる割合({@link com.yayuy.overgrownvegetation.client.CanopyLight#skyLift}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue canopySkyLift;
        /** (クライアント描画)二色の霧(空気遠近法)の強さ({@link com.yayuy.overgrownvegetation.client.AerialFog}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue aerialFog;
        /** (クライアント描画)雨上がりの森(葉先の滴・きらめき・湯気)の強さ({@link com.yayuy.overgrownvegetation.client.AfterRain}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue afterRain;
        /** (クライアント描画)葉の色の揺らぎの強さ({@link com.yayuy.overgrownvegetation.client.LeafColors}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue leafColorVariation;
        /** (クライアント描画)黄緑の若葉の房の強さ({@link com.yayuy.overgrownvegetation.client.LeafColors}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue youngLeaves;
        /** (クライアント描画)ボクセルの雲を描くか({@link com.yayuy.overgrownvegetation.client.VoxelClouds}、2026-10-17)。 */
        public final ModConfigSpec.BooleanValue voxelClouds;
        /** (クライアント描画)雲の量を固定する(−1 = 日ごとに変わる。{@link com.yayuy.overgrownvegetation.sky.CloudField}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudCoverage;
        /** (クライアント描画)流れる雲の影の強さ({@link com.yayuy.overgrownvegetation.client.CloudShadows}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudShadows;
        /** (クライアント描画)薄明光線(天使の梯子)の強さ({@link com.yayuy.overgrownvegetation.client.CrepuscularRays}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue crepuscularRays;
        /** (クライアント描画)積乱雲を立てるか(0 で立てない)({@link com.yayuy.overgrownvegetation.sky.CloudField.Storm}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cumulonimbus;
        /** (クライアント描画)積乱雲が 1 升(1280 ブロック四方)に立つ確率を固定する(−1 = 雲の量と雷雨で決まる)。 */
        public final ModConfigSpec.DoubleValue cumulonimbusChance;
        /** (クライアント描画)積乱雲の雷の光る頻度の倍率({@link com.yayuy.overgrownvegetation.client.Thunderstorms}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue lightningRate;
        /** (クライアント描画)立つ積乱雲のうち巨大(まわりに雲海)になる割合を固定する(−1 = 既定 0.12)。 */
        public final ModConfigSpec.DoubleValue giantStormChance;
        /** (クライアント描画)積乱雲の並び方(1 群れ・2 谷・3 壁と橋。10-17 夜の案出し中)。 */
        public final ModConfigSpec.IntValue cumulonimbusStyle;
        /** (クライアント描画)高空の晴天の強さと高さ({@link com.yayuy.overgrownvegetation.client.HighSky}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue highSkyClarity;
        public final ModConfigSpec.DoubleValue highSkyClearFrom;
        public final ModConfigSpec.DoubleValue highSkyClearTo;
        /** (クライアント描画)雲の中の演出の強さ(白い霧・もや・ちぎれ雲・画面の水滴・霧の向こうの影。{@link com.yayuy.overgrownvegetation.client.CloudInterior}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudInterior;
        /** (クライアント)乱気流の強さ(積乱雲の中で視点が揺れ、気流に押される。{@link com.yayuy.overgrownvegetation.client.CloudTurbulence}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudTurbulence;
        /** (クライアント)雲の中の風の音の大きさ({@link com.yayuy.overgrownvegetation.client.sound.CloudWindSound}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudWindSound;
        /** (ゲームの決まり)雷雨の積乱雲の中を走る雷(触れたら即死。{@link com.yayuy.overgrownvegetation.sky.CloudLightning}、2026-10-17)。 */
        public final ModConfigSpec.BooleanValue cloudLightning;
        public final ModConfigSpec.DoubleValue cloudLightningRate;
        /** (クライアント描画)雲のセル画調の陰影・輪郭のやわらかさ・雲が雲に落とす影の強さ({@link com.yayuy.overgrownvegetation.client.VoxelClouds}、2026-10-17)。 */
        public final ModConfigSpec.DoubleValue cloudCelShading;
        public final ModConfigSpec.DoubleValue cloudSoftEdges;
        public final ModConfigSpec.DoubleValue cloudSelfShadows;
        /** (クライアント描画)参考画像に寄せた荒天の雲・風の筋・青い霞の強さ({@link com.yayuy.overgrownvegetation.client.VoxelClouds}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue cloudRoughWeather;
        public final ModConfigSpec.DoubleValue cloudWindStreaks;
        public final ModConfigSpec.DoubleValue cloudBlueHaze;
        /** (クライアント描画)巻雲の濃さと量({@link com.yayuy.overgrownvegetation.client.Cirrus}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue cirrus;
        public final ModConfigSpec.DoubleValue cirrusAmount;
        /** (クライアント描画)雲に穴が開く演出({@link com.yayuy.overgrownvegetation.client.CloudHoles}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue cloudHoles;
        /** (クライアント描画)雲の質感(筆のムラ・雲の海の段差・積乱雲の底・遠くの輪郭。{@link com.yayuy.overgrownvegetation.client.VoxelClouds}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue cloudTexture;
        /** (クライアント描画)空の光({@link com.yayuy.overgrownvegetation.client.SkyDome}・{@link com.yayuy.overgrownvegetation.client.SkyLight}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue skyLight;
        public final ModConfigSpec.DoubleValue sunGlare;
        public final ModConfigSpec.DoubleValue highStars;
        public final ModConfigSpec.DoubleValue nightClouds;
        public final ModConfigSpec.DoubleValue skyRays;
        /** (クライアント描画)雲の上から見る遠くの雲({@link com.yayuy.overgrownvegetation.client.VoxelClouds} の遠景・{@link com.yayuy.overgrownvegetation.client.SkyDome} の書き割り、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue farClouds;
        public final ModConfigSpec.DoubleValue cloudCurvature;
        public final ModConfigSpec.DoubleValue cloudHorizon;
        /** (クライアント描画)風の流れの白い帯({@link com.yayuy.overgrownvegetation.client.WindRibbons}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue windRibbons;
        /** 尾流雲(金床と雲の底から垂れて消える筋。{@link com.yayuy.overgrownvegetation.client.Virga}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue virga;
        /** 雲を引く(雲から出た速いもの・空の巨大生物が後ろに雲のかけらを残す。{@link com.yayuy.overgrownvegetation.client.CloudWake}、2026-10-19)。 */
        public final ModConfigSpec.DoubleValue cloudWake;
        /** 空の気流(上昇・下降気流と突風。{@link com.yayuy.overgrownvegetation.client.SkyAir}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue skyAir;
        /** (クライアント描画)雲の海の切れ目の下の霞んだ地面({@link com.yayuy.overgrownvegetation.client.FarGround}、2026-10-18)。 */
        public final ModConfigSpec.DoubleValue farGround;
        public final ModConfigSpec.BooleanValue morningMist;
        public final ModConfigSpec.DoubleValue morningMistDensity;
        public final ModConfigSpec.DoubleValue morningMistFogDistance;
        public final ModConfigSpec.DoubleValue caveLightFloor;
        public final ModConfigSpec.BooleanValue caveMist;
        public final ModConfigSpec.DoubleValue caveMistDensity;
        public final ModConfigSpec.DoubleValue caveMistHeight;
        public final ModConfigSpec.BooleanValue climaxJungleEffects;
        /** (クライアント描画)過繁茂の森で時々、地脈になる線に沿って光が流れるか({@link com.yayuy.overgrownvegetation.client.LeyFlow}、2026-10-16)。 */
        public final ModConfigSpec.BooleanValue leyFlowEnabled;
        /** (クライアント描画)夜、地脈ブロックとエネルギー結晶のまわりに光のにじみと粒を描くか(2026-10-16)。 */
        public final ModConfigSpec.BooleanValue leyNightGlow;

        /**
         * (クライアント操作)剣・斧などの武器を持っている間、<b>当たり判定の無いブロック</b>
         * (下草・花・蔦など)に照準を吸われず、その奥にいる Mob を攻撃対象にするか
         * ({@link com.yayuy.overgrownvegetation.client.SwingThroughFoliage})。
         * 照準の決定だけを差し替えるクライアント側の処理で、サーバーには影響しない。
         */
        public final ModConfigSpec.BooleanValue swingThroughFoliage;

        /**
         * {@code #overgrown_vegetation:overgrowth_bane} タグの武器が、グループ1〜10 の Mob に与える
         * 近接ダメージの倍率。既定 1.5(= 1.5 倍)。1.0 で無効。
         */
        public final ModConfigSpec.DoubleValue overgrowthBaneMultiplier;

        /**
         * 樹液結晶を打ち込んだ {@code #overgrown_vegetation:overgrowth_ward} の防具を<b>4 部位そろえて</b>
         * 着ているとき、グループ1〜10 から受けるダメージに掛かる倍率。既定 0.8。
         * 部位が欠けているぶんは効果も比例して小さくなる(1 部位ごとに軽減の 1/4)。
         */
        public final ModConfigSpec.DoubleValue sapCrystalWardMultiplier;

        /**
         * {@code #overgrown_vegetation:overgrowth_resistant} の防具 1 部位につき、グループ1〜10 から
         * 受けるダメージを減らす割合。既定 0.1(= 10%)。4 部位そろえて 40%。
         */
        public final ModConfigSpec.DoubleValue overgrowthResistantPerPiece;

        Common(ModConfigSpec.Builder b) {
            b.comment("OvergrownVegetation — 侵食の設定").push("corruption");

            corruptionRadius = b
                    .comment("汚染が広がる球の半径(ブロック)。大きくするほど負荷が上がります。")
                    .defineInRange("radius", 30, 1, 128);

            blocksPerCycle = b
                    .comment("1 サイクルで置き換える最大ブロック数(開始値)。",
                            "実際の速度 = 実効blocksPerCycle / tickInterval * 20 ブロック毎秒。",
                            "blocksPerCycleGrowthPer が 0 でなければ、汚染が進むにつれてこの値から",
                            "blocksPerCycleMax まで増えていく(下記参照)。")
                    .defineInRange("blocksPerCycle", 2, 1, 4096);

            blocksPerCycleMax = b
                    .comment("blocksPerCycle が汚染の進行とともに増えていく上限値。")
                    .defineInRange("blocksPerCycleMax", 10, 1, 4096);

            blocksPerCycleGrowthPer = b
                    .comment("この個体(blight_fern・汚染死体ブロックそれぞれ)がこれまでに実際に汚染した",
                            "ブロック数がこの値だけ増えるたびに、1サイクルで置き換える最大ブロック数が1増える",
                            "(blocksPerCycleMax が上限)。汚染範囲が広がるほど同時に置き換わる数も",
                            "増えていく効果。0 にすると無効(常に blocksPerCycle のまま増えない)。")
                    .defineInRange("blocksPerCycleGrowthPer", 50, 0, 1000000);

            corruptionSpeedMultiplier = b
                    .comment("実効 blocksPerCycle にさらに掛ける倍率の、全ワールド共通の既定値。",
                            "倍率はワールドごと(ワールドの難易度「汚染シダの汚染速度」)で、この値はワールドに値が無いときだけ使う。",
                            "/ov corruption speed・/ov difficulty・ワールド作成画面やポーズ画面の葉っぱのボタンは",
                            "ワールドの値を書き換えるので、この値は変わらない。",
                            "1.0 で無効(常に等倍)。")
                    .defineInRange("corruptionSpeedMultiplier", 1.0D, 0.01D, 100.0D);

            checksPerCycle = b
                    .comment("1 サイクルで走査する最大ブロック数。置換対象が見つからない領域を素早く飛ばすための上限です。",
                            "置換速度には影響しません。空洞や既に汚染済みの領域を飛ばす速さだけが変わります。")
                    .defineInRange("checksPerCycle", 8192, 16, 65536);

            tickInterval = b
                    .comment("何 tick おきに侵食処理を行うか (20 tick = 1 秒)。",
                            "既定の tickInterval=20 / blocksPerCycle=1 で毎秒 1 ブロック。")
                    .defineInRange("tickInterval", 20, 1, 1200);

            blightFernDormantPhysics = b
                    .comment("汚染シダ・汚染水草が着地して静止している間、重力・摩擦・衝突の計算を飛ばす(休眠物理)。",
                            "動かない植物なので結果は変わらず、1 体あたりの tick コストの大半が消える。",
                            "足元のブロックが消えた・押された・水流に乗った tick は通常の物理に戻る。",
                            "挙動の比較をしたいときだけ false にする。")
                    .define("blightFernDormantPhysics", true);

            blightFernInteriorDepth = b
                    .comment("blight_fern: 露出面のあるブロックを置き換え終えたあと、そこから何ブロック奥(露出していない内部)まで",
                            "置き換えるか。0 なら露出面のあるブロックのみになる。",
                            "汚染死体ブロックはこの設定に関わらず常に露出面のみ(0固定)。")
                    .defineInRange("blightFernInteriorDepth", 0, 0, 64);

            tendrilCoreRadius = b
                    .comment("blight_fern: 起点(足元)を中心に、半径何ブロックを「コア」として広げるか",
                            "(触手フェーズより前の、起点付近だけの下ごしらえ)。0なら無効",
                            "(最初から触手だけで広がる旧仕様)。",
                            "汚染死体ブロックはこの設定に関わらず常に0固定(コアなし)。")
                    .defineInRange("tendrilCoreRadius", 10, 0, 32);

            coreInitialBranches = b
                    .comment("コアの起点(足元)セルだけが最初に同時分岐する方向の本数。",
                            "コアの他のマスは常に1方向だけへ(1ブロックずつランダムな方向へ)進むので、",
                            "この値がコア全体で独立して伸びる「筋」の本数を事実上決める。")
                    .defineInRange("coreInitialBranches", 6, 1, 16);

            tendrilInitialCount = b
                    .comment("起点(seedGroundした足元、コアが有効ならその縁)から最初に放射状に伸ばす",
                            "「根」(触手)の本数。根は他の触手が1本でも生きている間、毎tickの前進権を",
                            "独占する(まず根が伸びきり、それから根沿いに枝が湧いて空間を埋めていく)。")
                    .defineInRange("tendrilInitialCount", 6, 1, 16);

            tendrilBranchChance = b
                    .comment("触手が実際に1ブロック汚染するたびに、その場所から新しい触手が分岐する確率。",
                            "高いほど密に枝分かれし、低いほど1本1本が長く伸びる筋になる。")
                    .defineInRange("tendrilBranchChance", 0.18, 0.0, 1.0);

            tendrilMaxActive = b
                    .comment("同時に存在できる触手の最大本数。分岐で増え続けて重くなるのを防ぐ上限で、",
                            "これに達すると tendrilBranchChance が当たっても新しい触手は生まれない。",
                            "大きいほど根沿いの横広がりが途切れにくくなり、残ったスペースを埋めやすくなる。")
                    .defineInRange("tendrilMaxActive", 64, 1, 256);

            tendrilStraightChance = b
                    .comment("触手が直前と同じ方向へまっすぐ伸び続けようとする確率。",
                            "外れると(かつ直前の方向が塞がっていた場合も)横向き4方向のどれかへランダムに曲がる。",
                            "低いほど蛇行が激しくなる。")
                    .defineInRange("tendrilStraightChance", 0.3, 0.0, 1.0);

            tendrilBacktrackBudget = b
                    .comment("触手が行き止まりに達したとき、経路を何ブロック分まで後戻りして別方向を試せるか",
                            "(触手1本あたりの累計予算。後戻り自体はブロックを置き換えない)。",
                            "0なら後戻りせず即座に消滅する。値を大きくするほど1本の触手が枯れにくくなり",
                            "遠くまで届くようになるが、大きすぎる(数十以上)とほぼ全域を塗りつぶしてしまい",
                            "「まばらな筋」に見えなくなる点に注意。")
                    .defineInRange("tendrilBacktrackBudget", 6, 0, 64);

            corruptBlockEntities = b
                    .comment("チェストや看板など BlockEntity を持つブロックも汚染するか (true にすると中身が失われます)。",
                            "チェストと樽はこの設定に関係なく、中身を保ったまま「過繫茂したチェスト/樽」へ変換されます。")
                    .define("corruptBlockEntities", false);

            overgrownContainerStuckChanceAtMax = b
                    .comment("過繫茂したドア/チェスト/樽が蔦・コケにびっしり覆われた状態(段階 3)のとき、開けようとして",
                            "開けられない確率。段階 1・2 はこの 1/3・2/3。段階 0(ハサミで剥がした直後・置き直した直後)は必ず開く。")
                    .defineInRange("overgrownContainerStuckChanceAtMax", 0.75D, 0.0D, 1.0D);

            overgrownContainerRegrowChance = b
                    .comment("過繫茂したドア/チェスト/樽の覆われ具合が、ランダムティックごとに 1 段階進む確率。",
                            "randomTickSpeed=3 では 1 ブロックが約 68 秒に 1 回ランダムティックを受けるので、",
                            "0.35 なら剥がしてから約 3 分で 1 段階、約 10 分でびっしり(段階 3)に戻る。0 で再繁茂しない。")
                    .defineInRange("overgrownContainerRegrowChance", 0.35D, 0.0D, 1.0D);

            saplingOfflineGrowth = b
                    .comment("読み込まれていない間に育つはずだった汚染苗木(overgrown_sapling)を、チャンク読み込み時に追いつかせるか。",
                            "苗木の設置時刻をチャンクに記録しておき、読み込んだときに設置からの経過時間が",
                            "saplingOfflineGrowthTicks 以上なら、次の tick から少しずつ育てる。未読み込み中の処理は無い。")
                    .define("saplingOfflineGrowth", true);

            saplingOfflineGrowthTicks = b
                    .comment("追いつき成長: 設置からこの tick 数以上経った苗木をチャンク読み込み時に育てる。",
                            "既定 9600 = 8 分(randomTickSpeed=3 でのランダムティック成長の平均所要時間とほぼ同じ)。")
                    .defineInRange("saplingOfflineGrowthTicks", 9600, 20, 72000000);

            saplingOfflineGrowthPerTick = b
                    .comment("追いつき成長: 1 tick に育てる本数の上限。長時間放置した場所に戻ったとき、",
                            "数十本が同じ tick に育ってカクつくのを防ぐ。")
                    .defineInRange("saplingOfflineGrowthPerTick", 2, 1, 64);

            convertLavaToBasalt = b
                    .comment("汚染が溶岩の源泉 (minecraft:lava の source ブロック) に達したとき、玄武岩",
                            "へ固めるか(2026-10-05 から覆われた玄武岩。3 割は過繁茂しているブロック)。流れている溶岩は上流の源泉を固める。",
                            "false なら溶岩は汚染できない障害物のままで、そこで筋が途切れる。",
                            "水その他の液体は常に対象外。")
                    .define("convertLavaToBasalt", true);

            effectChance = b
                    .comment("置き換え時に破壊パーティクル/音を再生する確率。")
                    .defineInRange("effectChance", 0.15D, 0.0D, 1.0D);

            randomBlockChance = b
                    .comment("ブロックを汚染する際、対応表の変換先を無視して汎用の「汚染ブロック」(overgrown_block)に",
                            "差し替える確率。元のブロックの種類は問わない(石でも原木でも階段でも対象になる)。")
                    .defineInRange("randomBlockChance", 0.05D, 0.0D, 1.0D);

            stoneToGrassChance = b
                    .comment("石系ブロック(石・深層岩・安山岩・閃緑岩・花崗岩・丸石・鍾乳石ブロックなど、",
                            "overgrown_stone 系へ変換されるブロック)を汚染する際、その変換先の代わりに",
                            "overgrown_grass_block(過繁茂した草ブロック)へ差し替える確率。差し替わった場合、",
                            "真上が空気なら露出面抽選の確率に関係なく必ず上面へ下草を生やす。")
                    .defineInRange("stoneToGrassChance", 0.3D, 0.0D, 1.0D);

            dirtToGrassChance = b
                    .comment("汚染された土(overgrown_dirt)が、隣に汚染された草ブロックが無くても、",
                            "ランダムティックのたびに自分から過繁茂した草ブロックへ変わる確率。",
                            "0.0 にすると隣の草ブロックからの伝染でのみ草化する(バニラの土に近い挙動)。",
                            "真上が塞がって暗い/深い水中の場合は変化しない。")
                    .defineInRange("dirtToGrassChance", 0.25D, 0.0D, 1.0D);

            preferExistingTrees = b
                    .comment("触手が次に進む方向を選ぶとき、隣に既存の木の原木(#minecraft:logs)があれば",
                            "そちらを最優先にするか。true にすると汚染が既存の木を優先的に飲み込んでいく。")
                    .define("preferExistingTrees", true);

            decayCorruptedTreeLeaves = b
                    .comment("触手が既存の木の原木(#minecraft:logs)を汚染したとき、その木の自然生成の葉も",
                            "枯らして消すか。true にすると既存の木を汚染すると葉がしっかり消え、宙に浮いた",
                            "葉が残らない(既存の木を枯らして新しい木の土壌にする、という見た目)。")
                    .define("decayCorruptedTreeLeaves", true);

            corruptionArchChance = b
                    .comment("汚染の筋が1ブロック汚染するたびに、そこから巨大樹の根と同じ形の「アーチ」",
                            "(giant_overgrown_log の正弦カーブの橋)を1本生やす確率。0.0 で無効。装飾なので低め推奨",
                            "(既定 0.0003 ≒ 汚染 3000〜4000 ブロックあたり1本)。水中/水面をまたぐアーチも同じ確率。",
                            "このワールドに巨大樹が1本でも生えている(= 古代樹の核が存在する)ときだけ生成される。")
                    .defineInRange("corruptionArchChance", 0.0003D, 0.0D, 1.0D);
            corruptionArchMinLength = b
                    .comment("装飾アーチ / 川の橋の水平方向の長さの最小値(ブロック)。実際の長さはこの範囲でランダムに変動する。")
                    .defineInRange("corruptionArchMinLength", 3, 2, 64);
            corruptionArchMaxLength = b
                    .comment("装飾アーチの水平方向の長さの最大値(ブロック)。")
                    .defineInRange("corruptionArchMaxLength", 30, 2, 64);
            corruptionArchMinThickness = b
                    .comment("アーチの各ステップで置く塊の半径の最小値(長さが最小のときの太さ)。0=1マス、1≒十字断面、2〜=太い根。")
                    .defineInRange("corruptionArchMinThickness", 0, 0, 6);
            corruptionArchMaxThickness = b
                    .comment("アーチの各ステップで置く塊の半径の最大値(長さが最大のときの太さ)。",
                            "太さはランダムではなくアーチの長さに比例して min〜max を取る。",
                            "長さに応じて持ち上げ量の上限も上がり、大きいアーチほど高くなりやすい。",
                            "大きくすると最長アーチ1本の設置ブロック数が急増する(3 で最長アーチ ≒ 5000 ブロック)ので注意。")
                    .defineInRange("corruptionArchMaxThickness", 2, 0, 6);
            corruptionArchGrowthChance = b
                    .comment("アーチの背(頂部)の列1つあたり、overgrown_grass_block へ変えて草・下草・苗木を",
                            "生やす確率。外れた列は丸太のまま露出面にシダ・苔・蔦が生える。0.0 で背は緑化しない。",
                            "既定 0.95(2026-09-17 に 0.5 から引き上げ。根のアーチにしっかり草を生やすため)。")
                    .defineInRange("corruptionArchGrowthChance", 0.95D, 0.0D, 1.0D);
            corruptionRavineArchShare = b
                    .comment("アーチを1本生やすと決まったとき、まず「溝(渓谷)をまたぐ上下逆のアーチ」",
                            "(中央が谷底へ向かって垂れ下がる根)を試す割合。溝が見つからなければそのまま",
                            "通常のアーチになるので、溝の無い場所でのアーチの本数は変わらない。",
                            "つまり実際の出現率は corruptionArchChance × この値 ×(溝のある場所)。",
                            "0.0 で上下逆のアーチを作らない。既定 0.15。")
                    .defineInRange("corruptionRavineArchShare", 0.15D, 0.0D, 1.0D);
            corruptionRavineArchMinDepth = b
                    .comment("上下逆のアーチを架ける「溝」と認める深さ(ブロック)。縁の高さから下へこのマス数ぶん、",
                            "乗れる地面も液体も無い列を溝とみなす。大きくすると深い渓谷だけが対象になる。既定 6。")
                    .defineInRange("corruptionRavineArchMinDepth", 6, 2, 64);
            corruptionBridgeChance = b
                    .comment("触手が水でブロックされたとき、対岸まで根のアーチと同じ形の橋を架けて渡ろうとする確率。",
                            "0.0 で無効(従来どおり水で止まる)。川で汚染が止まらないようにするための機能。既定 0.04(2026-09-11 に 0.15 から引き下げ)。")
                    .defineInRange("corruptionBridgeChance", 0.04D, 0.0D, 1.0D);
            corruptionBridgeMaxLength = b
                    .comment("橋で渡れる水面の最大の幅(ブロック)。これより広い水(海など)は渡らない。")
                    .defineInRange("corruptionBridgeMaxLength", 20, 2, 96);
            corruptionBridgeMaxCount = b
                    .comment("1 個体(blight_fern・汚染死体ブロック)が架けられる橋の最大本数。水辺に橋が並びすぎないための上限。")
                    .defineInRange("corruptionBridgeMaxCount", 3, 0, 64);

            // 死に由来する汚染シダの密度の上限(2026-09-25、ユーザー承認。値は仮決め 2026-09-25)。
            // 大量死の地点で 1 体ごとに 1 本ずつ湧き、同じ場所を汚染する起点が重なっていたため。
            blightFernDeathSpawnMinSeparation = b
                    .comment("死に由来する汚染シダ(胞子感染死・グループ1の過繁茂滞在死・胞子感染3の期限切れで",
                            "死んだプレイヤー)は、死んだ地点からこの距離(ブロック)以内に汚染シダ/汚染水草が",
                            "1 体でもいれば湧かない。大量死の地点に起点が固まるのを防ぐ間隔。0 で無効。",
                            "攻城戦ボスに殺された Mob・死体ブロックの孵化・次世代の湧きには効かない。")
                    .defineInRange("blightFernDeathSpawnMinSeparation", 8, 0, 128);

            blightFernDeathSpawnMaxNearby = b
                    .comment("死に由来する汚染シダの密度の上限。死んだ地点の周囲 blightFernDeathSpawnNearbyRadius",
                            "ブロック以内に既にこの数以上の汚染シダ/汚染水草がいれば湧かない。0 で無効。",
                            "既定 10 は blightFernFinishSpawnMaxNearby(半径 64 で 40)と同じ面積あたりの密度。")
                    .defineInRange("blightFernDeathSpawnMaxNearby", 10, 0, 1000);

            blightFernDeathSpawnNearbyRadius = b
                    .comment("上記 blightFernDeathSpawnMaxNearby の判定に使う、死んだ地点からの半径(ブロック)。")
                    .defineInRange("blightFernDeathSpawnNearbyRadius", 32, 8, 256);

            spawnGrowthPlants = b
                    .comment("汚染したブロックの露出面(上/側面/下)にシダ・苔・蔦などの装飾植物を生やすか。")
                    .define("spawnGrowthPlants", true);

            growthChance = b
                    .comment("露出面 1 つあたりに装飾植物を生やす確率。",
                            "6 面すべてが露出していても必ず 6 個生えるわけではなく、面ごとに毎回この確率で抽選する。",
                            "密林の空を覆うくらい木・下草を密集させたい場合はここを上げる(treeChance も参照)。")
                    .defineInRange("growthChance", 0.85D, 0.0D, 1.0D);

            tallGrowthChance = b
                    .comment("上面(露出面が UP)に植物を生やすと決まった際、",
                            "高さ2ブロックの植物(大きなシダなど)を選ぶ確率。残りは通常の下草になる。")
                    .defineInRange("tallGrowthChance", 0.2D, 0.0D, 1.0D);

            spawnTrees = b
                    .comment("汚染した草・土の露出した上面に、下草の代わりに苗木を植えることがあるか。",
                            "植えられた苗木はバニラの苗木と同じくランダムティックで自動的に育ち、木になる。",
                            "「広がる密林」の森自体を作る設定。false にすると木は生えなくなる。")
                    .define("spawnTrees", true);

            treeChance = b
                    .comment("上面が草・土だった場合に、装飾植物の代わりに苗木を植える確率。",
                            "実際に苗木が植わる確率は growthChance × treeChance になる(既定値では約 10%)。",
                            "密林をもっと濃くしたいときはここを上げる。既定 0.12(2026-09-20 に 0.1 から 1.2 倍へ引き上げ)。")
                    .defineInRange("treeChance", 0.12D, 0.0D, 1.0D);

            // 2026-10-15 ユーザー指定「木の密度を下げる」(日が差す演出・木漏れ日を見やすく。葉が何層も重なると暗く、
            // その下で下草が育つのはおかしい)。間隔 8・空き地 15% で試したら疎らすぎた(ユーザー「上から見て地面が見えなくなるくらい」)→ 6・8% に。値は仮決め。
            // 2026-10-17 ユーザー「木の密度を今よりも少しだけ上げてほしい」→ 6 → 5(木の数は約 1.4 倍。値は仮決め。4 は林床がほぼ真っ暗だった)。
            treeSpacing = b
                    .comment("汚染で生える木の間隔(ブロック)。この大きさの格子 1 マスに木は 1 本まで(マスの中の位置はワールドのシードで決まる)。",
                            "大きくするほど森がまばらになり、林床に光が届く。0 で前の挙動(上面ごとに treeChance の確率で苗木。木がとても密になる)。",
                            "菌湿地帯(菌の柱)には効かない(もともと疎ら)。既に生えた木は減らない。既定 5。")
                    .defineInRange("treeSpacing", 5, 0, 64);
            // 2026-10-17 ユーザー「木の生えないエリアを少なくしても良い」「森の中に入った時、空が見える割合を減らして」→ 0.08 → 0.03(値は仮決め)。
            treeClearingChance = b
                    .comment("treeSpacing の格子のマスが、木の無い空き地になる確率。森の中に日だまりができる。既定 0.03。")
                    .defineInRange("treeClearingChance", 0.03D, 0.0D, 1.0D);
            // 2026-10-16 ユーザー選択「濃い森の光 = 森に穴を残す + 葉を樹冠の数で数える」の前者。値は仮決め。
            treeCanopyGapChance = b
                    .comment("林冠の穴: 36 ブロックの格子のマスごとに、この確率で半径 8〜11 ブロックの「木を植えない輪」を置く。",
                            "木の枝と葉は幹から 6〜7 ブロック広がるので、空き地 1 マス(treeClearingChance)では上がふさがるが、",
                            "この輪の上には林冠の穴が開き、森を濃くしても光の筋が地面まで差し込む。0 で無し。既に生えた木は減らない。既定 0.25。")
                    .defineInRange("treeCanopyGapChance", 0.25D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー「一度試しでクラウン・シャイネスを実装してみたい」→ 試した後「切って、空を減らす方を優先」で既定 0。隙間 1・見る高さ ±32 は仮決め(±4 では下から見えなかった)。
            crownShynessGap = b
                    .comment("樹冠の譲り合い(クラウン・シャイネス): 汚染で育つ木の葉を、ほかの木の葉からこのブロック数だけ離して止める(後から育つ木が譲る)。",
                            "見上げると樹冠と樹冠の間に細い空の切れ目ができる。見る上下の幅は crownShynessHeight。",
                            "0 で無し(後から育つ木の葉が前の木の葉を上書きして樹冠がつながる)。既に生えた木は変わらない。既定 0(試しで 1 にしたが、空が増えるのでユーザーが切った)。")
                    .defineInRange("crownShynessGap", 0, 0, 3);
            crownShynessHeight = b
                    .comment("樹冠の譲り合いで見る上下の幅(ブロック)。この高さの差より離れたほかの木の葉は見ない(高さの違う樹冠は重なってよい)。",
                            "小さい(4 など)と下から見上げたとき隙間がほかの高さの樹冠で埋まって見えない。大きいと縦に抜けた空の切れ目が見える。既定 32。")
                    .defineInRange("crownShynessHeight", 32, 1, 64);

            spawnUndevelopedBush = b
                    .comment("汚染した露出上面に「未発達の茂み」(undeveloped_bush)を生やすことがあるか。",
                            "実が付く下草で、実を右クリックで収穫すると「未発達な種子」が採れる。")
                    .define("spawnUndevelopedBush", true);

            undevelopedBushChance = b
                    .comment("上面に装飾植物の代わりに「未発達の茂み」を生やす確率。抽選は苗木の次に行う。",
                            "実際に生える確率は growthChance × undevelopedBushChance になる(既定値では約 6%)。",
                            "既定は treeChance(0.1)より少し低い 0.07 —— 苗木よりやや珍しい草、という位置づけ。")
                    .defineInRange("undevelopedBushChance", 0.07D, 0.0D, 1.0D);

            undevelopedFruitChance = b
                    .comment("過繫茂した木が生え終わったとき、置いた葉ブロック1個ごとに真下へ",
                            "「大きな未発達な果実」(large_undeveloped_fruit)をぶら下げる確率。",
                            "実際にぶら下がれるのは真下が空気の葉(=樹冠の下面)だけなので、",
                            "葉の総数(数百)より抽選対象はずっと少ない。既定 0.002 で木 5〜10 本に 1 個ほど。")
                    .defineInRange("undevelopedFruitChance", 0.002D, 0.0D, 1.0D);

            giantTreeUndevelopedFruitChance = b
                    .comment("巨大樹の葉ブロック1個ごとに「大きな未発達な果実」をぶら下げる確率。",
                            "巨大樹の葉は普通の木より桁が2つ多いので、別の(ずっと小さい)値にしてある。",
                            "既定 0.0002 で巨大樹1本あたり数個。")
                    .defineInRange("giantTreeUndevelopedFruitChance", 0.0002D, 0.0D, 1.0D);

            spawnLeafClumps = b
                    .comment("ブロックが汚染されたとき、overgrown_bush(過繁茂した茂み。当たり判定なしの葉)の",
                            "いびつな小さな塊を空気中に生成するか。きれいな立方体ではなく、外周が乱数で",
                            "欠けた楕円体状の茂みになる。")
                    .define("spawnLeafClumps", true);

            leafClumpChance = b
                    .comment("ブロックを1つ汚染するたびに、その上の空間へ茂みの塊を1つ生成する確率。",
                            "露出した上面(真上が空気)のときだけ生成する。")
                    .defineInRange("leafClumpChance", 0.02D, 0.0D, 1.0D);

            wallLeafClumpChance = b
                    .comment("壁面(側面の露出面)に汚染植物が生えるとき、そこに壁から突き出す茂みの塊も",
                            "生成する確率。地上の leafClumpChance より低めにしておくこと",
                            "(壁面ではさらに growthChance の抽選に通った面でしか判定されない)。")
                    .defineInRange("wallLeafClumpChance", 0.01D, 0.0D, 1.0D);

            bushSlowMultiplier = b
                    .comment("overgrown_bush(過繁茂した茂み)の中に入っている間の水平移動速度の倍率。",
                            "小さいほど強く減速する(バニラのスイートベリーの茂みは 0.8 相当)。",
                            "1.0 にすると減速しなくなる。縦方向は 0.75 固定(茂みから飛び出しにくくする)。")
                    .defineInRange("bushSlowMultiplier", 0.8D, 0.0D, 1.0D);

            bushUndergrowthChance = b
                    .comment("生成した茂みの列(X/Z)ごとに、その上面に下草(汚染時の露出上面と同じ",
                            "シダ・胞子キノコなどの抽選)を1つ生やす確率。0.0 で茂みからは下草が生えない。")
                    .defineInRange("bushUndergrowthChance", 0.3D, 0.0D, 1.0D);

            spawnGlowVines = b
                    .comment("汚染で下面(天井)に垂れ下がる植物を生やす際、通常の垂れ蔦の代わりに",
                            "overgrown_glow_vines(光るツタ。バニラのグロウベリーと同じ仕様)の先端を",
                            "生やすことがあるか。以降の伸長・実の付き方はバニラのグロウベリーと同じくランダムティック任せ。")
                    .define("spawnGlowVines", true);

            glowVinesChance = b
                    .comment("下面に垂れ下がる植物を生やすと決まった際、通常の垂れ蔦の代わりに",
                            "光るツタの先端を生やす確率。地下では glowVinesCaveMultiplier 倍になる。")
                    .defineInRange("glowVinesChance", 0.05D, 0.0D, 1.0D);

            glowVinesCaveMultiplier = b
                    .comment("地下(洞窟。地表より caveDepthMargin 以上深い場所)では、光るツタの先端を生やす確率",
                            "glowVinesChance にこの倍率を掛ける(上限 1.0)。光るツタは洞窟の明かりなので地上よりかなり多くする。",
                            "1.0 で地上と同じ。既定 6.0(既定値どおりなら地上 0.05 → 地下 0.30)。")
                    .defineInRange("glowVinesCaveMultiplier", 6.0D, 1.0D, 100.0D);

            spawnMossLayer = b
                    .comment("汚染時に露出面(上面・側面・下面)へ、薄く広がる苔(overgrown_moss_layer)を",
                            "生やすか。バニラのヒカリゴケ / ツタと同じ多面ブロックで、上面・側面・下面が",
                            "同じ1マスの中で同時に存在できる。")
                    .define("spawnMossLayer", true);

            mossLayerChance = b
                    .comment("汚染された各露出面につき、薄く広がる苔を1面ぶん生やす確率。",
                            "通常の下草・蔦の抽選とは独立して判定する(苔が付いても、外れた面には",
                            "別途通常の下草が抽選される)。")
                    .defineInRange("mossLayerChance", 0.35D, 0.0D, 1.0D);

            spawnUnderwaterPlants = b
                    .comment("水中を汚染したとき、露出した水の面へ汚染された水中植物(海草・背高海草・",
                            "藻柱・藻・水中垂れ蔓・水面のスイレン)を生やすか。",
                            "陸上の装飾植物(spawnGrowthPlants)とは独立した設定。")
                    .define("spawnUnderwaterPlants", true);

            underwaterGrowthChance = b
                    .comment("水に接した露出上面 1 つあたりに水中植物(海草・背高海草・藻柱)を生やす確率。",
                            "陸上の growthChance に相当する。下面の垂れ蔓は waterCeilingGrowthChance、",
                            "側面の藻は waterMossLayerChance の別抽選。",
                            "既定 0.95(2026-09-22 に 0.85 から引き上げ。藻を減らしたぶん水草を増やす)。")
                    .defineInRange("underwaterGrowthChance", 0.95D, 0.0D, 1.0D);

            waterCeilingGrowthChance = b
                    .comment("水に接した露出下面(天井)1 つあたりに水中垂れ蔓",
                            "(overgrown_water_ceiling_growth)を生やす確率。上面の水草・藻柱は",
                            "underwaterGrowthChance、側面の藻は waterMossLayerChance の別抽選。",
                            "既定 0.3(2026-09-22 に上面と共通の 0.95 から分離して引き下げ。",
                            "岩の裏・オーバーハングの下が垂れ蔓で埋まりすぎるため)。0.0 で垂れ蔓を生やさない。")
                    .defineInRange("waterCeilingGrowthChance", 0.3D, 0.0D, 1.0D);

            spawnWaterMossLayer = b
                    .comment("汚染時に水中の露出側面・上面へ、薄く覆う藻(overgrown_water_moss_layer)を生やすか。",
                            "陸の薄い苔(overgrown_moss_layer)と同じ多面ブロックの水中版(常に水を含む)。")
                    .define("spawnWaterMossLayer", true);

            waterMossLayerChance = b
                    .comment("汚染された水中の露出面(側面・上面)1 面につき、藻を 1 面ぶん生やす確率。",
                            "上面・下面の水中植物(underwaterGrowthChance)とは独立して判定する",
                            "(藻が生えた面には海草類は生えない)。",
                            "既定 0.7(2026-09-22 に 0.9 → 0.3 → 0.7 と調整。藻が外れた上面には水草・藻柱が",
                            "生えるので、下げるとそのぶん水草が増える)。")
                    .defineInRange("waterMossLayerChance", 0.7D, 0.0D, 1.0D);

            underwaterTallChance = b
                    .comment("上面(水側)に水中植物を生やすと決まった際、高さ2マスの背高海草を選ぶ確率。")
                    .defineInRange("underwaterTallChance", 0.25D, 0.0D, 1.0D);

            waterColumnChance = b
                    .comment("上面(水側)に水中植物を生やすと決まった際、昆布のように縦へ伸びる藻柱を選ぶ確率。",
                            "スイレンの抽選に外れたあと、背高海草より先に判定する。",
                            "既定 0.3(2026-09-22 に 0.18 から引き上げ)。")
                    .defineInRange("waterColumnChance", 0.3D, 0.0D, 1.0D);

            waterColumnFlowerChance = b
                    .comment("藻柱を 2 段目以降に 1 段積むごとに、その段を柱ではなく「藻柱の花」",
                            "(overgrown_water_column_flower)にする確率。花が咲いた時点でその藻柱の成長は止まる",
                            "(花の上には積まない)。花も高さに数えるので waterColumnMaxHeight は超えない。",
                            "0.0 で花を咲かせない(全部が伸びきるまで伸びる)。既定 0.15。")
                    .defineInRange("waterColumnFlowerChance", 0.15D, 0.0D, 1.0D);

            waterColumnMinHeight = b
                    .comment("藻柱の最小の高さ(マス)。")
                    .defineInRange("waterColumnMinHeight", 2, 1, 64);

            waterColumnMaxHeight = b
                    .comment("藻柱の最大の高さ(マス)。水面の手前で止まる。")
                    .defineInRange("waterColumnMaxHeight", 6, 1, 64);

            lilyChance = b
                    .comment("上面(水側)を汚染した際、その真上の水面へスイレンを浮かべる確率。",
                            "水深が lilyMaxDepth 以下のときだけ判定する。")
                    .defineInRange("lilyChance", 0.25D, 0.0D, 1.0D);

            lilyMaxDepth = b
                    .comment("汚染した地面から水面までの水深がこのマス数以下のときだけスイレンを浮かべる。")
                    .defineInRange("lilyMaxDepth", 10, 1, 64);

            convertToOvergrownBiome = b
                    .comment("チャンクの地表(16x16列)のうち一定割合が汚染ブロックになったら、",
                            "そのチャンクのバイオームを overgrown_vegetation:overgrown に書き換えるか。",
                            "バニラの /fillbiome コマンドと同じ経路(ChunkAccess#fillBiomesFromNoise +",
                            "ChunkMap#resendBiomesForChunks)でロード済みチャンクを直接書き換えて全クライアントへ再送する。",
                            "overgrown バイオームはワールド生成のバイオーム選択には登場しない、この書き換え専用のバイオーム。")
                    .define("convertToOvergrownBiome", true);

            overgrownBiomeThreshold = b
                    .comment("バイオームを書き換える閾値。チャンクの地表 256 列のうち、",
                            "汚染ブロックが最上部に露出している列の割合がこの値以上になった時点で書き換える。",
                            "下げるほど、汚染がそのチャンクを覆いきる前の早い段階でバイオームが切り替わる。")
                    .defineInRange("overgrownBiomeThreshold", 0.5D, 0.0D, 1.0D);

            overgrownBiomeEdgeSmoothing = b
                    .comment("バイオーム書き換えの縁を quart(4 ブロック)単位で塗り分け、隣接する別バイオームとの",
                            "縫い目を overgrownBiomeEdgeNoise ぶんノイズで波打たせて、チャンク境界に沿った",
                            "まっすぐな線・直角の階段状の境界を消すか。false にすると従来通りチャンク単位で一括に塗る。",
                            "縫い目を挟む 2 チャンクは同じノイズ場を使うので隙間や重なりはできない。")
                    .define("overgrownBiomeEdgeSmoothing", true);

            overgrownBiomeEdgeNoise = b
                    .comment("overgrownBiomeEdgeSmoothing の波打ちの最大振幅(ブロック)。縫い目は 0〜この値ぶん",
                            "チャンクの内側へ後退する。既定 8 = 最大 2 quart 列(8 ブロック)ぶん凹む。",
                            "4 未満だと外周 1 quart 列しか動かないので効果は控えめ。0 で実質無効。")
                    .defineInRange("overgrownBiomeEdgeNoise", 8.0D, 0.0D, 16.0D);

            overgrowthRegionSize = b
                    .comment("汚染で生まれる環境(密林 / 湿地 / 菌湿地帯)の地域マップの、地域 1 つの 1 辺の目安(ブロック)。",
                            "バニラのバイオーム分布とは無関係なワールドシード固有のマップで、同じ地域のチャンクは",
                            "必ず同じ環境になる(山だった場所が湿地に、平原だった場所が密林に、と一続きで塗り替わる)。",
                            "既定 768。")
                    .defineInRange("overgrowthRegionSize", 768, 64, 8192);

            overgrowthRegionWarp = b
                    .comment("地域境界のうねりの強さ(地域サイズに対する割合)。0 で多角形の境界、大きいほど入り江状にうねる。既定 0.25。")
                    .defineInRange("overgrowthRegionWarp", 0.25D, 0.0D, 1.0D);

            spawnJungleRadius = b
                    .comment("ワールドスポーン地点からこの距離(ブロック)以内は、地域マップの抽選に関わらず必ず密林になる。",
                            "最初の拠点のまわりが湿地や菌湿地帯になると、汚染が来たときに地面が水没して足場が消えるため。",
                            "境界は真円ではなくシードごとに歪ませてあるので、円形に切り替わっては見えない。",
                            "0 で無効(従来どおり地域マップだけで決まる)。既定 512。")
                    .defineInRange("spawnJungleRadius", 512, 0, 8192);

            overgrowthRegionBlend = b
                    .comment("地域の境目の移行帯の幅(ブロック)。境界からこの距離以内では、木(苗木)・泥への変換・下草・茂みが",
                            "境界に近いほど高い確率(境界上で半々)で隣の環境のルールになり、密林と湿地が徐々に混ざり合う。",
                            "バイオームの書き換えや浸水処理はチャンク単位のままで影響しない。0 で境目をくっきりさせる。既定 48。")
                    .defineInRange("overgrowthRegionBlend", 48, 0, 512);

            overgrowthVariantWeightJungle = b
                    .comment("地域が密林(従来の過繁茂バイオーム)になる重み。3 つの重みの比で抽選する。",
                            "既定は 密林 7 : 菌湿地帯 2 : 湿地 1(= 密林 70% / 菌湿地帯 20% / 湿地 10%)。既定 7.0。")
                    .defineInRange("overgrowthVariantWeightJungle", 7.0D, 0.0D, 100.0D);

            overgrowthVariantWeightSwamp = b
                    .comment("地域が湿地になる重み。既定 1.0。")
                    .defineInRange("overgrowthVariantWeightSwamp", 1.0D, 0.0D, 100.0D);

            overgrowthVariantWeightFungalSwamp = b
                    .comment("地域が菌湿地帯になる重み。既定 2.0。")
                    .defineInRange("overgrowthVariantWeightFungalSwamp", 2.0D, 0.0D, 100.0D);

            swampFlooding = b
                    .comment("湿地系(湿地 / 菌湿地帯)バイオームのチャンクに、水たまり(繁茂段階から)・池・水路・",
                            "棚田状の段々と滝(過繁茂段階から)を作るか。ワールドシード固有のノイズで場所が決まる。")
                    .define("swampFlooding", true);

            swampFloodIntervalTicks = b
                    .comment("浸水処理を走らせる間隔(tick)。既定 40 = 2 秒。")
                    .defineInRange("swampFloodIntervalTicks", 40, 1, 24000);

            swampFloodChunksPerPass = b
                    .comment("1 回の処理で扱うチャンク数。1 チャンク = 256 列をまとめて処理する。既定 1。")
                    .defineInRange("swampFloodChunksPerPass", 1, 1, 64);

            swampFloodRescanTicks = b
                    .comment("同じチャンクを再処理するまでの間隔(tick)。処理は冪等(処理済みの列は水・泥で目印が付き二度と触らない)",
                            "なので再処理は取りこぼしの拾い直し用。既定 2400 = 2 分。")
                    .defineInRange("swampFloodRescanTicks", 2400, 20, 720000);

            swampFloodSkipArtificial = b
                    .comment("地表が自然の土・石・砂や汚染地面でない列(建築物など)は、水たまりも池も作らず地形も削らないか。",
                            "true 推奨(家の中に水が溜まるのを防ぐ)。")
                    .define("swampFloodSkipArtificial", true);

            swampPondThreshold = b
                    .comment("池ノイズ(-1〜1)の閾値。これを超えた場所が池になる。下げるほど池が広く多くなる。既定 0.35。")
                    .defineInRange("swampPondThreshold", 0.35D, -1.0D, 1.0D);

            swampCreekThreshold = b
                    .comment("水路ノイズ(0〜1、尾根状)の閾値。下げるほど水路が太く増える。既定 0.86。")
                    .defineInRange("swampCreekThreshold", 0.86D, 0.0D, 1.0D);

            swampPuddleThreshold = b
                    .comment("水たまりノイズ(-1〜1)の閾値。下げるほど 1 マスの水たまりが増える。繁茂段階から現れる。既定 0.55。")
                    .defineInRange("swampPuddleThreshold", 0.55D, -1.0D, 1.0D);

            swampTerraceStep = b
                    .comment("棚田 1 段の高さ(ブロック)。斜面をこの高さごとの平らな段に削り、各段に水を張る。既定 3。")
                    .defineInRange("swampTerraceStep", 3, 2, 8);

            swampTerraceSlope = b
                    .comment("この傾斜(東西 8 ブロック間と南北 8 ブロック間の高低差の和)以上の場所を棚田にする。",
                            "それ未満の平坦な場所は池・水路になる。既定 5.0。")
                    .defineInRange("swampTerraceSlope", 5.0D, 0.0D, 64.0D);

            swampSpillwayChance = b
                    .comment("棚田の畦(下の段との境の土手)のうち、土手を切って水を溢れさせ滝にする割合。既定 0.2。")
                    .defineInRange("swampSpillwayChance", 0.2D, 0.0D, 1.0D);

            swampTerraceMaxStep = b
                    .comment("急斜面での棚田 1 段の最大高さ(ブロック)。なだらかな斜面は swampTerraceStep の小さな段を",
                            "何段も重ね、傾斜が swampTerraceSteepSlope に近づくほど段差がこの値まで大きくなり、",
                            "高い壁を持つ大きな段が数個だけになる。既定 10。")
                    .defineInRange("swampTerraceMaxStep", 10, 2, 24);

            swampTerraceSteepSlope = b
                    .comment("この傾斜(東西 8 ブロック間と南北 8 ブロック間の高低差の和)で段差が swampTerraceMaxStep に達する。",
                            "swampTerraceSlope との間は線形に補間。既定 14.0。")
                    .defineInRange("swampTerraceSteepSlope", 14.0D, 0.0D, 64.0D);

            swampTerraceMaxCut = b
                    .comment("棚田で 1 列を掘り下げる最大深さ(ブロック)。段の高さは平滑化した地表に一番近い段を選ぶので、",
                            "通常の掘り下げは段差の半分以下。これより深く掘る必要がある列(急な出っ張り)は崖として残す。",
                            "大きくすると段は綺麗に揃うが落とし穴状の深い穴ができやすい。既定 8。")
                    .defineInRange("swampTerraceMaxCut", 8, 1, 32);

            swampTerraceMaxFill = b
                    .comment("棚田で 1 列を盛り上げる最大高さ(ブロック)。段の高さに届かない窪みや斜面を過繁茂した泥で埋めて段を作る。",
                            "掘りすぎになる列は 1 段上の段、盛りすぎになる列は 1 段下の段に寄せるので、削る量と盛る量の両方が",
                            "この上限と swampTerraceMaxCut の範囲に収まる。既定 12。")
                    .defineInRange("swampTerraceMaxFill", 12, 1, 48);

            swampTerraceVoidDrop = b
                    .comment("隣接する列(8 近傍)との地表の落差がこれ(ブロック)以上ある列 — 大きな洞窟へ落ち込む穴や崖の",
                            "縁と底 — は棚田・池にせず、自然の地面として触らない。穴の縁に泥の壁と水が積み上がるのを防ぐ。",
                            "既定 10。")
                    .defineInRange("swampTerraceVoidDrop", 10, 1, 64);

            swampTerraceVoidMargin = b
                    .comment("上記の落差列から何列以内を同じく触らないか。0 で落差列そのものだけ。既定 2。")
                    .defineInRange("swampTerraceVoidMargin", 2, 0, 8);

            swampFloodStage2Columns = b
                    .comment("浸水処理は地域マップ上で湿地になる場所なら、バイオームがまだ書き換わっていなくても汚染された列から順に走る。",
                            "チャンク地表 256 列のうち汚染列がこの数以上(または既に過繁茂バイオーム)なら池・水路・棚田の段階、",
                            "それ未満は水たまりだけの段階。既定 64。")
                    .defineInRange("swampFloodStage2Columns", 64, 1, 256);

            swampGrowthChanceMultiplier = b
                    .comment("湿地・菌湿地帯になる場所で汚染したとき、装飾植物(下草・蔦)の生成確率 growthChance に掛ける倍率。",
                            "密林より草を少なくする。既定 0.35。")
                    .defineInRange("swampGrowthChanceMultiplier", 0.35D, 0.0D, 1.0D);

            swampMudMossShare = b
                    .comment("過繁茂している泥の上面には薄い苔と胞子キノコ(とまれに苗木)しか生えない。そのうち苔の割合。既定 0.8。")
                    .defineInRange("swampMudMossShare", 0.8D, 0.0D, 1.0D);

            fungalSwampTreeChanceMultiplier = b
                    .comment("菌湿地帯になる場所で汚染したとき、苗木を植える確率 treeChance に掛ける倍率。",
                            "菌類の柱を密林の木ほど密に立てないため。既定 0.037(27 分の 1。2026-09-17 に 9 分の 1 から引き下げ)。")
                    .defineInRange("fungalSwampTreeChanceMultiplier", 0.037D, 0.0D, 1.0D);

            swampTerraceShallow = b
                    .comment("棚田の各段の水面(水深 1)に水没した泥のハーフブロックを敷き、水深 0.5 の浅い段にするか。既定 true。")
                    .define("swampTerraceShallow", true);

            swampTerraceRimAboveWater = b
                    .comment("棚田の畦(段と段の仕切り)を水面より 1 段高い 2 段の土手にするか。false(既定)では",
                            "水面とちょうど同じ高さ(段の泥の 1 段上に泥 1 ブロックだけ)にして、仕切りが水面から",
                            "飛び出さないようにする。true にすると滝(swampSpillwayChance)の着水で水面の上に広がる",
                            "流水も枠の中に留められるが、仕切りが水面より 1 段高く見える。既定 false。")
                    .define("swampTerraceRimAboveWater", false);

            swampFloodProtectStructures = b
                    .comment("生成ストラクチャー(村の民家・道、前哨基地など)のピースの範囲では、水たまり・池・棚田を作らず",
                            "地形も削らないか。既定 true。")
                    .define("swampFloodProtectStructures", true);

            swampFloodStructureMargin = b
                    .comment("ストラクチャーの各ピース(建物 1 棟ぶんの箱)をこのぶん(ブロック)外側へ広げて守る。軒先・玄関前の余裕。既定 2。")
                    .defineInRange("swampFloodStructureMargin", 2, 0, 16);

            swampTreeFall = b
                    .comment("湿地・菌湿地帯になる場所で元の森の木(バニラの原木)が汚染されたとき、その木を丸ごと",
                            "消滅させるか切り株 + 倒木にするか(swampFallenTreeChance)。葉も消える。false なら従来どおり",
                            "汚染された木として立ったまま残る。")
                    .define("swampTreeFall", true);

            swampFallenTreeChance = b
                    .comment("木を消滅させる代わりに、切り株 1 個と幹の長さぶんの横倒しの原木(倒木)にする確率。既定 0.2。")
                    .defineInRange("swampFallenTreeChance", 0.2D, 0.0D, 1.0D);

            swampFallenTreeMossChance = b
                    .comment("倒木の各原木の上面に薄い苔が付く確率。既定 0.5。")
                    .defineInRange("swampFallenTreeMossChance", 0.5D, 0.0D, 1.0D);

            swampTreeFallPerTick = b
                    .comment("1 tick に処理する木の本数(森がまとめて汚染されたときの負荷上限)。既定 2。")
                    .defineInRange("swampTreeFallPerTick", 2, 1, 64);

            jungleTreeFall = b
                    .comment("密林(従来の過繁茂バイオーム)になる場所でも、元の森の木(バニラの原木)が汚染されたときに",
                            "木ごと倒木 / 消滅させるか。false なら従来どおり汚染された木として立ったまま残る。既定 true。")
                    .define("jungleTreeFall", true);

            jungleFallenTreeChance = b
                    .comment("密林で木を切り株 + 倒木にする確率(残りは消滅)。既定 0.6。")
                    .defineInRange("jungleFallenTreeChance", 0.6D, 0.0D, 1.0D);

            swampFogFactor = b
                    .comment("湿地・菌湿地帯バイオームの霧の濃さ。密林(過繁茂バイオーム)の霧を 1.0 としたときの割合で、",
                            "霧の距離の詰め方と霧色の暗さの両方に効く。0 でバニラの霧のまま。既定 0.45。")
                    .defineInRange("swampFogFactor", 0.45D, 0.0D, 1.0D);

            group1OvergrownDeath = b
                    .comment("グループ 1 の Mob(#overgrown_vegetation:group_1、寄生された動物)が繁茂系バイオーム",
                            "(過繁茂 / 繁茂、密林・湿地・菌湿地帯・海・川)の中で group1OvergrownDeathTicks を過ごすと、",
                            "その場で死んで死んだ場所に汚染シダが湧くか。既定 true。")
                    .define("group1OvergrownDeath", true);

            group1OvergrownDeathTicks = b
                    .comment("上記の滞在時間(tick)。既定 3600 = 3 分。")
                    .defineInRange("group1OvergrownDeathTicks", 3600, 20, 720000);

            group1OvergrownDeathDecay = b
                    .comment("繁茂系バイオームの外にいる間、滞在時間を同じ速さで減らすか。false(既定)なら累計で、",
                            "外に出ても時間は減らない。")
                    .define("group1OvergrownDeathDecay", false);

            swampNoBushes = b
                    .comment("湿地・菌湿地帯になる場所では茂み(overgrown_bush の塊)を一切生やさないか。既定 true。")
                    .define("swampNoBushes", true);

            waterArchChanceMultiplier = b
                    .comment("水中に架かる・水面をまたぐ根のアーチの生成確率に掛ける倍率。陸のアーチの確率",
                            "(corruptionArchChance)にこれを掛ける。1.0 で陸と同じ、0 で水辺には架けない。既定 0.067(2026-09-22 に 0.2 から 3 分の 1 へ引き下げ)。")
                    .defineInRange("waterArchChanceMultiplier", 0.067D, 0.0D, 1.0D);

            // --- 洞窟の植物・構造物 ---
            caveGrowth = b
                    .comment("洞窟(地表より caveDepthMargin 以上深い場所)専用の植物・構造物を有効にするか。",
                            "湿地・菌湿地帯の洞窟では通常の下草・蔦の代わりに微光の胞子嚢(cave_spore_sac)と",
                            "苔の塊(cave_moss_clump)が生え、まれに壁のキノコの傘(fungal_cap)・天井の根のアーチ",
                            "(giant_overgrown_log)・菌の柱(fungal_pillar + glowing_mycelium)が建つ。",
                            "密林(ジャングル)の洞窟では天井のアーチだけ(caveRootArchInJungle)。",
                            "天井のアーチは caveVineArchShare の割合で、根ではなく葉に包まれた巨大な蔦になる。既定 true。")
                    .define("caveGrowth", true);

            caveDepthMargin = b
                    .comment("その列の地表(葉を除く MOTION_BLOCKING_NO_LEAVES)よりこの深さ(ブロック)以上低い場所を",
                            "洞窟とみなす。空に開けた谷や崖の縁を洞窟扱いしないための余裕。既定 5。")
                    .defineInRange("caveDepthMargin", 5, 1, 64);

            caveGrowthChance = b
                    .comment("湿地系の洞窟で、汚染ブロックの露出面 1 つあたりに洞窟植物を生やす確率",
                            "(通常の growthChance / swampGrowthChanceMultiplier の代わりに使う)。既定 0.04(2026-09-11 に 0.35 → 0.1 → 0.04 と引き下げ)。")
                    .defineInRange("caveGrowthChance", 0.04D, 0.0D, 1.0D);

            caveSporeSacShare = b
                    .comment("洞窟植物のうち微光の胞子嚢になる割合。残りは苔の塊。既定 0.45。")
                    .defineInRange("caveSporeSacShare", 0.45D, 0.0D, 1.0D);

            caveMossClumpMinPatch = b
                    .comment("苔の塊 1 回の生成で、面に沿って置く塊の数の下限。既定 1。")
                    .defineInRange("caveMossClumpMinPatch", 1, 1, 32);

            caveMossClumpMaxPatch = b
                    .comment("苔の塊 1 回の生成で置く塊の数の上限。既定 3。")
                    .defineInRange("caveMossClumpMaxPatch", 3, 1, 32);

            caveStructureChance = b
                    .comment("洞窟の汚染ブロック 1 つあたりに構造物(壁の傘 / 天井の根 / 菌の柱)を建てようとする確率。",
                            "露出面に応じて建てられる種類を重みで選び、空間が足りなければ何も建てない。",
                            "既定 0.004(洞窟の壁 250 ブロックに 1 つ程度)。0 で構造物無し。")
                    .defineInRange("caveStructureChance", 0.004D, 0.0D, 1.0D);

            caveCapWeight = b
                    .comment("構造物の種類の重み: 壁のキノコの傘(湿地系のみ、壁面が露出しているとき)。既定 3。")
                    .defineInRange("caveCapWeight", 3, 0, 100);

            caveArchWeight = b
                    .comment("構造物の種類の重み: 天井の根のアーチ(下面が露出しているとき)。既定 2。")
                    .defineInRange("caveArchWeight", 2, 0, 100);

            cavePillarWeight = b
                    .comment("構造物の種類の重み: 菌の柱(湿地系のみ、上面が露出しているとき)。既定 2。")
                    .defineInRange("cavePillarWeight", 2, 0, 100);

            caveRootArchInJungle = b
                    .comment("密林(ジャングル)の洞窟にも天井のアーチ(根 / 蔦)を建てるか。既定 true。",
                            "植物・キノコの傘・菌の柱は湿地系の洞窟のみで、密林の洞窟はこのアーチだけが建つ。")
                    .define("caveRootArchInJungle", true);

            caveStructurePlantChance = b
                    .comment("建てた構造物の各ブロックの各面に何かを添える確率。キノコの傘・菌の柱には薄い苔かヒカリゴケの面",
                            "(胞子嚢・苔の塊は付かない)、天井の根のアーチには洞窟植物(密林では垂れ植物)。既定 0.12。")
                    .defineInRange("caveStructurePlantChance", 0.12D, 0.0D, 1.0D);

            caveStructureGlowLichenShare = b
                    .comment("キノコの傘・菌の柱の表面に添える面のうち、ヒカリゴケ(minecraft:glow_lichen)の割合。残りは薄い苔。既定 0.3。")
                    .defineInRange("caveStructureGlowLichenShare", 0.3D, 0.0D, 1.0D);

            caveCapMinRadius = b
                    .comment("壁のキノコの傘: 半径(壁からの張り出し)の下限。既定 3。")
                    .defineInRange("caveCapMinRadius", 3, 1, 16);

            caveCapMaxRadius = b
                    .comment("壁のキノコの傘: 半径の上限。既定 9。")
                    .defineInRange("caveCapMaxRadius", 9, 1, 16);

            caveCapThickness = b
                    .comment("壁のキノコの傘: 壁際(中心)の厚み(段)。縁へ向かって 1 段まで薄くなる。既定 1。")
                    .defineInRange("caveCapThickness", 1, 1, 6);

            caveCapDroop = b
                    .comment("壁のキノコの傘: 縁の垂れ下がり(マス)。0 で平ら。既定 1.0。")
                    .defineInRange("caveCapDroop", 1.0D, 0.0D, 6.0D);

            caveArchMinSpan = b
                    .comment("天井の根のアーチ: 両端の水平距離の下限(ブロック)。既定 13。")
                    .defineInRange("caveArchMinSpan", 13, 2, 64);

            caveArchMaxSpan = b
                    .comment("天井の根のアーチ: 水平距離の上限。既定 40。")
                    .defineInRange("caveArchMaxSpan", 40, 2, 64);

            caveArchDip = b
                    .comment("天井の根のアーチ: 中央の垂れ下がり(水平距離に対する割合)。既定 0.35(長さ 20 で 7 マス垂れる)。")
                    .defineInRange("caveArchDip", 0.35D, 0.0D, 2.0D);

            caveArchMinThickness = b
                    .comment("天井の根のアーチ: 中央の太さ(塊の半径。0 = 1 マス、1 ≒ 十字断面)。既定 1。")
                    .defineInRange("caveArchMinThickness", 1, 0, 4);

            caveArchMaxThickness = b
                    .comment("天井の根のアーチ: 両端(天井付近)の太さ。既定 1。")
                    .defineInRange("caveArchMaxThickness", 1, 0, 4);

            caveVineArchShare = b
                    .comment("天井のアーチのうち、根(giant_overgrown_log)ではなく葉に包まれた巨大な蔦",
                            "(overgrown_wood + overgrown_leaves、湿地系では overgrown_swamp_* )になる割合。",
                            "0 で常に根のアーチ、1 で常に蔦のアーチ。密林・湿地系どちらの洞窟でも同じ割合。既定 0.35。")
                    .defineInRange("caveVineArchShare", 0.35D, 0.0D, 1.0D);

            caveVineArchDip = b
                    .comment("蔦のアーチ: 中央の垂れ下がり(水平距離に対する割合)。蔦なので根(caveArchDip)より深く垂れる。既定 0.6。")
                    .defineInRange("caveVineArchDip", 0.6D, 0.0D, 2.0D);

            caveVineArchLeafRadius = b
                    .comment("蔦のアーチ: 蔓の周りを包む葉の厚み(蔓の半径に足す分)。0 で葉なし(裸の蔓)。既定 1。")
                    .defineInRange("caveVineArchLeafRadius", 1, 0, 3);

            caveVineArchLeafChance = b
                    .comment("蔦のアーチ: 葉の層の各マスが実際に葉になる確率。1 未満にすると房状にまばらになる。既定 0.65。")
                    .defineInRange("caveVineArchLeafChance", 0.65D, 0.0D, 1.0D);

            cavePillarMinHeight = b
                    .comment("菌の柱: 立てるのに必要な最低の天井の高さ。柱は必ず天井と床を継ぐので、",
                            "天井がこれより低い場所には立てない(高さの上限は無い)。既定 10。")
                    .defineInRange("cavePillarMinHeight", 10, 1, 64);

            cavePillarBaseRadius = b
                    .comment("菌の柱: 根元の半径(0 = 1 マス、1 = 十字 3x3、2 = 5x5 の円)。既定 2.0。")
                    .defineInRange("cavePillarBaseRadius", 2.0D, 0.0D, 6.0D);

            cavePillarTopRadius = b
                    .comment("菌の柱: 先端の半径(根元以下)。既定 0.5。")
                    .defineInRange("cavePillarTopRadius", 0.5D, 0.0D, 6.0D);

            cavePillarFlareRows = b
                    .comment("菌の柱: 裾を広げる段数(根元と天井側の両方を同じ段数広げる)。既定 2。")
                    .defineInRange("cavePillarFlareRows", 2, 0, 8);

            cavePillarFlare = b
                    .comment("菌の柱: 裾の追加半径。既定 1.2。")
                    .defineInRange("cavePillarFlare", 1.2D, 0.0D, 6.0D);

            cavePillarGlowChance = b
                    .comment("菌の柱: 各ブロックが発光菌糸(glowing_mycelium)になる割合。既定 0.06。")
                    .defineInRange("cavePillarGlowChance", 0.06D, 0.0D, 1.0D);

            convertToOvergrowthBiome = b
                    .comment("地表の汚染割合が overgrownBiomeThreshold に届く前でも、胞子濃度が 100% に達した",
                            "チャンクのバイオームを overgrown_vegetation:overgrowth(繁茂バイオーム)へ書き換えるか。",
                            "繁茂バイオームは霧・Mob 湧き・胞子濃度・AI・地表浸食などの挙動がすべて過繁茂バイオームと",
                            "同じで、実際の汚染が overgrownBiomeThreshold まで進むと過繁茂バイオームへ昇格する。",
                            "ブロックの汚染自体はしない(有機的なフロンティア浸食に任せる)ので、地表が矩形に",
                            "塗り潰されることはない。convertToOvergrownBiome が false のときは無効。")
                    .define("convertToOvergrowthBiome", true);

            overgrowthBiomeScanIntervalTicks = b
                    .comment("繁茂バイオーム化の判定間隔(tick)。既定 100 = 5 秒。")
                    .defineInRange("overgrowthBiomeScanIntervalTicks", 100, 20, 24000);

            overgrowthBiomeScanRadiusChunks = b
                    .comment("繁茂バイオーム化の判定対象にする、各プレイヤーからのチャンク半径。既定 6。")
                    .defineInRange("overgrowthBiomeScanRadiusChunks", 6, 1, 32);

            overgrowthBiomeMaxConvertsPerScan = b
                    .comment("繁茂バイオーム化の判定 1 回あたり、実際に書き換えを試みるチャンク数の上限。既定 4。")
                    .defineInRange("overgrowthBiomeMaxConvertsPerScan", 4, 1, 64);

            overgrowthBiomeRescanTicks = b
                    .comment("繁茂 / 過繁茂バイオームになったチャンクを次に再判定するまでの待ち時間(tick)。既定 6000 = 5 分。")
                    .defineInRange("overgrowthBiomeRescanTicks", 6000, 20, 2_400_000);

            overgrowthBiomeMissRescanTicks = b
                    .comment("まだ繁茂系バイオームにならなかったチャンクを次に再判定するまでの待ち時間(tick)。既定 600 = 30 秒。")
                    .defineInRange("overgrowthBiomeMissRescanTicks", 600, 20, 2_400_000);

            overgrownBiomeCreep = b
                    .comment("過繁茂バイオームの中に取り残された未汚染ブロックを、汚染シダが近くにいなくても",
                            "時間経過で汚染していくか。バイオームの中はプレイヤー周辺のチャンクを 1 つずつ",
                            "地表総ざらいして取りこぼしを塗り潰す。さらに過繁茂バイオームの縁から",
                            "overgrownBiomeCreepMargin ブロックぶんはバイオームの外(チャンク境界の向こう)へも",
                            "ランダムに汚染をにじませる → チャンク境界で汚染がまっすぐ途切れず外へにじむ。")
                    .define("overgrownBiomeCreep", true);

            overgrownBiomeCreepIntervalTicks = b
                    .comment("上記の判定間隔(tick)。既定 60 = 3 秒。")
                    .defineInRange("overgrownBiomeCreepIntervalTicks", 60, 20, 24000);

            overgrownBiomeCreepAttempts = b
                    .comment("バイオームの縁のにじみ処理で、判定 1 回あたり各プレイヤーの周囲で汚染を試みる回数",
                            "(多くは既に汚染済み等で空振りする)。バイオームの内側の塗り潰しはこの値ではなく",
                            "overgrownBiomeCreepChunksPerTick 側で制御する。既定 10。")
                    .defineInRange("overgrownBiomeCreepAttempts", 10, 0, 1000);

            overgrownBiomeCreepRadius = b
                    .comment("過繁茂バイオームの自己汚染が対象にする、各プレイヤーからの水平半径(ブロック)。既定 64。")
                    .defineInRange("overgrownBiomeCreepRadius", 64, 8, 256);

            overgrownBiomeCreepMargin = b
                    .comment("過繁茂バイオームの縁から何ブロックぶんまで、バイオームの外へ汚染をはみ出させるか。",
                            "0 でバイオームの中だけ(はみ出しなし)。既定 6。")
                    .defineInRange("overgrownBiomeCreepMargin", 6, 0, 32);

            overgrownBiomeCreepChunksPerTick = b
                    .comment("バイオーム内のフロンティア浸食で、判定 1 回につき処理するチャンク数の上限。",
                            "過繁茂バイオームになったばかりの広い範囲でも、これを超えて一度に処理しない。",
                            "0 で内部のフロンティア浸食を無効化(縁のにじみだけ残る)。既定 8。")
                    .defineInRange("overgrownBiomeCreepChunksPerTick", 8, 0, 256);

            overgrownBiomeCreepMaxConversionsPerChunk = b
                    .comment("フロンティア浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。",
                            "残りのフロンティアは次の処理で続きが汚染される。既定 64。")
                    .defineInRange("overgrownBiomeCreepMaxConversionsPerChunk", 64, 1, 256);

            overgrownBiomeCreepSurfaceDepth = b
                    .comment("フロンティア浸食で、各列の最上部から何ブロック下まで汚染/未汚染ブロックを探すか。",
                            "汚染された下草・茂みの下に残った通常の地面ブロックを拾うために 1 より大きくする。既定 4。")
                    .defineInRange("overgrownBiomeCreepSurfaceDepth", 4, 1, 16);

            overgrownBiomeCreepFrontierBaseChance = b
                    .comment("バイオーム内の取りこぼし掃除は、既に汚染されたブロックに隣接する未汚染ブロックだけを",
                            "外側へ 1 マスずつ汚染する(有機的に広がり、チャンク単位の矩形にならない)。",
                            "汚染源となる隣接ブロックがちょうど 1 つの列を、1 回の走査で汚染する確率。",
                            "低いほど汚染の縁がゆっくり・不規則に進む。既定 0.35。")
                    .defineInRange("overgrownBiomeCreepFrontierBaseChance", 0.35D, 0.0D, 1.0D);

            overgrownBiomeCreepFrontierChancePerNeighbor = b
                    .comment("汚染源となる隣接ブロックが 1 つ増えるごとに上記の確率へ加算する値。",
                            "既定 0.22(隣接 2 つで 0.57、3 つで 0.79、4 つ+自列で確定)。",
                            "汚染に囲まれた取りこぼしほど早く潰れ、平らな縁ほどゆっくり不規則に進む。")
                    .defineInRange("overgrownBiomeCreepFrontierChancePerNeighbor", 0.22D, 0.0D, 1.0D);

            overgrownBiomeCreepRescanTicks = b
                    .comment("フロンティア浸食で実際に汚染が起きたチャンクを、次にまた処理するまでの間隔(tick)。",
                            "既定 400 = 20 秒(まだフロンティアが残っている可能性が高いので短め)。")
                    .defineInRange("overgrownBiomeCreepRescanTicks", 400, 20, 24000);

            overgrownBiomeCreepCleanRescanTicks = b
                    .comment("フロンティア浸食で汚染すべきものが無かった(掃除済みの)チャンクを、次に再処理するまでの間隔(tick)。",
                            "既定 12000 = 10 分(建築物の設置などで新たな未汚染ブロックが増えた場合の拾い直し用)。")
                    .defineInRange("overgrownBiomeCreepCleanRescanTicks", 12000, 20, 720000);

            overgrownBiomeCreepDeepCorruption = b
                    .comment("過繁茂バイオームのチャンクを、時間経過で地表から下へ 1 層ずつ深部浸食し、最終的に",
                            "チャンク内の全固体ブロック(地表より上の建築物・浮遊ブロック、地下深部の露出して",
                            "いない石・鉱石まで)を汚染しきるか。地表のフロンティア浸食が済んだ列から順に、",
                            "汚染が真下へ降りていく。地下の一括変換なので this MOD の累計汚染ブロック数には数えない",
                            "(表示用の統計。フェーズ昇格条件は過繫茂チャンク数なので影響しない)。false で深部浸食を無効化。")
                    .define("overgrownBiomeCreepDeepCorruption", true);

            overgrownBiomeCreepDeepChunksPerTick = b
                    .comment("深部浸食で、判定 1 回につき処理するチャンク数の上限。0 で深部浸食を無効化。既定 8。")
                    .defineInRange("overgrownBiomeCreepDeepChunksPerTick", 8, 0, 256);

            overgrownBiomeCreepDeepBlocksPerChunk = b
                    .comment("深部浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。",
                            "残りは次回以降の処理で続きが汚染される。既定 128。")
                    .defineInRange("overgrownBiomeCreepDeepBlocksPerChunk", 128, 1, 4096);

            overgrownBiomeCreepDeepLayersPerVisit = b
                    .comment("深部浸食で、1 チャンクを 1 回処理するときに走査する Y 層数の上限。",
                            "再起動でチャンクごとの進捗が失われた後、汚染済みの上層を舐め直すコストの上限になる。既定 24。")
                    .defineInRange("overgrownBiomeCreepDeepLayersPerVisit", 24, 1, 384);

            overgrownBiomeCreepDeepChance = b
                    .comment("深部浸食で、条件を満たした 1 ブロックを 1 回の走査で汚染する確率。",
                            "1.0 で層ごとに一括で降りる(地下なので平面的でも見えない)。",
                            "下げるほど汚染の下端が不規則になるが、その層がクリーンになるまで次の層へ進まない。既定 1.0。")
                    .defineInRange("overgrownBiomeCreepDeepChance", 1.0D, 0.0D, 1.0D);

            overgrownBiomeCreepDeepRescanTicks = b
                    .comment("深部浸食で、あるチャンクを処理してから次に処理するまでの間隔(tick)。既定 100 = 5 秒。")
                    .defineInRange("overgrownBiomeCreepDeepRescanTicks", 100, 20, 720000);

            buildCorrosion = b
                    .comment("建築浸食: 過繁茂バイオームの中に後から置かれたブロック(プレイヤーの建築物、ピストンで動いた",
                            "ブロックなど)を、既存の汚染に面したものから少しずつ汚染していくか。地面と接した土台から",
                            "壁を這い上がり、屋根・内装へ広がる。チェストなどのブロックエンティティは corruptBlockEntities に従う。",
                            "false で無効化(その場合、建物の屋根と最上段以外は汚染されないまま残る)。")
                    .define("buildCorrosion", true);

            buildCorrosionIntervalTicks = b
                    .comment("建築浸食の判定間隔(tick)。候補が残っているチャンクはこの間隔で続きを処理する。既定 100 = 5 秒。")
                    .defineInRange("buildCorrosionIntervalTicks", 100, 20, 24000);

            buildCorrosionChunksPerPass = b
                    .comment("建築浸食で、判定 1 回につき処理するチャンク数の上限。0 で無効化。既定 4。")
                    .defineInRange("buildCorrosionChunksPerPass", 4, 0, 256);

            buildCorrosionBlocksPerChunk = b
                    .comment("建築浸食で、1 チャンクを 1 回処理するときに汚染するブロック数の上限。既定 6。",
                            "既定の間隔(5 秒)なら 1 チャンクあたり最大で毎分 72 ブロックほど。")
                    .defineInRange("buildCorrosionBlocksPerChunk", 6, 1, 1024);

            buildCorrosionBaseChance = b
                    .comment("建築浸食で、汚染に 1 面だけ接しているブロックを 1 回の判定で汚染する確率。既定 0.2。")
                    .defineInRange("buildCorrosionBaseChance", 0.2D, 0.0D, 1.0D);

            buildCorrosionChancePerNeighbor = b
                    .comment("建築浸食で、汚染に接する面が 1 つ増えるごとに buildCorrosionBaseChance へ加算する確率。",
                            "汚染に囲まれたブロックほど早く落ちる。既定 0.2。")
                    .defineInRange("buildCorrosionChancePerNeighbor", 0.2D, 0.0D, 1.0D);

            buildCorrosionSeedChance = b
                    .comment("建築浸食で、汚染にまったく接していない建築(空中の足場など)に「種」を 1 つ植える確率",
                            "(チャンク 1 回の処理あたり)。0 で汚染に接した建築だけを対象にする。既定 0.03。")
                    .defineInRange("buildCorrosionSeedChance", 0.03D, 0.0D, 1.0D);

            buildCorrosionBuriedNeighbors = b
                    .comment("建築浸食で、空気に面していない(完全に埋まった)ブロックを候補にするのに必要な、汚染済み隣接面の数。",
                            "二重壁の内側などは周囲が汚染されきってから落ちる。深部浸食がまだ届いていない地下の石は",
                            "上の 1 面しか汚染に接していないので、この値が 2 以上なら建築浸食は触らない。既定 3。")
                    .defineInRange("buildCorrosionBuriedNeighbors", 3, 1, 6);

            buildCorrosionIdleRescanTicks = b
                    .comment("建築浸食で、候補が無かったチャンクを次に再走査するまでの間隔(tick)。",
                            "設置イベントで拾えないブロック(ピストン、他 MOD、再起動前に置いたもの)の拾い直し用。既定 6000 = 5 分。")
                    .defineInRange("buildCorrosionIdleRescanTicks", 6000, 20, 720000);

            buildCorrosionInOvergrowthStage = b
                    .comment("建築浸食を「繁茂」段階(過繁茂になる前)のバイオームでも走らせるか。既定 false = 過繁茂のみ。")
                    .define("buildCorrosionInOvergrowthStage", false);

            spawnConnectingVines = b
                    .comment("木が生えたとき、近くに別の木があれば幹の間を蔦(リアナ)で繋ぐか。",
                            "密林の木々が蔦で連結されているような見た目になる(ジャングルのツル植物のイメージ)。")
                    .define("spawnConnectingVines", true);

            vinePerTreeChance = b
                    .comment("木が1本生えたとき、そもそも連結蔦の生成処理を試みる確率。",
                            "これに外れた木からは蔦が一切生えない(vineMinPerTree の下限保証より前に判定する)。",
                            "全体の蔦の量がおよそこの確率倍に絞られる(既定 0.12 = 木の約12%だけが蔦を生成)。",
                            "1.0 にすると従来どおり全ての木で生成を試みる。")
                    .defineInRange("vinePerTreeChance", 0.12D, 0.0D, 1.0D);

            vineChance = b
                    .comment("繋げる相手の木が見つかったとき、実際に蔦を張る確率。",
                            "本数の上限(vineMinPerTree〜vineMaxPerTreeからランダムに決まる)に達するまで、",
                            "候補の木ごとにこの確率を毎回抽選する。")
                    .defineInRange("vineChance", 0.08D, 0.0D, 1.0D);

            vineExistingVineTargetChance = b
                    .comment("蔦の終点候補が見つかるたびに、木の原木の代わりに近くの既存の蔦",
                            "(別の主アンカー)の曲線上のランダムな1点を終点候補として差し替える確率。",
                            "これにより、蔦が木同士だけでなく既存の蔦とも繋がるようになる。",
                            "0 にすると従来通り木同士のみを繋ぐ。")
                    .defineInRange("vineExistingVineTargetChance", 0.25D, 0.0D, 1.0D);

            vineExistingVineTargetEndMargin = b
                    .comment("既存の蔦の曲線上に接続点を取る際、蔦の始点・終点それぞれから",
                            "この割合ぶんの区間を接続点の候補から除外する。",
                            "既定 0.25 なら曲線の中央50%(割合0.25〜0.75)だけが接続点になり、",
                            "蔦の端(木の幹の近くやぶら下がり蔦の付け根)に新しい蔦が集中しなくなる。",
                            "0.0 で従来どおり(端すれすれを除く曲線全体、割合0.15〜0.85)。")
                    .defineInRange("vineExistingVineTargetEndMargin", 0.25D, 0.0D, 0.5D);

            vineMinLength = b
                    .comment("蔦の最小の長さ(始点〜終点の直線距離、ブロック数)。",
                            "これより近い(隣接しているような)木同士は蔦で繋がらない。")
                    .defineInRange("vineMinLength", 3, 0, 64);

            vineMaxLength = b
                    .comment("蔦の最大の長さ(始点〜終点の直線距離、ブロック数)。",
                            "これより離れた木同士は蔦で繋がらない。",
                            "vineStackLengthThreshold を超える長さの場合は、複数本重ねて生成される。")
                    .defineInRange("vineMaxLength", 30, 1, 64);

            vinePlaceMaxLength = b
                    .comment("プレイヤーが「蔓」アイテムで手で張れる蔦の最大の長さ",
                            "(始点〜終点の直線距離、ブロック数)。始点を指定したあと、これより遠い場所を",
                            "終点に選ぶと張れない(始点はそのまま残るので選び直せる)。",
                            "自然に生える蔦の vineMaxLength とは別枠。長くしすぎると、片方の端の",
                            "チャンクしか読み込まれていないときに蔦が描かれないことがある。")
                    .defineInRange("vinePlaceMaxLength", 32, 1, 64);

            vineMaxHeightDiff = b
                    .comment("蔦の始点・終点の Y 座標の差の最大値(ブロック数)。",
                            "これより高低差のある組み合わせは蔦で繋がらない(急な斜めの蔦を防ぐ)。")
                    .defineInRange("vineMaxHeightDiff", 5, 0, 64);

            vineMinGroundClearance = b
                    .comment("蔦の始点・終点それぞれの真下を調べ、実際の地面(に類する何か)までの距離が",
                            "この数未満なら、その候補は「地面に近すぎる」として使わない。",
                            "地面すれすれに蔦が張られるのを防ぐための設定。")
                    .defineInRange("vineMinGroundClearance", 4, 0, 32);

            vineMaxGroundClearance = b
                    .comment("蔦の始点・終点それぞれの真下を調べ、実際の地面(に類する何か)までの距離が",
                            "この数を超えるなら、その候補は「地面から高すぎる」として使わない。")
                    .defineInRange("vineMaxGroundClearance", 40, 1, 64);

            vineHeightBandLowMax = b
                    .comment("蔦の生える高さ(地面からの距離)を3段階に分けて偏りを持たせる際の、",
                            "「低」帯の上限。vineMinGroundClearance 〜 この値までが「低」帯。")
                    .defineInRange("vineHeightBandLowMax", 8, 0, 64);

            vineHeightBandMidMax = b
                    .comment("高さ帯「中」の上限。vineHeightBandLowMax の次の値 〜 この値までが「中」帯、",
                            "この値を超え vineMaxGroundClearance までが「高」帯。")
                    .defineInRange("vineHeightBandMidMax", 20, 0, 64);

            vineHeightWeightLow = b
                    .comment("高さ帯「低」に蔦が生える相対的な重み。低・中・高の3つの比で決まる",
                            "(既定では 50 : 30 : 20 = 低が選ばれやすい)。")
                    .defineInRange("vineHeightWeightLow", 50.0D, 0.0D, 1000.0D);

            vineHeightWeightMid = b
                    .comment("高さ帯「中」に蔦が生える相対的な重み。")
                    .defineInRange("vineHeightWeightMid", 30.0D, 0.0D, 1000.0D);

            vineHeightWeightHigh = b
                    .comment("高さ帯「高」に蔦が生える相対的な重み。")
                    .defineInRange("vineHeightWeightHigh", 20.0D, 0.0D, 1000.0D);

            vineStackLengthThreshold = b
                    .comment("始点・終点の直線距離がこの値を超える場合、1本だけでは頼りなく見えるため、",
                            "同じ組み合わせに対して蔦を vineStackMinCount〜vineStackMaxCount 本",
                            "重ねて生成する(垂れ具合は必ず互いに異なる値になる)。")
                    .defineInRange("vineStackLengthThreshold", 20, 1, 64);

            vineStackMinCount = b
                    .comment("長距離接続(vineStackLengthThreshold 超)で重ねて生成する蔦の最小本数。")
                    .defineInRange("vineStackMinCount", 2, 1, 16);

            vineStackMaxCount = b
                    .comment("長距離接続(vineStackLengthThreshold 超)で重ねて生成する蔦の最大本数。",
                            "垂れ具合の段階数(SAG_TIERS、既定6)を超えて重複しない値を割り当てられ",
                            "ないため、この値を超えて指定しても実際にはSAG_TIERS本までになる。")
                    .defineInRange("vineStackMaxCount", 6, 1, 16);

            vineStackCountWeight2 = b
                    .comment("重ねる本数が vineStackMinCount(既定2本)になる相対的な重み。",
                            "vineStackCountWeight3〜6 と合わせて 2本:3本:4本:5本:6本 の比で",
                            "本数を抽選する(既定 80:15:5:2:1 = 本数が多いほど生成されにくい)。")
                    .defineInRange("vineStackCountWeight2", 80.0D, 0.0D, 1000.0D);

            vineStackCountWeight3 = b
                    .comment("重ねる本数が3本になる相対的な重み。")
                    .defineInRange("vineStackCountWeight3", 15.0D, 0.0D, 1000.0D);

            vineStackCountWeight4 = b
                    .comment("重ねる本数が4本になる相対的な重み。")
                    .defineInRange("vineStackCountWeight4", 5.0D, 0.0D, 1000.0D);

            vineStackCountWeight5 = b
                    .comment("重ねる本数が5本になる相対的な重み。")
                    .defineInRange("vineStackCountWeight5", 2.0D, 0.0D, 1000.0D);

            vineStackCountWeight6 = b
                    .comment("重ねる本数が6本(以上)になる相対的な重み。",
                            "vineStackMaxCount を6より大きくした場合、7本目以降もこの重みを使い回す。")
                    .defineInRange("vineStackCountWeight6", 1.0D, 0.0D, 1000.0D);

            vineNestedCrossChance = b
                    .comment("重複蔦(ぶら下がり蔦)を生成する際、両端を1つの親の曲線上に乗せる代わりに、",
                            "既に生成済みの別の重複蔦(または主線)をまたぐように、両端をそれぞれ",
                            "別の曲線上から選んで生成する確率。同じ組み合わせに対して重複蔦が",
                            "2本以上生成済みの場合にのみ判定される(1本目は必ず通常通り親の曲線上に生成される)。",
                            "「またぐ」に該当しなかった場合は vineSecondTierShareStartWeight 等の4択で決まる。")
                    .defineInRange("vineNestedCrossChance", 0.3D, 0.0D, 1.0D);

            vineSecondTierShareStartWeight = b
                    .comment("重複蔦のうち、始点を第一の蔦(主線)の実際の始点と共有する",
                            "「第二の蔦」になる相対的な重み。vineSecondTierShareEndWeight/",
                            "vineSecondTierFloatingWeight/vineThirdTierWeight と合わせて",
                            "4択の比率を決める(既定 15:15:60:10)。")
                    .defineInRange("vineSecondTierShareStartWeight", 15.0D, 0.0D, 1000.0D);

            vineSecondTierShareEndWeight = b
                    .comment("終点を第一の蔦の実際の終点と共有する「第二の蔦」になる相対的な重み。")
                    .defineInRange("vineSecondTierShareEndWeight", 15.0D, 0.0D, 1000.0D);

            vineSecondTierFloatingWeight = b
                    .comment("始点・終点のどちらも第一の蔦の曲線上の点になる「第二の蔦」になる",
                            "相対的な重み(従来からの既定の垂れ下がり方)。")
                    .defineInRange("vineSecondTierFloatingWeight", 60.0D, 0.0D, 1000.0D);

            vineThirdTierWeight = b
                    .comment("第一の蔦(主線)からではなく、既に配置済みの「第二の蔦」の曲線上から",
                            "生える「第三の蔦」になる相対的な重み。vineThirdTierMinDuplicateCount",
                            "未満、またはまだ第二の蔦が1本も無い場合はこの選択肢自体が候補から除外される",
                            "(残り3つの重みだけで抽選)。第三の蔦からさらに孫が生えることはない。")
                    .defineInRange("vineThirdTierWeight", 10.0D, 0.0D, 1000.0D);

            vineThirdTierMinDuplicateCount = b
                    .comment("第三の蔦が生成候補になるために必要な、この組み合わせにおける重複蔦の",
                            "総本数(主線を除く)の最小値。既定4は「重複蔦が3本以下では第三の蔦は",
                            "生成されない」ことを意味する。vineStackMaxCount が既定の6(=重複蔦最大5本)",
                            "であれば、5本重ねられた束(重複蔦4本)以上でこの条件を満たしうる。")
                    .defineInRange("vineThirdTierMinDuplicateCount", 4, 1, 16);

            vineMinPerTree = b
                    .comment("木が1本生えるたびに実際に張る蔦の本数の下限。",
                            "vineMaxPerTree との間から毎回ランダムに本数が決まる(常に同じ本数にならない)。",
                            "vineMaxPerTree より大きい値を指定した場合は vineMaxPerTree と同じ扱いになる。")
                    .defineInRange("vineMinPerTree", 1, 0, 16);

            vineMaxPerTree = b
                    .comment("木が1本生えるたびに実際に張る蔦の本数の上限。",
                            "vineMinPerTree との間から毎回ランダムに本数が決まる。",
                            "0 にすると spawnConnectingVines を false にしたのと同じく蔦が一切生えなくなる。")
                    .defineInRange("vineMaxPerTree", 6, 0, 16);

            vineSagMin = b
                    .comment("蔦の垂れ下がりの「一番少ない段階(段階1)」の量(ブロック数)。")
                    .defineInRange("vineSagMin", 1, 0, 64);

            vineSagMax = b
                    .comment("蔦の垂れ下がりの「一番多い段階(最終段階)」の量(ブロック数)。",
                            "実際の垂れ具合は蔦ごとに、vineSagMin 〜 vineSagMax の範囲を均等分割した",
                            "段階数(SAG_TIERS、既定6)のどれかがランダムに選ばれる。")
                    .defineInRange("vineSagMax", 3, 0, 64);

            vineDroopLightChance = b
                    .comment("蔦の経路上に垂れ蔦(droop)が生成されるたびに、通常の見た目の代わりに",
                            "発光する見た目(overgrown_liana_droop_light)になる確率。",
                            "発光する垂れ蔦は見た目だけでなく実際に vineDroopLightLevel の明るさの",
                            "光源として機能する(見えない光源ブロックを裏側に置く)。")
                    .defineInRange("vineDroopLightChance", 0.3D, 0.0D, 1.0D);

            vineDroopLightLevel = b
                    .comment("発光する垂れ蔦(overgrown_liana_droop_light)が放つ光レベル。",
                            "バニラの光源ブロックと同じ0〜15の尺度(15が最も明るい)。")
                    .defineInRange("vineDroopLightLevel", 8, 0, 15);

            vineFruitRipenIntervalTicks = b
                    .comment("蔦から鉛直方向に垂れる蔦(droop)のうち発光していないものを、時間経過で",
                            "発光(=熟した)状態へ変える判定を、蔦1本につき何 tick ごとに行うか",
                            "(20 tick = 1 秒)。判定のたびに vineFruitRipenChance で1本だけ発光させる。",
                            "発光した垂れ蔦は素手の右クリックで「蔦の実」を1個収穫でき、収穫すると",
                            "発光しない状態へ戻る(この繰り返し)。")
                    .defineInRange("vineFruitRipenIntervalTicks", 1200, 20, 720000);

            vineFruitRipenChance = b
                    .comment("vineFruitRipenIntervalTicks ごとの判定で、発光していない垂れ蔦を1本",
                            "発光(=熟した)状態にする確率。0.0 にすると垂れ蔦は生成時の状態から",
                            "変化しなくなる(時間経過で実がならなくなる)。")
                    .defineInRange("vineFruitRipenChance", 0.35D, 0.0D, 1.0D);

            b.pop();

            b.comment("OvergrownVegetation — 胞子感染の設定").push("infection");

            // 「胞子感染」デバフの数値(閾値・持続時間・レベル2 昇格確率・攻撃力低下・検知範囲ボーナス・
            //  防御力/移動速度低下率・濃度閾値)は config/overgrown_vegetation/effects.json へ移行。
            //  → com.yayuy.overgrownvegetation.data.EffectTable

            parasitizedChaseSpeed = b
                    .comment("過繁茂した(MOB名)が他の Mob/プレイヤーを追跡・攻撃する際の MeleeAttackGoal 指定速度",
                            "(ブロック/tick)。種族ごとの基礎移動速度の違いはここでは吸収されるので、",
                            "全種族がこの同じ値で追いかけてくる。",
                            "既定値 0.3 は「プレイヤーのダッシュ速度(0.13)の0.9倍を体感できる」ことを",
                            "狙った経験的な補正値(経路探索で動く Mob は直接操作のプレイヤーより",
                            "体感速度が落ちるため、単純に 0.13*0.9=0.117 を入れると遅く感じる)。",
                            "体感が合わなければ調整してほしい。")
                    .defineInRange("parasitizedChaseSpeed", 0.3D, 0.0D, 4.0D);

            prioritizeUninfectedCreepers = b
                    .comment("過繁茂した(MOB名)が新規に索敵する際、まだ「胞子感染」にかかっていないクリーパーを",
                            "距離に関係なく最優先で狙うか。既定値 true。",
                            "対象のクリーパーが「胞子感染」にかかると優先度は元に戻り、通常どおり",
                            "「最も近い相手」の一体として扱われる。追跡中のターゲットは見失うまで切り替えない。")
                    .define("prioritizeUninfectedCreepers", true);

            bushConcealment = b
                    .comment("過繁茂した(MOB名)・過繁茂したスカトゥリウムが、茂み(overgrown_bush。",
                            "overgrown_vegetation:conceals_entities タグで追加可能)の中に入っている相手を",
                            "見失うか。既定 true。新規ターゲットとして選ばなくなるだけでなく、",
                            "追跡中の相手が茂みに逃げ込んだ場合も bushConcealmentGraceTicks の猶予後に見失う。")
                    .define("bushConcealment", true);

            bushConcealmentGraceTicks = b
                    .comment("追跡中の相手が茂みに逃げ込んでから、実際に見失うまでの猶予tick。",
                            "既定 20(=1秒)。0 にすると茂みに入った瞬間に見失う。")
                    .defineInRange("bushConcealmentGraceTicks", 20, 0, 200);

            unreachableTargetGraceTicks = b
                    .comment("過繁茂した(MOB名)・過繁茂したスカトゥリウムが、追跡中の標的へたどり着ける経路が",
                            "無い状態のまま連続でこの tick 数を超えたら見失い、別の標的に切り替える。",
                            "既定 60(=3秒)。壁や水路で隔てられた相手をいつまでも壁際で突き進もうとする",
                            "不具合の対策。")
                    .defineInRange("unreachableTargetGraceTicks", 60, 0, 1200);

            infectionFleeMinDistance = b
                    .comment("「胞子感染」中の Mob が汚染ブロックから最低でも確保しようとする距離(ブロック)。",
                            "この距離未満だと FleeCorruptionGoal が非汚染ブロックへ向かって逃げ続ける。")
                    .defineInRange("infectionFleeMinDistance", 5, 0, 64);

            corpseCorruptionRadius = b
                    .comment("汚染死体ブロック(overgrown_corpse)が広げる侵食の半径(ブロック)。",
                            "blight_fern が使う radius とは別の値で、既定では blight_fern より小さい範囲だけ汚染する。")
                    .defineInRange("corpseCorruptionRadius", 10, 1, 128);

            parasitizedCreeperInfectionRadius = b
                    .comment("過繁茂したクリーパーが爆発したとき、周囲の Mob へ「胞子感染1」を付与する半径(ブロック)。",
                            "爆発による地形破壊は起こさない。帯電クリーパーはこの 1.5 倍。")
                    .defineInRange("parasitizedCreeperInfectionRadius", 5.0D, 0.0D, 64.0D);

            parasitizedCreeperCorruptionRadius = b
                    .comment("過繁茂したクリーパーが爆発したとき、その場を汚染ブロックへ変える球のおおよその半径(ブロック)。",
                            "実際の境界は parasitizedCreeperCorruptionNoise 分だけ方向ごとに波打つので綺麗な球にはならない。",
                            "帯電クリーパーはこの 1.5 倍。")
                    .defineInRange("parasitizedCreeperCorruptionRadius", 7, 0, 32);

            parasitizedCreeperCorruptionNoise = b
                    .comment("過繁茂したクリーパーの爆発汚染の境界を方向ごとに波打たせる振幅(ブロック、±)。",
                            "0 にすると parasitizedCreeperCorruptionRadius ちょうどの綺麗な球になる。")
                    .defineInRange("parasitizedCreeperCorruptionNoise", 2.0D, 0.0D, 16.0D);

            invaderCorruptionRadius = b
                    .comment("グループ4(拡大種)がうずくまって胞子をばらまいている間に汚染する範囲の半径(ブロック、水平)。",
                            "汚染が広がるのはうずくまりの最中だけで、歩いているだけでは何も汚さない。")
                    .defineInRange("invaderCorruptionRadius", 4, 0, 32);

            invaderDespawnRange = b
                    .comment("グループ4(拡大種)が消えるまでの距離(ブロック)。この内側にプレイヤーが居る限り消えない。",
                            "バニラは「32ブロックより遠くで30秒何も起きなければ抽選で消す」なので、",
                            "真上を飛ぶヴェロフィルや、時間が経ってから動き出すフィロセンスの索敵が成立しない。",
                            "小さくすると数は抑えられるが、それらの行動もまた発動しなくなる。")
                    .defineInRange("invaderDespawnRange", 128, 32, 512);

            shielderMortarMinRange = b
                    .comment("スカトゥリウムが迫撃砲を撃ち始める最短距離(ブロック)。これより近ければ殴るか突進する。")
                    .defineInRange("shielderMortarMinRange", 8.0D, 1.0D, 64.0D);

            shielderMortarMaxRange = b
                    .comment("迫撃砲の最大射程(ブロック)。実際に弾が届くかは弾道計算で別に判定するので、",
                            "高い場所の相手には表示より短くなることがある。")
                    .defineInRange("shielderMortarMaxRange", 40.0D, 4.0D, 128.0D);

            shielderMortarWindupTicks = b
                    .comment("最初の 1 発までの待ち(tick)。既定 3。",
                            "これは「構え」ではなく、mortar アニメーションの沈み込みが底を打つ 0.125 秒(=2.5 tick)に",
                            "1 発目を合わせるための値。大きくすると、弾が出ないのに反動だけ起きる空撃ちが見える。",
                            "アニメの 0 秒目から数える。アニメが始まるまでの補間(3 tick)はコードで足す。")
                    .defineInRange("shielderMortarWindupTicks", 3, 0, 200);

            shielderMortarShots = b
                    .comment("1 回の斉射で撃つ胞子爆弾の数。弾はヴェロフィルのものと同じ。")
                    .defineInRange("shielderMortarShots", 3, 1, 16);

            shielderMortarShotIntervalTicks = b
                    .comment("斉射の 1 発ごとの間隔(tick)。既定 20 = 1 秒。",
                            "mortar アニメーションの長さ(1.0 秒)と揃えてある。ずらすと反動と発射がだんだん食い違う。")
                    .defineInRange("shielderMortarShotIntervalTicks", 20, 1, 100);

            shielderMortarCooldownTicks = b
                    .comment("撃ち切ってから次の斉射までの間隔(tick)。既定 750 = 37.5 秒。",
                            "ユーザー指定(2026-09-25): それまでの 300(15 秒)の 2.5 倍。機関砲台を狙う斉射も同じ間隔。")
                    .defineInRange("shielderMortarCooldownTicks", 750, 20, 6000);

            shielderMortarScatter = b
                    .comment("着弾点の散らばり(ブロック、±)。0 にすると狙いが正確になりすぎて避けようがなくなる。")
                    .defineInRange("shielderMortarScatter", 1.5D, 0.0D, 16.0D);

            shielderCorruptionRadius = b
                    .comment("スカトゥリウムが座って汚染を広げるときの水平半径(ブロック)。",
                            "体が大きいぶんアルボラの invaderCorruptionRadius より広く取ってある。")
                    .defineInRange("shielderCorruptionRadius", 7, 0, 64);

            invaderPathRange = b
                    .comment("グループ4 の経路探索にだけ使う射程(ブロック)。既定 48。",
                            "バニラは探索範囲も打ち切り距離も FOLLOW_RANGE(アルボラは 32)で決めるので、",
                            "「32 ブロック歩いて届かない回り道」を最初から探さない。",
                            "索敵距離を伸ばすと敵対する範囲まで広がってしまうため、ここだけ別に持つ。",
                            "大きくすると遠回りも見つけるようになるが、1 回の探索が重くなる。")
                    .defineInRange("invaderPathRange", 48.0D, 16.0D, 128.0D);

            invaderPathBudgetMultiplier = b
                    .comment("経路探索で展開してよいノード数の倍率。既定 3.0。",
                            "素の上限は FOLLOW_RANGE × 16(アルボラなら 512)。幅 1.4 の体は 2×2 の枡目を",
                            "占めるので通れる道が減り、洞窟や谷では 512 では足りずに途中で打ち切られる。",
                            "1.0 でバニラと同じ。上げるほど賢くなるが探索が重くなる。")
                    .defineInRange("invaderPathBudgetMultiplier", 3.0D, 1.0D, 10.0D);

            invaderPathFailuresBeforeStuck = b
                    .comment("経路を引けないのがこの回数続いたら「詰まった」扱いにして回り道を探す。既定 3。",
                            "標的を持たない個体(群れの陣形移動・縄張りの外へ出る途中)向けの歯止め。",
                            "詰まり検知は「経路が無い = そもそも歩いていない」とみなして素通りするので、",
                            "これが無いと引き直しを延々と繰り返すだけになる。")
                    .defineInRange("invaderPathFailuresBeforeStuck", 3, 1, 20);

            invaderUnstickRadius = b
                    .comment("詰まったときに回り道の行き先を探す半径(ブロック)。既定 12。",
                            "詰まっている向きから 横 → 斜め後ろ → 真後ろ の順に、この距離だけ離れた点を探す。",
                            "小さいと壁の裏へ回りきれず、大きいと立て直しに時間が掛かる。")
                    .defineInRange("invaderUnstickRadius", 12, 2, 48);

            landMobWaterAvoidance = b
                    .comment("陸のグループ1〜10 の Mob に「水を避ける」を一括で掛けるか。既定 true。",
                            "経路探索で水のマスを使わなくなり(= 自分から入水しない)、",
                            "水に面したマスの通行コストが landMobWaterBorderMalus になる。",
                            "水生種(AquaticOvergrownMob)と、自分で「水は平気」と宣言している種",
                            "(メガラクネなどの水陸両用)は対象外。",
                            "false にすると種ごとのコンストラクタで設定した素の値のままになる",
                            "(グループ1 の過繁茂した◯◯ はバニラ由来なので平気で水に入る)。")
                    .define("landMobWaterAvoidance", true);

            landMobWaterBorderMalus = b
                    .comment("水に面したマスの通行コスト。既定 16(バニラ既定は 8)。",
                            "大きいほど岸から離れた道を選ぶ。通れなくなるわけではないので、",
                            "岬や桟橋のような「そこしか道がない」場所は今までどおり通る。",
                            "0 にすると汀を避けなくなる(水に入らないだけになる)。")
                    .defineInRange("landMobWaterBorderMalus", 16.0D, 0.0D, 64.0D);

            landMobMaxTargetDepth = b
                    .comment("陸生の過繁茂 Mob が、自分よりこれ以上<深く潜っている>相手を狙わない(ブロック)。既定 8。",
                            "見るのは絶対の高さではなく「地表からの深さの差」:",
                            "  相手の深さ - 自分の深さ > この値  →  狙わない",
                            "・崖の上から崖下の地表にいる相手 → 相手の深さは 0 なので狙う(歩いて降りられる)",
                            "・自分が坑道の中(深さ20)で相手も同じ坑道 → 差が小さいので狙う",
                            "・自分が地表で相手が洞窟(深さ20) → 狙わない",
                            "これが無いと、地表に立ったまま洞窟の中の相手を睨み続け、",
                            "アルボラの場合はそこから坑道を掘り始めて地面を掘り続ける",
                            "(ユーザー報告 2026-09-20)。反撃には掛からない。",
                            "0 で無制限(以前の挙動)。")
                    .defineInRange("landMobMaxTargetDepth", 8.0D, 0.0D, 256.0D);

            invaderWaterLeapDelayTicks = b
                    .comment("標的への経路が引けない状態がこれだけ続いたら、水を跳び越えてよい(tick)。既定 10 = 0.5 秒。",
                            "水は基本的に避けるもので、跳ぶのは「どうしても越えないと相手に届かない」ときだけ",
                            "(ユーザー指定 2026-09-19)。陸路が一本でも引ければ跳ばずに回り込む。",
                            "0 にすると経路の有無を見ずに跳ぶ(以前の挙動)。",
                            "※陸路を探す範囲は invaderPathRange(既定 48)なので、",
                            "  それより遠回りしないと渡れない川は「陸路なし」とみなして跳ぶ。")
                    .defineInRange("invaderWaterLeapDelayTicks", 10, 0, 600);

            arboraDigEnabled = b
                    .comment("アルボラが標的への坑道を掘るか。既定 true。",
                            "経路がどうしても引けない相手(岩に囲まれた洞窟の中など)に対して、",
                            "ブロックを抜きながら進む。false にすると今までどおり回り道を探すだけになる。",
                            "※地形が掘られる。プレイヤーの建物のうち<ブロックエンティティ>",
                            "(チェスト・かまど等)は壊さないが、素のブロックは壊す。")
                    .define("arboraDigEnabled", true);

            arboraDigTicksPerBlock = b
                    .comment("坑道を 1 マス抜くのに掛かる時間(tick)。既定 25 = 1.25 秒。",
                            "断面は 2 マス幅 × 2 マス高なので、1 歩進むのに 4 マス = 5 秒かかる。",
                            "小さくすると速く掘る。")
                    .defineInRange("arboraDigTicksPerBlock", 25, 1, 600);

            arboraDigRange = b
                    .comment("この距離までの標的にしか坑道を掘らない(ブロック)。既定 48。",
                            "遠すぎる相手へ掘り始めると、地形が延々と抜かれることになる。")
                    .defineInRange("arboraDigRange", 48.0D, 4.0D, 128.0D);

            arboraDigMaxBlocks = b
                    .comment("1 本の坑道で抜いてよいマスの上限。既定 512(断面 4 マスなので約 128 歩)。",
                            "使い切ったらその坑道は打ち切る。")
                    .defineInRange("arboraDigMaxBlocks", 512, 8, 8192);

            arboraDigCorruptRadius = b
                    .comment("抜いたマスのまわりを汚染する半径(ブロック)。既定 2。",
                            "掘り進むそばから坑道の壁が過繁茂した岩肌になる(ユーザー指定)。",
                            "0 で汚染しない。稼働中の胞子除去装置の圏内では汚染できないので素の穴が残る。",
                            "大きくすると 1 マス抜くごとの処理が増える(半径 2 で約 33 マスを見る)。")
                    .defineInRange("arboraDigCorruptRadius", 2, 0, 4);

            arboraDigMaxTargetRise = b
                    .comment("相手がこれ以上<高いところ>に居るあいだは掘らない(ブロック)。既定 2.0。",
                            "壁の上や段の上にいる相手はブロックを壊さず登って襲う(ユーザー指定)。",
                            "ただし同じ相手で詰まりを繰り返したあとは、上へも掘るようになる",
                            "(岩に囲まれた高い空洞には登るだけでは永久に届かないため)。")
                    .defineInRange("arboraDigMaxTargetRise", 2.0D, 0.0D, 64.0D);

            arboraDigSpiralRadius = b
                    .comment("螺旋階段の半径(ブロック)。既定 2。",
                            "真下・真上の相手へは直下掘りをせず、この半径の輪を 45 度ずつ回りながら",
                            "1 段ずつ上下する(ユーザー指定 2026-09-17)。1 周 8 段。",
                            "1 にすると急で狭い螺旋、3 以上にすると緩やかで広い螺旋になる。")
                    .defineInRange("arboraDigSpiralRadius", 2, 1, 6);

            arboraDigSpiralThreshold = b
                    .comment("水平距離がこれ未満で高低差が 3 を超えるとき、螺旋階段を掘る(ブロック)。既定 6.0。",
                            "これより離れていれば、斜めに進む普通の坑道で高さを詰める。")
                    .defineInRange("arboraDigSpiralThreshold", 6.0D, 0.0D, 32.0D);

            arboraDigGiveUpTicks = b
                    .comment("掘れないもの(岩盤・水・溶岩・チェスト等)に当たったとき、",
                            "その相手を諦めている時間(tick)。既定 600 = 30 秒。",
                            "諦めないと同じ岩盤を延々と叩き続けることになる。")
                    .defineInRange("arboraDigGiveUpTicks", 600, 20, 12000);

            arboraDigMaxDescent = b
                    .comment("坑道を掘り始めた高さから、これより下へは掘らない(ブロック)。既定 16。",
                            "索敵の landMobMaxTargetDepth と掘削の判定で「深く潜っている相手は狙わない」を",
                            "見ているが、掘っている最中に相手が更に下へ降りると 1 歩ずつ条件を満たしたまま",
                            "いくらでも潜れてしまう。ここで落差を切っておけば青天井にはならない。",
                            "0 で無制限(以前の挙動)。")
                    .defineInRange("arboraDigMaxDescent", 16, 0, 256);

            arboraClimbPathMalus = b
                    .comment("経路探索で壁を 1 マス登る/降りる辺に乗せる重み。既定 2.0。",
                            "アルボラは壁を登れるので、経路探索にも縦の辺を持たせてある",
                            "(ClimbNodeEvaluator。ユーザー要望 2026-09-20)。A* は距離 + 重みで選ぶので、",
                            "平地を回り込めばすぐ着く場合は今までどおり歩く。壁を登るのは本当に近いときだけ。",
                            "0 にすると歩くのと同じ扱いになって何でも登る。",
                            "負の値にすると縦の辺を作らない(= 2026-09-20 より前の、登れない経路探索に戻る)。")
                    .defineInRange("arboraClimbPathMalus", 2.0D, -1.0D, 32.0D);

            invaderSporeCorruptionIntervalTicks = b
                    .comment("うずくまっている間、何 tick に1マス汚染するか(既定 5 = 1秒に4マス)。",
                            "うずくまりは時間では終わらず、範囲を汚し尽くすか襲う相手を見つけるまで続く。")
                    .defineInRange("invaderSporeCorruptionIntervalTicks", 5, 1, 200);

            invaderSporeIntervalTicks = b
                    .comment("グループ4 が次にうずくまれるようになるまでの間隔(tick)。実際は ±25% ばらつく。",
                            "既定 1200 = 1 分に 1 回。")
                    .defineInRange("invaderSporeIntervalTicks", 1200, 20, 72000);

            invaderSporeInfectRadius = b
                    .comment("うずくまっている間に「胞子感染1」を付ける半径(ブロック)。",
                            "プレイヤーとグループ1〜10 の Mob は対象外。")
                    .defineInRange("invaderSporeInfectRadius", 5.0D, 0.0D, 32.0D);

            invaderFernSpawnChance = b
                    .comment("うずくまり 1 回あたり、終わりぎわにその場へ汚染シダが芽吹く確率(0.0〜1.0)。",
                            "これが「拡大種が汚染シダを増やす」経路。芽吹いたシダが本格的な侵食を始める。")
                    .defineInRange("invaderFernSpawnChance", 0.15D, 0.0D, 1.0D);

            invaderMobSpawnChance = b
                    .comment("うずくまり 1 回あたり、終わりぎわにその場へ過繁茂 Mob が 1 体増える確率(0.0〜1.0)。",
                            "これが「拡大種が過繁茂 Mob を増やす」経路。増えるのは今のフェーズで過繁茂バイオームに",
                            "湧ける種のうち、陸生でグループ4 以外のもの(自己増殖はしない)。")
                    .defineInRange("invaderMobSpawnChance", 0.06D, 0.0D, 1.0D);

            invaderMobSpawnNearbyCap = b
                    .comment("周囲 24 ブロックにグループ1〜10 の Mob がこの数以上いるときは、上の増殖を行わない。")
                    .defineInRange("invaderMobSpawnNearbyCap", 8, 0, 256);

            invaderParryWindowTicks = b
                    .comment("グループ4 のとびかかりを盾で弾ける猶予(tick)。盾を構え始めてからこの tick 以内に",
                            "当たったときだけ弾ける。既定 10 = 0.5 秒。",
                            "構えっぱなしでは弾けない(ダメージはバニラの盾ガードで普通に防げる)。",
                            "大きくすると易しく、0 にすると「構えた瞬間ちょうど」でしか弾けなくなる。")
                    .defineInRange("invaderParryWindowTicks", 10, 0, 200);

            phyllosensSightRange = b
                    .comment("フィロセンスのふだんの索敵距離(ブロック)。mobs.json の followRange と揃えておく。",
                            "ビームを撃つ相手を見つける距離。印(視線/誘導)の配布とは無関係。")
                    .defineInRange("phyllosensSightRange", 56.0D, 1.0D, 512.0D);

            phyllosensAuraRange = b
                    .comment("ふだん「視線」「誘導」を配る半径(ブロック)。既定 32。",
                            "この距離のプレイヤーには phyllosens_watch が、味方には phyllosens_guidance が付き続ける。",
                            "索敵に入る判定(この距離にプレイヤーが居ないこと)もこの半径で見る。")
                    .defineInRange("phyllosensAuraRange", 32.0D, 1.0D, 256.0D);

            phyllosensAuraIntervalTicks = b
                    .comment("ふだんの印を配り直す間隔(tick)。既定 20 = 1 秒。")
                    .defineInRange("phyllosensAuraIntervalTicks", 20, 1, 200);

            phyllosensQuietTicks = b
                    .comment("phyllosensAuraRange にプレイヤーがこの時間居なければ索敵に入る(tick)。既定 1200 = 1 分。",
                            "見るのはプレイヤーだけ。牛やコウモリは数に入らない。")
                    .defineInRange("phyllosensQuietTicks", 1200, 20, 24000);

            phyllosensScoutWatchRange = b
                    .comment("索敵中に「視線」を配る半径(ブロック)。既定 512。",
                            "プレイヤーの走査だけなので広くても安い。")
                    .defineInRange("phyllosensScoutWatchRange", 512.0D, 1.0D, 2048.0D);

            phyllosensScoutGuidanceRange = b
                    .comment("索敵中に「誘導」を配る半径(ブロック)。既定 256。",
                            "こちらは全エンティティの走査になるので、広げるほど重くなる(読み込み済みチャンク内のみ)。")
                    .defineInRange("phyllosensScoutGuidanceRange", 256.0D, 1.0D, 1024.0D);

            phyllosensScanIntervalTicks = b
                    .comment("索敵中に印を配り直す間隔(tick)。範囲が広いので毎 tick はやらない。",
                            "小さくすると反応が早くなるがサーバー負荷が上がる。")
                    .defineInRange("phyllosensScanIntervalTicks", 40, 5, 600);

            phyllosensScoutDurationTicks = b
                    .comment("1 回の索敵を続ける長さ(tick)。既定 600 = 30 秒。",
                            "途中で終わるのは殴られたときと、32 ブロックにプレイヤーが入ってきたときだけ。")
                    .defineInRange("phyllosensScoutDurationTicks", 600, 20, 24000);

            phyllosensCorruptionRadius = b
                    .comment("索敵中に汚染を広げる水平半径(ブロック)。アルボラの invaderCorruptionRadius より広い。")
                    .defineInRange("phyllosensCorruptionRadius", 10, 0, 64);



            phyllosensRallyMinAllies = b
                    .comment("視線持ちのプレイヤーへ向かうとき、周りにこの人数の味方が集まるまで待ってから向かう。",
                            "0 にすると待たずに単独で突っ込む。周りに味方が 1 体も居ないときは待たない。")
                    .defineInRange("phyllosensRallyMinAllies", 3, 0, 32);

            phyllosensRallyMaxTicks = b
                    .comment("合流を待つ上限(tick)。これを過ぎたら人数が足りなくても向かう。既定 200 = 10 秒。")
                    .defineInRange("phyllosensRallyMaxTicks", 200, 0, 6000);

            // フィロセンスのビームの数値は mobs.json の phyllosens.beam へ移した(2026-09-16)。
            // toml を手で開かなくても mod マネージャーの Mob ステータスから調整できる。

            velophyllSightRange = b
                    .comment("ヴェロフィルが標的を見つける距離(ブロック)。mobs.json の followRange と揃えておく。")
                    .defineInRange("velophyllSightRange", 64.0D, 1.0D, 256.0D);

            velophyllMaxTargetDepth = b
                    .comment("地表からこれより深いところにいる相手は狙わない(ブロック)。既定 6。",
                            "爆弾は上から落とすものなので、洞窟の奥の相手には物理的に届かない。",
                            "これが無いと、届かない相手を抱えて上空を延々と旋回し続けることになる。",
                            "地表の判定は<葉を無視した>高さなので、森の木の下にいる相手はちゃんと狙える",
                            "(天蓋は爆弾が撃ち抜く)。大きくすると浅い洞窟や地下室の相手も狙いに来る。")
                    .defineInRange("velophyllMaxTargetDepth", 6, 0, 256);

            velophyllBiteMinAltitude = b
                    .comment("地表からこれだけ上に浮いている相手は「空中の相手」とみなし、",
                            "爆撃ではなく<噛みつき>で襲う(ブロック)。既定 3.0。",
                            "メガネウラのような飛ぶ相手に爆弾を落としても当たらず、",
                            "何もない地面へ落とし続けることになるための切り替え",
                            "(ユーザー報告 2026-09-17)。判定には「接地していないこと」も要るので、",
                            "跳んだだけの相手や木の枝の上の相手は今までどおり爆撃の対象。",
                            "小さくすると低く飛ぶ相手にも噛みつきに行く。")
                    .defineInRange("velophyllBiteMinAltitude", 3.0D, 0.5D, 64.0D);

            velophyllBiteReach = b
                    .comment("噛みつきが届く距離(ブロック)。既定 1.5。",
                            "体の中心からこの半径の箱に相手の当たり判定が重なったところで噛む。",
                            "当たり判定そのもの(mobs.json の width、翼端まで 5.75)は使わない ——",
                            "使うと翼の先が掠めただけで噛みつきになってしまう。噛むのは口。",
                            "大きくすると擦れ違いざまでも当たるようになる。")
                    .defineInRange("velophyllBiteReach", 1.5D, 0.0D, 8.0D);

            velophyllBiteCooldownTicks = b
                    .comment("一度噛んでから次に噛めるまで(tick)。既定 20 = 1 秒。",
                            "噛んだあとは離脱して回り込むので、実際の間隔はこれより長くなる。")
                    .defineInRange("velophyllBiteCooldownTicks", 20, 1, 600);

            velophyllBiteBreakOffTicks = b
                    .comment("噛んだあと、離脱して向き直すのに使う時間(tick)。既定 30 = 1.5 秒。",
                            "相手の向こう側の上空へ抜けてから旋回して戻る。0 にすると離脱せず、",
                            "相手にまとわりつき続ける(旋回半径の都合でその場を回るだけになりやすい)。")
                    .defineInRange("velophyllBiteBreakOffTicks", 30, 0, 600);

            velophyllCruiseAltitude = b
                    .comment("ふだん飛ぶ高さ(地表からのブロック数)。旋回もこの高さで行う。")
                    .defineInRange("velophyllCruiseAltitude", 28, 2, 128);

            velophyllDropAltitude = b
                    .comment("爆弾を落とす高さ(標的の頭上からのブロック数)。既定 8。",
                            "急降下してこの高さまで降りてから 1 発ずつ落とす。")
                    .defineInRange("velophyllDropAltitude", 8, 1, 64);




            velophyllCircleRadius = b
                    .comment("爆弾を落とせない間、標的の周りを旋回する半径(ブロック)。既定 12。")
                    .defineInRange("velophyllCircleRadius", 26.0D, 2.0D, 128.0D);

            velophyllCanopyClearance = b
                    .comment("木の天蓋(葉)の上をどれだけ空けて飛ぶか(ブロック)。既定 6。",
                            "森の上では velophyllDropAltitude まで降りると木につっかえるので、",
                            "「標的の 8 ブロック上」と「天蓋の 6 ブロック上」の高いほうを投下高度にする。",
                            "= 開けた土地では急降下爆撃、森の上では水平爆撃に自然と切り替わる。")
                    .defineInRange("velophyllCanopyClearance", 6, 0, 64);

            velophyllCanopyScanRadius = b
                    .comment("投下高度を決めるとき、標的の周り何ブロックまでの木の高さを見るか。既定 12。",
                            "爆撃コースは標的の上を通過するので、進入路ぶんも含めて広めに見る。")
                    .defineInRange("velophyllCanopyScanRadius", 12, 0, 64);

            velophyllTurnRate = b
                    .comment("1 tick に曲げられる向きの上限(度)。既定 3.0(バニラのファントムは 4.0)。",
                            "これが「その場でくるっと回らず、飛行機のように大きく旋回する」の正体。",
                            "",
                            "★ 旋回半径 ≒ 実速度 ÷ 角速度 なので、velophyllCruiseSpeed と一緒に決めること。",
                            "  実速度(ブロック/tick) ≒ velophyllCruiseSpeed × 0.55",
                            "  旋回半径(ブロック)   ≒ 実速度 ÷ (velophyllTurnRate × 0.01745)",
                            "  既定(速度 2.4 / 旋回 3.0)ならおよそ半径 25 ブロック。",
                            "  velophyllCircleRadius をこの半径よりだいぶ小さくすると、",
                            "  曲がりきれずに標的の周りを回れなくなる(外へ膨らんでいく)。")
                    .defineInRange("velophyllTurnRate", 3.0D, 0.2D, 30.0D);

            velophyllCruiseSpeed = b
                    .comment("直進しているときの巡航速度の上限。既定 2.4(実速度およそ 1.3 ブロック/tick",
                            "= 26 ブロック/秒。プレイヤーのダッシュのおよそ 4.5 倍)。",
                            "きつく曲がっている間は自動で落ちて、直進に戻るとまた上がっていく",
                            "(旋回で失速する飛行機と同じ)。",
                            "上げるときは velophyllTurnRate と velophyllCircleRadius も一緒に見ること。")
                    .defineInRange("velophyllCruiseSpeed", 2.4D, 0.2D, 6.0D);

            velophyllQuietTicks = b
                    .comment("この時間プレイヤーを見つけられなければ地上へ降りて胞子をばらまく(tick)。",
                            "既定 3600 = 3 分。プレイヤーを見つけるたびに 0 に戻る。")
                    .defineInRange("velophyllQuietTicks", 3600, 0, 72000);

            velophyllCorruptionRadius = b
                    .comment("地上でばらまくときに汚染を広げる水平半径(ブロック)。")
                    .defineInRange("velophyllCorruptionRadius", 6, 0, 64);

            velophyllGroundMaxTicks = b
                    .comment("地上に留まっていられる上限(tick)。既定 2400 = 2 分。",
                            "汚し尽くす前でもこれで切り上げて飛び立つ(飛行 Mob が地面に居座り続けないための保険)。")
                    .defineInRange("velophyllGroundMaxTicks", 2400, 100, 24000);









            sporophyllAuraRange = b
                    .comment("スポロフィルの傘の胞子が届く半径(ブロック)。既定 64。",
                            "バフ・デバフ・胞子濃度の上昇・胞子感染の進行、すべてこの半径で効く。",
                            "ユーザー指定(2026-09-26): 32 から倍に。代わりにバフの耐性をやめ、再生を II → I にした(effects.json)。")
                    .defineInRange("sporophyllAuraRange", 64.0D, 1.0D, 256.0D);

            sporophyllAuraIntervalTicks = b
                    .comment("効果を配り直す間隔(tick)。既定 20 = 1 秒。",
                            "持続(effects.json の sporophyllSpores / clingingSpores の durationTicks)より十分短くしておくこと。",
                            "短くするほど範囲の出入りへの反応が良くなるが、そのぶん重くなる。")
                    .defineInRange("sporophyllAuraIntervalTicks", 20, 1, 200);





            sporophyllGearContaminationIntervalTicks = b
                    .comment("範囲内の敵の装備に胞子汚染を進める間隔(tick)。既定 100 = 5 秒。")
                    .defineInRange("sporophyllGearContaminationIntervalTicks", 100, 20, 12000);

            sporophyllGearContaminationPercent = b
                    .comment("1 回あたりに進める装備の胞子汚染(%)。既定 1.0。",
                            "対象は防具・道具・武器(#overgrown_vegetation:spore_contaminable の規則どおり)。")
                    .defineInRange("sporophyllGearContaminationPercent", 1.0D, 0.0D, 100.0D);

            sporophyllConcentrationBoostPercent = b
                    .comment("周囲の胞子濃度に上乗せする値(%)。既定 60。",
                            "中心で最大、半径の縁で 0 まで直線的に落ちる。複数体いれば合算される。",
                            "胞子濃度は 200 tick キャッシュされるので、反映は少し遅れる。")
                    .defineInRange("sporophyllConcentrationBoostPercent", 60.0D, 0.0D, 100.0D);





            sporophyllPurifierClearsAura = b
                    .comment("稼働中の胞子除去装置の効果範囲内で、スポロフィルの効果が消えるか。既定 true。",
                            "true だと、範囲に入った時点で",
                            "  味方のバフ  : 配っているバニラの効果(既定は再生・攻撃力上昇)と「スポロフィルの胞子」",
                            "  敵のデバフ  : 「纏わりついた胞子」(移動速度低下と攻撃力低下を含む)",
                            "がどちらも剥がれ、範囲内にいるあいだは配り直されない(出れば再び掛かる)。",
                            "消えるのは胞子由来のぶんだけで、鈍化のポーションなど他所から掛かった効果は残る。",
                            "装備の胞子汚染も範囲内では進まない(既に付いている汚染は消えない)。",
                            "強さ(中心からの距離)は見ないので、縁でも中心でも等しく消える。")
                    .define("sporophyllPurifierClearsAura", true);

            sporophyllPurifierCheckIntervalTicks = b
                    .comment("スポロフィルの効果持ちが除去装置の圏内に入っていないか見直す間隔(tick)。既定 20 = 1 秒。",
                            "見るのはスポロフィルのバフ/デバフが付いている個体だけなので、ふだんは走らない。",
                            "傘の下に居る個体は sporophyllAuraIntervalTicks ごとに別途判定されるので、",
                            "ここが効くのは「バフを持ったまま傘を離れて装置に入った」場合。")
                    .defineInRange("sporophyllPurifierCheckIntervalTicks", 20, 1, 200);

            sporophyllCorruptionRadius = b
                    .comment("スポロフィルが常時汚染し続ける水平半径(ブロック)。0 で汚染しない。",
                            "ほかのグループ4 はうずくまっている間だけ汚すが、この Mob は立っていても歩いていても撒き続ける。",
                            "中心は歩いて半径の半分だけ離れるか、届く範囲を汚し尽くすと張り直される。")
                    .defineInRange("sporophyllCorruptionRadius", 8, 0, 64);

            sporophyllCorruptionIntervalTicks = b
                    .comment("常時汚染で、何 tick に1マス汚すか(既定 5 = 1秒に4マス)。")
                    .defineInRange("sporophyllCorruptionIntervalTicks", 5, 1, 200);

            sporophyllSummonDamage = b
                    .comment("スポロフィルがこれだけダメージを受けるごとにアルボラを呼ぶ。0 で呼ばない。",
                            "数えるのは実際に減った体力(防具・吸収などを通したあとの値)。",
                            "傘のバフは撒いている本人には掛からない(耐性も 2026-09-26 に配るのをやめた)。")
                    .defineInRange("sporophyllSummonDamage", 15.0D, 0.0D, 1000.0D);

            sporophyllSummonCount = b
                    .comment("1回に呼ぶアルボラの数。")
                    .defineInRange("sporophyllSummonCount", 4, 0, 32);

            sporophyllSummonNearbyCap = b
                    .comment("周囲16ブロックにグループ所属の Mob がこの数以上いるときは呼ばない。0 で無制限。",
                            "長い戦いで無制限に増え続けるのを防ぐための歯止め。")
                    .defineInRange("sporophyllSummonNearbyCap", 32, 0, 256);

            invaderDeathCorruptionRadius = b
                    .comment("グループ4 が死んだ瞬間に汚染する球のおおよその半径(ブロック)。",
                            "実際の境界は invaderDeathCorruptionNoise 分だけ方向ごとに波打つので綺麗な球にはならない。")
                    .defineInRange("invaderDeathCorruptionRadius", 3, 0, 32);

            invaderDeathCorruptionNoise = b
                    .comment("死亡時汚染の境界を方向ごとに波打たせる振幅(ブロック、±)。",
                            "0 にすると invaderDeathCorruptionRadius ちょうどの綺麗な球になる。")
                    .defineInRange("invaderDeathCorruptionNoise", 1.2D, 0.0D, 16.0D);

            corpseTendrilInitialCount = b
                    .comment("汚染死体ブロックが起点から放射状に伸ばす「根」の本数。",
                            "blight_fern が使う tendrilInitialCount とは別の値。")
                    .defineInRange("corpseTendrilInitialCount", 4, 1, 16);

            corpseTendrilMaxActive = b
                    .comment("汚染死体ブロックの触手が同時に存在できる最大本数。",
                            "blight_fern が使う tendrilMaxActive とは別の値。")
                    .defineInRange("corpseTendrilMaxActive", 24, 1, 256);

            corpseHatchChance = b
                    .comment("死体ブロックが自分の半径を全て汚染し終えたとき、その場を消して代わりに種族対応の",
                            "「過繁茂した(MOB名)」を孵化させる確率。外れた場合はその場に汚染シダが生える。",
                            "既定値 0.8 = 80%。")
                    .defineInRange("corpseHatchChance", 0.8D, 0.0D, 1.0D);

            giantTreeBlocksPerTick = b
                    .comment("巨大樹を1ゲームティックあたり何ブロックずつ設置するか(複数ティックに分けて生成)。",
                            "巨大樹は枝分かれ・傘の拡大で約10〜18万ブロックある。一度に置くとサーバーが固まる。",
                            "既定 2500 なら1本あたり約40〜70ティック(2〜3.5秒)かけてゆっくり生える。同時に複数本の",
                            "生成中でも合計でこの数を超えないよう配分する。小さくすると生成は遅いが1ティックの負荷は軽い。")
                    .defineInRange("giantTreeBlocksPerTick", 2500, 50, 50000);

            giantTreeMinSpacing = b
                    .comment("巨大樹同士がこのブロック数(水平距離)より近くには生えないようにする。",
                            "この範囲内に既存の巨大樹があれば新しい巨大樹の生成を中止する(苗木から育てる場合も",
                            "同じ。近すぎると苗木のまま育たない)。0 で無効。既定 384。","クリエイティブ専用の「巨大樹の苗木」は giantTreeSaplingForceGrowth=true なら対象外。",
                            "導入前から生えていた巨大樹は記録されていないので対象外。")
                    .defineInRange("giantTreeMinSpacing", 384, 0, 10000);

            giantTreeSaplingForceGrowth = b
                    .comment("クリエイティブ専用の「巨大樹の苗木」から育てるときは giantTreeMinSpacing の間隔チェックと",
                            "「洞窟内か」の判定を飛ばして必ず巨大樹を生やすか。既定 true(好きな場所で試せるように)。",
                            "false にすると自然生成と同じ扱いになり、既存の巨大樹の近くや屋根の下では苗木のまま育たない。")
                    .define("giantTreeSaplingForceGrowth", true);

            giantTreeHiveChamber = b
                    .comment("巨大樹の幹の真下に、ハイヴノードの住処「巣の球」を作るか。既定 true。",
                            "球は中まで詰まっていて(表面 1 層が「硬化した巨大樹の幹」、内側は形成層)、",
                            "中心のハイヴノードの小部屋・隣の儀式の広間・球面の出口へうねる通路だけが掘られる。")
                    .define("giantTreeHiveChamber", true);

            giantTreeHiveChamberRadius = b
                    .comment("「巣の球」の半径(ブロック)。既定 12。")
                    .defineInRange("giantTreeHiveChamberRadius", 12, 2, 48);

            giantTreeHiveChamberTrunkMargin = b
                    .comment("「巣の広間」の球を、幹の裾の半径よりこのブロック数だけ必ず太くする。既定 4。",
                            "幹の裾は木の大きさによって半径 11〜18 まで変わるので、これが無いと球が幹の中に",
                            "完全に埋もれて外から見えない。0 にすると giantTreeHiveChamberRadius をそのまま使う。")
                    .defineInRange("giantTreeHiveChamberTrunkMargin", 4, 0, 32);

            giantTreeHiveChamberDepth = b
                    .comment("「巣の広間」の中心を、巨大樹の根本の地面から何ブロック下に置くか。既定 0。",
                            "0 = 球の中心が地面の高さ = 球の上半分が地上に出て、その上から幹が生えているように見える。",
                            "大きくするほど地中に潜り、半径と同じ値(既定の半径なら 12)で球のてっぺんが地面の高さになる。",
                            "球の底が岩盤に届く場合は自動でずらし、それでも余裕が無ければ広間を作らない。")
                    .defineInRange("giantTreeHiveChamberDepth", 0, 0, 256);

            giantTreeHiveChamberBarkThickness = b
                    .comment("「巣の広間」の球の外側を、巨大樹の原木(giant_overgrown_log)で何層ぶん覆うか。既定 2。",
                            "殻の「硬化した巨大樹の幹」がむき出しにならず、樹皮に包まれた球に見える。0 で覆わない。")
                    .defineInRange("giantTreeHiveChamberBarkThickness", 2, 0, 8);

            hiveNodePulseIntervalTicks = b
                    .comment("「ハイヴノード」が鼓動する間隔(tick)。既定 80 = 4 秒。",
                            "1 回の鼓動で胞子を吹き出して音を鳴らすだけ(2026-09-19 に周囲へのバフ配布は撤去した)。")
                    .defineInRange("hiveNodePulseIntervalTicks", 80, 1, 12000);

            hiveNodeHeightOffset = b
                    .comment("「ハイヴノード」を広間の球の中心から何ブロック上にずらすか。既定 0 = ちょうど中心。")
                    .defineInRange("hiveNodeHeightOffset", 0, -64, 64);

            hiveNodeCavityRadius = b
                    .comment("「巣の球」の中心に掘る、ハイヴノードの小部屋の半径(ブロック)。既定 4(直径 9 の球)。",
                            "ノードの当たり判定は 3×3 だが、見た目の球は半径およそ 2.5 ブロックあるので 3 だと壁に食い込む。")
                    .defineInRange("hiveNodeCavityRadius", 4, 2, 8);

            hiveRitualChamberHalfWidth = b
                    .comment("小部屋の隣に掘る「儀式の広間」の半幅(ブロック)。既定 4 = 幅 9。",
                            "寄生植物装備を受け取る演出をここで再生する。演出の蔦は幅 7・奥行き 8 に収まる。")
                    .defineInRange("hiveRitualChamberHalfWidth", 4, 3, 10);

            hiveRitualChamberHeight = b
                    .comment("「儀式の広間」の高さ(ブロック)。既定 3。演出の蔦は高さ 3 の天井から生える前提で作ってある。")
                    .defineInRange("hiveRitualChamberHeight", 3, 3, 6);

            hiveTunnelRadius = b
                    .comment("広間から球面の出口へうねる通路の太さ(半径、ブロック)。既定 1.8(幅・高さ 3〜4)。")
                    .defineInRange("hiveTunnelRadius", 1.8D, 1.0D, 4.0D);

            hiveTunnelWinding = b
                    .comment("通路のうねりの強さ(半径と高さの揺れ)。0 = きれいならせん、1 = 大きく揺れる。既定 0.6。")
                    .defineInRange("hiveTunnelWinding", 0.6D, 0.0D, 1.0D);

            hiveTunnelTurns = b
                    .comment("通路がハイヴノードの小部屋の周りを何周らせん状に回ってから出口に抜けるか。既定 1.5。",
                            "大きいほど通路が長くなる(1 周で 60〜90 ブロック)。0.25 で最短。")
                    .defineInRange("hiveTunnelTurns", 1.5D, 0.25D, 4.0D);

            hiveTunnelGrassChance = b
                    .comment("通路・広間の壁面(巨大樹の幹)に下草・蔦・垂れ植物を生やす 1 面あたりの確率。既定 0.45。0 で生やさない。",
                            "幹の露出面と同じ抽選(giantTreePlantChance 相当)を、巣の球の中だけこの値で回す。")
                    .defineInRange("hiveTunnelGrassChance", 0.45D, 0.0D, 1.0D);

            plantArmorStage2Minutes = b
                    .comment("寄生植物装備が 2 段階目(体力 14・防御 12・耐久 450、実が倍、定期的に空腹)になるまでの装着時間(分)。既定 30。")
                    .defineInRange("plantArmorStage2Minutes", 30, 1, 6000);

            plantArmorStage3Minutes = b
                    .comment("寄生植物装備が 3 段階目(体力 20・防御 20・耐久 600、歩いた所が汚染、光合成、毒耐性)になるまでの装着時間(分)。既定 90。")
                    .defineInRange("plantArmorStage3Minutes", 90, 1, 6000);

            plantArmorExhaustionPerSecond = b
                    .comment("寄生植物装備を着けている間、毎秒足す食料の疲労値。4.0 で食料 1 目盛りぶん。既定 0.15(約 27 秒に 1 目盛り)。",
                            "過繫茂バイオームでは 1 段階目は半分、2 段階目以降は 0(減らない)。")
                    .defineInRange("plantArmorExhaustionPerSecond", 0.15D, 0.0D, 5.0D);

            plantArmorRepairIntervalTicks = b
                    .comment("寄生植物装備の耐久値が 1 回復する間隔(tick)。既定 40(2 秒)。バイオームの外では回復のたびに食料を使う。")
                    .defineInRange("plantArmorRepairIntervalTicks", 40, 1, 1200);

            plantArmorHungerEffectIntervalSeconds = b
                    .comment("寄生植物装備の 2 段階目以降で、空腹 II(14 秒)が付く間隔(秒)。既定 90。")
                    .defineInRange("plantArmorHungerEffectIntervalSeconds", 90, 1, 3600);

            plantArmorKillFood = b
                    .comment("寄生植物装備を着けて Mob を倒したとき回復する食料(目盛り)。既定 4。")
                    .defineInRange("plantArmorKillFood", 4, 0, 20);

            plantArmorKillGrowthSeconds = b
                    .comment("寄生植物装備を着けてグループ 1〜3 か過繫茂 Mob 以外の Mob を倒したとき、装備の成長時間(次の段階まで)が進む秒数。既定 10。")
                    .defineInRange("plantArmorKillGrowthSeconds", 10, 0, 3600);

            plantArmorFruitGrowthSeconds = b
                    .comment("寄生植物装備を着けて「大きな未発達な果実」を食べたとき、装備の成長時間(次の段階まで)が進む秒数。既定 60。")
                    .defineInRange("plantArmorFruitGrowthSeconds", 60, 0, 36000);

            plantArmorSporophyllGrowthMultiplier = b
                    .comment("寄生植物装備の成長速度の倍率(「スポロフィルの胞子」バフが付いている間)。既定 1.5。")
                    .defineInRange("plantArmorSporophyllGrowthMultiplier", 1.5, 0.0, 10.0);

            plantArmorWalkCorruptionChance = b
                    .comment("寄生植物装備の 3 段階目で、新しく踏んだマスの真下を汚染する確率。既定 1.0。")
                    .defineInRange("plantArmorWalkCorruptionChance", 1.0D, 0.0D, 1.0D);

            plantArmorPhotosynthesisFernMultiplier = b
                    .comment("寄生植物装備の 3 段階目の光合成中(日なた)、周囲に汚染シダが湧く確率の倍率。既定 2.0。")
                    .defineInRange("plantArmorPhotosynthesisFernMultiplier", 2.0D, 1.0D, 10.0D);

            hiveTunnelEntranceElevationMin = b
                    .comment("通路の出口を開ける仰角の下限(度)。0 = 球の赤道(= 地面の高さ)。既定 8。",
                            "出口は必ず地上に出ている上半分の、幹の裾より外側の帯に開く。")
                    .defineInRange("hiveTunnelEntranceElevationMin", 8, 0, 60);

            hiveTunnelEntranceElevationMax = b
                    .comment("通路の出口を開ける仰角の上限(度)。既定 35。大きくすると幹の裾に隠れやすくなる。")
                    .defineInRange("hiveTunnelEntranceElevationMax", 35, 0, 80);

            giantTreePlantChance = b
                    .comment("巨大樹が生えきったあと、その原木・葉の露出面に汚染ブロックと同じ装飾植物",
                            "(シダ・苔・蔦・垂れ植物)を生やす1面あたりの確率。0 で無効。",
                            "葉には下面の垂れ植物のみ、原木には全方向。通常の汚染(growthChance)より",
                            "低めにしないと1本で大量に生えて重くなる。既定 0.08。")
                    .defineInRange("giantTreePlantChance", 0.08D, 0.0D, 1.0D);

            giantTreeRootPlantChance = b
                    .comment("巨大樹・レイドの根の、地表を這うかまぼこ状の盛り上がりの露出面を、汚染された地面と",
                            "同じように飾る確率。地上なら上面に下草(シダ・苔・背高植物)+側面に苔・蔦、",
                            "水中なら上面に水草・側面に藻。0 で無効。根の上だけを狙うので",
                            "giantTreePlantChance より高めでも重くなりにくい。既定 0.55。")
                    .defineInRange("giantTreeRootPlantChance", 0.55D, 0.0D, 1.0D);

            giantTreeGlowLichenChance = b
                    .comment("巨大樹が生えきったあと、その原木の露出面にヒカリゴケ(minecraft:glow_lichen)を",
                            "貼る1面あたりの確率。0 で無効。ヒカリゴケは光源なので上げすぎると光更新が重い",
                            "(1本に数百個貼られうる)。既定 0.03。")
                    .defineInRange("giantTreeGlowLichenChance", 0.03D, 0.0D, 1.0D);

            giantTreeChlorophylliteNodesMin = b
                    .comment("巨大樹の幹の内側(形成層)に埋め込む「クロロフィライトの鉱石」の鉱石ノードの本数の最小値。",
                            "1ノード = giantTreeChlorophylliteNodeRadius の球状の塊。地中の鉱脈のように形成層内のランダムな位置に置かれる。",
                            "既定 45。")
                    .defineInRange("giantTreeChlorophylliteNodesMin", 45, 0, 800);

            giantTreeChlorophylliteNodesMax = b
                    .comment("「クロロフィライトの鉱石」ノードの本数の最大値(最小値との間でランダム)。既定 75。")
                    .defineInRange("giantTreeChlorophylliteNodesMax", 75, 0, 800);

            giantTreeChlorophylliteNodeRadius = b
                    .comment("「クロロフィライトの鉱石」ノード1個の塊の半径(球状)。大きいほど1ノードあたりの鉱石量が増える。",
                            "小数で指定できる(整数刻みでは1段で体積が倍近く変わってしまうため)。既定 3.75",
                            "(1ノードあたり約300ブロック。4.0 なら約348、3.5 なら約231)。")
                    .defineInRange("giantTreeChlorophylliteNodeRadius", 3.75D, 0.5D, 8.0D);

            giantTreeHardenedSapCrystalChance = b
                    .comment("巨大樹1本に「硬化樹液結晶の鉱石」ノードが生成される確率。引いたときだけ",
                            "giantTreeHardenedSapCrystalNodesMin〜Max 本のノードが追加される(たまにしか出ないレア鉱石)。既定 0.35。")
                    .defineInRange("giantTreeHardenedSapCrystalChance", 0.35D, 0.0D, 1.0D);

            giantTreeHardenedSapCrystalNodesMin = b
                    .comment("「硬化樹液結晶の鉱石」ノードが生成される場合の本数の最小値。既定 1。")
                    .defineInRange("giantTreeHardenedSapCrystalNodesMin", 1, 0, 100);

            giantTreeHardenedSapCrystalNodesMax = b
                    .comment("「硬化樹液結晶の鉱石」ノードが生成される場合の本数の最大値(最小値との間でランダム)。既定 3。")
                    .defineInRange("giantTreeHardenedSapCrystalNodesMax", 3, 0, 100);

            giantTreeHardenedSapCrystalNodeRadius = b
                    .comment("「硬化樹液結晶の鉱石」ノード1個の塊の半径(球状)。小数可。既定 1.9",
                            "(1ノードあたり約50ブロック。2.0 なら約67)。")
                    .defineInRange("giantTreeHardenedSapCrystalNodeRadius", 1.9D, 0.5D, 8.0D);

            b.pop();

            b.comment("OvergrownVegetation — 胞子濃度の設定",
                    "胞子濃度はチャンクごとに決まる 0〜100(%)の値で、過繁茂バイオームにどれだけ",
                    "近い/囲まれているかを表す。チャンク自身が過繁茂バイオームなら必ず 100%。",
                    "そうでなければ「半径内の過繁茂バイオーム割合」＋「隣接する過繁茂バイオームの面数×25%」。").push("spore_concentration");

            sporeConcentrationRadiusChunks = b
                    .comment("胞子濃度の割合成分を計算する正方領域の半径(チャンク)。既定 3 = 7x7=49 チャンク。",
                            "未ロードのチャンクは分母・分子どちらからも除外される。")
                    .defineInRange("sporeConcentrationRadiusChunks", 3, 1, 16);

            sporeConcentrationFullRatio = b
                    .comment("上記領域のうち過繁茂バイオームが占める割合がこの値に達したら割合成分を 100% とし、",
                            "それ以上は上がらない。既定 0.8 = 8 割で最大。")
                    .defineInRange("sporeConcentrationFullRatio", 0.8D, 0.05D, 1.0D);

            sporeConcentrationBiomeAdjacentPercent = b
                    .comment("最寄りの過繁茂バイオームチャンクと「密接」(隣接・斜め隣接)しているときに、",
                            "割合に関係なく胞子濃度へ加算する値(%)。既定 50。",
                            "判定範囲は sporeConcentrationRadiusChunks(既定 3)と共通。")
                    .defineInRange("sporeConcentrationBiomeAdjacentPercent", 50.0D, 0.0D, 100.0D);

            sporeConcentrationBiomeGap1Percent = b
                    .comment("最寄りの過繁茂バイオームチャンクとの間に 1 チャンク挟まっているときの加算値(%)。既定 25。")
                    .defineInRange("sporeConcentrationBiomeGap1Percent", 25.0D, 0.0D, 100.0D);

            sporeConcentrationBiomeGap2Percent = b
                    .comment("最寄りの過繁茂バイオームチャンクとの間に 2 チャンク挟まっているときの加算値(%)。既定 5。",
                            "これより遠い(3 チャンク以上離れている)場合は距離成分は 0。")
                    .defineInRange("sporeConcentrationBiomeGap2Percent", 5.0D, 0.0D, 100.0D);

            sporeConcentrationPerCorruptedBlockPercent = b
                    .comment("そのチャンクの 16x16=256 列のうち「地面(またはその上に生えた植物)が汚染ブロック」な",
                            "列 1 つあたり、胞子濃度へ加算する値(%)。既定 1.0(100 列が汚染で +100% = 上限)。",
                            "葉の天蓋・原木・蔦・下草は貫通して地面を見るので、密林でも地面の汚染が正しく数えられる。",
                            "バイオームがまだ書き換わっていないチャンクでも、汚染が進むほど胞子濃度が上がる。",
                            "0 でこの成分を無効化(列の走査自体も省略される)。")
                    .defineInRange("sporeConcentrationPerCorruptedBlockPercent", 1.0D, 0.0D, 10.0D);

            sporeConcentrationCorruptedScanStride = b
                    .comment("汚染ブロック成分の 256 列走査を、何ブロック間隔のグリッドで間引きサンプリングするか。",
                            "既定 2(64 列だけ調べて 256 列全体へ外挿。負荷を約 1/4 に)。1 で全 256 列を厳密走査。",
                            "大きいほど軽いが、まだらな汚染では推定がぶれる。")
                    .defineInRange("sporeConcentrationCorruptedScanStride", 2, 1, 8);

            sporeConcentrationCacheTtlTicks = b
                    .comment("胞子濃度の計算結果を次元ごと・チャンクごとにキャッシュする時間(tick)。",
                            "高頻度で参照されるため、小さくしすぎると重くなる。胞子濃度はゆっくりしか変化しないので",
                            "長めで問題ない。既定 200(10 秒)。")
                    .defineInRange("sporeConcentrationCacheTtlTicks", 200, 1, 12000);

            sporeInfectionCheckIntervalTicks = b
                    .comment("「胞子感染1」の滞在判定を、各エンティティにつき何 tick に 1 回行うか。既定 20(1 秒)。",
                            "判定 1 回で滞在カウントをこの間隔ぶん進める/0 に戻すので、閾値の意味は変わらない。",
                            "1 にすると毎 tick 判定(重い)。エンティティ数の多いサーバーほど効果が大きい。")
                    .defineInRange("sporeInfectionCheckIntervalTicks", 20, 1, 200);

            // sporeInfectionConcentrationThreshold は effects.json(sporeInfection.concentrationThreshold)へ移行。

            b.pop();

            b.comment("OvergrownVegetation — フェーズシステムの設定",
                    "フェーズ1 はワールド 3 日経過 かつ 実績 6 個で始まり、そこからは「ポイント」だけで進む",
                    "(直前のフェーズ開始時点からの増分。チャンク数による昇格は 2026-09-15 に廃止)。",
                    "既定では F2 = 36 万点 / F3 = 80 万点 / F4 = 140 万点 / F5 = 280 万点 / F6 = 580 万点",
                    "(汚染が 20 チャンク/分で広がる実測ペースだと F1 から F6 までおよそゲーム内 22 日)。",
                    "時間経過ポイントは 1 tick あたり perTick + perTickPerOvergrownChunk × 過繫茂系の総チャンク数",
                    "(既定 1 + 総チャンク数 × 0.005)なので、汚染が広がるほどポイントが速く貯まる。F7 以降は内容が未実装のため昇格条件が無く、",
                    "自動進行は F6 で止まる(コマンドでなら 10 まで上げられる)。").push("phase");

            // フェーズの昇格条件・内容(時刻ゲート/ポイント配分/必要ポイント・必要チャンク数と and・or/
            // グループ1 強化/レイド解禁フェーズ)は config/overgrown_vegetation/phases.json へ移行した
            // (com.yayuy.overgrownvegetation.data.PhaseTable)。ここには残していない。

            phase1InfectionChance = b
                    .comment("フェーズ 1 以降、#overgrown_vegetation:phase_infectable_animals タグの Mob が",
                            "湧いた瞬間から「胞子感染1」を持っている確率。既定 0.05 = 5%。0 にすると無効化。")
                    .defineInRange("phase1InfectionChance", 0.05D, 0.0D, 1.0D);

            overgrownSpawnIntervalTicks = b
                    .comment("フェーズ 1 以降、過繁茂バイオームの中の「過繁茂した Mob」の個体数を",
                            "プレイヤー周辺で補充する処理の判定間隔(tick)。既定 60 = 3 秒。")
                    .defineInRange("overgrownSpawnIntervalTicks", 60, 20, 24000);

            overgrownSpawnChance = b
                    .comment("上記の補充判定 1 回あたり、各プレイヤーについて実際に補充を行う確率。既定 0.9。")
                    .defineInRange("overgrownSpawnChance", 0.9D, 0.0D, 1.0D);

            overgrownSpawnGroupMax = b
                    .comment("1 パスで 1 種に湧かせる上限数(＝群れの大きさの上限)。既定 5。",
                            "種ごとに決まっている clusterMax(OvergrownSpawnProfile)と、小さい方が使われる。")
                    .defineInRange("overgrownSpawnGroupMax", 5, 1, 16);

            overgrownSpawnTargetTotal = b
                    .comment("過繁茂バイオームでプレイヤー周辺(overgrownSpawnRadius + overgrownSpawnCountMargin)に",
                            "維持しようとする「過繁茂した Mob」の総数の目安。既定 18(30 から減量)。",
                            "種ごとの目標数は OvergrownSpawnProfile の weight でこの数を比例配分して決まる",
                            "(被食者=グループ2 が捕食者=グループ3 の約3倍になるよう振ってある)。",
                            "ただし 1 種あたり最低 1 体は目標に付くので、湧ける種が多いフェーズでは",
                            "実際の合計がこの数を上回る(既定ではフェーズ5 が 25 体、フェーズ6以降が 19 体)。",
                            "生態系の密度を決める主なつまみ。0 で生態系スポーンを実質無効化。")
                    .defineInRange("overgrownSpawnTargetTotal", 18, 0, 500);

            overgrownSpawnMaxPerPass = b
                    .comment("1 パス・1 プレイヤーあたりに新規スポーンさせる最大数。既定 5(8 から減量)。",
                            "目標数までは数パスかけて徐々に埋まる(=一気にポップしない)。")
                    .defineInRange("overgrownSpawnMaxPerPass", 5, 1, 64);

            overgrownSpawnCountMargin = b
                    .comment("実個体数を数える範囲を overgrownSpawnRadius からどれだけ広げるか(ブロック)。既定 16。",
                            "スポーン範囲の縁の外へ出た個体も数に入れることで、縁での湧き過ぎ・振動を防ぐ。")
                    .defineInRange("overgrownSpawnCountMargin", 16, 0, 128);

            overgrownSpawnRadius = b
                    .comment("過繁茂バイオームへの生態系スポーンの、プレイヤーからの水平距離(ブロック)。既定 48。")
                    .defineInRange("overgrownSpawnRadius", 48, 8, 128);

            overgrownBiomeBlockWildSpawns = b
                    .comment("過繁茂バイオームの中で「過繁茂した(MOB名)」・blight_fern 以外の Mob の自然湧き",
                            "(自然/チャンク生成/ピリジャー巡回隊/増援/ジョッキー)を足元のブロックで振り分けるか。既定 true。",
                            "汚染ブロックの上=打ち消す。汚染されていないブロックの上=通常 Mob をそのまま湧かせる",
                            "(overgrownBiomeWildSpawnInfectChance で「胞子感染1」を持たせる)。",
                            "スポーンエッグ・/summon・繁殖・スポナー・行商人などの意図的な湧きは対象外。")
                    .define("overgrownBiomeBlockWildSpawns", true);

            overgrownBiomeWildSpawnInfectChance = b
                    .comment("上記が true のとき、過繁茂バイオームの「汚染されていないブロックの上」に湧いた通常 Mob が",
                            "「胞子感染1」を持って湧く確率。既定 1.0 = 必ず感染して湧く。0 にすると感染させずそのまま湧く。")
                    .defineInRange("overgrownBiomeWildSpawnInfectChance", 1.0D, 0.0D, 1.0D);

            b.pop();

            b.comment("OvergrownVegetation — レイド(過繫茂の苗床)の設定",
                    "フェーズ raidMinPhase 以降で発生する大規模イベント。プレイヤーから離れた地点に",
                    "「過繫茂の苗床」(ボス)が出現し、そこから汚染・巨大植物・敵対 Mob の波が広がる。",
                    "苗床を倒すとレイドは終了するが、広がった汚染・過繫茂バイオームはそのまま残る。",
                    "フェーズが raidMinPhase 以上へ上がった瞬間に必ず1回発生し、以降は周期的に発生する。").push("raid");

            raidEnabled = b
                    .comment("レイドイベントを有効にするか。")
                    .define("raidEnabled", true);

            raidPeriodicMinTicks = b
                    .comment("フェーズ昇格時のレイドとは別に周期的に発生するレイドの最短間隔(tick)。",
                            "24000 tick = ゲーム内 1 日。既定 72000 = 3 日。")
                    .defineInRange("raidPeriodicMinTicks", 72000, 0, Integer.MAX_VALUE);

            raidPeriodicMaxTicks = b
                    .comment("周期レイドの最長間隔(tick)。実際の間隔は min〜max でランダムに決まる。既定 120000 = 5 日。")
                    .defineInRange("raidPeriodicMaxTicks", 120000, 0, Integer.MAX_VALUE);

            raidRetryTicks = b
                    .comment("レイドを発生させようとしたが条件が揃わなかった(プレイヤー不在・適地なし・同時数上限)ときに、",
                            "次に再挑戦するまでの待ち時間(tick)。既定 6000 = 5 分。")
                    .defineInRange("raidRetryTicks", 6000, 20, Integer.MAX_VALUE);

            raidMaxConcurrent = b
                    .comment("同時に存在できるレイドの最大数。")
                    .defineInRange("raidMaxConcurrent", 1, 1, 16);

            raidSpawnMinDistance = b
                    .comment("レイドの苗床がプレイヤーから最低これだけ離れた地点に出現する(ブロック)。既定 80。")
                    .defineInRange("raidSpawnMinDistance", 80, 16, 512);

            raidSpawnMaxDistance = b
                    .comment("レイドの苗床が出現する、プレイヤーからの最大距離(ブロック)。",
                            "ロード済み領域内である必要があるため、シミュレーション距離より大きくしても効果は薄い。既定 128。")
                    .defineInRange("raidSpawnMaxDistance", 128, 32, 512);

            raidNexusHealth = b
                    .comment("レイドの苗床(ボス)の最大体力。既定 400。")
                    .defineInRange("raidNexusHealth", 400.0D, 1.0D, 100000.0D);

            raidBossBarRadius = b
                    .comment("苗床のボスバーが表示されるプレイヤーの範囲(ブロック)。既定 96。")
                    .defineInRange("raidBossBarRadius", 96, 16, 512);

            raidForceLoadRadius = b
                    .comment("レイド発生時に苗床の周囲を常時強制ロードする半径(チャンク)。この正方形の中は",
                            "プレイヤーが居なくても Mob まで動き、さらに外側 2 チャンクまでが読み込まれる",
                            "(ブロックは書き換わるが Mob は止まる)。苗床が止まらず、読み込まれた範囲の根と汚染は",
                            "誰も居なくても進む。その外の根と汚染は、プレイヤーが近くに来て読み込まれている間だけ進む",
                            "(読み込まれていない間は捨てずに待ち、読み込まれたら止まったところから続ける)。",
                            "レイド終了時に解除。途中で下げると、進行中のレイドもその範囲まで縮む。",
                            "既定 2(2026-09-25 仮決め。5x5 = 25 チャンクが動き、9x9 が読み込まれる ≒ 苗床から 64 ブロック)。",
                            "ウェーブの湧く輪(raidWaveSpawnRadius、既定 24 ブロック)がまるごと動く最小の値。",
                            "以前の既定は 7(15x15 = 225 チャンクを常に動かし、面汚染の半径 100 を覆っていた)。")
                    .defineInRange("raidForceLoadRadius", 2, 0, 24);

            raidRootGrowthIntervalTicks = b
                    .comment("根の先端を1ブロック進める間隔(tick)。既定 60(以前は150、1200、300、50と",
                            "行き来した末に60へ再調整)。小さいほど根が速く伸びる。")
                    .defineInRange("raidRootGrowthIntervalTicks", 60, 1, 4800);

            raidRootCount = b
                    .comment("苗床から放射状に伸ばす根の本数。既定 5。根は伸びきらず、強制ロード範囲/プレイヤーの",
                            "居る範囲の限りずっと外へ伸び続ける(レイドの間ずっと汚染が広がる)。")
                    .defineInRange("raidRootCount", 5, 1, 32);

            raidRootThicknessMin = b
                    .comment("根の太さ(半径)の下限。先端は常にこの太さ。既定 1。")
                    .defineInRange("raidRootThicknessMin", 1, 1, 8);

            raidRootThicknessMax = b
                    .comment("苗床のすぐ根本が、育ちきったときに到達する最大の太さ(半径)。既定 3(≒7x7の丸い断面)。")
                    .defineInRange("raidRootThicknessMax", 3, 1, 8);

            raidRootYoungLength = b
                    .comment("根の全長がこの長さ(ブロック)以下のうちは、根本もまだ raidRootThicknessMin のまま",
                            "(生えたばかりでまだ細い)。既定 10。")
                    .defineInRange("raidRootYoungLength", 10, 1, 256);

            raidRootMatureLength = b
                    .comment("根の全長がここまで伸びると、根本が raidRootThicknessMax まで育ちきる",
                            "(raidRootYoungLength〜ここまでの間は線形に太くなっていく)。既定 50。",
                            "先端は伸びている間ずっと raidRootThicknessMin のまま — 根本だけが、根が伸びるにつれて",
                            "後から太くなっていく(生えたばかりの若い根が徐々に育っていくイメージ)。")
                    .defineInRange("raidRootMatureLength", 50, 1, 512);

            raidRootMaxDistance = b
                    .comment("根がレイドビーコンから伸びられる最大距離(水平、ブロック)。既定 100。",
                            "この距離に達した先端はその場で成長を止め、全ての先端が止まると根の伸長そのものが終わる。",
                            "根の太さはこの距離を基準に細くなっていく — ビーコンの足元がいちばん太く",
                            "(raidRootThicknessMax まで)、ここに近づくほど raidRootThicknessMin まで細くなる。",
                            "根が止まると、以降はビーコンを中心にした「面汚染」(raidAreaCorruption*)に切り替わる。")
                    .defineInRange("raidRootMaxDistance", 100, 8, 512);

            raidCorruptionArchMultiplier = b
                    .comment("レイドの汚染起点が生やす装飾アーチ・川の橋の生成確率にかける倍率。",
                            "レイドは汚染起点を大量に撒くため既定で 0.3 に下げてある(アーチが並びすぎないように)。",
                            "1.0 で blight_fern と同じ、0 でレイドの汚染からはアーチ/橋を出さない。",
                            "アーチ/橋そのものの太さ・高さ・長さは corruptionArch* 設定を共有する。")
                    .defineInRange("raidCorruptionArchMultiplier", 0.3D, 0.0D, 1.0D);

            raidRootBranchChance = b
                    .comment("根の先端が1歩進むごとに枝分かれする確率(前回の分岐から",
                            "raidRootBranchCooldownSteps 歩以上空いているとき)。既定 0.15(以前は0.05、",
                            "枝分かれの頻度を増やしてほしいという要望を受けて上げた)。")
                    .defineInRange("raidRootBranchChance", 0.15D, 0.0D, 1.0D);

            raidRootBranchCooldownSteps = b
                    .comment("1本の根の先端が枝分かれしてから、また枝分かれできるようになるまでの歩数。既定 8(以前は12)。")
                    .defineInRange("raidRootBranchCooldownSteps", 8, 1, 256);

            raidRootMaxTips = b
                    .comment("同時に伸びていられる根の先端の最大数(枝分かれ込み)。既定 24(以前は12。",
                            "枝分かれの頻度を上げたぶん、上限にすぐ達して増えなくならないよう引き上げた)。")
                    .defineInRange("raidRootMaxTips", 24, 1, 128);

            raidRootNodeSpacing = b
                    .comment("根に沿って、この間隔(ブロック)ごとに汚染の起点を1つ落とす。既定 6(短いほど根沿いが濃く汚染される)。")
                    .defineInRange("raidRootNodeSpacing", 6, 1, 64);

            raidCorruptionRadius = b
                    .comment("根に沿って落とした汚染起点1つあたりの汚染半径(ブロック)。既定 20。")
                    .defineInRange("raidCorruptionRadius", 20, 4, 128);

            raidCorruptionCoreRadius = b
                    .comment("汚染起点1つあたり、起点周辺を(触手ではなく)まだらに塗りつぶす「コア」の半径。既定 6。",
                            "根のすぐ脇にコアを持たせることで、根沿いが確実に汚染されてバイオーム化しやすくなる。0 でコア無し。")
                    .defineInRange("raidCorruptionCoreRadius", 6, 0, 32);

            raidCorruptionTickInterval = b
                    .comment("汚染サイクルの実行間隔(tick)。小さくするほど速く広がる。既定 5。")
                    .defineInRange("raidCorruptionTickInterval", 5, 1, 1200);

            raidCorruptionNodesPerCycle = b
                    .comment("1サイクルで進める汚染起点の数(残りは次サイクルへ回す)。既定 8。")
                    .defineInRange("raidCorruptionNodesPerCycle", 8, 1, 64);

            raidMaxActiveCorruptionNodes = b
                    .comment("同時に処理し続ける汚染起点の最大数。これを超えると、完了済みの古い起点から処理をやめる",
                            "(汚染したブロックはそのまま残る)。既定 44。")
                    .defineInRange("raidMaxActiveCorruptionNodes", 44, 1, 256);

            raidTendrilInitialCount = b
                    .comment("汚染起点1つあたり、コアの縁から放射状に伸ばす触手の本数。既定 6。")
                    .defineInRange("raidTendrilInitialCount", 6, 1, 64);

            raidTendrilMaxActive = b
                    .comment("汚染起点1つあたり、同時に存在できる触手(根+枝)の最大本数。既定 18。")
                    .defineInRange("raidTendrilMaxActive", 18, 1, 256);

            raidAreaCorruptionRadius = b
                    .comment("根が raidRootMaxDistance まで伸びきったあと、レイドビーコンを中心に",
                            "ブロックを1つ残らず塗りつぶしていく球の半径(ブロック)。既定 100。",
                            "地表だけでなく地下も対象 — ビーコンから近い順に、同じ距離の中ではランダムに汚染していく。")
                    .defineInRange("raidAreaCorruptionRadius", 100, 8, 256);

            raidAreaCorruptionSweepMinutes = b
                    .comment("面汚染が、ビーコンの足元から raidAreaCorruptionRadius の外周まで塗りきるのに",
                            "かける時間(分)。既定 20。",
                            "汚染は「ビーコンからの距離が同じマスの層」を内側から1層ずつ処理していく方式で、",
                            "各層に割り当てる時間は等しい(既定なら 20分 ÷ 半径100 = 1層あたり12秒)。",
                            "層の広さは距離の2乗で増えるので、<b>ビーコンから離れるほど同時に汚染する量が自動で増える</b>",
                            "(半径100・20分なら外周付近で毎秒1万マス前後を処理する計算)。",
                            "短くするほど劇的に重くなる — 下げるときは raidAreaCorruptionMaxCellsPerTick も見ること。")
                    .defineInRange("raidAreaCorruptionSweepMinutes", 20, 1, 600);

            raidAreaCorruptionMaxCellsPerTick = b
                    .comment("面汚染が 1 tick に調べてよいマス数の上限(安全弁)。既定 1500。",
                            "半径100・20分なら必要なのは最大でも 1 tick あたり約520マスなので、既定では効かない。",
                            "半径を広げたり sweepMinutes を短くしたりして重くなりすぎたときだけ効き、",
                            "その場合は予定より時間が延びる(処理が止まるわけではない)。")
                    .defineInRange("raidAreaCorruptionMaxCellsPerTick", 1500, 16, 20000);

            raidWaveIntervalTicks = b
                    .comment("苗床が敵対 Mob の群れ(過繫茂した Mob)を苗床の周囲へ湧かせる間隔(tick)。",
                            "0 でウェーブ無効。既定 1200 = 1 分。")
                    .defineInRange("raidWaveIntervalTicks", 1200, 0, 72000);

            raidWaveMinSize = b
                    .comment("1ウェーブで湧かせる Mob 数の下限。実際の数は下限〜上限でランダム。既定 10。")
                    .defineInRange("raidWaveMinSize", 10, 0, 256);

            raidWaveMaxSize = b
                    .comment("1ウェーブで湧かせる Mob 数の上限。既定 20。")
                    .defineInRange("raidWaveMaxSize", 20, 0, 256);

            raidWaveMaxNearby = b
                    .comment("この苗床のウェーブで湧き、今も生きている(読み込まれている)Mob の数がこれを超えたら、",
                            "そのウェーブはスキップ。攻城戦の報復増援の上限にも使う。既定 100。")
                    .defineInRange("raidWaveMaxNearby", 100, 0, 1000);

            raidWaveSpawnRadius = b
                    .comment("ウェーブ Mob を湧かせる、苗床からの水平半径(ブロック)。既定 24。")
                    .defineInRange("raidWaveSpawnRadius", 24, 8, 128);

            raidWavePlayerRadius = b
                    .comment("苗床からこの距離(ブロック)以内に、生きているサバイバル/アドベンチャーのプレイヤーが",
                            "1 人も居ない間は、ウェーブの時計(raidWaveIntervalTicks)を止める。戻ってきたら止めたところの",
                            "続きから数える(居なかった間のウェーブは出ない)。既定 128(2026-09-25 仮決め)。")
                    .defineInRange("raidWavePlayerRadius", 128, 16, 512);

            raidNexusSonicRange = b
                    .comment("この距離以内にプレイヤーが入ると、苗床がつぼみをその方へ曲げて衝撃波",
                            "(ウォーデンの衝撃波と同じ)を狙い始める(ブロック)。既定 80(以前は32)。")
                    .defineInRange("raidNexusSonicRange", 80, 4, 128);

            raidNexusSonicMinRange = b
                    .comment("この距離より近いと衝撃波は狙わない(近距離戦は薙ぎ払いに任せる、ブロック)。既定 8。")
                    .defineInRange("raidNexusSonicMinRange", 8, 0, 64);

            raidNexusSonicChargeTicks = b
                    .comment("衝撃波の溜め時間(tick)。既定 34(ウォーデンと同じ)。")
                    .defineInRange("raidNexusSonicChargeTicks", 34, 5, 200);

            raidNexusSonicCooldownTicks = b
                    .comment("衝撃波を放ってから(のけぞりを含め)次に狙い始めるまでのクールダウン(tick)。既定 300(15秒)。")
                    .defineInRange("raidNexusSonicCooldownTicks", 300, 20, 2400);

            raidNexusSonicDamage = b
                    .comment("衝撃波1発のダメージ。既定 12(ウォーデンは 10)。")
                    .defineInRange("raidNexusSonicDamage", 12.0D, 0.0D, 1000.0D);

            raidNexusSweepRange = b
                    .comment("この距離以内にプレイヤーが近づくと、苗床が巨体で薙ぎ払って引き剥がす(ブロック)。",
                            "既定 8(以前は2、その前は7)。")
                    .defineInRange("raidNexusSweepRange", 8, 2, 32);

            raidNexusSweepHitRange = b
                    .comment("薙ぎ払いが当たる、苗床からの水平半径(ブロック)。既定 8(以前は2、その前は11)。")
                    .defineInRange("raidNexusSweepHitRange", 8, 2, 48);

            raidNexusSweepDamage = b
                    .comment("薙ぎ払い(vine2・vine3)のダメージ。既定 10。")
                    .defineInRange("raidNexusSweepDamage", 10.0D, 0.0D, 1000.0D);

            raidNexusSweepKnockback = b
                    .comment("薙ぎ払いの水平ノックバックの強さ。既定 0.9(半分に調整)。")
                    .defineInRange("raidNexusSweepKnockback", 0.9D, 0.0D, 20.0D);

            raidNexusSweepCooldownTicks = b
                    .comment("薙ぎ払いのクールダウン(tick)。既定 120(6秒)。")
                    .defineInRange("raidNexusSweepCooldownTicks", 120, 10, 1200);

            raidNexusSporeTriggerRange = b
                    .comment("この距離以内にプレイヤーが近づくと、苗床本体(vine)が胞子を撒き散らす(ブロック)。既定 10。")
                    .defineInRange("raidNexusSporeTriggerRange", 10, 2, 64);

            raidNexusSporeMobTriggerRange = b
                    .comment("この距離以内にグループ1〜10いずれにも属さない Mob(=まだ寄生されていない野生の Mob)が",
                            "近づいても、苗床本体(vine)が胞子を撒き散らす(ブロック)。既定 15。")
                    .defineInRange("raidNexusSporeMobTriggerRange", 15, 2, 64);

            raidNexusSporeBurstRadius = b
                    .comment("胞子をばら撒く実際の範囲(苗床中心からの半径、ブロック)。ここに居た全員が対象。既定 14。")
                    .defineInRange("raidNexusSporeBurstRadius", 14, 2, 64);

            raidNexusSporeBurstCooldownTicks = b
                    .comment("胞子をばら撒いてから次に撒けるようになるまでのクールダウン(tick)。既定 100。")
                    .defineInRange("raidNexusSporeBurstCooldownTicks", 100, 10, 2400);

            b.pop();

            b.comment("OvergrownVegetation — 地割れ(フェーズ1開始演出)の設定",
                    "フェーズ0→1移行時、全プレイヤーからできる限り離れた地表で地震が起きる。",
                    "画面が揺れ始めるのと同時に土砂崩れ(地響き)の効果音が鳴り出し(実尺 15.6 秒)、",
                    "はじめの数秒は徐々に揺れながら地表に裂け目の輪郭が浮かぶだけ、そのあと巨大な裂け目が",
                    "中心から両端へ走り出し、効果音の鳴り終わりとちょうど同時に完成する。",
                    "裂け目の内側は汚染ブロックに変わり、中にあった陸地の水源・水流は焼失する(海・川は流れ込む)。",
                    "完成間際から 3 分間、裂け目から上空へ胞子が噴き出し続け、その後ゆっくり揺れが収まる。",
                    "噴出中は噴出点から半径数チャンクの Mob が確定で胞子感染し、周囲に汚染シダが湧く。").push("quake");

            quakeEnabled = b
                    .comment("フェーズ1開始時の地割れ・胞子噴出演出を有効にするか。")
                    .define("quakeEnabled", true);

            quakeDistanceMin = b
                    .comment("地割れの発生地点を、基準にする1人のプレイヤーからこの距離以上離す(ブロック)。既定 112(=7チャンク)。",
                            "実際の下限は max(この値, 裂け目の全長 ÷ 2 + 24) ── 長い裂け目の先端がプレイヤーに",
                            "届かないよう自動的に押し出される。")
                    .defineInRange("quakeDistanceMin", 112, 32, 4096);

            quakeDistanceMax = b
                    .comment("地割れの発生地点の、基準プレイヤーからの距離の上限(ブロック)。既定 192(=12チャンク)。",
                            "実際は min〜max の範囲で、かつ全プレイヤーからできる限り遠い方向が選ばれる。",
                            "下限が上がった場合(長い裂け目)は、上限も min+64 まで自動で引き上げられる。")
                    .defineInRange("quakeDistanceMax", 192, 48, 8192);

            // quakeForceLoadRadius(地割れの強制ロードの半径、既定 8 = 17x17)は 2026-09-25 に廃止(報告 1450)。
            // 掘削中は裂け目の足跡が掛かるチャンクだけを固定し(EarthquakeFissure#footprintChunks)、
            // 胞子噴出中は quakeEruptHazardRadiusChunks の正方形だけを固定する。

            quakeFissureMinLength = b
                    .comment("裂け目の長さ(長軸)の最小値(ブロック)。既定 112。")
                    .defineInRange("quakeFissureMinLength", 112, 4, 1024);

            quakeFissureMaxLength = b
                    .comment("裂け目の長さ(長軸)の最大値(ブロック)。既定 384。実際の長さは min〜max でランダム。",
                            "震央は「全長の半分 + 余白」だけ自動的にプレイヤーから離れるので、長くすると",
                            "地割れ全体がその分遠くで起きる(遠景の巨大な裂け目になる)。")
                    .defineInRange("quakeFissureMaxLength", 384, 4, 2048);

            quakeFissureMinWidth = b
                    .comment("裂け目の幅(短軸)の最小値(ブロック)。既定 20。",
                            "実効の幅はさらに位置ごとのノイズ(最大 ×1.7)で膨らむ。")
                    .defineInRange("quakeFissureMinWidth", 20, 2, 128);

            quakeFissureMaxWidth = b
                    .comment("裂け目の幅(短軸)の最大値(ブロック)。既定 46。実際の幅は min〜max でランダム。",
                            "実効の幅はさらに位置ごとのノイズ(最大 ×1.7)で膨らむ。")
                    .defineInRange("quakeFissureMaxWidth", 46, 2, 256);

            quakeFissureMinFloorY = b
                    .comment("裂け目の最深部が到達する Y 座標の「浅い側」。既定 -20。",
                            "小さめの裂け目のとき最深部がこの Y になる(縁へ向かって地表まで浅くなる)。")
                    .defineInRange("quakeFissureMinFloorY", -20, -64, 320);

            quakeFissureMaxFloorY = b
                    .comment("裂け目の最深部が到達する Y 座標の「深い側」。既定 -54。",
                            "大きめの裂け目のとき最深部がこの Y になる。実際の Y は裂け目のサイズに応じて",
                            "この 2 値の間。地表が高いほど掘り下げる量そのものは大きくなる。")
                    .defineInRange("quakeFissureMaxFloorY", -54, -64, 320);

            quakeConvertBiome = b
                    .comment("裂け目が完成したとき、裂け目が跨ぐチャンク(+ quakeSporeZoneChunks の範囲)を",
                            "過繁茂バイオームへ変換するか。既定 false(バイオームは変えず、ブロックの汚染だけ)。",
                            "true にすると即座に胞子濃度 100% の胞子感染ゾーンができる。")
                    .define("quakeConvertBiome", false);

            quakeSporeZoneChunks = b
                    .comment("quakeConvertBiome が true のとき、胞子噴出点を中心にこのチャンク半径ぶんも",
                            "過繁茂バイオームへ変換する。既定 4(= 9x9 チャンク)。0 で裂け目が跨ぐチャンクだけ。")
                    .defineInRange("quakeSporeZoneChunks", 4, 0, 32);

            quakePreShakeTicks = b
                    .comment("画面が揺れ始めて(= 効果音が鳴り出して)から、実際に地面が裂け始めるまでの",
                            "「徐々に揺れながら輪郭だけ浮かぶ」時間(tick)。既定 120 = 6 秒。",
                            "quakeFormTicks から引いた残りが、裂け目が走る時間になる。")
                    .defineInRange("quakePreShakeTicks", 120, 0, 12000);

            quakeFormTicks = b
                    .comment("画面が揺れ始める(= 土砂崩れ効果音の再生開始、演出開始と同時)から、裂け目が完成する",
                            "までの合計時間(tick)。既定 312 = 15.6 秒 = 効果音(earthquake_fissure.ogg)の実尺。",
                            "効果音の鳴り終わりと裂け目の完成がちょうど揃うよう、この値は効果音の実尺に合わせる。",
                            "内訳: 先頭 quakePreShakeTicks は予兆(輪郭のみ)、残りで裂け目が中心から両端へ走る。",
                            "この値を変えると効果音と裂け目の完成タイミングがずれるので注意。")
                    .defineInRange("quakeFormTicks", 312, 1, 24000);

            quakeEruptStartTicks = b
                    .comment("裂け目が走り始めて(予兆の後)から、胞子噴出エフェクトが出現するまでの時間(tick)。既定 120 = 6 秒。",
                            "残りの走行時間(quakeFormTicks - quakePreShakeTicks、既定 192)より大きい場合は形成完了と同時になる。")
                    .defineInRange("quakeEruptStartTicks", 120, 0, 24000);

            quakeFadeOutTicks = b
                    .comment("裂け目が完成してから、画面の揺れがゆっくり収まりきるまでの時間(tick)。既定 160 = 8 秒。")
                    .defineInRange("quakeFadeOutTicks", 160, 0, 12000);

            quakeEruptDurationTicks = b
                    .comment("胞子を噴出し続ける時間(tick)。既定 3600 = 3 分(噴出開始からの時間)。",
                            "この間、噴出点から quakeEruptHazardRadiusChunks の範囲は強制ロードされたままになる。")
                    .defineInRange("quakeEruptDurationTicks", 3600, 0, 240000);

            quakeEruptIntervalTicks = b
                    .comment("胞子噴出パーティクルを出す間隔(tick)。小さいほど噴出が濃く見えるが負荷も上がる。既定 3。")
                    .defineInRange("quakeEruptIntervalTicks", 3, 1, 200);

            quakeEruptHazardRadiusChunks = b
                    .comment("胞子噴出中、噴出エフェクトの中心からこのチャンク半径以内(水平)にいる Mob を",
                            "一定間隔で確定「胞子感染1」にし、同じ範囲のランダムな地表に汚染シダ(水中なら汚染水草)を",
                            "湧かせる。既定 3(= 半径 48 ブロック)。0 でこのハザードを無効化。")
                    .defineInRange("quakeEruptHazardRadiusChunks", 3, 0, 16);

            quakeEruptInfectionIntervalTicks = b
                    .comment("噴出点周囲の Mob を確定感染させる判定の実行間隔(tick)。既定 40 = 2 秒。")
                    .defineInRange("quakeEruptInfectionIntervalTicks", 40, 1, 1200);

            quakeEruptFernIntervalTicks = b
                    .comment("噴出点周囲にランダムな汚染シダを湧かせる判定の実行間隔(tick)。既定 80 = 4 秒。")
                    .defineInRange("quakeEruptFernIntervalTicks", 80, 1, 2400);

            quakeEruptFernAttemptsPerInterval = b
                    .comment("上記1回あたりの汚染シダ出現の試行回数。既定 6。")
                    .defineInRange("quakeEruptFernAttemptsPerInterval", 6, 1, 64);

            quakeEruptFernMaxNearby = b
                    .comment("ハザード半径内の汚染シダがこの数に達したら、それ以上は湧かせない(積み上がり防止)。既定 48。")
                    .defineInRange("quakeEruptFernMaxNearby", 48, 0, 4000);

            quakeSporeSpeed = b
                    .comment("胞子噴出の初速(ブロック/tick)。噴出の瞬間の速さ。既定 6.5。",
                            "この速さで裂け目の底から飛び出し、摩擦で減速しながら地表を越えて上空まで届く。")
                    .defineInRange("quakeSporeSpeed", 6.5D, 0.5D, 30.0D);

            quakeSporeFriction = b
                    .comment("胞子パーティクルの毎 tick の速度倍率(摩擦)。既定 0.955。",
                            "1 に近いほど長く伸び、小さいほど急に減速する。")
                    .defineInRange("quakeSporeFriction", 0.955D, 0.80D, 0.999D);

            quakeSporeGravity = b
                    .comment("胞子パーティクルに掛かる重力(ブロック/tick^2)。既定 0.006。徐々に落ちてくる強さ。")
                    .defineInRange("quakeSporeGravity", 0.006D, 0.0D, 0.2D);

            quakeSporeConeDeg = b
                    .comment("胞子噴出の円錐の半角(度)。既定 34。大きいほど横に広く散る。")
                    .defineInRange("quakeSporeConeDeg", 34.0D, 0.0D, 89.0D);

            quakeSporeSize = b
                    .comment("胞子パーティクル 1 粒の大きさ(ブロック目安)。既定 0.35。",
                            "大きいほど遠くからでも噴出の柱が見える。近くで見て粒が大きすぎるなら下げる。")
                    .defineInRange("quakeSporeSize", 0.35D, 0.05D, 5.0D);

            quakeSporeRiseFade = b
                    .comment("胞子パーティクルが噴出点から何ブロック上昇すると完全に透明になるか。既定 180。",
                            "噴出点(裂け目の底)からの上昇量に応じて線形に薄くなり、上に行くほど消えていく。",
                            "小さくすると地表付近で消え、大きくすると空高くまで見える。")
                    .defineInRange("quakeSporeRiseFade", 180.0D, 8.0D, 2000.0D);

            quakeEruptionCorruptionMultiplier = b
                    .comment("胞子噴出エフェクトが出ている間(噴出開始〜quakeEruptDurationTicks)、汚染シダ・汚染死体などの",
                            "侵食速度(1サイクルの置き換えブロック数)に掛かる倍率。既定 5.0。1.0 で効果なし。",
                            "corruptionSpeedMultiplier とは別枠で、この期間だけ上乗せで掛かる。")
                    .defineInRange("quakeEruptionCorruptionMultiplier", 5.0D, 0.0D, 100.0D);

            quakeSporeDensity = b
                    .comment("胞子噴出パーティクルの量(密度)の倍率。既定 1.0。",
                            "大きいほど噴出が濃くなる。上げすぎるとパーティクル数の上限に達して",
                            "クライアントの FPS が落ちたり古い粒が消えたりする(目安 3.0 くらいまで)。")
                    .defineInRange("quakeSporeDensity", 1.0D, 0.1D, 10.0D);

            quakeShakeIntensity = b
                    .comment("(クライアント描画)画面の揺れの最大強度。0 で揺れなし、既定 1.0。")
                    .defineInRange("quakeShakeIntensity", 1.0D, 0.0D, 4.0D);

            quakeShakeRadius = b
                    .comment("(クライアント描画)震央からこの距離(ブロック)離れるほど画面の揺れが弱まる。",
                            "ただし下限があるので、遠くのプレイヤーにも地震は伝わる。既定 512。")
                    .defineInRange("quakeShakeRadius", 512, 32, 8192);

            b.pop();

            b.comment("OvergrownVegetation — 攻城戦(超大型レイドボス)の設定",
                    "プレイヤーからはるか遠くを周回し続ける巨大なボス。大砲で遠距離から削る想定。",
                    "このボスはエンティティではなく「軌道パラメータ + 体力」だけのデータとして扱われ、",
                    "位置はサーバーとクライアントが同じ式から計算する。そのためチャンクの読み込み状況や",
                    "エンティティ同期距離(サーバーの view-distance)に一切左右されず、負荷もほぼゼロ。",
                    "開始は /ov siege start(円の中心 = 実行時のプレイヤー位置で固定)。").push("siege");

            siegeEnabled = b
                    .comment("攻城戦を有効にするか。false にすると進行中のものも終了する。")
                    .define("siegeEnabled", true);

            siegeRadius = b
                    .comment("ボスが周回する円の半径(ブロック)。中心は攻城戦開始時のプレイヤー位置で固定され、",
                            "以後プレイヤーを追いかけない。既定 1025。")
                    .defineInRange("siegeRadius", 1025, 64, 16384);

            siegeAltitude = b
                    .comment("ボスが飛ぶ絶対 Y 座標の基準値(実際にはここから上下に ±15 ほど揺れる)。",
                            "遠方のチャンクは未ロードなので地形の高さは参照できない — 地形より確実に高い値にすること。",
                            "既定 210。")
                    .defineInRange("siegeAltitude", 210, -64, 1024);

            siegeSpeed = b
                    .comment("周回の速さ(ブロック/tick)。半径 1025・速さ 0.08 で 1 周およそ 67 分 ——",
                            "1 戦のあいだに空をゆっくり移動していくが、一巡はしない見え方になる。",
                            "1 戦で一巡させたいなら 0.5〜1.0 程度まで上げる。既定 0.08。")
                    .defineInRange("siegeSpeed", 0.08D, 0.01D, 20.0D);

            siegeHealth = b
                    .comment("ボスの最大体力。既定 18000。バニラの最大体力属性は 1024 が上限だが、この MOD は",
                            "RangedAttributeMixin でその上限を外している(ハイヴノード等も同じ上限に引っかかっていた)。",
                            "18000 でも 1024 を超えているので、この mixin は今も必要。")
                    .defineInRange("siegeHealth", 18000.0D, 1.0D, 1.0E9D);

            siegeArmor = b
                    .comment("ボスの防御値。バニラの防具値は 30(= 80% 軽減)が上限で大きな値を指定できないため、",
                            "ここは<しきい値>として働く: 防具貫通(bypasses_armor)でないダメージは、この値を差し引いた",
                            "残りだけが通る(40 未満のダメージは 0)。既定 40 —— 剣・矢はまず届かないので、",
                            "ボスを削る手段は実質的に砲台だけ。ただし 40 は TNT の至近爆発などでは超えうる値なので、",
                            "10000 の頃と違って「絶対に通らない」わけではない。砲弾はこの値を超える威力にすること。")
                    .defineInRange("siegeArmor", 40.0D, 0.0D, 1.0E9D);

            siegeApproachLaps = b
                    .comment("ボスは回りながら少しずつ中心へ寄ってくる(2026-09-13 追加)。",
                            "<b>開始半径から siegeMinRadius まで寄り切るのに、何周ぶん回るか</b>。既定 2.5 周。",
                            "0 にすると同じ半径を回り続ける(従来の挙動)。",
                            "接線速度(siegeSpeed)は変わらないので、寄るほど 1 周は速くなる。",
                            "<b>周回数は速さに依らない</b> — siegeSpeed を上げると螺旋の形はそのままに、",
                            "掛かる時間だけが短くなる。既定(半径 1025 / 速さ 0.08 / 下限 128 / 2.5 周)では",
                            "半径 512(近距離モードに入る距離)まで<b>約 40 分・0.83 周</b>、下限まで<b>約 70 分</b>。",
                            "siegeSpeed を 0.32 にすれば同じ 2.5 周を約 18 分で回る。")
                    .defineInRange("siegeApproachLaps", 2.5D, 0.0D, 1000.0D);

            siegeMinRadius = b
                    .comment("これ以上は中心へ寄らない周回半径(ブロック)。既定 128。",
                            "0 に近づけると中心で角速度が発散する(半径 0 で回ろうとする)ので下限を置いてある。",
                            "既定値なら、寄り切ったあとは真上あたりを 8 分ほどで 1 周する。")
                    .defineInRange("siegeMinRadius", 128.0D, 1.0D, 4096.0D);

            siegeScale = b
                    .comment("ボスのモデル倍率。ドルガ=アーケロウス・ティタニカのモデルは倍率 1 で全長およそ 24.6 ブロック、",
                            "既定 25 で全長およそ 615 ブロック(ヒレを含む幅はおよそ 155 ブロック)。",
                            "当たり判定(本体 + パーツ 28 個)・触手の先端・花の位置もこの倍率で決まる。",
                            "見かけの大きさは「全長 ÷ 距離」で決まるので、半径を変えたらこちらも合わせて調整する。",
                            "既定値(半径 1025・高度 210)では視角およそ 33 度。")
                    .defineInRange("siegeScale", 25.0D, 0.1D, 512.0D);

            siegeSyncIntervalTicks = b
                    .comment("ボスの状態(軌道・経過 tick・体力)を全プレイヤーへ再送する間隔(tick)。",
                            "座標そのものは送らないので、これを短くしても帯域はほとんど増えない。既定 20 = 1 秒。")
                    .defineInRange("siegeSyncIntervalTicks", 20, 1, 200);

            siegeCloseRange = b
                    .comment("プレイヤーがボスの胴の中心にこの距離まで近づくと、ボスが「データ」から「実エンティティ」へ",
                            "置き換わる(ブロック)。実エンティティは遠距離時とまったく同じ軌道の式で動くので、",
                            "切り替わりの瞬間に位置は飛ばない。ボスは全長 700 ブロック超なので、頭や尾の下に立った",
                            "プレイヤーからも胴の中心までは数百ブロックある — その状態でも砲弾が当たるよう、",
                            "半分の全長より大きめにしておく。実エンティティが居るあいだは胴の中心の周り 3×3 チャンクを",
                            "強制ロードする(エンティティはそのチャンクが無いと存在できないため)。既定 512。",
                            "離れて元に戻るのは この距離 × 1.35 を超えたとき(境界での出し入れのばたつき防止)。")
                    .defineInRange("siegeCloseRange", 512, 16, 4096);

            siegeHitboxWidth = b
                    .comment("本体(胴の中心の 1 節ぶん)の当たり判定の幅。<モデル単位>で指定し(胴の太さ = 16)、",
                            "siegeScale に合わせて自動で拡大する(16 × 30 / 16 = 30 ブロック)。",
                            "頭・首・胴・尾の全体は本体とは別の当たり判定パーツ 28 個(エンダードラゴンと同じマルチパート)が",
                            "体に沿って覆うので、ここを大きくする必要はない。既定 16。")
                    .defineInRange("siegeHitboxWidth", 16.0D, 1.0D, 256.0D);

            siegeHitboxHeight = b
                    .comment("本体の当たり判定の高さ(モデル単位、siegeScale で自動拡大)。既定 16。")
                    .defineInRange("siegeHitboxHeight", 16.0D, 1.0D, 256.0D);

            siegeBombIntervalTicks = b
                    .comment("爆撃の間隔(tick)。ボスが真下へ爆弾をばら撒く。0 にすると爆撃しない。既定 80 = 4 秒。",
                            "爆弾はボスの居る場所がロード済みのとき(= プレイヤーが近くに居るとき)だけ湧く。")
                    .defineInRange("siegeBombIntervalTicks", 80, 0, 12000);

            siegeBombCount = b
                    .comment("1 回の爆撃で落とす爆弾の数。機体に沿って一直線にばら撒かれる。既定 5。")
                    .defineInRange("siegeBombCount", 5, 1, 64);

            siegeBombDropOffset = b
                    .comment("爆弾を落とす位置の、触手の先端からさらに下へのオフセット(ブロック)。",
                            "爆弾は腹側の触手の先端(モデルから計算)から落ちるので、通常は小さな値でよい。既定 2。")
                    .defineInRange("siegeBombDropOffset", 2.0D, 0.0D, 128.0D);

            siegeBombPower = b
                    .comment("爆弾の爆発の強さ(TNT = 4.0)。エンティティへのダメージとノックバック、演出だけが起き、",
                            "ブロックは一切壊れない(破壊したい場合は下の siegeBombCraterRadius)。既定 3.0。")
                    .defineInRange("siegeBombPower", 3.0D, 0.0D, 20.0D);

            siegeBombCraterRadius = b
                    .comment("爆弾がえぐるクレーターの半径(ブロック)。既定 0 = 地形破壊を一切行わない。",
                            "1 以上にすると、この半径の球の中で「爆発耐性が siegeBombCraterMaxResistance 未満の",
                            "ブロックだけ」を壊す(液体・空気・岩盤などは常に対象外)。大きくしすぎると地形が荒れる。")
                    .defineInRange("siegeBombCraterRadius", 0, 0, 8);

            siegeBombCraterMaxResistance = b
                    .comment("クレーターで壊す対象の爆発耐性の上限。石は 6、土・草・砂は 0.5、葉は 0.2。",
                            "既定 3.0 なら土・砂・草・葉・草花などしか消えず、石やブロック建築は残る。")
                    .defineInRange("siegeBombCraterMaxResistance", 3.0D, 0.0D, 1200.0D);

            siegeBombScale = b
                    .comment("(描画)爆弾(胞子の塊)のモデル倍率。1 で直径およそ 1 ブロック。",
                            "全長 600 ブロック超のボスから落ちてくるものなので、小さすぎると見えない。既定 6.0。")
                    .defineInRange("siegeBombScale", 6.0D, 0.1D, 32.0D);

            siegeBombCorruptionRadius = b
                    .comment("爆弾の着弾点を中心に、地表を円形に汚染する半径(ブロック)。過繫茂したクリーパーの爆発と",
                            "同じ変換(CorruptionRegistry)で、地表の面だけを深さ 3 ブロックまで汚染する。0 で汚染しない。",
                            "既定 6.5 = 直径 13。大きくしすぎると 4 秒ごとの爆撃のたびに大量のブロック更新とバイオーム変換が走り、",
                            "クライアントのチャンク再構築で FPS が落ちる(半径 32 で顕著だった)。")
                    .defineInRange("siegeBombCorruptionRadius", 6.5D, 0.0D, 128.0D);

            siegeBombCorruptionNoise = b
                    .comment("爆弾の汚染の輪郭の波打ち(ブロック)。0 で真円、大きいほど輪郭がいびつになる。既定 1.5。")
                    .defineInRange("siegeBombCorruptionNoise", 1.5D, 0.0D, 32.0D);

            siegeBeamIntervalTicks = b
                    .comment("頭の先の花から放つ巨大ビーム(遠距離砲撃)の間隔(tick)。前の照射が終わってから次の溜めが",
                            "始まるまでの時間。迫撃砲のように、かなり離れたプレイヤーの足元へ定期的に撃ち込む。",
                            "0 で撃たない。既定 1200 = 60 秒。")
                    .defineInRange("siegeBeamIntervalTicks", 1200, 0, 72000);

            siegeBeamChargeTicks = b
                    .comment("花のビームの溜め(予告)の長さ(tick)。このあいだ首が標的へ向き、花が開き、着弾点に光の柱が立つ。",
                            "= 警告が出てから実際に撃たれるまでの時間。既定 200 = 10 秒。短くするほど避けにくくなる。")
                    .defineInRange("siegeBeamChargeTicks", 200, 10, 1200);

            siegeBeamFireTicks = b
                    .comment("花のビームの照射の長さ(tick)。既定 126 = 6.3 秒。")
                    .defineInRange("siegeBeamFireTicks", 126, 1, 1200);

            siegeBeamRecoverTicks = b
                    .comment("照射後、花が閉じるまでの長さ(tick)。既定 40。")
                    .defineInRange("siegeBeamRecoverTicks", 40, 0, 400);

            siegeBeamMinRange = b
                    .comment("花のビームの標的にするプレイヤーの、ボス(胴の中心)からの最小距離(ブロック)。",
                            "近距離モード(誰かが siegeCloseRange 以内に居て実エンティティが存在する)のあいだは、この値に",
                            "関係なく花のビームは撃たない。既定 0 = 近距離モードでない限り誰でも狙う。")
                    .defineInRange("siegeBeamMinRange", 0, 0, 8192);

            siegeBeamScatter = b
                    .comment("花のビームの着弾点の、標的プレイヤーの位置からのばらつき(半径・ブロック)。",
                            "0 で必ず足元に落ちる。既定 6。")
                    .defineInRange("siegeBeamScatter", 6.0D, 0.0D, 64.0D);

            siegeBeamRadius = b
                    .comment("花のビームは花では細く、離れるほど太くなる円錐。この値は花から siegeBeamGrowDistance",
                            "離れた地点での半径(ブロック)で、その先も同じ割合で太くなり続ける(上限なし)。",
                            "着弾のダメージ半径 = 着弾点での光線の太さ(中心で最大ダメージ、縁で半分)。",
                            "既定 50: 800 ブロック先で半径 50、1000 ブロック先で半径 65。")
                    .defineInRange("siegeBeamRadius", 50.0D, 1.0D, 512.0D);

            siegeBeamStartRadius = b
                    .comment("花のビームの発射点(花)での半径(ブロック)。既定 3。")
                    .defineInRange("siegeBeamStartRadius", 3.0D, 0.1D, 128.0D);

            siegeBeamGrowDistance = b
                    .comment("花のビームが siegeBeamRadius の太さに達するまでの距離(ブロック)。既定 800。")
                    .defineInRange("siegeBeamGrowDistance", 800, 1, 8192);

            siegeBeamOvershoot = b
                    .comment("花のビームが狙った地点で止まらず、その先へ突き進む距離(ブロック)。描画・ダメージ判定とも。",
                            "地面に刺さった先は地形に隠れる。既定 512。")
                    .defineInRange("siegeBeamOvershoot", 512, 0, 4096);

            siegeBeamDamage = b
                    .comment("花のビームのダメージ(<毎 tick>、光線の中に居る生物すべて)。<b>防具は貫通しない</b>",
                            "(2026-09-13 に siege_beam から bypasses_armor を外した)。無敵時間は無視して毎 tick 入る。既定 100。")
                    .defineInRange("siegeBeamDamage", 100.0D, 0.0D, 100000.0D);

            siegeBeamPower = b
                    .comment("花のビームの着弾の爆発の強さ(TNT = 4.0)。演出とノックバック用で、ブロックは壊れない。既定 5。")
                    .defineInRange("siegeBeamPower", 5.0D, 0.0D, 20.0D);

            siegeBeamCraterRadius = b
                    .comment("花のビームの着弾がえぐるクレーターの半径(ブロック)。既定 0 = 地形破壊を一切行わない。",
                            "1 以上にすると爆弾と同じ規則(siegeBombCraterMaxResistance 未満の柔らかいブロックだけ)で削る。")
                    .defineInRange("siegeBeamCraterRadius", 0, 0, 12);

            siegeTentacleBeamIntervalTicks = b
                    .comment("腹側の触手から近くのプレイヤーへ撃つ細いビームの間隔(tick)。実エンティティが居る",
                            "(= プレイヤーが siegeCloseRange 以内に居る)あいだだけ撃つ。0 で撃たない。既定 70。")
                    .defineInRange("siegeTentacleBeamIntervalTicks", 70, 0, 12000);

            siegeTentacleBeamCount = b
                    .comment("触手ビーム 1 斉射で撃つ本数。既定 8 — 別々の触手から、それぞれ<b>別々の相手</b>へ 1 本ずつ。",
                            "狙える相手がそれより少なければ同じ相手に重なる。",
                            "同時ではなく siegeTentacleBeamShotIntervalTicks おきに<b>1 本ずつ</b>走る。")
                    .defineInRange("siegeTentacleBeamCount", 8, 1, 18);

            siegeTentacleBeamChargeTicks = b
                    .comment("触手ビームの溜めの長さ(tick)= プレイヤーの位置を掴んでから撃つまでのディレイ。",
                            "判定線は掴んだ時点の位置に固定されるので、このあいだに走れば避けられる。既定 10 = 0.5 秒。")
                    .defineInRange("siegeTentacleBeamChargeTicks", 10, 1, 400);

            siegeTentacleBeamFireTicks = b
                    .comment("触手ビーム<b>1 本ごと</b>の見えている長さ(tick)。ダメージは撃った瞬間に 1 回だけ入り、",
                            "そのあとこの時間をかけて細く薄くなりながら消える。既定 12。")
                    .defineInRange("siegeTentacleBeamFireTicks", 12, 1, 400);

            siegeTentacleBeamShotIntervalTicks = b
                    .comment("触手ビームの斉射で、<b>1 本ずつ撃つ間隔</b>(tick)。既定 3 —— 小ビームの乱射と同じく、",
                            "同時にどんと出すのではなく次々に走らせる(ユーザー指示 2026-09-13)。",
                            "8 本 × 3 tick = 1 斉射に 24 tick 掛かる。")
                    .defineInRange("siegeTentacleBeamShotIntervalTicks", 3, 1, 100);

            siegeTentacleBeamDamage = b
                    .comment("触手ビームのダメージ。<b>防具は貫通しない</b>(2026-09-13 に貫通をやめた)。",
                            "既定 38 = ハート 19 個 — 生身なら即死、防具があれば軽減される。")
                    .defineInRange("siegeTentacleBeamDamage", 38.0D, 0.0D, 1000.0D);

            siegeTentacleBeamRadius = b
                    .comment("触手ビームの太さ。線からこの距離(ブロック)以内の生物に当たる。既定 3。")
                    .defineInRange("siegeTentacleBeamRadius", 3.0D, 0.1D, 32.0D);

            siegeTentacleBeamOvershoot = b
                    .comment("触手ビームが狙点を<b>通り越して</b>伸びる長さ(ブロック)。頭の花のビームと同じ考え方で、",
                            "標的の居た場所で止まらずそのまま突き進み、線上の生物を無差別に薙ぐ。既定 512。")
                    .defineInRange("siegeTentacleBeamOvershoot", 512, 0, 4096);

            siegeNeckFollowSpeed = b
                    .comment("首が標的へ向くときの追従の速さ（1 tick あたりに詰める残差の割合）。既定 0.10。")
                    .defineInRange("siegeNeckFollowSpeed", 0.10D, 0.001D, 1.0D);

            siegeNeckReturnSpeed = b
                    .comment("ビームが終わったあと首が正面へ戻る速さ（1 tick あたりに詰める残差の割合）。",
                            "既定 0.012 → 時定数およそ 4 秒、振り切った首が正面へ戻るまで 20 秒ほど。")
                    .defineInRange("siegeNeckReturnSpeed", 0.012D, 0.0005D, 1.0D);

            b.comment("胞子爆弾の曲射 — 頭のビームを撃っていないあいだ、触手の先から胞子爆弾を",
                    "プレイヤーのいる辺りへ山なりに投げ上げる。砲台の砲弾と同じく「発射位置・初速・発射時刻」だけの",
                    "データで飛ぶので、弾道はプレイヤー側からもはっきり見える。狙いはわざと粗い。").push("mortar");

            siegeMortarIntervalTicks = b
                    .comment("胞子爆弾を曲射する間隔(tick)。頭のビームを撃っているあいだは撃たない。0 で撃たない。",
                            "既定 200 = 10 秒ごとに 1 斉射。")
                    .defineInRange("siegeMortarIntervalTicks", 200, 0, 12000);

            siegeMortarCount = b
                    .comment("1 斉射で撃つ胞子爆弾の数。別々の触手から撃つ。既定 3。")
                    .defineInRange("siegeMortarCount", 3, 1, 32);

            siegeMortarRange = b
                    .comment("曲射で狙う相手を探す距離(ブロック)。既定 4096 = 実質どこでも。")
                    .defineInRange("siegeMortarRange", 4096, 16, 16384);

            siegeMortarScatter = b
                    .comment("狙点のばらつき(ブロック)。プレイヤーを中心にこの半径の円のどこかを狙う(円内一様)。",
                            "直撃をまれにするための主な仕掛け。既定 45 = 平均して 30 ブロックほど外す",
                            "(ユーザー指定 2026-09-17 に 14 → 45。反撃 siegeRetaliationScatter と同じ広さにして、",
                            "「プレイヤーの居場所ではなく、そのまわり一帯に降ってくる」挙動に揃えた)。")
                    .defineInRange("siegeMortarScatter", 45.0D, 0.0D, 256.0D);

            siegeMortarSpeed = b
                    .comment("胞子爆弾の水平速度(ブロック/tick)。飛翔時間 = 水平距離 / この値なので、",
                            "小さいほど長く飛んで高く上がる。既定 3.5 → 1000 ブロック先まで 14 秒。")
                    .defineInRange("siegeMortarSpeed", 3.5D, 0.5D, 40.0D);

            siegeMortarMinFlightTicks = b
                    .comment("胞子爆弾の最短の飛翔時間(tick)。近くへ撃つときも山なりにするための下限。既定 70。")
                    .defineInRange("siegeMortarMinFlightTicks", 70, 1, 1200);

            siegeMortarGravity = b
                    .comment("胞子爆弾にかかる重力(ブロック/tick²)。弾道の高さ = 重力 × 飛翔時間² / 8 なので、",
                            "大きいほど山なり。既定 0.03 → 1000 ブロック先を狙うと 300 ブロックほど上を通る。")
                    .defineInRange("siegeMortarGravity", 0.03D, 0.001D, 1.0D);

            siegeMortarJitter = b
                    .comment("初速に乗せる誤差(ブロック/tick)。飛翔時間ぶん増幅されるので遠いほど散る。",
                            "既定 0.004 → 14 秒の弾着で 1 ブロックほど。")
                    .defineInRange("siegeMortarJitter", 0.004D, 0.0D, 1.0D);

            siegeMortarDirectDamage = b
                    .comment("胞子爆弾が生物に直撃したときのダメージ(着弾の爆発 siegeBombPower とは別)。既定 18。")
                    .defineInRange("siegeMortarDirectDamage", 18.0D, 0.0D, 10000.0D);

            siegeMortarScale = b
                    .comment("飛んでいる胞子爆弾の描画の大きさ。既定 6.0 = 落としてくる爆弾(siegeBombScale)と同じ。",
                            "煙の尾の太さもこれに比例するので、下げすぎると 1000 ブロック先からの弾道が見えなくなる。")
                    .defineInRange("siegeMortarScale", 6.0D, 0.1D, 32.0D);

            b.pop();

            b.comment("砲撃への反撃 — 砲台の砲弾が命中すると、ボスは撃ってきた射手の<b>まわり一帯</b>へ",
                    "胞子爆弾を斉射 3 回連続で撃ち返す(居場所そのものは狙わない。siegeRetaliationScatter)。",
                    "この反撃で撃った胞子爆弾<b>だけ</b>は、着弾地点にグループ4の Mob を湧かせる",
                    "(8 割がアルボラ)。撃ち続ける砲台は増援に囲まれるので、「撃ったら守りも要る」という圧になる。").push("retaliation");

            siegeRetaliationShots = b
                    .comment("砲弾が命中したときに撃ち返す胞子爆弾の<b>斉射</b>の回数。0 で反撃しない。",
                            "既定 3 = 斉射を 3 回連続。")
                    .defineInRange("siegeRetaliationShots", 3, 0, 32);

            siegeRetaliationBombs = b
                    .comment("1 斉射で撃つ胞子爆弾の数(通常の曲射の siegeMortarCount にあたる)。既定 3 =",
                            "普段の斉射と同じ本数。既定では 3 発 × 3 回 = 9 発が飛び、",
                            "増援は着弾 1 発ごとに湧くので合わせて 27 体になる。多すぎるならここを下げる。")
                    .defineInRange("siegeRetaliationBombs", 3, 1, 32);

            siegeRetaliationGapTicks = b
                    .comment("斉射と斉射の間隔(tick)。既定 6 = 0.3 秒おきに 3 斉射。")
                    .defineInRange("siegeRetaliationGapTicks", 6, 1, 200);

            siegeRetaliationScatter = b
                    .comment("反撃の狙点のばらつき(ブロック)。撃ってきたプレイヤーを中心にこの半径の円のどこかへ",
                            "1 発ずつ落ちる(円内一様)。既定 45 —— <b>プレイヤーの居る場所そのものは狙わない</b>",
                            "(ユーザー指定 2026-09-17)。直撃で潰すのではなく、周り一帯に増援をばら撒くための値。",
                            "普段の曲射の siegeMortarScatter(14)とは別に持っている。")
                    .defineInRange("siegeRetaliationScatter", 45.0D, 0.0D, 256.0D);

            siegeRetaliationSpawnCount = b
                    .comment("反撃の胞子爆弾 1 発の着弾地点に湧くグループ4の Mob の数。0 で湧かない。既定 3。",
                            "内訳は 8 割がアルボラで、残りをスポロフィル・フィロセンス・ヴェロフィル・",
                            "過繁茂したスカトゥリウムで分ける(SporeBombReinforcements の重み表)。")
                    .defineInRange("siegeRetaliationSpawnCount", 3, 0, 32);

            siegeRetaliationCooldownTicks = b
                    .comment("斉射 3 回を撃ち終えてから、次の反撃を受け付けるまでの時間(tick)。既定 800 = 40 秒。",
                            "これが無いと砲弾 1 発ごとに反撃して、増援が無限に湧く。砲台のリロード",
                            "(cannonReloadTicks 840 = 42 秒)とほぼ同じなので、1 発撃つごとに 1 回返ってくる。")
                    .defineInRange("siegeRetaliationCooldownTicks", 800, 0, 24000);

            b.pop();

            b.comment("触手の小ビームの乱射 — 頭のビームを撃っていないあいだ、腹側の触手から細いビームを",
                    "「プレイヤーがいると思われる方角」へ撃ち散らす。狙いは始めた時点の位置に固定され、",
                    "1 発ごとに大きくばらつくので、当たるかどうかは運次第。展開した遮蔽盾の裏では防げる。").push("barrage");

            siegeBarrageIntervalTicks = b
                    .comment("遠距離の相手に対して乱射を始める間隔(tick)。0 で乱射しない。既定 40 = 2 秒ごと。",
                            "<b>前の乱射が終わってから</b>数えるので、1 回 6 秒 + 2 秒の間 = ほぼ降りっぱなしになる。",
                            "実際には 0.55〜1.45 倍に散らすので、22〜58 tick のあいだで揺れる。")
                    .defineInRange("siegeBarrageIntervalTicks", 40, 0, 24000);

            siegeBarrageCloseIntervalTicks = b
                    .comment("<b>近距離モード</b>(ボスの下や近くにプレイヤーが居て実エンティティが湧いている状態)",
                            "のときの乱射の間隔(tick)。既定 80 = 4 秒。遠距離の siegeBarrageIntervalTicks と違い、",
                            "こちらは<b>散らさず指定どおりちょうど</b>の間隔で来る(近くに居るあいだの決め打ちの圧)。")
                    .defineInRange("siegeBarrageCloseIntervalTicks", 80, 0, 24000);

            siegeBarrageDurationTicks = b
                    .comment("乱射 1 回の長さ(tick)。既定 120 = 6 秒。")
                    .defineInRange("siegeBarrageDurationTicks", 120, 1, 2400);

            siegeBarrageShotIntervalTicks = b
                    .comment("乱射中、次の発射までの間隔(tick)。既定 1 → 6 秒間で 120 発。",
                            "2026-09-13 に 3 → 1(撃っている時間はそのままに密度を上げた)。")
                    .defineInRange("siegeBarrageShotIntervalTicks", 1, 1, 100);

            siegeBarrageShotsPerStep = b
                    .comment("乱射の 1 回の発射で<b>同時に</b>撃つ本数。既定 1。",
                            "まだ薄いと感じたらここを上げる —— 2 にすれば密度が倍になる。",
                            "<b>ただし 1 発ごとに地形の判定とエンティティ検索が走る</b>ので、上げるほどサーバーが重くなる",
                            "(既定でも 6 秒間に 120 回走っている)。")
                    .defineInRange("siegeBarrageShotsPerStep", 1, 1, 16);

            siegeBarrageRange = b
                    .comment("乱射の相手を探す距離(ブロック)。既定 4096 = 実質どこでも。")
                    .defineInRange("siegeBarrageRange", 4096, 16, 16384);

            siegeBarrageScatter = b
                    .comment("乱射の狙いのばらつき(ブロック)。狙点を中心にこの半径の球のどこかへ撃つ",
                            "(上下は半分)。既定 26 — 当てに来ているのではなく、その辺りを薙いでいる見た目。")
                    .defineInRange("siegeBarrageScatter", 26.0D, 0.0D, 256.0D);

            siegeBarrageDamage = b
                    .comment("乱射の小ビーム 1 発のダメージ。<b>防具は貫通しない</b>(2026-09-13 に貫通をやめた)。",
                            "既定 38 = ハート 19 個 — 触手ビームと同じ。当たれば生身は即死。")
                    .defineInRange("siegeBarrageDamage", 38.0D, 0.0D, 1000.0D);

            siegeBarrageRadius = b
                    .comment("乱射の小ビームの太さ。線からこの距離(ブロック)以内の生物に当たる。既定 1.6。")
                    .defineInRange("siegeBarrageRadius", 1.6D, 0.1D, 32.0D);

            siegeBarrageOvershoot = b
                    .comment("乱射の小ビームが狙点を<b>通り越して</b>伸びる長さ(ブロック)。頭の花のビームと同じ考え方で、",
                            "狙った辺りで止まらずそのまま突き進む。既定 512。")
                    .defineInRange("siegeBarrageOvershoot", 512, 0, 4096);

            b.pop();

            siegeTargetMobRadius = b
                    .comment("<b>近距離の 2 つ</b>(触手の精密ビーム・小ビームの乱射)が「プレイヤー以外の生物」を",
                            "標的に選ぶときの探索半径(ブロック)。プレイヤーの周りだけを探すのは、",
                            "チャンクが読み込まれているのがそこだけだから(ボスの真下でもプレイヤーが",
                            "居なければ、そもそも生物は居ない)。既定 48。",
                            "<b>遠距離用の頭の花のビームと胞子爆弾の曲射はプレイヤーしか狙わない</b>のでこの値に関係しない。")
                    .defineInRange("siegeTargetMobRadius", 48, 0, 256);

            siegeMobTargetChance = b
                    .comment("<b>小ビームの乱射</b>が、プレイヤーではなく<b>周りの Mob</b> を狙う確率。",
                            "0 でプレイヤーしか狙わない、1 で Mob が居れば必ず Mob を狙う。既定 0.5。",
                            "触手の精密ビームは 1 斉射で 8 本撃つので確率ではなく、",
                            "<b>プレイヤーと Mob を全部混ぜて別々の相手へ振り分ける</b>(この値に関係しない)。")
                    .defineInRange("siegeMobTargetChance", 0.5D, 0.0D, 1.0D);

            siegeCryCooldownTicks = b
                    .comment("ボスが被弾の悲鳴を上げる最短の間隔(tick)。連続で撃たれても鳴りっぱなしにしない。既定 50。",
                            "悲鳴は 1 回で 2 秒ほど尾を引くので、これより短くすると重なって団子になる。",
                            "悲鳴はパケットで全員へ配り、クライアント側で鳴らす — ボスは 1000 ブロック先の",
                            "未ロードチャンクに居るので、サーバー側の playSound では誰にも届かないため。")
                    .defineInRange("siegeCryCooldownTicks", 50, 0, 1200);

            siegeCullBackFaces = b
                    .comment("(クライアント描画)ボスのモデルの<b>裏面を描かない</b>。既定 true(軽量化、2026-09-13)。",
                            "GeckoLib の既定は「両面描画」だが、このモデルの箱 325 個はすべて厚みのある直方体で、",
                            "内側が見えることは無い。画面を覆うほど大きいボスでは、裏面をやめるだけで",
                            "塗りつぶしの負荷がおよそ半分になる。",
                            "モデルを厚み 0 の板を含むものに差し替えて、片側から消えて見えるようになったら false に戻すこと。")
                    .define("siegeCullBackFaces", true);

            siegeCryPitch = b
                    .comment("ボスの悲鳴のピッチの倍率。既定 0.7。<b>下げるほど低く、そして長くなる</b> —",
                            "Minecraft はピッチを再生速度で作るので、0.5 なら倍の長さになる。",
                            "巨体の断末魔らしく引き伸ばしたいときはここを下げる(0.5 未満は再生が許されない)。")
                    .defineInRange("siegeCryPitch", 0.7D, 0.5D, 2.0D);

            siegeCryVolume = b
                    .comment("ボスの悲鳴の音量の倍率。既定 2.0(1.0 が素の音量)。1 を超えると Minecraft では",
                            "位置付きの音の減衰距離も一緒に伸びるので、遠くまで響くようになる。")
                    .defineInRange("siegeCryVolume", 2.0D, 0.0D, 10.0D);

            b.pop();

            b.comment("OvergrownVegetation — 砲台(攻城戦ボスを撃つ大砲)の設定",
                    "砲弾はエンティティではなく「発射位置・初速・発射時刻」だけのデータで、サーバーとクライアントが",
                    "同じ放物線の式から毎 tick / 毎フレームの位置を計算する(座標同期なし、チャンク強制ロードなし)。",
                    "ボスへの命中判定は当たり判定パーツ 28 個の箱との線分交差(実エンティティが居なくても式から再現)。",
                    "空気抵抗なしの放物線なので射程 = 初速² / 重力(仰角 45 度・同じ高さ)。").push("cannon");

            cannonMuzzleVelocity = b
                    .comment("装薬が最大(cannonMaxCharge)のときの初速(ブロック/tick)。既定 12.0 →",
                            "重力 0.025 なら同じ高さへの最大射程およそ 5700 ブロック。ボス(1025 先・高度差 +140)へは",
                            "低い弾道で仰角 13 度ほど・飛翔 4.4 秒。装薬を減らすと初速は sqrt(装薬 / 最大) 倍(装薬 1 で半分)。")
                    .defineInRange("cannonMuzzleVelocity", 12.0D, 0.5D, 40.0D);

            cannonMaxCharge = b
                    .comment("装薬(1 発に消費する火薬)の最大数。既定 4。")
                    .defineInRange("cannonMaxCharge", 4, 1, 8);

            cannonGravity = b
                    .comment("砲弾に掛かる重力(ブロック/tick²)。小さいほど弾道が平らになり射程が伸びる。既定 0.025。")
                    .defineInRange("cannonGravity", 0.025D, 0.0D, 1.0D);

            cannonReloadTicks = b
                    .comment("発射後、次に撃てるまでの装填時間(tick)。既定 840 = 42 秒。",
                            "1 発が重いぶん撃てる回数が少ない、という設計なので cannonShellDamage と一緒に調整すること。")
                    .defineInRange("cannonReloadTicks", 840, 0, 12000);

            cannonShellDamage = b
                    .comment("砲弾がボスに命中したときのダメージ。ボスの防御 siegeArmor(既定 80)が<しきい値>として",
                            "差し引かれるので、それを超える値にすること。",
                            "既定 1880 → 実効 1800 → 体力 18000 なら<b>ちょうど 10 発</b>で倒せる。",
                            "必要発射数 = siegeHealth / (cannonShellDamage - siegeArmor)。")
                    .defineInRange("cannonShellDamage", 1880.0D, 0.0D, 1.0E9D);

            cannonShellEntityDamage = b
                    .comment("砲弾がボス以外の生物(mob・プレイヤー)に直撃したときのダメージ。既定 40。")
                    .defineInRange("cannonShellEntityDamage", 40.0D, 0.0D, 10000.0D);

            cannonShellExplosionPower = b
                    .comment("砲弾の着弾の爆発の強さ(TNT = 4.0)。エンティティへのダメージとノックバック・演出だけで、",
                            "ブロックは壊れない(壊したい場合は cannonShellCraterRadius)。既定 2.5。")
                    .defineInRange("cannonShellExplosionPower", 2.5D, 0.0D, 20.0D);

            cannonShellCraterRadius = b
                    .comment("砲弾の着弾でえぐるクレーターの半径(ブロック)。既定 0 = 地形破壊なし。",
                            "1 以上で爆弾と同じ規則(siegeBombCraterMaxResistance 未満の柔らかいブロックだけ)で削る。")
                    .defineInRange("cannonShellCraterRadius", 0, 0, 8);

            cannonDispersion = b
                    .comment("発射のたびに仰角・方位へ乗る誤差の標準偏差(度)。1000 ブロック先では 1 度 ≒ 17 ブロック。",
                            "ボスの胴の太さはおよそ 25 ブロックなので、既定 0.25 なら自動照準でほぼ当たり、たまに外れる。")
                    .defineInRange("cannonDispersion", 0.25D, 0.0D, 30.0D);

            cannonAutoAim = b
                    .comment("騎乗中の照準器に、ボスへの偏差射撃の解(狙うべき点)のマーカーを出すか。",
                            "false にすると距離と解の数値だけになり、どこを狙うかは自分で判断することになる。既定 true。")
                    .define("cannonAutoAim", true);

            cannonAutoAimHighArc = b
                    .comment("照準補助で高い弾道(山なり)を選ぶか。false = 低い弾道(飛翔時間が短く偏差が小さい)。既定 false。")
                    .define("cannonAutoAimHighArc", false);

            cannonShellLifetimeTicks = b
                    .comment("砲弾が何にも当たらないときに消えるまでの tick。既定 1200 = 60 秒。")
                    .defineInRange("cannonShellLifetimeTicks", 1200, 20, 72000);

            cannonMinElevation = b
                    .comment("仰角(砲身ボーン housin)の下限(度)。モデルの可動域に合わせて既定 0。")
                    .defineInRange("cannonMinElevation", 0, -89, 89);

            cannonMaxElevation = b
                    .comment("仰角(砲身ボーン housin)の上限(度)。モデルの可動域に合わせて既定 40。",
                            "初速 7.5・重力 0.04 なら 40 度で水平射程およそ 1385 ブロック。")
                    .defineInRange("cannonMaxElevation", 40, -89, 89);

            cannonShellScale = b
                    .comment("(描画)砲弾のモデル倍率。1 で長さおよそ 1 ブロック。既定 1.0。")
                    .defineInRange("cannonShellScale", 1.0D, 0.1D, 16.0D);

            cannonModelScale = b
                    .comment("砲台のモデル倍率。1.0 = Blockbench のまま(底板およそ 6x4 ブロック・砲身 10 ブロック)。",
                            "砲口と座席の位置も同じ倍率で動くので、見た目と当たりがずれることはない。既定 1.0。")
                    .defineInRange("cannonModelScale", 1.0D, 0.1D, 4.0D);

            cannonScopeZoom = b
                    .comment("照準器(騎乗中の一人称)の視野倍率。0.4 = 視野が 0.4 倍 = 2.5 倍に拡大。",
                            "1.0 にすると拡大しない(照準環だけになる)。既定 0.4。")
                    .defineInRange("cannonScopeZoom", 0.4D, 0.05D, 1.0D);

            cannonTraverseSpeed = b
                    .comment("砲台の旋回速度（度/tick）。騎乗者の視点へ即座に合わせるのではなく、この速さで追従する。",
                            "既定 1.2 → 24 度/秒、真後ろを向くのに 7.5 秒。")
                    .defineInRange("cannonTraverseSpeed", 1.2D, 0.05D, 90.0D);

            cannonElevationSpeed = b
                    .comment("砲身の俯仰速度（度/tick）。既定 0.6 → 12 度/秒、可動域 0〜40 度を 3.3 秒。")
                    .defineInRange("cannonElevationSpeed", 0.6D, 0.05D, 90.0D);

            cannonRangingTicks = b
                    .comment("測距（照準器でボスを捉えてホイールクリック）にかかる時間（tick）。既定 200 = 10 秒。",
                            "完了するまで射撃ガイド（狙うべき点・必要仰角）は出ない。")
                    .defineInRange("cannonRangingTicks", 200, 0, 6000);

            cannonRangingValidTicks = b
                    .comment("測距の結果が有効な時間（tick）。既定 1200 = 60 秒。切れたら測り直し。")
                    .defineInRange("cannonRangingValidTicks", 1200, 20, 72000);

            cannonRangingConeDegrees = b
                    .comment("測距中にボスを視野へ捉えておく必要のある角度（度）。外すと中断。既定 6。")
                    .defineInRange("cannonRangingConeDegrees", 6.0D, 0.5D, 90.0D);

            b.pop();

            b.comment("OvergrownVegetation — 遮蔽盾(攻城戦ボスの花のビームを部分的に防ぐ、展開式の盾)の設定")
                    .push("shield");

            shieldDeployTicks = b
                    .comment("遮蔽盾が展開しきるまでの時間（tick）。既定 40 = 2 秒。",
                            "展開しきっているあいだだけビームを遮る。")
                    .defineInRange("shieldDeployTicks", 40, 1, 1200);

            shieldHandleStep = b
                    .comment("ハンドル(台の左側の輪)を 1 回右クリックしたときに回る角度(度)。既定 1 = 1 度ずつ。",
                            "スニークしながらだと逆回り。右クリック押しっぱなしで 4 tick ごとに繰り返される。たたんでいるときだけ回せる。")
                    .defineInRange("shieldHandleStep", 1.0D, 0.5D, 90.0D);

            shieldScale = b
                    .comment("遮蔽盾のモデル倍率。1.0 で幅 3・高さ 5.5 ブロック。遮蔽面の大きさも一緒に変わる。")
                    .defineInRange("shieldScale", 1.0D, 0.1D, 4.0D);

            shieldSearchRadius = b
                    .comment("ビームのダメージを受ける生物から、この距離までの盾を見る（ブロック）。既定 32。",
                            "ビームはほぼ平行に降ってくるので、遠くの盾の影はそもそも届かない。")
                    .defineInRange("shieldSearchRadius", 32.0D, 4.0D, 128.0D);

            shieldBlocksBeam = b
                    .comment("展開した遮蔽盾が花のビームのダメージを遮るか。既定 true。")
                    .define("shieldBlocksBeam", true);

            b.pop();

            b.comment("OvergrownVegetation — 性能の設定(2026-10-06)。いまの重さは /ov perf で見られる。").push("performance");

            tickBudgetEnabled = b
                    .comment("予算の番人。サーバーが重いとき、後回しにできる処理(台帳の書き換え・極相の全層置換・巨大樹の成長など)を",
                            "次の tick へ回して 1 tick を目標の時間に収める。結果は同じで、遅れるだけ。",
                            "サーバーが軽いときは何も変わらない。既定 true。")
                    .define("tickBudgetEnabled", true);

            tickBudgetTargetMspt = b
                    .comment("予算の番人が守る 1 tick の目標(ミリ秒)。50 を超えると TPS が 20 を割る。",
                            "mod の後回しにできる処理は「目標 − ほかの処理の重さ」の範囲でだけ動く。既定 40(仮決め)。")
                    .defineInRange("tickBudgetTargetMspt", 40.0D, 10.0D, 50.0D);

            mobThinkThrottleEnabled = b
                    .comment("遠くの Mob の思考の間引き。プレイヤーから離れたこの mod の Mob は、行動を選び直す(標的・逃げ先を探す)のを",
                            "数 tick に 1 回にする。歩く・戦うなどの今している行動は毎 tick 続く。",
                            "遠くでの反応が少し遅くなる(汚染の広がりも少し遅れる)。既定 true。")
                    .define("mobThinkThrottleEnabled", true);

            mobThinkThrottleDistance = b
                    .comment("間引きを始める、いちばん近いプレイヤーからの距離(ブロック)。既定 48(仮決め)。")
                    .defineInRange("mobThinkThrottleDistance", 48, 16, 512);

            mobThinkThrottleInterval = b
                    .comment("遠くの Mob が行動を選び直す間隔(tick)。バニラは 2。既定 8 = 0.4 秒(仮決め)。")
                    .defineInRange("mobThinkThrottleInterval", 8, 2, 40);

            b.pop();

            b.comment("OvergrownVegetation — クライアント描画の設定(サーバーには影響しない)").push("client");

            fogCullEnabled = b
                    .comment("この MOD が霧を上書きしているバイオーム(過繁茂・湿地・菌湿地帯)にいる間、",
                            "地形霧の終端より奥にあるチャンク",
                            "(草・葉などの地形)を描画対象から外すか。霧で完全に隠れて見えない範囲を",
                            "描かなくなるため GPU 負荷・ドローコールが減る。チャンク自体はアンロードしないので",
                            "バイオームを出入りしてもチャンク再構築のカクつきは起きない。",
                            "Sodium/Embeddium などの描画 MOD を入れている場合はそちら側でも同種の処理が",
                            "行われるが、併用しても問題はない。")
                    .define("fogCullEnabled", true);

            fogCullExtraMargin = b
                    .comment("上記スキップの判定を甘くする余裕(ブロック)。地形霧の終端からさらにこの距離ぶん",
                            "奥までは描画対象に残す。霧が終端ちょうどで完全な不透明になりきらない分の保険。",
                            "遠くの地形が霧の中でちらついて消えるように見える場合は大きくする。")
                    .defineInRange("fogCullExtraMargin", 24.0D, 0.0D, 256.0D);

            fogCullEntities = b
                    .comment("(クライアント描画)上記と同じ判定で、地形霧の終端より奥にいる Mob の描画も打ち切るか。",
                            "バニラは Mob にも霧を最終段のピクセル処理として掛けるだけなので、霧で完全に潰れて",
                            "1 ピクセルも見えない Mob も、骨の行列計算・全キューブの頂点生成・ドローコールまで",
                            "普通に行われ続けている。それをまとめて省く。見えない範囲だけなので見た目は変わらない。",
                            "mobRenderLodEnabled が止めるのはアニメーションだけ(レストポーズのジオメトリは",
                            "作られ続ける)で、こちらは描画そのものを打ち切る。攻城戦ボスは対象外。",
                            "fogCullEnabled が false のときは判定距離が出ないため、こちらも働かない。")
                    .define("fogCullEntities", true);

            fogFadeSeconds = b
                    .comment("(クライアント描画)過繁茂バイオームに入った・出たとき、霧の濃さと色が目標の状態へ到達するまでに",
                            "かける秒数(実時間)。大きいほどゆっくり霧が立ち込める/晴れる。既定 10。0 で即時。")
                    .defineInRange("fogFadeSeconds", 10.0D, 0.0D, 120.0D);

            mobRenderLodEnabled = b
                    .comment("(クライアント描画)この MOD の GeckoLib Mob のアニメーションを、遠いときは<b>完全に止める</b>。",
                            "Mob が数百体いるときの描画負荷対策。止まった Mob はレストポーズ(モデルの初期姿勢)で",
                            "固まるだけで、位置・向きの補間は毎フレーム行われ、サーバー側の行動には影響しない。",
                            "攻城戦ボスは対象外(いつでも動く)。")
                    .define("mobRenderLodEnabled", true);

            mobRenderLodAnimateChunkRadius = b
                    .comment("カメラのいるチャンクから、この半径(チャンク数)以内のチャンクにいる Mob は動かす。",
                            "それより外の Mob はレストポーズで固まる。既定 3(自分のチャンクを中心に 7×7 チャンク = 約 48 ブロック先まで)。",
                            "0 にすると自分のいるチャンクの中だけ動く。")
                    .defineInRange("mobRenderLodAnimateChunkRadius", 3, 0, 32);

            // 2026-09-25: 「近い数十体だけアニメ」(ユーザー承認 2026-09-24)。既定 64 は仮決め 2026-09-25。
            mobRenderLodAnimateBudget = b
                    .comment("(クライアント描画)上記の半径の中でも、画面に映っている Mob のうちカメラに近い順にこの体数だけ動かす。",
                            "それより遠い Mob は半径の中でもレストポーズで固まる(大群が近くに集まったときの描画負荷対策)。",
                            "半径の外・この体数の外のどちらかに当たれば止まる。0 で体数の上限なし(半径だけで決める)。既定 64。",
                            "攻城戦ボス・フィロセンス・スクティムネスはこの体数に数えず、いつでも動く。")
                    .defineInRange("mobRenderLodAnimateBudget", 64, 0, 1024);

            // 2026-09-25: 遠景の身代わり(ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」)。上限 2000 は仮決め。
            hordeSpritesEnabled = b
                    .comment("(クライアント描画)遠くて描かれない G4〜G9 の Mob を、1 枚の絵の身代わりで描く。",
                            "バニラは小さい Mob を「体の大きさ × 64 × エンティティ描画距離」より先で描かない(アルボラは約 63 ブロック)ので、",
                            "その先から描画距離の霧まで、その Mob のモデルから起動後に作った 8 方向の絵を立てて描く。",
                            "全部まとめて 1 回で描くので軽い(1 体あたり三角形 2 枚)。false で描かない(バニラと同じ見え方)。")
                    .define("hordeSpritesEnabled", true);

            hordeSpriteMaxCount = b
                    .comment("(クライアント描画)身代わりを 1 フレームに描く数の上限。超えたらカメラに近い順に描く。既定 2000。")
                    .defineInRange("hordeSpriteMaxCount", 2000, 0, 20000);

            // 2026-09-25: 遠景の身代わりの 4 段目(描画距離の外は地平線に胞子の嵐)。見た目の数値は仮決め。
            hordeStormsEnabled = b
                    .comment("(クライアント描画)描画距離・霧の外にいる TD の行進中の群れを、その方角の地平線の胞子の嵐(地上の群れ)と",
                            "空の影(ヴェロフィルの群れ)で描く。描画距離が短い・過繁茂の霧の中でも、遠くから迫る大群が見える。",
                            "hordeSpritesEnabled が false なら描かない。false で描かない(霧の外は何も見えない)。")
                    .define("hordeStormsEnabled", true);

            // 2026-09-25 ユーザー「地平線の胞子の嵐の量を増やせますか?地平線が見えないレベルにまで上げてもらって構わない」。既定 1 は仮決め。
            hordeStormDensity = b
                    .comment("(クライアント描画)地平線の胞子の嵐の濃さ。嵐の壁(群れのいる方角ほど濃く高い)と群れごとの土ぼこりの柱に掛ける。",
                            "1 で、群れが全方位にいれば地平線がぐるりと隠れる。小さくすると薄く低く、大きくすると少ない群れでも濃くなる。既定 1。")
                    .defineInRange("hordeStormDensity", 1.0D, 0.0D, 3.0D);

            // 2026-10-15 ユーザー「ジャングルの中のライティングにこだわる: 茂った森でも光が入る・胞子が光に当たって光る・木漏れ日」。
            // 試作。明るさの既定 1 は仮決め。
            canopyLightEnabled = b
                    .comment("(クライアント描画)汚染地域(繁茂・過繁茂・極相)で、林冠の隙間から差す光の筋と、その中で光る胞子を描く。",
                            "昼だけ・雨では弱まる。シェーダー(Iris など)で同じ演出が出るときは false で切る。")
                    .define("canopyLightEnabled", true);
            canopyLightIntensity = b
                    .comment("(クライアント描画)光の筋と光る胞子の明るさ。1 が既定、0 で見えない。")
                    .defineInRange("canopyLightIntensity", 1.0D, 0.0D, 3.0D);
            // 2026-10-15 夜 ユーザー「葉が風に揺られないので、木漏れ日や差し込む光がずっと同じ場所にい続けておかしく見える。
            // ランダムに風が吹いて葉を揺らしているような感じに」→ 案 A(風の場で光と粒を一緒に動かす)。倍率 1 は仮決め。
            canopyWindStrength = b
                    .comment("(クライアント描画)汚染地域の風の強さの倍率。ランダムな突風が林を渡り、木漏れ日の明滅とずれ・光の筋の揺れ・",
                            "胞子の流れ・落ち葉・風の音がその風で動く。0 で無風。既定 1。")
                    .defineInRange("canopyWindStrength", 1.0D, 0.0D, 2.0D);
            canopyFallingLeaves = b
                    .comment("(クライアント描画)汚染地域で、林冠の葉が風に吹かれて落ちる粒を出すか(突風のときに多い)。既定 true。")
                    .define("canopyFallingLeaves", true);
            // 2026-10-15 夜 ユーザー「突風が吹いた際に風の音が鳴るように。現実の音を参考に、しっかりリアリティのある環境音に」。
            // 録音ではなくその場で合成する(風の場と同じ突風で鳴る)。大きさ 1 は仮決め。
            canopyWindSoundVolume = b
                    .comment("(クライアントの音)汚染地域の風の音(葉擦れ・林を渡ってくる突風)の大きさ。0 で鳴らさない。",
                            "バニラの音量の「環境音」も掛かる。光の筋(canopyLightEnabled)を切っても鳴る。既定 1。")
                    .defineInRange("canopyWindSoundVolume", 1.0D, 0.0D, 2.0D);
            // 2026-10-15 夜 ユーザー「巨大樹は大きすぎて、霧に隠れて木全体が見えなくなる。シルエットだけでも見えるように」。
            giantTreeSilhouette = b
                    .comment("(クライアント描画)汚染地域の霧の向こうの巨大樹を、霞んだシルエットで見せるか。",
                            "本物の木が霧で 6 割ほど隠れる距離から先を、木の形を写した影に入れ替えて描く。シェーダーパックと重なって変に見えるなら false。既定 true。")
                    .define("giantTreeSilhouette", true);
            // 2026-10-15 夜、風の案 B(ユーザー「A から順番に」)。大きさ 1 は仮決め。
            canopyLeafSway = b
                    .comment("(クライアント描画)過繁茂の葉・湿地の葉・茂み・シダ・蔦・垂れ苔・太い蔓を、木漏れ日・胞子・風の音と同じ風で揺らす大きさの倍率。0 で揺らさない。",
                            "揺れるのは突風の風の音が鳴っている間だけ。",
                            "バニラの描画のときだけ揺れる(Sodium・Iris のシェーダーパック・「軽量」グラフィックでは揺れない)。既定 1。")
                    .defineInRange("canopyLeafSway", 1.0D, 0.0D, 2.0D);
            // 2026-10-16 ユーザー「霧の色を実装する」。大きさ 1 は仮決め。
            canopyFogGlow = b
                    .comment("(クライアント描画)汚染地域で太陽の方を向いたとき、霧を明るく暖かい色(朝夕は橙、昼は淡い金)へ寄せる強さの倍率。0 で寄せない。既定 1。")
                    .defineInRange("canopyFogGlow", 1.0D, 0.0D, 2.0D);
            // 2026-10-16 風の案 C(ユーザー「風の案 C の実装を」)。強さ 1 は仮決め。
            canopySurfaceShade = b
                    .comment("(クライアント描画)汚染地域の林冠の影を、地面だけでなく草・シダ・幹・葉にも落とす強さ(0〜1)。0 なら地面の上面だけに落とす(前の形)。",
                            "バニラの描画のときだけ(Sodium・Iris が入っていると地面だけ)。既定 1。")
                    .defineInRange("canopySurfaceShade", 1.0D, 0.0D, 1.0D);
            // 2026-10-16 ユーザー選択「昼の光の改善案 (3) 影を青緑に寄せ、筋と胞子の暖色を浮かせる」。色は仮決め。
            canopyShadeTint = b
                    .comment("(クライアント描画)汚染地域の林冠の影を青緑に寄せる強さ(0〜2)。影の明るさは変えず色だけ寄せ、光の筋・木漏れ日・胞子の金色を浮かせる。",
                            "0 で前の灰色の影、2 で倍の強さ。既定 1。")
                    .defineInRange("canopyShadeTint", 1.0D, 0.0D, 2.0D);
            // 2026-10-16 ユーザー「日光が差す場所以外でも、過繁茂の森の中にいるときは常に(青緑の影のように)」。夜・朝夕も含める・松明の明かりは除く は Claude の仮決め。
            canopyForestTint = b
                    .comment("(クライアント描画)過繁茂の森の中にいる間、日向以外の面をいつも青緑に寄せる強さ(0〜1)。",
                            "昼は林冠の影の中だけ、太陽の低い朝夕・夜・影の地図の外は全部の面を寄せる(明るさは変えない)。松明などの明かりで照らされた面は寄せない。",
                            "色の強さは canopyShadeTint。0 で林冠の影だけ(前の形)。バニラの描画のときだけ。既定 1。")
                    .defineInRange("canopyForestTint", 1.0D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー「木の密度を少しだけ上げて、その状態で木漏れ日や差し込む光が美しく見えれば完璧」。林床がバニラの空の光の減り(葉 1 枚ごと)と
            // 林冠の影の両方で暗くなり、木漏れ日・光の筋が闇に沈むので、空の光の減りの方を戻す。値は仮決め。
            canopySkyLift = b
                    .comment("(クライアント描画)過繁茂の森の中にいる間、葉に削られた空の光をこの割合だけ明るい方へ戻す(0〜1)。林床の暗さは林冠の影が受け持ち、",
                            "木漏れ日・光の筋が闇に沈まないようにする。空の光がほとんど届かない所は少しだけ(0 段で半分弱)、洞窟・家の中にいる間は持ち上げない。見た目だけ(湧き・作物は変わらない)。",
                            "Sodium でも効く。0 でバニラのまま。既定 0.6。")
                    .defineInRange("canopySkyLift", 0.6D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー選択(10-16 の「密林をさらに美しく見せる案」(2) 二色の霧)。強さ・色は仮決め。
            aerialFog = b
                    .comment("(クライアント描画)二色の霧(空気遠近法): 過繁茂の森の中で、昼、近くの霧を日に照らされた暖かい色に寄せる強さ(0〜1)。",
                            "遠くは今の青緑の霧のまま(空と同じ)なので、近くは暖かく遠くは青緑になり奥行きが出る。太陽の方ほど暖かい。地形だけ(Mob・粒はバニラの霧)。",
                            "バニラの描画のときだけ。0 で無し。既定 0.7。")
                    .defineInRange("aerialFog", 0.7D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー選択(10-16 の「密林をさらに美しく見せる案」(4) 雨上がりの水滴)。数・時間は仮決め。
            afterRain = b
                    .comment("(クライアント描画)雨上がりの森の強さ(0〜1): 過繁茂の森で雨が降ると濡れ、止んでから 2 分かけて乾く。濡れている間は葉の下から滴が垂れ(雨の最中も)、",
                            "昼の雨上がりには日の当たる地面で水がきらめき、湯気が立ちのぼる。葉そのものは光らせない。0 で無し。既定 1。")
                    .defineInRange("afterRain", 1.0D, 0.0D, 1.0D);
            // 水面のきらめき(waterGlitter・client/WaterGlitter)は 2026-10-22 ユーザー指定で削除。
            // 2026-10-17(10-16 の「密林をさらに美しく見せる案」(5) 色の揺らぎ。10-16 の採用外だったが作って見せ、ユーザーが了承)。色はユーザー指定(若葉は黄緑・成葉は少しだけ青緑、元の色に近く)、割合と色の量は仮決め。
            leafColorVariation = b
                    .comment("(クライアント描画)葉の色の揺らぎの強さ(0〜1): 過繁茂の葉を樹冠くらいのまとまりごとに、元の色から少しだけ青緑へ寄せる(成長しきった葉)。",
                            "葉の層のシェーダーで塗り替えるので、Sodium などや「軽量」グラフィックでは効かない。書き換えてすぐ効く。0 で無し。既定 1。")
                    .defineInRange("leafColorVariation", 1.0D, 0.0D, 1.0D);
            youngLeaves = b
                    .comment("(クライアント描画)若葉の房の強さ(0〜1): 過繁茂の森の一部の樹冠の外側に、元の色より少し明るい黄緑の若葉の房を混ぜる(胞子で増える木の新芽)。",
                            "葉を光らせはしない(暗い所では暗い)。書き換えてすぐ効く。0 で無し。既定 1。")
                    .defineInRange("youngLeaves", 1.0D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー指定(ideas.voxel_clouds: 雲は世界中・建築の上限より上・量は日によって変わり雲が無い日もある・下の層は y 300〜350)。箱の大きさ・範囲・風は仮決め。
            voxelClouds = b
                    .comment("(クライアント描画)バニラの雲の代わりに、y 300〜350 のボクセル調の雲(8 × 4 × 8 ブロックの箱の柱)を描くか。雲は世界中で、量は日によって変わる(雲が無い日もある)。",
                            "光は今の太陽・月の向きで付ける(下面は灰色、朝夕は日の当たる面が橙、太陽の方を向くと縁が光る)。バニラの雲の設定が「オフ」だと描かない。false でバニラの雲。既定 true。")
                    .define("voxelClouds", true);
            cloudCoverage = b
                    .comment("(クライアント描画)雲の量を固定する(0 = 快晴 〜 1 = 一面の雲)。−1 で日ごとに変わる(既定)。確かめる・撮るとき用。")
                    .defineInRange("cloudCoverage", -1.0D, -1.0D, 1.0D);
            // 2026-10-17 ユーザー採用(10-16 の「密林をさらに美しく見せる案」(3) 流れる雲の影)。濃さ・色は仮決め。
            cloudShadows = b
                    .comment("(クライアント描画)流れる雲の影の強さ(0〜1): ボクセルの雲の影が地面・森・水面を風で流れていく。カメラが雲の影に入ると光の筋・木漏れ日・水のきらめきも弱まる。",
                            "昼だけ。ボクセルの雲を描いていないときは出ない。地形のシェーダーで描くので Sodium などでは効かない。0 で無し。既定 1。")
                    .defineInRange("cloudShadows", 1.0D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー指定(10-16「雨や曇りの後に薄明光線(天使の梯子)が出るような」、10-17「次は薄明光線を進めて」)。数・濃さは仮決め。
            crepuscularRays = b
                    .comment("(クライアント描画)薄明光線(天使の梯子)の強さ(0〜1): ボクセルの雲の切れ目から、日の光の向きに地面まで大きな光の筋が差す。",
                            "雨が止んだ後の数分・朝・雲の多い日ほど濃い。太陽の方を向くほど明るい。森の中(林冠の下)では弱い。昼だけ。0 で無し。既定 1。")
                    .defineInRange("crepuscularRays", 1.0D, 0.0D, 1.0D);
            // 2026-10-17 ユーザー指定(10-16「積乱雲を用意し」「積乱雲は最大 y 600」、10-17「次は積乱雲と雷を進めてください」)。形・数・頻度は仮決め。
            cumulonimbus = b
                    .comment("(クライアント描画)積乱雲を立てるか(0 で立てない、0 より大きければ立てる)。どの日・どの時刻にも、雲ごとにばらばらの時刻に湧いて育ち、長く立ってから崩れる(雲の多い日ほど多い)。",
                            "y 300 から最大 1500 まで塔が伸び、一部は天辺に金床が広がる。雷雨の間は数が増え、ほぼ必ず近くに立つ。中で雷が光って雲を照らし、ときどき稲妻が地面へ落ち(見た目と音だけ)、塔の下に雨の柱が下りる。既定 1。")
                    .defineInRange("cumulonimbus", 1.0D, 0.0D, 1.0D);
            cumulonimbusChance = b
                    .comment("(クライアント描画)積乱雲が 1280 ブロック四方に 1 つ立つ確率を固定する(0〜1)。−1 で雲の量と雷雨で決まる(既定)。確かめる・撮るとき用。")
                    .defineInRange("cumulonimbusChance", -1.0D, -1.0D, 1.0D);
            lightningRate = b
                    .comment("(クライアント描画)積乱雲の中で雷が光る頻度の倍率(0〜10)。1 でふだんは 1 つの雲で 20 秒に 1 回ほど、雷雨の間は 4 秒に 1 回ほど。0 で光らない。既定 1。")
                    .defineInRange("lightningRate", 1.0D, 0.0D, 10.0D);
            // 2026-10-17 ユーザー指定「稀に積乱雲の周りに雲海を、とびっきり巨大なもの(空の巨大生物がすっぽり収まるくらい)」。割合・大きさは仮決め。
            giantStormChance = b
                    .comment("(クライアント描画)立つ積乱雲のうち、巨大な積乱雲(2 倍ほどの大きさ・天辺 y 1000 超え。まわり 1800 ブロックに厚さ 100 ほどの雲海を作り、",
                            "近くのほかの積乱雲を消す)になる割合(0〜1)。−1 で既定(0.12)。確かめる・撮るときは 1 に。")
                    .defineInRange("giantStormChance", -1.0D, -1.0D, 1.0D);
            // 2026-10-17 夜、ユーザー「積乱雲は一つだけでなく複数がそこら中に。空に雲の迷宮のような空間を」の受け取り方の案(ユーザーが選ぶまでの仮)。
            cumulonimbusStyle = b
                    .comment("(クライアント描画)積乱雲の並び方(案出し中)。1 = 群れ(中くらいの塔があちこちに、間は青空)/ 2 = 谷(雲の床から塔が密に立ち、間が谷の通路)/",
                            "3 = 壁と橋(2 に加えて、塔が並んだ雲の壁と宙に架かる雲の橋)。書き換えて 10 秒ほどで形が替わる。既定 2(10-17 ユーザーが B を選んだ。雲の海に山と谷)。")
                    .defineInRange("cumulonimbusStyle", 2, 1, 3);
            // 2026-10-17 ユーザー指定「どんな過繁茂したバイオームであっても高度 300〜350 辺りから神秘的なレベルで奇麗な晴天に(空の巨大生物とのバトルフィールド・空の移動の目玉)」。
            highSkyClarity = b
                    .comment("(クライアント描画)高空の晴天の強さ(0〜1): カメラが下の高さから上の高さへ上がるにつれて、過繁茂・極相の霧・空の色・暗さ・漂う胞子を外し、",
                            "晴れた空と遠くまで見える空気にする。0 で無し。既定 1。")
                    .defineInRange("highSkyClarity", 1.0D, 0.0D, 1.0D);
            highSkyClearFrom = b
                    .comment("(クライアント描画)高空の晴天が始まる高さ(y)。既定 300(ユーザー指定)。")
                    .defineInRange("highSkyClearFrom", 300.0D, 64.0D, 2000.0D);
            highSkyClearTo = b
                    .comment("(クライアント描画)高空の晴天になりきる高さ(y)。既定 350(ユーザー指定)。")
                    .defineInRange("highSkyClearTo", 350.0D, 65.0D, 2100.0D);
            // 2026-10-17 深夜ユーザー選択(雲の中の演出: 白い霧・流れるちぎれ雲・画面の端の水滴・抜けるときのもや・霧の中の巨大な影)。距離・濃さは仮決め。
            cloudInterior = b
                    .comment("(クライアント描画)雲の中の演出の強さ(0〜1): ボクセルの雲の中に入ると白い霧に包まれ(積乱雲の中は灰色)、出入りのときはもやが濃く・薄くなる。",
                            "中ではちぎれ雲が流れ、画面の端に水滴がつき、霧の向こうに積乱雲の影がぼんやり見える。0 で無し(雲の中もこれまで通り)。既定 1。")
                    .defineInRange("cloudInterior", 1.0D, 0.0D, 1.0D);
            cloudTurbulence = b
                    .comment("(クライアント描画)乱気流の強さ(0〜1): 積乱雲の中で視点が揺れ(画面のゆがみ効果の設定も掛かる)、上昇気流・下降気流に押される(エリトラは強く)。0 で無し。既定 1。")
                    .defineInRange("cloudTurbulence", 1.0D, 0.0D, 1.0D);
            cloudWindSound = b
                    .comment("(クライアント音)雲の中の風の音の大きさ(0〜1): 雲の中で突風のときだけ、こもった風の音(速く飛ぶと空気を切る音も)。音量の「環境音」も掛かる。0 で無し。既定 1。")
                    .defineInRange("cloudWindSound", 1.0D, 0.0D, 1.0D);
            // 2026-10-17 深夜ユーザー指定「雷雨の際の積乱雲のみで、雲の中に雷が走る。触れたら即死」。頻度・前触れの長さ・当たりの太さは仮決め。
            cloudLightning = b
                    .comment("(ゲームの決まり)雷雨の間、積乱雲の中にいるプレイヤーのまわりを雷が走る。光って音がした後に走り、触れると即死(防具・効果では防げない。不死のトーテムは効く)。",
                            "積乱雲の中だけ(下の層・雲の海・雷雨でないときは走らない)。false で走らない。既定 true。")
                    .define("cloudLightning", true);
            cloudLightningRate = b
                    .comment("(ゲームの決まり)積乱雲の中を走る雷の頻度の倍率(0〜5)。1 で 1 人のまわりに 2〜5 秒に 1 本、そのうち 3 割はその人を通る。既定 1。")
                    .defineInRange("cloudLightningRate", 1.0D, 0.0D, 5.0D);
            // 2026-10-17 深夜ユーザー選択(アニメ調の陰影 = 「Minecraft に合うように調整したセル画チックな感じ」・輪郭のやわらかさ・雲が雲に落とす影)。色と段の位置は仮決め。
            cloudCelShading = b
                    .comment("(クライアント描画)雲のセル画調の陰影の強さ(0〜1): 明るい所・中間・影の 3 つの平らな色にくっきり分ける(段は箱の境に来る)。影は青みのある灰色。0 でなめらかな陰影。既定 1。")
                    .defineInRange("cloudCelShading", 1.0D, 0.0D, 1.0D);
            cloudSoftEdges = b
                    .comment("(クライアント描画)雲の輪郭のやわらかさ(0〜1): 箱の出っ張った角・辺の近くを透かして、角張った輪郭をやわらかく見せる(逆光では縁が光る)。0 でくっきりした箱。既定 1。")
                    .defineInRange("cloudSoftEdges", 1.0D, 0.0D, 1.0D);
            cloudSelfShadows = b
                    .comment("(クライアント描画)雲が雲に落とす影の強さ(0〜1): 積乱雲の影が雲の海と積乱雲の日の当たらない側に落ちる(昼だけ)。0 で無し。既定 1。")
                    .defineInRange("cloudSelfShadows", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー依頼(参考画像 = 王立宇宙軍 オネアミスの翼の空): 荒天の雲を濃い青灰に・切れ目を水色に / 雲の海の上の風に流れた筋 / 遠くの雲の青い霞。色・濃さは仮決め。
            cloudRoughWeather = b
                    .comment("(クライアント描画)荒天の雲の強さ(0〜1): 雨・雷雨の間、雲を濃い青灰(スレート色)にし、雲の切れ目(輪郭の縁)を水色に光らせる。雲の中の霧も青灰に。0 でバニラの雨の灰色。既定 1。")
                    .defineInRange("cloudRoughWeather", 1.0D, 0.0D, 1.0D);
            cloudWindStreaks = b
                    .comment("(クライアント描画)雲の海の上の風に流れた筋の強さ(0〜1): 風の向きに長い筆の跡のような濃淡(雲と一緒に流れる)。積乱雲には弱く、遠くでは消える。0 で無し。既定 1。")
                    .defineInRange("cloudWindStreaks", 1.0D, 0.0D, 1.0D);
            cloudBlueHaze = b
                    .comment("(クライアント描画)遠くの雲の青い霞の強さ(0〜1): 遠くの雲ほど空の青に寄って淡くなる(空気遠近)。0 で無し。既定 1。")
                    .defineInRange("cloudBlueHaze", 1.0D, 0.0D, 1.0D);
            // 2026-10-16 ユーザー指定「現実の高い空の筋のような細い雲(巻雲)」、10-18「巻雲は y 1600 あたりに変更」。模様・量・色は仮決め。
            cirrus = b
                    .comment("(クライアント描画)巻雲の濃さ(0〜1): y 1600 に、風の向きに長い細い筋の雲が下の雲より速く流れる(朝夕は強く染まる)。量は日ごとに変わり、無い日もある。",
                            "ボクセルの雲を描いていないときは出ない。0 で無し。既定 1。")
                    .defineInRange("cirrus", 1.0D, 0.0D, 1.0D);
            cirrusAmount = b
                    .comment("(クライアント描画)巻雲の量を固定する(0〜1)。−1 で日ごとに変わる(既定)。確かめる・撮る用。")
                    .defineInRange("cirrusAmount", -1.0D, -1.0D, 1.0D);
            // 2026-10-16 ユーザー指定「ティタニカの極太ビームや攻城砲の砲弾などが雲に入ったとき、円形にぼこっと穴が開く演出」、10-18「巻雲の後に」。大きさ・時間は仮決め。
            cloudHoles = b
                    .comment("(クライアント描画)雲に穴が開く演出(0〜1): 攻城砲の砲弾・ティタニカの胞子爆弾・花のビームが雲を通ると、通った道に丸い穴が開き(縁がしばらく光り、",
                            "入口に水蒸気の輪が広がる)、1 分後から埋まり始めて 3 分で元に戻る。値は縁の光の強さ。0 で無し。既定 1。")
                    .defineInRange("cloudHoles", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー選択(雲の質感: 面の中の筆のムラ・雲の海の段差を目立たせない・積乱雲の底を暗く平らに・遠くの雲の輪郭を丸く)。濃さは仮決め。
            cloudTexture = b
                    .comment("(クライアント描画)雲の質感の強さ(0〜1): 平らな面に筆で塗ったような弱いムラ、雲の海の段差の色の差を小さく、積乱雲の下の方を雨雲のように暗く平らに、",
                            "遠くの雲ほど箱の角を溶かして丸い輪郭に。0 で前の見た目。既定 1。")
                    .defineInRange("cloudTexture", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー選択(空の光: 天頂の深い青・太陽の光輪とまぶしさ・雲の上の光芒・月明かりの夜の雲・雲の上の星空・朝焼けの空の色の帯・夜明け前の青い時間)。濃さ・色は仮決め。
            skyLight = b
                    .comment("(クライアント描画)空の色の強さ(0〜1): 昼は天頂ほど深い群青に、朝夕は地平の空に広い橙の帯とその上の薄紫、日の入りの後・日の出の前は空が深い青に(青い時間)。",
                            "雲の上(高空の晴天)で全部、その下は森の霧の分だけ弱い。雨では出ない。0 で無し。既定 1。")
                    .defineInRange("skyLight", 1.0D, 0.0D, 1.0D);
            sunGlare = b
                    .comment("(クライアント描画)太陽の光輪とまぶしさ(0〜1): 太陽のまわりの光の輪と、太陽が画面に入ったときのまぶしさ(雲・地形に隠れると消える)。0 で無し。既定 1。")
                    .defineInRange("sunGlare", 1.0D, 0.0D, 1.0D);
            highStars = b
                    .comment("(クライアント描画)雲の上の星空(0〜1): 雲より高い所で、夜空に細かな星と淡い天の川を足す(空と一緒に回る)。0 で無し。既定 1。")
                    .defineInRange("highStars", 1.0D, 0.0D, 1.0D);
            nightClouds = b
                    .comment("(クライアント描画)夜と青い時間の雲(0〜1): 夜の雲を真っ黒でなく月明かりの深い青に(満月ほど明るい)、日の入りの後・日の出の前は雲も深い青に。0 でバニラの色。既定 1。")
                    .defineInRange("nightClouds", 1.0D, 0.0D, 1.0D);
            skyRays = b
                    .comment("(クライアント描画)雲の上の光芒(0〜1): 雲の上の昼、積乱雲の上のこぶの縁から日の光の進む向きへ淡い光の筋(太陽の方を見るほど明るい)。0 で無し。既定 1。")
                    .defineInRange("skyRays", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー選択「A+E と D」(高度 400 を超えて雲より上に入ったとき、読み込み範囲の外の雲も見えるように)。距離・強さは仮決め。
            farClouds = b
                    .comment("(クライアント描画)雲の上から見る遠くの雲(0〜1): 高度 400 より上で、近くの雲の外(約 1344 ブロック)から 7680 ブロックまで(高度 950 より上は 18432 ブロックまで)、遠いほど大きな箱で雲の海と積乱雲を描く。",
                            "0 で無し(近くの雲だけ)。既定 1。")
                    .defineInRange("farClouds", 1.0D, 0.0D, 1.0D);
            cloudCurvature = b
                    .comment("(クライアント描画)雲の海の丸み(0〜1): 1200 ブロックより遠い雲ほど下げて(星の丸みのように)、雲の海の端を地平線の下へ沈める。0 で平ら。既定 1。")
                    .defineInRange("cloudCurvature", 1.0D, 0.0D, 1.0D);
            cloudHorizon = b
                    .comment("(クライアント描画)地平線の雲の書き割り(0〜1): 高度 400 より上で、遠くの雲の先の地平線に、描いた雲の海と遠くの積乱雲の頭の帯を置く。0 で無し。既定 1。")
                    .defineInRange("cloudHorizon", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー「風の流れを表現するように白い帯のようなエフェクトが漂っているとよい」(空の世界の順番 2)。数・長さ・速さは仮決め。
            windRibbons = b
                    .comment("(クライアント描画)風の流れの白い帯(0〜1): 雲の上の空で、世界の風の向きに伸びる細長い白い帯がうねりながら流れて現れては消える。風が強いほど多い。0 で無し。既定 1。")
                    .defineInRange("windRibbons", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー選択「乳房雲・尾流雲・入道雲」(空の世界の順番 4)。濃さ・長さは仮決め。
            virga = b
                    .comment("(クライアント)尾流雲の濃さ(0〜1): 育った金床の下と、育ちかけの積乱雲・入道雲の底から垂れて、地面に届く前に消える雨の筋。0 で無し。既定 1。",
                            "(乳房雲 = 金床の下に垂れる丸い袋と、入道雲 = 金床も雷も無い中くらいの塔は、積乱雲の一部なので cumulonimbus で一緒に消える)")
                    .defineInRange("virga", 1.0D, 0.0D, 1.0D);
            // 2026-10-19 ユーザー「ボスによる雲引き(ボス以外もある程度速度が出ている状態で雲から出た際、雲を引くように)」(空の世界の順番 7)。数・大きさ・長さは仮決め。
            cloudWake = b
                    .comment("(クライアント)雲を引く(0〜1): 雲から出たもの(ボス以外は秒速 8 ブロック以上で動くもの。観戦モードは除く)と空の巨大生物が、",
                            "出てから数秒、後ろに雲のかけらを残す(かけらは少し追いかけてから空気に止まり、膨らみながら薄れる)。0 で無し。既定 1。")
                    .defineInRange("cloudWake", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 ユーザー選択「上昇気流と下降気流」、空のギミックは「突風や気流に乱気流」(空の世界の順番 3)。強さ・場所は仮決め。
            skyAir = b
                    .comment("(クライアント)空の気流(0〜1): 雲の上で、育った積乱雲の脇は上昇気流で持ち上げ、風下側は下降気流で沈め、ときどき世界の風の向きへ突風で押す",
                            "(手元のプレイヤーだけ。地面に立っている・観戦・クリエイティブで飛んでいる・乗り物の中は押さない。エリトラは 1.6 倍)。突風のときは雲の上の風の音も鳴る。0 で無し。既定 1。")
                    .defineInRange("skyAir", 1.0D, 0.0D, 1.0D);
            // 2026-10-18 雲の上から見下ろすと、雲の海の切れ目の下が描画距離の外で霧の色の平らな面になる(夕方は橙で溶岩のよう)。距離・色は仮決め。
            farGround = b
                    .comment("(クライアント描画)雲の海の切れ目の下の地面(0〜1): 雲より上(高度 340〜400 で現れる)で、描画距離の外に霞んだ森の地面の身代わり(半径 1 万ブロック)を描く。",
                            "描画距離の中は本物の地形が上に描かれる。0 で無し(切れ目の下は霧の色)。既定 1。")
                    .defineInRange("farGround", 1.0D, 0.0D, 1.0D);
            // 2026-10-16 ユーザー指定「朝霧の谷に胞子がたまる」「山の上から森を見たとき森が霧がかって見えるように(下に行くと薄い)」。数値は仮決め。
            morningMist = b
                    .comment("(クライアント描画)夜明けに汚染地域とそのまわりの低い所へたまる朝霧の霞を描くか。霧がどこにあるかはサーバーと同じ式(感染の進み・胞子の露はこの設定と関係なく働く)。",
                            "地形と水のシェーダーで描くので、Sodium・Iris では霞は出ない(霧の中にいるときのバニラの霧は出る)。既定 true。")
                    .define("morningMist", true);
            morningMistDensity = b
                    .comment("(クライアント描画)朝霧の霞の濃さの倍率(0〜3)。既定 1。")
                    .defineInRange("morningMistDensity", 1.0D, 0.0D, 3.0D);
            morningMistFogDistance = b
                    .comment("(クライアント描画)朝霧の中にいるとき、ここから先(ブロック)はバニラの霧で隠す(遠くの地形も Mob も見えない)。近くはほとんど澄んだまま。既定 64。")
                    .defineInRange("morningMistFogDistance", 64.0D, 16.0D, 256.0D);
            // 2026-10-16 ユーザー指定「地下の光: 弱い光を青緑にする明るさの調整」。色と量は仮決め。
            caveLightFloor = b
                    .comment("(クライアント描画)汚染地域の地下(岩の天井の下)で、ブロックの光も空の光も届かない真っ暗な面を、その面の色のごく少しの青緑で底上げする強さ(0〜3)。",
                            "0 でバニラと同じ真っ暗。暗がりは残る(1 で面の色の 3〜7%)。バニラの描画のときだけ。既定 1。")
                    .defineInRange("caveLightFloor", 1.0D, 0.0D, 3.0D);
            // 2026-10-16 ユーザー「4 の案(地下の霧と深さの色)のような、環境を美しく見せる感じが良い」。厚さ・色・濃さは仮決め。
            caveMist = b
                    .comment("(クライアント描画)汚染地域の地下の大きな洞窟で、床の低い所に溜まる薄い霧(青緑の雲の板)を描くか。",
                            "光る植物・松明・洞窟の口から差す光の近くでは霧が照らされて明るく見える。深さで色が変わる(浅い = 緑・中 = 青緑・深い = 淡い水色)。既定 true。")
                    .define("caveMist", true);
            caveMistDensity = b
                    .comment("(クライアント描画)地下の霧の濃さの倍率(0〜3)。既定 1。")
                    .defineInRange("caveMistDensity", 1.0D, 0.0D, 3.0D);
            // 2026-10-16 ユーザー「霧の位置がブロックに近すぎて霧があまり見えない」。1.3 → 2.8 は仮決め。
            caveMistHeight = b
                    .comment("(クライアント描画)地下の霧の層の高さ: まわりの床の真ん中の高さから霧のてっぺんまで(ブロック、0.8〜6)。",
                            "大きいほど霧が床から浮いて厚くなり、立っていると霧に包まれる。ゲーム中に書き換えると数秒後(霧の作り直し)に反映。既定 2.8。")
                    .defineInRange("caveMistHeight", 2.8D, 0.8D, 6.0D);
            // 2026-10-16 ユーザー指定「極相の 8 案を全て実装」「とりあえず密林のみ」。数値は仮決め。
            climaxJungleEffects = b
                    .comment("(クライアント描画)極相の密林(結晶地帯を含む)の演出: 下からの光・真昼の星と霞んだ太陽・地脈のまわりに浮く胞子と葉と小石・",
                            "林冠の下の光る滴・光の筋が地面に届く前に吸われる・地脈・結晶の光が昼も・結晶の共鳴・地脈を流れる光。既定 true。")
                    .define("climaxJungleEffects", true);
            // 2026-10-16 ユーザー選択「地面から透ける光の帯」「光のにじみを足す」。
            leyFlowEnabled = b
                    .comment("(クライアント描画)過繁茂の森で時々(2〜4 分おき)、地脈になる線に沿って青緑の光の帯が地面に浮かび、光の粒が流れるか。見た目だけ。既定 true。")
                    .define("leyFlowEnabled", true);
            leyNightGlow = b
                    .comment("(クライアント描画)夜、地脈ブロックとエネルギー結晶のまわりに柔らかい光のにじみと、立ちのぼる光の粒を描くか。",
                            "周りの明るさ(ブロックの光)は変えない。既定 true。")
                    .define("leyNightGlow", true);

            // 巻きシダの精細さ curledFernDetail は 2026-10-23 にクライアント設定(OvergrownClientConfig)へ移した(品質の段階で変えるため)。

            swingThroughFoliage = b
                    .comment("(クライアント操作)剣・斧・トライデント・メイス(および",
                            "#overgrown_vegetation:swings_through_foliage タグのアイテム)を持っている間、",
                            "当たり判定の無い下草・花・蔦に照準を吸われず、その奥にいる Mob を攻撃対象にする。",
                            "森の中の戦闘で「草を斬ってしまって敵に当たらない」のを防ぐための設定。",
                            "透けるのは当たり判定の無いブロックだけなので、壁の向こうの Mob は狙えない。",
                            "草そのものを刈りたいときはスニーク(Shift)しながら殴れば従来どおり草を狙える。",
                            "レバー・ボタンなど「当たり判定は無いが触りたい」ブロックは",
                            "#overgrown_vegetation:not_swing_through タグで除外できる。")
                    .define("swingThroughFoliage", true);

            b.pop();

            b.comment("OvergrownVegetation — 装備の設定").push("gear");

            overgrowthBaneMultiplier = b
                    .comment("#overgrown_vegetation:overgrowth_bane タグの武器で、グループ1〜10 の Mob を殴ったときの",
                            "ダメージ倍率。既定 1.5。1.0 にすると特効が無くなる。",
                            "既定でタグに入っているのはクロロフィライトとクロロフィライト合金の剣・斧。",
                            "対象を増やしたければ、データパックでタグに足せばよい(Java の変更は要らない)。",
                            "掛かるのは直接殴ったダメージだけで、矢などの飛び道具には掛からない。")
                    .defineInRange("overgrowthBaneMultiplier", 1.5D, 0.0D, 100.0D);

            sapCrystalWardMultiplier = b
                    .comment("鍛冶台で樹液結晶を打ち込んだ #overgrown_vegetation:overgrowth_ward の防具を",
                            "4 部位そろえて着ているとき、グループ1〜10 の Mob から受けるダメージに掛かる倍率。既定 0.8。",
                            "部位が欠けているぶんは比例して弱まる(1 部位ごとに軽減の 1/4 = 5%)。",
                            "1.0 にすると軽減が無くなる。既定でタグに入っているのはクロロフィライトと",
                            "クロロフィライト合金の防具で、打ち込んでいない防具には効かない。",
                            "攻城戦ボスの攻撃(ビーム・胞子爆弾)にも掛かる。")
                    .defineInRange("sapCrystalWardMultiplier", 0.8D, 0.0D, 1.0D);

            overgrowthResistantPerPiece = b
                    .comment("#overgrown_vegetation:overgrowth_resistant の防具 1 部位につき、グループ1〜10 の Mob から",
                            "受けるダメージを減らす割合。既定 0.1 = 10%(4 部位そろえて 40%)。",
                            "既定でタグに入っているのは強化クロロフィライト合金装備。樹液結晶の打ち込みは要らない。",
                            "樹液結晶の軽減(sapCrystalWardMultiplier)とは足し算で重なる。",
                            "0 にすると素材そのものの耐性が無くなる。")
                    .defineInRange("overgrowthResistantPerPiece", 0.1D, 0.0D, 1.0D);

            b.pop();
        }
    }

    private OvergrownVegetationConfig() {
    }
}
