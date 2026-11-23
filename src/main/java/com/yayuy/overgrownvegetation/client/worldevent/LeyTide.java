package com.yayuy.overgrownvegetation.client.worldevent;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.LeyVeins;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>地脈の大潮</b>の見た目(2026-11-16。ユーザー決定: 地脈の流れが一時的にあふれ、地面からオーラのような光が漏れる。効き目(汲み上げ器が少し速い)は
 * サーバー側で、ここは見た目だけ)。理由(設定): 地脈技術の世界観 —— 地脈の線がどこを通っているかが、この日だけ地上から見える。
 *
 * <ul>
 *     <li><b>地上の光</b>: 地脈の線の真上(サーバーから届く「線までの距離」の升 {@link LeyVeins#distanceAt})の地面から、やわらかい光の帯が立ちのぼって
 *     ゆらめく(カメラの方を向く縦の帯。足し算)。線の上に数ブロックおき。色は通常世界が水色、汚染地域は地脈の根の色(岩肌の筋と同じ決まり)。
 *     地形と同じ霧で弱める。昼は弱く、夜がいちばん見える。</li>
 *     <li><b>岩肌の地脈の筋</b>(洞窟の中): 明るさを {@code veinBoost} 倍に、届く幅も少し広げる({@link #veinBoost} を {@link LeyVeins} が読む)。</li>
 * </ul>
 * 地脈の花からの光は、花のブロックが今のコードに無いので作っていない(花ができたら足す)。洞窟の口は地上から探せないので、線の上の地面で代える。
 * 値は {@code worldEvents.leyTide}(仮決め)。確かめは {@code /ov event start ley_tide <進み>}(地脈の線の上へは {@code /ov} の地脈の探し方か、羅針盤で)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class LeyTide {

    private LeyTide() {
    }

    private record Emitter(double x, double y, double z, boolean polluted, float seed) {
    }

    private static List<Emitter> emitters = List.of();
    private static int ticks;
    private static boolean broken;
    private static int drawn;
    private static float maxAlpha;

    /** 大潮の出ている度合い(0〜1)。 */
    public static float amount(float partialTick) {
        if (!WorldEventClient.is(WorldEventKind.LEY_TIDE)) {
            return 0.0F;
        }
        return WorldEventSky.envelope(WorldEventClient.elapsedTicks(partialTick), WorldEventClient.lengthTicks(), 600.0F, 600.0F);
    }

    /** 岩肌の地脈の筋の明るさの倍率(1 = いつも)。 */
    public static float veinBoost() {
        float a = amount(0.0F);
        return a <= 0.0F ? 1.0F : 1.0F + (Math.max(1.0F, DesignData.sky().events.leyTide.veinBoost) - 1.0F) * a;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        if (amount(0.0F) <= 0.0F) {
            emitters = List.of();
            return;
        }
        if (ticks % 40 == 3 || emitters.isEmpty() && ticks % 10 == 3) {
            scan(level, mc.gameRenderer.getMainCamera().getPosition());
        }
    }

    /** 線の真上の地面を、カメラのまわりで探す(2 秒おき)。場所は世界の座標から決まるくじ(歩いても同じ所に立つ)。 */
    private static void scan(ClientLevel level, Vec3 cam) {
        WorldEventLooks.LeyTide p = DesignData.sky().events.leyTide;
        int range = Mth.clamp((int) p.range, 16, 96);
        int step = 2;
        int cx = Mth.floor(cam.x / step) * step;
        int cz = Mth.floor(cam.z / step) * step;
        List<Emitter> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = -range; dz <= range && found.size() < 90; dz += step) {
            for (int dx = -range; dx <= range && found.size() < 90; dx += step) {
                int x = cx + dx;
                int z = cz + dz;
                float d = LeyVeins.distanceAt(x + 0.5, z + 0.5);
                if (d < 0.0F || d > p.lineWidth) {
                    continue;
                }
                float h = hash(x, z);
                if (h > p.density) {
                    continue;
                }
                pos.set(x, 0, z);
                if (!level.hasChunkAt(pos)) {
                    continue;
                }
                // 梢ではなく地面から: いちばん上から、葉・幹・空きを下へたどる。
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int k = 0; k < 48 && y > level.getMinBuildHeight(); k++) {
                    BlockState s = level.getBlockState(pos.set(x, y - 1, z));
                    if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.blocksMotion()) {
                        y--;
                    } else {
                        break;
                    }
                }
                found.add(new Emitter(x + 0.5, y + 0.05, z + 0.5, LeyVeins.pollutedAt(x + 0.5, z + 0.5), h / Math.max(0.001F, p.density)));
            }
        }
        emitters = found;
    }

    private static float hash(int x, int z) {
        int n = x * 73856093 ^ z * 19349663;
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        emitters = List.of();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || emitters.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || ShaderPacks.active() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects()) {
            return;
        }
        try {
            draw(event, level);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地脈の大潮の光で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void draw(RenderLevelStageEvent event, ClientLevel level) {
        float partial = event.getPartialTick();
        WorldEventLooks.LeyTide p = DesignData.sky().events.leyTide;
        float night = SunLight.night(level, partial);
        float amount = amount(partial) * p.strength * Mth.lerp(night, p.dayStrength, 1.0F);
        if (amount <= 0.01F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        float time = (level.getGameTime() % 1_728_000L + partial) / 20.0F;
        boolean main = EventLight.drawToMainTarget();
        MultiBufferSource.BufferSource buffers = EventLight.buffers();
        VertexConsumer c = buffers.getBuffer(EventLight.renderType());
        drawn = 0;
        maxAlpha = 0.0F;
        for (Emitter e : emitters) {
            float x = (float) (e.x - eye.x);
            float y = (float) (e.y - eye.y);
            float z = (float) (e.z - eye.z);
            float dist = Mth.sqrt(x * x + y * y + z * z);
            // 範囲の端(水平の距離)でなだらかに消す。
            float a = amount * EventLight.fogKeep(dist) * Mth.clamp((p.range - Mth.sqrt(x * x + z * z)) / 12.0F, 0.0F, 1.0F);
            if (a <= 0.01F) {
                continue;
            }
            drawn++;
            maxAlpha = Math.max(maxAlpha, a);
            float cr = e.polluted ? p.pollutedColor[0] : p.color[0];
            float cg = e.polluted ? p.pollutedColor[1] : p.color[1];
            float cb = e.polluted ? p.pollutedColor[2] : p.color[2];
            // カメラの方を向く横の向き(水平)。
            float hl = Mth.sqrt(x * x + z * z);
            float rx = hl > 1.0e-3F ? -z / hl : 1.0F;
            float rz = hl > 1.0e-3F ? x / hl : 0.0F;
            float s = e.seed * 37.0F;
            // 息づく: 1 つずつ別の調子でゆっくり強弱する。
            float breathe = 0.6F + 0.4F * Mth.sin(time * 0.7F + s);
            for (int k = 0; k < 3; k++) {
                float ph = s + k * 2.1F;
                float height = p.height * (0.55F + 0.45F * Mth.sin(time * 0.31F + ph) * 0.5F + 0.25F * k / 2.0F) * (0.6F + 0.4F * amount);
                float width = p.width * (0.7F + 0.3F * k);
                float ox = Mth.sin(time * 0.23F + ph) * 0.5F + (k - 1) * 0.45F;
                ribbon(c, x + rx * ox, y, z + rz * ox, rx, rz, width, height, time, ph, cr, cg, cb, a * breathe * (k == 0 ? 0.42F : 0.24F));
            }
        }
        boolean pushed = com.yayuy.overgrownvegetation.client.PortStageView.push(event); // 1.20.1: この段の描画の行列にカメラの回転を(PortStageView)
        buffers.endBatch(EventLight.renderType());
        com.yayuy.overgrownvegetation.client.PortStageView.pop(pushed);
        EventLight.restoreTarget(main);
    }

    /** 縦の光の帯: 根元が明るく上で消える。横は真ん中が明るく縁で透明。上へ行くほど横へゆらぐ。 */
    private static void ribbon(VertexConsumer c, float x, float y, float z, float rx, float rz, float width, float height, float time, float phase,
                               float r, float g, float b, float alpha) {
        final int seg = 5;
        float px = 0.0F, pa = alpha;
        for (int i = 1; i <= seg; i++) {
            float u = i / (float) seg;
            float sway = Mth.sin(time * 0.9F + phase + u * 2.4F) * 0.35F * u;
            float al = alpha * (1.0F - u) * (1.0F - u);
            float y0 = y + height * (i - 1) / seg;
            float y1 = y + height * u;
            float w0 = width * (1.0F + 0.5F * (i - 1) / (float) seg) * 0.5F;
            float w1 = width * (1.0F + 0.5F * u) * 0.5F;
            for (int side = -1; side <= 1; side += 2) {
                c.vertex(x + rx * px, y0, z + rz * px).color(r, g, b, pa).endVertex();
                c.vertex(x + rx * (px + side * w0), y0, z + rz * (px + side * w0)).color(r, g, b, 0.0F).endVertex();
                c.vertex(x + rx * (sway + side * w1), y1, z + rz * (sway + side * w1)).color(r, g, b, 0.0F).endVertex();
                c.vertex(x + rx * sway, y1, z + rz * sway).color(r, g, b, al).endVertex();
            }
            px = sway;
            pa = al;
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        List<Emitter> list = emitters;
        String first = list.isEmpty() ? "なし" : String.format("%.0f %.0f %.0f", list.get(list.size() / 2).x, list.get(list.size() / 2).y, list.get(list.size() / 2).z);
        return String.format("地脈の大潮: %.2f・光の立つ所 %d(描いた %d・いちばん濃い %.2f・1 つの場所 %s)%s", amount(0.0F), list.size(), drawn, maxAlpha, first,
                broken ? "・例外で止めた" : "");
    }
}
