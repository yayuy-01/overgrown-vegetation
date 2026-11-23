package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseRaids;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 司令塔の空いていた口に、今ある G2・G3 をつなぐ(2026-11-15)。起動のときに 1 回だけ登録する。
 * <ul>
 *   <li>苗床から孵す({@link ForestDirector#registerNurserySpawn}): 表の「司令塔の口に入れる地上の G2・G3」。G3 は F5 まで孵さない(buySpawn がグループのゲートを見る)。</li>
 *   <li>2026-11-23 片付け E: 拠点への送り込み(まわりの G2・G3 を歩かせ、足りない分を森の方角に湧かせる・ゲスタトリクスを混ぜる)は消した。拠点へは前線から出す。</li>
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
}
