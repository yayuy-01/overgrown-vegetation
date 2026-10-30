package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;

/**
 * 根({@link RaidRootGrowth})が {@code raidRootMaxDistance} まで伸びきったあとの<b>面汚染</b>。
 * レイドビーコン({@link com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity})を中心とした
 * 半径 {@code raidAreaCorruptionRadius}(既定100)の球の中のブロックを、<b>近い順に・同じ距離の中では
 * ランダムに</b>汚染していく(ユーザー要望 2026-09-17)。地表だけでなく地下も対象。
 *
 * <h2>順番の作り方(メモリを食わないように)</h2>
 * <p>半径100の球は約419万マスあり、座標を全部リストに持つと数十MBになる。そこで
 * <b>整数の距離ごとの殻(シェル)1枚ぶんだけ</b>を作業リストに持ち、殻を1枚使い切ったら次の殻を作る。
 * 殻1枚は最大でも約13万マス。殻の中はシャッフルしてから消費するので「近い順・中はランダム」になる。
 * シャッフルは<b>区画(16×16×16)の順番</b>と<b>区画の中</b>の 2 段(2026-09-25、{@link #groupBySection})——
 * 1 tick の変更が数個の区画にまとまり、区画ごとのまとめ送りが効く。殻の中は区画単位の斑点で埋まっていく。
 *
 * <h2>速さ —— 遠いほど同時に汚染する量が増える</h2>
 * <p>ペースは「毎秒◯ブロック」ではなく <b>殻1枚あたりの持ち時間</b>で決める
 * ({@code raidAreaCorruptionSweepMinutes} ÷ 半径。既定なら 20分 ÷ 100 = 1枚あたり12秒)。
 * 殻の広さは距離の2乗で増えるので、<b>ビーコンから離れるほど1 tick に処理するマスが自動で増える</b>
 * (ユーザー要望 2026-09-17:「離れれば離れるほど同時に汚染する量を上げたい、20分程度で塗りきりたい」)。
 * 半径100・20分なら、足元付近が毎秒数十マス、外周付近で毎秒1万マス前後という配分になる。
 *
 * <p>ノルマに数えるのは<b>調べたマス</b>であって汚染できたマスではない —— 球の上半分はたいてい空気で、
 * 「実際に置き換わった数」で数えると空気の多さ次第で所要時間が読めなくなるため。おかげで
 * 「{@code sweepMinutes} で必ず一周し終える」が地形によらず成り立つ。
 * 1 tick の上限は {@code raidAreaCorruptionMaxCellsPerTick} で頭打ちにしてある(安全弁)。
 *
 * <p>シャッフルの種はビーコンの座標から決める({@link #shellSeed})ので、
 * ワールドを読み直しても同じ並びが再現できる —— NBT には「今どの殻の何番目か」だけを保存し、
 * 読み込み時に殻を作り直して途中まで読み飛ばす(並べ方の版 {@link #SHELL_ORDER_VERSION} が違えばその殻は頭から)。
 *
 * <h2>読み込まれていないチャンク(取りこぼしは捨てずに待つ)</h2>
 * <p>半径100はチャンク12枚半ぶんで、レイドが自分で読み込んでおく範囲({@code raidForceLoadRadius} + 2 チャンク、
 * 既定で苗床から約 64 ブロック)の外側を含む。読み込み終わっていないチャンクのマスはその場では飛ばし、
 * そのチャンク(列)を {@link #missedChunks} に覚えておく。
 * 1 周し終えた時点で取りこぼしがあれば、それを {@link #retryChunks} に移して<b>そのどれかが読み込まれるまで待ち</b>
 * ({@value #RETRY_CHECK_TICKS} tick ごとに見る)、読み込まれたら取りこぼしたチャンクの列だけでもう 1 周なぞる。
 * そこでも読み込まれていなかったチャンクは次の取りこぼしになり、また待つ —— 誰も来ない間は待つだけで、
 * 周を使い切って終わることはない。取りこぼしの無い周が終わったら {@link #finished}。
 * (2026-09-25、ユーザー承認「レイドの見直し」の「その外はプレイヤーが近くに来て読み込まれている間だけ進む」を守るため。
 * 以前は 3 周で打ち切っていたので、強制ロードを縮めたあとは、誰も来ないまま約 60 分たつと外周が二度と塗られなかった。)
 *
 * <p>「読み込まれている」は {@code getChunkNow} が返すこと(FULL まで済んでいる)で見る。{@link ServerLevel#isLoaded} は
 * チケットの距離しか見ないので読み込み途中でも true になり、そのまま触るとメインスレッドがそのチャンクを待って止まる
 * —— 拾い直しの周はプレイヤーが近づいて周りを読み込み始めたところで始まるので、ちょうどそれに当たりやすい。
 * 飛ばしても次の周で拾うので、厳しめに見て失うものは無い。
 */
