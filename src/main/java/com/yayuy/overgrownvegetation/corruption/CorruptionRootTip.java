package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 地面・天井から突き出す根の先端(2026-10-09、ユーザー指定)。
 *
 * <p>根のアーチ({@link CorruptionArch}・洞窟の天井のアーチ)が出る瞬間に {@link #SHARE}(7:3 の 3)でこちらに替わる —— 合計の本数は今までの
 * アーチと同じ。地上({@link CorruptionSpreader} の装飾アーチの代わり)は<b>斜めに倒れて地面から突き出し、先が少し反る</b>。
 * 洞窟の天井({@link CaveGrowth} の根のアーチの代わり)は<b>斜めに垂れ下がり、先ほど下を向く</b>。
 *
 * <p>形(ユーザー指定 = Claude の提案どおり): 長さ {@link #MIN_LENGTH}〜{@link #MAX_LENGTH} ブロック、根元の太さ(塊の半径)1〜2(長いほど太い)。
 * 先へ行くほど細くなり、先は 1 マスの原木で終わる(先にフェンスを付けた版は合わなかったのでやめた、10-09 ユーザー判断)。
 * 根元は地面・天井へ少し埋まる(生えている見た目)。
 * 角度・反り・埋まる深さは Claude 仮決め。
 *
 * <p>形は {@link #plan} がワールドに触らずに決める(GameTest が面でつながっているか・先が細いかを直接見る)。
 */
public final class CorruptionRootTip {

    /** アーチの代わりに根の先端にする割合(ユーザー指定「アーチ:根の先端 = 7:3」)。 */
    public static final double SHARE = 0.3D;
    /** 長さ(ブロック、ユーザー指定 = 提案どおり)。 */
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 12;
    /** 根元の太さが 2 になる長さ(これ未満は 1)。仮決め。 */
    private static final int THICK_FROM_LENGTH = 9;
    /** 地上: 地面からの立ち上がりの角度(度)。斜めに倒れた感じ。仮決め。 */
    private static final double GROUND_MIN_ELEVATION = 35.0D;
    private static final double GROUND_MAX_ELEVATION = 60.0D;
    /** 地上: 先までに上へ反る角度(度)。仮決め。 */
    private static final double GROUND_CURL = 15.0D;
    /** 天井: 垂れ下がりの角度(度、下向き)。斜めに垂らす。仮決め。 */
    private static final double HANGING_MIN_ELEVATION = -55.0D;
    private static final double HANGING_MAX_ELEVATION = -30.0D;
    /** 天井: 先までにさらに下を向く角度(度)。仮決め。 */
    private static final double HANGING_DROOP = 25.0D;
    /** 根元を地面・天井へ埋める長さ(ブロック)。この区間は空いているかを問わない。 */
    static final double EMBED = 1.5D;
    /** 経路のうち、埋まっていない区間で塞がっていてよいマスの割合。これを超えたら建てない(家・木を突き抜けない)。 */
    private static final double MAX_BLOCKED_SHARE = 0.2D;

    private CorruptionRootTip() {
    }

    /**
     * 根のマス 1 つ。{@code radius} はその位置に置く塊の半径({@link CorruptionArch#blob} と同じ)、
     * {@code along} は根元から測った経路上の距離(ブロック)。
     */
    public record Cell(int x, int y, int z, int radius, double along) {
    }

    /** 抽選した形(長さ・太さ・向き・反り)。 */
    public record Shape(int length, int baseRadius, double azimuth, double elevation0, double elevation1) {
    }

    /** 地上の根の形を抽選する(斜めに倒れて、先が少し上へ反る)。 */
    public static Shape rollGround(RandomSource random) {
        double el0 = Mth.nextDouble(random, GROUND_MIN_ELEVATION, GROUND_MAX_ELEVATION);
        return roll(random, el0, Math.min(80.0D, el0 + GROUND_CURL));
    }

    /** 天井の根の形を抽選する(斜めに垂れて、先ほど下を向く)。 */
    public static Shape rollHanging(RandomSource random) {
        double el0 = Mth.nextDouble(random, HANGING_MIN_ELEVATION, HANGING_MAX_ELEVATION);
        return roll(random, el0, Math.max(-80.0D, el0 - HANGING_DROOP));
    }

    private static Shape roll(RandomSource random, double el0, double el1) {
        int length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
        int baseRadius = length >= THICK_FROM_LENGTH ? 2 : 1;
        double azimuth = random.nextDouble() * Math.PI * 2.0D;
        return new Shape(length, baseRadius, azimuth, el0, el1);
    }

    /**
     * 形 {@code shape} の根を、{@code (x0, y0, z0)}(根元の中心。地面・天井の中へ {@link #EMBED} 埋めた所)から伸ばしたときのマスの並び。
     * 根元から先へ順に並び、隣どうしは必ず面で接する(斜めに 1 歩進んだら間のマスを足す —— 角だけで接すると隙間に見える)。
     * 太さは根元の {@code baseRadius} から先へ向かって早めに細り、先は 1 マスの原木。
     */
    public static List<Cell> plan(double x0, double y0, double z0, Shape shape) {
        double total = shape.length() + EMBED;
        double step = 0.25D;
        double x = x0;
        double y = y0;
        double z = z0;
        List<Cell> cells = new ArrayList<>();
        int lx = Mth.floor(x);
        int ly = Mth.floor(y);
        int lz = Mth.floor(z);
        cells.add(new Cell(lx, ly, lz, shape.baseRadius(), 0.0D));
        for (double t = step; t <= total + 1.0e-9; t += step) {
            double frac = Math.min(1.0D, t / total);
            double el = Math.toRadians(Mth.lerp(frac, shape.elevation0(), shape.elevation1()));
            x += Math.cos(el) * Math.cos(shape.azimuth()) * step;
            y += Math.sin(el) * step;
            z += Math.cos(el) * Math.sin(shape.azimuth()) * step;
            int cx = Mth.floor(x);
            int cy = Mth.floor(y);
            int cz = Mth.floor(z);
            if (cx == lx && cy == ly && cz == lz) {
                continue;
            }
            // 1 軸ずつ寄せて、面で接するマスだけを足していく(縦 → 横の順。根元側の塊が先に太く出る)。
            while (lx != cx || ly != cy || lz != cz) {
                if (ly != cy) {
                    ly += Integer.signum(cy - ly);
                } else if (lx != cx) {
                    lx += Integer.signum(cx - lx);
                } else {
                    lz += Integer.signum(cz - lz);
                }
                cells.add(cellAt(lx, ly, lz, t, total, shape));
            }
        }
        return cells;
    }

    private static Cell cellAt(int x, int y, int z, double t, double total, Shape shape) {
        // 1.5 乗で早めに細る(根元の太い塊が地面を這う盛り土に見えないように)。
        int radius = (int) Math.round(shape.baseRadius() * Math.pow(Math.max(0.0D, 1.0D - t / total), 1.5D));
        return new Cell(x, y, z, radius, t);
    }

    /**
     * 計画 {@code cells} の、埋まっていない区間({@link #EMBED} より先)で塞がっているマスの割合が {@link #MAX_BLOCKED_SHARE} 以下か。
     * {@code open} がそのマスが空いているか(空気・置き換えられる草など)を答える。家・木・崖を突き抜ける根は建てない。
     */
    public static boolean pathIsOpen(List<Cell> cells, java.util.function.Predicate<BlockPos> open) {
        int checked = 0;
        int blocked = 0;
        for (Cell cell : cells) {
            if (cell.along() <= EMBED + 0.5D) {
                continue;
            }
            checked++;
            if (!open.test(new BlockPos(cell.x(), cell.y(), cell.z()))) {
                blocked++;
            }
        }
        return checked > 0 && blocked <= checked * MAX_BLOCKED_SHARE;
    }

    /**
     * 計画どおりに {@code log} の塊を置く({@link CorruptionArch#blob}。既にある汚染ブロック・破壊できないブロックは上書きしない)。
     *
     * @param shellOut 置いたマス(呼び出し元が表面に苔・植物を添える)
     * @return 1 マスでも置けたら true
     */
    public static boolean place(ServerLevel level, List<Cell> cells, BlockState log, LongOpenHashSet touchedChunks,
                                List<BlockPos> shellOut) {
        boolean any = false;
        for (Cell cell : cells) {
            any |= CorruptionArch.blob(level, cell.x(), cell.y(), cell.z(), cell.radius(), log, touchedChunks, shellOut);
        }
        return any;
    }
}
