package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 空の光の共通の値(2026-10-18。ユーザーが選んだ「夜明け前の青い時間」「月明かりの夜の雲」)。空({@link SkyDome})と雲({@link VoxelClouds}・{@link Cirrus}・
 * 雲の中の霧 {@link CloudInterior})が同じ式を読む(空と雲が一緒に変わる)。
 *
 * <ul>
 *   <li>青い時間: 日の入りの後と日の出の前(太陽の高さ −13〜−2° のあたり、日が沈む前後の 6° ほどで出入り)に、空と雲が深い青になる。</li>
 *   <li>月明かりの夜の雲: 夜の雲の色をバニラの黒(0.1・0.1・0.15)まで落とさず、深い青を下限にする(満月ほど明るい。雨では暗い)。月の側の縁の光は
 *       雲のシェーダーの銀の縁(光の向き = 月)がそのまま出す。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code nightClouds}・{@code skyLight}。
 */
public final class SkyLight {
    /** 夜の雲の色の下限(満月のとき)と、新月の割合。 */
    static final float[] NIGHT_CLOUD = {0.17F, 0.21F, 0.36F};
    static final float NEW_MOON = 0.55F;
    /** 青い時間の雲の色(寄せる先)と寄せる割合。 */
    static final float[] BLUE_CLOUD = {0.36F, 0.44F, 0.74F};
    static final float BLUE_CLOUD_MIX = 0.6F;

    /** 青い時間の度合い(0〜1)。太陽の高さ(向きの y)から。 */
    public static float blueHour(ClientLevel level, float partialTick) {
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        float e = (float) sun.y;
        float in = smooth(Mth.clamp((e + 0.24F) / 0.18F, 0.0F, 1.0F));
        float out = 1.0F - smooth(Mth.clamp((e - 0.0F) / 0.12F, 0.0F, 1.0F));
        return in * out;
    }

    /** 雲の色(バニラの {@code getCloudColor})に、夜の下限と青い時間を入れる。 */
    public static Vec3 cloudColor(ClientLevel level, float partialTick, Vec3 cloud) {
        float night = OvergrownVegetationConfig.COMMON.nightClouds.get().floatValue();
        if (night <= 0.0F) {
            return cloud;
        }
        float rain = com.yayuy.overgrownvegetation.sky.CloudSky.rawRain(level, partialTick);
        float moon = NEW_MOON + (1.0F - NEW_MOON) * level.getMoonBrightness();
        float k = night * moon * (1.0F - 0.5F * rain);
        double r = Math.max(cloud.x, NIGHT_CLOUD[0] * k);
        double g = Math.max(cloud.y, NIGHT_CLOUD[1] * k);
        double b = Math.max(cloud.z, NIGHT_CLOUD[2] * k);
        float blue = blueHour(level, partialTick) * BLUE_CLOUD_MIX * night * (1.0F - 0.6F * rain);
        if (blue > 0.0F) {
            r = Mth.lerp(blue, r, BLUE_CLOUD[0]);
            g = Mth.lerp(blue, g, BLUE_CLOUD[1]);
            b = Mth.lerp(blue, b, BLUE_CLOUD[2]);
        }
        return new Vec3(r, g, b);
    }

    static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private SkyLight() {
    }
}
