package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>雷雨の後に風下の空へ流れる胞子の雲</b>(雷雨の強化の残り。2026-11-16。ユーザー決定。設定: 雷で胞子の生産と拡散が強まる(11-12)のを見せる)。
 *
 * <p>サーバーでは、落雷した森の胞子が上空の層へ吹き上がり、上空の風で風下へ流れる。その層には今まで絵が無かった。ここは見た目だけ:
 * {@code stormAfter}(1 → 0、約 6000 tick)の濃さで、<b>風下(雲を流す上空の風の向き)</b>の空に、黄緑の薄い雲の筋を {@code driftCount} 枚、
 * 雲の底の少し下({@code driftLow}〜{@code driftHigh})に置く。筋は風の向きに長い水平の薄い楕円(2〜3 枚の重なり)で、雲と同じ速さで流れる
 * (雲の場の座標で持つ)。カメラから風下へ扇形に広がる。ふつうの混ぜ方(光らせない。夜は暗い)。濃霧・地下では出さない。
 * 描画距離の外の筋は、カメラの方へ寄せて同じ見かけの大きさで描く。数は節 {@code stormExtras} の drift*(仮決め)。
 */
final class SporeDrift {

    private SporeDrift() {
    }

    private static final class Wisp {
        double fx, fz;
        float y, len, wid, tone;
        long seed;
    }

    private static final List<Wisp> WISPS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static float dirX = 1.0F, dirZ;
    private static float shown;

    static void clear() {
        WISPS.clear();
        shown = 0.0F;
    }

