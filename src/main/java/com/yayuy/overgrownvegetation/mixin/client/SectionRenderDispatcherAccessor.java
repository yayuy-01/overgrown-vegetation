package com.yayuy.overgrownvegetation.mixin.client;

import java.util.Queue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 区画の送り込みの待ち行列(private)。1 フレームに送る量を絞る(2026-10-20、{@code client.SectionUploadBudget})。 */
@Mixin(SectionRenderDispatcher.class)
public interface SectionRenderDispatcherAccessor {

    @Accessor("toUpload")
    Queue<Runnable> overgrown_vegetation$toUpload();
}
