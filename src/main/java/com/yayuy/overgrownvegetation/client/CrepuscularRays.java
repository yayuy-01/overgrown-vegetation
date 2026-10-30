package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 薄明光線(天使の梯子)(2026-10-17。ideas.voxel_clouds の「空の光の演出」。ユーザー 10-16「雨や曇りの後に薄明光線(天使の梯子)が出るような」、
 * 10-17「次は薄明光線(天使の梯子)を進めてください」)。
 *
 * <ul>
 *   <li>雲の切れ目: 雲の影の地図({@link CloudShadows.Map}、雲の柱 1 本 = 1 画素)で、雲の無い升のうち周り 7 × 7 升の半分以上が雲の所をつなげて 1 つの切れ目にする
 *       (開けた青空は切れ目ではない)。1 秒ごとに探し直し、地図の真ん中(カメラから太陽の向きにたどった雲の点)に近い順に {@link #MAX_BEAMS} 本まで。</li>
 *   <li>筋: 切れ目の真ん中の雲の底(y 300)から、日の光の向き(太陽の反対)へ地面の高さまで伸ばす、カメラを向く帯(林冠の光の筋 {@code CanopyLight#ribbon} と同じ作り。
 *       横は芯が明るく縁が 0)。太さは切れ目の広さ(8〜40 ブロック)。上の雲の底で湧き、下の 4 割で薄れて消える。場は風で流れるので筋も流れる。</li>
 *   <li>明るさ: 加算の光。太陽の方を向くほど明るい(光の筋と同じ向きの差)。カメラの近くの部分は薄く(近くの筋が空いっぱいの放射に見えないように)、
 *       太陽が雲に隠れている間(カメラが雲の影の中)ほど濃い。空気が湿っているほど濃い: ふだん 0.3、雨が止んだ後の 5 分(だんだん薄れる)・
 *       朝・雲の多い日は濃く(ユーザーの「雨や曇りの後」)。雲がほとんど無い日・一面の曇りの日は出ない。</li>
 *   <li>森の中(林冠の下)は自前の光の筋があり、葉の向こうの空の筋は見えないので弱める(森の上から雲海を見下ろしているときは弱めない)。
 *       カメラの空の明るさが低い所(屋根の下・地下)でも弱める。</li>
 * </ul>
 * 雲の影の地図があるときだけ(昼・太陽の高さ 7° 以上・ボクセルの雲を描いている)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CrepuscularRays {
    static final int MAX_BEAMS = 48;
    static final int RESCAN_TICKS = 20;
    /** 地図の真ん中からこの升数の中で切れ目を探す(8 × 44 = 約 350 ブロック)。 */
    static final int SEARCH_RADIUS = 44;
    /** 切れ目とみなす: 周り(半径 3 升)のうち雲の割合。 */
    static final float ENCLOSED = 0.5F;
    /** これより広い空き(升の数)は切れ目でなく青空。 */
    static final int MAX_GAP_CELLS = 36;
    /** 筋 1 本の芯の濃さ(加算)。最初の 0.085 は雨上がりの地上から見て、空いっぱいに放射状の筋が広がりすぎた。 */
    static final float ALPHA = 0.04F;
    /** カメラの近くの筋は薄く(この距離まで 0.2 倍、NEAR_TO で元の濃さ)。近くの筋が画面いっぱいの放射に見えるのを抑える。 */
    static final float NEAR_FROM = 50.0F;
    static final float NEAR_TO = 220.0F;
    /** 雨が止んでから空気が乾くまで(tick)。 */
    static final int AFTERGLOW_TICKS = 6000;
    /** 筋が見えなくなっていく水平距離(ブロック)。 */
    static final float FADE_FROM = 260.0F;
    static final float FADE_TO = 420.0F;

    private record Beam(double fx, double fz, float width, float weight) {
    }

    private static List<Beam> beams = List.of();
    private static CloudShadows.Map scanned;
    private static int ticks;
    private static float afterglow;
    private static boolean broken;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        float rain = com.yayuy.overgrownvegetation.sky.CloudSky.rawRain(level, 1.0F);
        afterglow = rain > 0.2F ? 1.0F : Math.max(0.0F, afterglow - 1.0F / AFTERGLOW_TICKS);
        if (++ticks % RESCAN_TICKS != 0) {
            return;
        }
        CloudShadows.Map m = CloudShadows.current();
        if (m == null || CloudShadows.base() <= 0.0F) {
            beams = List.of();
            scanned = null;
            return;
        }
        if (m != scanned) {
            scanned = m;
            beams = scan(m);
        }
    }

    /** 地図から雲の切れ目を探す。 */
    private static List<Beam> scan(CloudShadows.Map m) {
        int n = CloudShadows.SIZE;
        float[] v = m.values;
        // 雲の升の数の累積(周りの雲の割合を一度で引くため)。
        int[] sum = new int[(n + 1) * (n + 1)];
        for (int j = 0; j < n; j++) {
            int row = 0;
            for (int i = 0; i < n; i++) {
                row += v[j * n + i] >= 0.05F ? 1 : 0;
                sum[(j + 1) * (n + 1) + i + 1] = sum[j * (n + 1) + i + 1] + row;
            }
        }
        int c = n / 2;
        boolean[] candidate = new boolean[n * n];
        for (int j = Math.max(3, c - SEARCH_RADIUS); j < Math.min(n - 3, c + SEARCH_RADIUS); j++) {
            for (int i = Math.max(3, c - SEARCH_RADIUS); i < Math.min(n - 3, c + SEARCH_RADIUS); i++) {
                if (v[j * n + i] >= 0.05F) {
                    continue;
                }
                int x0 = i - 3;
                int z0 = j - 3;
                int x1 = i + 4;
                int z1 = j + 4;
                int clouds = sum[z1 * (n + 1) + x1] - sum[z0 * (n + 1) + x1] - sum[z1 * (n + 1) + x0] + sum[z0 * (n + 1) + x0];
                candidate[j * n + i] = clouds >= ENCLOSED * 49.0F;
            }
        }
        List<Beam> found = new ArrayList<>();
        boolean[] seen = new boolean[n * n];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int k = 0; k < n * n; k++) {
            if (!candidate[k] || seen[k]) {
                continue;
            }
            int cells = 0;
            double si = 0.0;
            double sj = 0.0;
            seen[k] = true;
            queue.add(k);
            while (!queue.isEmpty()) {
                int q = queue.poll();
                int qi = q % n;
                int qj = q / n;
                cells++;
                si += qi;
                sj += qj;
                int[] next = {q - 1, q + 1, q - n, q + n};
                for (int d = 0; d < 4; d++) {
                    int r = next[d];
                    if (d < 2 && r / n != qj || r < 0 || r >= n * n || seen[r] || !candidate[r]) {
                        continue;
                    }
                    seen[r] = true;
                    queue.add(r);
                }
            }
            if (cells > MAX_GAP_CELLS) {
                continue;
            }
            double ci = si / cells;
            double cj = sj / cells;
            double fx = (m.originX + ci + 0.5) * CloudField.CELL_XZ;
            double fz = (m.originZ + cj + 0.5) * CloudField.CELL_XZ;
            float width = Mth.clamp(CloudField.CELL_XZ * (float) Math.sqrt(cells) * 1.1F, CloudField.CELL_XZ, 40.0F);
            double dist = Math.hypot(ci - c, cj - c);
            found.add(new Beam(fx, fz, width, (float) dist));
        }
        found.sort((a, b) -> Float.compare(a.weight(), b.weight()));
        int max = VisualQuality.shaftCap(MAX_BEAMS); // 品質の段階の「光の筋」(2026-10-23)
        return found.size() > max ? List.copyOf(found.subList(0, max)) : List.copyOf(found);
    }

    /** 空気の湿り(0.3〜1): 雨の後・朝・雲の多い日ほど筋が濃い。 */
    private static float humidity(ClientLevel level, float coverage) {
        long day = Math.floorMod(level.getDayTime(), 24000L);
        float morning = day < 4000L ? 0.7F * (1.0F - day / 4000.0F) : 0.0F;
        float cloudy = 0.7F * CanopyLight.smooth(Mth.clamp((coverage - 0.5F) / 0.3F, 0.0F, 1.0F));
        return 0.3F + 0.7F * Math.max(afterglow, Math.max(morning, cloudy));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || beams.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("薄明光線で例外が出たので、このセッションでは使わない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float coverage = VoxelClouds.coverage(level, partialTick);
        float covered = CanopyLight.smooth(Mth.clamp((coverage - 0.15F) / 0.15F, 0.0F, 1.0F))
                * (1.0F - CanopyLight.smooth(Mth.clamp((coverage - 0.9F) / 0.07F, 0.0F, 1.0F)));
        Vec3 eye = event.getCamera().getPosition();
        int skyLight = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye));
        float exposure = CanopyLight.smooth(Mth.clamp((skyLight - 9.0F) / 5.0F, 0.0F, 1.0F));
        float forest = 1.0F - 0.9F * CorruptionAtmosphere.forestFactor() * (1.0F - MorningMistView.cloudSeaView());
        float strength = OvergrownVegetationConfig.COMMON.crepuscularRays.get().floatValue() * CloudShadows.base() * covered
                * humidity(level, coverage) * exposure * forest * (1.0F - CaveLight.underground())
                // 太陽が雲に隠れているほど(カメラが雲の影の中)筋が目立つ。日が出ている間は半分。
                * (0.5F + 0.5F * Mth.clamp((1.0F - CloudShadows.sunVisibility()) / CloudShadows.SUN_BLOCK, 0.0F, 1.0F))
                // 雲の中では見えない(霧に沈む。CloudInterior)。
                * (1.0F - CloudInterior.amount(partialTick) * CloudInterior.setting());
        if (strength <= 0.003F) {
            return;
        }
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        if (sun.y < 0.05) {
            return;
        }
        // 日の光の色: 高い日は白っぽく、低い日は金色。
        float warm = 1.0F - CanopyLight.smooth(Mth.clamp(((float) sun.y - 0.15F) / 0.5F, 0.0F, 1.0F));
        float r = 1.0F;
        float g = Mth.lerp(warm, 0.95F, 0.78F);
        float b = Mth.lerp(warm, 0.84F, 0.5F);
        long gameTime = level.getGameTime();
        double windX = CloudField.offsetX((double) gameTime + partialTick);
        double windZ = CloudField.offsetZ((double) gameTime + partialTick);
        double bottomY = Math.min(eye.y - 24.0, 62.0);
        double length = (CloudField.LOW_BASE - bottomY) / sun.y;
        Vec3 down = sun.scale(-1.0);

        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        for (Beam beam : beams) {
            Vec3 top = new Vec3(beam.fx() + windX, CloudField.LOW_BASE, beam.fz() + windZ);
            ribbon(c, top, down, length, beam.width(), eye, sun, ALPHA * strength, r, g, b);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /**
     * 1 本の筋(カメラを向く帯)。横は 縁 0 → 中ほど → 芯 の山なり、縦は雲の底で湧き、下の 4 割で薄れる。
     * 軸に沿って見ている(真下から見上げる)ときは帯の向きが定まらないので薄くする。
     */
    private static void ribbon(VertexConsumer c, Vec3 top, Vec3 dir, double length, float width, Vec3 eye, Vec3 sun, float alpha,
                               float r, float g, float b) {
        final int rows = 10;
        float[] prev = null;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = top.add(dir.scale(length * t));
            Vec3 toEye = eye.subtract(p);
            Vec3 side = dir.cross(toEye);
            double len = side.length();
            if (len < 1.0E-5) {
                prev = null;
                continue;
            }
            double sinAngle = len / toEye.length();
            float facing = CanopyLight.smooth(Mth.clamp((float) ((sinAngle - 0.12) / 0.33), 0.0F, 1.0F));
            float half = width * 0.5F * (1.0F + 0.25F * t);
            side = side.scale(half / len);
            double horizontal = Math.hypot(p.x - eye.x, p.z - eye.z);
            float near = 0.2F + 0.8F * CanopyLight.smooth(Mth.clamp((float) ((toEye.length() - NEAR_FROM) / (NEAR_TO - NEAR_FROM)), 0.0F, 1.0F));
            float far = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((horizontal - FADE_FROM) / (FADE_TO - FADE_FROM)), 0.0F, 1.0F));
            float a = alpha * facing * far * near
                    * CanopyLight.smooth(Mth.clamp(t / 0.06F, 0.0F, 1.0F))
                    * (1.0F - CanopyLight.smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F)))
                    * CanopyLight.phase(-toEye.x, -toEye.y, -toEye.z, sun);
            a = Math.min(a, 1.0F);
            float cx = (float) (p.x - eye.x);
            float cy = (float) (p.y - eye.y);
            float cz = (float) (p.z - eye.z);
            float sx = (float) side.x;
            float sy = (float) side.y;
            float sz = (float) side.z;
            float[] row = {
                    cx - sx, cy - sy, cz - sz,
                    cx - sx * 0.5F, cy - sy * 0.5F, cz - sz * 0.5F,
                    cx, cy, cz,
                    cx + sx * 0.5F, cy + sy * 0.5F, cz + sz * 0.5F,
                    cx + sx, cy + sy, cz + sz,
                    a};
            if (prev != null) {
                float pa = prev[15];
                quad(c, prev, 0, 0.0F, row, 0, 0.0F, prev, 3, pa * 0.6F, row, 3, a * 0.6F, r, g, b);
                quad(c, prev, 3, pa * 0.6F, row, 3, a * 0.6F, prev, 6, pa, row, 6, a, r, g, b);
                quad(c, prev, 6, pa, row, 6, a, prev, 9, pa * 0.6F, row, 9, a * 0.6F, r, g, b);
                quad(c, prev, 9, pa * 0.6F, row, 9, a * 0.6F, prev, 12, 0.0F, row, 12, 0.0F, r, g, b);
            }
            prev = row;
        }
    }

    private static void quad(VertexConsumer c, float[] p0, int i0, float a0, float[] p1, int i1, float a1,
                             float[] p2, int i2, float a2, float[] p3, int i3, float a3, float r, float g, float b) {
        c.addVertex(p0[i0], p0[i0 + 1], p0[i0 + 2]).setColor(r, g, b, a0);
        c.addVertex(p1[i1], p1[i1 + 1], p1[i1 + 2]).setColor(r, g, b, a1);
        c.addVertex(p3[i3], p3[i3 + 1], p3[i3 + 2]).setColor(r, g, b, a3);
        c.addVertex(p2[i2], p2[i2 + 1], p2[i2 + 2]).setColor(r, g, b, a2);
    }

    /** 今の筋の数(開発用のログ)。 */
    static int count() {
        return beams.size();
    }

    private CrepuscularRays() {
    }
}
