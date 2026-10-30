package com.yayuy.overgrownvegetation.client.shader;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import net.neoforged.fml.ModList;

/**
 * Iris(Oculus)のシェーダーパックを使っているかの判定を 1 か所に(2026-10-23、10-30 作り直しで戻した)。
 *
 * <p>パック使用中、Iris は自分のもの以外の {@code ShaderInstance} の色と奥行きの書き込みを止めるので、自前のシェーダーの描画は何も残らない。
 * <ul>
 *   <li>{@link #active()}: パック使用中。</li>
 *   <li>{@link #ownEffects()}: パック使用中で、クライアント設定 {@code effectsWithShaderPack} がオン(既定オフ)。{@link OwnShaderInstance} が止めを外して描く。
 *   オフなら雰囲気の演出はパックに任せて描かない。</li>
 * </ul>
 */
public final class ShaderPacks {

    private static Boolean irisLoaded;
    private static java.lang.reflect.Method irisInUse;
    private static Object irisApi;

    /** Iris のシェーダーパックが使われているか(Iris の API {@code isShaderPackInUse} を名前で呼ぶ)。 */
    public static boolean active() {
        if (irisLoaded == null) {
            irisLoaded = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
            if (irisLoaded) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    irisApi = api.getMethod("getInstance").invoke(null);
                    irisInUse = api.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException | LinkageError e) {
                    irisInUse = null;
                }
            }
        }
        if (!irisLoaded || irisInUse == null) {
            return false;
        }
        try {
            return (Boolean) irisInUse.invoke(irisApi);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** パック使用中に、この MOD の演出をパック用の経路で描くか(設定オン)。パック無しなら false。 */
    public static boolean ownEffects() {
        return active() && VisualQuality.effectsWithShaderPack();
    }

    private ShaderPacks() {
    }
}
