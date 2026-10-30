package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.HighSky;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * 高空の晴天({@link HighSky}、2026-10-17): カメラが高さ 300〜350 より上にいる間、バイオームの空の色・霧の色をバニラの晴れた空の色へ寄せる。
 * バニラは空の丸天井と霧の色を、まわりのバイオームの {@code sky_color}/{@code fog_color} をなめらかに混ぜて作る(CubicSampler)ので、ここで返す色を
 * 変えれば時刻・雨・朝焼けの計算はそのまま効く。全部のバイオームに掛ける(過繁茂だけにすると、高空でバイオームの境を越えたときに色の継ぎ目が出る)。
 * 描画のスレッドだけ(バイオームの色は描画でしか使わないが、念のためほかのスレッドでは触らない)。
 */
@Mixin(Biome.class)
public class BiomeClearSkyMixin {

    // 2026-10-20: 空の色を混ぜるときバイオームごとに何百回も呼ばれるので、入れ物を作らない書き方に(答えは同じ)
    @ModifyReturnValue(method = "getSkyColor", at = @At("RETURN"))
    private int overgrown_vegetation$clearSkyAbove(int color) {
        if (!RenderSystem.isOnRenderThread()) {
            return color;
        }
        color = com.yayuy.overgrownvegetation.client.FogMood.biomeSky((Biome) (Object) this, color);  // 霧と空の色の詰め(2026-10-23 試作)
        float c = HighSky.clarity();
        return c > 0.001F ? HighSky.lerpColor(c, color, HighSky.clearSky()) : color;
    }

    @ModifyReturnValue(method = "getFogColor", at = @At("RETURN"))
    private int overgrown_vegetation$clearFogAbove(int color) {
        if (!RenderSystem.isOnRenderThread()) {
            return color;
        }
        color = com.yayuy.overgrownvegetation.client.FogMood.biomeFog((Biome) (Object) this, color);
        float c = HighSky.clarity();
        return c > 0.001F ? HighSky.lerpColor(c, color, HighSky.clearFog()) : color;
    }
}
