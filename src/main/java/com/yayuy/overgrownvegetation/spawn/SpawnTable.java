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
        public Boolean fernNaturalSpawn;
        public Integer openG1;
        public EdgeJson edge;
        public UnderJson underground;
        public SenderJson sender;
        public List<SpeciesJson> species;
        public FogLurkerJson fogLurker;
    }

    public static final class FogLurkerJson {
        public Boolean enabled;
        public Double minFog, meanSeconds, minBlocks, maxBlocks, wakeBlocks;
        public Integer tries;
        public java.util.Map<String, Double> weights;
    }

    /** 怖い現象 E の「たまに本物」(11-16。{@link FogLurkers})。数はどれも【仮】。 */
    public static final class FogLurker {
        public final boolean enabled;
        public final double minFog, meanSeconds, minBlocks, maxBlocks, wakeBlocks;
        public final int tries;
        /** 種の id → 重み(並びを保つ)。 */
        public final java.util.Map<String, Double> weights;

        FogLurker(@Nullable FogLurkerJson j) {
            FogLurkerJson s = j != null ? j : new FogLurkerJson();
            this.enabled = s.enabled == null || s.enabled;
            this.minFog = s.minFog != null ? s.minFog : 0.5D;
            this.meanSeconds = Math.max(1.0D, s.meanSeconds != null ? s.meanSeconds : 900.0D);
            this.minBlocks = Math.max(8.0D, s.minBlocks != null ? s.minBlocks : 29.0D);
            this.maxBlocks = Math.max(this.minBlocks, s.maxBlocks != null ? s.maxBlocks : 37.0D);
            this.wakeBlocks = Math.max(1.0D, s.wakeBlocks != null ? s.wakeBlocks : 12.0D);
            this.tries = Math.max(1, s.tries != null ? s.tries : 12);
            java.util.Map<String, Double> w = new java.util.LinkedHashMap<>();
            if (s.weights != null && !s.weights.isEmpty()) {
                s.weights.forEach((k, v) -> w.put(k.contains(":") ? k : OvergrownVegetation.MODID + ":" + k, Math.max(0.0D, v == null ? 0.0D : v)));
            } else {
                w.put(OvergrownVegetation.MODID + ":clipeimnes", 2.0D);
                w.put(OvergrownVegetation.MODID + ":falcimnes", 1.0D);
            }
            this.weights = java.util.Collections.unmodifiableMap(w);
        }
    }

    public static final class EdgeJson {
        public Integer intervalTicks, triesPerPlayer, minBlocks, maxBlocks, countRadius, despawnBlocks, skyMinAbove, skyMaxAbove, caveMinDepth;
        public Integer g1Band, g45Band, rebuildTicks;
        public Map<String, Integer> caps;
    }

    public static final class UnderJson {
        public Integer cap, triesPerPlayer, rangeChunks, nearChunks, minDepth;
    }

    public static final class SenderJson {
        public Double spawnMinBlocks, spawnMaxBlocks, spreadDeg, playerMinBlocks, clusterBlocks;
        public Integer tries;
    }

    public static final class PackJson {
        public Integer min, max;
        public List<PackWithJson> with;
    }

    public static final class PackWithJson {
        public String id;
        public Integer min, max;
    }

    /** 群れに混ぜる種(id と頭数の幅)。 */
    public record PackWith(String id, int min, int max) {
        @Nullable
        public EntityType<?> type() {
            return TYPES.computeIfAbsent(id, k -> {
                ResourceLocation rl = ResourceLocation.tryParse(k);
                return java.util.Optional.ofNullable(rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null));
            }).orElse(null);
        }
    }

    public static final class SpeciesJson {
        public String id;
        public String g1;
        public Integer group;
        public List<String> habitats;
        public Double weight;
        public Boolean director;
        public Boolean breaker;
        /** 群れで湧く頭数(この種の分)と、混ぜる種。 */
        public PackJson pack;
    }

    /** 表の 1 行。type は登録の有無を起動後に引く(登録の無い id は null = 飛ばす)。 */
    public record Species(@Nullable String id, @Nullable G1Species g1, int group, EnumSet<Habitat> habitats, double weight, boolean director, boolean breaker,
                          int packMin, int packMax, List<PackWith> packWith) {
        /** 群れで湧く種か(頭数の上が 2 以上)。 */
        public boolean packs() {
            return packMax >= 2 || !packWith.isEmpty();
        }

        /** 登録された型(起動後は変わらないので、1 回引いたら取っておく。見直し ②10: 毎 tick 登録表を引いていた)。 */
        @Nullable
        public EntityType<?> type() {
            if (g1 != null) return g1.entityType();
            return TYPES.computeIfAbsent(id, k -> {
                ResourceLocation rl = ResourceLocation.tryParse(k);
                return java.util.Optional.ofNullable(rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null));
            }).orElse(null);
        }

        public String label() {
            return g1 != null ? "g1:" + g1.key() : id;
        }
    }

    /** id → 登録された型(取っておく)。 */
    private static final java.util.Map<String, java.util.Optional<EntityType<?>>> TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    public final boolean enabled;
    /** 萌芽体(汚染シダ)の自然発生(地割れなど NATURAL の湧き)を許すか。mobs.json の naturalSpawn の代わり(見直し ④、2026-11-16)。 */
    public final boolean fernNaturalSpawn;
    /** 怖い現象 E の「たまに本物」。 */
    public final FogLurker fogLurker;
    /** G1 の縁と地下の湧きが開くフェーズ(G4・G5 は director.json の gates。11-16 見直しで 1 か所にまとめた)。 */
    public final int openG1;
    private final int[] caps = new int[11];

    public final int intervalTicks, triesPerPlayer, minBlocks, maxBlocks, countRadius, despawnBlocks, skyMinAbove, skyMaxAbove, caveMinDepth;
    /** 森からの距離の帯(11-17): G1 は外へ 1〜g1Band チャンク・G4/G5 は内外 g45Band チャンク。名簿の作り直しの間(tick)。 */
    public final int g1Band, g45Band, rebuildTicks;
    public final int underCap, underTries, underRangeChunks, underNearChunks, underMinDepth;
    public final double senderMin, senderMax, senderSpread, senderPlayerMin, senderCluster;
    public final int senderTries;
    public final List<Species> species;

    public static final SpawnTable DEFAULT = fromJson(null);

    private SpawnTable(Json j) {
        this.enabled = j.enabled == null || j.enabled;
        this.fernNaturalSpawn = j.fernNaturalSpawn == null || j.fernNaturalSpawn;
        this.fogLurker = new FogLurker(j.fogLurker);
        this.openG1 = Math.max(1, j.openG1 != null ? j.openG1 : 1);
        EdgeJson e = j.edge != null ? j.edge : new EdgeJson();
        caps[1] = 15;
        caps[4] = 15;
        caps[5] = 10;
        if (e.caps != null) e.caps.forEach((k, v) -> put(caps, k, v));
        intervalTicks = Math.max(1, or(e.intervalTicks, 20));
        g1Band = Math.max(1, Math.min(BandRoster.R - 1, or(e.g1Band, 16)));
        g45Band = Math.max(1, Math.min(BandRoster.R - 1, or(e.g45Band, 4)));
        rebuildTicks = Math.max(20, or(e.rebuildTicks, 100));
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
        return new Species(id, g1, Math.max(1, Math.min(10, sj.group)), hs, Math.max(0.0D, or(sj.weight, 1.0D)), sj.director == null || sj.director, sj.breaker != null && sj.breaker,
                sj.pack == null ? 1 : Math.max(1, or(sj.pack.min, 1)), sj.pack == null ? 1 : Math.max(Math.max(1, or(sj.pack.min, 1)), or(sj.pack.max, 1)), packWith(sj.pack));
    }

    private static List<PackWith> packWith(@Nullable PackJson p) {
        if (p == null || p.with == null) return List.of();
        List<PackWith> out = new ArrayList<>();
        for (PackWithJson w : p.with) {
            if (w == null || w.id == null || w.id.isEmpty()) continue;
            String id = w.id.contains(":") ? w.id : OvergrownVegetation.MODID + ":" + w.id;
            int min = Math.max(0, or(w.min, 0));
            out.add(new PackWith(id, min, Math.max(min, or(w.max, min))));
        }
        return List.copyOf(out);
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

    /**
     * そのグループの縁・地下の湧きが開くフェーズ。G1 は {@link #openG1}、G4 以降は司令塔の送り込みのゲート(director.json の gates: G4 waveG4・G5 waveG5・
     * G6 以降 waveMixed)と同じ(11-16 見直し: 前は spawns.json の openAt と 2 か所にあった)。G2・G3 は開かない。
     */
    public int openAt(int group) {
        if (group == 1) return openG1;
        if (group < 4 || group > 10) return Integer.MAX_VALUE;
        return com.yayuy.overgrownvegetation.director.DirectorGates.openAt(com.yayuy.overgrownvegetation.data.DesignData.director().gates, DirectorHooks.gateOf(group));
    }

    /** そのグループの縁の上限(最盛の値)。 */
    public int cap(int group) {
        return group >= 1 && group <= 10 ? caps[group] : 0;
    }
}
