package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 苗木から育つ巨大樹の計画を別スレッドで作る(2026-11-18 大群の軽量化 B の案 A。ユーザー決定「巨大樹 A 案を入れよう」。
 * 育ち始めが最大 1 秒ほど遅れるのは了承済み)。
 *
 * <p>前は苗木のランダムティックの中で計画(木の全マスの座標。数十万マス)をサーバースレッドで作り、その tick が 0.5〜1 秒止まっていた。
 * 今は {@link GiantTreeFeature#place} がサーバースレッドで周りの地形を写し({@link TreeTerrainSnapshot})、計画は専用のスレッドで作る。
 * 出来た計画は次の tick 以降にサーバースレッドで受け取り、{@link GiantTreeFeature#commit} で設置のキュー({@link GiantTreeGrowthQueue})へ積む。
 *
 * <ul>
 *     <li><b>苗木は計画が出来るまで残す</b>: バニラの手順は苗木を消してから place を呼ぶので、place の中で苗木を戻す。
 *     計画を積むときに苗木を消す。計画の途中で世界を閉じた・チャンクが外れた・計画が空だったときは、苗木が残っているので後でまた育つ
 *     (位置も記録しない)。</li>
 *     <li><b>同じ苗木から 2 本作らない</b>: 計画中の位置に来た成長は何もしない({@link #inFlightAt})。巨大樹どうしの間隔の判定も計画中の木を数える
 *     ({@link #inFlightWithin})。</li>
 *     <li>乱数は呼んだ側(世界の乱数。別スレッドから触ると落ちる)から種を 1 つ引き、計画のスレッドで新しく作る。</li>
 *     <li>計画のスレッドは世界に触らない(写しだけを読む)。受け取りはサーバースレッドの tick で見るだけで、サーバースレッドは計画を待たない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GiantTreePlanner {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation giant tree planner");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** 計画中の木(サーバースレッドだけが触る)。 */
    private static final List<InFlight> IN_FLIGHT = new ArrayList<>();

    private record InFlight(ServerLevel level, BlockPos origin, @Nullable BlockState sapling, long startedNanos,
                            long captureNanos, CompletableFuture<GiantTreeFeature.Plan> future) {
    }

    /** 確かめ・ログ用: 最後に積んだ木の、写すのにかかった ns・計画にかかった ns・始めてから積むまでの ns。 */
    static volatile long lastCaptureNanos;
    static volatile long lastPlanNanos;
    static volatile long lastWaitNanos;

    private GiantTreePlanner() {
    }

    /**
     * サーバースレッドで呼ぶ({@link GiantTreeFeature#place} から)。地形を写して計画を別スレッドへ出し、苗木を戻す。
     * 常に true を返す(バニラの手順に「育った」と答え、苗木を元に戻させない。苗木は自分で戻した)。
     */
    static boolean start(ServerLevel level, GiantTreeFeature.Config config, BlockPos origin, long seed, @Nullable BlockState sapling) {
        BlockPos at = origin.immutable();
        if (inFlightAt(level, at)) {
            restoreSapling(level, at, sapling);
            return true;
        }
        long t0 = System.nanoTime();
        TreeTerrainSnapshot snapshot = TreeTerrainSnapshot.capture(level, at, TreeTerrainSnapshot.RADIUS_CHUNKS);
        CompletableFuture<GiantTreeFeature.Plan> future = CompletableFuture.supplyAsync(() -> {
            GiantTreeFeature.Plan plan = GiantTreeFeature.buildPlan(snapshot, RandomSource.create(seed), config, at);
            int misses = snapshot.misses.get();
            if (misses > 0) {
                OvergrownVegetation.LOGGER.warn("GiantTree at {}: 写した地形の外を {} 回読みました(写しの半径 {} チャンク・写したチャンク {})",
                        at, misses, TreeTerrainSnapshot.RADIUS_CHUNKS, snapshot.chunkCount);
            }
            return plan;
        }, WORKER);
        IN_FLIGHT.add(new InFlight(level, at, sapling, t0, snapshot.captureNanos, future));
        restoreSapling(level, at, sapling);
        OvergrownVegetation.LOGGER.info("GiantTree at {}: 地形を写して計画を別スレッドへ(チャンク {}・区画 {}・{} ms)",
                at, snapshot.chunkCount, snapshot.sectionCount, String.format(java.util.Locale.ROOT, "%.1f", snapshot.captureNanos / 1e6));
        return true;
    }

    /** バニラの手順は苗木を流体(ふつうは空気)に変えてから place を呼ぶ。計画が出来るまで苗木を戻しておく(クライアントには知らせない = 見た目は苗木のまま)。 */
    private static void restoreSapling(ServerLevel level, BlockPos origin, @Nullable BlockState sapling) {
        if (sapling == null) return;
        BlockState now = level.getBlockState(origin);
        if (now == level.getFluidState(origin).createLegacyBlock()) {
            level.setBlock(origin, sapling, Block.UPDATE_INVISIBLE);
        }
    }

    /** 計画中の木がその位置にあるか。 */
    public static boolean inFlightAt(ServerLevel level, BlockPos origin) {
        for (InFlight f : IN_FLIGHT) {
            if (f.level == level && f.origin.equals(origin)) return true;
        }
        return false;
    }

    /** 計画中の木が水平距離 {@code distance} 以内にあるか(巨大樹どうしの間隔の判定)。 */
    static boolean inFlightWithin(ServerLevel level, int x, int z, int distance) {
        long d2 = (long) distance * distance;
        for (InFlight f : IN_FLIGHT) {
            if (f.level != level) continue;
            long dx = f.origin.getX() - x, dz = f.origin.getZ() - z;
            if (dx * dx + dz * dz <= d2) return true;
        }
        return false;
    }

    /** そのワールドで計画中の木の数。 */
    static int inFlight(ServerLevel level) {
        int n = 0;
        for (InFlight f : IN_FLIGHT) {
            if (f.level == level) n++;
        }
        return n;
    }

    /** そのワールドで計画中の木を捨てる(出来た計画は積まない。苗木は残っている)。 */
    static int discard(ServerLevel level) {
        int before = IN_FLIGHT.size();
        IN_FLIGHT.removeIf(f -> f.level == level);
        return before - IN_FLIGHT.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (IN_FLIGHT.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        Iterator<InFlight> it = IN_FLIGHT.iterator();
        while (it.hasNext()) {
            InFlight f = it.next();
            if (f.level != level || !f.future.isDone()) continue;
            it.remove();
            GiantTreeFeature.Plan plan;
            try {
                plan = f.future.join();
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.error("GiantTree at {}: 計画を作れませんでした(苗木は残します)", f.origin, e);
                continue;
            }
            // 計画の間にその場所のチャンクが外れていたら積まない(苗木はそのチャンクに残っていて、また読み込まれたら育つ)
            if (level.getChunkSource().getChunkNow(f.origin.getX() >> 4, f.origin.getZ() >> 4) == null) {
                OvergrownVegetation.LOGGER.info("GiantTree at {}: 計画の間にチャンクが外れたので積みません(苗木は残ります)", f.origin);
                continue;
            }
            if (!GiantTreeFeature.commit(level, plan, f.origin)) {
                OvergrownVegetation.LOGGER.warn("GiantTree at {}: 計画が空でした(苗木は残します)", f.origin);
                continue;
            }
            // 積んだので苗木を消す(前はバニラの手順が place の前に消していた。ここからは設置のキューが幹で埋める)
            if (f.sapling != null && level.getBlockState(f.origin).is(f.sapling.getBlock())) {
                level.setBlock(f.origin, level.getFluidState(f.origin).createLegacyBlock(), Block.UPDATE_CLIENTS);
            }
            long now = System.nanoTime();
            lastCaptureNanos = f.captureNanos;
            lastWaitNanos = now - f.startedNanos;
            lastPlanNanos = plan.buildNanos;
            OvergrownVegetation.LOGGER.info("GiantTree at {}: 計画を受け取って積みました(写し {} ms・計画 {} ms・始めてから {} ms)", f.origin,
                    String.format(java.util.Locale.ROOT, "%.1f", f.captureNanos / 1e6),
                    String.format(java.util.Locale.ROOT, "%.1f", plan.buildNanos / 1e6),
                    String.format(java.util.Locale.ROOT, "%.1f", lastWaitNanos / 1e6));
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            discard(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        IN_FLIGHT.clear();
    }
}
