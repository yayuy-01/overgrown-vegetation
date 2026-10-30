package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 空の色({@code ClientLevel#getSkyColor})を、同じ tick・同じ partialTick・同じ場所なら前の答えで返す(2026-10-20)。
 *
 * <p>バニラの空の色は、まわりのバイオームを 6 × 6 × 6 = 216 か所引いて混ぜる(CubicSampler。引くたびに Vec3 を作る)。バニラは 1 フレームに 2 回だが、
 * この MOD の空・雲の演出(空の丸天井・雲・巻雲・尾流雲・雲のかけら・遠くの地面)がカメラの所で 1 フレームに 6〜8 回呼んでいて、
 * 描画のスレッドのバイオームの引き(`getNoiseBiome`)と確保の大きな部分だった。答えは時刻・雨・雷の光・バイオームで決まり、どれも tick の中では
 * partialTick だけで決まる(雷の光の残りは tick ごとに減る)ので、tick と partialTick と場所が同じなら同じ答えになる。描画のスレッドだけ。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyColorMemoMixin {
    @Unique
    private Vec3 overgrown_vegetation$skyColor;
    @Unique
    private long overgrown_vegetation$skyTick = Long.MIN_VALUE;
    @Unique
    private float overgrown_vegetation$skyPartial;
    @Unique
    private double overgrown_vegetation$skyX;
    @Unique
    private double overgrown_vegetation$skyY;
    @Unique
    private double overgrown_vegetation$skyZ;

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void overgrown_vegetation$reuseSkyColor(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        Vec3 last = this.overgrown_vegetation$skyColor;
        if (last != null && pos.x == this.overgrown_vegetation$skyX && pos.y == this.overgrown_vegetation$skyY && pos.z == this.overgrown_vegetation$skyZ
                && partialTick == this.overgrown_vegetation$skyPartial && ((ClientLevel) (Object) this).getGameTime() == this.overgrown_vegetation$skyTick
                && RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(last);
        }
    }

    @Inject(method = "getSkyColor", at = @At("RETURN"), require = 0)
    private void overgrown_vegetation$rememberSkyColor(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        this.overgrown_vegetation$skyColor = cir.getReturnValue();
        this.overgrown_vegetation$skyTick = ((ClientLevel) (Object) this).getGameTime();
        this.overgrown_vegetation$skyPartial = partialTick;
        this.overgrown_vegetation$skyX = pos.x;
        this.overgrown_vegetation$skyY = pos.y;
        this.overgrown_vegetation$skyZ = pos.z;
    }
}
