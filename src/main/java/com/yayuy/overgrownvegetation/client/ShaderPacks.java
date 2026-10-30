package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.neoforged.fml.ModList;

/**
 * Iris(Oculus)のシェーダーパックを使っているかの判定を 1 か所に(2026-10-23。前は {@code MoodPost.irisPackActive})。
 *
 * <p>パック使用中、Iris は自分のもの以外の {@code ShaderInstance} の色と奥行きの書き込みを止める({@code MixinShaderInstance} の
 * {@code apply} の後 → {@code DepthColorStorage.disableDepthColor})ので、自前のシェーダーの描画は何も残らない。経路の選び方:
 * <ul>
 *   <li>{@link #active()}: パック使用中。色の調子・解像度など、パックと二重にしない後処理はこれで外す。</li>
 *   <li>{@link #cloudsOff()}: この MOD の雲とその由来の演出(雲の影・薄明光線・雲の穴・雲を引く演出・尾流雲・ちぎれ雲など)を止める。
 *       設定に関係なく、パック使用中はパックの雲を優先する(10-23 ユーザー決定)。</li>
 *   <li>{@link #ownEffects()}: パック使用中で、設定「シェーダーパック使用中も MOD の演出を描く」がオン(既定オフ)。パック用の経路で描く。</li>
 * </ul>
 * パック無しの経路はどれも今まで通り。
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

    /** この MOD の雲とその由来の演出を止めるか(パック使用中は設定に関係なく止める)。 */
    public static boolean cloudsOff() {
        return active();
    }

    /** パック使用中に、この MOD の演出をパック用の経路で描くか(設定オン)。パック無しなら false。 */
    public static boolean ownEffects() {
        return active() && OvergrownClientConfig.CLIENT.effectsWithShaderPack.get();
    }

    private ShaderPacks() {
    }
}
