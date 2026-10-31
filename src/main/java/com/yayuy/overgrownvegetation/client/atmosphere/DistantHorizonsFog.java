package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;

/**
 * <b>Distant Horizons(DH)と霧</b>(第 3 段「霧」、10-30 夜。元は parked の {@code client/DistantHorizonsCompat} の霧の分。雲の分は雲の段で)。
 * DH が無ければ何もしない。DH の型はコンパイルに入れず、DH の API を名前で呼ぶ(メモリ distant-horizons-compat)。
 *
 * <ul>
 *     <li>DH は既定でふつうの霧を切る({@code enableVanillaFog = false})。朝霧の中でバニラの霧を 64 ブロックへ寄せる間({@link MistView})だけ、
 *     DH の API でふつうの霧を有効にする。</li>
 *     <li>その間は DH の遠景の霧をいちばん濃くして({@code farFogMinThickness}・{@code farFogMaxThickness} を 1)、64 ブロックの霧の壁の向こうに
 *     DH の遠景がくっきり見えないようにする。</li>
 *     <li>森の中の胞子の霧(10-31、{@link MistView} が地形の霧を 96 ブロックへ)の間も同じ: ふつうの霧を有効にして遠景を霧で隠し、森を出たら戻す。</li>
 *     <li><b>遠景のオン・オフ({@code renderingEnabled})は触らない</b>(API で触ると DH が設定ファイルに {@code rendererMode = "DISABLED"} を書き残す)。</li>
 *     <li>DH の遠景には地形シェーダーの高さの霧は掛からない(遠景は DH のシェーダー)。層の上から見下ろす朝の谷の霧は、描画距離の中だけ。</li>
 * </ul>
 * 上書きは状態が変わったときだけ送る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class DistantHorizonsFog {

    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    /** 霧の中の DH の遠景の霧の濃さ(1 = 遠景は霧の色だけ)。 */
    private static final Float HIDDEN_FAR_FOG = 1.0F;

    private static boolean ready;
    private static boolean failed;
    private static Object vanillaFogValue;
    private static Object farFogMinValue;
    private static Object farFogMaxValue;
    private static Method setValue;
    private static Method clearValue;
    private static int fogSent = -1;
    private static int farFogMinSent = -1;
    private static int farFogMaxSent = -1;
    /** 森の中の胞子の霧で DH を上書きしているか(森の縁で行き来して設定を書き続けないよう 0.6 で入り 0.4 で出る)。 */
    private static boolean inForest;

    private DistantHorizonsFog() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!LOADED || failed) {
            return;
        }
        if (!ready && !init()) {
            return;
        }
        // 設定で霧を切っている間・雰囲気全体を切っている間は上書きを外す(2026-11-01)。
        boolean inWorld = Minecraft.getInstance().level != null && VisualQuality.fog();
        float inside = inWorld ? MistFog.cameraInside() : 0.0F;
        float forest = inWorld ? MistView.sporeFog() : 0.0F;
        inForest = inForest ? forest > 0.4F : forest >= 0.6F;
        fogSent = send("ふつうの霧", vanillaFogValue, inside > 0.001F || inForest, Boolean.TRUE, fogSent);
        boolean thick = inside > 0.5F || inForest;
        farFogMinSent = send("遠景の霧(薄い所)", farFogMinValue, thick, HIDDEN_FAR_FOG, farFogMinSent);
        farFogMaxSent = send("遠景の霧(濃い所)", farFogMaxValue, thick, HIDDEN_FAR_FOG, farFogMaxSent);
    }

    /** 上書きする/外すが前と変わったときだけ DH へ送る。 */
    private static int send(String name, Object configValue, boolean override, Object value, int last) {
        int now = override ? 1 : 0;
        if (now == last || configValue == null) {
            return now;
        }
        try {
            if (override) {
                setValue.invoke(configValue, value);
            } else {
                clearValue.invoke(configValue);
            }
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("Distant Horizons: {} を{}", name, override ? " " + value + " に上書き" : "元に戻した");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            OvergrownVegetation.LOGGER.warn("Distant Horizons の設定を上書きできなかった(霧の手当てをやめる): {}", e.toString());
        }
        return now;
    }

    /** DH の API が使えるようになっていれば、上書きする値を取っておく(DH が立ち上がるまでは null のまま。毎 tick 試す)。 */
    private static boolean init() {
        try {
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object configs = delayed.getField("configs").get(null);
            if (configs == null) {
                return false;
            }
            // 実装のクラスは外から触れないことがあるので、公開の API の型(インターフェース)からメソッドを引く
            String api = "com.seibel.distanthorizons.api.interfaces.config.";
            Object graphics = call(configs, api + "IDhApiConfig", "graphics");
            Object fog = call(graphics, api + "client.IDhApiGraphicsConfig", "fog");
            Class<?> valueType = Class.forName(api + "IDhApiConfigValue");
            setValue = valueType.getMethod("setValue", Object.class);
            clearValue = valueType.getMethod("clearValue");
            Method canOverride = valueType.getMethod("getCanBeOverrodeByApi");
            vanillaFogValue = overridable(call(fog, api + "client.IDhApiFogConfig", "enableVanillaFog"), canOverride);
            Object farFog = call(fog, api + "client.IDhApiFogConfig", "farFog");
            farFogMinValue = overridable(call(farFog, api + "client.IDhApiFarFogConfig", "farFogMinThickness"), canOverride);
            farFogMaxValue = overridable(call(farFog, api + "client.IDhApiFarFogConfig", "farFogMaxThickness"), canOverride);
            ready = true;
            OvergrownVegetation.LOGGER.info("Distant Horizons の霧の手当てを始めた(朝霧の中はふつうの霧を有効に・遠景を霧で隠す)");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            OvergrownVegetation.LOGGER.warn("Distant Horizons の API を使えない(霧の手当てをしない): {}", e.toString());
            return false;
        }
    }

    private static Object call(Object target, String type, String method) throws ReflectiveOperationException {
        return Class.forName(type).getMethod(method).invoke(target);
    }

    /** API から上書きできない値(DH の設定で禁じられている)なら null。 */
    private static Object overridable(Object configValue, Method canOverride) throws ReflectiveOperationException {
        return configValue != null && Boolean.TRUE.equals(canOverride.invoke(configValue)) ? configValue : null;
    }
}
