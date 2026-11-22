package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;

/**
 * <b>ネザーの地面の作り変え</b>(2026-11-11 骨組み)。空気に触れた面と、その奥 {@code depthMin}〜{@code depthMax} 段だけを変える
 * (10-02 の「過繁茂のチャンクは地中まで一括置き換え」はネザーではやめた)。
 *
 * <p><b>元 → 行き先</b>(2026-11-12 ユーザー決定で作り直し。搾られた岩 3 種はやめ、オーバーワールドと同じ「覆われたブロック」にした):
 * <ul>
 *     <li>ネザーラック → 覆われたネザーラック({@code overgrown_netherrack})、床の一部が泥炭</li>
 *     <li>真紅・歪んだナイリウム → 覆われたナイリウム({@code overgrown_nylium}。1 種類)。段階 1 で覆われない所は枯れて素のネザーラック</li>
 *     <li>ソウルソイル → 覆われたソウルソイル({@code overgrown_soul_soil})、床の一部が泥炭</li>
 *     <li>ソウルサンド → 泥炭(水の底も。泡の柱を出さない)</li>
 *     <li>表に無い立方体(玄武岩・ブラックストーン・鉱石など) → 段階 1〜2 は覆い(上掛け)のまだら、段階 3 から 7 割が覆い・3 割が
 *     ネザーの汎用の過繁茂ブロック({@code nether_overgrown_block})</li>
 *     <li>マグマブロック(溶岩の岸) → 冷えた殻(段階 2。{@link NetherLava})</li>
 * </ul>
 * 段階 1〜2 は前線の中の {@code coverStage1}(7 割)のまだらだけ、段階 3 からは表のブロックの面を全部({@code coverStage3} = 1.0)覆われたブロックにする。
 * 面の奥(地中の手前の数段)は変えない(11-12 に搾られた岩の奥の段をやめた。{@code depthMin/Max} は使っていない)。
 * 段階 1 で枯れる小さな植物(仮決め: 空気になる): ネザーの根(真紅・歪んだ)・ネザースプラウト・ネザーウォート・小さな菌(真紅・歪んだ)。
 * 蔦と大きな菌の木は表に無いので残す。
 *
 * <p>置くときは {@link Block#UPDATE_CLIENTS} と {@link Block#UPDATE_KNOWN_SHAPE}(近隣の更新も形の更新も出さない)。植物が飛んだり、溶岩が水を見て黒曜石になったりしない。
 */
public final class NetherGround {

    private NetherGround() {
    }

    /** 覆われたブロックの行き先(表。2026-11-12 ユーザー決定)。表に無ければ null。 */
    @Nullable
    static BlockState coveredOf(BlockState state) {
        if (state.is(Blocks.NETHERRACK)) return ModBlocks.OVERGROWN_NETHERRACK.get().defaultBlockState();
        if (isNylium(state)) return ModBlocks.OVERGROWN_NYLIUM.get().defaultBlockState();
        if (state.is(Blocks.SOUL_SOIL)) return ModBlocks.OVERGROWN_SOUL_SOIL.get().defaultBlockState();
        if (state.is(Blocks.SOUL_SAND)) return ModBlocks.NETHER_PEAT.get().defaultBlockState();
        return null;
    }

    /** 覆われたブロック(表の行き先と汎用の過繁茂ブロック。泥炭は含まない)か。 */
    public static boolean isCoveredGround(BlockState s) {
        return s.is(ModBlocks.OVERGROWN_NETHERRACK.get()) || s.is(ModBlocks.OVERGROWN_NYLIUM.get()) || s.is(ModBlocks.OVERGROWN_SOUL_SOIL.get())
                || s.is(ModBlocks.NETHER_OVERGROWN_BLOCK.get());
    }

    /** 床の一部が泥炭になるブロック(段階 3・5)。表の元と、その覆われた版。 */
    private static boolean peatSource(BlockState s) {
        return s.is(Blocks.NETHERRACK) || isNylium(s) || s.is(Blocks.SOUL_SOIL)
                || s.is(ModBlocks.OVERGROWN_NETHERRACK.get()) || s.is(ModBlocks.OVERGROWN_NYLIUM.get()) || s.is(ModBlocks.OVERGROWN_SOUL_SOIL.get());
    }

