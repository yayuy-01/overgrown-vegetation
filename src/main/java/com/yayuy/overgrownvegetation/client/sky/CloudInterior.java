package com.yayuy.overgrownvegetation.client.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.List;

/**
 * 雲の中の白い霧(2026-11-08 雲の段階 1 の改善 ②、ユーザー決定 Q4「雲の中の白い霧だけ段階 1 に。もや・画面の水滴は段階 2 のまま」)。
 * 前の版の {@code client/CloudInterior}(parked)の霧の部分だけを作り直したもの。
 *
 * <p>y 300〜350 は空を移動するときの通り道で、雲の中に入ると、雲の箱が半透明に重なって破綻して見えた。カメラが雲の柱の中にいる度合い
 * {@link #amount}(まわり 5 点の柱で、雲の面から {@link #DEPTH} ブロック入ると 1)だけ、霧を {@link #FOG} ブロックまで縮め、霧と空の色を
 * 雲の色(日の当たる白・夜は暗い青・雨は青灰)へ寄せる。雲の箱もシェーダーで同じ霧に沈め、内側の面は描かない({@code voxel_clouds.fsh} の InsideFog)。
 * 判定は描いている形と同じ柱(谷のある雲の海は 24 ブロックの柱)で引く。出入りは {@link #SECONDS} 秒でならす。数値は前の版の値(32 ブロックは
 * 10-18 ユーザー調整)か仮決め。雲を描いていないときは何もしない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudInterior {
    /** 中にいるとき、霧で見えなくなる距離(ブロック)。前の版 LOW_FOG(10-18 ユーザー「ほんのちょっとだけ視界をよく」で 32)。 */
    public static final float FOG = 32.0F;
    /** 雲の面からこれだけ入ると濃さ 1(ブロック。外側 2 ブロックから薄く始まる)。仮決め。 */
    static final float DEPTH = 6.0F;
    static final float OUTSIDE = 2.0F;
    /** 積乱雲・尾根・浮いた雲で濃さ 1 になる深さ(11-09 段階 2a-3。雲の床の 6 より長く、小さな浮いた雲で霧が急に変わらないように。仮決め)。 */
    static final float STORM_DEPTH = 14.0F;
    /** 出入りをならす秒数。仮決め。 */
    static final float SECONDS = 0.35F;
    /** カメラが雲の柱の中にいるときの濃さの下限(11-08。仮決め)。 */
    static final float CENTER_MIN = 0.6F;
    /** 霧の色: 雲の色に掛ける(前の版 LOW_TINT)・荒天の色(ROUGH_FOG)。 */
    static final float[] TINT = {0.90F, 0.92F, 0.96F};
    static final float[] ROUGH = {0.40F, 0.48F, 0.58F};

    private static float amount;
    private static float prev;
    private static ClientLevel lastLevel;
    /** 霧の色(0xRRGGBB、tick ごとに求める)。空の丸天井も中ではこの色へ寄せる({@code mixin/client/BiomeClearSkyMixin})。 */
    private static volatile int fogPacked = 0xFFFFFF;

    public static int fogPacked() {
        return fogPacked;
    }

    /** 雲の中にいる度合い(0〜1、部分 tick で補間)。 */
    public static float amount(float partialTick) {
        return Mth.lerp(partialTick, prev, amount);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        prev = amount;
        if (level != lastLevel) {
            lastLevel = level;
            amount = 0.0F;
            prev = 0.0F;
        }
        if (level == null || mc.isPaused()) {
            return;
        }
        float target = 0.0F;
        if (VoxelClouds.isActive() && level.dimensionType().hasSkyLight()) {
            Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
            if (eye.y > CloudField.LOW_BASE - OUTSIDE && eye.y < CloudField.LOW_BASE + 700.0) {
                target = depth(level, eye);
            }
        }
        float k = 1.0F - (float) Math.exp(-0.05F / SECONDS);
        amount += (target - amount) * k;
        if (amount < 0.002F && target == 0.0F) {
            amount = 0.0F;
        }
        if (amount > 0.0F) {
            float[] c = fogColor(level, 1.0F);
            fogPacked = (Math.round(Mth.clamp(c[0], 0.0F, 1.0F) * 255.0F) << 16) | (Math.round(Mth.clamp(c[1], 0.0F, 1.0F) * 255.0F) << 8)
                    | Math.round(Mth.clamp(c[2], 0.0F, 1.0F) * 255.0F);
        }
    }

    /** まわり 5 点(真ん中と ±{@link #DEPTH})の柱で、雲の面からの深さを平均する(0〜1)。 */
    static float depth(ClientLevel level, Vec3 eye) {
        long gt = level.getGameTime();
        float coverage = CloudSky.coverage(level, 1.0F);
        if (coverage <= 0.0F) {
            return 0.0F;
        }
        int style = CloudSky.style();
        float threshold = CloudField.threshold(coverage);
        // 積乱雲・入道雲(11-09 段階 2a): 足元の裾野は雲の床の柱に入り、塔の中は塊で見る(塔の中でも内側の面を描かず霧に沈める)。
        java.util.List<CloudField.Storm> storms = CloudSky.stormsNear(level, eye.x, eye.z, 1.0F, 64.0);
        double[][] offsets = {{0, 0}, {DEPTH, 0}, {-DEPTH, 0}, {0, DEPTH}, {0, -DEPTH}};
        float sum = 0.0F;
        boolean centerInside = false;
        for (double[] o : offsets) {
            double fx = CloudField.fieldX(eye.x + o[0], gt, 1.0F);
            double fz = CloudField.fieldZ(eye.z + o[1], gt, 1.0F);
            CloudField.Column c = CloudSky.columnRendered(fx, fz, threshold, coverage, style, storms);
            if (!c.present()) {
                continue;
            }
            float d = (float) Math.min(eye.y - c.bottom(), c.top() - eye.y);
            if (o[0] == 0.0 && o[1] == 0.0 && d >= 0.0F) {
                centerInside = true;
            }
            sum += Mth.clamp((d + OUTSIDE) / (DEPTH + OUTSIDE), 0.0F, 1.0F);
        }
        // 11-08(ユーザー報告「雲の角に立つと透けて下が見える」): カメラが柱の中にいる間は、内側の面を描かない(シェーダー)ので、
        // まわりの平均が浅くても霧を {@link #CENTER_MIN} まで濃くし、下の地面を霧に沈める。
        float avg = sum / offsets.length;
        float floor = centerInside ? Math.max(avg, CENTER_MIN) : avg;
        return storms.isEmpty() ? floor : Math.max(floor, stormDepth(storms, eye, gt));
    }

    /**
     * 積乱雲・尾根・浮いた雲の塊の中の深さ(0〜1)。11-09 段階 2a-3: いちばん深く入っている塊の面からの距離(ブロック、楕円体の近い方の半径で見積もる。
     * 下を切った塊は切り口からの距離も)で、雲の床と同じく面の外 {@link #OUTSIDE} から {@link #DEPTH} 入ると 1。
     * 前は 7 点のうち塊の中の割合だったので、小さい浮いた雲では濃さが段で急に変わり、トンネルの壁のそばでは 1 点だけ中に入って白い霧がかかった。
     */
    static float stormDepth(java.util.List<CloudField.Storm> storms, Vec3 eye, long gt) {
        double fx = CloudField.fieldX(eye.x, gt, 1.0F);
        double fz = CloudField.fieldZ(eye.z, gt, 1.0F);
        double y = eye.y;
        double best = -OUTSIDE;
        for (CloudField.Storm s : storms) {
            double reach = s.reach();
            if (Math.abs(fx - s.x()) > reach || Math.abs(fz - s.z()) > reach || y > s.topY() + 40.0) {
                continue;
            }
            for (CloudField.Puff p : CloudField.puffs(s, VoxelClouds.peakLevel())) {
                double r = p.r();
                if (Math.abs(fx - p.x()) > r * 1.1 || Math.abs(fz - p.z()) > r * 1.1) {
                    continue;
                }
                double ry = r * p.squash();
                double dx = (fx - p.x()) / r;
                double dy = (y - p.y()) / ry;
                double dz = (fz - p.z()) / r;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double depth = (1.0 - d) * Math.min(r, ry);
                if (p.floor() > Double.NEGATIVE_INFINITY) {
                    depth = Math.min(depth, y - p.floor());
                }
                if (depth > best) {
                    best = depth;
                    if (best >= STORM_DEPTH) {
                        return 1.0F;
                    }
                }
            }
        }
        return Mth.clamp((float) ((best + OUTSIDE) / (STORM_DEPTH + OUTSIDE)), 0.0F, 1.0F);
    }

    /** 描いている形と同じ柱(11-08 仕上げ 7 で共通の {@link CloudSky#columnRendered} へ移した)。 */
    static CloudField.Column column(double fx, double fz, float threshold, float coverage, int style) {
        return CloudSky.columnRendered(fx, fz, threshold, coverage, style);
    }

    /** 霧の色(雲の色 × 白っぽい色。雨・雷雨は青灰)。 */
    public static float[] fogColor(ClientLevel level, float partialTick) {
        Vec3 cloud = VoxelClouds.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        float[] clear = VoxelClouds.clearCloudColor(level, partialTick);
        float rough = VoxelClouds.roughness(level, partialTick);
        float[] out = new float[3];
        double[] c = {cloud.x, cloud.y, cloud.z};
        for (int i = 0; i < 3; i++) {
            out[i] = Mth.lerp(rough, (float) c[i] * TINT[i], clear[i] * ROUGH[i]);
        }
        return out;
    }

    /** 霧を縮める(雰囲気の霧 {@code MistView} の後に)。水の中・溶岩の中・盲目のときは触らない。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float partialTick = (float) event.getPartialTick();
        float a = amount(partialTick);
        if (a <= 0.002F || event.getType() != FogType.NONE) {
            return;
        }
        float far = Mth.lerp(a, event.getFarPlaneDistance(), Math.min(FOG, event.getFarPlaneDistance()));
        float near = Mth.lerp(a, event.getNearPlaneDistance(), Math.min(0.0F, event.getNearPlaneDistance()));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(near, far * 0.5F));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        ClientLevel level = Minecraft.getInstance().level;
        float partialTick = (float) event.getPartialTick();
        float a = amount(partialTick);
        if (level == null || a <= 0.002F || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float[] c = fogColor(level, partialTick);
        event.setRed(Mth.lerp(a, event.getRed(), c[0]));
        event.setGreen(Mth.lerp(a, event.getGreen(), c[1]));
        event.setBlue(Mth.lerp(a, event.getBlue(), c[2]));
    }

    private CloudInterior() {
    }
}
