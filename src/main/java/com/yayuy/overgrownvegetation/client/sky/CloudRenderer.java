package com.yayuy.overgrownvegetation.client.sky;

import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

/**
 * 雲の描き方の差し替え口(2026-11-08 雲の段階 1「将来のリアルな雲への備え」)。雲の形・量は {@code sky.CloudField} だけが正本で、
 * 描き方はこれを実装したもの 1 つが受け持つ。今の実装は {@link VoxelClouds}(ボクセルの雲)だけ。設定で「リアルな(ボリュームの)雲」を
 * 選べるようにするときは、同じ場を読んで描く実装を足し、{@link CloudRenderers#current()} で選ぶ(ユーザーの 11-08 の希望。まだ作らない)。
 * 雲の影({@code client.atmosphere.CloudShadows})と薄明光線は描き方に依らず場から作るので、描き方を替えても同じ影になる。
 */
public interface CloudRenderer {

    /** 今描いているか(設定・シェーダー・バニラの雲の設定)。false なら雲の影・薄明光線も出さない。 */
    boolean active();

    /** バニラの雲の代わりに描く({@code renderClouds} から)。false ならバニラの雲を描かせる。 */
    boolean render(ClientLevel level, float partialTick, Matrix4f modelView, Matrix4f projection, double camX, double camY, double camZ);
}
