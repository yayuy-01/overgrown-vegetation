package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import java.lang.reflect.Method;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Distant Horizons(DH)と並べたときの手当て(2026-10-20)。DH が無ければ何もしない。DH の型はコンパイルに入れず、DH の API を名前で呼ぶ。
 *
 * <p>DH は既定で次の 3 つをする(どれも DH の設定 {@code config/DistantHorizons.toml}):
 * <ul>
 *     <li>自分の雲を描く({@code enableCloudRendering})+ ふつうの雲の設定を切る({@code overrideVanillaGraphicsSettings})。
 *     この MOD のボクセルの雲はふつうの雲の設定が切だと描かなかったので、DH の四角い雲に置き換わっていた。
 *     → DH の API で DH の雲だけを止め({@code cloudRenderingEnabled}。ファイルは書き換えない一時の上書き)、DH があるときはボクセルの雲を
 *     この MOD の設定({@code voxelClouds})だけで出す。近くの雲はバニラの雲の入口({@code renderClouds}、雲の設定が切だと呼ばれない)の代わりに、
 *     同じ所(粒の後)でここから描く。</li>
 *     <li>ふつうの霧を切る({@code enableVanillaFog = false}。{@code FogRenderer#setupFog} の終わりで上書き)。汚染地域の濃い霧・朝霧の霧も消えていた。
 *     → この MOD が霧を上書きしている間({@link CorruptionAtmosphere#terrainFogFar()} が 0 以上)だけ、DH の API でふつうの霧を有効にする。
 *     さらに霧で区画を打ち切っている間(濃い霧)は DH の遠景の霧をいちばん濃くして({@code farFogMinThickness}・{@code farFogMaxThickness} を 1)、
 *     霧の壁の向こうに DH の遠景がくっきり見えないようにする。<b>遠景のオン・オフ({@code renderingEnabled})は触らない</b>: DH の中で
 *     {@code rendererMode} に換えて渡され、DH が設定ファイルへ {@code rendererMode = "DISABLED"} を書き残した(霧を出ても・起動し直しても
 *     DH の遠景が消えたまま。2026-10-20 に Sodium と並べて見つけた。雲・霧の上書きはファイルに残らない)。</li>
 *     <li>ふつうの描画を DH の遠景へ溶かし込む({@code vanillaFadeMode = DOUBLE_PASS})。描画距離より遠い深さの絵を塗り直すので、空の巨大生物は
 *     その後に描く({@code skyboss.SkyBossRenderer})。ボクセルの雲の遠い層(奥のクリップ面の 0.9 倍より遠い所)はまだ消される(残り)。</li>
 * </ul>
 * 上書きは状態が変わったときだけ送る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class DistantHorizonsCompat {
    public static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    private static boolean ready;
    private static boolean failed;
    private static Object cloudValue;
    private static Object vanillaFogValue;
    private static Object farFogMinValue;
    private static Object farFogMaxValue;
    private static Method setValue;
    private static Method clearValue;
    /** 最後に送った状態(-1 まだ送っていない・0 上書きを外した・1 上書きした)。 */
    private static int cloudsSent = -1;
    private static int fogSent = -1;
    private static int farFogMinSent = -1;
    private static int farFogMaxSent = -1;
    /** 濃い霧の間の DH の遠景の霧の濃さ(1 = 遠景は霧の色だけ)。 */
    private static final Float HIDDEN_FAR_FOG = 1.0F;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!LOADED || failed) {
            return;
        }
        if (!ready && !init()) {
            return;
        }
        // DH の雲: ボクセルの雲を出すときだけ止める
        // シェーダーパック使用中はこの MOD の雲を描かないので、DH の雲は DH(と Iris のパックの指定)に任せる(10-23)
        boolean ours = OvergrownVegetationConfig.COMMON.voxelClouds.get() && !ShaderPacks.cloudsOff();
        cloudsSent = send("雲", cloudValue, ours, Boolean.FALSE, cloudsSent);
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null;
        // ふつうの霧: この MOD が霧を上書きしている間だけ有効に
        boolean ourFog = inWorld && CorruptionAtmosphere.terrainFogFar() >= 0.0F;
        fogSent = send("ふつうの霧", vanillaFogValue, ourFog, Boolean.TRUE, fogSent);
        // DH の遠景: 霧で区画を打ち切っている(濃い霧の)間は、遠景の霧をいちばん濃くして隠す
        boolean thickFog = inWorld && CorruptionAtmosphere.terrainFogCullFarPlane() >= 0.0F;
        farFogMinSent = send("遠景の霧(薄い所)", farFogMinValue, thickFog, HIDDEN_FAR_FOG, farFogMinSent);
        farFogMaxSent = send("遠景の霧(濃い所)", farFogMaxValue, thickFog, HIDDEN_FAR_FOG, farFogMaxSent);
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
            OvergrownVegetation.LOGGER.warn("Distant Horizons の設定を上書きできなかった(手当てをやめる): {}", e.toString());
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
            Object generic = call(graphics, api + "client.IDhApiGraphicsConfig", "genericRendering");
            Object fog = call(graphics, api + "client.IDhApiGraphicsConfig", "fog");
            Class<?> valueType = Class.forName(api + "IDhApiConfigValue");
            setValue = valueType.getMethod("setValue", Object.class);
            clearValue = valueType.getMethod("clearValue");
            Method canOverride = valueType.getMethod("getCanBeOverrodeByApi");
            cloudValue = overridable(call(generic, api + "client.IDhApiGenericRenderingConfig", "cloudRenderingEnabled"), canOverride);
            vanillaFogValue = overridable(call(fog, api + "client.IDhApiFogConfig", "enableVanillaFog"), canOverride);
            Object farFog = call(fog, api + "client.IDhApiFogConfig", "farFog");
            farFogMinValue = overridable(call(farFog, api + "client.IDhApiFarFogConfig", "farFogMinThickness"), canOverride);
            farFogMaxValue = overridable(call(farFog, api + "client.IDhApiFarFogConfig", "farFogMaxThickness"), canOverride);
            ready = true;
            OvergrownVegetation.LOGGER.info("Distant Horizons の手当てを始めた(DH の雲を止める・この MOD の霧の間はふつうの霧を有効に・濃い霧の間は遠景を霧で隠す)");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            OvergrownVegetation.LOGGER.warn("Distant Horizons の API を使えない(手当てをしない): {}", e.toString());
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

    /**
     * 近くのボクセルの雲(バニラの雲の入口の代わり)。DH がふつうの雲の設定を切っているとバニラは {@code renderClouds} を呼ばないので、
     * 同じ所(粒の後・天気の前)で描く。ふつうの雲の設定が入っていればバニラの入口が描くので、ここは何もしない(二重に描かない)。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!LOADED || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.options.getCloudsType() != CloudStatus.OFF || !VoxelClouds.active()) {
            return;
        }
        if (mc.levelRenderer.getCloudsTarget() != null) {
            mc.levelRenderer.getCloudsTarget().clear(Minecraft.ON_OSX); // 「最高」の描画のときバニラが雲の前にすること
        }
        Vec3 cam = event.getCamera().getPosition();
        VoxelClouds.render(level, event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getModelViewMatrix(), event.getProjectionMatrix(),
                cam.x, cam.y, cam.z);
    }

    private DistantHorizonsCompat() {
    }
}
