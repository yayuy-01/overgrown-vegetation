package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * MOD 全体の「フェーズ」進行状況(ワールド単位、次元をまたいで共通)を保持する {@link SavedData}。
 *
 * <p>常にオーバーワールドの {@code DimensionDataStorage} に紐付けて保存する({@link #get(ServerLevel)}
 * はどの次元の {@link ServerLevel} を渡してもオーバーワールド側のインスタンスを返す)。
 *
 * <ul>
 *     <li>フェーズ 0: 初期状態。感染 Mob は一切自然湧きせず、ポイントも加算されない。</li>
 *     <li>フェーズ 1 は {@code global.startWorldAgeTicks}(既定 3 日)の経過<b>かつ</b>
 *     {@code global.startAdvancements}(既定 6 種類)の実績解除で始まる({@link #tryStartPhase1}、
 *     2026-09-15 から 2 条件)。</li>
 *     <li>フェーズ 1 以降は<b>ポイントだけ</b>で進む({@code phases.json} の {@code pointsToAdvance} /
 *     {@link com.yayuy.overgrownvegetation.data.PhaseTable#canAdvanceTo})。フェーズが進むと
 *     ポイントは 0 にリセットされるので、各フェーズの条件は「直前のフェーズ開始時点からの増分」を表す。
 *     <b>チャンク数が一定に達したら昇格</b>という条件は 2026-09-15 に廃止した — 汚染の広がりは
 *     時間経過ポイントの倍率({@code points.perTickPerOvergrownChunk})を通して昇格を早める形でだけ効く。</li>
 *     <li>既定の条件: フェーズ2 = 180000 / フェーズ3 = 400000 / フェーズ4 = 700000 /
 *     フェーズ5 = 1400000 / フェーズ6 = 2900000 ポイント
 *     (フェーズ1 からフェーズ6 までおよそゲーム内 45 日) /
 *     フェーズ7以降 = 条件なしなので<b>自動進行はフェーズ6で止まる</b>
 *     (コマンドでの手動変更だけは 10 まで通る)。</li>
 *     <li>フェーズ5へ上がった瞬間にレイドが 1 回確定で発生する
 *     ({@link com.yayuy.overgrownvegetation.raid.RaidData#onPhaseAdvanced})。</li>
 *     <li>{@code /ov phase freeze true} で自動進行を凍結できる(フェーズ0のまま
 *     止めておけばフェーズ1も始まらない)。{@link #frozen} を参照。</li>
 * </ul>
 *
 * <p>実際にポイントを加算する箇所は {@link AdvancementPoints}(実績解除)・{@link MobGroupPoints}
 * (グループ1〜10の死亡 / グループ1〜10による他 Mob の殺害)・{@link PhaseTicker}(時間経過分)・
 * {@link #addOvergrownChunks}(過繁茂系バイオームの増加)の 4 箇所。
 */
public final class PhaseData extends SavedData {

    private static final String ID = "overgrown_vegetation_phase";

    public static final SavedData.Factory<PhaseData> FACTORY =
            new SavedData.Factory<>(PhaseData::new, (tag, registries) -> load(tag), null);

    private int phase;
    private long points;
    /** 直近で時間経過ポイントを加算したゲーム内時刻(tick)。-1 はまだフェーズ1に到達していないことを表す。 */
    private long lastPointsGameTime = -1L;
    /** 時間経過ポイントの端数(pointsPerTick が小数のとき、整数化で切り捨てたぶんを次回へ繰り越す)。 */
    private double timePointsCarry;
    /** ワールドの難易度「フェーズ進行のポイント量」を掛けたときの端数(0.5 倍で 1 点ずつ入るときなど。{@link #addPoints})。 */
    private double multipliedPointsCarry;
    /**
     * この MOD がこのワールドで新規に汚染したブロックの累計数。加算は {@link CorruptionCounter} 経由
     * (汚染シダ・汚染死体ブロック・過繁茂バイオームのにじみ出し・過繁茂したクリーパーの爆発)。
     * 導入前から存在していた汚染や、データパックが直接置いた汚染ブロックは数えない。
     *
     * <p>2026-09-13 の再編で<b>フェーズ進行には使わなくなった</b>(フェーズ2の条件は汚染ブロック数から
     * 過繁茂系バイオームのチャンク数 {@link #overgrownChunks} に置き換わった)。{@code /ov phase}
     * に表示する進行状況の目安として残してある。
     */
    private long corruptedBlocks;
    /**
     * このワールドで新たに過繁茂系バイオーム(過繁茂 / 過繁茂の海 / 過繁茂の川、環境の別は問わない)に
     * なったチャンクの累計数。{@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} が
     * チャンクを過繁茂段階へ書き換えたときに 1 つずつ加算する({@link #addOvergrownChunks})。
     * 繁茂段階(過繁茂の手前)への書き換えは数えない。
     *
     * <p>ワールド生成時から過繁茂だったチャンクは存在しない(過繁茂系バイオームはこの MOD が
     * 書き換えたチャンクにしか現れない)ので、この値は「この MOD が塗り替えた面積」そのもの。
     */
    private long overgrownChunks;
    /**
     * 過繫茂系バイオームになったチャンクの台帳。キーは {@link ChunkPos#toLong}、値はそのチャンクが
     * 過繫茂になったゲーム内時刻(tick、オーバーワールド基準)。{@link #overgrownChunks} が「数」なのに対し、
     * こちらは「どこが・いつ」を持つ。
     *
     * <p>極相({@code engulfed})への昇格条件 —— 「周囲 8 チャンクがすべて過繫茂以上」かつ
     * 「自分が過繫茂になってから 14 日以上」—— は、この台帳を引くだけで判定できる
     * ({@link #canBecomeClimax})。<b>対象のチャンクを読み込む必要がない</b>ので、
     * プレイヤーが遠くに住んでいても極相化は進む。
     *
     * <p><b>極相になっても項目は消さない。</b> 台帳に載っている = 「過繫茂以上」という意味であり、
     * 消すと最初に極相化した 1 チャンクの周りが永久に条件を満たせなくなる。
     *
     * <p>この台帳が導入される前のセーブでは空から始まる。既存の過繫茂チャンクは
     * {@link #adoptOvergrownChunk} で「見かけた時刻」を入れて拾い直す(14 日の計時はそこから)。
     */
    private final Long2LongOpenHashMap overgrownSince = new Long2LongOpenHashMap();

    /**
     * 眠って飛ばした時間の累計(tick、2026-09-26)。{@link #clock} = オーバーワールドの gameTime + これ。
     * バニラの睡眠は dayTime だけを朝へ進めて gameTime を進めないので、gameTime だけで測ると「寝た夜」が
     * 台帳の広がり・極相の齢・時間経過ポイントから抜け落ちる(ユーザー指定「寝た時はしっかり一日分広げる」)。
     * 足すのは {@link com.yayuy.overgrownvegetation.corruption.LedgerSpread} の睡眠のイベントだけ
     * ({@code /time set} は数えない)。
     */
    private long sleepSkippedTicks;
    /**
     * 各フェーズに<b>初めて</b>着いた暦({@link #clock}、2026-09-26)。添字 = フェーズ番号、-1 = 不明。
     * 極相の齢から「F1 開始から F5 までにかかった日数」を差し引くのに使う({@link #climaxAgeOffsetTicks})。
     */
    private final long[] phaseReachedClock = unknownClocks();

    /**
     * <b>全層置換を終えた</b>極相チャンク(2026-09-22)。極相になったチャンクは岩盤まで
     * 過繫茂ブロックへ置き換える({@code ClimaxDeepConverter})が、1 チャンク約 98000 マスを
     * 何 tick にも分けて進めるので、<b>やり終えたかどうか</b>を覚えておかないと
     * チャンクを読み込み直すたびに最初からやり直してしまう。
     *
     * <p>逆に<b>途中で終わった</b>(サーバーを落とした・チャンクが読み込まれなくなった)ぶんは
     * ここに入らないので、次に読み込まれたときにもう一度最初から走る。置換は何度やっても
     * 同じ結果になる(冪等)ので、やり直しても壊れない。
     */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet deepConvertedChunks =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /**
     * フェーズ0の間に、このワールドで解除された実績の ID(重複なし)。フェーズ0→1 の実績ゲート
     * ({@code global.startAdvancements}、既定 6)の判定に使う。誰が解除したかは問わず、同じ実績を
     * 別のプレイヤーが解除しても 1 種類として数える。
     *
     * <p>フェーズ1へ上がった時点で用済みなので {@link #startAdvancementCount} に個数だけ残して
     * 中身は捨てる(長いワールドで数百個の文字列を抱え続けないため)。
     */
    private final java.util.LinkedHashSet<String> startAdvancementIds = new java.util.LinkedHashSet<>();
    /** {@link #startAdvancementIds} の個数(フェーズ1へ上がったあとも表示用に残す)。 */
    private int startAdvancementCount;
    /**
     * このワールドで巨大樹({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})が
     * これまでに 1 本でも生えたか。汚染時の装飾アーチ(巨大樹の根の形の「根」)は、この値が
     * {@code true}(= ワールドのどこかに古代樹の核が生成された)になって初めて生えるようになる。
     * 一度立ったら下がらない。
     */
    private boolean giantTreeGrown;
    /**
     * これまでにこのワールドで生成された巨大樹の中心座標(水平)を {@code (x<<32)|(z&0xFFFFFFFF)} で
     * パックしたもの。新しい巨大樹が近くに生えないようにする間隔チェック
     * ({@code giantTreeMinSpacing}、{@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#place})
     * に使う。導入前から存在していた巨大樹は含まれない。
     */
    private final LongArrayList giantTreeOrigins = new LongArrayList();
    /**
     * 巨大樹が生えたゲーム時刻(中心の {@code (x<<32)|(z&0xFFFFFFFF)} → 時刻、2026-10-23 胞子の場 D)。梢の胞子の放出が
     * 育ち具合で増えるのに使う。記録の無い(前からある)巨大樹は育ちきった扱い。
     */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap giantTreeBorn = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** ハイヴノードを倒された巨大樹の中心(胞子の生産が止まったまま。再生なし、2026-10-23 E2)。 */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet deadGiantTrees = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /**
     * これまでにこのワールドで湧いた「ハイヴノード」({@code hive_node})の座標
     * ({@link BlockPos#asLong})。胞子感染2 のプレイヤーが核へ近づいたときの胞子感染3 判定
     * ({@link #hasHiveNodeWithin})に使う。この記録が導入される前に生えた巨大樹は含まれないので、
     * 記録が 1 つも無いワールドでは {@link #giantTreeOrigins}(水平距離)で代用する。
     */
    private final LongArrayList hiveNodes = new LongArrayList();
    /**
     * 「ハイヴノード」の記録を一度でも始めたか。ハイヴノードが倒されて {@link #hiveNodes} が空に戻っても、
     * この値が {@code true} なら旧ワールド向けの {@link #giantTreeOrigins} 代用には<b>戻らない</b>
     * (全部倒したのに巨大樹の中心が感染源に化けるのを防ぐ)。
     */
    private boolean hiveNodesRecorded;
    /**
     * 巨大樹の幹の真下に作った「巣の広間」({@code hardened_giant_trunk} の球)の中心座標
     * ({@link BlockPos#asLong})。将来の mob「hive node」をここへ湧かせる/1体だけに保つための記録。
     * 広間は巨大樹1本につき1つなので {@link #hiveNodes} と 1 対 1 に対応する。
     */
    private final LongArrayList hiveChambers = new LongArrayList();
    /**
     * フェーズの自動進行を凍結するか({@code /ov phase freeze} で切り替え)。
     * {@code true} の間は、フェーズ0→1 の時刻ゲート・時間経過ポイント・ポイントによるフェーズ進行が
     * <b>すべて止まる</b>(ポイントの加算自体も行わない。過繁茂チャンク数の累計だけは、
     * 統計・ポイント倍率の元なので凍結中も増える)。
     * {@link #setPhase}({@code phase set})による手動変更だけは凍結中でも通す。
     */
    private boolean frozen;

    /** 胞子警報器の「次のフェーズが近い」警告をこのフェーズで出したか(2026-09-19)。昇格でリセット。 */
    private boolean phaseWarned;

    /**
     * 極相の判定をどの候補から始めるか({@link #collectClimaxPromotions} のラウンドロビン、2026-09-25)。
     * 「齢を満たした、まだ極相でない項目」に台帳の反復順で振った通し番号。
     *
     * <p><b>保存しない。</b> 読み直したら 0 から始まるだけで、1 周すれば公平さは戻る。
     * 台帳の反復順は再ハッシュや追加で変わりうるので、番号は「だいたいその辺」を指す目安でしかないが、
     * 予算切れのたびに続きから見る限り、どの候補も数回の判定のうちに必ず順番が回ってくる。
     */
    private int climaxScanCursor;

    private PhaseData() {
        overgrownSince.defaultReturnValue(-1L);
    }

    private static PhaseData load(CompoundTag tag) {
        PhaseData data = new PhaseData();
        data.phase = tag.getInt("phase");
        data.points = tag.getLong("points");
        data.lastPointsGameTime = tag.contains("lastPointsGameTime") ? tag.getLong("lastPointsGameTime") : -1L;
        data.timePointsCarry = tag.getDouble("timePointsCarry");
        data.multipliedPointsCarry = tag.getDouble("multipliedPointsCarry");
        data.corruptedBlocks = tag.getLong("corruptedBlocks");
        data.overgrownChunks = tag.getLong("overgrownChunks");
        long[] ledgerKeys = tag.getLongArray("overgrownChunkKeys");
        long[] ledgerTimes = tag.getLongArray("overgrownChunkTimes");
        if (ledgerKeys.length == ledgerTimes.length) {
            for (int i = 0; i < ledgerKeys.length; i++) {
                data.overgrownSince.put(ledgerKeys[i], ledgerTimes[i]);
            }
        }
        for (long key : tag.getLongArray("climaxDeepConvertedChunks")) {
            data.deepConvertedChunks.add(key);
        }
        data.sleepSkippedTicks = Math.max(0L, tag.getLong("sleepSkippedTicks"));
        long[] reached = tag.getLongArray("phaseReachedClock");
        for (int i = 0; i < reached.length && i < data.phaseReachedClock.length; i++) {
            data.phaseReachedClock[i] = reached[i];
        }
        net.minecraft.nbt.ListTag advancements =
                tag.getList("startAdvancementIds", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < advancements.size(); i++) {
            data.startAdvancementIds.add(advancements.getString(i));
        }
        data.startAdvancementCount = tag.contains("startAdvancementCount")
                ? tag.getInt("startAdvancementCount")
                : data.startAdvancementIds.size();
        data.frozen = tag.getBoolean("frozen");
        data.phaseWarned = tag.getBoolean("phaseWarned");
        data.giantTreeGrown = tag.getBoolean("giantTreeGrown");
        for (long packed : tag.getLongArray("giantTreeOrigins")) {
            data.giantTreeOrigins.add(packed);
        }
        for (long packed : tag.getLongArray("hiveNodes")) {
            data.hiveNodes.add(packed);
        }
        long[] born = tag.getLongArray("giantTreeBorn");
        for (int i = 0; i + 1 < born.length; i += 2) data.giantTreeBorn.put(born[i], born[i + 1]);
        for (long packed : tag.getLongArray("deadGiantTrees")) data.deadGiantTrees.add(packed);
        // 旧セーブ: 「古代樹の核」ブロック時代の記録も引き継ぐ(ブロックは削除済みだが、
        // 感染ゾーンの中心としての意味は同じなので、そのまま使う)。
        for (long packed : tag.getLongArray("giantTreeCores")) {
            data.hiveNodes.add(packed);
        }
        // 同じ座標は 1 つにまとめる(2026-09-24)。以前はノードのチャンクを読み込むたびに同じ座標を
        // 積み直していたので、古いセーブには重複が残っている。並びは最初に出た順のまま。
        if (data.hiveNodes.size() > 1) {
            it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet unique =
                    new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(data.hiveNodes);
            if (unique.size() != data.hiveNodes.size()) {
                data.hiveNodes.clear();
                data.hiveNodes.addAll(unique);
            }
        }
        // 旧セーブ(このフラグ導入前)は「核の記録があれば記録済み」とみなす。
        for (long packed : tag.getLongArray("hiveChambers")) {
            data.hiveChambers.add(packed);
        }
        data.hiveNodesRecorded = tag.contains("hiveNodesRecorded")
                ? tag.getBoolean("hiveNodesRecorded")
                : tag.contains("giantTreeCoresRecorded")
                        ? tag.getBoolean("giantTreeCoresRecorded")
                        : !data.hiveNodes.isEmpty();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("phase", phase);
        tag.putLong("points", points);
        tag.putLong("lastPointsGameTime", lastPointsGameTime);
        tag.putDouble("timePointsCarry", timePointsCarry);
        tag.putDouble("multipliedPointsCarry", multipliedPointsCarry);
        tag.putLong("corruptedBlocks", corruptedBlocks);
        tag.putLong("overgrownChunks", overgrownChunks);
        long[] ledgerKeys = new long[overgrownSince.size()];
        long[] ledgerTimes = new long[overgrownSince.size()];
        int ledgerIndex = 0;
        // fastIterator: 項目ごとに Entry を作らない(2026-09-25。並びも中身も同じ)。
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry
                : it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterable(overgrownSince)) {
            ledgerKeys[ledgerIndex] = entry.getLongKey();
            ledgerTimes[ledgerIndex] = entry.getLongValue();
            ledgerIndex++;
        }
        tag.putLongArray("overgrownChunkKeys", ledgerKeys);
        tag.putLongArray("overgrownChunkTimes", ledgerTimes);
        tag.putLongArray("climaxDeepConvertedChunks", deepConvertedChunks.toLongArray());
        tag.putLong("sleepSkippedTicks", sleepSkippedTicks);
        tag.putLongArray("phaseReachedClock", phaseReachedClock.clone());
        net.minecraft.nbt.ListTag advancements = new net.minecraft.nbt.ListTag();
        for (String id : startAdvancementIds) {
            advancements.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put("startAdvancementIds", advancements);
        tag.putInt("startAdvancementCount", startAdvancementCount);
        tag.putBoolean("frozen", frozen);
        tag.putBoolean("phaseWarned", phaseWarned);
        tag.putBoolean("giantTreeGrown", giantTreeGrown);
        tag.putLongArray("giantTreeOrigins", giantTreeOrigins.toLongArray());
        tag.putLongArray("hiveNodes", hiveNodes.toLongArray());
        long[] born = new long[giantTreeBorn.size() * 2];
        int bi = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry e : giantTreeBorn.long2LongEntrySet()) {
            born[bi++] = e.getLongKey();
            born[bi++] = e.getLongValue();
        }
        tag.putLongArray("giantTreeBorn", born);
        tag.putLongArray("deadGiantTrees", deadGiantTrees.toLongArray());
        tag.putBoolean("hiveNodesRecorded", hiveNodesRecorded);
        tag.putLongArray("hiveChambers", hiveChambers.toLongArray());
        return tag;
    }


    /**
     * 巨大樹の幹の真下に作った「巣の広間」の中心を記録する
     * ({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#place})。
     * 将来の mob「hive node」の湧き位置。
     */
    public void recordHiveChamber(BlockPos center) {
        hiveChambers.add(center.asLong());
        setDirty();
    }

    /** 記録済みの「巣の広間」の中心をすべて返す(読み取り専用)。 */
    public it.unimi.dsi.fastutil.longs.LongList hiveChambers() {
        return it.unimi.dsi.fastutil.longs.LongLists.unmodifiable(hiveChambers);
    }

    /**
     * {@code pos} から水平・垂直とも {@code radius} ブロック以内にある「巣の広間」の中心を返す。
     * 無ければ {@code null}。将来の mob「hive node」の湧き判定・再湧き抑止に使う想定。
     */
    public BlockPos nearestHiveChamber(BlockPos pos, double radius) {
        double limitSq = radius * radius;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < hiveChambers.size(); i++) {
            BlockPos center = BlockPos.of(hiveChambers.getLong(i));
            double dSq = center.distSqr(pos);
            if (dSq <= limitSq && dSq < bestSq) {
                bestSq = dSq;
                best = center;
            }
        }
        return best;
    }
    /**
     * 生成した巨大樹の「ハイヴノード」(旧「古代樹の核」)の座標を記録する。
     * ノードはチャンクを読み込むたびに登録し直すので、<b>同じ座標は 2 つ積まない</b>(2026-09-24)。
     */
    /** 記録にあるハイヴノードの位置({@link BlockPos#asLong} の写し)。地脈の光の流れの行き先(LeyFlowSync、2026-10-16)に使う。 */
    public long[] hiveNodeSnapshot() {
        return hiveNodes.toLongArray();
    }

    /** 記録にある巨大樹の中心(水平、(x << 32) | (z & 0xFFFFFFFF) の写し)。地脈の光の流れの行き先に使う。 */
    public long[] giantTreeOriginSnapshot() {
        return giantTreeOrigins.toLongArray();
    }

    public void recordHiveNode(BlockPos core) {
        long key = core.asLong();
        if (!hiveNodes.contains(key)) {
            hiveNodes.add(key);
        }
        hiveNodesRecorded = true;
        setDirty();
    }

    /**
     * 「ハイヴノード」が倒されたときに
     * {@link com.yayuy.overgrownvegetation.entity.HiveNodeEntity#die} から呼ぶ。記録から外すことで、
     * ハイヴノードの周囲(既定 60 ブロック)で胞子感染2 → 3 になる強制判定({@link #hasHiveNodeWithin})が消える。
     *
     * <p>記録が導入される前に生えた巨大樹(記録が無く {@link #giantTreeOrigins} で代用しているワールド)では、
     * 倒されたハイヴノードの真下・真上の中心座標(水平 8 ブロック以内)の巨大樹中心記録も外す。そうしないと
     * 倒しても代用判定が残ってしまう。中心記録は新しい巨大樹の間隔チェックにも使われるが、
     * ハイヴノードを失った巨大樹の近くに別の巨大樹が生えうるようになる程度の副作用なので許容する。
     *
     * @return 何かしら記録を外したら true
     */
    public boolean removeHiveNode(BlockPos core) {
        long key = core.asLong();
        boolean changed = false;
        while (hiveNodes.rem(key)) {                  // 重複して積まれていても全部外す
            changed = true;
        }
        if (!hiveNodesRecorded) {
            // 核の記録が無い旧ワールド: 中心座標の代用記録を外す。
            for (int i = giantTreeOrigins.size() - 1; i >= 0; i--) {
                long packed = giantTreeOrigins.getLong(i);
                double dx = (int) (packed >> 32) - core.getX();
                double dz = (int) packed - core.getZ();
                if (dx * dx + dz * dz <= 8.0D * 8.0D) {
                    giantTreeOrigins.removeLong(i);
                    changed = true;
                }
            }
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    /**
     * {@code pos} から {@code radius} ブロック以内に生きている「ハイヴノード」があるか(3D 距離)。
     * 記録が 1 つも無い旧ワールド(旧「古代樹の核」しか無いワールド)では、巨大樹の中心座標との水平距離で代用する
     * (記録を一度でも始めたワールドは、全部倒されて記録が空になっても代用しない)。
     */
    public boolean hasHiveNodeWithin(BlockPos pos, double radius) {
        return hiveNodeWithin(pos, radius, false);
    }

    /**
     * {@link #hasHiveNodeWithin} の水平距離版(高さを問わない円柱)。ハイヴノードのまわりで火が燃え広がらない範囲
     * ({@code purify/HiveFireGuard}、2026-10-10)に使う。
     */
    public boolean hasHiveNodeWithinHorizontal(BlockPos pos, double radius) {
        return hiveNodeWithin(pos, radius, true);
    }

    private boolean hiveNodeWithin(BlockPos pos, double radius, boolean horizontal) {
        if (radius <= 0.0D) {
            return false;
        }
        double r2 = radius * radius;
        if (!hiveNodes.isEmpty() || hiveNodesRecorded) {
            for (int i = 0; i < hiveNodes.size(); i++) {
                long packed = hiveNodes.getLong(i);
                double dx = BlockPos.getX(packed) - pos.getX();
                double dy = horizontal ? 0.0D : BlockPos.getY(packed) - pos.getY();
                double dz = BlockPos.getZ(packed) - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i < giantTreeOrigins.size(); i++) {
            long packed = giantTreeOrigins.getLong(i);
            double dx = (int) (packed >> 32) - pos.getX();
            double dz = (int) packed - pos.getZ();
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    /** このワールドで巨大樹がこれまでに 1 本でも生えたか(= 古代樹の核が存在する)。 */
    public boolean hasGiantTreeGrown() {
        return giantTreeGrown;
    }

    /**
     * 巨大樹が生え始めた/生えたときに呼ぶ。一度立てたフラグは戻さない。
     * {@link com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue#enqueue} から呼ばれる。
     */
    public void markGiantTreeGrown() {
        if (!giantTreeGrown) {
            giantTreeGrown = true;
            setDirty();
        }
    }

    /**
     * 生成した巨大樹の中心座標(水平)を記録する。以降、この位置の近く({@code giantTreeMinSpacing})
     * には新しい巨大樹が生えなくなる。{@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature#place}
     * から、木の座標計算に成功したとき呼ばれる。
     */
    public void recordGiantTreeOrigin(int x, int z) {
        giantTreeOrigins.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        setDirty();
    }

    /** 巨大樹が生えた時刻を覚える(梢の胞子の放出の育ち具合、2026-10-23 胞子の場 D)。 */
    public void recordGiantTreeBorn(int x, int z, long gameTime) {
        giantTreeBorn.put(((long) x << 32) | (z & 0xFFFFFFFFL), gameTime);
        setDirty();
    }

    /** 巨大樹が生えた時刻。記録が無ければ {@link Long#MIN_VALUE}(育ちきった扱い)。 */
    public long giantTreeBorn(long packedOrigin) {
        return giantTreeBorn.containsKey(packedOrigin) ? giantTreeBorn.get(packedOrigin) : Long.MIN_VALUE;
    }

    /** テスト用: 巨大樹の記録(中心・生えた時刻・止まった印)を消す。 */
    public void forgetGiantTreeForTests(long packedOrigin) {
        giantTreeOrigins.rem(packedOrigin);
        giantTreeBorn.remove(packedOrigin);
        deadGiantTrees.remove(packedOrigin);
        setDirty();
    }

    /** ハイヴノードを倒された巨大樹か(胞子の生産が止まっている)。 */
    public boolean isGiantTreeDead(long packedOrigin) {
        return deadGiantTrees.contains(packedOrigin);
    }

    public int deadGiantTreeCount() {
        return deadGiantTrees.size();
    }

    /** ハイヴノードを倒されていない巨大樹の数(バイオマスの貯蓄上限、2026-10-23 追記 14)。 */
    public int livingGiantTreeCount() {
        int n = 0;
        for (int i = 0; i < giantTreeOrigins.size(); i++) if (!deadGiantTrees.contains(giantTreeOrigins.getLong(i))) n++;
        return n;
    }

    /**
     * ハイヴノードが倒された: 水平 {@code radius} ブロック以内でいちばん近い巨大樹の胞子の生産を止める(2026-10-23 E2、再生なし)。
     *
     * @return 止めた巨大樹の中心(無ければ {@link Long#MIN_VALUE})
     */
    public long killGiantTreeNear(BlockPos node, double radius) {
        long best = Long.MIN_VALUE;
        double bestD = radius * radius;
        for (int i = 0; i < giantTreeOrigins.size(); i++) {
            long packed = giantTreeOrigins.getLong(i);
            double dx = (int) (packed >> 32) + 0.5D - node.getX(), dz = (int) packed + 0.5D - node.getZ();
            double d = dx * dx + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = packed;
            }
        }
        if (best != Long.MIN_VALUE && deadGiantTrees.add(best)) setDirty();
        return best;
    }

    /**
     * {@code (x, z)} を中心とした水平半径 {@code minSpacing} ブロック以内に、これまでに記録された
     * 巨大樹の中心があるか。{@code minSpacing <= 0} なら常に {@code false}(間隔チェック無効)。
     */
    public boolean hasGiantTreeOriginWithin(int x, int z, int minSpacing) {
        if (minSpacing <= 0) {
            return false;
        }
        long limitSq = (long) minSpacing * minSpacing;
        for (int i = 0; i < giantTreeOrigins.size(); i++) {
            long packed = giantTreeOrigins.getLong(i);
            long dx = (int) (packed >> 32) - x;
            long dz = (int) packed - z;
            if (dx * dx + dz * dz <= limitSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渡した {@link ServerLevel} がどの次元であっても、常にオーバーワールドに紐付いたインスタンスを返す。
     * フェーズはワールド全体で 1 つだけ管理する。
     */
    public static PhaseData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public int getPhase() {
        return phase;
    }

    public long getPoints() {
        return points;
    }

    public long getCorruptedBlocks() {
        return corruptedBlocks;
    }

    /** これまでに過繁茂系バイオームになったチャンクの累計数。 */
    public long getOvergrownChunks() {
        return overgrownChunks;
    }

    /** フェーズの自動進行が凍結されているか。 */
    public boolean isFrozen() {
        return frozen;
    }

    /**
     * フェーズの自動進行の凍結を切り替える({@code /ov phase freeze <true|false>})。
     *
     * <p>凍結を解除した直後に、凍結中に経過した時間分の時間経過ポイントがまとめて入らないよう、
     * 加算の基準時刻を「今」に引き直す。
     */
    public void setFrozen(ServerLevel overworld, boolean value) {
        if (frozen == value) {
            return;
        }
        frozen = value;
        if (!value && phase >= 1 && lastPointsGameTime >= 0) {
            lastPointsGameTime = overworld.getGameTime();
        }
        setDirty();
    }

    /**
     * {@link CorruptionCounter} から、この MOD が新規に汚染したブロック数を累計へ加える。
     * 進行状況の表示用の統計で、フェーズ進行には影響しない(2026-09-13 の再編で昇格条件から外れた)。
     *
     * @param level 呼び出し元の次元(どの次元でもよい)
     */
    public void addCorruptedBlocks(ServerLevel level, long amount) {
        if (amount <= 0L) {
            return;
        }
        corruptedBlocks += amount;
        setDirty();
    }

    /**
     * チャンクが新たに過繁茂系バイオームになったときに
     * {@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} から呼ぶ。
     * 累計を増やし、1 チャンクあたり {@code points.perOvergrownChunk}(既定 5)のポイントを加算する。
     * 累計は時間経過ポイントの倍率({@code points.perTickPerOvergrownChunk})の元にもなるので、
     * 汚染が広がるほどポイントの貯まり方が速くなる(チャンク数そのものが昇格条件になることはない)。
     *
     * <p>凍結中({@link #frozen})やフェーズ0の間は累計だけ増やし、ポイント加算とフェーズ判定は行わない
     * ({@link #addPoints} 側の早期リターンに合わせた挙動)。
     *
     * @param level 呼び出し元の次元(どの次元でもよい — フェーズ進行は必ずオーバーワールドで行う)
     */
    public void addOvergrownChunks(ServerLevel level, long amount) {
        if (amount <= 0L) {
            return;
        }
        overgrownChunks += amount;
        setDirty();

        ServerLevel overworld = level.getServer().overworld();
        long perChunk = DesignData.phases().pointsPerOvergrownChunk();
        if (perChunk > 0L) {
            // addPoints が終わりにフェーズ判定まで行う。
            addPoints(overworld, perChunk * amount);
        } else {
            tryAdvance(overworld);
        }
    }

    /**
     * チャンクが新たに過繫茂系バイオームになったことを<b>台帳へ記録</b>し、あわせて累計とポイントも
     * 増やす({@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} から呼ぶ)。
     *
     * <p>既に台帳に載っているチャンクは数え直さない。{@link #addOvergrownChunks} の注記にあった
     * 「縁のなだらか化の振幅を上げると同じチャンクを二重に数えうる」という穴は、
     * 台帳が重複を弾くようになったぶんここで塞がる。
     *
     * @return 実際に新しく記録されたチャンク数
     */
    public int markOvergrownChunks(ServerLevel level, Iterable<ChunkPos> positions) {
        long now = clock(level.getServer());
        int added = 0;
        for (ChunkPos pos : positions) {
            long key = pos.toLong();
            if (!overgrownSince.containsKey(key)) {
                overgrownSince.put(key, now);
                added++;
                // 胞子の場でも汚染済み・放出中に(2026-10-23 第 2 段。どの経路で載っても)
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onLedgerAdded(key, now);
            }
        }
        if (added > 0) {
            setDirty();
            addOvergrownChunks(level, added);
        }
        return added;
    }

    /** {@link #markOvergrownChunks} の 1 チャンク版。新しく記録されたら {@code true}。 */
    public boolean markOvergrownChunk(ServerLevel level, ChunkPos pos) {
        return markOvergrownChunks(level, java.util.List.of(pos)) > 0;
    }

    /**
     * この台帳が導入される前のセーブ向けの拾い直し。既に過繫茂バイオームなのに台帳へ載っていない
     * チャンクを見つけたときに呼ぶと、<b>そのときの時刻</b>で記録する。
     *
     * <p>累計とポイントは増やさない —— 昔からあった汚染を「今広がった」と数えると、
     * 旧セーブを読み込んだ瞬間にフェーズが跳ね上がるため。14 日の計時はここから始まるので、
     * 旧セーブでは「拾い直してから 14 日」で極相化が始まる。
     *
     * @return 新しく拾ったら {@code true}
     */
    public boolean adoptOvergrownChunk(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        if (overgrownSince.containsKey(key)) {
            return false;
        }
        overgrownSince.put(key, clock(level.getServer()));
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onLedgerAdded(key, clock(level.getServer()));
        setDirty();
        return true;
    }

    /**
     * チャンクを極相({@code engulfed})へ上げたことを台帳へ記録する。
     *
     * <p><b>台帳の項目は消さない。</b> 台帳に載っている = 「過繁茂以上(過繁茂 or 極相)」という意味なので、
     * 極相になったチャンクも周囲の判定({@link #canBecomeClimax})では条件を満たす側として数え続ける。
     * 消してしまうと、最初の 1 チャンクが極相になった時点でその周りが永久に条件を満たせなくなり、
     * <b>極相が 1 チャンクだけ生まれて広がらない</b>という形で表に出る(原因が分かりにくい)。
     *
     * <p>極相の印は<b>値の符号</b>で持つ(過繁茂になった時刻 {@code t} に対して {@code -(t+1)})。
     * 別の集合を持たないので、セーブ形式も走査コストも増えない。普通は過繁茂を経てから極相になるが、
     * 過繁茂を飛ばして直接極相にする経路(デバッグコマンドなど)のために、
     * 台帳に無ければ「今」の時刻で載せる。
     *
     * <p>面積({@link #getOvergrownChunks})とフェーズポイントはここでは増やさない ——
     * そのチャンクが過繁茂になった時点で数え済みだから。
     *
     * @return まだ極相でなかったので印を付けたら {@code true}
     */
    public boolean markClimaxChunk(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        // 台帳の既定値(-1)と「極相」の負値は見分けが付かないので、必ず containsKey で分ける。
        boolean present = overgrownSince.containsKey(key);
        long value = present ? overgrownSince.get(key) : 0L;
        if (present && value < 0L) {
            return false; // 既に極相。
        }
        long since = present ? value : clock(level.getServer());
        overgrownSince.put(key, -(since + 1L));
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onClimax(key, since);
        setDirty();
        return true;
    }

    /** そのチャンクが「過繁茂以上」(過繁茂 or 極相)として台帳に載っているか。 */
    public boolean isOvergrownChunk(ChunkPos pos) {
        return overgrownSince.containsKey(pos.toLong());
    }

    /** {@link #isOvergrownChunk} の {@link ChunkPos#toLong} 版(台帳の縁を数えるときに ChunkPos を作らない)。 */
    public boolean isOvergrownKey(long chunkKey) {
        return overgrownSince.containsKey(chunkKey);
    }

    /**
     * チャンクを台帳から外す(順 2 の着手順 9、火炎放射器で焼けた荒地にしたとき。2026-10-08)。台帳から消す経路はこれだけ。
     * <b>極相は外さない</b>(岩盤まで書き換え済みで、焼いても戻らない —— 呼ぶ側が極相を除く)。
     * 面積の累計({@link #getOvergrownChunks})とフェーズポイントは戻さない(進んだ分は取り消さない。また飲まれると数え直す)。
     *
     * @return 外したら {@code true}
     */
    public boolean forgetOvergrownChunk(ChunkPos pos) {
        long key = pos.toLong();
        if (!overgrownSince.containsKey(key) || overgrownSince.get(key) < 0L) {
            return false;
        }
        overgrownSince.remove(key);
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.onLedgerRemoved(key);
        setDirty();
        return true;
    }

    // --- 汚染の暦(2026-09-26) ---

    /**
     * 汚染の暦(tick)= オーバーワールドの gameTime + 眠って飛ばした時間({@link #sleepSkippedTicks})。
     * 台帳の時刻・極相の齢・台帳の広がりの予定表は全部これで測る(寝た夜も 1 日に数える)。
     * 睡眠を 1 度も挟んでいなければ gameTime と同じなので、この暦より前の台帳の時刻もそのまま使える。
     */
    public long clock(net.minecraft.server.MinecraftServer server) {
        return server.overworld().getGameTime() + sleepSkippedTicks;
    }

    /** {@link #clock} を、どの次元の {@link ServerLevel} からでも引く近道。 */
    public static long clockNow(ServerLevel level) {
        return get(level).clock(level.getServer());
    }

    /** 眠って飛ばした時間の累計(tick)。 */
    public long sleepSkippedTicks() {
        return sleepSkippedTicks;
    }

    /**
     * テスト用: 睡眠の累計を元の値へ戻す。GameTest のワールドは実行をまたいで残るので、足したまま終えると
     * 台帳の時刻と gameTime がずれ、極相の台帳のテストの「今」が狂う。
     */
    public void restoreSleepSkipForTests(long value) {
        sleepSkippedTicks = Math.max(0L, value);
        setDirty();
    }

    /** テスト用: 「ハイヴノードの記録を一度でも始めたか」(記録を足して外したテストが、旧ワールド向けの代用を切ったまま終えないように)。 */
    public boolean hiveNodesRecordedForTests() {
        return hiveNodesRecorded;
    }

    /** テスト用: {@link #hiveNodesRecordedForTests} を元の値へ戻す。 */
    public void restoreHiveNodesRecordedForTests(boolean value) {
        hiveNodesRecorded = value;
        setDirty();
    }

    /**
     * 眠って {@code ticks} だけ夜を飛ばした。暦を進め、{@code creditPoints} なら飛ばした時間ぶんの
     * 時間経過ポイント(毎 tick 「1 + 0.005 × 過繁茂チャンク数」)も入れる —— フェーズの日数は
     * 画面の「何日目」で数えたいので(ユーザー指定の日数から逆算したしきい値は、寝ても寝なくても同じ日に届く)。
     * フェーズ0 と凍結中はポイントを入れない({@link #addPoints} と同じ)。
     *
     * @return 入れたポイント(難易度の倍率を掛ける前)
     */
    public long addSleepSkip(ServerLevel overworld, long ticks, boolean creditPoints) {
        if (ticks <= 0L) {
            return 0L;
        }
        sleepSkippedTicks += ticks;
        setDirty();
        if (!creditPoints || frozen || phase < 1) {
            return 0L;
        }
        double raw = ticks * DesignData.phases().pointsPerTickWithChunks(overgrownChunks) + timePointsCarry;
        long whole = (long) Math.floor(raw);
        timePointsCarry = raw - whole;
        if (whole > 0L) {
            addPoints(overworld, whole);
        }
        return whole;
    }

    /** フェーズ {@code n} に初めて着いた暦。不明なら -1。 */
    public long phaseReachedClock(int n) {
        return n >= 0 && n < phaseReachedClock.length ? phaseReachedClock[n] : -1L;
    }

    /**
     * 極相の齢から差し引く長さ(tick)= 「F1 開始から F{@code minPhase} に着くまでにかかった暦」
     * (ユーザー指定 2026-09-26。{@link com.yayuy.overgrownvegetation.corruption.LedgerSpreadMath#climaxAgeReached})。
     *
     * <p>この記録より前のセーブは着いた時刻を持っていないので、ここで埋める: F{@code minPhase} は
     * <b>今</b>(= 更新後に初めて見た時刻)、F1 は<b>台帳でいちばん古い時刻</b>(汚染が始まったころ)。
     * こうすると更新の直後に古いチャンクが一斉に極相になることはなく、いちばん古いチャンクから
     * 今の {@code minAgeTicks} 後に始まる。
     */
    public long climaxAgeOffsetTicks(net.minecraft.server.MinecraftServer server, int minPhase) {
        if (minPhase <= 1 || minPhase >= phaseReachedClock.length || phase < minPhase) {
            return 0L;
        }
        long now = clock(server);
        if (phaseReachedClock[minPhase] < 0L) {
            phaseReachedClock[minPhase] = now;
            setDirty();
        }
        if (phaseReachedClock[1] < 0L) {
            phaseReachedClock[1] = Math.min(oldestLedgerClock(now), phaseReachedClock[minPhase]);
            setDirty();
        }
        return com.yayuy.overgrownvegetation.corruption.LedgerSpreadMath.phaseDelay(
                phaseReachedClock[1], phaseReachedClock[minPhase]);
    }

    /** 台帳でいちばん古い時刻(極相の印を外した値)。空なら {@code fallback}。 */
    private long oldestLedgerClock(long fallback) {
        long oldest = Long.MAX_VALUE;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry
                : it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterable(overgrownSince)) {
            oldest = Math.min(oldest, decodeSince(entry.getLongValue()));
        }
        return oldest == Long.MAX_VALUE ? fallback : oldest;
    }

    /**
     * フェーズが {@code newPhase} になったときの記録。初めて着いたフェーズの暦を残し、
     * 下がったとき(コマンド)はそれより上の記録を消す(上がり直したときに付け直すため)。
     */
    private void recordPhaseReached(net.minecraft.server.MinecraftServer server, int newPhase) {
        long now = clock(server);
        for (int n = 1; n < phaseReachedClock.length; n++) {
            if (n > newPhase) {
                phaseReachedClock[n] = -1L;
            } else if (n == newPhase && phaseReachedClock[n] < 0L) {
                phaseReachedClock[n] = now;
            }
        }
        setDirty();
    }

    private static long[] unknownClocks() {
        long[] clocks = new long[PhaseTable.MAX_PHASE + 1];
        java.util.Arrays.fill(clocks, -1L);
        return clocks;
    }

    /** そのチャンクが既に極相か。 */
    public boolean isClimaxChunk(ChunkPos pos) {
        return overgrownSince.get(pos.toLong()) < 0L && overgrownSince.containsKey(pos.toLong());
    }

    /**
     * 写しで選んだ極相の候補が、<b>今の</b>台帳でもまだ上げられるか(2026-10-06、別スレッドの判定の答えを当てはめるとき)。
     * 台帳に載っていて、まだ極相でなく、周り {@code radiusChunks} の円が埋まっている。齢は写してから減らないので見直さない。
     */
    public boolean canStillPromote(ChunkPos pos, int radiusChunks) {
        long key = pos.toLong();
        return overgrownSince.containsKey(key) && overgrownSince.get(key) >= 0L
                && neighbourhoodFilled(overgrownSince, pos, radiusChunks);
    }

    /** そのチャンクが過繁茂になったゲーム内時刻(tick)。台帳に無ければ {@code -1}。極相でも元の時刻を返す。 */
    public long overgrownSinceTick(ChunkPos pos) {
        long value = overgrownSince.get(pos.toLong());
        if (!overgrownSince.containsKey(pos.toLong())) {
            return -1L;
        }
        return decodeSince(value);
    }

    /** 台帳の値 → 過繁茂になった時刻。負なら極相の印なので復号する。 */
    private static long decodeSince(long value) {
        return value >= 0L ? value : -value - 1L;
    }

    /**
     * {@code center} が極相({@code engulfed})へ上がれる状態か。{@code climax.json} の
     * {@code biome.neighborRadiusChunks} / {@code biome.minAgeDays} から呼ぶ。
     * <ul>
     *     <li>まだ極相になっていないこと</li>
     *     <li>{@code center} 自身が過繁茂になってから {@code minAgeTicks} 以上経っていること</li>
     *     <li>半径 {@code radiusChunks} の<b>円</b>のチャンク({@code dx² + dz² <= r²}。既定 12 なら自分を含めて 441 個。
     *     2026-10-03 に 8 の正方形 289 個から変えた —— ユーザー指定)がすべて過繁茂以上であること</li>
     * </ul>
     *
     * <p>見るのは台帳だけなので、対象のチャンクも周囲のチャンクも<b>読み込まれている必要はない</b>。
     * 先に齢を見てから周囲を走査するので、条件を満たさないほとんどの呼び出しは
     * 1 回のハッシュ参照で終わる。
     */
    public boolean canBecomeClimax(ChunkPos center, int radiusChunks, long minAgeTicks, long nowGameTime) {
        long value = overgrownSince.get(center.toLong());
        if (!overgrownSince.containsKey(center.toLong()) || value < 0L) {
            return false; // 台帳に無い、または既に極相。
        }
        if (nowGameTime - decodeSince(value) < minAgeTicks) {
            return false;
        }
        return neighbourhoodFilled(overgrownSince, center, radiusChunks);
    }

    /**
     * {@code center} の半径 {@code radiusChunks} の円が全て過繁茂以上で埋まっているか。
     * 円で測る(順 1 の着手順 10、ユーザー指定)—— 縁からの深さ d({@code OvergrowthDepth})が上限に届くのと同じ条件。
     */
    private static boolean neighbourhoodFilled(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap ledger, ChunkPos center,
                                               int radiusChunks) {
        int r2 = radiusChunks * radiusChunks;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz <= r2
                        && !ledger.containsKey(ChunkPos.asLong(center.x + dx, center.z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code center} の半径 {@code radiusChunks} の円のうち、まだ過繁茂以上になっていないチャンク数
     * ({@code /ov climax status} の診断用)。0 なら周囲の条件は満たしている。
     */
    public int countMissingNeighbours(ChunkPos center, int radiusChunks) {
        int r2 = radiusChunks * radiusChunks;
        int missing = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz <= r2
                        && !overgrownSince.containsKey(ChunkPos.asLong(center.x + dx, center.z + dz))) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /** 半径 {@code radiusChunks} の円に入るチャンクの数(自分を含む。12 なら 441)。{@code /ov climax status} の表示用。 */
    public static int neighbourhoodSize(int radiusChunks) {
        int r2 = radiusChunks * radiusChunks;
        int n = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 台帳のうち、極相の 2 つの条件それぞれを満たしているチャンク数({@code /ov climax status} の診断用)。
     * 極相になったチャンクは数えない。
     *
     * <p>「14 日」と「周囲の円(既定 441 チャンク)」のどちらで止まっているのかは、立っているチャンク 1 つを見ても
     * 分からない(たまたまそこが縁なだけ、ということが多い)。世界全体でどちらが効いているかを出す。
     *
     * @return {@code [齢を満たす数, 周囲が埋まっている数, 両方を満たす数]}
     */
    public int[] climaxGateCounts(int radiusChunks, long minAgeTicks, long nowGameTime) {
        int aged = 0;
        int filled = 0;
        int both = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry
                : it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterable(overgrownSince)) {
            long value = entry.getLongValue();
            if (value < 0L) {
                continue; // 既に極相。
            }
            boolean oldEnough = nowGameTime - value >= minAgeTicks;
            boolean full = neighbourhoodFilled(overgrownSince, new ChunkPos(entry.getLongKey()), radiusChunks);
            if (oldEnough) {
                aged++;
            }
            if (full) {
                filled++;
            }
            if (oldEnough && full) {
                both++;
            }
        }
        return new int[] {aged, filled, both};
    }

    /**
     * 汚染の暦を {@code ticks} だけ先へ送る({@code /ov climax age} 専用、2026-09-26)。
     * 眠って夜を飛ばしたのと同じ扱いで、{@code gameTime} も画面の「何日目」も動かない。
     *
     * <p><b>台帳の時刻ではなく「今」を動かす。</b> 台帳の側を過去へずらす作りだと、時刻は 0 より前へ
     * 行けない(負の値は極相の印)ので、一度 0 まで下げたあとは<b>何度撃っても何も起こらない</b>。
     * 齢のしきい値が {@code minAgeDays} + F1→F{@code minPhase} の暦になってワールドの齢より長くなると
     * 必ずその状態に入る(実際に入った)。暦を進める側なら頭打ちが無く、台帳の時刻も壊さない。
     *
     * <p>{@link #addSleepSkip} と違い<b>ポイントは入れない</b> —— 時間を飛ばしたいだけで、
     * フェーズまで進めたいわけではないため。
     */
    public void skipCalendar(long ticks) {
        if (ticks <= 0L) {
            return;
        }
        sleepSkippedTicks += ticks;
        setDirty();
    }

    /**
     * 台帳でいちばん古いチャンクの齢(tick)。空なら 0。{@code /ov climax age} が
     * 「あと何日で齢が足りるか」を出すのに使う。
     */
    public long oldestOvergrownAge(long now) {
        long oldest = oldestLedgerClock(now);
        return Math.max(0L, now - oldest);
    }

    /**
     * いま極相へ上げられるチャンクを集める({@code ClimaxTicker} が呼ぶ)。
     *
     * <p>台帳を丸ごと走査するが、<b>ほとんどの項目は「既に極相」か「齢が足りない」で
     * ハッシュ参照 1 回ずつ</b>で落ちる。重い円の走査(半径 12 で 441 回の参照)に入った回数を {@code maxChecks} で、
     * 返す件数を {@code maxResults} で頭打ちにしてあるので、
     * 広い汚染域が一斉に条件を満たしても 1 回の呼び出しが跳ねない(残りは次回に回る)。
     *
     * <h2>続きから見る(ラウンドロビン、2026-09-25。ユーザー承認の「汚染の見直し」、監査報告 1956)</h2>
     * <p>以前は毎回台帳の<b>先頭から</b>予算を使っていた。反復順はハッシュ順で、再ハッシュしない限り毎回同じなので、
     * 汚染域の縁の「齢は足りたが周りが埋まっていない」チャンクが先頭側に {@code maxChecks} 件を超えてあると、
     * それより後ろの<b>昇格できるチャンクが永久に見られなかった</b>。
     *
     * <p>今は齢を満たした候補に反復順の通し番号を振り、{@link #climaxScanCursor} 番から予算を使う。
     * 予算が尽きたら、まだ見ていない最初の番号を覚えて次回そこから続ける。最後まで見て予算が残れば、
     * 先頭へ折り返して {@code cursor} の手前まで見る(1 周し終えたら 0 に戻す)。台帳の反復は多くて 2 回なので、
     * 手間は今までと同じ O(台帳の大きさ)。{@code maxChecks} / {@code maxResults} の意味も同じ。
     *
     * <p>返したチャンクは呼び出し側がすぐ極相の印を付ける(候補から外れる)前提で、カーソルをそのぶん前へ詰める
     * —— 詰めないと、外れた数だけ後ろの候補を読み飛ばす。
     */
    public java.util.List<ChunkPos> collectClimaxPromotions(int radiusChunks, long minAgeTicks, long nowGameTime,
                                                            int maxChecks, int maxResults) {
        ClimaxScan scan = scanClimaxPromotions(overgrownSince, climaxScanCursor, radiusChunks, minAgeTicks, nowGameTime,
                maxChecks, maxResults);
        climaxScanCursor = scan.nextCursor();
        return scan.found();
    }

    /** {@link #scanClimaxPromotions} の答え: 上げられるチャンクと、次に始める候補の番号。 */
    public record ClimaxScan(java.util.List<ChunkPos> found, int nextCursor) {
    }

    /**
     * {@link #collectClimaxPromotions} の中身を、台帳と続きの番号を引数に取る形にしたもの(2026-10-06、別スレッドで回すため)。
     * {@code ledger} は台帳そのものか、{@link #snapshotLedger} の写し。この PhaseData の状態は変えない。
     * 写しは中の配列ごと写すので反復の順番も同じ —— 同じ台帳なら答えも同じ。
     */
    public static ClimaxScan scanClimaxPromotions(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap ledger, int cursor,
                                                  int radiusChunks, long minAgeTicks, long nowGameTime,
                                                  int maxChecks, int maxResults) {
        java.util.List<ChunkPos> found = new java.util.ArrayList<>();
        final int start = cursor;
        int checks = 0;
        int stoppedAt = -1;      // 予算が尽きたとき、まだ見ていない最初の候補の番号
        int foundBeforeStop = 0; // 返す候補のうち、番号が stoppedAt より前のもの
        int lapEnd = Integer.MAX_VALUE; // この周で見る番号の上限(折り返しの周は start の手前まで)
        for (int lap = 0; lap < 2; lap++) {
            int lapStart = lap == 0 ? start : 0;
            int foundBeforeLap = found.size();
            int ordinal = 0;
            // fastIterator は項目ごとに Entry を作らない(同じ 1 つを使い回すので、ループの外へ持ち出さない)。
            it.unimi.dsi.fastutil.objects.ObjectIterator<it.unimi.dsi.fastutil.longs.Long2LongMap.Entry> entries =
                    ledger.long2LongEntrySet().fastIterator();
            while (entries.hasNext() && ordinal < lapEnd) {
                it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry = entries.next();
                long value = entry.getLongValue();
                if (value < 0L || nowGameTime - value < minAgeTicks) {
                    continue; // 既に極相 / まだ若い。番号も振らない。
                }
                int index = ordinal++;
                if (index < lapStart) {
                    continue; // 前回までに見た番号(1 周目だけ)。
                }
                if (checks >= maxChecks || found.size() >= maxResults) {
                    stoppedAt = index;
                    foundBeforeStop = found.size() - foundBeforeLap;
                    break;
                }
                checks++;
                ChunkPos pos = new ChunkPos(entry.getLongKey());
                if (neighbourhoodFilled(ledger, pos, radiusChunks)) {
                    found.add(pos);
                }
            }
            if (stoppedAt >= 0 || start == 0) {
                break; // 予算切れ、または先頭から始めて最後まで見た。
            }
            // 1 周目を最後まで見た(ordinal = 候補の総数)。先頭へ折り返して start の手前まで。
            // 候補が減って start が総数を超えていたら、折り返しの周で全部を見る。
            lapEnd = Math.min(start, ordinal);
        }
        return new ClimaxScan(found, stoppedAt >= 0 ? stoppedAt - foundBeforeStop : 0);
    }

    /** 極相の判定をどの候補から始めるか(別スレッドで判定するとき、写しと一緒に渡す)。 */
    public int climaxScanCursor() {
        return climaxScanCursor;
    }

    /** 別スレッドで判定した答えを当てはめるときに、続きの番号を書き戻す。 */
    public void setClimaxScanCursor(int cursor) {
        this.climaxScanCursor = Math.max(0, cursor);
    }

    /**
     * 台帳(過繁茂以上のチャンク → 載った暦。極相は負)の<b>写し</b>(2026-10-06)。中の配列ごと写すので速く(10 万件で 1 ms 未満)、
     * 反復の順番も元と同じ。別スレッドで台帳全体を舐める計算(縁・極相の判定・生態系の収入)に渡す。写しは書き換えないこと。
     */
    public it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap snapshotLedger() {
        return overgrownSince.clone();
    }

    /**
     * 台帳に載っているチャンク数(統計・デバッグ用)。{@link #getOvergrownChunks} の累計とは別物 ——
     * 累計は旧セーブぶんも含むが、台帳は導入後に記録したものだけを持つ。
     */
    public int overgrownLedgerSize() {
        return overgrownSince.size();
    }

    /**
     * 台帳に載っている全チャンク(過繁茂以上)の {@link ChunkPos#toLong()} を 1 つずつ渡す。
     * 生態系の台帳が区域ごとの繁茂率を数えるのに使う({@code ecosystem/Ecosystem})。
     */
    public void forEachOvergrownChunk(java.util.function.LongConsumer action) {
        it.unimi.dsi.fastutil.longs.LongIterator keys = overgrownSince.keySet().iterator();
        while (keys.hasNext()) {
            action.accept(keys.nextLong());
        }
    }

    /** 台帳のうち極相になっているチャンク数(統計・デバッグ用)。 */
    public int climaxLedgerSize() {
        int n = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry
                : it.unimi.dsi.fastutil.longs.Long2LongMaps.fastIterable(overgrownSince)) {
            if (entry.getLongValue() < 0L) {
                n++;
            }
        }
        return n;
    }

    /** そのチャンクは全層置換({@code ClimaxDeepConverter})をやり終えているか。 */
    public boolean isDeepConverted(ChunkPos pos) {
        return deepConvertedChunks.contains(pos.toLong());
    }

    /** 全層置換をやり終えた印を付ける。既に付いていれば {@code false}。 */
    public boolean markDeepConverted(ChunkPos pos) {
        if (deepConvertedChunks.add(pos.toLong())) {
            setDirty();
            return true;
        }
        return false;
    }

    /** 全層置換をやり終えたチャンク数(コマンドの表示用)。 */
    public int deepConvertedLedgerSize() {
        return deepConvertedChunks.size();
    }

    /**
     * サーバー側で定期的に(オーバーワールドの tick から)呼び出す。
     * フェーズ0→1の時刻ゲートと、フェーズ1以降の時間経過ポイント(pointsPerTick × 経過tick)をここで処理する。
     */
    public void tick(ServerLevel overworld) {
        long gameTime = overworld.getGameTime();

        if (frozen) {
            // 凍結中は時刻ゲートもポイント加算も行わない。解除した瞬間に凍結中の経過分が
            // まとめて入らないよう、基準時刻だけ進めておく。
            if (phase >= 1 && lastPointsGameTime >= 0 && lastPointsGameTime != gameTime) {
                lastPointsGameTime = gameTime;
                setDirty();
            }
            return;
        }

        if (phase == 0) {
            tryStartPhase1(overworld);
            return;
        }

        if (phase >= PhaseTable.MAX_PHASE) {
            return;
        }

        if (lastPointsGameTime < 0) {
            // フェーズ1になった直後の基準時刻。ここから経過した tick 分だけポイントを加算していく。
            lastPointsGameTime = gameTime;
            setDirty();
            return;
        }

        if (gameTime > lastPointsGameTime) {
            long elapsedTicks = gameTime - lastPointsGameTime;
            lastPointsGameTime = gameTime;
            // 1 tick あたりの収入は「perTick + perTickPerOvergrownChunk × 過繫茂系の総チャンク数」
            // (2026-09-15。汚染が広がるほど時間収入が増える)。総チャンク数はこの判定の時点の値を
            // 経過ぶん全体に当てる — 判定は 100 tick(5 秒)ごとなので誤差は無視できる。
            double perTick = DesignData.phases().pointsPerTickWithChunks(overgrownChunks);
            double gained = elapsedTicks * perTick + timePointsCarry;
            long whole = (long) gained;
            timePointsCarry = gained - whole;
            setDirty();
            if (whole > 0L) {
                addPoints(overworld, whole);
            }
            warnIfPhaseNear(overworld);
        }
    }

    /**
     * 次のフェーズまでの達成率が {@code defense.json} の {@code sporeAlarm.phaseWarnFraction}(既定 0.9)に
     * 達したら、胞子警報器のあるところへ「移行が近い」と 1 回だけ警告する(2026-09-19)。
     */
    private void warnIfPhaseNear(ServerLevel overworld) {
        // 胞子警報器(defense/SporeAlarms)は 2026-10-30 に parked/ へ。戻すときは parked の写しの中身へ
    }

    /**
     * プレイヤーが実績を解除したときに {@link AdvancementPoints} から呼ぶ(ポイント加算とは別枠)。
     * フェーズ0→1 の実績ゲート({@code global.startAdvancements}、既定 6)のために、
     * <b>フェーズ0の間だけ</b>解除された実績の種類を数える。同じ実績を別のプレイヤーが解除しても
     * 1 種類として数える。
     *
     * @param level          呼び出し元の次元(どの次元でもよい)
     * @param advancementId  実績の ID({@code minecraft:story/mine_stone} など)
     */
    public void recordStartAdvancement(ServerLevel level, String advancementId) {
        if (phase >= 1) {
            return; // ゲートは通過済み。以降は数えない(セーブを膨らませない)。
        }
        if (!startAdvancementIds.add(advancementId)) {
            return;
        }
        startAdvancementCount = startAdvancementIds.size();
        setDirty();
        tryStartPhase1(level.getServer().overworld());
    }

    /** フェーズ0の間に解除された実績の種類数(フェーズ1到達後はそのときの値のまま)。 */
    public int getStartAdvancementCount() {
        return startAdvancementCount;
    }

    /**
     * フェーズ0→1 の 2 つのゲート(ワールド経過時刻と実績の種類数、2026-09-15 から<b>両方</b>が条件)を
     * 見て、満たしていればフェーズ1へ上げる。{@link #tick} と {@link #recordStartAdvancement} の両方から
     * 呼ぶので、3 日経過後に 10 個目の実績を取った瞬間にも、実績を先に揃えて 3 日経った瞬間にも動く。
     */
    private void tryStartPhase1(ServerLevel overworld) {
        if (frozen || phase != 0) {
            return;
        }
        // 時刻の条件はワールドの難易度「地割れの発生日時」(2026-09-25。ワールドの値が無ければ startWorldAgeTicks)。
        if (overworld.getGameTime() < WorldDifficultyData.phase1StartTicks(overworld.getServer())) {
            return;
        }
        // 実績の数もワールドの値「地割れに要る実績の数」(2026-10-10。ワールドの値が無ければ startAdvancements)。
        if (startAdvancementCount < WorldDifficultyData.phase1Advancements(overworld.getServer())) {
            return;
        }
        advanceToPhase(overworld, 1);
    }

    /**
     * ポイントを加算し、必要ならフェーズを進める。フェーズが進むとポイントは 0 にリセットされるため、
     * 各フェーズの {@code pointsToAdvance} は「直前のフェーズ開始時点からの必要ポイント」を表す。
     *
     * <p>入る量にはワールドの難易度「フェーズ進行のポイント量」(2026-09-25 ユーザー指定: <b>獲得ポイント</b>に掛ける倍率)を掛ける。
     * 時間・チャンク・Mob・実績のポイントはすべてここを通るので、ここで 1 回掛ければ全部に効く。端数は次へ持ち越す。
     */
    public void addPoints(ServerLevel overworld, long amount) {
        if (frozen || phase < 1 || phase >= PhaseTable.MAX_PHASE || amount <= 0L) {
            return;
        }
        double scaled = amount * WorldDifficultyData.phasePointMultiplier(overworld.getServer()) + multipliedPointsCarry;
        long whole = (long) scaled;
        multipliedPointsCarry = scaled - whole;
        setDirty();
        if (whole <= 0L) {
            return;
        }
        points += whole;
        tryAdvance(overworld);
    }

    /**
     * 今のポイントで進めるところまでフェーズを進める({@code phases.json} の {@code pointsToAdvance} を
     * {@link PhaseTable#canAdvanceTo} が解釈する)。条件が設定されていないフェーズ(既定ではフェーズ7以降)
     * へは進まないので、ここで自動進行が止まる。
     */
    private void tryAdvance(ServerLevel overworld) {
        if (frozen || phase < 1) {
            return;
        }
        while (phase < PhaseTable.MAX_PHASE
                && DesignData.phases().canAdvanceTo(phase + 1, points)) {
            advanceToPhase(overworld, phase + 1);
        }
    }

    /**
     * フェーズ昇格のときに流す文。{@code phases.json} の {@code message} が書いてあればそれを
     * <b>そのまま</b>(翻訳を通さない)、無ければ言語ファイルの {@code phase_name.<n>}。
     * 胞子警報器の文にもこれが埋まる。
     */
    public static Component phaseAnnouncement(int phase) {
        String custom = com.yayuy.overgrownvegetation.data.DesignData.phases().message(phase);
        return custom != null
                ? Component.literal(custom)
                : Component.translatableWithFallback(
                        "message.overgrown_vegetation.phase_name." + phase, "Phase " + phase);
    }

    private void advanceToPhase(ServerLevel overworld, int newPhase) {
        this.phase = newPhase;
        // フェーズが進むたびにポイントを 0 にリセットする(次のフェーズの条件はここからの増分)。
        this.points = 0L;
        recordPhaseReached(overworld.getServer(), newPhase);
        setDirty();
        this.phaseWarned = false;
        // 切り替わりに流すのは 1 行だけ。文は phases.json の message(2026-09-29 ユーザー指定)、
        // 書いていなければ言語ファイルのフェーズ名(「フェーズ3-拡大」など)。
        Component name = phaseAnnouncement(newPhase);
        overworld.getServer().getPlayerList().broadcastSystemMessage(name, false);

        if (newPhase == 1) {
            // フェーズ1突入時の演出: 全プレイヤーから離れた地表で地震が起き、大地が裂けて胞子が噴き出す。
            com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
            // 実績ゲートの記録はもう使わないので捨てる(個数だけ startAdvancementCount に残す)。
            startAdvancementIds.clear();
        }
    }

    /**
     * コマンド用。フェーズを直接書き換え、ポイントは 0 にリセットする(通常のフェーズ進行と同じ挙動)。
     * フェーズ 1 未満から 1 へ上げた場合は、自然移行時と同じくフェーズ1突入時の地割れ演出も行う。
     */
    public void setPhase(ServerLevel overworld, int newPhase) {
        int clamped = Math.max(0, Math.min(PhaseTable.MAX_PHASE, newPhase));
        boolean enteringPhase1 = clamped == 1 && this.phase != 1;
        this.phase = clamped;
        this.points = 0L;
        recordPhaseReached(overworld.getServer(), clamped);
        setDirty();
        if (enteringPhase1) {
            com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
        }
    }
}
