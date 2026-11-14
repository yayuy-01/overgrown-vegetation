package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 胞子の場の保存係(2026-10-23 第 2 段 A)。区画(32×32 チャンク)ごとに 1 ファイル、
 * {@code <ワールド>/data/overgrown_vegetation_spore_field/t.<tx>.<tz>.dat}(圧縮 NBT)と、場の全体の値 {@code meta.dat}。
 * DataStorage はサブフォルダを作れないので自前で書く(相談セッションの答え 10-23)。
 *
 * <p>写しはサーバースレッドで(場の鍵を持って)取り、書き出しは専用の IO スレッドで。ワールドを閉じるときは書き出しを待つ。
 * 浮遊・林床も保存する(再起動で風下の雲が消えないように)。隣から入った分(16 ビット)は 1 回の更新ごとに作り直すので保存しない。
 */
final class SporeFieldStore {
    static final String DIR = "overgrown_vegetation_spore_field";
    /**
     * 区画ファイルの版。3 = 地下の層(地下の浮遊 under・地下の降り積もり underDep・洞窟の口の多さ mouth・洞窟の発芽の回数 cave。2026-11-04)。
     * 2 = 地表の高さ height を持つ(2026-10-30 谷風・逆転層・山の壁)。1(ver が無い)= 高さなし、読み込みで引き直す。
     * 3 より前は地下 0・口はまだ測っていない(読み込まれたら測り直す)。
     */
    static final int VERSION = 3;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation spore field IO");
        t.setDaemon(true);
        return t;
    });
    /** 最後に書いた区画のファイル名(消えた区画のファイルを消すため)。IO スレッドだけが触る。 */
    private static final java.util.Map<String, Set<String>> WRITTEN = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 区画ごとの、最後に書いた(または読んだ)中身の指紋(2026-10-23: 変わった区画だけ書く)。サーバースレッドだけが触る。
     * 書き出しに失敗したら空にする(次の保存で全部書き直す)。
     */
    private static final java.util.Map<String, java.util.Map<String, Long>> SAVED_HASH = new java.util.concurrent.ConcurrentHashMap<>();

    /** 場ごとの保存のフォルダ名(オーバーワールド = {@link #DIR}、ネザー = {@link #NETHER_DIR}。2026-10-23 F)。 */
    static final String NETHER_DIR = "overgrown_vegetation_spore_field_nether";

    private static Set<String> written(String name) {
        return WRITTEN.computeIfAbsent(name, k -> new HashSet<>());
    }

    private static java.util.Map<String, Long> savedHash(String name) {
        return SAVED_HASH.computeIfAbsent(name, k -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    private SporeFieldStore() {
    }

    static Path dir(MinecraftServer server) {
        return dir(server, DIR);
    }

    static Path dir(MinecraftServer server, String name) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(name);
    }

    /** 読み込む(サーバースレッド、場を作った直後)。読めた区画の数を返す。場の暦は meta から({@code clockOut[0]}、無ければ Long.MIN_VALUE)。 */
    static int load(MinecraftServer server, SporeField field, long[] clockOut) {
        return load(server, field, clockOut, DIR);
    }

    static int load(MinecraftServer server, SporeField field, long[] clockOut, String name) {
        clockOut[0] = Long.MIN_VALUE;
        Path dir = dir(server, name);
        java.util.Map<String, Long> savedHash = savedHash(name);
        Set<String> written = written(name);
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "t.*.dat")) {
            for (Path p : ds) {
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length != 4) continue;
                try {
                    int tx = Integer.parseInt(parts[1]), tz = Integer.parseInt(parts[2]);
                    CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
                    field.putLoadedTile(readTile(field, tx, tz, tag));
                    n++;
                } catch (IOException | RuntimeException e) {
                    OvergrownVegetation.LOGGER.error("胞子の場の区画を読めませんでした: {}", p, e);
                }
            }
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("胞子の場のフォルダを読めませんでした: {}", dir, e);
        }
        Path meta = dir.resolve("meta.dat");
        if (Files.isRegularFile(meta)) {
            try {
                CompoundTag tag = NbtIo.readCompressed(meta, NbtAccounter.unlimitedHeap());
                clockOut[0] = tag.getLong("clock");
                field.launched = tag.getLong("launched");
                field.sproutEvents = tag.getLong("sproutEvents");
                ListTag list = tag.getList("parcels", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag c = list.getCompound(i);
                    SporeField.Parcel pc = new SporeField.Parcel(c.getDouble("x"), c.getDouble("z"), c.getDouble("mass"),
                            c.getDouble("dropAt"), c.getLong("born"), c.getDouble("turn"));
                    pc.traveled = c.getDouble("traveled");
                    pc.outsideTicks = c.getLong("outside");
                    pc.bestX = c.getDouble("bestX");
                    pc.bestZ = c.getDouble("bestZ");
                    pc.bestD = c.getDouble("bestD");
                    field.parcels.add(pc);
                }
            } catch (IOException | RuntimeException e) {
                OvergrownVegetation.LOGGER.error("胞子の場の全体の値を読めませんでした: {}", meta, e);
            }
        }
        savedHash.clear();
        for (SporeField.Tile t : field.tilesView()) savedHash.put(fileName(t.tx, t.tz), hash(t));
        synchronized (written) {
            written.clear();
            for (SporeField.Tile t : field.tilesView()) written.add(fileName(t.tx, t.tz));
        }
        return n;
    }

    /** 区画 1 つの保存の形(サーバースレッド、場の鍵を持って)。 */
    static CompoundTag tileTag(SporeField.Tile t) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("air", bits(t.air));
        tag.putIntArray("low", bits(t.low));
        tag.putIntArray("dep", bits(t.dep));
        tag.putLongArray("sprout", t.sprout.clone());
        tag.putByteArray("count", t.count.clone());
        tag.putByteArray("flags", t.flags.clone());
        tag.putInt("ver", VERSION);
        if (t.height != null) tag.putIntArray("height", bits(t.height));
        tag.putIntArray("under", bits(t.under));
        tag.putIntArray("underDep", bits(t.underDep));
        tag.putIntArray("mouth", bits(t.mouth));
        tag.putByteArray("cave", t.caveCount.clone());
        return tag;
    }

    static SporeField.Tile readTile(SporeField field, int tx, int tz, CompoundTag tag) {
        // 地表の高さ(2026-10-30、版 2 から)。無ければ(前のセーブ)土地から引き直す
        float[] height = null;
        if (tag.getInt("ver") >= 2 && tag.contains("height")) {
            height = new float[SporeField.TN];
            floats(tag, "height", height);
        }
        SporeField.Tile t = field.newTile(tx, tz, height);
        floats(tag, "air", t.air);
        floats(tag, "low", t.low);
        floats(tag, "dep", t.dep);
        long[] sp = tag.getLongArray("sprout");
        if (sp.length == SporeField.TN) System.arraycopy(sp, 0, t.sprout, 0, SporeField.TN);
        byte[] cnt = tag.getByteArray("count");
        if (cnt.length == SporeField.TN) System.arraycopy(cnt, 0, t.count, 0, SporeField.TN);
        byte[] fl = tag.getByteArray("flags");
        if (fl.length == SporeField.TN) System.arraycopy(fl, 0, t.flags, 0, SporeField.TN);
        // 地下の層(版 3 から)。前の版は地下 0・口は NaN(まだ測っていない)のまま
        if (tag.getInt("ver") >= 3) {
            floats(tag, "under", t.under);
            floats(tag, "underDep", t.underDep);
            floats(tag, "mouth", t.mouth);
            byte[] cave = tag.getByteArray("cave");
            if (cave.length == SporeField.TN) System.arraycopy(cave, 0, t.caveCount, 0, SporeField.TN);
        }
        int c = 0;
        for (long v : t.sprout) if (v != SporeField.NOT) c++;
        t.corrupted = c;
        return t;
    }

    private static void floats(CompoundTag tag, String key, float[] out) {
        int[] bits = tag.getIntArray(key);
        if (bits.length != out.length) return;
        for (int i = 0; i < bits.length; i++) out[i] = Float.intBitsToFloat(bits[i]);
    }

    private static int[] bits(float[] a) {
        int[] b = new int[a.length];
        for (int i = 0; i < a.length; i++) b[i] = Float.floatToRawIntBits(a[i]);
        return b;
    }

    private static String fileName(int tx, int tz) {
        return "t." + tx + "." + tz + ".dat";
    }

    /** 区画の中身の指紋(値が 1 つでも変われば変わる)。 */
    private static long hash(SporeField.Tile t) {
        long h = 1469598103934665603L;
        h = h * 31 + java.util.Arrays.hashCode(t.air);
        h = h * 31 + java.util.Arrays.hashCode(t.low);
        h = h * 31 + java.util.Arrays.hashCode(t.dep);
        h = h * 31 + java.util.Arrays.hashCode(t.sprout);
        h = h * 31 + java.util.Arrays.hashCode(t.count);
        h = h * 31 + java.util.Arrays.hashCode(t.flags);
        h = h * 31 + java.util.Arrays.hashCode(t.height);
        h = h * 31 + java.util.Arrays.hashCode(t.under);
        h = h * 31 + java.util.Arrays.hashCode(t.underDep);
        h = h * 31 + java.util.Arrays.hashCode(t.mouth);
        h = h * 31 + java.util.Arrays.hashCode(t.caveCount);
        return h;
    }

    /**
     * 写しを取る(サーバースレッド、場の鍵を持って呼ぶ)。前に書いたときから変わっていない区画は中身を null にして、
     * ファイルをそのまま残す(2026-10-23: 毎回全部の区画を書いていたのを、変わった区画だけに)。
     */
    static Snapshot snapshot(SporeField field, long clock) {
        return snapshot(field, clock, DIR);
    }

    static Snapshot snapshot(SporeField field, long clock, String dirName) {
        java.util.Map<String, Long> savedHash = savedHash(dirName);
        List<Object[]> tiles = new ArrayList<>();
        for (SporeField.Tile t : field.tilesView()) {
            String name = fileName(t.tx, t.tz);
            long h = hash(t);
            Long before = savedHash.put(name, h);
            if (before != null && before == h) {
                tiles.add(new Object[]{name, null});
                continue;
            }
            tiles.add(new Object[]{name, tileTag(t)});
        }
        CompoundTag meta = new CompoundTag();
        meta.putLong("clock", clock);
        meta.putLong("launched", field.launched);
        meta.putLong("sproutEvents", field.sproutEvents);
        ListTag list = new ListTag();
        for (SporeField.Parcel pc : field.parcels) {
            CompoundTag c = new CompoundTag();
            c.putDouble("x", pc.x);
            c.putDouble("z", pc.z);
            c.putDouble("mass", pc.mass);
            c.putDouble("dropAt", pc.dropAt);
            c.putLong("born", pc.born);
            c.putDouble("turn", pc.turn);
            c.putDouble("traveled", pc.traveled);
            c.putLong("outside", pc.outsideTicks);
            c.putDouble("bestX", pc.bestX);
            c.putDouble("bestZ", pc.bestZ);
            c.putDouble("bestD", pc.bestD);
            list.add(c);
        }
        meta.put("parcels", list);
        return new Snapshot(tiles, meta, dirName);
    }

    record Snapshot(List<Object[]> tiles, CompoundTag meta, String dirName) {
    }

    /** 前の自動保存がまだ並んでいる・書いている最中か(場ごと)。 */
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> QUEUED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 保存を丸ごと IO スレッドで(2026-11-15 軽量化 C): 場の鍵を待つ・写しを取る・書き出す。サーバースレッドは頼むだけで待たない
     * (前は鍵を最大 2 秒待ってサーバーが止まり、取れなければその回の保存を飛ばしていた)。{@code wait}(ワールドを閉じるとき)は終わるまで待つ。
     * 自動保存は、前の自動保存がまだ終わっていなければ足さない(書き出しが長いときに積もらせない。次の自動保存で書く)。
     * {@code clock} は鍵を持った所で呼ぶ(場がどの暦まで進んだか)。
     */
    static void saveOffThread(MinecraftServer server, java.util.concurrent.locks.ReentrantLock lock, SporeField field,
                              java.util.function.LongSupplier clock, String dirName, boolean wait, boolean stats) {
        java.util.concurrent.atomic.AtomicBoolean queued = QUEUED.computeIfAbsent(dirName, k -> new java.util.concurrent.atomic.AtomicBoolean());
        if (!wait && !queued.compareAndSet(false, true)) {
            if (stats) SporeFieldServer.DevStats.savesSkipped++;
            return;
        }
        Runnable job = () -> {
            try {
                long t0 = System.nanoTime();
                Snapshot snap;
                lock.lock();
                long t1 = System.nanoTime();
                try {
                    snap = snapshot(field, clock.getAsLong(), dirName);
                } finally {
                    lock.unlock();
                }
                if (stats) {
                    long t2 = System.nanoTime();
                    SporeFieldServer.DevStats.saves++;
                    SporeFieldServer.DevStats.saveLockNanos += t1 - t0;
                    SporeFieldServer.DevStats.saveLockMaxNanos = Math.max(SporeFieldServer.DevStats.saveLockMaxNanos, t1 - t0);
                    SporeFieldServer.DevStats.saveSnapNanos += t2 - t1;
                    SporeFieldServer.DevStats.saveSnapMaxNanos = Math.max(SporeFieldServer.DevStats.saveSnapMaxNanos, t2 - t1);
                    int changed = 0;
                    for (Object[] e : snap.tiles()) if (e[1] != null) changed++;
                    SporeFieldServer.DevStats.lastSaveTiles = snap.tiles().size();
                    SporeFieldServer.DevStats.lastSaveChanged = changed;
                }
                writeNow(server, snap, true);
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.error("胞子の場を保存できませんでした: {}", dirName, e);
            } finally {
                if (!wait) queued.set(false);
            }
        };
        if (wait) {
            try {
                IO.submit(job).get();
            } catch (Exception e) {
                OvergrownVegetation.LOGGER.error("胞子の場の書き出しを待てませんでした", e);
            }
        } else {
            IO.execute(job);
        }
    }

    /** 写しを書き出す(呼んだスレッドで)。{@code onIoThread} = IO スレッドから(指紋の表を直に触ってよい)。 */
    private static void writeNow(MinecraftServer server, Snapshot snap, boolean onIoThread) {
        Path dir = dir(server, snap.dirName());
        Set<String> written = written(snap.dirName());
        java.util.Map<String, Long> savedHash = savedHash(snap.dirName());
        long w0 = System.nanoTime(); // 計測(2026-11-14)
        try {
            Files.createDirectories(dir);
            Set<String> now = new HashSet<>();
            for (Object[] e : snap.tiles()) {
                String name = (String) e[0];
                now.add(name);
                if (e[1] != null) writeAtomic(dir.resolve(name), (CompoundTag) e[1]); // null = 変わっていない
            }
            writeAtomic(dir.resolve("meta.dat"), snap.meta());
            synchronized (written) {
                for (String old : written) {
                    if (!now.contains(old)) Files.deleteIfExists(dir.resolve(old));
                }
                written.clear();
                written.addAll(now);
            }
            SporeFieldServer.DevStats.lastWriteNanos = System.nanoTime() - w0;
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("胞子の場を書き出せませんでした: {}", dir, e);
            // 次の保存で全部書き直す
            if (onIoThread) savedHash.clear();
            else server.execute(savedHash::clear);
        }
    }

    /** 書き出す。{@code wait} なら書き終わるまで待つ(ワールドを閉じるとき)。 */
    static void write(MinecraftServer server, Snapshot snap, boolean wait) {
        Runnable job = () -> writeNow(server, snap, false);
        if (wait) {
            try {
                IO.submit(job).get();
            } catch (Exception e) {
                OvergrownVegetation.LOGGER.error("胞子の場の書き出しを待てませんでした", e);
            }
        } else {
            IO.execute(job);
        }
    }

    private static void writeAtomic(Path path, CompoundTag tag) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, tmp);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
