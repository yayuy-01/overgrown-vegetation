package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereCaveLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>洞窟の口のカマドウマ</b>(2026-11-07 ユーザー採用の洞窟の小ネタ 2。設定: 洞窟の生き物も虫と爬虫類だけ)。
 *
 * <p>クライアントだけの描画(Mob にしない・当たり判定・通信なし・素材なし)。洞窟の口の近く = 上に厚い岩({@link CaveMap#thickRoofAbove})があり、
 * 空の光が {@code caveLife.crickets.skyMin}〜{@code skyMax}(15 段。口から奥へ十数ブロックまで)の空気のマスに接する壁と天井に、
 * {@code group} 匹ずつ固まって張り付く。カメラのまわりの数の上限は画質の段で {@code counts}。地上・洞窟の判定はカメラの地下モードに頼らない
 * (その所の空の光と上の岩で決める)。
 * <ul>
 *     <li>形(11-08 ゴキブリに見えたので直した): 頂点の色だけの四角で組む立体。背を丸く高くした弓なりの胴(幅は高さより狭い)、体長の 2.4 倍の触角、細い前脚と中脚、
 *     太く長い後ろ脚(腿を後ろ上へ上げ膝を背より上へ突き出す)。茶色(過繁茂では {@code overgrownTint} だけ緑へ寄せる)。1/16 ブロック幅の暗い縁取りで、暗がりでも輪郭が読める。</li>
 *     <li>逃げる: プレイヤーが {@code fleeDistance} に入ると、近くの別の面(床も)へ {@code hopSeconds} 秒の弧で跳ぶ。まだ近ければ続けて跳び
 *     ({@code hops} 回まで)、それでも近ければ消える。{@code respawnSeconds} 秒後に別の所へ。</li>
 * </ul>
 * 明るさはその所の空の光(× 昼夜)とブロックの光で暗くし、遠くは地下の霧で薄まる(地形と同じ)。値はすべて仮決め(節 {@code caveLife.crickets})。
 */
public final class CaveCrickets {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Cricket> CRICKETS = new ArrayList<>();
    private static long nextSpawnAt;
    private static int hopsTotal;
    /** 開発用({@code cavelife hold on|off}): 近づいても跳ばせない(横からのアップの写真用)。 */
    private static boolean devHold;
    private static long tickNanos;
    private static int ticks;
    private static long renderNanos;
    private static int renderFrames;

    /** 張り付いている面(岩のブロック + 外向き)。 */
    private record Spot(int bx, int by, int bz, Direction face, double u, double v) {
        /** 面の上の点(面から 0.012 浮かせる)。 */
        Vec3 point() {
            double cx = this.bx + 0.5 + this.face.getStepX() * 0.512;
            double cy = this.by + 0.5 + this.face.getStepY() * 0.512;
            double cz = this.bz + 0.5 + this.face.getStepZ() * 0.512;
            double[] ua = axisU(this.face);
            double[] va = axisV(this.face);
            return new Vec3(cx + ua[0] * this.u + va[0] * this.v, cy + ua[1] * this.u + va[1] * this.v, cz + ua[2] * this.u + va[2] * this.v);
        }
    }

    private static final class Cricket {
        Spot spot;
        double heading;
        float seed;
        float tint;
        float light = 1.0F;
        float alpha;
        /** 跳んでいる間: 元の面・跳び始めの時刻(-1 = 止まっている)。 */
        Spot from;
        long hopAt = -1L;
        int hops;
        long goneAt = -1L;
    }

    private CaveCrickets() {
    }

    static void clear() {
        CRICKETS.clear();
        nextSpawnAt = 0L;
    }

    static String describe() {
        int hopping = 0;
        for (Cricket c : CRICKETS) {
            hopping += c.hopAt >= 0L ? 1 : 0;
        }
        String s = String.format("カマドウマ %d(跳び中 %d・跳んだ回数 %d)tick %.3f ms 描画 %s", CRICKETS.size(), hopping, hopsTotal,
                tickNanos / 1.0e6 / Math.max(1, ticks), renderFrames > 0 ? String.format("%.3f ms/フレーム", renderNanos / 1.0e6 / renderFrames) : "-");
        tickNanos = 0L;
        ticks = 0;
        renderNanos = 0L;
        renderFrames = 0;
        return s;
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        AtmosphereCaveLife.CricketParams p = DesignData.atmosphere().caveLife().crickets();
        if (!p.enabled() || !VisualQuality.critters()) {
            clear();
            return;
        }
        long t0 = System.nanoTime();
        long now = level.getGameTime();
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long hopTicks = Math.max(1L, Math.round(p.hopSeconds() * 20.0));
        for (int i = CRICKETS.size() - 1; i >= 0; i--) {
            Cricket c = CRICKETS.get(i);
            Vec3 at = c.spot.point();
            double d2 = eye.distanceToSqr(at);
            if (d2 > (p.radius() + 12.0) * (p.radius() + 12.0) || c.goneAt >= 0L && now - c.goneAt > 8L) {
                CRICKETS.remove(i);
                continue;
            }
            if (c.hopAt >= 0L && now - c.hopAt >= hopTicks) {
                c.hopAt = -1L; // 着いた
            }
            if (!devHold && c.hopAt < 0L && c.goneAt < 0L && d2 < p.fleeDistance() * p.fleeDistance()) {
                if (c.hops >= p.hops()) {
                    c.goneAt = now;
                    nextSpawnAt = Math.max(nextSpawnAt, now + Math.round(p.respawnSeconds() * 20.0));
                } else {
                    Spot to = hopTarget(level, c.spot, eye, p, pos);
                    if (to != null) {
                        c.from = c.spot;
                        c.spot = to;
                        c.hopAt = now;
                        c.hops++;
                        c.heading = Math.atan2(-1.0, 0.0) + RANDOM.nextDouble() * Mth.TWO_PI;
                        hopsTotal++;
                        if (DEV_LOG) {
                            Vec3 a = c.from.point();
                            Vec3 b = to.point();
                            OvergrownVegetation.LOGGER.info("CaveCrickets 跳ぶ #{} {} {} {} → {} {} {}({})距離 {}", c.hops, String.format("%.1f", a.x),
                                    String.format("%.1f", a.y), String.format("%.1f", a.z), String.format("%.1f", b.x), String.format("%.1f", b.y),
                                    String.format("%.1f", b.z), to.face().getName(), String.format("%.1f", Math.sqrt(d2)));
                        }
                    } else {
                        c.goneAt = now; // 跳ぶ先が無い = 物陰へ消える
                        if (DEV_LOG) {
                            OvergrownVegetation.LOGGER.info("CaveCrickets 跳ぶ先が無く消える");
                        }
                    }
                }
            }
            if (now % 10L == 0L) {
                Vec3 q = c.spot.point();
                pos.set(Mth.floor(q.x + c.spot.face().getStepX() * 0.3), Mth.floor(q.y + c.spot.face().getStepY() * 0.3),
                        Mth.floor(q.z + c.spot.face().getStepZ() * 0.3));
                float sky = level.getBrightness(LightLayer.SKY, pos) / 15.0F * level.getSkyDarken(1.0F);
                float block = level.getBrightness(LightLayer.BLOCK, pos) / 15.0F;
                c.light = 0.3F + 0.7F * Math.max(sky, block);
            }
        }
        int cap = p.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        if (CRICKETS.size() + p.group() <= cap && now >= nextSpawnAt) {
            int added = trySpawnGroup(level, player, p, pos, false);
            nextSpawnAt = now + (added > 0 ? 40L : 4L);
        }
        tickNanos += System.nanoTime() - t0;
        ticks++;
    }

    /** まわりの空気のマスを試し、洞窟の口の条件に合えば、そこに接する壁・天井に group 匹を置く。force = 条件を見ない(開発用)。 */
    private static int trySpawnGroup(ClientLevel level, LocalPlayer player, AtmosphereCaveLife.CricketParams p, BlockPos.MutableBlockPos pos,
                                     boolean force) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        int r = Mth.ceil(p.radius());
        for (int tries = 0; tries < (force ? 200 : 6); tries++) {
            int x = Mth.floor(eye.x) + RANDOM.nextInt(2 * r + 1) - r;
            int y = Mth.floor(eye.y) + RANDOM.nextInt(force ? 7 : 13) - (force ? 3 : 5);
            int z = Mth.floor(eye.z) + RANDOM.nextInt(2 * r + 1) - r;
            if (force) {
                // 見ている向きにたどって、最初の岩の手前の空気のマス(そのまわりに置く)。
                int hx = Integer.MIN_VALUE;
                for (double s = 0.5; s < 32.0; s += 0.25) {
                    int qx = Mth.floor(eye.x + look.x * s);
                    int qy = Mth.floor(eye.y + look.y * s);
                    int qz = Mth.floor(eye.z + look.z * s);
                    if (CaveMap.isRock(level.getBlockState(pos.set(qx, qy, qz)))) {
                        double b = s - 0.3;
                        hx = Mth.floor(eye.x + look.x * b);
                        y = Mth.floor(eye.y + look.y * b);
                        z = Mth.floor(eye.z + look.z * b);
                        break;
                    }
                }
                if (hx == Integer.MIN_VALUE) {
                    return 0;
                }
                x = hx + (tries == 0 ? 0 : RANDOM.nextInt(3) - 1);
                y += tries == 0 ? 0 : RANDOM.nextInt(3) - 1;
                z += tries == 0 ? 0 : RANDOM.nextInt(3) - 1;
            }
            if (!mouthAir(level, x, y, z, p, pos, force)) {
                continue;
            }
            int added = 0;
            boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, y, z)));
            for (int k = 0; k < p.group() * 4 && added < p.group(); k++) {
                int ax = x + (k == 0 ? 0 : RANDOM.nextInt(5) - 2);
                int ay = y + (k == 0 ? 0 : RANDOM.nextInt(3) - 1);
                int az = z + (k == 0 ? 0 : RANDOM.nextInt(5) - 2);
                if (k > 0 && !mouthAir(level, ax, ay, az, p, pos, force)) {
                    continue;
                }
                Spot s = surfaceNear(level, ax, ay, az, false, pos);
                if (s == null) {
                    continue;
                }
                Vec3 q = s.point();
                double d = q.distanceTo(eye);
                if (!force && (d < p.fleeDistance() + 2.0 || d > p.radius() + 4.0 || !visible(level, eye, q, pos)
                        || d < 12.0 && q.subtract(eye).normalize().dot(look) > Math.cos(Math.toRadians(45.0)))) {
                    continue;
                }
                Cricket c = new Cricket();
                c.spot = s;
                c.heading = RANDOM.nextDouble() * Mth.TWO_PI;
                c.seed = RANDOM.nextFloat() * 100.0F;
                c.tint = overgrown ? p.overgrownTint() : 0.0F;
                CRICKETS.add(c);
                added++;
            }
            if (added > 0) {
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("CaveCrickets 出す {} 匹 そば {} {} {} 空の光 {}", added, x, y, z, level.getBrightness(LightLayer.SKY, pos.set(x, y, z)));
                }
                return added;
            }
        }
        return 0;
    }

    /** 洞窟の口の空気のマス: 空気で、空の光が skyMin〜skyMax、上に厚い岩。 */
    private static boolean mouthAir(ClientLevel level, int x, int y, int z, AtmosphereCaveLife.CricketParams p, BlockPos.MutableBlockPos pos, boolean force) {
        if (!level.hasChunkAt(pos.set(x, y, z)) || !level.getBlockState(pos).isAir()) {
            return false;
        }
        if (force) {
            return true;
        }
        int sky = level.getBrightness(LightLayer.SKY, pos);
        return sky >= p.skyMin() && sky <= p.skyMax() && CaveMap.thickRoofAbove(level, x, y + 1, z, 16);
    }

    /** 空気のマス (x, y, z) に接する岩の面(壁か天井。floor = 床も可)を 1 つ。 */
    private static Spot surfaceNear(ClientLevel level, int x, int y, int z, boolean floor, BlockPos.MutableBlockPos pos) {
        Direction[] dirs = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        int start = RANDOM.nextInt(dirs.length);
        for (int i = 0; i < dirs.length; i++) {
            Direction toRock = dirs[(start + i) % dirs.length];
            if (toRock == Direction.DOWN && !floor) {
                continue;
            }
            BlockState s = level.getBlockState(pos.set(x + toRock.getStepX(), y + toRock.getStepY(), z + toRock.getStepZ()));
            if (CaveMap.isRock(s) && s.canOcclude()) {
                return new Spot(x + toRock.getStepX(), y + toRock.getStepY(), z + toRock.getStepZ(), toRock.getOpposite(),
                        (RANDOM.nextDouble() - 0.5) * 0.6, (RANDOM.nextDouble() - 0.5) * 0.6);
            }
        }
        return null;
    }

    /** 跳ぶ先: 今の所から hopDistance 前後の、プレイヤーから遠ざかる側の空気のマスに接する面(床も)。 */
    private static Spot hopTarget(ClientLevel level, Spot from, Vec3 eye, AtmosphereCaveLife.CricketParams p, BlockPos.MutableBlockPos pos) {
        Vec3 a = from.point();
        Vec3 away = a.subtract(eye).multiply(1.0, 0.3, 1.0).normalize();
        for (int tries = 0; tries < 32; tries++) {
            double dist = p.hopDistance() * (0.6 + 0.8 * RANDOM.nextDouble());
            // 初めはプレイヤーから遠ざかる向き ±46°、見つからなければ横(±100°)まで広げる(狭い横穴でも壁に沿って逃げる)。
            double ang = (RANDOM.nextDouble() - 0.5) * (tries < 12 ? 1.6 : 3.5);
            double cx = away.x * Math.cos(ang) - away.z * Math.sin(ang);
            double cz = away.x * Math.sin(ang) + away.z * Math.cos(ang);
            int x = Mth.floor(a.x + cx * dist);
            int y = Mth.floor(a.y + (RANDOM.nextDouble() - 0.4) * 2.0);
            int z = Mth.floor(a.z + cz * dist);
            if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                continue;
            }
            Spot s = surfaceNear(level, x, y, z, true, pos);
            if (s != null && s.point().distanceTo(eye) > a.distanceTo(eye) - 0.5 && visible(level, a, s.point(), pos)) {
                return s;
            }
        }
        return null;
    }

    /** a から b まで空気(動きを止めるブロックが無い)か。0.5 ブロック刻み・両端を除く。 */
    private static boolean visible(ClientLevel level, Vec3 a, Vec3 b, BlockPos.MutableBlockPos pos) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        int steps = (int) Math.ceil(len / 0.5);
        for (int i = 1; i < steps - 1; i++) {
            double f = i / (double) steps;
            BlockState s = level.getBlockState(pos.set(Mth.floor(a.x + d.x * f), Mth.floor(a.y + d.y * f), Mth.floor(a.z + d.z * f)));
            if (!s.isAir() && s.blocksMotion()) {
                return false;
            }
        }
        return true;
    }

    /** 開発用: 近づいても跳ばせないか。 */
    public static void devHold(boolean on) {
        devHold = on;
    }

    /** 開発用(テストクライアントの {@code cavelife cricket}): 見ている向きの 4 ブロック先のまわりに、条件を見ずに 1 群れを置く。 */
    public static String devPlace(ClientLevel level, LocalPlayer player) {
        int added = trySpawnGroup(level, player, DesignData.atmosphere().caveLife().crickets(), new BlockPos.MutableBlockPos(), true);
        StringBuilder sb = new StringBuilder("置いた " + added);
        for (Cricket c : CRICKETS) {
            Vec3 q = c.spot.point();
            sb.append(String.format(" [%.2f %.2f %.2f %s]", q.x, q.y, q.z, c.spot.face().getName()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 描く

    /** 面の中の横の軸(壁は水平、床・天井は x)。 */
    static double[] axisU(Direction face) {
        return switch (face.getAxis()) {
            case X -> new double[]{0.0, 0.0, 1.0};
            case Z -> new double[]{1.0, 0.0, 0.0};
            case Y -> new double[]{1.0, 0.0, 0.0};
        };
    }

    /** 面の中の縦の軸(壁は上、床・天井は z)。 */
    static double[] axisV(Direction face) {
        return face.getAxis() == Direction.Axis.Y ? new double[]{0.0, 0.0, 1.0} : new double[]{0.0, 1.0, 0.0};
    }

    /** 描く({@link LifeSprites} の普通の重ね)。 */
    static void render(ClientLevel level, float partialTick) {
        if (CRICKETS.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        AtmosphereCaveLife.CricketParams p = DesignData.atmosphere().caveLife().crickets();
        double now = level.getGameTime() + partialTick;
        double t = now / 20.0;
        double hopTicks = Math.max(1.0, p.hopSeconds() * 20.0);
        for (Cricket c : CRICKETS) {
            float fade = c.goneAt >= 0L ? (float) Mth.clamp(1.0 - (now - c.goneAt) / 6.0, 0.0, 1.0) : 1.0F;
            c.alpha = Math.min(1.0F, c.alpha + 0.05F) * fade;
            if (c.alpha <= 0.01F) {
                continue;
            }
            Vec3 base = c.spot.point();
            double jump = 0.0;
            if (c.hopAt >= 0L && c.from != null) {
                double f = Mth.clamp((now - c.hopAt) / hopTicks, 0.0, 1.0);
                Vec3 a = c.from.point();
                Vec3 b = c.spot.point();
                // 弧: 元の面の外向きと上向きの間へ持ち上げる山(跳ぶ距離の 0.45 倍の高さ)。
                double h = 4.0 * f * (1.0 - f) * a.distanceTo(b) * 0.45;
                double nx = c.from.face().getStepX() * 0.5;
                double ny = c.from.face().getStepY() * 0.5 + 0.7;
                double nz = c.from.face().getStepZ() * 0.5;
                base = new Vec3(Mth.lerp(f, a.x, b.x) + nx * h, Mth.lerp(f, a.y, b.y) + ny * h, Mth.lerp(f, a.z, b.z) + nz * h);
                jump = Math.sin(f * Math.PI);
            }
            draw(c, base, p, t, jump);
        }
        renderNanos += System.nanoTime() - t0;
        renderFrames++;
    }

    /**
     * 胴の形(11-08 ユーザー: ゴキブリに見えるので直す)。体の軸 a(頭が +)に沿った節目ごとの背の高さ h と幅 w(単位は体の長さ)。
     * 背を丸く高い弓なりにし(胸がいちばん高く、頭は前へ下がり、腹は後ろへ細く下がる)、幅は高さより狭くして平たく横に広く見せない。
     */
    private static final double[] SPINE_A = {-0.62, -0.48, -0.30, -0.10, 0.10, 0.28, 0.40, 0.50};
    private static final double[] SPINE_H = {0.05, 0.16, 0.27, 0.34, 0.35, 0.28, 0.20, 0.09};
    private static final double[] SPINE_W = {0.06, 0.13, 0.18, 0.20, 0.20, 0.17, 0.14, 0.09};

    private static void draw(Cricket c, Vec3 base, AtmosphereCaveLife.CricketParams p, double t, double jump) {
        double[] u = axisU(c.spot.face());
        double[] v = axisV(c.spot.face());
        double[] n = {c.spot.face().getStepX(), c.spot.face().getStepY(), c.spot.face().getStepZ()};
        float len = p.length();
        double heading = c.heading + 0.08 * Math.sin(t * 0.5 + c.seed);
        double fu = Math.sin(heading);
        double fv = Math.cos(heading);
        float light = c.light;
        // 茶色(過繁茂では tint だけ緑へ)。
        float r = Mth.lerp(c.tint, 0.42F, 0.30F) * light;
        float g = Mth.lerp(c.tint, 0.30F, 0.35F) * light;
        float b = Mth.lerp(c.tint, 0.18F, 0.16F) * light;
        float a = c.alpha;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Body body = new Body(base, u, v, n, fu, fv, len, cam);
        double px = 1.0 / 16.0 / len;
        float er = r * 0.5F;
        float eg = g * 0.5F;
        float eb = b * 0.5F;
        // 足もとの影 = 縁取り(面に貼る、胴より両側 1/32 ブロックずつ広い暗い輪郭。体の幅が 1〜2 画素しかないので 1/16 では胴より影が太くなった)。面から浮いて見えないための接地の影も兼ねる。
        for (int k = 0; k + 1 < SPINE_A.length; k++) {
            double a0 = SPINE_A[k] - (k == 0 ? 0.5 * px : 0.0);
            double a1 = SPINE_A[k + 1] + (k + 2 == SPINE_A.length ? 0.5 * px : 0.0);
            body.part(a0, SPINE_W[k] + px, a1, SPINE_W[k + 1] + px, 0.0, er * 0.8F, eg * 0.8F, eb * 0.8F, a);
        }
        // 弓なりの胴: 節目ごとに、面に接する幅の広い底辺から背の高い所の細い背までの両側面と、背の帯(腹は節の帯を交互に)。
        double bob = 0.015 * Math.sin(t * 2.3 + c.seed);
        for (int k = 0; k + 1 < SPINE_A.length; k++) {
            double a0 = SPINE_A[k];
            double a1 = SPINE_A[k + 1];
            double h0 = SPINE_H[k] * (1.0 + bob);
            double h1 = SPINE_H[k + 1] * (1.0 + bob);
            double w0 = SPINE_W[k];
            double w1 = SPINE_W[k + 1];
            double t0 = w0 * 0.7;
            double t1 = w1 * 0.7;
            float tone = a0 < -0.05 ? ((k & 1) == 0 ? 1.0F : 0.82F) : 1.05F;
            float side = 0.85F * tone;
            for (int s = -1; s <= 1; s += 2) {
                body.quad3(a0, s * w0 * 0.5, 0.004, a1, s * w1 * 0.5, 0.004, a1, s * t1 * 0.5, h1, a0, s * t0 * 0.5, h0,
                        r * side, g * side, b * side, a);
            }
            body.quad3(a0, -t0 * 0.5, h0, a1, -t1 * 0.5, h1, a1, t1 * 0.5, h1, a0, t0 * 0.5, h0, r * tone, g * tone, b * tone, a);
        }
        // 背の明るい筋(胸の背の峰)。
        body.line3(-0.05, 0.0, 0.36, 0.30, 0.0, 0.29, 1.0 / 48.0, r * 1.45F, g * 1.45F, b * 1.35F, a);
        // 頭の前の暗い面(顔)。
        body.quad3(0.5, -0.045, 0.004, 0.5, 0.045, 0.004, 0.53, 0.03, 0.08, 0.53, -0.03, 0.08, er, eg, eb, a);
        // 尾の先の短い尾毛 2 本。
        for (int s = -1; s <= 1; s += 2) {
            body.line3(-0.60, s * 0.02, 0.06, -0.80, s * 0.08, 0.10, 1.0 / 64.0, er, eg, eb, a);
        }
        // 触角 2 本: 頭から前へ持ち上がり、外へ弧を描いて後ろへ流れる。体長の 2.4 倍で、ゆっくり揺れる。
        double antL = 2.4;
        for (int s = -1; s <= 1; s += 2) {
            double sa = 0.50;
            double ss = s * 0.03;
            double sh = 0.10;
            int segs = 12;
            for (int k = 1; k <= segs; k++) {
                double f = k / (double) segs;
                // 向きは前(0)から外へ回って後ろ寄り(約 1.7 rad)へ。1 節ずつ足して弧にする。
                double ang = 0.2 + 1.5 * f * f;
                double step = antL / segs;
                double sway = Math.sin(t * 1.7 + c.seed + s + f * 2.5) * 0.02;
                double ea = sa + step * Math.cos(ang);
                double es = ss + s * step * Math.sin(ang) + sway * f;
                double eh = 0.10 + 0.32 * Math.sin(Math.PI * Math.min(1.0, f * 1.4)) * (1.0 - 0.5 * f);
                body.line3(sa, ss, sh, ea, es, eh, 1.0 / 64.0, er, eg, eb, a);
                sa = ea;
                ss = es;
                sh = eh;
            }
        }
        // 前脚・中脚(細く長い): 胴の脇から膝を少し持ち上げて、面に足を置く。
        for (int s = -1; s <= 1; s += 2) {
            legs(body, 0.30, s * 0.06, 0.12, 0.44, s * 0.30, 0.24, 0.66, s * 0.40, 0.0, er, eg, eb, a);
            legs(body, 0.08, s * 0.08, 0.12, 0.04, s * 0.36, 0.26, -0.12, s * 0.52, 0.0, er, eg, eb, a);
        }
        // 後ろ脚: 太く長い腿を後ろ上へ上げ、膝を背より上へ突き出させる。すねは細く後ろ下の面へ。跳ぶ間は伸びる。
        for (int s = -1; s <= 1; s += 2) {
            double hipA = -0.12;
            double hipS = s * 0.08;
            double hipH = 0.16;
            double kneeA = -0.58 - 0.25 * jump;
            double kneeS = s * (0.22 + 0.04 * jump);
            double kneeH = 0.62 - 0.30 * jump;
            double footA = -1.10 - 0.35 * jump;
            double footS = s * (0.30 - 0.06 * jump);
            double footH = 0.0 + 0.15 * jump;
            float fr = r * 0.88F;
            float fg = g * 0.88F;
            float fb = b * 0.88F;
            // 腿は付け根で太く(体長の約 0.2)、膝で細る。縁を暗く、中ほどを明るく。
            body.taper3(hipA, hipS, hipH, kneeA, kneeS, kneeH, 0.090, 0.050, er, eg, eb, a);
            body.taper3(hipA, hipS, hipH + 0.004, kneeA, kneeS, kneeH + 0.004, 0.060, 0.030, fr, fg, fb, a);
            body.line3(kneeA, kneeS, kneeH, footA, footS, footH, 1.0 / 40.0, er, eg, eb, a);
            // 足先(ふ節): 面に沿って少し後ろへ。
            body.line3(footA, footS, footH, footA - 0.14, footS + s * 0.03, 0.0, 1.0 / 56.0, er, eg, eb, a);
        }
    }

    /** 付け根 → 膝 → 足の 2 本の細い帯の脚。 */
    private static void legs(Body body, double a0, double s0, double h0, double a1, double s1, double h1, double a2, double s2, double h2,
                             float r, float g, float b, float alpha) {
        body.line3(a0, s0, h0, a1, s1, h1, 1.0 / 48.0, r, g, b, alpha);
        body.line3(a1, s1, h1, a2, s2, h2, 1.0 / 56.0, r, g, b, alpha);
    }

    /**
     * 面の上の体の座標(a = 頭の向き、s = 右、h = 面からの高さ。単位は体の長さ)を世界へ移して四角を描く。
     * 脚と触角の帯はカメラへ向けて幅を取る(横から見ても線が消えない)。
     */
    private record Body(Vec3 base, double[] u, double[] v, double[] n, double fu, double fv, float len, Vec3 cam) {
        double[] at(double a, double s, double lift) {
            double cu = (this.fu * a + this.fv * s) * this.len;
            double cv = (this.fv * a - this.fu * s) * this.len;
            return new double[]{this.base.x + this.u[0] * cu + this.v[0] * cv + this.n[0] * lift,
                    this.base.y + this.u[1] * cu + this.v[1] * cv + this.n[1] * lift,
                    this.base.z + this.u[2] * cu + this.v[2] * cv + this.n[2] * lift};
        }

        double[] at3(double a, double s, double h) {
            return at(a, s, h * this.len);
        }

        /** 体の軸に沿った面に貼る台形(a0 で幅 w0、a1 で幅 w1。lift はブロック)。 */
        void part(double a0, double w0, double a1, double w1, double lift, float r, float g, float b, float alpha) {
            double[] p0 = at(a0, -w0 * 0.5, lift);
            double[] p1 = at(a1, -w1 * 0.5, lift);
            double[] p2 = at(a1, w1 * 0.5, lift);
            double[] p3 = at(a0, w0 * 0.5, lift);
            LifeSprites.quad(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], r, g, b, alpha);
        }

        /** 体の座標の 4 点の四角(高さ h も体の長さ単位)。 */
        void quad3(double a0, double s0, double h0, double a1, double s1, double h1, double a2, double s2, double h2,
                   double a3, double s3, double h3, float r, float g, float b, float alpha) {
            double[] p0 = at3(a0, s0, h0);
            double[] p1 = at3(a1, s1, h1);
            double[] p2 = at3(a2, s2, h2);
            double[] p3 = at3(a3, s3, h3);
            LifeSprites.quad(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], r, g, b, alpha);
        }

        /** (a0, s0, h0) から (a1, s1, h1) への細い帯(太さ thick ブロック、カメラ向き)。 */
        void line3(double a0, double s0, double h0, double a1, double s1, double h1, double thick, float r, float g, float b, float alpha) {
            taper3(a0, s0, h0, a1, s1, h1, thick, thick, r, g, b, alpha);
        }

        /** 太さが w0 から w1(ブロック)へ細る、カメラ向きの帯。 */
        void taper3(double a0, double s0, double h0, double a1, double s1, double h1, double w0, double w1, float r, float g, float b, float alpha) {
            double[] p = at3(a0, s0, h0);
            double[] q = at3(a1, s1, h1);
            double dx = q[0] - p[0];
            double dy = q[1] - p[1];
            double dz = q[2] - p[2];
            double mx = (p[0] + q[0]) * 0.5 - this.cam.x;
            double my = (p[1] + q[1]) * 0.5 - this.cam.y;
            double mz = (p[2] + q[2]) * 0.5 - this.cam.z;
            double cx = dy * mz - dz * my;
            double cy = dz * mx - dx * mz;
            double cz = dx * my - dy * mx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1e-9) {
                return;
            }
            cx /= cl;
            cy /= cl;
            cz /= cl;
            double h0w = w0 * 0.5;
            double h1w = w1 * 0.5;
            LifeSprites.quad(p[0] - cx * h0w, p[1] - cy * h0w, p[2] - cz * h0w, q[0] - cx * h1w, q[1] - cy * h1w, q[2] - cz * h1w,
                    q[0] + cx * h1w, q[1] + cy * h1w, q[2] + cz * h1w, p[0] + cx * h0w, p[1] + cy * h0w, p[2] + cz * h0w, r, g, b, alpha);
        }
    }
}
