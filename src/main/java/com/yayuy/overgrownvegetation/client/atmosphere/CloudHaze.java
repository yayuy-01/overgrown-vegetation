package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * <b>雲に掛ける森の霞</b>(2026-11-13。見え方の点検 A「巨大樹の霞の中で、雲だけが霞に掛からず貼り付いて見える」)。
 *
 * <p>森の中の霞は、バニラの霧の始まりに入れた印({@link MistView})を fog.glsl が読んで地形・Mob・粒に掛け、空の丸天井は霧の色で満たす。
 * ボクセルの雲({@code client.sky.VoxelClouds}、11-08)は自前のシェーダーで描くのでこの霞を受けず、黄緑の霞で満ちた空に青白い雲だけがくっきり浮いた。
 * 理由: 濃い霞の中で遠くの雲が透けて見えると、霞の奥行きが嘘になる(空気が汚染を語る、の方針から外れる)。
 *
 * <p>ここは雲のシェーダーへ、霞で満ちた空の色(林冠の陰で落とす前の霧の色 = 丸天井と同じ色)・丸天井を霧で満たしている割合
 * ({@link MistView#cloudWall})・カメラの所の霞の濃さ({@link MistView#cloudK}、1 ブロックあたり)を渡す。距離 d の雲は
 * (1 − 割合) × exp(−k d) だけ残る。森の外・高空(森の度合い 0)ではどちらも 0 で、雲は前のまま。空気遠近・地下の黒い霧は雲には掛けない(前と同じ)。
 */
public final class CloudHaze {

    private CloudHaze() {
    }

    /** 空の丸天井を霞の色で満たしている割合(0〜1。空の色 {@code client.sky.SkyColors} が読む。11-13 仕上げ A)。 */
    public static float wall() {
        return VisualQuality.fog() ? Math.min(MistView.cloudWall(), 1.0F) : 0.0F;
    }

    /** 雲のシェーダーに {@code ForestHaze}・{@code ForestHazeK} を入れる(遠くの雲・近くの雲のどちらの描画でも同じ値)。 */
    public static void set(ShaderInstance shader) {
        float wall = VisualQuality.fog() ? MistView.cloudWall() : 0.0F;
        float k = VisualQuality.fog() ? MistView.cloudK() : 0.0F;
        if (wall <= 0.001F && k <= 0.0F) {
            shader.safeGetUniform("ForestHaze").set(1.0F, 1.0F, 1.0F, 0.0F);
            shader.safeGetUniform("ForestHazeK").set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        // 丸天井と同じ色: 林冠の陰で落とす前の霧の色(今の霧の色は地形用に落としてあることがあるので、読んだら戻す)。
        float[] saved = RenderSystem.getShaderFogColor().clone();
        FogRenderer.levelFogColor();
        float[] c = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("ForestHaze").set(c[0], c[1], c[2], Math.min(wall, 1.0F));
        shader.safeGetUniform("ForestHazeK").set(k, 0.0F, 0.0F, 0.0F);
        RenderSystem.setShaderFogColor(saved[0], saved[1], saved[2], saved[3]);
    }
}
