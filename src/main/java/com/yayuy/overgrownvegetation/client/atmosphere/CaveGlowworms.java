package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereCaveLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>洞窟の天井の土ボタル</b>(2026-11-07 ユーザー採用の洞窟の小ネタ 1。実在: 洞窟の天井から光る粘り糸を垂らす虫の幼虫。天井一面が星空のように見える)。
 * 設定: 洞窟の生き物も虫と爬虫類だけ(鳥は消え、コウモリは胞子に寄生されて死んだ = 11-07)。
 *
 * <p>クライアントだけの描画(Mob にしない・通信なし・素材なし)。カメラのまわり半径 {@code caveLife.glowworms.radius} の列を少しずつ見回り、
 * 目の高さ −{@code below}〜+{@code above} の中の<b>天井</b>(空気の真上が岩 = {@link CaveMap#isRock}、その上に厚い岩 = {@link CaveMap#thickRoofAbove})で、
 * 空の光が {@code maxSky} 以下の暗いマスに、座標から決まる確率で 1 匹を置く(同じ所にはいつも同じ点 = 近づいても湧き直さない)。
 * 確率は普通の洞窟 {@code chance}・過繁茂の洞窟 {@code chanceOvergrown}、近くに水があれば {@code wetBoost} 倍。地上・洞窟の判定はカメラの地下モードに頼らない
 * (その所の空の光と上の岩で決める)。
 * <ul>
 *     <li>見え方: 天井に青緑の小さな光の点(ゆっくり明滅、周期 {@code blinkSeconds} 秒)と、そこから細い糸 1〜{@code threads} 本が短く垂れる
 *     (淡く光る粒の連なり。わずかに揺れる)。足し算の光で、<b>霧で薄めない</b>({@link LifeSprites#beginGlowNoFog}。地下モードの黒い霧の中でも
 *     光る画素と同じく沈めない)。</li>
 *     <li>驚く: 光を持ったプレイヤー(手に光るブロック)が {@code shyDistance} に近づくと、近くの点が {@code shyIn} 秒かけて {@code shyLevel} まで暗くなり、
 *     離れると {@code shyOut} 秒で戻る(実物は光や音で驚くと光を弱める)。</li>
 * </ul>
 * 値はすべて仮決め(節 {@code caveLife.glowworms})。画質の段で数の上限を変え、羽虫の切り替え({@link VisualQuality#critters})で切る。
 */
public final class CaveGlowworms {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    /** 1 tick に見回る列の数。 */
    private static final int COLUMNS_PER_TICK = 96;
    private static final int RESCAN_TICKS = 200;

    private static final class Worm {
        final double x;
        final double y;
        final double z;
        final float seed;
        final int threads;
        final float[] len;
        float shy = 1.0F;

        Worm(double x, double y, double z, float seed, int threads, float[] len) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.seed = seed;
            this.threads = threads;
            this.len = len;
        }
    }

    /** 列(x, z)ごとの点。 */
    private static final Long2ObjectOpenHashMap<List<Worm>> COLUMNS = new Long2ObjectOpenHashMap<>();
    private static ClientLevel trackedLevel;
    private static int scanIndex;
    private static int centerX;
    private static int centerZ;
    private static int centerY;
    private static int count;
    private static long tickNanos;
    private static long renderNanos;
    private static int renderFrames;
    private static int ticks;

    private CaveGlowworms() {
    }

    static void clear() {
        COLUMNS.clear();
        trackedLevel = null;
        scanIndex = 0;
        count = 0;
    }

    static String describe() {
        String s = String.format("土ボタル %d(列 %d)tick %.3f ms 描画 %s", count, COLUMNS.size(), tickNanos / 1.0e6 / Math.max(1, ticks),
                renderFrames > 0 ? String.format("%.3f ms/フレーム", renderNanos / 1.0e6 / renderFrames) : "-");
        tickNanos = 0L;
        ticks = 0;
        renderNanos = 0L;
        renderFrames = 0;
        return s;
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        AtmosphereCaveLife.GlowParams p = DesignData.atmosphere().caveLife().glowworms();
        if (!p.enabled() || !VisualQuality.critters()) {
            if (!COLUMNS.isEmpty()) {
                clear();
            }
            return;
        }
        long t0 = System.nanoTime();
        if (level != trackedLevel) {
            clear();
            trackedLevel = level;
        }
        int r = Mth.ceil(p.radius());
        int px = Mth.floor(player.getX());
        int pz = Mth.floor(player.getZ());
        int py = Mth.floor(player.getEyeY());
        // 中心が 4 ブロック動いたら、遠くなった列を捨てる(見回りは続ける)。
        if (Math.abs(px - centerX) > 4 || Math.abs(pz - centerZ) > 4 || Math.abs(py - centerY) > 4) {
            centerX = px;
            centerZ = pz;
            centerY = py;
            COLUMNS.long2ObjectEntrySet().removeIf(e -> {
                int cx = (int) (e.getLongKey() >> 32);
                int cz = (int) e.getLongKey();
                return (cx - px) * (cx - px) + (cz - pz) * (cz - pz) > (r + 4) * (r + 4);
            });
        }
        int side = 2 * r + 1;
        int total = side * side;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < COLUMNS_PER_TICK; n++) {
            int idx = scanIndex;
            scanIndex = (scanIndex + 1) % (total + RESCAN_TICKS * COLUMNS_PER_TICK / 4);
            if (idx >= total) {
                continue; // 一回りしたら少し休む
            }
            int dx = idx % side - r;
            int dz = idx / side - r;
            if (dx * dx + dz * dz > r * r) {
                continue;
            }
            scanColumn(level, p, centerX + dx, centerZ + dz, pos);
        }
        int c = 0;
        for (List<Worm> list : COLUMNS.values()) {
            c += list.size();
        }
        count = c;
        // 驚く: 光を持ったプレイヤーの近くの点を暗く、離れたら戻す。
        boolean lit = holdsLight(player);
        float shyD2 = p.shyDistance() * p.shyDistance();
        float down = 1.0F / (p.shyIn() * 20.0F);
        float up = 1.0F / (p.shyOut() * 20.0F);
        for (List<Worm> list : COLUMNS.values()) {
            for (Worm w : list) {
                boolean near = lit && player.distanceToSqr(w.x, w.y, w.z) < shyD2;
                w.shy = near ? Math.max(p.shyLevel(), w.shy - down * (1.0F - p.shyLevel())) : Math.min(1.0F, w.shy + up);
            }
        }
        tickNanos += System.nanoTime() - t0;
        ticks++;
    }

    /** 列 (x, z) の目の高さ −below〜+above の天井を探し、確率で点を置く。 */
    private static void scanColumn(ClientLevel level, AtmosphereCaveLife.GlowParams p, int x, int z, BlockPos.MutableBlockPos pos) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        List<Worm> list = new ArrayList<>(2);
        if (!level.hasChunkAt(pos.set(x, centerY, z))) {
            COLUMNS.remove(key);
            return;
        }
        int lo = Math.max(level.getMinBuildHeight() + 1, centerY - p.below());
        int hi = Math.min(level.getMaxBuildHeight() - 2, centerY + p.above());
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, centerY, z)));
        boolean prevAir = false;
        for (int y = lo; y <= hi; y++) {
            BlockState s = level.getBlockState(pos.set(x, y, z));
            boolean air = s.isAir();
            if (prevAir && CaveMap.isRock(s)) {
                // y−1 が空気、y が岩 = 天井の下面。
                int ay = y - 1;
                if (level.getBrightness(LightLayer.SKY, pos.set(x, ay, z)) <= p.maxSky() && CaveMap.thickRoofAbove(level, x, y, z, 12)) {
                    double chance = overgrown ? p.chanceOvergrown() : p.chance();
                    if (wet(level, x, ay, z, pos)) {
                        chance *= p.wetBoost();
                    }
                    float h = hash(x, ay, z, 0);
                    if (h < chance) {
                        float h1 = hash(x, ay, z, 1);
                        float h2 = hash(x, ay, z, 2);
                        int threads = 1 + (int) (hash(x, ay, z, 3) * p.threads());
                        float[] len = new float[threads];
                        for (int k = 0; k < threads; k++) {
                            len[k] = p.threadLength() * (0.4F + 0.8F * hash(x, ay, z, 10 + k));
                        }
                        list.add(new Worm(x + 0.15 + 0.7 * h1, y - 0.01, z + 0.15 + 0.7 * h2, hash(x, ay, z, 4) * 100.0F, threads, len));
                    }
                }
            }
            prevAir = air;
        }
        if (list.isEmpty()) {
            COLUMNS.remove(key);
        } else {
            List<Worm> old = COLUMNS.get(key);
            if (old != null && old.size() == list.size()) {
                return; // 同じ点(座標から決まる)。驚いた度合いを残す
            }
            COLUMNS.put(key, list);
        }
    }

    /** 近く(水平 3・下 6 ブロック)に水があるか(湿った所)。粗く 9 点だけ見る。 */
    private static boolean wet(ClientLevel level, int x, int y, int z, BlockPos.MutableBlockPos pos) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                for (int dy = 1; dy <= 6; dy++) {
                    BlockState s = level.getBlockState(pos.set(x + dx, y - dy, z + dz));
                    if (s.getFluidState().is(FluidTags.WATER)) {
                        return true;
                    }
                    if (!s.isAir()) {
                        break;
                    }
                }
            }
        }
        return false;
    }

    private static boolean holdsLight(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack st = player.getItemInHand(hand);
            if (st.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().getLightEmission() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 描く(足し算・霧で薄めない。{@link LifeSprites#beginGlowNoFog} と end の間で呼ぶ)。 */
    static boolean any() {
        return count > 0;
    }

    static void render(float partialTick, double camX, double camY, double camZ, Vector3f left, Vector3f up) {
        if (count == 0) {
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereCaveLife.GlowParams p = DesignData.atmosphere().caveLife().glowworms();
        int cap = p.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        double t = Atmosphere.time(partialTick) / 20.0;
        int drawn = 0;
        float r2 = p.radius() * p.radius();
        for (List<Worm> list : COLUMNS.values()) {
            for (Worm w : list) {
                if (drawn >= cap) {
                    break;
                }
                double dx = w.x - camX;
                double dy = w.y - camY;
                double dz = w.z - camZ;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2 * 1.3) {
                    continue;
                }
                drawn++;
                double d = Math.sqrt(d2);
                // 明滅: 点ごとに位相・速さの違うゆっくりした波(0.55〜1.0)。
                float blink = 0.55F + 0.45F * (0.5F + 0.5F * Mth.sin((float) (t * Mth.TWO_PI / p.blinkSeconds() * (0.7 + 0.6 * frac(w.seed))) + w.seed));
                float a = p.alpha() * blink * w.shy * (float) Mth.clamp((p.radius() * 1.15 - d) / 4.0, 0.0, 1.0);
                // 遠くでも 1〜2 画素に見えるように、点の大きさは距離に比例(下限 1/24 ブロック)。
                float half = (float) Math.max(1.0 / 24.0, d * 0.0026);
                LifeSprites.dot(w.x, w.y, w.z, half, left, up, p.r(), p.g(), p.b(), a);
                LifeSprites.blob(w.x, w.y, w.z, half * 5.0F, 1.0F, left, up, p.r() * 0.8F, p.g() * 0.8F, p.b() * 0.8F, a * 0.3F);
                // 糸: 淡く光る粒の連なり(近くだけ。12 ブロックより先は点だけ)。
                if (d < 12.0) {
                    for (int k = 0; k < w.threads; k++) {
                        double ox = (frac(w.seed * 1.7F + k * 3.1F) - 0.5) * 0.25;
                        double oz = (frac(w.seed * 2.3F + k * 5.7F) - 0.5) * 0.25;
                        int beads = Math.max(2, (int) (w.len[k] / 0.06F));
                        for (int b = 1; b <= beads; b++) {
                            double f = b / (double) beads;
                            double sway = Math.sin(t * (0.6 + 0.3 * k) + w.seed + k) * 0.02 * f;
                            double by = w.y - f * w.len[k];
                            float ba = a * (0.55F - 0.35F * (float) f) * (b % 3 == 0 ? 1.0F : 0.6F);
                            LifeSprites.dot(w.x + ox + sway, by, w.z + oz + sway * 0.7, 1.0F / 80.0F, left, up, p.r(), p.g(), p.b(), ba);
                        }
                    }
                }
            }
        }
        renderNanos += System.nanoTime() - t0;
        renderFrames++;
    }

    /** 開発用(テストクライアントの {@code cavelife glow}): 真上の天井を探し、まわり半径 3 の天井に点を必ず置く。返事は置いた数。 */
    public static String devPlace(ClientLevel level, LocalPlayer player) {
        AtmosphereCaveLife.GlowParams p = DesignData.atmosphere().caveLife().glowworms();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int placed = 0;
        int px = Mth.floor(player.getX());
        int pz = Mth.floor(player.getZ());
        int py = Mth.floor(player.getEyeY());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = py; y < py + 24; y++) {
                    if (level.getBlockState(pos.set(px + dx, y, pz + dz)).isAir() && CaveMap.isRock(level.getBlockState(pos.set(px + dx, y + 1, pz + dz)))) {
                        int x = px + dx;
                        int z = pz + dz;
                        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
                        float[] len = {p.threadLength(), p.threadLength() * 0.7F, p.threadLength() * 1.2F};
                        List<Worm> list = new ArrayList<>();
                        list.add(new Worm(x + 0.5, y + 0.99, z + 0.5, (x * 31 + z) * 0.37F, Math.max(1, Math.min(3, p.threads())), len));
                        COLUMNS.put(key, list);
                        placed++;
                        break;
                    }
                }
            }
        }
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CaveGlowworms 置いた {}", placed);
        }
        return "置いた " + placed;
    }

    private static float hash(int x, int y, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 40) / (float) (1L << 24);
    }

    private static double frac(float v) {
        return v - Math.floor(v);
    }
}
