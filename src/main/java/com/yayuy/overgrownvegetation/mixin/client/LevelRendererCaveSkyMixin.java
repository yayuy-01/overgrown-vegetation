package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.CaveMist;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地下の霧の中では、まだ描かれていない範囲(読み込み中のチャンクなど)から見える背景を霧の色にする(2026-10-16 ユーザー指摘「大きな洞窟でよくある、
 * 読み込まれていない範囲が奈落や空と同じ描画になって、霧に穴が開いているように見える」)。
 *
 * <p>背景はバニラでは空の色の半球と、目が地平の高さより下のときの真っ黒な下半分(奈落の板)。地形の霧は背景に掛からないので、洞窟の中では霧の向こうの
 * 描かれていない所が黒い穴になった。地下の霧が出ている間({@link CaveMist#hideVoid})は奈落の板を描かず、空の光がまったく届かない閉じた洞窟
 * ({@link CaveMist#hideSky})では空も描かない。背景は霧の色(画面を消す色)になり、霧の色は {@link CaveMist#fogColor} へ寄せてある。
 * 洞窟の口・天井の穴の近く(空の光が届く所)では空は今まで通り描く。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCaveSkyMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$noSkyInClosedCave(CallbackInfo ci) {
        // 雲の中に深く入っている間も空(太陽・月・星)を描かない(背景は霧の色。CloudInterior、2026-10-17)。
        if (CaveMist.hideSky() || com.yayuy.overgrownvegetation.client.CloudInterior.hideSky()) {
            ci.cancel();
        }
    }

    /** 雲も空と一緒に描かない(雲は空と別に描かれ、描かれていない範囲から雲がのぞいた)。 */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$noCloudsInClosedCave(CallbackInfo ci) {
        if (CaveMist.hideSky()) {
            ci.cancel();
        }
    }

    /** 奈落の板を描くかを決める「目の高さ − 地平の高さ」を、地下の霧の中では 0 以上にして描かせない。 */
    @ModifyVariable(method = "renderSky", at = @At("STORE"), ordinal = 0)
    private double overgrown_vegetation$noVoidPlaneInCaveMist(double belowHorizon) {
        return CaveMist.hideVoid() ? Math.max(belowHorizon, 0.0) : belowHorizon;
    }
}
