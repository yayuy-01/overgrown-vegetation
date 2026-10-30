package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 積乱雲の雷(2026-10-17。ideas.voxel_clouds の段取り (2)。ユーザー「次は積乱雲と雷を進めてください」)。見た目と音だけ(火も傷も無い。
 * 雷雨のときにバニラが落とす本物の雷は今まで通り)。
 *
 * <ul>
 *   <li>雲の中の光: 育ちきった積乱雲({@link CloudField.Storm}、育ち 0.75 以上)の胴の塊の中で、ときどき光が瞬く(ふだんは 1 つの雲で 20 秒に 1 回ほど、
 *       雷雨の間は 4 秒に 1 回ほど。設定 {@code lightningRate} で増減)。0.3〜0.6 秒の間に 2〜3 回またたき、一番明るい 3 つを雲のシェーダーへ渡して
 *       まわり 300 ブロックほどの雲を内側から青白く照らす({@link VoxelClouds})。</li>
 *   <li>稲妻: 光のうち 35%(雷雨の間 60%)は、雲の底(y 300)から地面までのぎざぎざの線(枝 1 本)を加算の光で描く。近い(300 ブロック以内)と空も光る。</li>
 *   <li>雷鳴: バニラの雷の音を、光った所から音の速さ(1 秒 343 ブロック)の遅れで、遠いほど小さく低く鳴らす(1500 ブロックより遠いと鳴らさない)。</li>
 *   <li>雨の柱: 育ちきった積乱雲の塔の下に、灰色の半透明の帯を数本、雲の底から地面へ(下の方で薄れる)。近づくとバニラの雨と重ならないよう薄くなる。</li>
 * </ul>
 * 数・明るさ・時間はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class Thunderstorms {
    /** 1 つの雲で 1 tick に光る確率(ふだん・雷雨)。 */
    static final float FLASH_CHANCE = 0.0025F;
    static final float FLASH_CHANCE_THUNDER = 0.012F;
    static final int MAX_FLASHES = 6;
    /** この距離より遠い雲は光らせない(ブロック。巨大な雲は GIANT_RANGE まで)。 */
    static final double FLASH_RANGE = 1800.0;
    static final double GIANT_RANGE = 2600.0;
    /** 雷鳴を鳴らす距離の上限。 */
    static final double SOUND_RANGE = 1500.0;
    /** 稲妻の帯の太さ(近く)と、遠くで太らせる割合。 */
    static final float BOLT_WIDTH = 0.7F;
    static final float BOLT_WIDTH_PER_BLOCK = 0.006F;
    /** 雨の柱の濃さ。 */
    static final float RAIN_ALPHA = 0.22F;

    private static final RandomSource RANDOM = RandomSource.create();

    private static final class Flash {
        double x;
        double y;
        double z;
        int age;
        int life;
        float[] pulses;
        List<Vec3> bolt;
        List<Vec3> branch;
        /** 光の強さの倍率(雷の前兆のちらつきは弱い)。 */
        float gain = 1.0F;

        float intensity(float partialTick) {
            float t = age + partialTick;
            float sum = 0.0F;
            for (float p : pulses) {
                float d = (t - p) / 1.3F;
                sum += (float) Math.exp(-d * d);
            }
            float tail = 1.0F - CanopyLight.smooth(Mth.clamp((t - life + 3.0F) / 3.0F, 0.0F, 1.0F));
            return Math.min(1.0F, sum) * tail * gain;
        }
    }

    private record Thunder(double x, double y, double z, int at, float volume, float pitch, boolean crack) {
        Thunder(double x, double y, double z, int at, float volume, float pitch) {
            this(x, y, z, at, volume, pitch, false);
        }
    }

    private static final List<Flash> FLASHES = new ArrayList<>();
    /** 積乱雲の中を走る雷(サーバーから。触れたら即死。{@link com.yayuy.overgrownvegetation.sky.CloudLightning})。 */
    private static final List<CloudBolt> BOLTS = new ArrayList<>();

    private static final class CloudBolt {
        List<Vec3> points;
        List<Vec3> branch;
        int age;
        int charge;
        int strike;
        long seed;
    }

    /** 雲の中で光る(光だけ、線は描かない)確率(1 tick、積乱雲の中にいるとき)。雷雨の間は倍。 */
    static final float INSIDE_FLASH = 0.02F;
    /** 雷の前兆のバチバチを鳴らす距離(線のいちばん近い点まで)。 */
    static final double CRACKLE_RANGE = 96.0;
    private static final List<Thunder> THUNDER = new ArrayList<>();
    private static List<CloudField.Storm> storms = List.of();
    private static int ticks;
    private static boolean broken;
    private static RenderType rainType;

    /** 雲のシェーダーへ渡す光(カメラからの位置と明るさ)3 つ。無い所は明るさ 0。 */
    static float[][] flashUniforms(double camX, double camY, double camZ) {
        float[][] out = new float[3][4];
        List<Flash> sorted = new ArrayList<>(FLASHES);
        sorted.sort((a, b) -> Float.compare(b.intensity(0.0F), a.intensity(0.0F)));
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            Flash f = sorted.get(i);
            out[i][0] = (float) (f.x - camX);
            out[i][1] = (float) (f.y - camY);
            out[i][2] = (float) (f.z - camZ);
            out[i][3] = f.intensity(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        }
        return out;
    }

    /** その点での雷の光の明るさ(0〜1。光った所からの距離で弱まる)。雲の中の霧の色が読む({@link CloudInterior})。 */
    static float glowAt(double x, double y, double z, float partialTick) {
        float sum = 0.0F;
        for (Flash f : FLASHES) {
            double dx = f.x - x;
            double dy = f.y - y;
            double dz = f.z - z;
            double d2 = dx * dx + dy * dy + dz * dz;
            sum += f.intensity(partialTick) * (float) Math.exp(-d2 / (2.0 * GLOW_SIGMA * GLOW_SIGMA));
        }
        return Math.min(1.0F, sum);
    }

    /** 雲の中で雷の光が届く広がり(ブロック、正規分布の幅)。 */
    static final double GLOW_SIGMA = 140.0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || broken) {
            return;
        }
        try {
            tick(mc, level);
        } catch (RuntimeException e) {
            broken = true;
            FLASHES.clear();
            OvergrownVegetation.LOGGER.error("積乱雲の雷で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(Minecraft mc, ClientLevel level) {
        ticks++;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (Iterator<Flash> it = FLASHES.iterator(); it.hasNext(); ) {
            Flash f = it.next();
            if (++f.age >= f.life) {
                it.remove();
            }
        }
        for (Iterator<Thunder> it = THUNDER.iterator(); it.hasNext(); ) {
            Thunder t = it.next();
            if (ticks >= t.at()) {
                level.playLocalSound(t.x(), t.y(), t.z(), t.crack() ? SoundEvents.LIGHTNING_BOLT_IMPACT : SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                        t.volume(), t.pitch(), false);
                it.remove();
            }
        }
        if (!VoxelClouds.active() || OvergrownVegetationConfig.COMMON.cumulonimbus.get() <= 0.0) {
            storms = List.of();
            return;
        }
        long gameTime = level.getGameTime();
        if (ticks % 20 == 0) {
            float coverage = VoxelClouds.coverage(level, 1.0F);
            double fx = CloudField.fieldX(cam.x, gameTime, 1.0F);
            double fz = CloudField.fieldZ(cam.z, gameTime, 1.0F);
            storms = VoxelClouds.storms(level, 1.0F, coverage, fx - GIANT_RANGE, fz - GIANT_RANGE, fx + GIANT_RANGE, fz + GIANT_RANGE);
        }
        boolean thunder = com.yayuy.overgrownvegetation.sky.CloudSky.rawThunder(level, 1.0F) > 0.5F;
        if (DEV_LOG && ticks % 100 == 0) {
            StringBuilder sb = new StringBuilder();
            for (CloudField.Storm s : storms) {
                sb.append(String.format(" [%.0f,%.0f m%.2f g%s d%.0f]", s.x() + CloudField.offsetX(gameTime), s.z() + CloudField.offsetZ(gameTime), s.maturity(), s.giant(),
                        Math.hypot(s.x() + CloudField.offsetX(gameTime) - cam.x, s.z() + CloudField.offsetZ(gameTime) - cam.z)));
            }
            OvergrownVegetation.LOGGER.info("Thunderstorms 雲 {} 雷雨 {} 光 {}{}", storms.size(), thunder, FLASHES.size(), sb);
        }
        float rate = OvergrownVegetationConfig.COMMON.lightningRate.get().floatValue() * (thunder ? FLASH_CHANCE_THUNDER : FLASH_CHANCE);
        double windX = CloudField.offsetX(gameTime);
        double windZ = CloudField.offsetZ(gameTime);
        for (CloudField.Storm s : storms) {
            if (s.congestus() || s.maturity() < 0.75F || FLASHES.size() >= MAX_FLASHES || RANDOM.nextFloat() >= rate) {
                continue;
            }
            double sx = s.x() + windX;
            double sz = s.z() + windZ;
            if (Math.hypot(sx - cam.x, sz - cam.z) > (s.giant() ? GIANT_RANGE : FLASH_RANGE)) {
                continue;
            }
            spawn(level, cam, s, sx, sz, thunder);
        }
        // 積乱雲の中にいると、まわりの雲の中でも光る(霧ごと青白く光り、雷鳴が近く大きい。線は描かない = 描く線は触れたら死ぬ雷だけ)。
        float inStorm = CloudInterior.storm(1.0F) * CloudInterior.setting();
        if (inStorm > 0.3F && FLASHES.size() < MAX_FLASHES + 2
                && RANDOM.nextFloat() < INSIDE_FLASH * OvergrownVegetationConfig.COMMON.lightningRate.get().floatValue() * (thunder ? 2.0F : 1.0F)) {
            for (int i = 0; i < 6; i++) {
                double a = RANDOM.nextDouble() * Math.PI * 2.0;
                double d = 25.0 + 135.0 * RANDOM.nextDouble();
                double x = cam.x + Math.cos(a) * d;
                double y = cam.y + (RANDOM.nextDouble() - 0.5) * 80.0;
                double z = cam.z + Math.sin(a) * d;
                if (CloudInterior.solidWorld(level, x, y, z) == 2) {
                    glow(level, cam, x, y, z, 7 + RANDOM.nextInt(6), 0.0F);
                    break;
                }
            }
        }
        for (Iterator<CloudBolt> it = BOLTS.iterator(); it.hasNext(); ) {
            CloudBolt b = it.next();
            if (++b.age > b.charge + b.strike + 3) {
                it.remove();
            }
        }
    }

    /** 光だけの雷(パルス 2〜3 回)と雷鳴。start = 最初のパルスまでの tick。 */
    private static void glow(ClientLevel level, Vec3 cam, double x, double y, double z, int life, float start) {
        Flash f = new Flash();
        f.x = x;
        f.y = y;
        f.z = z;
        f.life = life + (int) start;
        int n = 2 + RANDOM.nextInt(2);
        f.pulses = new float[n];
        for (int i = 0; i < n; i++) {
            f.pulses[i] = start + 0.5F + RANDOM.nextFloat() * (life - 3.5F);
        }
        FLASHES.add(f);
        double dist = Math.sqrt((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z));
        thunder(level, x, y, z, dist, (int) start);
    }

    /** 雷鳴(音の速さの遅れ・遠いほど小さく低く)。90 ブロックより近いと割れる音も。after = 鳴らし始めるまでの tick(遅れの前)。 */
    private static void thunder(ClientLevel level, double x, double y, double z, double dist, int after) {
        if (dist >= SOUND_RANGE) {
            return;
        }
        float gain = Mth.clamp(1.0F - (float) dist / 1600.0F, 0.12F, 0.85F);
        float volume = Math.max(1.0F, (float) dist / (16.0F * (1.0F - gain)));
        float pitch = Mth.lerp(Mth.clamp((float) (dist / SOUND_RANGE), 0.0F, 1.0F), 0.9F, 0.55F) + RANDOM.nextFloat() * 0.1F;
        int delay = after + (int) (dist / 17.15);
        THUNDER.add(new Thunder(x, y, z, ticks + delay, volume, pitch));
        if (dist < 90.0) {
            THUNDER.add(new Thunder(x, y, z, ticks + delay, 1.5F, 0.9F + RANDOM.nextFloat() * 0.2F, true));
        }
    }

    /** サーバーから積乱雲の中を走る雷が来た(前触れの間ちらちら光り、走る間は明るく、霧と雲を照らす)。 */
    public static void onBolt(com.yayuy.overgrownvegetation.network.CloudBoltPayload p) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || p.points().length < 6) {
            return;
        }
        CloudBolt b = new CloudBolt();
        b.points = new ArrayList<>();
        b.branch = new ArrayList<>();
        for (int i = 0; i + 2 < p.points().length; i += 3) {
            (i / 3 < p.branch() ? b.points : b.branch).add(new Vec3(p.x() + p.points()[i], p.y() + p.points()[i + 1], p.z() + p.points()[i + 2]));
        }
        if (b.points.size() < 2) {
            return;
        }
        b.charge = p.charge();
        b.strike = p.strike();
        b.seed = RANDOM.nextLong();
        BOLTS.add(b);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 mid = b.points.get(b.points.size() / 2);
        double near = Double.MAX_VALUE;
        Vec3 nearest = mid;
        for (Vec3 q : b.points) {
            double d = q.distanceTo(cam);
            if (d < near) {
                near = d;
                nearest = q;
            }
        }
        // 光: 走り出しに合わせたパルス(霧の色・雲のシェーダー)。
        Flash f = new Flash();
        f.x = mid.x;
        f.y = mid.y;
        f.z = mid.z;
        f.life = b.charge + b.strike + 4;
        f.pulses = new float[]{b.charge + 0.5F, b.charge + 3.5F, b.charge + Math.max(4.0F, b.strike - 2.0F)};
        FLASHES.add(f);
        // 前兆(10-18 ユーザー「前兆は先にバチバチなりながら細い雷が走り、そこから太い雷が走る」): 細い雷のちらつきで霧が弱く光り、近くの線からバチバチ鳴る。
        Flash pre = new Flash();
        pre.x = mid.x;
        pre.y = mid.y;
        pre.z = mid.z;
        pre.life = b.charge;
        pre.gain = 0.22F;
        int n = 5 + RANDOM.nextInt(4);
        pre.pulses = new float[n];
        for (int i = 0; i < n; i++) {
            // 終わりに向けて詰まる。
            pre.pulses[i] = b.charge * (float) Math.sqrt((i + 0.3F + 0.6F * RANDOM.nextFloat()) / n) - 1.0F;
        }
        FLASHES.add(pre);
        if (near < CRACKLE_RANGE) {
            com.yayuy.overgrownvegetation.client.sound.CrackleSound.play(nearest.x, nearest.y, nearest.z, b.charge, 1.0F);
        }
        thunder(level, nearest.x, nearest.y, nearest.z, near, b.charge);
    }

    private static void spawn(ClientLevel level, Vec3 cam, CloudField.Storm s, double sx, double sz, boolean thunder) {
        Flash f = new Flash();
        // 光る所: 胴の塊(金床の平たい円盤と、天辺の近くは除く)のどれかの中。
        double windX = sx - s.x();
        double windZ = sz - s.z();
        int top = s.topY();
        List<CloudField.Puff> body = new ArrayList<>();
        for (CloudField.Puff p : CloudField.puffs(s)) {
            if (p.squash() > 0.9F && p.y() < top - (top - CloudField.LOW_BASE) * 0.15) {
                body.add(p);
            }
        }
        if (body.isEmpty()) {
            return;
        }
        CloudField.Puff p = body.get(RANDOM.nextInt(body.size()));
        double a = RANDOM.nextDouble() * Math.PI * 2.0;
        double r = Math.sqrt(RANDOM.nextDouble()) * s.bodyRadius() * 0.6;
        f.x = p.x() + windX + (RANDOM.nextDouble() - 0.5) * p.r();
        f.z = p.z() + windZ + (RANDOM.nextDouble() - 0.5) * p.r();
        f.y = Math.max(CloudField.LOW_BASE + 20.0, p.y() + (RANDOM.nextDouble() - 0.5) * p.r());
        f.life = 7 + RANDOM.nextInt(6);
        int n = 2 + RANDOM.nextInt(2);
        f.pulses = new float[n];
        for (int i = 0; i < n; i++) {
            f.pulses[i] = 0.5F + RANDOM.nextFloat() * (f.life - 3.5F);
        }
        double dist = Math.sqrt((f.x - cam.x) * (f.x - cam.x) + (f.y - cam.y) * (f.y - cam.y) + (f.z - cam.z) * (f.z - cam.z));
        boolean bolt = RANDOM.nextFloat() < (thunder ? 0.6F : 0.35F);
        if (bolt) {
            double bx = sx + Math.cos(a + 1.0) * r * 0.8;
            double bz = sz + Math.sin(a + 1.0) * r * 0.8;
            double ground = 63.0;
            int ix = Mth.floor(bx);
            int iz = Mth.floor(bz);
            if (level.hasChunk(ix >> 4, iz >> 4)) {
                ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
            }
            f.bolt = zigzag(new Vec3(bx, CloudField.LOW_BASE + 2, bz), ground, 16, 7.0);
            Vec3 from = f.bolt.get(4 + RANDOM.nextInt(5));
            f.branch = zigzag(from, Math.max(ground, from.y - 80.0), 6, 6.0);
            if (Math.hypot(bx - cam.x, bz - cam.z) < 300.0) {
                level.setSkyFlashTime(2);
            }
        }
        FLASHES.add(f);
        if (dist < SOUND_RANGE) {
            float gain = Mth.clamp(1.0F - (float) dist / 1600.0F, 0.12F, 0.85F) * (bolt ? 1.0F : 0.6F);
            float volume = Math.max(1.0F, (float) dist / (16.0F * (1.0F - gain)));
            float pitch = Mth.lerp(Mth.clamp((float) (dist / SOUND_RANGE), 0.0F, 1.0F), 0.9F, 0.55F) + RANDOM.nextFloat() * 0.1F;
            int delay = (int) (dist / 17.15);
            THUNDER.add(new Thunder(f.x, f.y, f.z, ticks + delay, volume, pitch));
        }
    }

    /** 上から地面(または bottomY)までのぎざぎざの線。 */
    private static List<Vec3> zigzag(Vec3 top, double bottomY, int segments, double jitter) {
        List<Vec3> points = new ArrayList<>();
        points.add(top);
        double x = top.x;
        double z = top.z;
        double drift = RANDOM.nextDouble() * Math.PI * 2.0;
        double dx = Math.cos(drift) * jitter * 0.5;
        double dz = Math.sin(drift) * jitter * 0.5;
        for (int i = 1; i <= segments; i++) {
            double y = Mth.lerp(i / (double) segments, top.y, bottomY);
            x += dx + (RANDOM.nextDouble() - 0.5) * 2.0 * jitter;
            z += dz + (RANDOM.nextDouble() - 0.5) * 2.0 * jitter;
            points.add(new Vec3(x, y, z));
        }
        return points;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("積乱雲の雷(描画)で例外が出たので、このセッションでは使わない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || CaveMist.hideSky()) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        reach = mc.gameRenderer.getDepthFar() * 0.9;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (!storms.isEmpty()) {
            renderRain(level, buffers.getBuffer(rainType()), eye, partialTick);
            buffers.endBatch(rainType());
        }
        if (FLASHES.isEmpty() && BOLTS.isEmpty()) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        // 積乱雲の中を走る雷: 前触れは細くちらちら、走る間は明るく太く。雲の中では霧の距離の 3 倍ほどで薄れる(雷はとても明るいので消えきらない)。
        float inside = CloudInterior.amount(partialTick) * CloudInterior.setting();
        float fogFar = CloudInterior.fogFar(mc.gameRenderer.getRenderDistance(), partialTick);
        for (CloudBolt b : BOLTS) {
            float t = b.age + partialTick;
            float fog = 1.0F;
            if (inside > 0.01F) {
                double d = b.points.get(b.points.size() / 2).distanceTo(eye);
                fog = Mth.lerp(inside, 1.0F, Math.max(0.3F, (float) Math.exp(-d / (fogFar * 3.0F))));
            }
            if (t < b.charge) {
                leaders(c, b, t, eye, fog);
                continue;
            }
            float a;
            float w;
            if (t < b.charge + b.strike) {
                long h = (b.seed + (long) b.age * 0xBF58476D1CE4E5B9L) >>> 40;
                a = 0.85F + 0.15F * (h % 1000) / 1000.0F;
                w = 1.8F;
            } else {
                a = Math.max(0.0F, 1.0F - (t - b.charge - b.strike) / 3.0F) * 0.6F;
                w = 1.2F;
            }
            a *= fog;
            if (a > 0.01F) {
                line(c, b.points, eye, w, a);
                if (b.branch.size() >= 2) {
                    line(c, b.branch, eye, w * 0.7F, a * 0.8F);
                }
            }
        }
        for (Flash f : FLASHES) {
            if (f.bolt == null) {
                continue;
            }
            float a = f.intensity(partialTick);
            if (a <= 0.01F) {
                continue;
            }
            if (inside > 0.01F) {
                a *= Mth.lerp(inside, 1.0F, Math.max(0.15F, (float) Math.exp(-f.bolt.get(0).distanceTo(eye) / (fogFar * 3.0F))));
            }
            line(c, f.bolt, eye, 1.0F, a);
            line(c, f.branch, eye, 0.6F, a * 0.7F);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /**
     * 雷の前兆の細い雷(10-18 ユーザー「前兆は先にバチバチなりながら細い雷が走り、そこから太い雷が走る」)。太い雷の通り道を始まりから少しずつ伸びる細い線
     * (先導の雷。ちらつく)と、伸びた所から横へ探るように出ては消える短い細い枝(2 tick ごとに生え替わる)。太い雷はこの道をなぞって走る。当たりは無い。
     */
    private static void leaders(VertexConsumer c, CloudBolt b, float t, Vec3 eye, float fog) {
        float u = t / Math.max(1.0F, b.charge);
        // 伸び方: 初めは速く、太い雷の直前に道がつながる。
        float progress = Mth.clamp((float) Math.pow(u, 0.8) * 1.05F, 0.0F, 1.0F);
        long step = (long) (t / 2.0F);
        long h = (b.seed + step * 0x9E3779B97F4A7C15L) >>> 40;
        float flicker = 0.55F + 0.45F * (h % 1000) / 1000.0F;
        float a = (0.3F + 0.4F * u) * flicker * fog;
        List<Vec3> main = partial(b.points, progress);
        if (main.size() >= 2) {
            line(c, main, eye, 0.32F, a);
        }
        if (b.branch.size() >= 2) {
            int from = indexOf(b.points, b.branch.get(0));
            float reach = (progress * (b.points.size() - 1) - from) / (b.points.size() - 1) * 2.5F;
            List<Vec3> twig = partial(b.branch, Mth.clamp(reach, 0.0F, 1.0F));
            if (twig.size() >= 2) {
                line(c, twig, eye, 0.25F, a * 0.8F);
            }
        }
        // 探る枝: 伸びた道の上(先の方が多い)から 2〜4 本、3〜5 節。
        RandomSource r = RandomSource.create(b.seed ^ (step * 0xC2B2AE3D27D4EB4FL));
        int feelers = 2 + r.nextInt(3);
        for (int k = 0; k < feelers && main.size() >= 2; k++) {
            float along = (float) Math.sqrt(r.nextFloat());
            List<Vec3> upTo = partial(main, along);
            Vec3 p = upTo.get(upTo.size() - 1);
            List<Vec3> f = new ArrayList<>();
            f.add(p);
            Vec3 dir = new Vec3(r.nextDouble() - 0.5, (r.nextDouble() - 0.5) * 0.8, r.nextDouble() - 0.5).normalize();
            int segs = 3 + r.nextInt(3);
            for (int i = 0; i < segs; i++) {
                double len = 2.5 + 4.5 * r.nextDouble();
                dir = dir.add((r.nextDouble() - 0.5) * 0.9, (r.nextDouble() - 0.5) * 0.6, (r.nextDouble() - 0.5) * 0.9).normalize();
                p = p.add(dir.scale(len));
                f.add(p);
            }
            line(c, f, eye, 0.2F, a * (0.5F + 0.4F * r.nextFloat()));
        }
    }

    /** 折れ線の始まりから割合 p(0〜1)までの部分(最後の点は節の途中)。 */
    private static List<Vec3> partial(List<Vec3> points, float p) {
        List<Vec3> out = new ArrayList<>();
        if (points.isEmpty()) {
            return out;
        }
        float f = Mth.clamp(p, 0.0F, 1.0F) * (points.size() - 1);
        int whole = (int) Math.floor(f);
        for (int i = 0; i <= whole && i < points.size(); i++) {
            out.add(points.get(i));
        }
        if (whole + 1 < points.size()) {
            float frac = f - whole;
            if (frac > 0.001F) {
                Vec3 a = points.get(whole);
                Vec3 b = points.get(whole + 1);
                out.add(a.add(b.subtract(a).scale(frac)));
            }
        }
        return out;
    }

    private static int indexOf(List<Vec3> points, Vec3 q) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = points.get(i).distanceToSqr(q);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 稲妻の線(芯と、まわりの淡いにじみ)。 */
    private static void line(VertexConsumer c, List<Vec3> points, Vec3 eye, float widthScale, float alpha) {
        for (int i = 0; i + 1 < points.size(); i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            double dist = a.distanceTo(eye);
            float w = (BOLT_WIDTH + BOLT_WIDTH_PER_BLOCK * (float) dist) * widthScale;
            strip(c, a, b, eye, w, Math.min(1.0F, alpha), 0.92F, 0.95F, 1.0F);
            strip(c, a, b, eye, w * 6.0F, Math.min(1.0F, alpha * 0.18F), 0.6F, 0.7F, 1.0F);
            if (i > 0) {
                // 折れ目を丸く埋める(帯だけだと、太い雷の折れ目の外側ににじみの板の角と三角のすき間が見えた。10-18)。
                disc(c, a, eye, w * 3.0F, Math.min(1.0F, alpha * 0.18F), 0.6F, 0.7F, 1.0F);
            }
        }
    }

    /** カメラを向く円(真ん中が濃く縁が 0。8 つの三角を、つぶした四角で)。 */
    private static void disc(VertexConsumer c, Vec3 p, Vec3 eye, float radius, float alpha, float r, float g, float bl) {
        Vec3 view = p.subtract(eye);
        double len = view.length();
        if (len < 1.0E-4) {
            return;
        }
        view = view.scale(1.0 / len);
        Vec3 right = view.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0E-6) {
            right = new Vec3(1.0, 0.0, 0.0);
        }
        right = right.normalize();
        Vec3 up = right.cross(view).normalize();
        Vec3 center = p.subtract(eye);
        for (int k = 0; k < 8; k++) {
            double a0 = k * Math.PI / 4.0;
            double a1 = (k + 1) * Math.PI / 4.0;
            Vec3 e0 = center.add(right.scale(Math.cos(a0) * radius)).add(up.scale(Math.sin(a0) * radius));
            Vec3 e1 = center.add(right.scale(Math.cos(a1) * radius)).add(up.scale(Math.sin(a1) * radius));
            vertex(c, center, r, g, bl, alpha);
            vertex(c, e0, r, g, bl, 0.0F);
            vertex(c, e1, r, g, bl, 0.0F);
            vertex(c, center, r, g, bl, alpha);
        }
    }

    /** a から b への、カメラを向く帯 1 枚(横は芯が濃く縁が 0)。 */
    private static void strip(VertexConsumer c, Vec3 a, Vec3 b, Vec3 eye, float width, float alpha, float r, float g, float bl) {
        Vec3 axis = b.subtract(a);
        Vec3 side = axis.cross(eye.subtract(a));
        double len = side.length();
        if (len < 1.0E-6) {
            return;
        }
        side = side.scale(width * 0.5 / len);
        Vec3 ra = a.subtract(eye);
        Vec3 rb = b.subtract(eye);
        vertex(c, ra.subtract(side), r, g, bl, 0.0F);
        vertex(c, rb.subtract(side), r, g, bl, 0.0F);
        vertex(c, rb, r, g, bl, alpha);
        vertex(c, ra, r, g, bl, alpha);
        vertex(c, ra, r, g, bl, alpha);
        vertex(c, rb, r, g, bl, alpha);
        vertex(c, rb.add(side), r, g, bl, 0.0F);
        vertex(c, ra.add(side), r, g, bl, 0.0F);
    }

    /**
     * 頂点 1 つ(カメラからの位置)。奥の面より遠い所は、向きを変えずにカメラの方へ寄せる(巨大な積乱雲の稲妻・雨の柱は 1000 ブロック先にもある。
     * 加算・半透明で奥行きを書かないので、寄せても重なりの順は崩れない。手前の地形にはちゃんと隠れる)。
     */
    private static void vertex(VertexConsumer c, Vec3 p, float r, float g, float b, float a) {
        double l = p.length();
        if (l > reach) {
            p = p.scale(reach / l);
        }
        c.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    /** 育ちきった積乱雲の塔の下の雨の柱(灰色の半透明の帯を数本)。 */
    private static void renderRain(ClientLevel level, VertexConsumer c, Vec3 eye, float partialTick) {
        long gameTime = level.getGameTime();
        double windX = CloudField.offsetX((double) gameTime + partialTick);
        double windZ = CloudField.offsetZ((double) gameTime + partialTick);
        Vec3 cloud = SkyLight.cloudColor(level, partialTick, level.getCloudColor(partialTick));
        float r = (float) cloud.x * 0.62F;
        float g = (float) cloud.y * 0.64F;
        float b = (float) cloud.z * 0.68F;
        double bottom = Math.min(eye.y - 20.0, 63.0);
        float inCloud = 1.0F - CloudInterior.amount(partialTick) * CloudInterior.setting();
        for (CloudField.Storm s : storms) {
            // 入道雲(10-18)は雨の柱を出さない(尾流雲は Virga)。
            if (s.congestus() || s.maturity() < 0.75F) {
                continue;
            }
            double sx = s.x() + windX;
            double sz = s.z() + windZ;
            double horizontal = Math.hypot(sx - eye.x, sz - eye.z);
            // 近づくとバニラの雨と重なるので薄く。遠くは窓の外で消える。
            float near = CanopyLight.smooth(Mth.clamp((float) ((horizontal - s.bodyRadius()) / 200.0), 0.0F, 1.0F));
            float far = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((horizontal - (s.giant() ? 2000.0 : 1400.0)) / 400.0), 0.0F, 1.0F));
            // 雲の中では霧に沈むので消す(CloudInterior)。
            float alpha = RAIN_ALPHA * near * far * Mth.clamp((s.maturity() - 0.75F) / 0.15F, 0.0F, 1.0F) * inCloud;
            if (alpha <= 0.005F) {
                continue;
            }
            RandomSource shafts = RandomSource.create(s.id());
            int count = 8 + shafts.nextInt(5);
            for (int i = 0; i < count; i++) {
                double a = shafts.nextDouble() * Math.PI * 2.0;
                double rr = Math.sqrt(shafts.nextDouble()) * s.bodyRadius() * 0.7;
                double x = sx + Math.cos(a) * rr;
                double z = sz + Math.sin(a) * rr;
                float width = s.bodyRadius() * (0.2F + 0.15F * shafts.nextFloat());
                // 風下へ少し傾く。
                Vec3 top = new Vec3(x, CloudField.LOW_BASE, z);
                Vec3 foot = new Vec3(x + CloudField.windX((double) gameTime + partialTick) * 400.0, bottom,
                        z + CloudField.windZ((double) gameTime + partialTick) * 400.0);
                rainStrip(c, top, foot, eye, width, alpha * (0.6F + 0.4F * shafts.nextFloat()), r, g, b);
            }
        }
    }

    /** 雨の帯: 横は芯が濃く縁が 0、縦は雲の底から下の 4 割で薄れる。 */
    private static void rainStrip(VertexConsumer c, Vec3 top, Vec3 foot, Vec3 eye, float width, float alpha, float r, float g, float b) {
        final int rows = 6;
        Vec3 axis = foot.subtract(top);
        Vec3 prevL = null;
        Vec3 prevC = null;
        Vec3 prevR = null;
        float prevA = 0.0F;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = top.add(axis.scale(t));
            Vec3 side = axis.cross(eye.subtract(p));
            double len = side.length();
            if (len < 1.0E-6) {
                return;
            }
            side = side.scale(width * 0.5 / len);
            float a = alpha * (1.0F - CanopyLight.smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F)));
            Vec3 l = p.subtract(side).subtract(eye);
            Vec3 m = p.subtract(eye);
            Vec3 rr = p.add(side).subtract(eye);
            if (prevC != null) {
                quad(c, prevL, 0.0F, l, 0.0F, m, a, prevC, prevA, r, g, b);
                quad(c, prevC, prevA, m, a, rr, 0.0F, prevR, 0.0F, r, g, b);
            }
            prevL = l;
            prevC = m;
            prevR = rr;
            prevA = a;
        }
    }

    private static void quad(VertexConsumer c, Vec3 p0, float a0, Vec3 p1, float a1, Vec3 p2, float a2, Vec3 p3, float a3,
                             float r, float g, float b) {
        vertex(c, p0, r, g, b, a0);
        vertex(c, p1, r, g, b, a1);
        vertex(c, p2, r, g, b, a2);
        vertex(c, p3, r, g, b, a3);
    }

    /** 頂点を寄せる距離(奥の面の 0.9 倍。描くたびに決める)。 */
    private static double reach = 400.0;

    /** 雨の柱の層(半透明でまぜる。奥行きは書かない)。 */
    private static RenderType rainType() {
        if (rainType == null) {
            rainType = RenderType.create(OvergrownVegetation.MODID + "_storm_rain", DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return rainType;
    }

    /** 今光っている数(開発用のログ)。 */
    static int count() {
        return FLASHES.size();
    }

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private Thunderstorms() {
    }
}
