package com.yayuy.overgrownvegetation.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.infect.InfectRender;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 汎用の感染体(2026-11-15)の部品を、バニラ式の模型の部位に付いて行かせる: 部位が自分の姿勢を決めた直後に、その姿勢を
 * {@link InfectRender#onPartPose} へ知らせる。感染体を描いていない間は、静的な変数を 1 つ見て戻るだけ。
 */
@Mixin(ModelPart.class)
public abstract class ModelPartInfectMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void overgrown_vegetation$infectPartPose(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color,
                                                     CallbackInfo ci) {
        InfectRender.onPartPose((ModelPart) (Object) this, poseStack);
    }
}
