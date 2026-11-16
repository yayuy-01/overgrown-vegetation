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
                CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
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
        into.ensureCapacity(into.size() + n);
        for (long[][] part : parts) {
            for (int i = 0; i < part[0].length; i++) into.put(part[0][i], part[1][i]);
        }
        return n;
    }

    /** 確かめ用: 最後の書き出しで写した区域の数と、写しにかかった時間(サーバースレッド)。 */
    static volatile int lastRegions;
    static volatile long lastCopyNanos;

    /** 汚れた区域を写して、IO スレッドで書く(サーバースレッド)。書いたら汚れを消す。 */
    static void flush(MinecraftServer server, Long2LongOpenHashMap ledger, LongOpenHashSet dirty) {
        if (dirty.isEmpty()) return;
        long t0 = System.nanoTime();
        // 汚れた区域ごとに中身を集める(台帳を 1 回なめる。区域が少なければ区域の中だけ見る方が速いが、区域の中の鍵を引く表が無いので 1 回なめる)
        Long2LongOpenHashMap regionIndex = new Long2LongOpenHashMap();
        regionIndex.defaultReturnValue(-1L);
        List<long[]> keys = new ArrayList<>();
        List<long[]> times = new ArrayList<>();
        int[] counts = new int[dirty.size()];
        int idx = 0;
        for (long r : dirty) regionIndex.put(r, idx++);
        // 汚れた区域が少なければ、区域の 4096 升を台帳に引く(台帳全体をなめない)。多ければ台帳を 1 回なめる
        boolean probe = (long) dirty.size() * (1L << (2 * SHIFT)) < ledger.size();
        if (probe) {
            for (long r : dirty) {
                int k = (int) regionIndex.get(r);
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
                counts[k] = ks.size();
                while (keys.size() <= k) {
                    keys.add(null);
                    times.add(null);
                }
                keys.set(k, ks.toLongArray());
                times.set(k, ts.toLongArray());
            }
        } else {
            for (Long2LongMap.Entry e : Long2LongMaps.fastIterable(ledger)) {
                long i = regionIndex.get(regionOf(e.getLongKey()));
                if (i >= 0) counts[(int) i]++;
            }
            for (int i = 0; i < counts.length; i++) {
                keys.add(new long[counts[i]]);
                times.add(new long[counts[i]]);
            }
        }
        int[] fill = new int[counts.length];
        for (Long2LongMap.Entry e : (probe ? java.util.Collections.<Long2LongMap.Entry>emptyList() : Long2LongMaps.fastIterable(ledger))) {
            long i = regionIndex.get(regionOf(e.getLongKey()));
            if (i < 0) continue;
            int k = (int) i;
            keys.get(k)[fill[k]] = e.getLongKey();
            times.get(k)[fill[k]] = e.getLongValue();
            fill[k]++;
        }
        long[] regions = new long[counts.length];
        for (long r : dirty) regions[(int) regionIndex.get(r)] = r;
        dirty.clear();
        lastRegions = regions.length;
        lastCopyNanos = System.nanoTime() - t0;
        Path dir = dir(server);
        IO.execute(() -> {
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
                    NbtIo.writeCompressed(tag, tmp);
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    OvergrownVegetation.LOGGER.error("台帳の区域 {} を書けませんでした", f, e);
                }
            }
        });
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
