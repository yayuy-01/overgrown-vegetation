package com.yayuy.overgrownvegetation.mixin.client;

import net.minecraft.client.model.HoglinModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ホグリン・ゾグリンのモデルの部位(private、HoglinModel 自身が持つ)。崩れ落ちで死体の姿勢へ動かす
 * (2026-10-09 ネザーの媒介種、{@code client.corpse.CollapsePoser})。耳は head の子、たてがみは body の子なのでついてくる。
 */
@Mixin(HoglinModel.class)
public interface HoglinModelAccessor {

    @Accessor("head")
    ModelPart overgrown_vegetation$head();

    @Accessor("body")
    ModelPart overgrown_vegetation$body();

    @Accessor("rightFrontLeg")
    ModelPart overgrown_vegetation$rightFrontLeg();

    @Accessor("leftFrontLeg")
    ModelPart overgrown_vegetation$leftFrontLeg();

    @Accessor("rightHindLeg")
    ModelPart overgrown_vegetation$rightHindLeg();

    @Accessor("leftHindLeg")
    ModelPart overgrown_vegetation$leftHindLeg();
}
