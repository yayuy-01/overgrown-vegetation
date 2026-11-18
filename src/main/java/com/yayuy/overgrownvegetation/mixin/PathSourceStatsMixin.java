package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.perf.HordeMeter;
import com.yayuy.overgrownvegetation.perf.PathSourceStats;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** 経路探索の出どころと失敗の割合({@link PathSourceStats}、2026-11-18 流れの地図の下調べ)。数えていない間は何もしない。 */
@Mixin(PathFinder.class)
public abstract class PathSourceStatsMixin {

    @Unique
    private static long overgrown_vegetation$srcStart;

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void overgrown_vegetation$srcHead(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange, int accuracy,
                                              float depth, CallbackInfoReturnable<Path> cir) {
        if (HordeMeter.on) overgrown_vegetation$srcStart = System.nanoTime();
    }

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"))
    private void overgrown_vegetation$srcReturn(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange, int accuracy,
                                                float depth, CallbackInfoReturnable<Path> cir) {
        if (HordeMeter.on && overgrown_vegetation$srcStart != 0L) {
            PathSourceStats.record(mob, System.nanoTime() - overgrown_vegetation$srcStart, cir.getReturnValue());
            overgrown_vegetation$srcStart = 0L;
        }
    }
}
