package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 過繫茂系バイオームの<b>外</b>へ、拡大種を少数だけ湧かせる(2026-09-19)。
 * 既定では<b>フェーズ2〜4 のアルボラ</b>が対象で、汚染がまだ狭いうちから「はぐれ」として
 * 普通のバイオームにも現れるようにするためのもの。
 *
 * <p>2026-09-19: 同じ仕組みで<b>媒介種(グループ1)</b>も外に湧かせる({@code carrierSpawns}、
 * 既定はフェーズ2〜6)。媒介種は普通の Mob を襲って胞子感染させ({@code ParasitizedTargetGoal} /
 * {@code ParasitizedAttackInfectionHandler})、感染した Mob は死んで死体ブロック/汚染シダになる
 * ({@code SporeInfectionDeathHandler})= <b>汚染を運ぶ役</b>。枠(頭数の上限・確率・距離)は
 * 拡大種のはぐれとは別に数えるので、互いの枠を食い合わない。
 *
 * <p>{@link OvergrownBiomeSpawner}(過繫茂バイオームの<b>中</b>の生態系)とは別処理で、
 * 住み分けは湧かせる場所だけ:
 * <ul>
 *     <li>あちらは「過繫茂バイオームの中」にしか湧かせず、{@code spawns.overgrown} の全種を
 *     目標個体数まで補充する。</li>
 *     <li>こちらは「過繫茂バイオーム<b>ではない</b>地点」にしか湧かせず、
 *     {@code mobs.json} の {@code spawns.outside} に書いた種を、{@code phases.json} の
 *     {@code outsideSpawns} / {@code carrierSpawns} の枠(プレイヤー周辺に {@code maxNearby} 体まで、
 *     {@code chance} の確率)でぽつぽつ湧かせる。</li>
 * </ul>
 *
 * <p>湧かせた個体は {@link MobSpawnType#NATURAL}・非永続なので、プレイヤーが離れれば
 * 拡大種の通常のデスポーン規則({@code InvaderDespawn})で消える(家畜ベースの媒介種は
 * {@code Animal} なので消えず、その一帯に留まる)。判定間隔は生態系スポーンと
 * 同じ {@code overgrownSpawnIntervalTicks}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OutsideInvaderSpawner {

    /** 1 パスで試す地点の数(地表が見つからない・過繫茂バイオームだった等で空振りするため)。 */
    private static final int ATTEMPTS = 6;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        int interval = Math.max(1, OvergrownVegetationConfig.COMMON.overgrownSpawnIntervalTicks.get());
        if (level.getGameTime() % interval != 0L) {
            return;
        }
        int phase = PhaseData.get(level).getPhase();
        if (phase < 1) {
            return;
        }

        PhaseTable phases = DesignData.phases();
        // 拡大種のはぐれ(outsideSpawns)と媒介種(carrierSpawns)は別枠で数える。
        // 湧かせる種は mobs.json の spawns.outside(2026-09-21)。媒介種(グループ1)とそれ以外は
        // 頭数の枠を分けて数えるので、ここでプールを 2 つに割る。
        List<EntityType<?>> outside = new ArrayList<>();
        List<EntityType<?>> carriers = new ArrayList<>();
        for (EntityType<?> type : MobStats.outsideSpawnPool(phase)) {
            MobGroup group = MobGroup.of(type);
            (group == MobGroup.GROUP_1 ? carriers : outside).add(type);
        }
        runPool(level, phases.outsideSpawns(phase), outside);
        runPool(level, phases.carrierSpawns(phase), carriers);
    }

    private static void runPool(ServerLevel level, PhaseTable.OutsideSpawnSettings settings,
                                List<EntityType<?>> pool) {
        if (!settings.enabled() || pool.isEmpty()) {
            return;
        }
        double chance = settings.chance();
        int maxNearby = settings.maxNearby();
        int minDistance = settings.minDistance();
        int maxDistance = settings.maxDistance();
        RandomSource random = level.random;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (random.nextDouble() >= chance) {
                continue;
            }
            // プレイヤー周辺に既に居る対象種を数える(湧かせる範囲と同じ広さ)。
            // 確率判定を先に通しているので、この検索が走るのは既定で 8 回に 1 回ほど。
            AABB area = new AABB(player.blockPosition()).inflate(maxDistance);
            int nearby = level.getEntitiesOfClass(Mob.class, area, m -> pool.contains(m.getType())).size();
            if (nearby >= maxNearby) {
                continue;
            }
            EntityType<?> type = pool.get(random.nextInt(pool.size()));
            trySpawnOutside(level, player, type, minDistance, maxDistance, random);
        }
    }

    /**
     * id の一覧を EntityType に解決する({@code phases.json} の {@code outsideSpawns.types} /
     * {@code carrierSpawns.types} / 小規模レイドの編成が同じ書き方)。
     * 未登録の id と、{@code mobs.json} で {@code naturalSpawn: false} にした種は除く。
     */
    public static List<EntityType<?>> poolFor(List<String> ids) {
        List<EntityType<?>> pool = new ArrayList<>();
        for (String raw : ids) {
            EntityType<?> type = resolve(raw);
            if (type != null && MobStats.naturalSpawn(type) && !pool.contains(type)) {
                pool.add(type);
            }
        }
        return pool;
    }

    /** 名前空間省略時は overgrown_vegetation 扱い(predation.json と同じ書き方)。未登録なら null。 */
    @javax.annotation.Nullable
    public static EntityType<?> resolve(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            return null;
        }
        if (id.getNamespace().equals("minecraft") && !raw.contains(":")) {
            id = OvergrownVegetation.id(id.getPath());
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    /**
     * プレイヤーから {@code minDistance}〜{@code maxDistance} の輪の中で、
     * <b>過繫茂系バイオームではない</b>地表を探して 1 体湧かせる。
     */
    private static boolean trySpawnOutside(ServerLevel level, ServerPlayer player, EntityType<?> type,
                                           int minDistance, int maxDistance, RandomSource random) {
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!level.hasChunkAt(new BlockPos(x, level.getSeaLevel(), z))) {
                continue; // 未ロードのチャンクには湧かせない(地形の読み込みを誘発しない)
            }

            BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (spawnPos == null) {
                continue;
            }
            // ここが本題 — 過繫茂系バイオーム(繁茂段階も含む)の中では湧かせない。
            // 中は OvergrownBiomeSpawner の担当で、こちらは「その外」専用。
            if (ModBiomes.isOvergrownLike(level.getBiome(spawnPos))) {
                continue;
            }
            if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
                continue;
            }
            if (!SpawnFit.fits(level, type, spawnPos)) {
                continue;
            }

            Entity spawned = type.create(level, null, spawnPos, MobSpawnType.NATURAL, false, false);
            if (spawned == null) {
                return false;
            }
            level.addFreshEntity(spawned);
            return true;
        }
        return false;
    }

    private OutsideInvaderSpawner() {
    }
}