    /**
     * 表に無い立方体(段階 3 から 3 割が汎用の過繁茂ブロック、7 割が覆い): 6 面の実の立方体で、中身を持たず、覆える(汚染済みでない)もの。
     * オーバーワールドの対応表に無い立方体と同じ決め方(CorruptionRegistry#corruptUnmapped)。
     */
    static boolean isUnmappedCube(ServerLevel level, BlockPos pos, BlockState s) {
        return coveredOf(s) == null && !s.is(ModBlocks.NETHER_PEAT.get()) && !s.hasBlockEntity()
                && s.isCollisionShapeFullBlock(level, pos) && OvergrownLayerData.canCover(s);
    }

    /** 汎用の過繁茂ブロックになる 3 割の抽選の種。 */
    private static final int OVERGROWN_BLOCK_SALT = 29;

    /**
     * Mob などのふだんの汚染が当たったマスの行き先(2026-11-11 ユーザー決定「両方」)。表の行き先(覆われたブロック・ソウルサンドは泥炭)・
     * マグマ → 冷えた殻・小さな植物 → 空気。表に無ければ null(呼ぶ側が覆う)。
     */
    @Nullable
    static BlockState ordinaryTarget(BlockState state) {
        BlockState w = coveredOf(state);
        if (w != null) return w;
        if (state.is(Blocks.MAGMA_BLOCK)) return ModBlocks.COOLED_CRUST.get().defaultBlockState();
        if (isSmallPlant(state)) return Blocks.AIR.defaultBlockState();
        return null;
    }

