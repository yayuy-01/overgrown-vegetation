package com.yayuy.overgrownvegetation.port.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.port.event.tick.EntityTickEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 【1.20.1 だけの物・互換の層】NeoForge の EntityTickEvent(Pre・Post)をサーバーの Entity の tick で投げる。F の持ち物。 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelEntityTickMixin {

    @WrapOperation(method = "tickNonPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
    private void ov$tick(Entity entity, Operation<Void> original) {
        if (!MinecraftForge.EVENT_BUS.post(new EntityTickEvent.Pre(entity))) {
            original.call(entity);
        }
        MinecraftForge.EVENT_BUS.post(new EntityTickEvent.Post(entity));
    }

    @WrapOperation(method = "tickPassenger", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void ov$rideTick(Entity entity, Operation<Void> original) {
        if (!MinecraftForge.EVENT_BUS.post(new EntityTickEvent.Pre(entity))) {
            original.call(entity);
        }
        MinecraftForge.EVENT_BUS.post(new EntityTickEvent.Post(entity));
    }
}
