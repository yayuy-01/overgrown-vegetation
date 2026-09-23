package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.spore.ErosionCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 過繁茂バイオーム({@link ModBiomes#isFullyOvergrown})の中に「過繁茂した Mob」の<b>生態系</b>を
 * 維持する個体数マネージャ。過繁茂バイオームの json は {@code spawners} が空でバニラの自然湧きが
 * 一切起きないため、この処理が無いと過繁茂バイオームの中には何も湧かない。
 *
 * <p>やっていること(各プレイヤーについて {@code overgrownSpawnIntervalTicks} ごと):
 * <ol>
 *     <li>そのフェーズに湧く種({@code mobs.json} の {@code spawns.overgrown})を候補にする。</li>
 *     <li>各種の<b>目標個体数</b>を {@link OvergrownSpawnProfile} の weight から比例配分で決める
 *     (合計 ≒ {@code overgrownSpawnTargetTotal}、ただし 1 種あたり最低 1 体)。被食者=グループ2 が
 *     捕食者=グループ3 のおよそ 3 倍になるよう weight を振ってあるので、放っておくと被食者が多い
 *     ピラミッドになる。{@code spawnChance} が 1 未満の種(レア枠)は、そのパスの抽選に通ったときだけ
 *     候補に入る。</li>
 *     <li>プレイヤー周辺({@code overgrownSpawnRadius + overgrownSpawnCountMargin})の実個体数を種別に数え、
 *     目標を下回っている種を、不足の大きい順に、1 パス {@code overgrownSpawnMaxPerPass} 体まで補充する
 *     (その種の {@code clusterMax} を上限に、選んだ地点の周囲へ群れで)。</li>
 * </ol>
 *
 * <p>湧かせた Mob は {@link MobSpawnType#NATURAL}・非永続なので、プレイヤーが十分離れれば
 * バニラのルールで消える(＝プレイヤーの周りだけ密度が保たれ、通り過ぎた後ろは自然に空く)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownBiomeSpawner {

    /** プレイヤーに近すぎる位置には湧かせない下限距離(ブロック)。 */
    private static final int MIN_DISTANCE = 20;
    /** 1 種あたり、1 パスで試すスポーン地点(群れの中心)の数。 */
    private static final int CENTERS_PER_SPECIES = 3;
    /** 洞窟種を探すときに地表から何ブロック下まで見るか。 */
    private static final int CAVE_SEARCH_DEPTH = 96;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int interval = Math.max(1, cfg.overgrownSpawnIntervalTicks.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        int phase = PhaseData.get(level).getPhase();
        if (phase < 1) {
            return;
        }

        List<EntityType<?>> pool = spawnPoolFor(phase);
        if (pool.isEmpty()) {
            return;
        }
        double totalWeight = 0.0D;
        for (EntityType<?> type : pool) {
            totalWeight += OvergrownSpawnProfile.weight(type);
        }
        if (totalWeight <= 0.0D) {
            return;
        }

        double chance = cfg.overgrownSpawnChance.get();
        int radius = cfg.overgrownSpawnRadius.get();
        if (chance <= 0.0D || radius <= MIN_DISTANCE) {
            return;
        }
        int countRadius = radius + Math.max(0, cfg.overgrownSpawnCountMargin.get());
        int targetTotal = cfg.overgrownSpawnTargetTotal.get();
        int maxPerPass = Math.max(0, cfg.overgrownSpawnMaxPerPass.get());
        int clusterHardCap = Math.max(1, cfg.overgrownSpawnGroupMax.get());
        RandomSource random = level.random;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            // 浸食度を持ち出しているプレイヤーの周りは湧きやすくなる(ErosionCarrier)。
            if (random.nextDouble() >= chance * ErosionCarrier.spawnMultiplier(player)) {
                continue;
            }
            // 過繁茂バイオームの近くにいるプレイヤーだけを処理する(遠くのプレイヤーで
            // 毎回地表探索を空振りさせないための安いゲート。中心+スポーン範囲の四隅を見る)。
            if (!nearFullyOvergrown(level, player, radius)) {
                continue;
            }
            // プレイヤーの居る環境で候補を絞る(2026-09-21)。目標個体数も数えるのも
            // この絞ったあとの一覧で行うので、「湧けない種を目標に数えて永久に不足のまま」
            // にならない。実際に湧かせる座標の環境は spawnOne 側でもう一度見る
            // —— 環境の境目に立っているとき、隣の環境へはみ出して湧かせないため。
            List<EntityType<?>> localPool = new ArrayList<>();
            for (EntityType<?> type : pool) {
                if (OvergrownSpawnProfile.allowedIn(level.getBiome(player.blockPosition()), phase, type)) {
                    localPool.add(type);
                }
            }
            if (localPool.isEmpty()) {
                continue;
            }
            double localWeight = 0.0D;
            for (EntityType<?> type : localPool) {
                localWeight += OvergrownSpawnProfile.weight(type);
            }
            if (localWeight <= 0.0D) {
                continue;
            }
            topUpNear(level, player, phase, localPool, localWeight, targetTotal, countRadius, radius,
                    maxPerPass, clusterHardCap, random);
        }
    }

    /** プレイヤー位置、または半径 {@code radius} の東西南北いずれかが過繁茂バイオームか(biome 参照のみ、ブロックは読まない)。 */
    private static boolean nearFullyOvergrown(ServerLevel level, ServerPlayer player, int radius) {
        BlockPos base = player.blockPosition();
        if (ModBiomes.isFullyOvergrown(level.getBiome(base))) {
            return true;
        }
        for (int[] d : new int[][] {{radius, 0}, {-radius, 0}, {0, radius}, {0, -radius}}) {
            if (ModBiomes.isFullyOvergrown(level.getBiome(base.offset(d[0], 0, d[1])))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 現在のフェーズで過繁茂バイオームに湧けるグループの、全種族をまとめた候補プール
     * ({@code mobs.json} の {@code spawns.overgrown})。weight 0 の種は生態系スポーンの対象外なので除く。
     *
     * <p>グループ4(拡大種)がうずくまったあとに過繁茂 Mob を 1 体増やす処理
     * ({@link com.yayuy.overgrownvegetation.entity.InvaderSporeCrouchGoal})でも同じプールを使う。
     */
    public static List<EntityType<?>> spawnPoolFor(int phase) {
        List<EntityType<?>> pool = new ArrayList<>();
        for (EntityType<?> type : com.yayuy.overgrownvegetation.data.MobStats.overgrownSpawnPool(phase)) {
            if (OvergrownSpawnProfile.weight(type) > 0.0D && !pool.contains(type)) {
                pool.add(type);
            }
        }
        return pool;
    }

    private static void topUpNear(ServerLevel level, ServerPlayer player, int phase, List<EntityType<?>> pool,
                                  double totalWeight, int targetTotal, int countRadius, int spawnRadius,
                                  int maxPerPass, int clusterHardCap, RandomSource random) {
        if (maxPerPass <= 0 || targetTotal <= 0) {
            return;
        }

        // プレイヤー周辺の「過繁茂した Mob」を種別に数える(汚染シダ/汚染水草は weight 0 なので
        // そもそも候補プールに入らず、ここで数えても目標個体数の計算には影響しない)。
        AABB countArea = new AABB(player.blockPosition()).inflate(countRadius);
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, countArea, m -> MobGroup.of(m) != null)) {
            counts.merge(mob.getType(), 1, Integer::sum);
        }

        // 各種の不足数を出し、「目標に対する不足の割合」が大きい順に補充する
        // (捕食者が 0/1、被食者が 9/11 のようなとき、まず捕食者を埋める → ピラミッドが崩れにくい)。
        record Deficit(EntityType<?> type, int want, double ratio) {
        }
        List<Deficit> deficits = new ArrayList<>();
        // 混成の群れで湧かせるグループ(既定: フェーズ6以降のグループ4)は、種ごとに補充するのではなく
        // グループ単位で不足を合算し、種がばらばらな 1 群れとして湧かせる。
        com.yayuy.overgrownvegetation.data.PhaseTable phases =
                com.yayuy.overgrownvegetation.data.DesignData.phases();
        Map<Integer, Integer> packWant = new HashMap<>();
        Map<Integer, List<EntityType<?>>> packPools = new HashMap<>();
        for (EntityType<?> type : pool) {
            OvergrownSpawnProfile.Profile profile = OvergrownSpawnProfile.forType(type);
            // 「たまに出てくる程度」の種は、このパスでは候補にすら入れない(目標個体数を下げるのではなく
            // 湧く頻度だけを落とす)。混成の群れの抽選プールからも同時に外れる。
            if (profile.spawnChance() < 1.0D && random.nextDouble() >= profile.spawnChance()) {
                continue;
            }
            // 比例配分の端数で 0 体になる種(= 自然湧きしない種)を作らないよう、最低 1 体は目標にする。
            // overgrownSpawnTargetTotal を下げたときに少数種がまるごと消えるのを防ぐため。
            int target = Math.max(1, (int) Math.round(profile.weight() / totalWeight * targetTotal));
            int current = counts.getOrDefault(type, 0);
            int want = target - current;

            MobGroup group = MobGroup.of(type);
            if (group != null && phases.isMixedPackGroup(phase, group.number())) {
                // 群れの構成はグループ全種からの抽選なので、目標に足りている種も候補には残す
                // (「最低数を満たした群れ」ではなく混成の群れにしたい)。湧かせるかどうかだけを
                // グループ全体の不足で決める。
                packPools.computeIfAbsent(group.number(), k -> new ArrayList<>()).add(type);
                if (want > 0) {
                    packWant.merge(group.number(), want, Integer::sum);
                }
                continue;
            }

            if (want <= 0) {
                continue;
            }
            deficits.add(new Deficit(type, want, (double) want / target));
        }

        int budget = maxPerPass;
        // ワールドに同時に存在できる群れの数(phases.json の maxPacks)。0 なら無制限。
        // 「今いる群れの数」はこのパスの間ずっと同じ値を使わず、湧かせるたびに 1 つ減らす。
        int packBudget = PackTracker.budget(level, phase);

        // 先に混成の群れを湧かせる(残り予算の取り合いで潰れないように)。
        for (Map.Entry<Integer, Integer> entry : packWant.entrySet()) {
            if (budget <= 0 || packBudget <= 0) {
                break;
            }
            List<EntityType<?>> packPool = packPools.get(entry.getKey());
            if (packPool == null || packPool.isEmpty()) {
                continue;
            }
            int min = phases.mixedPackMin(phase);
            int max = phases.mixedPackMax(phase);
            if (budget < min) {
                continue; // 端数で 1〜2 体だけ湧かせない(群れとして成立させる)
            }
            // 群れの体数は min〜max のランダム。ただし予算と「グループ全体の不足数」で抑える
            // (不足が min を下回っていても群れの形は崩さないので、下限は min のまま)。
            int size = Math.min(Math.min(min + random.nextInt(max - min + 1), budget),
                    Math.max(min, entry.getValue()));
            int spawned = spawnMixedPack(level, player, phase, packPool, size, spawnRadius, random);
            budget -= spawned;
            if (spawned > 0) {
                packBudget--;
            }
        }

        if (deficits.isEmpty()) {
            return;
        }
        deficits.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));

        for (Deficit d : deficits) {
            if (budget <= 0 || packBudget <= 0) {
                break;
            }
            int clusterMax = Math.min(OvergrownSpawnProfile.forType(d.type()).clusterMax(), clusterHardCap);
            int want = Math.min(Math.min(d.want(), budget), Math.max(1, clusterMax));
            int spawned = spawnCluster(level, player, phase, d.type(), want, spawnRadius, random);
            budget -= spawned;
            if (spawned > 0) {
                packBudget--;
            }
        }
    }

    /**
     * 「混成の群れ」を 1 つ湧かせる(2026-09-15、既定はフェーズ6以降のグループ4)。1 箇所を中心に、
     * {@code size} 体ぶん<b>種を毎回抽選しながら</b>湧かせるので、同じ群れに別種が混ざる。
     * 抽選は {@link OvergrownSpawnProfile} の weight に比例する(= 雑魚枠が多く、支援・索敵役が少ない)。
     *
     * @return 実際に湧いた数
     */
    private static int spawnMixedPack(ServerLevel level, ServerPlayer player, int phase, List<EntityType<?>> packPool,
                                      int size, int spawnRadius, RandomSource random) {
        double totalWeight = 0.0D;
        for (EntityType<?> type : packPool) {
            totalWeight += OvergrownSpawnProfile.weight(type);
        }
        if (totalWeight <= 0.0D) {
            return 0;
        }

        double angle = random.nextDouble() * Math.PI * 2.0D;
        int distance = MIN_DISTANCE + random.nextInt(spawnRadius - MIN_DISTANCE + 1);
        int centerX = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int centerZ = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

        int packId = PackTracker.newPack();
        int spawned = 0;
        // 地表が見つからない座標を引くこともあるので、体数より少し多めに試す。
        for (int i = 0; i < size + 4 && spawned < size; i++) {
            EntityType<?> type = pickWeighted(packPool, totalWeight, random);
            if (type == null) {
                continue;
            }
            int x = centerX + random.nextInt(7) - 3;
            int z = centerZ + random.nextInt(7) - 3;
            if (spawnOne(level, type, x, z, packId, phase)) {
                spawned++;
            }
        }
        return spawned;
    }

    /** weight に比例して 1 種を抽選する。 */
    private static EntityType<?> pickWeighted(List<EntityType<?>> pool, double totalWeight, RandomSource random) {
        double roll = random.nextDouble() * totalWeight;
        for (EntityType<?> type : pool) {
            roll -= OvergrownSpawnProfile.weight(type);
            if (roll <= 0.0D) {
                return type;
            }
        }
        return pool.isEmpty() ? null : pool.get(pool.size() - 1);
    }

    /** {@code type} を最大 {@code want} 体、プレイヤー周辺の過繁茂バイオーム地表へ群れで湧かせる。実際に湧いた数を返す。 */
    private static int spawnCluster(ServerLevel level, ServerPlayer player, int phase, EntityType<?> type, int want,
                                    int spawnRadius, RandomSource random) {
        int packId = PackTracker.newPack();
        int spawned = 0;
        for (int c = 0; c < CENTERS_PER_SPECIES && spawned < want; c++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = MIN_DISTANCE + random.nextInt(spawnRadius - MIN_DISTANCE + 1);
            int centerX = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int centerZ = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            for (int i = 0; i < want - spawned + 2 && spawned < want; i++) {
                int x = centerX + random.nextInt(7) - 3;
                int z = centerZ + random.nextInt(7) - 3;
                if (spawnOne(level, type, x, z, packId, phase)) {
                    spawned++;
                }
            }
        }
        return spawned;
    }

    /** {@code (x, z)} 列に {@code type} を 1 体湧かせる。水生種は水中、それ以外は地表。 */
    private static boolean spawnOne(ServerLevel level, EntityType<?> type, int x, int z, int packId, int phase) {
        if (OvergrownSpawnProfile.isAquatic(type)) {
            return spawnOneAquatic(level, type, x, z, packId, phase);
        }
        if (OvergrownSpawnProfile.isCave(type)) {
            return spawnOneCave(level, type, x, z, packId, phase);
        }
        // 地表が木の天蓋・幹(過繁茂した葉・巨大樹の幹など)なら、その下の本物の地面まで潜る
        // (詳細は TreeCanopyGroundFinder 参照)。
        BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (spawnPos == null) {
            return false;
        }

        // 「過繁茂した Mob」は過繁茂バイオーム(陸・海)にだけ湧く。繁茂バイオームには湧かせない。
        if (!ModBiomes.isFullyOvergrown(level.getBiome(spawnPos))) {
            return false;
        }
        if (!OvergrownSpawnProfile.allowedIn(level.getBiome(spawnPos), phase, type)) {
            return false;
        }
        if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
            return false;
        }
        // 体がまるごと入るか(幅 1 を超える種・背の高い種が地面や壁へ埋まらないように。SpawnFit 参照)。
        if (!SpawnFit.fits(level, type, spawnPos)) {
            return false;
        }

        // spawnPos は既に地表(地面ブロックの1つ上)なので Y オフセットは不要。
        Entity spawned = type.create(level, null, spawnPos, MobSpawnType.NATURAL, false, false);
        if (spawned == null) {
            return false;
        }
        PackTracker.mark(spawned, packId);
        level.addFreshEntity(spawned);
        return true;
    }

    /**
     * {@code (x, z)} 列の過繁茂した海/川バイオームの水中に {@code type}(水生種)を 1 体湧かせる。
     * 水面と水底の間のランダムな深さ、水深 2 以上の場所にのみ湧かせる。
     */
    private static boolean spawnOneAquatic(ServerLevel level, EntityType<?> type, int x, int z, int packId, int phase) {
        int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        int floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);
        int depth = surface - 1 - floor;
        if (depth < 2) {
            return false;
        }
        int y = floor + 1 + level.random.nextInt(depth);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)) {
            return false;
        }
        if (!ModBiomes.isFullyOvergrown(level.getBiome(pos))) {
            return false;
        }
        if (!OvergrownSpawnProfile.allowedIn(level.getBiome(pos), phase, type)) {
            return false;
        }
        // 水中でも体が岩や海底へめり込まないようにする(実際に立たせる座標で見る)。
        if (!SpawnFit.fitsAt(level, type, x + 0.5D, y + 0.5D, z + 0.5D)) {
            return false;
        }

        Entity spawned = type.create(level, null, pos, MobSpawnType.NATURAL, false, false);
        if (spawned == null) {
            return false;
        }
        spawned.moveTo(x + 0.5D, y + 0.5D, z + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        PackTracker.mark(spawned, packId);
        level.addFreshEntity(spawned);
        return true;
    }

    /**
     * {@code (x, z)} 列の<b>地下の空洞</b>に {@code type}(洞窟種)を 1 体湧かせる。
     *
     * <h2>地表から潜って探す</h2>
     * <p>列の地表から下へ順に見ていき、「足場があって頭上が空いている」段を集めて、
     * そこから 1 つ選ぶ。深さの下限は {@link CaveGrowth#isCave} —— 洞窟の植物が生える判定と
     * 同じものを使う。<b>判定を分けると「植物は生えるのに住人は湧かない」段ができる</b>ので。
     *
     * <p>谷や峡谷の底は {@code isCave} が弾く(空に開けているので洞窟ではない)。
     * 地上の生き物と住み分けさせるための線引きで、そこもこの種の生態の一部。
     */
    private static boolean spawnOneCave(ServerLevel level, EntityType<?> type, int x, int z, int packId, int phase) {
        int margin = OvergrownVegetationConfig.COMMON.caveDepthMargin.get();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - margin;
        int bottom = Math.max(level.getMinBuildHeight() + 1, top - CAVE_SEARCH_DEPTH);
        List<Integer> floors = new ArrayList<>();
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return false;
            }
            // 足場がある + 自分の足元と頭の高さが空いている
            if (!level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                continue;
            }
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            if (!level.getFluidState(pos).isEmpty()) {
                continue;
            }
            if (!CaveGrowth.isCave(level, pos, margin)) {
                continue;
            }
            floors.add(y);
        }
        if (floors.isEmpty()) {
            return false;
        }
        int y = floors.get(level.random.nextInt(floors.size()));
        BlockPos pos = new BlockPos(x, y, z);
        if (!ModBiomes.isFullyOvergrown(level.getBiome(pos))) {
            return false;
        }
        if (!OvergrownSpawnProfile.allowedIn(level.getBiome(pos), phase, type)) {
            return false;
        }
        if (!SpawnFit.fits(level, type, pos)) {
            return false;
        }

        Entity spawned = type.create(level, null, pos, MobSpawnType.NATURAL, false, false);
        if (spawned == null) {
            return false;
        }
        PackTracker.mark(spawned, packId);
        level.addFreshEntity(spawned);
        return true;
    }

    private OvergrownBiomeSpawner() {
    }
}
