package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>洞窟の呼吸</b>(2026-11-08 境目の帯 1。11-07 ユーザー採用・11-08「この形で始める」)。洞窟の口(地上と地下の境目)で、卓越風の向きに合わせて空気が出入りして見える。
 * 設定: 地下の汚染は胞子が流れ込むから起きる。それを口の所で目に見せ、地上と地下に一本の「流れ」を通す。クライアントだけの見た目(素材・音・通信なし)。
 * <ul>
 *     <li><b>口を探す</b>: カメラのまわり {@code radius} の空気のマスを毎 tick {@code scanPerTick} か所試す。空の光が {@code skyMin}〜{@code skyMax} で
 *     上に厚い岩({@link CaveMap#thickRoofAbove})がある所(口の内側)から、8 方向に {@code reachMax} まで空気をたどり、上に岩の天井が無い所(外)に
 *     いちばん早く出る向きを「口の外向き」にする。真上へ開いた縦穴は横の向きが無いので選ばれない(風は水平)。奥へ続く空気の長さ({@code depthMax} まで)も測る。</li>
 *     <li><b>出入り</b>: 卓越風の向き({@link Wind#dirX}・{@link Wind#dirZ}。サーバーの卓越風に寄る)と口の外向きの内積で決める。風が口へ吹き込む(内積が負)なら
 *     外の胞子・埃の粒が口の外から中へ流れ込み、口から吹き出す(正)なら中の湿った空気が霞になって出ていく。{@code flowThreshold} 未満(風が口と平行)は凪。
 *     量はその所の風の強さ({@link Wind#strength})で増え、変わり目は 2 秒ほどで寄せる。</li>
 *     <li><b>粒の量と色</b>: 流れ込む粒の数はその所の胞子の濃さ({@link SporeAir#levelAt}、サーバーから届く升の値)で増える。胞子が無くても
 *     {@code dustFloor} の割合は埃として出す。色は濃さで埃 → 胞子の色へ寄せる。上限は画質の段で {@code moteCounts}・{@code hazeCounts}。</li>
 *     <li><b>明るさ</b>: 粒の所の空の光(× 昼夜)とブロックの光で暗くする(奥へ流れ込むほど暗がりに沈む)。遠くは霧で薄まる({@link LifeSprites})。
 *     岩に入った粒は消す。</li>
 * </ul>
 * 値は {@code atmosphere.json} の節 {@code caveBreath}(すべて仮決め)。確かめは {@code cavedev breath in|out|auto|off|on|info}。
 */
final class CaveBreath {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final double[][] DIRS = dirs();
    /** 口の内側から奥へ続く空気がこれより短い所は口にしない(口の脇の壁ぎわ。11-08 撮影で直した)。 */
    private static final int MIN_DEPTH = 3;
    /** 口の幅を測る片側の上限(ブロック)。 */
    private static final int MAX_SIDE = 6;
    private static final List<Vent> VENTS = new ArrayList<>();
    private static final List<Puff> PUFFS = new ArrayList<>();
    private static int motes;
    private static int hazes;
    private static long tickNanos;
    private static long tickCount;
    private static long renderNanos;
    private static long renderCount;
    /** 確かめ用: 描かない(cavedev breath off)。 */
    static boolean devOff;
    /** 確かめ用: 風に関わらず出入りを決める(1 = 吹き込み、−1 = 吹き出し、0 = 風のまま)。 */
    static int devForce;
    /** 確かめ用: 過繁茂の霞を前の緑寄せの色にする(cavedev breath oldcolor on。11-08 の撮り比べ)。 */
    static boolean devOldHazeColor;

    /** 口 1 つ。(x, y, z) は口の内側の空気のマスの床の真ん中、(ox, oz) は外向きの単位ベクトル。 */
    private static final class Vent {
        double x;
        double y;
        double z;
        double ox;
        double oz;
        /** 内側のマスから外のマスまでの長さ(ブロック)。 */
        int reach;
        /** 内側のマスから奥へ続く空気の長さ(ブロック)。 */
        int depth;
        /** 口の幅(外向きに直交する向きの、内側のマスからの両側の端。ブロック)。 */
        double sideMin;
        double sideMax;
        /** 出入り(+ = 吹き込み、− = 吹き出し。寄せた値)。 */
        float flow;
        float spore;
        boolean overgrown;
        long checkedAt;
        /** 縦穴(天井の穴。11-08 ユーザー決定: 昼は冷たい空気が霞で上へ出て、夜は外の空気が粒で下りる)。(x, y, z) は穴の下の床。 */
        boolean vertical;
        /** 縦穴: 洞窟の天井の下面の高さ・穴の上(地表)の高さ・穴の半径。 */
        double roofY;
        double top;
        double holeR;
    }

    /** 流れる粒(haze = 霞の塊)。 */
    private static final class Puff {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        int age;
        int life;
        float seed;
        float light = 0.5F;
        float r;
        float g;
        float b;
        boolean haze;
        /** 過繁茂の洞窟の、白く冷たい霞(明るさの下限 overgrownHazeLight を使う)。 */
        boolean cool;
    }

    private CaveBreath() {
    }

    private static double[][] dirs() {
        double[][] d = new double[8][];
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            d[i] = new double[]{Math.cos(a), Math.sin(a)};
        }
        return d;
    }

    static void clear() {
        VENTS.clear();
        PUFFS.clear();
        motes = 0;
        hazes = 0;
    }

    private static boolean on(ClientLevel level) {
        return !devOff && DesignData.atmosphere().caveBreath().enabled() && VisualQuality.motes() && level.dimensionType().hasSkyLight();
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, LocalPlayer player) {
        if (!on(level)) {
            clear();
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereEye.CaveBreathParams p = DesignData.atmosphere().caveBreath();
        long now = level.getGameTime();
        double time = Atmosphere.time(0.0F);
        Wind wind = Wind.current();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double px = player.getX();
        double pz = player.getZ();
        double keep = (p.radius() + 8.0) * (p.radius() + 8.0);
        double sunY = SunLight.sunDirection(level, 1.0F).y;

        // 口の出入りを寄せ、遠い・崩れた口を捨てる
        for (Iterator<Vent> it = VENTS.iterator(); it.hasNext(); ) {
            Vent v = it.next();
            double dx = v.x - px;
            double dz = v.z - pz;
            if (dx * dx + dz * dz > keep) {
                it.remove();
                continue;
            }
            if (now - v.checkedAt >= 40L) {
                v.checkedAt = now;
                int bx = Mth.floor(v.x);
                int by = Mth.floor(v.y);
                int bz = Mth.floor(v.z);
                boolean roofed = CaveMap.thickRoofAbove(level, bx, by + 1, bz, 16);
                if (!level.hasChunk(bx >> 4, bz >> 4) || !passable(level, pos, bx, by, bz) || roofed == v.vertical) {
                    it.remove();
                    continue;
                }
                v.spore = sporeAt(v.x, v.z);
            }
            double dot = -(wind.dirX() * v.ox + wind.dirZ() * v.oz); // + = 風が口へ吹き込む
            float target;
            if (devForce != 0) {
                target = devForce;
            } else if (v.vertical) {
                // 縦穴は昼夜の温度差: 太陽が高いほど洞窟の冷たい空気が出て(−)、夜は外の空気が下りる(+)。日の出・日の入りのころは凪
                target = -(float) Mth.clamp(sunY / p.thermalSun(), -1.0, 1.0) * p.thermalGain();
            } else {
                double a = Math.abs(dot);
                target = a < p.flowThreshold() ? 0.0F : (float) (Math.signum(dot) * (a - p.flowThreshold()) / Math.max(0.05, 1.0 - p.flowThreshold()));
                double strength = wind.strength(v.x, v.z, time);
                target *= (float) Mth.clamp(0.35 + strength * p.windGain(), 0.0, 1.5);
            }
            v.flow += (target - v.flow) * 0.025F;
        }

        // 口を探す
        int cap = p.maxVents();
        boolean near = CaveLights.mix() > 0.05F || CaveMap.nearRoof();
        if (near && VENTS.size() < cap) {
            for (int i = 0; i < p.scanPerTick() && VENTS.size() < cap; i++) {
                tryFind(level, player, p, pos, now);
            }
        }

        // 粒・霞を出す
        int tier = Math.min(2, VisualQuality.atmosphereTier());
        int moteCap = p.moteCounts()[tier];
        int hazeCap = p.hazeCounts()[tier];
        for (Vent v : VENTS) {
            if (v.flow > 0.05F && motes < moteCap) {
                float n = p.inRate() * v.flow * (p.dustFloor() + (1.0F - p.dustFloor()) * v.spore);
                int k = (int) n + (RANDOM.nextFloat() < n - (int) n ? 1 : 0);
                for (int i = 0; i < k && motes < moteCap; i++) {
                    if (v.vertical) {
                        spawnFallingMote(level, v, p, pos);
                    } else {
                        spawnMote(level, v, p, pos);
                    }
                }
            } else if (v.flow < -0.05F && hazes < hazeCap) {
                float n = p.outRate() * -v.flow;
                if (RANDOM.nextFloat() < n) {
                    if (v.vertical) {
                        spawnRisingHaze(level, v, p, pos);
                    } else {
                        spawnHaze(level, v, p, pos);
                    }
                }
            }
        }

        // 動かす
        float skyDarken = level.getSkyDarken(1.0F);
        for (Iterator<Puff> it = PUFFS.iterator(); it.hasNext(); ) {
            Puff f = it.next();
            f.age++;
            double wob = f.age * 0.07 + f.seed * 6.0;
            f.x += f.vx + Math.sin(wob) * 0.006;
            f.y += f.vy + Math.sin(wob * 1.3 + 1.0) * 0.004;
            f.z += f.vz + Math.cos(wob * 0.9) * 0.006;
            boolean dead = f.age >= f.life;
            if (!dead && (f.age & 3) == 0) {
                int bx = Mth.floor(f.x);
                int by = Mth.floor(f.y);
                int bz = Mth.floor(f.z);
                if (!passable(level, pos, bx, by, bz)) {
                    dead = true; // 岩に入った
                } else if ((f.age & 7) == 0) {
                    float sky = level.getBrightness(LightLayer.SKY, pos.set(bx, by, bz)) / 15.0F * skyDarken;
                    float block = level.getBrightness(LightLayer.BLOCK, pos) / 15.0F;
                    float lit = 0.1F + 0.9F * Math.max(sky, block);
                    // 暗くなるのはゆっくり(口から入った埃は外の光をしばらく抱えて見える。11-08 撮影で中から見えなかったので)
                    f.light += (lit - f.light) * (lit < f.light ? 0.12F : 0.5F);
                }
            }
            if (dead) {
                if (f.haze) {
                    hazes--;
                } else {
                    motes--;
                }
                it.remove();
            }
        }
        tickNanos += System.nanoTime() - t0;
        tickCount++;
    }

    private static boolean passable(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        BlockState s = level.getBlockState(pos.set(x, y, z));
        return (s.isAir() || s.getCollisionShape(level, pos).isEmpty()) && s.getFluidState().isEmpty();
    }

    /** まわりの空気のマスを 1 つ試し、口の内側なら外向きと奥行きを測って口を足す。 */
    private static void tryFind(ClientLevel level, LocalPlayer player, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos, long now) {
        int r = p.radius();
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(r * 2 + 1) - r;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(r * 2 + 1) - r;
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }
        // 列をカメラの高さ +8〜−8 で上から見て、口の内側の床(通れるマスで下が固い。苔の敷物・蔦のマスも通れる)を探す
        // (11-08: 前は 1 マスだけ試して空気でなければ捨てていたので、汚染された口では見つからず、切り替えの後 15 秒たっても口が 0 だった)
        int eyeY = Mth.floor(player.getY());
        int y = Integer.MIN_VALUE;
        for (int cy = eyeY + 8; cy >= eyeY - 8; cy--) {
            if (!passable(level, pos, x, cy, z) || passable(level, pos, x, cy - 1, z)) {
                continue;
            }
            int sky = level.getBrightness(LightLayer.SKY, pos.set(x, cy, z));
            if (sky >= p.skyMin() && sky <= p.skyMax() && CaveMap.thickRoofAbove(level, x, cy + 1, z, 16)) {
                y = cy;
                break;
            }
            if (sky >= p.shaftSkyMin() && tryShaft(level, x, cy, z, p, pos, now)) {
                return; // 縦穴の下の床(11-08)
            }
        }
        if (y == Integer.MIN_VALUE) {
            return;
        }
        for (Vent v : VENTS) {
            double dx = v.x - (x + 0.5);
            double dy = v.y - y;
            double dz = v.z - (z + 0.5);
            if (dx * dx + dy * dy * 4.0 + dz * dz < 25.0) {
                return; // 近くにもう口がある
            }
        }
        // 外向き: 8 方向に空気をたどり、上に岩の天井が無いマスにいちばん早く出る向き
        int[] reach = new int[8];
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            reach[i] = Integer.MAX_VALUE;
            for (int s = 1; s <= p.reachMax(); s++) {
                int cx = x + (int) Math.round(DIRS[i][0] * s);
                int cz = z + (int) Math.round(DIRS[i][1] * s);
                if (!level.hasChunk(cx >> 4, cz >> 4) || !passable(level, pos, cx, y, cz) && !passable(level, pos, cx, y + 1, cz)) {
                    break;
                }
                if (!CaveMap.thickRoofAbove(level, cx, y + 2, cz, 16)) {
                    reach[i] = s;
                    break;
                }
            }
            best = Math.min(best, reach[i]);
        }
        if (best == Integer.MAX_VALUE) {
            return;
        }
        double ox = 0.0;
        double oz = 0.0;
        for (int i = 0; i < 8; i++) {
            if (reach[i] <= best + 1) {
                double w = 1.0 / reach[i];
                ox += DIRS[i][0] * w;
                oz += DIRS[i][1] * w;
            }
        }
        double len = Math.sqrt(ox * ox + oz * oz);
        if (len < 1.0e-3) {
            return; // 四方が外(天井の小さな張り出しの下)
        }
        ox /= len;
        oz /= len;
        // 外のマスが縦穴の下(まわりを天井に囲まれている)なら、それは横の口でなく縦穴(tryShaft が受け持つ)。11-08 撮影: 縦穴の部屋の隅が穴の方を向いた横の口になった
        int ex = Mth.floor(x + 0.5 + ox * best);
        int ez = Mth.floor(z + 0.5 + oz * best);
        if (roofedAround(ex, y, ez) >= 6) {
            return;
        }
        int depth = 0;
        for (int s = 1; s <= p.depthMax(); s++) {
            int cx = Mth.floor(x + 0.5 - ox * s);
            int cz = Mth.floor(z + 0.5 - oz * s);
            if (!level.hasChunk(cx >> 4, cz >> 4) || !passable(level, pos, cx, y, cz) && !passable(level, pos, cx, y + 1, cz)) {
                break;
            }
            depth = s;
        }
        if (depth < MIN_DEPTH) {
            return; // 口の脇の壁ぎわ(外向きが斜めに出て奥へ続かない)。口の真ん中のマスで取り直す
        }
        // 口の幅: 内側のマスの段で、外向きに直交する向きへ両側に空気をたどる(粒・霞を口の幅いっぱいに出す。片側 MAX_SIDE まで)
        double sideMin = 0.0;
        double sideMax = 0.0;
        for (int sign = -1; sign <= 1; sign += 2) {
            for (int s = 1; s <= MAX_SIDE; s++) {
                if (!passable(level, pos, Mth.floor(x + 0.5 - oz * s * sign), y, Mth.floor(z + 0.5 + ox * s * sign))) {
                    break;
                }
                if (sign < 0) {
                    sideMin = -s;
                } else {
                    sideMax = s;
                }
            }
        }
        Vent v = new Vent();
        v.x = x + 0.5;
        v.y = y;
        v.z = z + 0.5;
        v.ox = ox;
        v.oz = oz;
        v.reach = best;
        v.depth = depth;
        v.sideMin = sideMin - 0.4;
        v.sideMax = sideMax + 0.4;
        v.spore = sporeAt(v.x, v.z);
        v.overgrown = ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, y, z)));
        v.checkedAt = now;
        VENTS.add(v);
    }

    private static final int[][] RING8 = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** (x, z) のまわり 4・7 ブロックの輪で、床 y より上に岩の天井がある方向の数(多い方の輪。地下の地図を読むだけ)。 */
    private static int roofedAround(int x, int y, int z) {
        int most = 0;
        for (int ring : new int[]{4, 7}) {
            int roofed = 0;
            for (int[] d : RING8) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                float[] t = CaveMap.texel(x + (int) Math.round(d[0] * ring / len), z + (int) Math.round(d[1] * ring / len));
                if (t != null && t[3] > y + 1.0F && t[3] < y + 40.0F) {
                    roofed++;
                }
            }
            most = Math.max(most, roofed);
        }
        return most;
    }

    /**
     * 縦穴(洞窟の天井に空いた、空へ抜ける穴)の下の床か(11-08 ユーザー決定)。床 (x, y, z) の列の上に岩が無く、まわり 4・7 ブロックの輪のどちらかで
     * 8 方向のうち 6 方向以上の列に床より上の岩の天井がある(地下の地図 {@link CaveMap#texel} の天井の下面で見る。地図を読むだけなので外の平地でも軽い)。
     * 天井の下面の高さ(輪の天井の下面のいちばん低いもの)・穴の上(輪の岩の上面のいちばん高いもの)・穴の半径(天井の下面の 1 段上で東西南北にたどる)を測って口にする。
     */
    private static boolean tryShaft(ClientLevel level, int x, int y, int z, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos, long now) {
        float[] own = CaveMap.texel(x, z);
        if (own == null || own[0] > y + 1.0F) {
            return false; // この列の上に岩がある(穴の下でない)
        }
        double roofY = Double.MAX_VALUE;
        double top = -Double.MAX_VALUE;
        boolean enclosed = false;
        for (int ring : new int[]{4, 7}) {
            int roofed = 0;
            for (int[] d : RING8) {
                double len = d[0] != 0 && d[1] != 0 ? Math.sqrt(2.0) : 1.0;
                float[] t = CaveMap.texel(x + (int) Math.round(d[0] * ring / len), z + (int) Math.round(d[1] * ring / len));
                if (t != null && t[3] > y + 1.0F && t[3] < y + 40.0F) {
                    roofed++;
                    roofY = Math.min(roofY, t[3]);
                    top = Math.max(top, t[0]);
                }
            }
            if (roofed >= 6) {
                enclosed = true;
                break;
            }
        }
        if (!enclosed || top - roofY < 2.0 || roofY - y < 2.0) {
            return false;
        }
        for (Vent v : VENTS) {
            double dx = v.x - (x + 0.5);
            double dz = v.z - (z + 0.5);
            if (dx * dx + dz * dz < 36.0 && Math.abs(v.y - y) < 12.0) {
                return true; // 同じ穴(探したことにする)
            }
        }
        int hy = (int) Math.floor(roofY) + 1;
        double r = 0.0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int s = 0;
            while (s < 6 && passable(level, pos, x + d[0] * (s + 1), hy, z + d[1] * (s + 1))) {
                s++;
            }
            r += s;
        }
        Vent v = new Vent();
        v.vertical = true;
        v.x = x + 0.5;
        v.y = y;
        v.z = z + 0.5;
        v.roofY = roofY;
        v.top = top;
        v.holeR = Math.max(0.6, r / 4.0 + 0.4);
        v.depth = (int) (roofY - y);
        v.spore = sporeAt(v.x, v.z);
        v.overgrown = ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, y, z)));
        v.checkedAt = now;
        VENTS.add(v);
        return true;
    }

    /** 縦穴の夜: 穴の上(地表の 0〜2 ブロック上)に粒を出し、床まで下ろす。 */
    private static void spawnFallingMote(ClientLevel level, Vent v, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos) {
        double a = RANDOM.nextDouble() * Mth.TWO_PI;
        double d = Math.sqrt(RANDOM.nextDouble()) * v.holeR;
        double x = v.x + Math.cos(a) * d;
        double z = v.z + Math.sin(a) * d;
        double y = v.top + RANDOM.nextDouble() * 2.0;
        if (!passable(level, pos, Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return;
        }
        Puff f = new Puff();
        double speed = p.inSpeed() * 0.6 * (0.7 + 0.6 * RANDOM.nextDouble()) * (0.6 + 0.4 * Math.min(1.0, v.flow));
        f.x = x;
        f.y = y;
        f.z = z;
        f.vx = (RANDOM.nextDouble() - 0.5) * 0.01;
        f.vz = (RANDOM.nextDouble() - 0.5) * 0.01;
        f.vy = -speed;
        f.life = (int) Math.min(900, (y - v.y + 0.5) / speed);
        f.seed = RANDOM.nextFloat();
        colorMote(f, v, p);
        f.light = 0.8F;
        PUFFS.add(f);
        motes++;
    }

    /** 縦穴の昼: 天井の下面のすぐ下に霞を出し、穴を昇らせて地表の上でほどく。 */
    private static void spawnRisingHaze(ClientLevel level, Vent v, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos) {
        double a = RANDOM.nextDouble() * Mth.TWO_PI;
        double d = Math.sqrt(RANDOM.nextDouble()) * v.holeR * 0.8;
        double x = v.x + Math.cos(a) * d;
        double z = v.z + Math.sin(a) * d;
        double y = v.roofY - 1.5 + RANDOM.nextDouble() * 1.5;
        if (!passable(level, pos, Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return;
        }
        Puff f = new Puff();
        double speed = p.outSpeed() * 1.8 * (0.7 + 0.6 * RANDOM.nextDouble()) * (0.6 + 0.4 * Math.min(1.0, -v.flow));
        f.x = x;
        f.y = y;
        f.z = z;
        f.vx = (RANDOM.nextDouble() - 0.5) * 0.008;
        f.vz = (RANDOM.nextDouble() - 0.5) * 0.008;
        f.vy = speed;
        f.life = (int) Math.min(1200, (v.top - y + 6.0 + RANDOM.nextDouble() * 4.0) / speed); // 地表の 6〜10 上でほどける
        f.seed = RANDOM.nextFloat();
        f.haze = true;
        colorHaze(f, v, p);
        f.light = 0.4F;
        PUFFS.add(f);
        hazes++;
    }

    private static void colorMote(Puff f, Vent v, AtmosphereEye.CaveBreathParams p) {
        float[] dust = p.dustColor();
        float[] spore = p.sporeColor();
        float s = Mth.clamp(v.spore * 1.5F, 0.0F, 1.0F);
        f.r = Mth.lerp(s, dust[0], spore[0]);
        f.g = Mth.lerp(s, dust[1], spore[1]);
        f.b = Mth.lerp(s, dust[2], spore[2]);
    }

    /**
     * 霞の色。過繁茂の洞窟は {@code overgrownHazeColor}(11-08 ユーザー決定: 緑の霧の中でも見分けられるよう、霧より少し白く冷たく)、ほかは {@code hazeColor}。
     * 確かめ用の {@code cavedev breath oldcolor on} で前の形(hazeColor から赤と青を 0.06 引いた緑寄せ)。
     */
    private static void colorHaze(Puff f, Vent v, AtmosphereEye.CaveBreathParams p) {
        float[] c = v.overgrown && !devOldHazeColor ? p.overgrownHazeColor() : p.hazeColor();
        float tint = v.overgrown && devOldHazeColor ? 0.06F : 0.0F;
        f.cool = v.overgrown && !devOldHazeColor;
        f.r = c[0] - tint;
        f.g = c[1];
        f.b = c[2] - tint;
    }

    /** その所の胞子の濃さ(0〜1)。確かめ用に {@code cavedev air <度合い>} で固定した値があればそれ(平らな試験のワールドでは胞子の場が 0 のまま撮れないので)。 */
    private static float sporeAt(double x, double z) {
        return SporeAir.devLevel >= 0.0F ? Math.min(1.0F, SporeAir.devLevel) : SporeAir.levelAt(x, z);
    }

    /** 吹き込み: 口の外(外の 0.5〜3.5 ブロック)に粒を出し、中へ流す。 */
    private static void spawnMote(ClientLevel level, Vent v, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos) {
        double out = v.reach + 0.5 + RANDOM.nextDouble() * 3.0;
        double side = Mth.lerp(RANDOM.nextDouble(), v.sideMin, v.sideMax);
        double x = v.x + v.ox * out - v.oz * side;
        double z = v.z + v.oz * out + v.ox * side;
        double y = v.y + 0.15 + RANDOM.nextDouble() * 2.2;
        if (!passable(level, pos, Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return;
        }
        Puff f = new Puff();
        double speed = p.inSpeed() * (0.7 + 0.6 * RANDOM.nextDouble()) * (0.6 + 0.4 * Math.min(1.0, v.flow));
        f.x = x;
        f.y = y;
        f.z = z;
        f.vx = -v.ox * speed;
        f.vz = -v.oz * speed;
        f.vy = -0.003 - RANDOM.nextDouble() * 0.004; // 胞子・埃はゆっくり沈む
        f.life = (int) Math.min(600, (out + v.depth * (0.5 + 0.5 * RANDOM.nextDouble()) + 1.0) / speed);
        f.seed = RANDOM.nextFloat();
        float[] dust = p.dustColor();
        float[] spore = p.sporeColor();
        float s = Mth.clamp(v.spore * 1.5F, 0.0F, 1.0F);
        f.r = Mth.lerp(s, dust[0], spore[0]);
        f.g = Mth.lerp(s, dust[1], spore[1]);
        f.b = Mth.lerp(s, dust[2], spore[2]);
        f.light = 0.8F;
        PUFFS.add(f);
        motes++;
    }

    /** 吹き出し: 口の内側の奥(6 ブロックまで)に霞の塊を出し、外へ流す。 */
    private static void spawnHaze(ClientLevel level, Vent v, AtmosphereEye.CaveBreathParams p, BlockPos.MutableBlockPos pos) {
        double back = RANDOM.nextDouble() * Math.min(v.depth, 6);
        double side = Mth.lerp(RANDOM.nextDouble(), v.sideMin + 0.3, v.sideMax - 0.3);
        double x = v.x - v.ox * back - v.oz * side;
        double z = v.z - v.oz * back + v.ox * side;
        double y = v.y + 0.4 + RANDOM.nextDouble() * 1.3;
        if (!passable(level, pos, Mth.floor(x), Mth.floor(y), Mth.floor(z))) {
            return;
        }
        Puff f = new Puff();
        double speed = p.outSpeed() * (0.7 + 0.6 * RANDOM.nextDouble()) * (0.6 + 0.4 * Math.min(1.0, -v.flow));
        f.x = x;
        f.y = y;
        f.z = z;
        f.vx = v.ox * speed;
        f.vz = v.oz * speed;
        f.vy = 0.002 + RANDOM.nextDouble() * 0.003; // 外へ出ながらほどけて少し昇る
        f.life = (int) Math.min(900, (back + v.reach + 4.0 + RANDOM.nextDouble() * 3.0) / speed);
        f.seed = RANDOM.nextFloat();
        f.haze = true;
        colorHaze(f, v, p);
        f.light = 0.4F;
        PUFFS.add(f);
        hazes++;
    }

    /** AFTER_PARTICLES({@link LifeSprites} の間)。 */
    static void render(ClientLevel level, float partialTick, Vector3f left, Vector3f up, double camX, double camY, double camZ) {
        if (PUFFS.isEmpty() || !on(level)) {
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereEye.CaveBreathParams p = DesignData.atmosphere().caveBreath();
        float dayLight = level.getSkyDarken(partialTick);
        for (Puff f : PUFFS) {
            double x = f.x + f.vx * partialTick;
            double y = f.y + f.vy * partialTick;
            double z = f.z + f.vz * partialTick;
            float age = f.age + partialTick;
            float fade = Mth.clamp(age / 20.0F, 0.0F, 1.0F) * Mth.clamp((f.life - age) / Math.max(20.0F, f.life * 0.3F), 0.0F, 1.0F);
            if (fade <= 0.0F) {
                continue;
            }
            float l = f.light;
            if (f.haze && f.cool) {
                l = Math.max(l, p.overgrownHazeLight() * dayLight); // 夜は下限も下げる(暗がりで光らない) // 過繁茂の霞は口の光を散らす湿った空気として明るさの下限を持つ(緑の霧の中で白く見せる。11-08)
            }
            // カメラに近いほど小さく・薄く(11-08 ユーザー決定: すぐ前の粒が四角く大きく見えた)
            double ddx = x - camX;
            double ddy = y - camY;
            double ddz = z - camZ;
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            if (f.haze) {
                fade *= SunLight.smooth(Mth.clamp((dist - 0.5F) / 2.5F, 0.0F, 1.0F));
                float t = Mth.clamp(age / f.life, 0.0F, 1.0F);
                float size = p.hazeSize() * (0.5F + 1.1F * t) * (0.8F + 0.4F * f.seed);
                LifeSprites.blob(x, y, z, size, 0.6F, left, up, f.r * l, f.g * l, f.b * l, p.hazeAlpha() * fade);
            } else {
                float twinkle = 0.75F + 0.25F * Mth.sin(age * 0.15F + f.seed * 40.0F);
                float near = SunLight.smooth(Mth.clamp((dist - p.nearFrom()) / Math.max(0.1F, p.nearTo() - p.nearFrom()), 0.0F, 1.0F));
                LifeSprites.dot(x, y, z, p.moteSize() * (0.7F + 0.6F * f.seed) * (0.35F + 0.65F * near), left, up, f.r * l, f.g * l, f.b * l,
                        p.moteAlpha() * fade * twinkle * (0.1F + 0.9F * near));
            }
        }
        renderNanos += System.nanoTime() - t0;
        renderCount++;
    }

    /** 確かめ用の一行(口・粒・霞の数と、1 tick・1 フレームの平均の時間。読むと数え直す)。 */
    static String describe() {
        int in = 0;
        int out = 0;
        StringBuilder vents = new StringBuilder();
        for (Vent v : VENTS) {
            in += v.flow > 0.05F ? 1 : 0;
            out += v.flow < -0.05F ? 1 : 0;
            if (vents.length() < 400) {
                vents.append(String.format(" [%s %.0f %.0f %.0f 外 %.2f,%.2f 口まで %d 奥 %d 出入り %+.2f 胞子 %.2f]", v.vertical ? "縦" : "横", v.x, v.y, v.z, v.ox, v.oz, v.reach, v.depth,
                        v.flow, v.spore));
            }
        }
        String s = String.format("洞窟の呼吸 口 %d(吹き込み %d・吹き出し %d)粒 %d 霞 %d 強制 %d 風 %.2f,%.2f tick %.3f ms 描く %.3f ms/フレーム%s", VENTS.size(), in,
                out, motes, hazes, devForce, Wind.current().dirX(), Wind.current().dirZ(), tickCount == 0 ? 0.0 : tickNanos / 1.0E6 / tickCount,
                renderCount == 0 ? 0.0 : renderNanos / 1.0E6 / renderCount, vents);
        tickNanos = 0L;
        tickCount = 0L;
        renderNanos = 0L;
        renderCount = 0L;
        return s;
    }
}
