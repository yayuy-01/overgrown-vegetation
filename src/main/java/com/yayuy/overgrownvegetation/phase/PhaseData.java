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
 * <p>実際にポイントを加算する箇所(2026-11-14 の作り直しから)は {@link AdvancementPoints}(実績解除)・{@link KillPoints}
 * (プレイヤーが植物側の Mob を倒した。1 日の上限つき {@link #addKillPoints})・{@link PhaseTicker}(時間 + 今の面積)の 3 箇所。
 * 上の「既定の条件」の数は古い。今の式と閾値は {@link com.yayuy.overgrownvegetation.data.PhaseTable} の頭。
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
     * <p>2026-10-30 まで極相の印を値の符号(負)で持っていた。極相は消したので、読み込むときに過繁茂の時刻へ戻す。
     *
     * <p>この台帳が導入される前のセーブでは空から始まる。既存の過繫茂チャンクは
     * {@link #adoptOvergrownChunk} で「見かけた時刻」を入れて拾い直す(時刻はそこから)。
     */
    private final Long2LongOpenHashMap overgrownSince = new Long2LongOpenHashMap();

    /**
     * 眠って飛ばした時間の累計(tick、2026-09-26)。{@link #clock} = オーバーワールドの gameTime + これ。
     * バニラの睡眠は dayTime だけを朝へ進めて gameTime を進めないので、gameTime だけで測ると「寝た夜」が
     * 台帳の時刻・時間経過ポイントから抜け落ちる(ユーザー指定「寝た時はしっかり一日分広げる」)。
     * 足すのは {@link com.yayuy.overgrownvegetation.corruption.LedgerSpread} の睡眠のイベントだけ
     * ({@code /time set} は数えない)。
     */
    private long sleepSkippedTicks;
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
    /** 撃破の点を数えている日(clock ÷ 24000)と、その日にもう入れた点(難易度の倍率を掛ける前。2026-11-14)。 */
    private long killPointsDay = -1L;
    private int killPointsToday;

    /** 胞子警報器の「次のフェーズが近い」警告をこのフェーズで出したか(2026-09-19)。昇格でリセット。 */
    private boolean phaseWarned;

    /**
     * 今のフェーズの中の小さな段(2026-11-14)。ポイントの進みで上がり({@link #refreshStage})、フェーズが変わると導入に戻る。
     * 保存するのは、変わった瞬間に 1 回だけイベントを流すためと、閾値の無いフェーズ(F10)で段をコマンドで決められるようにするため。
     */
    private PhaseStage stage = PhaseStage.INTRO;

    /**
     * テスト用の切り離した入れ物(ワールドの本物ではない)のときだけ非 null。イベントはバスへ流さずここへ渡し、
     * 全員への文・クライアントへの同期・地割れもしない。{@link #createDetachedForTests}。
     */
    @javax.annotation.Nullable
    private java.util.function.Consumer<net.neoforged.bus.api.Event> detachedSink;

    private PhaseData() {
        overgrownSince.defaultReturnValue(-1L);
    }

    /** GameTest 用: ワールドに登録しない入れ物を作る(イベントは {@code sink} へ)。 */
    public static PhaseData createDetachedForTests(java.util.function.Consumer<net.neoforged.bus.api.Event> sink) {
        PhaseData data = new PhaseData();
        data.detachedSink = sink;
        return data;
    }

    /** GameTest 用: 保存した中身から読み直す(ワールドの本物には触らない)。 */
    public static PhaseData loadDetachedForTests(CompoundTag tag, java.util.function.Consumer<net.neoforged.bus.api.Event> sink) {
        PhaseData data = load(tag);
        data.detachedSink = sink;
        return data;
    }

    private boolean isLive() {
        return detachedSink == null;
    }

    private void fire(net.neoforged.bus.api.Event event) {
        if (detachedSink != null) {
            detachedSink.accept(event);
        } else {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        }
    }

    private void syncToClients(ServerLevel overworld) {
        if (isLive()) {
            PhaseSync.sendToAll(overworld.getServer(), this);
        }
    }

    /** 今の段(フェーズ 0 では導入)。 */
    public PhaseStage getStage() {
        return stage;
    }

    /**
     * ポイントから段を求め直し、上がっていれば段を進めてイベントを流す(2026-11-14)。ポイントの進みでは段は下げない。
     * 閾値の無いフェーズ(物差しが無い)は今の段のまま。
     */
    private void refreshStage(ServerLevel overworld) {
        if (phase < 1) {
            return;
        }
        PhaseStage derived = DesignData.phases().stageFor(phase, points);
        if (derived == null || derived.ordinal() <= stage.ordinal()) {
            return;
        }
        PhaseStage old = stage;
        stage = derived;
        setDirty();
        fire(new PhaseEvents.StageChanged(overworld, phase, old, derived, true));
        syncToClients(overworld);
    }

    /**
     * コマンド用({@code /ov phase stage <段>})。次のフェーズの閾値があればポイントをその段の始まりに合わせ、
     * 無ければ段だけを決める。下げることもできる。
     *
     * @return 合わせた後のポイント(閾値が無くポイントを変えなかったときは -1)
     */
    public long setStage(ServerLevel overworld, PhaseStage newStage) {
        if (phase < 1) {
            return -1L;
        }
        long start = DesignData.phases().stageStartPoints(phase, newStage);
        if (start >= 0L) {
            points = start;
        }
        PhaseStage old = stage;
        stage = newStage;
        setDirty();
        if (old != newStage) {
            fire(new PhaseEvents.StageChanged(overworld, phase, old, newStage, false));
        }
        syncToClients(overworld);
        return start;
    }

    /** 保存したポイントの縮尺(2026-11-14 の作り直しで 2。印の無い古いセーブは {@link #convertOldPoints} で直す)。 */
    private static final int POINTS_SCALE = 2;
    /** 作り直しの前の既定の閾値(2026-09-28)。古いセーブの換算にだけ使う。 */
    private static final long[] OLD_POINTS_TO_ADVANCE = {0, 0, 263_000, 696_000, 1_421_000, 2_452_000,
            26_329_000, 67_841_000, 55_816_000, 73_784_000, 94_305_000};

    /**
     * 古い縮尺のポイントを、次のフェーズまでの進みの割合を保ったまま今の閾値へ直す【仮】(そのままだと読み込んだ直後に 1 フェーズ跳ぶ)。
     * 古い閾値は既定の値で見る(run/config で変えていたワールドは少しずれる)。
     */
    static long convertOldPoints(int phase, long oldPoints) {
        int next = phase + 1;
        if (next >= OLD_POINTS_TO_ADVANCE.length || OLD_POINTS_TO_ADVANCE[next] <= 0) {
            return 0L;
        }
        long now = DesignData.phases().pointsToAdvance(next);
        if (now == Long.MAX_VALUE) {
            return 0L;
        }
        double share = Math.max(0.0D, Math.min(0.999D, oldPoints / (double) OLD_POINTS_TO_ADVANCE[next]));
        return (long) Math.floor(share * now);
    }

    private static PhaseData load(CompoundTag tag) {
        PhaseData data = new PhaseData();
        data.phase = tag.getInt("phase");
        data.points = tag.getLong("points");
        if (tag.getInt("pointsScale") < POINTS_SCALE && data.phase >= 1) {
            data.points = convertOldPoints(data.phase, data.points);
        }
        data.killPointsDay = tag.contains("killPointsDay") ? tag.getLong("killPointsDay") : -1L;
        data.killPointsToday = tag.getInt("killPointsToday");
        // 小さな段(2026-11-14)。この欄の無い古いセーブは、ポイントから黙って求める(読み込みではイベントを流さない)。
        if (tag.contains("stage")) {
            data.stage = PhaseStage.byIndex(tag.getInt("stage"));
        } else if (data.phase >= 1) {
            PhaseStage derived = DesignData.phases().stageFor(data.phase, data.points);
            data.stage = derived != null ? derived : PhaseStage.INTRO;
        }
        data.lastPointsGameTime = tag.contains("lastPointsGameTime") ? tag.getLong("lastPointsGameTime") : -1L;
        data.timePointsCarry = tag.getDouble("timePointsCarry");
        data.multipliedPointsCarry = tag.getDouble("multipliedPointsCarry");
        data.corruptedBlocks = tag.getLong("corruptedBlocks");
        data.overgrownChunks = tag.getLong("overgrownChunks");
        long[] ledgerKeys = tag.getLongArray("overgrownChunkKeys");
        long[] ledgerTimes = tag.getLongArray("overgrownChunkTimes");
        if (ledgerKeys.length == ledgerTimes.length) {
            for (int i = 0; i < ledgerKeys.length; i++) {
                // 負の値は極相の印だった(極相は 2026-10-30 に消した)。過繁茂として、過繁茂になった時刻へ戻して読む
                long t = ledgerTimes[i];
                data.overgrownSince.put(ledgerKeys[i], t >= 0L ? t : -t - 1L);
            }
        }
        data.sleepSkippedTicks = Math.max(0L, tag.getLong("sleepSkippedTicks"));
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
        tag.putInt("pointsScale", POINTS_SCALE);
        tag.putLong("killPointsDay", killPointsDay);
        tag.putInt("killPointsToday", killPointsToday);
        tag.putInt("stage", stage.ordinal());
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
        tag.putLong("sleepSkippedTicks", sleepSkippedTicks);
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
     * 累計だけを増やす(2026-11-14 から点は入れない。面積の点は「今の面積」= 台帳の大きさで {@link #tick} が入れる)。
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

        // 2026-11-14: 新しいチャンク 1 つごとの点(5 点)はやめた。面積は「今の面積」として時間の点と一緒に入る(tick)。
        tryAdvance(level.getServer().overworld());
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
     * 旧セーブを読み込んだ瞬間にフェーズが跳ね上がるため。
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

    /** そのチャンクが過繁茂として台帳に載っているか。 */
    public boolean isOvergrownChunk(ChunkPos pos) {
        return overgrownSince.containsKey(pos.toLong());
    }

    /** {@link #isOvergrownChunk} の {@link ChunkPos#toLong} 版(台帳の縁を数えるときに ChunkPos を作らない)。 */
    public boolean isOvergrownKey(long chunkKey) {
        return overgrownSince.containsKey(chunkKey);
    }

    /**
     * チャンクを台帳から外す(順 2 の着手順 9、火炎放射器で焼けた荒地にしたとき。2026-10-08)。台帳から消す経路はこれだけ。
     * 面積の累計({@link #getOvergrownChunks})とフェーズポイントは戻さない(進んだ分は取り消さない。また飲まれると数え直す)。
     *
     * @return 外したら {@code true}
     */
    public boolean forgetOvergrownChunk(ChunkPos pos) {
        long key = pos.toLong();
        if (!overgrownSince.containsKey(key)) {
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
     * 台帳の時刻・胞子の場の暦は全部これで測る(寝た夜も 1 日に数える)。
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
     * 台帳の時刻と gameTime がずれ、台帳のテストの「今」が狂う。
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
        double raw = ticks * DesignData.phases().pointsPerTick(phase, overgrownLedgerSize()) + timePointsCarry;
        long whole = (long) Math.floor(raw);
        timePointsCarry = raw - whole;
        if (whole > 0L) {
            addPoints(overworld, whole);
        }
        return whole;
    }

    /** そのチャンクが過繁茂になったゲーム内時刻(tick)。台帳に無ければ {@code -1}。 */
    public long overgrownSinceTick(ChunkPos pos) {
        long value = overgrownSince.get(pos.toLong());
        if (!overgrownSince.containsKey(pos.toLong())) {
            return -1L;
        }
        return value;
    }

    /**
     * 台帳(過繁茂のチャンク → 載った暦)の<b>写し</b>(2026-10-06)。中の配列ごと写すので速く(10 万件で 1 ms 未満)、
     * 反復の順番も元と同じ。別スレッドで台帳全体を舐める計算(/ov perf bench の縁など)に渡す。写しは書き換えないこと。
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

        // 設計データの読み直しで閾値・区切りが変わったときも、ここで段が追いつく(下げはしない)。
        refreshStage(overworld);

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
            // 1 tick あたりの収入(2026-11-14 ユーザー決定)=(時間 50 + 面積 40 × 今の面積 ÷ そのフェーズのふつうの広さ)÷ 24000。
            // 今の面積 = 台帳の大きさ(押し返して台帳から外れると減る)。この判定の時点の値を経過ぶん全体に当てる(判定は 100 tick ごと)。
            double perTick = DesignData.phases().pointsPerTick(phase, overgrownLedgerSize());
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
     * プレイヤーが植物側の Mob を倒したときの点(2026-11-14【仮】。{@link KillPoints} から)。1 日(暦 {@link #clock} の 24000 tick の区切り)に
     * {@code points.killsPerDayCap} まで(難易度の倍率を掛ける前の点で数える)。実際に入れた点(倍率の前)を返す。
     */
    public int addKillPoints(ServerLevel overworld, int amount) {
        if (frozen || phase < 1 || phase >= PhaseTable.MAX_PHASE || amount <= 0) {
            return 0;
        }
        long day = clock(overworld.getServer()) / PhaseTable.TICKS_PER_DAY;
        if (day != killPointsDay) {
            killPointsDay = day;
            killPointsToday = 0;
        }
        int cap = DesignData.phases().killPointsPerDayCap();
        int add = Math.max(0, Math.min(amount, cap - killPointsToday));
        if (add <= 0) {
            return 0;
        }
        killPointsToday += add;
        setDirty();
        addPoints(overworld, add);
        return add;
    }

    /** 今の 1 日あたりの時間 + 面積の点(撃破と実績は入らない。難易度の倍率の前)。今のフェーズと台帳の大きさから。 */
    public double incomePerDay() {
        return DesignData.phases().pointsPerTick(phase, overgrownLedgerSize()) * PhaseTable.TICKS_PER_DAY;
    }

    /** 今日の撃破の点(テスト・表示用)。 */
    public int killPointsToday() {
        return killPointsToday;
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
        gainPoints(overworld, whole);
    }

    /** 倍率を掛けた後のポイントを足し、フェーズ → 段の順に進める(フェーズが上がったら新しいフェーズの段を見る)。 */
    private void gainPoints(ServerLevel overworld, long whole) {
        points += whole;
        tryAdvance(overworld);
        refreshStage(overworld);
    }

    /** GameTest 用: ワールドの難易度の倍率を通さずにポイントを足す(凍結・フェーズの範囲の判定は {@link #addPoints} と同じ)。 */
    public void addPointsUnscaledForTests(ServerLevel overworld, long amount) {
        if (frozen || phase < 1 || phase >= PhaseTable.MAX_PHASE || amount <= 0L) {
            return;
        }
        setDirty();
        gainPoints(overworld, amount);
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
        int oldPhase = this.phase;
        this.phase = newPhase;
        // フェーズが進むたびにポイントを 0 にリセットする(次のフェーズの条件はここからの増分)。
        this.points = 0L;
        // 新しいフェーズは導入から(2026-11-14)。
        this.stage = PhaseStage.INTRO;
        setDirty();
        this.phaseWarned = false;
        if (isLive()) {
            // 切り替わりに流すのは 1 行だけ。文は phases.json の message(2026-09-29 ユーザー指定)、
            // 書いていなければ言語ファイルのフェーズ名(「フェーズ3-拡大」など)。
            Component name = phaseAnnouncement(newPhase);
            overworld.getServer().getPlayerList().broadcastSystemMessage(name, false);
        }

        if (newPhase == 1) {
            if (isLive()) {
                // フェーズ1突入時の演出: 全プレイヤーから離れた地表で地震が起き、大地が裂けて胞子が噴き出す。
                com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
            }
            // 実績ゲートの記録はもう使わないので捨てる(個数だけ startAdvancementCount に残す)。
            startAdvancementIds.clear();
        }
        fire(new PhaseEvents.PhaseChanged(overworld, oldPhase, newPhase, true));
        syncToClients(overworld);
    }

    /**
     * コマンド用。フェーズを直接書き換え、ポイントは 0 にリセットする(通常のフェーズ進行と同じ挙動)。
     * フェーズ 1 未満から 1 へ上げた場合は、自然移行時と同じくフェーズ1突入時の地割れ演出も行う。
     */
    public void setPhase(ServerLevel overworld, int newPhase) {
        int clamped = Math.max(0, Math.min(PhaseTable.MAX_PHASE, newPhase));
        boolean enteringPhase1 = clamped == 1 && this.phase != 1;
        int oldPhase = this.phase;
        PhaseStage oldStage = this.stage;
        this.phase = clamped;
        this.points = 0L;
        // 段も導入へ戻す(ポイントを 0 にするのと同じ。2026-11-14)。
        this.stage = PhaseStage.INTRO;
        setDirty();
        if (enteringPhase1 && isLive()) {
            com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
        }
        if (oldPhase != clamped) {
            fire(new PhaseEvents.PhaseChanged(overworld, oldPhase, clamped, false));
        } else if (oldStage != PhaseStage.INTRO && clamped >= 1) {
            fire(new PhaseEvents.StageChanged(overworld, clamped, oldStage, PhaseStage.INTRO, false));
        }
        syncToClients(overworld);
    }
}
