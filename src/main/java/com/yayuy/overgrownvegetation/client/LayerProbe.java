package com.yayuy.overgrownvegetation.client;

/**
 * 葉の層(cutout_mipped)の描画を測る口(2026-10-24)。開発用の {@code devbridge/LeafLayerProbe} が {@link #hook} を入れたときだけ動き、ふだんは何もしない。
 * 呼ぶのは {@code mixin/client/LevelRendererFastChunkDrawMixin}(バニラの描き方のときだけ。Sodium では呼ばれない)。
 */
public final class LayerProbe {
    public interface Hook {
        /** 層の描画の始め。{@code redraw} = 測るための描き直しの回。 */
        void begin(boolean redraw);

        /** 層の描画の状態(奥行きの試し・書き込み)を整えた直後。ここで GL の状態を測る用に替えてよい(層の終わりに元へ戻る)。 */
        void afterSetup(boolean redraw);

        void end(boolean redraw);

        /** 層を描き終えた後、同じ層をもう 1 回描くか。 */
        boolean wantsRedraw();
    }

    public static volatile Hook hook;

    private LayerProbe() {
    }
}
