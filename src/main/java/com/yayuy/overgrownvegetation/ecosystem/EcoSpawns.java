package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import com.yayuy.overgrownvegetation.spawn.SpawnTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 汚染地帯の内側の湧き(2026-11-16)。縁の湧き({@link EnemySpawns})とは別の口で、縁の見分けは緩めない。
 *
 * <h2>内側の守り(G4・G5)</h2>
 * 「汚染地帯の内側には G2・G3 が住む。殺しすぎると、だんだん内側にも G4 以降が湧く」(11-15 ユーザー 決定 3)。
 * <ul>
 *   <li>多様性がしきい値(70%【仮】)以上なら 0 体(森は自分を守らない)。下回ると (しきい値 − 健康) ÷ しきい値 に比例して、
 *   最大 G4 8・G5 4【仮】× フェーズの段の強さ(縁と同じ {@link EnemySpawns#strength}。G4 F4・G5 F5 から)。</li>
 *   <li>場所は<b>減った升を優先</b>(「守りの G4〜6 もその升へ」)。プレイヤーから 24〜96 ブロックの減った升を欠けの重みで選ぶ。
 *   無ければまわりの森。どちらも「台帳に載っていて縁でない」チャンクだけ。</li>
 *   <li>予算は払わない(全体管理 11-16 決定 B。縁と同じく数はフェーズの段だけ)。G6 は種が無いので G4・G5 だけ。</li>
 *   <li>印 {@link #INNER_TAG}。どのプレイヤーからも 128 ブロックより遠くなったら消す。司令塔が歩かせ始めたら印を外す。</li>
 * </ul>
 *
 * <h2>仮の G2・G3(開発用のスイッチがあるときだけ)</h2>
 * G2・G3 の種がそろうまで、{@code stub} の種(素のカエル・ヤマネコ【仮】)に印を付けて内側に湧かせる。
 * <b>{@code -Dov.devbridge}(Claude のクライアント)か GameTest のときだけ</b>(全体管理 11-16 決定 D: ふつうに遊ぶと紛らわしく、設定にも合わない)。
 * 升の頭数 ÷ 満員 の割合で湧きやすさが下がる(倒した升には湧きにくい)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EcoSpawns {

    public static final String INNER_TAG = "ov_inner_spawn";
    public static final String INNER_GROUP_TAG = "ov_inner_g";
    public static final String STUB_TAG = "ov_eco_stub";

    private static final boolean DEV = Boolean.getBoolean("ov.devbridge");
    private static final int SWEEP_INTERVAL = 100;

    /** GameTest のサーバーでも tick で湧かせるか(既定は止める。テストは口を直に呼ぶ)。 */
    public static boolean tickInGameTests = false;

    public static long spawnedInner, spawnedStub;

    private EcoSpawns() {
    }

    public enum Miss {
        SPAWNED("湧いた"), HEALTHY("森が健康"), NOT_INSIDE("内側でない"), FULL("上限・開いていない"), NO_SPOT("置き場が無い"),
        NEAR_PLAYER("プレイヤーに近い"), NOT_TICKING("動いていない"), BLOCKED("体がはまる"), NO_TYPE("種が無い"),
        THIN("升の頭数が少ない"), NOT_DEV("開発用のスイッチが無い");

        public final String label;

        Miss(String label) {
            this.label = label;
        }
    }

    public static final long[] stats = new long[Miss.values().length];

    /** 仮の種を湧かせてよい環境か(開発用の操作口か GameTest のサーバー)。 */
    public static boolean stubAllowed(ServerLevel level) {
        return DEV || level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer;
    }

    /** そのチャンクは内側か(台帳に載っていて、縁でない)。 */
    public static boolean inside(PhaseData data, int cx, int cz) {
        return data.isOvergrownKey(ChunkPos.asLong(cx, cz)) && !EnemySpawns.isEdgeChunk(data, cx, cz);
    }

    // ---------------------------------------------------------------- 上限

    /** 守りの強さの割合(0〜1)= (しきい値 − 健康) ÷ しきい値。しきい値以上で 0。 */
    public static double defenseFactor(double health, double threshold) {
        if (threshold <= 0.0D || health >= threshold) return 0.0D;
        return Math.min(1.0D, (threshold - health) / threshold);
    }

    /** 内側の守りの、そのグループ(4・5)の今の上限。 */
    public static int innerCap(ServerLevel level, int group) {
        EcoTable t = DesignData.ecosystem();
        double f = defenseFactor(Ecosystem.health(level), t.innerThreshold);
        if (f <= 0.0D) return 0;
        double s = EnemySpawns.strength(level, group);
        int base = group == 5 ? t.innerCapG5 : group == 4 ? t.innerCapG4 : 0;
        return s <= 0.0D ? 0 : (int) Math.round(base * f * s);
    }

    /** 仮の種の上限(健康に比例)。 */
    public static int stubCap(ServerLevel level, int group) {
        EcoTable t = DesignData.ecosystem();
        return (int) Math.round((group == 3 ? t.stubCapG3 : t.stubCapG2) * Ecosystem.health(level));
    }

    // ---------------------------------------------------------------- 数える・片付け

    private static final Set<Mob> TRACKED = Collections.newSetFromMap(new WeakHashMap<>());

    public static boolean ours(Mob m) {
        return m.getTags().contains(INNER_TAG) || m.getTags().contains(STUB_TAG);
    }

    /** まわりの数: [4]・[5] = 内側の守り、[2]・[3] = 仮の G2・G3。 */
    public static int[] count(ServerLevel level, double x, double z, double r) {
        int[] out = new int[6];
        for (Mob m : TRACKED) {
            if (!m.isAlive() || m.level() != level || !ours(m)) continue;
            if (Math.abs(m.getX() - x) > r || Math.abs(m.getZ() - z) > r) continue;
            if (m.getTags().contains(STUB_TAG)) {
                int g = Ecosystem.residentGroup(m);
                if (g == 2 || g == 3) out[g]++;
            } else {
                if (m.getTags().contains(INNER_GROUP_TAG + 5)) out[5]++;
                else out[4]++;
            }
        }
        return out;
    }

    /** 遠くなった個体を消す。司令塔が歩かせ始めた守りは印を外して手放す。消した数を返す。 */
    public static int sweep(ServerLevel level) {
        double far = DesignData.ecosystem().innerDespawn;
        int n = 0;
        for (Mob m : List.copyOf(TRACKED)) {
            if (m.isRemoved() || !ours(m)) {
                TRACKED.remove(m);
                continue;
            }
            if (m.level() != level) continue;
            if (PlantMarch.active(m)) {
                m.removeTag(INNER_TAG);
                TRACKED.remove(m);
                continue;
            }
            if (m.hasCustomName() || m.isPassenger() || m.isVehicle() || m.isLeashed()) continue;
            boolean near = false;
            for (ServerPlayer p : level.players()) {
                if (!p.isSpectator() && p.distanceToSqr(m) < far * far) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                m.discard();
                TRACKED.remove(m);
                n++;
            }
        }
        return n;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob m && ours(m)) TRACKED.add(m);
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tickInGameTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        EcoTable t = DesignData.ecosystem();
        if (!t.enabled) return;
        long time = level.getGameTime();
        if (time % SWEEP_INTERVAL == 0) sweep(level);
        boolean inner = time % t.innerInterval == 0, stub = DEV && time % t.stubInterval == 0;
        if (!inner && !stub || !EnemySpawns.allowed(level)) return;
        RandomSource random = level.getRandom();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (inner) {
                for (int i = 0; i < t.innerTries; i++) stats[tryInner(level, p.getX(), p.getZ(), random).ordinal()]++;
            }
            if (stub) {
                for (int i = 0; i < t.stubTries; i++) stats[tryStub(level, p.getX(), p.getZ(), random).ordinal()]++;
            }
        }
    }

    // ---------------------------------------------------------------- 内側の守り

    /** プレイヤーのまわりで内側の守りを 1 回試す。 */
    public static Miss tryInner(ServerLevel level, double px, double pz, RandomSource random) {
        EcoTable t = DesignData.ecosystem();
        if (defenseFactor(Ecosystem.health(level), t.innerThreshold) <= 0.0D) return Miss.HEALTHY;
        int[] counts = count(level, px, pz, t.innerCountRadius);
        int cap4 = innerCap(level, 4), cap5 = innerCap(level, 5);
        if (counts[4] >= cap4 && counts[5] >= cap5) return Miss.FULL;
        long chunk = pickChunk(level, px, pz, t.innerMin, t.innerMax, random);
        if (!inside(PhaseData.get(level), ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
            // 選んだ減った升のチャンクが内側でない(森の外で倒した・縁だった)ときは、まわりの森へ落とす
            double ang = random.nextDouble() * Math.PI * 2.0D, d = t.innerMin + random.nextDouble() * (t.innerMax - t.innerMin);
            chunk = ChunkPos.asLong(Mth.floor(px + Math.cos(ang) * d) >> 4, Mth.floor(pz + Math.sin(ang) * d) >> 4);
        }
        return tryInnerAt(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), counts, cap4, cap5, random);
    }

    /** そのチャンクに内側の守りを 1 回試す(GameTest も直に呼ぶ)。 */
    public static Miss tryInnerAt(ServerLevel level, int cx, int cz, int[] counts, int cap4, int cap5, RandomSource random) {
        if (!inside(PhaseData.get(level), cx, cz)) return Miss.NOT_INSIDE;
        SpawnTable.Species sp = pickDefender(level, counts, cap4, cap5, random);
        if (sp == null) return Miss.FULL;
        EntityType<?> type = sp.type();
        if (type == null) return Miss.NO_TYPE;
        List<SpawnTable.Habitat> hs = new ArrayList<>(sp.habitats());
        SpawnTable.Habitat h = hs.get(random.nextInt(hs.size()));
        BlockPos at = EnemySpawns.spot(level, h, cx, cz, DesignData.spawns().caveMinDepth, random);
        if (at == null) return Miss.NO_SPOT;
        Miss m = place(level, type, at, DesignData.ecosystem().innerMin, INNER_TAG, INNER_GROUP_TAG + sp.group());
        if (m == Miss.SPAWNED) {
            counts[sp.group()]++;
            spawnedInner++;
        }
        return m;
    }

    @Nullable
    private static SpawnTable.Species pickDefender(ServerLevel level, int[] counts, int cap4, int cap5, RandomSource random) {
        List<SpawnTable.Species> cands = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        double total = 0.0D;
        double s4 = -1.0D, s5 = -1.0D;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            int g = sp.group();
            if (g == 4 ? counts[4] >= cap4 : g == 5 ? counts[5] >= cap5 : true) continue;
            if (sp.type() == null) continue;
            double s;
            if (g == 4) s = s4 < 0.0D ? (s4 = EnemySpawns.strength(level, 4)) : s4;
            else s = s5 < 0.0D ? (s5 = EnemySpawns.strength(level, 5)) : s5;
            double w = sp.weight() * s;
            if (w <= 0.0D) continue;
            cands.add(sp);
            ws.add(w);
            total += w;
        }
        if (cands.isEmpty()) return null;
        double r = random.nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) {
            r -= ws.get(i);
            if (r <= 0.0D) return cands.get(i);
        }
        return cands.get(cands.size() - 1);
    }

    /**
     * 湧かせるチャンクを選ぶ: プレイヤーから min〜max ブロックの減った升を、欠けの重みで 1 つ(その中のチャンクを 1 つ)。
     * 無ければ、プレイヤーからその距離のチャンクを 1 つ引く。
     */
    public static long pickChunk(ServerLevel level, double px, double pz, double min, double max, RandomSource random) {
        EcoTable t = DesignData.ecosystem();
        int shift = t.cellShift, size = 1 << shift;
        double half = size * 8.0D, reach = max + half * 1.5D;
        EcoLedger ledger = EcoLedger.get(level);
        long now = EcoLedger.now(level);
        long[] pick = {Long.MIN_VALUE};
        double[] acc = {0.0D};
        ledger.forEachDamaged(now, key -> {
            double cx = (ChunkPos.getX(key) << (shift + 4)) + half, cz = (ChunkPos.getZ(key) << (shift + 4)) + half;
            return Math.abs(cx - px) <= reach && Math.abs(cz - pz) <= reach;
        }, (key, w) -> {
            // 重み付きの 1 つ選び(貯水池法)
            acc[0] += w;
            if (random.nextDouble() * acc[0] < w) pick[0] = key;
        });
        if (pick[0] != Long.MIN_VALUE) {
            int x = (ChunkPos.getX(pick[0]) << shift) + random.nextInt(size), z = (ChunkPos.getZ(pick[0]) << shift) + random.nextInt(size);
            double dx = (x << 4) + 8 - px, dz = (z << 4) + 8 - pz, d2 = dx * dx + dz * dz;
            if (d2 >= min * min && d2 <= reach * reach) return ChunkPos.asLong(x, z);
        }
        double ang = random.nextDouble() * Math.PI * 2.0D;
        double d = min + random.nextDouble() * (max - min);
        return ChunkPos.asLong(Mth.floor(px + Math.cos(ang) * d) >> 4, Mth.floor(pz + Math.sin(ang) * d) >> 4);
    }

    // ---------------------------------------------------------------- 仮の G2・G3

    /** プレイヤーのまわりで仮の G2・G3 を 1 回試す(開発用のスイッチがあるときだけ)。 */
    public static Miss tryStub(ServerLevel level, double px, double pz, RandomSource random) {
        if (!stubAllowed(level)) return Miss.NOT_DEV;
        EcoTable t = DesignData.ecosystem();
        double ang = random.nextDouble() * Math.PI * 2.0D;
        double d = t.stubMin + random.nextDouble() * (t.stubMax - t.stubMin);
        int cx = Mth.floor(px + Math.cos(ang) * d) >> 4, cz = Mth.floor(pz + Math.sin(ang) * d) >> 4;
        return tryStubAt(level, cx, cz, count(level, px, pz, t.innerCountRadius), random);
    }

    /** そのチャンクに仮の G2・G3 を 1 回試す。升の頭数 ÷ 満員 の確率で湧く。 */
    public static Miss tryStubAt(ServerLevel level, int cx, int cz, int[] counts, RandomSource random) {
        if (!stubAllowed(level)) return Miss.NOT_DEV;
        if (!inside(PhaseData.get(level), cx, cz)) return Miss.NOT_INSIDE;
        EcoTable t = DesignData.ecosystem();
        int g = random.nextInt(t.fullG2 + t.fullG3) < t.fullG2 ? 2 : 3;
        if (counts[g] >= stubCap(level, g)) {
            g = 5 - g;
            if (counts[g] >= stubCap(level, g)) return Miss.FULL;
        }
        double ratio = EcoLedger.get(level).heads(g, cx, cz, EcoLedger.now(level)) / t.full(g);
        if (random.nextDouble() >= ratio) return Miss.THIN;
        List<EntityType<?>> types = t.stubTypes(g);
        if (types.isEmpty()) return Miss.NO_TYPE;
        EntityType<?> type = types.get(random.nextInt(types.size()));
        BlockPos at = EnemySpawns.spot(level, SpawnTable.Habitat.GROUND, cx, cz, 0, random);
        if (at == null) return Miss.NO_SPOT;
        Miss m = place(level, type, at, t.stubMin, STUB_TAG, g == 3 ? Ecosystem.STUB_TAG_G3 : Ecosystem.STUB_TAG_G2);
        if (m == Miss.SPAWNED) {
            counts[g]++;
            spawnedStub++;
        }
        return m;
    }

    // ---------------------------------------------------------------- 置く

    private static Miss place(ServerLevel level, EntityType<?> type, BlockPos at, double minFromPlayer, String tag, String tag2) {
        if (!level.isPositionEntityTicking(at)) return Miss.NOT_TICKING;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) < minFromPlayer * minFromPlayer) return Miss.NEAR_PLAYER;
        }
        Mob m = EnemySpawns.create(level, type, at, MobSpawnType.NATURAL, level.getRandom());
        if (m == null) return Miss.BLOCKED;
        m.addTag(tag);
        m.addTag(tag2);
        level.addFreshEntity(m);
        TRACKED.add(m);
        return Miss.SPAWNED;
    }
}
