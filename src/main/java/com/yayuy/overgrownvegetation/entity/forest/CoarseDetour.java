package com.yayuy.overgrownvegetation.entity.forest;

import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 回り道の粗い下調べ(11-09、森の中を動く型 ②。種によらない)。
 *
 * <p>経路の探索(バニラの A* と {@code OvergrownNodeEvaluator})はノード 1 つに数 µs〜10 µs かかり、回り道が探す範囲の外にあるとき
 * 範囲を広げて探し直すと 1 回 11〜13 ms かかった(群れで何体も詰まると 1 tick を食い潰す)。ここでは代わりに、
 * <b>ブロックの「動きを止めるか」だけを見る 2D の格子</b>で回り道を探す。見つかった道の途中、普段の探索で届く距離に
 * 「途中の目標」を置き、そこまでは普段の探索でつなぐ(呼ぶ側)。
 *
 * <p>格子のマス = 体の足場の角(バニラの大きい体のノードと同じ)。マスの高さは、体が乗る列(幅 × 幅)それぞれで
 * 「1 段下が動きを止め、足元から 3 段が空いている」高さを、隣のマスの高さの +1〜−3 の間で探して決める(高さ地図は葉の天蓋や
 * 天井で使えない)。列どうしの高さの差が 1 を超えるマスは通れない。葉を通れない体は、体の高さの葉を壁と見る。足元の水・溶岩は通れない。
 * 斜めは両脇も通れるときだけ。
 *
 * <p>重さを抑える仕組み:
 * <ul>
 *   <li><b>サーバー全体の予算</b>({@link #BUDGET_NANOS} / tick)。使い切ったら下調べせず、呼ぶ側は少し後に探し直す。</li>
 *   <li><b>使い回し</b>: 同じ目標(4 マス単位)へ、近く(4 マス単位)から {@link #SHARE_TICKS} 以内に下調べした結果があれば使う。</li>
 * </ul>
 * 値は全部【仮】。
 */
public final class CoarseDetour {

    /** 回り道に使う時間の、サーバー全体の 1 tick あたりの上限(ns)【仮 2 ms】。 */
    public static final long BUDGET_NANOS = 2_000_000L;
    /** 調べるマスの数の上限・探す半径(ブロック)【仮】。 */
    static final int MAX_EXPAND = 4000, RADIUS = 48;
    /** 結果を使い回す時間(tick)【仮】。 */
    static final long SHARE_TICKS = 100L;
    /** 体の高さ(足元から空いている段の数)・上れる段・下りる段【仮】。 */
    static final int BODY_ROWS = 3, STEP_UP = 1, STEP_DOWN = 3;

    private static long budgetTick = Long.MIN_VALUE;
    private static long budgetSpent;
    /** 計測用: 下調べの時間(ns)・回数・使い回した回数・予算で断った回数・1 tick の合計の最大(ns)・合計(ns)。 */
    public static volatile long nanos, calls, shared, deferred, maxTickNanos, totalNanos;
    private static final Map<Long, Shared> SHARE = new HashMap<>();

    private record Shared(Level level, Plan plan, long at) {
    }

    /** 下調べの答え: 途中の目標と、下調べの道の終わりから目標までの距離(ブロック。0 なら届く道)。 */
    public record Plan(BlockPos waypoint, double gap) {
    }

    private CoarseDetour() {
    }

    private static void roll(ServerLevel level) {
        long t = level.getGameTime();
        if (t != budgetTick) {
            budgetTick = t;
            budgetSpent = 0L;
        }
    }

    /** 回り道 1 回(下調べ + 途中の目標までの探索)にかかる時間の見積もり(ns、移動平均)。 */
    private static long avgCallNanos;

    /** 回り道 1 回にかかった時間を見積もりに入れる。 */
    public static void noteCall(long ns) {
        avgCallNanos = avgCallNanos == 0L ? ns : (avgCallNanos * 4L + ns) / 5L;
    }

    /**
     * 今の tick の予算に、もう 1 回分(見積もり)が入るか。その tick の最初の 1 回はいつでも通す。入らなければ断った数を数える。
     */
    /** 今の tick の予算の残り(ns、負なら 0)。竹馬型の経路が 1 回に使える時間を決める(11-11)。 */
    public static long remaining(ServerLevel level) {
        roll(level);
        return Math.max(0L, BUDGET_NANOS - budgetSpent);
    }

    public static boolean budgetLeft(ServerLevel level) {
        roll(level);
        if (budgetSpent > 0L && budgetSpent + avgCallNanos > BUDGET_NANOS) {
            deferred++;
            return false;
        }
        return true;
    }

    /** 回り道(下調べ・途中の目標までの探索・前のやり方の広げた探索)に使った時間を予算から引き、計測に足す。 */
    public static void spend(ServerLevel level, long ns) {
        roll(level);
        budgetSpent += ns;
        totalNanos += ns;
        if (budgetSpent > maxTickNanos) maxTickNanos = budgetSpent;
    }

    /** 計測を 0 に戻す(計測・GameTest)。使い回しの控えも消す。 */
    public static void resetStats() {
        nanos = calls = shared = deferred = maxTickNanos = totalNanos = 0L;
        SHARE.clear();
    }

    private static long shareKey(BlockPos from, BlockPos to) {
        return ((long) (from.getX() >> 2) & 0xFFFF) | (((long) (from.getZ() >> 2) & 0xFFFF) << 16)
                | (((long) (to.getX() >> 2) & 0xFFFF) << 32) | (((long) (to.getZ() >> 2) & 0xFFFF) << 48);
    }

    /**
     * {@code from}(体の足場の角)から {@code to} への回り道を格子で探し、道に沿って {@code reach} ブロック以内で一番先の「途中の目標」を返す。
     * 道が見つからなければ、一番 {@code to} に近づけた所までの道で同じように選ぶ。{@code from} から少しも進めなければ null。
     *
     * @param width       体が乗る列の数(バニラの entityWidth)
     * @param leavesBlock 葉を通れない体か
     */
    @Nullable
    public static Plan plan(ServerLevel level, BlockPos from, BlockPos to, int width, boolean leavesBlock, double reach) {
        long key = shareKey(from, to) ^ (leavesBlock ? 0x5555L : 0L);
        long now = level.getGameTime();
        Shared s = SHARE.get(key);
        if (s != null && s.level() == level && now - s.at() <= SHARE_TICKS && s.plan().waypoint().distSqr(from) > 4.0D) {
            shared++;
            return s.plan();
        }
        long t0 = System.nanoTime();
        Plan wp = new Search(level, from, to, width, leavesBlock).run(reach);
        long ns = System.nanoTime() - t0;
        nanos += ns;
        calls++;
        spend(level, ns);
        if (wp != null) {
            if (SHARE.size() > 256) SHARE.entrySet().removeIf(e -> now - e.getValue().at() > SHARE_TICKS);
            SHARE.put(key, new Shared(level, wp, now));
        }
        return wp;
    }

    /** 1 回の下調べ(格子・列の高さの控えを持つ)。 */
    private static final class Search {
        static final int NONE = Integer.MAX_VALUE, BLOCKED = Integer.MIN_VALUE;
        final ServerLevel level;
        final int n, ox, oz, width, stride, tx, tz;
        final boolean leavesBlock;
        final int[] colH, cellH, parent;
        final float[] g;
        final boolean[] closed;
        final LevelChunk[] chunks;
        final int chunkOx, chunkOz, chunkSpan;
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        final int baseY, start;

        Search(ServerLevel level, BlockPos from, BlockPos to, int width, boolean leavesBlock) {
            this.level = level;
            this.n = RADIUS * 2 + 1;
            this.ox = from.getX() - RADIUS;
            this.oz = from.getZ() - RADIUS;
            this.width = width;
            this.leavesBlock = leavesBlock;
            this.stride = n + width;
            this.colH = new int[stride * stride];
            this.cellH = new int[n * n];
            Arrays.fill(colH, NONE);
            Arrays.fill(cellH, NONE);
            this.g = new float[n * n];
            Arrays.fill(g, Float.MAX_VALUE);
            this.parent = new int[n * n];
            Arrays.fill(parent, -1);
            this.closed = new boolean[n * n];
            this.chunkOx = ox >> 4;
            this.chunkOz = oz >> 4;
            this.chunkSpan = ((ox + stride) >> 4) - chunkOx + 1;
            this.chunks = new LevelChunk[chunkSpan * chunkSpan];
            this.baseY = from.getY();
            this.tx = Math.max(0, Math.min(n - 1, to.getX() - ox));
            this.tz = Math.max(0, Math.min(n - 1, to.getZ() - oz));
            this.start = RADIUS + RADIUS * n;
        }

        @Nullable
        Plan run(double reach) {
            if (cell(RADIUS, RADIUS, baseY) == BLOCKED) cellH[start] = baseY;   // 立っている所が格子では通れない(体の芯だけ入る隙間など)
            g[start] = 0f;
            PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
            open.add(new long[]{(long) (dist(RADIUS, RADIUS, tx, tz) * 1000), start});
            int best = start;
            double bestD = dist(RADIUS, RADIUS, tx, tz);
            int expanded = 0;
            while (!open.isEmpty() && expanded < MAX_EXPAND) {
                int cur = (int) open.poll()[1];
                if (closed[cur]) continue;
                closed[cur] = true;
                expanded++;
                int cx = cur % n, cz = cur / n;
                double d = dist(cx, cz, tx, tz);
                if (d < bestD) {
                    bestD = d;
                    best = cur;
                }
                if (cx == tx && cz == tz) break;
                int ch = cellH[cur];
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        int nx = cx + dx, nz = cz + dz;
                        if (nx < 0 || nz < 0 || nx >= n || nz >= n) continue;
                        int ni = nx + nz * n;
                        if (closed[ni]) continue;
                        int nh = cell(nx, nz, ch);
                        if (nh == BLOCKED || nh - ch > STEP_UP || ch - nh > STEP_DOWN) continue;
                        if (dx != 0 && dz != 0 && (cell(cx + dx, cz, ch) == BLOCKED || cell(cx, cz + dz, ch) == BLOCKED)) continue;
                        float ng = g[cur] + (dx != 0 && dz != 0 ? 1.4142f : 1f);
                        if (ng < g[ni]) {
                            g[ni] = ng;
                            parent[ni] = cur;
                            open.add(new long[]{(long) ((ng + dist(nx, nz, tx, tz)) * 1000), ni});
                        }
                    }
                }
            }
            if (best == start) return null;
            // 道をたどって始めから並べ、道に沿って reach 以内で一番先のマスを選ぶ
            int len = 0;
            for (int c = best; c != -1; c = parent[c]) len++;
            int[] chain = new int[len];
            int k = len;
            for (int c = best; c != -1; c = parent[c]) chain[--k] = c;
            int pick = start;
            double arc = 0;
            for (int i = 1; i < len; i++) {
                arc += dist(chain[i - 1] % n, chain[i - 1] / n, chain[i] % n, chain[i] / n);
                if (arc > reach) break;
                pick = chain[i];
            }
            if (pick == start) return null;
            return new Plan(new BlockPos(pick % n + ox, cellH[pick], pick / n + oz), bestD);
        }

        /** マス(体の足場の角)の高さ。通れなければ BLOCKED。最初に訪れたときの基準の高さ {@code ref} で決まる(控える)。 */
        int cell(int cx, int cz, int ref) {
            int ci = cx + cz * n;
            if (cellH[ci] != NONE) return cellH[ci];
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int a = 0; a < width; a++) {
                for (int b = 0; b < width; b++) {
                    int h = column(cx + a, cz + b, ref);
                    if (h == BLOCKED) {
                        cellH[ci] = BLOCKED;
                        return BLOCKED;
                    }
                    lo = Math.min(lo, h);
                    hi = Math.max(hi, h);
                }
            }
            int r = hi - lo > 1 ? BLOCKED : hi;
            cellH[ci] = r;
            return r;
        }

        /** 列の足を置ける高さ({@code ref} の +1〜−3 で一番高い所)。無ければ BLOCKED。 */
        int column(int lx, int lz, int ref) {
            int i = lx + lz * stride;
            if (colH[i] != NONE) return colH[i];
            int x = lx + ox, z = lz + oz;
            LevelChunk chunk = chunk(x >> 4, z >> 4);
            int r = BLOCKED;
            if (chunk != null) {
                // 上から 1 段ずつ読む。動きを止める段の上に BODY_ROWS 段の空きがあれば、その上が足元
                int top = ref + STEP_UP + BODY_ROWS - 1, bottom = ref - STEP_DOWN - 1;
                int free = 0;   // 上から続けて空いている段の数
                for (int y = top; y >= bottom; y--) {
                    BlockState s = chunk.getBlockState(p.set(x, y, z));
                    boolean wet = !s.getFluidState().isEmpty();
                    if (!wet && passable(s)) {
                        free++;
                        continue;
                    }
                    if (free >= BODY_ROWS) {
                        if (wet) break;   // 水面・溶岩: 通れない
                        if (y + 1 <= ref + STEP_UP) {
                            r = y + 1;
                            break;
                        }
                    }
                    free = 0;
                }
            }
            colH[i] = r;
            return r;
        }

        boolean passable(BlockState s) {
            if (s.isAir()) return true;
            if (isLeaf(s)) return !leavesBlock;
            return !s.blocksMotion();
        }

        @Nullable
        LevelChunk chunk(int cx, int cz) {
            int i = (cx - chunkOx) + (cz - chunkOz) * chunkSpan;
            if (i < 0 || i >= chunks.length) return null;
            LevelChunk c = chunks[i];
            if (c == null) {
                c = level.getChunkSource().getChunkNow(cx, cz);   // 読み込まれていなければ null(読み込まない)
                chunks[i] = c;
            }
            return c;
        }
    }

    /** 確かめ用: 列の足を置ける高さ(BLOCKED = Integer.MIN_VALUE)。 */
    public static int debugColumn(ServerLevel level, int x, int z, int ref, boolean leavesBlock) {
        Search s = new Search(level, new BlockPos(x, ref, z), new BlockPos(x, ref, z), 1, leavesBlock);
        return s.column(RADIUS, RADIUS, ref);
    }

    private static double dist(int ax, int az, int bx, int bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean isLeaf(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.getBlock() instanceof OvergrownLeavesBlock;
    }
}
