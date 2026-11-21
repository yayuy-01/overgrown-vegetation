package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.gear.attach.ArmorAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 防具の付け物: 防具が壊れたら、付け物は持ち主の手元へ(2026-11-22 ユーザー「持ち物に入れ、いっぱいなら足元に落とす」)。
 * 耐久が尽きて {@code shrink} される直前に外して渡す(壊れた後のスタックは空なので中身が読めない)。付け物の無い物は何もしない。
 */
@Mixin(ItemStack.class)
public abstract class ArmorAttachBreakMixin {

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void overgrown_vegetation$dropAttachmentsOnBreak(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak, CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (!ArmorAttachments.hasAny(self)) return;
        ArmorAttachments.giveBack(entity, ArmorAttachments.detachAll(self));
    }
}
