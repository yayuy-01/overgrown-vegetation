package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.List;

/**
 * 雲の中(2026-10-17 深夜。ユーザー「次は雲の中の演出や雲のビジュアルについて詰めていきましょう」で選んだ「白い霧」「抜けるときのもや」)。
 * カメラがどれだけ雲の中にいるかを毎 tick 出し、ほかの雲の中の演出(ちぎれ雲・画面の水滴・風の音・乱気流・雷)もこれを読む。
 *
 * <ul>
 *   <li><b>中にいる度合い</b>: カメラと、そのまわりの半径 {@link #INNER}・{@link #OUTER} の球の上の 12 点ずつで「雲の箱の中か」を確かめ
 *       (描いている箱と同じ升・同じ式 {@link #solidAt})、中の割合をならす。雲の面の外 15 ブロックほどから入り始め、面で半分、中へ 15 ブロックで 1。
 *       この入り始めの幅が「抜けるときのもや」(出入りのとき、霧がだんだん濃く・薄くなる)。</li>
 *   <li><b>白い霧</b>: 中にいる度合いに合わせて、バニラの霧(地形・Mob・空)を近くまで寄せ(下の層・雲の海は {@link #LOW_FOG} ブロック、積乱雲は
 *       {@link #STORM_FOG} ブロックで見えなくなる)、霧の色を雲の色(積乱雲の中は灰色。雷が近くで光ると青白く)へ。雲そのものの箱もシェーダーで同じ霧に
 *       沈める(内側の面は描かない。{@link VoxelClouds})。深く入ると空(太陽・月・星)も描かない。</li>
 * </ul>
 * 数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudInterior {
    /** 確かめる球の半径(ブロック)。 */
    static final double INNER = 7.0;
    static final double OUTER = 15.0;
    /** 中にいるとき、霧で見えなくなる距離(ブロック。下の層・雲の海 / 積乱雲)。 */
    /** 10-18 ユーザー「ほんのちょっとだけ視界をよく(雷が見えづらい・乱気流で揺れているのがわかりづらい)」で 22 → 32・15 → 25。 */
    static final float LOW_FOG = 32.0F;
    static final float STORM_FOG = 25.0F;
    /** 霧の向こうの積乱雲の影の濃さ(霧の色をどれだけ暗くするか。900 ブロックまで)。 */
    static final float SILHOUETTE = 0.38F;
    /** 空を描かなくなる深さ。 */
    static final float HIDE_SKY = 0.7F;
    /** 積乱雲の中の霧の色(雲の色に掛ける)。 */
    static final float[] STORM_TINT = {0.60F, 0.63F, 0.69F};
    static final float[] LOW_TINT = {0.90F, 0.92F, 0.96F};
    /** 荒天の雲の中の霧の色(天気を入れない雲の色に掛ける。濃い青灰)。 */
    static final float[] ROUGH_FOG = {0.40F, 0.48F, 0.58F};

    /** 正 20 面体の頂点の向き(球の上の 12 点)。 */
    private static final double[][] DIRS;

    static {
        double t = (1.0 + Math.sqrt(5.0)) / 2.0;
        double[][] v = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t}, {t, 0, -1}, {t, 0, 1},
                {-t, 0, -1}, {-t, 0, 1}};
        for (double[] d : v) {
            double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            d[0] /= l;
            d[1] /= l;
            d[2] /= l;
        }
        DIRS = v;
    }

    private static float amount;
    private static float lastAmount;
    private static float storm;
    private static float lastStorm;
    private static List<CloudField.Storm> storms = List.of();
    private static int ticks;
    private static boolean broken;
    private static ClientLevel lastLevel;
    private static boolean nearClouds;
    private static float lastCoverage;
    private static float lastThreshold;

    /** 中にいる度合い(0 = 雲の外 〜 1 = 雲の奥)。tick の間はなめらかにつなぐ。 */
    public static float amount(float partialTick) {
        return Mth.lerp(partialTick, lastAmount, amount);
    }

    /** そのうち積乱雲の中の度合い(0〜1、amount 以下)。 */
    public static float storm(float partialTick) {
        return Mth.lerp(partialTick, lastStorm, storm);
    }

    /** 雲の演出の強さ(設定)。 */
    public static float setting() {
        return OvergrownVegetationConfig.COMMON.cloudInterior.get().floatValue();
    }

    /** 深く入っているので空を描かないか({@code LevelRendererCaveSkyMixin})。 */
    public static boolean hideSky() {
        return amount * setting() > HIDE_SKY;
    }

    /** カメラが雲の高さの近くにいて、雲の箱を確かめているか(ちぎれ雲が置き場所を探すのもこの間だけ)。 */
    static boolean near() {
        return nearClouds;
    }

    /** 世界の座標の点が雲の箱の中か(0 外・1 下の層など・2 積乱雲。{@link #solidAt} を前の tick の雲の量で引く)。 */
    static int solidWorld(ClientLevel level, double x, double y, double z) {
        if (!nearClouds) {
            return 0;
        }
        long gameTime = level.getGameTime();
        return solidAt(storms, lastThreshold, lastCoverage, CloudField.fieldX(x, gameTime, 1.0F), y, CloudField.fieldZ(z, gameTime, 1.0F));
    }

    /** 近くの積乱雲(10 tick ごとに引き直す。雷・乱気流も使う)。 */
    static List<CloudField.Storm> storms() {
        return storms;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            amount = lastAmount = storm = lastStorm = 0.0F;
            storms = List.of();
        }
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        lastAmount = amount;
        lastStorm = storm;
        try {
            tick(mc, level);
        } catch (RuntimeException e) {
            broken = true;
            amount = lastAmount = storm = lastStorm = 0.0F;
            OvergrownVegetation.LOGGER.error("雲の中の演出で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(Minecraft mc, ClientLevel level) {
        ticks++;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        float targetAll = 0.0F;
        float targetStorm = 0.0F;
        boolean near = VoxelClouds.active() && setting() > 0.0F && cam.y > CloudField.LOW_BASE - OUTER - 4.0
                && cam.y < CloudField.GIANT_TOP + OUTER + 40.0 && level.dimensionType().hasSkyLight();
        nearClouds = near;
        if (near) {
            long gameTime = level.getGameTime();
            float coverage = VoxelClouds.coverage(level, 1.0F);
            float threshold = CloudField.threshold(coverage);
            lastCoverage = coverage;
            lastThreshold = threshold;
            double fx = CloudField.fieldX(cam.x, gameTime, 1.0F);
            double fz = CloudField.fieldZ(cam.z, gameTime, 1.0F);
            if (ticks % 10 == 0 || storms.isEmpty() && ticks % 10 == 5) {
                double r = 3200.0;
                storms = VoxelClouds.storms(level, 1.0F, coverage, fx - r, fz - r, fx + r, fz + r);
            }
            // 真ん中 2・内の球 1・外の球 0.5 の重み(面のちょうど上で 0.5 ほど)。
            float all = 0.0F;
            float st = 0.0F;
            float weight = 0.0F;
            for (int i = -1; i < DIRS.length * 2; i++) {
                double ox = 0.0;
                double oy = 0.0;
                double oz = 0.0;
                float w = 2.0F;
                if (i >= 0) {
                    double[] d = DIRS[i % DIRS.length];
                    double rad = i < DIRS.length ? INNER : OUTER;
                    ox = d[0] * rad;
                    oy = d[1] * rad;
                    oz = d[2] * rad;
                    w = i < DIRS.length ? 1.0F / 6.0F : 1.0F / 12.0F;
                }
                int hit = solidAt(storms, threshold, coverage, fx + ox, cam.y + oy, fz + oz);
                weight += w;
                if (hit > 0) {
                    all += w;
                    if (hit == 2) {
                        st += w;
                    }
                }
            }
            targetAll = all / weight;
            targetStorm = st / weight;
        }
        // 入るときはすぐ寄せ(0.3 秒ほど)、出るときはゆっくり(1.5 秒ほど。抜けた後もしばらくもやが残る)。
        amount += (targetAll - amount) * (targetAll > amount ? 0.35F : 0.08F);
        storm += (targetStorm - storm) * (targetStorm > storm ? 0.35F : 0.08F);
        storm = Math.min(storm, amount);
        if (amount < 0.001F) {
            amount = 0.0F;
            storm = 0.0F;
        }
    }

    /**
     * 場の座標 (fx, y, fz) が描いている雲の箱の中か(0 = 外、1 = 下の層・雲の海・雲の海の地形、2 = 積乱雲)。{@link VoxelClouds} の作り方と同じ升・同じ式:
     * 積乱雲は箱 {@link VoxelClouds#STORM_VOXEL}(巨大な雲は {@link VoxelClouds#GIANT_VOXEL})の真ん中で塊の中か、雲の海の地形と巨大な雲の雲海は 24 ブロックの柱、
     * ほかは下の層の 8 ブロックの柱(積乱雲の底が覆う所は空ける)。
     */
    static int solidAt(List<CloudField.Storm> storms, float threshold, float coverage, double fx, double y, double fz) {
        return solidAt(storms, threshold, coverage, fx, y, fz, false);
    }

    /** ignoreHoles = 穴を数えない(穴を開ける側が、雲そのものの中かを見るとき)。 */
    static int solidAt(List<CloudField.Storm> storms, float threshold, float coverage, double fx, double y, double fz, boolean ignoreHoles) {
        if (y < CloudField.LOW_BASE) {
            return 0;
        }
        // 砲弾・ビームが開けた穴の中は雲の外(10-18、CloudHoles)。
        if (!ignoreHoles && CloudHoles.inside(fx, y, fz)) {
            return 0;
        }
        // 吐いた雲(10-19、空の巨大生物が隠れる雲)の中は積乱雲と同じ霧。
        if (CloudPuffs.inside(fx, y, fz)) {
            return 2;
        }
        for (CloudField.Storm s : storms) {
            double r = s.reach();
            if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r || y > s.topY() + 40.0) {
                continue;
            }
            int v = s.giant() ? VoxelClouds.GIANT_VOXEL : VoxelClouds.STORM_VOXEL;
            double cx = (Math.floor(fx / v) + 0.5) * v;
            double cz = (Math.floor(fz / v) + 0.5) * v;
            double cy = CloudField.LOW_BASE + (Math.floor((y - CloudField.LOW_BASE) / v) + 0.5) * v;
            if (CloudField.inside(CloudField.puffs(s), cx, cy, cz)) {
                return 2;
            }
        }
        if (y >= CloudField.SEA_TOP + 300) {
            return 0;
        }
        int style = com.yayuy.overgrownvegetation.sky.CloudSky.style();
        boolean floor = style >= CloudField.STYLE_VALLEY;
        int cell = Mth.floor(fx / CloudField.CELL_XZ);
        int cellZ = Mth.floor(fz / CloudField.CELL_XZ);
        boolean coarse = floor;
        if (!coarse && !storms.isEmpty()) {
            int gx = Math.floorDiv(cell, VoxelClouds.SEA_CELLS);
            int gz = Math.floorDiv(cellZ, VoxelClouds.SEA_CELLS);
            double size = VoxelClouds.SEA_CELLS * (double) CloudField.CELL_XZ;
            coarse = CloudField.sea(storms, (gx + 0.5) * size, (gz + 0.5) * size) >= 0.02F;
        }
        CloudField.Column c;
        double ccx;
        double ccz;
        if (coarse) {
            int gx = Math.floorDiv(cell, VoxelClouds.SEA_CELLS);
            int gz = Math.floorDiv(cellZ, VoxelClouds.SEA_CELLS);
            c = CloudField.column(gx * VoxelClouds.SEA_CELLS + 1, gz * VoxelClouds.SEA_CELLS + 1, threshold, coverage, storms, style);
            double size = VoxelClouds.SEA_CELLS * (double) CloudField.CELL_XZ;
            ccx = (gx + 0.5) * size;
            ccz = (gz + 0.5) * size;
        } else {
            c = CloudField.column(cell, cellZ, threshold, coverage, storms, style);
            ccx = (cell + 0.5) * CloudField.CELL_XZ;
            ccz = (cellZ + 0.5) * CloudField.CELL_XZ;
        }
        if (!c.present() || y < c.bottom() || y >= c.top()) {
            return 0;
        }
        return !storms.isEmpty() && CloudField.baseCovers(storms, ccx, ccz) ? 0 : 1;
    }

    /** 霧の色(雲の色に、下の層は白・積乱雲は灰色を掛け、近くの雷の光を足す)。 */
    static float[] fogColor(ClientLevel level, float partialTick, Vec3 cam) {
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        float a = Math.max(amount(partialTick), 1.0E-4F);
        float s = Mth.clamp(storm(partialTick) / a, 0.0F, 1.0F);
        float glow = Thunderstorms.glowAt(cam.x, cam.y, cam.z, partialTick);
        // 荒天(10-18): 霧も青灰へ(雲の上では雨を見せないので、雲の色は本当の天気から)。
        float rough = VoxelClouds.roughness(level, partialTick);
        float[] clear = VoxelClouds.clearCloudColor(level, partialTick);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            double base = i == 0 ? cloud.x : i == 1 ? cloud.y : cloud.z;
            float tint = Mth.lerp(s, LOW_TINT[i], STORM_TINT[i]);
            // 雷の光は控えめに(最初は 0.55〜0.75 足して霧が真っ白に飛んだ。ユーザーは前に「雷の輝きがまぶしすぎる」と言っている)。
            float flash = (i == 0 ? 0.2F : i == 1 ? 0.22F : 0.3F) * glow;
            float fog = Mth.lerp(rough, (float) base * tint, clear[i] * ROUGH_FOG[i] * Mth.lerp(s, 1.0F, 0.8F));
            out[i] = Math.min(1.0F, fog + flash);
        }
        return out;
    }

    /** 霧で見えなくなる距離(中にいる度合いで、バニラの距離から雲の中の距離へ対数で寄せる)。 */
    static float fogFar(float vanillaFar, float partialTick) {
        float a = amount(partialTick) * setting();
        if (a <= 0.0F) {
            return vanillaFar;
        }
        float s = Mth.clamp(storm(partialTick) / Math.max(amount(partialTick), 1.0E-4F), 0.0F, 1.0F);
        float inside = Mth.lerp(s, LOW_FOG, STORM_FOG);
        // 平方根で早めに寄せる(面のすぐ外でもう霞む。最初はそのまま寄せ、壁の 3 ブロック手前でもくっきり見えていた)。
        // 抜けた後の薄い残り(0.04 未満)は霧にしない(最初は残りでも遠くの雲が灰色の影のまま 2 秒ほど残った)。
        float w = Mth.clamp((a - 0.04F) / 0.96F, 0.0F, 1.0F);
        float far = (float) Math.exp(Mth.lerp((float) Math.sqrt(w), Math.log(Math.max(vanillaFar, inside)), Math.log(inside)));
        return Math.min(vanillaFar, far);
    }

    /** 霧の色を雲の色へ(森の霧などの後に。どの地域でも雲の中は雲の霧)。 */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float pt = (float) event.getPartialTick();
        float a = amount(pt) * setting();
        if (level == null || a <= 0.001F) {
            return;
        }
        float[] c = fogColor(level, pt, event.getCamera().getPosition());
        // 色は距離より早く寄せる(面の近くのもやも雲の色)。
        float k = (float) Math.sqrt(a);
        event.setRed(Mth.lerp(k, event.getRed(), c[0]));
        event.setGreen(Mth.lerp(k, event.getGreen(), c[1]));
        event.setBlue(Mth.lerp(k, event.getBlue(), c[2]));
    }

    /** 霧の距離を雲の中の距離へ(地形の霧と空の霧の両方。森の霧が先に決めた距離より近くだけ)。 */
    @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float pt = (float) event.getPartialTick();
        if (amount(pt) * setting() <= 0.001F) {
            return;
        }
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN && event.getMode() != FogRenderer.FogMode.FOG_SKY) {
            return;
        }
        float vanillaFar = event.getFarPlaneDistance();
        float far = fogFar(vanillaFar, pt);
        if (far >= vanillaFar - 0.01F) {
            return;
        }
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.05F));
        event.setCanceled(true);
    }

    private CloudInterior() {
    }
}
