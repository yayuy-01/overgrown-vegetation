package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.yayuy.overgrownvegetation.client.CorruptionAtmosphere;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 極相({@code engulfed})バイオームでは、<b>真昼でも夜と同じ暗さ</b>にする(2026-09-22)。
 * 林冠が閉じて林床に日が差さない、という極相林の絵をそのまま出すための処理。
 *
 * <p>{@code LightTexture#updateLightTexture} は毎クライアント tick にライトマップ(16×16)を作り直す。
 * その先頭で読む {@code clientlevel.getSkyDarken(1.0F)} が<b>空の光の強さ</b>(真昼 1.0 / 夜 0.2)で、
 * この 1 つの値がライトマップの空側の明るさと色の両方を決めている。ここを差し替えるだけで、
 * <b>ブロック光(松明・グロウベリー)はそのまま</b>に空の光だけを落とせる ——
 * 明かりを置けばそこだけ普通に明るい、という形になる。
 *
 * <p>ライトマップは {@code DimensionType#ambientLight} のような<b>次元単位</b>の仕組みしか
 * バニラには無く、バイオーム単位で暗さを変える口が無い。NeoForge にもライトマップのイベントは
 * 無いので、ここは mixin しか手が無い。
 *
 * <p>{@code @ModifyExpressionValue}(MixinExtras)を使っているのは、{@code @Redirect} と違って
 * <b>同じ場所に手を入れる他の MOD と共存できる</b>から(複数の MOD が同じ戻り値を順に加工できる)。
 * 暗くする量は {@code climax.json} の {@code biome.daylightBrightness}。
 *
 * <p><b>見た目だけの処理</b>であることに注意。サーバー側の明るさは 1 も変わらないので、
 * 湧き判定・作物の成長・日光による炎上には影響しない。本当に暗くしたいなら、
 * 極相化の二次生成パスで<b>実際に林冠(葉ブロック)を張る</b>必要がある。
 */
@Mixin(LightTexture.class)
public class LightTextureClimaxMixin {

    @ModifyExpressionValue(
            method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSkyDarken(F)F"))
    private float overgrown_vegetation$darkenClimaxDaylight(float original) {
        return CorruptionAtmosphere.climaxSkyDarken(original);
    }
}
