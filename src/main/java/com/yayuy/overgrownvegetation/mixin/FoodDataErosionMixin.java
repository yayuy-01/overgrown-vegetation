package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.spore.ErosionFood;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 浸食度で食料ゲージの上限が減ったとき、<b>自然回復の判定を上限に対する割合で読み替える</b>
 * (2026-09-22)。ユーザー指定「減った分の中で最大値になれば体力は回復する」。
 *
 * <p>バニラの {@code FoodData#tick} は回復条件を固定値で持っている。
 * <ul>
 *     <li>{@code foodLevel >= 20} —— 飽和度が残っているときの速い回復(20 = 満腹)</li>
 *     <li>{@code foodLevel >= 18} —— 通常の遅い回復</li>
 * </ul>
 * 上限を 10 に減らしたまま放っておくと<b>どちらの条件にも永久に届かず、一切回復しなくなる</b>。
 * ここで上限に合わせて読み替える(上限 10 なら 10 と 9)。
 *
 * <p>{@code tick} の中でこの 2 つの数はそれぞれ 1 回しか出てこないことをバイトコードで確認済み
 * ({@code bipush 20} と {@code bipush 18} が各 1 箇所)。プレイヤーは MixinExtras の
 * {@code @Local} で {@code tick} の引数から取る。
 *
 * <p>{@code FoodData#tick} はサーバー側でしか呼ばれない({@code Player#tick} が
 * {@code !level.isClientSide} で囲っている)ので、ここで読む浸食度はアタッチメントの値でよい。
 */
@Mixin(FoodData.class)
public class FoodDataErosionMixin {

    /** 飽和度による速い回復の条件「満腹」。 */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 20))
    private int overgrown_vegetation$fullFood(int original, @Local(argsOnly = true) Player player) {
        return ErosionFood.maxFood(player);
    }

    /** 通常の遅い回復の条件。 */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 18))
    private int overgrown_vegetation$regenThreshold(int original, @Local(argsOnly = true) Player player) {
        return ErosionFood.regenThreshold(player);
    }
}
