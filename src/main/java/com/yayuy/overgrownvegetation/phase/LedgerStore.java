package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 過繁茂の台帳の保存係(見直し ②10、2026-11-16)。前は {@link PhaseData} の保存に台帳を丸ごと入れていた。
 * PhaseData は 100 tick ごとに点で汚れるので、自動保存のたびに台帳が丸ごと書かれていた(300 万チャンクで 1 回 11 MB・
 * サーバースレッドで 50〜100 ms)。台帳を 64 × 64 チャンクの区域ごとに {@code <ワールド>/data/overgrown_vegetation_ledger/r.<rx>.<rz>.dat}
 * (圧縮 NBT)へ分け、<b>変わった区域だけ</b>を書く。書き出しは専用の IO スレッド(写しはサーバースレッドで取る)。
 * サーバーを止めるときは書き終わるのを待つ。胞子の場の保存(軽量化 F)と同じ形。
 *
 * <p>前の形(PhaseData の中の {@code overgrownChunkKeys})のワールドは、読み込んだら全区域を書き直して移す。
 */
final class LedgerStore {

    static final String DIR = "overgrown_vegetation_ledger";
    /** 区域の辺(2 の SHIFT 乗チャンク)。 */
    static final int SHIFT = 6;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation ledger IO");
        t.setDaemon(true);
        return t;
    });

    private LedgerStore() {
    }

    /**
     * 1.20.1: 同梱の fastutil では {@code Long2LongOpenHashMap#ensureCapacity} が private。先に大きさを取らないと hash の順に入れたとき
     * 固まって桁違いに遅くなる(PhaseData の注)ので、反射で呼ぶ(fastutil は難読化されないので名前で引ける)。呼べなければ何もしない。
     */
    static void ensureCapacity(Long2LongOpenHashMap map, int capacity) {
        if (ENSURE_CAPACITY == null) return;
        try {
            ENSURE_CAPACITY.invoke(map, capacity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            OvergrownVegetation.LOGGER.debug("Long2LongOpenHashMap#ensureCapacity を呼べない: {}", e.toString());
        }
    }

    @org.jetbrains.annotations.Nullable
    private static final java.lang.reflect.Method ENSURE_CAPACITY = findEnsureCapacity();

    @org.jetbrains.annotations.Nullable
    private static java.lang.reflect.Method findEnsureCapacity() {
        try {
            java.lang.reflect.Method m = Long2LongOpenHashMap.class.getDeclaredMethod("ensureCapacity", int.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("Long2LongOpenHashMap#ensureCapacity が無い(大きな台帳の読み込みが遅くなりうる): {}", e.toString());
            return null;
        }
    }

    static long regionOf(long chunkKey) {
        return ChunkPos.asLong(ChunkPos.getX(chunkKey) >> SHIFT, ChunkPos.getZ(chunkKey) >> SHIFT);
    }

    static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DIR);
    }

    private static Path file(Path dir, long region) {
        return dir.resolve("r." + ChunkPos.getX(region) + "." + ChunkPos.getZ(region) + ".dat");
    }

    /** 区域のファイルを全部読んで台帳へ入れる。読んだ件数を返す。 */
    static int loadAll(MinecraftServer server, Long2LongOpenHashMap into) {
        Path dir = dir(server);
        if (!Files.isDirectory(dir)) return 0;
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("r.") && name.endsWith(".dat")) paths.add(p);
            }
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("台帳のフォルダ {} を読めませんでした", dir, e);
            return 0;
        }
        // ファイルを並べて読む(1 つずつだと 300 万チャンク・1100 ファイルで 7 秒)。表へ入れるのは、先に大きさを取ってから 1 つのスレッドで
        List<long[][]> parts = paths.parallelStream().map(p -> {
            try {
                CompoundTag tag = NbtIo.readCompressed(p.toFile());
                long[] keys = tag.getLongArray("k");
                long[] times = tag.getLongArray("t");
                return keys.length == times.length ? new long[][] {keys, times} : null;
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.error("台帳の区域 {} を読めませんでした", p, e);
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
        int n = 0;
        for (long[][] part : parts) n += part[0].length;
        ensureCapacity(into, into.size() + n);
        for (long[][] part : parts) {
            for (int i = 0; i < part[0].length; i++) into.put(part[0][i], part[1][i]);
        }
        return n;
    }

    /** 確かめ用: 最後の書き出しで写した区域の数と、写しにかかった時間(サーバースレッド)。 */
    static volatile int lastRegions;
    static volatile long lastCopyNanos;

    /**
     * 汚れた区域を写して、IO スレッドで書く(サーバースレッド)。書いたら汚れを消す。
     *
     * <p>2026-11-18 大群の軽量化 B の ④: 汚れた区域が多くて台帳全体をなめるときは、サーバースレッドでは台帳を写す(配列の写しだけ。
     * 50 万升で数 ms)だけにして、区域ごとに分けるのは IO スレッドで行う(前は自動保存のたびにサーバースレッドで約 100 ms)。
     * 書く中身は写した時点の台帳なので、今までと同じ。
     */
    static void flush(MinecraftServer server, Long2LongOpenHashMap ledger, LongOpenHashSet dirty) {
        if (dirty.isEmpty()) return;
        long t0 = System.nanoTime();
        long[] regions = dirty.toLongArray();
        // 汚れた区域が少なければ、区域の 4096 升を台帳に引く(台帳全体をなめない)。多ければ台帳を写して IO スレッドで 1 回なめる
        // 11-18: 引く升の数(区域 × 4096)が台帳の 1/8 より多ければ、台帳を写して IO スレッドで分ける方が速い(写しは配列の写しで数 ms。
        // 前は「台帳より少なければ引く」で、汚れた区域が 100 前後のとき自動保存のたびにサーバースレッドで 40 万回引いて約 70 ms)
        boolean probe = (long) regions.length * (1L << (2 * SHIFT)) * 8L < ledger.size();
        Path dir = dir(server);
        if (probe) {
            List<long[]> keys = new ArrayList<>(regions.length);
            List<long[]> times = new ArrayList<>(regions.length);
            for (long r : regions) {
                int bx = ChunkPos.getX(r) << SHIFT, bz = ChunkPos.getZ(r) << SHIFT;
                it.unimi.dsi.fastutil.longs.LongArrayList ks = new it.unimi.dsi.fastutil.longs.LongArrayList(), ts = new it.unimi.dsi.fastutil.longs.LongArrayList();
                for (int x = 0; x < (1 << SHIFT); x++) {
                    for (int z = 0; z < (1 << SHIFT); z++) {
                        long key = ChunkPos.asLong(bx + x, bz + z);
                        long t = ledger.get(key);
                        if (t != ledger.defaultReturnValue() || ledger.containsKey(key)) {
                            ks.add(key);
                            ts.add(t);
                        }
                    }
                }
                keys.add(ks.toLongArray());
                times.add(ts.toLongArray());
            }
            dirty.clear();
            lastRegions = regions.length;
            lastCopyNanos = System.nanoTime() - t0;
            IO.execute(() -> writeRegions(dir, regions, keys, times));
        } else {
            Long2LongOpenHashMap copy = ledger.clone();
            dirty.clear();
            lastRegions = regions.length;
            lastCopyNanos = System.nanoTime() - t0;
            IO.execute(() -> {
                List<long[]> keys = new ArrayList<>(regions.length);
                List<long[]> times = new ArrayList<>(regions.length);
                groupByRegion(copy, regions, keys, times);
                writeRegions(dir, regions, keys, times);
            });
        }
    }

    /** 台帳を 1 回なめて、{@code regions} の区域ごとに鍵と時刻を集める(並びは {@code regions} と同じ)。 */
    private static void groupByRegion(Long2LongOpenHashMap ledger, long[] regions, List<long[]> keys, List<long[]> times) {
        Long2LongOpenHashMap regionIndex = new Long2LongOpenHashMap();
        regionIndex.defaultReturnValue(-1L);
        for (int i = 0; i < regions.length; i++) regionIndex.put(regions[i], i);
        int[] counts = new int[regions.length];
        for (Long2LongMap.Entry e : Long2LongMaps.fastIterable(ledger)) {
            long i = regionIndex.get(regionOf(e.getLongKey()));
            if (i >= 0) counts[(int) i]++;
        }
        for (int i = 0; i < counts.length; i++) {
            keys.add(new long[counts[i]]);
            times.add(new long[counts[i]]);
        }
        int[] fill = new int[counts.length];
        for (Long2LongMap.Entry e : Long2LongMaps.fastIterable(ledger)) {
            long i = regionIndex.get(regionOf(e.getLongKey()));
            if (i < 0) continue;
            int k = (int) i;
            keys.get(k)[fill[k]] = e.getLongKey();
            times.get(k)[fill[k]] = e.getLongValue();
            fill[k]++;
        }
    }

    /** 区域のファイルを書く(IO スレッド)。中身の無い区域はファイルを消す。 */
    private static void writeRegions(Path dir, long[] regions, List<long[]> keys, List<long[]> times) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("台帳のフォルダ {} を作れませんでした", dir, e);
            return;
        }
        for (int i = 0; i < regions.length; i++) {
            Path f = file(dir, regions[i]);
            try {
                if (keys.get(i).length == 0) {
                    Files.deleteIfExists(f);
                    continue;
                }
                CompoundTag tag = new CompoundTag();
                tag.putLongArray("k", keys.get(i));
                tag.putLongArray("t", times.get(i));
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                NbtIo.writeCompressed(tag, tmp.toFile());
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.error("台帳の区域 {} を書けませんでした", f, e);
            }
        }
    }

    /** 書き出しが終わるのを待つ(サーバーを止めるとき・確かめ用)。 */
    static void await() {
        try {
            IO.submit(() -> {
            }).get();
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("台帳の書き出しを待てませんでした", e);
        }
    }
}
