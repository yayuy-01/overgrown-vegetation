package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 胞子の降り積もりで繁茂の札を付ける定期処理(2026-10-23 胞子の場 第 2 段 G で SporeBiomeSweep から作り直し。
 * 「濃度 100% で繁茂の札」をやめ、場の降り積もり・発芽からの日数で決める。過繁茂は台帳)。
 * 地表の汚染割合での過繁茂化の安全網(maybeConvert)は今までどおり。以下は前の説明:
 *
 * <p>胞子濃度が 100% に達したチャンクのバイオームを「繁茂バイオーム」({@link ModBiomes#OVERGROWTH})へ
 * 書き換える定期処理。
 *
 * <p>「繁茂バイオームは胞子濃度が 100% であるが過繁茂バイオームではない状態の際に置き換えられる
 * バイオーム」という仕様をそのまま実装したもの。地表の汚染割合が
 * {@code overgrownBiomeThreshold} に達したチャンクを {@link ModBiomes#OVERGROWN} へ書き換える
 * {@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter#maybeConvert} と対になる
 * (そちらは「実際の汚染」で、こちらは「胞子濃度」で切り替わる)。
 *
 * <h2>矩形状に広がらないための注意</h2>
 * <p>チャンク単位でバイオームを一括で書き換えるが、これは<b>バイオームラベルだけ</b>の話で、
 * ブロックの汚染はしない。ブロックの汚染は従来どおり
 * {@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep} の有機的なフロンティア浸食
 * (既存の汚染の縁から 1 マスずつ)に任せるので、地表が矩形に塗り潰されることはない。
 * また繁茂バイオーム自体は {@link SporeConcentration} の近隣過繁茂率には数えない
 * ({@link ModBiomes#isOvergrownLike} の JavaDoc 参照)ので、繁茂 → 繁茂 と胞子濃度が
 * 際限なく外へ伝播することもない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class DepositBiomeSweep {

    /** ディメンションごとの「チャンク → 次に判定してよくなるゲーム内時刻」。処理済みチャンクの再判定クールダウン。 */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> NEXT_SCAN = new HashMap<>();

    /** クールダウン表が肥大化しすぎたら丸ごと捨てる閾値。 */
    private static final int SCAN_MAP_MAX_ENTRIES = 20_000;

    /**
     * ワールドを閉じたらクールダウン表を空にする(キーはディメンションだけなので、次に別のワールドを開くと
     * 前のワールドの時刻が残り、同じ座標のチャンクが待たされる。起動し直したときと同じ状態に戻す)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        NEXT_SCAN.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (com.yayuy.overgrownvegetation.corruption.CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.convertToOvergrownBiome.get() || !cfg.convertToOvergrowthBiome.get()) {
            return;
        }
        int interval = Math.max(1, cfg.overgrowthBiomeScanIntervalTicks.get());
        // 位相をずらして、ほかの地形の周期処理と同じ tick に重ならないようにする(2026-09-24 軽量化、報告 589 / 1516。
        // 間隔は同じで、走る tick が周期の中でずれるだけ)。
        if (!com.yayuy.overgrownvegetation.util.TickPhases.isDue(level.getGameTime(),
                com.yayuy.overgrownvegetation.util.TickPhases.SPORE_BIOME_SWEEP, interval)) {
            return;
        }
        // サーバーが重いときは予算の番人が次の周回へ回す(2026-10-06。結果は同じで遅れるだけ)。
        if (!TickBudget.mayStart(PerfJob.SPORE_DEPOSIT_SWEEP)) {
            return;
        }
        long start = TickBudget.begin();
        try {
            sweep(level, cfg);
        } finally {
            TickBudget.end(PerfJob.SPORE_DEPOSIT_SWEEP, start, 1);
        }
    }

    private static void sweep(ServerLevel level, OvergrownVegetationConfig.Common cfg) {
        int radiusChunks = Math.max(1, cfg.overgrowthBiomeScanRadiusChunks.get());
        int budget = Math.max(1, cfg.overgrowthBiomeMaxConvertsPerScan.get());
        long now = level.getGameTime();

        Long2LongOpenHashMap nextScan = NEXT_SCAN.computeIfAbsent(level.dimension(), key -> newScanMap());

        // クールダウンの切れた「まだ繁茂系でない」対象チャンクを全プレイヤーぶん洗い出す(重複除去)。
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList eligible = new LongArrayList();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            ChunkPos center = player.chunkPosition();
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!seen.add(key)) {
                        continue;
                    }
                    if (now < nextScan.get(key)) {
                        continue;
                    }
                    if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, cx, cz)) { // 読み込み途中のチャンクを読んで止まらないよう、hasChunk ではなく読み込み済みかで見る(2026-10-10、0.1.9 の前)
                        continue;
                    }
                    probe.set((cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
                    if (ModBiomes.isFullyOvergrown(level.getBiome(probe))) {
                        continue; // 既に過繁茂。繁茂チャンクは過繁茂への昇格判定のため残す。
                    }
                    eligible.add(key);
                }
            }
        }
        if (eligible.isEmpty()) {
            return;
        }

        // 偏りなく処理するためシャッフルしてから予算ぶんだけ。
        shuffle(eligible, level.random);
        int limit = Math.min(budget, eligible.size());
        for (int i = 0; i < limit && (i == 0 || TickBudget.timeLeft()); i++) {
            long key = eligible.getLong(i);
            ChunkPos chunkPos = new ChunkPos(key);
            // 胞子の場(2026-10-23 第 2 段): 発芽して 2 日を過ぎた台帳のチャンクは過繁茂へ(それまでは繁茂の札)
            if (SporeConcentration.useField(level)) {
                com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.Cell cell =
                        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.cell(chunkPos.x, chunkPos.z);
                if (cell != null && cell.stage() >= 2
                        && com.yayuy.overgrownvegetation.phase.PhaseData.get(level).isOvergrownChunk(chunkPos)) {
                    OvergrownBiomeConverter.forceOvergrown(level, chunkPos);
                }
            }
            OvergrownBiomeConverter.maybeConvertToOvergrowth(level, chunkPos);
            // 地表の汚染割合が閾値に達していれば(繁茂を経ずとも)過繁茂へ。汚染イベントの通知に頼らず、
            // 定期的に拾い直す安全網(2026-09-10: 泥に変わった湿地チャンクが昇格しない報告への対処)。
            OvergrownBiomeConverter.maybeConvert(level, chunkPos);
            // 変換されたかどうかに関係なく、少しの間このチャンクは再判定しない。
            boolean converted = ModBiomes.isOvergrownLike(
                    level.getBiome(chunkPos.getMiddleBlockPosition(level.getSeaLevel())));
            nextScan.put(key, now + (converted ? cfg.overgrowthBiomeRescanTicks.get()
                    : cfg.overgrowthBiomeMissRescanTicks.get()));
        }

        if (nextScan.size() > SCAN_MAP_MAX_ENTRIES) {
            nextScan.clear();
        }
    }

    private static Long2LongOpenHashMap newScanMap() {
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(Long.MIN_VALUE);
        return map;
    }

    private static void shuffle(LongArrayList list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = list.getLong(i);
            list.set(i, list.getLong(j));
            list.set(j, tmp);
        }
    }

    private DepositBiomeSweep() {
    }
}
