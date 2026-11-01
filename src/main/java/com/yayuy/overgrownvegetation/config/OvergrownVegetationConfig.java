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
        public final ModConfigSpec.BooleanValue branchJointTaper;
        public final ModConfigSpec.BooleanValue branchJointFill;
        /** 汚染した上面に「未発達の茂み」({@code undeveloped_bush})を生やすことがあるか。 */
        public final ModConfigSpec.BooleanValue spawnUndevelopedBush;
        /** 上面に「未発達の茂み」を生やす確率(0.0〜1.0)。growthChance にさらに掛かる。 */
        public final ModConfigSpec.DoubleValue undevelopedBushChance;
        /** 林床(2026-11-01、密林だけ): 下草の抽選の当たりのうち、小葉類の敷物を敷く割合。 */
        public final ModConfigSpec.DoubleValue floorCarpetChance;
        /** 林床: 敷物の上に小葉類の株(敷物付き)を立てる確率。 */
        public final ModConfigSpec.DoubleValue floorCarpetPlantChance;
        /** 林床: 敷物にならなかった下草の抽選で、小葉類の株(敷物なし)を立てる確率。 */
        public final ModConfigSpec.DoubleValue floorLycopsidChance;
        /** 林床: 小葉類の株のうち、2 段の立ち上がる小葉類にする割合。 */
        public final ModConfigSpec.DoubleValue floorUprightShare;
        /** 林床: 下草の抽選でメドゥロサ(高さ 2〜3)を立てる確率。 */
        public final ModConfigSpec.DoubleValue floorMedullosaChance;
        /** 林床: 1 チャンクの泥炭の斑の最大数(1〜この数。低地・水辺だけで実る)。 */
        public final ModConfigSpec.IntValue floorPeatPatches;
        /** 林床: 泥炭の斑の内側のマスを浅い水たまりにする確率。 */
        public final ModConfigSpec.DoubleValue floorPeatPoolChance;
        /** 林床: 倒木の枠(1 チャンクに 2 つ)それぞれに倒木を置く確率。 */
        public final ModConfigSpec.DoubleValue floorFallenLogChance;
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
        /** 水中を汚染したとき、露出した水の面へ水中の植物(シャジクモ・垂れる根・水面の膜・胞子昆布・浮き藻)を生やすか。 */
        public final ModConfigSpec.BooleanValue spawnUnderwaterPlants;
        /** 水に接した露出<b>上面</b> 1 つあたりにシャジクモ(川・池)を生やす確率(0.0〜1.0)。海の胞子昆布は格子の点だけ(UnderwaterPlants)。 */
        public final ModConfigSpec.DoubleValue underwaterGrowthChance;
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
        /** 湿地系の場所では茂み(overgrown_bush の塊)を生やさないか。 */
        public final ModConfigSpec.BooleanValue swampNoBushes;
        /** 水中・水面をまたぐ根のアーチの生成確率に掛ける倍率(陸のアーチ = corruptionArchChance に対して)。 */
        public final ModConfigSpec.DoubleValue waterArchChanceMultiplier;

        // --- 洞窟の植物・構造物({@link com.yayuy.overgrownvegetation.corruption.CaveGrowth}) ---
        /** 洞窟専用の植物・構造物を有効にするか。 */
        public final ModConfigSpec.BooleanValue caveGrowth;
        /** 地表(葉を除く)よりこの深さ以上低い場所を「洞窟」とみなす。 */
        public final ModConfigSpec.IntValue caveDepthMargin;
        /** 洞窟の汚染ブロック 1 つあたりに構造物を 1 つ建てようとする確率。 */
        public final ModConfigSpec.DoubleValue caveStructureChance;
        /** 構造物(天井の根 / 蔦のアーチ)の重み。0 で建てない。 */
        public final ModConfigSpec.IntValue caveArchWeight;
        /** 密林(ジャングル)の洞窟にも天井のアーチ(根 / 蔦)を建てるか(湿地系の洞窟ではいつも建つ)。 */
        public final ModConfigSpec.BooleanValue caveRootArchInJungle;
        /** 建てた構造物(根 / 蔦のアーチ)の各ブロックの下面に垂れ植物を添える確率。 */
        public final ModConfigSpec.DoubleValue caveStructurePlantChance;
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

        // --- グループ4(拡大種): AbstractOvergrownInvaderEntity / InvaderSporeCrouchGoal ---

        // 旧いグループ4 の群れ(InvaderPack 系)の設定 invaderPack* 28 個・invaderPreyIntel* 2 個・velophyllPackLeash は
        // 2026-09-25 に群れ v2(swarm/、数値は design/swarm.json)へ置き換えて消した。toml に残った行は NeoForge が直すときに消える。

        // --- 過繁茂したスカトゥリウム: 迫撃砲と座り込み(ユーザー要望 2026-09-15)---

        // --- 経路探索(InvaderNavigation。ユーザー報告 2026-09-17「壁につっかえる」)---

        // --- 水を避ける(LandMobWaterAvoidance。ユーザー要望 2026-09-19)---

        // --- アルボラの坑道掘り(ArboraTunnelGoal。ユーザー要望 2026-09-17)---

        // --- うずくまって胞子を撒く(InvaderSporeCrouchGoal・群れの SPREAD)---
        // --- フィロセンス(グループ4・索敵役): PhyllosensEntity / PhyllosensScoutGoal / PhyllosensBeamGoal ---
        /** 「視線」の持続(tick)。範囲外へ出てからこれだけで切れる。 */
        /** 「誘導」の持続(tick)。切れた Mob は追跡をやめて元の行動へ戻る。 */

        /** ビームのダメージ。防具は効く。 */
        /** ビームの射程(ブロック)。これより遠い相手には近づいてから撃つ。 */
        /** ビームの当たり判定の太さ(ブロック)。ティタニカの精密ビームより細い。 */
        /** ビームの見た目の太さ(ブロック)。 */
        /** 溜めの長さ(tick)。この間は頭が相手を追う。既定 60 = 3 秒。 */
        /** 狙点を固定してから発射するまで(tick)。既定 10 = 0.5 秒。 */
        /** 撃ったあと次に撃てるまで(tick)。 */

        // --- ヴェロフィル(グループ4・爆撃役): VelophyllEntity / VelophyllBombGoal / VelophyllGroundSporeGoal ---

        // --- 噛みつき(空中の相手。ユーザー要望 2026-09-17)---
        /** 1 回の爆撃で落とす爆弾の数。1 発ずつ順に落とす。 */
        /** 爆弾 1 発ごとの間隔(tick)。 */
        /** 撃ち切ってから次の爆撃に入れるまで(tick)。 */

        /** 胞子爆弾の爆風が届く半径(ブロック)。ティタニカの爆弾より広い。 */
        /** 爆風に巻き込まれたときのダメージ。距離では減衰しない。 */
        /** 爆弾が直撃したときのダメージ(爆風とは別に、当たった 1 体だけが受ける)。 */
        /** 着弾点の地表を汚染する半径(ブロック)。 */
        /** 汚染の輪郭を波打たせる振幅(ブロック、±)。0 で綺麗な円。 */
        /** 爆風のノックバックの強さ。 */
        /** 爆風で胞子感染させる半径(ブロック)。0 で無効。 */
        /** 爆発したときに葉を薙ぎ払う半径(ブロック)。0 で無効。 */

        // --- スポロフィル(グループ4・支援役): SporophyllEntity / SporophyllAura ---
        /** 味方に配るバフの持続(tick)。範囲内に居る限り配り直されるので途切れない。 */
        /** 敵に配るデバフの持続(tick)。 */
        /** 味方バフ(再生・攻撃力上昇・耐性)の強さ。0 = I、1 = II。 */
        /** 敵デバフ「纏わりついた胞子」(移動速度低下・攻撃力低下を含む)の段階。0 = I、1 = II。 */
        /** 「スポロフィルの胞子」持ちの攻撃が無視する防御力の割合(0〜1)。 */
        /** 「スポロフィルの胞子」持ちの攻撃が防具の耐久値を削る量(与ダメージ × この値)。 */
        /** 「纏わりついた胞子」を持つ相手が、グループ4 の Mob から余計に見つかる距離(ブロック)。 */
        /** 範囲内で胞子感染が満了したとき、1 段階進める(過繫茂バイオームと同じ扱いにする)か。 */

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
        /** 胞子噴出中、噴出点からこのチャンク半径以内にランダムに汚染シダを湧かせる。0 で無効。 */
        public final ModConfigSpec.IntValue quakeEruptHazardRadiusChunks;
        /** 噴出点周囲にランダムな汚染シダを湧かせる処理の実行間隔(tick)。 */
        public final ModConfigSpec.IntValue quakeEruptFernIntervalTicks;
        /** 上記1回あたりの汚染シダ出現の試行回数。 */
        public final ModConfigSpec.IntValue quakeEruptFernAttemptsPerInterval;
        /** 噴出点周囲(ハザード半径内)の汚染シダがこの数に達したら、それ以上は湧かせない。 */
        public final ModConfigSpec.IntValue quakeEruptFernMaxNearby;
        /** 胞子噴出エフェクトが出ている間、汚染シダなどによる侵食速度に掛かる倍率。 */
        public final ModConfigSpec.DoubleValue quakeEruptionCorruptionMultiplier;
        /** (クライアント描画)画面の揺れの最大強度。0 で揺れなし、1.0 が既定。 */
        public final ModConfigSpec.DoubleValue quakeShakeIntensity;
        /** (クライアント描画)震央からこの距離(ブロック)離れるほど揺れが弱まる(下限あり)。既定 512。 */
        public final ModConfigSpec.IntValue quakeShakeRadius;

        /**
         * (クライアント操作)剣・斧などの武器を持っている間、<b>当たり判定の無いブロック</b>
         * (下草・花・蔦など)に照準を吸われず、その奥にいる Mob を攻撃対象にするか
         * ({@link com.yayuy.overgrownvegetation.client.SwingThroughFoliage})。
         * 照準の決定だけを差し替えるクライアント側の処理で、サーバーには影響しない。
         */
        public final ModConfigSpec.BooleanValue swingThroughFoliage;

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
                            "既定 4800 = 4 分(randomTickSpeed=3 でのランダムティック成長の平均所要時間とほぼ同じ。10-31 に成長を 2 倍にして 9600 から半分)。")
                    .defineInRange("saplingOfflineGrowthTicks", 4800, 20, 72000000);

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
                            "0.0 で無効(従来どおり水で止まる)。川で汚染が止まらないようにするための機能。既定 0.01(2026-09-11 に 0.15 から 0.04 へ、2026-10-31 に 0.01 へ引き下げ。ユーザー: 湖に橋と、その背に生えた木が多すぎる)。")
                    .defineInRange("corruptionBridgeChance", 0.01D, 0.0D, 1.0D);
            corruptionBridgeMaxLength = b
                    .comment("橋で渡れる水面の最大の幅(ブロック)。これより広い水(海など)は渡らない。")
                    .defineInRange("corruptionBridgeMaxLength", 20, 2, 96);
            corruptionBridgeMaxCount = b
                    .comment("1 個体(blight_fern・汚染死体ブロック)が架けられる橋の最大本数。水辺に橋が並びすぎないための上限。既定 1(2026-10-31 に 3 から。ユーザー指定)。")
                    .defineInRange("corruptionBridgeMaxCount", 1, 0, 64);

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
                            "密林の空を覆うくらい木・下草を密集させたい場合はここを上げる(treeChance も参照)。",
                            "既定 0.9(2026-11-01 夜に 0.85 から。何も生えない上面を減らす。値は仮決め)。")
                    .defineInRange("growthChance", 0.9D, 0.0D, 1.0D);

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
                            "この輪の上には林冠の穴が開き、森を濃くしても光の筋が地面まで差し込む。0 で無し。既に生えた木は減らない。既定 0.2(2026-10-31 に 0.25 から。ユーザー「苗木の生えない場所が多い」)。")
                    .defineInRange("treeCanopyGapChance", 0.2D, 0.0D, 1.0D);
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
            // 2026-11-01 ユーザー決定「枝の継ぎ目を作る」(階段状の枝の隙間埋めと枝先の先細り)。既定 true は仮決め。
            branchJointTaper = b
                    .comment("汚染で育つ木の枝先の 1〜2 マスを、原木の代わりに枝の継ぎ目(overgrown_branch)にして先細りにする",
                            "(3 マス以上の枝は最後から 2 番目が太い・最後が細い、2 マスの枝は最後だけ細い)。既に生えた木は変わらない。既定 true。")
                    .define("branchJointTaper", true);
            branchJointFill = b
                    .comment("汚染で育つ木の斜めに進む枝の、辺か角でしか接していない原木の間を枝の継ぎ目(太い)で埋めて面で繋ぐ。",
                            "空いたマスにだけ置き、低い方・根元に近い方を優先する。既に生えた木は変わらない。既定 true。")
                    .define("branchJointFill", true);

            spawnUndevelopedBush = b
                    .comment("汚染した露出上面に「未発達の茂み」(undeveloped_bush)を生やすことがあるか。",
                            "実が付く下草で、実を右クリックで収穫すると「未発達な種子」が採れる。")
                    .define("spawnUndevelopedBush", true);

            undevelopedBushChance = b
                    .comment("上面に装飾植物の代わりに「未発達の茂み」を生やす確率。抽選は苗木の次に行う。",
                            "実際に生える確率は growthChance × undevelopedBushChance になる(既定値では約 6%)。",
                            "既定は treeChance(0.1)より少し低い 0.07 —— 苗木よりやや珍しい草、という位置づけ。")
                    .defineInRange("undevelopedBushChance", 0.07D, 0.0D, 1.0D);

            // 林床(2026-11-01)。どれも密林だけ。値は仮決め(下絵の配置の図: 敷物 5〜6 割・小葉類の株 1 チャンク 20〜30・倒木 0〜2 本)
            floorCarpetChance = b
                    .comment("林床(密林): 上面の下草の抽選に当たったマスのうち、小葉類の敷物を敷く割合(苗木・木生シダの次に引く)。",
                            "敷物を敷いたマスには、ほかの下草は生えない(小葉類の株だけ敷物付きで立つ)。仮決め 0.65(GameTest の平らな 1 チャンクで敷物 5 割強)。")
                    .defineInRange("floorCarpetChance", 0.65D, 0.0D, 1.0D);
            floorCarpetPlantChance = b
                    .comment("林床: 敷物の上に小葉類の株(敷物付きの 1 ブロック)を立てる確率。仮決め 0.15。")
                    .defineInRange("floorCarpetPlantChance", 0.15D, 0.0D, 1.0D);
            floorLycopsidChance = b
                    .comment("林床: 敷物にならなかったマスで、ほかの下草の代わりに小葉類の株(敷物なし)を立てる確率。仮決め 0.05。")
                    .defineInRange("floorLycopsidChance", 0.05D, 0.0D, 1.0D);
            floorUprightShare = b
                    .comment("林床: 小葉類の株のうち、2 段の「立ち上がる小葉類」にする割合。仮決め 0.15。")
                    .defineInRange("floorUprightShare", 0.15D, 0.0D, 1.0D);
            floorMedullosaChance = b
                    .comment("林床: 上面の下草の抽選でメドゥロサ(高さ 2〜3 のヤシに似た種子シダ)を立てる確率。0.02(11-02 ユーザー指定。前は仮の 0.03)。")
                    .defineInRange("floorMedullosaChance", 0.02D, 0.0D, 1.0D);
            floorPeatPatches = b
                    .comment("林床: 1 チャンクの泥炭の斑の最大数(1〜この数、各 3〜8 マス)。低地(海面 +4 以下)か水辺(5 ブロック以内に水)のマスだけ泥炭になる。0 で無し。")
                    .defineInRange("floorPeatPatches", 3, 0, 8);
            floorPeatPoolChance = b
                    .comment("林床: 泥炭の斑で、4 方を泥炭に囲まれた平らなマスを浅い水たまり(深さ 1〜2)にする確率。仮決め 0.6。")
                    .defineInRange("floorPeatPoolChance", 0.6D, 0.0D, 1.0D);
            floorFallenLogChance = b
                    .comment("林床: 1 チャンクに 2 つある倒木の枠それぞれに倒木を置く確率(0〜2 本)。仮決め 0.4。")
                    .defineInRange("floorFallenLogChance", 0.4D, 0.0D, 1.0D);

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
                    .comment("水中を汚染したとき、露出した水の面へ水中の植物(シャジクモ・垂れる根・水面の膜・",
                            "胞子昆布・浮き藻)を生やすか。水中の藻の覆いは覆いの絵(cover.json)。",
                            "陸上の装飾植物(spawnGrowthPlants)とは独立した設定。")
                    .define("spawnUnderwaterPlants", true);

            underwaterGrowthChance = b
                    .comment("水に接した露出上面 1 つあたりにシャジクモ(川・池。群落の外は生えない)を生やす確率。",
                            "陸上の growthChance に相当する。",
                            "既定 0.95(2026-09-22 に 0.85 から引き上げ。藻を減らしたぶん水草を増やす)。")
                    .defineInRange("underwaterGrowthChance", 0.95D, 0.0D, 1.0D);

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

            swampNoBushes = b
                    .comment("湿地・菌湿地帯になる場所では茂み(overgrown_bush の塊)を一切生やさないか。既定 true。")
                    .define("swampNoBushes", true);

            waterArchChanceMultiplier = b
                    .comment("水中に架かる・水面をまたぐ根のアーチの生成確率に掛ける倍率。陸のアーチの確率",
                            "(corruptionArchChance)にこれを掛ける。1.0 で陸と同じ、0 で水辺には架けない。既定 0.067(2026-09-22 に 0.2 から 3 分の 1 へ引き下げ)。")
                    .defineInRange("waterArchChanceMultiplier", 0.067D, 0.0D, 1.0D);

            // --- 洞窟の植物・構造物 ---
            caveGrowth = b
                    .comment("洞窟(地表より caveDepthMargin 以上深い場所)専用の構造物(天井の根のアーチ = giant_overgrown_log)を有効にするか。",
                            "密林(ジャングル)の洞窟では caveRootArchInJungle がオンのときだけ、湿地系の洞窟ではいつも建つ。",
                            "天井のアーチは caveVineArchShare の割合で、根ではなく葉に包まれた巨大な蔦になる。既定 true。")
                    .define("caveGrowth", true);

            caveDepthMargin = b
                    .comment("その列の地表(葉を除く MOTION_BLOCKING_NO_LEAVES)よりこの深さ(ブロック)以上低い場所を",
                            "洞窟とみなす。空に開けた谷や崖の縁を洞窟扱いしないための余裕。既定 5。")
                    .defineInRange("caveDepthMargin", 5, 1, 64);

            caveStructureChance = b
                    .comment("洞窟の汚染ブロック 1 つあたりに構造物(天井の根 / 蔦のアーチ)を建てようとする確率。",
                            "下面が露出しているときだけ建て、空間が足りなければ何も建てない。",
                            "既定 0.004(洞窟の壁 250 ブロックに 1 つ程度)。0 で構造物無し。")
                    .defineInRange("caveStructureChance", 0.004D, 0.0D, 1.0D);

            caveArchWeight = b
                    .comment("構造物の重み: 天井の根 / 蔦のアーチ(下面が露出しているとき)。既定 2。")
                    .defineInRange("caveArchWeight", 2, 0, 100);

            caveRootArchInJungle = b
                    .comment("密林(ジャングル)の洞窟にも天井のアーチ(根 / 蔦)を建てるか。既定 true。",
                            "湿地系の洞窟ではこのキーに関わらず建つ。")
                    .define("caveRootArchInJungle", true);

            caveStructurePlantChance = b
                    .comment("建てた構造物(天井の根 / 蔦のアーチ)の各ブロックの下面に垂れ植物を添える確率。既定 0.12。")
                    .defineInRange("caveStructurePlantChance", 0.12D, 0.0D, 1.0D);

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



            // フィロセンスのビームの数値は mobs.json の phyllosens.beam へ移した(2026-09-16)。
            // toml を手で開かなくても mod マネージャーの Mob ステータスから調整できる。




















            corpseTendrilInitialCount = b
                    .comment("汚染死体ブロックが起点から放射状に伸ばす「根」の本数。",
                            "blight_fern が使う tendrilInitialCount とは別の値。")
                    .defineInRange("corpseTendrilInitialCount", 4, 1, 16);

            corpseTendrilMaxActive = b
                    .comment("汚染死体ブロックの触手が同時に存在できる最大本数。",
                            "blight_fern が使う tendrilMaxActive とは別の値。")
                    .defineInRange("corpseTendrilMaxActive", 24, 1, 256);

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
                    .comment("胞子噴出中、噴出エフェクトの中心からこのチャンク半径以内(水平)のランダムな地表に",
                            "汚染シダ(水中なら汚染水草)を湧かせる。既定 3(= 半径 48 ブロック)。0 でこのハザードを無効化。")
                    .defineInRange("quakeEruptHazardRadiusChunks", 3, 0, 16);

            quakeEruptFernIntervalTicks = b
                    .comment("噴出点周囲にランダムな汚染シダを湧かせる判定の実行間隔(tick)。既定 80 = 4 秒。")
                    .defineInRange("quakeEruptFernIntervalTicks", 80, 1, 2400);

            quakeEruptFernAttemptsPerInterval = b
                    .comment("上記1回あたりの汚染シダ出現の試行回数。既定 6。")
                    .defineInRange("quakeEruptFernAttemptsPerInterval", 6, 1, 64);

            quakeEruptFernMaxNearby = b
                    .comment("ハザード半径内の汚染シダがこの数に達したら、それ以上は湧かせない(積み上がり防止)。既定 48。")
                    .defineInRange("quakeEruptFernMaxNearby", 48, 0, 4000);

            quakeEruptionCorruptionMultiplier = b
                    .comment("胞子噴出エフェクトが出ている間(噴出開始〜quakeEruptDurationTicks)、汚染シダ・汚染死体などの",
                            "侵食速度(1サイクルの置き換えブロック数)に掛かる倍率。既定 5.0。1.0 で効果なし。",
                            "corruptionSpeedMultiplier とは別枠で、この期間だけ上乗せで掛かる。")
                    .defineInRange("quakeEruptionCorruptionMultiplier", 5.0D, 0.0D, 100.0D);

            quakeShakeIntensity = b
                    .comment("(クライアント描画)画面の揺れの最大強度。0 で揺れなし、既定 1.0。")
                    .defineInRange("quakeShakeIntensity", 1.0D, 0.0D, 4.0D);

            quakeShakeRadius = b
                    .comment("(クライアント描画)震央からこの距離(ブロック)離れるほど画面の揺れが弱まる。",
                            "ただし下限があるので、遠くのプレイヤーにも地震は伝わる。既定 512。")
                    .defineInRange("quakeShakeRadius", 512, 32, 8192);

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
        }
    }

    private OvergrownVegetationConfig() {
    }
}
