package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse;
import com.yayuy.overgrownvegetation.client.corpse.CollapsePoser;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 胞子感染で死んだバニラの Mob を、バニラの描画のまま死体の姿勢へ崩れ落とす(2026-10-09)。
 * <ul>
 *   <li>死亡の横倒し({@code getFlipDegrees} の呼び出し側。クモの 180 は SpiderRenderer の上書きなので本体には差さない)を 0 に。</li>
 *   <li>体の向きを死んだときのサーバーの値に固定(死体ブロックも同じ向き)。</li>
 *   <li>部位の姿勢は {@code setupAnim} の直後に寄せ、描き終えたら戻す(モデルは種で共有)。</li>
 *   <li>モデル全体の倒れる回転はその直後(= {@code translate(0,-1.501,0)} の後のモデルの空間)。寄せた部位で地面に潜らない高さを測る。</li>
 *   <li>被弾の赤を出さない(死体へ変わる最中は血ではなく植物)。</li>
 * </ul>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererCollapseMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow
    protected M model;

    @Unique
    private Map<ModelPart, PartPose> overgrown_vegetation$saved;

    @ModifyExpressionValue(method = "setupRotations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;getFlipDegrees(Lnet/minecraft/world/entity/LivingEntity;)F"))
    private float overgrown_vegetation$noDeathFlip(float original, @Local(argsOnly = true) LivingEntity entity) {
        return ClientCorpseCollapse.get(entity) != null ? 0.0F : original;
    }

    @ModifyVariable(method = "setupRotations", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float overgrown_vegetation$fixYaw(float yBodyRot, @Local(argsOnly = true) LivingEntity entity) {
        ClientCorpseCollapse.Collapse c = ClientCorpseCollapse.get(entity);
        return c != null ? c.yaw() : yBodyRot;
    }

    /** 死体ブロックが描かれ始めたら、崩れ落ちた Mob は描かない(サーバーはしばらく Mob を残す。点滅を防ぐ引き継ぎ)。 */
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$hideAfterHandoff(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                                       MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (ClientCorpseCollapse.shouldHide(entity)) {
            ci.cancel();
        }
    }

    /** 部位を寄せてから、モデル全体を倒す(寄せた部位で地面に潜らない高さを測るので、この順。描く前ならポーズの積み方は変わらない)。 */
    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void overgrown_vegetation$collapse(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                               MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        ClientCorpseCollapse.Collapse c = ClientCorpseCollapse.get(entity);
        if (c != null) {
            float p = CollapsePoser.progress(entity, partialTicks);
            this.overgrown_vegetation$saved = CollapsePoser.applyParts(this.model, c, p);
            CollapsePoser.applyRoot(poseStack, this.model, c, p);
        }
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"))
    private void overgrown_vegetation$restoreParts(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                                   MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (this.overgrown_vegetation$saved != null) {
            CollapsePoser.restore(this.overgrown_vegetation$saved);
            this.overgrown_vegetation$saved = null;
        }
    }

    @Inject(method = "getOverlayCoords", at = @At("HEAD"), cancellable = true)
    private static void overgrown_vegetation$noRedWhileCollapsing(LivingEntity entity, float u, CallbackInfoReturnable<Integer> cir) {
        if (ClientCorpseCollapse.get(entity) != null) {
            cir.setReturnValue(OverlayTexture.pack(OverlayTexture.u(u), OverlayTexture.v(false)));
        }
    }
}
