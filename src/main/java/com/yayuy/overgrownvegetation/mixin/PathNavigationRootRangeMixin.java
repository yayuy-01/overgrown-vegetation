package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.cliff.RootPath;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 植物側の Mob が上の相手へ経路を引くとき、縄を上る分だけ経路の届く距離を延ばす(2026-11-20 崖の汚染。{@link RootPath#climbAllowance})。
 * 経路の長さは追う距離(follow range)で打ち切られ、崖の下まで歩いて 20 段上ると届かなかった(経路が途中で切れると、G1 は届かない経路を捨てる)。
 * 行き先が 3 段以上上のときだけ延ばすので、ふつうの経路の重さは変わらない。
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationRootRangeMixin {

    @Shadow
    @Final
    protected Mob mob;

    @Shadow
    private float maxVisitedNodesMultiplier;

    @org.spongepowered.asm.mixin.Unique
    private float overgrown$savedMult = Float.NaN;

    @ModifyVariable(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"), argsOnly = true)
    private float overgrown$climbRange(float followRange, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) java.util.Set<net.minecraft.core.BlockPos> targets) {
        float extra = RootPath.climbAllowance(this.mob, targets);
        if (extra > 0.0F) {
            // 崖の下の床を広く調べてから縄へ入るので、調べる節の上限もこの探索の間だけ 4 倍に(終わりで戻す。2 倍では平らな崖で上限に当たった)
            this.overgrown$savedMult = this.maxVisitedNodesMultiplier;
            this.maxVisitedNodesMultiplier = Math.max(this.maxVisitedNodesMultiplier, 4.0F);
        }
        return followRange + extra;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("RETURN"))
    private void overgrown$restoreMult(CallbackInfoReturnable<net.minecraft.world.level.pathfinder.Path> cir) {
        if (!Float.isNaN(this.overgrown$savedMult)) {
            this.maxVisitedNodesMultiplier = this.overgrown$savedMult;
            this.overgrown$savedMult = Float.NaN;
        }
    }
}
