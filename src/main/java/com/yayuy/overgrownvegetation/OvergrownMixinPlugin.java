package com.yayuy.overgrownvegetation;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin を当てるかを、ほかの MOD の有無で決める(2026-10-20)。
 *
 * <p><b>地形の描画を差し替える MOD(Sodium・Embeddium)がいるときは、バニラの地形描画の近道を当てない</b>。
 * Sodium は {@code LevelRenderer} の {@code renderSectionLayer}・{@code setupRender} など 9 つを丸ごと上書きするので、
 * そこへ刺さる Mixin は {@code require = 0} でも当てる段階で落ちる(Mixin は他の Mixin が上書きした中身へは刺さらない。
 * Sodium 0.8.13 で起動できなかった)。近道は Sodium がいると使われない区画の一覧・バニラの描画の部品に効くものなので、
 * まとめて外しても見た目は変わらない(霧の奥の区画は Sodium の霧の切り捨てが切る)。
 *
 * <p>この時点ではまだゲームのクラスを読み込めないので、MOD の有無は FML の {@link LoadingModList} で見る。
 */
public final class OvergrownMixinPlugin implements IMixinConfigPlugin {

    private static final String PREFIX = "com.yayuy.overgrownvegetation.mixin.";

    /** 地形の描画を差し替える MOD の id。 */
    private static final List<String> CHUNK_RENDERER_MODS = List.of("sodium", "embeddium");

    /** 地形の描画を差し替える MOD がいるときに当てないもの(参照用の Accessor は当てたまま。中身の無い一覧を読むだけ)。 */
    private static final Set<String> VANILLA_CHUNK_RENDER = Set.of(
            "client.LevelRendererFastChunkDrawMixin",
            "client.LevelRendererSectionListsMixin",
            "client.LevelRendererPhaseMarkMixin",
            "client.LevelRendererFogCullMixin",
            "client.LevelRendererUploadBudgetMixin",
            "client.SectionOcclusionGraphFastCullMixin",
            "client.RenderSectionOriginMixin",
            "client.RenderSectionDrawableMixin",
            "client.RenderChunkLazyCopyMixin",
            "client.RenderRegionCacheLazyCopyMixin");

    private boolean chunkRendererReplaced;

    @Override
    public void onLoad(String mixinPackage) {
        chunkRendererReplaced = chunkRendererReplaced();
    }

    /** 地形の描画を差し替える MOD がいるか(いれば地形の近道の Mixin は当たっていない)。 */
    public static boolean chunkRendererReplaced() {
        LoadingModList mods = LoadingModList.get();
        return mods != null && CHUNK_RENDERER_MODS.stream().anyMatch(id -> mods.getModFileById(id) != null);
    }

    /**
     * 切り分け用(テストクライアントだけ): {@code -Dov.mixin.skipClient=true} でクライアント側の Mixin を Accessor・Invoker 以外ぜんぶ外す。
     * ほかの MOD の不具合がこの MOD の Mixin のせいかを確かめる(2026-10-20、DH の遠景が Sodium と一緒だと出ない件)。
     */
    private static final boolean SKIP_CLIENT = Boolean.getBoolean("ov.mixin.skipClient");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PREFIX)) {
            return true;
        }
        String name = mixinClassName.substring(PREFIX.length());
        if (SKIP_CLIENT && name.startsWith("client.") && !name.endsWith("Accessor") && !name.endsWith("Invoker")) {
            return false;
        }
        // Sodium の中へ刺すもの(client.sodium.*)は、Sodium 本家がいるときだけ(Embeddium はクラスもシェーダーも別物。2026-10-22、10-30 に地形のシェーダーと一緒に戻した)。
        if (name.startsWith("client.sodium.")) {
            LoadingModList mods = LoadingModList.get();
            return mods != null && mods.getModFileById("sodium") != null && mods.getModFileById("embeddium") == null;
        }
        return !(chunkRendererReplaced && VANILLA_CHUNK_RENDER.contains(name));
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
