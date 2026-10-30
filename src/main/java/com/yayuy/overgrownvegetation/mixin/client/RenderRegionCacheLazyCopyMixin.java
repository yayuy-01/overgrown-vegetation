package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.LazySectionCopy;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 組み立てる区画の場所が分かった所で、まわりのチャンクの写しに上下 1 区画だけ写させる(2026-10-20、{@link LazySectionCopy})。 */
@Mixin(RenderRegionCache.class)
public abstract class RenderRegionCacheLazyCopyMixin {

    @Inject(method = "createRegion(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/SectionPos;Z)Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;",
            at = @At("RETURN"), require = 0)
    private void overgrown_vegetation$copyNeededSections(Level level, SectionPos sectionPos, boolean nullForEmpty,
                                                         CallbackInfoReturnable<RenderChunkRegion> cir) {
        LazySectionCopy.ensure(cir.getReturnValue(), sectionPos.y());
    }
}
