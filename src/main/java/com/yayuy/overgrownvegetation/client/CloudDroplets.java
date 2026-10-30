package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 画面の端の水滴(2026-10-17 深夜。ユーザーが選んだ「画面の端の水滴」= 雲の中にいる間、画面の端に細かな水滴がつく)。
 *
 * <ul>
 *   <li>雲の中にいる度合い({@link CloudInterior})に合わせて、画面の端(外側 2 割ほど)に水滴がつく。積乱雲の中は多い。</li>
 *   <li>中にいる間は少しずつ育ち、大きくなった粒はゆっくり下へ流れる。速く飛んでいると風で画面の外へ向かって流れる。</li>
 *   <li>雲を出ると 2〜5 秒で乾いて消える。一人称のときだけ(F1 で画面の表示を消すと出ない。かぼちゃの覗き穴と同じ扱い)。</li>
 *   <li>絵はその場で作る(縁の上が暗く下が明るい透明な粒と、左上の光の点。ファイルは無い)。</li>
 * </ul>
 * 数・大きさ・速さはすべて仮決め。設定 {@code cloudInterior} の強さで数が変わる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudDroplets {
    static final int MAX = 90;
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_droplet");
    private static final int TEX = 32;
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Drop> DROPS = new ArrayList<>();
    private static boolean textureReady;

    private static final class Drop {
        /** 画面の中の位置(0〜1)と、前の tick の位置。 */
        float x;
        float y;
        float px;
        float py;
        /** 半径(GUI の単位)。 */
        float r;
        float grow;
        float alpha;
        float slide;
        float wobble;
        float dry;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            DROPS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        float inside = CloudInterior.amount(1.0F) * CloudInterior.setting();
        float storm = Mth.clamp(CloudInterior.storm(1.0F) / Math.max(CloudInterior.amount(1.0F), 1.0E-4F), 0.0F, 1.0F);
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        double speed = mc.player.getDeltaMovement().length();
        float aspect = mc.getWindow().getGuiScaledHeight() / (float) Math.max(1, mc.getWindow().getGuiScaledWidth());
        for (Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.px = d.x;
            d.py = d.y;
            if (inside > 0.15F) {
                d.r = Math.min(d.r + d.grow, 6.5F);
                d.alpha = Math.min(1.0F, d.alpha + 0.05F);
            } else {
                d.alpha -= d.dry;
            }
            // 大きい粒は下へ流れる(少しふらつく)。速く飛ぶと、画面の真ん中から外へ風で流れる。
            if (d.r > 3.6F) {
                d.slide = Math.min(d.slide + 0.0004F, 0.006F);
                d.y += d.slide;
                d.x += (float) Math.sin(d.y * 60.0F + d.wobble) * 0.0006F;
            }
            if (speed > 0.6) {
                float k = (float) Math.min(1.0, (speed - 0.6) / 1.2) * 0.012F;
                d.x += (d.x - 0.5F) * k;
                d.y += (d.y - 0.5F) * k * aspect;
            }
            if (d.alpha <= 0.0F || d.y > 1.05F || d.x < -0.05F || d.x > 1.05F || d.y < -0.05F) {
                it.remove();
            }
        }
        if (!firstPerson || inside <= 0.05F) {
            return;
        }
        // 1 tick につく数(中にいるほど・積乱雲ほど多い)。
        float rate = inside * (0.5F + 0.6F * storm);
        int n = (int) rate + (RANDOM.nextFloat() < rate - (int) rate ? 1 : 0);
        for (int i = 0; i < n && DROPS.size() < Math.round(MAX * Math.min(1.0F, CloudInterior.setting())); i++) {
            float x = RANDOM.nextFloat();
            float y = RANDOM.nextFloat();
            // 端ほど多い(真ん中から 2 割より内側にはほとんどつかない)。
            float edge = Math.max(Math.abs(x - 0.5F), Math.abs(y - 0.5F));
            float w = Mth.clamp((edge - 0.32F) / 0.16F, 0.0F, 1.0F);
            if (RANDOM.nextFloat() > w * w) {
                continue;
            }
            Drop d = new Drop();
            d.x = d.px = x;
            d.y = d.py = y;
            d.r = 1.2F + 2.8F * RANDOM.nextFloat() * RANDOM.nextFloat();
            d.grow = 0.002F + 0.01F * RANDOM.nextFloat();
            d.alpha = 0.0F;
            d.wobble = RANDOM.nextFloat() * 10.0F;
            d.dry = 1.0F / (40.0F + 60.0F * RANDOM.nextFloat());
            DROPS.add(d);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (DROPS.isEmpty() || mc.player == null || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        ensureTexture();
        GuiGraphics g = event.getGuiGraphics();
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth();
        int h = g.guiHeight();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (Drop d : DROPS) {
            float a = Mth.clamp(d.alpha, 0.0F, 1.0F) * 0.85F;
            if (a <= 0.01F) {
                continue;
            }
            float x = Mth.lerp(pt, d.px, d.x) * w;
            float y = Mth.lerp(pt, d.py, d.y) * h;
            float size = d.r * 2.0F;
            g.pose().pushPose();
            g.pose().translate(x - d.r, y - d.r * 1.1F, 0.0F);
            g.pose().scale(size / TEX, size * 1.1F / TEX, 1.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, a);
            g.blit(TEXTURE, 0, 0, TEX, TEX, 0.0F, 0.0F, TEX, TEX, TEX, TEX);
            g.pose().popPose();
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /** 水滴の絵(32 × 32): うっすら明るい中身・縁の上は暗く下は明るい(光が粒の中で曲がる)・左上の光の点。 */
    private static void ensureTexture() {
        if (textureReady) {
            return;
        }
        textureReady = true;
        NativeImage image = new NativeImage(TEX, TEX, true);
        float c = TEX / 2.0F;
        float rad = TEX / 2.0F - 1.5F;
        for (int py = 0; py < TEX; py++) {
            for (int px = 0; px < TEX; px++) {
                float dx = px + 0.5F - c;
                float dy = py + 0.5F - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy) / rad;
                int rgba = 0;
                if (d < 1.0F) {
                    float rim = Mth.clamp((d - 0.72F) / 0.28F, 0.0F, 1.0F);
                    float down = Mth.clamp(dy / rad, -1.0F, 1.0F);
                    // 中身: 淡い青白。縁: 上は暗い灰青、下は明るい白。
                    float r = 0.85F;
                    float gg = 0.9F;
                    float b = 0.96F;
                    float a = 0.12F;
                    if (rim > 0.0F) {
                        float bright = 0.5F + 0.5F * down;
                        r = Mth.lerp(bright, 0.32F, 0.98F);
                        gg = Mth.lerp(bright, 0.36F, 1.0F);
                        b = Mth.lerp(bright, 0.44F, 1.0F);
                        a = Mth.lerp(rim, 0.12F, Mth.lerp(bright, 0.5F, 0.62F));
                    }
                    // 縁はなめらかに切る。
                    a *= Mth.clamp((1.0F - d) * rad / 1.2F, 0.0F, 1.0F);
                    // 左上の光の点。
                    float hx = px + 0.5F - (c - rad * 0.38F);
                    float hy = py + 0.5F - (c - rad * 0.42F);
                    float hd = (float) Math.sqrt(hx * hx + hy * hy) / (rad * 0.2F);
                    if (hd < 1.0F) {
                        float k = 1.0F - hd * hd;
                        r = Mth.lerp(k, r, 1.0F);
                        gg = Mth.lerp(k, gg, 1.0F);
                        b = Mth.lerp(k, b, 1.0F);
                        a = Mth.lerp(k, a, 0.9F);
                    }
                    int ai = Mth.clamp((int) (a * 255.0F), 0, 255);
                    rgba = (ai << 24) | (Mth.clamp((int) (b * 255.0F), 0, 255) << 16) | (Mth.clamp((int) (gg * 255.0F), 0, 255) << 8)
                            | Mth.clamp((int) (r * 255.0F), 0, 255);
                }
                image.setPixelRGBA(px, py, rgba);
            }
        }
        Minecraft.getInstance().getTextureManager().register(TEXTURE, new DynamicTexture(image));
    }

    private CloudDroplets() {
    }
}
