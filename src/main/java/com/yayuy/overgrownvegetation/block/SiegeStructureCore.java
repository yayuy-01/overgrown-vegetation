package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.siege.SiegeStructureShape;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 砲台・遮蔽盾のように「モデルが 1 ブロックに収まらず、しかも向きが変わる」構造物の<b>本体</b>。
 * まわりに敷いた {@link SiegeStructurePartBlock} が、いまの向きでのマスごとの当たり判定をここへ訊く。
 *
 * <p>形は毎フレーム作り直すには重いので、<b>向き(と展開の進み)を粗く量子化した鍵</b>でまとめて
 * 作り置きする({@link #structureShapes})。旋回は速くても 1.2 度/tick なので、
 * {@link #SHAPE_STEP_DEGREES} 刻みなら数 tick に 1 回しか作り直さない。
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
        Map<BlockPos, VoxelShape> shapes = cache.shapes;
        if (cache.key != key || shapes == null) {
            Map<BlockPos, VoxelShape> built = new HashMap<>();
            List<SiegeStructureShape.Box> boxes = structureBoxes();
            double scale = structureScale();
            double azimuth = structureAzimuth();
            for (BlockPos offset : structureTemplate()) {
                VoxelShape shape = SiegeStructureShape.cellShape(boxes, scale, azimuth, offset);
                if (!shape.isEmpty()) {
                    built.put(offset, shape);
                }
            }
            shapes = built;
            cache.shapes = built;
            cache.key = key;
        }
        return shapes.getOrDefault(cell, Shapes.empty());
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
     * 中身は作り直したものを丸ごと差し替える(書き換えない)。
     */
    final class ShapeCache {
        volatile long key = Long.MIN_VALUE;
        volatile Map<BlockPos, VoxelShape> shapes;
    }
}
