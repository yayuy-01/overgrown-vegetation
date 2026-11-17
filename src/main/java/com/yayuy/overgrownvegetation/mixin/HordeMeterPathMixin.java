package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.perf.HordeMeter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** 大群の重さの内訳({@link HordeMeter}、2026-11-18): 経路探索の回数と時間。数えていない間は何もしない。 */
@Mixin(PathFinder.class)
public abstract class HordeMeterPathMixin {

    @Unique
    private static long overgrown_vegetation$start;

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void overgrown_vegetation$pathStart(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange, int accuracy,
                                                float depth, CallbackInfoReturnable<Path> cir) {
        if (HordeMeter.on) overgrown_vegetation$start = System.nanoTime();
    }

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"))
    private void overgrown_vegetation$pathEnd(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange, int accuracy,
                                              float depth, CallbackInfoReturnable<Path> cir) {
        if (HordeMeter.on && overgrown_vegetation$start != 0L) {
            HordeMeter.path(mob, System.nanoTime() - overgrown_vegetation$start);
            overgrown_vegetation$start = 0L;
        }
    }
}
