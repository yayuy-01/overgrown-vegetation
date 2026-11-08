package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.sky.HighSky;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * 雲の上では雨・雪を降らせない(2026-10-17 ユーザー「雲の上でも雨や雪が降ってしまうのを直してほしい」)。
 *
 * <p>クライアントのワールドが返す雨の強さを、カメラが雲の底(高空の晴天の始まり、既定 y 300)から晴天になりきる高さ(既定 350)へ上がるにつれて 0 へ弱める
 * ({@link HighSky#clarity})。雨の強さはバニラの雨・雪の描画、雨の音と水しぶき、雨の日の空の暗さ・灰色・太陽と月が隠れること、雷の暗さ
 * (雷雨の強さは雨の強さを掛けて出る)に全部効くので、雲の上は雨の日でも晴れた空になる。サーバーの天気は変えない(クライアントの描画のスレッドだけ)。
 * 2026-11-08 雲の段階 1 で parked から戻した。雲の量は本当の天気で決める({@link CloudSky#rawRain} の間は弱めない)。
 */
@Mixin(Level.class)
public abstract class LevelRainAboveCloudsMixin {

    // 2026-10-20: 呼ばれる回数が多いので、入れ物を作らない書き方に(答えは同じ)
    @ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
    private float overgrown_vegetation$noRainAboveClouds(float rain) {
        if (rain <= 0.0F || !((Object) this instanceof ClientLevel) || !RenderSystem.isOnRenderThread() || CloudSky.readingRawWeather()) {
            return rain;
        }
        float c = HighSky.clarity();
        return c > 0.001F ? rain * (1.0F - c) : rain;
    }
}
