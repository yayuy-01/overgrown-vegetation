package com.yayuy.overgrownvegetation.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.yayuy.overgrownvegetation.client.ClimaxJungle;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 極相の密林の「時間の狂い」(2026-10-16 ユーザー指定、極相の 8 案の (3)): 真昼でも林冠の穴から星が見える。
 * 空を描くときに読む星の明るさに、極相の密林に居る度合いぶんの下限を足す({@link ClimaxJungle#dayStars})。
 * 戻り値を加工するだけなので、同じ所に手を入れる他の MOD と共存できる。見た目だけ。
 */
@Mixin(ClientLevel.class)
public class ClientLevelDayStarsMixin {

    @ModifyReturnValue(method = "getStarBrightness", at = @At("RETURN"))
    private float overgrown_vegetation$dayStars(float original) {
        return Math.max(original, ClimaxJungle.dayStars());
    }
}
