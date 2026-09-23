package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PhaseTable;
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

    private PhaseData() {
        overgrownSince.defaultReturnValue(-1L);
    }

    private static PhaseData load(CompoundTag tag) {
        PhaseData data = new PhaseData();
        data.phase = tag.getInt("phase");
        data.points = tag.getLong("points");
        data.lastPointsGameTime = tag.contains("lastPointsGameTime") ? tag.getLong("lastPointsGameTime") : -1L;
        data.timePointsCarry = tag.getDouble("timePointsCarry");
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
        // 旧セーブ: 「古代樹の核」ブロック時代の記録も引き継ぐ(ブロックは削除済みだが、
        // 感染ゾーンの中心としての意味は同じなので、そのまま使う)。
        for (long packed : tag.getLongArray("giantTreeCores")) {
            data.hiveNodes.add(packed);
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
        tag.putLong("corruptedBlocks", corruptedBlocks);
        tag.putLong("overgrownChunks", overgrownChunks);
        long[] ledgerKeys = new long[overgrownSince.size()];
        long[] ledgerTimes = new long[overgrownSince.size()];
        int ledgerIndex = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : overgrownSince.long2LongEntrySet()) {
            ledgerKeys[ledgerIndex] = entry.getLongKey();
            ledgerTimes[ledgerIndex] = entry.getLongValue();
            ledgerIndex++;
        }
        tag.putLongArray("overgrownChunkKeys", ledgerKeys);
        tag.putLongArray("overgrownChunkTimes", ledgerTimes);
        tag.putLongArray("climaxDeepConvertedChunks", deepConvertedChunks.toLongArray());
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
    /** 生成した巨大樹の「ハイヴノード」(旧「古代樹の核」)の座標を記録する。 */
    public void recordHiveNode(BlockPos core) {
        hiveNodes.add(core.asLong());
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
        boolean changed = hiveNodes.rem(core.asLong());
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
        if (radius <= 0.0D) {
            return false;
        }
        double r2 = radius * radius;
        if (!hiveNodes.isEmpty() || hiveNodesRecorded) {
            for (int i = 0; i < hiveNodes.size(); i++) {
                long packed = hiveNodes.getLong(i);
                double dx = BlockPos.getX(packed) - pos.getX();
                double dy = BlockPos.getY(packed) - pos.getY();
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
        long now = level.getServer().overworld().getGameTime();
        int added = 0;
        for (ChunkPos pos : positions) {
            long key = pos.toLong();
            if (!overgrownSince.containsKey(key)) {
                overgrownSince.put(key, now);
                added++;
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
        overgrownSince.put(key, level.getServer().overworld().getGameTime());
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
        long since = present ? value : level.getServer().overworld().getGameTime();
        overgrownSince.put(key, -(since + 1L));
        setDirty();
        return true;
    }

    /** そのチャンクが「過繁茂以上」(過繁茂 or 極相)として台帳に載っているか。 */
    public boolean isOvergrownChunk(ChunkPos pos) {
        return overgrownSince.containsKey(pos.toLong());
    }

    /** そのチャンクが既に極相か。 */
    public boolean isClimaxChunk(ChunkPos pos) {
        return overgrownSince.get(pos.toLong()) < 0L && overgrownSince.containsKey(pos.toLong());
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
     *     <li>半径 {@code radiusChunks} のチャンク((2r+1)² 個。既定 8 なら 17×17 = 289)が
     *     すべて過繁茂以上であること</li>
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
        return neighbourhoodFilled(center, radiusChunks);
    }

    /** {@code center} の半径 {@code radiusChunks} が全て過繁茂以上で埋まっているか。 */
    private boolean neighbourhoodFilled(ChunkPos center, int radiusChunks) {
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (!overgrownSince.containsKey(ChunkPos.asLong(center.x + dx, center.z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code center} の半径 {@code radiusChunks} のうち、まだ過繁茂以上になっていないチャンク数
     * ({@code /ov climax status} の診断用)。0 なら周囲の条件は満たしている。
     */
    public int countMissingNeighbours(ChunkPos center, int radiusChunks) {
        int missing = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (!overgrownSince.containsKey(ChunkPos.asLong(center.x + dx, center.z + dz))) {
                    missing++;
                }
            }
        }
        return missing;
    }

    /**
     * いま極相へ上げられるチャンクを集める({@code ClimaxTicker} が呼ぶ)。
     *
     * <p>台帳を丸ごと走査するが、<b>ほとんどの項目は「既に極相」か「齢が足りない」で
     * ハッシュ参照 1 回ずつ</b>で落ちる。重い (2r+1)² の走査に入った回数を {@code maxChecks} で、
     * 返す件数を {@code maxResults} で頭打ちにしてあるので、
     * 広い汚染域が一斉に条件を満たしても 1 回の呼び出しが跳ねない(残りは次回に回る)。
     */
    public java.util.List<ChunkPos> collectClimaxPromotions(int radiusChunks, long minAgeTicks, long nowGameTime,
                                                            int maxChecks, int maxResults) {
        java.util.List<ChunkPos> found = new java.util.ArrayList<>();
        int checks = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : overgrownSince.long2LongEntrySet()) {
            long value = entry.getLongValue();
            if (value < 0L || nowGameTime - value < minAgeTicks) {
                continue; // 既に極相 / まだ若い。
            }
            if (checks >= maxChecks || found.size() >= maxResults) {
                break;
            }
            checks++;
            ChunkPos pos = new ChunkPos(entry.getLongKey());
            if (neighbourhoodFilled(pos, radiusChunks)) {
                found.add(pos);
            }
        }
        return found;
    }

    /**
     * 台帳に載っているチャンク数(統計・デバッグ用)。{@link #getOvergrownChunks} の累計とは別物 ——
     * 累計は旧セーブぶんも含むが、台帳は導入後に記録したものだけを持つ。
     */
    public int overgrownLedgerSize() {
        return overgrownSince.size();
    }

    /** 台帳のうち極相になっているチャンク数(統計・デバッグ用)。 */
    public int climaxLedgerSize() {
        int n = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : overgrownSince.long2LongEntrySet()) {
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
        if (phaseWarned || phase < 1) {
            return;
        }
        long need = DesignData.phases().pointsToAdvance(phase + 1);
        if (need == Long.MAX_VALUE) {
            return;
        }
        double fraction = DesignData.defense().alarmPhaseWarnFraction();
        if (fraction <= 0.0D || points < (long) (need * fraction)) {
            return;
        }
        phaseWarned = true;
        setDirty();
        com.yayuy.overgrownvegetation.defense.SporeAlarms.warn(overworld,
                Component.translatable("message.overgrown_vegetation.alarm.phase_near",
                        Component.translatableWithFallback(
                                "message.overgrown_vegetation.phase_name." + (phase + 1), "Phase " + (phase + 1)),
                        (int) Math.round(fraction * 100.0D)), null);
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
        PhaseTable phases = DesignData.phases();
        if (overworld.getGameTime() < phases.startWorldAgeTicks()) {
            return;
        }
        if (startAdvancementCount < phases.startAdvancements()) {
            return;
        }
        advanceToPhase(overworld, 1);
    }

    /**
     * ポイントを加算し、必要ならフェーズを進める。フェーズが進むとポイントは 0 にリセットされるため、
     * 各フェーズの {@code pointsToAdvance} は「直前のフェーズ開始時点からの必要ポイント」を表す。
     */
    public void addPoints(ServerLevel overworld, long amount) {
        if (frozen || phase < 1 || phase >= PhaseTable.MAX_PHASE || amount <= 0L) {
            return;
        }
        points += amount;
        setDirty();
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

    private void advanceToPhase(ServerLevel overworld, int newPhase) {
        this.phase = newPhase;
        // フェーズが進むたびにポイントを 0 にリセットする(次のフェーズの条件はここからの増分)。
        this.points = 0L;
        setDirty();
        this.phaseWarned = false;
        // 切り替わりに流すのはフェーズ名の 1 行だけ(「汚染がさらに広がった……」のような演出文は載せない)。
        overworld.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatableWithFallback(
                        "message.overgrown_vegetation.phase_name." + newPhase, "Phase " + newPhase), false);
        // 胞子警報器(2026-09-19): 近くのプレイヤーへ。
        com.yayuy.overgrownvegetation.defense.SporeAlarms.warn(overworld,
                Component.translatable("message.overgrown_vegetation.alarm.phase_changed",
                        Component.translatableWithFallback(
                                "message.overgrown_vegetation.phase_name." + newPhase, "Phase " + newPhase)), null);

        if (newPhase == 1) {
            // フェーズ1突入時の演出: 全プレイヤーから離れた地表で地震が起き、大地が裂けて胞子が噴き出す。
            com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
            // 実績ゲートの記録はもう使わないので捨てる(個数だけ startAdvancementCount に残す)。
            startAdvancementIds.clear();
        }
        // フェーズ2の「グループ1の体力・攻撃力強化」をロード済みの該当 Mob へ即座に反映/解除する。
        Group1PhaseBehavior.onPhaseChanged(overworld.getServer());
        // フェーズ5以降は、昇格した瞬間にレイドを1回確定で発生させる(失敗しても RaidTicker が再挑戦する)。
        com.yayuy.overgrownvegetation.raid.RaidData.onPhaseAdvanced(overworld, newPhase);
        // フェーズ7以降は、昇格した瞬間に攻城戦(遠距離ボス)を始める(同じく失敗しても再挑戦する)。
        com.yayuy.overgrownvegetation.siege.SiegeData.onPhaseAdvanced(overworld, newPhase);
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
        setDirty();
        if (enteringPhase1) {
            com.yayuy.overgrownvegetation.quake.EarthquakeEvent.onEnterPhase1(overworld);
        }
        Group1PhaseBehavior.onPhaseChanged(overworld.getServer());
    }
}
