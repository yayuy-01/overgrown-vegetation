package com.yayuy.overgrownvegetation.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;

/**
 * 区画を組み立てる前のチャンクの写しを、組み立てる区画とその上下 1 区画だけにする(2026-10-20)。
 *
 * <p>バニラ({@code RenderRegionCache} → {@code RenderChunk} の作り)は、区画を 1 つ組み立てるたびに、まわり 3 × 3 のチャンクの
 * 空気でない区画を<b>全部</b>(高さ 24 区画ぶん)描画のスレッドで写す。写しはそのフレームの中だけ使い回すので、飛んで新しいチャンクが
 * 次々届くと毎フレーム写し直す(描画のスレッドの確保の 9 割・飛行中に毎秒数百 MB。GC の引っかかりの元)。
 * 組み立て({@code SectionCompiler}・NeoForge の {@code AddSectionGeometryEvent}・この MOD の葉の色)が読むのは区画のまわり 1 ブロックまでで、
 * NeoForge のモデルデータの写しも上下 1 区画({@link RenderChunkRegion#RADIUS})しか取らない。そこで写しを遅らせ、区画の場所が分かった所
 * ({@code createRegion} の終わり、まだ描画のスレッド)で上下 1 区画だけ写す。写していない区画は空気に見える。
 * 同じフレームで同じチャンクの別の高さを組み立てるときは、足りない区画だけ一覧のその場所へ足す(一覧は差し替えない。理由は mixin の説明)。
 */
public final class LazySectionCopy {
    /** devbridge の {@code lazycopy on|off}。 */
    public static volatile boolean enabled = true;

    private static final MethodHandle CHUNKS; // RenderChunkRegion#chunks
    private static final boolean READY;

    static {
        MethodHandle chunks = null;
        boolean ready = false;
        try {
            Class<?> renderChunk = Class.forName("net.minecraft.client.renderer.chunk.RenderChunk");
            chunks = MethodHandles.privateLookupIn(RenderChunkRegion.class, MethodHandles.lookup())
                    .findGetter(RenderChunkRegion.class, "chunks", renderChunk.arrayType())
                    .asType(MethodType.methodType(Object[].class, RenderChunkRegion.class));
            ready = true;
        } catch (Throwable t) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn("区画の写しを絞れない(バニラのまま): {}", t.toString());
        }
        CHUNKS = chunks;
        READY = ready;
    }

    /** {@code RenderChunk} に足す口(mixin.client.RenderChunkLazyCopyMixin)。 */
    public interface Copier {
        /** まだ写していなければ、区画の高さ(区画の座標)minY〜maxY を写す。遅らせていない写しなら何もしない。 */
        void overgrown_vegetation$ensureSections(int minSectionY, int maxSectionY);
    }

    /** 新しい {@code RenderChunk} の写しを遅らせてよいか(作る時)。 */
    public static boolean deferOnCreate() {
        return enabled && READY;
    }

    /** {@code createRegion} の終わり。返す区画のまわりのチャンクに、上下 1 区画を写させる。 */
    public static void ensure(RenderChunkRegion region, int sectionY) {
        if (!READY || region == null) {
            return;
        }
        Object[] chunks;
        try {
            chunks = (Object[]) CHUNKS.invokeExact(region);
        } catch (Throwable t) {
            return;
        }
        int lo = sectionY - RenderChunkRegion.RADIUS;
        int hi = sectionY + RenderChunkRegion.RADIUS;
        for (Object c : chunks) {
            if (c instanceof Copier copier) {
                copier.overgrown_vegetation$ensureSections(lo, hi);
            }
        }
    }

    private LazySectionCopy() {
    }
}
