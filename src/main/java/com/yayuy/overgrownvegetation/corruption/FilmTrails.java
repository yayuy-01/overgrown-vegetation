package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.FloatingFilmBlock;
import com.yayuy.overgrownvegetation.network.FilmTrailPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 水面の膜を通った跡の記録(サーバー。2026-11-13、ユーザー「水草が大量に浮いている所を人や動物が通ったとき、軌跡が少しだけ残る」)。
 * <p>
 * ユーザーが決めたこと: 見た目は筋の中にも草が少し残る薄い筋(見本 B)・約 1 分で閉じる・使い道は
 * 「水中の Mob や生き物が通った跡が見える」「筋を見て追ってくる Mob」。追う Mob の判断はサーバーで走るので、跡をここに記録する。
 * <p>
 * 膜(藻の膜・胞子の膜・浮き藻の帯)を通った生き物とボートの位置を、{@value #STEP} ブロック動くごとに点として残す(膜の板の下
 * {@value #UNDER} ブロックまでを泳ぐものも)。点は {@value #LIFETIME} tick で消える。新しい点はその場所を見ているプレイヤーへ送り、
 * クライアントはそれで膜を抜いて描く({@code client.atmosphere.FilmWakes})。見えている筋と、Mob が読む筋は同じもの。
 * 浮く落ち葉の跡はクライアントだけの見た目で、ここには入らない(Mob は追わない)。数値は 1 分(ユーザー指定)のほかは仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FilmTrails {

    /** 跡が残る長さ(tick)。ユーザー指定 約 1 分。 */
    public static final int LIFETIME = 1200;
    /** 点を残す間隔(ブロック)。 */
    static final double STEP = 0.4;
    /** 止まっていても点を足し直す間隔(tick。その場の跡が閉じないように)。 */
    static final int REFRESH = 40;
    /** 生き物を見る間隔(tick)。 */
    static final int SCAN = 2;
    /** 膜の板の下この深さまでを泳ぐものも膜を割る。深いほど細い筋。 */
    public static final double UNDER = 1.5;
    /** 膜の板からこの高さより下に足があれば割る(跳び越えたものは割らない)。 */
    public static final double OVER = 0.3;
    /** 膜の板の高さ(ブロックの底から)。 */
    public static final double PLANE = 0.25 / 16.0;

    /** 跡の 1 点。{@code width} は筋の半分の幅(ブロック)、{@code strength} は 0〜1(深く潜ったものほど弱い)。 */
    public record Point(double x, double y, double z, long time, float width, float strength, int owner, boolean ownerIsPlayer) {
        public Vec3 position() {
            return new Vec3(x, y, z);
        }
    }

    private static final class Data {
        final Long2ObjectOpenHashMap<List<Point>> byChunk = new Long2ObjectOpenHashMap<>();
        final Int2ObjectOpenHashMap<Point> last = new Int2ObjectOpenHashMap<>();
        int count;
    }

    private static final Map<ServerLevel, Data> LEVELS = new WeakHashMap<>();

    private FilmTrails() {
    }

    private static Data data(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, l -> new Data());
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (now % SCAN == 0) {
            scan(level, now);
        }
        if (now % 100 == 0) {
            expire(level, now);
        }
    }

    /** 膜を割るもの: 水に入った生き物(観戦者・防具立ては除く)とボート。 */
    public static boolean wakes(Entity entity) {
        if (entity.isRemoved()) {
            return false;
        }
        if (entity instanceof Boat) {
            return true;
        }
        if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand || !entity.isInWater()) {
            return false;
        }
        return !(entity instanceof Player player) || !player.isSpectator();
    }

    private static void scan(ServerLevel level, long now) {
        Data d = data(level);
        Map<Long, List<Point>> fresh = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Entity entity : level.getAllEntities()) {
            if (!wakes(entity)) {
                continue;
            }
            double plane = filmPlane(level, entity.getBoundingBox(), pos);
            if (Double.isNaN(plane)) {
                continue;
            }
            Point prev = d.last.get(entity.getId());
            double x = entity.getX();
            double z = entity.getZ();
            if (prev != null && now - prev.time < REFRESH && Mth.lengthSquared(x - prev.x, z - prev.z) < STEP * STEP) {
                continue;
            }
            // 深く潜っているほど細く弱い筋(板の下 UNDER で半分)
            double depth = Math.max(0.0, plane - entity.getBoundingBox().maxY);
            float strength = (float) (1.0 - 0.5 * Math.min(1.0, depth / UNDER));
            float width = (float) Mth.clamp(entity.getBbWidth() * 0.5 + 0.15, 0.25, 1.2) * (0.6F + 0.4F * strength);
            Point p = new Point(x, plane, z, now, width, strength, entity.getId(), entity instanceof Player);
            record(level, p);
            if (fresh == null) {
                fresh = new java.util.HashMap<>();
            }
            fresh.computeIfAbsent(ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4), k -> new ArrayList<>()).add(p);
        }
        if (fresh != null) {
            for (Map.Entry<Long, List<Point>> e : fresh.entrySet()) {
                PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(e.getKey()), FilmTrailPayload.of(e.getValue()));
            }
        }
    }

    /** 箱 {@code box} が膜の板に触れていればその板の高さ(ワールドの y)、触れていなければ NaN。 */
    public static double filmPlane(ServerLevel level, AABB box, BlockPos.MutableBlockPos pos) {
        int x0 = Mth.floor(box.minX);
        int x1 = Mth.floor(box.maxX);
        int z0 = Mth.floor(box.minZ);
        int z1 = Mth.floor(box.maxZ);
        int y0 = Mth.floor(box.minY - OVER);
        int y1 = Mth.floor(box.maxY + UNDER);
        for (int y = y0; y <= y1; y++) {
            double plane = y + PLANE;
            if (!(box.maxY > plane - UNDER && box.minY < plane + OVER)) {
                continue;
            }
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (level.getBlockState(pos.set(x, y, z)).getBlock() instanceof FloatingFilmBlock) {
                        return plane;
                    }
                }
            }
        }
        return Double.NaN;
    }

    /** 点を足す(GameTest・ギミックから直に足すときも)。送信はしない。 */
    public static void record(ServerLevel level, Point p) {
        Data d = data(level);
        d.byChunk.computeIfAbsent(ChunkPos.asLong(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4), k -> new ArrayList<>()).add(p);
        d.last.put(p.owner, p);
        d.count++;
    }

    private static void expire(ServerLevel level, long now) {
        Data d = LEVELS.get(level);
        if (d == null) {
            return;
        }
        var it = d.byChunk.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<List<Point>> e = it.next();
            List<Point> list = e.getValue();
            int before = list.size();
            list.removeIf(p -> now - p.time > LIFETIME);
            d.count -= before - list.size();
            if (list.isEmpty()) {
                it.remove();
            }
        }
        d.last.values().removeIf(p -> now - p.time > LIFETIME);
    }

    /** {@code center} から水平 {@code radius} 以内の、{@code maxAge} tick より新しい点({@code filter} を通るもの)。 */
    public static List<Point> near(ServerLevel level, Vec3 center, double radius, int maxAge, Predicate<Point> filter) {
        List<Point> out = new ArrayList<>();
        Data d = LEVELS.get(level);
        if (d == null) {
            return out;
        }
        long now = level.getGameTime();
        int cx0 = Mth.floor(center.x - radius) >> 4;
        int cx1 = Mth.floor(center.x + radius) >> 4;
        int cz0 = Mth.floor(center.z - radius) >> 4;
        int cz1 = Mth.floor(center.z + radius) >> 4;
        double r2 = radius * radius;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<Point> list = d.byChunk.get(ChunkPos.asLong(cx, cz));
                if (list == null) {
                    continue;
                }
                for (Point p : list) {
                    if (now - p.time <= maxAge && Mth.lengthSquared(p.x - center.x, p.z - center.z) <= r2 && filter.test(p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** {@code near} のうち一番新しい点。 */
    @Nullable
    public static Point freshestNear(ServerLevel level, Vec3 center, double radius, int maxAge, Predicate<Point> filter) {
        Point best = null;
        for (Point p : near(level, center, radius, maxAge, filter)) {
            if (best == null || p.time > best.time) {
                best = p;
            }
        }
        return best;
    }

    /** 記録している点の数(開発用)。 */
    public static int count(ServerLevel level) {
        Data d = LEVELS.get(level);
        return d == null ? 0 : d.count;
    }
}
