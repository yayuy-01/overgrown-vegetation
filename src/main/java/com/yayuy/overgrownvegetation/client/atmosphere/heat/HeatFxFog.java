package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HeatFxTable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * <b>2. 熱水の森の霧</b>(2026-11-10 熱水の森のシェーダー段階 1。ユーザー了承「ネザーの赤い霧を、森の中では深い青緑〜黒の霧に。
 * オーバーワールドの深い霧に当たるもの。熱水の湯気という設定」)。
 *
 * <p>バニラのネザーの霧は、色 = バイオームの fog_color(0x330808 の赤)、距離 = 描画距離 × 0.05 〜 min(描画距離, 192) × 0.5 の球。
 * 熱水の森の中では、色を設計値の深い青緑〜黒へ、距離を {@code start}〜{@code end} へ、度合い({@link HeatFxPresence})で寄せる。線形の霧なので奥ほど濃く、
 * 遠くの溶岩の橙は霧の向こうにぼんやり残る。ネザーには空が無いので、霧の色が背景の色にもなる。
 * <ul>
 *     <li>{@code cylinder}: 霧を横の距離だけで測る(真上の高い天井 = 偽の空が霧に沈まない)。度合いが半分を超えたら切り替える。</li>
 *     <li>{@code keepFarSections}: 霧の色の透明度を 0.999 にして、Sodium の「霧の終わりの先の区画を描かない」間引きを止める(メモリ sodium-iris-compat)。</li>
 *     <li>Distant Horizons は既定でふつうの霧を切るので、森の中の間は {@code DistantHorizonsFog} がふつうの霧を有効にする。そのとき霧が無い所(描画距離)から寄せる。</li>
 * </ul>
 * 水・溶岩・粉雪の中・盲目・暗闇の間は触らない。
 */
public final class HeatFxFog {

    private static final boolean DH = ModList.get().isLoaded("distanthorizons");
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    /** 確かめ用: 霧を止める。 */
    static boolean devOff;
    /** 確かめ用: 0 以上なら霧の終わりをこの値にする(負 = 設計値)。 */
    static float devEnd = -1.0F;
    /** 確かめ用: 0 = 球・1 = 横だけ(負 = 設計値)。 */
    static int devCylinder = -1;

    private HeatFxFog() {
    }

    /** 今の霧の強さ(0〜1)。 */
    public static float amount() {
        if (devOff || !HeatFx.enabled() || !VisualQuality.fog()) {
            return 0.0F;
        }
        return HeatFxPresence.presence();
    }

