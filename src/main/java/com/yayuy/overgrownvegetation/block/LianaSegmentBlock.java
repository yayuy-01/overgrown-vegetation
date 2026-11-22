package com.yayuy.overgrownvegetation.block;

import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * 蔦の「ここを狙えば切れる」マス(2026-11-08、ユーザー依頼「木々に巻き付いたり、その間に生えている方の蔦をしっかりハサミや手で壊せるようにしたい」)。
 *
 * <p>蔦の見た目は主アンカー({@link CorruptedLianaBlock} + {@link CorruptedLianaBlockEntity})が曲線として描くだけで、曲線そのものには
 * 当たり判定が無い。登る蔓({@code worldgen.ClimbingVine})は前は「渡る区間」にしか副アンカーを置かず、幹を登る区間・林冠から垂れる蔓は
 * どこを叩いても幹か葉に当たっていた。そこで経路が通るすべての空気のマスにこのブロックを置き、狙いの箱を
 * 「そのマスの中で蔓が通る所」だけにする(1/3 ブロック刻み。{@link #X}・{@link #Y}・{@link #Z} の 6 通り × 3 軸 = 216 状態)。
 *
 * <p>前の副アンカーと違って BlockEntity を持たない(登る蔓 1 本で数十マスになり、木 1 本に百を超えるので、描画の一覧に載る
 * BlockEntity にはしない)。どの蔓のものかは、主アンカーの {@link CorruptedLianaBlockEntity#getSecondaryPositions} を
 * 周りのチャンクから探して決める({@link CorruptedLianaBlock#primaryOf})。壊す・切る・実を摘むは主アンカーと同じ入り口を通る。
 * 描画はしない({@link RenderShape#INVISIBLE})。当たらない(noCollission)。
 */
public class LianaSegmentBlock extends Block {


    /** 各軸の区間: 0 = [0, 1/3]・1 = [1/3, 2/3]・2 = [2/3, 1]・3 = [0, 2/3]・4 = [1/3, 1]・5 = [0, 1]。 */
    public static final IntegerProperty X = IntegerProperty.create("x", 0, 5);
    public static final IntegerProperty Y = IntegerProperty.create("y", 0, 5);
    public static final IntegerProperty Z = IntegerProperty.create("z", 0, 5);

    private static final int[] LO = {0, 1, 2, 0, 1, 0};
    private static final int[] HI = {1, 2, 3, 2, 3, 3};
    private static final VoxelShape[] SHAPES = new VoxelShape[216];

    static {
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 6; y++) {
                for (int z = 0; z < 6; z++) {
                    SHAPES[(x * 6 + y) * 6 + z] = Shapes.box(LO[x] / 3.0D, LO[y] / 3.0D, LO[z] / 3.0D,
                            HI[x] / 3.0D, HI[y] / 3.0D, HI[z] / 3.0D);
                }
            }
        }
    }

    /** 箱の端を 1/3 の格子へ丸めるときの遊び(端がわずかにはみ出しただけで次の 1/3 まで広げない)。 */
    private static final double SLACK = 0.15D;
    /** 経路の点の間を分けて調べる刻み(ブロック)。 */
    private static final double SAMPLE_STEP = 0.2D;

    public LianaSegmentBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(X, 5).setValue(Y, 5).setValue(Z, 5));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(X, Y, Z);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state.getValue(X), state.getValue(Y), state.getValue(Z));
    }

    /** 光を遮らない(狙いの箱がマス全体になる状態でも、見えないブロックが林床を暗くしないように)。 */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    static VoxelShape shape(int x, int y, int z) {
        return SHAPES[(x * 6 + y) * 6 + z];
    }

    // ---- 壊す・切る(主アンカーと同じ入り口)----

    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return CorruptedLianaBlock.destroyProgress(state, player);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        CorruptedLianaBlock.removeRestOfVine(level, pos);
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        CorruptedLianaBlock.attackCut(state, level, pos, player);
    }

    @Nullable
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = CorruptedLianaBlock.shearCut(stack, state, level, pos, player, hand);
        return r != null ? r : null;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        return CorruptedLianaBlock.harvest(level, pos, hitResult);
    }

    // ---- 置く ----

    /**
     * 経路 {@code pts}(世界の座標。点は x, y, z, …)が通る空気のマスに、このブロックを置く。半径 {@code radius} の太さぶん箱を広げる。
     * {@code skip} のマス(主アンカー)は飛ばす。空気でないマス(葉・幹・草)には置かない(草を消さない、葉の中は狙えなくてよい)。
     * 既にこのブロックがあるマス(別の蔓が先に通った)は箱を足し合わせるだけで、そのマスは先の蔓のものとして残す。
     *
     * @return 置いたマス(この蔓の副アンカーの一覧に入れる)
     */
    public static List<BlockPos> placeAlong(ServerLevel level, BlockPos skip, List<double[]> pts, double radius, int flags) {
        // マス → 箱(min x, y, z, max x, y, z。マスの中の 0〜1)
        Long2ObjectLinkedOpenHashMap<double[]> boxes = new Long2ObjectLinkedOpenHashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] b = i + 1 < pts.size() ? pts.get(i + 1) : a;
            double len = Math.sqrt(sq(b[0] - a[0]) + sq(b[1] - a[1]) + sq(b[2] - a[2]));
            int n = Math.max(1, (int) Math.ceil(len / SAMPLE_STEP));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n;
                addSample(boxes, Mth.lerp(t, a[0], b[0]), Mth.lerp(t, a[1], b[1]), Mth.lerp(t, a[2], b[2]), radius);
            }
            if (i + 1 == pts.size()) {
                addSample(boxes, a[0], a[1], a[2], radius);
            }
        }
        List<BlockPos> placed = new ArrayList<>();
        Block self = com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_LIANA_SEGMENT.get();
        for (var e : boxes.long2ObjectEntrySet()) {
            BlockPos c = BlockPos.of(e.getLongKey());
            if (c.equals(skip) || !com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, c)) {
                continue;
            }
            double[] box = e.getValue();
            int ix = interval(box[0], box[3]);
            int iy = interval(box[1], box[4]);
            int iz = interval(box[2], box[5]);
            BlockState there = level.getBlockState(c);
            if (there.is(self)) {
                // 先の蔓のマス: 箱を足し合わせる(そのマスを叩くと先の蔓が切れる)
                BlockState merged = there.setValue(X, union(there.getValue(X), ix)).setValue(Y, union(there.getValue(Y), iy))
                        .setValue(Z, union(there.getValue(Z), iz));
                if (merged != there) {
                    level.setBlock(c, merged, flags);
                }
                continue;
            }
            if (!there.isAir()) {
                continue;
            }
            level.setBlock(c, self.defaultBlockState().setValue(X, ix).setValue(Y, iy).setValue(Z, iz), flags);
            placed.add(c);
        }
        return placed;
    }

    /**
     * 主アンカーのマス {@code marker} の中を通る蔓の箱(経路 {@code path} は主アンカーからの相対座標を {@code stride} おき)。
     * 通らなければ null(呼び手は前の小さな箱のまま)。
     */
    @org.jetbrains.annotations.Nullable
    static VoxelShape markerShape(float[] path, int stride, double radius) {
        Long2ObjectLinkedOpenHashMap<double[]> boxes = new Long2ObjectLinkedOpenHashMap<>();
        int count = path.length / stride;
        for (int i = 0; i < count; i++) {
            int o = i * stride;
            int o2 = Math.min(i + 1, count - 1) * stride;
            double len = Math.sqrt(sq(path[o2] - path[o]) + sq(path[o2 + 1] - path[o + 1]) + sq(path[o2 + 2] - path[o + 2]));
            int n = Math.max(1, (int) Math.ceil(len / SAMPLE_STEP));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n;
                double x = Mth.lerp(t, path[o], path[o2]);
                double y = Mth.lerp(t, path[o + 1], path[o2 + 1]);
                double z = Mth.lerp(t, path[o + 2], path[o2 + 2]);
                if (x >= -radius && x < 1.0D + radius && y >= -radius && y < 1.0D + radius && z >= -radius && z < 1.0D + radius) {
                    addSample(boxes, x, y, z, radius);
                }
            }
        }
        double[] box = boxes.get(BlockPos.asLong(0, 0, 0));
        if (box == null) {
            return null;
        }
        return shape(interval(box[0], box[3]), interval(box[1], box[4]), interval(box[2], box[5]));
    }

    /** 点 (x, y, z) の半径 r の箱を、点を含むマスの箱へ足す(マスの外へはみ出した分は切る)。 */
    private static void addSample(Long2ObjectLinkedOpenHashMap<double[]> boxes, double x, double y, double z, double r) {
        int cx = Mth.floor(x);
        int cy = Mth.floor(y);
        int cz = Mth.floor(z);
        long key = BlockPos.asLong(cx, cy, cz);
        double lx = x - cx;
        double ly = y - cy;
        double lz = z - cz;
        double[] box = boxes.get(key);
        double x0 = Math.max(0.0D, lx - r), y0 = Math.max(0.0D, ly - r), z0 = Math.max(0.0D, lz - r);
        double x1 = Math.min(1.0D, lx + r), y1 = Math.min(1.0D, ly + r), z1 = Math.min(1.0D, lz + r);
        if (box == null) {
            boxes.put(key, new double[]{x0, y0, z0, x1, y1, z1});
        } else {
            box[0] = Math.min(box[0], x0);
            box[1] = Math.min(box[1], y0);
            box[2] = Math.min(box[2], z0);
            box[3] = Math.max(box[3], x1);
            box[4] = Math.max(box[4], y1);
            box[5] = Math.max(box[5], z1);
        }
    }

    /** マスの中の区間 [lo, hi] を 1/3 の格子の区間の番号へ(少しのはみ出しは切り捨てる)。 */
    static int interval(double lo, double hi) {
        int l = Mth.clamp(Mth.floor(lo * 3.0D + SLACK), 0, 2);
        int h = Mth.clamp(Mth.ceil(hi * 3.0D - SLACK), 1, 3);
        if (h <= l) {
            h = l + 1;
        }
        return code(l, h);
    }

    private static int code(int lo, int hi) {
        for (int i = 0; i < 6; i++) {
            if (LO[i] == lo && HI[i] == hi) {
                return i;
            }
        }
        return 5;
    }

    private static int union(int a, int b) {
        return code(Math.min(LO[a], LO[b]), Math.max(HI[a], HI[b]));
    }

    private static double sq(double v) {
        return v * v;
    }

    /** 1.20.1: 1.21 の useItemOn → useWithoutItem(主の手だけ)の順を use 1 つで呼ぶ。useItemOn の null = 1.21 の PASS_TO_DEFAULT_BLOCK_INTERACTION。 */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        InteractionResult r = useItemOn(player.getItemInHand(hand), state, level, pos, player, hand, hitResult);
        if (r != null) {
            return r;
        }
        return hand == InteractionHand.MAIN_HAND ? useWithoutItem(state, level, pos, player, hitResult) : InteractionResult.PASS;
    }
}