    /** 段階 1 で枯れる小さな植物か。 */
    static boolean isSmallPlant(BlockState state) {
        return state.is(Blocks.CRIMSON_ROOTS) || state.is(Blocks.WARPED_ROOTS) || state.is(Blocks.NETHER_SPROUTS)
                || state.is(Blocks.NETHER_WART) || state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS);
    }

    static boolean isNylium(BlockState state) {
        return state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM);
    }

    /** 床の上として空いているか({@link NetherSurface#isOpen} と同じ。液体は空いていない)。 */
    static boolean isOpen(BlockState state) {
        return NetherSurface.isOpen(state);
    }

    static void put(ServerLevel level, BlockPos pos, BlockState state) {
        // UPDATE_KNOWN_SHAPE: 隣の形の更新も出さない(出すと、土台が変わった根・菌がアイテムを落として壊れる。11-11 に 866 個落ちた)
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** 座標の決まった乱数(0〜1)。同じ座標なら何度でも同じ値(段階 3 と段階 5 の泥炭が揃う)。 */
    static double hash01(long seed, int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0x27D4EB2F165667C5L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------------------------------
    // 見回り。どれも「持ち主がこのチャンクの列」({@link NetherJitter#ownedColumns}、x, z を交互に詰めた配列)だけを変える
    // (2026-11-11 境目のまだら。持ち主の揺らぎ 0 ならチャンクの 16×16 と同じ)
    // ------------------------------------------------------------------------------------------

    @Nullable
    static LevelChunk chunkAt(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4);
    }

    // ------------------------------------------------------------------------------------------
    // 覆い(重ねる苔の絵)の広がり方(2026-11-12 ユーザー決定: 時間をかけて広げる・搾られた岩も覆う・段階 3 から 8〜9 割)
    // ------------------------------------------------------------------------------------------

    /**
     * 覆いの斑の種。どの割合でも同じ種なので、割合が大きいほど覆いは「前の覆い + 足した所」になる(泥炭 3 割 ⊂ 段階 1 の 7 割 ⊂ 段階 3 の 8.5 割)。
     */
    static final int COVER_SALT = 53;

    /**
     * 列 (x, z) に、起点 {@code origin}(BlockPos の long、y は見ない)から広がる前線が来ているか。{@code progress} は 0〜1
     * (段階 1 の経過 ÷ 段階 1 の長さ)。起点からの距離 ÷ {@code reach} になめらかな揺らぎ(± 0.25)を足した値が {@code progress × 1.3} 以下なら来ている
     * (1.3 = 揺らぎの分。progress 1 でチャンクの全部に届く)。
     */
    static boolean inFront(long seed, int x, int z, long origin, double progress, int reach) {
        if (progress >= 1) return true;
        double dx = x - BlockPos.getX(origin), dz = z - BlockPos.getZ(origin);
        double d = Math.sqrt(dx * dx + dz * dz) / reach + 0.25 * NetherJitter.noise(seed, x, z, 8, 61);
        return d <= progress * 1.3;
    }

    /**
     * 列 (x, z) が起点から {@code coverOriginRadius}(仮 2)ブロックの中か。そこは斑の「覆わない側」でも必ず覆う
     * (2026-11-12 ユーザー決定: 萌芽体の足もとが裸だと、そこから広がったように見えない)。
     */
    static boolean nearOrigin(int x, int z, long origin) {
        double r = com.yayuy.overgrownvegetation.data.DesignData.netherSpread().coverOriginRadius;
        double dx = x - BlockPos.getX(origin), dz = z - BlockPos.getZ(origin);
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * 空気に触れた面か。流れている(源でない)液体に触れた面も含める(2026-11-12: 溶岩の滝の流れの下にあった床が、見回りの後で流れが引いて
     * 素のネザーラックの四角として現れていた)。
     */
    private static boolean exposed(ServerLevel level, BlockPos.MutableBlockPos at, BlockPos.MutableBlockPos n, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(n.setWithOffset(at.set(x, y, z), d));
            if (isOpen(s) || !s.getFluidState().isEmpty() && !s.getFluidState().isSource()) return true;
        }
        return false;
    }

    /**
     * 段階 3 からの泥炭の覆い(上掛け): {@code coverPeat}(仮 0.3。湿地の土の絵を見せる)の割合の斑に揃える(足すか剥がす)。
     * 2026-11-12 から上掛けを使うのは泥炭と表に無いブロックだけ(表のブロックは覆われたブロックに置き換える)。
     */
    static void applyLateCover(ServerLevel level, BlockPos pos, BlockState state, NetherSpreadTable t) {
        if (!state.is(ModBlocks.NETHER_PEAT.get())) return;
        if (NetherJitter.patch(level.getSeed(), pos.getX(), pos.getY(), pos.getZ(), t.coverPeat, 5, COVER_SALT) || nearOrigin(pos.getX(), pos.getZ(),
                NetherLedgerData.get(level).coverOriginOf(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)))) {
            OvergrownLayerData.cover(level, pos);
        } else {
            OvergrownLayerData.uncover(level, pos);
        }
    }

    /**
     * ふだんの汚染(Mob など)が、もう段階 3 を終えたチャンクのマスを泥炭に変えたとき、泥炭の割合の斑で覆う(2026-11-12)。
     * {@code mapped} は置き換え先。覆われたブロックはそれ自体が覆われた絵なので何もしない。
     */
    static void coverAfterOrdinary(ServerLevel level, BlockPos pos, BlockState mapped) {
        if (!mapped.is(ModBlocks.NETHER_PEAT.get())) return;
        long key = net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (NetherLedgerData.get(level).appliedStage(key) < 3) return;
        applyLateCover(level, pos, mapped, com.yayuy.overgrownvegetation.data.DesignData.netherSpread());
    }

    /** 覆い(段階 1 のまだら)の当たり: {@code fraction} の斑か、起点のまわり。 */
    private static boolean coverHit(long seed, int x, int y, int z, double fraction, long origin) {
        return NetherJitter.patch(seed, x, y, z, fraction, 5, COVER_SALT) || nearOrigin(x, z, origin);
    }

    /**
     * 段階 1: 列の、高さ {@code sectionY}(セクションの番号)の 16 段の小さな植物を枯らし(空気)、ナイリウムを、覆いの斑の当たりなら
     * 覆われたナイリウム、外れなら素のネザーラック(バニラで上が塞がれたときと同じ)へ。置き換えた数を返す。
     */
    static int pioneerSection(ServerLevel level, int[] cols, int sectionY, long origin, double progress, int reach, double fraction) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int changed = 0;
        int baseY = sectionY << 4;
        long seed = level.getSeed();
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            if (!inFront(seed, x, z, origin, progress, reach)) continue; // 起点からの前線がまだ来ていない列
            LevelChunk chunk = chunkAt(level, x, z);
            if (chunk == null) continue;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir()) continue;
            for (int ly = 0; ly < 16; ly++) {
                BlockState s = section.getBlockState(x & 15, ly, z & 15);
                if (isSmallPlant(s)) {
                    put(level, at.set(x, baseY + ly, z), Blocks.AIR.defaultBlockState());
                    changed++;
                } else if (isNylium(s)) {
                    int y = baseY + ly;
                    put(level, at.set(x, y, z), coverHit(seed, x, y, z, fraction, origin)
                            ? ModBlocks.OVERGROWN_NYLIUM.get().defaultBlockState() : Blocks.NETHERRACK.defaultBlockState());
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * 段階 1: 列の高さ {@code sectionY} の 16 段で、空気に触れた面のブロックの {@code fraction} の割合(座標のまだら)を覆う。2026-11-12 から、
     * 表のブロック(ネザーラック・ソウルソイル・ソウルサンド)は覆われたブロック(ソウルサンドは泥炭)へ置き換え、表に無いブロックは覆い(重ねる苔の絵)を付ける
     * ({@link OvergrownLayerData#canCover})。置き換えた数を返す(覆いは記録だけなので数えない)。
     */
    static int coverSection(ServerLevel level, int[] cols, int sectionY, double fraction, long origin, double progress, int reach) {
        if (fraction <= 0) return 0;
        long seed = level.getSeed();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int covered = 0;
        int baseY = sectionY << 4;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            // 覆いは起点から染みるように広がる(2026-11-12)。起点のまわりは前線の揺らぎに関係なく最初から覆う(11-12: 揺らぎが起点の近くで高いと、
            // 段階 1 のはじめに半径 coverOriginRadius も覆われず、coverSpreadsFromTheOrigin が 5 回に 2 回ほど落ちていた)
            if (!inFront(seed, x, z, origin, progress, reach) && !nearOrigin(x, z, origin)) continue;
            LevelChunk chunk = chunkAt(level, x, z);
            if (chunk == null) continue;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir()) continue;
            for (int ly = 0; ly < 16; ly++) {
                BlockState s = section.getBlockState(x & 15, ly, z & 15);
                if (s.isAir() || !s.blocksMotion()) continue;
                BlockState to = coveredOf(s);
                if (to == null && !OvergrownLayerData.canCover(s)) continue;
                int y = baseY + ly;
                if (!coverHit(seed, x, y, z, fraction, origin)) continue; // かたまりの斑(1 マスごとのごま塩にしない)。起点のまわりは必ず覆う(2026-11-12)
                if (!exposed(level, at, n, x, y, z)) continue;
                if (to != null) {
                    // 表のブロック: 覆われたブロックへ置き換える(2026-11-12 ユーザー決定。上掛けは使わない)
                    put(level, at.set(x, y, z), to);
                    covered++;
                } else {
                    OvergrownLayerData.cover(level, at.set(x, y, z)); // 表に無いブロック: 覆い(上掛け。記録だけなので数えない)
                }
            }
        }
        return covered;
    }

    /**
    /**
     * 段階 3: 列の高さ {@code sectionY} の 16 段で、空気に触れた面(床・壁・天井)を覆う(2026-11-12 ユーザー決定で作り直し):
     * <ul>
     *     <li>表のブロック(ネザーラック・ナイリウム・ソウルソイル)と、その覆われた版: 床(真上が空いている)は {@code peatStage3} の割合で泥炭、
     *     ほかは {@code coverStage3}(1.0 = 全部)の割合で覆われたブロックへ。ソウルサンドは泥炭</li>
     *     <li>泥炭: 上掛けを {@code coverPeat} の斑に揃える</li>
     *     <li>表に無い立方体: {@code netherOvergrownBlockShare}(3 割)を汎用の過繁茂ブロック、残りを覆い(上掛け)</li>
     * </ul>
     * 面の奥は変えない(搾られた岩の奥の段はやめた)。置き換えた数を返す。
     */
    static int lateSection(ServerLevel level, int[] cols, int sectionY, NetherSpreadTable t) {
        long seed = level.getSeed();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        int changed = 0;
        int baseY = sectionY << 4;
        long origin = 0;
        boolean haveOrigin = false;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            LevelChunk chunk = chunkAt(level, x, z);
            if (chunk == null) continue;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section.hasOnlyAir()) continue;
            for (int ly = 0; ly < 16; ly++) {
                BlockState s = section.getBlockState(x & 15, ly, z & 15);
                if (s.isAir() || !s.blocksMotion()) continue;
                int y = baseY + ly;
                boolean peat = s.is(ModBlocks.NETHER_PEAT.get());
                boolean source = peatSource(s);
                BlockState to = coveredOf(s);
                if (!peat && !source && to == null && !isUnmappedCube(level, at.set(x, y, z), s)) continue;
                if (!exposed(level, at, n, x, y, z)) continue;
                at.set(x, y, z);
                if (peat) {
                    applyLateCover(level, at, s, t);
                    continue;
                }
                if (source || to != null) {
                    boolean floor = isOpen(level.getBlockState(n.set(x, y + 1, z)));
                    BlockState next;
                    if (floor && source && hash01(seed, x, y, z, 7) < t.peatStage3) {
                        next = ModBlocks.NETHER_PEAT.get().defaultBlockState();
                    } else if (to != null) {
                        if (!haveOrigin) {
                            origin = NetherLedgerData.get(level).coverOriginOf(net.minecraft.world.level.ChunkPos.asLong(x >> 4, z >> 4));
                            haveOrigin = true;
                        }
                        // 表のブロックは coverStage3(1.0)の割合で覆われたブロックへ。外れはそのまま(ナイリウムは枯れて素のネザーラック)
                        next = coverHit(seed, x, y, z, t.coverStage3, origin) ? to
                                : isNylium(s) ? Blocks.NETHERRACK.defaultBlockState() : null;
                    } else {
                        next = null; // もう覆われたブロック
                    }
                    if (next == null) continue;
                    put(level, at, next);
                    changed++;
                    if (next.is(ModBlocks.NETHER_PEAT.get())) applyLateCover(level, at, next, t);
                    continue;
                }
                // 表に無い立方体: 3 割を汎用の過繁茂ブロック、7 割を覆い(上掛け)
                if (hash01(seed, x, y, z, OVERGROWN_BLOCK_SALT) < t.netherOvergrownBlockShare) {
                    put(level, at, ModBlocks.NETHER_OVERGROWN_BLOCK.get().defaultBlockState());
                    changed++;
                } else {
                    OvergrownLayerData.cover(level, at);
                }
            }
        }
        return changed;
    }

    /** 段階 5: 床の覆われたブロック(と表の元)を {@code peatStage5} の割合まで泥炭へ(段階 3 の泥炭と同じ乱数なので広がるだけ)。 */
    static int peatColumns(ServerLevel level, int[] cols, NetherSpreadTable t) {
        long seed = level.getSeed();
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int changed = 0;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            LevelChunk chunk = chunkAt(level, x, z);
            if (chunk == null) continue;
            int nf = NetherSurface.floors(level, chunk, x, z, floors);
            for (int i = 0; i < nf; i++) {
                int y = floors[i];
                BlockState s = chunk.getBlockState(at.set(x, y, z));
                if (!peatSource(s)) continue;
                if (hash01(seed, x, y, z, 7) < t.peatStage5) {
                    put(level, at, ModBlocks.NETHER_PEAT.get().defaultBlockState());
                    changed++;
                    applyLateCover(level, at, ModBlocks.NETHER_PEAT.get().defaultBlockState(), t); // 泥炭は薄い覆い
                }
            }
        }
        return changed;
    }

    /**
     * 段階 3: くぼみに池。床の 1 つ上の空気から同じ高さを塗り広げ、まわりが全部固体で囲まれ(広さ {@code pondMaxCells} 以下)、
     * どのマスも下が固体なら、{@code pondChance} で水を張る。溶岩に触れる池は作らない。くぼみは始まりの列の持ち主が張る。
     * 水の下のソウルサンド・ソウルソイルは泥炭、マグマは冷えた殻へ先に変える(泡の柱を出さない)。置いた水の数を返す。
     */
    static int ponds(ServerLevel level, int[] cols, NetherSpreadTable t) {
        long seed = level.getSeed();
        int[] floors = new int[NetherSurface.MAX_FLOORS];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        LongOpenHashSet tried = new LongOpenHashSet();
        int placed = 0;
        for (int c = 0; c + 1 < cols.length; c += 2) {
            int x = cols[c], z = cols[c + 1];
            LevelChunk chunk = chunkAt(level, x, z);
            if (chunk == null) continue;
            int nf = NetherSurface.floors(level, chunk, x, z, floors);
            for (int i = 0; i < nf; i++) {
                int y = floors[i] + 1;
                long start = BlockPos.asLong(x, y, z);
                if (tried.contains(start)) continue;
                LongOpenHashSet basin = basinAt(level, x, y, z, t.pondMaxCells, tried);
                if (basin == null) continue;
                if (hash01(seed, x, y, z, 23) >= t.pondChance) continue;
                for (long key : basin) {
                    at.set(key);
                    n.set(at.getX(), at.getY() - 1, at.getZ());
                    BlockState below = level.getBlockState(n);
                    if (below.is(Blocks.SOUL_SAND) || below.is(Blocks.SOUL_SOIL)) {
                        put(level, n, ModBlocks.NETHER_PEAT.get().defaultBlockState()); // 水の底も泥炭(泡の柱を出さない。2026-11-12)
                    } else if (below.is(Blocks.MAGMA_BLOCK)) {
                        put(level, n, ModBlocks.COOLED_CRUST.get().defaultBlockState());
                    }
                }
                for (long key : basin) {
                    put(level, at.set(key), Fluids.WATER.defaultFluidState().createLegacyBlock());
                    placed++;
                }
            }
        }
        return placed;
    }

    /** {@code (x, y, z)} から始まるくぼみ(同じ高さの空気。下は固体・まわりは固体で閉じる)。くぼみでなければ null。 */
    @Nullable
    private static LongOpenHashSet basinAt(ServerLevel level, int x, int y, int z, int max, LongOpenHashSet tried) {
        LongOpenHashSet cells = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = BlockPos.asLong(x, y, z);
        queue.enqueue(start);
        cells.add(start);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        boolean ok = true;
        while (!queue.isEmpty()) {
            p.set(queue.dequeueLong());
            tried.add(p.asLong());
            BlockState here = level.getBlockState(p);
            if (!here.isAir()) {
                ok = false; // 草・蔦などが残っている所は池にしない
                continue;
            }
            BlockState below = level.getBlockState(q.set(p.getX(), p.getY() - 1, p.getZ()));
            if (!below.blocksMotion() || !below.getFluidState().isEmpty()) {
                ok = false;
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                q.setWithOffset(p, d);
                if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.isLoaded(level, q)) {
                    ok = false;
                    continue;
                }
                BlockState s = level.getBlockState(q);
                if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                    ok = false;
                    continue;
                }
                if (s.blocksMotion()) continue;
                long key = q.asLong();
                if (cells.add(key)) {
                    if (cells.size() > max) return null; // 開けた床(くぼみではない)
                    queue.enqueue(key);
                }
            }
        }
        if (!ok) return null;
        // 池の下のマスも溶岩に触れないか(下の固体の横・下は見ない。固体なので水は流れない)
        return cells;
    }
}