    private static boolean skip() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            return true;
        }
        return mc.player != null && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS));
    }

    static void computeFogColor(ViewportEvent.ComputeFogColor event) {
        float p = amount();
        if (p <= 0.001F || skip()) {
            return;
        }
        HeatFxTable t = DesignData.heatFx();
        event.setRed(Mth.lerp(p, event.getRed(), color(0)));
        event.setGreen(Mth.lerp(p, event.getGreen(), color(1)));
        event.setBlue(Mth.lerp(p, event.getBlue(), color(2)));
    }

    static void renderFog(ViewportEvent.RenderFog event) {
        float p = amount();
        if (p <= 0.001F || event.getType() != FogType.NONE || skip()) {
            return;
        }
        HeatFxTable t = DesignData.heatFx();
        float view = Minecraft.getInstance().gameRenderer.getRenderDistance();
        // DH があると、森の外ではふつうの霧が切られている。森へ入るとき霧の無い所(描画距離)から寄せる。
        float baseFar = DH ? view : Math.min(event.getFarPlaneDistance(), view);
        float baseNear = DH ? view : Math.min(event.getNearPlaneDistance(), baseFar);
        float end = end();
        float far = Mth.lerp(p, baseFar, Math.min(end, baseFar));
        float near = Mth.lerp(p, baseNear, Math.min(t.fogStart(), far - 1.0F));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        boolean cylinder = devCylinder >= 0 ? devCylinder == 1 : t.fogCylinder();
        if (p > 0.5F) {
            event.setFogShape(cylinder ? FogShape.CYLINDER : FogShape.SPHERE);
        }
        event.setCanceled(true);
        if (DEV_LOG) {
            lastSet = near + "〜" + far + " " + event.getMode();
        }
        if (event.getMode() == FogRenderer.FogMode.FOG_TERRAIN && t.fogKeepFarSections()) {
            FogRenderer.levelFogColor();
            float[] fc = RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(fc[0], fc[1], fc[2], Math.min(fc[3], 0.999F));
        }
    }

    /** 霧の終わり(ブロック。確かめ用の値があればそれ)。 */
    /**
     * 霧の色の成分(0 = r・1 = g・2 = b)。夜の色(fog.color)と昼の色(day.fogColor)を偽の空の昼の度合いで寄せ、夕と朝は紫(day.duskFogColor)へ
     * (11-10 段階 2。「夕と朝は空の光が紫へ寄る」決)。
     */
    static float color(int i) {
        HeatFxTable t = DesignData.heatFx();
        float c = Mth.lerp(HeatFxSky.day(), t.fogColor(i), t.glow().dayFogColor[i]);
        return Mth.lerp(HeatFxSky.dusk() * t.glow().duskFogMix, c, t.glow().duskFogColor[i]);
    }

    private static float end() {
        return devEnd >= 0.0F ? devEnd : Mth.lerp(HeatFxSky.day(), DesignData.heatFx().fogEnd(), DesignData.heatFx().glow().dayFogEnd);
    }

    private static boolean cylinder() {
        return devCylinder >= 0 ? devCylinder == 1 : DesignData.heatFx().fogCylinder();
    }

    /**
     * 地形のシェーダーへ霧を渡す(AFTER_SKY。{@code ov_heat.glsl})。Distant Horizons は {@code FogRenderer#setupFog} の終わりでふつうの霧を消し、
     * API でふつうの霧を有効にしてもネザーでは戻らなかった(11-10 にゲームで確かめた)。地形は霧の値をこの uniform から取り、ふつうの霧の結果の上に度合いで混ぜる。
     * Sodium はバニラの固い層のシェーダーの値を写す({@code SodiumTerrain})。
     */
    static void upload(boolean on) {
        float p = on && !skip() ? amount() : 0.0F;
        HeatFxTable t = DesignData.heatFx();
        float end = end();
        float start = Math.min(t.fogStart(), end - 1.0F);
        for (net.minecraft.client.renderer.ShaderInstance s : new net.minecraft.client.renderer.ShaderInstance[]{
                net.minecraft.client.renderer.GameRenderer.getRendertypeSolidShader(), net.minecraft.client.renderer.GameRenderer.getRendertypeCutoutShader(),
                net.minecraft.client.renderer.GameRenderer.getRendertypeCutoutMippedShader()}) {
            if (s == null) {
                continue;
            }
            s.safeGetUniform("OvHeatFog").set(p, start, end, cylinder() ? 1.0F : 0.0F);
            s.safeGetUniform("OvHeatFogColor").set(color(0), color(1), color(2), 0.0F);
        }
    }

    /**
     * 地形の固い層を描いた後: DH がふつうの霧を消していたら(霧の終わりが巨大)、Mob・粒・半透明のために霧の値を入れ直す
     * (地形は {@link #upload} の uniform で霧を掛けるので、ここより後の物だけ)。
     */
    static void afterSolid() {
        float p = amount();
        if (p <= 0.001F || skip() || RenderSystem.getShaderFogEnd() < 1.0E9F) {
            return;
        }
        float view = Minecraft.getInstance().gameRenderer.getRenderDistance();
        float end = end();
        float far = Mth.lerp(p, view, Math.min(end, view));
        RenderSystem.setShaderFogStart(Mth.lerp(p, view, Math.min(DesignData.heatFx().fogStart(), far - 1.0F)));
        RenderSystem.setShaderFogEnd(far);
        RenderSystem.setShaderFogShape(cylinder() ? FogShape.CYLINDER : FogShape.SPHERE);
    }

    /** 確かめ用: 最後に入れた霧の距離・地形を描いた後の霧の距離。 */
    static String lastSet = "-";
    static String seenAfterSolid = "-";

    static void noteAfterSolid() {
        seenAfterSolid = RenderSystem.getShaderFogStart() + "〜" + RenderSystem.getShaderFogEnd() + " a " + RenderSystem.getShaderFogColor()[3];
    }

    static String describe() {
        return "入れた " + lastSet + " 地形の後 " + seenAfterSolid + " 霧 " + (devOff ? "止め" : String.format("%.2f", amount())) + " 距離 " + RenderSystem.getShaderFogStart() + "〜" + RenderSystem.getShaderFogEnd()
                + " 形 " + RenderSystem.getShaderFogShape();
    }
}
