package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import com.yayuy.overgrownvegetation.organ.OrganCell;
import com.yayuy.overgrownvegetation.organ.OrganData;
import com.yayuy.overgrownvegetation.organ.OrganField;
import com.yayuy.overgrownvegetation.organ.OrganInfluence;
import com.yayuy.overgrownvegetation.organ.OrganKind;
import com.yayuy.overgrownvegetation.organ.OrganShape;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 器官(2026-09-23)。
 *
 * <h2>台帳を必ず空にして始め、空にして終わる</h2>
 * <p>器官の効果は<b>世界全体に効く</b> —— 半径が無い。つまり器官を 1 つ台帳に残したまま
 * テストを終えると、<b>後から走る全部のテストの胞子濃度が上がる</b>。
 * 胞子濃度を見ているテスト(除去装置・感染・シダの湧き)が理由もなく落ちるので、
 * すべての試験を {@link #isolated} で挟む。
 *
 * <h2>台帳に触る試験は 1 本ずつ別のバッチにする</h2>
 * <p><b>同じバッチのテストは並行して走る。</b> 器官の台帳は次元に 1 つしかないので、
 * 同じバッチに入れると<b>互いの台帳を消し合う</b> ——
 * 片方が節を置いた直後に、もう片方の {@link #isolated} が {@code clear()} して
 * 「節を置いたのに台帳へ載っていない」で落ちた(実際に出た)。
 *
 * <p>「自分の分だけ消す」では足りない。下限({@code worldFloorPercent})は
 * <b>台帳ぜんぶの合計</b>なので、他の試験の節が 1 つでも残っていると値が狂う。
 * バッチは順番に走るので、<b>1 本 1 バッチ</b>が唯一の手。
 * 形と場だけを見る試験(世界に触らないもの)は同じバッチでよい。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OrganGameTests {

    private static final String BATCH = "organs";

    /** 台帳に直接載せるときに使う、テスト場から遠い座標(ブロックを置かないので読み込みも要らない)。 */
    private static final BlockPos FAR = new BlockPos(40_000, 70, 40_000);

    /**
     * 塔を<b>実際に建てる</b>試験で使う専用のチャンク列。
     *
     * <p>テスト場の中では建てられない —— 塔は高さ 38・幅 25 あるので、
     * <b>隣で並行して走っている別のテストの足場を壊す</b>
     * (テスト場は狭い升目に並んでいて、外はバリアで囲われているだけ)。
     */
    private static final int TOWER_CHUNK_X = 30_200;

    /**
     * 塔を建てる試験ごとに別のチャンクを使い、<b>置く前に更地へ戻す</b>。
     *
     * <p>更地に戻すのが要る —— テスト場と違い、この遠いチャンクは
     * <b>実行のあいだセーブに残る</b>。前回の節がそのまま残っていると
     * 「同じブロックを置き直した」ことになり、{@code onPlace} が早々に戻って
     * <b>台帳に載らない</b>(実際にこれで落ちた)。
     *
     * @return 節を置く位置
     */
    private static BlockPos freshTowerSite(ServerLevel level, int index) {
        ChunkPos chunk = new ChunkPos(TOWER_CHUNK_X + index * 4, TOWER_CHUNK_X);
        level.getChunk(chunk.x, chunk.z);
        BlockPos node = new BlockPos(chunk.getMiddleBlockX(), 80, chunk.getMiddleBlockZ());
        for (OrganCell cell : OrganShape.build(OrganKind.SPORE_GLAND, level.getSeed(),
                node.getX(), node.getY(), node.getZ())) {
            level.setBlock(node.offset(cell.x(), cell.y(), cell.z()),
                    Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(node, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return node;
    }

    private OrganGameTests() {
    }

    /**
     * 台帳を空にしてから中身を走らせ、<b>必ず</b>空に戻す。
     * 途中で {@code helper.fail} しても戻すため、{@code finally} で囲む。
     */
    private static void isolated(GameTestHelper helper, Consumer<ServerLevel> body) {
        ServerLevel level = helper.getLevel();
        OrganData data = OrganData.get(level);
        data.clear();
        try {
            body.accept(level);
        } finally {
            data.clear();
        }
    }

    /** 育ちきった器官を 1 つ、台帳へ直に載せる(7 日待たないため)。 */
    private static void addMature(ServerLevel level, BlockPos pos) {
        OrganTable.SporeGland spec = OrganData.spec();
        OrganData.get(level).add(pos, OrganKind.SPORE_GLAND, level.getGameTime() - spec.maturityTicks() - 1L);
    }

    // ------------------------------------------------------------------
    // 効き方
    // ------------------------------------------------------------------

    /**
     * <b>育ちきった器官は世界の胞子濃度に下限を作る。</b>
     * 器官から遠く離れた場所でも効く —— これが器官の本体で、
     * 「汚染から遠ざかる」という今までの答えを封じるためのもの。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "1")
    public static void matureOrganRaisesTheWorldFloor(GameTestHelper helper) {
        isolated(helper, level -> {
            OrganTable.SporeGland spec = OrganData.spec();
            if (OrganInfluence.worldFloorPercent(level) != 0.0D) {
                helper.fail("器官が無いのに下限が 0 ではない");
                return;
            }
            addMature(level, FAR);

            double floor = OrganInfluence.worldFloorPercent(level);
            if (Math.abs(floor - spec.worldFloorPercent()) > 0.01D) {
                helper.fail("下限が 1 個ぶんになっていない: " + floor + " (期待 " + spec.worldFloorPercent() + ")");
                return;
            }
            // 器官から 10 万ブロック離れたチャンクでも下限が効くこと。
            double percent = SporeConcentration.at(level, new ChunkPos(-6000, -6000)).percent();
            if (percent < spec.worldFloorPercent() - 0.01D) {
                helper.fail("器官から遠く離れたチャンクに下限が効いていない: " + percent);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>若い器官は何もしない。</b> 生えた瞬間から効くと「今まさに何かが起きた」と分かってしまい、
     * 症状から原因を辿る面白さが消える。気付いたときにはもう効いている、が狙い。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "2")
    public static void freshOrgansDoNothingYet(GameTestHelper helper) {
        isolated(helper, level -> {
            if (OrganData.spec().maturityTicks() <= 0L) {
                helper.succeed(); // 育つ時間を 0 にしてあるなら、この試験は意味を持たない。
                return;
            }
            OrganData.get(level).add(FAR, OrganKind.SPORE_GLAND, level.getGameTime());
            double floor = OrganInfluence.worldFloorPercent(level);
            if (floor > 0.01D) {
                helper.fail("生えたばかりの器官が既に効いている: " + floor);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>下限には頭打ちがある。</b> 器官が増え続けた世界が全部 100% になると、
     * どこも同じになって濃度の差が意味を失う。拠点の手入れも無駄になる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "3")
    public static void theFloorIsCapped(GameTestHelper helper) {
        isolated(helper, level -> {
            OrganTable.SporeGland spec = OrganData.spec();
            int needed = (int) Math.ceil(spec.worldFloorMaxPercent() / Math.max(0.01D, spec.worldFloorPercent())) + 5;
            for (int i = 0; i < needed; i++) {
                addMature(level, FAR.offset(i * 64, 0, 0));
            }
            double floor = OrganInfluence.worldFloorPercent(level);
            if (floor > spec.worldFloorMaxPercent() + 0.01D) {
                helper.fail(needed + " 個で下限が頭打ちを超えた: " + floor);
                return;
            }
            if (floor < spec.worldFloorMaxPercent() - 0.01D) {
                helper.fail(needed + " 個あるのに下限が頭打ちに届いていない: " + floor);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>まわりの上乗せは距離に比例して減る。</b> 下限は世界のどこでも同じ値なので、
     * それだけでは器官を探しようがない。上乗せの勾配が<b>唯一の手がかり</b>で、
     * 胞子濃度計を持って数字が上がる方へ歩けば辿り着ける。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "4")
    public static void theLocalBonusLeadsToTheOrgan(GameTestHelper helper) {
        isolated(helper, level -> {
            OrganTable.SporeGland spec = OrganData.spec();
            addMature(level, FAR);
            ChunkPos home = new ChunkPos(FAR);

            double onTop = OrganInfluence.localPercentAt(level, home);
            double near = OrganInfluence.localPercentAt(level, new ChunkPos(home.x + 2, home.z));
            double far = OrganInfluence.localPercentAt(level, new ChunkPos(home.x + spec.localReachChunks() - 2, home.z));
            double outside = OrganInfluence.localPercentAt(level,
                    new ChunkPos(home.x + spec.localReachChunks() + 4, home.z));

            if (!(onTop > near && near > far && far > 0.0D)) {
                helper.fail("上乗せが距離とともに減っていない: 真上 " + onTop + " / 2 " + near + " / 遠 " + far);
                return;
            }
            if (outside != 0.0D) {
                helper.fail("届く距離の外に上乗せが漏れている: " + outside);
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>器官が 1 つも無い世界では胞子濃度が一切変わらない。</b>
     * 器官は中盤以降のものなので、ほとんどのセーブは最後までこの状態。
     * ここで値がずれると、既存の調整(感染・装備汚染・シダの湧き)が全部動いてしまう。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "5")
    public static void noOrgansMeansNoChange(GameTestHelper helper) {
        isolated(helper, level -> {
            ChunkPos here = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
            SporeConcentration.Result r = SporeConcentration.at(level, here);
            if (r.organFloorComponent() != 0.0D || r.organLocalComponent() != 0.0D) {
                helper.fail("器官が無いのに成分が乗っている: 下限 " + r.organFloorComponent()
                        + " / 上乗せ " + r.organLocalComponent());
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------
    // 節と体
    // ------------------------------------------------------------------

    /**
     * <b>節を置くと体が組み上がる。</b> 生成もコマンドもクリエイティブの手置きも
     * 同じ道({@code OrganNodeBlock#onPlace})を通るので、経路ごとに形が違うことがない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "6")
    public static void placingTheNodeGrowsTheBody(GameTestHelper helper) {
        isolated(helper, level -> {
            BlockPos node = freshTowerSite(level, 0);
            level.setBlock(node, ModBlocks.ORGAN_NODE.get().defaultBlockState(), Block.UPDATE_ALL);

            if (!OrganData.get(level).has(node)) {
                helper.fail("節を置いたのに台帳へ載っていない: " + node.toShortString()
                        + " / そこにあるブロック = " + level.getBlockState(node)
                        + " / 台帳 " + OrganData.get(level).count() + " 件");
                return;
            }
            List<OrganCell> cells = OrganShape.build(OrganKind.SPORE_GLAND, level.getSeed(),
                    node.getX(), node.getY(), node.getZ());
            if (cells.size() < 50) {
                helper.fail("体が小さすぎる(" + cells.size() + " マス)。塔になっていない");
                return;
            }
            // 上へ伸びていること(塔なので)。
            int top = cells.stream().mapToInt(OrganCell::y).max().orElse(0);
            if (top < 10) {
                helper.fail("塔の高さが " + top + " しかない");
                return;
            }
            // 実際に置かれていること(テスト場は空気なので全部置けるはず)。
            long placed = cells.stream()
                    .filter(c -> !(c.x() == 0 && c.y() == 0 && c.z() == 0))
                    .filter(c -> isBody(level.getBlockState(
                            node.offset(c.x(), c.y(), c.z()))))
                    .count();
            if (placed < cells.size() * 0.8D) {
                helper.fail("体が置かれていない: " + placed + " / " + cells.size());
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>節を潰せば効果はその場で消え、体は枯れていく。</b>
     * ここが繋がっていないと「壊したのに濃度が下がらない」になり、
     * プレイヤーは自分の推理が正しかったのか分からなくなる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH + "7")
    public static void breakingTheNodeKillsTheOrgan(GameTestHelper helper) {
        isolated(helper, level -> {
            BlockPos node = freshTowerSite(level, 1);
            level.setBlock(node, ModBlocks.ORGAN_NODE.get().defaultBlockState(), Block.UPDATE_ALL);
            OrganData data = OrganData.get(level);

            // 育ちきらせてから潰す。
            data.clear();
            addMature(level, node);
            if (OrganInfluence.worldFloorPercent(level) <= 0.0D) {
                helper.fail("育ちきった器官が下限を作っていない");
                return;
            }

            level.destroyBlock(node, false);
            if (data.has(node)) {
                helper.fail("節を壊したのに生きている側へ残っている");
                return;
            }
            if (OrganInfluence.worldFloorPercent(level) != 0.0D) {
                helper.fail("潰したのに下限が残っている: " + OrganInfluence.worldFloorPercent(level));
                return;
            }
            if (!data.withering().containsKey(node.asLong())) {
                helper.fail("枯れかけに回っていない —— 体が残りっぱなしになる");
                return;
            }
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------
    // 形
    // ------------------------------------------------------------------

    /**
     * <b>三つ編みの塔は胞子腺だけの姿。</b> 形が種類ごとに差し替えられること、
     * そして<b>同じ節なら何度計算しても同じ形</b>になることを見る ——
     * 枯らすときに形を計算し直すので、ここがぶれると体が残る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void theShapeIsStableAndHasAllThreeParts(GameTestHelper helper) {
        long seed = helper.getLevel().getSeed();
        BlockPos node = new BlockPos(1234, 70, -5678);
        List<OrganCell> first = OrganShape.build(OrganKind.SPORE_GLAND, seed,
                node.getX(), node.getY(), node.getZ());
        List<OrganCell> second = OrganShape.build(OrganKind.SPORE_GLAND, seed,
                node.getX(), node.getY(), node.getZ());
        if (!new HashSet<>(first).equals(new HashSet<>(second))) {
            helper.fail("同じ節から違う形が返った —— 枯らすときに体が残る");
            return;
        }

        Set<OrganCell.Part> parts = new HashSet<>();
        first.forEach(c -> parts.add(c.part()));
        if (!parts.contains(OrganCell.Part.VINE) || !parts.contains(OrganCell.Part.OVERGROWN)) {
            helper.fail("蔦と汚染ブロックが混ざっていない: " + parts);
            return;
        }
        if (!parts.contains(OrganCell.Part.GLAND)) {
            helper.fail("腺が 1 つも無い —— 削って進む罰が掛からない");
            return;
        }
        // いちばん上は必ず腺(胞子の噴き出し口)。
        int top = first.stream().mapToInt(OrganCell::y).max().orElse(-1);
        boolean tipIsGland = first.stream()
                .filter(c -> c.y() == top)
                .anyMatch(c -> c.part() == OrganCell.Part.GLAND);
        if (!tipIsGland) {
            helper.fail("先端が腺になっていない —— 胞子を噴く場所が無い");
            return;
        }
        // 根元は節を隠せる太さがあること。
        long rootCells = first.stream().filter(c -> c.y() == 0).count();
        if (rootCells < 9) {
            helper.fail("根元が細すぎて節がむき出しになる: " + rootCells + " マス");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>筋は互いに触れて支え合っていること。</b>
     *
     * <p>最初の実装は「独立した蔦がうねりながら立っているだけ」に見えた(報告)。
     * 原因は幾何で決まっていた —— 筋の<b>角度が 120 度で固定</b>だと、
     * 半径をどう振っても隣との距離が縮まらず、<b>一度も触れない</b>。
     *
     * <p>目で見ても分かりにくいので<b>数で縛る</b>。1 段を横に切って
     * 「4 近傍で繋がった塊」の数を数えると、<b>3.0 = 3 本が一度も触れていない</b>。
     * 2.95 を超えていたら、角度の振れ({@code weaveDegrees})が効いていないか、
     * 半径に対して筋が細すぎる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200, batch = BATCH)
    public static void strandsLeanOnEachOther(GameTestHelper helper) {
        OrganTable.Tower tower = DesignData.organs().sporeGland().tower();
        List<OrganCell> cells = OrganShape.build(OrganKind.SPORE_GLAND,
                helper.getLevel().getSeed(), 500, 70, -500);
        int top = cells.stream().mapToInt(OrganCell::y).max().orElse(0);

        double sum = 0.0D;
        int layers = 0;
        for (int y = tower.rootHeight() + 1; y <= top - 3; y++) {
            Set<Long> layer = new HashSet<>();
            for (OrganCell c : cells) {
                if (c.y() == y) {
                    layer.add(((long) c.x() << 32) ^ (c.z() & 0xFFFFFFFFL));
                }
            }
            if (layer.isEmpty()) {
                continue;
            }
            sum += islands(layer);
            layers++;
        }
        if (layers == 0) {
            helper.fail("編み目の段が 1 つも無い —— 塔が低すぎる");
            return;
        }
        double average = sum / layers;
        if (average > 2.95D) {
            helper.fail("筋が互いに触れていない(1 段あたりの島 " + String.format("%.2f", average)
                    + ")。角度の振れが効いていないか、半径に対して筋が細すぎる");
            return;
        }
        helper.succeed();
    }

    /** その段の「4 近傍で繋がった塊」の数。 */
    private static int islands(Set<Long> layer) {
        Set<Long> seen = new HashSet<>();
        int count = 0;
        for (long start : layer) {
            if (!seen.add(start)) {
                continue;
            }
            count++;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long k = queue.poll();
                int x = (int) (k >> 32);
                int z = (int) k;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long n = ((long) (x + d[0]) << 32) ^ ((z + d[1]) & 0xFFFFFFFFL);
                    if (layer.contains(n) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
        }
        return count;
    }

    /**
     * <b>器官は地脈の上にしか生えない。</b> 候補地は升目ごとにちょうど 1 つで、
     * 必ず地脈が通っているチャンクから選ばれる(要望)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = BATCH)
    public static void sitesSitOnLeyVeins(GameTestHelper helper) {
        long seed = helper.getLevel().getSeed();
        int cell = DesignData.organs().sporeGland().cellSizeChunks();
        int found = 0;

        for (int cellX = 0; cellX <= 2; cellX++) {
            for (int cellZ = 0; cellZ <= 2; cellZ++) {
                ChunkPos site = OrganField.siteIn(seed, cellX, cellZ, cell);
                if (site == null) {
                    continue; // この升目には地脈が 1 本も通っていない。
                }
                found++;
                if (!OrganField.hasVein(seed, site.x, site.z)) {
                    helper.fail("候補地に地脈が通っていない: " + site);
                    return;
                }
                if (Math.floorDiv(site.x, cell) != cellX || Math.floorDiv(site.z, cell) != cellZ) {
                    helper.fail("候補地が升目の外に出ている: " + site);
                    return;
                }
                if (!OrganField.isSite(seed, site.x, site.z, cell)) {
                    helper.fail("候補地なのに isSite が false: " + site);
                    return;
                }
                if (!site.equals(OrganField.siteIn(seed, cellX, cellZ, cell))) {
                    helper.fail("同じ升目から違う候補地が返った: " + site);
                    return;
                }
            }
        }
        if (found == 0) {
            helper.fail("9 升目のどこにも候補地が無い —— 地脈の網目が粗すぎるか、判定が壊れている");
            return;
        }
        helper.succeed();
    }

    private static boolean isBody(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(ModBlocks.ORGAN_VINE.get())
                || state.is(ModBlocks.SPORE_GLAND.get())
                || state.is(ModBlocks.OVERGROWN_BLOCK.get());
    }
}
