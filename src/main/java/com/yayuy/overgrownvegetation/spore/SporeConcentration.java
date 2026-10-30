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

    private record Cached(double percent, long computedGameTime) {
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Cached>> CACHE = new HashMap<>();
    /** 1 次元あたりのキャッシュ上限。超えたら期限切れを捨て、それでも上限なら丸ごと捨てる。 */
    private static final int MAX_CACHE_PER_DIM = 20_000;

    /** ワールドを閉じたらキャッシュを空にする(キーは次元だけなので、次に開くワールドへ前の値を返さない)。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        CACHE.clear();
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
        double fx = pos.getX() / 16.0D - 0.5D, fz = pos.getZ() / 16.0D - 0.5D;
        int x0 = Mth.floor(fx), z0 = Mth.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double v00 = chunkPercent(level, x0, z0);
        double v10 = chunkPercent(level, x0 + 1, z0);
        double v01 = chunkPercent(level, x0, z0 + 1);
        double v11 = chunkPercent(level, x0 + 1, z0 + 1);
        double v = (v00 * (1 - tx) + v10 * tx) * (1 - tz) + (v01 * (1 - tx) + v11 * tx) * tz;
        return (v + riftFloorPercent(level, pos)) * pointFactor(level, pos);
    }

    /** {@code chunk} の胞子濃度(%)。地点の補正なし。 */
    public static double percentAt(ServerLevel level, ChunkPos chunk) {
        return useField(level) ? chunkPercent(level, chunk.x, chunk.z) : 0.0D;
    }

    /** チャンクの値(キャッシュ付き。場は 5 秒ごとに変わるので、その間だけ覚える)。 */
    private static double chunkPercent(ServerLevel level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        long ttl = DesignData.sporeField().updateTicks();
        Long2ObjectOpenHashMap<Cached> perDim = CACHE.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        long now = level.getGameTime();
        Cached cached = perDim.get(key);
        if (cached != null) {
            long age = now - cached.computedGameTime();
            if (age >= 0 && age < ttl) return cached.percent();
        }
        double percent = fieldBase(level, cx, cz);
        if (perDim.size() >= MAX_CACHE_PER_DIM) {
            perDim.values().removeIf(c -> {
                long age = now - c.computedGameTime();
                return age < 0 || age >= ttl;
            });
            if (perDim.size() >= MAX_CACHE_PER_DIM) perDim.clear();
        }
        perDim.put(key, new Cached(percent, now));
        return percent;
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
                f *= depth > 12 ? t.deepCaveFactor() : enclosed(level, pos.above()) ? t.roomFactor() : t.roofFactor();
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
