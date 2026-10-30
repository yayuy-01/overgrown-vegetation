package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.SectionFrustumCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 区画が描く物(地形の層かブロックエンティティ)を持つかを、組み立て済みの中身が替わるたびに区画自身へ書いておく(2026-10-20)。
 * 見える区画の短い一覧({@code LevelRendererSectionListsMixin})を毎フレーム作るとき、区画 → 中身の入れ物 → 中身 → 層の集合と 4 回たどらず、
 * 区画を 1 回読むだけにする(飛んでいる間、その一覧作りが描画のスレッドの 1 割を使っていた)。中身が替わるのは {@code setCompiled} と
 * {@code reset}(原点が動いた・バッファを捨てた)だけ。{@code setCompiled} は組み立てのスレッドからも呼ばれるので volatile。
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionDrawableMixin implements SectionFrustumCache.Drawable {
    @Unique
    private volatile boolean overgrown_vegetation$drawable;

    @Inject(method = "setCompiled", at = @At("TAIL"), require = 0)
    private void overgrown_vegetation$compiled(SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci) {
        this.overgrown_vegetation$drawable = !compiled.hasNoRenderableLayers() || !compiled.getRenderableBlockEntities().isEmpty();
    }

    @Inject(method = "reset", at = @At("TAIL"), require = 0)
    private void overgrown_vegetation$reset(CallbackInfo ci) {
        this.overgrown_vegetation$drawable = false;
    }

    @Override
    public boolean overgrown_vegetation$drawable() {
        return this.overgrown_vegetation$drawable;
    }
}
