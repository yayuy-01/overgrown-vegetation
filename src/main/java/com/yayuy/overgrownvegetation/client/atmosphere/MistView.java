package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * <b>バニラの霧と空</b>(第 3 段「霧」、10-30 夜。元は parked の {@code CorruptionAtmosphere} の朝霧の分)。
 *
 * <ul>
 *     <li><b>朝霧の中</b>: 地形シェーダーの霧({@link MistFog})は地形と水と粒にしか掛からないので、カメラが層の中にいる間はバニラの霧の終わりを
 *     {@code insideFogEnd}(64 ブロック、10-16 了承)・始まりを × {@code insideNearFraction} へ寄せ、色を霧の遠い色へ寄せる。
 *     Mob・バニラの粒・空の縁もこれで霞む(霧に紛れる Mob)。層の上から見下ろしている間はバニラのまま(空気は澄んでいる)。
 *     過繁茂の中の一日中の霧(parked の 96 ブロック)は戻さない(10-30 夜 相談セッションの答え)。</li>
 *     <li><b>林冠の隙間の空を白く飛ばす</b>(A5、10-30 夜 ユーザー採用。参考画像「林冠の隙間の空は白く飛ぶ」): 森の中(頭上の覆い
 *     {@link ForestPresence#openForest})にいる間は、霧の色を明るい白({@code forestSkyColor})へ寄せ、空の霧をすぐ満ちるようにして
 *     空の丸天井まで霧の色にする。朝霧の中でも(10-30 夜 ユーザー「白い霧の中の幹の影絵」= 参考画像 4)。森の外では今まで通り。</li>
 *     <li><b>太陽の方の霧が光る</b>(B1): 霧の色を、見ている向きが太陽に近いほど暖かい白へ。</li>
 *     <li><b>嵐</b>(提案 2、10-30 夜 ユーザー採用): 雷雨の間、過繁茂の地域ではバニラの霧を {@code stormFogEnd}(仮 40)まで近づけて遠くを早く隠す。</li>
 * </ul>
 */
final class MistView {

    private MistView() {
    }

    /** 森の中で空を白く飛ばす度合い(0〜1)。 */
    private static float whiteSky() {
        return ForestPresence.openForest() * DesignData.atmosphere().fog().forestSky();
    }

    static void computeFogColor(ViewportEvent.ComputeFogColor event) {
        float inside = MistFog.cameraInside();
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        if (inside > 0.001F) {
            float[] c = MistFog.farColor();
            r = Mth.lerp(inside, r, c[0]);
            g = Mth.lerp(inside, g, c[1]);
            b = Mth.lerp(inside, b, c[2]);
        }
        float white = whiteSky();
        if (white > 0.001F) {
            float[] s = MistFog.forestSkyColor();
            r = Mth.lerp(white, r, s[0]);
            g = Mth.lerp(white, g, s[1]);
            b = Mth.lerp(white, b, s[2]);
        }
        float glow = MistFog.viewGlow(event.getCamera().getLookVector());
        if (glow > 0.001F) {
            float[] n = MistFog.nearColor();
            r = Mth.lerp(glow, r, Math.min(1.0F, n[0] * 1.15F + 0.06F));
            g = Mth.lerp(glow, g, Math.min(1.0F, n[1] * 1.08F + 0.05F));
            b = Mth.lerp(glow, b, Math.min(1.0F, n[2] * 0.95F + 0.02F));
        }
        event.setRed(r);
        event.setGreen(g);
        event.setBlue(b);
    }

    static void renderFog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.NONE) {
            return;
        }
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            // 空の丸天井を霧の色(森の中は明るい白)で満たす: 空の霧の終わりを手前へ。
            float white = whiteSky();
            if (white > 0.001F) {
                event.setFarPlaneDistance(Mth.lerp(white, event.getFarPlaneDistance(), 1.0F));
                event.setNearPlaneDistance(Mth.lerp(white, event.getNearPlaneDistance(), 0.0F));
                event.setCanceled(true);
            }
            return;
        }
        float inside = MistFog.cameraInside();
        float storm = MistFog.stormNear();
        if (inside <= 0.001F && storm <= 0.001F || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        var p = DesignData.atmosphere().fog();
        float vanillaFar = event.getFarPlaneDistance();
        float vanillaNear = event.getNearPlaneDistance();
        float far = Mth.lerp(inside, vanillaFar, Math.min(vanillaFar, p.insideFogEnd()));
        far = Mth.lerp(storm, far, Math.min(far, p.stormFogEnd()));
        float near = Mth.lerp(Math.max(inside, storm), vanillaNear, Math.min(vanillaNear, far * p.insideNearFraction()));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }
}
