package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.SwingThroughFoliage;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 背の高い草に照準を吸われず、その奥の Mob を殴れるようにする
 * ({@link SwingThroughFoliage} に条件と理屈を書いてある)。
 *
 * <p>{@link GameRenderer#pick} の最後 —— バニラが {@code minecraft.hitResult} と
 * {@code crosshairPickEntity} を確定させた直後 —— に割り込み、当たり判定の無い草に当たっていた場合だけ
 * その奥の相手へ差し替える。バニラの判定そのものには手を付けないので、条件を満たさないときは
 * 完全にバニラの挙動(= 草を刈れる)。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSwingThroughFoliageMixin {

    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void overgrownvegetation$swingThroughFoliage(float partialTicks, CallbackInfo ci) {
        SwingThroughFoliage.retarget(partialTicks);
    }
}
