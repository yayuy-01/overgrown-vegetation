package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.mixin.client.FrustumAccessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;

/**
 * 視点を回したときの「見える区画」の選び直しを速くする(2026-10-20)。
 *
 * <p>バニラ({@code LevelRenderer#applyFrustum} → {@code SectionOcclusionGraph#addSectionsInFrustum})は視点が 2° 動くたびに、
 * 閉塞グラフの区画を全部(描画距離 16 で 1〜2 万){@code LinkedHashSet} でたどり、区画の箱を 1 つずつ視錐台に当てる。
 * たどる所(集合の項目 → ノード → 区画 → 箱と、メモリのばらばらの所を続けて読む)が重く、ジャングルで振り回すと描画のスレッドの標本の半分を食っていた
 * (止めて 92 FPS・振り回して 71 FPS)。
 *
 * <p>ここでは区画と原点を平らな配列に写しておき、バニラと同じ当て方({@link FrustumIntersection#testAab}、同じ float の値)で選ぶ。
 * 選ぶ区画も順番もバニラと同じ。配列は、集合が入れ替わったとき(グラフの全部の作り直し)・増えたとき(部分の作り直しは足すだけなので、
 * 増えた分を後ろから足す)・区画の原点が動いたとき(カメラが区画をまたぐと {@code ViewArea} が区画を付け替える)に作り直す。
 * 描画のスレッドだけで使う。
 */
public final class SectionFrustumCache {
    /** devbridge の {@code fastcull on|off} で切り替えて比べる。 */
    public static volatile boolean enabled = true;
    /** 見える区画の一覧を、汚れた区画だけ・中身のある区画だけの短い一覧に替える({@code LevelRendererSectionListsMixin})。devbridge の {@code shortlists on|off}。 */
    public static volatile boolean shortLists = true;

    private static final MethodHandle STORAGE; // SectionOcclusionGraph.GraphState#storage
    private static final MethodHandle RENDER_SECTIONS; // SectionOcclusionGraph.GraphStorage#renderSections
    private static final MethodHandle NODE_SECTION; // SectionOcclusionGraph.Node#section
    private static final boolean READY;

