package com.yayuy.overgrownvegetation.research;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>研究ツリー</b>の中身(2026-11-17 強くなる道 決定 20・22・23)。設計値は {@code design/research_tree.json}。
 *
 * <p>節を開くのに払うのは、持ち帰った<b>標本</b>・<b>地脈の力</b>・<b>時間</b>(決定 20)。節が開くと、{@code unlocks} の作り方が
 * 作業台で作れるようになる({@code mixin.CraftingMenuResearchLockMixin})。数・名前はどれも【仮】。
 *
 * <p>読み方は {@code DesignData} と同じ(config の写し → 無ければ classpath から写す。GameTest のサーバーは classpath を直に読む)。
 * 共有の {@code DesignData} を太らせないよう、ここで自分で読む。サーバーは画面を開くたびに JSON の文字列をクライアントへ送り、
 * クライアントはそれを {@link #parse} する(サーバーの config と画面の中身が食い違わないように)。
 */
public final class ResearchTree {

    public static final String FILE = "research_tree.json";
    private static final String RESOURCE = "/overgrown_vegetation/design/" + FILE;
    private static final Gson GSON = new GsonBuilder().create();

    /** 行(画面の縦の並び)。 */
    public static final List<String> LANES = List.of("armor", "material", "melee", "ranged");

    // ---- JSON の形 ----

    public static final class Json {
        public Map<String, TierJson> tiers = new LinkedHashMap<>();
        public double instrument_radius = 4;
        public int bench_buffer = 400_000;
        public int bench_max_receive = 2_000;
        public Map<String, NodeJson> nodes = new LinkedHashMap<>();
    }

    public static final class TierJson {
        public int fe_per_tick = 10;
        public int ticks = 2000;
        /** この段の研究に要る器具のブロック id(研究台のそばに置く)。空なら要らない。 */
        public List<String> instruments = new ArrayList<>();
    }

    public static final class NodeJson {
        public int tier;
        public String lane = "material";
        public double col;
        public String icon = "";
        public List<String> requires = new ArrayList<>();
        public List<SpecimenJson> specimens = new ArrayList<>();
        public List<String> unlocks = new ArrayList<>();
        public Integer fe_per_tick;
        public Integer ticks;
        /** 器具の節(各段の最初の門。決定 23 の答え 11-17 夜): どの段の器具で調べるか。書かなければその節の段。器具の節は 1 つ前の段の器具で調べる。 */
        public Integer instruments_of_tier;
    }

    public static final class SpecimenJson {
        public String item = "";
        public int count = 1;
    }

    // ---- 読んだ形 ----

    public record Tier(int fePerTick, int ticks, List<ResourceLocation> instruments) {
    }

    public record Specimen(ResourceLocation item, int count) {
        /** その物がこの版にあるか(段 3 の標本は森の器官と一緒に来るまで無い)。 */
        public boolean exists() {
            return BuiltInRegistries.ITEM.containsKey(item);
        }

        public Item asItem() {
            return exists() ? BuiltInRegistries.ITEM.get(item) : Items.AIR;
        }
    }

    public record Node(String id, int tier, String lane, double col, ResourceLocation icon, List<String> requires,
                       List<Specimen> specimens, List<ResourceLocation> unlocks, int fePerTick, int ticks,
                       int instrumentTier) {
        public boolean isRoot() {
            return tier == 0;
        }

        public long totalFe() {
            return (long) fePerTick * ticks;
        }

        /** 標本がすべてこの版にあるか。 */
        public boolean specimensExist() {
            return specimens.stream().allMatch(Specimen::exists);
        }
    }

    private final String json;
    private final Map<String, Node> nodes;
    private final Map<Integer, Tier> tiers;
    private final double instrumentRadius;
    private final int benchBuffer;
    private final int benchMaxReceive;
    private final Map<ResourceLocation, String> lockedBy;

    private ResearchTree(String json, Json j) {
        this.json = json;
        Map<Integer, Tier> t = new HashMap<>();
        for (Map.Entry<String, TierJson> e : j.tiers.entrySet()) {
            List<ResourceLocation> inst = new ArrayList<>();
            for (String s : e.getValue().instruments) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) inst.add(rl);
            }
            t.put(Integer.parseInt(e.getKey()), new Tier(e.getValue().fe_per_tick, e.getValue().ticks, List.copyOf(inst)));
        }
        this.tiers = Collections.unmodifiableMap(t);
        Map<String, Node> n = new LinkedHashMap<>();
        Map<ResourceLocation, String> lock = new HashMap<>();
        for (Map.Entry<String, NodeJson> e : j.nodes.entrySet()) {
            if (e.getKey().startsWith("_")) continue;
            NodeJson nj = e.getValue();
            Tier tier = t.getOrDefault(nj.tier, new Tier(0, 0, List.of()));
            List<Specimen> specs = new ArrayList<>();
            for (SpecimenJson s : nj.specimens) {
                ResourceLocation rl = ResourceLocation.tryParse(s.item);
                if (rl != null && s.count > 0) specs.add(new Specimen(rl, s.count));
            }
            List<ResourceLocation> unlocks = new ArrayList<>();
            for (String s : nj.unlocks) {
                ResourceLocation rl = ResourceLocation.tryParse(s);
                if (rl != null) {
                    unlocks.add(rl);
                    lock.putIfAbsent(rl, e.getKey());
                }
            }
            ResourceLocation icon = ResourceLocation.tryParse(nj.icon);
            n.put(e.getKey(), new Node(e.getKey(), nj.tier, LANES.contains(nj.lane) ? nj.lane : "material", nj.col,
                    icon == null ? ResourceLocation.withDefaultNamespace("barrier") : icon,
                    List.copyOf(nj.requires), List.copyOf(specs), List.copyOf(unlocks),
                    nj.fe_per_tick != null ? nj.fe_per_tick : tier.fePerTick(),
                    nj.ticks != null ? nj.ticks : tier.ticks(),
                    nj.instruments_of_tier != null ? nj.instruments_of_tier : nj.tier));
        }
        this.nodes = Collections.unmodifiableMap(n);
        this.lockedBy = Collections.unmodifiableMap(lock);
        this.instrumentRadius = j.instrument_radius;
        this.benchBuffer = j.bench_buffer;
        this.benchMaxReceive = j.bench_max_receive;
    }

    public String json() {
        return json;
    }

    public Map<String, Node> nodes() {
        return nodes;
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    public Tier tier(int tier) {
        return tiers.getOrDefault(tier, new Tier(0, 0, List.of()));
    }

    public double instrumentRadius() {
        return instrumentRadius;
    }

    public int benchBuffer() {
        return benchBuffer;
    }

    public int benchMaxReceive() {
        return benchMaxReceive;
    }

    /** この作り方を開く節(どの節も開かないなら null = 鍵なし)。 */
    public String lockedBy(ResourceLocation recipe) {
        return lockedBy.get(recipe);
    }

    /** 研究台の節(段 0)。はじめから開いている。 */
    public Set<String> roots() {
        Set<String> s = new java.util.HashSet<>();
        nodes.values().forEach(n -> {
            if (n.isRoot()) s.add(n.id());
        });
        return s;
    }

    // ---- 読み込み ----

    private static volatile ResearchTree current;

    /** 今の研究ツリー(サーバー側。最初に呼ばれたときに読む)。 */
    public static ResearchTree get() {
        ResearchTree t = current;
        if (t == null) {
            synchronized (ResearchTree.class) {
                if (current == null) current = load();
                t = current;
            }
        }
        return t;
    }

    /** 読み直す({@code /ov perf ley research reload})。 */
    public static synchronized ResearchTree reload() {
        current = load();
        return current;
    }

    /** JSON の文字列から作る(クライアントがサーバーから受け取った物を読む)。壊れていれば空の木。 */
    public static ResearchTree parse(String json) {
        try {
            Json j = GSON.fromJson(json, Json.class);
            return new ResearchTree(json, j == null ? new Json() : j);
        } catch (JsonSyntaxException | NumberFormatException e) {
            OvergrownVegetation.LOGGER.error("研究ツリーの JSON が読めません", e);
            return new ResearchTree("{}", new Json());
        }
    }

    private static ResearchTree load() {
        String text = readText();
        return parse(text == null ? "{}" : text);
    }

    private static String readText() {
        try {
            if (Boolean.getBoolean("ov.designFromClasspath")) {
                try (InputStream in = ResearchTree.class.getResourceAsStream(RESOURCE)) {
                    return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            Path dir = FMLPaths.CONFIGDIR.get().resolve("overgrown_vegetation");
            Path file = dir.resolve(FILE);
            if (!Files.exists(file)) {
                try (InputStream in = ResearchTree.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) {
                        OvergrownVegetation.LOGGER.warn("バンドル既定値 {} が見つかりません。", RESOURCE);
                        return null;
                    }
                    Files.createDirectories(dir);
                    Files.copy(in, file);
                }
            }
            try (Reader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int k;
                while ((k = r.read(buf)) > 0) sb.append(buf, 0, k);
                return sb.toString();
            }
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("{} の読み込みに失敗しました。空の研究ツリーを使います。", FILE, e);
            return null;
        }
    }
}
