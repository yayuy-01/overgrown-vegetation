package com.yayuy.overgrownvegetation.raid;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CorruptionSpreader;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 苗床({@link com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity})の足元から地面を這って
 * <b>時間経過で少しずつ伸びていく</b>太い根。巨大樹の根({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature})の
 * うねり・地形追従・枝分かれを、worldgen 時ではなく tick ごとに1歩ずつ進める形に落とし込んだもの。
 *
 * <h2>地形追従(重要)</h2>
 * <p>次に置くかまぼこの高さは、先端そのものの列ではなく <b>数ブロック先の「まだ根が無い」列</b>の地表を
 * 見て決める({@link #probeGroundAhead})。先端の列は直前のかまぼこで覆われていて、そこを読むと
 * 「自分の根の下の地面」を拾ってしまい根が一段ずつ地中へ潜っていく(=途切れて見える原因)。
 * 先読みした地表へ1歩あたり最大 {@value #MAX_STEP_DY} だけ寄せるので、かまぼこどうしは必ず縦に重なり、
 * 崖でも途切れない。
 *
 * <h2>伸びられる距離には上限がある</h2>
 * <p>先端はレイドビーコンから水平距離 {@code raidRootMaxDistance}(既定100)まで来たらその場で止まり、
 * その先端は捨てられる。全ての先端が止まると {@link #isFinished()} が true になり、レイドは
 * 根の伸長から<b>面汚染</b>({@link RaidAreaCorruption})へ切り替わる。
 *
 * <h2>読み込まれていないチャンクの手前では「待つ」(捨てない)</h2>
 * <p>この先のチャンクが読み込まれていない先端は、その場で待つ。{@value #STALL_LIMIT} サイクル続けて進めなかった先端は
 * 「待機中」とみなし、残りの先端が全部待機中なら {@link #isFinished()} を立てて面汚染へ進ませる
 * (一度立てたら戻さない)。待機中の先端も捨てずに持っておき、毎サイクル進めるかだけ見る —— プレイヤーが近くに来て
 * その先が読み込まれたら、止まったところから伸び直す(2026-09-25、ユーザー承認「レイドの見直し」の
 * 「読み込まれている間だけ進む」を守るため。以前は待機中の先端を捨てていたので、強制ロードを縮めたあとは
 * 約 64 ブロックより外の根が、誰も来ないまま 30 分たつと二度と伸びなかった)。
 *
 * <h2>太さ(2つの軸で決まる)</h2>
 * <p>ある1点の太さは <b>ビーコンからの距離</b>で決まる — 足元がいちばん太く、限界距離
 * {@code raidRootMaxDistance} に<b>近づくほど細く</b>なって {@code raidRootThicknessMin}(既定1)に至る
 * ({@link #thicknessAt})。その「足元の太さ」自体も、その根が<b>今まで伸びた全長</b>が
 * {@code raidRootYoungLength}(既定10)〜{@code raidRootMatureLength}(既定50)の間で育つにつれ
 * {@code raidRootThicknessMin}〜{@code raidRootThicknessMax}(既定3)まで太くなっていく
 * ({@link #baseThicknessForLength})——つまり <b>根が伸びるにつれて、既に生えている根本側が
 * 後から太くなっていく</b>。これを実現するため、各先端は苗床から
 * {@code raidRootMatureLength} 前後までの区間で通った点を {@link Tip#baseWaypoints} に覚えておき、
 * 数サイクルおきに({@link #rethickenBase}）その太さを今の全長で計算し直して置き直す
 * (かまぼこは足すだけで削らないので、太さは増える一方)。
 */
public final class RaidRootGrowth {

    /** かまぼこ状の塊をまとめて置くので、光更新を省いた軽い設置(巨大樹の設置キューと同じ)。 */
    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 1歩で地表の高さを寄せる最大量(上下とも)。 */
    private static final int MAX_STEP_DY = 2;
    /** 地表を先読みする距離(ブロック、進行方向へ)。ここは直前のかまぼこの外なので素の地形が読める。 */
    private static final int LOOKAHEAD = 3;
    /** 苗床の中心からこの距離までは根で埋めない(苗床の当たり判定を埋めないため)。 */
    private static final int NEXUS_CLEAR_RADIUS = 3;
    /** 苗床の周りに最初に敷く「根の株」の外径。先端はこの縁から伸び始める。 */
    private static final int CROWN_RADIUS = 6;

    /** 先読みで地表を探す上下範囲。 */
    private static final int SCAN_UP = 6;
    private static final int SCAN_DOWN = 24;

    /** 育つにつれ太くなる「根本」の再計算対象にする範囲(この距離より先はもう再計算しない)。 */
    private static final double RETHICKEN_WINDOW_MULTIPLIER = 1.25D;
    /** {@link #rethickenBase} は毎サイクルではなく、この回数に1回だけ実行する(重い処理なので間引く)。 */
    private static final int RETHICKEN_EVERY_N_GROWTHS = 6;

    /**
     * 未ロードのチャンクに阻まれて何サイクル進めなかったら、先端を「待機中」とみなすか
     * (既定の {@code raidRootGrowthIntervalTicks}=60 で約 30 分)。レイドが自分で読み込んでおくのは
     * 苗床のチャンクから {@code raidForceLoadRadius} + 2 チャンクまで(既定 2 → 4 チャンク ≒ 64 ブロック。
     * 強制ロードは周囲 2 チャンクも読み込む。2026-09-25 に既定を 7 から下げた)なので、
     * {@code raidRootMaxDistance}(既定100)まで伸ばすには途中からプレイヤーが近くに要る。
     * 待機中の先端しか残っていないのに {@link #isFinished()} を待たせると、誰も来ない間は
     * 面汚染({@link RaidAreaCorruption})へいつまでも切り替わらないので、ここで区切る。
     * 待機中の先端は捨てない(2026-09-25。以前はここで捨てていたので、外周の根が二度と伸びなかった)。
     */
    private static final int STALL_LIMIT = 600;

    /**
     * 1 歩進める前に「本当に読み込み終わっている」ことを確かめる幅(ブロック、先端の列から水平に)に足す分。
     * 実際の幅は {@code raidRootThicknessMax} + これ(既定 3 + 5 = 8。上限 15)で、かまぼこの半径・
     * 先読み {@value #LOOKAHEAD}・汚染の起点を置く脇(先端の半径 + 最大 5、{@link #corruptionNodePos})が収まる。
     * 仮決め 2026-09-25。
     */
    private static final int READY_MARGIN_EXTRA = 5;

    /**
     * 根本側の「育つにつれて太くなる」再計算のために覚えておく、通過済みの1点。
     * {@code dist} はその点が置かれた時点の(不変の)苗床からの距離 — 再計算のたびに
     * 「今の」全長({@link Tip}の現在位置までの距離)と組み合わせて {@link #thicknessAt} に渡す。
     * 再起動を挟むと消える(NBT には保存しない)——次に伸びたタイミングで自然に復帰していく、
     * 見た目上のごく軽い割り切り。
     */
    private static final class Waypoint {
        final int x;
        final int y;
        final int z;
        final double dist;

        Waypoint(int x, int y, int z, double dist) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dist = dist;
        }
    }

    private static final class Tip {
        double x;
        double z;
        int y;
        int prevY;
        double heading;
        int step;
        int sinceNode;
        int lastBranchStep = -100;
        int lastRad = 1;
        double waveFreq;
        double wavePhase;
        /**
         * 続けて「進めなかった」({@link #stepTip} が false を返した = この先のチャンクがまだ読み込まれていない)回数。
         * 1歩でも進めたら 0 に戻る。{@value #STALL_LIMIT} で頭打ちにし、そこに達した先端は待機中
         * ({@link #isParked()})—— 捨てずに毎サイクル進めるかだけ見る。待機中の先端しか残っていなければ
         * {@link #isFinished()} が立つ(待たせ続けると、誰も居ない方角へ伸びた先端のせいで面汚染へ切り替わらない)。
         * NBT に保存する(2026-09-25〜。読み直すたびに 30 分の猶予をやり直すと、再起動の多いサーバーで面汚染が始まらない)。
         */
        int stalled;
        final List<Waypoint> baseWaypoints = new ArrayList<>();

        /** 読み込まれていないチャンクの手前で {@value #STALL_LIMIT} サイクル以上待っている先端か。 */
        boolean isParked() {
            return stalled >= STALL_LIMIT;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", x);
            t.putDouble("z", z);
            t.putInt("y", y);
            t.putInt("prevY", prevY);
            t.putDouble("heading", heading);
            t.putInt("step", step);
            t.putInt("sinceNode", sinceNode);
            t.putInt("lastBranchStep", lastBranchStep);
            t.putDouble("waveFreq", waveFreq);
            t.putDouble("wavePhase", wavePhase);
            t.putInt("stalled", stalled);
            return t;
        }

        static Tip load(CompoundTag t) {
            Tip tip = new Tip();
            tip.x = t.getDouble("x");
            tip.z = t.getDouble("z");
            tip.y = t.getInt("y");
            tip.prevY = t.contains("prevY") ? t.getInt("prevY") : tip.y;
            tip.heading = t.getDouble("heading");
            tip.step = t.getInt("step");
            tip.sinceNode = t.getInt("sinceNode");
            tip.lastBranchStep = t.contains("lastBranchStep") ? t.getInt("lastBranchStep") : -100;
            tip.waveFreq = t.getDouble("waveFreq");
            tip.wavePhase = t.getDouble("wavePhase");
            tip.stalled = Mth.clamp(t.getInt("stalled"), 0, STALL_LIMIT); // 古いセーブは 0(猶予をやり直す)
            return tip;
        }
    }

    private final List<Tip> tips = new ArrayList<>();
    private boolean initialized;
    private int rethickenCounter;

    /**
     * 残りの先端が全部待機中({@link Tip#isParked()})になった、または 1 本も無くなったことがあるか。
     * 一度立てたら戻さない(NBT {@code settled})—— 待機中の先端がプレイヤーの接近で伸び直しても、
     * 始まった面汚染を止めないため。
     */
    private boolean settled;

    /**
     * 根の伸長が一段落したか = 面汚染({@link RaidAreaCorruption})へ切り替えてよいか。
     * 株を敷いたあと({@code initialized})に、全ての先端が {@code raidRootMaxDistance} まで到達して止まったか、
     * 残りの先端が全部、読み込まれていないチャンクの手前で待機中になった(一度そうなったら以後ずっと true)。
     * {@link com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity} はこれを見て面汚染を始める。
     * 待機中の先端がまだ残っていることがあるので、{@link #grow} は {@link #isFullyGrown()} まで呼び続けること。
     */
    public boolean isFinished() {
        return initialized && (settled || tips.isEmpty());
    }

    /**
     * 全ての先端が {@code raidRootMaxDistance} まで伸びきって無くなった(待機中の先端も残っていない)か。
     * これが立つまで {@link #grow} を呼び続ける —— 待機中の先端は、その先が読み込まれたら伸び直す。
     */
    public boolean isFullyGrown() {
        return initialized && tips.isEmpty();
    }

    /**
     * 全ての根の先端を1歩ずつ進める。新しく「汚染の起点」にすべき位置ができたら返す
     * (無ければ空リスト)。1回の呼び出しにつき各先端は最大1ブロックしか進まない。
     * 先端が {@code raidRootMaxDistance} に達したらその先端はそこで止まる(捨てられる)。
     */
    public List<BlockPos> grow(ServerLevel level, BlockPos nexusOrigin, RandomSource random) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        List<BlockPos> nodes = new ArrayList<>();

        BlockState rootState = ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();

        if (!initialized) {
            if (!level.isAreaLoaded(nexusOrigin, CROWN_RADIUS + 4)
                    || !chunksReady(level, nexusOrigin.getX(), nexusOrigin.getZ(), CROWN_RADIUS + 4)) {
                // 周辺チャンクが揃うまで待つ。開始時に同期で読むのは苗床のチャンクだけで、周りは裏で読み込み中のことがある
                // (isAreaLoaded はチケットしか見ないので、読み込み途中のチャンクへ株を敷くとメインスレッドが待つ)。
                return nodes;
            }
            initialized = true;
            buildCrown(level, nexusOrigin, rootState, cfg.raidRootThicknessMax.get(), random);
            spawnInitialTips(level, nexusOrigin, random, cfg);
            // 株の周りをいくつか汚染の起点にする。
            for (int i = 0; i < 4; i++) {
                double a = Math.PI * 2.0D * i / 4.0D;
                int nx = nexusOrigin.getX() + (int) Math.round(Math.cos(a) * (CROWN_RADIUS + 2));
                int nz = nexusOrigin.getZ() + (int) Math.round(Math.sin(a) * (CROWN_RADIUS + 2));
                int gy = scanGroundY(level, nx, nexusOrigin.getY(), nz, true);
                if (gy != Integer.MIN_VALUE) {
                    nodes.add(new BlockPos(nx, gy + 1, nz));
                }
            }
        }

        int maxTips = cfg.raidRootMaxTips.get();
        double branchChance = cfg.raidRootBranchChance.get();
        int branchCooldown = cfg.raidRootBranchCooldownSteps.get();
        int nodeSpacing = cfg.raidRootNodeSpacing.get();
        double maxDistance = cfg.raidRootMaxDistance.get();

        List<Tip> newborn = new ArrayList<>();
        for (var it = tips.iterator(); it.hasNext(); ) {
            Tip tip = it.next();
            // 限界距離まで来た先端はその場で止める。捨てる前に根本側の太さだけは今の全長で
            // 決め直しておく(この先この先端は二度と rethickenBase に掛からないため)。
            if (horizontalDistance(nexusOrigin, tip.x, tip.z) >= maxDistance) {
                rethickenBase(level, nexusOrigin, tip, rootState, cfg);
                it.remove();
                continue;
            }
            if (!stepTip(level, nexusOrigin, tip, rootState, cfg)) {
                // 進めなかった = この先がまだ読み込まれていない。捨てずに待つ(長く続いたら待機中。STALL_LIMIT 参照)。
                // 根本の太さは止まってからも 6 サイクルごとに決め直してあるので、待機に入るときに改めて置き直す必要は無い。
                if (tip.stalled < STALL_LIMIT) {
                    tip.stalled++;
                }
                continue;
            }
            tip.stalled = 0;
            tip.step++;
            tip.sinceNode++;

            if (tip.sinceNode >= nodeSpacing) {
                tip.sinceNode = 0;
                BlockPos nodePos = corruptionNodePos(level, tip, random);
                if (nodePos != null) {
                    nodes.add(nodePos);
                }
            }

            if (tips.size() + newborn.size() < maxTips && tip.step > 6
                    && tip.step - tip.lastBranchStep >= branchCooldown
                    && random.nextDouble() < branchChance) {
                tip.lastBranchStep = tip.step;
                double sign = random.nextBoolean() ? 1.0D : -1.0D;
                double turn = sign * (Math.PI / 7.0D + random.nextDouble() * (Math.PI / 3.0D));
                Tip child = new Tip();
                child.x = tip.x;
                child.z = tip.z;
                child.y = tip.y;
                child.prevY = tip.y;
                child.heading = tip.heading + turn;
                child.waveFreq = 0.10D + random.nextDouble() * 0.14D;
                child.wavePhase = random.nextDouble() * Math.PI * 2.0D;
                newborn.add(child);
                tip.heading -= sign * (Math.abs(turn) * 0.4D);
            }
        }
        tips.addAll(newborn);

        // 残りの先端が全部待機中(または 1 本も無い)なら、面汚染へ切り替えてよい。一度立てたら戻さない。
        if (!settled && tips.stream().allMatch(Tip::isParked)) {
            settled = true;
        }

        // 根本側の「育つにつれて太くなる」再計算は重いので間引く(呼び出しのたびにやらない)。
        // 待機中の先端は全長が変わらず、太さも決め直し済みなので飛ばす(以前は捨てていたので、掛けていなかったのと同じ)。
        rethickenCounter++;
        if (rethickenCounter >= RETHICKEN_EVERY_N_GROWTHS) {
            rethickenCounter = 0;
            for (Tip tip : tips) {
                if (!tip.isParked()) {
                    rethickenBase(level, nexusOrigin, tip, rootState, cfg);
                }
            }
        }

        return nodes;
    }

    /**
     * {@link Tip#baseWaypoints} に覚えている、根本寄りの通過済みの点を、その根の「今の」全長を使って
     * 太さを計算し直し、置き直す(太くなる一方— {@link #fillRootMound} は足すだけで削らない)。
     */
    private void rethickenBase(ServerLevel level, BlockPos nexusOrigin, Tip tip, BlockState rootState,
                               OvergrownVegetationConfig.Common cfg) {
        if (tip.baseWaypoints.isEmpty()) {
            return;
        }
        double tipLength = horizontalDistance(nexusOrigin, tip.x, tip.z);
        for (Waypoint wp : tip.baseWaypoints) {
            int rad = thicknessAt(cfg, wp.dist, tipLength);
            if (rad <= 1) {
                continue; // 最小の太さのままなら、生えた時点で既にその太さで置いてあるので再設置不要。
            }
            if (level.isLoaded(new BlockPos(wp.x, wp.y, wp.z))) {
                fillRootMound(level, wp.x, wp.y, wp.z, rad, rootState);
            }
        }
    }

    /**
     * 根本(苗床付近)が、その根の全長 {@code tipLength} に応じて育ちきる太さ(半径)。
     * {@code raidRootYoungLength} 以下ではまだ {@code raidRootThicknessMin}、
     * {@code raidRootMatureLength} 以上で {@code raidRootThicknessMax} に達する(線形補間)。
     */
    private static double baseThicknessForLength(OvergrownVegetationConfig.Common cfg, double tipLength) {
        double minLen = cfg.raidRootYoungLength.get();
        double maxLen = cfg.raidRootMatureLength.get();
        double minThickness = cfg.raidRootThicknessMin.get();
        double maxThickness = cfg.raidRootThicknessMax.get();
        double t = maxLen > minLen ? Mth.clamp((tipLength - minLen) / (maxLen - minLen), 0.0D, 1.0D) : 1.0D;
        return minThickness + (maxThickness - minThickness) * t;
    }

    /**
     * (ビーコンからの水平距離){@code dist} の1点の、その根の全長 {@code tipLength} における太さ(半径)。
     *
     * <p>細り方の基準は「その時点の先端まで」ではなく <b>伸びられる限界距離</b>
     * {@code raidRootMaxDistance}(既定100) —— 足元({@code dist}=0)では
     * {@link #baseThicknessForLength} の値、限界距離では {@code raidRootThicknessMin} になるよう
     * 線形補間する。つまり<b>限界に近づくほど根が細くなる</b>(ユーザー要望 2026-09-17)。
     * 以前は {@code tipLength} を分母にしていたので、根がどれだけ伸びても「先端だけが常に最小の太さ」で、
     * 途中の太さは根が伸びるたびに後から太っていくだけだった。
     */
    private static int thicknessAt(OvergrownVegetationConfig.Common cfg, double dist, double tipLength) {
        double baseNow = baseThicknessForLength(cfg, Math.max(tipLength, 1.0D));
        double tipThickness = cfg.raidRootThicknessMin.get();
        double maxDistance = Math.max(1.0D, cfg.raidRootMaxDistance.get());
        double t = Mth.clamp(dist / maxDistance, 0.0D, 1.0D);
        double raw = baseNow + (tipThickness - baseNow) * t;
        return Math.max(1, (int) Math.round(raw));
    }

    /** {@code origin} の中心から {@code (x, z)} までの水平距離(根の長さ・限界距離はすべてこの尺度)。 */
    private static double horizontalDistance(BlockPos origin, double x, double z) {
        double dx = x - (origin.getX() + 0.5D);
        double dz = z - (origin.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 苗床の周りに、中心を空けた「根の株」(かまぼこのリング)を敷く。先端はこの縁から生える。 */
    /**
     * 苗床の周りに、根が地面から生えてきたように見える緩やかな盛り上がりを敷く
     * (以前はバットレス状に段差のはっきりした「台座」を作っていたが、苗床のすぐ縁で
     * 高さが急に切り替わり「スパッと切れている」ように見えていた)。中心に近いほど高い
     * なだらかな丸いドーム(かまぼこ根と同じ丸い断面の考え方)にし、外側は縁がギザギザに
     * なるよう半径・高さの両方に軽くノイズを掛けて、地形にとけ込む輪郭にする。
     */
    private void buildCrown(ServerLevel level, BlockPos nexus, BlockState rootState, int baseThickness,
                            RandomSource random) {
        int nearY = scanGroundY(level, nexus.getX(), nexus.getZ(), nexus.getY(), true);
        if (nearY == Integer.MIN_VALUE) {
            nearY = nexus.getY() - 1;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -CROWN_RADIUS - 1; dx <= CROWN_RADIUS + 1; dx++) {
            for (int dz = -CROWN_RADIUS - 1; dz <= CROWN_RADIUS + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                // 縁をギザギザにする: 半径そのものに個体差(位置ごとの疑似ノイズ)を持たせる。
                double edgeJitter = (random.nextDouble() - 0.5D) * 1.6D;
                double effectiveRadius = CROWN_RADIUS + edgeJitter;
                if (d > effectiveRadius) {
                    continue;
                }
                int gy = scanGroundY(level, nexus.getX() + dx, nearY, nexus.getZ() + dz, true);
                if (gy == Integer.MIN_VALUE) {
                    gy = nearY;
                }
                // 中心に近いほど高い、なだらかなドーム(2乗カーブで外側ほど急速にゼロへ)。
                // 以前のような「1+」の下駄を履かせず、外周では自然にほぼ0になる。
                double t = Mth.clamp(d / CROWN_RADIUS, 0.0D, 1.5D);
                double bump = baseThickness * 0.55D * (1.0D - t * t) + (random.nextDouble() - 0.5D) * 0.6D;
                int topDy = (int) Math.round(bump);
                if (d < NEXUS_CLEAR_RADIUS) {
                    continue; // 苗床自身の当たり判定は埋めない
                }
                if (topDy < 0) {
                    continue; // 外周のノイズでマイナスになったら何も置かない(地形そのまま)
                }
                for (int dy = -1; dy <= topDy; dy++) {
                    p.set(nexus.getX() + dx, gy + dy, nexus.getZ() + dz);
                    if (level.isLoaded(p)) {
                        level.setBlock(p, rootState, UPDATE_FLAGS);
                    }
                }
            }
        }
    }

    private void spawnInitialTips(ServerLevel level, BlockPos nexus, RandomSource random,
                                  OvergrownVegetationConfig.Common cfg) {
        int count = cfg.raidRootCount.get();
        for (int i = 0; i < count; i++) {
            double heading = Math.PI * 2.0D * i / count + (random.nextDouble() - 0.5D) * 0.6D;
            double sx = nexus.getX() + 0.5D + Math.cos(heading) * CROWN_RADIUS;
            double sz = nexus.getZ() + 0.5D + Math.sin(heading) * CROWN_RADIUS;
            int gy = scanGroundY(level, Mth.floor(sx), nexus.getY(), Mth.floor(sz), true);
            if (gy == Integer.MIN_VALUE) {
                gy = nexus.getY() - 1;
            }
            Tip tip = new Tip();
            tip.x = sx;
            tip.z = sz;
            tip.y = gy;
            tip.prevY = gy;
            tip.heading = heading;
            tip.waveFreq = 0.09D + random.nextDouble() * 0.12D;
            tip.wavePhase = random.nextDouble() * Math.PI * 2.0D;
            tips.add(tip);
        }
    }

    /**
     * 先端を1ブロック前進させて根を置く。進めた場合 true。行き先の周り({@link #chunksReady})のチャンクが
     * まだ読み込み終わっていなければ何も置かずに false。
     */
    private boolean stepTip(ServerLevel level, BlockPos nexusOrigin, Tip tip, BlockState rootState,
                            OvergrownVegetationConfig.Common cfg) {
        // 外向きへゆるく寄せ直してから、うねりを加えて1マス進む。
        double radialOut = Math.atan2(tip.z - (nexusOrigin.getZ() + 0.5D), tip.x - (nexusOrigin.getX() + 0.5D));
        double toOut = Math.atan2(Math.sin(radialOut - tip.heading), Math.cos(radialOut - tip.heading));
        tip.heading += 0.04D * toOut;
        double dirX = Math.cos(tip.heading);
        double dirZ = Math.sin(tip.heading);

        double wobble = Math.sin(tip.wavePhase + tip.step * tip.waveFreq);
        double nx = tip.x + dirX + (-dirZ) * wobble * 0.3D;
        double nz = tip.z + dirZ + dirX * wobble * 0.3D;
        int ix = Mth.floor(nx);
        int iz = Mth.floor(nz);
        int readyMargin = Math.min(15, cfg.raidRootThicknessMax.get() + READY_MARGIN_EXTRA);
        if (!level.isLoaded(new BlockPos(ix, tip.y, iz)) || !chunksReady(level, ix, iz, readyMargin)) {
            return false;
        }

        // 数ブロック先(まだ根が無い列)の地表を見て、そこへ1歩あたり最大 MAX_STEP_DY だけ寄せる。
        int ahead = probeGroundAhead(level, nx, nz, dirX, dirZ, tip.y);
        int groundY = ahead == Integer.MIN_VALUE
                ? tip.y - 1
                : Mth.clamp(ahead, tip.y - MAX_STEP_DY, tip.y + MAX_STEP_DY);

        // この根の「今の」全長(ビーコンからの水平距離)。新しく置くのはまさに先端なので dist==tipLength。
        // 限界距離 raidRootMaxDistance に近いほど thicknessAt が細い値を返す。
        double dist = horizontalDistance(nexusOrigin, nx, nz);
        int rad = thicknessAt(cfg, dist, dist);
        tip.lastRad = rad;

        // 前のかまぼこと高さが変わったら、縦につなぐ柱を入れてから今のかまぼこを置く(段差でも途切れない)。
        if (tip.prevY != groundY) {
            int lo = Math.min(tip.prevY, groundY);
            int hi = Math.max(tip.prevY, groundY);
            fillColumn(level, ix, iz, lo - 1, hi + 1, rad, rootState);
        }
        fillRootMound(level, ix, groundY, iz, rad, rootState);

        // 置いたかまぼこの盛り上がりを、汚染された地面と同じように苔むして見せる
        // (巨大樹の根と同じ処理。水中なら水草・藻、地上ならシダ・苔・蔦)。
        CorruptionSpreader.growTopUndergrowth(level, ix, groundY, iz,
                ModBlocks.GIANT_OVERGROWN_LOG.get(),
                cfg.giantTreeRootPlantChance.get());

        // 根本寄り(raidRootMatureLength 程度まで)のこの点は、後で根がさらに伸びたときに
        // 太さを計算し直す対象として覚えておく({@link #rethickenBase}）。
        double rethickenWindow = cfg.raidRootMatureLength.get() * RETHICKEN_WINDOW_MULTIPLIER;
        if (dist <= rethickenWindow) {
            tip.baseWaypoints.add(new Waypoint(ix, groundY, iz, dist));
        }

        tip.prevY = groundY;
        tip.x = nx;
        tip.z = nz;
        tip.y = groundY;
        return true;
    }

    /**
     * {@code (x, z)} から水平 {@code margin} ブロック以内に掛かるチャンクが、すべて本当に読み込み終わっているか
     * ({@code getChunkNow} が返す = FULL まで済んでいる)。{@link ServerLevel#isLoaded} はチケットの距離しか見ないので、
     * 読み込み・生成の途中でも true になり、そのまま getBlockState / setBlock するとメインスレッドがそのチャンクを待って止まる。
     * 待機中の先端はプレイヤーが近づいて周りを読み込み始めたところで伸び直すので、その途中のチャンクへ踏み込まないよう
     * ここで確かめる(2026-09-25)。{@code margin} は 15 以下なので、見るチャンクは各軸 3 枚まで。
     */
    private static boolean chunksReady(ServerLevel level, int x, int z, int margin) {
        int minCx = SectionPos.blockToSectionCoord(x - margin);
        int maxCx = SectionPos.blockToSectionCoord(x + margin);
        int minCz = SectionPos.blockToSectionCoord(z - margin);
        int maxCz = SectionPos.blockToSectionCoord(z + margin);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * {@code (fx, fz)} から進行方向 {@code (dirX, dirZ)} へ {@value #LOOKAHEAD} ブロック先の列の地表 Y。
     * その列はまだ根で覆われていないので素の地形が読める。見つからなければ {@link Integer#MIN_VALUE}。
     */
    private static int probeGroundAhead(ServerLevel level, double fx, double fz, double dirX, double dirZ, int nearY) {
        int px = Mth.floor(fx + dirX * LOOKAHEAD);
        int pz = Mth.floor(fz + dirZ * LOOKAHEAD);
        return scanGroundY(level, px, nearY, pz, false);
    }

    /**
     * 汚染の起点にする位置を、根の先端の<b>すぐ脇</b>(進行方向に対して直角、かまぼこの外)に取る。
     * 起点の真下が丸太だと {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader#seedGround}
     * が足がかりを作れず汚染が始まらないため。左右・距離を何段階か試し、見つからなければ {@code null}。
     */
    @Nullable
    private static BlockPos corruptionNodePos(ServerLevel level, Tip tip, RandomSource random) {
        double perpX = -Math.sin(tip.heading);
        double perpZ = Math.cos(tip.heading);
        int firstSide = random.nextBoolean() ? 1 : -1;
        for (int side : new int[] {firstSide, -firstSide}) {
            for (int extra = 2; extra <= 5; extra++) {
                int off = tip.lastRad + extra;
                int x = Mth.floor(tip.x + perpX * side * off);
                int z = Mth.floor(tip.z + perpZ * side * off);
                if (!level.isLoaded(new BlockPos(x, tip.y, z))) {
                    continue;
                }
                int gy = scanGroundY(level, x, tip.y, z, false);
                if (gy == Integer.MIN_VALUE) {
                    continue;
                }
                return new BlockPos(x, gy + 1, z);
            }
        }
        return null;
    }

    /** {@code (cx, cz)} 列を {@code fromY}〜{@code toY} まで、水平半径 {@code radius} の丸い断面で埋める。 */
    private static void fillColumn(ServerLevel level, int cx, int cz, int fromY, int toY, int radius, BlockState state) {
        double edgeSq = (radius + 0.35D) * (radius + 0.35D);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = fromY; y <= toY; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if ((double) (dx * dx + dz * dz) > edgeSq) {
                        continue;
                    }
                    p.set(cx + dx, y, cz + dz);
                    if (level.isLoaded(p)) {
                        level.setBlock(p, state, UPDATE_FLAGS);
                    }
                }
            }
        }
    }

    /**
     * {@code (cx, cz)} の地表 {@code gy} に、かまぼこ状の盛り上がりを置く({@link com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature}
     * の {@code fillRootMound} と同じ形)。下端は {@code gy - 1}、上端は円弧({@code gy + √(radius² - 水平距離²)})
     * まで — 地中に潜らず地上へ露出する。
     */
    private static void fillRootMound(ServerLevel level, int cx, int gy, int cz, int radius, BlockState state) {
        double edgeSq = (radius + 0.5D) * (radius + 0.5D);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizSq = dx * dx + dz * dz;
                if (horizSq > edgeSq) {
                    continue;
                }
                int topDy = (int) Math.round(Math.sqrt(Math.max(0.0D, edgeSq - horizSq)));
                for (int dy = -1; dy <= topDy; dy++) {
                    p.set(cx + dx, gy + dy, cz + dz);
                    // 既に同じ状態なら置かない(2026-09-24 軽量化、報告 1108)。rethickenBase は 360 tick ごとに
                    // 根本の全点を置き直すが、太さが頭打ちになったあとはほぼ全マスが同じ状態の上書きだった。
                    // 同じ状態の setBlock はもともと LevelChunk#setBlockState が null を返して何も起きないので、
                    // 結果は同じ(プレイヤーが削ったマスは今までどおり埋め直される)。
                    if (level.isLoaded(p) && level.getBlockState(p) != state) {
                        level.setBlock(p, state, UPDATE_FLAGS);
                    }
                }
            }
        }
    }

    /**
     * {@code (x, z)} 列で、{@code near}({@code +}{@value #SCAN_UP} 〜 {@code -}{@value #SCAN_DOWN})の範囲の
     * 一番高い「地面らしいブロック」の Y。葉・草などは飛ばす。{@code passRoots} が true のときは原木も
     * 貫通する(株を敷くとき・脇の起点探しで自分の根の下の地面を拾いたい場合)。見つからなければ
     * {@link Integer#MIN_VALUE}。
     */
    private static int scanGroundY(ServerLevel level, int x, int near, int z, boolean passRoots) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = near + SCAN_UP; y >= near - SCAN_DOWN; y--) {
            p.set(x, y, z);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            if (s.is(BlockTags.LEAVES) || s.is(ModBlocks.OVERGROWN_LEAVES.get())) {
                continue;
            }
            boolean isRoot = s.is(ModBlocks.GIANT_OVERGROWN_LOG.get()) || s.is(ModBlocks.OVERGROWN_LOG.get())
                    || s.is(BlockTags.LOGS);
            if (isRoot && passRoots) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("initialized", initialized);
        tag.putBoolean("settled", settled);
        ListTag list = new ListTag();
        for (Tip tip : tips) {
            list.add(tip.save());
        }
        tag.put("tips", list);
        return tag;
    }

    public void load(CompoundTag tag) {
        tips.clear();
        initialized = tag.getBoolean("initialized");
        ListTag list = tag.getList("tips", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            tips.add(Tip.load(list.getCompound(i)));
        }
        // 古いセーブ(settled が無い)は、先端が残っていれば次の grow で決め直す(先端が無ければ isFinished は tips から立つ)。
        settled = tag.getBoolean("settled");
    }
}
