package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;

/**
 * 「栄養満点！」— {@link ModItems#OVERGROWN_GLOWBERRY 熟した垂れ草の光る果実}を食べると付くバフ。
 *
 * <p>効果は 1 つ: <b>毎 tick、食料ゲージが 1 回復する</b>。20 で頭打ちなので、
 * 実際には「付いているあいだ満腹のまま」という意味になる —— 走っても跳んでも減った端から戻る。
 * 付与は {@link FruitEffects}、持続は {@code effects.json} の {@code glowFruit.wellFedTicks}。
 *
 * <h2>回復させるのは食料ゲージだけ(隠し満腹度は触らない)</h2>
 * {@link FoodData#eat} を使うと隠し満腹度(saturation)まで一緒に上がってしまい、
 * 「1 回復」という指定より大きく効いてしまう。ここでは {@link FoodData#setFoodLevel} で
 * ゲージだけを 1 上げる —— 満腹度の回復は果実を食べた瞬間のぶん(グロウベリー相当)だけ。
 *
 * <h2>サーバー側でしか触らない</h2>
 * 食料ゲージはサーバーが持つ値で、変化は {@code ServerPlayer#tick} が毎 tick クライアントへ送る。
 * クライアント側で書き換えると次の同期パケットで巻き戻り、ゲージがちらつく。
 */
public class WellFedEffect extends MobEffect {

    /** 1 tick あたりの回復量。 */
    private static final int FOOD_PER_TICK = 1;

    public WellFedEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.level().isClientSide() || !(entity instanceof Player player)) {
            return true;
        }
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() < 20) {
            food.setFoodLevel(Math.min(20, food.getFoodLevel() + FOOD_PER_TICK));
        }
        return true;
    }
}
