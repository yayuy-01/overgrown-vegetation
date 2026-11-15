package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 敵の湧き方の設計値({@code design/spawns.json}、2026-11-15)。正本は Vault「敵の湧き方(縁・地下・司令塔)(11-15)」。数はどれも【仮】。
 */
public final class SpawnTable {

    /** 住む場所(11-15 ユーザー: 地上は地上・空は縁の上空・海は海に面した縁・洞窟は縁の下の洞窟・地中は地中)。 */
    public enum Habitat {
        GROUND, SKY, SEA, CAVE, SOIL;

        @Nullable
        static Habitat byName(String s) {
            for (Habitat h : values()) {
                if (h.name().equalsIgnoreCase(s)) return h;
            }
            return null;
        }
    }

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Map<String, Integer> openAt;
        public EdgeJson edge;
        public UnderJson underground;
        public SenderJson sender;
        public List<SpeciesJson> species;
    }

    public static final class EdgeJson {
        public Integer intervalTicks, triesPerPlayer, minBlocks, maxBlocks, countRadius, despawnBlocks, skyMinAbove, skyMaxAbove, caveMinDepth;
        public Map<String, Integer> caps;
    }

    public static final class UnderJson {
        public Integer cap, triesPerPlayer, rangeChunks, nearChunks, minDepth;
    }

    public static final class SenderJson {
        public Double spawnMinBlocks, spawnMaxBlocks, spreadDeg, playerMinBlocks, clusterBlocks;
        public Integer tries;
    }

    public static final class SpeciesJson {
        public String id;
        public String g1;
        public Integer group;
        public List<String> habitats;
        public Double weight;
        public Boolean director;
        public Boolean breaker;
    }

    /** 表の 1 行。type は登録の有無を起動後に引く(登録の無い id は null = 飛ばす)。 */
    public record Species(@Nullable String id, @Nullable G1Species g1, int group, EnumSet<Habitat> habitats, double weight, boolean director, boolean breaker) {
        @Nullable
        public EntityType<?> type() {
            if (g1 != null) return g1.entityType();
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
        }

        public String label() {
            return g1 != null ? "g1:" + g1.key() : id;
        }
    }

    public final boolean enabled;
    private final int[] openAt = new int[11];
    private final int[] caps = new int[11];

    public final int intervalTicks, triesPerPlayer, minBlocks, maxBlocks, countRadius, despawnBlocks, skyMinAbove, skyMaxAbove, caveMinDepth;
    public final int underCap, underTries, underRangeChunks, underNearChunks, underMinDepth;
    public final double senderMin, senderMax, senderSpread, senderPlayerMin, senderCluster;
    public final int senderTries;
    public final List<Species> species;

    public static final SpawnTable DEFAULT = fromJson(null);

    private SpawnTable(Json j) {
        this.enabled = j.enabled == null || j.enabled;
        for (int g = 0; g < 11; g++) openAt[g] = Integer.MAX_VALUE;
        openAt[1] = 1;
        openAt[4] = 4;
        openAt[5] = 5;
        if (j.openAt != null) j.openAt.forEach((k, v) -> put(openAt, k, v));
        EdgeJson e = j.edge != null ? j.edge : new EdgeJson();
        caps[1] = 15;
        caps[4] = 15;
        caps[5] = 10;
        if (e.caps != null) e.caps.forEach((k, v) -> put(caps, k, v));
        intervalTicks = Math.max(1, or(e.intervalTicks, 20));
        triesPerPlayer = Math.max(0, or(e.triesPerPlayer, 4));
        minBlocks = Math.max(0, or(e.minBlocks, 24));
        maxBlocks = Math.max(minBlocks + 16, or(e.maxBlocks, 96));
        countRadius = Math.max(16, or(e.countRadius, 128));
        despawnBlocks = Math.max(32, or(e.despawnBlocks, 128));
        skyMinAbove = Math.max(2, or(e.skyMinAbove, 12));
        skyMaxAbove = Math.max(skyMinAbove, or(e.skyMaxAbove, 28));
        caveMinDepth = Math.max(2, or(e.caveMinDepth, 8));
        UnderJson u = j.underground != null ? j.underground : new UnderJson();
        underCap = Math.max(0, or(u.cap, 10));
        underTries = Math.max(0, or(u.triesPerPlayer, 2));
        underRangeChunks = Math.max(1, or(u.rangeChunks, 6));
        underNearChunks = Math.max(0, or(u.nearChunks, 1));
        underMinDepth = Math.max(2, or(u.minDepth, 8));
        SenderJson s = j.sender != null ? j.sender : new SenderJson();
        senderMin = Math.max(8.0D, or(s.spawnMinBlocks, 40.0D));
        senderMax = Math.max(senderMin, or(s.spawnMaxBlocks, 64.0D));
        senderSpread = Math.max(0.0D, or(s.spreadDeg, 40.0D));
        senderPlayerMin = Math.max(0.0D, or(s.playerMinBlocks, 24.0D));
        senderCluster = Math.max(0.0D, or(s.clusterBlocks, 4.0D));
        senderTries = Math.max(1, or(s.tries, 12));
        List<Species> list = new ArrayList<>();
        if (j.species != null) {
            for (SpeciesJson sj : j.species) {
                Species sp = parse(sj);
                if (sp != null) list.add(sp);
            }
        }
        this.species = List.copyOf(list);
    }

    @Nullable
    private static Species parse(SpeciesJson sj) {
        if (sj == null || sj.group == null) return null;
        G1Species g1 = null;
        if (sj.g1 != null && !sj.g1.isEmpty()) {
            for (G1Species s : G1Species.values()) {
                if (s.key().equals(sj.g1)) g1 = s;
            }
            if (g1 == null) {
                OvergrownVegetation.LOGGER.warn("spawns.json: G1 の種 {} が見つかりません", sj.g1);
                return null;
            }
        } else if (sj.id == null || sj.id.isEmpty()) {
            return null;
        }
        EnumSet<Habitat> hs = EnumSet.noneOf(Habitat.class);
        if (sj.habitats != null) {
            for (String h : sj.habitats) {
                Habitat hab = h == null ? null : Habitat.byName(h.toLowerCase(Locale.ROOT));
                if (hab != null) hs.add(hab);
            }
        }
        if (hs.isEmpty()) hs.add(Habitat.GROUND);
        String id = g1 != null ? null : (sj.id.contains(":") ? sj.id : OvergrownVegetation.MODID + ":" + sj.id);
        return new Species(id, g1, Math.max(1, Math.min(10, sj.group)), hs, Math.max(0.0D, or(sj.weight, 1.0D)), sj.director == null || sj.director, sj.breaker != null && sj.breaker);
    }

    private static void put(int[] arr, String k, Integer v) {
        try {
            int g = Integer.parseInt(k);
            if (g >= 1 && g <= 10 && v != null) arr[g] = v;
        } catch (NumberFormatException ignored) {
        }
    }

    private static int or(Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(Double v, double d) {
        return v != null ? v : d;
    }

    public static SpawnTable fromJson(@Nullable Json j) {
        return new SpawnTable(j != null ? j : new Json());
    }

    /** そのグループの縁・地下の湧きが開くフェーズ(表に無いグループは開かない)。 */
    public int openAt(int group) {
        return group >= 1 && group <= 10 ? openAt[group] : Integer.MAX_VALUE;
    }

    /** そのグループの縁の上限(最盛の値)。 */
    public int cap(int group) {
        return group >= 1 && group <= 10 ? caps[group] : 0;
    }
}
