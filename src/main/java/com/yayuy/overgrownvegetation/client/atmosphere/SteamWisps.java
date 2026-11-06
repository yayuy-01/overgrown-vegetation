package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>雨上がりの蒸気</b>(一覧 37、2026-10-31 見え方の段。設定: 熱帯の林床は雨上がりの日差しで湯気が立つ)。
 * 雨が上がって日が出ている間({@code steam.minutes} 分かけて弱まる。10-31 照りの 3 分に揃えた・仮。時計は照りと同じ {@link MistFog#wetAfter})、林冠の穴の下(空の光がほぼ届く林床)から細い湯気がゆっくり立ちのぼる。
 * カメラが森の中ほど(森の度合いが {@code presenceFrom}〜{@code presenceTo})のときだけ。形は {@link LifeSprites#blob} を縦に伸ばして重ねる。
 * 値は {@code atmosphere.json} の節 {@code steam}(仮決め)。
 */
final class SteamWisps {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Wisp> WISPS = new ArrayList<>();
    private static final double[] WIND = new double[2];
    private static float amount;

    private record Wisp(double x, double y, double z, float seed, long born, int life) {
    }

    static void clear() {
        WISPS.clear();
        amount = 0.0F;
    }

    /** 開発用: 今の出方。 */
    static String describe() {
        return String.format("湯気 %.2f 数 %d", amount, WISPS.size());
    }

    /** 毎 tick。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.motes()) {
            clear();
            return;
        }
        AtmosphereLife.SteamParams p = DesignData.atmosphere().steam();
        long now = level.getGameTime();
        float rain = level.getRainLevel(1.0F);
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        float day = Mth.clamp(((float) sun.y - 0.1F) / 0.25F, 0.0F, 1.0F);
        float presence = ForestPresence.openOvergrown();
        float window = Mth.clamp((presence - p.presenceFrom()) / 0.1F, 0.0F, 1.0F) * Mth.clamp((p.presenceTo() + 0.1F - presence) / 0.1F, 0.0F, 1.0F);
        float target = MistFog.wetAfter(p.minutes(), 0.0F) * day * (1.0F - rain) * window;
        amount += (target - amount) * 0.05F;
        WISPS.removeIf(w -> now - w.born > w.life);
        int cap = Math.round(p.counts()[Math.min(2, VisualQuality.atmosphereTier())] * amount);
        if (WISPS.size() >= cap || !VisualQuality.atmosphere()) {
            return;
        }
        // 1 tick に 2 か所まで試す(林冠の穴の下の、空の光がほぼ届く過繁茂の林床)。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < 2 && WISPS.size() < cap; tries++) {
            double x = player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius();
            double z = player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius();
            int floor = WaterSigns.floorBelow(level, Mth.floor(x), Mth.floor(player.getY()) + 6, Mth.floor(z), 16, pos);
            if (floor == Integer.MIN_VALUE) {
                continue;
            }
            pos.set(Mth.floor(x), floor, Mth.floor(z));
            if (level.getBrightness(LightLayer.SKY, pos) < 13 || !ModBiomes.isOvergrownLike(level.getBiome(pos))
                    || !level.getFluidState(pos).isEmpty()) {
                continue;
            }
            int life = Math.round(p.height() / p.riseSpeed() * 20.0F * (0.7F + 0.6F * RANDOM.nextFloat()));
            WISPS.add(new Wisp(x, floor, z, RANDOM.nextFloat() * 100.0F, now, life));
        }
    }

    /** AFTER_PARTICLES({@link LifeSprites} の間)。 */
    static void render(ClientLevel level, float partialTick, Vector3f left, Vector3f up) {
        if (!VisualQuality.motes()) {
            return;
        }
        if (WISPS.isEmpty()) {
            return;
        }
        AtmosphereLife.SteamParams p = DesignData.atmosphere().steam();
        double now = level.getGameTime() + partialTick;
        double time = Atmosphere.time(partialTick);
        Wind wind = Wind.current();
        for (Wisp w : WISPS) {
            double age = (now - w.born) / 20.0;
            float t = (float) Math.min(1.0, (now - w.born) / w.life);
            float fade = Mth.clamp(t / 0.2F, 0.0F, 1.0F) * Mth.clamp((1.0F - t) / 0.4F, 0.0F, 1.0F);
            wind.velocity(w.x, w.z, time, WIND);
            // 3 つの塊を縦に連ねて細い柱にし、上ほど風下へ流れて薄く大きく。
            for (int k = 0; k < 3; k++) {
                double rise = age * p.riseSpeed() - k * 0.35;
                if (rise < 0.0) {
                    continue;
                }
                double drift = rise * rise * 0.06;
                double sway = Math.sin(age * 0.8 + w.seed + k) * 0.08 * rise;
                double x = w.x + WIND[0] * drift + sway;
                double z = w.z + WIND[1] * drift + Math.cos(age * 0.7 + w.seed) * 0.08 * rise;
                float size = p.size() * (0.5F + (float) rise * 0.25F);
                float alpha = p.alpha() * fade * amount * (1.0F - k * 0.2F);
                LifeSprites.blob(x, w.y + 0.1 + rise, z, size, 1.8F, left, up, 0.93F, 0.95F, 0.90F, alpha);
            }
        }
    }

    private SteamWisps() {
    }
}
