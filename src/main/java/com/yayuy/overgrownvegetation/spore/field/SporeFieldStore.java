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
 * 胞子の場の保存係(2026-10-23 第 2 段 A)。区画(32×32 チャンク)を 8×8 ずつ 1 ファイルにまとめ、
 * {@code <ワールド>/data/overgrown_vegetation_spore_field/r.<rx>.<rz>.dat}(圧縮 NBT)と、場の全体の値 {@code meta.dat}
 * (2026-11-15 軽量化 F。前は区画ごとに {@code t.<tx>.<tz>.dat} で、530 万チャンクだと 8,000 個を開くのに 1 分かかった。前の形は読み込みで書き直す)。
 * DataStorage はサブフォルダを作れないので自前で書く(相談セッションの答え 10-23)。
 *
 * <p>写しも書き出しも専用の IO スレッドで(場の鍵を持って写す。2026-11-15 軽量化 C)。ワールドを閉じるときは書き出しを待つ。
 * 浮遊・林床も保存する(再起動で風下の雲が消えないように)。隣から入った分(16 ビット)は 1 回の更新ごとに作り直すので保存しない。
 */
final class SporeFieldStore {
    static final String DIR = "overgrown_vegetation_spore_field";
    /**
     * 区画の保存の版。3 = 地下の層(地下の浮遊 under・地下の降り積もり underDep・洞窟の口の多さ mouth・洞窟の発芽の回数 cave。2026-11-04)。
     * 2 = 地表の高さ height を持つ(2026-10-30 谷風・逆転層・山の壁)。1(ver が無い)= 高さなし、読み込みで引き直す。
     * 3 より前は地下 0・口はまだ測っていない(読み込まれたら測り直す)。
     * 4 = 土地の表(germ・spread・airEmit・wet)も持つ(2026-11-15 軽量化 B。区画の landSig が今の土地の版と同じときだけ使う。無ければ引き直す)。
     */
    static final int VERSION = 4;

