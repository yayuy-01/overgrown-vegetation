package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
 *
 * <p>【1.20.1】{@code ModelPart.render} の色は int 1 つ(ARGB)ではなく float 4 つ(赤・緑・青・不透明度)。狙いは 8 引数の {@code render}
 * (4 引数の物は白で 8 引数を呼ぶ)。色を寄せる {@link InfectRender#softenTint} は 1 回の描画で必ず 1 回だけ呼ぶ(部位の深さを数えていて、
 * RETURN の {@link InfectRender#onPartDone} と対になる)ので、本流の HEAD の ModifyVariable の代わりに {@code WrapMethod} で 1 回だけ呼び、
 * 4 つの float を ARGB に詰めて渡し、変わった時だけ解いて渡す。
 */
@Mixin(ModelPart.class)
public abstract class ModelPartInfectMixin {

    private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";

    @Inject(method = RENDER,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;translateAndRotate(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void overgrown_vegetation$infectPartPose(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                                                     float red, float green, float blue, float alpha, CallbackInfo ci) {
        InfectRender.onPartPose((ModelPart) (Object) this, poseStack);
    }

    // 感染体を描いていなければ、色はそのまま返る
    @WrapMethod(method = RENDER, require = 0)
    private void overgrown_vegetation$infectSoftenTint(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                                                       float red, float green, float blue, float alpha, Operation<Void> original) {
        int color = overgrown_vegetation$channel(alpha) << 24 | overgrown_vegetation$channel(red) << 16
                | overgrown_vegetation$channel(green) << 8 | overgrown_vegetation$channel(blue);
        int soft = InfectRender.softenTint(color);
        if (soft == color) {
            original.call(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }
        original.call(poseStack, buffer, packedLight, packedOverlay,
                ((soft >> 16) & 0xFF) / 255.0F, ((soft >> 8) & 0xFF) / 255.0F, (soft & 0xFF) / 255.0F, ((soft >>> 24) & 0xFF) / 255.0F);
    }

    @Inject(method = RENDER, at = @At("RETURN"), require = 0)
    private void overgrown_vegetation$infectPartDone(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                                                     float red, float green, float blue, float alpha, CallbackInfo ci) {
        InfectRender.onPartDone();
    }

    @org.spongepowered.asm.mixin.Unique
    private static int overgrown_vegetation$channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }
}
