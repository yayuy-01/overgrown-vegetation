package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.perf.HordeMeter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

/** 大群の重さの内訳({@link HordeMeter}、2026-11-18): サーバー側の押し合いの回数・見つけた相手・時間。数えていない間は何もしない。 */
@Mixin(LivingEntity.class)
public abstract class HordeMeterPushMixin {

    @Unique
    private static long overgrown_vegetation$pushStart;
    @Unique
    private static int overgrown_vegetation$pushFound;

    @Inject(method = "pushEntities", at = @At("HEAD"))
    private void overgrown_vegetation$pushHead(CallbackInfo ci) {
        if (HordeMeter.on && !((Entity) (Object) this).level().isClientSide) {
            overgrown_vegetation$pushStart = System.nanoTime();
            overgrown_vegetation$pushFound = 0;
        }
    }

    @WrapOperation(method = "pushEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Entity> overgrown_vegetation$pushFind(Level level, Entity self, AABB box, Predicate<? super Entity> filter, Operation<List<Entity>> original) {
        List<Entity> list = original.call(level, self, box, filter);
        if (HordeMeter.on) overgrown_vegetation$pushFound = list.size();
        return list;
    }

    @Inject(method = "pushEntities", at = @At("TAIL"))
    private void overgrown_vegetation$pushTail(CallbackInfo ci) {
        if (HordeMeter.on && overgrown_vegetation$pushStart != 0L) {
            HordeMeter.push((Entity) (Object) this, overgrown_vegetation$pushFound, System.nanoTime() - overgrown_vegetation$pushStart);
            overgrown_vegetation$pushStart = 0L;
        }
    }
}
