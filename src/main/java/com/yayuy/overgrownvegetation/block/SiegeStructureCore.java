package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.siege.SiegeStructureShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 砲台・遮蔽盾のように「モデルが 1 ブロックに収まらず、しかも向きが変わる」構造物の<b>本体</b>。
 * まわりに敷いた {@link SiegeStructurePartBlock} が、いまの向きでのマスごとの当たり判定をここへ訊く。
 *
 * <p>形は毎フレーム作り直すには重いので、<b>向き(と展開の進み)を粗く量子化した鍵</b>ごとに
 * 作り置きする({@link #structureShapes})。旋回は速くても 1.2 度/tick なので、
 * {@link #SHAPE_STEP_DEGREES} 刻みなら数 tick に 1 回しか作り直さない。
 * 作り直すときも、全マスではなく訊かれたマスだけを作る(2026-09-24 軽量化)。
 */
public interface SiegeStructureCore {

    /** 形を作り直す向きの刻み(度)。細かすぎると毎 tick 作り直しになる。 */
    double SHAPE_STEP_DEGREES = 5.0D;

    /** この構造物の本体ブロックの位置。 */
    BlockPos structurePos();

    /** 本体のある world(クライアント・サーバーそれぞれの)。 */
    Level structureLevel();

    /** 敷き詰めるマス(本体からの相対座標)。 */
    List<BlockPos> structureTemplate();

    /** いまの形を決める塊(展開などで変わる場合はその状態を反映して返すこと)。 */
    List<SiegeStructureShape.Box> structureBoxes();

    /** モデルの拡大率。 */
    double structureScale();

    /** いまの向き(度、0 = 北)。 */
    double structureAzimuth();

    /** 形の作り置き。{@link #structureShapes} が入れ替える。 */
    ShapeCache structureShapeCache();

    /**
     * {@code cell}(本体からの相対座標)のうち、いま塞がっている部分の形。
     * 部品ブロックと本体ブロックの {@code getShape} / {@code getCollisionShape} から呼ばれる。
     */
    default VoxelShape structureShapes(BlockPos cell) {
        ShapeCache cache = structureShapeCache();
        long key = shapeKey();
        ShapeCache.Generation generation = cache.generation;
        if (generation == null || generation.key != key) {
            // 2026-09-24 軽量化(監査 1296): 鍵が変わった瞬間の塊・倍率・向きだけを控え、マスの形は
            // 訊かれたマスだけその控えから作る。前は鍵が変わるたびに全マスをまとめて作っていた。
            // 控えた値はまとめて作っていたときと同じ瞬間のものなので、どのマスの形も前と同じになる。
            generation = new ShapeCache.Generation(key, structureBoxes(), structureScale(), structureAzimuth(),
                    structureTemplate());
            cache.generation = generation;
        }
        return generation.shape(cell);
    }

    /**
     * 作り置きの鍵。向き(と展開などの状態)を粗く量子化したもの。
     * 展開が進む構造物はこれを上書きして進み具合も混ぜること。
     */
    default long shapeKey() {
        return Math.round(structureAzimuth() / SHAPE_STEP_DEGREES);
    }

    /**
     * 形の作り置き入れ物。ライトエンジンなど別スレッドから {@code getShape} が来ても壊れないよう、
     * 鍵が変わったら{@link Generation 世代}を丸ごと差し替える(古い世代は書き換えない)。
     */
    final class ShapeCache {
        volatile Generation generation;

        /**
         * 1 つの鍵ぶんの形。作り直すと決めた瞬間の塊・倍率・向きを控えておき、マスの形は訊かれたときに
         * 控えから作って覚える(2026-09-24 軽量化、監査 1296)。形の表は並行して引かれても壊れない
         * {@link ConcurrentHashMap}。同じマスを 2 つのスレッドが同時に作っても、同じ控えから作るので中身は同じ。
         */
        static final class Generation {
            final long key;
            private final List<SiegeStructureShape.Box> boxes;
            private final double scale;
            private final double azimuth;
            private final List<BlockPos> template;
            private final Map<BlockPos, VoxelShape> shapes = new ConcurrentHashMap<>();

            Generation(long key, List<SiegeStructureShape.Box> boxes, double scale, double azimuth,
                       List<BlockPos> template) {
                this.key = key;
                this.boxes = boxes;
                this.scale = scale;
                this.azimuth = azimuth;
                this.template = template;
            }

            /** {@code cell} の形。敷き詰めるマス({@code template})の外は空。 */
            VoxelShape shape(BlockPos cell) {
                VoxelShape shape = shapes.get(cell);
                if (shape == null) {
                    shape = template.contains(cell)
                            ? SiegeStructureShape.cellShape(boxes, scale, azimuth, cell)
                            : Shapes.empty();
                    VoxelShape raced = shapes.putIfAbsent(cell.immutable(), shape);
                    if (raced != null) {
                        shape = raced;
                    }
                }
                return shape;
            }
        }
    }
}
