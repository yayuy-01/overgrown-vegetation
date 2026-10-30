package com.yayuy.overgrownvegetation.mixin.client;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 今のフレームの見える区画の一覧(private)。devbridge の数え上げ用(2026-10-20)。 */
@Mixin(LevelRenderer.class)
public interface LevelRendererVisibleAccessor {

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> overgrown_vegetation$visibleSections();
}
