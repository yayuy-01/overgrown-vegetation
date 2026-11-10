package com.yayuy.overgrownvegetation.corruption;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>ネザーの菌の木(真紅・歪んだ森)の倒木</b>(2026-11-10 ユーザー決定: オーバーワールドと同じく、元の森の木は残さない)。
 *
 * <p>幹({@code #logs} の真紅・歪んだ幹)が汚染されると、{@link SwampTreeFall} が木ごと集めて消し、6 割は元の幹のまま覆った倒木と
 * 切り株にする(残りは消える。割合は密林の {@code jungleFallenTreeChance} と同じ、仮)。葉の代わりに、ウォートブロック({@code #wart_blocks})と
 * シュルームライトを集めて全部消す(ドロップなし)。ここはその判定と後始末だけを持つ。
 * <ul>
 *     <li>ウォートとシュルームライトは、ネザーでは汚染で覆わない({@link #keepForFall})。覆いや過繁茂ブロックに替わると、倒木の葉集めから外れて宙に残るため</li>
 *     <li>消したウォートに付いていた垂れるツタ・ねじれるツタも消す({@link #clearVines}。仮)</li>
 * </ul>
 */
public final class HeatForestFungus {

    /** 菌の木の「葉」(ウォートブロック・シュルームライト)。 */
    public static boolean isCap(BlockState state) {
        return state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT);
    }

    /** ネザーで汚染が来ても、倒木の仕組みが消すまでそのままにするブロックか。 */
    public static boolean keepForFall(BlockState state) {
        return isCap(state);
    }

    /** 宙に残った傘の塊を 1 つ調べる上限(超えたら大きな塊として触らない)。 */
    private static final int ORPHAN_LIMIT = 4096;
    /** 倒した木の箱をこれだけ広げた中の傘を調べる(傘の下の縁や、離れた欠片まで)。 */
    private static final int SEED_MARGIN = 4;

    /**
     * 倒した木のまわりで、<b>立っている幹につながらなくなった傘</b>(ウォート・シュルームライト)を消す(2026-11-10、追記 12)。
     *
     * <p>倒木の葉集めは幹から傘づたいに {@code LEAF_REACH}(7)歩までしか集めない。自然の大きな菌の木の傘は、下の縁が幹から 7 歩より遠く、
     * 密な森では隣の木の傘とつながるので、先に倒れた木が隣の傘の途中を消すと、隣の木からも届かなくなって宙に残っていた
     * (heat7 の歪んだ森 47 個・真紅の森 181 個)。ここでは消したマスに触れる傘から、傘づたい(斜めも含む 26 方向)に塊を集め、
     * 塊のどこにも立っている幹({@link #isStandingStem})が触れていなければ、塊ごとドロップなしで消す。
     * 立っている幹につながる塊は残す(まだ倒れていない木 = 汚染の境目の外の木や、順番待ちの木の傘。その木が倒れたときにまた調べる)。
     */
    public static void clearOrphanCaps(ServerLevel level, LongOpenHashSet removed) {
        LongOpenHashSet visited = new LongOpenHashSet();
        LongOpenHashSet cleared = new LongOpenHashSet();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        java.util.ArrayDeque<Long> frontier = new java.util.ArrayDeque<>();
        LongOpenHashSet component = new LongOpenHashSet();
        BlockState air = Blocks.AIR.defaultBlockState();
        if (removed.isEmpty()) return;
        // 種: 倒した木の箱を SEED_MARGIN 広げた中の傘すべて(消したマスに触れない、離れた小さな欠片も拾う)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long l : removed) {
            minX = Math.min(minX, BlockPos.getX(l)); maxX = Math.max(maxX, BlockPos.getX(l));
            minY = Math.min(minY, BlockPos.getY(l)); maxY = Math.max(maxY, BlockPos.getY(l));
            minZ = Math.min(minZ, BlockPos.getZ(l)); maxZ = Math.max(maxZ, BlockPos.getZ(l));
        }
        for (int x = minX - SEED_MARGIN; x <= maxX + SEED_MARGIN; x++) {
            for (int z = minZ - SEED_MARGIN; z <= maxZ + SEED_MARGIN; z++) {
            for (int y = minY - SEED_MARGIN; y <= maxY + SEED_MARGIN; y++) {
                c.set(x, y, z);
                long seed = c.asLong();
                if (visited.contains(seed) || !level.isLoaded(c) || !isCap(level.getBlockState(c))) continue;
                // 塊を集める
                component.clear();
                frontier.clear();
                frontier.add(seed);
                visited.add(seed);
                component.add(seed);
                boolean anchored = false;
                while (!frontier.isEmpty()) {
                    long cur = frontier.poll();
                    int cx = BlockPos.getX(cur), cy = BlockPos.getY(cur), cz = BlockPos.getZ(cur);
                    for (int ex = -1; ex <= 1; ex++) for (int ey = -1; ey <= 1; ey++) for (int ez = -1; ez <= 1; ez++) {
                        if (ex == 0 && ey == 0 && ez == 0) continue;
                        c.set(cx + ex, cy + ey, cz + ez);
                        if (!level.isLoaded(c)) {
                            anchored = true; // 読み込まれていない先は分からないので、残す側に倒す
                            continue;
                        }
                        BlockState s = level.getBlockState(c);
                        if (isStandingStem(level, c, s)) {
                            anchored = true;
                            continue;
                        }
                        long n = c.asLong();
                        if (!visited.contains(n) && isCap(s)) {
                            visited.add(n);
                            component.add(n);
                            frontier.add(n);
                        }
                    }
                    if (component.size() > ORPHAN_LIMIT) {
                        anchored = true; // 大きすぎる塊は触らない(集めた分は visited に入っているので再び調べない)
                        break;
                    }
                }
                if (anchored) continue;
                for (long p : component) {
                    c.set(p);
                    level.setBlock(c, air, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                    cleared.add(p);
                }
            }
            }
        }
        if (!cleared.isEmpty()) {
            clearVines(level, cleared);
        }
    }

    /**
     * 立っている菌の幹か: 真紅・歪んだ幹(覆われていても同じブロック)で、縦向きで、真上か真下も幹。
     * 倒木(横向き)と切り株(1 段だけ)は数えない —— 地面近くまで垂れた傘が倒木に触れて残らないように。
     */
    static boolean isStandingStem(ServerLevel level, BlockPos pos, BlockState state) {
        if (!isStem(state)) return false;
        if (state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                && state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS) != net.minecraft.core.Direction.Axis.Y) {
            return false;
        }
        return isStem(level.getBlockState(pos.above())) || isStem(level.getBlockState(pos.below()));
    }

    /**
     * 傘の無い菌の幹の塊(隣の木に傘を全部集められた背の低い木)を、傘が無くても木として倒してよいか。
     * 塊が菌の幹(と、汚染で化けた幹のブロック)だけで、根元の下が菌の地面(真紅・歪んだナイリウムか、それが汚染された熱水の森の地面)で、
     * <b>そばで木が倒れたときに印が付いた</b>({@link #markCaplessStubs}・{@link HeatForestStubs})とき true。印は消す。
     * (2026-11-10 追記 13、ユーザー決定: 近くで木が倒れたときだけ。ナイリウムの上に菌の幹で立てた柱は、汚染が来ただけでは倒さない)
     */
    static boolean isCaplessStub(ServerLevel level, it.unimi.dsi.fastutil.longs.LongSet logs, BlockPos lowest) {
        for (long l : logs) {
            BlockState s = level.getBlockState(BlockPos.of(l));
            if (!isStemOrTrunk(s)) return false;
        }
        BlockState ground = level.getBlockState(lowest.below());
        boolean fungusGround = ground.is(Blocks.CRIMSON_NYLIUM) || ground.is(Blocks.WARPED_NYLIUM)
                || ground.is(com.yayuy.overgrownvegetation.registry.ModBlocks.HEAT_MAT.get())
                || ground.is(com.yayuy.overgrownvegetation.registry.ModBlocks.HEAT_MAT_STRANDS.get())
                || ground.is(com.yayuy.overgrownvegetation.registry.ModBlocks.HEAT_COOLED_CRUST.get());
        if (!fungusGround) return false;
        HeatForestStubs stubs = HeatForestStubs.get(level);
        boolean marked = false;
        for (long l : logs) marked |= stubs.has(BlockPos.of(l));
        if (marked) {
            for (long l : logs) stubs.take(BlockPos.of(l));
        }
        return marked;
    }

    /** 1 つの幹の塊として調べる大きさの上限(背の低い木の幹。仮)。 */
    private static final int STUB_LIMIT = 64;

    /**
     * 木が倒れたあと(倒木を並べる前)、倒した木の箱を {@link #SEED_MARGIN} マス広げた中で、<b>立っている菌の幹の塊のうち、傘に 1 つも触れていないもの</b>に
     * 印を付ける({@link HeatForestStubs})。隣の木に傘を全部集められた背の低い木。汚染がその幹に届いたとき、{@link #isCaplessStub} が倒す。
     */
    public static void markCaplessStubs(ServerLevel level, LongOpenHashSet removed) {
        if (removed.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long l : removed) {
            minX = Math.min(minX, BlockPos.getX(l)); maxX = Math.max(maxX, BlockPos.getX(l));
            minY = Math.min(minY, BlockPos.getY(l)); maxY = Math.max(maxY, BlockPos.getY(l));
            minZ = Math.min(minZ, BlockPos.getZ(l)); maxZ = Math.max(maxZ, BlockPos.getZ(l));
        }
        LongOpenHashSet visited = new LongOpenHashSet();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        HeatForestStubs stubs = null;
        for (int x = minX - SEED_MARGIN; x <= maxX + SEED_MARGIN; x++) {
            for (int z = minZ - SEED_MARGIN; z <= maxZ + SEED_MARGIN; z++) {
                for (int y = minY - SEED_MARGIN; y <= maxY + SEED_MARGIN; y++) {
                    c.set(x, y, z);
                    if (visited.contains(c.asLong()) || !level.isLoaded(c) || !isStemOrTrunk(level.getBlockState(c))) continue;
                    // 幹の塊を集める(斜めも含む)
                    LongOpenHashSet cluster = new LongOpenHashSet();
                    java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
                    BlockPos start = c.immutable();
                    q.add(start);
                    cluster.add(start.asLong());
                    visited.add(start.asLong());
                    boolean standing = false, capped = false, tooBig = false;
                    while (!q.isEmpty()) {
                        BlockPos p = q.poll();
                        standing |= isStandingStem(level, p, level.getBlockState(p));
                        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                            BlockPos n = p.offset(dx, dy, dz);
                            if (!level.isLoaded(n)) { capped = true; continue; } // 分からないので印を付けない
                            BlockState s = level.getBlockState(n);
                            if (isCap(s)) capped = true;
                            else if (isStemOrTrunk(s) && cluster.add(n.asLong())) {
                                visited.add(n.asLong());
                                q.add(n);
                            }
                        }
                        if (cluster.size() > STUB_LIMIT) { tooBig = true; break; }
                    }
                    if (!standing || capped || tooBig) continue;
                    if (stubs == null) stubs = HeatForestStubs.get(level);
                    for (long l : cluster) stubs.mark(BlockPos.of(l));
                }
            }
        }
    }

    private static boolean isStemOrTrunk(BlockState s) {
        return isStem(s) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_TRUNK_BLOCK.get());
    }

    private static boolean isStem(BlockState state) {
        return state.is(BlockTags.CRIMSON_STEMS) || state.is(BlockTags.WARPED_STEMS);
    }

    /** 消したマスの下へ垂れるツタ・上へ伸びるツタを、ドロップなしで消す。 */
    public static void clearVines(ServerLevel level, LongOpenHashSet removed) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (long l : removed) {
            BlockPos p = BlockPos.of(l);
            c.set(p.getX(), p.getY() - 1, p.getZ());
            for (int i = 0; i < 32 && level.isLoaded(c); i++, c.move(0, -1, 0)) {
                BlockState s = level.getBlockState(c);
                if (!s.is(Blocks.WEEPING_VINES) && !s.is(Blocks.WEEPING_VINES_PLANT)) break;
                level.setBlock(c, air, Block.UPDATE_CLIENTS);
            }
            c.set(p.getX(), p.getY() + 1, p.getZ());
            for (int i = 0; i < 32 && level.isLoaded(c); i++, c.move(0, 1, 0)) {
                BlockState s = level.getBlockState(c);
                if (!s.is(Blocks.TWISTING_VINES) && !s.is(Blocks.TWISTING_VINES_PLANT)) break;
                level.setBlock(c, air, Block.UPDATE_CLIENTS);
            }
        }
    }

    private HeatForestFungus() {
    }
}
