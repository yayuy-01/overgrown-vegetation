package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.ForestLightningPayload;
import com.yayuy.overgrownvegetation.network.WeatherPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

import java.util.function.Consumer;

/**
 * サーバーから届く「今の天気」の受け口(2026-11-16 weather-system。{@link WeatherPayload})。値を持つだけで、寄せる(描画用に滑らかにする)のは
 * 見た目の側(自然環境とシェーダーの調整)。値はサーバーで立ち上がりを掛けた後の 0〜1 なので、ここで二重に立ち上がりを掛けない。
 *
 * <ul>
 *   <li>{@link #received()} = 1 通でも届いたか(ワールドに入った直後の最初の 1 通で一気に合わせる用)。{@link #lastTick()} = 届いたときのクライアントの tick。</li>
 *   <li>ディメンションを移るとサーバーがすぐ送り直す(オーバーワールド以外は全部 0)。ログアウトで消す。</li>
 *   <li>確かめ用の上書き {@link #debugOverride}: 上書き中は届いた値より優先(サーバー側ができる前から見た目を作るため)。</li>
 *   <li>汚染の森への落雷({@link ForestLightningPayload})は {@link #lightningListener} へそのまま渡す(1 本の雷につき 1 通)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class WeatherClient {

    private static WeatherPayload last = WeatherPayload.NONE;
    private static boolean received;
    private static long lastTick;
    private static WeatherPayload override;

    /** 汚染の森に雷が落ちたときに呼ぶ(見た目の側が差し替える。既定は何もしない)。 */
    public static volatile Consumer<ForestLightningPayload> lightningListener = p -> {
    };

    public static void accept(WeatherPayload payload) {
        last = payload;
        received = true;
        var mc = net.minecraft.client.Minecraft.getInstance();
        lastTick = mc.level != null ? mc.level.getGameTime() : 0L;
    }

    public static void acceptLightning(ForestLightningPayload payload) {
        lightningListener.accept(payload);
    }

    /** 積乱雲から地面への落雷のときに呼ぶ(見た目の側が差し替える。既定は何もしない。11-16 CloudStrikePayload)。 */
    public static volatile Consumer<com.yayuy.overgrownvegetation.network.CloudStrikePayload> strikeListener = p -> {
    };

    public static void acceptStrike(com.yayuy.overgrownvegetation.network.CloudStrikePayload payload) {
        strikeListener.accept(payload);
    }

    private static WeatherPayload now() {
        WeatherPayload o = override;
        return o != null ? o : last;
    }

    /** 1 通でも届いたか(上書き中は true)。 */
    public static boolean received() {
        return override != null || received;
    }

    /** 最後に届いたときのクライアントのワールドの tick。 */
    public static long lastTick() {
        return lastTick;
    }

    public static float fog() {
        return now().fog();
    }

    /** 濃霧の種類 0 = 朝方・1 = 雨上がり。 */
    public static int fogKind() {
        return now().fogKind();
    }

    public static float calm() {
        return now().calm();
    }

    public static float gale() {
        return now().gale();
    }

    public static float stormAfter() {
        return now().stormAfter();
    }

    public static float thunder() {
        return now().thunder();
    }

    public static float preStorm() {
        return now().preStorm();
    }

    /** 今の濃霧・凪が終わるまでの tick(届いたときの値)。 */
    public static int ticksLeft() {
        return now().ticksLeft();
    }

    /** 確かめ用に値を上書きする(clearDebugOverride で戻す)。 */
    public static void debugOverride(float fog, float calm, float gale, float stormAfter, float thunder, float preStorm) {
        override = new WeatherPayload(fog, (byte) 0, calm, gale, stormAfter, thunder, preStorm, 0, 0L);
    }

    public static void clearDebugOverride() {
        override = null;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        last = WeatherPayload.NONE;
        received = false;
        lastTick = 0L;
        override = null;
    }

    private WeatherClient() {
    }
}
