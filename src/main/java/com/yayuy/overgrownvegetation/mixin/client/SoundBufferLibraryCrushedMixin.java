package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.voice.CrushedVoiceCache;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 汚した声({@link CrushedVoiceCache})を、ふつうの効果音と同じ「全部読む音」の枠で鳴らすための口(2026-11-14)。
 * 道が {@code overgrown_vegetation:sounds/crushed/…} のときだけ自前のバッファーを返す(ほかの音には触らない)。
 * 再読み込み(F3+T・リソースパックの変更)でバニラのキャッシュを捨てるとき、自前の分も OpenAL のバッファーごと捨てる。
 * 注入が見つからないときは落とさない({@code require = 0})。その場合 {@link CrushedVoiceCache} は口が無いと見て、毎回流す方式で鳴らす。
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryCrushedMixin {

    @Inject(method = "getCompleteBuffer", at = @At("HEAD"), cancellable = true, require = 0)
    private void overgrown$crushedBuffer(ResourceLocation soundID, CallbackInfoReturnable<CompletableFuture<SoundBuffer>> cir) {
        CompletableFuture<SoundBuffer> own = CrushedVoiceCache.lookup(soundID);
        if (own != null) {
            cir.setReturnValue(own);
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void overgrown$hookAlive(CallbackInfo ci) {
        CrushedVoiceCache.hookAlive();
    }

    @Inject(method = "clear", at = @At("TAIL"), require = 0)
    private void overgrown$clearCrushed(CallbackInfo ci) {
        CrushedVoiceCache.clear();
    }
}
