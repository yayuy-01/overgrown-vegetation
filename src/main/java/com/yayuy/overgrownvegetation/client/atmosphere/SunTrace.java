package com.yayuy.overgrownvegetation.client.atmosphere;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 地面から太陽の方へ線をたどって、<b>葉を何枚・樹冠をいくつ通るか</b>を数える所(2026-10-30 作り直し。元は parked の {@code CanopyLight} の
 * {@code traceSun}・樹冠の数え方・地面の探し方)。光の筋・木漏れ日・地面の影が同じたどり方を使う(別々に書くと、斜めの光が真上の空いた列で
 * 「空へ抜けた」ことになり隣の木を素通りした)。描画のスレッドだけが使う(直近の結果を静的な値で返す)。
 *
 * <p>過繁茂の葉はバニラの葉のタグを持たないので、葉かどうかは形で見る(当たり判定があって不透明でない)。
 *
 * <h2>樹冠の数え方</h2>
 * 葉のブロックの数のままでは、厚い樹冠を 1 つ通るだけで木漏れ日が消える。線が通る<b>葉の塊(間に {@link #CROWN_GAP} ブロック以上の空気をはさんだ
 * 葉の続き)</b>を 1 層と数え、厚い塊は {@link #CROWN_DEPTH} ブロックごとに 1 層足す(2026-10-16 ユーザー選択「葉を樹冠の数で数える」)。数値は仮決め。
 *
 * <h2>高い林冠</h2>
 * 巨大樹の傘(地面から 100 ブロック以上上)はどの列にも葉が何枚も重なり、ブロック単位では隙間が無い。高い所の葉の影ほど地面ではぼやけて薄くなる
 * ので、最初の葉が高いほど葉を少なく数える({@link #highCanopyAllowance}・{@link #highCanopyShaftAllowance}。2026-10-15 夜、ユーザー「巨大樹の下では発生しない」)。
 */
public final class SunTrace {

    /** 0.5 ブロック刻みの歩数の上限(360 ブロック。巨大樹の傘まで届くように)。 */
    private static final int TRACE_STEPS = 720;
    static final double CROWN_GAP = 1.5;
    static final int CROWN_DEPTH = 3;

    /** 直近の {@link #traceSun} が通った樹冠の数(厚さで重み付け)。 */
    static int lastCrowns;
    /** 直近の {@link #traceSun} で最初に通った葉の、出発点からの高さ(ブロック。葉を通らなかったら NaN)。 */
    static double lastFirstLeafRise = Double.NaN;
    private static int crownRun;
    private static int crownLastStep;

    /**
     * (x, y, z) から太陽の方へたどる。空へ抜けたら通り抜けた葉の枚数(0 = じかに日が当たる)、不透明なブロックに当たったら
     * −1 − それまでの葉の枚数(−1 = 葉を通らずに幹・崖に当たった)。
     * 空へ抜けたとみなすのは、今いる列の一番上より上で、かつ出発点より 30 ブロック以上高いとき(斜めの光は真上が空いていても先の樹冠に入る)。
     */
    static int traceSun(ClientLevel level, double x, double y, double z, Vec3 sun) {
        lastFirstLeafRise = Double.NaN;
        lastCrowns = 0;
        crownRun = 0;
        crownLastStep = Integer.MIN_VALUE;
        if (sun.y < 0.02) {
            return -1;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long last = Long.MIN_VALUE;
        long lastColumn = Long.MIN_VALUE;
        int columnTop = 0;
        long lastSection = Long.MIN_VALUE;
        boolean sectionAir = false;
        int leaves = 0;
        double startY = y;
        int maxY = level.getMaxBuildHeight();
        for (int step = 0; step < TRACE_STEPS; step++) {
            x += sun.x * 0.5;
            y += sun.y * 0.5;
            z += sun.z * 0.5;
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            if (by >= maxY) {
                closeCrown();
                return leaves;
            }
            long column = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (column != lastColumn) {
                lastColumn = column;
                columnTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            }
            if (by >= columnTop) {
                if (y - startY >= 30.0) {
                    closeCrown();
                    return leaves;
                }
                continue;
            }
            long here = BlockPos.asLong(bx, by, bz);
            if (here == last) {
                continue;
            }
            last = here;
            long section = SectionPos.asLong(bx >> 4, by >> 4, bz >> 4);
            if (section != lastSection) {
                lastSection = section;
                sectionAir = sectionIsAir(level, bx, by, bz);
            }
            if (sectionAir) {
                continue;
            }
            pos.set(bx, by, bz);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            if (state.canOcclude()) {
                closeCrown();
                return -1 - leaves;
            }
            if (leaves == 0) {
                lastFirstLeafRise = y - startY;
            }
            leaves = Math.min(leaves + 1, 64);
            countCrown(step);
        }
        closeCrown();
        return leaves;
    }

    private static void countCrown(int step) {
        if (crownRun > 0 && (step - crownLastStep) * 0.5 > CROWN_GAP) {
            closeCrown();
        }
        crownRun++;
        crownLastStep = step;
    }

    private static void closeCrown() {
        if (crownRun > 0) {
            lastCrowns += crownWeight(crownRun);
            crownRun = 0;
        }
    }

    /** 厚さ {@code blocks} の葉の塊の層の数({@link #CROWN_DEPTH} ごとに 1)。 */
    static int crownWeight(int blocks) {
        return (blocks + CROWN_DEPTH - 1) / CROWN_DEPTH;
    }

    /** 高い林冠の葉を薄く数える分(枚)。最初の葉が地面から 32 ブロックで 0、14 ブロックごとに 1 枚ずつ、6 枚まで。木漏れ日に使う。 */
    static int highCanopyAllowance() {
        double rise = lastFirstLeafRise;
        if (Double.isNaN(rise) || rise < 32.0) {
            return 0;
        }
        return (int) Math.min(6, Math.round((rise - 32.0) / 14.0));
    }

    /** 光の筋として、高い林冠の薄い所を「穴」とみなす葉の枚数の上限(最初の葉が 48 ブロックより上のときだけ。1〜4 枚)。 */
    static int highCanopyShaftAllowance() {
        double rise = lastFirstLeafRise;
        if (Double.isNaN(rise) || rise < 48.0) {
            return 0;
        }
        return (int) Math.min(4, 1 + Math.round((rise - 48.0) / 20.0));
    }

    /**
     * 列 (x, z) の地面の上面の高さ。一番上から下へ、空気・当たり判定の無い植物・葉のようなブロックを通り抜け、最初の不透明な
     * 当たり判定のあるブロックの上面(幹・枝の上のこともある)。64 ブロック下りても無ければ・水なら NaN。
     * 列の一番上が {@code refY} より 40 ブロック以上上(巨大樹の傘の下)なら、{@code refY} の 40 ブロック上から探す。
     */
    static float groundAt(ClientLevel level, int x, int z, int refY) {
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, refY + 40);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y > top - 64; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                return Float.NaN;
            }
            if (state.isAir() || !state.canOcclude()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                continue;
            }
            return y + (float) shape.max(Direction.Axis.Y);
        }
        return Float.NaN;
    }

    /**
     * プレイヤーの高さの近く(上 3〜下 12)で、上が空いている(当たり判定の無い草・空気)地面の上面の高さ。葉の上(林冠)は地面にしない。
     * 見つからなければ NaN。木漏れ日と地面の影に使う。
     */
    static float findGround(ClientLevel level, int x, int playerY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, playerY + 3, z);
        boolean openAbove = false;
        for (int y = playerY + 3; y >= playerY - 12; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                openAbove = true;
                continue;
            }
            if (openAbove && state.canOcclude()) {
                return y + (float) shape.max(Direction.Axis.Y);
            }
            openAbove = false;
        }
        return Float.NaN;
    }

    /**
     * 地面 (x, y, z) のマスの中心から太陽の方へたどり、通る葉の層(樹冠の数)。不透明なブロックに当たる・{@code maxLayers} 層を超えたら −1、
     * 何も通らずに空へ抜けたら 0。高い林冠の葉は薄く数える。
     */
    static int leafLayersOnSunPath(ClientLevel level, int x, float y, int z, Vec3 sun, int maxLayers) {
        if (sun.y < 0.05) {
            return -1;
        }
        int trace = traceSun(level, x + 0.5, y + 0.05, z + 0.5, sun);
        if (trace <= 0) {
            return trace < 0 ? -1 : 0;
        }
        int layers = lastCrowns;
        if (layers <= maxLayers) {
            return Math.max(1, layers);
        }
        int extra = highCanopyAllowance();
        return trace <= maxLayers + extra ? Math.max(1, trace - extra) : -1;
    }

    /** 葉のようなブロック(当たり判定はあるが不透明でない)。 */
    static boolean isLeafLike(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canOcclude() && state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** (x, y, z) を含む区画(16×16×16)が空気だけか。読み込まれていない所・高さの外も空気とみなす。 */
    public static boolean sectionIsAir(ClientLevel level, int x, int y, int z) {
        int index = level.getSectionIndex(y);
        if (index < 0 || index >= level.getSectionsCount()) {
            return true;
        }
        return level.getChunk(x >> 4, z >> 4).getSection(index).hasOnlyAir();
    }

    /** 箱(両端を含む)が、空気だけの区画にすっかり収まっているか。 */
    static boolean boxIsAir(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
            for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    if (!sectionIsAir(level, sx << 4, sy << 4, sz << 4)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private SunTrace() {
    }
}
