package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>洞窟の天井の穴から差す日光の筋</b>(2026-11-07。ユーザー「暗闇の中の洞窟へ地上に空いた穴から日光が差し込む、これほど幻想的な光景はなかなかない」。
 * 地下モードの仕様にあった「洞窟の口の光の筋」)。林冠の隙間の筋 {@link LightShafts} と同じ筋({@link LightShafts.Shaft})と描き方(カメラを向く 2 重の帯・
 * 床のにじみ・加算 {@link AddLight})を使い、見つけ方だけ洞窟用にした。筋の中の漂う粒は {@link ShaftMotes}(過繁茂では胞子、外では埃の色)。
 *
 * <h2>見つけ方</h2>
 * カメラのまわり {@code radius} の列を毎 tick {@code scanPerTick} か所ずつ見る。地下モードの間か、まわりに天井になれる岩があるときだけ({@link CaveMap#nearRoof})。
 * <ol>
 *     <li>カメラの高さ +24〜−32 で、床(下が固い・上 2 段が空き)で、空の光が 9 以上(穴の下)の所。</li>
 *     <li>洞窟の中: まわり 6・12・20 ブロックの 24 列のうち 6 列以上に岩の天井がある(外の地面ではない)。</li>
 *     <li>そこから太陽(夜は月)の方へたどって空へ抜ける({@link SunTrace#traceSun})。</li>
 *     <li>同じ線で岩の天井の穴に入る所と抜ける所を探し、抜けた所を筋の上端にする。太さは穴の入口の半径 × widthPerRadius。</li>
 * </ol>
 * 上端は動かず、下端は太陽の向きで毎フレーム決める(太陽が動いて穴から光が通らなくなったら消える)。昼は金〜白、夜は月光({@link SunLight#airColor})、
 * 雨は弱く({@link SunLight#lightStrength})。地下モードの黒い霧の中でも、霧で沈める分は {@code 1 − fogKeep} だけ(加算の光は霧を受けない)。
 */
final class CaveShafts {

    private static final List<LightShafts.Shaft> SHAFTS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final float[] RGB = new float[3];
    private static float lastDay;
    private static long tickNanos;
    private static long tickCount;
    private static long renderNanos;
    private static long renderCount;
    /** 確かめ用: 描かない(wind cavedev shafts off)。 */
    static boolean devOff;

    private CaveShafts() {
    }

    static void clear() {
        SHAFTS.clear();
        lastDay = 0.0F;
    }

    static List<LightShafts.Shaft> shafts() {
        return SHAFTS;
    }

    static int count() {
        return SHAFTS.size();
    }

    /** 漂う粒の強さ(筋が無ければ 0)。 */
    static float moteIntensity() {
        return SHAFTS.isEmpty() || devOff ? 0.0F : DesignData.atmosphere().caveShafts().strength();
    }

    private static boolean on(ClientLevel level) {
        return !devOff && DesignData.atmosphere().caveShafts().enabled() && VisualQuality.lightShafts() && level.dimensionType().hasSkyLight();
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!on(level)) {
            clear();
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereEye.CaveShaftsParams p = DesignData.atmosphere().caveShafts();
        Vec3 sun = SunLight.lightDirection(level, 1.0F);
        float day = SunLight.lightStrength(level, sun, 1.0F);
        lastDay = day;
        Vec3 center = player.position();
        for (Iterator<LightShafts.Shaft> it = SHAFTS.iterator(); it.hasNext(); ) {
            LightShafts.Shaft s = it.next();
            s.age++;
            s.groundY += (s.targetGroundY - s.groundY) * 0.08;
            s.bottom = s.bottomFor(sun);
            if (s.dyingAt < 0) {
                double dx = s.bottom.x - center.x;
                double dz = s.bottom.z - center.z;
                boolean far = dx * dx + dz * dz > (p.radius() + 8.0) * (p.radius() + 8.0);
                boolean recheck = (s.age + (int) (s.phase * 20.0F)) % 20 == 0;
                if (far || day <= 0.0F || s.age >= s.life || recheck && !revalidate(level, s, sun)) {
                    s.dyingAt = s.age;
                }
            }
            if (s.gone()) {
                it.remove();
            }
        }
        boolean near = CaveLights.mix() > 0.05F || CaveMap.nearRoof();
        if (day > 0.0F && near && sun.y >= 0.08) {
            int cap = VisualQuality.shaftCap(p.maxShafts());
            for (int i = 0; i < p.scanPerTick() && SHAFTS.size() < cap; i++) {
                tryFind(level, player, sun, p);
            }
        }
        tickNanos += System.nanoTime() - t0;
        tickCount++;
    }

    private static boolean air(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z)).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    private static boolean solid(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        return !level.getBlockState(pos.set(x, y, z)).getCollisionShape(level, pos).isEmpty();
    }

    private static void tryFind(ClientLevel level, LocalPlayer player, Vec3 sun, AtmosphereEye.CaveShaftsParams p) {
        int r = p.radius();
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(r * 2 + 1) - r;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(r * 2 + 1) - r;
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eyeY = Mth.floor(player.getY());
        for (int y = eyeY + 24; y > eyeY - 32; y--) {
            if (!air(level, pos, x, y, z) || !air(level, pos, x, y + 1, z) || !solid(level, pos, x, y - 1, z)) {
                continue;
            }
            if (level.getBrightness(LightLayer.SKY, pos.set(x, y, z)) < 9) {
                continue;
            }
            if (consider(level, pos, x, y, z, sun, p)) {
                return;
            }
        }
    }

    /** 床 (x, y, z) が洞窟の天井の穴の下で、太陽の光が穴から届くなら筋を足す。 */
    private static boolean consider(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, Vec3 sun, AtmosphereEye.CaveShaftsParams p) {
        // 洞窟の中: 6・12・20 ブロックの輪のどれか 1 つで、8 方向のうち 6 方向以上に岩の天井がある(穴をぐるりと天井が囲む)。
        // 丘や崖の脇の地面は片側にしか天井が無いので外れる(11-07: 前は 24 列のうち 6 列で、fog14 の丘の脇の林床に筋が出た)。
        boolean enclosed = false;
        for (int ring : new int[]{6, 12, 20}) {
            int roofed = 0;
            for (int[] d : DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                int rx = x + (int) Math.round(d[0] * ring / len);
                int rz = z + (int) Math.round(d[1] * ring / len);
                if (level.hasChunk(rx >> 4, rz >> 4) && CaveMap.thickRoofAbove(level, rx, y + 2, rz, 64)) {
                    roofed++;
                }
            }
            if (roofed >= 6) {
                enclosed = true;
                break;
            }
        }
        if (!enclosed) {
            return false;
        }
        double gx = x + 0.5;
        double gz = z + 0.5;
        int trace = SunTrace.traceSun(level, gx, y + 0.05, gz, sun);
        if (trace < 0 || trace > SunTrace.highCanopyShaftAllowance()) {
            return false;
        }
        // 太陽の方へたどって、天井の穴に入る所(まわり 2 ブロックの 8 方向のうち 3 方向以上が岩)と抜ける所(岩が無くなる)を探す。
        int kIn = -1;
        int kOut = -1;
        for (int k = 1; k <= 200; k++) {
            double px = gx + sun.x * k;
            double py = y + sun.y * k;
            double pz = gz + sun.z * k;
            int rock = 0;
            for (int[] d : DIRS) {
                if (CaveMap.isRock(level.getBlockState(pos.set(Mth.floor(px + d[0] * 2.0), Mth.floor(py), Mth.floor(pz + d[1] * 2.0))))) {
                    rock++;
                }
            }
            if (kIn < 0) {
                if (rock >= 3) {
                    kIn = k;
                }
            } else if (rock == 0) {
                kOut = k;
                break;
            }
        }
        if (kIn < 0 || kOut < 0) {
            return false;
        }
        Vec3 top = new Vec3(gx + sun.x * kOut, y + sun.y * kOut, gz + sun.z * kOut);
        if (top.y - y < p.minDrop()) {
            return false;
        }
        // 太さ: 穴の入口の高さで 8 方向に岩までの距離(最大 24)の平均。
        int hy = Mth.floor(y + sun.y * kIn);
        double hx = gx + sun.x * kIn;
        double hz = gz + sun.z * kIn;
        float sum = 0.0F;
        for (int[] d : DIRS) {
            double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
            int reach = 24;
            for (int s = 1; s <= 24; s++) {
                if (CaveMap.isRock(level.getBlockState(pos.set(Mth.floor(hx + d[0] * s / len), hy, Mth.floor(hz + d[1] * s / len))))) {
                    reach = s;
                    break;
                }
            }
            sum += reach;
        }
        float width = Mth.clamp(sum / DIRS.length * p.widthPerRadius(), 0.6F, p.maxWidth());
        double spacing = Math.max(2.5, width * 1.6);
        for (LightShafts.Shaft s : SHAFTS) {
            double dx = s.bottom.x - gx;
            double dz = s.bottom.z - gz;
            if (dx * dx + dz * dz < spacing * spacing) {
                return false;
            }
        }
        LightShafts.Shaft shaft = new LightShafts.Shaft(top, new Vec3(gx, y, gz), width, RANDOM.nextFloat(), 600 + RANDOM.nextInt(600));
        SHAFTS.add(shaft);
        return true;
    }

    /** 太陽が動いた後も穴から光が床へ落ちるか。落ちるなら床の高さの行き先だけ替える。 */
    private static boolean revalidate(ClientLevel level, LightShafts.Shaft s, Vec3 sun) {
        if (sun.y < 0.08) {
            return false;
        }
        Vec3 bottom = s.bottomFor(sun);
        int bx = Mth.floor(bottom.x);
        int bz = Mth.floor(bottom.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int ref = Mth.floor(s.targetGroundY);
        float ground = Float.NaN;
        for (int y = ref + 3; y > ref - 6; y--) {
            if (air(level, pos, bx, y, bz) && solid(level, pos, bx, y - 1, bz)) {
                ground = y;
                break;
            }
        }
        if (Float.isNaN(ground) || ground > s.top.y - 2.0) {
            return false;
        }
        int trace = SunTrace.traceSun(level, bottom.x, ground + 0.05, bottom.z, sun);
        if (trace < 0 || trace > SunTrace.highCanopyShaftAllowance()) {
            return false;
        }
        s.targetGroundY = ground;
        return true;
    }

    static void render(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (SHAFTS.isEmpty() || level == null || !on(level)) {
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereEye.CaveShaftsParams p = DesignData.atmosphere().caveShafts();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = SunLight.lightDirection(level, partialTick);
        float day = SunLight.lightStrength(level, sun, partialTick);
        SunLight.airColor(level, sun, partialTick, RGB);
        LightShafts.color(RGB[0], RGB[1], RGB[2]);
        Vec3 eye = event.getCamera().getPosition();
        float soft = SunLight.softness(sun);
        float coreWidth = LightShafts.widthScale(soft);
        float haloWidth = 2.6F + 1.4F * soft;
        // 長い筋の遠い所を薄くする距離(LightShafts#longFade)。地下モードの短い霧ではなく、探す範囲で決める。
        float fadeEnd = p.radius() * 2.2F;
        double seconds = Atmosphere.time(partialTick) / 20.0;
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer consumer = buffers.getBuffer(AddLight.renderType());
        for (LightShafts.Shaft s : SHAFTS) {
            Vec3 bottom = s.bottomFor(sun);
            double dist = bottom.distanceTo(eye);
            float far = 1.0F - SunLight.smooth(Mth.clamp((float) ((dist - p.radius()) / 12.0), 0.0F, 1.0F));
            float fog = 1.0F - (1.0F - p.fogKeep()) * MistView.fogAmount((float) dist);
            float flicker = (float) (0.96 + 0.04 * Math.sin(2.0 * Math.PI * 0.15 * seconds + s.phase * 5.0));
            float alpha = s.presence() * day * p.strength() * far * fog * flicker;
            if (alpha <= 0.003F) {
                continue;
            }
            LightShafts.ribbon(consumer, s, bottom, eye, sun, coreWidth, p.coreAlpha() * alpha, soft, 0.0F, fadeEnd);
            LightShafts.ribbon(consumer, s, bottom, eye, sun, haloWidth, p.haloAlpha() * alpha, soft, 0.0F, fadeEnd);
            LightShafts.groundGlow(consumer, level, s, bottom, eye, 1.0F + 0.8F * soft, p.glow() * alpha * SunLight.lookScatter());
        }
        buffers.endBatch(AddLight.renderType());
        renderNanos += System.nanoTime() - t0;
        renderCount++;
    }

    /** 確かめ用の一行(筋の数・1 tick と 1 フレームの平均の時間。読むと数え直す)。 */
    static String describe() {
        String s = String.format("洞窟の光の筋 %d 本(昼 %.2f)探す %.3f ms/tick・描く %.3f ms/フレーム", SHAFTS.size(), lastDay,
                tickCount == 0 ? 0.0 : tickNanos / 1.0E6 / tickCount, renderCount == 0 ? 0.0 : renderNanos / 1.0E6 / renderCount);
        tickNanos = 0L;
        tickCount = 0L;
        renderNanos = 0L;
        renderCount = 0L;
        return s;
    }
}
