package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.SectionFrustumCache;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 視点を回すたびの「見える区画」の選び直しを、平らな配列で回す近道へ回す(2026-10-20)。選ぶ区画も順番もバニラと同じ。
 * 中身と理由は {@link SectionFrustumCache}。近道が使えないとき(切ってある・ほかの MOD の視錐台)はバニラのまま。
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class SectionOcclusionGraphFastCullMixin {

    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true, require = 0)
    private void overgrownvegetation$fastFrustum(Frustum frustum, List<SectionRenderDispatcher.RenderSection> sections, CallbackInfo ci) {
        if (SectionFrustumCache.addSectionsInFrustum(this.currentGraph.get(), frustum, sections)) {
            ci.cancel();
        }
    }
}
