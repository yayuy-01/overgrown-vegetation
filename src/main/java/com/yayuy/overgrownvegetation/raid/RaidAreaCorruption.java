package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.phase.CorruptionCounter;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

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
 * 読み込み時に殻を作り直して途中まで読み飛ばす。
 *
 * <h2>未ロードのチャンク</h2>
 * <p>半径100はチャンク12枚半ぶんで、レイドの強制ロード({@code raidForceLoadRadius})の外側が
 * 含まれうる。未ロードのマスはその場では飛ばし、「飛ばしたマスがあった」ことだけを覚えておく。
 * 球を1周し終えた時点で取りこぼしがあれば、もう一度先頭から周回して埋めにいく
 * ({@value #MAX_PASSES} 周まで)。既に汚染済みのマスは即座に空振りするだけなので、
 * 2周目以降は実質「取りこぼしの拾い直し」になる。
 */
public final class RaidAreaCorruption {

    /** 取りこぼし(未ロード)があったときに球をもう一度なぞる最大周回数。 */
    private static final int MAX_PASSES = 3;

    /** 今なぞっている殻(ビーコンからの整数距離)。0 は「まだ始めていない」。 */
    private int shell;
    /** {@link #shellCells} の何番目まで消費したか。 */
    private int cursor;
    /** 何周目か(0 始まり)。 */
    private int pass;
    /** この周回で未ロードのために飛ばしたマスがあったか。 */
    private boolean skipped;
    /** 球を汚染し終えたか。 */
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

        // ノルマは「今の殻の広さ ÷ 殻1枚の持ち時間」。殻が遠いほど広いので、自動で速くなる。
        if (!ensureShell(level, origin, radius)) {
            return; // 塗りきった
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

        while (quota > 0) {
            if (cursor >= shellCells.size() && !ensureShell(level, origin, radius)) {
                break; // 塗りきった
            }
            long offset = shellCells.getLong(cursor++);
            quota--;
            pos.set(origin.getX() + BlockPos.getX(offset),
                    origin.getY() + BlockPos.getY(offset),
                    origin.getZ() + BlockPos.getZ(offset));
            if (!level.isLoaded(pos)) {
                skipped = true;
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
     * 球を塗りきっていたら({@link #finished} を立てて)false。
     *
     * <p>作り直しが要るのは 2 通り —— 殻を使い切った(次の殻へ)ときと、ワールドを読み直した直後
     * ({@link #shellCells} は保存していないので、保存してあった {@link #shell} のぶんを作り直して
     * {@link #cursor} の続きから再開する)。空の殻に当たったら次へ送る。
     */
    private boolean ensureShell(ServerLevel level, BlockPos origin, int radius) {
        // 無限ループよけ: 殻は高々 radius 枚 × MAX_PASSES 周しかない。
        for (int guard = 0; guard <= radius * MAX_PASSES + 4; guard++) {
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
     * 次の殻を用意する。用意できたら true。球を1周し終えて、取りこぼしも無い(or 周回上限に達した)
     * 場合は {@link #finished} を立てて false を返す。取りこぼしがあれば先頭の殻へ戻して true を返す。
     */
    private boolean advanceShell(ServerLevel level, BlockPos origin, int radius) {
        int next = shell + 1;
        if (next > radius) {
            if (skipped && pass + 1 < MAX_PASSES) {
                // 未ロードで取りこぼしたマスがあった。もう一度先頭からなぞって埋めにいく。
                pass++;
                skipped = false;
                shell = 0;
                cursor = 0;
                shellReady = false;
                return true;
            }
            finished = true;
            return false;
        }
        shell = next;
        cursor = 0;
        buildShell(level, origin, shell);
        shellReady = true;
        return true;
    }

    /**
     * ビーコンからの整数距離が {@code r} の殻(= {@code r-1 < 距離 <= r} のマス)を
     * {@link #shellCells} へ組み立て、シャッフルする。ワールドの縦の範囲外は最初から入れない。
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

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int h2 = dx * dx + dz * dz;
                if (h2 > r2) {
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
        shuffle(shellCells, RandomSource.create(shellSeed(origin, r)));
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

    private static void shuffle(LongArrayList list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long tmp = list.getLong(i);
            list.set(i, list.getLong(j));
            list.set(j, tmp);
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
        tag.putBoolean("skipped", skipped);
        tag.putBoolean("finished", finished);
        tag.putInt("corruptedTotal", corruptedTotal);
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
        skipped = tag.getBoolean("skipped");
        finished = tag.getBoolean("finished");
        corruptedTotal = tag.getInt("corruptedTotal");
        // 殻そのものは保存していないので、次の tick で shell のぶんを作り直して cursor まで読み飛ばす。
        shellReady = false;
        shellCells.clear();
    }
}
