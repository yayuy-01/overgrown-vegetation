package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 木が生え終わった直後に、近くにある別の木との間へ密林のツル植物のような
 * 連結蔦(リアナ、{@link ModBlocks#OVERGROWN_LIANA})を張る。
 *
 * <h2>「他の木」の判別方法</h2>
 * <p>「今生えたばかりの木」を構成する原木ブロック(幹+枝の座標、{@code ownLogPositions}) は
 * 呼び出し側({@link ForcedTreeFeature})がまさに置いた瞬間の座標をそのまま渡してくれるので、
 * ここでは何も推測する必要が無い。周囲を走査して見つかった {@link ModBlocks#OVERGROWN_LOG}
 * のうち、この集合に含まれないものは「呼び出しより前に(=別の成長イベントで)存在していた
 * ブロック」だと確定するため、自動的に「他の木」だけを対象にできる
 * (幹と枝を区別する必要が無く、ヒューリスティックな推測も一切不要)。
 *
 * <h2>蔦の実体</h2>
 * <p>蔦1本につき {@link ModBlocks#OVERGROWN_LIANA} ブロックを1個だけ(始点側の幹の近くの
 * 空いたマス)に置き、実際の始点・終点(=両方の木の原木ブロックそのものの絶対座標)と
 * 垂れ具合(sag)は {@link CorruptedLianaBlockEntity} に持たせる。ブロックを経路上に何個も
 * 並べる必要が無いので、曲線の見た目は完全にクライアント側の
 * {@code com.yayuy.overgrownvegetation.client.CorruptedLianaRenderer}(バニラのリードと同じ、
 * 格子に縛られないポリゴン描画)に任せられる。水平方向は常に始点・終点を結ぶ直線1本
 * (曲線補間・折れ線化は一切しない)で、垂直方向の垂れ(sag)だけが曲線。
 *
 * <h2>長距離接続の「束ね」とぶら下がり蔦(swag)({@link #tryConnect} 参照)</h2>
 * <p>始点・終点の距離が {@code vineStackLengthThreshold} を超える場合、1本だけでは頼りなく
 * 見えるため、同じ始点・終点の組み合わせに対して蔦を重ねて生成する。本数は
 * {@code vineStackMinCount}〜{@code vineStackMaxCount}(既定2〜6)の範囲を
 * {@code vineStackCountWeight2}〜{@code vineStackCountWeight6}(既定 80:15:5:2:1)の比で
 * 重み付けして抽選する(本数が多いほど単調に生成されにくくなる)ため、
 * 束が重厚になりすぎないようにしている({@link #pickStackCount} 参照)。各蔦は垂れ具合(sag)を
 * 必ず異なる値にする({@link #pickDistinctSags} 参照)。主アンカーは1マスに1個しか置けないため、
 * {@link #findMarkerPosition} で束ねる本数ぶんの空いたマスを探す。
 *
 * <p>垂れ具合が一番小さい1本だけは通常通り、木の原木ブロックである始点・終点を直接結ぶ
 * (ブンティング飾りの「主線のワイヤー」に相当)。この主線は**必ず最初に配置を試み、失敗したら
 * その組み合わせ全体を諦める**({@link #tryConnect} 参照)——主線が存在しないのに
 * ぶら下がり蔦だけが浮いて生成されてしまう不具合を防ぐため(2026-08-24 に発覚・修正。
 * 以前は本数ぶんのループを1回でも失敗すると即座に打ち切っていたため、シャッフルされた順序次第で
 * 主線より先にぶら下がり蔦だけが配置され、直後に主線の配置が失敗してループが打ち切られると、
 * 主線の無い浮いたぶら下がり蔦だけが残ってしまうことがあった)。以降、この主線を**「第一の蔦」**、
 * 残りの(垂れ具合が大きい)蔦を**「重複蔦」**と呼ぶ——木の原木には一切届かない、蔦から生えた
 * 蔦のこと({@link CorruptedLianaBlockEntity#hasSwag()} 参照)。1本の重複蔦の配置に
 * 失敗しても(周囲が塞がっている等)他の重複蔦の生成は継続する。
 *
 * <h2>重複蔦の分類:第二の蔦/第三の蔦(2026-08-25、{@link #pickDuplicateTier} 参照)</h2>
 * <p>「またぐ」({@link #pickTwoDistinctCurves} 参照)に該当しなかった重複蔦は、
 * {@link DuplicateTier} の4分類のどれになるかを重み付き抽選({@code vineSecondTierShareStartWeight}
 * : {@code vineSecondTierShareEndWeight} : {@code vineSecondTierFloatingWeight} :
 * {@code vineThirdTierWeight}、既定 15:15:60:10)で決める。
 * <ul>
 *     <li>{@code SHARE_START}: 始点が第一の蔦の実際の始点そのもの、終点が第一の蔦の曲線上の点。</li>
 *     <li>{@code SHARE_END}: 終点が第一の蔦の実際の終点そのもの、始点が第一の蔦の曲線上の点。</li>
 *     <li>{@code FLOATING}: 始点・終点のどちらも第一の蔦の曲線上の点(従来からの既定の
 *         垂れ方、{@link #pickSwagEndpoints} 参照)。</li>
 *     <li>{@code THIRD_TIER}(**第三の蔦**): 第一の蔦からではなく、既にこの束で配置済みの
 *         {@code SHARE_START}/{@code SHARE_END}/{@code FLOATING} のいずれか(=**第二の蔦**)
 *         からランダムに1本選び、その曲線上に浮く({@link #pickSwagEndpoints} を流用)。
 *         第三の蔦からさらに孫(第四の蔦)が生えることはない(第三で打ち止め)。</li>
 * </ul>
 * <p>{@code THIRD_TIER} は、この束における重複蔦の総本数が {@code vineThirdTierMinDuplicateCount}
 * (既定4)以上、かつ既に第二の蔦が1本以上配置済みの場合にのみ候補に入る——それ以外の場合は
 * 残り3分類だけで抽選する({@link #pickDuplicateTier} のフラグ引数参照)。
 *
 * <h2>終点が既存の蔦になる場合(2026-08-24、{@link #findExistingVineAnchors} 参照)</h2>
 * <p>{@link #tryConnect} が終点候補を集める際、見つかった候補(=木の原木)ごとに
 * {@code vineExistingVineTargetChance} の確率で、その代わりに近くの**既存の蔦**(既に配置済みの
 * 別の {@link CorruptedLianaBlockEntity} 主アンカー——通常の蔦・重複蔦のどちらでもよい)の
 * 曲線上のランダムな1点を終点候補として差し替える。これにより蔦が木同士だけでなく、
 * 既存の蔦を経由してさらに別の蔦へ繋がる連鎖が生まれる。実際に配置する際は、この場合も
 * (木の原木のような格子上の座標ではなく)実座標どうしを結ぶ必要があるため、
 * {@link #placeSwagVine} をそのまま流用する(意味的には「主線」だが、両端が格子に
 * 縛られない実座標である点は重複蔦と同じ仕組みで足りるため)。
 *
 * <h2>重複蔦が別の重複蔦をまたぐ場合(2026-08-24、{@link #pickTwoDistinctCurves} 参照)</h2>
 * <p>{@link #tryConnect} が重複蔦を1本生成するたびに、その組み合わせで**既に配置済みの曲線**
 * (主線、または既に置いた他の重複蔦)を {@link CurveRef} のリストとして蓄積しておく。
 * プールに2本以上溜まっている場合、{@code vineNestedCrossChance} の確率で、新しい重複蔦の
 * 両端を「同じ1本の親の曲線上の2点」ではなく「プールから無作為に選んだ**異なる2本の曲線**
 * それぞれの上の1点ずつ」にする——これにより、重複蔦が単に親から垂れるだけでなく、
 * 別の重複蔦(や主線)をまたぐように斜めに架け渡されることがある。プールは同じ始点・終点の
 * 組み合わせ(=同じ束)の中でのみ共有され、束ごとにリセットされる(遠く離れた無関係な蔦同士が
 * またがってしまうのを防ぐため)。この「またぐ」判定は、下記「重複蔦の分類」の4択とは
 * 完全に独立した別枠の判定(2026-08-25、ユーザーの明示的な選択により両立させている)——
 * 「またぐ」に該当した重複蔦は第二/第三のどちらにも分類されず、プールには加わるが
 * 第三の蔦の親候補にはならない。
 *
 * <h2>発光する垂れ蔦(2026-08-24、{@link #placeDroops} 参照)</h2>
 * <p>主リボンの経路上に生える垂れ蔦(droop、見た目自体は
 * {@code com.yayuy.overgrownvegetation.client.CorruptedLianaRenderer} が描く)の生成位置
 * (どの分割点に、どんな長さで生えるか)は、以前はクライアント側のレンダラーが座標由来の
 * 決定的な乱数列を毎フレーム消費して導出していたが、{@code vineDroopLightChance} の確率で
 * 実際に光源として機能する垂れ蔦(見た目は overgrown_liana_droop_light)を導入するにあたり、
 * サーバー側の {@link #placeDroops} が生成時に1回だけ決定するよう変更した——光の伝播は
 * サーバー権威のワールドデータに基づくため、クライアント専用の描画ロジックだけでは実際に
 * 世界を明るくできない。決定した結果(分割点の添字・長さ・発光するかどうか)は
 * {@link CorruptedLianaBlockEntity} に保存してクライアントへ同期し、発光すると決まった
 * 垂れ蔦の分だけ、見えない光源ブロック({@link Blocks#LIGHT}、光レベル
 * {@code vineDroopLightLevel})をその垂れ蔦の先端付近に実際に置く。
 *
 * <h2>親を切ったら子も連鎖して消える(2026-08-24、{@link #registerParentChild} 参照)</h2>
 * <p>「終点が既存の蔦になる場合」「重複蔦」のどちらも、生成された蔦は必ず**依存元の主アンカー**
 * (通常1個、別の重複蔦をまたいだ場合のみ2個)を持つ。この蔦を実際に配置するたびに
 * {@link #registerParentChild} を呼び、子側の {@link CorruptedLianaBlockEntity#setParentPositions}
 * と親側の {@link CorruptedLianaBlockEntity#addChildPosition} を同時に設定して双方向のリンクを
 * 作る。これにより {@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlock#cut} が
 * 依存元の蔦を切った際、依存している蔦(たち)を再帰的に連鎖除去できる——「元の蔦が切られたら、
 * そこから生えていた蔦も一緒に落ちる」という直感どおりの見た目になる。
 */
public final class TreeVineConnector {

    /** 周囲の走査でこれだけ候補が見つかったら打ち切る(密林での負荷対策)。 */
    private static final int MAX_CANDIDATES = 48;

    /**
     * 垂れ具合(sag)の段階数。{@code vineSagMin}(段階1)〜{@code vineSagMax}(段階{@code SAG_TIERS})を
     * 均等に分けたどれかを蔦ごとに選ぶ({@link #pickDistinctSags} 参照)。{@code vineStackMaxCount}
     * の既定値(6)と同じにしてある——1つの束の全蔦(第一の蔦含む)の垂れ具合を互いに重複しない
     * 値にする必要があるため、{@code vineStackMaxCount} をこれより大きくする場合は、ここも
     * 合わせて引き上げないと重複しない値が足りなくなる({@link #pickDistinctSags} 参照)。
     */
    private static final int SAG_TIERS = 6;

    /**
     * 主アンカーを置く候補マスの優先順(始点から見た相対オフセット、進行方向1歩ぶんへの加算値)。
     * 先頭ほど優先。同じ始点・終点の組み合わせで複数本の蔦を束ねて生成する場合、1本目は
     * 従来通り {@code (0,0,0)}(=進行方向そのもの)、2本目以降は既に使われているマス・
     * 塞がっているマスを避けて次の候補を順に試す({@link #findMarkerPosition} 参照)。
     */
    private static final int[][] MARKER_OFFSET_JITTERS = {
            {0, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {1, 1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, -1},
    };

    /**
     * ぶら下がり蔦(swag)の両端を、一番小さい蔦の曲線上のどの割合(0=始点、1=終点)から
     * 選ぶかの範囲。両端に近すぎるとぶら下がって見えないので、中央寄りの範囲に限定する
     * ({@link #pickSwagEndpoints} 参照)。
     */
    private static final float SWAG_T_MIN = 0.15F;
    private static final float SWAG_T_MAX = 0.85F;
    /** ぶら下がり蔦の両端(経路上の割合)が最低限これだけ離れるようにする(潰れた点にならないため)。 */
    private static final float SWAG_MIN_SEPARATION = 0.15F;

    /**
     * 主リボンの経路を何分割して垂れ蔦(droop)の候補地点を歩くか。クライアント側の
     * {@code CorruptedLianaRenderer} の描画分割数(定数 {@code SEGMENTS})と同じ値でなければ
     * ならない——ここで決めた添字(0..{@link #DROOP_SEGMENTS})がそのままレンダラーの
     * 分割点添字として使われるため({@link #placeDroops} 参照)。
     */
    private static final int DROOP_SEGMENTS = 24;
    /**
     * 垂れ蔦の"候補地点"どうしの間隔({@link #DROOP_SEGMENTS} のうちいくつ進むか)の最小・最大値。
     * 1本ごとにこの範囲でランダムに次の間隔を選ぶため、蔦の生える間隔が格子状に揃わずランダムになる。
     */
    private static final int DROOP_STRIDE_MIN = 2;
    private static final int DROOP_STRIDE_MAX = 6;
    /** 候補地点のうち、実際に垂れ蔦を生やす確率。 */
    private static final double DROOP_CHANCE = 0.45D;
    private static final float DROOP_MIN_LENGTH = 0.3F;
    private static final float DROOP_MAX_LENGTH = 0.9F;

    /**
     * 既に配置済みの1本の蔦(主線、または重複蔦)の曲線を表す、「またぐ」判定
     * ({@link #pickTwoDistinctCurves})専用の軽量な参照。始点・終点は常に実座標(Vec3)——
     * 主線でも木の原木ブロックの中心座標を渡せばよく、重複蔦ならその swagStart/swagEnd を
     * そのまま渡せる(どちらも {@link #curvePointOnLine} に食わせられる形式は同じ)。
     *
     * @param ownerMarker この曲線を持つ蔦自身の主アンカーの座標。「まただ」判定で選ばれた際、
     *                    新しく生成する蔦の親としてこの座標を登録する
     *                    ({@link #registerParentChild} 参照——後で親を切ったときに子として
     *                    連鎖除去できるようにするため)。
     */
    private record CurveRef(Vec3 start, Vec3 end, float sag, BlockPos ownerMarker) {
    }

    private TreeVineConnector() {
    }

    /**
     * プレイヤーが「蔓」アイテム({@link com.yayuy.overgrownvegetation.item.LianaItem})で
     * 始点・終点を指定して張る1本を配置する。世界生成の {@link #tryConnect} が候補を選び終えた
     * あとにやっていることだけを、抽選なしでそのまま実行する版。
     *
     * <p>垂れ具合だけは毎回変わったほうが自然なので、世界生成と同じ {@link #pickDistinctSags}
     * から1つ引く(設定 {@code vineSagMin}〜{@code vineSagMax} の段階)。垂れ蔦・蔦の実の
     * 発光確率も設定どおりで、自然に生えた蔦と全く同じものができる。
     *
     * @param level  ブロックを置くレベル
     * @param source 始点。プレイヤーが1回目に右クリックしたブロックそのもの
     * @param target 終点。プレイヤーが2回目に右クリックしたブロックそのもの
     * @param random 乱数
     * @return 置けたら true。始点のまわりにアンカーを置ける隙間が無ければ false(何も置かない)
     */
    public static boolean placeByHand(ServerLevel level, BlockPos source, BlockPos target, RandomSource random) {
        BlockPos markerPos = findMarkerPosition(level, source, target, new LongOpenHashSet());
        if (markerPos == null) {
            return false;
        }
        int sagMin = OvergrownVegetationConfig.COMMON.vineSagMin.get();
        int sagMax = OvergrownVegetationConfig.COMMON.vineSagMax.get();
        float sag = pickDistinctSags(sagMin, sagMax, 1, random).get(0);
        placeVine(level, markerPos, source, target, sag,
                OvergrownVegetationConfig.COMMON.vineDroopLightChance.get(),
                OvergrownVegetationConfig.COMMON.vineDroopLightLevel.get(), random);
        return level.getBlockEntity(markerPos) instanceof CorruptedLianaBlockEntity;
    }

    /**
     * {@link ForcedTreeFeature#place} の末尾から呼ぶこと。
     *
     * @param level           実際にブロックを置く/調べるレベル
     * @param random          乱数(呼び出し側の {@code context.random()} をそのまま渡せばよい)
     * @param searchCenter    近隣を探索する際の中心(苗木があった位置 = 木の根本)
     * @param ownLogPositions 今回の成長で置いたばかりの原木ブロック(幹+枝)の座標一覧
     */
    public static void tryConnect(ServerLevel level, RandomSource random, BlockPos searchCenter,
            LongOpenHashSet ownLogPositions) {
        if (!OvergrownVegetationConfig.COMMON.spawnConnectingVines.get() || ownLogPositions.isEmpty()) {
            return;
        }

        // 木ごとに、そもそも連結蔦の生成を試みるか(vineMinPerTree の下限保証より前に判定)。
        // これに外れた木からは蔦が一切生えない——全体の蔦の量をおよそこの確率倍に絞るためのゲート。
        double perTreeChance = OvergrownVegetationConfig.COMMON.vinePerTreeChance.get();
        if (perTreeChance <= 0.0D || random.nextDouble() >= perTreeChance) {
            return;
        }

        int minLength = OvergrownVegetationConfig.COMMON.vineMinLength.get();
        int maxLength = OvergrownVegetationConfig.COMMON.vineMaxLength.get();
        int maxHeightDiff = OvergrownVegetationConfig.COMMON.vineMaxHeightDiff.get();
        int minGroundClearance = OvergrownVegetationConfig.COMMON.vineMinGroundClearance.get();
        int maxGroundClearance = OvergrownVegetationConfig.COMMON.vineMaxGroundClearance.get();
        int bandLowMax = OvergrownVegetationConfig.COMMON.vineHeightBandLowMax.get();
        int bandMidMax = OvergrownVegetationConfig.COMMON.vineHeightBandMidMax.get();
        double weightLow = OvergrownVegetationConfig.COMMON.vineHeightWeightLow.get();
        double weightMid = OvergrownVegetationConfig.COMMON.vineHeightWeightMid.get();
        double weightHigh = OvergrownVegetationConfig.COMMON.vineHeightWeightHigh.get();
        int minPerTree = OvergrownVegetationConfig.COMMON.vineMinPerTree.get();
        int maxPerTree = OvergrownVegetationConfig.COMMON.vineMaxPerTree.get();
        double chance = OvergrownVegetationConfig.COMMON.vineChance.get();
        double existingVineTargetChance = OvergrownVegetationConfig.COMMON.vineExistingVineTargetChance.get();
        double existingVineTargetEndMargin = OvergrownVegetationConfig.COMMON.vineExistingVineTargetEndMargin.get();
        int sagMin = OvergrownVegetationConfig.COMMON.vineSagMin.get();
        int sagMax = OvergrownVegetationConfig.COMMON.vineSagMax.get();
        int stackLengthThreshold = OvergrownVegetationConfig.COMMON.vineStackLengthThreshold.get();
        int stackMinCount = OvergrownVegetationConfig.COMMON.vineStackMinCount.get();
        int stackMaxCount = OvergrownVegetationConfig.COMMON.vineStackMaxCount.get();
        double stackCountWeight2 = OvergrownVegetationConfig.COMMON.vineStackCountWeight2.get();
        double stackCountWeight3 = OvergrownVegetationConfig.COMMON.vineStackCountWeight3.get();
        double stackCountWeight4 = OvergrownVegetationConfig.COMMON.vineStackCountWeight4.get();
        double stackCountWeight5 = OvergrownVegetationConfig.COMMON.vineStackCountWeight5.get();
        double stackCountWeight6 = OvergrownVegetationConfig.COMMON.vineStackCountWeight6.get();
        double nestedCrossChance = OvergrownVegetationConfig.COMMON.vineNestedCrossChance.get();
        double secondTierShareStartWeight = OvergrownVegetationConfig.COMMON.vineSecondTierShareStartWeight.get();
        double secondTierShareEndWeight = OvergrownVegetationConfig.COMMON.vineSecondTierShareEndWeight.get();
        double secondTierFloatingWeight = OvergrownVegetationConfig.COMMON.vineSecondTierFloatingWeight.get();
        double thirdTierWeight = OvergrownVegetationConfig.COMMON.vineThirdTierWeight.get();
        int thirdTierMinDuplicateCount = OvergrownVegetationConfig.COMMON.vineThirdTierMinDuplicateCount.get();
        double droopLightChance = OvergrownVegetationConfig.COMMON.vineDroopLightChance.get();
        int droopLightLevel = OvergrownVegetationConfig.COMMON.vineDroopLightLevel.get();
        if (maxPerTree <= 0 || chance <= 0.0D || maxLength <= 0) {
            return;
        }
        // 木ごとに実際に張る本数の上限を [vineMinPerTree, vineMaxPerTree] からランダムに決める
        // (常に vineMaxPerTree 本ぴったりになってしまうと単調に見えるため)。
        int lowPerTree = Math.min(minPerTree, maxPerTree);
        int highPerTree = Math.max(minPerTree, maxPerTree);
        int targetPerTree = lowPerTree + random.nextInt(highPerTree - lowPerTree + 1);
        if (targetPerTree <= 0) {
            return;
        }

        List<BlockPos> ownLogs = new ArrayList<>(ownLogPositions.size());
        int ownMinY = Integer.MAX_VALUE;
        int ownMaxY = Integer.MIN_VALUE;
        LongIterator it = ownLogPositions.iterator();
        while (it.hasNext()) {
            BlockPos pos = BlockPos.of(it.nextLong());
            ownLogs.add(pos);
            ownMinY = Math.min(ownMinY, pos.getY());
            ownMaxY = Math.max(ownMaxY, pos.getY());
        }

        // 地面から近すぎる/高すぎる候補(低木の根本や、地面から離れすぎた枝など)は始点・終点として使わない。
        List<BlockPos> eligibleOwnLogs = filterByGroundClearance(level, ownLogs, minGroundClearance, maxGroundClearance);
        if (eligibleOwnLogs.isEmpty()) {
            return;
        }

        // 木の原木・既存の蔦の主アンカーの両方を、探索範囲が完全に同じなので1回の走査で
        // まとめて探す(以前は2回の完全に別々の走査だった——{@link #findCandidates} の
        // Javadoc「同じマスに対してブロック状態を2回読む無駄」参照)。
        boolean collectExistingVines = existingVineTargetChance > 0.0D;
        List<BlockPos> targets = new ArrayList<>(MAX_CANDIDATES);
        List<BlockPos> vineAnchors = new ArrayList<>(collectExistingVines ? MAX_CANDIDATES : 0);
        findCandidates(level, searchCenter, ownLogPositions, maxLength, ownMinY, ownMaxY,
                collectExistingVines, random, targets, vineAnchors);

        // 終点候補には木の原木だけでなく、近くの既存の蔦(既に配置済みの別の主アンカー)の
        // 曲線上のランダムな1点も、vineExistingVineTargetChance の確率で混ぜる。丸めた
        // BlockPos を以降のパイプライン(距離・地面クリアランス・高さ帯)にそのまま乗せつつ、
        // 実際に配置する際に使う実座標(Vec3)は exactVineTargets に、依存元(=親)の主アンカー
        // 座標は exactVineTargetParents に控えておく(蔦の曲線上の点は必ずしもブロック格子に
        // 一致しないうえ、親子リンク登録に親の座標が必要なため)。
        Map<BlockPos, Vec3> exactVineTargets = new HashMap<>();
        Map<BlockPos, BlockPos> exactVineTargetParents = new HashMap<>();
        for (BlockPos anchorPos : vineAnchors) {
            if (random.nextDouble() >= existingVineTargetChance) {
                continue;
            }
            if (!(level.getBlockEntity(anchorPos) instanceof CorruptedLianaBlockEntity existingLiana)) {
                continue;
            }
            Vec3 curveSource = existingLiana.hasSwag() ? existingLiana.getSwagStart()
                    : Vec3.atCenterOf(existingLiana.getSourcePos());
            Vec3 curveTarget = existingLiana.hasSwag() ? existingLiana.getSwagEnd()
                    : Vec3.atCenterOf(existingLiana.getTargetPos());
            // 接続点は既存の蔦の端(始点・終点周辺)を避け、中央寄りの区間からだけ選ぶ。
            // vineExistingVineTargetEndMargin(既定0.25)ぶんを両端から除外する。ただし従来の
            // 「端すれすれは常に避ける」制約(SWAG_T_MIN〜SWAG_T_MAX)より狭くなる場合のみ効かせる。
            float tLow = Math.max(SWAG_T_MIN, (float) existingVineTargetEndMargin);
            float tHigh = Math.min(SWAG_T_MAX, 1.0F - (float) existingVineTargetEndMargin);
            if (tLow >= tHigh) {
                tLow = tHigh = 0.5F;
            }
            float t = tLow + (tHigh - tLow) * random.nextFloat();
            Vec3 point = curvePointOnLine(curveSource, curveTarget, existingLiana.getSag(), t);
            BlockPos rounded = BlockPos.containing(point);
            exactVineTargets.put(rounded, point);
            exactVineTargetParents.put(rounded, anchorPos);
            targets.add(rounded);
        }

        if (targets.isEmpty()) {
            return;
        }
        List<BlockPos> eligibleTargets = filterByGroundClearance(level, targets, minGroundClearance, maxGroundClearance);
        if (eligibleTargets.isEmpty()) {
            return;
        }
        // 単純なシャッフルではなく、地面からの高さ帯(低・中・高)に重みを付けた順序で並べ替える
        // ことで、蔦の生える高さの分布(既定 50:30:20)を実現する。
        List<BlockPos> orderedTargets = weightedOrderByHeightBand(level, eligibleTargets,
                maxGroundClearance, bandLowMax, bandMidMax, weightLow, weightMid, weightHigh, random);

        int placed = 0;
        for (BlockPos target : orderedTargets) {
            if (placed >= targetPerTree) {
                break;
            }
            if (random.nextDouble() >= chance) {
                continue;
            }

            BlockPos anchor = randomWithinRange(eligibleOwnLogs, target, minLength, maxLength, maxHeightDiff, random);
            if (anchor == null) {
                // この target からは [minLength, maxLength] の範囲内(かつ高低差 maxHeightDiff 以内)に
                // 今回の木の原木が無い(探索半径の都合上の取りこぼしを含む)
                continue;
            }

            // 距離が長い(vineStackLengthThreshold 超)場合は、1本だけでは頼りなく見えるため
            // 複数本重ねて生成する。本数は2本に大きく偏った重み付き抽選(既定 80:15:5)。
            // 垂れ具合(sag)は必ず互いに異なる値にする。
            int strandCount = 1;
            double distSq = anchor.distSqr(target);
            if (stackMaxCount > 1 && distSq > (double) stackLengthThreshold * stackLengthThreshold) {
                strandCount = pickStackCount(stackMinCount, stackMaxCount, stackCountWeight2, stackCountWeight3,
                        stackCountWeight4, stackCountWeight5, stackCountWeight6, random);
            }

            List<Float> sags = pickDistinctSags(sagMin, sagMax, strandCount, random);
            // 垂れ具合が一番小さい1本だけは木の原木を直接結ぶ通常の蔦(主線)。残りはそれぞれ独立に、
            // その主線の曲線上のランダムな2点を自分だけの始点・終点にする「ぶら下がり蔦」。
            float smallestSag = sags.get(0);
            for (float s : sags) {
                smallestSag = Math.min(smallestSag, s);
            }

            LongOpenHashSet usedMarkers = new LongOpenHashSet();

            // 主線は必ず最初に配置を試み、失敗したらこの組み合わせ全体を諦める(continue)。
            // 主線抜きでぶら下がり蔦だけが浮いて生成されるのを防ぐため
            // (2026-08-24 に発覚・修正——詳細はクラスの Javadoc 参照)。
            BlockPos primaryMarker = findMarkerPosition(level, anchor, target, usedMarkers);
            if (primaryMarker == null) {
                continue;
            }
            usedMarkers.add(primaryMarker.asLong());

            Vec3 anchorVec = Vec3.atCenterOf(anchor);
            // target が既存の蔦の曲線上の点として選ばれていた場合、実際の配置には
            // (木の原木のような格子座標ではなく)その実座標を使う必要があるため、
            // placeVine ではなく placeSwagVine を流用する(両端が実座標である点さえ
            // 満たせば、意味上「主線」でも swag と全く同じ仕組みで描画できるため)。
            Vec3 exactTarget = exactVineTargets.get(target);
            Vec3 targetVec = exactTarget != null ? exactTarget : Vec3.atCenterOf(target);
            if (exactTarget != null) {
                placeSwagVine(level, primaryMarker, anchorVec, exactTarget, smallestSag,
                        droopLightChance, droopLightLevel, random);
                // 既存の蔦を終点にした場合、この蔦はその既存の蔦に依存している——依存元(親)が
                // 切られたら道連れで消えるよう、親子リンクを登録する({@link #registerParentChild} 参照)。
                registerParentChild(level, primaryMarker, List.of(exactVineTargetParents.get(target)));
            } else {
                placeVine(level, primaryMarker, anchor, target, smallestSag, droopLightChance, droopLightLevel, random);
            }
            placed++;

            // この組み合わせ(anchor→target)で配置した曲線のプール。重複蔦を生成するたびに
            // 追加され、後続の重複蔦が「別の重複蔦をまたぐ」際の候補になる
            // ({@link #pickTwoDistinctCurves} 参照)。束(=同じ組み合わせ)ごとにリセットする。
            List<CurveRef> placedCurves = new ArrayList<>();
            placedCurves.add(new CurveRef(anchorVec, targetVec, smallestSag, primaryMarker));
            // placedCurves のうち「第二の蔦」(第一の蔦=主線から直接生えたもの)だけを控えた
            // 部分集合。「第三の蔦」(2026-08-25追加)がどの曲線から生えるかを選ぶ候補になる
            // ({@link #pickDuplicateTier} 参照)。「またぐ」で生成された蔦・第三の蔦自身は
            // ここには入れない(第三から先の孫は生やさない、「第三で打ち止め」の方針)。
            List<CurveRef> secondTierCurves = new ArrayList<>();
            // この束における重複蔦(主線を除く)の総本数。vineThirdTierMinDuplicateCount 未満なら
            // 第三の蔦の選択肢そのものが候補から除外される({@link #pickDuplicateTier} 参照)。
            int duplicateCount = strandCount - 1;

            for (float sag : sags) {
                if (sag == smallestSag) {
                    // 主線として既に配置済み。同じ値のsagが複数あることは pickDistinctSags の
                    // 保証上あり得ないので、continue で十分(smallestPlaced フラグは不要)。
                    continue;
                }
                // 1本のぶら下がり蔦の配置に失敗しても(周囲が塞がっている等)、他のぶら下がり蔦の
                // 生成は継続する(以前は break していたため、これも浮いた蔦の一因になり得た)。
                Vec3 swagStart;
                Vec3 swagEnd;
                List<BlockPos> parents;
                boolean placedAsSecondTier = false;
                if (placedCurves.size() >= 2 && random.nextDouble() < nestedCrossChance) {
                    // 別の重複蔦(または主線)をまたぐ:両端をそれぞれ異なる既存の曲線から選ぶ。
                    // またいだ2本どちらに依存しているとみなす(どちらが切られても道連れで消える)。
                    // この「またぐ」判定は下記の第二/第三の蔦の4択とは独立した別枠
                    // (ユーザーの明示的な選択、2026-08-25)。
                    CurveRef[] pair = pickTwoDistinctCurves(placedCurves, random);
                    swagStart = curvePointOnLine(pair[0].start(), pair[0].end(), pair[0].sag(), randomSwagT(random));
                    swagEnd = curvePointOnLine(pair[1].start(), pair[1].end(), pair[1].sag(), randomSwagT(random));
                    parents = List.of(pair[0].ownerMarker(), pair[1].ownerMarker());
                } else {
                    // 「またぐ」に該当しなかった重複蔦は、第一の蔦(主線)の始点/終点をそのまま
                    // 共有する・第一の蔦の曲線上に浮く(従来の見た目)・既に配置済みの第二の蔦から
                    // 生える(第三の蔦)、の4択で決める(2026-08-25、{@link #pickDuplicateTier} 参照)。
                    boolean thirdTierEligible = duplicateCount >= thirdTierMinDuplicateCount && !secondTierCurves.isEmpty();
                    DuplicateTier tier = pickDuplicateTier(thirdTierEligible, secondTierShareStartWeight,
                            secondTierShareEndWeight, secondTierFloatingWeight, thirdTierWeight, random);
                    switch (tier) {
                        case SHARE_START -> {
                            swagStart = anchorVec;
                            swagEnd = curvePointOnLine(anchorVec, targetVec, smallestSag, randomSwagT(random));
                            parents = List.of(primaryMarker);
                            placedAsSecondTier = true;
                        }
                        case SHARE_END -> {
                            swagStart = curvePointOnLine(anchorVec, targetVec, smallestSag, randomSwagT(random));
                            swagEnd = targetVec;
                            parents = List.of(primaryMarker);
                            placedAsSecondTier = true;
                        }
                        case THIRD_TIER -> {
                            CurveRef parentCurve = secondTierCurves.get(random.nextInt(secondTierCurves.size()));
                            Vec3[] endpoints = pickSwagEndpoints(parentCurve.start(), parentCurve.end(), parentCurve.sag(), random);
                            swagStart = endpoints[0];
                            swagEnd = endpoints[1];
                            parents = List.of(parentCurve.ownerMarker());
                        }
                        default -> {
                            // FLOATING: 従来通り、両端とも第一の蔦の曲線上のランダムな2点。
                            Vec3[] endpoints = pickSwagEndpoints(anchorVec, targetVec, smallestSag, random);
                            swagStart = endpoints[0];
                            swagEnd = endpoints[1];
                            parents = List.of(primaryMarker);
                            placedAsSecondTier = true;
                        }
                    }
                }
                BlockPos markerPos = findMarkerPosition(level,
                        BlockPos.containing(swagStart), BlockPos.containing(swagEnd), usedMarkers);
                if (markerPos == null) {
                    continue;
                }
                usedMarkers.add(markerPos.asLong());
                placeSwagVine(level, markerPos, swagStart, swagEnd, sag, droopLightChance, droopLightLevel, random);
                registerParentChild(level, markerPos, parents);
                CurveRef placedCurve = new CurveRef(swagStart, swagEnd, sag, markerPos);
                placedCurves.add(placedCurve);
                if (placedAsSecondTier) {
                    secondTierCurves.add(placedCurve);
                }
            }
        }
    }

    /**
     * 探索中心のまわり(水平半径 {@code radius})から、終点候補になりうる2種類のブロックを
     * **1回の走査で同時に**探す——{@code ownLogPositions} に含まれない(=今回の木のものではない)
     * {@link ModBlocks#OVERGROWN_LOG}(木の原木)と、{@code collectLianas} が true の場合に限り
     * 既に配置済みの {@link ModBlocks#OVERGROWN_LIANA} のうち**主アンカー**(副アンカーではない
     * ——{@link CorruptedLianaBlockEntity#getPrimaryPos()} が null かつ始点・終点が設定済み)。
     * 副アンカーは自分自身の曲線データを持たないため対象外。
     *
     * <p>この2つの候補探索は以前は完全に別々の2回の走査(それぞれ独自に {@code isLoaded}/
     * {@code getBlockState} を呼ぶ)として実装されていたが、探索する円柱の形(中心・半径・
     * Y範囲)が全く同じであるため、{@code collectLianas} が true の場合は**同じマスに対して
     * ブロック状態を2回読む**という完全に無駄な二度手間になっていた(2026-08-24 追加の
     * 既存蔦ターゲット機能により常時発生するようになった)。1回の走査にまとめ、1マスにつき
     * {@code getBlockState} を高々1回だけ呼ぶことで、この無駄を解消する
     * (結果として選ばれる候補の集合・確率分布は変わらない——挙動は従来と同一)。
     *
     * <p>枝は幹から離れた場所にも生えるため、この探索は木の根本を中心にした円柱状になる
     * (=遠くの枝を起点にした接続は取りこぼすことがある)。取りこぼしても「その組み合わせの
     * 蔦が生えない」だけで不具合にはならないので、負荷を抑えるためにあえて簡略化している。
     * 円の内接判定(水平距離が {@code radius} 以内かどうか)も、以前は正方形の全マスを走査して
     * マスごとに二乗距離を比較していたが、X ごとに実際に円に収まる Z の範囲を事前に計算して
     * その範囲だけを直接走査するよう変更した——同じ円形の範囲を走査する点は変わらないが、
     * 円の外側にある正方形の四隅ぶん(半径30なら全体の約2割)の無駄な反復・比較を省ける。
     *
     * <p>候補数の上限({@link #MAX_CANDIDATES})は、見つかった順に先着で {@code found.size()}
     * が上限に達した時点で走査を打ち切るのではなく、**貯水池サンプリング(reservoir sampling)**
     * で選ぶ(木・蔦それぞれ独立に)。Y を下から上へ走査する都合上、先着順だと地面付近(低い高さ)
     * の候補だけで上限を使い切ってしまい、木のかなり高い場所の候補には走査が一度も届かない
     * (=高い場所に絶対に蔦が生えなくなる)実害のある偏りがあった。貯水池サンプリングなら
     * 走査した範囲全体からほぼ均等な確率で選ばれるため、この偏りが起きない。
     *
     * @param collectLianas 蔦の主アンカーも一緒に探すか。呼び出し側で
     *                      {@code vineExistingVineTargetChance <= 0} など、その結果を使う予定が
     *                      無いと分かっている場合は false にすることで、判定そのもの
     *                      (ブロック状態が既に読めているので追加コストはごくわずかだが)を
     *                      丸ごと省ける。
     * @param logsOut       見つかった木の原木の座標を追加していく出力リスト(呼び出し側が空で渡す)
     * @param lianasOut     見つかった蔦の主アンカーの座標を追加していく出力リスト
     *                      (呼び出し側が空で渡す。{@code collectLianas} が false なら常に空のまま)
     */
    private static void findCandidates(ServerLevel level, BlockPos center, LongOpenHashSet ownLogPositions,
            int radius, int ownMinY, int ownMaxY, boolean collectLianas, RandomSource random,
            List<BlockPos> logsOut, List<BlockPos> lianasOut) {
        int logSeen = 0;
        int lianaSeen = 0;
        Block logBlock = ModBlocks.OVERGROWN_LOG.get();
        Block lianaBlock = ModBlocks.OVERGROWN_LIANA.get();

        int minY = Math.max(level.getMinBuildHeight(), ownMinY - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, ownMaxY + radius);
        int radiusSq = radius * radius;

        BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            // この x の列で実際に円の内側に収まる z の範囲を事前に1回だけ計算し、
            // その範囲だけを走査する(円の外側になる正方形の四隅ぶんの反復・比較を省く)。
            int zRadius = Mth.floor(Math.sqrt(radiusSq - x * x));
            int worldX = center.getX() + x;
            for (int z = -zRadius; z <= zRadius; z++) {
                int worldZ = center.getZ() + z;
                for (int y = minY; y <= maxY; y++) {
                    scratch.set(worldX, y, worldZ);
                    if (ownLogPositions.contains(scratch.asLong())) {
                        // 今回の木自身の原木。ログ候補にはなり得ず、蔦(リアナ)ブロックにも
                        // なり得ない座標なので、ブロック状態すら読まずまとめてスキップできる。
                        continue;
                    }
                    if (!level.isLoaded(scratch)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(scratch);
                    if (state.is(logBlock)) {
                        logSeen++;
                        if (logsOut.size() < MAX_CANDIDATES) {
                            logsOut.add(scratch.immutable());
                        } else {
                            int replaceIndex = random.nextInt(logSeen);
                            if (replaceIndex < MAX_CANDIDATES) {
                                logsOut.set(replaceIndex, scratch.immutable());
                            }
                        }
                    } else if (collectLianas && state.is(lianaBlock)
                            && level.getBlockEntity(scratch) instanceof CorruptedLianaBlockEntity liana
                            && liana.getPrimaryPos() == null
                            && liana.getSourcePos() != null && liana.getTargetPos() != null) {
                        lianaSeen++;
                        if (lianasOut.size() < MAX_CANDIDATES) {
                            lianasOut.add(scratch.immutable());
                        } else {
                            int replaceIndex = random.nextInt(lianaSeen);
                            if (replaceIndex < MAX_CANDIDATES) {
                                lianasOut.set(replaceIndex, scratch.immutable());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code list} の中から、{@code target} との直線距離が {@code [minLength, maxLength]} の
     * 範囲内、かつ Y 座標の差が {@code maxHeightDiff} 以内のものをランダムに1つ選ぶ。
     */
    private static BlockPos randomWithinRange(List<BlockPos> list, BlockPos target, int minLength, int maxLength,
            int maxHeightDiff, RandomSource random) {
        int lowLength = Math.min(minLength, maxLength);
        int highLength = Math.max(minLength, maxLength);
        double minLengthSq = (double) lowLength * lowLength;
        double maxLengthSq = (double) highLength * highLength;
        List<BlockPos> inRange = new ArrayList<>();
        for (BlockPos pos : list) {
            double distSq = pos.distSqr(target);
            if (distSq >= minLengthSq && distSq <= maxLengthSq
                    && Math.abs(pos.getY() - target.getY()) <= maxHeightDiff) {
                inRange.add(pos);
            }
        }
        if (inRange.isEmpty()) {
            return null;
        }
        return inRange.get(random.nextInt(inRange.size()));
    }

    /**
     * {@code positions} のうち、実際の地面(相当のもの)までの距離が {@code [minClearance, maxClearance]}
     * の範囲内であるものだけを残す。
     */
    private static List<BlockPos> filterByGroundClearance(ServerLevel level, List<BlockPos> positions,
            int minClearance, int maxClearance) {
        List<BlockPos> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            int clearance = measureGroundClearance(level, pos, maxClearance);
            if (clearance >= minClearance && clearance <= maxClearance) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * {@code pos} の真下を1マスずつ調べ、空気でも木の一部(原木・葉・蔦)でもない最初のブロック
     * (=「地面」相当)までの距離を返す。{@code maxScan} マス調べても見つからなければ
     * {@code maxScan + 1} を返す(=「少なくとも maxScan より高い」ことだけ分かればよい呼び出し側向けの
     * 打ち切り)。未ロードのマスに当たった場合も同様に打ち切る(安全側)。
     */
    private static int measureGroundClearance(ServerLevel level, BlockPos pos, int maxScan) {
        if (maxScan <= 0) {
            return 0;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int distance = 1; distance <= maxScan; distance++) {
            cursor.set(pos.getX(), pos.getY() - distance, pos.getZ());
            if (!level.isLoaded(cursor) || !isTreeOrAirMaterial(level.getBlockState(cursor))) {
                return distance - 1;
            }
        }
        return maxScan + 1;
    }

    /**
     * {@code positions}(既に {@link #filterByGroundClearance} で最小値以上に絞り込み済みであること)
     * を、地面からの高さ(距離)を「低」(〜{@code bandLowMax})・「中」({@code bandLowMax}の次〜
     * {@code bandMidMax})・「高」({@code bandMidMax}の次〜{@code maxClearance})の3段階に分類し、
     * {@code weightLow}:{@code weightMid}:{@code weightHigh} の比で重み付けした順序に並べ替えて返す
     * (復元抽出なし=同じ要素は1回しか出てこない)。
     *
     * <p>戻り値の並び順を先頭から使う({@link #tryConnect} の呼び出し元がそうしている)ことで、
     * 結果的に「どの高さ帯からどれだけ蔦が生えやすいか」がこの重みの比に従うようになる。
     */
    private static List<BlockPos> weightedOrderByHeightBand(ServerLevel level, List<BlockPos> positions,
            int maxClearance, int bandLowMax, int bandMidMax,
            double weightLow, double weightMid, double weightHigh, RandomSource random) {
        List<BlockPos> low = new ArrayList<>();
        List<BlockPos> mid = new ArrayList<>();
        List<BlockPos> high = new ArrayList<>();
        for (BlockPos pos : positions) {
            int clearance = measureGroundClearance(level, pos, maxClearance);
            if (clearance <= bandLowMax) {
                low.add(pos);
            } else if (clearance <= bandMidMax) {
                mid.add(pos);
            } else {
                high.add(pos);
            }
        }

        List<List<BlockPos>> pools = new ArrayList<>(3);
        pools.add(low);
        pools.add(mid);
        pools.add(high);
        double[] weights = {weightLow, weightMid, weightHigh};

        List<BlockPos> result = new ArrayList<>(positions.size());
        while (result.size() < positions.size()) {
            double sum = 0.0D;
            for (int i = 0; i < pools.size(); i++) {
                if (!pools.get(i).isEmpty()) {
                    sum += weights[i];
                }
            }
            int chosenBucket = -1;
            if (sum > 0.0D) {
                double roll = random.nextDouble() * sum;
                double cursor = 0.0D;
                for (int i = 0; i < pools.size(); i++) {
                    if (pools.get(i).isEmpty()) {
                        continue;
                    }
                    cursor += weights[i];
                    if (roll < cursor) {
                        chosenBucket = i;
                        break;
                    }
                }
            }
            if (chosenBucket == -1) {
                // 全帯の重みが0(または丸め誤差)の場合は、空でない帯を先頭から使うフォールバック。
                for (int i = 0; i < pools.size(); i++) {
                    if (!pools.get(i).isEmpty()) {
                        chosenBucket = i;
                        break;
                    }
                }
            }
            List<BlockPos> pool = pools.get(chosenBucket);
            result.add(pool.remove(random.nextInt(pool.size())));
        }
        return result;
    }

    /** 空気、またはこの MOD の木を構成するブロック(原木・葉・蔦)かどうか。 */
    private static boolean isTreeOrAirMaterial(BlockState state) {
        return state.isAir()
                || state.is(ModBlocks.OVERGROWN_LOG.get())
                || state.is(ModBlocks.OVERGROWN_LEAVES.get())
                || state.is(ModBlocks.OVERGROWN_LIANA.get());
    }

    /**
     * 垂れ具合(sag)を {@code minSag}(段階1)〜{@code maxSag}(段階{@code SAG_TIERS})の範囲を
     * 均等に分けた {@code SAG_TIERS} 段階(既定では 1, 1.4, 1.8, 2.2, 2.6, 3 の6段階)から、
     * 互いに重複しない {@code count} 個をランダムに選んで返す(段階をシャッフルしてから
     * 先頭 {@code count} 個を採用するだけなので、同じ組み合わせで束ねて生成する蔦どうしの
     * 垂れ具合が必ず異なることを保証できる)。{@code count} が {@code SAG_TIERS} を超える場合は
     * {@code SAG_TIERS} に切り詰める(これ以上は重複しない値を作れないため)。
     */
    private static List<Float> pickDistinctSags(int minSag, int maxSag, int count, RandomSource random) {
        int n = Math.max(1, Math.min(count, SAG_TIERS));
        Integer[] tiers = new Integer[SAG_TIERS];
        for (int i = 0; i < SAG_TIERS; i++) {
            tiers[i] = i;
        }
        for (int i = tiers.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Integer tmp = tiers[i];
            tiers[i] = tiers[j];
            tiers[j] = tmp;
        }

        float lower = Math.min(minSag, maxSag);
        float upper = Math.max(minSag, maxSag);
        List<Float> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (upper <= 0.0F) {
                result.add(0.0F);
                continue;
            }
            float t = SAG_TIERS > 1 ? (float) tiers[i] / (SAG_TIERS - 1) : 0.0F;
            result.add(lower + (upper - lower) * t);
        }
        return result;
    }

    /**
     * 束ねて生成する蔦の本数を {@code minCount}〜{@code maxCount} の範囲から、
     * {@code weight2}(=minCount 本になる重み):{@code weight3}(=minCount+1 本):
     * {@code weight4}(=minCount+2 本):{@code weight5}(=minCount+3 本):
     * {@code weight6}(=minCount+4 本以降すべてに使い回す)の比で重み付けして1つ選ぶ
     * (既定の minCount=2/maxCount=6 なら文字通り 2本:3本:4本:5本:6本 = 80:15:5:2:1、
     * 本数が多いほど生成されにくい単調減少)。
     */
    private static int pickStackCount(int minCount, int maxCount, double weight2, double weight3, double weight4,
            double weight5, double weight6, RandomSource random) {
        int range = Math.max(1, maxCount - minCount + 1);
        double[] weights = new double[range];
        for (int i = 0; i < range; i++) {
            weights[i] = switch (i) {
                case 0 -> weight2;
                case 1 -> weight3;
                case 2 -> weight4;
                case 3 -> weight5;
                default -> weight6;
            };
        }
        double sum = 0.0D;
        for (double w : weights) {
            sum += w;
        }
        if (sum <= 0.0D) {
            return minCount;
        }
        double roll = random.nextDouble() * sum;
        double cursor = 0.0D;
        for (int i = 0; i < range; i++) {
            cursor += weights[i];
            if (roll < cursor) {
                return minCount + i;
            }
        }
        return maxCount;
    }

    /**
     * {@code source}〜{@code target} を結ぶ直線+{@code sag} の放物線(通常の蔦の描画式と全く
     * 同じ、{@code CorruptedLianaRenderer#curvePoint} 参照)上の、経路の割合 {@code t}
     * (0=始点、1=終点)に対応する絶対座標を返す。{@code source}/{@code target} は木の原木
     * ブロックの中心(トップレベルの主線)でも、別のぶら下がり蔦自身の始点・終点(入れ子の親)
     * でもよい——どちらも実座標(Vec3)である以外の違いは無い。
     */
    private static Vec3 curvePointOnLine(Vec3 source, Vec3 target, float sag, float t) {
        double dx = target.x - source.x;
        double dy = target.y - source.y;
        double dz = target.z - source.z;
        double x = source.x + dx * t;
        double z = source.z + dz * t;
        double y = source.y + dy * t - 4.0 * sag * t * (1.0 - t);
        return new Vec3(x, y, z);
    }

    /**
     * ぶら下がり蔦(swag)の両端を、{@code source}〜{@code target} を結ぶ直線+{@code parentSag}
     * の放物線(=親の蔦の曲線。トップレベルの主線、または入れ子の場合は1つ上のぶら下がり蔦)
     * 上のランダムな2点として選ぶ。2点は {@link #SWAG_T_MIN}〜{@link #SWAG_T_MAX} の範囲から
     * それぞれ独立に乱数で選び、最低 {@link #SWAG_MIN_SEPARATION} は離す(潰れた点にならない
     * ため)。戻り値は {@code [始点, 終点]}(経路の割合が小さい方が先)。
     */
    private static Vec3[] pickSwagEndpoints(Vec3 source, Vec3 target, float parentSag, RandomSource random) {
        float tA = randomSwagT(random);
        float tB = randomSwagT(random);
        if (tA > tB) {
            float tmp = tA;
            tA = tB;
            tB = tmp;
        }
        if (tB - tA < SWAG_MIN_SEPARATION) {
            tB = Math.min(SWAG_T_MAX, tA + SWAG_MIN_SEPARATION);
            tA = Math.max(SWAG_T_MIN, tB - SWAG_MIN_SEPARATION);
        }
        return new Vec3[] {
                curvePointOnLine(source, target, parentSag, tA),
                curvePointOnLine(source, target, parentSag, tB)
        };
    }

    /** {@link #SWAG_T_MIN}〜{@link #SWAG_T_MAX} の範囲から乱数で選んだ経路上の割合(0=始点、1=終点)。 */
    private static float randomSwagT(RandomSource random) {
        return SWAG_T_MIN + (SWAG_T_MAX - SWAG_T_MIN) * random.nextFloat();
    }

    /**
     * 重複蔦(主線をまたがずに生成する場合)が第一の蔦(主線)に対してどう生えるかの4分類
     * (2026-08-25追加、{@link #pickDuplicateTier} 参照)。
     */
    private enum DuplicateTier {
        /** 始点を第一の蔦の実際の始点と共有する「第二の蔦」。 */
        SHARE_START,
        /** 終点を第一の蔦の実際の終点と共有する「第二の蔦」。 */
        SHARE_END,
        /** 始点・終点のどちらも第一の蔦の曲線上の点になる「第二の蔦」(従来からの既定の垂れ方)。 */
        FLOATING,
        /** 第一の蔦からではなく、既に配置済みの第二の蔦から生える「第三の蔦」。 */
        THIRD_TIER
    }

    /**
     * {@link DuplicateTier} の4分類を重み付きで1つ選ぶ。{@code thirdTierEligible} が false の場合、
     * {@code THIRD_TIER} は候補から完全に除外し、残り3分類の重みだけで抽選する
     * ({@link #tryConnect} 呼び出し元が、この束の重複蔦の総本数が
     * {@code vineThirdTierMinDuplicateCount} 未満、またはまだ第二の蔦が1本も無い場合に false を渡す)。
     */
    private static DuplicateTier pickDuplicateTier(boolean thirdTierEligible, double shareStartWeight,
            double shareEndWeight, double floatingWeight, double thirdTierWeight, RandomSource random) {
        double w4 = thirdTierEligible ? thirdTierWeight : 0.0D;
        double sum = shareStartWeight + shareEndWeight + floatingWeight + w4;
        if (sum <= 0.0D) {
            return DuplicateTier.FLOATING;
        }
        double roll = random.nextDouble() * sum;
        double cursor = shareStartWeight;
        if (roll < cursor) {
            return DuplicateTier.SHARE_START;
        }
        cursor += shareEndWeight;
        if (roll < cursor) {
            return DuplicateTier.SHARE_END;
        }
        cursor += floatingWeight;
        if (roll < cursor) {
            return DuplicateTier.FLOATING;
        }
        return DuplicateTier.THIRD_TIER;
    }

    /**
     * 新しく配置した子の主アンカー({@code childMarker})に依存元(親)の主アンカー一覧
     * ({@code parents}、通常1個、別の重複蔦をまたいだ場合は2個)を設定し、各親側にも
     * この子を紐付ける(双方向のリンクを1回でまとめて構築する)。木の原木どうしを直接結ぶ
     * だけの通常の蔦(依存元が無い)ではそもそも呼ばれない。
     *
     * <p>親のブロックエンティティが({@code findMarkerPosition} が空気/置換可能と確認した
     * マスへ直前に置いたばかりのはずなので理論上起こらないが)見つからない場合は、その親だけ
     * 静かにスキップする(防御的。取りこぼしても連鎖除去の対象が1つ減るだけで実害は無い)。
     */
    private static void registerParentChild(ServerLevel level, BlockPos childMarker, List<BlockPos> parents) {
        if (level.getBlockEntity(childMarker) instanceof CorruptedLianaBlockEntity childLiana) {
            childLiana.setParentPositions(parents);
        }
        for (BlockPos parentPos : parents) {
            if (level.getBlockEntity(parentPos) instanceof CorruptedLianaBlockEntity parentLiana) {
                parentLiana.addChildPosition(childMarker);
            }
        }
    }

    /**
     * {@code pool}(要素数2以上であること)から、互いに異なるインデックスの2本の曲線を
     * 無作為に選んで返す({@link #tryConnect}/{@link #maybeSpawnNestedSwags} の
     * 「重複蔦が別の重複蔦をまたぐ」機能専用)。
     */
    private static CurveRef[] pickTwoDistinctCurves(List<CurveRef> pool, RandomSource random) {
        int i = random.nextInt(pool.size());
        int j;
        do {
            j = random.nextInt(pool.size());
        } while (j == i);
        return new CurveRef[] {pool.get(i), pool.get(j)};
    }

    /**
     * {@code source}(今回の木の原木、またはぶら下がり蔦の場合は丸めた始点)の近くから、
     * 主アンカー({@link ModBlocks#OVERGROWN_LIANA})を置ける(空気または置換可能で、
     * {@code avoid} にまだ含まれていない)マスを1つ探して返す。見つからなければ {@code null}。
     *
     * <p>候補は {@code source} から {@code target} への進行方向(符号のみ)を基準に
     * {@link #MARKER_OFFSET_JITTERS} ぶんずらした位置を優先順に試す。同じ始点・終点の組み合わせで
     * 複数本の蔦を束ねて生成する際、1本ごとに主アンカーの実マスは異なる必要がある
     * (1マスには1個のブロックしか置けない)ため、{@code avoid} に既に使ったマスを渡して
     * 除外しながら繰り返し呼ぶ。
     */
    @Nullable
    private static BlockPos findMarkerPosition(ServerLevel level, BlockPos source, BlockPos target,
            LongOpenHashSet avoid) {
        int dx = Integer.signum(target.getX() - source.getX());
        int dy = Integer.signum(target.getY() - source.getY());
        int dz = Integer.signum(target.getZ() - source.getZ());
        if (dx == 0 && dy == 0 && dz == 0) {
            return null;
        }

        for (int[] jitter : MARKER_OFFSET_JITTERS) {
            BlockPos candidate = source.offset(dx + jitter[0], dy + jitter[1], dz + jitter[2]);
            if (avoid.contains(candidate.asLong())) {
                continue;
            }
            if (!level.isLoaded(candidate)) {
                continue;
            }
            BlockState state = level.getBlockState(candidate);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * {@code markerPos} に主アンカー({@link ModBlocks#OVERGROWN_LIANA})を1個置き、実際の描画に
     * 使う始点・終点(=木の原木ブロックそのもの)・垂れ具合をその {@link CorruptedLianaBlockEntity}
     * に設定する。さらに {@link #placeSecondaryAnchors} で経路上に副アンカーを追加し、
     * どこをクリックしても切れるようにする。仕上げに {@link #placeDroops} で垂れ蔦(の一部は
     * 発光する)の生成位置を決める。
     *
     * <p>{@code markerPos} は {@link #findMarkerPosition} が既に「空気または置換可能」である
     * ことを確認済みの前提。両端そのもの(=どちらも木の原木ブロック)は上書きしない。
     */
    private static void placeVine(ServerLevel level, BlockPos markerPos, BlockPos source, BlockPos target,
            float sag, double droopLightChance, int droopLightLevel, RandomSource random) {
        level.setBlock(markerPos, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(markerPos) instanceof CorruptedLianaBlockEntity primaryLiana)) {
            return;
        }
        primaryLiana.setEndpoints(source, target, sag);

        List<BlockPos> secondaries = placeSecondaryAnchors(level,
                source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5,
                target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, sag, markerPos);
        primaryLiana.setSecondaryPositions(secondaries);

        placeDroops(level, primaryLiana, Vec3.atCenterOf(source), Vec3.atCenterOf(target), sag,
                droopLightChance, droopLightLevel, random);
    }

    /**
     * {@code markerPos} に主アンカーを1個置き、{@code swagStart}〜{@code swagEnd}(一番小さい蔦の
     * 曲線上の実座標)の間だけを {@code sag} で垂れ下がる「ぶら下がり蔦」として
     * {@link CorruptedLianaBlockEntity} に設定する。{@link #placeVine} のぶら下がり蔦版。
     */
    private static void placeSwagVine(ServerLevel level, BlockPos markerPos, Vec3 swagStart, Vec3 swagEnd,
            float sag, double droopLightChance, int droopLightLevel, RandomSource random) {
        level.setBlock(markerPos, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(markerPos) instanceof CorruptedLianaBlockEntity primaryLiana)) {
            return;
        }
        primaryLiana.setSwagEndpoints(swagStart, swagEnd, sag);

        List<BlockPos> secondaries = placeSecondaryAnchors(level,
                swagStart.x, swagStart.y, swagStart.z, swagEnd.x, swagEnd.y, swagEnd.z, sag, markerPos);
        primaryLiana.setSecondaryPositions(secondaries);

        placeDroops(level, primaryLiana, swagStart, swagEnd, sag, droopLightChance, droopLightLevel, random);
    }

    /**
     * {@code source}〜{@code target}(垂れ具合 {@code sag})の主リボンの経路に沿って垂れ蔦
     * (droop)の生成位置を決める——どの分割点(0..{@link #DROOP_SEGMENTS})に、どんな長さで
     * 生えるか、そのうち何本が発光する見た目(overgrown_liana_droop_light)になるか
     * ({@code droopLightChance} の確率、droop が生成されると決まった時点でさらに判定する)。
     * 決定結果は {@code liana} に保存してクライアントへ同期し(実際に描くのはレンダラー)、
     * 発光すると決まった垂れ蔦の分だけ、その先端付近に見えない光源ブロック
     * ({@link Blocks#LIGHT}、光レベル {@code droopLightLevel})を実際に置き、置けた座標を
     * {@link CorruptedLianaBlockEntity#setLightBlockPositions} に記録する(蔦がカットされた際に
     * 一緒に撤去できるように)。
     *
     * <p>候補地点を歩くアルゴリズム自体(間隔・生えるかどうか)は、以前クライアント側の
     * レンダラーが座標由来の決定的な乱数列を毎フレーム消費していたものと全く同じ式(クラスの
     * Javadoc「発光する垂れ蔦」参照)——サーバー側で生成時に1回だけ判定するようになっただけで、
     * 見た目の分布は変えていない。周囲が塞がっている等で光源ブロックを置けなかった場合は、
     * その垂れ蔦は見た目(発光テクスチャ)だけ残り、実際の光は無い(取りこぼしても不具合には
     * ならない、この蔦システム全体で繰り返されている方針と同じ)。
     */
    private static void placeDroops(ServerLevel level, CorruptedLianaBlockEntity liana, Vec3 source, Vec3 target,
            float sag, double droopLightChance, int droopLightLevel, RandomSource random) {
        List<Integer> indices = new ArrayList<>();
        List<Float> lengths = new ArrayList<>();
        List<Boolean> isLightFlags = new ArrayList<>();
        List<BlockPos> lightBlocks = new ArrayList<>();

        int droopIndex = nextDroopStride(random);
        while (droopIndex <= DROOP_SEGMENTS - DROOP_STRIDE_MIN) {
            if (random.nextDouble() < DROOP_CHANCE) {
                float length = Mth.lerp(random.nextFloat(), DROOP_MIN_LENGTH, DROOP_MAX_LENGTH);
                boolean isLight = droopLightChance > 0.0D && random.nextDouble() < droopLightChance;
                indices.add(droopIndex);
                lengths.add(length);
                isLightFlags.add(isLight);

                if (isLight) {
                    float t = (float) droopIndex / DROOP_SEGMENTS;
                    Vec3 origin = curvePointOnLine(source, target, sag, t);
                    // 垂れ蔦の先端(発光しているように見える箇所)におおよそ対応する位置。
                    BlockPos lightPos = BlockPos.containing(origin.x, origin.y - length, origin.z);
                    if (level.isLoaded(lightPos)) {
                        BlockState state = level.getBlockState(lightPos);
                        if (state.isAir() || state.canBeReplaced()) {
                            level.setBlock(lightPos, Blocks.LIGHT.defaultBlockState()
                                    .setValue(LightBlock.LEVEL, droopLightLevel), Block.UPDATE_ALL);
                            lightBlocks.add(lightPos);
                        }
                    }
                }
            }
            droopIndex += nextDroopStride(random);
        }

        int[] indexArray = new int[indices.size()];
        float[] lengthArray = new float[lengths.size()];
        boolean[] isLightArray = new boolean[isLightFlags.size()];
        for (int i = 0; i < indexArray.length; i++) {
            indexArray[i] = indices.get(i);
            lengthArray[i] = lengths.get(i);
            isLightArray[i] = isLightFlags.get(i);
        }
        liana.setDroops(indexArray, lengthArray, isLightArray);
        liana.setLightBlockPositions(lightBlocks);
    }

    /** 次の垂れ蔦の候補地点までの間隔([{@link #DROOP_STRIDE_MIN}, {@link #DROOP_STRIDE_MAX}]の範囲でランダム)。 */
    private static int nextDroopStride(RandomSource random) {
        return DROOP_STRIDE_MIN + random.nextInt(DROOP_STRIDE_MAX - DROOP_STRIDE_MIN + 1);
    }

    /**
     * 始点({@code startX,startY,startZ})から終点({@code endX,endY,endZ})への直線+{@code sag}の
     * 放物線(レンダラーの {@code curvePoint} と全く同じ式)に沿って、クリック判定専用の
     * 副アンカーを追加で置く。曲線そのもの(ポリゴン描画)には当たり判定が無いため、主アンカーの
     * 1マスだけでは「見えている蔦のどこを触っても反応しない」状態になってしまうのを防ぐための
     * 処置。通常の蔦(木の原木どうし)・ぶら下がり蔦(swagの実座標どうし)のどちらでも、
     * 始点・終点の実座標を渡すだけで同じ式が使える。
     *
     * <p>両端(始点・終点そのもの)には置かない。主アンカーと重なるマス・空気でも置き換え
     * 可能でもないマスはスキップする(取りこぼしてもクリック判定の密度が下がるだけで、
     * 不具合にはならない)。置けた副アンカーの座標一覧を返す。
     */
    private static List<BlockPos> placeSecondaryAnchors(ServerLevel level,
            double startX, double startY, double startZ, double endX, double endY, double endZ,
            float sag, BlockPos primaryPos) {
        List<BlockPos> placed = new ArrayList<>();
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        int steps = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)));

        BlockPos previous = null;
        for (int i = 1; i < steps; i++) {
            float t = (float) i / steps;
            double x = startX + dx * t;
            double z = startZ + dz * t;
            double y = startY + dy * t - 4.0 * sag * t * (1.0 - t);
            BlockPos point = BlockPos.containing(x, y, z);

            if (point.equals(previous) || point.equals(primaryPos)) {
                continue;
            }
            previous = point;

            if (!level.isLoaded(point)) {
                continue;
            }
            BlockState state = level.getBlockState(point);
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }

            level.setBlock(point, ModBlocks.OVERGROWN_LIANA.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(point) instanceof CorruptedLianaBlockEntity secondaryLiana) {
                secondaryLiana.setPrimaryPos(primaryPos);
                placed.add(point);
            }
        }
        return placed;
    }
}
