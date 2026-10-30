package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import com.yayuy.overgrownvegetation.spore.ErosionCarrier;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
import com.yayuy.overgrownvegetation.swarm.SwarmTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
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
 *     (その種の {@code clusterMax} を上限に、選んだ地点の周囲へ群れで)。
 *     数える箱の縦は足元から湧かせる地面まで伸ばし、グループ4 だけは {@code invaderDespawnRange} まで数える
 *     (2026-09-24、{@link #countBox})。</li>
 * </ol>
 *
 * <p>湧かせた Mob は {@link MobSpawnType#NATURAL}・非永続なので、プレイヤーが十分離れれば
 * バニラのルールで消える(＝プレイヤーの周りだけ密度が保たれ、通り過ぎた後ろは自然に空く)。
 *
 * <h2>G4 の群れ(群れ v2、2026-09-25)</h2>
 * 群れに入る G4(アルボラ・スカトゥリウム・スポロフィル・フィロセンス・ヴェロフィル)は種ごとに補充せず、地上 / 空の種類ごとに
 * 不足を合算して、{@code swarm.json} の編成で<b>揃った 1 群れ</b>として湧かせる({@link SwarmSpawner}、出どころ NATURAL)。
 * プレイヤーごとに 1 パス 1 群れまで・{@code overgrownSpawnMaxPerPass} とは別枠・読み込まれている群れの数が
 * {@code phases.json} の {@code maxPacks} に達していれば湧かせない。群れの一員は群れごと消える(個体ではバニラに消させない)。
 * 数えるのは今までどおり 1 体ずつ。ペディキュラ(空を漂う 1 体)は群れに入らず、今までどおり種ごとに湧く。
 * 旧い {@code PackTracker}(かたまりごとの群れ番号)は群れ v2 で消した。
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
            // 縁からの深さ d(順 1 の着手順 10、ユーザー指定): プレイヤーのいるチャンクで測り、グループの帯で重みを変える
            // (縁寄り G4・6・7・9 / 奥寄り G2・3・8 / G1・G5 はそのまま。OvergrowthDepth)。目標の配分は重みの比なので、
            // 全体の数(targetTotal)は変わらず、顔ぶれだけが縁 ↔ 奥で入れ替わる。湧く場所は 20〜48 ブロック先なので、
            // プレイヤーのチャンクの d とは最大 3 チャンクずれる。台帳はオーバーワールドのものなので、ほかの次元では変えない。
            double depth = level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    ? OvergrowthDepth.depth(level, new net.minecraft.world.level.ChunkPos(player.blockPosition())) : -1.0D;
            double localWeight = 0.0D;
            for (EntityType<?> type : localPool) {
                localWeight += depthWeight(type, depth);
            }
            if (localWeight <= 0.0D) {
                continue;
            }
            topUpNear(level, player, phase, localPool, localWeight, depth, targetTotal, countRadius, radius,
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

    /**
     * 湧いた Mob を数える箱(2026-09-24、ユーザー承認)。水平は今までどおりプレイヤーの周り {@code radius}、
     * 縦は<b>足元と {@code groundY}(このパスが Mob を置く地面)の両方</b>から {@code radius} まで。
     *
     * <p>足元を中心にした立方体だけで数えていたころは、巨大樹の上・飛行中・深い採掘のように
     * 地面から縦に {@code radius} 以上離れていると、自分で地面に置いた Mob を 1 体も数えられず、
     * 毎パス予算いっぱいまで湧かせ続けていた。
     * 地面に立っていれば {@code groundY} は足元の高さなので、箱は今までの
     * {@code new AABB(feet).inflate(radius)} とまったく同じになる。
     *
     * <p>媒介種の外湧き({@link OutsideInvaderSpawner})と生態系防衛({@code EcosystemDefense})も同じ箱で数える。
     *
     * @param groundY {@link #countGroundY} の値
     */
    public static AABB countBox(BlockPos feet, int groundY, int radius) {
        int low = Math.min(feet.getY(), groundY);
        int high = Math.max(feet.getY(), groundY);
        return new AABB(feet.getX() - radius, low - radius, feet.getZ() - radius,
                feet.getX() + 1 + radius, high + 1 + radius, feet.getZ() + 1 + radius);
    }

    /**
     * {@link #countBox} の縦を伸ばす先 —— プレイヤーの列で、湧かせる側と同じ
     * {@link TreeCanopyGroundFinder#findSpawnSurface} が返す地面の高さ。
     *
     * <p>あちらは木の天蓋を 48 段までしか掘らないので、巨大樹(高さ 120〜240)の上に立つと null になる。
     * そのときだけ<b>同じ規則</b>(木の一部と、動きを止めないブロックを読み飛ばす)で最下段まで潜り直す
     * (48 段より深い水の上も同じ扱いになる)。
     * それでも見つからなければ足元の高さ(= 今までどおりの箱)。
     *
     * <p>高さが 1 ブロックに満たない地面(土の道・耕地・泥・ハーフなど)に立っていると、足元の座標は
     * その地面のブロック自身で、見つかる地面はその 1 つ上になる。地面に立っているのだから
     * 足元の高さを返す(= 今までどおりの箱。上へ 1 段伸ばさない)。
     */
    public static int countGroundY(ServerLevel level, BlockPos feet) {
        BlockPos ground = TreeCanopyGroundFinder.findSpawnSurface(level, feet.getX(), feet.getZ());
        if (ground != null) {
            return ground.getY() == feet.getY() + 1 ? feet.getY() : ground.getY();
        }
        if (!level.hasChunkAt(feet.getX(), feet.getZ())) {
            return feet.getY();
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(feet.getX(),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()), feet.getZ());
        int minY = level.getMinBuildHeight() + 1;
        while (cursor.getY() > minY) {
            BlockState below = level.getBlockState(cursor.below());
            if (below.blocksMotion() && !TreeCanopyGroundFinder.isTreePart(below)) {
                return cursor.getY() == feet.getY() + 1 ? feet.getY() : cursor.getY();
            }
            cursor.move(Direction.DOWN);
        }
        return feet.getY();
    }

    /**
     * グループ4 を数える箱の半径 —— {@code invaderDespawnRange}(既定 128)。グループ4 はその内側では
     * 消えない({@link com.yayuy.overgrownvegetation.entity.InvaderDespawn})ので、ゲームが実際に
     * 消すまでは数に入れておく(2026-09-24、ユーザー承認)。{@code radius} より狭くはしない(仮決め)。
     */
    public static int invaderCountRadius(int radius) {
        return Math.max(radius, OvergrownVegetationConfig.COMMON.invaderDespawnRange.get());
    }

    /**
     * 湧きの重みに縁からの深さ d の帯の重みを掛けたもの(順 1 の着手順 10)。{@code depth} が負なら掛けない。
     * 目標の配分はこの比で決める({@link #topUpNear})。
     */
    public static double depthWeight(EntityType<?> type, double depth) {
        double weight = OvergrownSpawnProfile.weight(type);
        if (weight <= 0.0D) {
            return weight;
        }
        return weight * bandWeight(type, depth);
    }

    /** {@code type} のグループの帯の重み(0〜1。順 1 の着手順 10)。{@code depth} が負・帯の無いグループ(G1・G5)は 1。 */
    public static double bandWeight(EntityType<?> type, double depth) {
        if (depth < 0.0D) {
            return 1.0D;
        }
        return OvergrowthDepth.weight(OvergrowthDepth.band(MobGroup.of(type)), depth, OvergrowthDepth.radius());
    }

    /**
     * 「最低 1 体」の床がプレイヤーのいる場所で付くか(順 1 の着手順 10、ユーザー指定 2026-10-04)。確率は帯の重みで、
     * プレイヤーのいるチャンク・種・ゲーム内の日で決まる({@link #floorRoll}。同じ所に立っている間は変わらない)。
     */
    private static boolean floorHolds(ServerLevel level, ServerPlayer player, EntityType<?> type, double depth) {
        double chance = bandWeight(type, depth);
        if (chance >= 1.0D) {
            return true;
        }
        long day = level.getServer().overworld().getGameTime() / 24000L;
        return floorRoll(new net.minecraft.world.level.ChunkPos(player.blockPosition()).toLong(),
                BuiltInRegistries.ENTITY_TYPE.getId(type), day, chance);
    }

    /**
     * チャンク・種・日から決まる 0〜1 の値が {@code chance} 未満か。同じ引数なら必ず同じ答え、チャンクを替えると
     * およそ {@code chance} の割合で true になる。毎回振り直さないのは、一度湧いた個体が残るので、振り直すと
     * 結局ほとんどいつも床が付いた状態になるため。
     */
    public static boolean floorRoll(long chunkKey, int typeId, long day, double chance) {
        if (chance >= 1.0D) {
            return true;
        }
        if (chance <= 0.0D) {
            return false;
        }
        long h = chunkKey * 0x9E3779B97F4A7C15L ^ (long) typeId * 0xC2B2AE3D27D4EB4FL ^ day * 0x165667B19E3779F9L;
        // MurmurHash3 の 64 ビットの仕上げ(ビットをよく混ぜる)
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 < chance;
    }

    private static void topUpNear(ServerLevel level, ServerPlayer player, int phase, List<EntityType<?>> pool,
                                  double totalWeight, double depth, int targetTotal, int countRadius, int spawnRadius,
                                  int maxPerPass, int clusterHardCap, RandomSource random) {
        if (maxPerPass <= 0 || targetTotal <= 0) {
            return;
        }

        // プレイヤー周辺の「過繁茂した Mob」を種別に数える(汚染シダ/汚染水草は weight 0 なので
        // そもそも候補プールに入らず、ここで数えても目標個体数の計算には影響しない)。
        // 箱の縦は「足元」と「このパスが湧かせる地面」の両方を覆う(2026-09-24、countBox 参照)。
        BlockPos feet = player.blockPosition();
        int groundY = countGroundY(level, feet);
        AABB countArea = countBox(feet, groundY, countRadius);
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, countArea, m -> {
            MobGroup group = MobGroup.of(m);
            return group != null && group != MobGroup.GROUP_4;
        })) {
            counts.merge(mob.getType(), 1, Integer::sum);
        }
        // グループ4 は invaderDespawnRange の内側では消えない(InvaderDespawn)ので、そこまで数える
        // (2026-09-24、ユーザー承認)。countRadius で数えると、その外の輪でまだ生きている個体のぶんまで
        // 補充してしまい、目標の 2 倍ほどに居座る。種ごとの補充も混成の群れの不足もこの数を使う。
        boolean poolHasInvaders = false;
        for (EntityType<?> type : pool) {
            if (MobGroup.of(type) == MobGroup.GROUP_4) {
                poolHasInvaders = true;
                break;
            }
        }
        if (poolHasInvaders) {
            AABB invaderArea = countBox(feet, groundY, invaderCountRadius(countRadius));
            for (Mob mob : level.getEntitiesOfClass(Mob.class, invaderArea,
                    m -> MobGroup.of(m) == MobGroup.GROUP_4)) {
                counts.merge(mob.getType(), 1, Integer::sum);
            }
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
        // G4 の群れ(群れ v2、2026-09-25)。そのフェーズの編成(swarm.json の templates)に載っている種は種ごとに補充せず、
        // 編成の先頭の種(主力)が足りないときだけ、地上 / 空の種類ごとに 1 群れとして湧かせる(下の spawnSwarm。支援役は群れごとの抽選)。
        // 数えるのは今までどおり 1 体ずつ。編成に載っていない種(F5 のスカトゥリウムなど)は今までどおり種ごと。
        Map<Swarm.Kind, Integer> swarmWant = new EnumMap<>(Swarm.Kind.class);
        Map<Swarm.Kind, Integer> swarmTarget = new EnumMap<>(Swarm.Kind.class);
        for (EntityType<?> type : pool) {
            // TD の侵攻の間は G4 以降でない種(G1〜G3)を湧かせない(td/TdHostiles)。作って取り消されるより、ここで外す方が軽い。
            // 目標の配分(totalWeight)は外す前の一覧のままなので、G4 の目標は変わらない(2026-09-25)。
            if (com.yayuy.overgrownvegetation.td.TdHostiles.blocksSpawn(level, type, feet)) {
                continue;
            }
            // ピースフルでは敵の種を湧かせない(difficulty/Peaceful。2026-09-26)。上と同じく目標の配分は外す前の一覧のままなので、
            // 残る原生生産種の目標は増えない。
            if (com.yayuy.overgrownvegetation.difficulty.Peaceful.blocksSpawn(level, type)) {
                continue;
            }
            OvergrownSpawnProfile.Profile profile = OvergrownSpawnProfile.forType(type);
            Swarm.Kind swarmKind = swarmKindOf(type, phase);
            // 「たまに出てくる程度」の種は、このパスでは候補にすら入れない(目標個体数を下げるのではなく
            // 湧く頻度だけを落とす)。混成の群れの抽選プールからも同時に外れる。
            // 群れに入る種はここでは振らない —— 支援役の spawnChance は群れの編成を振るときに 1 群れごとに効く
            // (SwarmTable.Template#roll。設計 v2「support species rolled per swarm with their spawnChance」)。
            if (swarmKind == null && profile.spawnChance() < 1.0D && random.nextDouble() >= profile.spawnChance()) {
                continue;
            }
            // 比例配分の端数で 0 体になる種(= 自然湧きしない種)を作らないよう、最低 1 体は目標にする。
            // overgrownSpawnTargetTotal を下げたときに少数種がまるごと消えるのを防ぐため。
            // 重みは縁からの深さ d で変えたもの(順 1 の着手順 10。totalWeight も同じ重みの合計)。
            // 「最低 1 体」も帯の重みの確率で(同、ユーザー指定 2026-10-04): 縁では G3 の各種が 15%、奥では G4 が 15% の
            // 確率でだけ床が付く。帯の重みが 1 の端と G1・G5 は今までどおり必ず 1 体。毎回振り直すと、一度湧いた個体が残るので
            // 結局ほとんどいつも 1 体いることになる —— チャンクごと・1 日ごとに決める(floorHolds)。
            int target = (int) Math.round(depthWeight(type, depth) / totalWeight * targetTotal);
            if (target < 1 && floorHolds(level, player, type, depth)) {
                target = 1;
            }
            // 生態系の台帳(2026-09-24): 狩られて豊かさが落ちた区域では、グループ2・3 の目標を豊かさの割合まで減らす
            // (0 体にもなる。グループ2・3 以外はそのまま)。区域はプレイヤーの居場所で見る。
            target = com.yayuy.overgrownvegetation.ecosystem.Ecosystem.scaleSpawnTarget(
                    level, player.blockPosition(), type, target, random);
            int current = counts.getOrDefault(type, 0);
            int want = target - current;

            if (swarmKind != null) {
                // 群れを湧かせるか・主力の頭数は、編成の<b>先頭の種</b>(主力: 地上はアルボラ、空はヴェロフィル)の不足だけで決める。
                // 支援役(スカトゥリウム・スポロフィル・フィロセンス)は 1 群れごとの抽選で付くだけで、不足には数えない
                // (仮決め 2026-09-25、レビュー指摘: 支援役の目標は最低 1 体なので、抽選で出なかった支援役の不足がいつまでも閉じず、
                // 主力が目標を大きく超えても毎パス・プレイヤーごとに群れが湧き続けていた)。
                if (isTemplateLead(type, phase, swarmKind)) {
                    swarmTarget.merge(swarmKind, target, Integer::sum);
                    if (want > 0) {
                        swarmWant.merge(swarmKind, want, Integer::sum);
                    }
                }
                continue;
            }

            MobGroup group = MobGroup.of(type);
            // 空を漂う種は 1 体で流れていくので、群れには混ぜず種ごとの補充へ回す(2026-09-23)。
            if (group != null && phases.isMixedPackGroup(phase, group.number())
                    && !OvergrownSpawnProfile.isSky(type)) {
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

        // G4 の群れ: プレイヤーごとに 1 パス 1 群れまで(maxPerPass とは別枠。設計 v2)。ワールドに読み込まれている群れの
        // 上限(phases.json の maxPacks、0 = 無制限)に達していれば湧かせない。地上と空の両方が足りないときは、
        // 不足の割合が大きい種類から試す(種ごとの補充と同じ考え方。仮決め 2026-09-25)。
        if (!swarmWant.isEmpty() && SwarmManager.budget(level, phase) > 0) {
            List<Swarm.Kind> kinds = new ArrayList<>(swarmWant.keySet());
            kinds.sort((a, b) -> Double.compare(
                    (double) swarmWant.get(b) / Math.max(1, swarmTarget.getOrDefault(b, 1)),
                    (double) swarmWant.get(a) / Math.max(1, swarmTarget.getOrDefault(a, 1))));
            for (Swarm.Kind kind : kinds) {
                if (spawnSwarm(level, player, phase, kind, swarmWant.get(kind), spawnRadius, depth, random)) {
                    break;
                }
            }
        }

        // 先に混成の群れを湧かせる(残り予算の取り合いで潰れないように)。群れに入らない種だけの混成なので
        // 司令塔は持たない(ばらばらの個体。既定の phases.json ではグループ4 だけが混成で、それは上の群れになる)。
        for (Map.Entry<Integer, Integer> entry : packWant.entrySet()) {
            if (budget <= 0) {
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
        }

        if (deficits.isEmpty()) {
            return;
        }
        deficits.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));

        // 種ごとのかたまりは群れではないので、群れの上限(maxPacks)には数えない(旧 PackTracker はかたまりも 1 群れと数えていた。
        // maxPacks は群れ v2 で「読み込まれている群れの上限」になった。swarm-api)。
        for (Deficit d : deficits) {
            if (budget <= 0) {
                break;
            }
            int clusterMax = Math.min(OvergrownSpawnProfile.forType(d.type()).clusterMax(), clusterHardCap);
            int want = Math.min(Math.min(d.want(), budget), Math.max(1, clusterMax));
            int spawned = spawnCluster(level, player, phase, d.type(), want, spawnRadius, random);
            budget -= spawned;
        }
    }

    /**
     * {@code type} が入る群れの種類。群れに入らない種(ペディキュラなど。{@link SwarmRole#of} が null)と、
     * このフェーズの編成({@code swarm.json} の templates)に<b>その種が載っていない</b>ときは null(今までどおり種ごとに補充する)。
     *
     * <p>2026-09-25(レビュー指摘): はじめは「その種類の編成があるか」だけを見ていたので、F5 の地上の編成(アルボラだけ)でも
     * スカトゥリウム・スポロフィル・フィロセンスが群れの側へ回され、群れでは一度も湧かず、種ごとの補充からも外れて
     * <b>F5 で自然に湧かなくなっていた</b>(群れの作業の前は 1 体ずつ湧いていた)。載っていない種は前の湧き方に戻す
     * (仮決め 2026-09-25。F5 の編成に足すかはユーザー確認待ち)。
     */
    @Nullable
    private static Swarm.Kind swarmKindOf(EntityType<?> type, int phase) {
        SwarmRole role = SwarmRole.of(type);
        if (role == null) {
            return null;
        }
        Swarm.Kind kind = role == SwarmRole.AIR ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
        SwarmTable.Template template = DesignData.swarm().template(phase, kind);
        return template != null && templateRow(template, type) >= 0 ? kind : null;
    }

    /** 編成の中でのその種の行(0 = 先頭の主力)。載っていなければ -1。 */
    private static int templateRow(SwarmTable.Template template, EntityType<?> type) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        List<SwarmTable.MemberSpec> members = template.members();
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).id().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** その種がこのフェーズの {@code kind} の編成の先頭(主力)か。 */
    private static boolean isTemplateLead(EntityType<?> type, int phase, Swarm.Kind kind) {
        SwarmTable.Template template = DesignData.swarm().template(phase, kind);
        return template != null && templateRow(template, type) == 0;
    }

    /**
     * G4 の群れを 1 つ湧かせる(群れ v2、2026-09-25)。プレイヤーから {@link #MIN_DISTANCE}〜{@code spawnRadius} の地点を
     * {@link #CENTERS_PER_SPECIES} か所まで試し、読み込み済みで、過繁茂バイオームで、編成の先頭の種(主力)が湧ける環境の地表を中心にする。
     * 編成は {@code swarm.json} の templates を振り(主力の頭数 = 主力の種の不足を {@code [min, max]} に収めたもの)、
     * その環境に湧けない支援役は外す(今までの 1 体ずつの {@code allowedIn} と同じ)。湧かせ方(所属を足す前に書く・置き場所)は
     * {@link SwarmSpawner}。出どころは NATURAL(消えるのは群れごと)。
     *
     * @return 1 体でも湧いたか
     */
    private static boolean spawnSwarm(ServerLevel level, ServerPlayer player, int phase, Swarm.Kind kind, int deficit,
                                      int spawnRadius, double depth, RandomSource random) {
        SwarmTable table = DesignData.swarm();
        SwarmTable.Template template = table.template(phase, kind);
        if (template == null || template.members().isEmpty()) {
            return false;
        }
        EntityType<?> lead = BuiltInRegistries.ENTITY_TYPE.getOptional(template.members().get(0).id()).orElse(null);
        if (lead == null) {
            return false;
        }
        for (int c = 0; c < CENTERS_PER_SPECIES; c++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = MIN_DISTANCE + random.nextInt(spawnRadius - MIN_DISTANCE + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            // 読み込み済みのチャンクだけを見る(読み込みの途中のチャンクを読むとメインスレッドが生成を待つ。2026-09-24 の監査)。
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            BlockPos center = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (center == null) {
                continue;
            }
            Holder<Biome> biome = level.getBiome(center);
            if (!ModBiomes.isFullyOvergrown(biome) || !OvergrownSpawnProfile.allowedIn(biome, phase, lead)) {
                continue;
            }
            List<EntityType<?>> types = new ArrayList<>();
            // 森の奥では群れを小さくする: 主力の頭数に主力の帯の重みを掛ける(最低 1 体。順 1 の着手順 10、ユーザー指定 2026-10-04)。
            // 主力は G4 なので縁寄り —— d 12 で 0.15 倍(アルボラ 1 体前後)、縁では今までどおり
            for (EntityType<?> type : template.roll(random, deficit, table.maxSize(kind), bandWeight(lead, depth))) {
                if (type == lead || OvergrownSpawnProfile.allowedIn(biome, phase, type)) {
                    types.add(type);
                }
            }
            if (SwarmSpawner.spawn(level, center, types, SwarmOrigin.NATURAL, "", null).spawned()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「混成の群れ」を 1 つ湧かせる(2026-09-15、既定はフェーズ6以降のグループ4)。1 箇所を中心に、
     * {@code size} 体ぶん<b>種を毎回抽選しながら</b>湧かせるので、同じ群れに別種が混ざる。
     * 抽選は {@link OvergrownSpawnProfile} の weight に比例する(= 雑魚枠が多く、支援・索敵役が少ない)。
     * 群れ v2(2026-09-25)からは、群れに入る G4 はここを通らず {@link #spawnSwarm} で湧く。ここに来るのは
     * {@code phases.json} の {@code mixedPacks} に G4 以外のグループを足したときだけ(司令塔の無いばらばらの個体)。
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

        int spawned = 0;
        // 地表が見つからない座標を引くこともあるので、体数より少し多めに試す。
        for (int i = 0; i < size + 4 && spawned < size; i++) {
            EntityType<?> type = pickWeighted(packPool, totalWeight, random);
            if (type == null) {
                continue;
            }
            int x = centerX + random.nextInt(7) - 3;
            int z = centerZ + random.nextInt(7) - 3;
            if (spawnOne(level, type, x, z, phase)) {
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
        int spawned = 0;
        for (int c = 0; c < CENTERS_PER_SPECIES && spawned < want; c++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = MIN_DISTANCE + random.nextInt(spawnRadius - MIN_DISTANCE + 1);
            int centerX = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int centerZ = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            for (int i = 0; i < want - spawned + 2 && spawned < want; i++) {
                int x = centerX + random.nextInt(7) - 3;
                int z = centerZ + random.nextInt(7) - 3;
                if (spawnOne(level, type, x, z, phase)) {
                    spawned++;
                }
            }
        }
        return spawned;
    }

    /**
     * {@code (x, z)} 列に {@code type} を 1 体湧かせる。水生種は水中、上空を漂う種は空、
     * それ以外は {@code mobs.json} の {@code spawns.depth}(地上 / 地下)に従う。
     *
     * <p>両方に湧く種は<b>半々で振り分ける</b>(2026-09-26 ユーザー指定「両方とも選択できるように」)。
     * 地下を引いて空洞が見つからなければその 1 回は空振りで、次の試行でまた抽選し直す。
     *
     * <p>4 つの経路(地上・空・水中・洞窟)とも、世界に足す直前に種ごとの関門
     * {@link com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck} を通す(2026-10-01。false なら捨てて空振り)。
     */
    private static boolean spawnOne(ServerLevel level, EntityType<?> type, int x, int z, int phase) {
        if (OvergrownSpawnProfile.isAquatic(type)) {
            return spawnOneAquatic(level, type, x, z, phase);
        }
        if (OvergrownSpawnProfile.isSky(type)) {
            return spawnOneSky(level, type, x, z, phase);
        }
        boolean cave = com.yayuy.overgrownvegetation.data.MobStats.spawnsInCaves(type);
        boolean surface = com.yayuy.overgrownvegetation.data.MobStats.spawnsOnSurface(type);
        if (cave && (!surface || level.random.nextBoolean())) {
            return spawnOneCave(level, type, x, z, phase);
        }
        if (!surface) {
            return false; // 地上も地下も外してある = この経路では湧かない
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
        // 種ごとの最後の関門(2026-10-01。OvergrownSpawnCheck。位置を動かしてもよい)。
        if (!com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck.accept(spawned, level)) {
            spawned.discard();
            return false;
        }
        level.addFreshEntity(spawned);
        return true;
    }

    /**
     * {@code (x, z)} 列の<b>上空</b>に {@code type}(空を漂う種)を 1 体湧かせる(2026-09-23)。
     * 高さは地表(葉・水面を含む)から mobs.json の {@code drift.cruiseAltitude}。
     * バイオームの判定は真下の地表で行う(地表の湧きと同じ基準)。
     *
     * <p>群れ番号は付けない —— 1 体で漂う種なので、{@code maxPacks} の勘定に数えない。
     *
     * <p>チャンクは<b>読み込み済みのものだけ</b>を読む({@code getChunkNow})。{@code hasChunk} は読み込みの途中でも
     * true で、そのまま高さやバイオームを読むとメインスレッドが生成を待って止まる(2026-09-24 の重さの監査)。
     */
    private static boolean spawnOneSky(ServerLevel level, EntityType<?> type, int x, int z, int phase) {
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = chunk.getNoiseBiome(
                net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(surface),
                net.minecraft.core.QuartPos.fromBlock(z));
        if (!ModBiomes.isFullyOvergrown(biome) || !OvergrownSpawnProfile.allowedIn(biome, phase, type)) {
            return false;
        }
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        double y = Math.min(surface + com.yayuy.overgrownvegetation.data.MobStats.drift(path).cruiseAltitude(),
                level.getMaxBuildHeight() - type.getHeight() - 1.0D);
        if (y < surface || !SpawnFit.fitsAt(level, type, x + 0.5D, y, z + 0.5D)) {
            return false;
        }
        Entity spawned = type.create(level, null, BlockPos.containing(x + 0.5D, y, z + 0.5D),
                MobSpawnType.NATURAL, false, false);
        if (spawned == null) {
            return false;
        }
        spawned.moveTo(x + 0.5D, y, z + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        if (!com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck.accept(spawned, level)) {
            spawned.discard();
            return false;
        }
        level.addFreshEntity(spawned);
        return true;
    }

    /**
     * {@code (x, z)} 列の過繁茂した海/川バイオームの水中に {@code type}(水生種)を 1 体湧かせる。
     * 水面と水底の間のランダムな深さ、水深 2 以上の場所にのみ湧かせる。
     */
    private static boolean spawnOneAquatic(ServerLevel level, EntityType<?> type, int x, int z, int phase) {
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
        if (!com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck.accept(spawned, level)) {
            spawned.discard();
            return false;
        }
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
    private static boolean spawnOneCave(ServerLevel level, EntityType<?> type, int x, int z, int phase) {
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
        if (!com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck.accept(spawned, level)) {
            spawned.discard();
            return false;
        }
        level.addFreshEntity(spawned);
        return true;
    }

    private OvergrownBiomeSpawner() {
    }
}
