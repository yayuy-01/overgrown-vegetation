package com.yayuy.overgrownvegetation.worldgen.fusion;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 構造物の判定の口(2026-11-19 構造物と森の融合 段 1。ユーザー決定「判定は A + C」)。
 *
 * <p>A = ゲームの構造物の記録(チャンクが持つ構造物の参照 → 開始 {@link StructureStart} → 部品の箱)。バニラも、登録された構造物として
 * 生成する構造物 MOD の大半も同じ形で引ける。C = 構造物のタグ {@code #overgrown_vegetation:fusion_excluded} で外す(地下の物・水の中の物)。
 * さらに部品ごとに「地表に出ているか」を見て、上端が地表より {@code surfaceMargin} 以上下の部品は外す(タグに無い MOD の地下構造物も拾える)。
 * プレイヤーの建物と、地物(feature)として置く小物は拾えない(0.2.0 では融合させない決定)。
 *
 * <p><b>重さ</b>: チャンクごとに、そのチャンクにかかる構造物を写して持つ(参照の無いチャンクは空の印)。1 マスの問い合わせはハッシュ 1 回 + 箱の比べだけ。
 * バニラの {@code StructureManager#getStructureWithPieceAt} は開始のチャンクを同期で取り、まだ読み込まれていなければ待つので使わない
 * (開始のチャンクが {@code getChunkNow} で取れないときは「まだ分からない」= {@link #UNKNOWN} を返し、呼び手は置かずに待つ)。
 * 主スレッドだけで使う。チャンクを降ろしたら写しを捨てる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class StructureFusion {
    private StructureFusion() {
    }

    /** 融合させない(森は今まで通りに振る舞う)構造物。 */
    public static final TagKey<Structure> EXCLUDED = TagKey.create(Registries.STRUCTURE, OvergrownVegetation.id("fusion_excluded"));

    public static final int NONE = 0, PROTECTED = 1, UNKNOWN = -1;

    /** 大きさの段(11-19 ユーザー決定「構造物の大きさで融合の形と大きさを変える」)。 */
    public enum SizeClass {
        SMALL, MEDIUM, LARGE
    }

    /** 構造物 1 つ。{@code whole} = 開始の全体の箱、{@code surfaceWhole} = 地表に出ている部品だけをまとめた箱。 */
    public record Site(ResourceLocation id, BoundingBox whole, BoundingBox surfaceWhole, BoundingBox[] pieces, SizeClass wholeClass,
                       boolean complete, long builtTick) {
    }

    /** 当たった部品と、その部品・構造物全体の大きさの段(村なら全体は大、1 軒は小、のように両方を見る)。 */
    public record Hit(Site site, BoundingBox piece, SizeClass pieceClass) {
        public SizeClass wholeClass() {
            return site.wholeClass;
        }
    }

    /** 範囲の問い合わせの答え。{@code unknown} = 読み込まれていないチャンクがあって全部は分からない。 */
    public record Query(boolean unknown, List<Hit> hits) {
        public boolean clear() {
            return !unknown && hits.isEmpty();
        }
    }

    private record SiteKey(Structure structure, long start) {
    }

    private record ChunkEntry(Site[] sites, boolean complete, long builtTick) {
    }

    private static final Site[] NO_SITES = new Site[0];
    private static final ChunkEntry EMPTY = new ChunkEntry(NO_SITES, true, 0L);
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<ChunkEntry>> CHUNKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<SiteKey, Site>> SITES = new HashMap<>();
    /** 部品の地表が全部は引けなかった写しを作り直すまでの tick。 */
    private static final long REBUILD_TICKS = 100L;

    public static FusionTable table() {
        return DesignData.organs().fusion;
    }

    // ---------------------------------------------------------------- 問い合わせ

    /**
     * そのマスが守る範囲(地表に出ている部品の箱 + {@code pieceMargin})か。{@link #PROTECTED}・{@link #NONE}・{@link #UNKNOWN}。
     * 融合が切ってあれば常に {@link #NONE}。
     */
    public static int state(ServerLevel level, BlockPos pos) {
        FusionTable t = table();
        if (!t.enabled) return NONE;
        int m = t.pieceMargin;
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int c0x = (x - m) >> 4, c1x = (x + m) >> 4, c0z = (z - m) >> 4, c1z = (z + m) >> 4;
        boolean unknown = false;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                ChunkEntry e = entry(level, cx, cz);
                if (e == null) {
                    unknown = true;
                    continue;
                }
                for (Site s : e.sites) {
                    for (BoundingBox b : s.pieces) {
                        if (x >= b.minX() - m && x <= b.maxX() + m && y >= b.minY() - m && y <= b.maxY() + m && z >= b.minZ() - m
                                && z <= b.maxZ() + m) return PROTECTED;
                    }
                }
            }
        }
        return unknown ? UNKNOWN : NONE;
    }

    /**
     * 森の置き物がそのマスに置いてはいけないか(守る範囲 or まだ分からない)。生きた世界({@link ServerLevel})のときだけ見る
     * (世界生成の並列スレッドからは false。今の森の置き物はどれも生きた世界に置くので、そこは通らない)。
     */
    public static boolean blocks(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl) || !sl.getServer().isSameThread()) return false;
        return state(sl, pos) != NONE;
    }

    /** 守る範囲の中か(分からないときは false)。テスト・コマンド向け。 */
    /**
     * 木・巨大樹を置くときに、そのマスを飛ばすか(11-19 段 2d で、箱の中を全部飛ばしていたのを直した)。守る範囲の外なら置く。
     * 守る範囲の中では、空気と草花のマス(水なし)に置く(段 3 から部屋の中も)。
     * 段 1 は箱の中を空気でも全部飛ばしたので、樹冠が部品の箱の形で真四角に欠けた(Cataclysm の神殿のように箱が建物より大きい物で目立った)。
     * まだ分からない所は飛ばす。
     */
    public static boolean keepsOut(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl) || !sl.getServer().isSameThread()) return blocks(level, pos);
        int st = state(sl, pos);
        if (st == NONE) return false;
        if (st == UNKNOWN) return true;
        net.minecraft.world.level.block.state.BlockState s = level.getBlockState(pos);
        // 段 3(11-19 夜 ユーザー「部屋の中まで入り込み、入口を塞いでもよい」): 部屋の中は飛ばさない。建物のブロックだけ飛ばす
        return !(s.isAir() || s.canBeReplaced()) || !s.getFluidState().isEmpty();
    }

    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        return state(level, pos) == PROTECTED;
    }

    /** そのマスを含む部品(余白なし)。無い・分からないなら null。 */
    @Nullable
    public static Hit at(ServerLevel level, BlockPos pos) {
        ChunkEntry e = entry(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (e == null) return null;
        for (Site s : e.sites) {
            for (BoundingBox b : s.pieces) {
                if (b.isInside(pos)) return new Hit(s, b, pieceClass(b));
            }
        }
        return null;
    }

    /** そのチャンクに参照のある構造物(地表の部品を持つ物)。読み込まれていない・開始のチャンクが無ければ null。 */
    @Nullable
    public static Site[] sitesAt(ServerLevel level, int cx, int cz) {
        if (!table().enabled) return NO_SITES;
        ChunkEntry e = entry(level, cx, cz);
        return e == null ? null : e.sites;
    }

    /** 構造物の地表の部品を全部、当たりの形で(構造物の木が建物を見るため)。 */
    public static List<Hit> hits(Site s) {
        List<Hit> out = new ArrayList<>(s.pieces.length);
        for (BoundingBox b : s.pieces) out.add(new Hit(s, b, pieceClass(b)));
        return out;
    }

    /** 箱にかかる(余白込み)部品を全部。同じ部品は 1 回。 */
    public static Query near(ServerLevel level, BoundingBox box) {
        FusionTable t = table();
        if (!t.enabled) return new Query(false, List.of());
        int m = t.pieceMargin;
        BoundingBox q = box.inflatedBy(m);
        boolean unknown = false;
        List<Hit> out = new ArrayList<>();
        java.util.Set<BoundingBox> seen = new java.util.HashSet<>();
        for (int cx = q.minX() >> 4; cx <= q.maxX() >> 4; cx++) {
            for (int cz = q.minZ() >> 4; cz <= q.maxZ() >> 4; cz++) {
                ChunkEntry e = entry(level, cx, cz);
                if (e == null) {
                    unknown = true;
                    continue;
                }
                for (Site s : e.sites) {
                    for (BoundingBox b : s.pieces) {
                        if (b.intersects(q) && seen.add(b)) out.add(new Hit(s, b, pieceClass(b)));
                    }
                }
            }
        }
        return new Query(unknown, out);
    }

    /**
     * 水平の円(半径 r)の中に守る部品があるか(巨大樹の起点など)。読み込まれていないチャンクは見ない(分からない所は「無い」扱い。
     * 巨大樹はそこへ置くときにマスごとの守り {@link #blocks} が効き、まだ分からないマスには置かない)。
     */
    public static boolean anyNear(ServerLevel level, int x, int z, double r) {
        if (!table().enabled) return false;
        int ri = (int) Math.ceil(r);
        BoundingBox box = new BoundingBox(x - ri, level.getMinBuildHeight(), z - ri, x + ri, level.getMaxBuildHeight(), z + ri);
        Query q = near(level, box);
        double r2 = r * r;
        for (Hit h : q.hits) {
            BoundingBox b = h.piece;
            double dx = Math.max(0, Math.max(b.minX() - x, x - b.maxX())), dz = Math.max(0, Math.max(b.minZ() - z, z - b.maxZ()));
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /**
     * 地面・地形のブロック(部品の箱に入っていても建物と数えない・汚染の覆いにしない)。村の道の部品は地面ごと箱に入る。
     * 覆いの記録は「石・土に置き直したら消える」決まりなので、箱の中の地形は覆わずそのまま残す(11-19 決め A)。
     */
    public static boolean isTerrain(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                || s.is(net.minecraft.world.level.block.Blocks.FARMLAND) || s.is(net.minecraft.tags.BlockTags.SNOW) || s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_GRASS_BLOCK.get())
                || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_DIRT.get()) || s.is(com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_STONE.get())
                || !s.getFluidState().isEmpty();
    }

    // ---------------------------------------------------------------- 大きさの段

    /** 長い辺 = 横の長い辺か高さの大きい方(11-19 全体管理: 塔は横 11・高さ 23 なので横だけだと小になる)。 */
    public static int span(BoundingBox b) {
        return Math.max(Math.max(b.getXSpan(), b.getZSpan()), b.getYSpan());
    }

    public static SizeClass pieceClass(BoundingBox b) {
        FusionTable t = table();
        int s = span(b);
        return s >= t.pieceLargeSpan ? SizeClass.LARGE : s >= t.pieceMediumSpan ? SizeClass.MEDIUM : SizeClass.SMALL;
    }

    public static SizeClass wholeClass(BoundingBox b) {
        FusionTable t = table();
        int s = span(b);
        long area = (long) b.getXSpan() * b.getZSpan();
        if (s >= t.wholeLargeSpan || area >= t.wholeLargeArea) return SizeClass.LARGE;
        return s >= t.wholeMediumSpan ? SizeClass.MEDIUM : SizeClass.SMALL;
    }

    // ---------------------------------------------------------------- 写し

    @Nullable
    private static ChunkEntry entry(ServerLevel level, int cx, int cz) {
        Long2ObjectOpenHashMap<ChunkEntry> map = CHUNKS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
        ChunkEntry e = map.get(key);
        long now = level.getGameTime();
        if (e != null && (e.complete || now - e.builtTick < REBUILD_TICKS)) return e;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return null;
        e = build(level, chunk, now);
        if (e != null) map.put(key, e);
        return e;
    }

    @Nullable
    private static ChunkEntry build(ServerLevel level, LevelChunk chunk, long now) {
        Map<Structure, LongSet> refs = chunk.getAllReferences();
        if (refs.isEmpty()) return EMPTY;
        Registry<Structure> reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<SiteKey, Site> sites = SITES.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        if (sites.size() > 8192) sites.clear();
        List<Site> out = new ArrayList<>();
        boolean complete = true;
        for (Map.Entry<Structure, LongSet> en : refs.entrySet()) {
            Structure structure = en.getKey();
            if (reg.wrapAsHolder(structure).is(EXCLUDED)) continue;
            for (long start : en.getValue()) {
                SiteKey sk = new SiteKey(structure, start);
                Site s = sites.get(sk);
                if (s == null || !s.complete && now - s.builtTick >= REBUILD_TICKS) {
                    // getChunk(…, STRUCTURE_STARTS, false) は生成中のチャンクを主スレッドで待ちうるので使わない(hasChunk の罠と同じ)
                    ChunkAccess startChunk = level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(start),
                            net.minecraft.world.level.ChunkPos.getZ(start));
                    if (startChunk == null) return null; // 開始のチャンクが無い = まだ分からない
                    StructureStart ss = startChunk.getStartForStructure(structure);
                    s = ss == null || !ss.isValid() ? null : site(level, reg.getKey(structure), ss, now);
                    if (s == null) {
                        s = new Site(reg.getKey(structure), BoundingBox.infinite(), BoundingBox.infinite(), new BoundingBox[0], SizeClass.SMALL, true, now);
                    }
                    sites.put(sk, s);
                }
                if (s.pieces.length == 0) continue;
                complete &= s.complete;
                out.add(s);
            }
        }
        return out.isEmpty() ? EMPTY : new ChunkEntry(out.toArray(NO_SITES), complete, now);
    }

    @Nullable
    private static Site site(ServerLevel level, @Nullable ResourceLocation id, StructureStart ss, long now) {
        FusionTable t = table();
        List<BoundingBox> keep = new ArrayList<>();
        boolean complete = true;
        for (StructurePiece p : ss.getPieces()) {
            BoundingBox b = p.getBoundingBox();
            int surface = surface(level, b);
            if (surface == Integer.MIN_VALUE) {
                complete = false; // 地表が引けない(読み込まれていない)部品は守る側に倒す
            } else if (b.maxY() < surface - t.surfaceMargin) {
                continue; // 地下の部品
            }
            keep.add(b);
        }
        BoundingBox whole = ss.getBoundingBox();
        BoundingBox surfaceWhole = keep.isEmpty() ? whole : BoundingBox.encapsulatingBoxes(keep).orElse(whole);
        return new Site(id != null ? id : OvergrownVegetation.id("unknown"), whole, surfaceWhole, keep.toArray(new BoundingBox[0]),
                wholeClass(surfaceWhole), complete, now);
    }

    /**
     * 部品の箱の地表: 真ん中と四隅(1 内側)の 5 本の列の、葉を除いた高さ地図のいちばん低い所。読み込まれていない列は飛ばし、
     * 1 本も引けなければ {@code Integer.MIN_VALUE}。建物そのものが地表になるので、地表に出ている部品の上端は地表より下にならない。
     */
    private static int surface(ServerLevel level, BoundingBox b) {
        int[][] cols = {{(b.minX() + b.maxX()) >> 1, (b.minZ() + b.maxZ()) >> 1}, {Math.min(b.maxX(), b.minX() + 1), Math.min(b.maxZ(), b.minZ() + 1)},
                {Math.max(b.minX(), b.maxX() - 1), Math.min(b.maxZ(), b.minZ() + 1)}, {Math.min(b.maxX(), b.minX() + 1), Math.max(b.minZ(), b.maxZ() - 1)},
                {Math.max(b.minX(), b.maxX() - 1), Math.max(b.minZ(), b.maxZ() - 1)}};
        int min = Integer.MAX_VALUE;
        boolean any = false;
        for (int[] c : cols) {
            LevelChunk ch = level.getChunkSource().getChunkNow(c[0] >> 4, c[1] >> 4);
            if (ch == null) continue;
            any = true;
            min = Math.min(min, ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0] & 15, c[1] & 15));
        }
        return any ? min : Integer.MIN_VALUE;
    }

    /** 写しを捨てる(テストで構造物の記録を足し引きしたとき)。 */
    public static void invalidate(ServerLevel level) {
        CHUNKS.remove(level.dimension());
        SITES.remove(level.dimension());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Long2ObjectOpenHashMap<ChunkEntry> map = CHUNKS.get(level.dimension());
        if (map != null) map.remove(event.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CHUNKS.clear();
        SITES.clear();
    }
}
