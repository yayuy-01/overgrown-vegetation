package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.cliff.RootClimb;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 縄(絡む根)に取り付いている植物側の Mob は、宙にいても経路を引き直し・たどれる(2026-11-20 崖の汚染。{@link com.yayuy.overgrownvegetation.corruption.cliff.RootPath})。
 * バニラの地上の経路は地面か水の中でしか引き直さないので、登っている途中に相手を見失って経路が止まると、縄から落ちるまで次の経路を引けなかった。
 * 地面に立っていないときにだけ足すので、ふつうに歩く Mob の重さは変わらない。
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationRootMixin extends PathNavigation {

    protected GroundPathNavigationRootMixin(Mob mob, Level level) {
        super(mob, level);
    }

    @Inject(method = "canUpdatePath", at = @At("RETURN"), cancellable = true)
    private void overgrown$updateOnRope(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        if (PlantSideRules.isPlantSide(this.mob)
                && (RootClimb.touching(this.mob) || com.yayuy.overgrownvegetation.corruption.cliff.RootClimbAssist.onRopePath(this.mob))) {
            cir.setReturnValue(true);
        }
    }
}