    /** 保存の形(meta の format)。2 = 8×8 区画を 1 ファイル {@code r.<rx>.<rz>.dat} にまとめる(2026-11-15 軽量化 F)。無い = 区画ごとの {@code t.<tx>.<tz>.dat}。 */
    static final int FORMAT = 2;
    /** 1 ファイルにまとめる区画の辺(2 の RB 乗)。 */
    static final int RB = 3;
    /** 読み込みで一度に並べて読むファイルの数(全部を一度に読むとメモリを食う)。 */
    private static final int READ_BATCH = 8;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Overgrown Vegetation spore field IO");
        t.setDaemon(true);
        return t;
    });
    /** 最後に書いたファイル名(区画が無くなったファイルを消すため)。 */
    private static final java.util.Map<String, Set<String>> WRITTEN = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * ファイルごとの、最後に書いた(または読んだ)中身の指紋(2026-10-23: 変わった所だけ書く)。
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

    /** 計測(2026-11-15): 直近の読み込みの内訳。 */
    static volatile long devReadNanos, devBuildNanos;

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
        return load(dir(server, name), field, clockOut, name);
    }

    /**
     * {@code dir} から読む。meta に形の印({@link #FORMAT})があれば、まとめたファイル {@code r.*.dat} だけを読む。
     * 無ければ前の形(区画ごとの {@code t.*.dat})を読み、その場でまとめた形に書き直して照らし合わせ、合っていれば前のファイルを消す(2026-11-15 軽量化 F)。
     */
    static int load(Path dir, SporeField field, long[] clockOut, String name) {
        clockOut[0] = Long.MIN_VALUE;
        java.util.Map<String, Long> savedHash = savedHash(name);
        Set<String> written = written(name);
        savedHash.clear();
        synchronized (written) {
            written.clear();
        }
        if (!Files.isDirectory(dir)) return 0;
        // 今の土地の版(区画ごとに、保存したときの版と同じなら保存した土地の表を使う。2026-11-15 軽量化 B)
        long nowSig = field.ground == null ? 0L : field.ground.landSignature();
        if (DIR.equals(name)) devReadNanos = devBuildNanos = 0;
        CompoundTag meta = null;
        Path metaPath = dir.resolve("meta.dat");
        if (Files.isRegularFile(metaPath)) {
            try {
                meta = NbtIo.readCompressed(metaPath.toFile());
            } catch (IOException | RuntimeException e) {
                OvergrownVegetation.LOGGER.error("胞子の場の全体の値を読めませんでした: {}", metaPath, e);
            }
        }
        List<Path> legacy = list(dir, "t.*.dat");
        // 前の形のファイルは、印の付いた meta を書いた後にしか消さない。だから「印がある」か「前の形が 1 つも無い」ならまとめた形
        boolean regions = (meta != null && meta.getInt("format") >= FORMAT) || legacy.isEmpty();
        int n = regions ? loadRegions(dir, field, nowSig, savedHash, written) : loadLegacy(legacy, field, nowSig);
        if (meta != null) {
            try {
                clockOut[0] = meta.getLong("clock");
                field.launched = meta.getLong("launched");
                field.sproutEvents = meta.getLong("sproutEvents");
                ListTag list = meta.getList("parcels", Tag.TAG_COMPOUND);
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
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.error("胞子の場の全体の値を読めませんでした: {}", metaPath, e);
            }
        }
        if (!regions) convert(dir, field, clockOut[0], name, legacy);
        return n;
    }

    private static List<Path> list(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) out.add(p);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("胞子の場のフォルダを読めませんでした: {}", dir, e);
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString())); // 読む順を決めておく(フォルダの並びに任せない)
        return out;
    }

    /** 前の形(区画ごとに 1 ファイル)を読む。 */
    private static int loadLegacy(List<Path> files, SporeField field, long nowSig) {
        int n = 0;
        List<Path> failed = new ArrayList<>();
        for (Path p : files) {
            String[] parts = p.getFileName().toString().split("\\.");
            if (parts.length != 4) continue;
            try {
                int tx = Integer.parseInt(parts[1]), tz = Integer.parseInt(parts[2]);
                long r0 = System.nanoTime();
                CompoundTag tag = NbtIo.readCompressed(p.toFile());
                long r1 = System.nanoTime();
                field.putLoadedTile(readTile(field, tx, tz, tag, nowSig));
                devReadNanos += r1 - r0; // 計測(2026-11-15): ファイルを読む時間と、区画に組み立てる時間
                devBuildNanos += System.nanoTime() - r1;
                n++;
            } catch (IOException | RuntimeException e) {
                OvergrownVegetation.LOGGER.error("胞子の場の区画を読めませんでした: {}", p, e);
                failed.add(p);
            }
        }
        files.removeAll(failed); // 読めなかったファイルは、書き直した後も消さない
        return n;
    }

    private static CompoundTag readRegion(Path p) {
        try {
            return NbtIo.readCompressed(p.toFile());
        } catch (IOException | RuntimeException e) {
            OvergrownVegetation.LOGGER.error("胞子の場のファイルを読めませんでした: {}", p, e);
            return null;
        }
    }

    /** まとめた形を読む。ファイルは何個かずつ並べて読み(解凍と NBT の解釈)、区画への組み立ては順に。変わっていないファイルを書かないための指紋も入れる。 */
    private static int loadRegions(Path dir, SporeField field, long nowSig, java.util.Map<String, Long> savedHash, Set<String> written) {
        List<Path> files = list(dir, "r.*.dat");
        int n = 0;
        for (int from = 0; from < files.size(); from += READ_BATCH) {
            List<Path> batch = files.subList(from, Math.min(files.size(), from + READ_BATCH));
            long r0 = System.nanoTime();
            List<CompoundTag> tags = batch.parallelStream().map(SporeFieldStore::readRegion).collect(java.util.stream.Collectors.toList());
            long r1 = System.nanoTime();
            for (int b = 0; b < batch.size(); b++) {
                CompoundTag region = tags.get(b);
                tags.set(b, null); // 組み立て終わった分から手放す
                if (region == null) continue;
                String fileName = batch.get(b).getFileName().toString();
                ListTag list = region.getList("tiles", Tag.TAG_COMPOUND);
                long sum = 0;
                int count = 0;
                boolean landAll = true, whole = true;
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag tag = list.getCompound(i);
                    try {
                        int tx = tag.getInt("tx"), tz = tag.getInt("tz");
                        if (!regionName(tx, tz).equals(fileName)) throw new IllegalStateException("区画 " + tx + "," + tz + " が別のファイルに入っています");
                        if (nowSig != 0L && tag.getLong("landSig") != nowSig) landAll = false;
                        SporeField.Tile t = readTile(field, tx, tz, tag, nowSig);
                        field.putLoadedTile(t);
                        sum += tileMix(t);
                        count++;
                        n++;
                    } catch (RuntimeException e) {
                        whole = false;
                        OvergrownVegetation.LOGGER.error("胞子の場の区画を読めませんでした: {} の {} 個目", fileName, i, e);
                    }
                }
                synchronized (written) {
                    written.add(fileName);
                }
                // 土地の表が古い・読めない区画があったファイルは、次の保存で書き直す(指紋を入れない)
                if (landAll && whole) savedHash.put(fileName, regionHash(sum, count, nowSig));
            }
            devReadNanos += r1 - r0;
            devBuildNanos += System.nanoTime() - r1;
        }
        return n;
    }

    /**
     * 前の形から読んだ場を、その場でまとめた形に書き直す(2026-11-15 軽量化 F)。書いたファイルを読み直して、今の場の区画と 1 つずつ・配列ごとに照らし合わせ、
     * 全部合っていれば前のファイルを消す。合わなければ前のファイルを控えとして残す。前の読みかけ(印の無い r.*.dat)は消す。
     */
    private static void convert(Path dir, SporeField field, long clock, String name, List<Path> legacy) {
        long t0 = System.nanoTime();
        Snapshot snap = snapshot(field, clock, name); // 指紋の表は空なので全部書く
        Set<String> now = new HashSet<>();
        for (Object[] e : snap.tiles()) now.add((String) e[0]);
        for (Path stale : list(dir, "r.*.dat")) {
            if (!now.contains(stale.getFileName().toString())) {
                try {
                    Files.deleteIfExists(stale);
                } catch (IOException e) {
                    OvergrownVegetation.LOGGER.error("胞子の場の古いファイルを消せませんでした: {}", stale, e);
                }
            }
        }
        if (!writeNow(dir, snap)) {
            OvergrownVegetation.LOGGER.error("胞子の場: まとめた形への書き直しに失敗しました。前の形のファイルはそのまま残します: {}", dir);
            return;
        }
        long landSig = field.ground == null ? 0L : field.ground.landSignature();
        java.util.Map<Long, SporeField.Tile> live = new java.util.HashMap<>();
        for (SporeField.Tile t : field.tilesView()) live.put(tileKey(t.tx, t.tz), t);
        int same = 0, differ = 0;
        for (String file : now) {
            CompoundTag region = readRegion(dir.resolve(file));
            if (region == null) {
                differ++;
                continue;
            }
            ListTag list = region.getList("tiles", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                SporeField.Tile t = live.remove(tileKey(tag.getInt("tx"), tag.getInt("tz")));
                if (t != null && sameAsTag(t, tag, landSig)) same++;
                else differ++;
            }
        }
        differ += live.size(); // ファイルに入らなかった区画
        if (differ == 0) {
            int deleted = 0;
            for (Path p : legacy) {
                try {
                    if (Files.deleteIfExists(p)) deleted++;
                } catch (IOException e) {
                    OvergrownVegetation.LOGGER.error("胞子の場の前の形のファイルを消せませんでした: {}", p, e);
                }
            }
            OvergrownVegetation.LOGGER.info("胞子の場: 保存の形を書き直しました({})。区画 {} 個 → ファイル {} 個・読み直して全部一致・前のファイル {} 個を消しました({} ms)",
                    name, same, now.size(), deleted, (System.nanoTime() - t0) / 1_000_000L);
        } else {
            OvergrownVegetation.LOGGER.error("胞子の場: 保存の形を書き直しましたが、読み直すと {} 区画が合いません(一致 {})。前の形のファイルを控えとして残します: {}", differ, same, dir);
        }
    }

    /** 書いた形 {@code tag} が、区画 {@code t} の保存する配列と 1 つ残らず同じか。 */
    private static boolean sameAsTag(SporeField.Tile t, CompoundTag tag, long landSig) {
        boolean ok = java.util.Arrays.equals(tag.getIntArray("air"), bits(t.air)) && java.util.Arrays.equals(tag.getIntArray("low"), lowBits(t))
                && java.util.Arrays.equals(tag.getIntArray("dep"), bits(t.dep)) && java.util.Arrays.equals(tag.getLongArray("sprout"), t.sprout)
                && java.util.Arrays.equals(tag.getByteArray("count"), t.count) && java.util.Arrays.equals(tag.getByteArray("flags"), t.flags)
                && java.util.Arrays.equals(tag.getIntArray("under"), bits(t.under)) && java.util.Arrays.equals(tag.getIntArray("underDep"), bits(t.underDep))
                && java.util.Arrays.equals(tag.getIntArray("mouth"), bits(t.mouth)) && java.util.Arrays.equals(tag.getByteArray("cave"), t.caveCount)
                && tag.getInt("ver") == VERSION
                && (t.height == null ? !tag.contains("height") : java.util.Arrays.equals(tag.getIntArray("height"), bits(t.height)));
        if (!ok) return false;
        if (landSig != 0L && t.germ != null && t.spread != null && t.airEmit != null && t.wet != null) {
            byte[] wetB = tag.getByteArray("wet");
            if (tag.getLong("landSig") != landSig || wetB.length != t.wet.length) return false;
            for (int i = 0; i < wetB.length; i++) if ((wetB[i] != 0) != t.wet[i]) return false;
            return java.util.Arrays.equals(tag.getIntArray("germ"), bits(t.germ)) && java.util.Arrays.equals(tag.getIntArray("spread"), bits(t.spread))
                    && java.util.Arrays.equals(tag.getIntArray("airEmit"), bits(t.airEmit));
        }
        return !tag.contains("germ");
    }

    /** 区画 1 つの保存の形(場の鍵を持って)。 */
    static CompoundTag tileTag(SporeField.Tile t) {
        return tileTag(t, 0L);
    }

    /** {@code landSig} = 土地の版(0 = 土地の表を書かない)。区画ごとに書く(書き直されない区画に古い土地が残っても、版が違えば使わない)。 */
    static CompoundTag tileTag(SporeField.Tile t, long landSig) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("air", bits(t.air));
        tag.putIntArray("low", lowBits(t));
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
        // 土地の表(2026-11-15 軽量化 B。開くときに引き直さない)
        if (landSig != 0L && t.germ != null && t.spread != null && t.airEmit != null && t.wet != null) {
            tag.putLong("landSig", landSig);
            tag.putIntArray("germ", bits(t.germ));
            tag.putIntArray("spread", bits(t.spread));
            tag.putIntArray("airEmit", bits(t.airEmit));
            byte[] wetB = new byte[t.wet.length];
            for (int i = 0; i < wetB.length; i++) wetB[i] = (byte) (t.wet[i] ? 1 : 0);
            tag.putByteArray("wet", wetB);
        }
        return tag;
    }

    static SporeField.Tile readTile(SporeField field, int tx, int tz, CompoundTag tag) {
        return readTile(field, tx, tz, tag, 0L);
    }

    /** {@code nowSig} = 今の土地の版(0 = 保存した土地の表を使わない)。区画に書いてある版と同じときだけ使う。 */
    static SporeField.Tile readTile(SporeField field, int tx, int tz, CompoundTag tag, long nowSig) {
        boolean landOk = nowSig != 0L && tag.getLong("landSig") == nowSig;
        // 地表の高さ(2026-10-30、版 2 から)。無ければ(前のセーブ)土地から引き直す
        float[] height = null;
        if (tag.getInt("ver") >= 2 && tag.contains("height")) {
            height = new float[SporeField.TN];
            floats(tag, "height", height);
        }
        SporeField.Tile t = null;
        if (landOk && height != null && tag.getInt("ver") >= 4 && tag.contains("germ")) {
            float[] germ = new float[SporeField.TN], spread = new float[SporeField.TN], airEmit = new float[SporeField.TN];
            byte[] wetB = tag.getByteArray("wet");
            if (floats(tag, "germ", germ) && floats(tag, "spread", spread) && floats(tag, "airEmit", airEmit) && wetB.length == SporeField.TN) {
                boolean[] wet = new boolean[SporeField.TN];
                for (int i = 0; i < wetB.length; i++) wet[i] = wetB[i] != 0;
                t = field.newTileWithLand(tx, tz, height, germ, spread, airEmit, wet);
            }
        }
        if (t == null) t = field.newTile(tx, tz, height);
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

    private static boolean floats(CompoundTag tag, String key, float[] out) {
        int[] bits = tag.getIntArray(key);
        if (bits.length != out.length) return false;
        for (int i = 0; i < bits.length; i++) out[i] = Float.intBitsToFloat(bits[i]);
        return true;
    }

    /** 奥の区画は林床の層の配列を持たない(null = どこも 0。軽量化 M1)。保存の形と指紋は 0 の配列と同じにする。 */
    private static final int ZERO_LOW_HASH = java.util.Arrays.hashCode(new float[SporeField.TN]);

    private static int[] lowBits(SporeField.Tile t) {
        return t.low == null ? new int[SporeField.TN] : bits(t.low);
    }

    private static int[] bits(float[] a) {
        int[] b = new int[a.length];
        for (int i = 0; i < a.length; i++) b[i] = Float.floatToRawIntBits(a[i]);
        return b;
    }

    /** 区画 (tx, tz) が入るファイル(8×8 区画で 1 つ。2026-11-15 軽量化 F)。 */
    static String regionName(int tx, int tz) {
        return "r." + (tx >> RB) + "." + (tz >> RB) + ".dat";
    }

    private static long tileKey(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    /** 区画の中身の指紋(値が 1 つでも変われば変わる)。 */
    private static long hash(SporeField.Tile t) {
        long h = 1469598103934665603L;
        h = h * 31 + java.util.Arrays.hashCode(t.air);
        h = h * 31 + (t.low == null ? ZERO_LOW_HASH : java.util.Arrays.hashCode(t.low));
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

    /** ファイルの指紋に足す、区画 1 つ分(足す順によらない)。 */
    private static long tileMix(SporeField.Tile t) {
        long x = hash(t) * 31 + tileKey(t.tx, t.tz);
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        return x;
    }

    /** ファイルの指紋(入っている区画のどれかが変わる・増える・減る・土地の版が変わると変わる)。 */
    private static long regionHash(long sum, int count, long landSig) {
        return (sum * 31 + count) * 31 + landSig;
    }

    /**
     * 写しを取る(場の鍵を持って呼ぶ)。前に書いたときから中の区画がどれも変わっていないファイルは中身を null にして、そのまま残す。
     * 写しの 1 件 = ファイル 1 つ({@code {名前, 中身}}。2026-11-15 軽量化 F で、区画ごとから 8×8 区画ごとに)。
     */
    static Snapshot snapshot(SporeField field, long clock, String dirName) {
        java.util.Map<String, Long> savedHash = savedHash(dirName);
        long landSig = field.ground == null ? 0L : field.ground.landSignature();
        java.util.Map<String, List<SporeField.Tile>> byFile = new java.util.HashMap<>();
        for (SporeField.Tile t : field.tilesView()) byFile.computeIfAbsent(regionName(t.tx, t.tz), k -> new ArrayList<>()).add(t);
        List<Object[]> tiles = new ArrayList<>();
        for (java.util.Map.Entry<String, List<SporeField.Tile>> en : byFile.entrySet()) {
            String name = en.getKey();
            long sum = 0;
            for (SporeField.Tile t : en.getValue()) sum += tileMix(t);
            long h = regionHash(sum, en.getValue().size(), landSig);
            Long before = savedHash.put(name, h);
            if (before != null && before == h) {
                tiles.add(new Object[]{name, null});
                continue;
            }
            ListTag list = new ListTag();
            for (SporeField.Tile t : en.getValue()) {
                CompoundTag tag = tileTag(t, landSig);
                tag.putInt("tx", t.tx);
                tag.putInt("tz", t.tz);
                list.add(tag);
            }
            CompoundTag region = new CompoundTag();
            region.put("tiles", list);
            tiles.add(new Object[]{name, region});
        }
        CompoundTag meta = new CompoundTag();
        meta.putInt("format", FORMAT);
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

    /**
     * 計測用(2026-11-16 コードの見直し ③11): {@code f} の写しを 2 回取る時間(ns)。1 回目 = 全部のファイルを作る(初めての保存)、
     * 2 回目 = 何も変わっていない(指紋だけ取って全部書かない)。どちらも保存の鍵を持ったままの時間に当たる。書き出しはしない。
     */
    public static long[] benchSnapshotForTests(SporeField f) {
        String name = "bench_" + System.nanoTime();
        try {
            long t0 = System.nanoTime();
            snapshot(f, 0L, name);
            long t1 = System.nanoTime();
            snapshot(f, 0L, name);
            long t2 = System.nanoTime();
            return new long[] {t1 - t0, t2 - t1};
        } finally {
            SAVED_HASH.remove(name);
        }
    }

    /** {@code tiles} の 1 件 = ファイル 1 つ({@code {名前, 中身}}。中身が null = 変わっていないので書かない)。 */
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
        Path dir = dir(server, dirName);
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
                writeNow(dir, snap);
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

    /**
     * 写しを書き出す(呼んだスレッドで)。ファイルは並べて圧縮して書き、全体の値 meta(形の印つき)は最後に書く。
     * 区画が 1 つも無くなったファイルは消す。失敗したら false(指紋を空にして、次の保存で全部書き直す)。
     */
    private static boolean writeNow(Path dir, Snapshot snap) {
        Set<String> written = written(snap.dirName());
        java.util.Map<String, Long> savedHash = savedHash(snap.dirName());
        long w0 = System.nanoTime(); // 計測(2026-11-14)
        try {
            Files.createDirectories(dir);
            Set<String> now = new HashSet<>();
            for (Object[] e : snap.tiles()) now.add((String) e[0]);
            try {
                snap.tiles().parallelStream().forEach(e -> {
                    if (e[1] == null) return; // null = 変わっていない
                    try {
                        writeAtomic(dir.resolve((String) e[0]), (CompoundTag) e[1]);
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
            } catch (java.io.UncheckedIOException ex) {
                throw ex.getCause();
            }
            writeAtomic(dir.resolve("meta.dat"), snap.meta());
            synchronized (written) {
                for (String old : written) {
                    if (!now.contains(old)) Files.deleteIfExists(dir.resolve(old));
                }
                written.clear();
                written.addAll(now);
            }
            if (DIR.equals(snap.dirName())) SporeFieldServer.DevStats.lastWriteNanos = System.nanoTime() - w0;
            return true;
        } catch (IOException | RuntimeException e) {
            OvergrownVegetation.LOGGER.error("胞子の場を書き出せませんでした: {}", dir, e);
            savedHash.clear(); // 次の保存で全部書き直す
            return false;
        }
    }

    /**
     * 仮の名前に書いてから置き換える。バニラの {@code NbtIo.writeCompressed(tag, path)} は書き込みのたびにディスクと同期して遅いので、
     * 普通に書いて最後に 1 回だけ同期する(2026-11-15 軽量化 F)。
     */
    private static void writeAtomic(Path path, CompoundTag tag) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
            NbtIo.writeCompressed(tag, out);
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * テスト用(2026-11-15 軽量化 F): {@code src} を前の形(区画ごとのファイル)で {@code dir} に書き、読み込んで(その場でまとめた形に書き直される)、もう一度読み込む。
     * 3 つの場の区画が 1 つ残らず同じで、前の形のファイルが消えていれば null。違えばその説明。
     */
    static String migrationRoundTripForTests(SporeField src, java.util.function.Supplier<SporeField> fresh, Path dir) throws IOException {
        String name = "test_" + System.nanoTime();
        try {
            Files.createDirectories(dir);
            int tiles = 0;
            for (SporeField.Tile t : src.tilesView()) {
                NbtIo.writeCompressed(tileTag(t, 0L), dir.resolve("t." + t.tx + "." + t.tz + ".dat").toFile());
                tiles++;
            }
            CompoundTag meta = snapshot(src, 1234L, name).meta();
            meta.remove("format");
            NbtIo.writeCompressed(meta, dir.resolve("meta.dat").toFile());
            savedHash(name).clear();
            long[] clock = new long[1];
            SporeField b = fresh.get();
            int nb = load(dir, b, clock, name);
            if (nb != tiles || clock[0] != 1234L) return "前の形の読み込み: 区画 " + nb + "/" + tiles + "・暦 " + clock[0];
            if (!list(dir, "t.*.dat").isEmpty()) return "前の形のファイルが残っている";
            if (list(dir, "r.*.dat").isEmpty()) return "まとめたファイルが無い";
            SporeField c = fresh.get();
            int nc = load(dir, c, clock, name);
            if (nc != tiles || clock[0] != 1234L) return "まとめた形の読み込み: 区画 " + nc + "/" + tiles + "・暦 " + clock[0];
            for (SporeField other : new SporeField[]{b, c}) {
                java.util.Map<Long, SporeField.Tile> m = new java.util.HashMap<>();
                for (SporeField.Tile t : other.tilesView()) m.put(tileKey(t.tx, t.tz), t);
                for (SporeField.Tile t : src.tilesView()) {
                    SporeField.Tile o = m.get(tileKey(t.tx, t.tz));
                    if (o == null || o.corrupted != t.corrupted || !sameAsTag(o, tileTag(t, 0L), 0L)) return "区画 " + t.tx + "," + t.tz + " が違う";
                }
                if (other.launched != src.launched || other.sproutEvents != src.sproutEvents || other.parcels.size() != src.parcels.size()) return "全体の値が違う";
            }
            return null;
        } finally {
            SAVED_HASH.remove(name);
            WRITTEN.remove(name);
        }
    }
}