public final class RaidAreaCorruption {

    /**
     * 取りこぼしの読み込み待ちのあいだ、{@link #retryChunks} のどれかが読み込まれたかを見る間隔(tick)。
     * 見るのは高々数百チャンクの {@code getChunkNow} だけなので軽い。仮決め 2026-09-25(5 秒)。
     */
    private static final int RETRY_CHECK_TICKS = 100;

    /**
     * 殻の並べ方の版(NBT {@code order})。保存してある {@link #cursor} は「この並べ方で何番目か」なので、
     * 並べ方を変えたら上げる。版の違うセーブを読んだら、その殻だけ {@code cursor = 0} からなぞり直す
     * —— 続きの番号をそのまま使うと、新しい並びで前の方にあるまだ塗っていないマスを飛ばしてしまう。
     * 塗り済みのマスは {@link CorruptionSpreader#corruptSingle} が即座に空振りするだけなので無害で、
     * その殻の持ち時間(既定で 12 秒)ぶん遅れるだけ。
     * 1 = 殻全体を一様にシャッフル(〜2026-09-24。NBT に版が無いセーブ)、2 = 区画ごとにまとめる(2026-09-25)。
     */
    private static final int SHELL_ORDER_VERSION = 2;

    /** 今なぞっている殻(ビーコンからの整数距離)。0 は「まだ始めていない」。 */
    private int shell;
    /** {@link #shellCells} の何番目まで消費したか。 */
    private int cursor;
    /** 何周目か(0 始まり。1 周目以降は取りこぼしの拾い直し)。 */
    private int pass;
    /**
     * この周で、読み込まれていなかったために飛ばしたマスがあったチャンク({@link ChunkPos#asLong(int, int)})。
     * 周の終わりに {@link #retryChunks} へ移す。NBT {@code missed}。
     */
    private LongOpenHashSet missedChunks = new LongOpenHashSet();
    /**
     * 拾い直しの周でなぞるチャンク(前の周の {@link #missedChunks})。空なら球全体をなぞる(最初の周)。
     * 殻を組み立てるときにこの外の列を入れない({@link #buildShell})—— 保存してあるので、読み直しても同じ殻になり
     * {@link #cursor} がずれない。NBT {@code retry}。
     */
    private LongOpenHashSet retryChunks = new LongOpenHashSet();
    /** 取りこぼした {@link #retryChunks} のどれかが読み込まれるのを待っているか。NBT {@code waiting}。 */
    private boolean awaitingLoad;
    /** 球を汚染し終えたか(取りこぼしの無い周が終わった)。 */
    private boolean finished;
    /** この面汚染がこれまでに実際に置き換えたブロック数(デバッグ・表示用)。 */
    private int corruptedTotal;

    /**
     * 球の中心。最初の tick でビーコンの位置を1度だけ焼き付ける({@code null} は未設定)。
     * 毎回 {@code blockPosition()} を読み直さないのは、{@link #shellSeed} が中心座標から
     * シャッフルの種を作っているため —— 中心が1マスでもずれると殻の並びが変わり、
     * 保存してあった {@link #cursor} が別のマスを指してしまう。
     */
    @Nullable
    private BlockPos center;

    /**
     * 今なぞっている殻のマス(ビーコンからのオフセットを {@link BlockPos#asLong(int, int, int)} で詰めたもの)。
     * NBT には保存せず、{@link #shell} と {@link #cursor} から作り直す。
     */
    private final LongArrayList shellCells = new LongArrayList();
    /** {@link #shellCells} が {@link #shell} のぶんとして作られているか。 */
    private boolean shellReady;

    /**
     * 1 tick ぶんのノルマの端数(足元の殻は 1 tick に 1 マスも回ってこないので、
     * 切り捨てずに持ち越さないと内側がいつまでも進まない)。
     * 1マス未満の端数なので NBT には保存しない。
     */
    private double budget;

    public boolean isFinished() {
        return finished;
    }

    public int getCorruptedTotal() {
        return corruptedTotal;
    }

