package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.network.CloudStrikePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * <b>天気の見た目の残りの束ね役</b>(2026-11-16。雷雨の強化の残り・凪・強風の日)。{@link Atmosphere} からは、毎 tick・ふつうの混ぜ方の描画・
 * 加算の光の描画の 3 か所でここを呼ぶだけ。中身:
 * <ul>
 *     <li>雷雨の強化の残り: 地平線の遠い雷 {@link HorizonLightning}・スプライト {@link Sprites}・雷雨の後の胞子の雲 {@link SporeDrift}・
 *     セントエルモの火 {@link StElmo}・アーク雲と突風 {@link ArcusCloud}。</li>
 *     <li>凪: 風の場を止める({@link GustField#setWeather})・遠くの霞を薄め森の霞を濃く({@link #aerialScale}・{@link #forestReachScale})・霞の層 {@link CalmLayers}。</li>
 *     <li>強風の日: 突風を多く強く・森の霞を薄め・胞子の綿 {@link GaleFluff}。</li>
 * </ul>
 * 天気の値({@link WeatherClient})は 5 秒ごとの段で届くので、ここで時定数 約 3 秒で寄せてから使う(最初の 1 通は一気に合わせる)。
 * 数は design の節 {@code stormExtras}・{@code calmLook}・{@code galeLook}(すべて仮決め)。確かめは試験用クライアントの命令 {@code wx}({@link WeatherLookDev})。
 */
public final class WeatherLook {

    private WeatherLook() {
    }

    private static float calm, gale, after, thunder, pre;
    private static boolean primed;
    private static Consumer<CloudStrikePayload> wrapper;

    /** 確かめ用: 止めている演出の名前・差し替えた値。 */
    static final Set<String> OFF = new HashSet<>();
    static WeatherLookEye.Params devStorm, devCalm, devGale;

    static boolean off(String name) {
        return OFF.contains(name);
    }

    static WeatherLookEye.Params storm() {
        return devStorm != null ? devStorm : DesignData.atmosphere().stormExtras();
    }

    static WeatherLookEye.Params calmParams() {
        return devCalm != null ? devCalm : DesignData.atmosphere().calmLook();
    }

    static WeatherLookEye.Params gale() {
        return devGale != null ? devGale : DesignData.atmosphere().galeLook();
    }

    /** 寄せた後の値(0〜1)。 */
    public static float calmAmount() {
        return off("calm") ? 0.0F : calm;
    }

    public static float galeAmount() {
        return off("gale") ? 0.0F : gale;
    }

    public static float stormAfter() {
        return after;
    }

    public static float thunderAmount() {
        return thunder;
    }

    public static float preStorm() {
        return pre;
    }

    /** 遠い雷の出やすさ(雷雨の間 1・前触れと雷雨の後は節の割合)。 */
    static float stormDrive() {
        WeatherLookEye.Params p = storm();
        return Mth.clamp(Math.max(thunder, Math.max(pre * p.f("farPre"), after * p.f("farAfter"))), 0.0F, 1.0F);
    }

    /** 遠くの霞(空気遠近)に掛ける倍率: 凪の日は遠くが澄む。 */
    static float aerialScale() {
        return 1.0F - calmParams().f("farClear") * calmAmount();
    }

    /** 森の中の霞の濃さ(見える距離を縮める倍率 {@code MistFog.forestReach})に掛ける倍率: 凪は濃く・強風の日は少し澄む。 */
    static float forestReachScale() {
        return (1.0F + calmParams().f("forestThicken") * calmAmount()) * (1.0F - gale().f("forestClear") * galeAmount());
    }

    static void clear() {
        HorizonLightning.clear();
        Sprites.clear();
        SporeDrift.clear();
        StElmo.clear();
        GaleFluff.clear();
        ArcusCloud.clear();
        primed = false;
        calm = gale = after = thunder = pre = 0.0F;
    }

    private static float ease(float value, float target) {
        float v = value + (target - value) * 0.017F;
        return Math.abs(target - v) < 0.002F ? target : v;
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(Minecraft mc, ClientLevel level, LocalPlayer player, double time) {
        // 落雷の知らせを横から聞く(スプライト用)。受け手は StormStrikes が 1 つ持っているので、その前に挟む。
        Consumer<CloudStrikePayload> current = WeatherClient.strikeListener;
        if (current != wrapper) {
            wrapper = s -> {
                current.accept(s);
                if (!off("sprite")) {
                    Sprites.onStrike(s);
                }
            };
            WeatherClient.strikeListener = wrapper;
        }
        boolean has = WeatherClient.received();
        float tc = has ? Mth.clamp(WeatherClient.calm(), 0.0F, 1.0F) : 0.0F;
        float tg = has ? Mth.clamp(WeatherClient.gale(), 0.0F, 1.0F) : 0.0F;
        float ta = has ? Mth.clamp(WeatherClient.stormAfter(), 0.0F, 1.0F) : 0.0F;
        float tt = has ? Mth.clamp(WeatherClient.thunder(), 0.0F, 1.0F) : 0.0F;
        float tp = has ? Mth.clamp(WeatherClient.preStorm(), 0.0F, 1.0F) : 0.0F;
        if (!primed && has) {
            calm = tc;
            gale = tg;
            after = ta;
            thunder = tt;
            pre = tp;
            primed = true;
        } else {
            calm = ease(calm, tc);
            gale = ease(gale, tg);
            after = ease(after, ta);
            thunder = ease(thunder, tt);
            pre = ease(pre, tp);
        }
        WeatherLookEye.Params cp = calmParams();
        WeatherLookEye.Params gp = gale();
        GustField.INSTANCE.setWeather(calmAmount(), galeAmount(), cp.f("gustStop"), cp.f("breathScale"), gp.f("gustInterval"), gp.f("gustBonus"),
                gp.f("between"));
        float sky = 1.0F - ForestPresence.underground();
        HorizonLightning.tick(level, player, off("far") ? 0.0F : stormDrive() * sky);
        SporeDrift.tick(level, player, off("drift") ? 0.0F : after);
        // セントエルモ: 雷雨の間と、前触れの終わり(preStorm 0.7 → 1)。
        float elmo = Math.max(thunder, SunLight.smooth(Mth.clamp((pre - 0.7F) / 0.3F, 0.0F, 1.0F)));
        StElmo.tick(level, player, off("elmo") ? 0.0F : elmo * sky);
        GaleFluff.tick(level, player, off("fluff") ? 0.0F : galeAmount(), time);
        ArcusCloud.tick(level, player, off("arcus") ? 0.0F : pre, thunder);
    }

    /** 奥行きを書く物(アーク雲)。AFTER_CUTOUT_BLOCKS。 */
    static void renderOpaque(RenderLevelStageEvent event) {
        ArcusCloud.render(event);
    }

    /** ふつうの混ぜ方の物(胞子の雲・胞子の綿)。AFTER_PARTICLES、加算の光の前。 */
    static void render(RenderLevelStageEvent event) {
        WeatherShapes.begin(event);
        try {
            SporeDrift.render(event);
            GaleFluff.render(event);
        } finally {
            WeatherShapes.end();
        }
    }

    /** 加算の光(スプライト・セントエルモの火)。{@link AddLight} の描き先の中で。 */
    static void renderGlow(RenderLevelStageEvent event) {
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        if (!off("sprite")) {
            Sprites.render(event, c);
        }
        if (!off("elmo")) {
            StElmo.render(event, c);
        }
        buffers.endBatch(AddLight.renderType());
    }

    public static String describe() {
        return String.format("天気の見た目 凪 %.2f 強風 %.2f 雷雨 %.2f 前触れ %.2f 雷雨の後 %.2f / %s / %s / %s / %s / %s / %s / %s", calm, gale, thunder, pre, after,
                HorizonLightning.describe(), Sprites.describe(), SporeDrift.describe(), StElmo.describe(), GaleFluff.describe(), ArcusCloud.describe(),
                CalmLayers.describe());
    }
}
