package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.perf.MobThinkThrottle;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 遠くの Mob の思考の間引き({@link MobThinkThrottle}、2026-10-06)。
 *
 * <p>{@code Mob#serverAiStep} は偶数側の tick に {@code targetSelector.tick()} と {@code goalSelector.tick()}
 * (全部の行動に「始められるか」を聞き直す)、奇数側に {@code tickRunningGoals(false)}(今している行動のうち毎 tick 動くものだけ)を呼ぶ。
 * 間引く Mob は偶数側でも奇数側と同じ {@code tickRunningGoals(false)} にする。移動・経路の追従・視線などの残りは毎 tick そのまま。
 * {@code GoalSelector#tick()} の呼び出しは {@code serverAiStep} の中に 2 つ(標的と行動)あり、両方とも包む。
 */
@Mixin(Mob.class)
public abstract class MobThinkThrottleMixin {

    /** この tick の選び直しを飛ばすか(頭で 1 回だけ決め、2 つの呼び出しで使う)。 */
    @Unique
    private boolean overgrown_vegetation$holdThinking;

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void overgrown_vegetation$decideThrottle(CallbackInfo ci) {
        overgrown_vegetation$holdThinking = MobThinkThrottle.holdThinking((Mob) (Object) this);
    }

    @WrapOperation(method = "serverAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"))
    private void overgrown_vegetation$tickSelector(GoalSelector selector, Operation<Void> original) {
        if (overgrown_vegetation$holdThinking) {
            selector.tickRunningGoals(false);
        } else {
            original.call(selector);
        }
    }
}
