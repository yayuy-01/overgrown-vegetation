package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import com.yayuy.overgrownvegetation.sky.RainIntensity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * カメラの所の雨の強さ(2026-11-12、雨の種類。ユーザー選択「雨ごと + 真上の雲」「雨粒と水面・音・見通しと空」)。
 *
 * <p>世界で 1 つの強さ({@link RainIntensity#global}: 雨ごとの小雨・普通・大雨 × 降り始めと止み際)に、真上の雲で強弱を付ける:
 * 積乱雲・入道雲の下は {@link #UNDER_STORM} 倍、雲の床の下は厚さで {@link #FLOOR_THIN}〜{@link #FLOOR_THICK} 倍、雲の無い所(雲の切れ目)は
 * {@link #NO_CLOUD} 倍。歩いて雲の下を出入りしても跳ばないよう、ゆっくり寄せる。見た目と音だけ(サーバーの天気・胞子は変えない)。
 *
 * <p>効かせ方: クライアントの雨の強さ({@code Level.getRainLevel}、{@code LevelRainAboveCloudsMixin})に {@link #rainScale} を掛ける。バニラの雨の筋の濃さ・
 * 水しぶきの数・雨の日の空の暗さ、この MOD の水面の雨の輪・霞などが一緒に強弱を持つ。雨音は {@code LevelRendererRainSoundMixin}、
 * 大雨の霞の上乗せは {@link MistView#aerial}、雲の暗さは {@code VoxelClouds#roughness}。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class RainFeel {

    static final float UNDER_STORM = 1.35F;
    static final float FLOOR_THIN = 0.8F;
    static final float FLOOR_THICK = 1.05F;
    static final float NO_CLOUD = 0.55F;
    /** 雲の床の厚さ(ブロック)がこれ以上なら FLOOR_THICK。 */
    static final float FLOOR_FULL = 50.0F;

    private static float intensity;
    private static float local = 1.0F;
    private static float localTarget = 1.0F;
    private static int ticks;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            intensity = 0.0F;
            local = 1.0F;
            return;
        }
        try {
            float rain = CloudSky.rawRain(level, 1.0F);
            float thunder = CloudSky.rawThunder(level, 1.0F);
            float global = RainIntensity.global(level, rain > 0.0F, thunder > 0.2F, 0.0F);
            if (++ticks % 10 == 0) {
                localTarget = localFactor(level, mc.gameRenderer.getMainCamera().getPosition());
            }
            local += (localTarget - local) * 0.04F;
            float target = Mth.clamp(global * local, 0.0F, 1.0F);
            intensity += (target - intensity) * 0.03F;
        } catch (RuntimeException e) {
            intensity = RainIntensity.NORMAL;
        }
    }

    private static float localFactor(ClientLevel level, Vec3 cam) {
        if (CloudSky.underStorm(level, cam.x, cam.y, cam.z, 1.0F)) {
            return UNDER_STORM;
        }
        int[] floor = CloudSky.floorAt(level, cam.x, cam.z, 1.0F);
        if (floor == null || floor[1] <= floor[0]) {
            return NO_CLOUD;
        }
        return Mth.lerp(Mth.clamp((floor[1] - floor[0]) / FLOOR_FULL, 0.0F, 1.0F), FLOOR_THIN, FLOOR_THICK);
    }

    /** カメラの所の雨の強さ(0〜1。小雨 0.3・普通 0.6・大雨 0.9 前後)。雨でなければ 0 へ寄っていく。 */
    public static float intensity() {
        return intensity;
    }

    /** クライアントの雨の強さに掛ける倍率(小雨 0.58・普通 0.8・大雨 1)。 */
    public static float rainScale() {
        return Mth.clamp(0.35F + 0.75F * intensity, 0.0F, 1.0F);
    }

    /** 大雨の度合い(普通 0 〜 大雨 1)。霞・雲の暗さの上乗せに。 */
    public static float heavy() {
        float k = Mth.clamp((intensity - 0.6F) / 0.35F, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    /** 雨音の音量・高さの倍率(小雨はさらさらと小さく高め、大雨はざあざあと大きく低め)。 */
    public static float soundVolume() {
        return Mth.lerp(intensity, 0.45F, 1.5F);
    }

    public static float soundPitch() {
        return Mth.lerp(intensity, 1.12F, 0.88F);
    }

    /** 開発用。 */
    public static String describe() {
        return String.format("雨の強さ %.2f(真上の雲 %.2f → %.2f)", intensity, localTarget, local);
    }

    private RainFeel() {
    }
}
