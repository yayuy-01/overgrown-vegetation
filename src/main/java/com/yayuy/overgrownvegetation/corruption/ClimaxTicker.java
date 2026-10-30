package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * 過繁茂 → <b>極相</b>({@code engulfed})への昇格を進める常駐処理(2026-09-22)。
 *
 * <h2>台帳と実体を分ける</h2>
 * <p>極相化は 3 段に分かれている。
 * <ol>
 *     <li><b>台帳の印</b>({@link PhaseData#markClimaxChunk}) —— {@link ClimaxTable#checkIntervalTicks()}
 *     ごとに台帳を走査し、条件を満たしたチャンクへ印を付ける。<b>世界には一切触らない</b>ので、
 *     プレイヤーが遠くに住んでいても、そのチャンクが読み込まれていなくても進む。</li>
 *     <li><b>バイオームの書き換え</b>({@link OvergrownBiomeConverter#forceClimax}) ——
 *     そのチャンクが読み込まれたときに行う。久しぶりに訪れたら極相になっていた、という形になる。</li>
 *     <li><b>全層置換</b>({@link ClimaxDeepConverter}) —— 岩盤まで過繫茂ブロックへ置き換える。
 *     1 チャンク約 98000 マスあるので、読み込まれている間に何 tick にも分けて進める。
 *     やり終えたチャンクは台帳に記録して二度と走らせない。</li>
 * </ol>
 * この分け方をやめて「昇格と同時に書き換える」にすると、{@link ServerLevel#getBiome} が
 * チャンクを読み込んでしまい、遠くの汚染域を次々とロードしてサーバーが潰れる。
 *
 * <h2>オーバーワールドだけ</h2>
 * <p>台帳のキーは {@link ChunkPos#toLong} で<b>次元を持たない</b>(過繁茂チャンクの累計も昔から同じ)。
 * 別の次元のチャンクを同じ台帳に混ぜると座標が衝突して「隣が埋まっている」と誤判定しうるので、
 * 極相の判定と書き換えは<b>オーバーワールドに限る</b>。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClimaxTicker {

    /** 1 回の判定で周囲の円の走査(半径 12 で 441 回の参照)に入ってよい回数。広い汚染域が一斉に条件を満たしても跳ねないため。 */
    private static final int MAX_NEIGHBOUR_CHECKS = 256;

    /** 1 回の判定で極相へ上げてよいチャンク数。 */
    private static final int MAX_PROMOTIONS = 16;

    /** 1 tick で書き換えてよいチャンク数。バイオームの再送はチャンク単位で重いので絞る。 */
    private static final int MAX_MATERIALIZE_PER_TICK = 4;

    /**
     * 読み込まれたばかりで、まだ極相かどうかを見ていないチャンク。
     * {@link ChunkEvent.Load} の中で世界を書き換えるのは避けたい(チャンクの読み込み途中)ので、
     * ここへ積んで次の tick で処理する。
     *
     * <p>読み込まれなくなったチャンクの取りこぼしは、{@link #drainPending} が
     * 「読み込まれていなければ捨てる」で掃除する。
     */
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        PENDING.add(event.getChunk().getPos().toLong());
    }

    /**
     * ワールドを閉じたら、まだ見ていないチャンクの一覧を空にする(キーはチャンクの座標だけなので、
     * 残すと次に開いた別のワールドで同じ座標のチャンクを見に行く)。起動し直したときと同じ状態に戻す。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        // ワールドスポーン周りを必ず密林にするための位置。変わったときだけ地域マップのキャッシュを捨てる。
        OvergrowthRegionMap.rememberSpawn(level);
        ClimaxTable table = DesignData.climax();
        if (CorruptionSwitch.isOff()) {
            // /ov corruption false(2026-10-10 ユーザー指定): 極相の昇格・書き換え・深い変換を止める(順番待ちは残り、オンで続きから)
            if (level.getGameTime() % 200L == 0L) {
                // 読み込まれたチャンクの一覧はオフの間も増えるので、もう読み込まれていないものを落とす(オンでも drainPending が同じく飛ばすもの)
                PENDING.removeIf((long key) -> !com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, ChunkPos.getX(key), ChunkPos.getZ(key)));
            }
            return;
        }
        drainPending(level, table);
        // 極相になったチャンクを岩盤まで置き換える(何 tick にも分けて進む)。
        ClimaxDeepConverter.tick(level);
        // 位相をずらして、ほかの地形の周期処理と同じ tick に重ならないようにする(2026-09-24 軽量化、報告 589 / 1516。
        // 間隔は同じで、走る tick が周期の中でずれるだけ)。
        if (com.yayuy.overgrownvegetation.util.TickPhases.isDue(level.getGameTime(),
                com.yayuy.overgrownvegetation.util.TickPhases.CLIMAX_PROMOTE, table.checkIntervalTicks())) {
            promoteInBackground(level, table);
        }
    }

    /** 極相の判定(台帳全体の走査)を別スレッドで回す仕事(2026-10-06、④)。 */
    private static final com.yayuy.overgrownvegetation.perf.BackgroundTask<PhaseData.ClimaxScan> PROMOTE_TASK =
            new com.yayuy.overgrownvegetation.perf.BackgroundTask<>("極相の判定");

    /**
     * {@link #promote} の常駐版(2026-10-06、④)。台帳の写しを別スレッドで走査し、答えを次の tick の頭に当てはめる。
     * 台帳は過繁茂が広がるほど大きくなり、走査はその大きさに比例するので、サーバースレッドから外す。
     * 写しは中の配列ごと写すので走査の順番も同じ —— 写してから当てはめるまでの 1 tick の間に台帳が変わらなければ答えは
     * {@link #promote} と同じ。変わっていたら、当てはめるときに今の台帳で確かめ直す({@link PhaseData#canStillPromote})。
     */
    private static void promoteInBackground(ServerLevel level, ClimaxTable table) {
        PhaseData data = PhaseData.get(level);
        if (data.getPhase() < table.minPhase() || PROMOTE_TASK.running()) {
            return;
        }
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap snapshot = data.snapshotLedger();
        int cursor = data.climaxScanCursor();
        int radius = table.neighborRadiusChunks();
        long minAge = effectiveMinAgeTicks(level, table);
        long now = data.clock(level.getServer());
        PROMOTE_TASK.start(level.getServer(),
                () -> PhaseData.scanClimaxPromotions(snapshot, cursor, radius, minAge, now, MAX_NEIGHBOUR_CHECKS, MAX_PROMOTIONS),
                scan -> {
                    PhaseData current = PhaseData.get(level);
                    current.setClimaxScanCursor(scan.nextCursor());
                    List<ChunkPos> valid = new java.util.ArrayList<>(scan.found().size());
                    for (ChunkPos pos : scan.found()) {
                        if (current.canStillPromote(pos, radius)) {
                            valid.add(pos);
                        }
                    }
                    markPromotions(level, current, valid);
                });
    }

    /**
     * 読み込まれたチャンクを 1 つずつ見て、
     * <ul>
     *     <li>台帳では極相なのにバイオームがまだ過繁茂 → 書き換える(留守中に上がったぶん)</li>
     *     <li>バイオームは過繁茂以上なのに台帳に無い → 拾い直す(この台帳より前のセーブ)</li>
     * </ul>
     */
    private static void drainPending(ServerLevel level, ClimaxTable table) {
        if (PENDING.isEmpty() || !TickBudget.mayStart(PerfJob.CLIMAX_MATERIALIZE)) {
            return;
        }
        long start = TickBudget.begin();
        try {
            drainPendingInBudget(level);
        } finally {
            TickBudget.end(PerfJob.CLIMAX_MATERIALIZE, start, 1);
        }
    }

    private static void drainPendingInBudget(ServerLevel level) {
        PhaseData data = PhaseData.get(level);
        int done = 0;
        LongIterator it = PENDING.iterator();
        while (it.hasNext() && done < MAX_MATERIALIZE_PER_TICK && (done == 0 || TickBudget.timeLeft())) {
            long key = it.nextLong();
            it.remove();
            ChunkPos pos = new ChunkPos(key);
            if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, pos.x, pos.z)) {
                continue; // もう読み込まれていない(読み込みの途中も含む)。次に読み込まれたときにまた積まれる。
            }
            done++;
            if (data.isClimaxChunk(pos)) {
                OvergrownBiomeConverter.forceClimax(level, pos);
                ClimaxDeepConverter.enqueue(level, pos);
                continue;
            }
            Holder<Biome> biome = level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight()));
            if (ModBiomes.isFullyOvergrown(biome) && !data.isOvergrownChunk(pos)) {
                // この台帳より前のセーブ。過繁茂なのに台帳に無いので、見かけた時刻で拾い直す
                // (面積とポイントは増やさない。14 日の計時はここから)。
                data.adoptOvergrownChunk(level, pos);
            }
        }
    }

    /** {@code /ov climax scan} から、間隔を待たずに 1 回だけ判定を回す。上げたチャンク数を返す。 */
    public static int promoteNow(ServerLevel level) {
        return promote(level, DesignData.climax());
    }

    /**
     * 台帳を走査して、条件を満たしたチャンクへ極相の印を付ける。読み込まれていれば書き換えもする。上げた数を返す。
     *
     * <p>2026-09-26 のユーザー指定: 極相化は {@link ClimaxTable#minPhase}(既定 5)以降だけ。齢は
     * 「過繁茂になってからの日数 − F1 開始から F5 までにかかった日数」で数える ——
     * F5 になった瞬間に古いチャンクが広く一斉に極相化しないよう、過繁茂になった順に、
     * いちばん古いチャンクが F5 の 14 日後から始まる。時刻は汚染の暦({@link PhaseData#clock}、睡眠の夜も数える)。
     */
    private static int promote(ServerLevel level, ClimaxTable table) {
        PhaseData data = PhaseData.get(level);
        if (data.getPhase() < table.minPhase()) {
            return 0;
        }
        List<ChunkPos> promotions = data.collectClimaxPromotions(
                table.neighborRadiusChunks(), effectiveMinAgeTicks(level, table), data.clock(level.getServer()),
                MAX_NEIGHBOUR_CHECKS, MAX_PROMOTIONS);
        markPromotions(level, data, promotions);
        return promotions.size();
    }

    /** 選んだチャンクに極相の印を付け、読み込まれていれば書き換える({@link #promote} と別スレッドの版の共通)。 */
    private static void markPromotions(ServerLevel level, PhaseData data, List<ChunkPos> promotions) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10): 別スレッドの判定が切り替えの前に始まっていても印を付けない
        }
        for (ChunkPos pos : promotions) {
            data.markClimaxChunk(level, pos);
            if (com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, pos.x, pos.z)) {
                OvergrownBiomeConverter.forceClimax(level, pos);
                ClimaxDeepConverter.enqueue(level, pos);
            }
            // 読み込まれていないチャンクは台帳の印だけ。次に読み込まれたら drainPending が書き換える。
        }
    }

    /**
     * 実際に使う齢のしきい値(tick)= {@link ClimaxTable#minAgeTicks} + 「F1 開始から {@link ClimaxTable#minPhase} に
     * 着くまでにかかった暦」。{@code /ov climax status} も同じ値で出す。
     */
    public static long effectiveMinAgeTicks(ServerLevel level, ClimaxTable table) {
        return table.minAgeTicks() + PhaseData.get(level).climaxAgeOffsetTicks(level.getServer(), table.minPhase());
    }

    private ClimaxTicker() {
    }
}
