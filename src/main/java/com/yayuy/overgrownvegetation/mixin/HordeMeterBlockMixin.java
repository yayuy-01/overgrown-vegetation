package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.perf.HordeMeter;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 大群の重さの内訳({@link HordeMeter}、2026-11-18 ④): サーバースレッドの同期の待ち({@code managedBlock}。チャンクの同期読み込みなど)の時間。
 * 待ちは 0.1 ms ずつ寝るので JFR の待ちの記録(10 ms 以上)にも標本にも出ない。数えていない間は何もしない。
 */
@Mixin(BlockableEventLoop.class)
public abstract class HordeMeterBlockMixin {

    @Inject(method = "managedBlock", at = @At("HEAD"))
    private void overgrown_vegetation$blockHead(BooleanSupplier isDone, CallbackInfo ci) {
        if (HordeMeter.on) HordeMeter.blockStart();
    }

    @Inject(method = "managedBlock", at = @At("RETURN"))
    private void overgrown_vegetation$blockEnd(BooleanSupplier isDone, CallbackInfo ci) {
        if (HordeMeter.on) HordeMeter.blockEnd();
    }
}
