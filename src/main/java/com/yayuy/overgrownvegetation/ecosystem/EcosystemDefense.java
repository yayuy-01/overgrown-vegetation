package com.yayuy.overgrownvegetation.ecosystem;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EcosystemTable;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.SpawnFit;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventSwarms;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
import com.yayuy.overgrownvegetation.swarm.Trail;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>生態系防衛</b>(2026-09-24、ユーザー指定)。狩られて豊かさが落ちた区域に、植物がグループ4 以降の兵を送り込む。
 *
 * <ul>
 *     <li>区域の豊かさが {@code defense.thresholds}(既定 70% / 40% / 15%)を下回ると、段階 1 / 2 / 3 に入る。
 *     段階が深いほど<b>強いグループまで</b>出る(既定: 段階 1 = グループ4、段階 2 = +グループ5、段階 3 = +グループ6)。
 *     グループ4 以降は番号が上がるほど強いので、「荒らすほど強い兵が来る」になる。</li>
 *     <li><b>1 体ごとにバイオマスを使う</b>(ユーザー指定: グループ4 = 100 / 5 = 1500 / 6 = 4500)。
 *     蓄えが足りなければ 1 つ下のグループを出し、何も払えなければ出さない
 *     ({@link EcosystemMath#chooseDefenderGroup})。狩り続けると植物の蓄えが尽きて守りも弱っていく。</li>
 *     <li>出すのは<b>その区域の中にいるプレイヤーの周り</b>だけ(Mob は読み込まれた場所にしか置けない)。
 *     プレイヤーから {@code minDistance}〜{@code maxDistance} の輪の中で、<b>同じ区域の過繁茂バイオーム</b>の地表。</li>
 *     <li>「今のフェーズで解禁されているグループだけ」—— その種が今のフェーズに過繁茂バイオームで湧く設定のとき
 *     ({@code mobs.json} の {@code spawns}。{@code naturalSpawn: false} の種も出さない)。
 *     既定ではグループ4 がフェーズ5 から、グループ5 がフェーズ7 から。グループ6 はまだ種がいない。</li>
 *     <li>空・水・洞窟の種(ペディキュラなど)と、生態系スポーンの weight が 0 の種(汚染シダ・レイドビーコン)は出さない。</li>
 * </ul>
 *
 * <p>出した個体には {@link #DEFENDER_TAG} を付け、「今いる防衛の兵」はこの印で数える(生態系スポーンで
 * 湧いたグループ4 は数えない)。消え方は各種の普段の規則のまま(グループ4 の群れは群れごと、群れに居ない個体は {@code InvaderDespawn})。
 * 払ったバイオマスは戻らない。
 *
 * <h2>G4 は防衛の群れになる(群れ v2、2026-09-25)</h2>
 * 出す間隔・1 回 1 体・段階ごとの上限・バイオマスの値段は今までどおり。変わったのは出し方だけ:
 * <ul>
 *     <li>そのプレイヤーの<b>防衛の群れ</b>(出どころ {@code DEFENSE}、イベント ID = プレイヤーの UUID。地上 / 空で別)がまだ無い・
 *     空きが無い({@link SwarmManager#hasRoom})なら、今までどおり輪の中に 1 体湧かせて<b>新しい防衛の群れ</b>を作り、
 *     そのプレイヤーを群れの標的に指定する(狩りと同じ扱い: 距離の上限なし、死亡・観戦・クリエイティブ・長く不在で諦める)。</li>
 *     <li>空きのある防衛の群れがあれば、地上の群れは<b>その群れの足跡のいちばん後ろの少し後ろ</b>に湧かせて群れに加える
 *     (以前のように輪のあちこちに出すと、後から来た 1 体が経路の無いまま密林を横切って群れを追うことになり、詰まって外れる。
 *     レビュー指摘)。空の群れ(ヴェロフィル)は飛んで追いつけるので輪の中に湧かせて加える。</li>
 *     <li>イベント ID をプレイヤーごとにしてあるので、別のプレイヤーの防衛の群れ・自然の群れとは合流しない(残党の合流の鍵)。
 *     仮決め 2026-09-25。</li>
 *     <li>足跡の後ろは<b>過繁茂バイオームでなくても</b>置く(群れはプレイヤーを追ってバイオームの外にいることがある。
 *     読み込み済み・乾いている・体が入る・足跡から高さ ±6 は見る)。そこに置けなければ輪の中に新しい群れを作る。仮決め 2026-09-25。</li>
 *     <li>G5・G6(群れに入らない種)は今までどおり 1 体ずつの個体で、湧いてすぐそのプレイヤーを狙う。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class EcosystemDefense {

    /** 防衛の兵に付ける印(エンティティのタグ。セーブに残る)。 */
    public static final String DEFENDER_TAG = OvergrownVegetation.MODID + ".eco_defender";

    /** 1 体を湧かせるために試す地点の数(区域の外・過繁茂でない・地表が無いで空振りするため)。 */
    private static final int ATTEMPTS = 8;
    /** 足跡の後ろに置く場所を探す回数(後ろの点のまわり ±{@link #BEHIND_JITTER})。 */
    private static final int BEHIND_ATTEMPTS = 6;
    private static final int BEHIND_JITTER = 2;
    /** 足跡の後ろに置くとき、足跡の点から地面までの高さの差の上限(ブロック)。仮決め 2026-09-25。 */
    private static final int BEHIND_MAX_DY = 6;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Ecosystem.isLedgerLevel(level)) {
            return;
        }
        EcosystemTable table = DesignData.ecosystem();
        // 位相をずらして、湧き・地形の周期処理と同じ tick に重ならないようにする(2026-09-24 軽量化、報告 1516。
        // 湧かせる頻度と 1 回の数は同じで、走る tick が周期の中でずれるだけ)。
        if (!com.yayuy.overgrownvegetation.util.TickPhases.isDue(level.getGameTime(),
                com.yayuy.overgrownvegetation.util.TickPhases.ECOSYSTEM_DEFENSE, table.defenseIntervalTicks())
                || level.players().isEmpty()) {
            return;
        }
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.is(level)) {
            return; // ピースフルでは防衛の兵(グループ4 以降 = 敵)を出さない(difficulty/Peaceful。2026-09-26)
        }
        PhaseData phaseData = PhaseData.get(level);
        int phase = phaseData.getPhase();
        if (phase < 1) {
            return;
        }
        EcosystemData data = EcosystemData.get(level.getServer());
        Int2ObjectOpenHashMap<List<EntityType<?>>> candidates = new Int2ObjectOpenHashMap<>();
        RandomSource random = level.random;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            if (com.yayuy.overgrownvegetation.td.TdEvents.suppressesEcosystemDefense(level, player.blockPosition())) {
                // TD の侵攻(予告・侵攻中)のターゲットの近く(仮決め 256)では防衛の兵を出さない(TD 設計 v2)。
                // 侵攻の攻め手と重なって上限・財布を二重に使わないように。
                continue;
            }
            long region = Ecosystem.regionKeyAt(player.blockPosition());
            if (Ecosystem.coveredChunks(phaseData, region) == 0) {
                continue; // まだ過繁茂が届いていない区域に生態系は無い
            }
            int tier = data.tier(level.getGameTime(), table.recoveryPerTick(), table.thresholds(), table.hysteresis());
            if (tier <= 0) {
                continue;
            }
            Int2IntOpenHashMap current = countDefenders(level, player, table.countRadius());
            int group = EcosystemMath.chooseDefenderGroup(tier, current::get, data.biomass(), table,
                    g -> !candidates.computeIfAbsent(g, k -> candidatesFor(k, phase)).isEmpty());
            if (group == 0) {
                continue;
            }
            EntityType<?> type = pickWeighted(candidates.get(group), random);
            if (type != null && spawnDefender(level, player, region, type, table, random)) {
                data.spend(table.cost(group));
            }
        }
    }

    /**
     * グループ {@code group} のうち、今のフェーズに防衛の兵として出せる種。
     * 過繁茂バイオームのどこかに今のフェーズで湧く種(= 解禁済み)で、地表に湧く種だけ。
     */
    public static List<EntityType<?>> candidatesFor(int group, int phase) {
        MobGroup g = MobGroup.byNumber(group);
        if (g == null) {
            return List.of();
        }
        List<EntityType<?>> unlocked = MobStats.overgrownSpawnPool(phase);
        List<EntityType<?>> out = new ArrayList<>();
        for (EntityType<?> type : g.members()) {
            if (unlocked.contains(type) && OvergrownSpawnProfile.weight(type) > 0.0D
                    && !OvergrownSpawnProfile.isSky(type) && !OvergrownSpawnProfile.isAquatic(type)
                    && !OvergrownSpawnProfile.isCave(type) && !out.contains(type)) {
                out.add(type);
            }
        }
        return out;
    }

    /**
     * プレイヤーの周りにいる防衛の兵を、グループごとに数える。
     *
     * <p>数え方は生態系スポーンと同じ(2026-09-24、ユーザー承認。{@link OvergrownBiomeSpawner#countBox}):
     * 箱の縦は足元から湧かせる地面まで伸ばし(高所・地下に居る間に地面へ出した兵を数え漏らして
     * バイオマスを使い続けないように)、グループ4 は {@code invaderDespawnRange} まで数える
     * (その内側では消えないので、ゲームが消すまでは数に入れておく)。
     */
    private static Int2IntOpenHashMap countDefenders(ServerLevel level, ServerPlayer player, int radius) {
        Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
        BlockPos feet = player.blockPosition();
        int groundY = OvergrownBiomeSpawner.countGroundY(level, feet);
        AABB area = OvergrownBiomeSpawner.countBox(feet, groundY, radius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area,
                m -> m.getTags().contains(DEFENDER_TAG) && MobGroup.of(m.getType()) != MobGroup.GROUP_4)) {
            MobGroup group = MobGroup.of(mob.getType());
            if (group != null) {
                counts.addTo(group.number(), 1);
            }
        }
        AABB invaderArea = OvergrownBiomeSpawner.countBox(feet, groundY,
                OvergrownBiomeSpawner.invaderCountRadius(radius));
        int invaders = level.getEntitiesOfClass(Mob.class, invaderArea,
                m -> m.getTags().contains(DEFENDER_TAG) && MobGroup.of(m.getType()) == MobGroup.GROUP_4).size();
        if (invaders > 0) {
            counts.addTo(MobGroup.GROUP_4.number(), invaders);
        }
        return counts;
    }

    /** 生態系スポーンの weight に比例して 1 種選ぶ(アルボラが多く、支援・索敵役が少ない)。 */
    @Nullable
    private static EntityType<?> pickWeighted(List<EntityType<?>> pool, RandomSource random) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        double total = 0.0D;
        for (EntityType<?> type : pool) {
            total += OvergrownSpawnProfile.weight(type);
        }
        double roll = random.nextDouble() * total;
        for (EntityType<?> type : pool) {
            roll -= OvergrownSpawnProfile.weight(type);
            if (roll <= 0.0D) {
                return type;
            }
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 防衛の兵を 1 体出し、そのプレイヤーを狙わせる。G4(群れに入る種)は防衛の群れへ(クラスの説明)、
     * それ以外は今までどおり輪の中の個体。
     *
     * @return 1 体出せた(バイオマスを払う)
     */
    private static boolean spawnDefender(ServerLevel level, ServerPlayer player, long region, EntityType<?> type,
                                         EcosystemTable table, RandomSource random) {
        SwarmRole role = SwarmRole.of(type);
        if (role == null) {
            BlockPos spawnPos = ringSpawnPos(level, player, region, type, table, random);
            if (spawnPos == null) {
                return false;
            }
            Entity spawned = type.create(level, null, spawnPos, MobSpawnType.EVENT, false, false);
            if (spawned == null) {
                return false;
            }
            // 種ごとの最後の関門(2026-10-01。OvergrownSpawnCheck。断られたら出さずバイオマスも払わない)。
            if (!com.yayuy.overgrownvegetation.entity.OvergrownSpawnCheck.accept(spawned, level)) {
                spawned.discard();
                return false;
            }
            spawned.addTag(DEFENDER_TAG);
            if (!level.addFreshEntity(spawned)) {
                // 入れなかった(TD の侵攻の範囲では G4 以降でない敵対 Mob を湧かせない。td/TdHostiles)。バイオマスは払わない。
                return false;
            }
            // 世界に置いてから狙わせる(標的の変更イベントは世界にいる個体へ向けたもの)。
            if (spawned instanceof Mob mob) {
                mob.setTarget(player);
            }
            return true;
        }

        Swarm.Kind kind = role == SwarmRole.AIR ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
        String eventId = defenseEventId(player);
        Swarm swarm = defenseSwarmWithRoom(level, player, kind, eventId);
        if (swarm != null) {
            BlockPos joinPos = kind == Swarm.Kind.GROUND
                    ? behindSwarm(level, swarm, player, type, random)
                    : ringSpawnPos(level, player, region, type, table, random);
            Mob mob = joinPos != null
                    ? EventSwarms.spawnJoining(level, type, joinPos, swarm, m -> m.addTag(DEFENDER_TAG))
                    : null;
            if (mob != null) {
                if (SwarmManager.get(mob) == null) {
                    // 群れに入れなかった(上限)。ふつうの個体として、今までどおりそのプレイヤーを狙う。
                    mob.setTarget(player);
                } else if (swarm.target() != player && !swarm.hasGivenUp(player)) {
                    // 読み込み直しで狩りの相手を忘れた群れ(狙いは保存していない)にも、そのプレイヤーを思い出させる。
                    swarm.commandTarget(player);
                }
                return true;
            }
            // 足跡の後ろに置けなければ、輪の中に新しい防衛の群れを作る(出す間隔を落とさない)。
        }
        BlockPos spawnPos = ringSpawnPos(level, player, region, type, table, random);
        if (spawnPos == null) {
            return false;
        }
        return SwarmSpawner.spawn(level, spawnPos, List.of(type), SwarmOrigin.DEFENSE, eventId, player,
                m -> m.addTag(DEFENDER_TAG)).spawned();
    }

    /** 防衛の群れのイベント ID = 狙うプレイヤーの UUID(プレイヤーごとに別の群れ。残党の合流の鍵にもなる)。 */
    private static String defenseEventId(ServerPlayer player) {
        return player.getUUID().toString();
    }

    /**
     * そのプレイヤーの防衛の群れ(種類 {@code kind})のうち、1 体入る空きがあるもの。いくつかあればプレイヤーにいちばん近いもの。
     * 無ければ null。
     */
    @Nullable
    private static Swarm defenseSwarmWithRoom(ServerLevel level, ServerPlayer player, Swarm.Kind kind, String eventId) {
        Swarm best = null;
        double bestSq = Double.MAX_VALUE;
        for (Swarm swarm : SwarmManager.swarms(level)) {
            if (swarm.origin() != SwarmOrigin.DEFENSE || swarm.kind() != kind || swarm.isEmpty()
                    || !eventId.equals(swarm.eventId()) || !SwarmManager.hasRoom(swarm, 1)) {
                continue;
            }
            Mob anchor = anchorOf(swarm);
            if (anchor == null) {
                continue;
            }
            double sq = anchor.distanceToSqr(player);
            if (sq < bestSq) {
                bestSq = sq;
                best = swarm;
            }
        }
        return best;
    }

    /** 群れの位置の目安(先導役、いなければ動いている一員の 1 体目)。 */
    @Nullable
    private static Mob anchorOf(Swarm swarm) {
        Mob vanguard = swarm.vanguard();
        if (vanguard != null && vanguard.isAlive()) {
            return vanguard;
        }
        for (Mob mob : swarm.activeMembers()) {
            if (mob.isAlive()) {
                return mob;
            }
        }
        return null;
    }

    /**
     * 地上の防衛の群れの<b>後ろ</b>(足跡のいちばん後ろの一員の陣形の位置より、列の間隔 2 つぶん後ろ)に置ける地面。
     * 足跡がまだ無ければ(湧いた直後)、群れの位置からプレイヤーと反対側へ同じ距離。
     * 読み込み済み・足元と頭が乾いている・体が入る・足跡の点から高さ ±{@link #BEHIND_MAX_DY} のところだけ。無ければ null。
     */
    @Nullable
    private static BlockPos behindSwarm(ServerLevel level, Swarm swarm, ServerPlayer player, EntityType<?> type,
                                        RandomSource random) {
        Mob anchor = anchorOf(swarm);
        if (anchor == null) {
            return null;
        }
        double spacing = DesignData.swarm().formationSpacing();
        Trail trail = swarm.trail();
        // 「後ろ」の向き(足跡が 1 点しか無いとき): 群れはプレイヤーを追っているので、プレイヤーと反対側。
        Vec3 back = new Vec3(anchor.getX() - player.getX(), 0.0D, anchor.getZ() - player.getZ());
        if (back.lengthSqr() < 1.0E-6D) {
            back = new Vec3(0.0D, 0.0D, 1.0D);
        }
        double depth = 0.0D;
        for (Mob mob : swarm.activeMembers()) {
            long seq = swarm.formationSeq(mob);
            if (seq >= 0L && trail.has(seq)) {
                depth = Math.max(depth, trail.lengthFromNewest(seq));
            }
        }
        depth += spacing * 2.0D;
        Vec3 base = trail.isEmpty()
                ? anchor.position().add(back.normalize().scale(depth))
                : trail.behindNewest(depth, back).pos();
        for (int i = 0; i < BEHIND_ATTEMPTS; i++) {
            int x = (int) Math.floor(base.x) + (i == 0 ? 0 : random.nextInt(BEHIND_JITTER * 2 + 1) - BEHIND_JITTER);
            int z = (int) Math.floor(base.z) + (i == 0 ? 0 : random.nextInt(BEHIND_JITTER * 2 + 1) - BEHIND_JITTER);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (spawnPos == null || Math.abs(spawnPos.getY() - base.y) > BEHIND_MAX_DY) {
                continue;
            }
            if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
                continue;
            }
            if (SpawnFit.fits(level, type, spawnPos)) {
                return spawnPos;
            }
        }
        return null;
    }

    /**
     * プレイヤーから {@code minDistance}〜{@code maxDistance} の輪の中で、<b>同じ区域の過繁茂バイオーム</b>の地表
     * (乾いていて体が入る)。見つからなければ null。
     */
    @Nullable
    private static BlockPos ringSpawnPos(ServerLevel level, ServerPlayer player, long region, EntityType<?> type,
                                         EcosystemTable table, RandomSource random) {
        int min = table.minDistance();
        int max = table.maxDistance();
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = min + random.nextInt(max - min + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos column = new BlockPos(x, level.getSeaLevel(), z);
            // 区域の外 / 読み込みが済んでいない(地形の読み込みを誘発しない。群れの湧かせ方 SwarmSpawner と同じ getChunkNow の基準。
            // hasChunkAt は読み込みの途中でも true で、そこに決めると群れの 1 体目が置けずに終わる。2026-09-25)
            if (Ecosystem.regionKeyAt(column) != region || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                continue;
            }
            BlockPos spawnPos = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
            if (spawnPos == null || !ModBiomes.isFullyOvergrown(level.getBiome(spawnPos))) {
                continue;
            }
            if (!level.getFluidState(spawnPos).isEmpty() || !level.getFluidState(spawnPos.above()).isEmpty()) {
                continue;
            }
            if (!SpawnFit.fits(level, type, spawnPos)) {
                continue;
            }
            return spawnPos;
        }
        return null;
    }

    private EcosystemDefense() {
    }
}
