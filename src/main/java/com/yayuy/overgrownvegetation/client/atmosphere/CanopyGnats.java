package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereGiantLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * <b>巨大樹の傘のまわりを回る羽虫の群れ</b>(2026-11-06 ユーザー採用の改善案 ⑤ の 1。設定: 環境の生き物は虫と爬虫類、鳥はいない)。
 *
 * <p>近い巨大樹(最大 3 本)ごとに、傘の縁の下側と幹の上の方に {@code giantLife.gnats.swarms} 個の群れを置く。群れの中の虫は群れの真ん中のまわりを
 * 渦を巻いて回り、群れそのものも傘の縁に沿ってゆっくり巡る。描き方は林床の羽虫({@link GnatSwarms})と同じ暗い点({@link LifeSprites#dot})だが、
 * 群れは 50〜200 ブロック先なので、<b>点の大きさを距離に比例させて</b>(見かけの大きさ一定)遠くでも 1〜3 画素に見せる。
 * 出るのは {@code fromHour}〜{@code toHour} 時(雨の間は減る)。夜は光る虫({@code nightGlow}、11-07 ユーザー決定。既定 0.6 は仮決め。0 にすると夜は出さない)。
 * 値はすべて仮決め(節 {@code giantLife.gnats})。
 */
final class CanopyGnats {

    private static final int MAX_TREES = 3;
    private static List<GiantTrees.Info> trees = List.of();
    private static float day;
    private static float night;

    private CanopyGnats() {
    }

    static void clear() {
        trees = List.of();
        day = 0.0F;
        night = 0.0F;
    }

    static String describe() {
        GiantTrees.Info t = trees.isEmpty() ? null : trees.get(0);
        return String.format("傘の羽虫 昼 %.2f 夜 %.2f 木 %d 描画 %s", day, night, trees.size(), timing()) + (t == null ? "" : String.format("(軸 %.0f %.0f 根元 %d 先 %d 傘 %.0f)", t.axisX(), t.axisZ(), t.baseY(), t.topY(), t.crownRadius()));
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.critters()) {
            clear();
            return;
        }
        AtmosphereGiantLife.GnatsParams p = DesignData.atmosphere().giantLife().gnats();
        float hour = (float) (Math.floorMod(level.getDayTime(), 24000L) / 1000.0 + 6.0) % 24.0F;
        float time = Mth.clamp((hour - p.fromHour()) / 0.75F, 0.0F, 1.0F) * Mth.clamp((p.toHour() - hour) / 0.75F, 0.0F, 1.0F);
        float dry = 1.0F - 0.8F * level.getRainLevel(1.0F);
        day += (time * dry - day) * 0.02F;
        float dark = Mth.clamp((hour >= 12.0F ? hour - p.toHour() : p.fromHour() - hour) / 1.0F, 0.0F, 1.0F);
        night += (dark * p.nightGlow() * dry - night) * 0.02F;
        if (level.getGameTime() % 20L == 0L || trees.isEmpty()) {
            trees = GiantLifeTrees.nearest(player.getX(), player.getZ(), p.maxDistance(), MAX_TREES);
        }
    }

    /** 昼の暗い点({@link LifeSprites} の普通の重ね)。 */
    static void render(float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        if (day > 0.01F) {
            draw(partialTick, cam, left, up, false, day);
        }
    }

    /** 夜の光る虫({@link LifeSprites} の足し算の重ね。nightGlow が 0 なら何もしない)。 */
    static void renderGlow(float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        if (night > 0.01F) {
            draw(partialTick, cam, left, up, true, night);
        }
    }

    static boolean glowing() {
        return night > 0.01F && !trees.isEmpty();
    }

    /** 開発用: 描くのに掛かった時間(ナノ秒)の合計と回数。 */
    private static long nanos;
    private static int frames;

    static String timing() {
        String s = frames > 0 ? String.format("%.3f ms/フレーム", nanos / 1.0e6 / frames) : "-";
        nanos = 0L;
        frames = 0;
        return s;
    }

    private static void draw(float partialTick, Vec3 cam, Vector3f left, Vector3f up, boolean glow, float amount) {
        long t0 = System.nanoTime();
        try {
            drawInner(partialTick, cam, left, up, glow, amount);
        } finally {
            nanos += System.nanoTime() - t0;
            frames++;
        }
    }

    private static void drawInner(float partialTick, Vec3 cam, Vector3f left, Vector3f up, boolean glow, float amount) {
        if (!VisualQuality.critters() || trees.isEmpty()) {
            return;
        }
        AtmosphereGiantLife.GnatsParams p = DesignData.atmosphere().giantLife().gnats();
        int count = p.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        if (glow) {
            count = Math.max(1, count / 4); // 光る虫はまばらに
        }
        double t = Atmosphere.time(partialTick) / 20.0 * p.speed();
        for (GiantTrees.Info tree : trees) {
            float ks = (float) Math.floorMod((GiantLifeTrees.key(tree) * 0x9E3779B97F4A7C15L) >>> 40, 10000L) * 0.01F;
            double crown = tree.crownRadius();
            double height = tree.topY() - tree.baseY();
            for (int s = 0; s < p.swarms(); s++) {
                float h1 = hash(ks + s * 7.31F);
                float h2 = hash(ks + s * 3.17F + 5.0F);
                float h3 = hash(ks + s * 1.91F + 9.0F);
                double cx;
                double cy;
                double cz;
                // 群れの真ん中: 最後の 1 つは幹の上の方(幹の軸から少し外)、ほかは傘の縁の下側。どちらも木のまわりをゆっくり巡る。
                double orbit = h1 * Mth.TWO_PI + t * 0.02 * (h2 < 0.5 ? -1.0 : 1.0);
                if (s == p.swarms() - 1) {
                    double r = Math.max(6.0, crown * 0.12) + 2.0 * h3;
                    cx = tree.axisX() + Math.cos(orbit) * r;
                    cz = tree.axisZ() + Math.sin(orbit) * r;
                    cy = tree.baseY() + height * (0.6 + 0.15 * h2);
                } else {
                    double r = crown * (0.82 + 0.2 * h3);
                    cx = tree.axisX() + Math.cos(orbit) * r;
                    cz = tree.axisZ() + Math.sin(orbit) * r;
                    cy = tree.topY() - crown * (0.3 + 0.2 * h2) + Math.sin(t * 0.13 + s) * 2.0;
                }
                double dist = Math.sqrt((cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z));
                float half = (float) Math.max(p.minHalf(), dist * p.angular()) * (glow ? 1.4F : 1.0F);
                float spread = p.spread();
                for (int k = 0; k < count; k++) {
                    float a = hash(s * 13.7F + k * 1.7F + ks);
                    float b = hash(s * 5.9F + k * 3.1F + 11.0F);
                    float c = hash(s * 2.3F + k * 5.3F + 23.0F);
                    // 渦: 内側ほど速く回り、上下にゆっくりふらつく。群れの形は横に広い楕円。
                    double rr = spread * (0.15 + 0.85 * Math.sqrt(a));
                    double w = (b < 0.5 ? -1.0 : 1.0) * (1.2 + 2.0 * c) / (0.4 + rr / spread);
                    double ang = t * w + a * 40.0;
                    double x = cx + Math.cos(ang) * rr;
                    double z = cz + Math.sin(ang) * rr * 0.8;
                    double y = cy + (b - 0.5) * p.depth() + Math.sin(t * (1.5 + 2.5 * a) + k) * 0.4;
                    if (glow) {
                        // 明滅(ゆっくり)。色は青緑寄り(植物のエネルギーの色。琥珀にしない)。
                        float blink = 0.5F + 0.5F * Mth.sin((float) (t * (2.0 + 3.0 * c) + k * 1.3));
                        LifeSprites.dot(x, y, z, half, left, up, 0.45F, 0.95F, 0.75F, 0.8F * amount * blink * blink);
                    } else {
                        LifeSprites.dot(x, y, z, half, left, up, 0.07F, 0.07F, 0.05F, 0.8F * amount);
                    }
                }
            }
        }
    }

    private static float hash(float v) {
        float s = Mth.sin(v * 12.9898F) * 43758.547F;
        return s - Mth.floor(s);
    }
}
