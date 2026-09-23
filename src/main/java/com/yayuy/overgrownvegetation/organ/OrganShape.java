package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.organ.shape.SporeGlandTower;

import java.util.List;

/**
 * <b>種類 → 姿</b>(2026-09-23)。
 *
 * <p>器官の形は種類ごとに違う(要望)ので、生やす側({@link OrganPlacer})も
 * 枯らす側({@link OrganWither})も<b>ここしか見ない</b>。
 * 新しい器官を足すときに触るのは {@link OrganKind} とこの分岐だけ。
 *
 * <h2>形は関数であって、保存しない</h2>
 * <p>組み上がった器官のブロック一覧は<b>どこにも保存していない</b> ——
 * 節の位置とワールドの種から<b>毎回同じ形が再計算できる</b>ので、
 * 枯らすときも同じ関数を呼び直すだけで済む。
 * 台帳が抱えるのは節 1 つぶん(位置と種類と生えた時刻)だけ。
 */
public final class OrganShape {

    private OrganShape() {
    }

    /**
     * その節から生える器官を組み立てる。座標は<b>節からの相対</b>。
     *
     * @param seed ワールドの種。同じ節なら何度呼んでも同じ形が返る
     */
    public static List<OrganCell> build(OrganKind kind, long seed, int nodeX, int nodeY, int nodeZ) {
        return switch (kind) {
            case SPORE_GLAND -> SporeGlandTower.build(seed, nodeX, nodeY, nodeZ);
        };
    }
}