    static {
        MethodHandle storage = null;
        MethodHandle renderSections = null;
        MethodHandle nodeSection = null;
        boolean ready = false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> state = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph$GraphState");
            Class<?> graphStorage = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage");
            Class<?> node = Class.forName("net.minecraft.client.renderer.SectionOcclusionGraph$Node");
            MethodType objToObj = MethodType.methodType(Object.class, Object.class);
            storage = MethodHandles.privateLookupIn(state, lookup).findGetter(state, "storage", graphStorage).asType(objToObj);
            renderSections = MethodHandles.privateLookupIn(graphStorage, lookup).findGetter(graphStorage, "renderSections", LinkedHashSet.class)
                    .asType(objToObj);
            nodeSection = MethodHandles.privateLookupIn(node, lookup).findGetter(node, "section", SectionRenderDispatcher.RenderSection.class)
                    .asType(objToObj);
            ready = true;
        } catch (Throwable t) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn("見える区画の選び直しの近道を使えない(バニラのまま): {}", t.toString());
        }
        STORAGE = storage;
        RENDER_SECTIONS = renderSections;
        NODE_SECTION = nodeSection;
        READY = ready;
    }

    /** 区画に足す口(mixin.client.RenderSectionDrawableMixin): 組み立て済みの中身が描く物を持つか。 */
    public interface Drawable {
        boolean overgrown_vegetation$drawable();
    }

    /** 区画の原点が動くたびに増える({@code RenderSection#setOrigin})。 */
    private static int originVersion;

    private static Object cachedSet;
    private static int cachedSize;
    private static int cachedVersion = -1;
    private static SectionRenderDispatcher.RenderSection[] sections = new SectionRenderDispatcher.RenderSection[0];
    private static int[] originX = new int[0];
    private static int[] originY = new int[0];
    private static int[] originZ = new int[0];
    /** 作り直した回数・足した回数(devbridge の表示用)。 */
    public static int rebuilds;
    public static int appends;

    /** {@code RenderSection#setOrigin} の後(描画のスレッド)。 */
    public static void onOriginChanged() {
        originVersion++;
    }

    public static int cachedSize() {
        return cachedSize;
    }

    /**
     * バニラの {@code addSectionsInFrustum} の代わり。
     *
     * @return false ならバニラに任せる(使えない・切ってある・ほかの MOD の視錐台)
     */
    public static boolean addSectionsInFrustum(Object graphState, Frustum frustum, List<SectionRenderDispatcher.RenderSection> out) {
        if (!enabled || !READY || graphState == null || frustum.getClass() != Frustum.class) {
            return false;
        }
        LinkedHashSet<?> set;
        try {
            set = (LinkedHashSet<?>) (Object) RENDER_SECTIONS.invokeExact(STORAGE.invokeExact(graphState));
            refresh(set);
        } catch (Throwable t) {
            return false;
        }
        FrustumAccessor access = (FrustumAccessor) frustum;
        FrustumIntersection intersection = access.overgrown_vegetation$intersection();
        double camX = access.overgrown_vegetation$camX();
        double camY = access.overgrown_vegetation$camY();
        double camZ = access.overgrown_vegetation$camZ();
        int n = cachedSize;
        SectionRenderDispatcher.RenderSection[] secs = sections;
        int[] xs = originX;
        int[] ys = originY;
        int[] zs = originZ;
        for (int i = 0; i < n; i++) {
            // バニラの箱は (x, y, z)〜(x+16, y+16, z+16) の double。Frustum#cubeInFrustum と同じく、カメラを引いてから float にする
            double x = xs[i];
            double y = ys[i];
            double z = zs[i];
            if (intersection.testAab((float) (x - camX), (float) (y - camY), (float) (z - camZ),
                    (float) (x + 16.0 - camX), (float) (y + 16.0 - camY), (float) (z + 16.0 - camZ))) {
                out.add(secs[i]);
            }
        }
        return true;
    }

    private static void refresh(LinkedHashSet<?> set) throws Throwable {
        int size = set.size();
        if (set == cachedSet && cachedVersion == originVersion) {
            if (size == cachedSize) {
                return;
            }
            if (size > cachedSize) {
                // 部分の作り直しは集合の後ろへ足すだけ。増えた分だけを後ろからたどる
                ensure(size);
                Iterator<?> it = set.reversed().iterator();
                for (int i = size - 1; i >= cachedSize; i--) {
                    put(i, it.next());
                }
                cachedSize = size;
                appends++;
                return;
            }
        }
        ensure(size);
        int i = 0;
        for (Object node : set) {
            put(i++, node);
        }
        if (i < cachedSize) {
            java.util.Arrays.fill(sections, i, cachedSize, null); // 前の区画を掴んだままにしない
        }
        cachedSet = set;
        cachedSize = i;
        cachedVersion = originVersion;
        rebuilds++;
    }

    private static void put(int i, Object node) throws Throwable {
        SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) (Object) NODE_SECTION.invokeExact(node);
        sections[i] = section;
        var origin = section.getOrigin();
        originX[i] = origin.getX();
        originY[i] = origin.getY();
        originZ[i] = origin.getZ();
    }

    private static void ensure(int size) {
        if (sections.length >= size) {
            return;
        }
        int cap = Math.max(size, sections.length + (sections.length >> 1) + 16);
        sections = java.util.Arrays.copyOf(sections, cap);
        originX = java.util.Arrays.copyOf(originX, cap);
        originY = java.util.Arrays.copyOf(originY, cap);
        originZ = java.util.Arrays.copyOf(originZ, cap);
    }

    private SectionFrustumCache() {
    }
}
