package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.perf.HordeMeter;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/** 大群の重さの内訳({@link HordeMeter}、2026-11-18 ④): SavedData 1 つの保存(NBT を作って書く)の時間。数えていない間は何もしない。 */
@Mixin(SavedData.class)
public abstract class HordeMeterSaveMixin {

    @Inject(method = "save(Ljava/io/File;)V", at = @At("HEAD"))
    private void overgrown_vegetation$saveHead(File file, CallbackInfo ci) {
        if (HordeMeter.on) HordeMeter.saveStart();
    }

    @Inject(method = "save(Ljava/io/File;)V", at = @At("RETURN"))
    private void overgrown_vegetation$saveEnd(File file, CallbackInfo ci) {
        if (HordeMeter.on) HordeMeter.saveEnd(this, file);
    }
}
