package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 台帳の書き換え待ち({@link LedgerSpreadData} の pending)の保存係(2026-11-18 大群の軽量化 B の P1。全体管理の決定)。
 *
 * <p>前は書き換え待ちを {@code overgrown_vegetation_ledger_spread.dat} の 1 本の long 配列に入れていた。待ちは「まだ行っていない
 * 発芽したチャンク」なので台帳とほぼ同じ数になり(本物の写しで 50 万件・530 万の世界で 235 万件)、自動保存のたびにサーバースレッドで
 * 全件を配列にして圧縮して書いていた(65〜300 ms)。台帳({@code phase/LedgerStore})と同じく 64 × 64 チャンクの区域ごとに
 * {@code <ワールド>/data/overgrown_vegetation_ledger_pending/r.<rx>.<rz>.dat}(圧縮 NBT、{@code k} = チャンクの鍵)へ分け、
 * <b>変わった区域だけ</b>を専用の IO スレッドで書く。サーバーを止めた後は書き終わるのを待つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
final class LedgerPendingStore {

    static final String DIR = "overgrown_vegetation_ledger_pending";
    /** 区域の辺(2 の SHIFT 乗チャンク)。台帳と同じ。 */
    static final int SHIFT = 6;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation ledger pending IO");
        t.setDaemon(true);
        return t;
    });

    /** 書けなかった区域(次の保存で書き直す。11-18 見直し ③12。前は書けなくても書き直さず、その区域の待ちが失われた)。 */
    static final java.util.concurrent.ConcurrentLinkedQueue<Long> FAILED = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 確かめ用: 最後の書き出しで書いた区域の数。 */
    static volatile int lastRegions;

    private LedgerPendingStore() {
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

    /** 区域のファイルを全部読む(並べて読む)。区域ごとの鍵の配列を返す。 */
    static List<long[]> loadAll(MinecraftServer server) {
        Path dir = dir(server);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("r.") && name.endsWith(".dat")) paths.add(p);
            }
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("書き換え待ちのフォルダ {} を読めませんでした", dir, e);
            return List.of();
        }
        return paths.parallelStream().map(p -> {
            try {
                return NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap()).getLongArray("k");
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.error("書き換え待ちの区域 {} を読めませんでした", p, e);
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    /** 区域ごとの写し({@code regions[i]} の中身が {@code keys.get(i)})を IO スレッドで書く。空の区域はファイルを消す。 */
    static void write(MinecraftServer server, long[] regions, List<long[]> keys) {
        Path dir = dir(server);
        lastRegions = regions.length;
        IO.execute(() -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                OvergrownVegetation.LOGGER.error("書き換え待ちのフォルダ {} を作れませんでした", dir, e);
                for (long r : regions) FAILED.add(r); // 次の保存で書き直す
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
                    Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                    NbtIo.writeCompressed(tag, tmp);
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    OvergrownVegetation.LOGGER.error("書き換え待ちの区域 {} を書けませんでした(次の保存で書き直す)", f, e);
                    FAILED.add(regions[i]);
                }
            }
        });
    }

    /** 書き出しが終わるのを待つ(サーバーを止めた後・確かめ用)。 */
    static void await() {
        try {
            IO.submit(() -> {
            }).get();
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("書き換え待ちの書き出しを待てませんでした", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        await();
        FAILED.clear(); // 次のワールドへ持ち越さない
    }
}
