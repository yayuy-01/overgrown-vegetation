package com.yayuy.overgrownvegetation.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
        for (int i = 0; i < trunkHeight; i++) {
            int y = origin.getY() + i;
            for (int dx = 0; dx < trunkWidth; dx++) {
                for (int dz = 0; dz < trunkWidth; dz++) {
                    BlockPos trunkPos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
                    setForced(level, trunkPos, config.trunkState());
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
        }

        // 育ち終えた直後に、近くの別の木との間へ蔦を張れないか試す
        // (spawnConnectingVines / vineMaxPerTree を 0 にすれば無効化できる)。
        TreeVineConnector.tryConnect(level.getLevel(), random, origin, logPositions);

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

            for (int step = 0; step < length; step++) {
                x += dir[0];
                y += 1;
                z += dir[1];
                BlockPos branchPos = new BlockPos(x, y, z);
                setForced(level, branchPos, config.trunkState());
                logPositions.add(branchPos.asLong());
            }

            placeCanopy(level, random, new BlockPos(x, y + 1, z), 1, config, leafPositions);
        }
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
     * 根に使うブロック状態を決める。幹が {@code overgrown_log}(側面が樹皮・木口に年輪)なら、
     * 6面すべてが樹皮の {@code overgrown_wood} に差し替える(根は木口が見えないほうが自然)。
     * 軸({@code axis})は幹の状態から引き継ぐ。幹がそれ以外のブロックなら幹の状態をそのまま使う。
     */
    private static BlockState rootStateFor(BlockState trunkState) {
        BlockState wood;
        if (trunkState.is(ModBlocks.OVERGROWN_LOG.get())) {
            wood = ModBlocks.OVERGROWN_WOOD.get().defaultBlockState();
        } else if (trunkState.is(ModBlocks.OVERGROWN_SWAMP_LOG.get())) {
            // 湿地の木も同様に、根は樹皮だけの湿地の木ブロック。
            wood = ModBlocks.OVERGROWN_SWAMP_WOOD.get().defaultBlockState();
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
                    && !state.is(ModBlocks.OVERGROWN_LEAVES.get()) && !state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get())) {
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
                    if (level.getBlockState(leafPos).is(config.trunkState().getBlock())) {
                        // 幹・枝そのものの位置には上書きしない。
                        continue;
                    }
                    setForced(level, leafPos, config.leavesState());
                    leafPositions.add(leafPos.asLong());
                }
            }
        }
    }

    private static void setForced(WorldGenLevel level, BlockPos pos, BlockState state) {
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
