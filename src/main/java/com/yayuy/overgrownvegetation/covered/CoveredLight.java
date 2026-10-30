package com.yayuy.overgrownvegetation.covered;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * <b>覆われた光源は暗くなる</b>(2026-10-05、ユーザー要望「光源を持つブロックが覆われた時、光源レベルを -60%」)。
 *
 * <ul>
 *     <li><b>覆われたブロック</b>(海のランタン・銅の電球)は、元の明るさの 4 割を性質に持つ({@link CoveredBlocks})。</li>
 *     <li><b>汚染が座標の記録で覆ったブロック</b>(ランタン・グロウストーン・ろうそくなど)は、NeoForge の
 *     位置付きの明るさ({@code IBlockExtension#getLightEmission(state, level, pos)})を mixin で差し込み
 *     ({@code mixin/BlockCoveredLightMixin})、記録にあるマスだけ 4 割にする。ブロックは置き換えないので、
 *     レッドストーンランプのように明るさが変わるものも、変わった後の明るさの 4 割になる。</li>
 *     <li><b>汚染が中身ごと止めたブロック</b>(火の付いたかまど・焚き火など、{@code OvergrownHuskBlock})は、
 *     止めたときの明るさの 4 割を状態に持つ。</li>
 *     <li><b>松明</b>は暗くするのではなく、火の消えた松明に置き換える(ユーザー指定。{@link ExtinguishedTorchBlock})。</li>
 * </ul>
 *
 * <h2>明かりの計算は別のスレッドで走る</h2>
 * <p>サーバーの明かりの計算は専用のスレッドで、{@code level} にはサーバーの世界が渡ってくる。そこから
 * {@code level.getChunk} を呼ぶと<b>メインスレッドへ取りに行って待つ</b>(固まる・遅くなる)ので、明かりの計算と同じ
 * {@code getChunkForLighting}(読み込み済みのチャンクを覗くだけ)で引く。記録を読むのは書き換えと同時になりうるので、
 * 例外が出たら暗くしない側に倒す(次の明かりの計算で直る)。記録を書き換えたときは
 * {@link OvergrownLayerData#cover} / {@code uncover} がそのマスの明かりを計算し直させる。
 *
 * <p>クライアントの描画(チャンクの組み立て)は {@code RenderChunkRegion} を渡してくるのでここでは分からず、
 * <b>光源ブロック自身の面の明るさ</b>だけは元のまま(周りを照らす明るさは 4 割になる)。
 */
public final class CoveredLight {

    /** 残す明るさの割合(−60%)。 */
    public static final float KEEP = 0.4F;

    private CoveredLight() {
    }

    /** 明るさを 4 割にする(四捨五入。15 → 6、7 → 3、1 → 0)。 */
    public static int dim(int light) {
        return light <= 0 ? 0 : Math.round(light * KEEP);
    }

    /** mixin から。汚染が記録で覆ったマスの光源なら 4 割にする。{@code base} = いつもの明るさ(0 より大きい)。 */
    public static int emission(BlockState state, BlockGetter getter, BlockPos pos, int base) {
        try {
            LevelChunk chunk = chunkOf(getter, pos);
            if (chunk == null) {
                return base;
            }
            LongOpenHashSet set = chunk.getExistingData(ModAttachments.OVERGROWN_LAYER.get()).orElse(null);
            if (set == null || set.isEmpty() || !set.contains(pos.asLong())) {
                return base;
            }
            // 記録が古い(壊して別のブロックを置いた)ときは覆われていない。掃除は OvergrownLayerData#isCovered に任せる
            // (ここは別のスレッドなので記録を書き換えない)。
            return OvergrownLayerData.canCover(state) ? dim(base) : base;
        } catch (RuntimeException e) {
            return base;
        }
    }

    @Nullable
    private static LevelChunk chunkOf(BlockGetter getter, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (getter instanceof LevelChunk chunk) {
            return chunk.getPos().x == cx && chunk.getPos().z == cz ? chunk : null;
        }
        if (getter instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        if (getter instanceof ServerLevel server) {
            return unwrap(server.getChunkSource().getChunkForLighting(cx, cz));
        }
        if (getter instanceof Level level && level.isClientSide) {
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            return chunk instanceof LevelChunk levelChunk ? levelChunk : null;
        }
        return null;
    }

    @Nullable
    private static LevelChunk unwrap(@Nullable LightChunk chunk) {
        if (chunk instanceof LevelChunk levelChunk) {
            return levelChunk;
        }
        if (chunk instanceof ImposterProtoChunk imposter) {
            return imposter.getWrapped();
        }
        return null;
    }
}
