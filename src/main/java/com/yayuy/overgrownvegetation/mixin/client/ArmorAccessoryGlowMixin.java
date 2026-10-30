package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.ArmorAccessoryClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「索敵の装飾品」(順 1 の着手順 9)—— ヘルメットに付けていると、半径内の敵が<b>自分の画面でだけ</b>発光の輪郭で見える。
 *
 * <p>寄生植物装備の {@link PlantArmorGlowMixin} と同じ手: 発光の効果を付けるのではなく、見ている側のクライアントで
 * {@link Entity#isCurrentlyGlowing} の答えだけを変える(効果を付けると他の人にも光って見えてしまうため)。
 * 判定は {@link ArmorAccessoryClient#sensesEnemy}。
 */
@Mixin(Entity.class)
public abstract class ArmorAccessoryGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void overgrownvegetation$senseEnemies(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() && ArmorAccessoryClient.sensesEnemy(self)) {
            cir.setReturnValue(true);
        }
    }
}
