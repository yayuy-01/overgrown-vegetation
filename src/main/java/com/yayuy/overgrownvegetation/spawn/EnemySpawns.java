package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseStages;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 敵の湧き方: 汚染地帯の縁と、その真下の洞窟(2026-11-15 ユーザー決定。正本は Vault「敵の湧き方(縁・地下・司令塔)(11-15)」)。
 *
 * <ul>
 *   <li><b>縁</b> = 過繁茂の台帳({@link PhaseData#isOvergrownKey})に載ったチャンクと載っていないチャンクの境目を挟んで、内側 1・外側 1。
 *   あるチャンクが縁かは、そのチャンクと隣 8 つの台帳を引くだけで分かる({@link #isEdgeChunk})。プレイヤーのまわりから引いたチャンクだけを
 *   調べるので、過繁茂が数百万チャンクあっても重さは変わらない。</li>
 *   <li>湧くのは G1(段階 0)・G4・G5。開くフェーズは {@code spawns.json} の {@code openAt}(G1 F1・G4 F4・G5 F5【仮】)。</li>
 *   <li>数はフェーズの小さな段の強さだけで決める(森の予算は払わない)。上限はグループごとに 基本の数 × 強さ(最盛で G1 15・G4 15・G5 10【仮】)。</li>
 *   <li>昼も夜も同じ。明るさでは止まらない。ピースフル・{@code doMobSpawning} が切れているとき・大きな襲撃の前触れの間は湧かない。</li>
 *   <li>種は住む場所で分ける({@link SpawnTable.Habitat})。</li>
 *   <li><b>地下</b>: 過繁茂の真下と、そこから {@code nearChunks} チャンクまでの洞窟に G1 の段階 0。上限は縁とは別。</li>
 *   <li>片付け: 縁・地下で湧いた個体は、どのプレイヤーからも {@code despawnBlocks} より遠くなったら消す(空の種は遠くでも消えない作りのため)。
 *   司令塔が拠点へ送ると印が外れ、片付けにも上限にも入らなくなる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EnemySpawns {

    /** 縁で湧いた印。 */
    public static final String EDGE_TAG = "ov_edge_spawn";
    /** 地下で湧いた印。 */
    public static final String UNDER_TAG = "ov_under_spawn";
    /** グループの印の頭({@code ov_spawn_g4} など)。 */
    public static final String GROUP_TAG = "ov_spawn_g";

    /** 片付けの間(tick)。 */
    private static final int SWEEP_INTERVAL = 100;

    private EnemySpawns() {
    }

    /** GameTest のサーバーでも tick で湧かせるか(既定は止める。テストは下の口を直に呼ぶ)。 */
    public static boolean tickInGameTests = false;

    /** 確かめ用の数(/ov spawns)。 */
    public static final long[] stats = new long[Miss.values().length];
    public static long spawnedEdge, spawnedUnder;

    /** 1 回の試しの結果。 */
    public enum Miss {
        SPAWNED("湧いた"), NOT_EDGE("縁でない"), NOT_NEAR("汚染の下でない"), FULL("上限・開いていない"), NO_SPOT("置き場が無い"),
        NEAR_PLAYER("プレイヤーに近い"), NOT_TICKING("動いていない"), BLOCKED("体がはまる"), NO_TYPE("種が無い");

        public final String label;

        Miss(String label) {
            this.label = label;
        }
    }

    // ---------------------------------------------------------------- 強さと上限

    /** そのグループの今の強さ(0〜1)。フェーズと段は司令塔と同じ口から(テストで差し替えられる)。 */
    public static double strength(ServerLevel level, int group) {
        SpawnTable t = DesignData.spawns();
        int open = t.openAt(group);
        if (open == Integer.MAX_VALUE) return 0.0D;
        return PhaseStages.strength(DesignData.phases(), DirectorGates.phase(level), DirectorGates.stage(level), open);
    }

    /** 縁の、そのグループの今の上限(基本 × 強さ。四捨五入)。 */
    public static int edgeCap(ServerLevel level, int group) {
        double s = strength(level, group);
        return s <= 0.0D ? 0 : (int) Math.round(DesignData.spawns().cap(group) * s);
    }

    /** 地下の G1 の今の上限。 */
    public static int underCap(ServerLevel level) {
        double s = strength(level, 1);
        return s <= 0.0D ? 0 : (int) Math.round(DesignData.spawns().underCap * s);
    }

    // ---------------------------------------------------------------- 縁

    /** そのチャンクは縁か: 台帳での自分の扱い(載っている / いない)と違う隣が 8 方向に 1 つでもある。 */
    public static boolean isEdgeChunk(PhaseData data, int cx, int cz) {
        boolean self = data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)) != self) return true;
            }
        }
        return false;
    }

    /** そのチャンクから r チャンクの中に、過繁茂のチャンクがあるか(地下の湧き)。 */
    public static boolean nearLedger(PhaseData data, int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    /** まわりの数: [0] = 地下の G1、[g] = 縁のグループ g。 */
    public static int[] count(ServerLevel level, double x, double y, double z, double r) {
        int[] out = new int[11];
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(x - r, level.getMinBuildHeight(), z - r, x + r, level.getMaxBuildHeight(), z + r),
                m -> m.isAlive() && (m.getTags().contains(EDGE_TAG) || m.getTags().contains(UNDER_TAG)))) {
            if (m.getTags().contains(UNDER_TAG)) {
                out[0]++;
                continue;
            }
            out[groupOf(m)]++;
        }
        return out;
    }

    private static int groupOf(Mob m) {
        for (String tag : m.getTags()) {
            if (tag.startsWith(GROUP_TAG)) {
                try {
                    int g = Integer.parseInt(tag.substring(GROUP_TAG.length()));
                    if (g >= 1 && g <= 10) return g;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * 縁に 1 回試す: (cx, cz) のチャンクが縁なら、開いていて上限に余りのあるグループから種を重みで選び、住む場所に置く。
     * counts は {@link #count} の結果(湧いたら足す)。
     */
    public static Miss tryEdge(ServerLevel level, int cx, int cz, int[] counts, RandomSource random) {
        if (!isEdgeChunk(PhaseData.get(level), cx, cz)) return Miss.NOT_EDGE;
        SpawnTable.Species sp = pick(level, counts, random, false);
        if (sp == null) return Miss.FULL;
        EntityType<?> type = sp.type();
        if (type == null) return Miss.NO_TYPE;
        SpawnTable.Habitat h = sp.habitats().contains(SpawnTable.Habitat.GROUND) && sp.group() == 1 ? SpawnTable.Habitat.GROUND
                : pickHabitat(sp, random);
        BlockPos at = spot(level, h, cx, cz, DesignData.spawns().caveMinDepth, random);
        if (at == null) return Miss.NO_SPOT;
        Miss m = place(level, type, at, h, EDGE_TAG, sp.group());
        if (m == Miss.SPAWNED) {
            counts[sp.group()]++;
            spawnedEdge++;
        }
        return m;
    }

    /** 地下に 1 回試す: (cx, cz) が過繁茂の真下かそのそばなら、その下の洞窟に G1 の段階 0。 */
    public static Miss tryUnder(ServerLevel level, int cx, int cz, int[] counts, RandomSource random) {
        SpawnTable t = DesignData.spawns();
        if (!nearLedger(PhaseData.get(level), cx, cz, t.underNearChunks)) return Miss.NOT_NEAR;
        if (counts[0] >= underCap(level)) return Miss.FULL;
        SpawnTable.Species sp = pick(level, counts, random, true);
        if (sp == null) return Miss.FULL;
        EntityType<?> type = sp.type();
        if (type == null) return Miss.NO_TYPE;
        BlockPos at = spot(level, SpawnTable.Habitat.CAVE, cx, cz, t.underMinDepth, random);
        if (at == null) return Miss.NO_SPOT;
        Miss m = place(level, type, at, SpawnTable.Habitat.CAVE, UNDER_TAG, 1);
        if (m == Miss.SPAWNED) {
            counts[0]++;
            spawnedUnder++;
        }
        return m;
    }

    /** 種を重み × 強さで選ぶ(縁: 上限に余りのあるグループから。地下: 洞窟に住む G1 だけ)。 */
    @Nullable
    public static SpawnTable.Species pick(ServerLevel level, int[] counts, RandomSource random, boolean under) {
        List<SpawnTable.Species> cands = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0D;
        double[] str = new double[11];
        for (int g = 1; g <= 10; g++) str[g] = -1.0D;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            int g = sp.group();
            if (under && (g != 1 || !sp.habitats().contains(SpawnTable.Habitat.CAVE))) continue;
            if (!under && counts[g] >= edgeCap(level, g)) continue;
            if (str[g] < 0.0D) str[g] = strength(level, g);
            double w = sp.weight() * str[g];
            if (w <= 0.0D || sp.type() == null) continue;
            cands.add(sp);
            weights.add(w);
            total += w;
        }
        if (cands.isEmpty()) return null;
        double r = random.nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) {
            r -= weights.get(i);
            if (r <= 0.0D) return cands.get(i);
        }
        return cands.get(cands.size() - 1);
    }

    private static SpawnTable.Habitat pickHabitat(SpawnTable.Species sp, RandomSource random) {
        List<SpawnTable.Habitat> hs = new ArrayList<>(sp.habitats());
        return hs.get(random.nextInt(hs.size()));
    }

    // ---------------------------------------------------------------- 置き場

    /** そのチャンクの中で、住む場所に合う置き場(足元)を 1 つ。無ければ null。 */
    @Nullable
    public static BlockPos spot(ServerLevel level, SpawnTable.Habitat h, int cx, int cz, int caveMinDepth, RandomSource random) {
        int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
        if (!com.yayuy.overgrownvegetation.util.LoadedChunks.loaded(level, cx, cz)) return null;
        return switch (h) {
            case GROUND, SOIL -> ground(level, x, z);
            case SKY -> {
                SpawnTable t = DesignData.spawns();
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int y = top + t.skyMinAbove + random.nextInt(t.skyMaxAbove - t.skyMinAbove + 1);
                yield y < level.getMaxBuildHeight() - 4 ? new BlockPos(x, y, z) : null;
            }
            case SEA -> sea(level, x, z, random);
            case CAVE -> cave(level, x, z, caveMinDepth, random);
        };
    }

    @Nullable
    private static BlockPos ground(ServerLevel level, int x, int z) {
        BlockPos feet = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (feet == null) return null;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) return null;
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP) ? feet : null;
    }

    @Nullable
    private static BlockPos sea(ServerLevel level, int x, int z, RandomSource random) {
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        if (surface - floor < 3) return null;
        BlockPos p = new BlockPos(x, floor + random.nextInt(surface - floor - 1), z);
        return level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p.above()).is(FluidTags.WATER) ? p : null;
    }

    @Nullable
    private static BlockPos cave(ServerLevel level, int x, int z, int minDepth, RandomSource random) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - minDepth;
        int bottom = level.getMinBuildHeight() + 1;
        if (top <= bottom) return null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, bottom + random.nextInt(top - bottom + 1), z);
        // そこから下へ 24 ブロックまで、床のある 2 マスの空洞を探す
        for (int i = 0; i < 24 && p.getY() > bottom; i++, p.move(Direction.DOWN)) {
            if (!open(level, p) || !open(level, p.above())) continue;
            BlockPos below = p.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            if (level.canSeeSky(p)) return null; // 縦穴の底は洞窟でない
            return p.immutable();
        }
        return null;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.blocksMotion() && s.getFluidState().isEmpty();
    }

    /** 置く: プレイヤーから近すぎない・動いている・体がはまらない所に湧かせ、印を付ける。 */
    public static Miss place(ServerLevel level, EntityType<?> type, BlockPos at, SpawnTable.Habitat h, String tag, int group) {
        if (!level.isPositionEntityTicking(at)) return Miss.NOT_TICKING;
        double min = DesignData.spawns().minBlocks;
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) < min * min) return Miss.NEAR_PLAYER;
        }
        Mob m = create(level, type, at, MobSpawnType.NATURAL, level.getRandom());
        if (m == null) return Miss.BLOCKED;
        m.addTag(tag);
        m.addTag(GROUP_TAG + group);
        level.addFreshEntity(m);
        track(m);
        return Miss.SPAWNED;
    }

    /** Mob を作って置き場へ動かし、はまらなければ finalizeSpawn まで済ませて返す(まだ世界には足さない)。 */
    @Nullable
    public static Mob create(ServerLevel level, EntityType<?> type, BlockPos at, MobSpawnType reason, RandomSource random) {
        Entity e = type.create(level);
        if (!(e instanceof Mob m)) {
            if (e != null) e.discard();
            return null;
        }
        m.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(m)) {
            m.discard();
            return null;
        }
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), reason, null);
        return m;
    }

    // ---------------------------------------------------------------- 片付け

    private static final Set<Mob> TRACKED = Collections.newSetFromMap(new WeakHashMap<>());

    static void track(Mob m) {
        TRACKED.add(m);
    }

    /** 縁・地下の印を外す(司令塔が拠点へ送ったとき。上限にも片付けにも入らなくなる)。 */
    public static void release(Mob m) {
        m.removeTag(EDGE_TAG);
        m.removeTag(UNDER_TAG);
        TRACKED.remove(m);
    }

    /** 縁・地下の湧きの印か(進化で引き継がない。見直し ②4)。 */
    public static boolean isSpawnTag(String tag) {
        return tag.equals(EDGE_TAG) || tag.equals(UNDER_TAG) || tag.startsWith(GROUP_TAG);
    }

    public static boolean spawnedHere(Mob m) {
        return m.getTags().contains(EDGE_TAG) || m.getTags().contains(UNDER_TAG);
    }

    /** 遠くなった印つきの個体を消す。消した数を返す。 */
    public static int sweep(ServerLevel level) {
        double far = DesignData.spawns().despawnBlocks;
        int n = 0;
        for (Mob m : List.copyOf(TRACKED)) {
            if (m.isRemoved() || !spawnedHere(m)) {
                TRACKED.remove(m);
                continue;
            }
            if (m.level() != level) continue;
            if (m.hasCustomName() || m.isPassenger() || m.isVehicle() || m.isLeashed()) continue;
            // 拠点へ向かう・司令塔が道(進化・根付き・苗床)を決めた G1 は片付けない(予算を払っているので。見直し ②4)
            if (m instanceof AbstractParasitizedMonster g1 && (g1.march().active() || g1.fate() != null && g1.fate().route() != com.yayuy.overgrownvegetation.director.ForestDirector.Route.NONE)) continue;
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
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob m && spawnedHere(m)) track(m); // 保存から戻った個体も片付けに入れる
    }

    // ---------------------------------------------------------------- tick

    /** 湧かせてよい世界か(オーバーワールド・ピースフルでない・doMobSpawning・大きな襲撃の前触れでない)。 */
    public static boolean allowed(ServerLevel level) {
        return DesignData.spawns().enabled && level.dimension() == Level.OVERWORLD && level.getDifficulty() != Difficulty.PEACEFUL
                && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && !DirectorGates.plantSpawnsSuppressed(level);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!tickInGameTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return;
        long time = level.getGameTime();
        if (time % SWEEP_INTERVAL == 0) sweep(level);
        SpawnTable t = DesignData.spawns();
        if (time % t.intervalTicks != 0 || !allowed(level)) return;
        RandomSource random = level.getRandom();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            tickPlayer(level, p.getX(), p.getY(), p.getZ(), random);
        }
    }

    /** プレイヤー 1 人ぶんの試し(縁を triesPerPlayer 回・地下を underTries 回)。 */
    public static void tickPlayer(ServerLevel level, double px, double py, double pz, RandomSource random) {
        SpawnTable t = DesignData.spawns();
        int[] counts = count(level, px, py, pz, t.countRadius);
        for (int i = 0; i < t.triesPerPlayer; i++) {
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double d = t.minBlocks + random.nextDouble() * (t.maxBlocks - t.minBlocks);
            int cx = net.minecraft.util.Mth.floor(px + Math.cos(ang) * d) >> 4, cz = net.minecraft.util.Mth.floor(pz + Math.sin(ang) * d) >> 4;
            stats[tryEdge(level, cx, cz, counts, random).ordinal()]++;
        }
        int pcx = net.minecraft.util.Mth.floor(px) >> 4, pcz = net.minecraft.util.Mth.floor(pz) >> 4;
        for (int i = 0; i < t.underTries; i++) {
            int cx = pcx + random.nextInt(t.underRangeChunks * 2 + 1) - t.underRangeChunks;
            int cz = pcz + random.nextInt(t.underRangeChunks * 2 + 1) - t.underRangeChunks;
            stats[tryUnder(level, cx, cz, counts, random).ordinal()]++;
        }
    }

    // ---------------------------------------------------------------- 見る口

    public static List<String> describe(ServerLevel level, double x, double y, double z) {
        SpawnTable t = DesignData.spawns();
        PhaseData data = PhaseData.get(level);
        int pcx = net.minecraft.util.Mth.floor(x) >> 4, pcz = net.minecraft.util.Mth.floor(z) >> 4, r = t.maxBlocks >> 4;
        int edges = 0, inside = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isEdgeChunk(data, pcx + dx, pcz + dz)) continue;
                edges++;
                if (data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(pcx + dx, pcz + dz))) inside++;
            }
        }
        int[] c = count(level, x, y, z, t.countRadius);
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "湧き %s・フェーズ %s・段 %s / まわり %d チャンクの縁 %d(内側 %d・外側 %d)",
                allowed(level) ? "動く" : "止まっている", DirectorGates.phase(level) == Integer.MAX_VALUE ? "全部開(テスト)" : "F" + DirectorGates.phase(level),
                DirectorGates.stage(level).name(), (2 * r + 1) * (2 * r + 1), edges, inside, edges - inside));
        out.add(String.format(Locale.ROOT, "縁: G1 %d/%d・G4 %d/%d・G5 %d/%d / 地下の G1 %d/%d(強さ G1 %.2f・G4 %.2f・G5 %.2f)",
                c[1], edgeCap(level, 1), c[4], edgeCap(level, 4), c[5], edgeCap(level, 5), c[0], underCap(level),
                strength(level, 1), strength(level, 4), strength(level, 5)));
        StringBuilder sb = new StringBuilder("これまで: 縁 " + spawnedEdge + "・地下 " + spawnedUnder + " / 試しの結果: ");
        for (Miss m : Miss.values()) sb.append(m.label).append(' ').append(stats[m.ordinal()]).append("・");
        out.add(sb.toString());
        return out;
    }
}
