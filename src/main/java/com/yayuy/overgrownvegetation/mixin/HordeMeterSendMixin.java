package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.yayuy.overgrownvegetation.perf.HordeMeter;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** 大群の重さの内訳({@link HordeMeter}、2026-11-18): この MOD の Mob の送り(sendChanges の時間・パケットの数)。数えていない間は何もしない。 */
@Mixin(ServerEntity.class)
public abstract class HordeMeterSendMixin {

    @Shadow
    @Final
    private Entity entity;

    @Unique
    private static long overgrown_vegetation$sendStart;

    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void overgrown_vegetation$sendHead(CallbackInfo ci) {
        overgrown_vegetation$sendStart = HordeMeter.on && HordeMeter.isMod(entity) ? System.nanoTime() : 0L;
    }

    @Inject(method = "sendChanges", at = @At("TAIL"))
    private void overgrown_vegetation$sendTail(CallbackInfo ci) {
        if (overgrown_vegetation$sendStart != 0L) {
            HordeMeter.send(System.nanoTime() - overgrown_vegetation$sendStart);
            overgrown_vegetation$sendStart = 0L;
        }
    }

    @WrapOperation(method = {"sendChanges", "broadcastAndSend"},
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void overgrown_vegetation$countPacket(Consumer<Object> consumer, Object packet, Operation<Void> original) {
        if (HordeMeter.on && HordeMeter.isMod(entity)) HordeMeter.packet();
        original.call(consumer, packet);
    }
}
