package com.yayuy.overgrownvegetation;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin を当てるかを、ほかの MOD の有無で決める。
 *
 * <p>Sodium・Embeddium は {@code LevelRenderer} の地形描画のメソッドを丸ごと上書きするので、そこへ刺さる Mixin は
 * {@code require = 0} でも当てる段階で落ちる(古い作りで 2026-10-20 に起きた)。地形描画へ刺す Mixin を足したら
 * {@link #VANILLA_CHUNK_RENDER} にも名前を足す。{@code client.sodium.*} は Sodium 本家がいるときだけ当てる。
 *
 * <p>この時点ではまだゲームのクラスを読み込めないので、MOD の有無は FML の {@link LoadingModList} で見る。
 */
public final class OvergrownMixinPlugin implements IMixinConfigPlugin {

    private static final String PREFIX = "com.yayuy.overgrownvegetation.mixin.";

    /** 地形の描画を差し替える MOD の id。 */
    private static final List<String> CHUNK_RENDERER_MODS = List.of("sodium", "embeddium");

    /** 地形の描画を差し替える MOD がいるときに当てないもの({@code PREFIX} を除いた名前)。 */
    private static final Set<String> VANILLA_CHUNK_RENDER = Set.of();

    /** 切り分け用: {@code -Dov.mixin.skipClient=true} でクライアント側の Mixin を Accessor・Invoker 以外ぜんぶ外す。 */
    private static final boolean SKIP_CLIENT = Boolean.getBoolean("ov.mixin.skipClient");

    private boolean chunkRendererReplaced;

    @Override
    public void onLoad(String mixinPackage) {
        chunkRendererReplaced = chunkRendererReplaced();
    }

    /** 地形の描画を差し替える MOD がいるか。 */
    public static boolean chunkRendererReplaced() {
        LoadingModList mods = LoadingModList.get();
        return mods != null && CHUNK_RENDERER_MODS.stream().anyMatch(id -> mods.getModFileById(id) != null);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PREFIX)) {
            return true;
        }
        String name = mixinClassName.substring(PREFIX.length());
        if (SKIP_CLIENT && name.startsWith("client.") && !name.endsWith("Accessor") && !name.endsWith("Invoker")) {
            return false;
        }
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
