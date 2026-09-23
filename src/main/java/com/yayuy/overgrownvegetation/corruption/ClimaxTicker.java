package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.organ.OrganWither;
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

    /** 1 回の判定で (2r+1)² の走査に入ってよい回数。広い汚染域が一斉に条件を満たしても跳ねないため。 */
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

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        // ワールドスポーン周りを必ず密林にするための位置。変わったときだけ地域マップのキャッシュを捨てる。
        OvergrowthRegionMap.rememberSpawn(level);
        ClimaxTable table = DesignData.climax();
        drainPending(level, table);
        // 極相になったチャンクを岩盤まで置き換える(何 tick にも分けて進む)。
        ClimaxDeepConverter.tick(level);
        // 節を潰された器官を少しずつ崩す(2026-09-23)。
        OrganWither.tick(level);
        if (level.getGameTime() % table.checkIntervalTicks() == 0L) {
            promote(level, table);
        }
    }

    /**
     * 読み込まれたチャンクを 1 つずつ見て、
     * <ul>
     *     <li>台帳では極相なのにバイオームがまだ過繁茂 → 書き換える(留守中に上がったぶん)</li>
     *     <li>バイオームは過繁茂以上なのに台帳に無い → 拾い直す(この台帳より前のセーブ)</li>
     * </ul>
     */
    private static void drainPending(ServerLevel level, ClimaxTable table) {
        if (PENDING.isEmpty()) {
            return;
        }
        PhaseData data = PhaseData.get(level);
        int done = 0;
        LongIterator it = PENDING.iterator();
        while (it.hasNext() && done < MAX_MATERIALIZE_PER_TICK) {
            long key = it.nextLong();
            it.remove();
            ChunkPos pos = new ChunkPos(key);
            if (!level.hasChunk(pos.x, pos.z)) {
                continue; // もう読み込まれていない。次に読み込まれたときにまた積まれる。
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

    /** 台帳を走査して、条件を満たしたチャンクへ極相の印を付ける。読み込まれていれば書き換えもする。上げた数を返す。 */
    private static int promote(ServerLevel level, ClimaxTable table) {
        PhaseData data = PhaseData.get(level);
        List<ChunkPos> promotions = data.collectClimaxPromotions(
                table.neighborRadiusChunks(), table.minAgeTicks(), level.getGameTime(),
                MAX_NEIGHBOUR_CHECKS, MAX_PROMOTIONS);
        for (ChunkPos pos : promotions) {
            data.markClimaxChunk(level, pos);
            if (level.hasChunk(pos.x, pos.z)) {
                OvergrownBiomeConverter.forceClimax(level, pos);
                ClimaxDeepConverter.enqueue(level, pos);
            }
            // 読み込まれていないチャンクは台帳の印だけ。次に読み込まれたら drainPending が書き換える。
        }
        return promotions.size();
    }

    private ClimaxTicker() {
    }
}
