package com.yayuy.overgrownvegetation.organ;

/**
 * 器官を組み立てるブロック 1 つぶん(2026-09-23)。座標は<b>節からの相対</b>。
 *
 * <p>{@link Part} が何を置くかを決める。置き換え先のブロックを
 * ここで持たないのは、形を決めるところ({@code organ/shape})を
 * <b>レジストリから切り離して</b>おくため —— プレビュー用の道具からも、
 * ゲームを起動せずに同じ計算を回せる。
 */
public record OrganCell(int x, int y, int z, OrganCell.Part part) {

    public enum Part {
        /** 器官の蔦。三つ編みの筋を作る主材。 */
        VINE,
        /** 汎用の汚染ブロック。蔦に混ぜて編み目の色を散らす。 */
        OVERGROWN,
        /** 表面に点在する腺。触ると害がある。先端のものが胞子を噴く。 */
        GLAND
    }
}
