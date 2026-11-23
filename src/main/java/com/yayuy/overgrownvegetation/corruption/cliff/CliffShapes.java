package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.pushback.RingWards;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 崖・急斜面の汚染の「形」(2026-11-20。A1 太い縄の滝 + B2 嚢の房)。巡回({@link CliffCorruption})とコマンド・GameTest がここを直接呼ぶ。
 * 形は Fable の模型(side-session-notes の {@code Cliff2.java})を、本物の地形をなぞる形に移した物。数はどれも {@code cliff.json}【仮】。
 *
 * <h2>崖の見分け</h2>
 * 地面の高さ = 木・葉・下草・自分の根と嚢を除いたいちばん上の地面({@link #groundY})。列 c と、その向き d の隣の列の段差が
 * {@code cliffDrop} 以上なら「崖の縁」、{@code slopeRun} マス先までの下りが {@code slopeDrop} 以上なら「急斜面」。
 * 同じ向き・同じ横の位置の列に縁が何段もあるときは、いちばん高い物だけを使う(急斜面は下りの途中の列もみな当たるので)。
 *
 * <h2>縄(A1)</h2>
 * 縁に沿う横の位置 u がワールド座標で決まる間隔({@code spacing} ± {@code spacingJitter})に当たる縁から、2 マス幅の縄を下ろす。
 * 1 段ごとに、縄の横の位置で「後ろが固い・前が空いている」面を探し(外へ最大 {@code maxStepOut} マス)、そこへ絡む根を置く。
 * 面が外へ 2 マス以上ずれた(岩棚・急斜面)ときは、棚の上を這ってつなぐ(急斜面ではこれが続いて「這う網」になる)。
 * 左右にゆらぎ、長さの 4〜6 割で二股に分かれて離れ、短い股は 8 割で終わる。先に垂れる根。崖の上では 3 本に分かれて奥へ這う。
 * 縄の間に 1 マス幅の細い根。縁には短い根と垂れる根の房。
 *
 * <h2>嚢の房(B2)</h2>
 * 割れ目 = 横にうねる縦の筋。面の岩 1 マスを器官の肉に置き換え(3 段に 1 つは隣も)、{@code clusterEvery} 段おきに崖の嚢の房が面の外へ膨れ、
 * その間に茎 1 + 嚢 1〜2 の小さい垂れ。芯・光る筋・膜は無し(ユーザー決定)。
 *
 * <p>置くのは空気と置き換えてよい植物(水は除く)のマスだけ(巨大樹の根・ほかの物を上書きしない)。肉だけは面の地形の岩・土を置き換える。
 * 押し返す道具の効く所({@link RingWards#guarded})には置かない。
 */
public final class CliffShapes {
    private CliffShapes() {
    }

    static final TagKey<Block> TREE = BlockTags.create(OvergrownVegetation.id("organ_clear_tree"));
    static final TagKey<Block> BRUSH = com.yayuy.overgrownvegetation.ModTags.CLEARABLE_BRUSH;
    /** 置く印: 近くの形だけを更新(大量の setBlock で植物が落ちないよう UPDATE_KNOWN_SHAPE も)。 */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** 見る範囲: チャンクの外へ何マスまで高さを読むか。 */
    static final int MARGIN = 8;

    /** 置いた数(テスト・調べ用)。 */
    public static final class Result {
        public int cliffEdges, slopeEdges, ropes, thin, cracks, roots, sacs, flesh;
        public long nanos;

        @Override
        public String toString() {
            return "崖の縁 " + cliffEdges + " / 急斜面 " + slopeEdges + " / 縄 " + ropes + " / 細い根 " + thin + " / 割れ目 " + cracks
                    + " / 根のブロック " + roots + " / 嚢 " + sacs + " / 肉 " + flesh + " / " + String.format("%.2f ms", nanos / 1e6);
        }
    }

    // ------------------------------------------------------------------ 地面

    /** 地面と数えない物: 空気・木・葉・下草・足を止めない物・自分の根と嚢と肉・器官。水は地面と数える。 */
    static boolean notGround(BlockState s) {
        if (s.isAir()) return true;
        if (s.is(Blocks.BARRIER)) return true; // テスト場の天井・壁(高さの地図はバリアの上を答える)
        if (!s.getFluidState().isEmpty()) return false;
        if (s.is(TREE) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(ModTags.GIANT_WOOD) || s.is(ModTags.GIANT_SILHOUETTE_LEAVES) || s.is(BRUSH)) {
            return true;
        }
        if (isOurs(s)) return true;
        return !s.blocksMotion();
    }

    static boolean isOurs(BlockState s) {
        return s.is(OrganBlocks.TANGLED_ROOT.get()) || s.is(OrganBlocks.CLIFF_SAC.get()) || s.is(OrganBlocks.ORGAN_STALK.get())
                || s.is(ModBlocks.DANGLING_ROOTS.get()) || s.is(ModBlocks.DANGLING_ROOTS_PLANT.get());
    }

    /** 木を除いた地面のいちばん上のブロックの y。 */
    public static int groundY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y > min; y--) {
            if (!notGround(level.getBlockState(p.set(x, y, z)))) return y;
        }
        return top;
    }

    /** 置いてよいマス(空気か、置き換えてよい植物。水・溶岩は除く)。 */
    static boolean open(BlockState s) {
        if (s.isAir()) return true;
        if (!s.getFluidState().isEmpty()) return false;
        if (isOurs(s)) return false;
        return s.canBeReplaced() || s.is(BRUSH) && !s.blocksMotion();
    }

    /** 面の後ろ(縄が寄りかかる)として数える固い物。 */
    static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && s.blocksMotion() && !isOurs(s) && !s.is(Blocks.BARRIER) && !s.is(BlockTags.LEAVES);
    }

    /** 肉に置き換えてよい面の地形(岩・土・砂利など。人工のブロック・鉱石は置き換えない)。 */
    static boolean replaceableRock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(Blocks.GRAVEL) || s.is(BlockTags.TERRACOTTA)
                || s.is(ModBlocks.OVERGROWN_STONE.get()) || s.is(ModBlocks.OVERGROWN_DIRT.get()) || s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || s.is(BlockTags.SAND) || s.is(Blocks.SANDSTONE) || s.is(Blocks.CALCITE) || s.is(Blocks.TUFF);
    }

    // ------------------------------------------------------------------ チャンク 1 つ

    /** 高さの窓(チャンク + まわり MARGIN マス)。 */
    static final class Heights {
        final int x0, z0, w;
        final int[] h;

        Heights(ServerLevel level, int x0, int z0, int w) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.h = new int[w * w];
            for (int i = 0; i < w; i++) for (int j = 0; j < w; j++) h[i * w + j] = groundY(level, x0 + i, z0 + j);
        }

        int at(int x, int z) {
            int i = x - x0, j = z - z0;
            if (i < 0 || j < 0 || i >= w || j >= w) return Integer.MIN_VALUE;
            return h[i * w + j];
        }
    }

    /**
     * チャンク 1 つの崖・急斜面に生やす。{@code ropes} = 縄と細い根と縁の房、{@code sacs} = 割れ目と嚢の房。
     * まわり 1 チャンクが読み込まれていること(呼ぶ側が確かめる)。
     */
    public static Result decorateChunk(ServerLevel level, ChunkPos chunk, boolean ropes, boolean sacs) {
        Result r = new Result();
        long start = System.nanoTime();
        CliffTable t = DesignData.cliff();
        int bx = chunk.getMinBlockX(), bz = chunk.getMinBlockZ();
        Heights hs = new Heights(level, bx - MARGIN, bz - MARGIN, 16 + 2 * MARGIN);
        boolean wards = true; // 押し返しの確かめは置くマスごと(Placer#guarded。帯・杭が無ければ mayBlockIn がすぐ false)
        Placer put = new Placer(level, wards, r);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            // 同じ横の位置の列(d に沿った線)ごとに、いちばん高い縁を 1 つ
            Direction side = d.getClockWise();
            int[] bestY = new int[16];
            BlockPos[] best = new BlockPos[16];
            boolean[] bestCliff = new boolean[16];
            boolean floorRope = false; // このチャンクのこの向きで床まで届く縄をもう置いたか(11-23 ユーザー決定: 崖の 1 続きごとに少なくとも 1 本。1 続き = チャンクの中の同じ向きの縁【仮】)
            for (int i = 0; i < 16; i++) {
                for (int j = 0; j < 16; j++) {
                    int x = bx + i, z = bz + j;
                    int h = hs.at(x, z);
                    int adj = hs.at(x + d.getStepX(), z + d.getStepZ());
                    if (adj == Integer.MIN_VALUE) continue;
                    boolean cliff = h - adj >= t.detect.cliffDrop;
                    boolean slope = false;
                    if (!cliff && h - adj >= 1) {
                        int far = hs.at(x + d.getStepX() * t.detect.slopeRun, z + d.getStepZ() * t.detect.slopeRun);
                        slope = far != Integer.MIN_VALUE && h - far >= t.detect.slopeDrop;
                    }
                    if (!cliff && !slope) continue;
                    int lane = Math.floorMod(x * side.getStepX() + z * side.getStepZ(), 16);
                    if (best[lane] == null || h > bestY[lane]) {
                        best[lane] = new BlockPos(x, h, z);
                        bestY[lane] = h;
                        bestCliff[lane] = cliff;
                    }
                }
            }
            for (int lane = 0; lane < 16; lane++) {
                BlockPos c = best[lane];
                if (c == null) continue;
                if (bestCliff[lane]) r.cliffEdges++;
                else r.slopeEdges++;
                int u = c.getX() * side.getStepX() + c.getZ() * side.getStepZ();
                int base = baseY(hs, c, d, t);
                int height = c.getY() - base;
                if (height < 3) continue;
                if (sacs && isAnchor(u, t.sacs.spacing, 0, d, 7) && !isAnchorNear(u, t, d) && chance(c, d, 5) < t.sacs.chance) {
                    crack(put, level, c, d, side, base, t, r);
                }
                if (ropes) {
                    if (isAnchor(u, t.rope.spacing, t.rope.spacingJitter, d, 1)) {
                        if (chance(c, d, 2) < t.rope.chance) {
                            boolean toFloor = !floorRope || chance(c, d, 8) < t.rope.floorShare;
                            if (rope(put, level, hs, c, d, side, height, !bestCliff[lane], toFloor, t, r) && toFloor) floorRope = true;
                        }
                    } else if (isAnchor(u - t.rope.spacing / 2, t.rope.spacing, t.rope.spacingJitter, d, 1) && chance(c, d, 4) < t.rope.chance) {
                        thin(put, level, hs, c, d, side, height, t, r);
                    }
                    if (bestCliff[lane] && chance(c, d, 6) < t.rope.edgeTuftChance && !isAnchorNear(u, t, d)) edgeTuft(put, level, c, d);
                }
            }
        }
        if (ropes && r.roots > 0) {
            // 縄を生やした印(経路の延長が縄の近くか見る。コマンド・テストから直に呼んだときも付ける)
            CliffCorruption.Data data = CliffCorruption.Data.get(level);
            data.mark(chunk, (byte) (data.get(chunk) | CliffCorruption.ROPES));
        }
        r.nanos = System.nanoTime() - start;
        return r;
    }

    /** 縁 c から向き d へ下りた先の地面の高さ(縄の長さの見積もり。近くの列の低いほう)。 */
    static int baseY(Heights hs, BlockPos c, Direction d, CliffTable t) {
        int base = c.getY();
        for (int j = 1; j <= MARGIN; j++) {
            int h = hs.at(c.getX() + d.getStepX() * j, c.getZ() + d.getStepZ() * j);
            if (h == Integer.MIN_VALUE) break;
            base = Math.min(base, h);
        }
        return base;
    }

    /**
     * 横の位置 u が間隔 spacing の縄(割れ目)の場所か。spacing ごとの区間に 1 か所、区間の真ん中 ± jitter(区間の番号のハッシュ)。
     * ワールド座標だけで決まるので、チャンクの境で間隔が崩れない。
     */
    static boolean isAnchor(int u, int spacing, int jitter, Direction d, int salt) {
        if (spacing <= 0) return false;
        int g = Math.floorDiv(u, spacing);
        int off = spacing / 2;
        if (jitter > 0) off += Math.floorMod(hash(g, d.get2DDataValue(), salt), 2 * jitter + 1) - jitter;
        return Math.floorMod(u, spacing) == Math.floorMod(off, spacing);
    }

    /**
     * u が縄の付け根の 2 マス(縄の場所とその右)に当たるか(割れ目・縁の房を縄の真上に重ねない)。
     * 11-20 に縄を 5 マスおきにしたら、前の「まわり 1 マス」では割れ目がほとんど置けなくなったので、縄そのものの 2 マスだけにした。
     */
    static boolean isAnchorNear(int u, CliffTable t, Direction d) {
        for (int k = -1; k <= 0; k++) if (isAnchor(u + k, t.rope.spacing, t.rope.spacingJitter, d, 1)) return true;
        return false;
    }

    static int hash(int a, int b, int c) {
        int h = a * 0x27d4eb2d ^ b * 0x165667b1 ^ c * 0x61c88647;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return h & 0x7fffffff;
    }

    static int hash(BlockPos p, Direction d, int salt) {
        return hash(p.getX() * 31 + d.get2DDataValue(), p.getZ() * 17 + p.getY(), salt);
    }

    /** 0〜1 の乱数(位置で決まる)。 */
    static double chance(BlockPos p, Direction d, int salt) {
        return (hash(p, d, salt) % 10000) / 10000.0D;
    }

    // ------------------------------------------------------------------ 置く

    /** 置く役(押し返しの確かめと数え)。 */
    static final class Placer {
        final ServerLevel level;
        final boolean wards;
        final Result r;

        Placer(ServerLevel level, boolean wards, Result r) {
            this.level = level;
            this.wards = wards;
            this.r = r;
        }

        /** 押し返しの道具の効く所か。縄は隣のチャンクへはみ出すので、置くマスのチャンクで近道を見る(生やすチャンクだけで見ると、隣のチャンクの帯を越えて生えた)。 */
        boolean guarded(BlockPos p) {
            return wards && RingWards.mayBlockIn(level, new ChunkPos(p)) && RingWards.guarded(level, p);
        }

        /** 空いたマスに置く。置けたら true。 */
        boolean place(BlockPos p, BlockState s) {
            if (!level.isLoaded(p) || !open(level.getBlockState(p)) || guarded(p)) return false;
            level.setBlock(p, s, FLAGS);
            if (s.is(OrganBlocks.TANGLED_ROOT.get())) r.roots++;
            else if (s.is(OrganBlocks.CLIFF_SAC.get())) r.sacs++;
            return true;
        }

        boolean root(BlockPos p) {
            return place(p, OrganBlocks.TANGLED_ROOT.get().defaultBlockState());
        }

        /** 下へ n 段の垂れる根(上が置けた物のときだけ)。 */
        void dangle(BlockPos under, int n) {
            BlockPos.MutableBlockPos q = under.mutable();
            int placed = 0;
            for (int i = 0; i < n; i++) {
                if (!open(level.getBlockState(q)) || guarded(q)) break;
                placed++;
                q.move(Direction.DOWN);
            }
            if (placed == 0) return;
            for (int i = 0; i < placed; i++) {
                BlockPos p = under.below(i);
                BlockState s = i == placed - 1 ? ModBlocks.DANGLING_ROOTS.get().defaultBlockState() : ModBlocks.DANGLING_ROOTS_PLANT.get().defaultBlockState();
                level.setBlock(p, s, FLAGS);
            }
        }
    }

    /** 縁の列 c から、横 l・外へ t・高さ y のマス。 */
    static BlockPos at(BlockPos c, Direction d, Direction side, int l, int t, int y) {
        return new BlockPos(c.getX() + side.getStepX() * l + d.getStepX() * t, y, c.getZ() + side.getStepZ() * l + d.getStepZ() * t);
    }

    /**
     * 面をなぞって 1 本下ろす。lat[k] = k 段目の横の位置。戻り値 = 最後に置けた段(置けなければ -1)。
     * 面 = 後ろ(内側)が固く、そのマスが空いている所。前の段の面から外へ最大 maxStepOut マス探す。外へ 2 マス以上ずれたら棚の上を這ってつなぐ。
     */
    static int run(Placer put, ServerLevel level, BlockPos c, Direction d, Direction side, int[] lat, int from, int to, int tipDangle, CliffTable t) {
        return run(put, level, c, d, side, lat, from, to, tipDangle, t, null);
    }

    /** 張り出しの下へ真下に垂れる最大の段数【仮】。 */
    static final int HANG_MAX = 8;

    /** {@code faces} が null でなければ、段ごとの面の位置(外へのマス数。置けなかった段は MIN_VALUE)を書く。 */
    static int run(Placer put, ServerLevel level, BlockPos c, Direction d, Direction side, int[] lat, int from, int to, int tipDangle, CliffTable t, int[] faces) {
        int prevT = 0, last = -1, prevL = Integer.MIN_VALUE, hang = 0;
        BlockPos lastPos = null;
        for (int k = from; k < to; k++) {
            int y = c.getY() - k;
            int l = lat[k];
            int face = Integer.MIN_VALUE;
            for (int tt = prevT - 1; tt <= prevT + t.detect.maxStepOut; tt++) {
                BlockState here = level.getBlockState(at(c, d, side, l, tt, y));
                BlockState behind = level.getBlockState(at(c, d, side, l, tt - 1, y));
                if (solid(behind) && (open(here) || here.is(OrganBlocks.TANGLED_ROOT.get()))) {
                    face = tt;
                    break;
                }
            }
            if (face == Integer.MIN_VALUE) {
                if (k - from < 2) continue; // 縁が引っ込んだ列: 次の段で探し直す
                // 張り出し・岩棚の縁の下(後ろに岩が無い): 前の段の真下へまっすぐ垂れる(11-20。前は棚で縄が切れ、棚の下に縄が無かった)
                if (lastPos != null && hang < HANG_MAX) {
                    BlockPos below = lastPos.below();
                    if (open(level.getBlockState(below)) && put.root(below)) {
                        hang++;
                        last = k;
                        lastPos = below;
                        continue;
                    }
                }
                break; // 下の地面・広い棚: ここで終わる
            }
            hang = 0;
            if (k > from && face > prevT + 1) {
                for (int tt = prevT + 1; tt < face; tt++) put.root(at(c, d, side, l, tt, y + 1)); // 棚の上を這う
            }
            if (lastPos != null && prevL != Integer.MIN_VALUE && l != prevL) {
                // 横へずれた段: 前の段の高さで、前の横の位置から今の横の位置まで根を足す(角だけでつながる縄にしない。
                // 角だけだと見た目が途切れ、Mob も次の根を見失う。クライアントで踏んだ)
                int step = l > prevL ? 1 : -1;
                for (int ll = prevL + step; ll != l + step; ll += step) put.root(at(c, d, side, ll, prevT, y + 1));
            }
            BlockPos p = at(c, d, side, l, face, y);
            if (put.root(p) || level.getBlockState(p).is(OrganBlocks.TANGLED_ROOT.get())) {
                last = k;
                lastPos = p;
            }
            if (faces != null && k < faces.length) faces[k] = face;
            prevL = l;
            prevT = face;
        }
        if (lastPos != null && tipDangle > 0) put.dangle(lastPos.below(), tipDangle);
        return last;
    }

    /**
     * A1 太い縄の滝 1 本。置いたら true。
     * {@code toFloor} なら長さを崖の高さちょうどにして、下端を真下の足場のすぐ上(床から 1 段)まで下ろす(11-23 ユーザー決定「一部が床から 2 段以内まで」。
     * 前は長さの上限と乱数で 0〜2 段短く、床から縄に取り付けない崖があり、植物側の Mob が崖の下で止まった)。途中の出っ張り・水などは
     * 今までどおり置けるマスだけに置く(貫かない)。
     */
    static boolean rope(Placer put, ServerLevel level, Heights hs, BlockPos c, Direction d, Direction side, int height, boolean slope, boolean toFloor, CliffTable t, Result r) {
        CliffTable.Rope rp = t.rope;
        int seed = hash(c, d, 9) % 1000;
        int len = toFloor ? height : Math.min(rp.maxLength, height) - seed % 3;
        if (len < 3) return false;
        int split = (int) (len * (rp.forkFrom + rp.forkSpread * ((seed % 97) / 97.0D)));
        int[] a = new int[len], b = new int[len];
        for (int k = 0; k < len; k++) {
            int cc = (int) Math.round(rp.sway * Math.sin(k * rp.swayPhasePerStep + seed));
            int drift = k > split ? (k - split + 2) / Math.max(1, rp.forkDriftEvery) : 0;
            a[k] = cc - drift;
            b[k] = cc + 1 + drift;
        }
        r.ropes++;
        run(put, level, c, d, side, a, 0, len, rp.tipDangle, t);
        int bLen = Math.max(1, (int) (len * rp.shortBranch));
        int[] bFaces = new int[len];
        java.util.Arrays.fill(bFaces, Integer.MIN_VALUE);
        run(put, level, c, d, side, b, 0, bLen, rp.tipDangle, t, bFaces);
        if (slope && rp.slopeLinkEvery > 0) link(put, level, c, d, side, b, bFaces, bLen, t);
        // 二股の付け根を太らせる(3 マス幅を 2 段)
        int[] fat = new int[len];
        for (int k = 0; k < len; k++) fat[k] = a[k] - 1;
        if (split > 0 && split < len) run(put, level, c, d, side, fat, Math.max(0, split - 1), split + 1, 0, t);
        // 崖の上: 縁から奥へ 3 本に分かれて這う
        for (int rr = -1; rr <= 1; rr++) {
            int l = a[0] + (rr > 0 ? 1 : 0);
            int n = rp.crawlMin + hash(c, d, rr + 20) % Math.max(1, rp.crawlSpread);
            for (int i = 0; i < n; i++) {
                if (i > 0 && i % 2 == 0) l += rr;
                crawl(put, level, hs, c, d, side, l, -i);
            }
        }
        return true;
    }

    /**
     * 急斜面の横の根(2026-11-20 ユーザー決定「急斜面は縄の間を横の根でつないで網に見せる」)。縄の右の股から {@code slopeLinkEvery} 段おきに、
     * 次の縄(横へ {@code spacing} マス先)まで、斜面の面をなぞって 1 マス幅の根を渡す。網も登れる(絡む根)。
     */
    static void link(Placer put, ServerLevel level, BlockPos c, Direction d, Direction side, int[] lat, int[] faces, int len, CliffTable t) {
        int every = t.rope.slopeLinkEvery, span = Math.max(2, t.rope.spacing);
        for (int k = every; k < len; k += every) {
            if (faces[k] == Integer.MIN_VALUE) continue;
            int y = c.getY() - k, prevT = faces[k];
            for (int l = lat[k] + 1; l < lat[k] + span; l++) {
                int face = faceAt(level, c, d, side, l, y, prevT, t);
                if (face == Integer.MIN_VALUE) break; // 斜面が切れた(端・張り出し)
                put.root(at(c, d, side, l, face, y));
                prevT = face;
            }
        }
    }

    /** 崖の上の地面に這う根 1 マス(地面の高さに沿う。急な段では止まる)。 */
    static void crawl(Placer put, ServerLevel level, Heights hs, BlockPos c, Direction d, Direction side, int l, int t) {
        BlockPos col = at(c, d, side, l, t, 0);
        int h = hs.at(col.getX(), col.getZ());
        if (h == Integer.MIN_VALUE || Math.abs(h - c.getY()) > 2) return;
        put.root(col.atY(h + 1));
    }

    /** 縄の間の細い根(1 マス幅・崖の thinFrom〜+thinSpread)。 */
    static void thin(Placer put, ServerLevel level, Heights hs, BlockPos c, Direction d, Direction side, int height, CliffTable t, Result r) {
        CliffTable.Rope rp = t.rope;
        int seed = hash(c, d, 13);
        int len = Math.max(3, (int) (height * (rp.thinFrom + rp.thinSpread * ((seed % 101) / 101.0D))));
        len = Math.min(len, rp.maxLength);
        int[] a = new int[len];
        int l = 0;
        for (int k = 0; k < len; k++) {
            if (k > 2 && hash(seed, k, 3) % 7 == 0) l += (hash(seed, k, 4) & 1) * 2 - 1;
            a[k] = l;
        }
        r.thin++;
        run(put, level, c, d, side, a, 0, len, rp.tipDangle, t);
        for (int i = 0; i < 2; i++) crawl(put, level, hs, c, d, side, 0, -i);
    }

    /** 縁の短い根と垂れる根の房(縁のすぐ外の 1 マスに根、その下に垂れる根 1〜2)。 */
    static void edgeTuft(Placer put, ServerLevel level, BlockPos c, Direction d) {
        BlockPos p = c.relative(d);
        if (!solid(level.getBlockState(c))) return;
        if (put.root(p)) put.dangle(p.below(), 1 + (hash(c, d, 8) & 1));
    }

    /** B2 割れ目 1 本と嚢の房。 */
    static void crack(Placer put, ServerLevel level, BlockPos c, Direction d, Direction side, int base, CliffTable t, Result r) {
        CliffTable.Sacs sc = t.sacs;
        int seed = hash(c, d, 31) % 1000;
        int top = c.getY() - sc.marginTop, bottom = base + sc.marginBottom;
        if (top - bottom < 3) return;
        r.cracks++;
        int prevT = 0;
        for (int y = top; y >= bottom; y--) {
            int k = (top - y) + seed % 6;
            int l = (int) Math.round(sc.wiggle * Math.sin(y * 0.5D + seed));
            int face = faceAt(level, c, d, side, l, y, prevT, t);
            if (face == Integer.MIN_VALUE) continue;
            prevT = face;
            flesh(put, level, at(c, d, side, l, face - 1, y), r);
            if (k % 3 == 0) {
                int f2 = faceAt(level, c, d, side, l + 1, y, face, t);
                if (f2 != Integer.MIN_VALUE) flesh(put, level, at(c, d, side, l + 1, f2 - 1, y), r);
            }
            int every = Math.max(2, sc.clusterEvery);
            if (k % every == every / 2) {
                int rad = 1 + (hash(seed, y, 5) & 1);
                for (int a = -rad; a <= rad; a++) {
                    for (int bb = -rad - 1; bb <= rad; bb++) {
                        for (int dd = 0; dd <= rad; dd++) {
                            if (a * a + bb * bb * 0.7D + dd * dd > rad * rad + 0.6D) continue;
                            if (y + bb < bottom) continue;
                            put.place(at(c, d, side, l + a, face + dd, y + bb), OrganBlocks.CLIFF_SAC.get().defaultBlockState());
                        }
                    }
                }
            } else if (k % every == 0 && y > bottom + 2) {
                BlockPos s = at(c, d, side, l, face, y);
                if (put.place(s, OrganBlocks.ORGAN_STALK.get().defaultBlockState())) {
                    if (put.place(s.below(), OrganBlocks.CLIFF_SAC.get().defaultBlockState()) && (hash(seed, y, 9) & 1) == 0) {
                        put.place(s.below(2), OrganBlocks.CLIFF_SAC.get().defaultBlockState());
                    }
                }
            }
        }
    }

    /** 面(後ろが固く、そのマスが空き)を外へ探す。無ければ MIN_VALUE。 */
    static int faceAt(ServerLevel level, BlockPos c, Direction d, Direction side, int l, int y, int prevT, CliffTable t) {
        for (int tt = prevT - 1; tt <= prevT + t.detect.maxStepOut; tt++) {
            BlockState here = level.getBlockState(at(c, d, side, l, tt, y));
            BlockState behind = level.getBlockState(at(c, d, side, l, tt - 1, y));
            if (solid(behind) && !solid(here)) return tt;
        }
        return Integer.MIN_VALUE;
    }

    /** 面の岩 1 マスを器官の肉に。 */
    static void flesh(Placer put, ServerLevel level, BlockPos p, Result r) {
        if (!level.isLoaded(p) || put.guarded(p)) return;
        if (!replaceableRock(level.getBlockState(p))) return;
        level.setBlock(p, OrganBlocks.ORGAN_FLESH.get().defaultBlockState(), FLAGS);
        r.flesh++;
    }
}
