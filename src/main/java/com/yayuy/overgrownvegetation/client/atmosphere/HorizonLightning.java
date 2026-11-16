package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.sky.VoxelClouds;
import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * <b>地平線の遠い雷</b>(雷雨の強化の残り。2026-11-16。ユーザー「遠くで雷が見えたりとか、雷雨の時にしか見れない自然現象を詰めたい」:
 * 遠くの雲の中で静かに光り続ける稲光・音は届かない)。
 *
 * <p>サーバーの落雷({@link StormStrikes})は 1500 ブロック以内の雲だけなので、それより遠い積乱雲の中を、クライアントが見た目だけ光らせる
 * (サーバーの知らせなし・音なし・稲妻の線なし・雲の中の光だけ)。雲のシェーダーの光の口(Flash0〜2)は本物の落雷が先に使い、
 * 空いた口をここが使う({@link StormStrikes#cloudFlash} の続き)。出るのは雷雨の間・前触れ・雷雨の後しばらく({@link WeatherLook#stormDrive})。
 * 1 回の稲光は 0.1 秒ほどの瞬きを 2〜4 回。場所は雲と一緒に流れる(雲の場の座標で持つ)。数は節 {@code stormExtras} の far*(仮決め)。
 */
final class HorizonLightning {

    private HorizonLightning() {
    }

    private static final class Flash {
        /** 雲の場の座標(風で流れた分を引いた座標)と高さ。 */
        double fx, fz;
        float y;
        long startNanos;
        float strength;
        float[] at;
        float[] amp;
        float life;
    }

    private static final List<Flash> FLASHES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static List<CloudField.Storm> candidates = List.of();
    private static int refreshIn;
    private static int seen;
    private static long lastQueryNanos;

    static void clear() {
        FLASHES.clear();
        candidates = List.of();
        refreshIn = 0;
    }

    /** 毎 tick。{@code drive} = 出やすさ 0〜1。 */
    static void tick(ClientLevel level, LocalPlayer player, float drive) {
        long now = System.nanoTime();
        FLASHES.removeIf(f -> (now - f.startNanos) / 1.0e9 > (devHold ? 20.0F : f.life));
        if (drive <= 0.02F || !VoxelClouds.isActive()) {
            candidates = List.of();
            refreshIn = 0;
            return;
        }
        WeatherLookEye.Params p = WeatherLook.storm();
        long gameTime = level.getGameTime();
        double camFx = CloudField.fieldX(player.getX(), gameTime, 0.0F);
        double camFz = CloudField.fieldZ(player.getZ(), gameTime, 0.0F);
        // 相手の雲の一覧は、雲の場の座標では動かないので、たまに引き直すだけでよい(1 回 5〜14 ms かかる。20 秒に 1 回)。
        if (--refreshIn <= 0) {
            refreshIn = 400;
            double max = p.f("farMax");
            double min = p.f("farMin");
            long t0 = System.nanoTime();
            List<CloudField.Storm> all = VoxelClouds.storms(level, 0.0F, VoxelClouds.coverage(level, 1.0F), camFx - max, camFz - max, camFx + max, camFz + max);
            lastQueryNanos = System.nanoTime() - t0;
            List<CloudField.Storm> keep = new ArrayList<>();
            for (CloudField.Storm s : all) {
                if (s.kind() == CloudField.KIND_FLOAT || s.kind() == CloudField.KIND_CUMULUS || s.maturity() < 0.5F) {
                    continue;
                }
                double d = Math.hypot(s.x() - camFx, s.z() - camFz);
                if (d >= min && d <= max) {
                    keep.add(s);
                }
            }
            candidates = keep;
        }
        if (candidates.isEmpty() || FLASHES.size() >= Math.round(p.f("farMaxAlive"))) {
            return;
        }
        if (RANDOM.nextFloat() >= drive / Math.max(1.0F, p.f("farEvery") * 20.0F)) {
            return;
        }
        CloudField.Storm s = candidates.get(RANDOM.nextInt(candidates.size()));
        spawn(level, s, camFx, camFz, player, p);
    }

    private static void spawn(ClientLevel level, CloudField.Storm s, double camFx, double camFz, LocalPlayer player, WeatherLookEye.Params p) {
        Flash f = new Flash();
        // 橋・尾根は 2 点の間のどこか、塔は胴の中のどこか。
        double t = s.kind() == 1 || s.kind() == CloudField.KIND_RIDGE ? RANDOM.nextDouble() : 0.0;
        double cx = s.x() + (s.x2() - s.x()) * t;
        double cz = s.z() + (s.z2() - s.z()) * t;
        double a = RANDOM.nextDouble() * Math.PI * 2.0;
        double r = s.radius() * 0.5 * Math.sqrt(RANDOM.nextDouble());
        f.fx = cx + Math.cos(a) * r;
        f.fz = cz + Math.sin(a) * r;
        int top = s.topY();
        float low = CloudField.LOW_BASE + 60.0F;
        float high = Math.max(low + 40.0F, top - 120.0F);
        f.y = low + RANDOM.nextFloat() * (high - low) * 0.8F;
        f.startNanos = System.nanoTime();
        f.strength = p.f("farStrength") * (0.6F + 0.4F * RANDOM.nextFloat());
        int n = 2 + RANDOM.nextInt(3);
        f.at = new float[n];
        f.amp = new float[n];
        float at = 0.0F;
        for (int i = 0; i < n; i++) {
            f.at[i] = at;
            f.amp[i] = i == 0 ? 1.0F : 0.45F + 0.55F * RANDOM.nextFloat();
            at += 0.08F + RANDOM.nextFloat() * 0.22F;
        }
        f.life = at + 0.2F;
        FLASHES.add(f);
        seen++;
        // スプライト(夜だけ・遠い雲だけ。Sprites が割合を決める)。
        double wx = f.fx - camFx + player.getX();
        double wz = f.fz - camFz + player.getZ();
        Sprites.maybeSpawn(level, player, wx, wz, top);
    }

    /** 確かめ用: 光りっぱなしにする(1 コマの絵に撮るため)。 */
    static boolean devHold;

    private static float level(Flash f, float age) {
        if (devHold) {
            return 1.0F;
        }
        float v = 0.0F;
        for (int i = 0; i < f.at.length; i++) {
            float a = age - f.at[i];
            if (a >= 0.0F) {
                // 立ち上がりは一瞬、0.13 秒で消える。
                v = Math.max(v, f.amp[i] * Math.max(0.0F, 1.0F - a / 0.13F));
            }
        }
        // 瞬きの合間もうっすら残る(雲の中で光がくすぶる)。
        return Math.max(v, age < f.life - 0.2F ? 0.12F : 0.0F);
    }

    /** 雲の中の光({@link StormStrikes#cloudFlash} の空いた口へ): i 番目の {カメラからの x, y, z, 強さ}。無ければ強さ 0。 */
    static float[] cloudFlash(int i, double camX, double camY, double camZ) {
        if (i < 0 || i >= FLASHES.size() || WeatherLook.off("far")) {
            return new float[]{0.0F, 0.0F, 0.0F, 0.0F};
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (lvl == null) {
            return new float[]{0.0F, 0.0F, 0.0F, 0.0F};
        }
        Flash f = FLASHES.get(FLASHES.size() - 1 - i);
        float age = (float) ((System.nanoTime() - f.startNanos) / 1.0e9);
        float v = level(f, age);
        if (v <= 0.0F) {
            return new float[]{0.0F, 0.0F, 0.0F, 0.0F};
        }
        double t = lvl.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false);
        double wx = f.fx + CloudField.offsetX(t);
        double wz = f.fz + CloudField.offsetZ(t);
        return new float[]{(float) (wx - camX), (float) (f.y - camY), (float) (wz - camZ), v * f.strength};
    }

    /** 確かめ用: 一番近い相手の雲を今すぐ光らせる。返事はその雲までの距離と向き。 */
    static String force(ClientLevel level, LocalPlayer player) {
        refreshIn = 0;
        tick(level, player, 1.0F);
        if (candidates.isEmpty()) {
            return "相手の積乱雲が無い";
        }
        long gameTime = level.getGameTime();
        double camFx = CloudField.fieldX(player.getX(), gameTime, 0.0F);
        double camFz = CloudField.fieldZ(player.getZ(), gameTime, 0.0F);
        CloudField.Storm best = null;
        for (CloudField.Storm s : candidates) {
            if (best == null || Math.hypot(s.x() - camFx, s.z() - camFz) < Math.hypot(best.x() - camFx, best.z() - camFz)) {
                best = s;
            }
        }
        spawn(level, best, camFx, camFz, player, WeatherLook.storm());
        double dx = best.x() - camFx;
        double dz = best.z() - camFz;
        return String.format("光らせた 距離 %.0f 向き(yaw)%.0f 天辺 %d 半径 %.0f 種類 %d", Math.hypot(dx, dz), Math.toDegrees(Math.atan2(-dx, dz)),
                best.topY(), best.radius(), best.kind());
    }

    static String describe() {
        return String.format("遠い雷 相手の雲 %d・いま %d・これまで %d・雲を引く %.2f ms", candidates.size(), FLASHES.size(), seen, lastQueryNanos / 1.0e6);
    }
}
