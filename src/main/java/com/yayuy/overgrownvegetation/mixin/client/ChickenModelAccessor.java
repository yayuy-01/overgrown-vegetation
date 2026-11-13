package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 鶏のモデルの部位(private)。崩れ落ちで死体の姿勢へ動かす(2026-10-09、{@code client.corpse.CollapsePoser})。 */
@Mixin(ChickenModel.class)
public interface ChickenModelAccessor {

    @Accessor("head")
    ModelPart overgrown_vegetation$head();

    @Accessor("beak")
    ModelPart overgrown_vegetation$beak();

    @Accessor("redThing")
    ModelPart overgrown_vegetation$redThing();

    @Accessor("body")
    ModelPart overgrown_vegetation$body();

    @Accessor("rightLeg")
    ModelPart overgrown_vegetation$rightLeg();

    @Accessor("leftLeg")
    ModelPart overgrown_vegetation$leftLeg();

    @Accessor("rightWing")
    ModelPart overgrown_vegetation$rightWing();

    @Accessor("leftWing")
    ModelPart overgrown_vegetation$leftWing();
}