    /** 毎 tick。{@code amount} = 濃さ 0〜1(stormAfter)。 */
    static void tick(ClientLevel level, LocalPlayer player, float amount) {
        shown = amount;
        if (amount <= 0.004F) {
            WISPS.clear();
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        long gameTime = level.getGameTime();
        // 風下 = 雲が流れていく向き(1 秒ぶんの流れから)。
        double vx = CloudField.offsetX(gameTime + 20.0) - CloudField.offsetX(gameTime);
        double vz = CloudField.offsetZ(gameTime + 20.0) - CloudField.offsetZ(gameTime);
        double vl = Math.hypot(vx, vz);
        if (vl > 1.0e-6) {
            dirX = (float) (vx / vl);
            dirZ = (float) (vz / vl);
        }
        double camFx = CloudField.fieldX(player.getX(), gameTime, 0.0F);
        double camFz = CloudField.fieldZ(player.getZ(), gameTime, 0.0F);
        int want = Math.round(p.f("driftCount"));
        float range = p.f("driftRange");
        while (WISPS.size() > want) {
            WISPS.remove(WISPS.size() - 1);
        }
        while (WISPS.size() < want) {
            Wisp w = new Wisp();
            place(w, camFx, camFz, p, range, false);
            WISPS.add(w);
        }
        for (Wisp w : WISPS) {
            double rx = w.fx - camFx;
            double rz = w.fz - camFz;
            double along = rx * dirX + rz * dirZ;
            double side = -rx * dirZ + rz * dirX;
            // 扇の外へ出た筋(プレイヤーが動いた・風下へ流れ切った)は、風上の端へ置き直す。
            if (along > range * 1.1 || along < -range * 0.3 || Math.abs(side) > range * 0.9) {
                place(w, camFx, camFz, p, range, along > range);
            }
        }
    }

    private static void place(Wisp w, double camFx, double camFz, WeatherLookEye.Params p, float range, boolean atStart) {
        // 風下へ 0〜1(置き直しは手前の 3 割)。横は遠いほど広がる扇。
        double along = (atStart ? RANDOM.nextDouble() * 0.3 - 0.15 : RANDOM.nextDouble() * 1.15 - 0.15) * range;
        double spread = 0.25 + 0.5 * Math.max(0.0, along) / range;
        double side = (RANDOM.nextDouble() - 0.5) * 2.0 * spread * range;
        w.fx = camFx + dirX * along - dirZ * side;
        w.fz = camFz + dirZ * along + dirX * side;
        w.y = Mth.lerp(RANDOM.nextFloat(), p.f("driftLow"), p.f("driftHigh"));
        w.len = p.f("driftLength") * (0.6F + 0.9F * RANDOM.nextFloat());
        w.wid = p.f("driftWidth") * (0.6F + 0.8F * RANDOM.nextFloat());
        w.tone = RANDOM.nextFloat();
        w.seed = RANDOM.nextLong();
    }

    /** {@link Atmosphere} のふつうの混ぜ方の所から({@link WeatherShapes#begin} の中)。 */
    static void render(RenderLevelStageEvent event) {
        if (WISPS.isEmpty() || shown <= 0.004F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float gate = p.f("driftAlpha") * shown * (1.0F - DenseFog.amount()) * (1.0F - ForestPresence.underground()) * (1.0F - 0.9F * ForestPresence.fogForest());
        if (gate <= 0.004F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        double t = level.getGameTime() + partial;
        double offX = CloudField.offsetX(t);
        double offZ = CloudField.offsetZ(t);
        float night = SunLight.night(level, partial);
        float light = Mth.lerp(night, 1.0F, p.f("driftNight")) * (1.0F - 0.3F * level.getRainLevel(partial));
        float[] col = p.rgb("driftColor");
        float[] fog = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
        float range = p.f("driftRange");
        float drawAt = mc.gameRenderer.getDepthFar() * 0.8F;
        for (Wisp w : WISPS) {
            float x = (float) (w.fx + offX - eye.x);
            float y = (float) (w.y - eye.y);
            float z = (float) (w.fz + offZ - eye.z);
            float dist = Mth.sqrt(x * x + y * y + z * z);
            float flat = Mth.sqrt(x * x + z * z);
            // 遠い縁は消える・真上のすぐ近くも薄く(頭の上に板が見えないように)。
            float fade = (1.0F - SunLight.smooth(Mth.clamp((flat - range * 0.75F) / (range * 0.35F), 0.0F, 1.0F)))
                    * SunLight.smooth(Mth.clamp(dist / 160.0F, 0.0F, 1.0F));
            float a = gate * fade * (0.7F + 0.3F * w.tone);
            if (a <= 0.004F) {
                continue;
            }
            // 遠いほど霧の色へ寄せる(空に溶ける)。
            float far = Mth.clamp(flat / range, 0.0F, 1.0F) * 0.55F;
            float tone = 0.88F + 0.24F * w.tone;
            float r = Math.min(1.0F, Mth.lerp(far, col[0] * light * tone, fog[0]));
            float g = Math.min(1.0F, Mth.lerp(far, col[1] * light * tone, fog[1]));
            float b = Math.min(1.0F, Mth.lerp(far, col[2] * light * tone, fog[2]));
            float k = Math.min(1.0F, drawAt / Math.max(dist, 1.0F));
            Random rnd = new Random(w.seed);
            // 薄い芯と、前後にずれた小さなかたまり 6 つ(まだらな筋にする)。
            WeatherShapes.softEllipse(x, y, z, dirX, dirZ, w.len * 0.5F, w.wid * 0.5F, k, r, g, b, a * 0.55F);
            for (int i = 0; i < 6; i++) {
                float sh = (rnd.nextFloat() - 0.5F) * w.len * 0.9F;
                float sd = (rnd.nextFloat() - 0.5F) * w.wid * 0.8F;
                float up = (rnd.nextFloat() - 0.5F) * 18.0F;
                WeatherShapes.softEllipse(x + dirX * sh - dirZ * sd, y + up, z + dirZ * sh + dirX * sd, dirX, dirZ,
                        w.len * (0.1F + 0.16F * rnd.nextFloat()), w.wid * (0.16F + 0.22F * rnd.nextFloat()), k, r, g, b, a * (0.6F + 0.4F * rnd.nextFloat()));
            }
        }
    }

    static String describe() {
        return String.format("胞子の雲 %.2f・筋 %d・風下 (%.2f, %.2f)", shown, WISPS.size(), dirX, dirZ);
    }
}
