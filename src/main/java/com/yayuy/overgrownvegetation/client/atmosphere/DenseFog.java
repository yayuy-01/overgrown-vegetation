package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * <b>濃霧</b>の見た目(天気の 1 つ。2026-11-16。ユーザー決定: たまに雨上がりや朝方に世界中が濃霧に包まれる。本物の多重散乱は重いので、
 * 前方散乱・光の柔らかいにじみ・高さで薄くなる・木々の間の光の筋を組み合わせて近づける。絵を見て詰める)。
 *
 * <p>理由: 「美しいが怖い」E(霧の中のシルエット)の舞台。見通しが落ち、遠征に危ない朝になる。胞子の濃さは変えない(サーバー側の決まり)。
 *
 * <h2>第 1 版(この版)の作り</h2>
 * サーバーの濃霧の度合い({@link WeatherClient#fog}。立ち上がりはサーバーで掛けてある)を 5 秒おきの知らせの間だけ寄せて持ち、今ある霧の口へ足す:
 * <ul>
 *     <li><b>距離の霞</b>({@link #hazeK}): {@code MistView.renderFog} の「距離で濃くなる霞」(胞子の空気と同じ印)へ足す。地形・Mob・粒・雲が同じ霞になる
 *     (11-01 の決まり)。見通し {@code visibility} ブロックで 95% が霧。洞窟の中は足さない。</li>
 *     <li><b>空</b>({@link #skyFill}): 空の丸天井・雲・空の色を霧の色で満たす({@code MistView.whiteSky} へ足す)。</li>
 *     <li><b>色</b>({@link #tint}): 霧の色は昼は白い灰色、夜は暗い青灰色。<b>前方散乱</b>: 太陽(月)の方を向くほど霧が明るく暖かく光る
 *     (画面全体の色を、見ている向きで変える。画素ごとではない)。</li>
 *     <li><b>高さ</b>({@link #heightFactor}): カメラが霧の層の上({@code topAbove} = 海面からの高さ)へ出ると、{@code topFade} 段かけて晴れる。
 *     画素ごとではないので、高い所から見下ろしても下に霧の海は見えない(第 2 版の候補)。</li>
 * </ul>
 * 数は design の節 {@code denseFog}(すべて仮決め)。確かめは {@code /ov weather fog 1}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class DenseFog {

    private DenseFog() {
    }

    private static float value;
    private static boolean primed;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !WeatherClient.received()) {
            value = 0.0F;
            primed = false;
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        float target = Mth.clamp(WeatherClient.fog(), 0.0F, 1.0F);
        if (!primed) {
            // ワールドに入った直後の最初の 1 通は、その場の値へ一気に合わせる。
            value = target;
            primed = true;
            return;
        }
        // 5 秒ごとの知らせの間を寄せるだけ(立ち上がりはサーバーの値に入っている)。時定数 約 3 秒。
        value += (target - value) * 0.017F;
        if (Math.abs(target - value) < 0.002F) {
            value = target;
        }
    }

    /** 濃霧の度合い(0〜1。高さを掛ける前)。 */
    public static float level() {
        return VisualQuality.fog() ? value : 0.0F;
    }

    /** カメラの高さの分(霧の層の中 1、層の上 0)。 */
    static float heightFactor() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return 0.0F;
        }
        AtmosphereEye.DenseFogParams p = DesignData.atmosphere().denseFog();
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        float top = level.getSeaLevel() + p.topAbove();
        return 1.0F - SunLight.smooth(Mth.clamp((float) ((y - top) / Math.max(1.0F, p.topFade())), 0.0F, 1.0F));
    }

    /** カメラの所の濃霧の度合い(0〜1。高さ込み)。 */
    public static float amount() {
        float v = level();
        return v <= 0.001F ? 0.0F : v * heightFactor();
    }

    /** 距離の霞の濃さ(1 ブロックあたり)。見通し visibility ブロックで 95% が霧(exp(−3))。 */
    static float hazeK() {
        float a = amount();
        return a <= 0.001F ? 0.0F : a * 3.0F / Math.max(8.0F, DesignData.atmosphere().denseFog().visibility());
    }

    /** 同じ値(ミキシンから読む用)。 */
    public static float skyFillPublic() {
        return skyFill();
    }

    /** 空の丸天井を霧の色で満たす割合(0〜1)。 */
    static float skyFill() {
        return Mth.clamp(amount() * DesignData.atmosphere().denseFog().skyFill(), 0.0F, 1.0F);
    }

    /**
     * 霧の色を、今の霧の色 {@code rgb}(長さ 3、書き換える)へ混ぜる。{@code look} = 見ている向き。
     * 昼の色 → 夜は明るさを落とす。太陽(夜は月)の方を向くほど、暖かい色を足す(前方散乱)。
     */
    static void tint(ClientLevel level, float partialTick, Vector3f look, float[] rgb) {
        float a = amount();
        if (a <= 0.001F) {
            return;
        }
        AtmosphereEye.DenseFogParams p = DesignData.atmosphere().denseFog();
        float night = SunLight.night(level, partialTick);
        float bright = Mth.lerp(night, 1.0F, p.nightBrightness()) * (1.0F - 0.25F * level.getRainLevel(partialTick));
        float r = p.red() * bright;
        float g = p.green() * bright;
        float b = p.blue() * bright;
        Vec3 sun = SunLight.lightDirection(level, partialTick);
        float facing = (float) Math.max(0.0, look.x() * sun.x + look.y() * sun.y + look.z() * sun.z);
        // 低い太陽ほど暖かく強く(朝の霧)。夜(月)は弱く青白く。
        float low = 1.0F - Mth.clamp((float) sun.y, 0.0F, 1.0F);
        float glow = p.scatter() * (float) Math.pow(facing, p.scatterPower()) * Mth.lerp(night, 0.6F + 0.4F * low, 0.25F);
        r += glow * Mth.lerp(night, 1.0F, 0.55F);
        g += glow * Mth.lerp(night, 0.86F + 0.1F * (1.0F - low), 0.62F);
        b += glow * Mth.lerp(night, 0.62F + 0.3F * (1.0F - low), 0.75F);
        rgb[0] = Mth.lerp(a, rgb[0], Math.min(1.0F, r));
        rgb[1] = Mth.lerp(a, rgb[1], Math.min(1.0F, g));
        rgb[2] = Mth.lerp(a, rgb[2], Math.min(1.0F, b));
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("濃霧 %.2f(届いた値 %.2f・高さ %.2f・霞 %.4f/ブロック・空 %.2f)", level(), WeatherClient.fog(), heightFactor(), hazeK(), skyFill());
    }
}
