package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.item.PlantArmor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「寄生植物装備」を着けたプレイヤー(ホスト)には、<b>装備を着けていない他のプレイヤーが発光して見える</b>
 * (ユーザー要望 2026-09-18: フィロセンスに狙われる側から、過繫茂側として相手の居場所が見える側へ)。
 *
 * <p>発光はバニラの「発光」効果と同じ輪郭表示だが、効果を付けるのではなく<b>見ている側のクライアントだけ</b>で
 * {@link Entity#isCurrentlyGlowing} を差し替える。効果を付けると本人や他の人にも光って見えてしまうため。
 * クリエイティブ・観戦者・自分自身・ホスト同士は光らない。
 */
@Mixin(Entity.class)
public abstract class PlantArmorGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void overgrownvegetation$hostSeesPlayers(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player other) || !self.level().isClientSide()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player == self || !PlantArmor.isHost(mc.player)) {
            return;
        }
        if (PlantArmor.isHost(other) || other.isSpectator()) {
            return;
        }
        cir.setReturnValue(true);
    }
}
