package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基地のチャンクごとの手入れを、<b>読み込まれたチャンクだけ</b>で進める(2026-10-04、順 2 の着手順 2):
 * 胞子除去装置の後付け({@link Job#PURIFY})と、陥落の後片付け = 機関砲台と除去装置を空にする({@link Job#EMPTY})。
 *
 * <p>始めるときに、基地が手を入れる範囲のチャンクを全部「控え」({@link BaseSite#purifyPending} / {@link BaseSite#emptyPending}、保存する)に入れ、
 * 今読み込まれているものから 1 tick に {@link #CHUNKS_PER_TICK} つずつ片付ける。読み込まれていないチャンクは控えに残り、読み込まれたとき
 * ({@link ChunkEvent.Load})に片付く。前は無いチャンクを作ってでも回っていた —— 世界生成の基地が増えると遠くのチャンクを大量に作る
 * (ユーザー指定 10-02「除去装置の後付けは読み込んだチャンクから」。陥落の後片付けも同じ理由)。世界生成の基地は、台帳に載った後に生まれたチャンクにも
 * 読み込まれたときに手が入る。
 *
 * <p>後付けは周り 8 チャンクも読み込まれているときだけ置く(書き出しが隣のチャンクの 1 マスの地面を読むので、読み込まれていない隣を作らせない)。
 * 工事中の基地の後付けは工事の後(残りの工事は除去装置も置く)。今すぐ全部済ませる道は {@link BasePurifiers#installNow} と {@link BaseEconomy#fallNow}(読み込んで回る)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseChunkJobs {

    /** 手入れの種類。 */
    public enum Job {
        /** 胞子除去装置の後付け。 */
        PURIFY,
        /** 陥落の後片付け(設備を空にする)。 */
        EMPTY
    }

    /** 1 tick に片付けるチャンクの数(仮決め。前の工事と同じ 2 から、読み込まれた所だけになったので少し多め)。 */
    public static final int CHUNKS_PER_TICK = 4;

    /** 読み込まれたばかりのチャンク(次元ごと。イベントの中では世界にもデータにも触らず積むだけ)。 */
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> LOADED = new HashMap<>();
    /** 片付けてよい(読み込まれていて控えにある)チャンク: 基地の番号 → チャンク。 */
    private static final Map<Integer, LongLinkedOpenHashSet> READY = new LinkedHashMap<>();
    /** サーバーを開いてから、控えのうち読み込まれているものを一度数えたか。 */
    private static boolean scanned;

    /** 基地 {@code site} の手入れ {@code job} を始める(範囲のチャンクを全部控えに入れ、読み込まれているものを片付ける列へ)。 */
    public static void start(@Nullable ServerLevel level, BaseSites data, BaseSite site, Job job) {
        LongOpenHashSet pending = pending(site, job);
        pending.clear();
        for (ChunkPos c : BaseConstruction.chunks(site, site.level())) {
            pending.add(c.toLong());
        }
        if (job == Job.PURIFY) {
            site.restartPurify = false;
        } else {
            site.restartEmpty = false;
        }
        data.changed();
        if (level != null) {
            enqueueLoaded(level, site);
        }
    }

    /** 手入れ {@code job} をやめる(控えを空に)。 */
    public static void cancel(BaseSites data, BaseSite site, Job job) {
        LongOpenHashSet pending = pending(site, job);
        if (!pending.isEmpty() || (job == Job.PURIFY ? site.restartPurify : site.restartEmpty)) {
            pending.clear();
            if (job == Job.PURIFY) {
                site.restartPurify = false;
            } else {
                site.restartEmpty = false;
            }
            data.changed();
        }
    }

    static LongOpenHashSet pending(BaseSite site, Job job) {
        return job == Job.PURIFY ? site.purifyPending : site.emptyPending;
    }

    private static boolean idle(BaseSite site) {
        return site.purifyPending.isEmpty() && site.emptyPending.isEmpty();
    }

    /** 控えのうち今読み込まれているチャンクを、片付ける列に入れる。 */
    private static void enqueueLoaded(ServerLevel level, BaseSite site) {
        for (LongOpenHashSet set : new LongOpenHashSet[]{site.emptyPending, site.purifyPending}) {
            LongIterator it = set.iterator();
            while (it.hasNext()) {
                long c = it.nextLong();
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null) {
                    READY.computeIfAbsent(site.id, k -> new LongLinkedOpenHashSet()).add(c);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LOADED.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
        READY.clear();
        scanned = false;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        BaseSites data = BaseSites.get(server);
        if (data.all().isEmpty()) {
            LOADED.clear();
            READY.clear();
            return;
        }
        for (BaseSite site : data.all()) {
            if (site.restartPurify || site.restartEmpty) {
                ServerLevel level = server.getLevel(site.dimension);
                if (site.restartEmpty && site.fallen) {
                    start(level, data, site, Job.EMPTY);
                }
                if (site.restartPurify && !site.fallen && !site.outdated()) {
                    start(level, data, site, Job.PURIFY);
                }
                site.restartPurify = false;
                site.restartEmpty = false;
                data.changed();
            }
        }
        if (!scanned) {
            scanned = true;
            for (BaseSite site : data.all()) {
                ServerLevel level = server.getLevel(site.dimension);
                if (level != null && !idle(site)) {
                    enqueueLoaded(level, site);
                }
            }
        }
        drainLoaded(data);
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (READY.isEmpty() || !com.yayuy.overgrownvegetation.perf.TickBudget.mayStart(
                com.yayuy.overgrownvegetation.perf.PerfJob.BASE_CHUNK_JOBS)) {
            return;
        }
        long start = com.yayuy.overgrownvegetation.perf.TickBudget.begin();
        try {
            process(server, data);
        } finally {
            com.yayuy.overgrownvegetation.perf.TickBudget.end(com.yayuy.overgrownvegetation.perf.PerfJob.BASE_CHUNK_JOBS,
                    start, 1);
        }
    }

    /**
     * 読み込まれたばかりのチャンクと、その周り 8 チャンクのうち、どれかの基地の控えにあるものを片付ける列へ
     * (後付けは周りが全部読み込まれるのを待つので、隣が読み込まれたときにも見直す)。
     */
    private static void drainLoaded(BaseSites data) {
        for (Map.Entry<ResourceKey<Level>, LongLinkedOpenHashSet> e : LOADED.entrySet()) {
            LongLinkedOpenHashSet chunks = e.getValue();
            if (chunks.isEmpty()) {
                continue;
            }
            for (BaseSite site : data.all()) {
                if (idle(site) || !site.dimension.equals(e.getKey())) {
                    continue;
                }
                LongIterator it = chunks.iterator();
                while (it.hasNext()) {
                    long c = it.nextLong();
                    int x = ChunkPos.getX(c);
                    int z = ChunkPos.getZ(c);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = ChunkPos.asLong(x + dx, z + dz);
                            if (site.emptyPending.contains(n) || site.purifyPending.contains(n)) {
                                READY.computeIfAbsent(site.id, k -> new LongLinkedOpenHashSet()).add(n);
                            }
                        }
                    }
                }
            }
            chunks.clear();
        }
    }

    /** 片付ける列から、1 tick に {@link #CHUNKS_PER_TICK} つまで片付ける。読み込みが外れたチャンクは控えに残す(また読み込まれたときに)。 */
    private static void process(MinecraftServer server, BaseSites data) {
        int budget = CHUNKS_PER_TICK;
        Iterator<Map.Entry<Integer, LongLinkedOpenHashSet>> sites = READY.entrySet().iterator();
        while (budget > 0 && sites.hasNext()) {
            Map.Entry<Integer, LongLinkedOpenHashSet> e = sites.next();
            BaseSite site = data.byId(e.getKey());
            ServerLevel level = site != null ? server.getLevel(site.dimension) : null;
            if (site == null || level == null || idle(site)) {
                sites.remove();
                continue;
            }
            LongLinkedOpenHashSet ready = e.getValue();
            LongIterator it = ready.iterator();
            while (budget > 0 && it.hasNext()) {
                long c = it.nextLong();
                int x = ChunkPos.getX(c);
                int z = ChunkPos.getZ(c);
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    it.remove(); // 読み込みが外れた(控えには残る)
                    continue;
                }
                if (site.emptyPending.remove(c)) { // 陥落の後片付けが先(陥落した基地には後付けしない)
                    site.purifyPending.remove(c);
                    BaseEconomy.emptyDevices(level, site, chunk);
                    it.remove();
                    budget--;
                    data.changed();
                    continue;
                }
                if (!site.purifyPending.contains(c)) {
                    it.remove();
                    continue;
                }
                if (site.building() || site.fallen) {
                    break; // 工事の後に回す(陥落した基地の控えは陥落のときに空にしてある)
                }
                if (!neighboursLoaded(level, x, z)) {
                    it.remove(); // 控えには残る。隣が読み込まれたときに列へ戻る(drainLoaded)
                    continue;
                }
                site.purifyPending.remove(c);
                BasePlacer.placePurifiers(level, site, site.level(), new ChunkPos(x, z));
                it.remove();
                budget--;
                data.changed();
                if (site.purifyPending.isEmpty()) {
                    BasePurifiers.finished(level, site);
                }
            }
            if (ready.isEmpty()) {
                sites.remove();
            }
        }
    }

    /** 周り 8 チャンクも読み込まれているか。 */
    private static boolean neighboursLoaded(ServerLevel level, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && level.getChunkSource().getChunkNow(x + dx, z + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** チャンク {@code pos} が手入れ {@code job} の控えに残っているか(GameTest)。 */
    public static boolean isPending(BaseSite site, Job job, ChunkPos pos) {
        return pending(site, job).contains(pos.toLong());
    }

    /** 開発用: 片付ける列に入っているチャンクの数(コマンドの表示と GameTest)。 */
    public static int readyCount(BaseSite site) {
        LongLinkedOpenHashSet ready = READY.get(site.id);
        return ready != null ? ready.size() : 0;
    }

    /** GameTest: 読み込まれたばかりのチャンクとして積む(イベントを待たずに)。 */
    public static void markLoadedForTests(ServerLevel level, ChunkPos pos) {
        LOADED.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(pos.toLong());
    }

    private BaseChunkJobs() {
    }
}
