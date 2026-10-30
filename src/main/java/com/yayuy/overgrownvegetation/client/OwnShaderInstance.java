package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import java.io.IOException;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * この MOD の自前のシェーダー(2026-10-23)。パック無しではふつうの {@link ShaderInstance} と同じ。
 *
 * <p><b>Iris のシェーダーパック使用中</b>、Iris は自分のもの以外のシェーダーの {@code apply()} の最後で色と奥行きの書き込みを止める
 * ({@code DepthColorStorage.disableDepthColor})ので、何も残らない。設定「シェーダーパック使用中も MOD の演出を描く」がオン
 * ({@link ShaderPacks#ownEffects})のときだけ、{@code apply()} の後にその止めを外し、パックの既定の枠(colortex と奥行き。
 * {@code IrisRenderingPipeline.bindDefault})へ描かせる。Iris の全体設定 {@code allowUnknownShaders=true} と同じことを、この MOD のシェーダーにだけする
 * ({@code clear()} で元の枠へ戻すのも同じ)。Iris の公開の API ではない名前を呼ぶので、見つからなければ以後は外さない(= 今まで通り出ない)。
 * 空の巨大生物はそのとき {@link #unmaskBroken()} を見てバニラのエンティティの描き方へ落とす。
 */
public class OwnShaderInstance extends ShaderInstance {

    private static boolean resolved;
    private static boolean broken;
    private static Method isLocked;
    private static Method unlock;
    private static Method pipelineNullable;
    private static Object pipelineManager;
    private static Class<?> irisPipelineClass;
    private static Method bindDefault;

    /** この apply で止めを外して Iris の枠を結んだか(clear で戻す)。 */
    private boolean unmasked;

    public OwnShaderInstance(ResourceProvider provider, ResourceLocation location, VertexFormat format) throws IOException {
        super(provider, location, format);
    }

    @Override
    public void apply() {
        super.apply();
        unmasked = false;
        if (ShaderPacks.ownEffects()) {
            unmasked = unmask();
        }
    }

    @Override
    public void clear() {
        super.clear();
        if (unmasked) {
            unmasked = false;
            // Iris が知らないシェーダーを描かせたときと同じく、ふつうの描画の枠へ戻す(以後のバニラのシェーダーがパックのものに替わる条件)
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    /** Iris の内部の名前が見つからず、パック使用中に自前のシェーダーで描けない(パック使用中で設定オンのときだけ意味がある)。 */
    public static boolean unmaskBroken() {
        if (!resolved) {
            resolve();
        }
        return broken;
    }

    /** 書き込みの止めを外し、パックの既定の枠を結ぶ。止められていなければ何もしない(影の描画中・世界の外など)。 */
    private static boolean unmask() {
        if (!resolved) {
            resolve();
        }
        if (broken) {
            return false;
        }
        try {
            if (!(Boolean) isLocked.invoke(null)) {
                return false;
            }
            Object pipeline = pipelineNullable.invoke(pipelineManager);
            if (!irisPipelineClass.isInstance(pipeline)) {
                return false;
            }
            unlock.invoke(null);
            bindDefault.invoke(pipeline);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("Iris の書き込みの止めを外せなかったので、シェーダーパック使用中はこの MOD の演出を描かない", e);
            return false;
        }
    }

    private static void resolve() {
        resolved = true;
        // 確かめ用: -Dov.irisNoUnmask=true で「外せない」ときの経路(空の巨大生物のバニラの描き方)を試す
        if (Boolean.getBoolean("ov.irisNoUnmask")) {
            broken = true;
            return;
        }
        try {
            Class<?> storage = Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage");
            isLocked = storage.getMethod("isDepthColorLocked");
            unlock = storage.getMethod("unlockDepthColor");
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            pipelineManager = iris.getMethod("getPipelineManager").invoke(null);
            pipelineNullable = pipelineManager.getClass().getMethod("getPipelineNullable");
            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            bindDefault = irisPipelineClass.getMethod("bindDefault");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            broken = true;
            if (ShaderPacks.active()) {
                OvergrownVegetation.LOGGER.warn("この版の Iris では書き込みの止めを外せないので、シェーダーパック使用中はこの MOD の演出を描かない(空の巨大生物はバニラの描き方): {}", e.toString());
            }
        }
    }
}
