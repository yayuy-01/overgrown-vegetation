package com.yayuy.overgrownvegetation.port.mixin;

import com.yayuy.overgrownvegetation.port.entity.PortAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の SCALE・JUMP_STRENGTH 属性を、1.20.1 の LivingEntity に効かせる({@link PortAttributes})。
 * F の持ち物(overgrown_vegetation.port.mixins.json)。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPortMixin {

    @Shadow
    @Final
    private AttributeMap attributes;

    @Unique
    private float ov$appliedScale = 1.0F;

    /** 体格: 1.20.1 の getScale(子どもなら 0.5)に SCALE 属性を掛ける。getDimensions もこれを使うので当たり判定も変わる。 */
    @Inject(method = "getScale", at = @At("RETURN"), cancellable = true)
    private void ov$scale(CallbackInfoReturnable<Float> cir) {
        if (attributes == null || !PortAttributes.SCALE.isPresent()) {
            return;
        }
        var inst = attributes.getInstance(PortAttributes.SCALE.get());
        if (inst != null) {
            cir.setReturnValue(cir.getReturnValueF() * (float) inst.getValue());
        }
    }

    /** 体格が変わったら当たり判定を作り直す(1.20.5 の LivingEntity#tick の appliedScale と同じ)。 */
    @Inject(method = "tick", at = @At("HEAD"))
    private void ov$refreshScale(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float s = self.getScale();
        if (s != ov$appliedScale) {
            ov$appliedScale = s;
            self.refreshDimensions();
        }
    }

    /** 跳ぶ力: 0.42 の代わりに JUMP_STRENGTH 属性。 */
    @ModifyConstant(method = "getJumpPower", constant = @Constant(floatValue = 0.42F))
    private float ov$jumpStrength(float original) {
        if (attributes == null || !PortAttributes.JUMP_STRENGTH.isPresent()) {
            return original;
        }
        var inst = attributes.getInstance(PortAttributes.JUMP_STRENGTH.get());
        return inst != null ? (float) inst.getValue() : original;
    }
}
