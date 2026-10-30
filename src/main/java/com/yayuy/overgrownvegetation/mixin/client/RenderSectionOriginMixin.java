package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.SectionFrustumCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 区画の原点が動いた(カメラが区画をまたいで {@code ViewArea} が付け替えた)ことを、見える区画の近道の写しへ知らせる(2026-10-20)。 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionOriginMixin {

    @Inject(method = "setOrigin", at = @At("TAIL"), require = 0)
    private void overgrownvegetation$originMoved(int x, int y, int z, CallbackInfo ci) {
        SectionFrustumCache.onOriginChanged();
    }
}