    /** ビーコンの毎 tick 処理から呼ぶ。根が伸びきっていない間は呼ばないこと。 */
    public void tick(ServerLevel level, BlockPos beaconPos) {
        if (finished) {
            return;
        }
        if (center == null) {
            center = beaconPos.immutable();
        }
        final BlockPos origin = center;
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        final int radius = cfg.raidAreaCorruptionRadius.get();

        if (awaitingLoad) {
            // 取りこぼしたチャンクのどれかが読み込まれるまで待つ(誰も来なければいつまでも待つ。苗床ごとに見る tick をずらす)。
            if (Math.floorMod(level.getGameTime() + origin.asLong(), RETRY_CHECK_TICKS) != 0
                    || !anyRetryChunkReady(level)) {
                return;
            }
            startRetryPass(origin);
        }

        // ノルマは「今の殻の広さ ÷ 殻1枚の持ち時間」。殻が遠いほど広いので、自動で速くなる。
        if (!ensureShell(level, origin, radius)) {
            return; // 塗りきった、または取りこぼしの読み込み待ちに入った
        }
        budget += cellsPerTick(cfg, radius);
        int quota = (int) budget;
        if (quota <= 0) {
            return;
        }
        budget -= quota;
        quota = Math.min(quota, cfg.raidAreaCorruptionMaxCellsPerTick.get());

        int corruptedNow = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 殻の中は区画ごとに固まっているので、続くマスはたいてい同じチャンク。読み込みの確認はチャンクが替わったときだけ。
        long seenChunk = ChunkPos.INVALID_CHUNK_POS;
        boolean seenReady = false;

        while (quota > 0) {
            if (cursor >= shellCells.size() && !ensureShell(level, origin, radius)) {
                break; // 塗りきった、または取りこぼしの読み込み待ちに入った
            }
            long offset = shellCells.getLong(cursor++);
            quota--;
            pos.set(origin.getX() + BlockPos.getX(offset),
                    origin.getY() + BlockPos.getY(offset),
                    origin.getZ() + BlockPos.getZ(offset));
            int cx = SectionPos.blockToSectionCoord(pos.getX());
            int cz = SectionPos.blockToSectionCoord(pos.getZ());
            long chunk = ChunkPos.asLong(cx, cz);
            if (chunk != seenChunk) {
                seenChunk = chunk;
                seenReady = level.getChunkSource().getChunkNow(cx, cz) != null;
            }
            if (!seenReady) {
                missedChunks.add(chunk); // 捨てずに覚えておき、読み込まれたら拾い直す
                continue;
            }
            if (CorruptionSpreader.corruptSingle(level, pos)) {
                corruptedNow++;
            }
        }

        if (corruptedNow > 0) {
            corruptedTotal += corruptedNow;
            CorruptionCounter.record(level, corruptedNow);
        }
    }

    /**
     * この tick に消費してよいマス数(端数込み)。殻1枚に割り当てる時間は全ての殻で等しいので、
     * 広い殻(＝遠い殻)ほど 1 tick あたりの本数が増える。
     */
    private double cellsPerTick(OvergrownVegetationConfig.Common cfg, int radius) {
        long sweepTicks = (long) cfg.raidAreaCorruptionSweepMinutes.get() * 60L * 20L;
        double ticksPerShell = Math.max(1.0D, (double) sweepTicks / Math.max(1, radius));
        return shellCells.size() / ticksPerShell;
    }

    /**
     * 「今すぐ消費できるマスが残っている殻」を用意する。用意できたら true、
     * 球を塗りきっていたら({@link #finished} を立てて)、または周が終わって取りこぼしの読み込み待ちに入ったら false。
     *
     * <p>作り直しが要るのは 2 通り —— 殻を使い切った(次の殻へ)ときと、ワールドを読み直した直後
     * ({@link #shellCells} は保存していないので、保存してあった {@link #shell} のぶんを作り直して
     * {@link #cursor} の続きから再開する)。空の殻に当たったら次へ送る(拾い直しの周では、取りこぼしたチャンクに
     * 掛からない殻は空になる)。
     */
    private boolean ensureShell(ServerLevel level, BlockPos origin, int radius) {
        // 無限ループよけ: 1 周の殻は高々 radius 枚(周の終わりでは必ず false を返すので、周をまたいで回ることは無い)。
        for (int guard = 0; guard <= 2 * radius + 4; guard++) {
            if (shellReady && cursor < shellCells.size()) {
                return true;
            }
            if (!shellReady && shell >= 1 && shell <= radius) {
                buildShell(level, origin, shell);
                shellReady = true;
                cursor = Math.min(cursor, shellCells.size());
                continue;
            }
            if (!advanceShell(level, origin, radius)) {
                return false;
            }
        }
        return false;
    }

