package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.sky.HighSky;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

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
        // 段の「なし」(11-23)ではバニラの雨の強さのまま(雨の種類の強弱も雲の上の晴れも、この MOD の天気の見た目なので切る)。
        if (rain <= 0.0F || !((Object) this instanceof ClientLevel) || !RenderSystem.isOnRenderThread() || CloudSky.readingRawWeather()
                || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()) {
            return rain;
        }
        // 11-12 雨の種類: カメラの所の雨の強さ(小雨・普通・大雨 + 真上の雲)を掛ける({@link com.yayuy.overgrownvegetation.client.atmosphere.RainFeel})。
        rain *= com.yayuy.overgrownvegetation.client.atmosphere.RainFeel.rainScale();
        float c = HighSky.clarity();
        return c > 0.001F ? rain * (1.0F - c) : rain;
    }

    /**
     * 見直し 第 2 回 ④4(11-18): 弱めた雨の強さは描画・音・空の暗さのための物。{@code isRaining}・{@code isThundering}(しきい値 0.2・0.9 で決まる
     * 遊びの判定: 雨に濡れる・激流のトライデント・火が消える など)まで弱めた値で答えると、小雨(× 0.35)や雲の上で、クライアントだけ「降っていない」になり
     * サーバーと食い違う。この 2 つは本当の天気で答える({@link CloudSky#rawRain} と同じ印を立てて元の計算を呼ぶ)。{@code isRainingAt} は中で
     * {@code isRaining} を呼ぶので一緒に直る。雨・雪の描画、雨の音と水しぶき、空の色は {@code getRainLevel} をじかに読むので、今までどおり弱まる。
     */
    @WrapMethod(method = "isRaining", require = 0)
    private boolean overgrown_vegetation$rainingByRealWeather(Operation<Boolean> original) {
        if (!((Object) this instanceof ClientLevel)) {
            return original.call();
        }
        return CloudSky.withRawWeather(original::call);
    }

    @WrapMethod(method = "isThundering", require = 0)
    private boolean overgrown_vegetation$thunderingByRealWeather(Operation<Boolean> original) {
        if (!((Object) this instanceof ClientLevel)) {
            return original.call();
        }
        return CloudSky.withRawWeather(original::call);
    }
}
