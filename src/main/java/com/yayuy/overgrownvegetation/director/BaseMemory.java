package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>拠点の記憶</b>(2026-11-14、司令塔 第 2 段 区切り 1。D-7・D-12)。オーバーワールドに 1 つの保存データ。
 *
 * <p>升(チャンク)ごとに「プレイヤーがいた時間」と「置いた物(タグ {@code base_structure})の数」を持ち、近い升をまとめて 1 つの拠点と見る。
 * 点を足すのは見張り({@link BaseWatch})。日はワールドの日付(dayTime ÷ 24000。寝て朝にしても 1 日進む)で数える。
 *
 * <ul>
 *   <li><b>覚える</b>: まとまりの点が {@code rememberScore} を超えたら、拠点 1 つを覚える({@link Stage#REMEMBERED})。</li>
 *   <li><b>忘れる</b>(11-14 ユーザー決定 Q6 = 1): 拠点の升に誰もいない日が 1 日を超えると点が減り始め、認識した拠点は 5 日・覚えただけの拠点は 2 日で 0 になって忘れる。
 *   減った分は戻らない(帰ってきたときに、減った所から足し直す)。</li>
 *   <li>偵察({@link Stage#SCOUTED})と認識({@link Stage#RECOGNIZED})へ進めるのは区切り 2・3。</li>
 * </ul>
 * 中身の計算は世界を見ない(GameTest は {@link #createForTests} の記憶に、自分で日付を渡して試す)。
 */
public final class BaseMemory extends SavedData {

    private static final String ID = "overgrown_vegetation_base_memory";

    public static final SavedData.Factory<BaseMemory> FACTORY =
            new SavedData.Factory<>(BaseMemory::new, (tag, registries) -> load(tag), null);

    /** 拠点の段。保存は名前で。 */
    public enum Stage {
        /** 覚えた(まだ偵察が来ていない)。 */
        REMEMBERED("覚えた"),
        /** 偵察が来た(認識までの日数を数えている)。 */
        SCOUTED("偵察済み"),
        /** 拠点と認識した(送り込む)。 */
        RECOGNIZED("認識");

        public final String label;

        Stage(String label) {
            this.label = label;
        }

        static Stage byName(String name) {
            for (Stage s : values()) {
                if (s.name().equals(name)) return s;
            }
            return REMEMBERED;
        }
    }

    /** 1 つの升の覚え。 */
    static final class Cell {
        double presence;
        int structures;
        double lastSeen;
        /** この升がいる拠点の段(忘れる日数の選び分け。数え直すたびに付け直す。保存しない)。 */
        @Nullable
        transient Stage stage;
    }

    /** 覚えた拠点 1 つ。真ん中と点は数え直すたびに付け直す。 */
    public static final class Base {
        private final int id;
        private Stage stage = Stage.REMEMBERED;
        /** その段になった日。 */
        private double stageSince;
        private double centerX;
        private double centerZ;
        private double score;
        private double lastSeen;
        private int cells;
        private int structures;
        // ---- 区切り 2: 偵察
        /** 偵察済みから認識までの日数(偵察済みになったときに 1〜2 日から 1 つ)。 */
        private double recognizeAfter;
        /** 眺め終えた偵察の数と、倒された偵察の数。 */
        private int scoutsDone;
        private int scoutKills;
        /** 次に偵察を出してよい日。 */
        private double nextScoutDay;
        // ---- 区切り 3: 送り込み
        /** 次に送り込む日と、これまでに送り込んだ回数。 */
        private double nextWaveDay;
        private int wavesSent;
        // ---- 第 3 段 区切り 2: 弱い所を突く
        /** いちばん守りの薄い方位(−1 = まだ分からない)と、見た日。方位ごとの守り(保存しない)。 */
        private int weakSector = -1;
        private double defenseAt = Double.NEGATIVE_INFINITY;
        @Nullable
        private double[] defense;

        Base(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public Stage stage() {
            return stage;
        }

        public double stageSince() {
            return stageSince;
        }

        public double centerX() {
            return centerX;
        }

        public double centerZ() {
            return centerZ;
        }

        /** 減りを掛けた後の点。 */
        public double score() {
            return score;
        }

        /** 最後に誰かがいた日。 */
        public double lastSeen() {
            return lastSeen;
        }

        public int cells() {
            return cells;
        }

        public int structures() {
            return structures;
        }

        public double recognizeAfter() {
            return recognizeAfter;
        }

        public int scoutsDone() {
            return scoutsDone;
        }

        public int scoutKills() {
            return scoutKills;
        }

        public double nextScoutDay() {
            return nextScoutDay;
        }

        public double nextWaveDay() {
            return nextWaveDay;
        }

        public int wavesSent() {
            return wavesSent;
        }

        public int weakSector() {
            return weakSector;
        }

        @Nullable
        public double[] defense() {
            return defense;
        }

        public double defenseAt() {
            return defenseAt;
        }

        /** 守りを見た結果を入れる。 */
        public void setDefense(double[] d, int weak, double day) {
            defense = d;
            weakSector = weak;
            defenseAt = day;
        }

        /**
         * 弱い方角の輪の上の点(拠点の真ん中から {@code radius})。まだ分からなければ null。
         */
        @Nullable
        public net.minecraft.world.phys.Vec3 weakPoint(double radius, double y) {
            if (weakSector < 0) return null;
            int n = DesignData.director().base.weakSectors;
            double a = BaseDefense.angleOf(weakSector, n);
            return new net.minecraft.world.phys.Vec3(centerX + Math.cos(a) * radius, y, centerZ + Math.sin(a) * radius);
        }

        /** 認識まで、あと何日(偵察済みのときだけ。ほかは NaN)。 */
        public double daysToRecognize(double day) {
            return stage == Stage.SCOUTED ? Math.max(0.0D, stageSince + recognizeAfter - day) : Double.NaN;
        }

        /** 段を進める(区切り 2・3 の偵察と認識が呼ぶ)。 */
        void setStage(Stage s, double day) {
            stage = s;
            stageSince = day;
        }
    }

    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final List<Base> bases = new ArrayList<>();
    private int nextId = 1;
    /** これまでに忘れた拠点の数(確かめ用)。 */
    private long forgotten;

    private BaseMemory() {
    }

    public static BaseMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** テスト用: 世界に 1 つの記憶とは別の、空の記憶。 */
    public static BaseMemory createForTests() {
        return new BaseMemory();
    }

    /** ワールドの日付(日。寝て朝にすると進む)。 */
    public static double today(MinecraftServer server) {
        return Math.max(0L, server.overworld().getDayTime()) / 24000.0D;
    }

    private static BaseMemoryTable table() {
        return DesignData.director().base;
    }

    // ---------------------------------------------------------------- 点を足す(見張りから)

    /** 升にいた時間(ゲーム内の時間)を足す。前にいなかった間の減りは、ここで焼き込む(減った分は戻らない)。 */
    public void addPresence(long chunk, double hours, double day) {
        Cell c = touch(chunk, day);
        c.presence = Math.min(table().presenceCapHours, c.presence + Math.max(0.0D, hours));
        setDirty();
    }

    /** 升の置き物の数を、数え直した値にする。 */
    public void setStructures(long chunk, int count, double day) {
        Cell c = touch(chunk, day);
        c.structures = Math.max(0, Math.min(table().structureCap, count));
        setDirty();
    }

    private Cell touch(long chunk, double day) {
        Cell c = cells.get(chunk);
        if (c == null) {
            c = new Cell();
            c.lastSeen = day;
            cells.put(chunk, c);
            return c;
        }
        double keep = table().keep(day - c.lastSeen, forgetDays(c.stage));
        c.presence *= keep;
        c.lastSeen = Math.max(c.lastSeen, day);
        return c;
    }

    private static double forgetDays(@Nullable Stage stage) {
        BaseMemoryTable t = table();
        return stage == Stage.RECOGNIZED ? t.forgetRecognizedDays : t.forgetRememberedDays;
    }

    /** 升 1 つの点(減りを掛けた後)。 */
    static double cellScore(Cell c, double day) {
        BaseMemoryTable t = table();
        double raw = c.presence + t.structureHours * c.structures;
        return raw * t.keep(day - c.lastSeen, forgetDays(c.stage));
    }

    // ---------------------------------------------------------------- 数え直す

    /**
     * 升をまとめ直して、拠点を覚える・付け直す・忘れる。見張りが {@code sampleTicks} ごとに呼ぶ(誰もいなくても呼ぶ = 忘れるのはここ)。
     * 升の数はプレイヤーの足跡ぶん(2 日で消える)なので、まとめは毎回やり直す。
     */
    public void update(double day) {
        update(day, true);
    }

    /**
     * {@code recognizeOpen} = 偵察済み → 認識へ進めてよいか(フェーズ F5 から。DirectorGates)。閉じている間も、覚える・忘れる・偵察済みの日数は進む
     * (F4 までに偵察済みになった拠点は、F5 になった時点で日数が足りていればすぐ認識 =「森は前から見ていた」)。
     */
    public void update(double day, boolean recognizeOpen) {
        BaseMemoryTable t = table();
        // つながった升をまとめる(clusterChunks 以内)
        LongOpenHashSet seen = new LongOpenHashSet();
        List<LongArrayList> groups = new ArrayList<>();
        int r = t.clusterChunks;
        for (long start : cells.keySet().toLongArray()) {
            if (!seen.add(start)) continue;
            LongArrayList keys = new LongArrayList();
            LongArrayList queue = new LongArrayList();
            queue.add(start);
            while (!queue.isEmpty()) {
                long k = queue.removeLong(queue.size() - 1);
                keys.add(k);
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        long n = ChunkPos.asLong(cx + dx, cz + dz);
                        if (cells.containsKey(n) && seen.add(n)) queue.add(n);
                    }
                }
            }
            groups.add(keys);
        }
        // いない日は拠点(まとまり)ごとに数える: 寝室の升にいれば、隣の升のチェストも「いた」ことにする。
        // 減りの焼き込みを先に済ませてから「最後にいた日」をそろえる(そろえた後に焼くと、減りが消える)
        List<Cluster> clusters = new ArrayList<>();
        for (LongArrayList keys : groups) {
            double last = Double.NEGATIVE_INFINITY;
            for (long k : keys) last = Math.max(last, cells.get(k).lastSeen);
            Stage stage = null;
            for (long k : keys) {
                Cell c = cells.get(k);
                if (c.stage == Stage.RECOGNIZED) stage = Stage.RECOGNIZED;
                if (c.lastSeen < last) {
                    c.presence *= t.keep(day - c.lastSeen, forgetDays(c.stage)) / Math.max(1.0E-9D, t.keep(day - last, forgetDays(c.stage)));
                    c.lastSeen = last;
                }
            }
            // 忘れたまとまりは升ごと捨てる
            if (t.keep(day - last, forgetDays(stage)) <= 0.0D) {
                for (long k : keys) cells.remove(k);
                continue;
            }
            Cluster cl = new Cluster();
            for (long k : keys) cl.add(k, cells.get(k), day);
            clusters.add(cl);
        }
        // 前の拠点に付け直す(真ん中がいちばん近いもの)。付かなかった拠点は忘れる
        List<Base> kept = new ArrayList<>();
        boolean[] used = new boolean[clusters.size()];
        for (Base b : bases) {
            int best = -1;
            double bestD = t.matchBlocks * t.matchBlocks;
            for (int i = 0; i < clusters.size(); i++) {
                if (used[i] || clusters.get(i).weight <= 0.0D) continue;
                double dx = clusters.get(i).cx() - b.centerX, dz = clusters.get(i).cz() - b.centerZ;
                double d2 = dx * dx + dz * dz;
                if (d2 <= bestD) {
                    bestD = d2;
                    best = i;
                }
            }
            if (best < 0) {
                forgotten++;
                continue;
            }
            used[best] = true;
            clusters.get(best).applyTo(b);
            kept.add(b);
        }
        // 新しく覚える
        for (int i = 0; i < clusters.size(); i++) {
            Cluster cl = clusters.get(i);
            if (used[i] || cl.score < t.rememberScore) continue;
            Base b = new Base(nextId++);
            b.stageSince = day;
            b.nextScoutDay = day + table().scoutFirstDays; // 覚えてすぐには来ない【仮】
            cl.applyTo(b);
            kept.add(b);
        }
        bases.clear();
        bases.addAll(kept);
        // 偵察済みから 1〜2 日引っ越さなければ認識する(11-14 ユーザー決定 Q5 = 1: 偵察が来てから数える)。
        // 引っ越した = 2 日いなければ、覚えただけの拠点と同じく忘れる(上の付け直しで消える)
        for (Base b : bases) {
            if (recognizeOpen && b.stage == Stage.SCOUTED && day - b.stageSince >= b.recognizeAfter && confirmed(b, day)) {
                b.setStage(Stage.RECOGNIZED, day);
                b.nextWaveDay = day + t.waveIntervalMinDays * 0.5D; // 認識してすぐ、最初の送り込み【仮】
            }
        }
        // 升に、いる拠点の段を付け直す(次の忘れる日数)
        for (Cell c : cells.values()) c.stage = null;
        for (Base b : bases) {
            for (long k : clusterOf(b, clusters)) {
                Cell c = cells.get(k);
                if (c != null) c.stage = b.stage;
            }
        }
        setDirty();
    }

    private static LongArrayList clusterOf(Base b, List<Cluster> clusters) {
        for (Cluster cl : clusters) {
            if (cl.base == b) return cl.keys;
        }
        return new LongArrayList();
    }

    /** まとまり 1 つ(数え直しの途中だけ)。 */
    private static final class Cluster {
        final LongArrayList keys = new LongArrayList();
        double score;
        double weight;
        double sx;
        double sz;
        double lastSeen = Double.NEGATIVE_INFINITY;
        int structures;
        @Nullable
        Base base;

        void add(long k, Cell c, double day) {
            keys.add(k);
            double s = cellScore(c, day);
            score += s;
            // 真ん中は点の重みで(点が 0 の升も、わずかに重みを持たせる)
            double w = Math.max(1.0E-3D, s);
            weight += w;
            sx += (ChunkPos.getX(k) * 16 + 8) * w;
            sz += (ChunkPos.getZ(k) * 16 + 8) * w;
            lastSeen = Math.max(lastSeen, c.lastSeen);
            structures += c.structures;
        }

        double cx() {
            return sx / weight;
        }

        double cz() {
            return sz / weight;
        }

        void applyTo(Base b) {
            base = b;
            b.centerX = cx();
            b.centerZ = cz();
            b.score = score;
            b.lastSeen = lastSeen;
            b.cells = keys.size();
            b.structures = structures;
        }
    }

    // ---------------------------------------------------------------- 認識を確定する口・遅らせる・忘れさせる(監視塔を後から差し込む口)

    /**
     * 認識を確定する条件(外から足す)。全部が true を返したときだけ、偵察済みから認識へ進む。何も登録されていなければ、今の形
     * (偵察が来てから 1〜2 日)だけで認識する。11-14 ユーザー(未確定)「偵察は感染体、確定は監視塔」にするなら、監視塔の側が
     * 「この拠点のそばに監視塔が立っているか」をここに登録する。
     */
    public interface Confirmer {
        boolean confirm(Base base, double day);
    }

    private static final List<Confirmer> CONFIRMERS = new ArrayList<>();

    public static void registerConfirmer(Confirmer c) {
        CONFIRMERS.add(c);
    }

    private static boolean confirmed(Base b, double day) {
        for (Confirmer c : CONFIRMERS) {
            if (!c.confirm(b, day)) return false;
        }
        return true;
    }

    /** 外から: 偵察済みの拠点の認識を {@code days} 日遅らせる(例: 監視塔を刈った)。偵察済みでなければ何もしない。 */
    public void delayRecognition(int baseId, double days) {
        Base b = byId(baseId);
        if (b == null || b.stage != Stage.SCOUTED) return;
        b.stageSince += Math.max(0.0D, days);
        setDirty();
    }

    /**
     * 外から: 拠点を 1 段戻す(認識 → 偵察済み → 覚えた)か、すっかり忘れさせる。
     *
     * @param forgetAll true なら記憶ごと消す(升も)。false なら 1 段戻す
     */
    public void setBack(int baseId, double day, boolean forgetAll) {
        Base b = byId(baseId);
        if (b == null) return;
        if (forgetAll) {
            bases.remove(b);
            double r2 = table().matchBlocks * table().matchBlocks;
            cells.long2ObjectEntrySet().removeIf(en -> {
                double dx = ChunkPos.getX(en.getLongKey()) * 16 + 8 - b.centerX, dz = ChunkPos.getZ(en.getLongKey()) * 16 + 8 - b.centerZ;
                return dx * dx + dz * dz <= r2;
            });
            forgotten++;
        } else if (b.stage == Stage.RECOGNIZED) {
            b.setStage(Stage.SCOUTED, day);
        } else if (b.stage == Stage.SCOUTED) {
            b.setStage(Stage.REMEMBERED, day);
        }
        setDirty();
    }

    /** テスト用: 足した確定の条件を外す。 */
    public static void clearConfirmersForTests() {
        CONFIRMERS.clear();
    }

    // ---------------------------------------------------------------- 偵察(区切り 2)

    @Nullable
    public Base byId(int id) {
        for (Base b : bases) {
            if (b.id == id) return b;
        }
        return null;
    }

    /** 偵察を出したい拠点か(覚えた・偵察済みで、前の偵察から間が空いた)。 */
    public static boolean wantsScout(Base b, double day) {
        return (b.stage == Stage.REMEMBERED || b.stage == Stage.SCOUTED) && day >= b.nextScoutDay;
    }

    /**
     * 偵察を出した: 次の偵察は、出した時から間を空ける(眺め終えても・倒されても・着けずに帰っても、次の時は変えない)。
     * {@code quiet} = F4 の間(認識がまだ開いていない)。間は ふだん 0.4〜0.6 日・F4 は 1〜2 日(11-14 ユーザー)。
     */
    public void scoutSent(Base b, double day, net.minecraft.util.RandomSource random, boolean quiet) {
        b.nextScoutDay = day + scoutInterval(random, quiet);
        setDirty();
    }

    /** 出せる段階 0 がいなかった: 少し後でまた探す。 */
    public void scoutNotFound(Base b, double day) {
        b.nextScoutDay = day + table().scoutRetryDays;
        setDirty();
    }

    private static void markScouted(Base b, double day, net.minecraft.util.RandomSource random) {
        if (b.stage != Stage.REMEMBERED) return;
        BaseMemoryTable t = table();
        b.setStage(Stage.SCOUTED, day);
        b.recognizeAfter = t.recognizeMinDays + random.nextDouble() * (t.recognizeMaxDays - t.recognizeMinDays);
    }

    /** 偵察の間(日)。 */
    public static double scoutInterval(net.minecraft.util.RandomSource random, boolean quiet) {
        BaseMemoryTable t = table();
        double lo = quiet ? t.scoutQuietIntervalMinDays : t.scoutIntervalMinDays, hi = quiet ? t.scoutQuietIntervalMaxDays : t.scoutIntervalMaxDays;
        return lo + random.nextDouble() * (hi - lo);
    }

    /** 偵察が眺め終えた: 覚えた拠点なら偵察済みにする(認識までの日数を数え始める)。 */
    public void onScouted(int baseId, double day, net.minecraft.util.RandomSource random) {
        Base b = byId(baseId);
        if (b == null) return;
        markScouted(b, day, random);
        b.scoutsDone++;
        setDirty();
    }

    /**
     * 偵察がプレイヤーに倒された: 森に見つかったことになり、認識が進む(11-14 ユーザー決定「倒すと逆に認識が進むようにしたい」)。
     * 覚えただけの拠点は、眺め終えたのと同じく偵察済みになり、さらに {@code killAdvanceDays} 日ぶん認識が早まる。偵察済みの拠点も同じだけ早まる。
     */
    public void onScoutKilled(int baseId, double day, net.minecraft.util.RandomSource random) {
        Base b = byId(baseId);
        if (b == null) return;
        markScouted(b, day, random);
        if (b.stage == Stage.SCOUTED) b.stageSince -= table().killAdvanceDays;
        b.scoutKills++;
        setDirty();
    }

    // ---------------------------------------------------------------- 送り込み(区切り 3)

    /** 送り込む拠点か(認識していて、前の送り込みから間が空いた)。 */
    public static boolean wantsWave(Base b, double day) {
        return b.stage == Stage.RECOGNIZED && day >= b.nextWaveDay;
    }

    /** 送り込んだ。 */
    public void waveSent(Base b, double day, net.minecraft.util.RandomSource random) {
        waveSent(b, day, random, 1.0D);
    }

    /** 送り込んだ。間はフェーズの小さな段の倍率で割る(送り込みの強さ = 小さな段。大きいほど頻繁。DirectorGates#strength)。 */
    public void waveSent(Base b, double day, net.minecraft.util.RandomSource random, double stageMultiplier) {
        BaseMemoryTable t = table();
        double interval = t.waveIntervalMinDays + random.nextDouble() * (t.waveIntervalMaxDays - t.waveIntervalMinDays);
        b.nextWaveDay = day + interval / Math.max(0.1D, stageMultiplier);
        b.wavesSent++;
        setDirty();
    }

    /** 確かめ用: 次の送り込みを今にする。 */
    public void waveNow(Base b, double day) {
        b.nextWaveDay = day;
        setDirty();
    }

    // ---------------------------------------------------------------- 見る口

    public List<Base> bases() {
        return bases;
    }

    /** いちばん近い拠点(真ん中まで {@code within} ブロック以内)。無ければ null。 */
    @Nullable
    public Base nearest(double x, double z, double within) {
        Base best = null;
        double bestD = within * within;
        for (Base b : bases) {
            double dx = b.centerX - x, dz = b.centerZ - z;
            double d2 = dx * dx + dz * dz;
            if (d2 <= bestD) {
                bestD = d2;
                best = b;
            }
        }
        return best;
    }

    public int cellCount() {
        return cells.size();
    }

    public long forgottenCount() {
        return forgotten;
    }

    public List<String> describe(double day) {
        List<String> out = new ArrayList<>();
        BaseMemoryTable t = table();
        out.add(String.format(Locale.ROOT, "拠点の記憶: 拠点 %d・覚えている升 %d・これまでに忘れた拠点 %d(覚える点 %.0f。今日 = %.2f 日目)",
                bases.size(), cells.size(), forgotten, t.rememberScore, day));
        for (Base b : bases) {
            double absent = Math.max(0.0D, day - b.lastSeen);
            double forget = b.stage == Stage.RECOGNIZED ? t.forgetRecognizedDays : t.forgetRememberedDays;
            out.add(String.format(Locale.ROOT, "  #%d %s(%.0f, %.0f): 点 %.1f・升 %d・置き物 %d・いない %.2f 日(%.1f 日で減り始め、%.1f 日で忘れる)・この段になって %.2f 日",
                    b.id, b.stage.label, b.centerX, b.centerZ, b.score, b.cells, b.structures, absent, t.decayStartDays, forget,
                    Math.max(0.0D, day - b.stageSince)));
            String scout = b.stage == Stage.SCOUTED
                    ? String.format(Locale.ROOT, "認識まであと %.2f 日", b.daysToRecognize(day)) : "";
            if (b.stage == Stage.RECOGNIZED) {
                double since = Math.max(0.0D, day - b.stageSince);
                BaseMemoryTable.Wave w = t.waveFor(since);
                out.add(String.format(Locale.ROOT, "     送り込み: 段 %d(段階 0 %d・進化体 %d・G4 %d・G5〜 %d)・送った回数 %d・次まで %.2f 日",
                        t.waveIndex(since), w.stageZero(), w.developed(), w.g4(), w.g5plus(), b.wavesSent, Math.max(0.0D, b.nextWaveDay - day)));
            }
            String next = b.stage == Stage.RECOGNIZED ? "" :String.format(Locale.ROOT, "次の偵察まで %.2f 日", Math.max(0.0D, b.nextScoutDay - day));
            out.add(String.format(Locale.ROOT, "     偵察: 眺め終えた %d・倒された %d %s %s", b.scoutsDone, b.scoutKills, scout, next));
            if (b.defense != null) {
                out.add(String.format(Locale.ROOT, "     守り(%.2f 日前に見た): %s", Math.max(0.0D, day - b.defenseAt), BaseDefense.describe(b.defense, b.weakSector)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 確かめ用

    /** 確かめ用: 全部の升と拠点の「最後にいた日」を {@code days} 日だけ前にずらす(いなかったことにする)。 */
    public void pretendAbsent(double days) {
        for (Cell c : cells.values()) c.lastSeen -= days;
        for (Base b : bases) {
            b.lastSeen -= days;
            b.stageSince -= days;
        }
        setDirty();
    }

    /** 確かめ用のコマンド: 段を変える。偵察済みにするなら、認識までの日数も決める。 */
    public void setStage(Base b, Stage s, double day, net.minecraft.util.RandomSource random) {
        if (s == Stage.SCOUTED) {
            b.stage = Stage.REMEMBERED;
            markScouted(b, day, random);
        } else {
            b.setStage(s, day);
            if (s == Stage.RECOGNIZED) b.nextWaveDay = day + table().waveIntervalMinDays * 0.5D;
        }
        setDirty();
        update(day);
    }

    /** テスト用: 拠点の段を進める(偵察・認識は区切り 2・3)。 */
    public void setStageForTests(Base b, Stage s, double day) {
        b.setStage(s, day);
        setDirty();
    }

    /** テスト用: 保存して読み直した記憶。 */
    public BaseMemory roundTripForTests() {
        return load(save(new CompoundTag(), null));
    }

    /** 確かめ用: 全部忘れる。 */
    public void clear() {
        cells.clear();
        bases.clear();
        setDirty();
    }

    // ---------------------------------------------------------------- 保存

    private static BaseMemory load(CompoundTag tag) {
        BaseMemory m = new BaseMemory();
        m.nextId = Math.max(1, tag.getInt("NextId"));
        m.forgotten = tag.getLong("Forgotten");
        for (Tag e : tag.getList("Cells", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            Cell cell = new Cell();
            cell.presence = c.getDouble("P");
            cell.structures = c.getInt("S");
            cell.lastSeen = c.getDouble("L");
            m.cells.put(c.getLong("K"), cell);
        }
        for (Tag e : tag.getList("Bases", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) e;
            Base b = new Base(c.getInt("Id"));
            b.stage = Stage.byName(c.getString("Stage"));
            b.stageSince = c.getDouble("Since");
            b.centerX = c.getDouble("X");
            b.centerZ = c.getDouble("Z");
            b.lastSeen = c.getDouble("L");
            b.recognizeAfter = c.getDouble("RA");
            b.scoutsDone = c.getInt("SD");
            b.scoutKills = c.getInt("SK");
            b.nextScoutDay = c.getDouble("NS");
            b.nextWaveDay = c.getDouble("NW");
            b.wavesSent = c.getInt("WS");
            b.weakSector = c.contains("Weak") ? c.getInt("Weak") : -1;
            b.defenseAt = c.contains("DefAt") ? c.getDouble("DefAt") : Double.NEGATIVE_INFINITY;
            m.bases.add(b);
            m.nextId = Math.max(m.nextId, b.id + 1);
        }
        // 升の段は次の数え直しで付く。それまでの間に忘れすぎないよう、認識した拠点の升は先に付けておく
        for (Base b : m.bases) {
            if (b.stage != Stage.RECOGNIZED) continue;
            for (var en : m.cells.long2ObjectEntrySet()) {
                double dx = ChunkPos.getX(en.getLongKey()) * 16 + 8 - b.centerX, dz = ChunkPos.getZ(en.getLongKey()) * 16 + 8 - b.centerZ;
                if (dx * dx + dz * dz <= 64.0D * 64.0D) en.getValue().stage = Stage.RECOGNIZED;
            }
        }
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        tag.putLong("Forgotten", forgotten);
        ListTag cl = new ListTag();
        for (var en : cells.long2ObjectEntrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("K", en.getLongKey());
            c.putDouble("P", en.getValue().presence);
            c.putInt("S", en.getValue().structures);
            c.putDouble("L", en.getValue().lastSeen);
            cl.add(c);
        }
        tag.put("Cells", cl);
        ListTag bl = new ListTag();
        for (Base b : bases) {
            CompoundTag c = new CompoundTag();
            c.putInt("Id", b.id);
            c.putString("Stage", b.stage.name());
            c.putDouble("Since", b.stageSince);
            c.putDouble("X", b.centerX);
            c.putDouble("Z", b.centerZ);
            c.putDouble("L", b.lastSeen);
            c.putDouble("RA", b.recognizeAfter);
            c.putInt("SD", b.scoutsDone);
            c.putInt("SK", b.scoutKills);
            c.putDouble("NS", b.nextScoutDay);
            c.putDouble("NW", b.nextWaveDay);
            c.putInt("WS", b.wavesSent);
            c.putInt("Weak", b.weakSector);
            c.putDouble("DefAt", b.defenseAt);
            bl.add(c);
        }
        tag.put("Bases", bl);
        return tag;
    }
}
