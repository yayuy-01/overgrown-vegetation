package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.SectionUploadBudget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 毎フレームの区画の送り込み({@code compileSections} の中だけ)を 1 フレーム 2 ms までにする(2026-10-20)。中身は {@link SectionUploadBudget}。 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererUploadBudgetMixin {

    @Redirect(method = "compileSections", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadAllPendingUploads()V"), require = 0)
    private void overgrownvegetation$uploadWithinBudget(SectionRenderDispatcher dispatcher) {
        if (!SectionUploadBudget.uploadSome(((SectionRenderDispatcherAccessor) dispatcher).overgrown_vegetation$toUpload())) {
            dispatcher.uploadAllPendingUploads();
        }
    }
}
