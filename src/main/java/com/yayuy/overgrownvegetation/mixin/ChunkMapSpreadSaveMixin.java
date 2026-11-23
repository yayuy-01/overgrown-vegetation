package com.yayuy.overgrownvegetation.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 自動保存を tick に分ける(2026-11-23 サーバーの tick の軽量化・ユーザー了承「おすすめ通りで」)。
 *
 * <p>バニラ 1.21.1 は {@code ChunkMap#processUnloads} で、その tick に<b>余り時間があるときだけ</b>変わったチャンクを 1 tick に 20 個まで
 * 先に書き出す({@code saveChunkIfNeeded}。書いたチャンクは 10 秒は書かない)。森が広がって tick が 50 ms を超え続けると余り時間が無く、
 * 読み込み中の全チャンクの書き出しが自動保存の 1 tick に溜まって秒の桁で止まっていた(D5 の 39 日目で 5 秒。書き出しの 9 割はバニラの
 * チャンクの符号化)。
 *
 * <p>ここでは余り時間が無かった tick に限り、同じ {@code saveChunkIfNeeded} を 1 tick に {@link #SAVES} 個まで回す(見る数は {@link #SCAN} まで。
 * 続きから順に)。書き出しの道・中身・書き出し先はバニラの先書きと同じで、ディスクに書かれる時刻が自動保存より前にずれるだけ。
 * 自動保存・{@code /save-all}・終わるときの保存は今までどおり全部を書く(ここで先に書いた分は「変わっていない」ので飛ばされる)。
 * チャンクの仕組みを差し替える MOD と一緒のときは当たらないので {@code require = 0}。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSpreadSaveMixin {

    @Unique
    private static final int SAVES = 1, SCAN = 64;

    @Shadow
    private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    protected abstract boolean saveChunkIfNeeded(ChunkHolder holder);

    /** 続きから見るための、前の tick の並びとその中の位置(並びは変わるたびに丸ごと作り直されるので、替わったら頭から)。 */
    @Unique
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> overgrown_vegetation$cursorMap;
    @Unique
    private ObjectIterator<ChunkHolder> overgrown_vegetation$cursor;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"), require = 0)
    private void overgrown_vegetation$spreadSave(BooleanSupplier hasMoreTime, CallbackInfo ci) {
        if (this.level.noSave() || hasMoreTime.getAsBoolean()) return; // 余り時間があればバニラが書く
        Long2ObjectLinkedOpenHashMap<ChunkHolder> map = this.visibleChunkMap;
        if (map != this.overgrown_vegetation$cursorMap || this.overgrown_vegetation$cursor == null) {
            this.overgrown_vegetation$cursorMap = map;
            this.overgrown_vegetation$cursor = map.values().iterator();
        }
        com.yayuy.overgrownvegetation.perf.SpreadSave.ticks++;
        int saved = 0;
        for (int seen = 0; seen < SCAN && saved < SAVES; seen++) {
            if (!this.overgrown_vegetation$cursor.hasNext()) {
                this.overgrown_vegetation$cursor = map.values().iterator(); // 一巡したら頭から(次の tick で)
                break;
            }
            if (this.saveChunkIfNeeded(this.overgrown_vegetation$cursor.next())) saved++;
        }
        com.yayuy.overgrownvegetation.perf.SpreadSave.saved += saved;
    }
}
