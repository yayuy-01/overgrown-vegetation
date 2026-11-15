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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 司令塔の空いていた口に、今ある G4・G5 をつなぐ(2026-11-15)。起動のときに 1 回だけ登録する。
 * <ul>
 *   <li>苗床から孵す({@link ForestDirector#registerNurserySpawn}): 表の「司令塔の口に入れる地上の G4・G5」。G5 は F5 まで孵さない(buySpawn がグループのゲートを見る)。</li>
 *   <li>拠点への送り込み({@link BaseRaids#registerSender}): 拠点のまわりにいる個体を先に歩かせ、足りない分は森の方角の見えない地面に湧かせて歩かせる
 *   (司令塔の予算から costSpawn × 段。11-15 全体管理)。F6(混ざった群れ)からは G4 と G5 を同じ所に固めて出す。</li>
 *   <li>歩かせる({@link BaseRaids#registerMarcher}): {@link PlantMarch}。</li>
 * </ul>
 * 空の種(velophyll・seminaria・pedicula・acumnes)は表で {@code director: false}。最初は縁だけ(11-15 全体管理)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
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
        BaseRaids.registerSender(true, (level, base, count, random) -> send(level, base, count, random, 4));
        BaseRaids.registerSender(false, (level, base, count, random) -> send(level, base, count, random, 5));
        BaseRaids.registerMarcher(PlantMarch::begin);
    }

    /** 司令塔の口に入れる種か(G4・G5 の、地上に住む、director が false でない種)。 */
    static boolean directorSpecies(SpawnTable.Species sp) {
        return sp.director() && sp.g1() == null && (sp.group() == 4 || sp.group() == 5) && sp.habitats().contains(SpawnTable.Habitat.GROUND);
    }

    /** その種を拠点へ歩かせられるか。 */
    public static boolean marchable(EntityType<?> type) {
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (directorSpecies(sp) && sp.type() == type) return true;
        }
        return false;
    }

    /** その種のグループの番号(グループの無い種は 4)。 */
    public static int groupOf(EntityType<?> type) {
        com.yayuy.overgrownvegetation.phase.MobGroup g = com.yayuy.overgrownvegetation.phase.MobGroup.of(type);
        return g == null ? 4 : g.number();
    }

    /** そのグループの、送り込みのゲート。 */
    public static DirectorGates.Gate gateOf(int group) {
        return group >= 6 ? DirectorGates.Gate.WAVE_MIXED : group == 5 ? DirectorGates.Gate.WAVE_G5 : DirectorGates.Gate.WAVE_G4;
    }

    /**
     * 司令塔が出せる G4・G5 を 1 種選ぶ(group 0 = どちらでも)。そのグループの送り込みのゲートが開いている種だけ、重み × 段の強さで。無ければ null。
     */
    @Nullable
    public static EntityType<?> pickPlant(ServerLevel level, RandomSource random, int group) {
        List<EntityType<?>> cands = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double total = 0.0D;
        for (SpawnTable.Species sp : DesignData.spawns().species) {
            if (!directorSpecies(sp) || group != 0 && sp.group() != group) continue;
            DirectorGates.Gate gate = gateOf(sp.group());
            if (!DirectorGates.open(level, gate)) continue;
            EntityType<?> type = sp.type();
            double weight = sp.weight() * DirectorGates.strength(level, gate);
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

    /** 送り込みで最後に湧かせた所(混ざった群れ: 同じ回の G4 と G5 を同じ所に固める)。 */
    private static BlockPos lastSpot;
    private static long lastSpotTime = -1L;
    private static int lastSpotBase = -1;

    /** 確かめ用: 送り込みで歩かせた数・湧かせた数。 */
    public static long sentExisting, sentSpawned;

    /** G4(group 4)か G5(group 5)を count 体まで拠点へ送る。送れた数を返す。 */
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
                sent++;
                sentExisting++;
            }
        }
        // 足りない分は森の方角に湧かせる(予算から)
        ForestDirector d = ForestDirector.get(level);
        DirectorGates.Gate gate = gateOf(group);
        BlockPos spot = null;
        if (DirectorGates.open(level, DirectorGates.Gate.WAVE_MIXED) && lastSpot != null && lastSpotTime == level.getGameTime() && lastSpotBase == b.id()) {
            spot = lastSpot;
        }
        int attempts = (count - sent) * 2;
        while (sent < count && attempts-- > 0) {
            EntityType<?> type = pickPlant(level, random, group);
            if (type == null) break;
            double price = ForestDirector.stagePrice(ForestDirector.spawnCost(type), DirectorGates.strength(level, gate));
            if (d.available(level) + 1.0E-9D < price) break;
            if (spot == null) spot = hiddenSpot(level, b, random);
            if (spot == null) break;
            Mob m = RaidSpawns.spawnPlantAttacker(level, near(level, spot, random), type, random);
            if (m == null) continue;
            d.tryPay(level, price);
            PlantMarch.begin(m, b);
            sent++;
            sentSpawned++;
        }
        if (spot != null) {
            lastSpot = spot;
            lastSpotTime = level.getGameTime();
            lastSpotBase = b.id();
        }
        return sent;
    }

    /** テスト用: 送り込みで湧かせる所を決め打ちにする(null で戻す。テスト場の高さの地図は天井のバリアを答えるため)。 */
    @Nullable
    private static java.util.function.Supplier<BlockPos> spotOverride;

    public static void setSpotForTests(@Nullable java.util.function.Supplier<BlockPos> p) {
        spotOverride = p;
    }

    /** 森の方角の、プレイヤーから見えない地面(大きな襲撃の湧き方と同じ口)。 */
    @Nullable
    public static BlockPos hiddenSpot(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (spotOverride != null) return spotOverride.get();
        SpawnTable t = DesignData.spawns();
        Vec3 away = ForestEdge.of(level).away(new Vec3(b.centerX(), 64.0D, b.centerZ()));
        double angle = away != null ? Math.atan2(-away.z, -away.x) : random.nextDouble() * Math.PI * 2.0D;
        return RaidSpawns.findHiddenSpot(level, b.centerX(), b.centerZ(), angle, t.senderSpread, t.senderMin, t.senderMax, t.senderPlayerMin, t.senderTries, random);
    }

    /** 固まって出す: 真ん中から clusterBlocks の中の地面(見つからなければ真ん中)。 */
    private static BlockPos near(ServerLevel level, BlockPos c, RandomSource random) {
        double s = DesignData.spawns().senderCluster;
        if (s < 1.0D) return c;
        int x = c.getX() + (int) Math.round((random.nextDouble() * 2.0D - 1.0D) * s), z = c.getZ() + (int) Math.round((random.nextDouble() * 2.0D - 1.0D) * s);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return Math.abs(y - c.getY()) <= 3 ? new BlockPos(x, y, z) : c;
    }
}
