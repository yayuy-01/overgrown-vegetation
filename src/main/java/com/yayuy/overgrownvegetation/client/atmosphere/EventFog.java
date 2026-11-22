package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * <b>地形と同じ霧の掛かり具合</b>を、このパッケージの外の描画(ワールドイベントの光 {@code client/worldevent/})へ貸す口(2026-11-17)。
 *
 * <p>この MOD は霧の始まりの値に印を入れている({@link MistView} の霞の印。値そのものは −数百万のような数)ので、
 * {@code RenderSystem.getShaderFogStart()} をそのまま線形の霧の式に入れると、全部が霧に沈んだ扱いになる。地形のシェーダーと同じ読み方
 * ({@link MistView#fogAmount}: 森の霞・濃霧・描画距離の端の壁)で出した値を返す。色だけの頂点の足し算の光は、この分だけ弱めれば地形と同じに霧へ沈む。
 */
public final class EventFog {

    private EventFog() {
    }

    /** 距離 {@code distance}(ブロック)の所の光が残る割合(1 = 霧なし、0 = 霧に沈んだ)。描画の段で呼ぶ(地形の霧の値が入っている間)。 */
    public static float keep(float distance) {
        return 1.0F - MistView.fogAmount(distance);
    }
}
