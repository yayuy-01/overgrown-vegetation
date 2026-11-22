package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.FallenLogBlock;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereCritters;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>見た目だけの小さな生き物</b>(2026-11-17 ユーザー決定。設定: この世界では鳥は消えていて、生き物は虫と爬虫類。環境生物だけでは作り切れない
 * 「生きている森」を、小さな虫が飛び交い這う様子で見せる)。
 *
 * <p><b>1 つの仕組み + 種の表</b>(節 {@code smallCritters}、{@link AtmosphereCritters})。エンティティにしない(当たり判定・ドロップ・通信なし。プレイヤーは触れない)。
 * 種は 10: イトトンボ・カゲロウの群れ・明かりに寄る蛾・ホタル・クモ/ザトウムシ・倒木の甲虫/シロアリ・ヤスデ(小)・小さなトカゲ・アメンボ・洞窟のゲジ
 * (洞窟のカマドウマは前からある {@link CaveCrickets} が受け持ち、数だけ升の賑わいに合わせる)。
 *
 * <ul>
 *     <li><b>湧き方</b>: 世界を 8 ブロックの升に分け、(升・種・時刻の区切り)のハッシュから「居るか・どこか・何匹か」を決める。時刻は
 *     {@code level.getGameTime()}(サーバーと同じ)なので、マルチでも同じ所に同じ虫が見える。動きも時刻の式。プレイヤーに合わせるのは反応と出し入れのなじませだけ。</li>
 *     <li><b>出る所</b>: 過繁茂だけ({@link ModBiomes#isFullyOvergrown}。繁茂段階・汚染の外には出さない)。洞窟の虫は真上の地表が過繁茂の所。</li>
 *     <li><b>数</b>: 種ごとに同時に出す所の上限(重さの段)。所ごとに決まったしきい値があり、升の賑わい({@link EcoCellsClient#liveliness} =
 *     台帳の環境生物の頭数 ÷ 満員)がそれを下回ると消える。狩りすぎた升は虫が減って静かになる。</li>
 *     <li><b>反応</b>: トカゲ・甲虫・クモ・ゲジは逃げる / 羽虫の群れ・アメンボ・イトトンボは割れてまた集まる / ヤスデは丸まる /
 *     明かりを持っていると蛾が手元に寄り、ホタルが近づく。</li>
 *     <li><b>食べられる</b>: 近くの環境生物(食物網 {@code prey})の口元に入った虫はその場で消える(クライアントだけ。仕組みへの効き目なし)。</li>
 *     <li><b>描き方</b>: {@link LifeSprites} の四角(頂点の色だけ・素材なし)。体は 1/16 ブロックの画素の単位で組み、色は置かれる所の明るさで暗くする。
 *     遠い物は 1 つの四角か点。ホタルの光だけ足し算。</li>
 * </ul>
 * ユーザーが決めたのは上の骨組み。時刻・数・距離・速さ・色・形はすべて仮決め。音は付けない(11-16)。
 */
public final class SmallCritters {

    public static final int DAMSELFLY = 0;
    public static final int MAYFLY = 1;
    public static final int MOTH = 2;
    public static final int FIREFLY = 3;
    public static final int SPIDER = 4;
    public static final int BEETLE = 5;
    public static final int MILLIPEDE = 6;
    public static final int LIZARD = 7;
    public static final int STRIDER = 8;
    public static final int CAVE = 9;
    private static final int N = 10;

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final int CELL = 8;
    private static final double PX = 1.0 / 16.0;
    /** 雨の間は出さない種(飛ぶ虫・日光浴のトカゲ)。 */
    private static final boolean[] RAIN_HIDES = {true, true, true, true, false, false, false, true, false, false};
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static final class Site {
        int species;
        long key;
        long seed;
        long startTick;
        long endTick;
        /** 拠り所(飛ぶ虫は群れの真ん中、這う虫は面の上の点)。 */
        double x;
        double y;
        double z;
        /** 面の 2 本の接線と外向き(這う虫)。 */
        double t1x = 1.0;
        double t1y;
        double t1z;
        double t2x;
        double t2y;
        double t2z = 1.0;
        double nx;
        double ny = 1.0;
        double nz;
        /** 倒木の軸の向き(面の中の角度)。 */
        double axis;
        int members = 1;
        int variant;
        /** 升の賑わいがこれを下回ると消える。 */
        float threshold;
        float live = 1.0F;
        float light = 1.0F;
        /** プレイヤーが近い度合い(0〜1)。 */
        float scare;
        /** 明かりに寄る度合い(ホタル)。 */
        float pull;
        long fleeAt = -1L;
        double fleeA;
        double fleeB;
        double fleeDa;
        double fleeDb;
        long backAt = -1L;
        /** 食べられた・水の外へ出た個体のビット。 */
        long gone;
        /** 丸まっている間に止めた分(秒)。 */
        double tOff;
    }

    private static final List<Site> SITES = new ArrayList<>();
    private static final LongOpenHashSet KNOWN = new LongOpenHashSet();
    private static final int[] ACTIVE = new int[N];
    private static final float[] WEIGHT = new float[N];
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final Int2LongOpenHashMap LAST_EAT = new Int2LongOpenHashMap();
    private static Map<EntityType<?>, Integer> predators;
    private static Map<String, String[]> predatorsFrom;
    private static int eats;
    /** 重さの計測(開発用の表示): tick と描画にかかった時間のならし(ナノ秒)と、直近のフレームで描いた四角の数。 */
    private static double tickNanos;
    private static double renderNanos;
    private static int quads;
    private static int lastQuads;
    /**
     * 食べられたときの知らせ(食べた Mob・食べられた種の名前)。環境生物の行動の段で「食べるアニメを鳴らす」をここへつなぐ(クライアントだけ。
     * 11-18 行動の段でつないだ: 環境生物(EcoLife)は止まっていれば eat を少し流す。
     */
    public static java.util.function.BiConsumer<Entity, String> eatHook = (e, name) -> {
        if (e instanceof com.yayuy.overgrownvegetation.ecosystem.life.EcoLife life) life.ecoClientAte();
    };
    private static boolean holdLight;
    /** 手元に寄る蛾。 */
    private static float handAlpha;
    private static int handCount;
    private static double handX;
    private static double handY;
    private static double handZ;
    private static boolean handOk;

    // 開発用(テストクライアントの critters 命令)
    private static boolean devBiome;
    private static boolean devTime;
    private static boolean devStill;
    private static int devOnly = -1;

    private SmallCritters() {
    }

    static void clear() {
        SITES.clear();
        KNOWN.clear();
        java.util.Arrays.fill(ACTIVE, 0);
        LAST_EAT.clear();
        handAlpha = 0.0F;
    }

    /** 明滅の光を描く物があるか(足し算の描画を始めるか)。 */
    static boolean glowing() {
        return ACTIVE[FIREFLY] > 0;
    }

    /** 升の賑わい(0〜1)。台帳の頭数を読む口はここ 1 か所。 */
    static float liveliness(double x, double z) {
        return EcoCellsClient.liveliness(x, z);
    }

    static String describe() {
        // 種ごとに「見えている所 / 居る所」(見えている = 升の賑わいがしきい値を上回り、時刻が合っている)。
        int[] shown = new int[N];
        for (Site s : SITES) {
            if (s.live > s.threshold && WEIGHT[s.species] > 0.5F) {
                shown[s.species]++;
            }
        }
        int all = 0;
        StringBuilder sb = new StringBuilder("小さな生き物");
        for (int i = 0; i < N; i++) {
            sb.append(' ').append(AtmosphereCritters.NAMES.get(i)).append(' ').append(shown[i]).append('/').append(ACTIVE[i]);
            all += shown[i];
        }
        sb.append(" 見えている所の計 ").append(all);
        return sb.append(String.format(" / 食べられた %d 手元の蛾 %.2f 知っている升 %d / 重さ tick %.1f µs 描画 %.1f µs 四角 %d", eats, handAlpha, KNOWN.size(),
                tickNanos / 1000.0, renderNanos / 1000.0, lastQuads)).toString();
    }

    // ───────────── 開発用 ─────────────

    /** 開発用: biome = 過繁茂でなくても出す / time = 時刻・雨を見ない / still = 逃げない(アップの写真用)。 */
    public static String devForce(String what, boolean on) {
        switch (what) {
            case "biome" -> devBiome = on;
            case "time" -> devTime = on;
            case "still" -> devStill = on;
            default -> {
                return "biome / time / still";
            }
        }
        if (!what.equals("still")) {
            clear();
        }
        return what + " " + on;
    }

    /** 開発用: その種だけ出す(all で戻す)。 */
    public static String devOnly(String name) {
        devOnly = AtmosphereCritters.NAMES.indexOf(name);
        clear();
        return devOnly < 0 ? "全部" : name;
    }

    /** 開発用: その種のいちばん近い所。 */
    public static String devNearest(String name, double x, double y, double z) {
        int sp = AtmosphereCritters.NAMES.indexOf(name);
        Site best = null;
        double bd = Double.MAX_VALUE;
        for (Site s : SITES) {
            double d = (s.x - x) * (s.x - x) + (s.y - y) * (s.y - y) + (s.z - z) * (s.z - z);
            if ((sp < 0 || s.species == sp) && d < bd) {
                bd = d;
                best = s;
            }
        }
        return best == null ? "なし " + describe() : String.format("%s %.2f %.2f %.2f 面の外向き %.0f %.0f %.0f 匹 %d 種類 %d しきい値 %.2f 賑わい %.2f 距離 %.1f",
                AtmosphereCritters.NAMES.get(best.species), best.x, best.y, best.z, best.nx, best.ny, best.nz, best.members, best.variant, best.threshold,
                best.live, Math.sqrt(bd));
    }

    public static String devDescribe() {
        return describe();
    }

    // ───────────── ハッシュ ─────────────

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** seed と番号 k から 0〜1。 */
    private static float h01(long seed, int k) {
        return (mix(seed + k * 0x9E3779B97F4A7C15L) >>> 40) / 16777216.0F;
    }

    /** −1〜1。 */
    private static double hs(long seed, int k) {
        return h01(seed, k) * 2.0 - 1.0;
    }

    // ───────────── 毎 tick ─────────────

    static void tick(ClientLevel level, LocalPlayer player) {
        long t0 = System.nanoTime();
        tick0(level, player);
        tickNanos += (System.nanoTime() - t0 - tickNanos) * 0.02;
    }

    private static void tick0(ClientLevel level, LocalPlayer player) {
        AtmosphereCritters.Params p = DesignData.atmosphere().smallCritters();
        if (!VisualQuality.critters() || !p.enabled()) {
            clear();
            return;
        }
        long now = level.getGameTime();
        float hour = (float) (Math.floorMod(level.getDayTime(), 24000L) / 1000.0 + 6.0) % 24.0F;
        float rain = level.getRainLevel(1.0F);
        for (int i = 0; i < N; i++) {
            AtmosphereCritters.Species sp = p.species()[i];
            float w = !sp.enabled() || devOnly >= 0 && devOnly != i ? 0.0F : devTime ? 1.0F : sp.weight(hour, p.fadeHours()) * (RAIN_HIDES[i] ? 1.0F - rain : 1.0F);
            WEIGHT[i] = w;
        }
        holdLight = holdsLight(player);
        Vec3 eye = player.getEyePosition();
        double range = p.range();
        double t = now / 20.0;
        for (int i = SITES.size() - 1; i >= 0; i--) {
            Site s = SITES.get(i);
            double d2 = eye.distanceToSqr(s.x, s.y, s.z);
            double flat2 = (s.x - eye.x) * (s.x - eye.x) + (s.z - eye.z) * (s.z - eye.z); // 洞窟の虫は深い所にも居るので、外すのは水平の距離で
            if (now >= s.endTick || flat2 > (range + 12.0) * (range + 12.0)) {
                SITES.remove(i);
                ACTIVE[s.species]--;
                if (now < s.endTick) {
                    KNOWN.remove(s.key); // 離れただけ: 戻ってきたらまた出す
                }
                continue;
            }
            s.live += (liveliness(s.x, s.z) - s.live) * 0.05F;
            if ((now + s.key & 15L) == 0L) {
                sampleLight(level, s);
                if (s.species == STRIDER) {
                    checkWater(level, s, t);
                }
            }
            react(level, p, s, player, eye, d2, now, t);
        }
        scan(level, p, player, now);
        if ((now & 3L) == 0L) {
            eat(level, p, eye, now, t);
        }
        tickHand(level, p, player, now);
        if (KNOWN.size() > 8000) {
            KNOWN.clear();
            for (Site s : SITES) {
                KNOWN.add(s.key);
            }
        }
    }

    private static boolean holdsLight(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack st = player.getItemInHand(hand);
            if (st.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().getLightEmission() > 0) {
                return true;
            }
        }
        return CaveLights.heldCount() > 0;
    }

    private static void sampleLight(ClientLevel level, Site s) {
        POS.set(Mth.floor(s.x + s.nx * 0.3), Mth.floor(s.y + s.ny * 0.3), Mth.floor(s.z + s.nz * 0.3));
        float sky = level.getBrightness(LightLayer.SKY, POS) / 15.0F * level.getSkyDarken(1.0F);
        float block = level.getBrightness(LightLayer.BLOCK, POS) / 15.0F;
        float floor = s.species == CAVE ? 0.3F : 0.44F; // 下限: 暗がりで置かれた面より暗く沈まない(TrunkLizards と同じ考え)
        s.light = floor + (1.0F - floor) * Math.max(sky, block);
    }

    /** アメンボ: 水の外へ出た個体を隠す。 */
    private static void checkWater(ClientLevel level, Site s, double t) {
        long dry = 0L;
        for (int i = 0; i < s.members; i++) {
            flyer(s, i, t, null, 0.0F);
            if (level.getFluidState(POS.set(Mth.floor(mx), Mth.floor(s.y - 0.5), Mth.floor(mz))).isEmpty()
                    || !level.getFluidState(POS.set(Mth.floor(mx), Mth.floor(s.y + 0.5), Mth.floor(mz))).isEmpty()) {
                dry |= 1L << i;
            }
        }
        s.gone = s.gone & EATEN_MASK | dry;
    }

    /** アメンボの「水の外」は下位 32 ビット、食べられたのは上位 32 ビット。 */
    private static final long EATEN_MASK = 0xFFFFFFFF00000000L;

    private static boolean gone(Site s, int i) {
        return (s.gone >> i & 1L) != 0L || (s.gone >> (32 + i) & 1L) != 0L;
    }

    /** 近づいたときの反応(種ごと)。 */
    private static void react(ClientLevel level, AtmosphereCritters.Params p, Site s, LocalPlayer player, Vec3 eye, double d2, long now, double t) {
        float react = p.species()[s.species].react();
        boolean near = !devStill && react > 0.0F && d2 < (react + (s.species <= FIREFLY || s.species == STRIDER ? 2.5 : 0.0)) * (react + (s.species <= FIREFLY || s.species == STRIDER ? 2.5 : 0.0));
        s.scare += ((near ? 1.0F : 0.0F) - s.scare) * (near ? 0.35F : s.species == MILLIPEDE ? 0.012F : 0.03F);
        if (s.species == MILLIPEDE && s.scare > 0.05F) {
            s.tOff -= 0.05; // 丸まっている間は歩みを止める
        }
        if (s.species == FIREFLY) {
            boolean lure = holdLight && d2 < p.lightPullDistance() * p.lightPullDistance();
            s.pull += ((lure ? 1.0F : 0.0F) - s.pull) * 0.02F;
        }
        boolean flees = s.species == LIZARD || s.species == SPIDER || s.species == CAVE || s.species == BEETLE && s.variant == 0;
        if (!flees) {
            return;
        }
        if (s.fleeAt < 0L && near && now >= s.backAt) {
            crawl(s, 0, t, now);
            s.fleeA = cA;
            s.fleeB = cB;
            // プレイヤーから離れる向きを面の上へ落とす。幹・壁は上下だけ、倒木は軸に沿って(面からはみ出さない)。
            double ax = s.x - eye.x;
            double ay = s.y - eye.y;
            double az = s.z - eye.z;
            double da = ax * s.t1x + ay * s.t1y + az * s.t1z;
            double db = ax * s.t2x + ay * s.t2y + az * s.t2z;
            if (s.species == SPIDER || s.species == CAVE) {
                da = 0.0;
                db = db >= 0.0 ? 1.0 : -1.0;
            } else if (s.species == BEETLE) {
                double along = da * Math.cos(s.axis) + db * Math.sin(s.axis) >= 0.0 ? 1.0 : -1.0;
                da = Math.cos(s.axis) * along;
                db = Math.sin(s.axis) * along;
            }
            double n = Math.sqrt(da * da + db * db);
            if (n < 1.0e-4) {
                da = 1.0;
                db = 0.0;
                n = 1.0;
            }
            s.fleeDa = da / n;
            s.fleeDb = db / n;
            s.fleeAt = now;
        } else if (s.fleeAt >= 0L && now - s.fleeAt > Math.round(p.respawnSeconds() * 20.0F) && d2 > (react + 2.0) * (react + 2.0)) {
            s.fleeAt = -1L;
            s.backAt = now; // ここから 1 秒でなじませて戻す
        }
    }

    // ───────────── 湧かせる ─────────────

    private static void scan(ClientLevel level, AtmosphereCritters.Params p, LocalPlayer player, long now) {
        int sp = (int) (now % N);
        if (WEIGHT[sp] <= 0.0F) {
            return;
        }
        AtmosphereCritters.Species spec = p.species()[sp];
        int cap = spec.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        int slot = Math.max(200, spec.slotTicks());
        int pcx = Math.floorDiv(Mth.floor(player.getX()), CELL);
        int pcz = Math.floorDiv(Mth.floor(player.getZ()), CELL);
        int rc = Mth.ceil(p.range() / CELL);
        int resolves = 0;
        // 近い升から(上限に当たったとき、近くの虫が先に出る)。
        for (int ring = 0; ring <= rc; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    if (ACTIVE[sp] >= cap || resolves >= 2) {
                        return;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    double ccx = cx * CELL + 4.0 - player.getX();
                    double ccz = cz * CELL + 4.0 - player.getZ();
                    if (ccx * ccx + ccz * ccz > (p.range() + 2.0) * (p.range() + 2.0)) {
                        continue; // 出す距離の外(升の端が range + 8 を超えない: 出してすぐ外すのを防ぐ)
                    }
                    long cellSeed = mix(cx * 0x9E3779B97F4A7C15L + cz) ^ mix(sp * 0xD1B54A32D192ED03L + 77L);
                    long phase = Math.floorMod(cellSeed, (long) slot);
                    long bucket = Math.floorDiv(now + phase, (long) slot);
                    long key = mix(cellSeed + bucket * 0x2545F4914F6CDD1DL);
                    if (KNOWN.contains(key)) {
                        continue;
                    }
                    if (!level.hasChunkAt(POS.set(cx * CELL + 4, 0, cz * CELL + 4))) {
                        continue;
                    }
                    long seed = mix(key + 1L);
                    KNOWN.add(key);
                    if (h01(seed, 0) >= spec.chance()) {
                        continue;
                    }
                    resolves++;
                    Site s = new Site();
                    s.species = sp;
                    s.key = key;
                    s.seed = seed;
                    s.startTick = bucket * slot - phase;
                    s.endTick = s.startTick + slot;
                    s.threshold = h01(seed, 1) * 0.94F + 0.03F;
                    if (resolve(level, s, cx, cz)) {
                        s.live = liveliness(s.x, s.z);
                        sampleLight(level, s);
                        SITES.add(s);
                        ACTIVE[sp]++;
                    }
                }
            }
        }
    }

    private static boolean overgrown(ClientLevel level, int x, int y, int z) {
        return devBiome || ModBiomes.isFullyOvergrown(level.getBiome(POS.set(x, y, z)));
    }

    private static int top(ClientLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    /** 所を世界に当てはめる(プレイヤーの位置は使わない: 同じ世界なら誰が見ても同じ所)。 */
    private static boolean resolve(ClientLevel level, Site s, int cx, int cz) {
        int x0 = cx * CELL;
        int z0 = cz * CELL;
        long seed = s.seed;
        switch (s.species) {
            case DAMSELFLY, MAYFLY, STRIDER -> {
                for (int k = 0; k < 6; k++) {
                    int x = x0 + (int) (h01(seed, 10 + k * 2) * CELL);
                    int z = z0 + (int) (h01(seed, 11 + k * 2) * CELL);
                    int wy = WaterSigns.waterSurface(level, x, top(level, x, z), z, 40, POS);
                    if (wy == Integer.MIN_VALUE || !overgrown(level, x, wy, z)) {
                        continue;
                    }
                    s.x = x + 0.5;
                    s.z = z + 0.5;
                    if (s.species == STRIDER) {
                        s.y = wy + 0.889 + 0.012;
                        s.members = 3 + (int) (h01(seed, 30) * 3.0F);
                    } else if (s.species == MAYFLY) {
                        s.y = wy + 2.0;
                        s.members = 12 + (int) (h01(seed, 30) * 9.0F);
                    } else {
                        s.y = wy + 1.3 + h01(seed, 31) * 0.6;
                        s.members = 1 + (int) (h01(seed, 30) * 2.0F);
                    }
                    return true;
                }
                return false;
            }
            case MOTH -> {
                for (int k = 0; k < 4; k++) {
                    int x = x0 + (int) (h01(seed, 10 + k * 3) * CELL);
                    int z = z0 + (int) (h01(seed, 11 + k * 3) * CELL);
                    int fy = WaterSigns.floorBelow(level, x, top(level, x, z), z, 48, POS);
                    if (fy == Integer.MIN_VALUE) {
                        continue;
                    }
                    int y = fy + (int) (h01(seed, 12 + k * 3) * 4.0F);
                    int b = level.getBrightness(LightLayer.BLOCK, POS.set(x, y, z));
                    if (b < 8) {
                        continue;
                    }
                    // 明るい方へたどって明かりのそばへ。
                    for (int step = 0; step < 12; step++) {
                        int bestB = b;
                        Direction best = null;
                        for (Direction d : Direction.values()) {
                            POS.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                            if (level.getBlockState(POS).canOcclude()) {
                                continue;
                            }
                            int nb = level.getBrightness(LightLayer.BLOCK, POS);
                            if (nb > bestB) {
                                bestB = nb;
                                best = d;
                            }
                        }
                        if (best == null) {
                            break;
                        }
                        x += best.getStepX();
                        y += best.getStepY();
                        z += best.getStepZ();
                        b = bestB;
                    }
                    if (b < 13 || !overgrown(level, x, y, z)) {
                        continue;
                    }
                    s.x = x + 0.5;
                    s.y = y + 0.6;
                    s.z = z + 0.5;
                    s.members = 2 + (int) (h01(seed, 30) * 3.0F);
                    return true;
                }
                return false;
            }
            case FIREFLY -> {
                for (int k = 0; k < 3; k++) {
                    int x = x0 + (int) (h01(seed, 10 + k * 2) * CELL);
                    int z = z0 + (int) (h01(seed, 11 + k * 2) * CELL);
                    int fy = WaterSigns.floorBelow(level, x, top(level, x, z), z, 48, POS);
                    if (fy == Integer.MIN_VALUE || !level.getFluidState(POS.set(x, fy, z)).isEmpty() || level.getBlockState(POS.set(x, fy + 1, z)).blocksMotion()
                            || !overgrown(level, x, fy, z)) {
                        continue;
                    }
                    s.x = x + 0.5;
                    s.y = fy + 1.3;
                    s.z = z + 0.5;
                    s.members = 5 + (int) (h01(seed, 30) * 5.0F);
                    return true;
                }
                return false;
            }
            case MILLIPEDE -> {
                for (int k = 0; k < 4; k++) {
                    int x = x0 + (int) (h01(seed, 10 + k * 2) * CELL);
                    int z = z0 + (int) (h01(seed, 11 + k * 2) * CELL);
                    int fy = WaterSigns.floorBelow(level, x, top(level, x, z), z, 48, POS);
                    if (fy == Integer.MIN_VALUE) {
                        continue;
                    }
                    BlockState below = level.getBlockState(POS.set(x, fy - 1, z));
                    if (!(below.is(BlockTags.DIRT) || below.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get()) || below.is(ModBlocks.OVERGROWN_DIRT.get())
                            || below.is(ModBlocks.PEAT.get()))) {
                        continue;
                    }
                    BlockState at = level.getBlockState(POS.set(x, fy, z));
                    if (!at.getFluidState().isEmpty() || !overgrown(level, x, fy, z)) {
                        continue;
                    }
                    s.x = x + 0.5;
                    s.y = fy + lowTop(level, at, POS.set(x, fy, z)) + 0.012;
                    s.z = z + 0.5;
                    return true;
                }
                return false;
            }
            case SPIDER, BEETLE -> {
                int fyc = WaterSigns.floorBelow(level, x0 + 4, top(level, x0 + 4, z0 + 4), z0 + 4, 48, POS);
                if (fyc == Integer.MIN_VALUE) {
                    return false;
                }
                int start = (int) (h01(seed, 10) * 64.0F);
                int turn = (int) (h01(seed, 11) * 4.0F);
                for (int i = 0; i < 64; i++) {
                    int col = start + i * 37 & 63;
                    int x = x0 + (col & 7);
                    int z = z0 + (col >> 3);
                    for (int dy = s.species == BEETLE ? -1 : 0; dy <= (s.species == BEETLE ? 2 : 3); dy++) {
                        int y = fyc + dy;
                        BlockState st = level.getBlockState(POS.set(x, y, z));
                        if (s.species == BEETLE) {
                            if (!st.is(ModBlocks.FALLEN_LOG.get()) || st.getValue(FallenLogBlock.ROOT)) {
                                continue;
                            }
                            BlockState above = level.getBlockState(POS.set(x, y + 1, z));
                            if (above.blocksMotion() || !above.getFluidState().isEmpty() || !overgrown(level, x, y, z)) {
                                continue;
                            }
                            s.x = x + 0.5;
                            s.y = y + 1.012;
                            s.z = z + 0.5;
                            s.axis = st.getValue(FallenLogBlock.AXIS) == Direction.Axis.X ? 0.0 : Math.PI * 0.5;
                            s.variant = h01(seed, 32) < 0.4F ? 1 : 0; // 1 = シロアリの列
                            s.members = s.variant == 1 ? 8 : 1; // 甲虫は 1 匹(逃げる向きと位置を所で 1 つ持つため)
                            return true;
                        }
                        if (!(st.is(BlockTags.LOGS) || st.is(ModTags.CLIMBABLE_TRUNK) || GiantSilhouettes.isWood(st)) || !st.canOcclude()) {
                            continue;
                        }
                        for (int k = 0; k < 4; k++) {
                            Direction d = SIDES[k + turn & 3];
                            if (!level.getBlockState(POS.set(x + d.getStepX(), y, z + d.getStepZ())).isAir() || !overgrown(level, x, y, z)) {
                                continue;
                            }
                            wall(s, x, y, z, d);
                            s.variant = h01(seed, 32) < 0.5F ? 1 : 0; // 1 = ザトウムシ
                            return true;
                        }
                    }
                }
                return false;
            }
            case LIZARD -> {
                int start = (int) (h01(seed, 10) * 64.0F);
                for (int i = 0; i < 64; i++) {
                    int col = start + i * 37 & 63;
                    int x = x0 + (col & 7);
                    int z = z0 + (col >> 3);
                    int y = top(level, x, z);
                    BlockState below = level.getBlockState(POS.set(x, y - 1, z));
                    // 日の当たる岩。岩の少ない森でも見えるよう、日の当たる倒木の上も【仮】。
                    if (!(below.is(BlockTags.BASE_STONE_OVERWORLD) || below.is(net.minecraftforge.common.Tags.Blocks.STONES)
                            || below.is(net.minecraftforge.common.Tags.Blocks.COBBLESTONES) || below.is(ModBlocks.FALLEN_LOG.get())) || !below.canOcclude()
                            || !level.getBlockState(POS.set(x, y, z)).isAir() || level.getBrightness(LightLayer.SKY, POS) < 14 || !overgrown(level, x, y, z)) {
                        continue;
                    }
                    s.x = x + 0.5;
                    s.y = y + 0.012;
                    s.z = z + 0.5;
                    return true;
                }
                return false;
            }
            case CAVE -> {
                int x = x0 + (int) (h01(seed, 10) * CELL);
                int z = z0 + (int) (h01(seed, 11) * CELL);
                int ty = top(level, x, z);
                int fy = WaterSigns.floorBelow(level, x, ty, z, 48, POS);
                if (fy == Integer.MIN_VALUE) {
                    fy = ty;
                }
                if (!overgrown(level, x, fy, z)) {
                    return false;
                }
                int turn = (int) (h01(seed, 12) * 4.0F);
                int minY = Math.max(level.getMinBuildHeight() + 1, fy - 64);
                for (int y = fy - 4; y > minY; y--) {
                    if (!level.getBlockState(POS.set(x, y, z)).isAir() || level.getBrightness(LightLayer.SKY, POS) > 0) {
                        continue;
                    }
                    for (int k = 0; k < 4; k++) {
                        Direction d = SIDES[k + turn & 3];
                        // 面は「岩の外向き」: 岩は (x, y, z) の d の逆側。
                        if (CaveMap.isRock(level.getBlockState(POS.set(x - d.getStepX(), y, z - d.getStepZ())))) {
                            wall(s, x - d.getStepX(), y, z - d.getStepZ(), d);
                            return true;
                        }
                    }
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** ブロック (x, y, z) の d の面の真ん中へ置く(接線 1 = 水平、接線 2 = 上)。 */
    private static void wall(Site s, int x, int y, int z, Direction d) {
        s.nx = d.getStepX();
        s.ny = 0.0;
        s.nz = d.getStepZ();
        s.t1x = -s.nz;
        s.t1y = 0.0;
        s.t1z = s.nx;
        s.t2x = 0.0;
        s.t2y = 1.0;
        s.t2z = 0.0;
        s.x = x + 0.5 + s.nx * 0.512;
        s.y = y + 0.5;
        s.z = z + 0.5 + s.nz * 0.512;
    }

    /** 林床の上の低い物(苔の層・落ち葉など高さ 1/4 以下)の上面。背の高い草は 0(根元を這う)。 */
    private static double lowTop(ClientLevel level, BlockState at, BlockPos pos) {
        if (at.isAir()) {
            return 0.0;
        }
        VoxelShape shape = at.getShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0;
        }
        double top = shape.max(Direction.Axis.Y);
        return top <= 0.26 ? top : 0.0;
    }

    // ───────────── 動き(時刻の式。誰が見ても同じ) ─────────────

    private static double mx;
    private static double my;
    private static double mz;
    /** 向き(水平の角度)。 */
    private static double mh;
    /** ホタルの光の強さ(0〜1)。 */
    private static float mlit;

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double smooth(double v) {
        double c = Mth.clamp(v, 0.0, 1.0);
        return c * c * (3.0 - 2.0 * c);
    }

    /** 飛ぶ虫・アメンボの個体 i の位置を mx・my・mz に。player があれば「割れる」「寄る」を足す。 */
    private static void flyer(Site s, int i, double t, Vec3 player, float lightPull) {
        long sd = s.seed + i * 0x632BE59BD9B4E019L;
        mlit = 0.0F;
        switch (s.species) {
            case DAMSELFLY -> {
                double per = 1.6 + 0.8 * h01(sd, 1);
                double u = t / per + h01(sd, 2);
                long k = (long) Math.floor(u);
                double e = smooth((u - k) / 0.3); // さっと動いて、あとは止まって浮く
                int ka = (int) (k & 0xFFFFF) * 3;
                double ax = hs(sd, 100 + ka);
                double ay = hs(sd, 101 + ka);
                double az = hs(sd, 102 + ka);
                double bx = hs(sd, 103 + ka);
                double by = hs(sd, 104 + ka);
                double bz = hs(sd, 105 + ka);
                mx = s.x + Mth.lerp(e, ax, bx) * 2.2;
                my = s.y + Mth.lerp(e, ay, by) * 0.35 + 0.02 * Math.sin(t * 5.0 + i);
                mz = s.z + Mth.lerp(e, az, bz) * 2.2;
                mh = Math.atan2(bz - az, bx - ax);
            }
            case MAYFLY -> {
                double ang = h01(sd, 1) * Mth.TWO_PI;
                double rad = Math.sqrt(h01(sd, 2)) * 1.4;
                double per = 1.5 + h01(sd, 3);
                double f = frac(t / per + h01(sd, 4));
                double hop = f < 0.3 ? f / 0.3 : 1.0 - (f - 0.3) / 0.7; // 素早く上がり、ゆっくり降りる(カゲロウの舞い)
                mx = s.x + Math.cos(ang) * rad + 0.25 * Math.sin(t * 0.31 + i);
                my = s.y - 1.0 + h01(sd, 5) * 1.3 + 0.7 * hop;
                mz = s.z + Math.sin(ang) * rad + 0.25 * Math.cos(t * 0.27 + i * 1.7);
                mh = 0.0;
            }
            case MOTH -> {
                double dir = (i & 1) == 0 ? 1.0 : -1.0;
                double ang = t * (2.2 + 1.5 * h01(sd, 1)) * dir + h01(sd, 2) * Mth.TWO_PI;
                double rad = 0.5 + 0.25 * Math.sin(t * 1.3 + i * 2.1);
                mx = s.x + Math.cos(ang) * rad;
                my = s.y + 0.25 * Math.sin(t * 2.9 + i) + 0.12 * Math.sin(t * 7.1 + i * 2.0);
                mz = s.z + Math.sin(ang) * rad;
                mh = ang + dir * Math.PI * 0.5;
            }
            case FIREFLY -> {
                mx = s.x + 2.2 * Math.sin(t * 0.11 * (0.7 + h01(sd, 1)) + h01(sd, 2) * Mth.TWO_PI);
                mz = s.z + 2.2 * Math.cos(t * 0.09 * (0.7 + h01(sd, 3)) + h01(sd, 4) * Mth.TWO_PI);
                my = s.y + 0.5 * Math.sin(t * 0.17 + h01(sd, 5) * Mth.TWO_PI);
                double per = 2.6 + 2.2 * h01(sd, 6);
                double f = frac(t / per + h01(sd, 7));
                double rise = f < 0.3 ? f / 0.3 : Math.max(0.0, 1.0 - (f - 0.3) / 0.35);
                my += 0.22 * rise; // 光りながらすっと上がる
                mlit = f < 0.3 ? (float) Math.sin(Math.PI * f / 0.3) : 0.0F;
                mh = 0.0;
                if (player != null && lightPull > 0.001F) {
                    mx += (player.x - mx) * lightPull;
                    my += (player.y - 0.3 - my) * lightPull;
                    mz += (player.z - mz) * lightPull;
                    double dx = mx - player.x;
                    double dz = mz - player.z;
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d < 1.2) { // 顔には張り付かない
                        double a = d < 1.0e-3 ? h01(sd, 8) * Mth.TWO_PI : Math.atan2(dz, dx);
                        mx = player.x + Math.cos(a) * 1.2;
                        mz = player.z + Math.sin(a) * 1.2;
                    }
                }
                return;
            }
            default -> { // アメンボ: すっと滑っては止まる
                double per = 0.9 + 0.9 * h01(sd, 1);
                double u = t / per + h01(sd, 2);
                long k = (long) Math.floor(u);
                double c = 1.0 - Mth.clamp((u - k) / 0.3, 0.0, 1.0);
                double e = 1.0 - c * c;
                int ka = (int) (k & 0xFFFFF) * 2;
                double ax = hs(sd, 100 + ka);
                double az = hs(sd, 101 + ka);
                double bx = hs(sd, 102 + ka);
                double bz = hs(sd, 103 + ka);
                mx = s.x + Mth.lerp(e, ax, bx) * 1.3;
                my = s.y;
                mz = s.z + Mth.lerp(e, az, bz) * 1.3;
                mh = Math.atan2(bz - az, bx - ax);
            }
        }
        // 割れてまた集まる: プレイヤーのまわり react ブロックから外へ押し出す。
        if (player != null && s.scare > 0.01F) {
            float react = DesignData.atmosphere().smallCritters().species()[s.species].react();
            double dx = mx - player.x;
            double dz = mz - player.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < react && Math.abs(my - player.y) < 3.0) {
                double a = d < 1.0e-3 ? h01(sd, 8) * Mth.TWO_PI : Math.atan2(dz, dx);
                double push = (react - d) * s.scare;
                mx += Math.cos(a) * push;
                mz += Math.sin(a) * push;
            }
        }
    }

    private static double cA;
    private static double cB;
    /** 面の中の向き。 */
    private static double cH;
    private static boolean cMove;
    private static float cAlpha;
    /** 歩いた道のり(脚の振りの位相)。 */
    private static double cWalk;

    /** 這う虫の個体 i の面の上の位置(ブロック)と向き。 */
    private static void crawl(Site s, int i, double tIn, double nowTicks) {
        long sd = s.seed + i * 0x632BE59BD9B4E019L;
        double t = tIn + s.tOff;
        cAlpha = 1.0F;
        cWalk = 0.0;
        if (s.species == BEETLE && s.variant == 1) {
            // シロアリの列: 倒木の軸に沿って行進。驚くと列が乱れる。
            double u = frac(t * 0.03 + (double) i / s.members);
            double along = u * 0.9 - 0.45;
            double side = 0.02 * Math.sin(t * 5.0 + i) + s.scare * 0.2 * Math.sin(i * 2.4 + t * 9.0);
            cA = along * Math.cos(s.axis) - side * Math.sin(s.axis);
            cB = along * Math.sin(s.axis) + side * Math.cos(s.axis);
            cH = s.axis;
            cMove = true;
            cWalk = t * 6.0;
            cAlpha = (float) Math.min(1.0, Math.min(u, 1.0 - u) * 8.0);
            return;
        }
        if (s.species == LIZARD) {
            cA = hs(sd, 1) * 0.2;
            cB = hs(sd, 2) * 0.2;
            cH = h01(sd, 3) * Mth.TWO_PI;
            cMove = false;
        } else {
            double per;
            double mf;
            double box = 0.36;
            switch (s.species) {
                case MILLIPEDE -> {
                    per = 7.0;
                    mf = 0.7;
                }
                case SPIDER -> {
                    per = 12.0;
                    mf = 0.12;
                    box = 0.3;
                }
                case BEETLE -> {
                    per = 5.0;
                    mf = 0.5;
                    box = 0.3;
                }
                default -> {
                    per = 2.6;
                    mf = 0.14;
                }
            }
            double u = t / per + h01(sd, 4);
            long k = (long) Math.floor(u);
            double f = u - k;
            double e = smooth(f / mf);
            int ka = (int) (k & 0xFFFFF) * 2;
            double pa = hs(sd, 98 + ka);
            double pb = hs(sd, 99 + ka);
            double ax = hs(sd, 100 + ka);
            double ab = hs(sd, 101 + ka);
            double bx = hs(sd, 102 + ka);
            double bb = hs(sd, 103 + ka);
            cA = Mth.lerp(e, ax, bx) * box;
            cB = Mth.lerp(e, ab, bb) * box;
            double h0 = Math.atan2(ab - pb, ax - pa);
            double h1 = Math.atan2(bb - ab, bx - ax);
            double turn = smooth(f / (mf * 0.25));
            cH = h0 + Mth.wrapDegrees(Math.toDegrees(h1 - h0)) * Math.PI / 180.0 * turn;
            cMove = f < mf;
            cWalk = (k + e) * Math.hypot(bx - ax, bb - ab) * box * 16.0;
        }
        if (s.fleeAt >= 0L) {
            double q = (nowTicks - s.fleeAt) / 20.0;
            double speed = s.species == LIZARD ? 3.2 : s.species == CAVE ? 3.6 : 2.0;
            double dist = speed * Math.min(q, 0.45);
            cA = s.fleeA + s.fleeDa * dist;
            cB = s.fleeB + s.fleeDb * dist;
            cH = Math.atan2(s.fleeDb, s.fleeDa);
            cMove = true;
            cWalk = dist * 16.0;
            cAlpha = (float) Mth.clamp(1.0 - (q - 0.12) / 0.3, 0.0, 1.0);
        } else if (s.backAt >= 0L) {
            cAlpha = (float) Mth.clamp((nowTicks - s.backAt) / 20.0, 0.0, 1.0);
        }
    }

    // ───────────── 食べられる ─────────────

    private static Map<EntityType<?>, Integer> predators(AtmosphereCritters.Params p) {
        if (predators == null || predatorsFrom != p.prey()) {
            Map<EntityType<?>, Integer> m = new HashMap<>();
            for (Map.Entry<String, String[]> e : p.prey().entrySet()) {
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(OvergrownVegetation.id(e.getKey()));
                if (type.isEmpty() || e.getValue() == null) {
                    continue;
                }
                int mask = 0;
                for (String name : e.getValue()) {
                    int idx = AtmosphereCritters.NAMES.indexOf(name);
                    if (idx >= 0) {
                        mask |= 1 << idx;
                    }
                }
                m.put(type.get(), mask);
            }
            predators = m;
            predatorsFrom = p.prey();
        }
        return predators;
    }

    /** 近くの環境生物の口元に入った虫を消す(クライアントだけ。台帳には響かない)。 */
    private static void eat(ClientLevel level, AtmosphereCritters.Params p, Vec3 eye, long now, double t) {
        if (SITES.isEmpty()) {
            return;
        }
        Map<EntityType<?>, Integer> pred = predators(p);
        if (pred.isEmpty()) {
            return;
        }
        long cooldown = Math.round(p.eatCooldownSeconds() * 20.0F);
        for (Entity e : level.entitiesForRendering()) {
            Integer mask = pred.get(e.getType());
            if (mask == null || !e.isAlive() || e.distanceToSqr(eye) > 48.0 * 48.0 || now - LAST_EAT.getOrDefault(e.getId(), -100000L) < cooldown) {
                continue;
            }
            float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
            double half = e.getBbWidth() * 0.5;
            double ex = e.getX() - Mth.sin(yaw * Mth.DEG_TO_RAD) * half;
            double ey = e.getY() + e.getBbHeight() * 0.4;
            double ez = e.getZ() + Mth.cos(yaw * Mth.DEG_TO_RAD) * half;
            double reach = p.eatReach() + half;
            outer:
            for (Site s : SITES) {
                if ((mask >> s.species & 1) == 0 || siteAlpha(s, now) < 0.3F) {
                    continue;
                }
                double sr = reach + 3.0;
                if ((s.x - ex) * (s.x - ex) + (s.y - ey) * (s.y - ey) + (s.z - ez) * (s.z - ez) > sr * sr) {
                    continue;
                }
                for (int i = 0; i < s.members; i++) {
                    if (gone(s, i)) {
                        continue;
                    }
                    memberWorld(s, i, t, now);
                    if ((mx - ex) * (mx - ex) + (my - ey) * (my - ey) + (mz - ez) * (mz - ez) <= reach * reach) {
                        s.gone |= 1L << (32 + i);
                        LAST_EAT.put(e.getId(), now);
                        eats++;
                        if (eatHook != null) {
                            eatHook.accept(e, AtmosphereCritters.NAMES.get(s.species));
                        }
                        if (DEV_LOG) {
                            OvergrownVegetation.LOGGER.info("SmallCritters 食べられた {} ← {} 位置 {} {} {}", AtmosphereCritters.NAMES.get(s.species),
                                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), String.format("%.1f", mx), String.format("%.1f", my), String.format("%.1f", mz));
                        }
                        break outer;
                    }
                }
            }
        }
        if (LAST_EAT.size() > 512) {
            LAST_EAT.clear();
        }
    }

    /** 個体 i の世界の位置を mx・my・mz に(反応なしの素の位置)。 */
    private static void memberWorld(Site s, int i, double t, double nowTicks) {
        if (s.species <= FIREFLY || s.species == STRIDER) {
            flyer(s, i, t, null, 0.0F);
        } else {
            crawl(s, i, t, nowTicks);
            mx = s.x + s.t1x * cA + s.t2x * cB;
            my = s.y + s.t1y * cA + s.t2y * cB;
            mz = s.z + s.t1z * cA + s.t2z * cB;
        }
    }

    // ───────────── 手元に寄る蛾 ─────────────

    private static void tickHand(ClientLevel level, AtmosphereCritters.Params p, LocalPlayer player, long now) {
        if (now % 20L == 0L || !handOk && handAlpha <= 0.0F && now % 5L == 0L) {
            BlockPos feet = player.blockPosition();
            handOk = overgrown(level, feet.getX(), feet.getY(), feet.getZ());
        }
        float live = liveliness(player.getX(), player.getZ());
        handCount = Math.round(p.handMoths() * live);
        boolean want = holdLight && handOk && WEIGHT[MOTH] > 0.5F && handCount > 0;
        handAlpha = Mth.clamp(handAlpha + (want ? 1.0F / (p.handMothSeconds() * 20.0F) : -0.03F), 0.0F, 1.0F);
        Vec3 look = player.getViewVector(1.0F);
        double tx = player.getX() + look.x * 1.2;
        double ty = player.getEyeY() - 0.3 + look.y * 0.8;
        double tz = player.getZ() + look.z * 1.2;
        if (handAlpha <= 0.0F) {
            handX = tx;
            handY = ty;
            handZ = tz;
        } else {
            handX += (tx - handX) * 0.12;
            handY += (ty - handY) * 0.12;
            handZ += (tz - handZ) * 0.12;
        }
    }

    // ───────────── 描く ─────────────

    /** 所の濃さ(賑わい・時刻・区切りの端)。距離のなじませは描く側で。 */
    private static float siteAlpha(Site s, double nowTicks) {
        float vis = Mth.clamp((s.live - s.threshold) * 10.0F + 0.5F, 0.0F, 1.0F);
        float edge = (float) Mth.clamp(Math.min(nowTicks - s.startTick, s.endTick - nowTicks) / 60.0, 0.0, 1.0);
        return vis * edge * WEIGHT[s.species];
    }

    // 描く枠: 原点と 3 本の軸(前・右・上。長さ 1 画素)。
    private static double ox;
    private static double oy;
    private static double oz;
    private static double fX;
    private static double fY;
    private static double fZ;
    private static double rX;
    private static double rY;
    private static double rZ;
    private static double uX;
    private static double uY;
    private static double uZ;
    private static float cl;
    private static float ca;

    /** 面の上の枠: 位置 (a, b)・向き h。 */
    private static void faceFrame(Site s, double a, double b, double h) {
        ox = s.x + s.t1x * a + s.t2x * b;
        oy = s.y + s.t1y * a + s.t2y * b;
        oz = s.z + s.t1z * a + s.t2z * b;
        double c = Math.cos(h) * PX;
        double sn = Math.sin(h) * PX;
        fX = s.t1x * c + s.t2x * sn;
        fY = s.t1y * c + s.t2y * sn;
        fZ = s.t1z * c + s.t2z * sn;
        rX = -s.t1x * sn + s.t2x * c;
        rY = -s.t1y * sn + s.t2y * c;
        rZ = -s.t1z * sn + s.t2z * c;
        uX = s.nx * PX;
        uY = s.ny * PX;
        uZ = s.nz * PX;
    }

    /** 空中の枠: 水平の向き h。 */
    private static void airFrame(double x, double y, double z, double h) {
        ox = x;
        oy = y;
        oz = z;
        fX = Math.cos(h) * PX;
        fY = 0.0;
        fZ = Math.sin(h) * PX;
        rX = -Math.sin(h) * PX;
        rY = 0.0;
        rZ = Math.cos(h) * PX;
        uX = 0.0;
        uY = PX;
        uZ = 0.0;
    }

    private static void quad(double a0, double b0, double h0, double a1, double b1, double h1, double a2, double b2, double h2, double a3, double b3,
                             double h3, float r, float g, float b, float alpha) {
        quads++;
        LifeSprites.quad(ox + fX * a0 + rX * b0 + uX * h0, oy + fY * a0 + rY * b0 + uY * h0, oz + fZ * a0 + rZ * b0 + uZ * h0,
                ox + fX * a1 + rX * b1 + uX * h1, oy + fY * a1 + rY * b1 + uY * h1, oz + fZ * a1 + rZ * b1 + uZ * h1,
                ox + fX * a2 + rX * b2 + uX * h2, oy + fY * a2 + rY * b2 + uY * h2, oz + fZ * a2 + rZ * b2 + uZ * h2,
                ox + fX * a3 + rX * b3 + uX * h3, oy + fY * a3 + rY * b3 + uY * h3, oz + fZ * a3 + rZ * b3 + uZ * h3, r * cl, g * cl, b * cl, ca * alpha);
    }

    /** 面に平行な四角(高さ h)。 */
    private static void rect(double a0, double b0, double a1, double b1, double h, float r, float g, float b) {
        quad(a0, b0, h, a1, b0, h, a1, b1, h, a0, b1, h, r, g, b, 1.0F);
    }

    /** 立てた四角(前と上の面。横の位置 b)。 */
    private static void rectV(double a0, double h0, double a1, double h1, double b, float r, float g, float bl) {
        quad(a0, b, h0, a1, b, h0, a1, b, h1, a0, b, h1, r, g, bl, 1.0F);
    }

    /** 細い帯(脚)。幅 w は面の中で。 */
    private static void seg(double a0, double b0, double h0, double a1, double b1, double h1, double w, float r, float g, float b, float alpha) {
        double da = a1 - a0;
        double db = b1 - b0;
        double n = Math.sqrt(da * da + db * db);
        double pa = n < 1.0e-4 ? w * 0.5 : -db / n * w * 0.5;
        double pb = n < 1.0e-4 ? 0.0 : da / n * w * 0.5;
        quad(a0 - pa, b0 - pb, h0, a1 - pa, b1 - pb, h1, a1 + pa, b1 + pb, h1, a0 + pa, b0 + pb, h0, r, g, b, alpha);
    }

    /** 普通の重ねで描く({@link LifeSprites#begin} と end の間)。 */
    static void render(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        long t0 = System.nanoTime();
        quads = 0;
        render0(level, partialTick, cam, left, up);
        lastQuads = quads;
        renderNanos += (System.nanoTime() - t0 - renderNanos) * 0.02;
    }

    private static void render0(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        if (SITES.isEmpty() && handAlpha <= 0.0F) {
            return;
        }
        LocalPlayer pl = net.minecraft.client.Minecraft.getInstance().player;
        if (pl == null) {
            return;
        }
        AtmosphereCritters.Params p = DesignData.atmosphere().smallCritters();
        double nowTicks = level.getGameTime() + partialTick;
        double t = nowTicks / 20.0;
        Vec3 player = pl.getPosition(partialTick).add(0.0, 1.0, 0.0);
        double range = p.range();
        for (Site s : SITES) {
            float alpha = siteAlpha(s, nowTicks);
            if (alpha <= 0.01F) {
                continue;
            }
            double d = Math.sqrt(cam.distanceToSqr(s.x, s.y, s.z));
            alpha *= (float) Mth.clamp((range - d) / 4.0, 0.0, 1.0);
            if (alpha <= 0.01F) {
                continue;
            }
            cl = s.light;
            boolean far = d > (s.species <= FIREFLY ? 16.0 : 12.0);
            if (s.species <= FIREFLY || s.species == STRIDER) {
                for (int i = 0; i < s.members; i++) {
                    if (gone(s, i)) {
                        continue;
                    }
                    flyer(s, i, t, player, s.pull * p.lightPull());
                    ca = alpha;
                    switch (s.species) {
                        case DAMSELFLY -> {
                            if (far) {
                                LifeSprites.dot(mx, my, mz, (float) PX, left, up, 0.14F * cl, 0.30F * cl, 0.32F * cl, alpha);
                            } else {
                                airFrame(mx, my, mz, mh);
                                drawDamselfly(t, i);
                            }
                        }
                        case MAYFLY -> {
                            // 体(淡い縦の点)と、上で震える薄い翅。
                            LifeSprites.dot(mx, my, mz, (float) (PX * 0.5), left, up, 0.80F * cl, 0.76F * cl, 0.58F * cl, alpha);
                            if (!far) {
                                LifeSprites.dot(mx, my - PX, mz, (float) (PX * 0.5), left, up, 0.62F * cl, 0.58F * cl, 0.42F * cl, alpha);
                                LifeSprites.dot(mx, my + PX * 1.2, mz, (float) (PX * 1.1), left, up, 0.88F * cl, 0.88F * cl, 0.80F * cl, alpha * 0.32F);
                            }
                        }
                        case MOTH -> {
                            if (far) {
                                LifeSprites.dot(mx, my, mz, (float) PX, left, up, 0.44F * cl, 0.38F * cl, 0.29F * cl, alpha);
                            } else {
                                airFrame(mx, my, mz, mh);
                                drawMoth(t, i);
                            }
                        }
                        case FIREFLY -> {
                            if (d < 12.0) { // 光っていない間の暗い体
                                LifeSprites.dot(mx, my, mz, (float) (PX * 0.5), left, up, 0.07F, 0.07F, 0.04F, alpha * 0.6F);
                            }
                        }
                        default -> {
                            if (far) {
                                LifeSprites.dot(mx, my + PX * 0.5, mz, (float) (PX * 0.8), left, up, 0.10F * cl, 0.10F * cl, 0.08F * cl, alpha);
                            } else {
                                airFrame(mx, my, mz, mh);
                                drawStrider();
                            }
                        }
                    }
                }
                continue;
            }
            for (int i = 0; i < s.members; i++) {
                if (gone(s, i)) {
                    continue;
                }
                crawl(s, i, t, nowTicks);
                ca = alpha * cAlpha;
                if (ca <= 0.01F) {
                    continue;
                }
                faceFrame(s, cA, cB, cH);
                if (far) {
                    drawFar(s);
                    continue;
                }
                switch (s.species) {
                    case SPIDER -> drawSpider(s, t);
                    case BEETLE -> {
                        if (s.variant == 1) {
                            drawTermite();
                        } else {
                            drawBeetle();
                        }
                    }
                    case MILLIPEDE -> drawMillipede(s, t);
                    case LIZARD -> drawLizard(s, t);
                    default -> drawGeji(t);
                }
            }
        }
        // 手元に寄る蛾: 遠くから渦を巻いて寄り、明かりのまわりを回る。
        if (handAlpha > 0.0F) {
            cl = 0.72F; // 手元の明かりに照らされた色(真っ白に浮かせない)
            ca = Math.min(1.0F, handAlpha * 3.0F);
            double wide = (1.0 - handAlpha) * (1.0 - handAlpha) * 6.0;
            for (int i = 0; i < handCount; i++) {
                double dir = (i & 1) == 0 ? 1.0 : -1.0;
                double ang = t * (2.4 + 0.7 * i) * dir + i * 2.1;
                double rad = 0.38 + 0.1 * Math.sin(t * 1.3 + i * 2.1) + wide * (0.6 + 0.2 * i);
                double x = handX + Math.cos(ang) * rad;
                double y = handY + 0.16 * Math.sin(t * 2.9 + i) + 0.08 * Math.sin(t * 7.1 + i * 2.0) + wide * 0.3;
                double z = handZ + Math.sin(ang) * rad;
                airFrame(x, y, z, ang + dir * Math.PI * 0.5);
                drawMoth(t, i + 5);
            }
        }
    }

    /** ホタルの光(足し算。{@link LifeSprites#begin}(event, true) と end の間)。 */
    static void renderGlow(ClientLevel level, float partialTick, Vec3 cam, Vector3f left, Vector3f up) {
        if (ACTIVE[FIREFLY] <= 0) {
            return;
        }
        LocalPlayer pl = net.minecraft.client.Minecraft.getInstance().player;
        if (pl == null) {
            return;
        }
        AtmosphereCritters.Params p = DesignData.atmosphere().smallCritters();
        double nowTicks = level.getGameTime() + partialTick;
        double t = nowTicks / 20.0;
        Vec3 player = pl.getPosition(partialTick).add(0.0, 1.0, 0.0);
        for (Site s : SITES) {
            if (s.species != FIREFLY) {
                continue;
            }
            float alpha = siteAlpha(s, nowTicks);
            double d = Math.sqrt(cam.distanceToSqr(s.x, s.y, s.z));
            alpha *= (float) Mth.clamp((p.range() - d) / 4.0, 0.0, 1.0);
            if (alpha <= 0.01F) {
                continue;
            }
            for (int i = 0; i < s.members; i++) {
                flyer(s, i, t, player, s.pull * p.lightPull());
                if (mlit <= 0.02F) {
                    continue;
                }
                // 黄緑(青くしない: 土ボタル・ネザーの偽物の空と被らせない、11-08 ユーザー)。芯は白っぽい黄。
                LifeSprites.blob(mx, my, mz, 0.14F + 0.06F * mlit, 1.0F, left, up, 0.74F, 0.92F, 0.20F, alpha * mlit * 0.9F);
                LifeSprites.dot(mx, my, mz, (float) (PX * 0.6), left, up, 1.0F, 1.0F, 0.62F, alpha * mlit);
            }
        }
    }

    // ───────────── 形(単位 = 1 画素 = 1/16 ブロック) ─────────────

    private static void drawFar(Site s) {
        switch (s.species) {
            case SPIDER -> rect(-1.5, -1.5, 1.5, 1.5, 0.6, 0.14F, 0.11F, 0.08F);
            case BEETLE -> {
                if (s.variant == 1) {
                    rect(-0.5, -0.5, 0.5, 0.5, 0.4, 0.62F, 0.52F, 0.37F);
                } else {
                    rect(-1.5, -1.0, 2.5, 1.0, 0.8, 0.10F, 0.11F, 0.08F);
                }
            }
            case MILLIPEDE -> rect(-4.0, -0.5, 4.0, 0.5, 0.6, 0.22F, 0.11F, 0.07F);
            case LIZARD -> rect(-6.0, -0.8, 4.0, 0.8, 0.7, 0.30F, 0.29F, 0.17F);
            default -> rect(-3.0, -1.5, 3.0, 1.5, 0.6, 0.40F, 0.35F, 0.24F);
        }
    }

    /** ヤスデ(小): 8 節。歩くとき体が波打ち、驚くと渦に丸まる。 */
    private static void drawMillipede(Site s, double t) {
        double curl = smooth(s.scare * 1.4);
        double wave = cMove ? cWalk * 1.6 : s.seed % 7;
        for (int k = 7; k >= 0; k--) {
            double a = 3.5 - k;
            double b = 0.4 * Math.sin(k * 0.9 - wave);
            double ang = k * 0.82;
            double rad = 1.75 - k * 0.14;
            a = Mth.lerp(curl, a, Math.cos(ang) * rad);
            b = Mth.lerp(curl, b, Math.sin(ang) * rad);
            if (curl < 0.5) {
                // 脚の房(体より淡い)。
                quad(a - 0.5, b - 1.0, 0.2, a + 0.5, b - 1.0, 0.2, a + 0.5, b + 1.0, 0.2, a - 0.5, b + 1.0, 0.2, 0.36F, 0.25F, 0.15F, 0.8F * (float) (1.0 - curl * 2.0));
            }
            if ((k & 1) == 0) {
                rect(a - 0.5, b - 0.5, a + 0.5, b + 0.5, 0.6, 0.20F, 0.10F, 0.07F);
            } else {
                rect(a - 0.5, b - 0.5, a + 0.5, b + 0.5, 0.6, 0.26F, 0.14F, 0.09F);
            }
        }
        if (curl < 0.5) {
            double b0 = 0.4 * Math.sin(-wave);
            seg(4.0, b0 + 0.2, 0.5, 5.2, b0 + 0.9, 0.3, 0.3, 0.14F, 0.08F, 0.05F, 1.0F);
            seg(4.0, b0 - 0.2, 0.5, 5.2, b0 - 0.9, 0.3, 0.3, 0.14F, 0.08F, 0.05F, 1.0F);
        }
    }

    /** 甲虫: 黒褐色の背・鈍い緑の艶・6 本脚。 */
    private static void drawBeetle() {
        for (int j = 0; j < 3; j++) {
            double swing = cMove ? 0.5 * Math.sin(cWalk * 2.2 + j * 2.1) : 0.0;
            seg(1.0 - j, 0.9, 0.4, 1.4 - j * 1.2 + swing, 2.3, 0.0, 0.35, 0.07F, 0.06F, 0.05F, 1.0F);
            seg(1.0 - j, -0.9, 0.4, 1.4 - j * 1.2 - swing, -2.3, 0.0, 0.35, 0.07F, 0.06F, 0.05F, 1.0F);
        }
        rect(-1.5, -1.0, 1.5, 1.0, 0.8, 0.10F, 0.11F, 0.08F);
        rect(-1.5, -0.15, 1.2, 0.15, 0.9, 0.05F, 0.06F, 0.04F);
        rect(-0.9, -0.85, 0.6, -0.4, 0.9, 0.16F, 0.23F, 0.13F);
        rect(1.5, -0.75, 2.2, 0.75, 0.8, 0.07F, 0.07F, 0.06F);
        rect(2.2, -0.4, 2.8, 0.4, 0.7, 0.05F, 0.05F, 0.04F);
    }

    /** シロアリ: 淡い体に茶の頭。 */
    private static void drawTermite() {
        rect(-0.5, -0.4, 0.3, 0.4, 0.4, 0.66F, 0.56F, 0.40F);
        rect(0.3, -0.35, 0.8, 0.35, 0.4, 0.48F, 0.32F, 0.18F);
    }

    /** クモ(種類 0)・ザトウムシ(種類 1): ザトウムシは小さな体と長い脚、驚くと体を揺する。 */
    private static void drawSpider(Site s, double t) {
        boolean harvest = s.variant == 1;
        double foot = harvest ? 4.4 : 2.7;
        double knee = harvest ? 2.2 : 1.3;
        double kneeH = harvest ? 2.4 : 1.2;
        double bodyH = harvest ? 1.3 + 0.5 * s.scare * Math.sin(t * 22.0) : 0.6;
        for (int j = 0; j < 4; j++) {
            double ang = 0.5 + j * 0.62 + (cMove ? 0.18 * Math.sin(cWalk * 2.5 + j * 1.6) : 0.0);
            double c = Math.cos(ang);
            double sn = Math.sin(ang);
            for (int side = -1; side <= 1; side += 2) {
                seg(c * 0.4, sn * 0.4 * side, bodyH, c * knee, sn * knee * side, kneeH, 0.3, 0.10F, 0.08F, 0.06F, 1.0F);
                seg(c * knee, sn * knee * side, kneeH, c * foot, sn * foot * side, 0.0, 0.3, 0.10F, 0.08F, 0.06F, 1.0F);
            }
        }
        if (harvest) {
            rect(-0.75, -0.6, 0.75, 0.6, bodyH, 0.30F, 0.21F, 0.12F);
            rect(-0.2, -0.3, 0.4, 0.3, bodyH + 0.1, 0.19F, 0.13F, 0.08F);
        } else {
            rect(-2.0, -1.0, 0.0, 1.0, bodyH + 0.2, 0.17F, 0.13F, 0.09F);
            rect(-1.5, -0.2, -0.5, 0.2, bodyH + 0.3, 0.27F, 0.21F, 0.13F);
            rect(0.0, -0.6, 1.0, 0.6, bodyH, 0.11F, 0.09F, 0.07F);
        }
    }

    /** 小さなトカゲ: 全長 12 画素ほど。日光浴でじっとし、ときどき頭を振る。 */
    private static void drawLizard(Site s, double t) {
        float r = 0.30F;
        float g = 0.29F;
        float b = 0.17F;
        boolean run = s.fleeAt >= 0L;
        double sw = run ? 0.9 * Math.sin(cWalk * 1.4) : 0.0;
        // 脚
        seg(1.3, 0.8, 0.5, 2.0 + sw, 2.4, 0.0, 0.5, r * 0.6F, g * 0.6F, b * 0.6F, 1.0F);
        seg(1.3, -0.8, 0.5, 2.0 - sw, -2.4, 0.0, 0.5, r * 0.6F, g * 0.6F, b * 0.6F, 1.0F);
        seg(-1.5, 0.8, 0.5, -2.3 - sw, 2.5, 0.0, 0.5, r * 0.6F, g * 0.6F, b * 0.6F, 1.0F);
        seg(-1.5, -0.8, 0.5, -2.3 + sw, -2.5, 0.0, 0.5, r * 0.6F, g * 0.6F, b * 0.6F, 1.0F);
        // 尾 4 節
        double wave = run ? 0.8 * Math.sin(cWalk * 1.4) : 0.3 * Math.sin(t * 0.8 + s.seed % 11);
        double a0 = -2.0;
        double w0 = 0.8;
        double s0 = 0.0;
        for (int k = 1; k <= 4; k++) {
            double a1 = -2.0 - 1.4 * k;
            double w1 = 0.8 * (1.0 - k / 4.6);
            double s1 = wave * Math.sin(k * 0.9) * k / 4.0;
            quad(a0, s0 - w0, 0.6, a1, s1 - w1, 0.5, a1, s1 + w1, 0.5, a0, s0 + w0, 0.6, r * 0.95F, g * 0.95F, b * 0.95F, 1.0F);
            a0 = a1;
            w0 = w1;
            s0 = s1;
        }
        rect(-2.0, -1.0, 2.0, 1.0, 0.7, r, g, b);
        // 頭(ときどき横へ振る)
        double look = run ? 0.0 : 0.5 * Math.sin(t * 0.5 + s.seed % 5) * (Math.sin(t * 0.21 + s.seed % 3) > 0.5 ? 1.0 : 0.0);
        quad(2.0, -0.9, 0.7, 4.2, -0.5 + look, 0.8, 4.2, 0.5 + look, 0.8, 2.0, 0.9, 0.7, r * 1.12F, g * 1.12F, b * 1.1F, 1.0F);
        // 背の筋と斑点(1 画素)
        rect(-2.0, -0.2, 3.2, 0.2, 0.85, r * 1.7F, g * 1.75F, b * 1.5F);
        rect(-1.2, 0.4, -0.4, 0.9, 0.85, r * 0.7F, g * 0.7F, b * 0.7F);
        rect(0.6, -0.9, 1.4, -0.4, 0.85, r * 0.7F, g * 0.7F, b * 0.7F);
    }

    /** アメンボ: 細い体と水面に広げた長い脚。足先に水のくぼみ。 */
    private static void drawStrider() {
        float r = 0.08F;
        float g = 0.08F;
        float b = 0.07F;
        for (int side = -1; side <= 1; side += 2) {
            seg(0.3, 0.3 * side, 0.6, 0.9, 3.3 * side, 0.0, 0.25, r, g, b, 1.0F);
            seg(-1.0, 0.3 * side, 0.6, -3.1, 2.3 * side, 0.0, 0.25, r, g, b, 1.0F);
            seg(1.3, 0.2 * side, 0.6, 2.4, 1.0 * side, 0.0, 0.25, r, g, b, 1.0F);
            quad(0.4, 2.8 * side, 0.02, 1.4, 2.8 * side, 0.02, 1.4, 3.8 * side, 0.02, 0.4, 3.8 * side, 0.02, 0.02F, 0.03F, 0.03F, 0.3F);
            quad(-3.6, 1.8 * side, 0.02, -2.6, 1.8 * side, 0.02, -2.6, 2.8 * side, 0.02, -3.6, 2.8 * side, 0.02, 0.02F, 0.03F, 0.03F, 0.3F);
        }
        rect(-1.7, -0.3, 1.7, 0.3, 0.6, 0.12F, 0.11F, 0.08F);
    }

    /** ゲジ: 縞のある細い体に、後ろほど長い脚がたくさん。 */
    private static void drawGeji(double t) {
        for (int j = 0; j < 10; j++) {
            double a = 2.6 - j * 0.58;
            double len = 2.0 + j * 0.24;
            double off = cMove ? 0.7 * Math.sin(cWalk * 3.0 - j * 0.8) : 0.15 * Math.sin(j * 1.3);
            seg(a, 0.4, 0.7, a + off - 0.3, len, 0.0, 0.25, 0.30F, 0.26F, 0.17F, 1.0F);
            seg(a, -0.4, 0.7, a - off - 0.3, -len, 0.0, 0.25, 0.30F, 0.26F, 0.17F, 1.0F);
        }
        seg(3.0, 0.2, 0.6, 6.5, 1.6, 0.2, 0.2, 0.30F, 0.26F, 0.17F, 1.0F);
        seg(3.0, -0.2, 0.6, 6.5, -1.6, 0.2, 0.2, 0.30F, 0.26F, 0.17F, 1.0F);
        seg(-3.0, 0.2, 0.6, -7.0, 1.4, 0.0, 0.2, 0.30F, 0.26F, 0.17F, 1.0F);
        seg(-3.0, -0.2, 0.6, -7.0, -1.4, 0.0, 0.2, 0.30F, 0.26F, 0.17F, 1.0F);
        rect(-3.0, -0.5, 3.0, 0.5, 0.7, 0.46F, 0.40F, 0.27F);
        rect(-2.2, -0.5, -1.7, 0.5, 0.8, 0.22F, 0.18F, 0.12F);
        rect(-0.25, -0.5, 0.25, 0.5, 0.8, 0.22F, 0.18F, 0.12F);
        rect(1.7, -0.5, 2.2, 0.5, 0.8, 0.22F, 0.18F, 0.12F);
    }

    /** イトトンボ: 細い腹・暗い胸と頭・震える 4 枚の薄い翅(上下 2 つの姿を重ねてぶれに見せる)。 */
    private static void drawDamselfly(double t, int i) {
        for (int pose = -1; pose <= 1; pose += 2) {
            double h = 1.5 * pose;
            for (int side = -1; side <= 1; side += 2) {
                quad(1.7, 0.4 * side, 0.3, 0.9, 0.4 * side, 0.3, 0.2, 5.0 * side, 0.3 + h, 1.2, 5.0 * side, 0.3 + h, 0.78F, 0.84F, 0.80F, 0.2F);
                quad(0.9, 0.4 * side, 0.3, 0.2, 0.4 * side, 0.3, -1.2, 4.6 * side, 0.3 - h, -0.2, 4.6 * side, 0.3 - h, 0.78F, 0.84F, 0.80F, 0.2F);
            }
        }
        rect(-5.0, -0.25, 0.5, 0.25, 0.0, 0.14F, 0.34F, 0.36F);
        rectV(-5.0, -0.25, 0.5, 0.25, 0.0, 0.14F, 0.34F, 0.36F);
        rect(0.5, -0.5, 2.0, 0.5, 0.0, 0.11F, 0.22F, 0.20F);
        rectV(0.5, -0.5, 2.0, 0.5, 0.0, 0.11F, 0.22F, 0.20F);
        rect(2.0, -0.6, 2.8, 0.6, 0.0, 0.07F, 0.09F, 0.08F);
        rectV(2.0, -0.4, 2.8, 0.4, 0.0, 0.07F, 0.09F, 0.08F);
    }

    /** 蛾: 灰茶の体と、ばたつく 2 枚の翅。 */
    private static void drawMoth(double t, int i) {
        double phi = Math.sin(t * 24.0 + i * 1.9) * 1.1;
        double wb = 2.3 * Math.cos(phi);
        double wh = 2.3 * Math.sin(phi);
        for (int side = -1; side <= 1; side += 2) {
            quad(0.9, 0.0, 0.0, -1.0, 0.0, 0.0, -1.5, wb * side, wh, 1.3, wb * side, wh, 0.47F, 0.41F, 0.31F, 1.0F);
            quad(0.1, wb * 0.55 * side, wh * 0.55, -0.6, wb * 0.55 * side, wh * 0.55, -0.9, wb * 0.8 * side, wh * 0.8, 0.5, wb * 0.8 * side, wh * 0.8,
                    0.30F, 0.25F, 0.19F, 1.0F);
        }
        rect(-1.0, -0.3, 1.0, 0.3, 0.0, 0.30F, 0.25F, 0.18F);
        rectV(-1.0, -0.3, 1.0, 0.3, 0.0, 0.30F, 0.25F, 0.18F);
    }
}
