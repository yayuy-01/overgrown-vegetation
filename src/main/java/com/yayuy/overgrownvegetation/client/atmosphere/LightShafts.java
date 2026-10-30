package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 林冠の隙間から差す<b>光の筋</b>(一覧 1。2026-10-15 試作・ユーザー「ジャングルの中のライティングにこだわる」、10-30 作り直し。
 * 元は parked の {@code client/CanopyLight})。筋の中の光る胞子は {@link ShaftMotes}。
 *
 * <h2>見つけ方</h2>
 * 本物の林冠の穴の下でしか出さない(出し過ぎは浮く。迷ったら出さない側へ)。プレイヤーの周り {@link #RADIUS} ブロックの地面を毎 tick
 * {@link #SCAN_PER_TICK} か所ずつ見て、次を全部満たすときだけ置く(安い順):
 * <ol>
 *     <li><b>じかに日が当たる地面</b>(巨大樹の傘の下では薄い所から漏れる光も。{@link SunTrace#highCanopyShaftAllowance})。</li>
 *     <li><b>葉の穴を通ってくる</b>: 光の道を上へたどり、水平 1〜3 ブロックの 8 方向のうち {@link #HOLE_RING_MIN} 方向以上に葉がある高さがある。</li>
 *     <li><b>落ちた所の周りが葉の影</b>: 周り 3・5 ブロックの地面の {@link #SHADE_RING_MIN} 以上が葉を通らないと日が当たらない。</li>
 * </ol>
 * 上端(林冠の穴)は動かず、下端はその時の太陽の向きで毎フレーム決める。20〜45 秒で入れ替わり、太陽が動いて穴から光が通らなくなったら消える。
 * 夜は月の向きで青白い月光の筋(2026-10-16 ユーザー指定)。
 *
 * <h2>描き方</h2>
 * 加算の半透明({@link AddLight})。カメラを向く帯を 2 重(芯と、広い薄いにじみ)にし、上端は林冠から湧くように薄く、落ちる所には地面のにじみ。
 * 明るさは目からその点への向きで決まる({@link SunLight#phase})。太陽が真上に近いほど太く薄くぼかす({@link SunLight#softness}。正午の拡散は弱く、2026-10-16)。
 * 風({@link Wind})で明滅し、枝の揺れで少し行き来する。朝夕は金色で長く、正午は白っぽく短く弱く(提案 1)、空気の霞み具合({@link AirHaze})で濃さが変わる(提案 2)。強さは森の中で空の下にいる度合い({@link ForestPresence#openForest})を掛けて森の縁でなめらかに出入りする。
 */
final class LightShafts {

    private static final int RADIUS = 28;
    private static final int SCAN_PER_TICK = 10;
    /** 筋の長さ(林冠の下面から地面まで)の下限(ブロック)。 */
    private static final int MIN_DROP = 4;
    private static final int HOLE_RING_MIN = 4;
    private static final float SHADE_RING_MIN = 0.75F;
    /** 林冠の穴を探す道のりの上限(ブロック。巨大樹の傘は地面から 120〜240 ブロック上)。 */
    private static final int HOLE_MAX_K = 400;
    private static final int[][] RING_DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static final List<Shaft> SHAFTS = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float[] RGB = new float[3];
    /** 直近の tick の昼(夜は月)の強さとぼかし具合(光る胞子の束が読む)。 */
    private static float lastDay;
    private static float softness;

    /** 1 本の光の筋。 */
    static final class Shaft {
        final Vec3 top;
        /** 光が落ちる高さ(なめらかに寄せる今の値と、当たり判定で決めた行き先)。 */
        double groundY;
        double targetGroundY;
        /** 直近の tick の太陽で求めた下端。描くときはフレームの太陽で求め直す。 */
        Vec3 bottom;
        final float width;
        final float phase;
        final int life;
        int age;
        int dyingAt = -1;
        /** 高い林冠(巨大樹の傘)の、穴ではなく薄い所から漏れる筋(薄く描く)。 */
        boolean thin;
        /** 光る胞子の束の中のこの筋の組(-1 = まだ)。筋が消えるまで変えない。 */
        int moteGroup = -1;
        /** 足元のにじみを置くマスの地面の高さ(NaN = 地面が無い)。 */
        final HashMap<Long, Float> glowHeights = new HashMap<>();

        Shaft(Vec3 top, Vec3 bottom, float width, float phase, int life) {
            this.top = top;
            this.bottom = bottom;
            this.groundY = bottom.y;
            this.targetGroundY = bottom.y;
            this.width = width;
            this.phase = phase;
            this.life = life;
        }

        float glowHeight(ClientLevel level, int x, int z) {
            long key = BlockPos.asLong(x, 0, z);
            Float known = this.glowHeights.get(key);
            if (known != null) {
                return known;
            }
            if (this.glowHeights.size() > 600) {
                this.glowHeights.clear();
            }
            float h = SunTrace.groundAt(level, x, z, Mth.floor(this.targetGroundY) + 2);
            this.glowHeights.put(key, h);
            return h;
        }

        /** 太陽の向き {@code sun} のときの下端。 */
        Vec3 bottomFor(Vec3 sun) {
            double drop = Math.max(0.0, this.top.y - this.groundY);
            return this.top.subtract(sun.scale(drop / Math.max(sun.y, 0.05)));
        }

        /** 0〜1 の出方(湧き始め・消え際のなめらかな出入り)。 */
        float presence() {
            float in = Mth.clamp(this.age / 60.0F, 0.0F, 1.0F);
            float out = this.dyingAt < 0 ? 1.0F : 1.0F - Mth.clamp((this.age - this.dyingAt) / 50.0F, 0.0F, 1.0F);
            return SunLight.smooth(in) * SunLight.smooth(out);
        }

        boolean gone() {
            return this.dyingAt >= 0 && this.age - this.dyingAt >= 50;
        }
    }

    static void clear() {
        SHAFTS.clear();
        lastDay = 0.0F;
    }

    static int count() {
        return SHAFTS.size();
    }

    static List<Shaft> shafts() {
        return SHAFTS;
    }

    static float lastDay() {
        return lastDay;
    }

    static float lastSoftness() {
        return softness;
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        Vec3 sun = SunLight.lightDirection(level, 1.0F);
        float day = SunLight.lightStrength(level, sun, 1.0F);
        lastDay = day;
        softness = SunLight.softness(sun);
        boolean inForest = ForestPresence.openForest() > 0.001F;

        Vec3 center = player.position();
        for (Iterator<Shaft> it = SHAFTS.iterator(); it.hasNext(); ) {
            Shaft s = it.next();
            s.age++;
            s.groundY += (s.targetGroundY - s.groundY) * 0.08;
            s.bottom = s.bottomFor(sun);
            if (s.dyingAt < 0) {
                boolean far = horizontalDist(s.bottom, center) > RADIUS + 6;
                boolean recheck = (s.age + (int) (s.phase * 20.0F)) % 20 == 0;
                if (far || day <= 0.0F || !inForest || s.age >= s.life || recheck && !revalidate(level, s, sun)) {
                    s.dyingAt = s.age;
                }
            }
            if (s.gone()) {
                it.remove();
            }
        }
        if (day > 0.0F && inForest) {
            int cap = VisualQuality.shaftCap(DesignData.atmosphere().maxShafts());
            for (int i = 0; i < SCAN_PER_TICK && SHAFTS.size() < cap; i++) {
                tryFindShaft(level, player, sun);
            }
        }
    }

    /** 地面 1 か所を調べて、林冠の穴から光が落ちる所なら筋を足す(条件はクラスの説明の 3 つ)。 */
    private static void tryFindShaft(ClientLevel level, LocalPlayer player, Vec3 sun) {
        if (sun.y < 0.08) {
            return;
        }
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        float ground = SunTrace.groundAt(level, x, z, Mth.floor(player.getY()));
        if (Float.isNaN(ground) || !ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, Mth.floor(ground), z)))) {
            return;
        }
        double gx = x + 0.5;
        double gz = z + 0.5;
        int trace = SunTrace.traceSun(level, gx, ground + 0.05, gz, sun);
        boolean thin = trace > 0 && trace <= SunTrace.highCanopyShaftAllowance();
        if (trace != 0 && !thin) {
            return;
        }
        for (Shaft s : SHAFTS) {
            double dx = s.bottom.x - gx;
            double dz = s.bottom.z - gz;
            if (dx * dx + dz * dz < 3.0 * 3.0) {
                return;
            }
        }
        float[] hole = findHole(level, gx, ground, gz, sun);
        if (hole == null || !shadedAround(level, x, ground, z, sun)) {
            return;
        }
        double up = hole[0];
        // 林冠の下面から 1 ブロックだけ葉の中へ入れた所を上端にする(葉の中から湧いて見える。林冠より上には描かない)。
        Vec3 top = new Vec3(gx + sun.x * (up + 1.0), ground + sun.y * (up + 1.0), gz + sun.z * (up + 1.0));
        Shaft shaft = new Shaft(top, new Vec3(gx, ground, gz), hole[1], RANDOM.nextFloat(), 400 + RANDOM.nextInt(500));
        shaft.thin = thin;
        SHAFTS.add(shaft);
    }

    /**
     * 地面から太陽の方へたどって林冠の穴を探す。水平 1〜3 ブロックの 8 方向のうち {@link #HOLE_RING_MIN} 方向以上が葉になる最初の高さを林冠の下面とし、
     * {道のりの長さ, 穴の半径(その高さで 8 方向に葉までの距離の平均)} を返す。無ければ null(一本の木の横・林の縁の空など)。
     */
    private static float[] findHole(ClientLevel level, double gx, float ground, double gz, Vec3 sun) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int k = MIN_DROP; k <= HOLE_MAX_K; k++) {
            double px = gx + sun.x * k;
            double py = ground + sun.y * k;
            double pz = gz + sun.z * k;
            int by = Mth.floor(py);
            // 周り 3 ブロックの列の一番高い所より上へ抜けたら、そこまでに穴が無かった。
            int ringTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(px), Mth.floor(pz));
            for (int[] d : RING_DIRS) {
                ringTop = Math.max(ringTop, level.getHeight(Heightmap.Types.WORLD_SURFACE, Mth.floor(px + d[0] * 3.0), Mth.floor(pz + d[1] * 3.0)));
            }
            if (by > ringTop + 1) {
                return null;
            }
            // 周りが空気だけの区画に収まっていれば葉は無い(巨大樹の幹の下の広い空間を速く抜ける)。
            if (SunTrace.boxIsAir(level, Mth.floor(px - 3.0), by, Mth.floor(pz - 3.0), Mth.floor(px + 3.0), by + 1, Mth.floor(pz + 3.0))) {
                continue;
            }
            int leafy = 0;
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                boolean found = false;
                for (int r = 1; r <= 3 && !found; r++) {
                    int lx = Mth.floor(px + d[0] * r / len);
                    int lz = Mth.floor(pz + d[1] * r / len);
                    found = SunTrace.isLeafLike(level, pos.set(lx, by, lz)) || SunTrace.isLeafLike(level, pos.set(lx, by + 1, lz));
                }
                if (found) {
                    leafy++;
                }
            }
            if (leafy < HOLE_RING_MIN) {
                continue;
            }
            float sum = 0.0F;
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                float reach = 4.0F;
                for (int r = 1; r <= 4; r++) {
                    if (SunTrace.isLeafLike(level, pos.set(Mth.floor(px + d[0] * r / len), by, Mth.floor(pz + d[1] * r / len)))) {
                        reach = r;
                        break;
                    }
                }
                sum += reach;
            }
            return new float[]{k, Mth.clamp(sum / RING_DIRS.length - 0.5F, 0.5F, 2.0F)};
        }
        return null;
    }

    /** 落ちた所の周り 3・5 ブロックの地面のうち、高さの近い所の {@link #SHADE_RING_MIN} 以上が葉を通らないと日が当たらないか(崖・幹だけの影は数えない)。 */
    private static boolean shadedAround(ClientLevel level, int x, float ground, int z, Vec3 sun) {
        int counted = 0;
        int leafShade = 0;
        for (int radius : new int[]{3, 5}) {
            for (int[] d : RING_DIRS) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                int sx = x + (int) Math.round(d[0] * radius / len);
                int sz = z + (int) Math.round(d[1] * radius / len);
                float g = SunTrace.groundAt(level, sx, sz, Mth.floor(ground));
                if (Float.isNaN(g) || Math.abs(g - ground) > 4.0F) {
                    continue;
                }
                counted++;
                int trace = SunTrace.traceSun(level, sx + 0.5, g + 0.05, sz + 0.5, sun);
                if (trace > 0 || trace < -1) {
                    leafShade++;
                }
            }
        }
        return counted >= 10 && leafShade >= counted * SHADE_RING_MIN;
    }

    /** 太陽が動いた後も同じ穴から光が落ちるか。落ちるなら落ちる高さの行き先だけ替える(筋は飛ばない)。 */
    private static boolean revalidate(ClientLevel level, Shaft s, Vec3 sun) {
        if (sun.y < 0.08) {
            return false;
        }
        Vec3 bottom = s.bottomFor(sun);
        float ground = SunTrace.groundAt(level, Mth.floor(bottom.x), Mth.floor(bottom.z), Mth.floor(s.targetGroundY));
        if (Float.isNaN(ground) || ground > s.top.y - MIN_DROP) {
            return false;
        }
        int trace = SunTrace.traceSun(level, bottom.x, ground + 0.05, bottom.z, sun);
        if (trace != 0 && !(s.thin && trace > 0 && trace <= SunTrace.highCanopyShaftAllowance())) {
            return false;
        }
        s.targetGroundY = ground;
        return true;
    }

    static void render(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (SHAFTS.isEmpty() || level == null) {
            return;
        }
        float intensity = DesignData.atmosphere().intensity() * ForestPresence.openForest();
        if (intensity <= 0.0F) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = SunLight.lightDirection(level, partialTick);
        float day = SunLight.lightStrength(level, sun, partialTick);
        SunLight.airColor(level, sun, partialTick, RGB);
        Vec3 eye = event.getCamera().getPosition();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float soft = SunLight.softness(sun);
        float coreWidth = widthScale(soft);
        // 薄めすぎると昼は見えなくなったので、太くしたぶんの半分ほどだけ薄める。
        float coreAlpha = 0.28F * (1.0F - 0.35F * soft);
        // 提案 1(10-30): 正午は白っぽく(色は SunLight#airColor)短く弱く、朝夕は金色で長く。提案 2: 空気に何かあるときだけはっきり({@link AirHaze})。
        float noon = SunLight.noon(sun);
        float airAlpha = Mth.lerp(noon, 1.0F, DesignData.atmosphere().noonShaftStrength()) * AirHaze.haze();
        float shorten = DesignData.atmosphere().noonShaftShorten() * noon;
        float haloWidth = 2.6F + 1.4F * soft;
        float haloAlpha = 0.09F;
        Wind wind = Wind.current();
        double windTime = Atmosphere.time(partialTick);
        double seconds = windTime / 20.0;

        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer consumer = buffers.getBuffer(AddLight.renderType());
        for (Shaft s : SHAFTS) {
            Vec3 bottom = s.bottomFor(sun);
            // 穴のまわりの葉が揺れるので、筋は明滅し(木漏れ日より遅く浅く)、枝の揺れに合わせて風の向きへ少し行き来する
            // (描く位置だけ。カメラ側を逆へずらして描くのと同じ)。
            double gust = Math.min(wind.strength(bottom.x, bottom.z, windTime), 1.0);
            float flicker = (float) ((0.97 + 0.03 * Math.sin(2.0 * Math.PI * 0.2 * seconds + s.phase * 5.0))
                    * (1.0 - 0.3 * gust * (0.5 + 0.5 * Math.sin(2.0 * Math.PI * 0.7 * seconds + s.phase * 6.0))
                    * (0.6 + 0.4 * Math.sin(2.0 * Math.PI * 1.6 * seconds + s.phase * 11.0))));
            double sway = (0.04 + 0.22 * gust) * Wind.branch(windTime, s.phase * 9.0)
                    + 0.06 * gust * Math.sin(2.0 * Math.PI * 0.9 * seconds + s.phase * 4.0);
            Vec3 view = eye.subtract(wind.dirX() * sway, 0.0, wind.dirZ() * sway);
            float alpha = s.presence() * day * intensity * flicker * fade(bottom, eye, fogEnd) * (s.thin ? 0.6F : 1.0F);
            if (alpha <= 0.003F) {
                continue;
            }
            ribbon(consumer, s, bottom, view, sun, coreWidth, coreAlpha * alpha * airAlpha, soft, shorten, fogEnd);
            ribbon(consumer, s, bottom, view, sun, haloWidth, haloAlpha * alpha * airAlpha, soft, shorten, fogEnd);
            groundGlow(consumer, level, s, bottom, view, 1.0F + 0.8F * soft, 0.30F * (1.0F - 0.4F * soft) * alpha * SunLight.lookScatter());
        }
        buffers.endBatch(AddLight.renderType());
    }

    /**
     * カメラを向く帯(筋の中心線のまわりで回す)。{@code shorten} は上端から薄くする割合の上乗せ(正午の短い筋)。横は 縁 0 → 中ほど → 中心 の 5 点で山なりに、縦は上端(林冠)で 0 から湧き、下端の手前で弱まる。
     * 筋の軸に沿って見ている(真下から見上げる)ほど薄くする(帯の向きが少しの揺れで大きく回り、画面いっぱいのにじみがちらつくので)。
     */
    private static void ribbon(VertexConsumer c, Shaft s, Vec3 bottom, Vec3 eye, Vec3 sun, float widthScale, float alpha, float soft, float shorten, float fogEnd) {
        final int rows = 8;
        Vec3 axis = bottom.subtract(s.top);
        float mid = Mth.lerp(soft, 0.55F, 0.8F);
        // 上端から湧き出す長さ。正午は shorten だけ長く湧かせて、見える筋を短くする。
        float emerge = 0.22F + 0.15F * soft + shorten;
        float sink = 0.45F + 0.2F * soft;
        float[] prev = null;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            Vec3 p = s.top.add(axis.scale(t));
            Vec3 toEye = eye.subtract(p);
            Vec3 side = axis.cross(toEye);
            double len = side.length();
            if (len < 1.0E-5) {
                prev = null;
                continue;
            }
            float half = halfWidth(s.width, t) * widthScale;
            double sinAngle = len / (axis.length() * toEye.length());
            float facing = SunLight.smooth(Mth.clamp((float) ((sinAngle - 0.12) / 0.33), 0.0F, 1.0F));
            side = side.scale(half / len);
            float a = alpha * facing * SunLight.smooth(Mth.clamp(t / emerge, 0.0F, 1.0F))
                    * (1.0F - sink * SunLight.smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F)))
                    * longFade(p, eye, fogEnd)
                    * SunLight.phase(-toEye.x, -toEye.y, -toEye.z, sun);
            // 1 を超えると頂点の色の 1 バイトがあふれて暗くなる(太陽の方では芯 0.28 × 3 倍で超える)。
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
                quad(c, prev, 0, 0.0F, row, 0, 0.0F, prev, 3, pa * mid, row, 3, a * mid);
                quad(c, prev, 3, pa * mid, row, 3, a * mid, prev, 6, pa, row, 6, a);
                quad(c, prev, 6, pa, row, 6, a, prev, 9, pa * mid, row, 9, a * mid);
                quad(c, prev, 9, pa * mid, row, 9, a * mid, prev, 12, 0.0F, row, 12, 0.0F);
            }
            prev = row;
        }
    }

    private static void quad(VertexConsumer c, float[] p0, int i0, float a0, float[] p1, int i1, float a1,
                             float[] p2, int i2, float a2, float[] p3, int i3, float a3) {
        c.addVertex(p0[i0], p0[i0 + 1], p0[i0 + 2]).setColor(RGB[0], RGB[1], RGB[2], a0);
        c.addVertex(p1[i1], p1[i1 + 1], p1[i1 + 2]).setColor(RGB[0], RGB[1], RGB[2], a1);
        c.addVertex(p3[i3], p3[i3 + 1], p3[i3 + 2]).setColor(RGB[0], RGB[1], RGB[2], a3);
        c.addVertex(p2[i2], p2[i2 + 1], p2[i2 + 2]).setColor(RGB[0], RGB[1], RGB[2], a2);
    }

    /**
     * 光が落ちた所の地面のにじみ(中心が明るく、半径で 0 になる丸)。マスごとにそのマスの地面の上面に置き、落ちた所と高さが
     * 0.6 ブロック以上違うマスには置かない(段の外へはみ出した板が宙に浮かないように)。
     */
    private static void groundGlow(VertexConsumer c, ClientLevel level, Shaft s, Vec3 bottom, Vec3 eye, float radScale, float alpha) {
        float rad = halfWidth(s.width, 1.0F) * 2.2F * radScale;
        int x0 = Mth.floor(bottom.x - rad);
        int x1 = Mth.floor(bottom.x + rad);
        int z0 = Mth.floor(bottom.z - rad);
        int z1 = Mth.floor(bottom.z + rad);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                float a00 = glowAt(x, z, bottom, rad);
                float a01 = glowAt(x, z + 1, bottom, rad);
                float a11 = glowAt(x + 1, z + 1, bottom, rad);
                float a10 = glowAt(x + 1, z, bottom, rad);
                if (a00 + a01 + a11 + a10 <= 0.0F) {
                    continue;
                }
                float h = s.glowHeight(level, x, z);
                if (Float.isNaN(h) || Math.abs(h - s.groundY) > 0.6) {
                    continue;
                }
                float y = (float) (h + 0.04 - eye.y);
                float fx0 = (float) (x - eye.x);
                float fz0 = (float) (z - eye.z);
                c.addVertex(fx0, y, fz0).setColor(RGB[0], RGB[1], RGB[2], alpha * a00);
                c.addVertex(fx0, y, fz0 + 1.0F).setColor(RGB[0], RGB[1], RGB[2], alpha * a01);
                c.addVertex(fx0 + 1.0F, y, fz0 + 1.0F).setColor(RGB[0], RGB[1], RGB[2], alpha * a11);
                c.addVertex(fx0 + 1.0F, y, fz0).setColor(RGB[0], RGB[1], RGB[2], alpha * a10);
            }
        }
    }

    private static float glowAt(int x, int z, Vec3 bottom, float rad) {
        double dx = x - bottom.x;
        double dz = z - bottom.z;
        float f = Math.max(0.0F, 1.0F - (float) (Math.sqrt(dx * dx + dz * dz) / rad));
        return f * f;
    }

    /** 芯の帯の幅の倍率。ぼかすほど太い。 */
    static float widthScale(float soft) {
        return 1.0F + 1.6F * soft;
    }

    /** 筋の半分の幅。地面へ向かうほど少し広がる。 */
    static float halfWidth(float width, float t) {
        return width * (0.75F + 0.5F * t);
    }

    /** 長い筋(巨大樹の傘の穴から 100 ブロック以上落ちる)の、目から遠い部分を霧の中へ消す(加算なので霧の色へ寄せる代わりに薄くする)。 */
    private static float longFade(Vec3 p, Vec3 eye, float fogEnd) {
        double d = Math.sqrt(p.distanceToSqr(eye));
        float far = Math.max(56.0F, Math.min(fogEnd * 0.9F, 110.0F));
        return 1.0F - SunLight.smooth(Mth.clamp((float) ((d - far * 0.55) / (far * 0.45)), 0.0F, 1.0F));
    }

    /** 遠い筋を霧の手前で消す。 */
    private static float fade(Vec3 p, Vec3 eye, float fogEnd) {
        double d = Math.sqrt(p.distanceToSqr(eye));
        float far = Math.max(16.0F, Math.min(fogEnd, RADIUS + 10.0F));
        return 1.0F - SunLight.smooth(Mth.clamp((float) ((d - far * 0.6) / (far * 0.4)), 0.0F, 1.0F));
    }

    private static double horizontalDist(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private LightShafts() {
    }
}
