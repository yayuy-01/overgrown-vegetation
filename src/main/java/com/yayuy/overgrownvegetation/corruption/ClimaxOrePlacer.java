package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 極相の地下にだけ生まれる鉱石を置く(順 1、2026-10-03 ユーザー指定)。
 *
 * <ul>
 *     <li><b>フロエマイト</b> —— 地脈のあるチャンクを中心に 3×3 チャンク({@code climax.json} の
 *     {@code ores.phloemiteChunkRadius})。どの環境の極相でも出る</li>
 *     <li><b>チラコイト</b> —— 結晶地帯({@link ModBiomes#ENGULFED_CRYSTAL})の地下だけ</li>
 * </ul>
 *
 * <h2>いつ置くか</h2>
 * <p>ワールド生成では置かない。<b>極相の全層置換を終えたとき</b>に {@link ClimaxDeepConverter} から 1 回だけ呼ばれる。
 * 全層置換の<b>後</b>なのは、先に置くと置換が鉱石を「覆った」扱いにしてしまうため(鉱石は対応表に無い)。
 * 置換の後なので周りは苔石・苔深層岩で、鉱石の地もそれに合わせてある。
 *
 * <p>地脈のある/無しは {@link LeyVeinField} がシードと座標だけで答えるので、3×3 の判定のために
 * <b>隣のチャンクを読み込むことはない</b>。
 *
 * <h2>置き方</h2>
 * <p>1 チャンクに {@code veinsPerChunk} 本の鉱脈。鉱脈 1 本は高さを三角分布で引いた点から
 * {@code veinSize} 歩の酔歩で、歩いたマスのうち {@link ModTags#CLIMAX_ORE_REPLACEABLE}(苔石系)を鉱石へ、
 * {@link ModTags#CLIMAX_DEEPSLATE_ORE_REPLACEABLE}(苔深層岩)を深層岩版へ置き換える。洞窟の空気・ほかの鉱石・
 * プレイヤーのブロックは潰さない。歩みはチャンクの外へ出ない(隣のチャンクを書き換えない)。
 *
 * <p>乱数はシードとチャンク座標から作るので、同じチャンクなら何度呼んでも同じ場所に置く
 * (置き換え先は鉱石なので、2 回目は何も変わらない)。
 */
public final class ClimaxOrePlacer {

    private static final long PHLOEMITE_SALT = 0x5F10E4171A2BL;
    private static final long THYLAKITE_SALT = 0x7A1C0E2B9D33L;

    private ClimaxOrePlacer() {
    }

    /** 置いた鉱石のマス数(フロエマイト / チラコイト)。 */
    public record Result(int phloemite, int thylakite) {
    }

    /** そのチャンクに鉱石を置く。<b>読み込まれているチャンクにだけ呼ぶこと。</b> */
    public static Result place(ServerLevel level, ChunkPos pos) {
        ClimaxTable table = DesignData.climax();
        long seed = level.getSeed();
        int phloemite = 0;
        int thylakite = 0;
        if (nearVein(seed, pos, table.phloemiteChunkRadius())) {
            phloemite = placeVeins(level, pos, table.phloemiteOre(),
                    ModBlocks.PHLOEMITE_ORE.get(), ModBlocks.DEEPSLATE_PHLOEMITE_ORE.get(), seed ^ PHLOEMITE_SALT);
        }
        if (isCrystalBiome(level, pos)) {
            thylakite = placeVeins(level, pos, table.thylakiteOre(),
                    ModBlocks.THYLAKITE_ORE.get(), ModBlocks.DEEPSLATE_THYLAKITE_ORE.get(), seed ^ THYLAKITE_SALT);
        }
        return new Result(phloemite, thylakite);
    }

    /** 地脈のあるチャンクから {@code radius} チャンク以内か(1 = 3×3)。チャンクは読まない。 */
    public static boolean nearVein(long seed, ChunkPos pos, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (LeyVeinField.hasVeinInChunk(seed, pos.x + dx, pos.z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * チャンクの中央の列が結晶地帯か。バイオームの書き換え({@code OvergrownBiomeConverter#forceClimax})と
     * 同じ点で見る。<b>読み込まれているチャンクにだけ呼ぶこと</b>({@link ServerLevel#getBiome} は読み込みを起こす)。
     */
    public static boolean isCrystalBiome(ServerLevel level, ChunkPos pos) {
        return level.getBiome(pos.getMiddleBlockPosition(level.getMinBuildHeight())).is(ModBiomes.ENGULFED_CRYSTAL);
    }

    private static int placeVeins(ServerLevel level, ChunkPos pos, ClimaxTable.Ore ore,
                                  Block stoneOre, Block deepslateOre, long salt) {
        if (ore.veinsPerChunk() <= 0 || ore.veinSize() <= 0) {
            return 0;
        }
        RandomSource random = RandomSource.create(salt ^ (pos.toLong() * 0x9E3779B97F4A7C15L));
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        BlockState stoneState = stoneOre.defaultBlockState();
        BlockState deepState = deepslateOre.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int placed = 0;
        for (int v = 0; v < ore.veinsPerChunk(); v++) {
            int x = random.nextInt(16);
            int z = random.nextInt(16);
            int y = triangular(random, ore.minY(), ore.maxY(), ore.peakY());
            for (int step = 0; step < ore.veinSize(); step++) {
                if (y >= minY && y < maxY) {
                    cursor.set(pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z);
                    BlockState here = chunk.getBlockState(cursor);
                    BlockState target = here.is(ModTags.CLIMAX_ORE_REPLACEABLE) ? stoneState
                            : here.is(ModTags.CLIMAX_DEEPSLATE_ORE_REPLACEABLE) ? deepState : null;
                    if (target != null) {
                        // 近隣更新は飛ばす(全層置換と同じ)。石 → 鉱石は形も光の通り方も変わらない。
                        level.setBlock(cursor, target, Block.UPDATE_CLIENTS);
                        placed++;
                    }
                }
                // 隣のマスへ 1 歩。横はチャンクの中に留める(隣のチャンクを書き換えない)。
                switch (random.nextInt(6)) {
                    case 0 -> x = Math.min(15, x + 1);
                    case 1 -> x = Math.max(0, x - 1);
                    case 2 -> z = Math.min(15, z + 1);
                    case 3 -> z = Math.max(0, z - 1);
                    case 4 -> y++;
                    default -> y--;
                }
            }
        }
        return placed;
    }

    /**
     * {@code [min, max]} の三角分布で、{@code peak} がいちばん出やすい整数を引く(逆関数法)。
     * バニラの {@code TrapezoidHeight} は左右対称にしか作れないので自前で引く
     * (ユーザー指定の「y −30〜40、15〜20 が最多」は頂点が真ん中にない)。
     */
    public static int triangular(RandomSource random, int min, int max, double peak) {
        if (max <= min) {
            return min;
        }
        double a = min;
        double b = max + 1.0D; // 上端の整数も出るように半開区間 [min, max + 1)
        double c = Math.max(a, Math.min(b, peak + 0.5D));
        double u = random.nextDouble();
        double split = (c - a) / (b - a);
        double value = u < split
                ? a + Math.sqrt(u * (b - a) * (c - a))
                : b - Math.sqrt((1.0D - u) * (b - a) * (b - c));
        return Math.max(min, Math.min(max, (int) Math.floor(value)));
    }
}
