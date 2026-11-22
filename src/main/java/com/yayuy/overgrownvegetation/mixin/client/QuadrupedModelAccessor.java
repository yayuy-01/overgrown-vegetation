package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.model.QuadrupedModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 牛・豚・羊(と羊の毛)のモデルの部位(protected)。崩れ落ちで死体の姿勢へ動かす(2026-10-09、{@code client.corpse.CollapsePoser})。 */
@Mixin(QuadrupedModel.class)
public interface QuadrupedModelAccessor {

    @Accessor("head")
    ModelPart overgrown_vegetation$head();

    @Accessor("body")
    ModelPart overgrown_vegetation$body();

    @Accessor("rightHindLeg")
    ModelPart overgrown_vegetation$rightHindLeg();

    @Accessor("leftHindLeg")
    ModelPart overgrown_vegetation$leftHindLeg();

    @Accessor("rightFrontLeg")
    ModelPart overgrown_vegetation$rightFrontLeg();

    @Accessor("leftFrontLeg")
    ModelPart overgrown_vegetation$leftFrontLeg();
}
