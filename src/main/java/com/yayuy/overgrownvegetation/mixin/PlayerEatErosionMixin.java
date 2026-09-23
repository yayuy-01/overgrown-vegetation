package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.spore.ErosionFood;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 浸食度のぶんだけ、<b>食べ物で回復する量を減らす</b>(2026-09-22)。
 *
 * <p>減らす割合は食料ゲージの<b>上限の減り方と同じ</b>にしてある(既定 100% で半分)。
 * つまり<b>ゲージを満たすのに要る食料の数は変わらない</b> —— 上限が半分になったぶん、
 * 1 個あたりの回復も半分。「小さくなったゲージを楽に満たせる」ことにはならない。
 *
 * <p>消費する側({@code exhaustion})には触っていないので、減る速さはそのまま。
 * 結果として「腹が減るのが速くなった」と感じられる。
 *
 * <p>{@code Player#eat} はクライアントでも呼ばれるが、そちらの浸食度は
 * アタッチメントに入っていない(0 として扱われる)ので何も起きない。
 * 表示はサーバーからの体力/食料パケットで上書きされるため、ずれは見えない。
 */
@Mixin(Player.class)
public abstract class PlayerEatErosionMixin {

    @ModifyVariable(method = "eat", at = @At("HEAD"), argsOnly = true)
    private FoodProperties overgrown_vegetation$reduceNutrition(FoodProperties original) {
        Player self = (Player) (Object) this;
        double factor = ErosionFood.restoreFactor(self);
        if (factor >= 1.0D) {
            return original;
        }
        int nutrition = Math.max(0, (int) Math.round(original.nutrition() * factor));
        float saturation = (float) (original.saturation() * factor);
        return new FoodProperties(nutrition, saturation, original.canAlwaysEat(),
                original.eatSeconds(), original.usingConvertsTo(), original.effects());
    }
}
