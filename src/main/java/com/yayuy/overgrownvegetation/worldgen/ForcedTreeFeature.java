package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.block.BranchJointBlock;
import com.yayuy.overgrownvegetation.block.CordaitesButtressBlock;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * バニラの {@code minecraft:tree}(= {@link net.minecraft.world.level.levelgen.feature.TreeFeature})は
 * 「幹・葉に必要な位置がすべて空気/置き換え可能でなければ丸ごと失敗する」という全か無かの判定を持つため、
 * 頭上が塞がっている・明るさが足りないといった条件下では苗木がいつまでも育たないことがある。
 *
 * <p>このクラスはそれとは逆に、判定を一切行わず幹・枝・葉が必要な位置だけを強制的に置き換える。
 * 苗木は{@link com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock}側で明るさ判定も
 * 外してあるので、この Feature と組み合わせることで「どんな場所でも(頭上を岩で塞いでいても)
 * 苗木は必ず木に育つが、木のブロックが実際に必要な場所以外は一切いじらない」という
 * 最小限の置き換えを実現する。
 *
 * <p>幹は途中まで(あるいは {@code branchCount} が 0 なら最後まで)まっすぐ伸び、
 * その先は{@code branchCount} 本の枝が幹上部のランダムな高さから斜め上へ分岐する
 * (バニラのアカシアの木の {@code forking_trunk_placer} に近いイメージ)。
 * 葉は幹の先端に必ず1つ乗るほか、枝があればその先端それぞれにも
 * アカシアの木のような薄く平たい塊(半径に対して層数=厚みが小さい)で乗る。
 * {@code branchCount} が 0 の場合は分岐せず、幹の先端の葉だけになる
 * (低木バリアント {@code overgrown_tree_short} 向け)。
 *
 * <p>幹の根本からは {@code rootMinCount}〜{@code rootMaxCount} 本の太い根が放射状に伸びる。
 * 根は 6 面すべてが樹皮の {@code overgrown_wood}(過繁茂した木。幹が {@code overgrown_log} の場合)で
 * 構成され({@link #rootStateFor})、既存のブロックが何であろうと強制的に置き換える点は幹と同じ
 * (地面の下に少しずつ潜っていくが、地下の岩盤やブロックを気にせず貫通する)。
 *
 * <p>高さ・葉の大きさ・幹の太さ・枝の本数と長さはすべて {@link Config} でパラメータ化してあるので、
 * 複数の設定を {@code minecraft:random_selector} で束ねれば「普段は高さ25〜30、
 * まれに1〜4の低木や31〜40の巨木も混じる」といった熱帯雨林らしい高さのばらつきを
 * JSON だけで表現できる(実際の既定設定は
 * {@code data/overgrown_vegetation/worldgen/configured_feature/overgrown_tree.json} と
 * その参照先である {@code overgrown_tree_short.json} / {@code _main.json} / {@code _tall.json} を参照)。
 */
public class ForcedTreeFeature extends Feature<ForcedTreeFeature.Config> {

    /** 枝が伸びる8方向(斜め上へ1ブロックずつ進む)。 */
    private static final int[][] BRANCH_DIRECTIONS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1}, {0, 1},
            {1, -1}, {1, 0}, {1, 1}
    };

    public ForcedTreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Config config = context.config();
        BlockPos origin = context.origin();
        // 構造物にかかる所の苗は構造物に合わせた形で育つ(11-19 構造物と森の融合 段 2b。欠けて浮いた木を作らない)
        Boolean fusionTree = com.yayuy.overgrownvegetation.organ.StructureSaplingTrees.tryGrow(level, origin, random);
        if (fusionTree != null) return fusionTree;

        int minHeight = Math.min(config.minTrunkHeight(), config.maxTrunkHeight());
        int maxHeight = Math.max(config.minTrunkHeight(), config.maxTrunkHeight());
        int trunkHeight = minHeight >= maxHeight ? minHeight : minHeight + random.nextInt(maxHeight - minHeight + 1);
        int trunkWidth = Math.max(1, Math.min(2, config.trunkWidth()));

        // 高さ 6 を超える木は洞窟内(空が見えない場所)には生やさない。低木(短木バリアント)は許可。
        if (trunkHeight > TreePlacementRules.CAVE_MAX_TREE_HEIGHT
                && TreePlacementRules.isEnclosed(level, origin)) {
            return false;
        }

        // 今回の成長で置いた原木ブロック(幹+枝)の座標。生え終わった直後に
        // TreeVineConnector へそのまま渡し、「他の木」との判別に使う(下記参照)。
        LongOpenHashSet logPositions = new LongOpenHashSet();

        // 今回の成長で置いた葉ブロックの座標。全部置き終えたあとに「大きな未発達な果実」を
        // ぶら下げる抽選に使う(置いている途中だと、真下がまだ葉で埋まるか分からない)。
        LongOpenHashSet leafPositions = new LongOpenHashSet();

        // 幹: 苗木があった位置から真上へ、既存のブロックが何であろうと必ず置き換える。
        // trunkWidth == 2 のときは 2x2 の太い幹にする(バニラのジャングルの巨木のイメージ)。
        // 根元の skirt 段は苔の付いた幹の絵(案 H の 31、2026-10-31)。
        int skirt = skirtSteps(config, random);
        for (int i = 0; i < trunkHeight; i++) {
            int y = origin.getY() + i;
            BlockState trunk = i < skirt ? config.trunkState().setValue(com.yayuy.overgrownvegetation.block.OvergrownLogBlock.SKIRT, true) : config.trunkState();
            for (int dx = 0; dx < trunkWidth; dx++) {
                for (int dz = 0; dz < trunkWidth; dz++) {
                    BlockPos trunkPos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    setForced(level, trunkPos, trunk);
                    logPositions.add(trunkPos.asLong());
                    // (枝ではなく)主幹にだけ、ごく低確率でヒカリゴケを1面貼り付ける。
                    maybeAddGlowLichen(level, random, trunkPos, config);
                }
            }
        }

        // 幹の先端には、枝の有無に関わらず必ず葉の塊を乗せる
        // (分岐していても幹のてっぺんが丸裸に見えないように)。
        placeCanopy(level, random, origin.above(trunkHeight), trunkWidth, config, leafPositions);

        // 幹の根本から放射状に太い根を伸ばす(6面が樹皮の overgrown_wood で構成される)。
        placeRoots(level, random, origin, trunkWidth, config);

        int branchCount = Math.max(0, config.branchCount());
        if (branchCount > 0) {
            placeBranches(level, random, origin, trunkHeight, trunkWidth, branchCount, config, logPositions, leafPositions);
            // 中層の葉の房(2026-11-02、下絵 33 の案 B)。林冠の塊を置き終えてから(塊の下を避けるため)。
            if (jointStateFor(config.trunkState()) != null) {
                placeMidTufts(level, random, origin, trunkHeight, trunkWidth, config, leafPositions);
            }
        }

        // 木の洞(案 H の 43、2026-10-31): 幹の何段目かの、空気に面した 1 面に小さな穴。
        maybeHollow(level, random, origin, trunkHeight, trunkWidth, config);

        // 育ち終えた直後に、近くの別の木との間へ蔦を張れないか試す
        // (spawnConnectingVines / vineMaxPerTree を 0 にすれば無効化できる)。
        TreeVineConnector.tryConnect(level.getLevel(), random, origin, logPositions);
        // 側面・底面に生えるもの(2026-11-01): 幹に巻き付く種子シダ・気根・ひげ地衣・胞子嚢の袋。
        TreeSideGrowth.decorate(level, random, logPositions, leafPositions, false);

        // 樹冠が出来上がってから、葉の下へ「大きな未発達な果実」を低確率でぶら下げる。
        double fruitChance = OvergrownVegetationConfig.COMMON.undevelopedFruitChance.get();
        if (fruitChance > 0.0D) {
            BlockPos.MutableBlockPos leafPos = new BlockPos.MutableBlockPos();
            for (LongIterator it = leafPositions.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                leafPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                UndevelopedFruitPlacement.tryHang(level, random, leafPos.immutable(), fruitChance);
            }
        }

        return true;
    }

    /**
     * 幹上部のランダムな高さから、斜め上へ伸びる枝を {@code branchCount} 本生やし、
     * それぞれの先端に葉の塊を乗せる。
     */
    private static void placeBranches(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkHeight,
            int trunkWidth, int branchCount, Config config, LongOpenHashSet logPositions,
            LongOpenHashSet leafPositions) {
        int minLen = Math.max(1, Math.min(config.branchMinLength(), config.branchMaxLength()));
        int maxLen = Math.max(minLen, Math.max(config.branchMinLength(), config.branchMaxLength()));
        // 枝の分岐点は幹の最上部から branchZoneHeight ぶん下までの範囲に散らす。
        int zone = Math.max(1, Math.min(config.branchZoneHeight(), trunkHeight));
        List<BlockPos> joints = new ArrayList<>();

        for (int i = 0; i < branchCount; i++) {
            int startOffsetFromTop = random.nextInt(zone);
            int startY = origin.getY() + trunkHeight - 1 - startOffsetFromTop;

            // trunkWidth==2 のときは2x2の幹のどこかの角から分岐させて見た目にばらつきを出す。
            int cornerX = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;
            int cornerZ = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;

            int[] dir = BRANCH_DIRECTIONS[random.nextInt(BRANCH_DIRECTIONS.length)];
            int length = minLen + random.nextInt(maxLen - minLen + 1);

            int x = origin.getX() + cornerX;
            int y = startY;
            int z = origin.getZ() + cornerZ;

            // 枝の列(先頭は幹のマス)を集めてから、枝の継ぎ目の計画(BranchJoints。2026-11-01)に従って置く。
            List<BlockPos> path = new ArrayList<>(length + 1);
            path.add(new BlockPos(x, y, z));
            for (int step = 0; step < length; step++) {
                x += dir[0];
                y += 1;
                z += dir[1];
                path.add(new BlockPos(x, y, z));
            }
            BlockState joint = jointStateFor(config.trunkState());
            var cfg = OvergrownVegetationConfig.COMMON;
            BranchJoints.Plan plan = BranchJoints.plan(path, joint != null && cfg.branchJointTaper.get(),
                    joint != null && cfg.branchJointFill.get(),
                    p -> isFreeForJoint(level.getBlockState(p), config),
                    p -> BranchJointBlock.connectsTo(level.getBlockState(p)));
            joints.addAll(BranchJoints.place(level, plan, config.trunkState(), joint, (p, s) -> setForced(level, p, s)));
            for (BlockPos p : plan.logs()) {
                logPositions.add(p.asLong());
            }

            placeCanopy(level, random, new BlockPos(x, y + 1, z), 1, config, leafPositions);
        }
        // 腕は全部の枝と葉を置き終えてから組む(後の枝の原木が隣に来ることがある)。
        BranchJoints.reconnect(level, joints);
        // 後の枝が前の枝の原木を継ぎ目に置き換えることがある。蔦の端(原木の集合)に継ぎ目を残さない。
        for (BlockPos p : joints) {
            if (!level.getBlockState(p).is(config.trunkState().getBlock())) {
                logPositions.remove(p.asLong());
            }
        }
    }

    /** 1 本の木の中層の房の数・高さの帯(幹の高さの割合)・房の上に林冠の塊を許さない段数。どれも仮決め(2026-11-02)。 */
    static final int MID_TUFTS = 2;
    static final double MID_TUFT_LOW = 0.4, MID_TUFT_HIGH = 0.6;
    static final int MID_TUFT_CLEARANCE = 3;
    /** 林冠の塊を見る水平の幅(房の芯から ±。2 では低い枝の塊に掛かり、幹 20 前後の木の多くが房 0〜1 になった)。仮決め。 */
    public static final int MID_TUFT_SPAN = 1;

    /**
     * 中層の葉の房(2026-11-02、下絵 33 の案 B。ユーザー指定 + 腕は枝のブロック)。幹の高さの {@value #MID_TUFT_LOW}〜{@value #MID_TUFT_HIGH} の帯の
     * 2 か所(高さは 2 段以上離す・向きは別々)に、幹の面から水平に 1〜2 マスの短い腕(枝の継ぎ目 {@code overgrown_branch}。2 マスなら先が細い)を出し、
     * 腕の延長の次のマス(同じ高さ。腕が刺さる)に葉 1 ブロック + 向き付きの板 5 枚(上に立ち上がる 1・下に垂れる 1・腕の軸に直交する横 2・先 1。幹の側は無し。
     * {@code overgrown_leaves_tuft}。11-02 浮きの直しで、前の「腕先の上に葉・板 4 枚」から変えた。5 枚は仮決め)。
     * 房の上 {@value #MID_TUFT_CLEARANCE} 段(水平 ±{@value #MID_TUFT_SPAN})に葉がある所(林冠の塊の下)と、腕・葉・上下の板のマスが空いていない所には置かない(先と横は空いていれば)。
     */
    private static void placeMidTufts(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkHeight, int trunkWidth,
            Config config, LongOpenHashSet leafPositions) {
        int lo = (int) Math.round(trunkHeight * MID_TUFT_LOW), hi = (int) Math.round(trunkHeight * MID_TUFT_HIGH);
        if (lo < 2 || hi < lo) {
            return;
        }
        List<Integer> heights = new ArrayList<>();
        for (int i = lo; i <= hi; i++) {
            heights.add(i);
        }
        net.minecraft.Util.shuffle(heights, random);
        List<Direction> dirs = new ArrayList<>(List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
        net.minecraft.Util.shuffle(dirs, random);
        List<Integer> usedHeights = new ArrayList<>();
        List<Direction> usedDirs = new ArrayList<>();
        LongOpenHashSet tuftCells = new LongOpenHashSet();
        for (int h : heights) {
            if (usedHeights.size() >= MID_TUFTS) {
                break;
            }
            if (usedHeights.stream().anyMatch(u -> Math.abs(u - h) < 2)) {
                continue;
            }
            for (Direction dir : dirs) {
                if (usedDirs.contains(dir)) {
                    continue;
                }
                int len = 1 + random.nextInt(2);
                if (tryMidTuft(level, random, origin, trunkWidth, h, dir, len, config, leafPositions, tuftCells)
                        || tryMidTuft(level, random, origin, trunkWidth, h, dir, 3 - len, config, leafPositions, tuftCells)) {
                    usedHeights.add(h);
                    usedDirs.add(dir);
                    break;
                }
            }
        }
    }

    private static boolean tryMidTuft(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkWidth, int h, Direction dir, int len,
            Config config, LongOpenHashSet leafPositions, LongOpenHashSet tuftCells) {
        int along = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;
        int tx = origin.getX() + (dir == Direction.EAST ? trunkWidth - 1 : dir == Direction.WEST ? 0 : along);
        int tz = origin.getZ() + (dir == Direction.SOUTH ? trunkWidth - 1 : dir == Direction.NORTH ? 0 : along);
        BlockPos trunk = new BlockPos(tx, origin.getY() + h, tz);
        if (!level.getBlockState(trunk).is(config.trunkState().getBlock())) {
            return false;
        }
        List<BlockPos> arm = new ArrayList<>(len);
        for (int k = 1; k <= len; k++) {
            arm.add(trunk.relative(dir, k));
        }
        // 芯は腕の延長の次のマス(同じ高さ)。腕が芯に刺さる(11-02 浮きの直し。前は腕先の上に乗せていて、細い枝から浮いて見えた)
        BlockPos core = trunk.relative(dir, len + 1);
        BlockPos rise = core.above(), hang = core.below();
        for (BlockPos p : arm) {
            if (!isFreeForTuft(level, p, leafPositions)) {
                return false;
            }
        }
        // 芯・上・下は必ず空いていること。先と横 2 枚は空いていれば付ける(全部を条件にすると房がさらに減る。仮決め)
        if (!isFreeForTuft(level, core, leafPositions) || !isFreeForTuft(level, rise, leafPositions) || !isFreeForTuft(level, hang, leafPositions)) {
            return false;
        }
        // 林冠の塊の下は避ける(房の上 MID_TUFT_CLEARANCE 段・水平 ±MID_TUFT_SPAN に葉)
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy <= MID_TUFT_CLEARANCE; dy++) {
            for (int dx = -MID_TUFT_SPAN; dx <= MID_TUFT_SPAN; dx++) {
                for (int dz = -MID_TUFT_SPAN; dz <= MID_TUFT_SPAN; dz++) {
                    BlockState s = level.getBlockState(q.set(core.getX() + dx, core.getY() + dy, core.getZ() + dz));
                    if (tuftCells.contains(q.asLong())) {
                        continue; // 先に置いたこの木の房は塊に数えない
                    }
                    if (s.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock || s.is(BlockTags.LEAVES)) {
                        return false;
                    }
                }
            }
        }
        BlockState joint = ModBlocks.OVERGROWN_BRANCH.get().defaultBlockState();
        for (int k = 0; k < len; k++) {
            setForced(level, arm.get(k), joint.setValue(BranchJointBlock.THICK, !(len > 1 && k == len - 1)));
        }
        setForced(level, core, config.leavesState());
        BranchJoints.reconnect(level, arm);
        // 継ぎ目は葉へ腕を出さないので、腕先から芯へだけ手で繋ぐ(BranchJointBlock#updateShape は葉への腕を残す)
        BlockPos tip = arm.get(len - 1);
        setForced(level, tip, level.getBlockState(tip).setValue(net.minecraft.world.level.block.PipeBlock.PROPERTY_BY_DIRECTION.get(dir), true));
        leafPositions.add(core.asLong());
        tuftCells.add(core.asLong());
        tuftCells.add(rise.asLong());
        tuftCells.add(hang.asLong());
        BlockState tuft = ModBlocks.OVERGROWN_LEAVES_TUFT.get().defaultBlockState();
        setForced(level, rise, tuft.setValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.FACING, Direction.DOWN));
        setForced(level, hang, tuft.setValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.FACING, Direction.UP));
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side == dir.getOpposite()) {
                continue; // 幹・腕の側
            }
            BlockPos p = core.relative(side);
            BlockState here = level.getBlockState(p);
            if (here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty()) {
                setForced(level, p, tuft.setValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.FACING, side.getOpposite()));
                tuftCells.add(p.asLong());
            }
        }
        return true;
    }

    private static boolean isFreeForTuft(WorldGenLevel level, BlockPos pos, LongOpenHashSet leafPositions) {
        BlockState s = level.getBlockState(pos);
        return !leafPositions.contains(pos.asLong()) && (s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty());
    }

    /** 幹が {@code overgrown_log} なら枝の継ぎ目 {@code overgrown_branch}、それ以外の幹には継ぎ目を使わない(null)。 */
    private static BlockState jointStateFor(BlockState trunkState) {
        return trunkState.is(ModBlocks.OVERGROWN_LOG.get()) ? ModBlocks.OVERGROWN_BRANCH.get().defaultBlockState() : null;
    }

    /** 隙間埋めの継ぎ目を置いてよいマス(空気・置き換えられる物・葉)。 */
    private static boolean isFreeForJoint(BlockState state, Config config) {
        return state.isAir() || state.canBeReplaced() || state.is(config.leavesState().getBlock());
    }

    /** 根の1歩ごとに、直前の高さからこの範囲内で地表を探す(急な崖・穴での暴走を防ぐ)。 */
    private static final int ROOT_GROUND_SEARCH_RANGE = 4;

    /**
     * 幹の根本(苗木があった位置)から水平8方向のうち {@code rootMinCount}〜{@code rootMaxCount} 本を
     * ランダムに選び、それぞれ {@code rootLength} ブロックぶん放射状に伸ばす。1歩ごとに水平へ1マス
     * 進み、その位置の地表の高さ({@link #findGroundY})に合わせて上下することで、地形の起伏に
     * 沿うように這う太い根のシルエットにする。根に使うブロックは {@link #rootStateFor}(幹が
     * {@code overgrown_log} なら樹皮だけの {@code overgrown_wood}、それ以外の幹はそのまま流用)。
     */
    private static void placeRoots(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkWidth, Config config) {
        if (config.trunkState().is(ModBlocks.OVERGROWN_LOG.get())) {
            placeButtresses(level, random, origin, trunkWidth, config);
            return;
        }
        BlockState rootState = rootStateFor(config.trunkState());
        int minCount = Math.max(0, Math.min(config.rootMinCount(), config.rootMaxCount()));
        int maxCount = Math.max(minCount, Math.max(config.rootMinCount(), config.rootMaxCount()));
        if (maxCount <= 0) {
            return;
        }
        int count = Math.min(BRANCH_DIRECTIONS.length,
                minCount >= maxCount ? minCount : minCount + random.nextInt(maxCount - minCount + 1));
        if (count <= 0) {
            return;
        }

        int length = Math.max(1, config.rootLength());

        // 使う方向を重複無く選ぶため、8方向をシャッフルしてから先頭 count 本だけ使う。
        List<int[]> directions = new ArrayList<>(BRANCH_DIRECTIONS.length);
        for (int[] dir : BRANCH_DIRECTIONS) {
            directions.add(dir);
        }
        for (int i = directions.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] tmp = directions.get(i);
            directions.set(i, directions.get(j));
            directions.set(j, tmp);
        }

        for (int i = 0; i < count; i++) {
            int[] dir = directions.get(i);

            int cornerX = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;
            int cornerZ = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;

            int x = origin.getX() + cornerX;
            int y = origin.getY();
            int z = origin.getZ() + cornerZ;

            for (int step = 0; step < length; step++) {
                x += dir[0];
                z += dir[1];
                // 直前の高さを中心に、その列の地表を探して合わせる(=地形の起伏に沿わせる)。
                y = findGroundY(level, x, y, z);
                setForced(level, new BlockPos(x, y, z), rootState);
            }
        }
    }

    /**
     * コルダイテスの板根(2026-11-02、下絵 32 の案 A。ユーザー指定)。幹の 4 面のうち {@code rootMinCount}〜{@code rootMaxCount} 面(4 まで)から、
     * 外へ {@code rootLength} 段の板({@link CordaitesButtressBlock})を地形に沿って置く。段は幹から離れるほど低い:
     * 2 段 = NEAR → TIP(NEAR の上に幹を這い上がる CLIMB_TOP)、3 段以上 = BIG → MID … → TIP(BIG の上に CLIMB・CLIMB_TOP)、1 段 = TIP。
     * 板は地面の上のマスに置く(前の根は地面のマスを原木に置き換えていた)。地面の段差は {@link #nextRootGround}: 1 段目は地面が幹の根元と同じ高さの面だけ、
     * 2 段目からは 1 段下がる列に落ちる部品({@link CordaitesButtressBlock.Part#DROP}。段差の面を降りてその列の段を兼ねる)、上がる・2 段以上・水・地面でない物で止める。
     * 2×2 の幹は面の 2 列のどちらかから出す。
     */
    private static void placeButtresses(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkWidth, Config config) {
        int minCount = Math.max(0, Math.min(config.rootMinCount(), config.rootMaxCount()));
        int maxCount = Math.max(minCount, Math.max(config.rootMinCount(), config.rootMaxCount()));
        int count = Math.min(4, minCount >= maxCount ? minCount : minCount + random.nextInt(maxCount - minCount + 1));
        if (count <= 0) {
            return;
        }
        int length = Math.max(1, config.rootLength());
        List<Direction> faces = new ArrayList<>(List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
        net.minecraft.Util.shuffle(faces, random);
        BlockState base = ModBlocks.CORDAITES_BUTTRESS.get().defaultBlockState();
        for (int i = 0; i < count; i++) {
            Direction face = faces.get(i);
            int along = trunkWidth > 1 ? random.nextInt(trunkWidth) : 0;
            // 面に接する幹の列(2×2 なら面の 2 列のどちらか)
            int tx = origin.getX() + (face == Direction.EAST ? trunkWidth - 1 : face == Direction.WEST ? 0 : along);
            int tz = origin.getZ() + (face == Direction.SOUTH ? trunkWidth - 1 : face == Direction.NORTH ? 0 : along);
            int y = origin.getY() - 1;
            for (int step = 0; step < length; step++) {
                int x = tx + face.getStepX() * (step + 1);
                int z = tz + face.getStepZ() * (step + 1);
                int ground = nextRootGround(level, x, y, z);
                // 幹に接する 1 段目は地面が幹の根元と同じ高さのときだけ。2 段目からは 1 段下がるなら落ちる部品で降りる
                if (ground == Integer.MIN_VALUE || step == 0 && ground != y) {
                    break;
                }
                boolean drop = ground < y;
                y = ground;
                CordaitesButtressBlock.Part part = drop ? CordaitesButtressBlock.Part.DROP : buttressPart(step, length);
                BlockPos cell = new BlockPos(x, y + 1, z);
                setForced(level, cell, base.setValue(CordaitesButtressBlock.FACING, face).setValue(CordaitesButtressBlock.PART, part));
                if (step == 0) {
                    // 幹を這い上がる箱(幹に接する 1 段目の上)
                    if (part == CordaitesButtressBlock.Part.BIG) {
                        placeClimb(level, cell.above(), base.setValue(CordaitesButtressBlock.FACING, face).setValue(CordaitesButtressBlock.PART, CordaitesButtressBlock.Part.CLIMB));
                        placeClimb(level, cell.above(2), base.setValue(CordaitesButtressBlock.FACING, face).setValue(CordaitesButtressBlock.PART, CordaitesButtressBlock.Part.CLIMB_TOP));
                    } else if (part == CordaitesButtressBlock.Part.NEAR) {
                        placeClimb(level, cell.above(), base.setValue(CordaitesButtressBlock.FACING, face).setValue(CordaitesButtressBlock.PART, CordaitesButtressBlock.Part.CLIMB_TOP));
                    }
                }
            }
        }
    }

    /** GameTest 用: 板根だけを置く(幹は置かない。段差の扱いを狭い台で見る)。 */
    public static void placeButtressesForTests(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkWidth, Config config) {
        placeButtresses(level, random, origin, trunkWidth, config);
    }

    /** 板根の {@code step} 段目(0 = 幹の隣)の形。 */
    static CordaitesButtressBlock.Part buttressPart(int step, int length) {
        if (step == length - 1) {
            return CordaitesButtressBlock.Part.TIP;
        }
        if (length == 2) {
            return CordaitesButtressBlock.Part.NEAR;
        }
        return step == 0 ? CordaitesButtressBlock.Part.BIG : CordaitesButtressBlock.Part.MID;
    }

    /** 這い上がりの箱は空いたマスにだけ(幹の脇の葉・蔦・ほかの木は壊さない)。 */
    private static void placeClimb(WorldGenLevel level, BlockPos pos, BlockState state) {
        BlockState here = level.getBlockState(pos);
        if (here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty()) {
            setForced(level, pos, state);
        }
    }

    /**
     * 板根・スティグマリアの次の列の地面(2026-11-02 浮きの直し)。手前の根の地面 {@code prevGround} と比べて、
     * 同じ高さなら {@code prevGround}、ちょうど 1 段下がるなら {@code prevGround - 1}(落ちる部品で降りる)、
     * 上がる・2 段以上下がる・水・地面でない物(倒木・菌・葉など)なら {@link Integer#MIN_VALUE}(そこで根を止める)。
     */
    static int nextRootGround(WorldGenLevel level, int x, int prevGround, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, prevGround + 1, z);
        if (!isRootFree(level.getBlockState(p))) {
            return Integer.MIN_VALUE; // 地面が上がる・水・ほかの物
        }
        p.setY(prevGround);
        if (isRootGround(level, p)) {
            return prevGround;
        }
        if (!isRootFree(level.getBlockState(p))) {
            return Integer.MIN_VALUE;
        }
        p.setY(prevGround - 1);
        return isRootGround(level, p) ? prevGround - 1 : Integer.MIN_VALUE;
    }

    /** 根を置いてよいマス(空気か置き換えられる植物。水・水没した物と、残す萌芽体は不可)。 */
    private static boolean isRootFree(BlockState state) {
        return state.getFluidState().isEmpty() && (state.isAir() || state.canBeReplaced()) && !TreePlacementRules.treeKeeps(state);
    }

    /**
     * 根の支えになる地面: 上面が平らに塞がっている(isFaceSturdy UP)・水が無い・葉や木の部品でない。
     * 倒木(全部の面が塞がっていても林床の上の物)・メドゥロサ・木生シダの幹・棚の菌・自分の根の上には置かない。
     */
    private static boolean isRootGround(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()
                && state.isFaceSturdy(level, pos, Direction.UP)
                && !state.is(BlockTags.LEAVES)
                && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock)
                && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock)
                && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.FallenLogBlock)
                && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.MedullosaBlock)
                && !(state.getBlock() instanceof CordaitesButtressBlock)
                && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.StigmariaBlock);
    }

    /**
     * 根に使うブロック状態を決める。幹が {@code overgrown_log}(側面が樹皮・木口に年輪)なら、
     * 6面すべてが樹皮の {@code overgrown_wood} に差し替える(根は木口が見えないほうが自然)。
     * 軸({@code axis})は幹の状態から引き継ぐ。幹がそれ以外のブロックなら幹の状態をそのまま使う。
     */
    private static BlockState rootStateFor(BlockState trunkState) {
        BlockState wood;
        if (trunkState.is(ModBlocks.OVERGROWN_LOG.get())) {
            wood = ModBlocks.OVERGROWN_WOOD.get().defaultBlockState();
        } else {
            return trunkState;
        }
        if (trunkState.hasProperty(RotatedPillarBlock.AXIS) && wood.hasProperty(RotatedPillarBlock.AXIS)) {
            wood = wood.setValue(RotatedPillarBlock.AXIS, trunkState.getValue(RotatedPillarBlock.AXIS));
        }
        return wood;
    }

    /**
     * {@code (x, z)} の列で、{@code searchCenterY}({@code ±}{@link #ROOT_GROUND_SEARCH_RANGE})の
     * 範囲のうち一番高い位置にある「空気でも置き換え可能でもないブロック」の Y 座標を返す
     * (=その列の地表の高さ)。範囲内に見つからなければ(崖・穴などで大きく地形が変わった場合)
     * 暴走を防ぐため {@code searchCenterY} をそのまま返す。
     */
    private static int findGroundY(WorldGenLevel level, int x, int searchCenterY, int z) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int top = searchCenterY + ROOT_GROUND_SEARCH_RANGE;
        int bottom = searchCenterY - ROOT_GROUND_SEARCH_RANGE;
        for (int y = top; y >= bottom; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            // 葉(このMODの葉・バニラの葉)は根の支えにしない: 隣の木の天蓋の上を根が這うのを防ぐ。
            if (!state.isAir() && !state.canBeReplaced() && !state.is(BlockTags.LEAVES)
                    && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock)
                    && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock)) {
                return y;
            }
        }
        return searchCenterY;
    }

    /**
     * 指定した中心の真下を先端として、薄く平たい葉の塊(アカシアの木のイメージ)を乗せる。
     *
     * <p>各層は「半径 {@code radius} の正方形」ではなく「半径 {@code radius}(+0.5)の円」で切り出し、
     * さらに円周付近のブロックをまばらに間引くことで、四角い塊にならないよう角を大きく落とす。
     * 一番上(層が3以上なら一番下も)の層は半径を1縮めて上下方向にも丸める。幹・枝そのものの
     * 位置には上書きしない。
     */
    private static void placeCanopy(WorldGenLevel level, RandomSource random, BlockPos canopyTop, int trunkWidth,
            Config config, LongOpenHashSet leafPositions) {
        int layers = Math.max(1, config.foliageHeight());
        int baseRadius = Math.max(1, config.foliageRadius());
        int reach = baseRadius + (trunkWidth - 1);
        int shyGap = OvergrownVegetationConfig.COMMON.crownShynessGap.get();
        int shyHeight = OvergrownVegetationConfig.COMMON.crownShynessHeight.get();

        for (int dy = 0; dy < layers; dy++) {
            int y = canopyTop.getY() - dy;
            // 一番上の層(層が3以上なら一番下の層も)は半径を1縮めて、上下方向にも丸みを出す。
            int radius = reach;
            if (layers > 1 && dy == 0) {
                radius = Math.max(1, reach - 1);
            } else if (layers > 2 && dy == layers - 1) {
                radius = Math.max(1, reach - 1);
            }

            double edgeSq = (radius + 0.5D) * (radius + 0.5D);
            double rimSq = (radius - 0.5D) * (radius - 0.5D);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distSq = (double) (dx * dx + dz * dz);
                    if (distSq > edgeSq) {
                        // 円の外(=四角の角)には葉を置かない。
                        continue;
                    }
                    if (distSq > rimSq && random.nextInt(5) == 0) {
                        // 円周付近のブロックを少しだけ間引いて輪郭を自然に崩す。
                        continue;
                    }

                    BlockPos leafPos = new BlockPos(canopyTop.getX() + dx, y, canopyTop.getZ() + dz);
                    BlockState here = level.getBlockState(leafPos);
                    if (here.is(config.trunkState().getBlock()) || here.is(ModBlocks.SIGILLARIA_LOG.get())
                            || here.is(ModBlocks.SIGILLARIA_CONE.get())
                            || here.getBlock() instanceof com.yayuy.overgrownvegetation.block.TreeFernTrunkBlock // 木生シダの幹(#logs でない)
                            || here.getBlock() instanceof BranchJointBlock) {
                        // 幹・枝そのものの位置と、隣の封印木の原木・球果、枝の継ぎ目には上書きしない。
                        continue;
                    }
                    if (shyGap > 0) {
                        // 樹冠の譲り合い: ほかの木の葉は上書きせず、そのそばにも葉を出さない(塊の芯は残す)。
                        if (isOtherTreesLeaf(here, leafPos, leafPositions)
                                || distSq > SHY_CORE_SQ && nearOtherCrown(level, leafPos, shyGap, shyHeight, leafPositions)) {
                            continue;
                        }
                    }
                    setForced(level, leafPos, config.leavesState());
                    leafPositions.add(leafPos.asLong());
                }
            }
        }
    }

    /**
     * <b>樹冠の譲り合い(クラウン・シャイネス)</b>(2026-10-17 ユーザー「一度試しでクラウン・シャイネスを実装してみたい」。設定 {@code crownShynessGap}):
     * 葉を置く所の水平 {@code gap} ブロック以内・上下 {@code height}(設定 {@code crownShynessHeight})ブロック以内に、ほかの木(今回の成長で置いていない)の葉があれば置かない。
     * 後から育つ木が前の木に譲るので、同じくらいの高さの樹冠の間に幅 {@code gap} の空の筋が残る。高さの違う樹冠は重なってよい
     * (上下 {@code height} より離れた葉は見ない)。塊の芯(中心から {@code √}{@value #SHY_CORE_SQ} 以内)は譲らない
     * —— 間隔 5 では樹冠の直径(7〜9)が木の間隔より大きく、三方を囲まれた木が葉の無い棒になるので。
     */
    private static boolean nearOtherCrown(WorldGenLevel level, BlockPos pos, int gap, int height, LongOpenHashSet ownLeaves) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int oy = -height; oy <= height; oy++) {
            for (int ox = -gap; ox <= gap; ox++) {
                for (int oz = -gap; oz <= gap; oz++) {
                    if (ox == 0 && oy == 0 && oz == 0) {
                        continue;
                    }
                    p.set(pos.getX() + ox, pos.getY() + oy, pos.getZ() + oz);
                    if (isOtherTreesLeaf(level.getBlockState(p), p, ownLeaves)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isOtherTreesLeaf(BlockState state, BlockPos pos, LongOpenHashSet ownLeaves) {
        // mod の葉は封印木の葉も(どれも OvergrownLeavesBlock)。
        return (state.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock || state.is(BlockTags.LEAVES))
                && !ownLeaves.contains(pos.asLong());
    }

    /** 譲り合いでも残す塊の芯(中心からの水平距離の 2 乗)。仮決め。 */
    private static final double SHY_CORE_SQ = 2.0;

    /** 幹の苔のスカートの段数(幹が overgrown_log のときだけ。{@code cover.json} の skirtHeight、skirtTallChance の木は 1 段高く。仮決め)。 */
    private static int skirtSteps(Config config, RandomSource random) {
        if (!config.trunkState().hasProperty(com.yayuy.overgrownvegetation.block.OvergrownLogBlock.SKIRT)
                || config.trunkState().getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) {
            return 0;
        }
        com.yayuy.overgrownvegetation.data.CoverTable t = com.yayuy.overgrownvegetation.data.DesignData.cover();
        if (t.skirtHeight() <= 0) {
            return 0;
        }
        return t.skirtHeight() + (random.nextDouble() < t.skirtTallChance() ? 1 : 0);
    }

    /**
     * 木の {@code hollowChance} に 1 本、幹の {@code hollowMinStep}〜{@code hollowMaxStep} 段目(根元が 1)の、空気に面した 1 面を洞にする(仮決め)。
     * 面は 4 向きをばらばらの順に試し、隣が空気の最初の面(太い幹の内側・ヒカリゴケの付いた面は選ばれない)。
     */
    private static void maybeHollow(WorldGenLevel level, RandomSource random, BlockPos origin, int trunkHeight, int trunkWidth, Config config) {
        com.yayuy.overgrownvegetation.data.CoverTable t = com.yayuy.overgrownvegetation.data.DesignData.cover();
        if (!config.trunkState().hasProperty(com.yayuy.overgrownvegetation.block.OvergrownLogBlock.HOLLOW) || random.nextDouble() >= t.hollowChance()) {
            return;
        }
        int lo = t.hollowMinStep() - 1;
        int hi = Math.min(t.hollowMaxStep(), trunkHeight) - 1;
        if (hi < lo) {
            return;
        }
        BlockPos pos = origin.offset(trunkWidth > 1 ? random.nextInt(trunkWidth) : 0, lo + random.nextInt(hi - lo + 1),
                trunkWidth > 1 ? random.nextInt(trunkWidth) : 0);
        BlockState state = level.getBlockState(pos);
        if (!state.is(config.trunkState().getBlock()) || state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) {
            return;
        }
        List<Direction> sides = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));
        net.minecraft.Util.shuffle(sides, random);
        for (Direction d : sides) {
            if (level.getBlockState(pos.relative(d)).isAir()) {
                level.setBlock(pos, state.setValue(com.yayuy.overgrownvegetation.block.OvergrownLogBlock.HOLLOW,
                        com.yayuy.overgrownvegetation.block.OvergrownLogBlock.Hollow.of(d)), Block.UPDATE_CLIENTS);
                return;
            }
        }
    }

    private static void setForced(WorldGenLevel level, BlockPos pos, BlockState state) {
        if (TreePlacementRules.treeKeeps(level.getBlockState(pos))) return; // 根付いた萌芽体は残す
        if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.keepsOut(level, pos)) return; // 構造物の守る範囲では空気と草花のマスだけ・部屋の中は飛ばす(11-19 段 2d。段 1 は箱を全部飛ばして樹冠が四角く欠けた)
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /**
     * 主幹の原木1本につき {@code glowLichenChance} の確率で、水平4方向のどれか1面に
     * ヒカリゴケ(バニラの {@code minecraft:glow_lichen} = {@link MultifaceBlock})を貼り付ける。
     * 太い幹(trunkWidth == 2)の内側を向く面は、その先が既に別の原木で埋まっている
     * (=空気ではない)ため自然に候補から外れる。既に何か置かれている面には貼らない
     * (装飾なので、地形や他の木を壊してまでは生やさない)。
     *
     * <p>{@code glowLichenState} に {@link MultifaceBlock} 以外(面ごとの真偽プロパティを
     * 持たないブロック)を指定した場合は何も貼らない。
     */
    private static void maybeAddGlowLichen(WorldGenLevel level, RandomSource random, BlockPos trunkPos, Config config) {
        double chance = config.glowLichenChance();
        BlockState baseState = config.glowLichenState();
        if (chance <= 0.0D || !(baseState.getBlock() instanceof MultifaceBlock) || random.nextDouble() >= chance) {
            return;
        }

        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockPos decalPos = trunkPos.relative(facing);
        if (!level.getBlockState(decalPos).isAir()) {
            return;
        }

        // ヒカリゴケは「幹に向いた面」= facing.getOpposite() 側に貼り付く。
        setForced(level, decalPos,
                baseState.setValue(MultifaceBlock.getFaceProperty(facing.getOpposite()), Boolean.TRUE));
    }

    /**
     * @param trunkState        幹・枝に使うブロック状態(例: {@code overgrown_log[axis=y]})
     * @param leavesState       葉に使うブロック状態
     * @param minTrunkHeight    幹の高さの最小値
     * @param maxTrunkHeight    幹の高さの最大値(この範囲でランダムに決まる)
     * @param foliageRadius     葉の塊の半径
     * @param foliageHeight     葉の塊の厚み(層の数)。アカシア風の薄い葉にしたいときは 1〜2 程度に
     * @param trunkWidth        幹の太さ(主幹のみに適用。1 = 通常の1本、2 = 2x2の巨木)。範囲外の値は 1〜2 に丸められる
     * @param branchCount       幹の上部から斜めに分岐させる枝の本数。0 なら分岐せず幹の先端に葉が1つ乗るだけ
     * @param branchMinLength   枝1本あたりの長さの最小値(ブロック数)
     * @param branchMaxLength   枝1本あたりの長さの最大値(この範囲でランダムに決まる)
     * @param branchZoneHeight  枝の分岐点をばらけさせる範囲。幹の最上部からこの高さぶん下までの
     *                          ランダムな位置から各枝が生える
     * @param rootMinCount      幹の根本から放射状に伸ばす根の本数の最小値。0 なら根は生えない
     * @param rootMaxCount      根の本数の最大値(この範囲でランダムに決まる。水平8方向から重複無く選ぶため
     *                          実際の上限は8本)
     * @param rootLength        根1本あたりの長さ(ブロック数)
     * @param glowLichenChance  主幹の原木1本につきヒカリゴケを1面貼り付ける確率。0 なら生えない
     * @param glowLichenState   ヒカリゴケに使うブロック状態(向きは自動で設定するので指定不要。
     *                          {@code FACING} プロパティを持たないブロックを指定した場合は何も生えない)
     */
    public record Config(
            BlockState trunkState,
            BlockState leavesState,
            int minTrunkHeight,
            int maxTrunkHeight,
            int foliageRadius,
            int foliageHeight,
            int trunkWidth,
            int branchCount,
            int branchMinLength,
            int branchMaxLength,
            int branchZoneHeight,
            int rootMinCount,
            int rootMaxCount,
            int rootLength,
            double glowLichenChance,
            BlockState glowLichenState
    ) implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockState.CODEC.fieldOf("trunk_state").forGetter(Config::trunkState),
                        BlockState.CODEC.fieldOf("leaves_state").forGetter(Config::leavesState),
                        Codec.INT.fieldOf("min_trunk_height").forGetter(Config::minTrunkHeight),
                        Codec.INT.fieldOf("max_trunk_height").forGetter(Config::maxTrunkHeight),
                        Codec.INT.optionalFieldOf("foliage_radius", 3).forGetter(Config::foliageRadius),
                        Codec.INT.optionalFieldOf("foliage_height", 4).forGetter(Config::foliageHeight),
                        Codec.INT.optionalFieldOf("trunk_width", 1).forGetter(Config::trunkWidth),
                        Codec.INT.optionalFieldOf("branch_count", 0).forGetter(Config::branchCount),
                        Codec.INT.optionalFieldOf("branch_min_length", 3).forGetter(Config::branchMinLength),
                        Codec.INT.optionalFieldOf("branch_max_length", 5).forGetter(Config::branchMaxLength),
                        Codec.INT.optionalFieldOf("branch_zone_height", 6).forGetter(Config::branchZoneHeight),
                        Codec.INT.optionalFieldOf("root_min_count", 0).forGetter(Config::rootMinCount),
                        Codec.INT.optionalFieldOf("root_max_count", 0).forGetter(Config::rootMaxCount),
                        Codec.INT.optionalFieldOf("root_length", 3).forGetter(Config::rootLength),
                        Codec.DOUBLE.optionalFieldOf("glow_lichen_chance", 0.0D).forGetter(Config::glowLichenChance),
                        BlockState.CODEC.optionalFieldOf("glow_lichen_state", Blocks.AIR.defaultBlockState())
                                .forGetter(Config::glowLichenState))
                .apply(instance, Config::new));
    }
}
