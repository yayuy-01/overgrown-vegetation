package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 視錐台の中身(private)。見える区画の選び直しをバニラと同じ当て方で速く回す(2026-10-20、{@code client.SectionFrustumCache})。 */
@Mixin(Frustum.class)
public interface FrustumAccessor {

    @Accessor("intersection")
    FrustumIntersection overgrown_vegetation$intersection();

    @Accessor("camX")
    double overgrown_vegetation$camX();

    @Accessor("camY")
    double overgrown_vegetation$camY();

    @Accessor("camZ")
    double overgrown_vegetation$camZ();
}
