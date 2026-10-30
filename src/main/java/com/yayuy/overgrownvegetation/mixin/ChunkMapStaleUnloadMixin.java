package com.yayuy.overgrownvegetation.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ワールドを閉じると「Saving worlds」のまま固まるバニラ 1.21.1 の不具合を塞ぐ(2026-10-18)。
 *
 * <p>読み込み解除を待っている({@code pendingUnloads} に入った)チャンクへプレイヤーが数秒のうちに戻ると、
 * チャンクは {@code updateChunkScheduling} で生き返り、周りの地形生成({@code ChunkGenerationTask})に掴まれる。
 * そのあと古い解除の予約が走ると {@code isReadyForSaving()} が false(掴まれている)なので {@code scheduleUnload} をやり直し、
 * 保存の待ち({@code saveSync})は済んでいるので {@code unloadQueue} にその場で積み直す。ふだんはその tick の余り時間を食うだけだが、
 * 停止中は {@code processUnloads} の「時間がある間は回す」が常に真なので抜けられない。掴んでいる生成はメインスレッドの仕事を
 * 待っていて、メインスレッドはここで回り続けるので永遠に放されない。
 *
 * <p>生き返ったチャンク(もう {@code pendingUnloads} に居ない)の古い予約はここで捨てる。バニラも「保存できる」側の枝では
 * 同じ場合({@code pendingUnloads.remove(pos, holder)} が false)に何もしないので、結果は変わらない。もう一度外れるときは
 * {@code processUnloads} が新しく予約し直す。チャンクの仕組みを丸ごと差し替える MOD と一緒のときは当たらないので {@code require = 0}。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapStaleUnloadMixin {

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true, require = 0)
    private void overgrown_vegetation$dropStaleUnload(long chunkPos, ChunkHolder chunkHolder, CallbackInfo ci) {
        if (this.pendingUnloads.get(chunkPos) != chunkHolder) {
            ci.cancel();
        }
    }
}
