package com.yayuy.overgrownvegetation.corruption.pushback;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>侵入を止める物</b>(押し返し 2026-11-18 夜 ユーザー決定 C。11-18 夜の直しで「帯 = 線・杭 = 範囲の場」に)。
 *
 * <ul>
 *   <li><b>灰と石灰の帯 = 線</b>: 線を引くように地面に置く。汚染はその線を越えられない(閉じた輪でなくてよい。川や崖と組み合わせて仕切る)。
 *   <ul>
 *     <li>帯の列(帯のマスの x, z。高さは帯の y −{@code below}〜+{@code above})へは汚染が入らない(隣へ 1 マスずつ這う道は、
 *     8 方向につながった帯の線を 4 方向の一歩では越えられない)。</li>
 *     <li>跳ぶ道(根のアーチ・蔦の渡し・根の先・出どころから半径の中へ広がる芽や触手): 出どころ({@link #withSource})から置く先へ引いた
 *     線分が帯の列を横切るなら止める({@link #crossesBand})。線分は平面で 1 マスずつたどる(長さ L で L 回の表引き)。</li>
 *     <li>止めたら、その場所にいちばん近い帯のマスに 1 回数える(1 マス {@code bandHitCooldownSeconds} に 1 回まで)。{@code bandHits} 回で消える。</li>
 *     <li>帯の線の向こう側でも、胞子の発芽そのものは止めない(止めるのは杭と換気機の範囲)。</li>
 *   </ul></li>
 *   <li><b>地脈の杭 = 範囲の場</b>: FE がある限り、杭から水平 {@code stakeRadius}(高さは杭の y −{@code below}〜+{@code above})の中では、
 *   胞子の発芽で始まる汚染も、這ってくる汚染も起きない。中心が範囲に入るチャンクでは発芽も受け取らない({@link #stakeCoversChunk})。
 *   止めるたびに、その杭が {@code stakeFePerBlock} FE 払う(杭ごとに 1 秒 1 回まで)。</li>
 * </ul>
 * 帯の座標は次元ごとの SavedData に持つ(中身の無いブロックなので)。杭は中身(FE)があるので、読み込まれて FE のある杭だけを覚える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RingWards extends SavedData {

    public static final String ID = "overgrown_vegetation_ring_wards";
    /** 「まだ数えていない」の時刻(引き算が桁あふれしない値)。 */
    private static final long NEVER = Long.MIN_VALUE / 4;

    // --- 保存するもの ---
    final LongOpenHashSet bands = new LongOpenHashSet();
    /** 地面以外にくっついた帯 → くっついている向き(Direction の 3D の番号)。地面の帯は載せない。 */
    final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap bandDirs = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    // --- 保存しないもの ---
    /** 帯の列(x, z)→ その列の帯の y の [最小, 最大]。 */
    private final Long2ObjectOpenHashMap<int[]> bandCols = new Long2ObjectOpenHashMap<>();
    /** 帯の列を含むチャンク(近道)。 */
    private final LongOpenHashSet bandChunks = new LongOpenHashSet();
    /** 壁・天井の帯が守るマス(くっついたブロックのまわり 3×3×3)→ その帯のマス。洞窟の壁を 26 方向に這う一歩でも越えられない厚さ。 */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap bandCells = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private boolean rebuild = true;
    /** 帯のマス → 最後に数えた tick。 */
    private final Long2LongOpenHashMap bandLastHit = new Long2LongOpenHashMap();
    /** FE のある杭(読み込まれているもの)→ 最後に払った tick。 */
    private final Long2LongOpenHashMap stakes = new Long2LongOpenHashMap();
    /** チャンク → その範囲がかかる杭。 */
    private final Long2ObjectOpenHashMap<List<BlockPos>> stakeChunks = new Long2ObjectOpenHashMap<>();
    private boolean rebuildStakes = true;

    /** 確かめ用: 止めた回数(数えた分)。 */
    public static int blockedCount;

    private static final ThreadLocal<BlockPos> SOURCE = new ThreadLocal<>();

    public static final SavedData.Factory<RingWards> FACTORY = new SavedData.Factory<>(RingWards::new, RingWards::load, null);

    private static final Map<ResourceKey<Level>, RingWards> CACHE = new HashMap<>();

    public static RingWards get(ServerLevel level) {
        RingWards w = CACHE.get(level.dimension());
        if (w == null) {
            w = level.getDataStorage().computeIfAbsent(FACTORY, ID);
            CACHE.put(level.dimension(), w);
        }
        return w;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
        SOURCE.remove();
    }

    static RingWards load(CompoundTag tag, HolderLookup.Provider registries) {
        RingWards w = new RingWards();
        for (long l : tag.getLongArray("bands")) w.bands.add(l);
        long[] dk = tag.getLongArray("dirPos");
        byte[] dv = tag.getByteArray("dirs");
        for (int i = 0; i < Math.min(dk.length, dv.length); i++) w.bandDirs.put(dk[i], dv[i]);
        return w;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("bands", new LongArrayTag(bands.toLongArray()));
        tag.putLongArray("dirPos", bandDirs.keySet().toLongArray());
        byte[] dv = new byte[bandDirs.size()];
        int i = 0;
        for (long k : bandDirs.keySet()) dv[i++] = bandDirs.get(k);
        tag.putByteArray("dirs", dv);
        return tag;
    }

    public static PushBackTable.Ring table() {
        return com.yayuy.overgrownvegetation.data.DesignData.pushBack().ring;
    }

    // ------------------------------------------------------------------ 出入り(ブロックから)

    public static void addBand(ServerLevel level, BlockPos pos, net.minecraft.core.Direction attach) {
        RingWards w = get(level);
        boolean dirChanged = false;
        if (attach != net.minecraft.core.Direction.DOWN) dirChanged = w.bandDirs.put(pos.asLong(), (byte) attach.get3DDataValue()) != (byte) attach.get3DDataValue();
        else dirChanged = w.bandDirs.remove(pos.asLong()) != w.bandDirs.defaultReturnValue();
        if (w.bands.add(pos.asLong()) || dirChanged) {
            w.setDirty();
            w.rebuild = true;
        }
    }

    public static void removeBand(ServerLevel level, BlockPos pos) {
        RingWards w = get(level);
        w.bandDirs.remove(pos.asLong());
        if (w.bands.remove(pos.asLong())) {
            w.setDirty();
            w.rebuild = true;
        }
        w.bandLastHit.remove(pos.asLong());
    }

    /** 杭が FE を持った・失った・読み込まれた・外れた({@link LeyStakeBlockEntity} から)。 */
    public static void setStakePowered(ServerLevel level, BlockPos pos, boolean powered) {
        RingWards w = get(level);
        boolean changed = powered ? !w.stakes.containsKey(pos.asLong()) : w.stakes.containsKey(pos.asLong());
        if (!changed) return;
        if (powered) w.stakes.put(pos.asLong(), NEVER);
        else w.stakes.remove(pos.asLong());
        w.rebuildStakes = true;
    }

    // ------------------------------------------------------------------ 問い

    /** 出どころを付けて汚染を走らせる(芽の点・触手の起点など)。帯の線をまたぐかを、出どころから置く先への線分で見る。 */
    public static void withSource(@Nullable BlockPos origin, Runnable body) {
        BlockPos prev = SOURCE.get();
        SOURCE.set(origin);
        try {
            body.run();
        } finally {
            SOURCE.set(prev);
        }
    }

    /** このチャンクで止めることがありうるか(チャンク単位の近道。false ならそのチャンクのどこも止めない)。 */
    public static boolean mayBlockIn(ServerLevel level, ChunkPos chunk) {
        RingWards w = get(level);
        if (w.bands.isEmpty() && w.stakes.isEmpty()) return false;
        w.ensure();
        if (w.stakeChunks.containsKey(chunk.toLong())) return true;
        if (w.bandChunks.isEmpty()) return false;
        if (SOURCE.get() != null) return true; // 出どころからの線分は遠くの帯をまたぎうる
        return w.bandChunks.contains(chunk.toLong());
    }

    /** そのマスへ汚染(覆い・植物・根を含む)を入れてはいけないか。止めたら帯・杭に数える。 */
    public static boolean blocks(ServerLevel level, BlockPos pos) {
        return check(level, pos, true);
    }

    /** {@link #blocks} の数えない版(植物・飾りを置く所で、1 マスごとに帯をすり減らさないために)。 */
    public static boolean guarded(ServerLevel level, BlockPos pos) {
        return check(level, pos, false);
    }

    /** 出どころを直に渡す数えない版(巨大樹の置き場所など、出どころの線分を自分で持っている所)。 */
    public static boolean guardedFrom(ServerLevel level, @Nullable BlockPos source, BlockPos pos) {
        BlockPos prev = SOURCE.get();
        SOURCE.set(source);
        try {
            return check(level, pos, false);
        } finally {
            SOURCE.set(prev);
        }
    }

    /**
     * 中心から水平 {@code radius} の中に、杭の範囲か帯の列がかかるか(苗木が木に育つ前に。木の形は先に分からないので、広がる半径で見る)。
     */
    public static boolean nearAny(ServerLevel level, BlockPos center, int radius) {
        RingWards w = get(level);
        if (w.bands.isEmpty() && w.stakes.isEmpty()) return false;
        w.ensure();
        PushBackTable.Ring t = table();
        for (BlockPos s : w.stakesNear(center, radius)) {
            double dx = s.getX() - center.getX(), dz = s.getZ() - center.getZ();
            double reach = t.stakeRadius + radius;
            if (dx * dx + dz * dz <= reach * reach) return true;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (w.bandOnColumn(center.getX() + dx, center.getZ() + dz, center.getY()) != Long.MIN_VALUE) return true;
            }
        }
        return false;
    }

    private List<BlockPos> stakesNear(BlockPos center, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int r = radius + 16;
        for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
            for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
                List<BlockPos> l = stakeChunks.get(ChunkPos.asLong(cx, cz));
                if (l != null) for (BlockPos p : l) if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    private static boolean check(ServerLevel level, BlockPos pos, boolean count) {
        RingWards w = get(level);
        if (w.bands.isEmpty() && w.stakes.isEmpty()) return false;
        w.ensure();
        BlockPos stake = w.stakeAt(pos);
        if (stake != null) {
            if (count) w.payStake(level, stake);
            return true;
        }
        if (w.bandCols.isEmpty() && w.bandCells.isEmpty()) return false;
        long hit = w.bandOnColumn(pos.getX(), pos.getZ(), pos.getY());
        if (hit == Long.MIN_VALUE && !w.bandCells.isEmpty()) hit = w.bandCells.get(pos.asLong());
        if (hit == Long.MIN_VALUE) {
            BlockPos src = SOURCE.get();
            if (src != null) hit = w.firstBandOnSegment(src, pos);
        }
        if (hit == Long.MIN_VALUE) return false;
        if (count) w.wearBand(level, hit);
        return true;
    }

    /** そのマスが FE のある杭の範囲か(数えない。見せる・テスト用)。 */
    public static boolean inStakeField(ServerLevel level, BlockPos pos) {
        RingWards w = get(level);
        if (w.stakes.isEmpty()) return false;
        w.ensure();
        return w.stakeAt(pos) != null;
    }

    /** チャンクの中心が FE のある杭の範囲か(そこでは発芽を受け取らない)。 */
    public static boolean stakeCoversChunk(ServerLevel level, int cx, int cz) {
        RingWards w = get(level);
        if (w.stakes.isEmpty()) return false;
        w.ensure();
        List<BlockPos> list = w.stakeChunks.get(ChunkPos.asLong(cx, cz));
        if (list == null) return false;
        double r = table().stakeRadius;
        int mx = cx * 16 + 8, mz = cz * 16 + 8;
        for (BlockPos s : list) {
            double dx = s.getX() + 0.5D - mx, dz = s.getZ() + 0.5D - mz;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** from から to への線分が帯の列を横切るか(跳ぶ道を置く前に。数える)。 */
    public static boolean crossesBand(ServerLevel level, BlockPos from, BlockPos to) {
        RingWards w = get(level);
        if (w.bands.isEmpty()) return false;
        w.ensure();
        long hit = w.firstBandOnSegment(from, to);
        if (hit == Long.MIN_VALUE) return false;
        w.wearBand(level, hit);
        return true;
    }

    // ------------------------------------------------------------------ 中身

    @Nullable
    private BlockPos stakeAt(BlockPos pos) {
        if (stakes.isEmpty()) return null;
        List<BlockPos> list = stakeChunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (list == null) return null;
        PushBackTable.Ring t = table();
        double r2 = t.stakeRadius * t.stakeRadius;
        for (BlockPos s : list) {
            if (pos.getY() < s.getY() - t.below || pos.getY() > s.getY() + t.above) continue;
            double dx = s.getX() - pos.getX(), dz = s.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2) return s;
        }
        return null;
    }

    /** 列 (x, z) に、高さ y を守る帯があればそのマス(BlockPos#asLong)。無ければ Long.MIN_VALUE。 */
    private long bandOnColumn(int x, int z, int y) {
        int[] ys = bandCols.get(ChunkPos.asLong(x, z));
        if (ys == null) return Long.MIN_VALUE;
        PushBackTable.Ring t = table();
        if (y < ys[0] - t.below || y > ys[1] + t.above) return Long.MIN_VALUE;
        return BlockPos.asLong(x, ys[0], z);
    }

    /** from の列を除いて to までの線分(平面・1 マスずつ)で最初に当たる帯のマス。無ければ Long.MIN_VALUE。 */
    private long firstBandOnSegment(BlockPos from, BlockPos to) {
        int x0 = from.getX(), z0 = from.getZ(), x1 = to.getX(), z1 = to.getZ();
        int n = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        if (n == 0 || n > 256) return Long.MIN_VALUE;
        // 近道: 線分がかかるチャンク(両端の箱)に帯が 1 枚も無ければたどらない(帯が世界のどこかにあるだけで全部の問い合わせが重くならないように)
        boolean any = false;
        for (int cx = Math.min(x0, x1) >> 4; cx <= Math.max(x0, x1) >> 4 && !any; cx++)
            for (int cz = Math.min(z0, z1) >> 4; cz <= Math.max(z0, z1) >> 4 && !any; cz++)
                any = bandChunks.contains(ChunkPos.asLong(cx, cz));
        if (!any) return Long.MIN_VALUE;
        int px = x0, pz = z0;
        for (int i = 1; i <= n; i++) {
            double f = i / (double) n;
            int x = (int) Math.round(x0 + (x1 - x0) * f), z = (int) Math.round(z0 + (z1 - z0) * f);
            int y = (int) Math.round(from.getY() + (to.getY() - from.getY()) * f);
            long hit = bandOnColumn(x, z, y);
            if (hit == Long.MIN_VALUE && !bandCells.isEmpty()) hit = bandCells.get(BlockPos.asLong(x, y, z));
            // 斜めの一歩は角の 2 列も見る(斜めにつながった帯の線のすき間を抜けないように)
            if (hit == Long.MIN_VALUE && x != px && z != pz) {
                long a = bandOnColumn(x, pz, y), b = bandOnColumn(px, z, y);
                if (a != Long.MIN_VALUE && b != Long.MIN_VALUE) hit = a;
            }
            if (hit != Long.MIN_VALUE) return hit;
            px = x;
            pz = z;
        }
        return Long.MIN_VALUE;
    }

    private void wearBand(ServerLevel level, long band) {
        long now = level.getGameTime();
        long last = bandLastHit.getOrDefault(band, NEVER);
        if (now - last < table().bandHitCooldownSeconds * 20L) return;
        bandLastHit.put(band, now);
        blockedCount++;
        BlockPos bp = BlockPos.of(band);
        if (level.isLoaded(bp)) AshLimeBandBlock.wear(level, bp);
    }

    private void payStake(ServerLevel level, BlockPos stake) {
        long now = level.getGameTime();
        long last = stakes.getOrDefault(stake.asLong(), NEVER);
        if (now - last < 20) return;
        stakes.put(stake.asLong(), now);
        blockedCount++;
        if (level.isLoaded(stake) && level.getBlockEntity(stake) instanceof LeyStakeBlockEntity be) {
            be.pay(table().stakeFePerBlock);
        }
    }

    private void ensure() {
        if (rebuild) {
            rebuild = false;
            bandCols.clear();
            bandChunks.clear();
            bandCells.clear();
            bandCells.defaultReturnValue(Long.MIN_VALUE);
            for (long b : bands) {
                int x = BlockPos.getX(b), y = BlockPos.getY(b), z = BlockPos.getZ(b);
                if (bandDirs.containsKey(b)) {
                    // 壁・天井の帯: くっついたブロックのまわり 3×3×3 と帯のマス
                    net.minecraft.core.Direction d = net.minecraft.core.Direction.from3DDataValue(bandDirs.get(b));
                    int sx = x + d.getStepX(), sy = y + d.getStepY(), sz = z + d.getStepZ();
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                        bandCells.put(BlockPos.asLong(sx + dx, sy + dy, sz + dz), b);
                        bandChunks.add(ChunkPos.asLong((sx + dx) >> 4, (sz + dz) >> 4));
                    }
                    bandCells.put(b, b);
                    continue;
                }
                int[] ys = bandCols.computeIfAbsent(ChunkPos.asLong(x, z), k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
                ys[0] = Math.min(ys[0], y);
                ys[1] = Math.max(ys[1], y);
                bandChunks.add(ChunkPos.asLong(x >> 4, z >> 4));
            }
        }
        if (rebuildStakes) {
            rebuildStakes = false;
            stakeChunks.clear();
            int r = (int) Math.ceil(table().stakeRadius);
            for (long s : stakes.keySet()) {
                BlockPos p = BlockPos.of(s);
                for (int cx = (p.getX() - r) >> 4; cx <= (p.getX() + r) >> 4; cx++) {
                    for (int cz = (p.getZ() - r) >> 4; cz <= (p.getZ() + r) >> 4; cz++) {
                        stakeChunks.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>()).add(p);
                    }
                }
            }
        }
    }

    /** テスト用: その次元の帯・杭を忘れる。 */
    public static void clearForTests(ServerLevel level) {
        RingWards w = get(level);
        w.bands.clear();
        w.stakes.clear();
        w.bandLastHit.clear();
        w.rebuild = true;
        w.rebuildStakes = true;
        w.setDirty();
    }

    /** テスト用: 数える間を忘れる(すぐ次を数える)。 */
    public static void resetCooldownForTests(ServerLevel level) {
        RingWards w = get(level);
        w.bandLastHit.clear();
        w.stakes.replaceAll((k, v) -> NEVER);
    }
}
