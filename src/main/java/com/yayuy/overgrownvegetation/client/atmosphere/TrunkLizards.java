package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereGiantLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>巨大樹の幹に張り付く爬虫類</b>(2026-11-06 ユーザー採用の改善案 ⑤ の 2。設定: 環境の生き物は虫と爬虫類。ヤモリ系の声と対の、姿の見える小さな爬虫類)。
 *
 * <p>見た目だけ(本物の Mob にしない。当たり判定・ドロップ・サーバーとの通信なし)。<b>素材を増やさない</b>: 形は頂点の色だけの四角
 * ({@link LifeSprites#quad})を幹の面に沿って並べて CPU で描く(頭・胴・尾 5 節・脚 4 本、背に暗い筋)。
 * 幹の軸から {@code giantLife.lizards.range} ブロック以内にいる間、近い巨大樹 1 本に最大 {@code perTree} 匹を、目の高さ
 * −{@code heightBelow}〜+{@code heightAbove} の幹の外向きの面(外側が開いている所)に出す。出すのはプレイヤーの視野の外か遠くだけ(目の前で湧かせない)。
 * ふだんは頭を少し振り、尾をゆっくり揺らす。プレイヤーが {@code fleeDistance} ブロックに入ると上を向いて {@code climbSpeed} ブロック/秒で幹を駆け上がり、
 * 面が尽きる所か {@code fleeSeconds} 秒で消える。{@code respawnSeconds} 秒後に別の所へ出る。
 * 色は幹の樹皮に寄せた暗い緑茶(浮かせない。memory mob-texture-must-not-float)を、その所の明るさ(空の光 × 昼夜・ブロックの光)で暗くする。
 * 値はすべて仮決め(節 {@code giantLife.lizards})。
 */
public final class TrunkLizards {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX = 8;
    private static final List<Lizard> LIZARDS = new ArrayList<>();
    private static long nextSpawnAt;
    private static int flees;
    /** 湧かせ損ねた理由の数(開発用のログ): 高さが木の外・面が見つからない・面の外が塞がる・登れる高さが足りない・遠すぎ近すぎ・目の前・見通せない(葉の陰)。 */
    private static final int[] FAILS = new int[7];
    private static String lastTree = "";

    private static final class Lizard {
        /** 張り付いている木部のブロック(登るのはこの列)。 */
        int wx;
        int wy;
        int wz;
        /** 面の外向き(東西なら nx = ±1、南北なら nz = ±1)。 */
        int nx;
        int nz;
        /** 高さ wy + k の段での、面の向きへのずれ(段々の幹を乗り移る)。 */
        int[] shift = new int[17];
        /** 面の上の横の位置(0〜1)。 */
        double u;
        double y;
        double yAtFlee;
        /** 面が続く上の端(ここで消える)。 */
        double maxY;
        /** 頭の向き(0 = 真上、π = 真下)。 */
        double heading;
        float seed;
        long fleeAt = -1L;
        long goneAt = -1L;
        double leg;
        float alpha;
        float light = 1.0F;
    }

    private TrunkLizards() {
    }

    static void clear() {
        pinned = false;
        LIZARDS.clear();
        nextSpawnAt = 0L;
    }


    /** 開発用(テストクライアントの {@code lizard}): 決めた木部のブロックの面に 1 匹だけ置き、逃げも湧きも止める(写真の前後を同じ所で撮る)。 */
    private static boolean pinned;

    public static String devPlace(ClientLevel level, int wx, int wy, int wz, int nx, int nz, double heading) {
        LIZARDS.clear();
        pinned = true;
        if (!GiantSilhouettes.isWood(level.getBlockState(new BlockPos(wx, wy, wz)))) {
            return "木部ではない";
        }
        Lizard l = new Lizard();
        l.wx = wx;
        l.wy = wy;
        l.wz = wz;
        l.nx = nx;
        l.nz = nz;
        l.u = 0.5;
        l.y = wy + 0.3;
        l.maxY = wy + 16.0;
        l.heading = heading;
        l.seed = 42.0F;
        l.alpha = 1.0F;
        LIZARDS.add(l);
        return String.format("置いた 面の上 %.2f %.2f %.2f", surfaceX(l), l.y, surfaceZ(l));
    }

    /** 開発用: 置いたトカゲを外して、ふつうの湧きに戻す。 */
    public static void devRelease() {
        LIZARDS.clear();
        pinned = false;
    }
    static String describe() {
        int fleeing = 0;
        for (Lizard l : LIZARDS) {
            fleeing += l.fleeAt >= 0L ? 1 : 0;
        }
        return String.format("幹のトカゲ %d(逃げ中 %d・逃げた回数 %d)損ね %s %s", LIZARDS.size(), fleeing, flees, java.util.Arrays.toString(FAILS), lastTree);
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        AtmosphereGiantLife.LizardsParams p = DesignData.atmosphere().giantLife().lizards();
        if (!VisualQuality.critters() || !p.enabled()) {
            clear();
            return;
        }
        long now = level.getGameTime();
        List<GiantTrees.Info> near = GiantLifeTrees.nearest(player.getX(), player.getZ(), p.range(), 1);
        GiantTrees.Info tree = near.isEmpty() ? null : near.get(0);
        lastTree = tree == null ? "木なし" : String.format("木 軸 %.0f %.0f 根元 %d 先 %d 水平距離 %.0f", tree.axisX(), tree.axisZ(), tree.baseY(), tree.topY(), Math.sqrt(GiantLifeTrees.distSqr(tree, player.getX(), player.getZ())));
        Vec3 eye = player.getEyePosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = LIZARDS.size() - 1; i >= 0; i--) {
            Lizard l = LIZARDS.get(i);
            double lx = surfaceX(l);
            double lz = surfaceZ(l);
            double d2 = eye.distanceToSqr(lx, l.y, lz);
            if (tree == null || d2 > (p.range() + 16.0) * (p.range() + 16.0) || l.goneAt >= 0L && now - l.goneAt > 6L) {
                LIZARDS.remove(i);
                continue;
            }
            if (!pinned && l.fleeAt < 0L && d2 < p.fleeDistance() * p.fleeDistance()) {
                l.fleeAt = now;
                l.yAtFlee = l.y;
                flees++;
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("TrunkLizards 逃げる 位置 {} {} {} 距離 {} 面の上端 {}", String.format("%.1f", lx), String.format("%.1f", l.y),
                            String.format("%.1f", lz), String.format("%.1f", Math.sqrt(d2)), String.format("%.1f", l.maxY));
                }
            }
            if (l.fleeAt >= 0L && l.goneAt < 0L && (l.y >= l.maxY - 0.3 || (now - l.fleeAt) / 20.0 > p.fleeSeconds())) {
                l.goneAt = now;
                nextSpawnAt = Math.max(nextSpawnAt, now + Math.round(p.respawnSeconds() * 20.0));
            }
            if (now % 10L == 0L) {
                pos.set(Mth.floor(lx + l.nx * 0.5), Mth.floor(l.y + 0.2), Mth.floor(lz + l.nz * 0.5));
                float sky = level.getBrightness(LightLayer.SKY, pos) / 15.0F * level.getSkyDarken(1.0F);
                float block = level.getBrightness(LightLayer.BLOCK, pos) / 15.0F;
                l.light = 0.44F + 0.56F * Math.max(sky, block); // 下限 0.44: 林床の暗がりで樹皮より暗く沈まず、明るくもなりすぎない(11-07 測り: 樹皮との明るさの比 0.85〜1.2)
            }
        }
        if (pinned || tree == null || LIZARDS.size() >= Math.min(MAX, p.perTree()) || now < nextSpawnAt) {
            return;
        }
        Lizard l = trySpawn(level, player, tree, p, pos);
        if (l != null) {
            LIZARDS.add(l);
            nextSpawnAt = now + 10L;
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("TrunkLizards 出す 位置 {} {} {} 面 {} {} 登れる高さ {} 木の軸 {} {}", String.format("%.1f", surfaceX(l)),
                        String.format("%.1f", l.y), String.format("%.1f", surfaceZ(l)), l.nx, l.nz, String.format("%.1f", l.maxY - l.y),
                        String.format("%.1f", tree.axisX()), String.format("%.1f", tree.axisZ()));
            }
        } else {
            nextSpawnAt = now + 5L;
        }
    }

    /** 幹の軸から外へたどって木部の外向きの面を探す。見つからない・目の前・近すぎるなら null。 */
    private static Lizard trySpawn(ClientLevel level, LocalPlayer player, GiantTrees.Info tree, AtmosphereGiantLife.LizardsParams p,
                                   BlockPos.MutableBlockPos pos) {
        double a = RANDOM.nextDouble() * Mth.TWO_PI;
        // 半分はプレイヤーの側の面(見える所)を狙う。
        if (RANDOM.nextBoolean()) {
            a = Math.atan2(player.getZ() - tree.axisZ(), player.getX() - tree.axisX()) + (RANDOM.nextDouble() - 0.5) * 1.6;
        }
        int y = Mth.floor(player.getEyeY() - p.heightBelow() + RANDOM.nextDouble() * (p.heightBelow() + p.heightAbove()));
        if (y < tree.baseY() || y > tree.topY()) {
            FAILS[0]++;
            return null;
        }
        // 傾いた幹・曲がった幹に沿うよう、その高さの幹の中心から(断面があれば)。
        double ox = tree.profile() != null ? tree.profile().centerX(y) : tree.axisX();
        double oz = tree.profile() != null ? tree.profile().centerZ(y) : tree.axisZ();
        double ca = Math.cos(a);
        double sa = Math.sin(a);
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        boolean inWood = false;
        for (double r = 0.0; r < 48.0; r += 0.4) {
            int bx = Mth.floor(ox + ca * r);
            int bz = Mth.floor(oz + sa * r);
            if (bx == lastX && bz == lastZ) {
                continue;
            }
            boolean wood = GiantSilhouettes.isWood(level.getBlockState(pos.set(bx, y, bz)));
            if (inWood && !wood) {
                int nx = Integer.signum(bx - lastX);
                int nz = Integer.signum(bz - lastZ);
                if (nx != 0 && nz != 0) {
                    // 斜めに抜けた: 東西の隣が開いていれば東西の面、でなければ南北の面。
                    if (!GiantSilhouettes.isWood(level.getBlockState(pos.set(lastX + nx, y, lastZ)))) {
                        nz = 0;
                    } else {
                        nx = 0;
                    }
                }
                return place(level, player, p, lastX, y, lastZ, nx, nz, pos);
            }
            inWood = wood;
            lastX = bx;
            lastZ = bz;
        }
        FAILS[1]++;
        return null;
    }

    private static Lizard place(ClientLevel level, LocalPlayer player, AtmosphereGiantLife.LizardsParams p, int wx, int wy, int wz, int nx, int nz,
                                BlockPos.MutableBlockPos pos) {
        if (!open(level.getBlockState(pos.set(wx + nx, wy, wz + nz)))) {
            FAILS[2]++;
            return null;
        }
        // 面が上へ続く高さ(登って逃げる先)。幹の面は段々なので、1 ブロック内か外へずれた面にも乗り移る(shift = 面の向きへのずれ)。
        int[] shift = new int[17];
        int up = 0;
        int col = 0;
        while (up < 16) {
            int y1 = wy + up + 1;
            int cx = wx + nx * col;
            int cz = wz + nz * col;
            if (face(level, pos, cx, y1, cz, nx, nz)) {
                // そのまま
            } else if (face(level, pos, cx - nx, y1, cz - nz, nx, nz)) {
                col--;
            } else if (face(level, pos, cx + nx, y1, cz + nz, nx, nz)) {
                col++;
            } else {
                break;
            }
            up++;
            shift[up] = col;
        }
        if (up < 3) {
            FAILS[3]++;
            return null;
        }
        Lizard l = new Lizard();
        l.wx = wx;
        l.wy = wy;
        l.wz = wz;
        l.nx = nx;
        l.nz = nz;
        l.u = 0.25 + 0.5 * RANDOM.nextDouble();
        l.y = wy + 0.2 + 0.6 * RANDOM.nextDouble();
        l.maxY = wy + up + 0.9;
        l.shift = shift;
        l.heading = RANDOM.nextFloat() < 0.3F ? Math.PI + (RANDOM.nextDouble() - 0.5) * 0.8 : (RANDOM.nextDouble() - 0.5) * 0.9;
        l.seed = RANDOM.nextFloat() * 100.0F;
        Vec3 eye = player.getEyePosition();
        double dx = surfaceX(l) - eye.x;
        double dy = l.y - eye.y;
        double dz = surfaceZ(l) - eye.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < p.fleeDistance() + 2.0 || d > 28.0) { // 近すぎず、見える所(28 ブロック以内。仮決め)
            FAILS[4]++;
            return null;
        }
        // 目の前(視野の真ん中 ±50°)の 14 ブロック以内では湧かせない。
        Vec3 look = player.getViewVector(1.0F);
        if (d < 14.0 && (dx * look.x + dy * look.y + dz * look.z) / d > Math.cos(Math.toRadians(50.0))) {
            FAILS[5]++;
            return null;
        }
        // プレイヤーの目から見通せる所だけ(間に葉・幹・地面が無い。0.5 ブロック刻みで調べる)。
        int steps = (int) Math.ceil(d / 0.5);
        for (int i = 1; i < steps - 1; i++) {
            double f = i / (double) steps;
            BlockState s = level.getBlockState(pos.set(Mth.floor(eye.x + dx * f), Mth.floor(eye.y + dy * f), Mth.floor(eye.z + dz * f)));
            if (!s.isAir() && s.blocksMotion()) {
                FAILS[6]++;
                return null;
            }
        }
        return l;
    }

    /** 今の高さの段のずれ。 */
    private static int shiftAt(Lizard l) {
        return l.shift[Mth.clamp(Mth.floor(l.y) - l.wy, 0, l.shift.length - 1)];
    }

    /** (x, y, z) が木部で、面の向きの隣が開いているか。 */
    private static boolean face(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int y, int z, int nx, int nz) {
        return GiantSilhouettes.isWood(level.getBlockState(pos.set(x, y, z))) && open(level.getBlockState(pos.set(x + nx, y, z + nz)));
    }

    private static boolean open(BlockState s) {
        return !s.canOcclude() && !GiantSilhouettes.isWood(s) && s.getFluidState().isEmpty();
    }

    /** 面の上の点(面から 0.015 浮かせる)。東西の面は x が面、z が横。 */
    private static double surfaceX(Lizard l) {
        int wx = l.wx + l.nx * shiftAt(l);
        return l.nx != 0 ? wx + (l.nx > 0 ? 1.015 : -0.015) : wx + l.u;
    }

    private static double surfaceZ(Lizard l) {
        int wz = l.wz + l.nz * shiftAt(l);
        return l.nz != 0 ? wz + (l.nz > 0 ? 1.015 : -0.015) : wz + l.u;
    }

    /** 描く({@link LifeSprites} の普通の重ね)。動きもここで進める(なめらかにするため描くたび)。 */
    static void render(ClientLevel level, float partialTick) {
        if (LIZARDS.isEmpty()) {
            return;
        }
        AtmosphereGiantLife.LizardsParams p = DesignData.atmosphere().giantLife().lizards();
        double now = level.getGameTime() + partialTick;
        double t = now / 20.0;
        for (Lizard l : LIZARDS) {
            boolean running = l.fleeAt >= 0L;
            double run = 0.0;
            if (running) {
                double s = (now - l.fleeAt) / 20.0;
                // 0.15 秒で上を向き、0.3 秒で全速。進んだ距離 = 速さ × (s − 0.15) を立ち上がりでなめらかに。
                double moving = Math.max(0.0, s - 0.15);
                double dist = p.climbSpeed() * (moving < 0.3 ? moving * moving / 0.6 : moving - 0.15);
                l.y = Math.min(l.maxY, l.yAtFlee + dist);
                double turn = Mth.clamp(s / 0.15, 0.0, 1.0);
                double target = 0.0;
                double from = Math.atan2(Math.sin(l.heading), Math.cos(l.heading));
                l.heading = from + (target - from) * turn;
                run = Mth.clamp(moving / 0.2, 0.0, 1.0);
                l.leg = moving * p.climbSpeed() / (p.length() * 0.9) * Mth.TWO_PI;
            }
            float fade = l.goneAt >= 0L ? (float) Mth.clamp(1.0 - (now - l.goneAt) / 5.0, 0.0, 1.0) : 1.0F;
            l.alpha = Math.min(1.0F, l.alpha + 0.05F) * fade;
            if (l.alpha <= 0.01F) {
                continue;
            }
            draw(l, p.length(), t, run);
        }
    }

    private static void draw(Lizard l, float len, double t, double run) {
        // 面の軸: 横 U、上 V、外向き N。
        double ux = l.nx != 0 ? 0.0 : 1.0;
        double uz = l.nx != 0 ? 1.0 : 0.0;
        double heading = l.heading + (run > 0.0 ? 0.0 : 0.25 * Math.sin(t * 0.6 + l.seed) * smoothStep(Math.sin(t * 0.23 + l.seed * 2.0)));
        double fu = Math.sin(heading);
        double fv = Math.cos(heading);
        // 体の真ん中を (a, b) = (0, 0)。a は頭の向き、b は右。
        double bx = surfaceX(l);
        double by = l.y;
        double bz = surfaceZ(l);
        float light = l.light;
        float r = 0.28F * light;
        float g = 0.27F * light;
        float b = 0.15F * light;
        float alpha = l.alpha;
        double wave = run > 0.0 ? 0.06 * len * Math.sin(l.leg) : 0.025 * len * Math.sin(t * 1.3 + l.seed);
        // 縁取り: 胴・頭・尾を 1/16 ブロックずつ太らせた暗い影を先に敷く(樹皮の上で輪郭が読めるように。色は胴の 0.6 倍 = 一段暗いだけ)。
        double px = 1.0 / 16.0 / len;
        float er = r * 0.6F;
        float eg = g * 0.6F;
        float eb = b * 0.6F;
        part(l, bx, by, bz, ux, uz, fu, fv, 0.05 - px, 0.26 + 2 * px, 0.22, 0.26 + 2 * px, len, 0.0, er, eg, eb, alpha);
        part(l, bx, by, bz, ux, uz, fu, fv, 0.22, 0.26 + 2 * px, 0.36, 0.16 + 2 * px, len, 0.0, er, eg, eb, alpha);
        part(l, bx, by, bz, ux, uz, fu, fv, 0.36, 0.19 + 2 * px, 0.5 + px, 0.07 + 2 * px, len, 0.0, er, eg, eb, alpha);
        tail(l, bx, by, bz, ux, uz, fu, fv, len, t, run, wave, 2 * px, 0.0, er, eg, eb, alpha);
        // 胴(2 つ)と頭。
        part(l, bx, by, bz, ux, uz, fu, fv, 0.05, 0.26, 0.22, 0.26, len, 0.001, r, g, b, alpha);
        part(l, bx, by, bz, ux, uz, fu, fv, 0.22, 0.26, 0.36, 0.16, len, 0.001, r, g, b, alpha);
        part(l, bx, by, bz, ux, uz, fu, fv, 0.36, 0.19, 0.5, 0.07, len, 0.001, r * 1.12F, g * 1.12F, b * 1.1F, alpha);
        // 尾 5 節(先ほど細く、波打つ)。
        tail(l, bx, by, bz, ux, uz, fu, fv, len, t, run, wave, 0.0, 0.001, r * 0.95F, g * 0.95F, b * 0.95F, alpha);
        // 背の明るい筋(樹皮の黄緑の明るい所の色。一段明るいだけ)。
        part(l, bx, by, bz, ux, uz, fu, fv, 0.04, 0.06, 0.42, 0.04, len, 0.003, r * 1.9F, g * 1.95F, b * 1.6F, alpha);
        // 斑点: 樹皮の 1 画素(1/16 ブロック)と同じ大きさの暗い点・明るい点を胴と頭に(のっぺりした板に見せない。memory mob-texture-pixel-size-uniform)。
        for (int k = 0; k < 16; k++) {
            float h1 = hash(l.seed + k * 1.37F);
            float h2 = hash(l.seed + k * 2.71F + 7.0F);
            double sa = 0.06 + 0.4 * h1;
            double half = (sa < 0.36 ? 0.1 : 0.07) * (2.0 * h2 - 1.0);
            float tone = (k & 1) == 0 ? 0.7F : 1.7F;
            part(l, bx, by, bz, ux, uz, fu, fv, sa, px, sa + px, px, len, 0.004, r * tone, g * tone, b * tone, alpha, half);
        }
        // 脚 4 本: 前 a = 0.3、後ろ a = 0.08。走るときは対角の脚が揃って振れる。
        for (int leg = 0; leg < 4; leg++) {
            double side = (leg & 1) == 0 ? 1.0 : -1.0;
            double at = leg < 2 ? 0.3 : 0.08;
            double phase = (leg == 0 || leg == 3) ? 0.0 : Math.PI;
            double swing = run > 0.0 ? 0.09 * Math.sin(l.leg + phase) * run : 0.0;
            double footA = at + (leg < 2 ? 0.08 : -0.07) + swing; // 前脚は前へ、後ろ脚は後ろへ開く(ヤモリの形)
            limb(l, bx, by, bz, ux, uz, fu, fv, at, side * 0.13, footA, side * 0.34, len, r * 0.6F, g * 0.6F, b * 0.6F, alpha);
        }
    }

    /** 尾 5 節(先ほど細く、波打つ)。grow = 両側へ足す幅(縁取り)、lift = 面から浮かせる分。 */
    private static void tail(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, float len, double t, double run,
                             double wave, double grow, double lift, float r, float g, float b, float alpha) {
        double a0 = 0.05;
        double w0 = 0.15 + grow;
        double s0 = 0.0;
        for (int k = 1; k <= 5; k++) {
            double a1 = 0.05 - 0.55 * k / 5.0 - (k == 5 ? grow * 0.5 : 0.0);
            double w1 = 0.15 * (1.0 - k / 5.5) + grow;
            double s1 = wave * Math.sin(k * 0.9 - (run > 0.0 ? l.leg : t * 1.3 + l.seed)) * (k / 5.0);
            bend(l, bx, by, bz, ux, uz, fu, fv, a0, w0, s0, a1, w1, s1, len, r, g, b, alpha, lift);
            a0 = a1;
            w0 = w1;
            s0 = s1;
        }
    }

    private static double smoothStep(double v) {
        return v > 0.6 ? 1.0 : v < 0.2 ? 0.0 : (v - 0.2) / 0.4;
    }

    /** 体の軸に沿った台形(a0 で幅 w0、a1 で幅 w1。単位は体の長さ)。 */
    private static void part(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a0, double w0, double a1,
                             double w1, float len, double lift, float r, float g, float b, float alpha) {
        bend(l, bx, by, bz, ux, uz, fu, fv, a0, w0, 0.0, a1, w1, 0.0, len, r, g, b, alpha, lift);
    }

    /** 横へ side(体の長さを 1)ずらした台形(斑点)。 */
    private static void part(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a0, double w0, double a1,
                             double w1, float len, double lift, float r, float g, float b, float alpha, double side) {
        bend(l, bx, by, bz, ux, uz, fu, fv, a0, w0, side, a1, w1, side, len, r, g, b, alpha, lift + 0.001);
    }

    private static float hash(float v) {
        float s = Mth.sin(v * 12.9898F) * 43758.547F;
        return s - Mth.floor(s);
    }

    private static void bend(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a0, double w0, double s0,
                             double a1, double w1, double s1, float len, float r, float g, float b, float alpha) {
        bend(l, bx, by, bz, ux, uz, fu, fv, a0, w0, s0, a1, w1, s1, len, r, g, b, alpha, 0.0);
    }

    private static void bend(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a0, double w0, double s0,
                             double a1, double w1, double s1, float len, float r, float g, float b, float alpha, double lift) {
        double[] p0 = at(l, bx, by, bz, ux, uz, fu, fv, a0 * len, (s0 - w0 * 0.5) * len, lift);
        double[] p1 = at(l, bx, by, bz, ux, uz, fu, fv, a1 * len, (s1 - w1 * 0.5) * len, lift);
        double[] p2 = at(l, bx, by, bz, ux, uz, fu, fv, a1 * len, (s1 + w1 * 0.5) * len, lift);
        double[] p3 = at(l, bx, by, bz, ux, uz, fu, fv, a0 * len, (s0 + w0 * 0.5) * len, lift);
        LifeSprites.quad(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], r, g, b, alpha);
    }

    /** 脚: 付け根 (a0, b0) から足先 (a1, b1) への細い帯(太さは体の長さの 0.025)。 */
    private static void limb(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a0, double b0, double a1,
                             double b1, float len, float r, float g, float b, float alpha) {
        double da = a1 - a0;
        double db = b1 - b0;
        double n = Math.max(1e-6, Math.sqrt(da * da + db * db));
        double pa = -db / n * 0.06;
        double pb = da / n * 0.06;
        double[] p0 = at(l, bx, by, bz, ux, uz, fu, fv, (a0 - pa) * len, (b0 - pb) * len, 0.0);
        double[] p1 = at(l, bx, by, bz, ux, uz, fu, fv, (a1 - pa) * len, (b1 - pb) * len, 0.0);
        double[] p2 = at(l, bx, by, bz, ux, uz, fu, fv, (a1 + pa) * len, (b1 + pb) * len, 0.0);
        double[] p3 = at(l, bx, by, bz, ux, uz, fu, fv, (a0 + pa) * len, (b0 + pb) * len, 0.0);
        LifeSprites.quad(p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], r, g, b, alpha);
    }

    /** 体の座標 (a = 頭の向き、s = 右) を世界の座標へ。lift は面から少し浮かせる分(筋を胴の上に)。 */
    private static double[] at(Lizard l, double bx, double by, double bz, double ux, double uz, double fu, double fv, double a, double s, double lift) {
        // 頭の向き f = fu·U + fv·V、右 = fv·U − fu·V。
        double cu = fu * a + fv * s;
        double cv = fv * a - fu * s;
        return new double[]{bx + ux * cu + l.nx * lift, by + cv, bz + uz * cu + l.nz * lift};
    }
}
