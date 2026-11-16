package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>強風の日に横に流れる胞子の綿</b>(天気「強風の日」の見た目。2026-11-16。ユーザー決定: 木々が大きく揺れ、葉や胞子の綿が横に流れる)。
 *
 * <p>白っぽい黄緑の小さな綿を、プレイヤーのまわり {@code fluffRange} ブロックに {@code fluffCount} 個まで。林の風({@link Wind#current()})に乗って
 * 横へ速く流れ(風の強さ 1 で毎秒 {@code fluffSpeed} ブロック)、ゆるく上下する。突風の前線が来ると一斉に速くなる。流れの向きに短い尾を引く帯で描く
 * (ふつうの混ぜ方・光らせない)。ブロックに当たると消え、範囲の外へ出たら風上の側から入り直す。森の外では {@code fluffOpen} 倍に減らす
 * (綿は森から飛んでくる)。葉(落ち葉)は {@link FallingLeaves} が風の強さで増やすので、ここでは足さない。数は節 {@code galeLook} の fluff*(仮決め)。
 */
final class GaleFluff {

    private GaleFluff() {
    }

    private static final class Fluff {
        double x, y, z, px, py, pz;
        float phase, size;
        int age;
    }

    private static final List<Fluff> LIVE = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static final double[] WIND = new double[2];
    private static float shown;

    static void clear() {
        LIVE.clear();
        shown = 0.0F;
    }

    /** 毎 tick。{@code gale} = 強風の日の度合い 0〜1。 */
    static void tick(ClientLevel level, LocalPlayer player, float gale, double time) {
        shown = gale;
        if (gale <= 0.01F || ForestPresence.underground() > 0.6F) {
            LIVE.clear();
            return;
        }
        WeatherLookEye.Params p = WeatherLook.gale();
        float range = p.f("fluffRange");
        float place = Mth.lerp(Math.max(ForestPresence.cover(), ForestPresence.overgrown()), p.f("fluffOpen"), 1.0F);
        int want = Math.round(VisualQuality.particleCap(Math.round(p.f("fluffCount"))) * gale * place * (1.0F - 0.6F * level.getRainLevel(1.0F)));
        Wind wind = Wind.current();
        float speed = p.f("fluffSpeed") / 20.0F;
        Vec3 eye = player.getEyePosition();
        for (int i = LIVE.size() - 1; i >= 0; i--) {
            Fluff f = LIVE.get(i);
            f.px = f.x;
            f.py = f.y;
            f.pz = f.z;
            wind.velocity(f.x, f.z, time, WIND);
            f.x += WIND[0] * speed;
            f.z += WIND[1] * speed;
            f.y += Math.sin(time * 0.11 + f.phase) * 0.035 + Math.sin(time * 0.043 + f.phase * 2.3) * 0.025 - 0.004;
            f.age++;
            double dx = f.x - eye.x, dy = f.y - eye.y, dz = f.z - eye.z;
            boolean out = dx * dx + dz * dz > range * range || Math.abs(dy) > 18.0;
            if (out || i >= want || !level.getBlockState(BlockPos.containing(f.x, f.y, f.z)).isAir()) {
                LIVE.remove(i);
            }
        }
        // 足りない分は、風上の縁(ときどき中)から。
        for (int tries = 0; tries < 6 && LIVE.size() < want; tries++) {
            double a = Math.atan2(-wind.dirZ(), -wind.dirX()) + (RANDOM.nextDouble() - 0.5) * 2.4;
            double d = range * (RANDOM.nextFloat() < 0.25F ? Math.sqrt(RANDOM.nextDouble()) : 0.85 + 0.12 * RANDOM.nextDouble());
            Fluff f = new Fluff();
            f.x = eye.x + Math.cos(a) * d;
            f.z = eye.z + Math.sin(a) * d;
            f.y = eye.y - 5.0 + RANDOM.nextDouble() * 17.0;
            if (!level.getBlockState(BlockPos.containing(f.x, f.y, f.z)).isAir()) {
                continue;
            }
            f.px = f.x;
            f.py = f.y;
            f.pz = f.z;
            f.phase = RANDOM.nextFloat() * 6.28F;
            f.size = p.f("fluffSize") * (0.6F + 0.9F * RANDOM.nextFloat());
            LIVE.add(f);
        }
    }

    /** {@link Atmosphere} のふつうの混ぜ方の所から({@link WeatherShapes#begin} の中)。 */
    static void render(RenderLevelStageEvent event) {
        if (LIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        WeatherLookEye.Params p = WeatherLook.gale();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = event.getCamera().getPosition();
        float[] col = p.rgb("fluffColor");
        // 明るさは空の明るさで(夜は暗い)。
        float light = Mth.lerp(SunLight.night(level, partial), 1.0F, 0.2F) * (1.0F - 0.25F * level.getRainLevel(partial));
        float alpha = p.f("fluffAlpha");
        float range = p.f("fluffRange");
        for (Fluff f : LIVE) {
            float x = (float) (Mth.lerp(partial, f.px, f.x) - eye.x);
            float y = (float) (Mth.lerp(partial, f.py, f.y) - eye.y);
            float z = (float) (Mth.lerp(partial, f.pz, f.z) - eye.z);
            // 尾 = 3 tick ぶん後ろ(速いほど長い帯になる)。止まっていても綿の大きさは残す。
            float tx = (float) (f.x - f.px) * 3.0F, ty = (float) (f.y - f.py) * 3.0F, tz = (float) (f.z - f.pz) * 3.0F;
            float tl = Mth.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl < f.size * 1.5F) {
                float k = f.size * 1.5F / Math.max(tl, 1.0e-4F);
                tx = tl < 1.0e-4F ? f.size * 1.5F : tx * k;
                ty *= tl < 1.0e-4F ? 0.0F : k;
                tz *= tl < 1.0e-4F ? 0.0F : k;
            }
            float dist = Mth.sqrt(x * x + y * y + z * z);
            float a = alpha * Mth.clamp(f.age / 6.0F, 0.0F, 1.0F) * (1.0F - SunLight.smooth(Mth.clamp((dist - range * 0.75F) / (range * 0.25F), 0.0F, 1.0F)))
                    * (1.0F - MistView.fogAmount(dist));
            WeatherShapes.softStreak(x, y, z, x - tx, y - ty, z - tz, f.size * 2.0F, col[0] * light, col[1] * light, col[2] * light, a);
        }
    }

    static String describe() {
        return String.format("胞子の綿 %.2f・%d 個", shown, LIVE.size());
    }
}