    /**
     * 次の殻を用意する。用意できたら true。球を1周し終えて取りこぼしも無ければ {@link #finished} を立てて false。
     * 取りこぼしがあれば、それを {@link #retryChunks} に移して読み込み待ち({@link #awaitingLoad})に入り false
     * —— 以前のように周の数で打ち切らない(誰も来ない間に周を使い切ると、外周を二度と塗らなくなるため)。
     */
    private boolean advanceShell(ServerLevel level, BlockPos origin, int radius) {
        int next = shell + 1;
        if (next > radius) {
            if (missedChunks.isEmpty()) {
                finished = true;
                retryChunks.clear();
                shellCells.clear();
                shellReady = false;
                return false;
            }
            // 読み込まれていなかったチャンクを飛ばした。そのどれかが読み込まれたら、そこだけもう 1 周なぞって拾い直す。
            LongOpenHashSet swap = retryChunks;
            retryChunks = missedChunks;
            missedChunks = swap;
            missedChunks.clear();
            pass++;
            awaitingLoad = true;
            shell = 0;
            cursor = 0;
            shellCells.clear();
            shellReady = false;
            return false;
        }
        shell = next;
        cursor = 0;
        buildShell(level, origin, shell);
        shellReady = true;
        return true;
    }

    /** 取りこぼしたチャンク({@link #retryChunks})のどれかが、いま本当に読み込まれているか。 */
    private boolean anyRetryChunkReady(ServerLevel level) {
        for (LongIterator it = retryChunks.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 読み込み待ちを終えて拾い直しの周を始める。取りこぼしたチャンクのうち苗床にいちばん近いものより内側の殻には
     * 拾い直すマスが無いので、そこまで飛ばす(内側の空の殻を 1 tick に何十枚も組み立てないため)。
     * チャンクの列の中のマスは水平距離が {@code nearest} 以上 = 3 次元の距離も {@code nearest} 以上なので、
     * 殻 {@code nearest} より手前には入らない。
     */
    private void startRetryPass(BlockPos origin) {
        awaitingLoad = false;
        int nearest = Integer.MAX_VALUE;
        for (LongIterator it = retryChunks.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            int minX = SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk));
            int minZ = SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk));
            int dx = Math.max(0, Math.max(minX - origin.getX(), origin.getX() - (minX + 15)));
            int dz = Math.max(0, Math.max(minZ - origin.getZ(), origin.getZ() - (minZ + 15)));
            long d2 = (long) dx * dx + (long) dz * dz;
            nearest = Math.min(nearest, isqrt((int) Math.min(d2, Integer.MAX_VALUE)));
        }
        // 空の殻を「使い切った殻」として置き、次の ensureShell で nearest の殻から組み立てさせる。
        shell = nearest == Integer.MAX_VALUE ? 0 : Math.max(0, nearest - 1);
        cursor = 0;
        shellCells.clear();
        shellReady = true;
    }

    /**
     * ビーコンからの整数距離が {@code r} の殻(= {@code r-1 < 距離 <= r} のマス)を
     * {@link #shellCells} へ組み立て、区画ごとにまとめてシャッフルする({@link #groupBySection})。
     * ワールドの縦の範囲外は最初から入れない。拾い直しの周({@link #retryChunks} が空でない)では、
     * そのチャンクに掛かる列だけを入れる —— 殻 1 枚の持ち時間は変えないので、拾い直しは外周の殻ぶんの時間
     * (既定で殻 1 枚 12 秒)をかけて、最初の周と同じ近い順になぞる。
     *
     * <p>列({@code dx, dz})ごとに「入る {@code dy} の範囲」を直接計算するので、
     * 球の内側を1マスずつ舐め直すことはない(殻の大きさに比例した手間で済む)。
     */
    private void buildShell(ServerLevel level, BlockPos origin, int r) {
        shellCells.clear();
        final int r2 = r * r;
        final int inner2 = (r - 1) * (r - 1);
        final int minY = level.getMinBuildHeight() - origin.getY();
        final int maxY = level.getMaxBuildHeight() - 1 - origin.getY();
        // 拾い直しの周は、取りこぼしたチャンクの列だけを入れる(ほかの列は前の周までに塗り終えている)。
        final boolean retrying = !retryChunks.isEmpty();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h2 = dx * dx + dz * dz;
                if (h2 > r2) {
                    continue;
                }
                if (retrying && !retryChunks.contains(ChunkPos.asLong(
                        SectionPos.blockToSectionCoord(origin.getX() + dx),
                        SectionPos.blockToSectionCoord(origin.getZ() + dz)))) {
                    continue;
                }
                int outer = isqrt(r2 - h2);
                int innerLimit = inner2 - h2;
                if (innerLimit < 0) {
                    // この列は殻の「肩」より外側 — 縦一列まるごとが殻に入る。
                    addRange(dx, dz, -outer, outer, minY, maxY);
                } else {
                    int innerStart = isqrt(innerLimit) + 1;
                    addRange(dx, dz, innerStart, outer, minY, maxY);
                    addRange(dx, dz, -outer, -innerStart, minY, maxY);
                }
            }
        }
        groupBySection(origin, RandomSource.create(shellSeed(origin, r)));
    }

    /**
     * {@link #shellCells} を「区画(16×16×16 のセクション)の順番」と「区画の中の順番」の 2 段で並べ直す
     * (2026-09-25、ユーザー承認の「汚染の見直し」。監査報告 399 / 1086)。
     *
     * <p>以前は殻全体を一様にシャッフルしていたので、1 tick に塗る数百マスが殻の全区画
     * (半径 100 の外周で約 700 区画)へほぼ 1 マスずつ散っていた。サーバーは区画ごとに変更をまとめて送る
     * ({@code ChunkHolder}: 1 区画に 1 マスなら単体の更新、2 マス以上ならその区画ぶんを 1 つのまとめ送り)ので、
     * 散らばるとほぼ 1 マス = 1 パケットになり、クライアントは見えている区画を毎 tick 作り直していた。
     * 区画ごとに固めると、1 tick に触れる区画は数個で済む。
     *
     * <p>変わらないもの: 殻に入るマスの集合(= 1 tick のノルマと 1 周の時間)、「近い順」(殻の順番)、
     * 並びが種だけで決まること(読み直しても同じ並び)。変わるのは殻の中の塗り順だけで、
     * 殻全体に点描のように散る形から、区画ごとの斑点が順に埋まっていく形になる。
     *
     * <p>乱数は「区画の順番」→「並べた順に各区画の中」の順で引く。区画の番号は組み立ての順番
     * ({@link #buildShell} のループは固定)で初出順に振るので、ハッシュの反復順には頼らない。
     */
    private void groupBySection(BlockPos origin, RandomSource random) {
        final int n = shellCells.size();
        if (n <= 1) {
            return;
        }
        // 1) 各マスの区画に初出順の番号を振り、区画ごとのマス数を数える。
        Long2IntOpenHashMap sectionIds = new Long2IntOpenHashMap();
        sectionIds.defaultReturnValue(-1);
        IntArrayList counts = new IntArrayList();
        int[] sectionOf = new int[n];
        for (int i = 0; i < n; i++) {
            long offset = shellCells.getLong(i);
            long section = SectionPos.asLong(
                    SectionPos.blockToSectionCoord(origin.getX() + BlockPos.getX(offset)),
                    SectionPos.blockToSectionCoord(origin.getY() + BlockPos.getY(offset)),
                    SectionPos.blockToSectionCoord(origin.getZ() + BlockPos.getZ(offset)));
            int id = sectionIds.get(section);
            if (id < 0) {
                id = counts.size();
                sectionIds.put(section, id);
                counts.add(0);
            }
            sectionOf[i] = id;
            counts.set(id, counts.getInt(id) + 1);
        }
        final int sections = counts.size();

        // 2) 区画の順番をシャッフルし、並べた順に各区画の書き込み開始位置を決める。
        int[] order = new int[sections];
        for (int s = 0; s < sections; s++) {
            order[s] = s;
        }
        for (int s = sections - 1; s > 0; s--) {
            int j = random.nextInt(s + 1);
            int tmp = order[s];
            order[s] = order[j];
            order[j] = tmp;
        }
        int[] start = new int[sections];
        int at = 0;
        for (int k = 0; k < sections; k++) {
            start[order[k]] = at;
            at += counts.getInt(order[k]);
        }

        // 3) 区画ごとに詰め直す(区画の中は組み立ての順番のまま)。
        int[] fill = start.clone();
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) {
            sorted[fill[sectionOf[i]]++] = shellCells.getLong(i);
        }

        // 4) 並べた順に、各区画の中をシャッフルする。
        for (int k = 0; k < sections; k++) {
            int from = start[order[k]];
            shuffleRange(sorted, from, from + counts.getInt(order[k]), random);
        }
        for (int i = 0; i < n; i++) {
            shellCells.set(i, sorted[i]);
        }
    }

    private void addRange(int dx, int dz, int fromDy, int toDy, int minY, int maxY) {
        int lo = Math.max(fromDy, minY);
        int hi = Math.min(toDy, maxY);
        for (int dy = lo; dy <= hi; dy++) {
            shellCells.add(BlockPos.asLong(dx, dy, dz));
        }
    }

    /** ワールドを読み直しても同じ並びになるよう、ビーコンの座標と殻の番号だけから決める種。 */
    private static long shellSeed(BlockPos origin, int r) {
        return origin.asLong() * 31L + r * 0x9E3779B97F4A7C15L;
    }

    /** {@code cells[from, to)} を Fisher–Yates でシャッフルする。 */
    private static void shuffleRange(long[] cells, int from, int to, RandomSource random) {
        for (int i = to - 1; i > from; i--) {
            int j = from + random.nextInt(i - from + 1);
            long tmp = cells[i];
            cells[i] = cells[j];
            cells[j] = tmp;
        }
    }

    /** {@code n} 以下の最大の平方数の根({@code Math.sqrt} の丸め誤差を潰した整数版)。 */
    private static int isqrt(int n) {
        if (n <= 0) {
            return 0;
        }
        int s = (int) Math.sqrt(n);
        while (s > 0 && s * s > n) {
            s--;
        }
        while ((s + 1) * (s + 1) <= n) {
            s++;
        }
        return s;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("shell", shell);
        tag.putInt("cursor", cursor);
        tag.putInt("pass", pass);
        tag.putLongArray("missed", missedChunks.toLongArray());
        tag.putLongArray("retry", retryChunks.toLongArray());
        tag.putBoolean("waiting", awaitingLoad);
        tag.putBoolean("finished", finished);
        tag.putInt("corruptedTotal", corruptedTotal);
        tag.putInt("order", SHELL_ORDER_VERSION);
        if (center != null) {
            tag.put("center", NbtUtils.writeBlockPos(center));
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        center = tag.contains("center") ? NbtUtils.readBlockPos(tag, "center").orElse(null) : null;
        shell = tag.getInt("shell");
        cursor = tag.getInt("cursor");
        pass = tag.getInt("pass");
        missedChunks.clear();
        retryChunks.clear();
        for (long chunk : tag.getLongArray("missed")) {
            missedChunks.add(chunk);
        }
        for (long chunk : tag.getLongArray("retry")) {
            retryChunks.add(chunk);
        }
        awaitingLoad = tag.getBoolean("waiting") && !retryChunks.isEmpty();
        finished = tag.getBoolean("finished");
        corruptedTotal = tag.getInt("corruptedTotal");
        if (!tag.contains("missed") && tag.getBoolean("skipped") && center != null && !finished) {
            // 2026-09-25 より前のセーブ(取りこぼしの有無だけを覚えていた)。どのチャンクかは分からないので、
            // 球の足もとのチャンクを全部取りこぼし扱いにする(塗り済みのマスは拾い直しで空振りするだけ)。
            // 以前の既定(raidForceLoadRadius 7)は既定の球をまるごと読み込んでいたので、ふつうはここに来ない。
            addFootprint(missedChunks, center, OvergrownVegetationConfig.COMMON.raidAreaCorruptionRadius.get());
        }
        if (tag.getInt("order") != SHELL_ORDER_VERSION) {
            // 並べ方の違う(古い)セーブ。cursor は別の並びの番号なので、今の殻を頭からなぞり直す。
            cursor = 0;
        }
        // 殻そのものは保存していないので、次の tick で shell のぶんを作り直して cursor まで読み飛ばす。
        shellReady = false;
        shellCells.clear();
    }

    /** {@code origin} を中心とした水平半径 {@code radius} の円に掛かるチャンク(の正方形)を {@code into} に足す。 */
    private static void addFootprint(LongOpenHashSet into, BlockPos origin, int radius) {
        int minCx = SectionPos.blockToSectionCoord(origin.getX() - radius);
        int maxCx = SectionPos.blockToSectionCoord(origin.getX() + radius);
        int minCz = SectionPos.blockToSectionCoord(origin.getZ() - radius);
        int maxCz = SectionPos.blockToSectionCoord(origin.getZ() + radius);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                into.add(ChunkPos.asLong(cx, cz));
            }
        }
    }
}
