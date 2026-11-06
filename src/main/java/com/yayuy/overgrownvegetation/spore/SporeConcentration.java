package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.PortalCorruptionData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.quake.EarthquakeData;
import com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 「胞子濃度」(%)— 胞子の場の読み(2026-10-23 第 2 段 D)。100% = 過繁茂のチャンクの晴れた午後のふつうの浮遊 + 林床
 * ({@code spore_field.json} の {@code reading.fullAt})。中身は上限なし(嵐で 250% ほど)、表示する側が丸める。
 *
 * <ul>
 *     <li><b>チャンクの値</b>: 場の浮遊 + 林床 ÷ fullAt。汚染されたチャンクは段階ごとの下支え(繁茂・過繁茂)より下がらない。
 *     写しが無い所(プレイヤーから遠い)は台帳の段階の下支えだけ。ネザーはネザーの場、エンドは塗られた島を過繁茂の下支えで。</li>
 *     <li><b>地点の値</b>: まわり 4 チャンクの値をなめらかに混ぜ、地割れの周りの下支えを足し、地点の補正(梢の上・洞窟・部屋・
 *     雨・風上の壁の陰)を掛ける。</li>
 * </ul>
 *
 * <p>2026-10-30: 場が止まっているとき(spore_field.json の enabled=false・それ以外の次元)の古い計算(過繁茂バイオームの割合と距離・
 * 汚染列の数・ペディキュラ・浸食度の持ち出し・装置の上乗せ)と、その設定項目を消した。場が回っていなければ 0%。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeConcentration {

    /** percent = 地上の値、under = 地下の層の値(2026-11-04)。 */
    private record Cached(double percent, double under, long computedGameTime) {
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Cached>> CACHE = new HashMap<>();
    /** 1 次元あたりのキャッシュ上限。超えたら期限切れを捨て、それでも上限なら丸ごと捨てる。 */
    private static final int MAX_CACHE_PER_DIM = 20_000;

    /** ワールドを閉じたらキャッシュを空にする(キーは次元だけなので、次に開くワールドへ前の値を返さない)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
        treeCache = new long[0];
        treeCacheTick = Long.MIN_VALUE;
    }

    /**
     * 胞子の場を読む次元か: オーバーワールド・エンド(場は無く、塗られた島を過繁茂の下支えで)・ネザー(ネザーの場が回っているとき)。
     * 場が設計データで止められていれば false(濃度 0)。
     */
    public static boolean useField(ServerLevel level) {
        if (!SporeFieldServer.enabled()) return false;
        return level.dimension() == Level.OVERWORLD || level.dimension() == Level.END
                || (level.dimension() == Level.NETHER && NetherSporeFieldServer.running());
    }

    /** {@code pos} の胞子濃度(%)。地点の補正込み。 */
    public static double percentAt(ServerLevel level, BlockPos pos) {
        if (!useField(level)) return 0.0D;
        // 地面より下で空の光 0 の所は地下の層の値を読む(2026-11-04 暴露量ノート 追記 6。前の深い洞窟 ×0.5 をやめた)
        boolean deep = level.dimension() == Level.OVERWORLD && undergroundAt(level, pos);
        double fx = pos.getX() / 16.0D - 0.5D, fz = pos.getZ() / 16.0D - 0.5D;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double v00 = chunkPercent(level, x0, z0, deep);
        double v10 = chunkPercent(level, x0 + 1, z0, deep);
        double v01 = chunkPercent(level, x0, z0 + 1, deep);
        double v11 = chunkPercent(level, x0 + 1, z0 + 1, deep);
        double v = (v00 * (1 - tx) + v10 * tx) * (1 - tz) + (v01 * (1 - tx) + v11 * tx) * tz;
        double out = (v + riftFloorPercent(level, pos)) * (deep ? 1.0D : pointFactor(level, pos));
        return highCut(level, pos, out) + sporePoolPercent(level, pos);
    }

    /**
     * 地下の層を読む所か(2026-11-04、仮決め): 空の光 0 で、葉を除いた地面より 2 段以上下、かつ「深さ 12 超」か「頭の上から地面までに塞ぐブロックが 3 つ以上」
     * (地面の下の洞窟。屋根 1〜2 枚の家の中は地上の値 × 閉じた部屋・屋根の下のまま)。
     */
    static boolean undergroundAt(ServerLevel level, BlockPos pos) {
        // 本当の地面(梢・幹をくぐる。過繁茂の葉は高さ地図に入るので、林床が「地面より下」に見えないように。2026-11-04)の 1 つ上 = 最初の空気
        int ground = com.yayuy.overgrownvegetation.spore.field.CaveMouths.groundTop(level, pos.getX(), pos.getZ()) + 1;
        int y = pos.getY();
        if (y >= ground - 1) return false;
        if (level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above()) != 0) return false;
        if (ground - y > 12) return true;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int solid = 0;
        for (int yy = y + 2; yy < ground && solid < 3; yy++) {
            m.set(pos.getX(), yy, pos.getZ());
            net.minecraft.world.level.block.state.BlockState s = level.getBlockState(m);
            if (s.isCollisionShapeFullBlock(level, m) && !com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder.isTreePart(s)) solid++;
        }
        return solid >= 3;
    }

    // ---------------------------------------------------------------- 高所(2026-11-04 追記 6)

    /**
     * 逆転層より上の切り(2026-11-04 暴露量ノート 追記 6【決定】): その日の逆転層から上 {@code high.fadeBlocks} ブロックで、地点の値を
     * {@code high.capPercent}(暴露を積む下限より下)以下へなめらかに寄せる。生きている巨大樹の根元から水平 {@code high.treeExemptChunks} チャンク以内は切らない。
     */
    static double highCut(ServerLevel level, BlockPos pos, double v) {
        if (level.dimension() != Level.OVERWORLD) return v;
        double inv = SporeFieldServer.inversionNow(level);
        if (!(pos.getY() > inv)) return v;
        SporeFieldTable t = DesignData.sporeField();
        if (nearLivingTree(level, pos, t.field().treeExemptChunks * 16.0D)) return v;
        return highCut(v, pos.getY(), inv, t.highFadeBlocks(), t.highCapPercent());
    }

    /** 高所の切りの式(素の数。テストから)。y ≤ inv はそのまま、inv + fade 以上は min(v, cap)、間は smoothstep で寄せる。 */
    public static double highCut(double v, double y, double inv, double fade, double cap) {
        if (y <= inv) return v;
        double s = Mth.smoothstep(Math.min(1.0D, (y - inv) / Math.max(1e-6, fade)));
        return v * (1.0D - s) + Math.min(v, cap) * s;
    }

    private static long[] treeCache = new long[0];
    private static long treeCacheTick = Long.MIN_VALUE;

    /** 生きている巨大樹の根元(ブロック座標)から水平 {@code radius} ブロック以内か。根元の一覧は tick ごとに覚える。 */
    static boolean nearLivingTree(ServerLevel level, BlockPos pos, double radius) {
        long now = level.getGameTime();
        if (now != treeCacheTick) {
            PhaseData pd = PhaseData.get(level);
            it.unimi.dsi.fastutil.longs.LongArrayList alive = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (long tr : pd.giantTreeOriginSnapshot()) if (!pd.isGiantTreeDead(tr)) alive.add(tr);
            treeCache = alive.toLongArray();
            treeCacheTick = now;
        }
        return nearTree(treeCache, pos.getX(), pos.getZ(), radius);
    }

    /** 根元の一覧({@code x << 32 | z} のブロック座標)のどれかから水平 radius ブロック以内か(素の数。テストから)。 */
    public static boolean nearTree(long[] origins, int x, int z, double radius) {
        double r2 = radius * radius;
        for (long tr : origins) {
            double dx = x - (int) (tr >> 32), dz = z - (int) tr;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /**
     * 洞窟の胞子の溜まり(2026-11-03 地下の植生 2・U6)の中にいる間の上乗せ(%、仮決め)。感染(プレイヤーの 10 段階)は 10-30 に parked へ退避中なので、
     * 今は濃度の読みが上がるだけ。感染を戻したら、この読みで進む。
     */
    public static final double SPORE_POOL_PERCENT = 80.0D;

    /** {@code pos} が胞子の溜まりの中なら {@link #SPORE_POOL_PERCENT}、外なら 0。 */
    public static double sporePoolPercent(ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && com.yayuy.overgrownvegetation.corruption.CaveFlora.inSporePool(level, pos)
                ? SPORE_POOL_PERCENT : 0.0D;
    }

    /** {@code chunk} の胞子濃度(%)。地点の補正なし。 */
    public static double percentAt(ServerLevel level, ChunkPos chunk) {
        return useField(level) ? chunkPercent(level, chunk.x, chunk.z, false) : 0.0D;
    }

    /** チャンクの地下の層の値(%、地点の補正なし。2026-11-04)。オーバーワールドだけ、写しが無ければ 0。 */
    public static double underPercentAt(ServerLevel level, ChunkPos chunk) {
        return useField(level) ? chunkPercent(level, chunk.x, chunk.z, true) : 0.0D;
    }

    /** チャンクの値(キャッシュ付き。場は 5 秒ごとに変わるので、その間だけ覚える)。{@code under} = 地下の層の値。 */
    private static double chunkPercent(ServerLevel level, int cx, int cz, boolean under) {
        long key = ChunkPos.asLong(cx, cz);
        long ttl = DesignData.sporeField().updateTicks();
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        long now = level.getGameTime();
        Cached cached = perDim.get(key);
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0 && age < ttl) return under ? cached.under() : cached.percent();
        }
        double percent = fieldBase(level, cx, cz);
        double underPercent = fieldUnder(level, cx, cz);
        if (perDim.size() >= MAX_CACHE_PER_DIM) {
            perDim.values().removeIf(c -> {
                long age = now - c.computedGameTime();
                return age < 0 || age >= ttl;
            });
            if (perDim.size() >= MAX_CACHE_PER_DIM) perDim.clear();
        }
        perDim.put(key, new Cached(percent, underPercent, now));
        return under ? underPercent : percent;
    }

    /**
     * チャンクの地下の層の値 = 場の地下の浮遊 ÷ fullAt(2026-11-04)。段階の下支えは地上だけ(地下には掛けない。仮決め)。
     * オーバーワールドだけ、写しが無い所(プレイヤーから遠い)は 0。
     */
    private static double fieldUnder(ServerLevel level, int cx, int cz) {
        if (level.dimension() != Level.OVERWORLD) return 0.0D;
        SporeFieldServer.Cell cell = SporeFieldServer.cell(cx, cz);
        return cell == null ? 0.0D : cell.under() / DesignData.sporeField().fullAt() * 100.0D;
    }

    /**
     * チャンクの濃度 = 場の浮遊 + 林床を「過繁茂のチャンクの晴れた午後 = 100%」で割った値(上限なし)。
     * 下支え: 汚染されたチャンクは段階ごとの下限より下がらない。写しが無い所(プレイヤーから遠い)は台帳の段階の下限だけ。
     */
    private static double fieldBase(ServerLevel level, int cx, int cz) {
        SporeFieldTable t = DesignData.sporeField();
        ChunkPos chunk = new ChunkPos(cx, cz);
        SporeFieldServer.Cell cell = SporeFieldServer.cell(level, cx, cz);
        int stage;
        double raw;
        if (level.dimension() == Level.END) {
            // エンドは場に乗せない: 過繁茂したエンドへ塗られたチャンクを「発芽済み・過繁茂」として下支えだけ(2026-10-23 F)
            stage = PortalCorruptionData.get(level.getServer()).isEndIslandDone(chunk.toLong()) ? 2 : 0;
            raw = 0.0D;
        } else if (level.dimension() == Level.NETHER && cell == null) {
            stage = NetherLedgerData.get(level).isOvergrown(chunk.toLong()) ? 2 : 0;
            raw = 0.0D;
        } else if (cell != null) {
            stage = cell.stage();
            raw = cell.conc() / t.fullAt() * 100.0D;
            // 台帳の段階も見る(2026-10-24 ユーザーの指摘: 発芽したばかりのチャンクは台帳・バイオームではもう過繁茂なのに、
            // 場では発芽から 2 日まで「繁茂」で下支えが 10% のまま、浮遊 8〜12% と読めていた)
            if (level.dimension() == Level.OVERWORLD && PhaseData.get(level).isOvergrownChunk(chunk)) {
                stage = Math.max(stage, 2);
            }
        } else {
            stage = PhaseData.get(level).isOvergrownChunk(chunk) ? 2 : 0;
            raw = 0.0D;
        }
        return Math.max(raw, t.floorFor(stage));
    }

    /**
     * 地割れの周りの下支え(%、2026-10-24 追記 19【決定】): 噴出の後も地割れの中心から rift.floorRadiusChunks の内側は
     * 濃度の読みに rift.floorPercent を足す。半径の半分までは満額、そこから縁へなだらかに 0。地割れが複数ならいちばん近いもの。
     * オーバーワールドだけ。
     */
    public static double riftFloorPercent(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return 0.0D;
        long[] rifts = EarthquakeData.riftCenters();
        if (rifts.length == 0) return 0.0D;
        SporeFieldTable t = DesignData.sporeField();
        double r = t.riftFloorRadius() * 16.0D;
        if (r <= 0.0D || t.riftFloorPercent() <= 0.0D) return 0.0D;
        double best = 0.0D;
        for (long c : rifts) {
            double dx = pos.getX() - BlockPos.getX(c), dz = pos.getZ() - BlockPos.getZ(c);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= r) continue;
            double f = d <= r * 0.5D ? 1.0D : 1.0D - Mth.smoothstep((d - r * 0.5D) / (r * 0.5D));
            best = Math.max(best, f);
        }
        return best * t.riftFloorPercent();
    }

    /**
     * 地点の補正(粗くてよい。【決定】閉じた部屋は厳密でなくてよい・屋根付きの廊下も閉じ扱いで可):
     * 梢より上は薄い / 空が少し見える地下(洞窟の口・窪地)は濃い / 空の光 0 で地面から浅い所は屋根の下(周り 4 方が塞がれば閉じた部屋)/
     * 深い洞窟 / 雨の最中 / 風上のすぐ隣が塞がっていれば壁の陰。
     */
    static double pointFactor(ServerLevel level, BlockPos pos) {
        SporeFieldTable t = DesignData.sporeField();
        if (level.dimension() == Level.NETHER) {
            // ネザー: 天井の岩盤の下はどこも「地表」扱い(高さ地図は天井を指すので使わない)。雨も風も無い。閉じた部屋だけ薄い(2026-10-23 F)
            return enclosed(level, pos.above()) && level.getBlockState(pos.above(2)).isCollisionShapeFullBlock(level, pos.above(2)) ? t.roomFactor() : 1.0D;
        }
        int x = pos.getX(), z = pos.getZ(), y = pos.getY();
        double f = 1.0D;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y > top + 2) {
            f *= Math.exp(-(y - top - 2) / t.aboveCanopyFalloff());
        } else if (y < ground - 1) {
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above());
            int depth = ground - y;
            if (sky == 0) {
                // 地面の下の洞窟は percentAt が地下の層を読む(2026-11-04)。ここへ来るのは家の中など浅い屋根の下
                f *= enclosed(level, pos.above()) ? t.roomFactor() : t.roofFactor();
            } else if (sky < 15) {
                f *= t.hollowFactor();
            }
        }
        if (level.isRainingAt(pos.above(2)) || (level.isRaining() && y >= ground - 1 && level.canSeeSky(pos.above()))) {
            f *= t.rainFactor();
        }
        PrevailingWind.Sample w = level.dimension() == Level.OVERWORLD ? SporeFieldServer.windNow(level) : null;
        if (w != null && w.strength > 0.3D) {
            BlockPos up = pos.offset(-(int) Math.round(w.dirX), 1, -(int) Math.round(w.dirZ));
            if (level.getBlockState(up).isCollisionShapeFullBlock(level, up)) {
                f *= t.upwindShadeFactor();
            }
        }
        return f;
    }

    /** 頭の高さで東西南北それぞれ 6 ブロック以内に塞ぐブロックがあるか。 */
    private static boolean enclosed(ServerLevel level, BlockPos head) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            boolean hit = false;
            for (int i = 1; i <= 6 && !hit; i++) {
                m.set(head.getX() + d.getStepX() * i, head.getY(), head.getZ() + d.getStepZ() * i);
                hit = level.getBlockState(m).isCollisionShapeFullBlock(level, m);
            }
            if (!hit) return false;
        }
        return true;
    }

    private SporeConcentration() {
    }
}
