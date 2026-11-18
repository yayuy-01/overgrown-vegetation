package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.registry.ModBiomes;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.DebugScreenOverlay;

/**
 * F3 デバッグ画面の左側「Biome: 〜」行を、繁茂系バイオーム
 * ({@link ModBiomes#isOvergrownLike} に相当する 4 種 —— 過繁茂・繁茂 × 陸・海)の
 * ときだけ緑({@code #2C470A})で描画する。
 *
 * <p>{@code renderLines} 内の {@code drawString} 呼び出しを差し替え、描画対象の文字列が
 * 該当バイオームの行と一致した場合だけ色引数を上書きする。行の内容は
 * {@code "Biome: " + biome.unwrapKey().location()}(例: {@code "Biome: overgrown_vegetation:overgrown"})
 * なので、{@link ModBiomes} のキーから組み立てた文字列と完全一致で判定する。
 */
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenBiomeColorMixin {

    /** {@code #1B8200}(不透明)。 */
    private static final int OVERGROWN_BIOME_COLOR = 0xFF1B8200;

    /** 緑で描画すべき「Biome: 〜」行の全文一覧。 */
    private static final Set<String> OVERGROWN_BIOME_LINES = ModBiomes.ALL.stream()
            .map(key -> "Biome: " + key.location())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    // 見直し 第 2 回 ④5(11-18): @Redirect → @WrapOperation・require = 0(ほかの MOD と重なっても落ちない。当たらなければ色付けだけ無くなる)。
    @WrapOperation(
            method = "renderLines",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
    private int overgrownvegetation$colorOvergrownBiomeLine(
            GuiGraphics guiGraphics, Font font, String text, int x, int y, int color, boolean dropShadow, Operation<Integer> original) {
        if (text != null && OVERGROWN_BIOME_LINES.contains(text)) {
            color = OVERGROWN_BIOME_COLOR;
        }
        return original.call(guiGraphics, font, text, x, y, color, dropShadow);
    }
}
