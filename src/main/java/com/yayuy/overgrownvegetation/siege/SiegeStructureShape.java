package com.yayuy.overgrownvegetation.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * 砲台・遮蔽盾のように<b>向きが連続的に変わる大きな構造物</b>に、モデルどおりの当たり判定を持たせるための道具。
 *
 * <h2>考え方</h2>
 * <p>ブロックの当たり判定は 1 ブロックの中にしか書けないので、構造物が占める範囲へ
 * {@code SiegeStructurePartBlock}(見えない部品ブロック)を敷き詰める。部品は置いたきり動かさず、
 * <b>各部品が毎回「いまの向きでこのマスのどこが塞がっているか」を計算</b>して返す。こうすると
 * 旋回に合わせて当たり判定も回る(部品ブロックを置き直す必要がない)。
 *
 * <h2>箱の持ち方</h2>
 * <p>モデルの主要な塊を {@link Box}(構造物の中心から見た<b>前・上・右</b>の直方体)で数個だけ書く。
 * 前 = 砲口 / 盾の表の向き、右 = それを向いて右、単位はブロック({@code scale} を掛ける前)。
 * この座標系は {@link SiegeCannonGeometry#offset} と同じなので、モデルの寸法表からそのまま写せる。
 *
 * <h2>敷き詰める範囲({@link #template})</h2>
 * <p>「どの向きになっても絶対に触れないマス」は最初から要らないので、箱の<b>掃引</b>
 * (軸まわりに 1 周させた範囲)と重なるマスだけを部品にする。判定は解析的
 * ({@link #radiusRange} / {@link #cellRadiusRange})で、サンプリングしない。
 */
public final class SiegeStructureShape {

    /** 1 マスを何分割して塞がり具合を調べるか。4 なら 0.25 ブロック刻み。 */
    public static final int RESOLUTION = 4;

    /**
     * 構造物の中心(ブロックの底面中央)から見た直方体。単位はブロックで、拡大率を掛ける前の値。
     *
     * @param minForward 前(砲口 / 盾の表)方向の最小
     * @param maxForward 同・最大
     * @param minUp      上方向の最小(ブロックの底面が 0)
     * @param maxUp      同・最大
     * @param minRight   前を向いて右方向の最小
     * @param maxRight   同・最大
     */
    public record Box(double minForward, double maxForward, double minUp, double maxUp,
                      double minRight, double maxRight) {

        public boolean contains(double forward, double up, double right) {
            return forward >= minForward && forward <= maxForward
                    && up >= minUp && up <= maxUp
                    && right >= minRight && right <= maxRight;
        }
    }

    /**
     * {@code boxes} が占める範囲に敷き詰めるべきマス(構造物の中心からの相対座標)。
     * 「どの向きでも触れない」マスは含めない。
     *
     * @param boxes     構造物の塊(最大まで展開した状態で渡すこと)
     * @param scale     モデルの拡大率
     * @param maxRadius  横方向に見る最大の距離(ブロック)。これを超える部分は部品にしない
     * @param maxUpCells 縦に何段まで部品にするか。高く取るほど置くのに必要な空きが増える
     */
    public static List<BlockPos> template(List<Box> boxes, double scale, int maxRadius, int maxUpCells) {
        List<BlockPos> cells = new ArrayList<>();
        int maxUp = 0;
        for (Box box : boxes) {
            maxUp = Math.max(maxUp, (int) Math.ceil(box.maxUp() * scale));
        }
        maxUp = Math.min(maxUp, maxUpCells);
        for (int dy = 0; dy < maxUp; dy++) {
            for (int dx = -maxRadius; dx <= maxRadius; dx++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    if (sweptOverlaps(boxes, scale, dx, dy, dz)) {
                        cells.add(new BlockPos(dx, dy, dz));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    /** このマスは、箱を軸まわりに 1 周させた範囲と重なるか。 */
    private static boolean sweptOverlaps(List<Box> boxes, double scale, int dx, int dy, int dz) {
        double[] cell = cellRadiusRange(dx, dz);
        for (Box box : boxes) {
            if (dy + 1 <= box.minUp() * scale || dy >= box.maxUp() * scale) {
                continue;
            }
            double[] ring = radiusRange(box);
            if (cell[1] >= ring[0] * scale && cell[0] <= ring[1] * scale) {
                return true;
            }
        }
        return false;
    }

    /** 箱の水平断面(前×右の長方形)の、軸からの距離の最小・最大。 */
    private static double[] radiusRange(Box box) {
        double maxF = Math.max(Math.abs(box.minForward()), Math.abs(box.maxForward()));
        double maxR = Math.max(Math.abs(box.minRight()), Math.abs(box.maxRight()));
        double max = Math.sqrt(maxF * maxF + maxR * maxR);
        // 長方形が軸を含むなら最小は 0、そうでなければ最も近い辺までの距離。
        double nearF = box.minForward() > 0.0D ? box.minForward() : (box.maxForward() < 0.0D ? -box.maxForward() : 0.0D);
        double nearR = box.minRight() > 0.0D ? box.minRight() : (box.maxRight() < 0.0D ? -box.maxRight() : 0.0D);
        return new double[]{Math.sqrt(nearF * nearF + nearR * nearR), max};
    }

    /** マス(1×1 の正方形)の、軸からの距離の最小・最大。軸はブロックの底面中央にある。 */
    private static double[] cellRadiusRange(int dx, int dz) {
        double minX = dx - 0.5D;
        double maxX = dx + 0.5D;
        double minZ = dz - 0.5D;
        double maxZ = dz + 0.5D;
        double nearX = minX > 0.0D ? minX : (maxX < 0.0D ? -maxX : 0.0D);
        double nearZ = minZ > 0.0D ? minZ : (maxZ < 0.0D ? -maxZ : 0.0D);
        double farX = Math.max(Math.abs(minX), Math.abs(maxX));
        double farZ = Math.max(Math.abs(minZ), Math.abs(maxZ));
        return new double[]{Math.sqrt(nearX * nearX + nearZ * nearZ), Math.sqrt(farX * farX + farZ * farZ)};
    }

    /**
     * いまの向きで、{@code cell}(構造物の中心からの相対座標)のうち塞がっている部分の形。
     * マスを {@link #RESOLUTION} 分割して 1 つずつ箱の中かを調べ、X 方向に連続する部分をまとめて箱にする。
     *
     * @param azimuthDeg 構造物の向き(度、0 = 北)
     */
    public static VoxelShape cellShape(List<Box> boxes, double scale, double azimuthDeg, BlockPos cell) {
        double a = Math.toRadians(azimuthDeg);
        double sin = Math.sin(a);
        double cos = Math.cos(a);
        double step = 1.0D / RESOLUTION;
        VoxelShape shape = Shapes.empty();
        for (int j = 0; j < RESOLUTION; j++) {
            double py = cell.getY() + (j + 0.5D) * step;
            for (int k = 0; k < RESOLUTION; k++) {
                double pz = cell.getZ() + (k + 0.5D) * step - 0.5D;
                int runStart = -1;
                for (int i = 0; i <= RESOLUTION; i++) {
                    boolean solid = false;
                    if (i < RESOLUTION) {
                        double px = cell.getX() + (i + 0.5D) * step - 0.5D;
                        solid = occupied(boxes, scale, sin, cos, px, py, pz);
                    }
                    if (solid && runStart < 0) {
                        runStart = i;
                    } else if (!solid && runStart >= 0) {
                        shape = Shapes.or(shape, Shapes.box(runStart * step, j * step, k * step,
                                i * step, (j + 1) * step, (k + 1) * step));
                        runStart = -1;
                    }
                }
            }
        }
        return shape.isEmpty() ? Shapes.empty() : shape.optimize();
    }

    /**
     * 構造物の中心から見た点 {@code (px, py, pz)}(ブロック単位)が、どれかの箱の中にあるか。
     * ワールド座標 → 前/上/右 への変換は {@link SiegeCannonGeometry#offset} の逆。
     */
    private static boolean occupied(List<Box> boxes, double scale, double sin, double cos,
                                    double px, double py, double pz) {
        double x = px / scale;
        double y = py / scale;
        double z = pz / scale;
        double forward = x * sin - z * cos;
        double right = x * cos + z * sin;
        for (Box box : boxes) {
            if (box.contains(forward, y, right)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ワールド座標の点を、構造物の中心から見た「前・上・右」へ直す。
     * 盾のハンドル・ボタンのように「モデルのどこを触ったか」を知りたいときに使う。
     *
     * @return {@code {前, 上, 右}}(ブロック、拡大率で割った後)
     */
    public static double[] toLocal(BlockPos corePos, double azimuthDeg, double scale,
                                   double worldX, double worldY, double worldZ) {
        double a = Math.toRadians(azimuthDeg);
        double x = (worldX - (corePos.getX() + 0.5D)) / scale;
        double y = (worldY - corePos.getY()) / scale;
        double z = (worldZ - (corePos.getZ() + 0.5D)) / scale;
        return new double[]{x * Math.sin(a) - z * Math.cos(a), y, x * Math.cos(a) + z * Math.sin(a)};
    }

    private SiegeStructureShape() {
    }
}
