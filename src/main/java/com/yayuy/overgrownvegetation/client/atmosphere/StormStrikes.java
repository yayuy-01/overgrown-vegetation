package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.network.CloudStrikePayload;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * <b>積乱雲からの落雷の見た目</b>(雷雨の強化の 1 つ目。2026-11-16。ユーザー決定: 雷雨のたびに、積乱雲から汚染の森へも落雷を起こし、
 * 森が光って胞子を吹き上げるのを遠くから見られるようにする)。
 *
 * <p>場所と時刻はサーバーが決めて {@link CloudStrikePayload} で知らせる({@link WeatherClient#strikeListener})。ここは描くだけ:
 * <ul>
 *     <li><b>稲妻</b>: 雲の底({@code cloudBaseY})から当たった高さ({@code hitY})まで、折れ曲がった光の線(枝つき)。形は {@code seed} から。
 *     1 回光って、少し置いてもう 1 回光り、約 0.5 秒で消える。遠くの稲妻は描画距離の内側へ寄せて同じ見かけの大きさで描く。</li>
 *     <li><b>雲の中の光</b>: 落ちた所の雲を青白く光らせる({@code VoxelClouds} の {@code Flash0〜2}。前は口だけで、いつも 0 だった)。</li>
 *     <li><b>空の光</b>: 近い落雷({@link #SKY_FLASH_RANGE} 以内)は、バニラの雷と同じ空の閃き。</li>
 *     <li><b>森の光と吹き上がり</b>({@code forest} = 1): 林冠の所に黄緑(地脈の根の光と同じ色)の光が広がって約 2.5 秒で消える。
 *     近ければ、胞子の粒が林冠から吹き上がる。</li>
 *     <li><b>雷鳴</b>: 距離 ÷ 音の速さだけ遅れて鳴る(バニラの雷鳴)。遠いほど小さく低い。雲が多い日は 2 秒に 1 回届くので、
 *     鳴らすのは {@link #THUNDER_GAP} tick に 1 回まで(近い物が優先。鳴りっぱなしにしない)。{@link #THUNDER_RANGE} より遠い雷は音なし。</li>
 * </ul>
 * 数は仮決め。確かめは {@code /ov weather strike [forest|ground]}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class StormStrikes {

    private StormStrikes() {
    }

    private static final float LIFE = 0.55F;
    private static final float GLOW_LIFE = 2.5F;
    private static final double SKY_FLASH_RANGE = 700.0;
    private static final double THUNDER_RANGE = 1600.0;
    private static final int THUNDER_GAP = 50;
    private static final double PUFF_RANGE = 200.0;
    /** これより遠い稲妻は、この距離へ寄せて描く(描画距離の外でも見えるように)。 */
    private static final double DRAW_AT = 380.0;

    private static final class Strike {
        double x, z;
        float top, hit, ground;
        boolean forest;
        long seed;
        long startNanos;
        /** 稲妻の折れ点(世界の座標。x y z の並び)と、枝(同じ並び)。 */
        float[] main;
        final List<float[]> branches = new ArrayList<>();
    }

    private static final List<Strike> STRIKES = new ArrayList<>();
    private static final List<long[]> THUNDERS = new ArrayList<>(); // {鳴らす tick, 距離}
    private static long ticks;
    private static long lastThunder = -1000L;
    private static boolean hooked;
    private static int seen;

    private static void accept(CloudStrikePayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        seen++;
        Strike s = new Strike();
        s.x = p.x();
        s.z = p.z();
        s.top = p.cloudBaseY();
        float sea = level.getSeaLevel();
        s.hit = p.hitY() == Integer.MIN_VALUE ? sea : p.hitY();
        s.ground = p.groundY() == Integer.MIN_VALUE ? s.hit : p.groundY();
        s.forest = p.forest() > 0.5F;
        s.seed = p.seed();
        s.startNanos = System.nanoTime();
        build(s);
        STRIKES.add(s);
        if (STRIKES.size() > 24) {
            STRIKES.remove(0);
        }
        double dx = s.x - player.getX();
        double dz = s.z - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < SKY_FLASH_RANGE && !mc.options.hideLightningFlash().get()) {
            level.setSkyFlashTime(2);
        }
        if (dist < THUNDER_RANGE) {
            // 音の速さ 約 17 ブロック/tick。
            THUNDERS.add(new long[]{ticks + Math.round(dist / 17.0), Math.round(dist)});
        }
        if (s.forest && dist < PUFF_RANGE && VisualQuality.fog()) {
            Random r = new Random(s.seed);
            for (int i = 0; i < 36; i++) {
                double px = s.x + (r.nextDouble() - 0.5) * 9.0;
                double pz = s.z + (r.nextDouble() - 0.5) * 9.0;
                double py = s.hit - 2.0 + r.nextDouble() * 4.0;
                level.addParticle(ModParticles.SPORE_JET.get(), px, py, pz, (r.nextDouble() - 0.5) * 0.08, 0.25 + r.nextDouble() * 0.35, (r.nextDouble() - 0.5) * 0.08);
            }
        }
    }

    /** 稲妻の形: 上から下へ 12 節、横へのずれは高さの 7% まで。枝は 2〜3 本、途中から斜め下へ。 */
    private static void build(Strike s) {
        Random r = new Random(s.seed * 0x9E3779B97F4A7C15L + 7L);
        int n = 12;
        float height = Math.max(8.0F, s.top - s.hit);
        float amp = height * 0.07F;
        s.main = new float[(n + 1) * 3];
        // 雲の底では少し横にずれた所から出る。
        double ox = (r.nextDouble() - 0.5) * amp * 2.0;
        double oz = (r.nextDouble() - 0.5) * amp * 2.0;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float wob = i == n ? 0.0F : amp * (1.0F - 0.5F * t);
            s.main[i * 3] = (float) (s.x + ox * (1.0 - t) + (r.nextDouble() - 0.5) * wob);
            s.main[i * 3 + 1] = s.top - height * t;
            s.main[i * 3 + 2] = (float) (s.z + oz * (1.0 - t) + (r.nextDouble() - 0.5) * wob);
        }
        int branches = 2 + r.nextInt(2);
        for (int b = 0; b < branches; b++) {
            int from = 2 + r.nextInt(n - 5);
            int len = 3 + r.nextInt(3);
            float[] br = new float[(len + 1) * 3];
            double ang = r.nextDouble() * Math.PI * 2.0;
            double bx = s.main[from * 3];
            double by = s.main[from * 3 + 1];
            double bz = s.main[from * 3 + 2];
            for (int i = 0; i <= len; i++) {
                br[i * 3] = (float) bx;
                br[i * 3 + 1] = (float) by;
                br[i * 3 + 2] = (float) bz;
                double step = height / n * 0.8;
                bx += Math.cos(ang) * step * (0.5 + r.nextDouble() * 0.6);
                bz += Math.sin(ang) * step * (0.5 + r.nextDouble() * 0.6);
                by -= step * (0.5 + r.nextDouble() * 0.5);
                ang += (r.nextDouble() - 0.5) * 0.9;
            }
            s.branches.add(br);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!hooked) {
            WeatherClient.strikeListener = StormStrikes::accept;
            hooked = true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            STRIKES.clear();
            THUNDERS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        long now = System.nanoTime();
        STRIKES.removeIf(s -> (now - s.startNanos) / 1.0e9 > GLOW_LIFE);
        for (Iterator<long[]> it = THUNDERS.iterator(); it.hasNext(); ) {
            long[] t = it.next();
            if (t[0] > ticks) {
                continue;
            }
            it.remove();
            // 間引き: 前の雷鳴から THUNDER_GAP tick たっていなければ鳴らさない(近い雷(300 以内)は半分の間で鳴らす)。
            long gap = t[1] < 300 ? THUNDER_GAP / 2 : THUNDER_GAP;
            if (ticks - lastThunder < gap) {
                continue;
            }
            lastThunder = ticks;
            float near = 1.0F - Mth.clamp(t[1] / (float) THUNDER_RANGE, 0.0F, 1.0F);
            float volume = 0.25F + 2.75F * near * near;
            float pitch = 0.55F + 0.35F * near;
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, volume, pitch, false);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STRIKES.clear();
        THUNDERS.clear();
    }

    /** 嵐の巣の稲妻(2026-11-18。サーバーの知らせなし・見た目だけ)。{@link HorizonLightning} の巣の雷が足す。 */
    private static final class NestBolt {
        float[] main;
        final List<float[]> branches = new ArrayList<>();
        long startNanos;
        float mx, my, mz;
    }

    private static final List<NestBolt> NEST_BOLTS = new ArrayList<>();

    /**
     * 嵐の巣の稲妻を 1 本足す: (x0, y0, z0) から (x1, y1, z1) へ、折れ曲がりながら走る筋と枝 2〜3 本。光り方は落雷と同じ({@link #boltLevel})。
     * 同時に 6 本まで(古い物から消える)。
     */
    static void nestBolt(double x0, double y0, double z0, double x1, double y1, double z1, long seed) {
        Random r = new Random(seed);
        NestBolt b = new NestBolt();
        int n = 14;
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        double amp = len * 0.06;
        b.main = new float[(n + 1) * 3];
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double wob = i == 0 || i == n ? 0.0 : amp;
            b.main[i * 3] = (float) (x0 + (x1 - x0) * t + (r.nextDouble() - 0.5) * wob);
            b.main[i * 3 + 1] = (float) (y0 + (y1 - y0) * t + (r.nextDouble() - 0.5) * wob);
            b.main[i * 3 + 2] = (float) (z0 + (z1 - z0) * t + (r.nextDouble() - 0.5) * wob);
        }
        int branches = 2 + r.nextInt(2);
        for (int k = 0; k < branches; k++) {
            int from = 2 + r.nextInt(n - 5);
            int bl = 3 + r.nextInt(3);
            float[] br = new float[(bl + 1) * 3];
            double bx = b.main[from * 3];
            double by = b.main[from * 3 + 1];
            double bz = b.main[from * 3 + 2];
            double ang = r.nextDouble() * Math.PI * 2.0;
            double step = len / n * 0.8;
            for (int i = 0; i <= bl; i++) {
                br[i * 3] = (float) bx;
                br[i * 3 + 1] = (float) by;
                br[i * 3 + 2] = (float) bz;
                bx += Math.cos(ang) * step * (0.5 + r.nextDouble() * 0.6);
                bz += Math.sin(ang) * step * (0.5 + r.nextDouble() * 0.6);
                by -= step * (0.2 + r.nextDouble() * 0.7);
                ang += (r.nextDouble() - 0.5) * 0.9;
            }
            b.branches.add(br);
        }
        b.mx = (float) ((x0 + x1) * 0.5);
        b.my = (float) ((y0 + y1) * 0.5);
        b.mz = (float) ((z0 + z1) * 0.5);
        b.startNanos = System.nanoTime();
        while (NEST_BOLTS.size() >= 6) {
            NEST_BOLTS.remove(0);
        }
        NEST_BOLTS.add(b);
    }

    /** 稲妻の明るさ(0〜1): 最初の閃き → 弱まる → 2 回目の閃き → 消える。 */
    private static float boltLevel(float age) {
        if (age < 0.0F || age > LIFE) {
            return 0.0F;
        }
        float first = Math.max(0.0F, 1.0F - age / 0.10F);
        float second = age > 0.16F ? Math.max(0.0F, 1.0F - (age - 0.16F) / 0.14F) * 0.85F : 0.0F;
        float tail = Math.max(0.0F, 1.0F - age / LIFE) * 0.25F;
        return Math.max(Math.max(first, second), tail);
    }

    /**
     * 雲の中の光(雲のシェーダーの {@code Flash<i>}): いま光っている落雷のうち i 番目に新しい物の {カメラからの x, y, z, 強さ}。無ければ強さ 0。
     */
    public static float[] cloudFlash(int i, double camX, double camY, double camZ) {
        long now = System.nanoTime();
        int k = 0;
        for (int j = STRIKES.size() - 1; j >= 0; j--) {
            Strike s = STRIKES.get(j);
            float level = boltLevel((float) ((now - s.startNanos) / 1.0e9));
            if (level <= 0.0F) {
                continue;
            }
            if (k++ == i) {
                return new float[]{(float) (s.x - camX), (float) (s.top + 40.0 - camY), (float) (s.z - camZ), level * 1.6F};
            }
        }
        // 嵐の巣の稲妻(11-18): 新しい順に。強さは落雷より強く(暗い壁を一瞬明るく照らす)。
        for (int q = NEST_BOLTS.size() - 1; q >= 0; q--) {
            NestBolt nb = NEST_BOLTS.get(q);
            float level = boltLevel((float) ((now - nb.startNanos) / 1.0e9));
            if (level <= 0.0F) {
                continue;
            }
            if (k++ == i) {
                return new float[]{(float) (nb.mx - camX), (float) (nb.my - camY), (float) (nb.mz - camZ), level * 6.0F};
            }
        }
        // 空いた口は、地平線の遠い雷(サーバーの知らせなしの、遠い雲の中の光。11-16 HorizonLightning)が使う。
        return HorizonLightning.cloudFlash(i - k, camX, camY, camZ);
    }

    /** {@link Atmosphere} の加算の光の所から(光の筋と同じ描き先・同じ足し方)。 */
    static void render(RenderLevelStageEvent event) {
        long nowNest = System.nanoTime();
        NEST_BOLTS.removeIf(b -> (nowNest - b.startNanos) / 1.0e9 > LIFE);
        if (STRIKES.isEmpty() && NEST_BOLTS.isEmpty()) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        org.joml.Vector3f lookF = event.getCamera().getLookVector();
        long now = System.nanoTime();
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(AddLight.renderType());
        for (Strike s : STRIKES) {
            float age = (float) ((now - s.startNanos) / 1.0e9);
            float level = boltLevel(age);
            double dx = s.x - eye.x;
            double dz = s.z - eye.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            // 遠い物は寄せて描く(見かけの大きさは同じ)。
            float scale = (float) Math.min(1.0, DRAW_AT / Math.max(dist, 1.0));
            if (level > 0.003F) {
                float width = (float) Math.max(0.35, dist * 0.0035);
                line(c, s.main, eye, scale, width, level, 1.0F);
                line(c, s.main, eye, scale, width * 3.5F, level * 0.22F, 1.0F);
                for (float[] br : s.branches) {
                    line(c, br, eye, scale, width * 0.6F, level * 0.6F, 0.4F);
                }
            }
            if (s.forest && age < GLOW_LIFE) {
                // 森の光: 林冠の高さに、カメラを向く丸い光(黄緑)。最初は稲妻と一緒に強く、あとはゆっくり消える。
                float g = (float) Math.pow(1.0F - age / GLOW_LIFE, 1.6) * (0.55F + 0.45F * level);
                float radius = (float) Math.max(24.0, dist * 0.05) * (1.0F + 0.5F * age / GLOW_LIFE);
                disc(c, (float) (dx * scale), (float) ((s.hit - eye.y) * scale), (float) (dz * scale), radius * scale, lookF, 0.675F, 0.89F, 0.10F, g * 0.85F);
                disc(c, (float) (dx * scale), (float) ((s.hit - eye.y) * scale), (float) (dz * scale), radius * scale * 0.45F, lookF, 0.9F, 1.0F, 0.55F, g * 0.6F);
            }
        }
        for (NestBolt nb : NEST_BOLTS) {
            float level = boltLevel((float) ((now - nb.startNanos) / 1.0e9));
            if (level <= 0.003F) {
                continue;
            }
            double dx = nb.mx - eye.x;
            double dz = nb.mz - eye.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            float scale = (float) Math.min(1.0, DRAW_AT / Math.max(dist, 1.0));
            float width = (float) Math.max(5.0, dist * 0.02);
            line(c, nb.main, eye, scale, width, level, 0.6F);
            line(c, nb.main, eye, scale, width * 4.5F, level * 0.45F, 0.6F);
            line(c, nb.main, eye, scale, width * 12.0F, level * 0.12F, 0.6F);
            for (float[] br : nb.branches) {
                line(c, br, eye, scale, width * 0.6F, level * 0.7F, 0.3F);
                line(c, br, eye, scale, width * 2.6F, level * 0.25F, 0.3F);
            }
        }
        buffers.endBatch(AddLight.renderType());
    }

    /** 折れ線を、カメラを向く帯で描く。{@code taper} = 先端の太さの倍率。 */
    private static void line(VertexConsumer c, float[] pts, Vec3 eye, float scale, float width, float alpha, float taper) {
        int n = pts.length / 3 - 1;
        float r = 0.82F, g = 0.88F, b = 1.0F;
        for (int i = 0; i < n; i++) {
            float ax = (float) (pts[i * 3] - eye.x) * scale;
            float ay = (float) (pts[i * 3 + 1] - eye.y) * scale;
            float az = (float) (pts[i * 3 + 2] - eye.z) * scale;
            float bx = (float) (pts[i * 3 + 3] - eye.x) * scale;
            float by = (float) (pts[i * 3 + 4] - eye.y) * scale;
            float bz = (float) (pts[i * 3 + 5] - eye.z) * scale;
            // 帯の横の向き = 線の向き × 視線(カメラ → 線)。
            float dx = bx - ax, dy = by - ay, dz = bz - az;
            float mx = (ax + bx) * 0.5F, my = (ay + by) * 0.5F, mz = (az + bz) * 0.5F;
            float sx = dy * mz - dz * my;
            float sy = dz * mx - dx * mz;
            float sz = dx * my - dy * mx;
            float len = Mth.sqrt(sx * sx + sy * sy + sz * sz);
            if (len < 1.0e-5F) {
                continue;
            }
            float wa = width * scale * Mth.lerp(i / (float) n, 1.0F, taper) * 0.5F / len;
            float wb = width * scale * Mth.lerp((i + 1) / (float) n, 1.0F, taper) * 0.5F / len;
            c.addVertex(ax - sx * wa, ay - sy * wa, az - sz * wa).setColor(r, g, b, alpha);
            c.addVertex(ax + sx * wa, ay + sy * wa, az + sz * wa).setColor(r, g, b, alpha);
            c.addVertex(bx + sx * wb, by + sy * wb, bz + sz * wb).setColor(r, g, b, alpha);
            c.addVertex(bx - sx * wb, by - sy * wb, bz - sz * wb).setColor(r, g, b, alpha);
        }
    }

    /** カメラを向く丸い光(真ん中が明るく、縁で 0。8 角形の扇を四角 8 枚で)。 */
    private static void disc(VertexConsumer c, float x, float y, float z, float radius, org.joml.Vector3f look, float r, float g, float b, float alpha) {
        // 視線に直交する 2 つの向き。
        float ux = -look.z(), uz = look.x();
        float ul = Mth.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-4F) {
            ux = 1.0F;
            uz = 0.0F;
            ul = 1.0F;
        }
        ux /= ul;
        uz /= ul;
        float vx = look.y() * uz, vy = look.z() * ux - look.x() * uz, vz = -look.y() * ux;
        for (int i = 0; i < 8; i++) {
            double a0 = i * Math.PI / 4.0;
            double a1 = (i + 1) * Math.PI / 4.0;
            float c0 = (float) Math.cos(a0) * radius, s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius, s1 = (float) Math.sin(a1) * radius;
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex(x + ux * c0 + vx * s0, y + vy * s0, z + uz * c0 + vz * s0).setColor(r, g, b, 0.0F);
            c.addVertex(x + ux * c1 + vx * s1, y + vy * s1, z + uz * c1 + vz * s1).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return "雲の落雷 受け取った " + seen + " 回・いま " + STRIKES.size() + " 本・雷鳴の待ち " + THUNDERS.size();
    }
}
