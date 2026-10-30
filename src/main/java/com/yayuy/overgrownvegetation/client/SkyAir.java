package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * 空の気流(2026-10-18。空の世界の順番 3。ユーザー選択「上昇気流と下降気流」、空のギミックは「突風や気流に乱気流、雲の中の雷と乱気流」)。
 *
 * <ul>
 *   <li>上昇気流: 育った積乱雲の脇(胴の半径の 0.85〜1.7 倍、雲の底 300 から天辺の 7 割まで)で上へ持ち上げる(雲へ吸い込まれる流れ)。入道雲(10-18)の脇も 6 割の強さで。
 *       塔の間を縫って飛ぶと、雲の脇で持ち上がる。</li>
 *   <li>下降気流: 積乱雲の風下側(胴の 1.7〜2.6 倍、風の向きから ±60°)で下へ(雲から吹き出す冷たい流れ)。晴れた地域({@link CloudField#region} が負)は弱く沈む。</li>
 *   <li>突風: 雲の上(高空の晴天)で、ゆっくりした雑音の高い所だけ、今の世界の風の向きへ押す(数秒の突風が十数秒おきに。風が強いほど強く多い)。</li>
 *   <li>押すのは手元のプレイヤーだけ(プレイヤーの動きはクライアントが決める)。地面に立っている・観戦モード・クリエイティブで飛んでいる・乗り物の中は押さない。
 *       エリトラで滑空中は 1.6 倍。雲の中の乱気流({@link CloudTurbulence})とは別に足す。</li>
 *   <li>風の白い帯({@link WindRibbons})と雲の上の風の音({@code sound/CloudWindSound})は {@link #gust()} と {@link #lift} を読み、見え方と音を揃える。</li>
 * </ul>
 * 数値はすべて仮決め。設定 {@code skyAir}(0〜1)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SkyAir {
    /** 上昇・下降気流が 1 tick に足す上下の速さ(ブロック/tick)。 */
    static final double UPDRAFT = 0.032;
    static final double DOWNDRAFT = 0.02;
    static final double SUBSIDE = 0.004;
    /** 突風が 1 tick に足す横の速さ(ブロック/tick、風が秒速 1 のとき)。 */
    static final double GUST_PUSH = 0.014;
    static final double GLIDE = 1.6;

    private static float gust;
    private static float lastLift;
    private static long ticks;
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    static float setting() {
        return OvergrownVegetationConfig.COMMON.skyAir.get().floatValue();
    }

    /** 今の突風(0〜1。雲の上でだけ。風の強さで大きくなる)。 */
    public static float gust() {
        return gust;
    }

    /** プレイヤーの所の上下の気流(最後に測った値。上 + / 下 −、ブロック/tick)。 */
    public static float playerLift() {
        return lastLift;
    }

    /**
     * 場の座標 (fx, fz)・高さ y の上下の気流(ブロック/tick の目安。上 + / 下 −)。積乱雲は {@link CloudInterior#storms()}(カメラのまわり)。
     */
    public static double lift(double fx, double y, double fz, double windX, double windZ) {
        // 上昇と下降は別々にいちばん強いものを取って差し引く(最初は 1 つの値に max と min を重ねたので、風上にいる別の雲の下降気流 −0.0 が上昇気流を消した)。
        double up = 0.0;
        double down = 0.0;
        List<CloudField.Storm> storms = CloudInterior.storms();
        double wl = Math.max(1.0E-6, Math.sqrt(windX * windX + windZ * windZ));
        for (CloudField.Storm s : storms) {
            if (s.kind() == 1 || s.maturity() < 0.4F) {
                continue;
            }
            // 入道雲(10-18)の脇も持ち上げる(積乱雲の 6 割)。
            double scale = s.congestus() ? 0.6 : 1.0;
            double r = s.bodyRadius();
            double dx = fx - s.x();
            double dz = fz - s.z();
            if (Math.abs(dx) > r * 2.7 || Math.abs(dz) > r * 2.7) {
                continue;
            }
            double d = Math.sqrt(dx * dx + dz * dz);
            double top = s.topY();
            double h = (y - CloudField.LOW_BASE) / Math.max(1.0, top - CloudField.LOW_BASE);
            if (h < -0.15 || h > 1.0) {
                continue;
            }
            float grown = Mth.clamp((s.maturity() - 0.4F) / 0.4F, 0.0F, 1.0F);
            // 上昇気流: 脇の輪(0.85〜1.7 倍)。高さの 7 割より上では弱まる。
            double ring = (d - r * 0.85) / (r * 0.85);
            if (ring > 0.0 && ring < 1.0) {
                double bump = Math.sin(Math.PI * ring);
                double vert = 1.0 - CanopyLight.smooth((float) Mth.clamp((h - 0.55) / 0.3, 0.0, 1.0));
                up = Math.max(up, UPDRAFT * scale * bump * vert * grown);
            }
            // 下降気流: 風下側の 1.7〜2.6 倍。
            double out = (d - r * 1.7) / (r * 0.9);
            if (out > 0.0 && out < 1.0 && d > 1.0) {
                double toward = (dx * windX + dz * windZ) / (d * wl);
                double cone = CanopyLight.smooth((float) Mth.clamp((toward - 0.5) / 0.4, 0.0, 1.0));
                down = Math.max(down, DOWNDRAFT * scale * Math.sin(Math.PI * out) * cone * grown);
            }
        }
        double w = up - down;
        // 晴れた地域は弱く沈む(高気圧の下の空)。
        float region = CloudField.region(fx, fz);
        if (region < 0.0F) {
            w -= SUBSIDE * Math.min(1.0, -region / 0.6);
        }
        return w;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        float setting = setting();
        float high = HighSky.clarity();
        float inside = CloudInterior.amount(1.0F) * CloudInterior.setting();
        double t = mc.level.getGameTime();
        double windX = CloudField.windX(t);
        double windZ = CloudField.windZ(t);
        double speed = Math.sqrt(windX * windX + windZ * windZ);
        // 突風: ゆっくりした雑音の高い所(数秒の突風が十数秒おき)。風が強いほど出やすく強い。
        double sec = t / 20.0;
        float n = valueNoise(sec / 4.5) * 0.7F + valueNoise(sec / 1.6 + 17.0) * 0.3F;
        float windy = Mth.clamp((float) (speed / 0.05), 0.3F, 1.8F);
        float g = smoothstep(Mth.lerp(Mth.clamp(windy - 0.3F, 0.0F, 1.0F), 0.62F, 0.5F), 0.85F, n) * Math.min(1.0F, windy);
        gust = g * high * (1.0F - inside) * setting;
        if (setting <= 0.0F || high <= 0.01F || player.onGround() || player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
            lastLift = 0.0F;
            return;
        }
        Vec3 pos = player.position();
        double fx = CloudField.fieldX(pos.x, mc.level.getGameTime(), 1.0F);
        double fz = CloudField.fieldZ(pos.z, mc.level.getGameTime(), 1.0F);
        double w = lift(fx, pos.y, fz, windX, windZ) * high * setting;
        lastLift = (float) w;
        if (DEV_LOG && ticks % 20 == 0) {
            OvergrownVegetation.LOGGER.info("SkyAir 気流 {} 突風 {} 地域 {} 積乱雲 {} 速さ {}", String.format("%.4f", w), String.format("%.2f", gust),
                    String.format("%.2f", CloudField.region(fx, fz)), CloudInterior.storms().size(), String.format("%.3f", player.getDeltaMovement().y));
        }
        double k = player.isFallFlying() ? GLIDE : 1.0;
        double push = GUST_PUSH * gust * windy * k;
        double wl = Math.max(1.0E-6, speed);
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x + windX / wl * push, v.y + w * k, v.z + windZ / wl * push);
    }

    /** 0〜1 のなめらかな 1 次元の雑音。 */
    private static float valueNoise(double x) {
        long i = (long) Math.floor(x);
        float f = (float) (x - i);
        return Mth.lerp(f * f * (3.0F - 2.0F * f), hash(i), hash(i + 1));
    }

    private static float hash(long i) {
        long h = i * 0x9E3779B97F4A7C15L + 0x5A1A1EL;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1 << 24);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private SkyAir() {
    }
}
