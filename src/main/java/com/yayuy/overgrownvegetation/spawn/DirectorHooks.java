package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.ForestEdge;
import com.yayuy.overgrownvegetation.director.RaidSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerAboutToStartEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 司令塔の空いていた口に、今ある G2・G3 をつなぐ(2026-11-15)。起動のときに 1 回だけ登録する。
 * <ul>
 *   <li>苗床から孵す({@link ForestDirector#registerNurserySpawn}): 表の「司令塔の口に入れる地上の G2・G3」。G3 は F5 まで孵さない(buySpawn がグループのゲートを見る)。</li>
 *   <li>拠点への送り込み({@link BaseRaids#registerSender}): 拠点のまわりにいる個体を先に歩かせ、足りない分は森の方角の見えない地面に湧かせて歩かせる
 *   (司令塔の予算から costSpawn × 段。11-15 全体管理)。F6(混ざった群れ)からは G2 と G3 を同じ所に固めて出す。</li>
 *   <li>歩かせる({@link BaseRaids#registerMarcher}): {@link PlantMarch}。</li>
 * </ul>
 * 空の種(velophyll・seminaria・pedicula・acumnes)は表で {@code director: false}。最初は縁だけ(11-15 全体管理)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class DirectorHooks {

    private static boolean registered;

    private DirectorHooks() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        register();
    }

    /** 1 回だけ登録する(二度目は何もしない)。 */
    @SuppressWarnings("unchecked")
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (!directorSpecies(sp)) continue;
            EntityType<?> type = sp.type();
            if (type == null) continue;
            EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) type;
            ForestDirector.registerNurserySpawn(() -> mobType);
        }
        BaseRaids.registerSender(true, (level, base, count, random) -> send(level, base, count, random, 2));
        BaseRaids.registerSender(false, (level, base, count, random) -> send(level, base, count, random, 3));
        BaseRaids.registerMarcher(PlantMarch::begin);
    }

    /** 司令塔の口に入れる種か(G2・G3 の、地上に住む、director が false でない種)。 */
    static boolean directorSpecies(SpawnTable.Species sp) {
        return sp.director() && sp.g1() == null && (sp.group() == 2 || sp.group() == 3) && sp.habitats().contains(SpawnTable.Habitat.GROUND);
    }

    /** その種は壁や扉を壊すか(spawns.json の breaker。11-16)。 */
    public static boolean breaker(EntityType<?> type) {
        return sets().breakers.contains(type);
    }

    /** 種の表から作った「壊す種」「歩かせる種」の組(表が替わったら作り直す。見直し ②10: 毎 tick 表をなめていた)。 */
    private record Sets(SpawnTable table, java.util.Set<EntityType<?>> breakers, java.util.Set<EntityType<?>> marchers, java.util.Set<EntityType<?>> followers,
                        java.util.Map<EntityType<?>, SpawnTable.Role> roles) {
    }

    /** 外から作る群れ(11-17)に入って先導役に付いていく種か(群れで湧く種と混ぜる種。グレガリアは自前の群れなので除く)。 */
    public static boolean packFollower(EntityType<?> type) {
        return sets().followers.contains(type);
    }

    /** 群れの中の役割(spawns.json の role。無ければ null = 司令塔は群れにまとめない。11-17 深夜)。 */
    @Nullable
    public static SpawnTable.Role roleOf(EntityType<?> type) {
        return sets().roles.get(type);
    }

    private static volatile Sets sets;

    private static Sets sets() {
        SpawnTable t = DesignData.spawns();
        Sets s = sets;
        if (s != null && s.table() == t) return s;
        java.util.Set<EntityType<?>> b = new java.util.HashSet<>(), m = new java.util.HashSet<>(), f = new java.util.HashSet<>();
        java.util.Map<EntityType<?>, SpawnTable.Role> r = new java.util.HashMap<>();
        for (SpawnTable.Species sp : t.species) {
            EntityType<?> type = sp.type();
            if (type == null) continue;
            if (sp.breaker()) b.add(type);
            if (directorSpecies(sp)) m.add(type);
            if (sp.packs()) {
                f.add(type);
                for (SpawnTable.PackWith w : sp.packWith()) if (w.type() != null) f.add(w.type());
            }
            if (sp.role() != null && sp.habitats().contains(SpawnTable.Habitat.GROUND)) r.put(type, sp.role());
        }
        f.remove(com.yayuy.overgrownvegetation.registry.ModCrawlers.GREGARIA.get());
        f.addAll(r.keySet()); // 司令塔がまとめた群れ(11-17 深夜)に入る種も付いていく(グレガリアもまとめられたら外の群れへ移る)
        s = new Sets(t, java.util.Set.copyOf(b), java.util.Set.copyOf(m), java.util.Set.copyOf(f), java.util.Map.copyOf(r));
        sets = s;
        return s;
    }

    /** その種を拠点へ歩かせられるか。 */
    public static boolean marchable(EntityType<?> type) {
        return sets().marchers.contains(type);
    }

    /**
     * 司令塔が森から湧かせる G1 の段階 0 を 1 種選ぶ(送り込み・大きな襲撃・偵察。11-16 見直し: 重みは spawns.json の種の表 1 か所)。
     * 選ぶのは、表の G1 のうち地上に住み、director が false でない種(村人・エンダーマン・チビゾンビ・鶏は出さない)。重みは表の weight。
     */
    public static com.yayuy.overgrownvegetation.corpse.G1Species pickG1(RandomSource random) {
        double total = 0.0D;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (sp.g1() != null && sp.director() && sp.habitats().contains(SpawnTable.Habitat.GROUND)) total += sp.weight();
        }
        double r = random.nextDouble() * total;
        com.yayuy.overgrownvegetation.corpse.G1Species last = com.yayuy.overgrownvegetation.corpse.G1Species.ZOMBIE;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (sp.g1() == null || !sp.director() || !sp.habitats().contains(SpawnTable.Habitat.GROUND)) continue;
            last = sp.g1();
            r -= sp.weight();
            if (r <= 0.0D) return sp.g1();
        }
        return last;
    }

    /** その種のグループの番号(グループの無い種は 4)。 */
    public static int groupOf(EntityType<?> type) {
        com.yayuy.overgrownvegetation.phase.MobGroup g = com.yayuy.overgrownvegetation.phase.MobGroup.of(type);
        return g == null ? 2 : g.number();
    }

    /** そのグループの、送り込みのゲート。 */
    public static DirectorGates.Gate gateOf(int group) {
        return group >= 4 ? DirectorGates.Gate.WAVE_MIXED : group == 3 ? DirectorGates.Gate.WAVE_G3 : DirectorGates.Gate.WAVE_G2;
    }

    /**
     * 司令塔が出せる G2・G3 を 1 種選ぶ(group 0 = どちらでも)。そのグループの送り込みのゲートが開いている種だけ、重み × 段の強さで。無ければ null。
     */
    @Nullable
    public static EntityType<?> pickPlant(ServerLevel level, RandomSource random, int group) {
        List<EntityType<?>> cands = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double total = 0.0D;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (!directorSpecies(sp) || group != 0 && sp.group() != group) continue;
            EntityType<?> type = sp.type();
            // 11-18 夜 ユーザー決定: 出るフェーズは種ごと(mob_class.json の phase。前はグループの送り込みのゲート)
            if (!DirectorGates.speciesOpen(level, type)) continue;
            double weight = sp.weight() * DirectorGates.speciesStrength(level, type);
            if (type == null || weight <= 0.0D) continue;
            cands.add(type);
            w.add(weight);
            total += weight;
        }
        if (cands.isEmpty()) return null;
        double r = random.nextDouble() * total;
        for (int i = 0; i < cands.size(); i++) {
            r -= w.get(i);
            if (r <= 0.0D) return cands.get(i);
        }
        return cands.get(cands.size() - 1);
    }

    /** 送り込みで最後に湧かせた所(混ざった群れ: 同じ回の G2 と G3 を同じ所に固める)。 */
    private static BlockPos lastSpot;
    private static long lastSpotTime = -1L;
    private static int lastSpotBase = -1;

    /** 確かめ用: 送り込みで歩かせた数・湧かせた数。 */
    public static long sentExisting, sentSpawned;

    /** G2(group 2)か G3(group 3)を count 体まで拠点へ送る。送れた数を返す。 */
    public static int send(ServerLevel level, BaseMemory.Base b, int count, RandomSource random, int group) {
        if (count <= 0) return 0;
        BaseMemoryTable bt = DesignData.director().base;
        double r = bt.raidSearchBlocks;
        AABB box = new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r);
        List<Mob> near = new ArrayList<>();
        for (Mob m : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && !PlantMarch.active(m) && marchable(m.getType()))) {
            if (com.yayuy.overgrownvegetation.phase.MobGroup.of(m) == null || com.yayuy.overgrownvegetation.phase.MobGroup.of(m).number() != group) continue;
            double dx = m.getX() - b.centerX(), dz = m.getZ() - b.centerZ(), d2 = dx * dx + dz * dz;
            if (d2 > r * r || d2 < bt.raidFromMinBlocks * bt.raidFromMinBlocks || !level.isPositionEntityTicking(m.blockPosition())) continue;
            near.add(m);
        }
        Collections.shuffle(near, new java.util.Random(random.nextLong()));
        int sent = 0;
        for (Mob m : near) {
            if (sent >= count) break;
            if (PlantMarch.begin(m, b)) {
                tagWave(m);
                sent++;
                sentExisting++;
            }
        }
        // 足りない分は森の方角に湧かせる(予算から)
        ForestDirector d = ForestDirector.get(level);
        BlockPos spot = null;
        if (DirectorGates.open(level, DirectorGates.Gate.WAVE_MIXED) && lastSpot != null && lastSpotTime == level.getGameTime() && lastSpotBase == b.id()) {
            spot = lastSpot;
        }
        int spawnedNow = 0;
        // ゲスタトリクスを混ぜる回は、G2 の分を 1 つ空けておく(送る数 count を超えない。大きな襲撃の同時の上限を守る)
        boolean mix = group == 2 && count - sent >= 2 && random.nextDouble() < DesignData.spawns().gestatrix.senderChance;
        int g2Limit = mix ? count - 1 : count;
        int attempts = (count - sent) * 2;
        while (sent < g2Limit && attempts-- > 0) {
            EntityType<?> type = pickPlant(level, random, group);
            if (type == null) break;
            double price = ForestDirector.stagePrice(ForestDirector.spawnCost(type), DirectorGates.speciesStrength(level, type));
            if (d.available(level) + 1.0E-9D < price) break;
            if (spot == null) spot = hiddenSpot(level, b, random);
            if (spot == null) break;
            Mob m = RaidSpawns.spawnPlantAttacker(level, near(level, spot, random), type, random);
            if (m == null) continue;
            d.tryPay(level, price);
            PlantMarch.begin(m, b);
            tagWave(m);
            sent++;
            sentSpawned++;
            spawnedNow++;
        }
        if (mix && spawnedNow > 0 && spot != null && mixGestatrix(level, b, spot, random, d)) sent++;
        if (spot != null) {
            lastSpot = spot;
            lastSpotTime = level.getGameTime();
            lastSpotBase = b.id();
        }
        return sent;
    }

    /** 確かめ用: 送り込みに混ぜたゲスタトリクスの数。 */
    public static long sentGestatrix;

    /**
     * 送り込みで G2 を森の方角から湧かせた回に、ゲスタトリクス(G4・歩く苗床)を 1 匹混ぜる(11-17。確率 {@code gestatrix.senderChance}【仮】は呼ぶ側で引き、送る数のうち 1 つをこれに充てる)。
     * 拠点のまわり(raidSearchBlocks)の、根付いていないゲスタトリクスが edge.caps の 6 以上なら混ぜない。予算は G4 の値段 × 段の強さ。
     * 拠点へ歩かせ(MarchGoal ではなく自前の歩き)、拠点のそばで腰を据えて産む。
     */
    static boolean mixGestatrix(ServerLevel level, BaseMemory.Base b, BlockPos spot, RandomSource random, ForestDirector d) {
        EntityType<?> type = com.yayuy.overgrownvegetation.registry.ModCrawlers.GESTATRIX.get();
        double r = DesignData.director().base.raidSearchBlocks;
        AABB box = new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r);
        int cap = Math.max(1, DesignData.spawns().cap(4));
        if (level.getEntitiesOfClass(com.yayuy.overgrownvegetation.entity.GestatrixEntity.class, box, g -> g.isAlive() && !g.rooted()).size() >= cap) return false;
        if (!DirectorGates.speciesOpen(level, type)) return false;   // ゲスタトリクスの出るフェーズ(F4。11-18 夜)
        double price = ForestDirector.stagePrice(ForestDirector.spawnCost(type), DirectorGates.speciesStrength(level, type));
        if (d.available(level) + 1.0E-9D < price) return false;
        Mob m = RaidSpawns.spawnPlantAttacker(level, near(level, spot, random), type, random);
        if (!(m instanceof com.yayuy.overgrownvegetation.entity.GestatrixEntity g)) return false;
        d.tryPay(level, price);
        g.sendTo(b);
        tagWave(m);
        sentGestatrix++;
        return true;
    }

    /** テスト用: 送り込みで湧かせる所を決め打ちにする(null で戻す。テスト場の高さの地図は天井のバリアを答えるため)。 */
    @Nullable
    private static java.util.function.Supplier<BlockPos> spotOverride;

    public static void setSpotForTests(@Nullable java.util.function.Supplier<BlockPos> p) {
        spotOverride = p;
    }

    /** 送り込みの印(貯めた襲撃の継ぎ足しが数える)を付ける。 */
    private static void tagWave(Mob m) {
        String tag = BaseRaids.waveTag();
        if (tag != null) m.addTag(tag);
    }

    /** 森の方角の、プレイヤーから見えない地面(大きな襲撃の湧き方と同じ口)。 */
    @Nullable
    public static BlockPos hiddenSpot(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (spotOverride != null) return spotOverride.get();
        SpawnTable t = DesignData.spawns();
        Vec3 away = ForestEdge.of(level).away(new Vec3(b.centerX(), 64.0D, b.centerZ()));
        double angle = away != null ? Math.atan2(-away.z, -away.x) : random.nextDouble() * Math.PI * 2.0D;
        return RaidSpawns.findHiddenSpotWidening(level, b.centerX(), b.centerZ(), angle, t.senderSpread, t.senderMin, t.senderMax, t.senderPlayerMin, t.senderTries, random);
    }

    /** 固まって出す: 真ん中から clusterBlocks の中の地面(見つからなければ真ん中)。 */
    public static BlockPos near(ServerLevel level, BlockPos c, RandomSource random) {
        double s = DesignData.spawns().senderCluster;
        if (s < 1.0D) return c;
        int x = c.getX() + (int) Math.round((random.nextDouble() * 2.0D - 1.0D) * s), z = c.getZ() + (int) Math.round((random.nextDouble() * 2.0D - 1.0D) * s);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return Math.abs(y - c.getY()) <= 3 ? new BlockPos(x, y, z) : c;
    }
}
