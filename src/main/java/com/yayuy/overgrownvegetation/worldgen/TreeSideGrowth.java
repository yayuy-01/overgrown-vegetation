package com.yayuy.overgrownvegetation.worldgen;

import com.yayuy.overgrownvegetation.block.AerialRootBlock;
import com.yayuy.overgrownvegetation.block.BeardLichenBlock;
import com.yayuy.overgrownvegetation.block.SporeSacBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 側面・底面に生えるもの(2026-11-01)を、木が生え終わった直後に置く({@link TreeVineConnector} と同じ時機)。
 * 呼ぶのはコルダイテス({@link ForcedTreeFeature})・封印木({@link SigillariaTreeFeature})・巨大樹({@link GiantTreeGrowthQueue} の最後)。
 * <ul>
 *     <li>S1 幹に巻き付く種子シダは 2026-11-03 にユーザー指示で消した(登る蔓 {@link ClimbingVine} に置き換え)。</li>
 *     <li>S3 気根のカーテン: 林冠・腕の縁(下が空気で、横にも空気がある葉・枝)から 1 本の木に 1〜3 本、長さ 2〜6。
 *     地面まで届いて根付いた柱になるのは 1 本の木に 0〜1 本。</li>
 *     <li>S4 ひげ地衣: 腕・枝の下面の 2〜4 か所(1 段 7 割・2 段 3 割)。根元のまわりに水があれば倍。</li>
 *     <li>S6 胞子嚢の袋: 腕・枝の下面に 0〜2 個(木の 4 割に 1 個以上)。</li>
 * </ul>
 * 巨大樹は数を {@link #GIANT_SCALE} 倍にし、幹の巻き付きは露出した面の一部の列だけ。数はすべて下絵の配置の図の目安(仮決め)。
 */
public final class TreeSideGrowth {

    /** 巨大樹の数の倍率(仮決め)。 */
    private static final int GIANT_SCALE = 4;
    /** 気根が地面まで届いて柱になる確率(1 本の木に 0〜1 本。仮決め)。 */
    private static final double PILLAR_CHANCE = 0.4;
    /** 柱にできる高さの上限(林冠の下から地面まで。これより高いと届かない)。 */
    private static final int PILLAR_MAX_DROP = 14;

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * 木 1 本を飾る。{@code logs} は今置いた原木(幹と枝)、{@code leaves} は今置いた葉(null なら原木のまわりを探す)。
     */
    public static void decorate(LevelAccessor level, RandomSource random, LongCollection logs, @Nullable LongCollection leaves, boolean giant) {
        if (logs.isEmpty()) {
            return;
        }
        // 幹の列 = 根元から 4 段以上原木が続く列。根元 = いちばん下の原木の高さ。
        Long2IntOpenHashMap columnCount = new Long2IntOpenHashMap();
        int base = Integer.MAX_VALUE;
        for (LongIterator it = logs.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            base = Math.min(base, BlockPos.getY(p));
            columnCount.addTo(column(BlockPos.getX(p), BlockPos.getZ(p)), 1);
        }
        List<long[]> trunk = new ArrayList<>();
        for (var e : columnCount.long2IntEntrySet()) {
            int x = (int) (e.getLongKey() >> 32);
            int z = (int) e.getLongKey();
            if (logs.contains(BlockPos.asLong(x, base, z)) && logs.contains(BlockPos.asLong(x, base + 3, z))) {
                trunk.add(new long[]{x, z});
            }
        }
        LongOpenHashSet trunkColumns = new LongOpenHashSet();
        for (long[] c : trunk) {
            trunkColumns.add(column((int) c[0], (int) c[1]));
        }

        // 下面の候補: 腕・枝(幹の列でない原木)と葉で、真下が空気のもの。縁 = 横のどこかも空気。
        List<BlockPos> armUnder = new ArrayList<>();
        List<BlockPos> edgeUnder = new ArrayList<>();
        for (LongIterator it = logs.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            if (trunkColumns.contains(column(BlockPos.getX(p), BlockPos.getZ(p)))) {
                continue;
            }
            addUnderside(level, BlockPos.of(p), armUnder, edgeUnder);
        }
        List<BlockPos> leafUnder = new ArrayList<>();
        if (leaves != null) {
            for (LongIterator it = leaves.iterator(); it.hasNext(); ) {
                addUnderside(level, BlockPos.of(it.nextLong()), leafUnder, edgeUnder);
            }
        } else {
            scanLeaves(level, logs, leafUnder, edgeUnder);
        }
        int scale = giant ? GIANT_SCALE : 1;

        // S3 気根: 縁から 1〜3 本。うち 0〜1 本が地面まで届いて柱になる。
        Collections.shuffle(edgeUnder, new java.util.Random(random.nextLong()));
        int roots = (1 + random.nextInt(3)) * scale;
        int pillars = random.nextDouble() < PILLAR_CHANCE ? scale : 0;
        for (BlockPos top : edgeUnder) {
            if (roots <= 0) {
                break;
            }
            AerialRootBlock.Kind kind = random.nextInt(3) == 0 ? AerialRootBlock.Kind.SINGLE : AerialRootBlock.Kind.BUNDLE;
            boolean toGround = pillars > 0;
            int n = hangRoot(level, top.below(), 2 + random.nextInt(5), toGround, kind);
            if (n > 0) {
                roots--;
                if (level.getBlockState(top.below(n)).is(ModBlocks.AERIAL_ROOT_PILLAR.get())) {
                    pillars--;
                }
            }
        }

        // S4・S6: 腕・枝の下面が先、足りなければ葉の下面。
        List<BlockPos> under = new ArrayList<>(armUnder);
        Collections.shuffle(under, new java.util.Random(random.nextLong()));
        List<BlockPos> leafShuffled = new ArrayList<>(leafUnder);
        Collections.shuffle(leafShuffled, new java.util.Random(random.nextLong()));
        under.addAll(leafShuffled);
        int lichens = (2 + random.nextInt(3)) * scale * (isWet(level, trunk, base) ? 2 : 1);
        double sacRoll = random.nextDouble();
        int sacs = (sacRoll < 0.6 ? 0 : sacRoll < 0.85 ? 1 : 2) * scale;
        for (BlockPos top : under) {
            BlockPos at = top.below();
            if (!level.getBlockState(at).isAir()) {
                continue;
            }
            if (sacs > 0) {
                if (placeSac(level, at, random.nextBoolean() ? SporeSacBlock.Stage.SMALL : SporeSacBlock.Stage.LARGE)) {
                    sacs--;
                }
            } else if (lichens > 0) {
                BeardLichenBlock.Kind kind = random.nextBoolean() ? BeardLichenBlock.Kind.FINE : BeardLichenBlock.Kind.TUFT;
                if (placeLichen(level, at, kind, random.nextDouble() < 0.3)) {
                    lichens--;
                }
            } else {
                break;
            }
        }
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static void addUnderside(LevelAccessor level, BlockPos host, List<BlockPos> under, List<BlockPos> edge) {
        BlockPos below = host.below();
        if (!level.getBlockState(below).isAir()) {
            return;
        }
        under.add(host);
        for (Direction d : SIDES) {
            if (level.getBlockState(host.relative(d)).isAir()) {
                edge.add(host);
                return;
            }
        }
    }

    /** 封印木など葉の座標を持たない木: 原木を包む箱(横 4・上 4 広げる)の葉を探す。 */
    private static void scanLeaves(LevelAccessor level, LongCollection logs, List<BlockPos> under, List<BlockPos> edge) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (LongIterator it = logs.iterator(); it.hasNext(); ) {
            long p = it.nextLong();
            minX = Math.min(minX, BlockPos.getX(p));
            minY = Math.min(minY, BlockPos.getY(p));
            minZ = Math.min(minZ, BlockPos.getZ(p));
            maxX = Math.max(maxX, BlockPos.getX(p));
            maxY = Math.max(maxY, BlockPos.getY(p));
            maxZ = Math.max(maxZ, BlockPos.getZ(p));
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = minX - 4; x <= maxX + 4; x++) {
            for (int z = minZ - 4; z <= maxZ + 4; z++) {
                for (int y = minY + 2; y <= maxY + 4; y++) {
                    if (isLeafLike(level.getBlockState(m.set(x, y, z)))) {
                        addUnderside(level, m.immutable(), under, edge);
                    }
                }
            }
        }
    }

    /** 葉(この MOD の葉は #minecraft:leaves に入っていない。封印木の芯の葉と板の葉も)。 */
    private static boolean isLeafLike(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(ModBlocks.OVERGROWN_LEAVES.get()) || s.is(ModBlocks.SIGILLARIA_LEAVES.get())
                || s.is(ModBlocks.SIGILLARIA_LEAVES_FRINGE.get());
    }

    /** 根元のまわり(横 6・上下 2)に水があるか(湿った谷・水辺。ひげ地衣が倍)。 */
    private static boolean isWet(LevelAccessor level, List<long[]> trunk, int base) {
        if (trunk.isEmpty()) {
            return false;
        }
        int cx = (int) trunk.get(0)[0];
        int cz = (int) trunk.get(0)[1];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                for (int dy = -2; dy <= 1; dy++) {
                    if (level.getFluidState(m.set(cx + dx, base + dy, cz + dz)).is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code start}(林冠・腕の下の空気)から気根を {@code length} 段垂らす。{@code toGround} なら、地面まで {@link #PILLAR_MAX_DROP} 段以内のとき
     * 地面まで伸ばし、地面の上のマスを根付いた柱にする。置いた段の数を返す(置けなければ 0)。
     */
    public static int hangRoot(LevelAccessor level, BlockPos start, int length, boolean toGround, AerialRootBlock.Kind kind) {
        BlockPos up = start.above();
        if (!level.getBlockState(start).isAir() || !level.getBlockState(up).isFaceSturdy(level, up, Direction.DOWN)) {
            return 0;
        }
        // 空気の続く段数(地面の手前まで)
        int drop = 0;
        BlockPos.MutableBlockPos m = start.mutable();
        while (drop <= PILLAR_MAX_DROP && level.getBlockState(m).isAir()) {
            drop++;
            m.move(Direction.DOWN);
        }
        boolean reaches = toGround && drop <= PILLAR_MAX_DROP && level.getBlockState(m).isFaceSturdy(level, m, Direction.UP);
        int n = reaches ? drop : Math.min(length, drop - 1);
        if (n <= 0) {
            return 0;
        }
        BlockState root = ModBlocks.AERIAL_ROOT.get().defaultBlockState().setValue(AerialRootBlock.KIND, kind);
        for (int i = 0; i < n; i++) {
            BlockPos at = start.below(i);
            if (reaches && i == n - 1) {
                level.setBlock(at, ModBlocks.AERIAL_ROOT_PILLAR.get().defaultBlockState().setValue(AerialRootBlock.KIND, kind), Block.UPDATE_CLIENTS);
            } else {
                level.setBlock(at, root.setValue(AerialRootBlock.TIP, !reaches && i == n - 1), Block.UPDATE_CLIENTS);
            }
        }
        return n;
    }

    /** ひげ地衣を 1 段(または 2 段)吊る。 */
    public static boolean placeLichen(LevelAccessor level, BlockPos at, BeardLichenBlock.Kind kind, boolean twoRows) {
        BlockState top = ModBlocks.BEARD_LICHEN.get().defaultBlockState().setValue(BeardLichenBlock.KIND, kind);
        if (!top.canSurvive(level, at)) {
            return false;
        }
        boolean two = twoRows && level.getBlockState(at.below()).isAir();
        level.setBlock(at, top.setValue(BeardLichenBlock.PART, two ? BeardLichenBlock.Part.TOP : BeardLichenBlock.Part.SHORT), Block.UPDATE_CLIENTS);
        if (two) {
            level.setBlock(at.below(), top.setValue(BeardLichenBlock.PART, BeardLichenBlock.Part.TIP), Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** 胞子嚢の袋を吊る。 */
    public static boolean placeSac(LevelAccessor level, BlockPos at, SporeSacBlock.Stage stage) {
        BlockState sac = ModBlocks.HANGING_SPORE_SAC.get().defaultBlockState().setValue(SporeSacBlock.STAGE, stage);
        if (!sac.canSurvive(level, at)) {
            return false;
        }
        level.setBlock(at, sac, Block.UPDATE_CLIENTS);
        return true;
    }

    /** 巻き付く幹(コルダイテス・封印木・巨大樹の原木)。 */
    public static boolean isTrunk(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_LOG.get()) || state.is(ModBlocks.SIGILLARIA_LOG.get()) || state.is(ModBlocks.GIANT_OVERGROWN_LOG.get());
    }

    private TreeSideGrowth() {
    }
}
