package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 結晶地帯の極相で、<b>地脈に沿ってエネルギー結晶を突き出させる</b>(順 1、2026-10-03)。
 * ユーザーの言葉では「地脈に沿って大きなエネルギー結晶が生えているバイオーム」。
 *
 * <h2>いつ・どこに</h2>
 * <p>極相の全層置換を終えたとき、{@link ClimaxDeepConverter} から 1 回だけ呼ばれる。対象は
 * <b>チャンクの中央が結晶地帯</b>({@link ClimaxOrePlacer#isCrystalBiome})のチャンクだけ —— 結晶地帯の地域でも、
 * 川・海のチャンク(普通の極相)には生やさない。
 *
 * <p>候補点はワールド座標の {@code spireSpacing} 格子に 1 点ずつ(場所は格子ごとのハッシュ)。
 * 候補点が<b>このチャンクの中</b>にあり、地脈の上({@link LeyVeinField#strengthAt} が {@link #MIN_STRENGTH} より上)で、
 * {@code spireChance} のくじに当たれば 1 本立てる。1 本の結晶は<b>根元の候補点が入っているチャンク</b>だけが立てるので、
 * 二重に立つことはない。地脈の中心に近いほど高く太い。
 *
 * <h2>形</h2>
 * <p>傾いた六角柱で、上の 4 割が尖った先端へすぼまる(水晶の形)。根元は地面へ 2 マス埋める。
 * 置き換えるのは<b>空気・草花のような置き換え可能なもの・葉</b>と、埋めた根元の地面だけ ——
 * 幹やプレイヤーのブロックは壊さない(結晶はそこで欠ける)。葉を通すのは、林冠を突き抜けて見えるように。
 *
 * <p>傾いた先は隣のチャンクへはみ出しうる。<b>読み込まれていないチャンクには書かない</b>
 * ({@link ServerLevel#hasChunk} を 1 マスごとに見る)—— 書くと読み込みが起きて、
 * 「極相化は世界を読み込まずに進む」前提が崩れる。全層置換はプレイヤーの近くで走るので、隣はふつう読み込まれている。
 *
 * <p>光量は 0(モデルの自己発光だけ)。{@link ModBlocks#ENERGY_CRYSTAL} を参照。
 */
public final class EnergyCrystalPlacer {

    /** 地脈の強さ(0〜1)がこれ以下の候補点には立てない(地脈の縁ぎりぎりに立つと、地脈から外れて見える)。 */
    private static final double MIN_STRENGTH = 0.15D;

    /** 1 チャンクで置いてよいマス数の上限。くじが偏って巨大な結晶が並んでも、1 回の処理が重くなりすぎないように。 */
    private static final int MAX_BLOCKS_PER_CHUNK = 1500;

    /** 根元を地面へ埋める深さ。 */
    private static final int SINK = 2;

    private static final long SALT = 0x3C7A1E5B44D1L;

    private EnergyCrystalPlacer() {
    }

    /** 結晶を 1 本立てる根元の列。{@code hash} はその本の形を決める乱数の種。 */
    public record Site(int x, int z, long hash, double strength) {
    }

    /** そのチャンクに結晶を立てる。<b>読み込まれているチャンクにだけ呼ぶこと。</b>置いたマス数を返す。 */
    public static int place(ServerLevel level, ChunkPos pos) {
        if (!ClimaxOrePlacer.isCrystalBiome(level, pos)) {
            return 0;
        }
        ClimaxTable.Crystal spec = DesignData.climax().crystal();
        int placed = 0;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (Site site : spireSites(level.getSeed(), pos, spec)) {
            int ground = LeyVeinPlacer.groundHeight(level, site.x(), site.z());
            if (ground == Integer.MIN_VALUE) {
                continue;
            }
            // groundHeight は地脈ブロックを地面と見ない(地脈は LEY_VEIN_REPLACEABLE に入っていない)ので、
            // 地脈の上ではその下の石を答える。地脈の層の上面まで上がってから根元にする —— そうしないと
            // 地脈の 2 マスぶん結晶が欠けて、地面から浮いて見える。
            probe.set(site.x(), ground + 1, site.z());
            while (probe.getY() < level.getMaxBuildHeight() && level.getBlockState(probe).is(ModBlocks.LEY_VEIN.get())) {
                ground++;
                probe.move(0, 1, 0);
            }
            placed += spire(level, site.x(), ground, site.z(), site.strength(), site.hash(), spec,
                    MAX_BLOCKS_PER_CHUNK - placed);
            if (placed >= MAX_BLOCKS_PER_CHUNK) {
                return placed;
            }
        }
        return placed;
    }

    /**
     * そのチャンクで結晶を立てる根元の列(シードと座標だけで決まる。チャンクは読まない)。
     * 候補点はワールド座標の {@code spireSpacing} 格子に 1 点ずつで、根元が<b>このチャンクの中</b>にあり、
     * {@code spireChance} のくじに当たり、地脈の強さが {@link #MIN_STRENGTH} より上のものだけ。
     */
    public static java.util.List<Site> spireSites(long seed, ChunkPos pos, ClimaxTable.Crystal spec) {
        java.util.List<Site> sites = new java.util.ArrayList<>();
        int spacing = spec.spireSpacing();
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        for (int gz = Math.floorDiv(minZ, spacing); gz <= Math.floorDiv(minZ + 15, spacing); gz++) {
            for (int gx = Math.floorDiv(minX, spacing); gx <= Math.floorDiv(minX + 15, spacing); gx++) {
                long h = hash(seed, gx, gz);
                int x = gx * spacing + (int) ((h >>> 8) % spacing);
                int z = gz * spacing + (int) ((h >>> 20) % spacing);
                if ((x >> 4) != pos.x || (z >> 4) != pos.z) {
                    continue; // 根元が隣のチャンク。そちらが立てる。
                }
                if (unit(h >>> 32) >= spec.spireChance()) {
                    continue;
                }
                double strength = LeyVeinField.strengthAt(seed, x, z);
                if (strength <= MIN_STRENGTH) {
                    continue;
                }
                sites.add(new Site(x, z, h, strength));
            }
        }
        return sites;
    }

    /** 1 本立てる。置いたマス数を返す。 */
    private static int spire(ServerLevel level, int baseX, int groundY, int baseZ, double strength, long h,
                             ClimaxTable.Crystal spec, int budget) {
        RandomSource random = RandomSource.create(h ^ SALT);
        double t = Mth.clamp(strength, 0.0D, 1.0D);
        int span = spec.spireMaxHeight() - spec.spireMinHeight();
        double length = spec.spireMinHeight() + span * (0.35D + 0.65D * t) * (0.7D + 0.3D * random.nextDouble()) + SINK;
        double radius = Math.max(0.8D, spec.spireMaxRadius() * (0.55D + 0.45D * t) * (0.8D + 0.2D * random.nextDouble()));
        double tilt = Math.toRadians(spec.spireMaxTiltDegrees() * random.nextDouble());
        double yaw = random.nextDouble() * Math.PI * 2.0D;
        double twist = random.nextDouble() * Math.PI / 3.0D;

        // 軸 a と、それに直交する 2 本 u, w(六角形の断面の座標)。
        double ax = Math.sin(tilt) * Math.cos(yaw);
        double ay = Math.cos(tilt);
        double az = Math.sin(tilt) * Math.sin(yaw);
        double[] u = normalize(cross(ax, ay, az, 1.0D, 0.0D, 0.0D));
        if (u == null) {
            u = normalize(cross(ax, ay, az, 0.0D, 0.0D, 1.0D));
        }
        double[] w = cross(ax, ay, az, u[0], u[1], u[2]);

        double ox = baseX + 0.5D;
        double oy = groundY - SINK + 0.5D;
        double oz = baseZ + 0.5D;
        int reach = (int) Math.ceil(radius + length * Math.sin(tilt)) + 1;
        int top = (int) Math.ceil(oy + length) + 1;
        BlockState crystal = ModBlocks.ENERGY_CRYSTAL.get().defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int y = groundY - SINK; y <= top && y < level.getMaxBuildHeight(); y++) {
            if (y < level.getMinBuildHeight()) {
                continue;
            }
            for (int x = baseX - reach; x <= baseX + reach; x++) {
                for (int z = baseZ - reach; z <= baseZ + reach; z++) {
                    double px = x + 0.5D - ox;
                    double py = y + 0.5D - oy;
                    double pz = z + 0.5D - oz;
                    double s = px * ax + py * ay + pz * az; // 軸に沿った位置
                    if (s < 0.0D || s > length) {
                        continue;
                    }
                    // 上の 4 割で先端へすぼまる六角柱。
                    double r = s < length * 0.6D ? radius : radius * (length - s) / (length * 0.4D);
                    double qx = px - s * ax;
                    double qy = py - s * ay;
                    double qz = pz - s * az;
                    double cu = qx * u[0] + qy * u[1] + qz * u[2];
                    double cw = qx * w[0] + qy * w[1] + qz * w[2];
                    if (hexDistance(cu, cw, twist) > r) {
                        continue;
                    }
                    if (!level.hasChunk(x >> 4, z >> 4)) {
                        continue; // 読み込まれていないチャンクには書かない
                    }
                    cursor.set(x, y, z);
                    BlockState here = level.getBlockState(cursor);
                    // 地脈ブロックは地表にしか無いので、どの高さでも押しのけてよい。
                    // 地面は埋めた根元(地表から下)だけ。
                    boolean buried = y <= groundY;
                    if (!softForCrystal(here) && !here.is(ModBlocks.LEY_VEIN.get())
                            && !(buried && here.is(ModTags.LEY_VEIN_REPLACEABLE))) {
                        continue;
                    }
                    // 近隣更新は飛ばす(全層置換と同じ)。草花が支えを失ってアイテムを撒かないように。
                    level.setBlock(cursor, crystal, Block.UPDATE_CLIENTS);
                    if (++placed >= budget) {
                        return placed;
                    }
                }
            }
        }
        return placed;
    }

    /** 結晶が押しのけてよいもの: 空気・置き換え可能なもの(草花・蔦・雪・水)・葉。 */
    private static boolean softForCrystal(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.LEAVES)
                || state.is(ModBlocks.OVERGROWN_LEAVES.get()) || state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get());
    }

    /** 平らな辺が 6 つの六角形の「半径」({@code twist} だけ回す)。 */
    private static double hexDistance(double cu, double cw, double twist) {
        double cos = Math.cos(twist);
        double sin = Math.sin(twist);
        double a = cu * cos - cw * sin;
        double b = cu * sin + cw * cos;
        double k = Math.sqrt(3.0D) * 0.5D;
        return Math.max(Math.abs(a), Math.max(Math.abs(a * 0.5D + b * k), Math.abs(a * 0.5D - b * k)));
    }

    private static double[] cross(double ax, double ay, double az, double bx, double by, double bz) {
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1.0e-6D) {
            return null;
        }
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ SALT;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return h;
    }

    private static double unit(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }
}
