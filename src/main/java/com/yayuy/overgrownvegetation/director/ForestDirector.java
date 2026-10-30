package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.g1.G1Evolutions;
import com.yayuy.overgrownvegetation.g1.G1Life;
import com.yayuy.overgrownvegetation.g1.MoistDarkPlaces;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>森の知性(司令塔)</b> —— 世界に 1 つの「世界の意思」(2026-10-22 ユーザー決定。Mob ではなくワールドの保存データ)。
 * 仕様の正本は Vault「森の知性(司令塔)」と「G1 媒介種(寄生された動物)」の【案】。
 *
 * <p>今持つ判断は <b>G1 の分岐だけ</b>(根付き / 苗床 / 進化。個体に 1 回、サーバースレッドで軽く、経路探索はしない)。
 * 点 = 欲求への効き ÷ (1 + 払うバイオマス)、それに ±{@code jitter}(20%)の揺らぎを掛けて、いちばん高い道を選ぶ。
 * 値段は今は 0(ユーザーが後で決める。g1.json の director.cost* が枠)なので、バイオマスはまだ減らさない。
 *
 * <p>入力(G1 のノートの【案】の表):
 * <ul>
 *     <li>根付き … この升の降り積もりが薄い({@code SporeField#deposited})・森の前線(台帳の過繁茂チャンク)から近〜中・人が少ない</li>
 *     <li>苗床 … 近くに湿った暗い場所が<b>要る</b>({@link MoistDarkPlaces})・前線から遠い・人が少ない</li>
 *     <li>進化 … 近くにプレイヤー・ここ(周り 3×3 チャンク)で倒された G1 が多い。進化体のある種だけ({@link G1Evolutions})</li>
 *     <li>元の種の性向 … g1.json の speciesBias(ゾンビ・村人・スケルトン・クリーパー = 進化寄り、牛・豚・羊 = 苗床寄り、小型 = 根付き寄り)</li>
 * </ul>
 * プレイヤーの武器・設備・拠点の記憶の本体は後(今はプレイヤーとの距離と倒された G1 の数だけ)。
 */
public final class ForestDirector extends SavedData {

    private static final String ID = "overgrown_vegetation_forest_director";

    public static final SavedData.Factory<ForestDirector> FACTORY =
            new SavedData.Factory<>(ForestDirector::new, (tag, registries) -> load(tag), null);

    /** チャンク → [倒された数(減衰込み), 最後に数えたゲーム時刻]。 */
    private final Long2ObjectOpenHashMap<double[]> kills = new Long2ObjectOpenHashMap<>();
    /** これまでの判断の数(根付き・苗床・進化)。 */
    private final long[] decided = new long[3];
    /** 直近の判断(保存しない。/ov perf g1 で見る)。 */
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    private ForestDirector() {
    }

    public static ForestDirector get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // ---------------------------------------------------------------- 判断

    /** 1 つの判断の入力と点(テスト・コマンドで見る)。 */
    public record Decision(G1Life.Route route, BlockPos nurseryTarget, double rooted, double nursery, double evolve,
                           double deposit, int frontChunks, double playerDistance, double kills, String summary) {
    }

    /** G1 の 1 体の分岐を決める。乱数は Mob のもの。 */
    public Decision decide(ServerLevel level, Mob mob) {
        G1Table g = DesignData.g1();
        BlockPos pos = mob.blockPosition();
        ChunkPos chunk = new ChunkPos(pos);

        SporeFieldServer.Cell cell = SporeFieldServer.cell(level, chunk.x, chunk.z);
        double deposit = cell != null ? cell.dep() : 0.0D;
        int front = frontDistance(level, chunk, g.frontSearchChunks);
        Player nearest = level.getNearestPlayer(mob.getX(), mob.getY(), mob.getZ(), -1.0D,
                p -> !p.isSpectator() && !((Player) p).isCreative());
        double playerDist = nearest != null ? nearest.distanceTo(mob) : Double.MAX_VALUE;
        double killCount = killsNear(level, chunk);
        BlockPos moist = MoistDarkPlaces.find(level, pos, g.nurserySearchRadius, g, mob.getRandom());
        double[] bias = g.biasFor(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
        boolean canEvolve = G1Evolutions.next(mob.getType()) != null;

        double fewPeople = Math.min(1.0D, playerDist / g.fewPeopleBlocks);
        double frontNear = front <= g.frontNearChunks ? 1.0D : front <= g.frontFarChunks ? 0.5D : 0.0D;

        double rooted = g.rootedBase
                + g.rootedThinDeposit * (1.0D - Math.min(1.0D, deposit / g.depositRef))
                + g.rootedFrontNear * frontNear
                + g.rootedFewPeople * fewPeople
                + bias[0];
        double nursery = moist == null ? Double.NEGATIVE_INFINITY
                : g.nurseryBase
                + g.nurseryFarFront * Math.min(1.0D, front / (double) g.frontFarChunks)
                + g.nurseryFewPeople * fewPeople
                + bias[1];
        double evolve = !canEvolve ? Double.NEGATIVE_INFINITY
                : g.evolveBase
                + g.evolvePlayerNear * Math.max(0.0D, 1.0D - playerDist / g.playerNearBlocks)
                + g.evolveKills * Math.min(1.0D, killCount / g.killsRef)
                + bias[2];

        rooted = jitter(rooted / (1.0D + g.costRooted), g, mob);
        nursery = jitter(nursery / (1.0D + g.costNursery), g, mob);
        evolve = jitter(evolve / (1.0D + g.costEvolve1), g, mob);

        G1Life.Route route = G1Life.Route.ROOTED;
        double best = rooted;
        if (nursery > best) {
            route = G1Life.Route.NURSERY;
            best = nursery;
        }
        if (evolve > best) {
            route = G1Life.Route.EVOLVE;
        }
        String summary = String.format("%s → %s(根 %.2f / 苗 %s / 進 %s。降り積もり %.3f・前線 %s チャンク・人 %s・倒された %.1f)",
                BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath(), routeName(route), rooted, fmt(nursery), fmt(evolve),
                deposit, front > g.frontSearchChunks ? ">" + g.frontSearchChunks : Integer.toString(front),
                playerDist == Double.MAX_VALUE ? "なし" : String.format("%.0f", playerDist), killCount);
        decided[route.ordinal() - 1]++;
        recent.addFirst(summary);
        while (recent.size() > 8) recent.removeLast();
        setDirty();
        return new Decision(route, route == G1Life.Route.NURSERY ? moist : null, rooted, nursery, evolve,
                deposit, front, playerDist, killCount, summary);
    }

    private static double jitter(double v, G1Table g, Mob mob) {
        if (!Double.isFinite(v)) return v;
        return v * (1.0D + g.jitter * (mob.getRandom().nextDouble() * 2.0D - 1.0D));
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format("%.2f", v) : "-";
    }

    public static String routeName(G1Life.Route route) {
        return switch (route) {
            case NONE -> "未定";
            case ROOTED -> "根付き";
            case NURSERY -> "苗床";
            case EVOLVE -> "進化";
        };
    }

    /** いちばん近い台帳の過繁茂チャンクまでの距離(チャンク、チェビシェフ)。{@code max} までに無ければ max + 1。 */
    public static int frontDistance(ServerLevel level, ChunkPos center, int max) {
        PhaseData phase = PhaseData.get(level);
        for (int r = 0; r <= max; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (phase.isOvergrownChunk(new ChunkPos(center.x + dx, center.z + dz))) return r;
                }
            }
        }
        return max + 1;
    }

    // ---------------------------------------------------------------- 倒された G1 の記憶

    /** このチャンクで G1 が 1 体倒された。 */
    public void recordKill(ServerLevel level, ChunkPos chunk) {
        double[] k = kills.computeIfAbsent(chunk.toLong(), key -> new double[]{0.0D, level.getGameTime()});
        k[0] = decayed(k, level.getGameTime()) + 1.0D;
        k[1] = level.getGameTime();
        setDirty();
    }

    /** 周り 3×3 チャンクで倒された G1 の数(半減期 killsHalfLifeDays で減る)。 */
    public double killsNear(ServerLevel level, ChunkPos chunk) {
        double sum = 0.0D;
        long now = level.getGameTime();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                double[] k = kills.get(ChunkPos.asLong(chunk.x + dx, chunk.z + dz));
                if (k != null) sum += decayed(k, now);
            }
        return sum;
    }

    private static double decayed(double[] k, long now) {
        double days = Math.max(0L, now - (long) k[1]) / 24000.0D;
        return k[0] * Math.pow(0.5D, days / DesignData.g1().killsHalfLifeDays);
    }

    // ---------------------------------------------------------------- 見る口

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add(String.format("司令塔: これまでの判断 根付き %d・苗床 %d・進化 %d / 倒された G1 を覚えているチャンク %d",
                decided[0], decided[1], decided[2], kills.size()));
        out.addAll(recent);
        return out;
    }

    public long decidedCount(G1Life.Route route) {
        return route == G1Life.Route.NONE ? 0 : decided[route.ordinal() - 1];
    }

    // ---------------------------------------------------------------- 保存

    private static ForestDirector load(CompoundTag tag) {
        ForestDirector d = new ForestDirector();
        long[] dec = tag.getLongArray("Decided");
        System.arraycopy(dec, 0, d.decided, 0, Math.min(dec.length, 3));
        ListTag list = tag.getList("Kills", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag k = list.getCompound(i);
            d.kills.put(k.getLong("C"), new double[]{k.getDouble("N"), k.getLong("T")});
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("Decided", decided);
        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<double[]> e : kills.long2ObjectEntrySet()) {
            if (e.getValue()[0] < 0.05D) continue;
            CompoundTag k = new CompoundTag();
            k.putLong("C", e.getLongKey());
            k.putDouble("N", e.getValue()[0]);
            k.putLong("T", (long) e.getValue()[1]);
            list.add(k);
        }
        tag.put("Kills", list);
        return tag;
    }
}
