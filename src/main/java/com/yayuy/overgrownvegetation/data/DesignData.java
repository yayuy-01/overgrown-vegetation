package com.yayuy.overgrownvegetation.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * MOD の「設計データ」— Mob ステータス・捕食表・フェーズ・デバフの数値を、Java のハードコードではなく
 * {@code config/overgrown_vegetation/*.json} から読む仕組み。
 *
 * <p>{@link #load()} を {@code OvergrownVegetation} コンストラクタの先頭で呼ぶ。この時点は
 * エンティティ登録・{@code EntityAttributeCreationEvent}・{@code MobEffect} の生成より前なので、
 * ヒットボックス・属性・効果すべてに間に合う。
 *
 * <ul>
 *     <li>各ファイルが無ければ、バンドルした既定値(classpath の {@code /overgrown_vegetation/design/*.json})を
 *     その場所へ書き出してから読む。</li>
 *     <li>読み込みに失敗した個別ファイルは、Java 側のハードコード既定値(各 {@code *Table.DEFAULT} 等)へ
 *     フォールバックする(起動は止めない)。</li>
 *     <li>{@code /ov design reload} で再読込。実行時参照される predation / phases は即反映、
 *     Mob ステータス・ヒットボックス・効果の数値は再起動が必要。</li>
 * </ul>
 */
public final class DesignData {

    /** {@code config/overgrown_vegetation/} 直下のファイル名。 */
    public static final String DIR_NAME = OvergrownVegetation.MODID;
    private static final String RESOURCE_BASE = "/overgrown_vegetation/design/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile MobStats mobs = MobStats.defaultsOnly();
    private static volatile PhaseTable phases = PhaseTable.DEFAULT;
    private static volatile SpreadTable spread = SpreadTable.DEFAULT;
    private static volatile PortalTable portal = PortalTable.DEFAULT;
    private static volatile CoverTable cover = CoverTable.DEFAULT;
    private static volatile SporeFieldTable sporeField = SporeFieldTable.DEFAULT;
    private static volatile AtmosphereTable atmosphere = AtmosphereTable.DEFAULT;
    private static volatile EffectTable effects = EffectTable.DEFAULT;
    private static volatile AdaptationTable adaptation = AdaptationTable.DEFAULT;
    private static volatile ActionTable actions = ActionTable.DEFAULT;
    private static volatile SkyTable sky = SkyTable.DEFAULT;
    private static volatile PerfTable perf = PerfTable.DEFAULT;
    private static volatile NetherSpreadTable netherSpread = NetherSpreadTable.DEFAULT;

    private static volatile DirectorTable director = DirectorTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.spawn.SpawnTable spawns = com.yayuy.overgrownvegetation.spawn.SpawnTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.ecosystem.EcoTable ecosystem = com.yayuy.overgrownvegetation.ecosystem.EcoTable.DEFAULT;
    private static volatile com.yayuy.overgrownvegetation.organ.OrganTable organs = com.yayuy.overgrownvegetation.organ.OrganTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.worldevent.WorldEventTable worldEvents = com.yayuy.overgrownvegetation.worldevent.WorldEventTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.gear.GearTierTable gearTiers = com.yayuy.overgrownvegetation.gear.GearTierTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable pushBack = com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.phase.MobClassTable mobClass = com.yayuy.overgrownvegetation.phase.MobClassTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.corruption.cliff.CliffTable cliff = com.yayuy.overgrownvegetation.corruption.cliff.CliffTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.corruption.cave.CaveTable cave = com.yayuy.overgrownvegetation.corruption.cave.CaveTable.DEFAULT;

    /** 汚染された洞窟の飾り(柱・水ぶくれ・縄。{@code cave.json}、2026-11-20)。 */
    public static com.yayuy.overgrownvegetation.corruption.cave.CaveTable cave() {
        return cave;
    }

    private static volatile com.yayuy.overgrownvegetation.corruption.fissure.FissureTable fissure = com.yayuy.overgrownvegetation.corruption.fissure.FissureTable.DEFAULT;

    private static volatile com.yayuy.overgrownvegetation.drift.DriftTable drift = com.yayuy.overgrownvegetation.drift.DriftTable.DEFAULT;

    /** 漂流する擬態 Mob と腫れた島({@code drift.json}、2026-11-20)。 */
    public static com.yayuy.overgrownvegetation.drift.DriftTable drift() {
        return drift;
    }

    /** 地下の小さな亀裂と亀裂の巣({@code fissure.json}、2026-11-20)。 */
    public static com.yayuy.overgrownvegetation.corruption.fissure.FissureTable fissure() {
        return fissure;
    }

    /** 崖・急斜面の汚染(根の縄・嚢の房・登り・嚢の霧。{@code cliff.json}、2026-11-20)。 */
    public static com.yayuy.overgrownvegetation.corruption.cliff.CliffTable cliff() {
        return cliff;
    }

    /** Mob の分類(系統・型・危険度・出るフェーズ。{@code mob_class.json}、2026-11-18 夜)。 */
    public static com.yayuy.overgrownvegetation.phase.MobClassTable mobClass() {
        return mobClass;
    }

    /** 押し返す用の道具と方法の設計値({@code push_back.json}、2026-11-18 夜)。 */
    public static com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable pushBack() {
        return pushBack;
    }

    /** 強くなる道 段 1・段 2 の物の設計値({@code gear_tiers.json}、2026-11-17)。 */
    public static com.yayuy.overgrownvegetation.gear.GearTierTable gearTiers() {
        return gearTiers;
    }

    /** ワールドイベント 7 つの設計値({@code world_events.json}、2026-11-16 world-events)。サーバー(日程・地脈の大潮)とクライアント(長さ)が読む。 */
    public static com.yayuy.overgrownvegetation.worldevent.WorldEventTable worldEvents() {
        return worldEvents;
    }

    /** 生態系の台帳(升ごとの個体群・多様性・内側の湧き)の設計値({@code ecosystem.json}、2026-11-16)。 */
    public static com.yayuy.overgrownvegetation.ecosystem.EcoTable ecosystem() {
        return ecosystem;
    }

    /** 森の器官(大きい版)の設計値({@code organs.json}、2026-11-17)。 */
    public static com.yayuy.overgrownvegetation.organ.OrganTable organs() {
        return organs;
    }

    /** 敵の湧き方(縁・地下・司令塔が出す G2・G3)の設計値({@code spawns.json}、2026-11-15)。 */
    public static com.yayuy.overgrownvegetation.spawn.SpawnTable spawns() {
        return spawns;
    }

    /** 森の知性(司令塔)の設計値({@code director.json}、2026-11-13)。 */
    public static DirectorTable director() {
        return director;
    }

    /** ネザーの汚染の骨組みの設計値({@code nether_spread.json}、2026-11-11)。 */
    public static NetherSpreadTable netherSpread() {
        return netherSpread;
    }

    /** テスト用: ネザーの汚染の骨組みの設計値を差し替える。 */
    public static void setNetherSpreadForTests(NetherSpreadTable table) {
        netherSpread = table;
    }

    private static volatile boolean loaded;

    public static MobStats mobs() {
        return mobs;
    }

    public static PhaseTable phases() {
        return phases;
    }

    /** 台帳の広がりの設計値({@code spread.json}、2026-09-26)。 */
    public static SpreadTable spread() {
        return spread;
    }

    /** ポータルからネザー・エンドへの侵食の設計値({@code portal.json}、2026-10-02)。 */
    /** 胞子の場と風の設計値({@code spore_field.json}、2026-10-23)。 */
    public static SporeFieldTable sporeField() {
        return sporeField;
    }

    /** テスト用: 胞子の場の設計値を差し替える。 */
    public static void setSporeFieldForTests(SporeFieldTable table) {
        sporeField = table;
    }

    /** 効果の設計値({@code effects.json}、2026-11-04 にプレイヤーの胞子感染 10 段階だけで戻した)。 */
    public static EffectTable effects() {
        return effects;
    }

    /** 過繁茂の密林の雰囲気の設計値({@code atmosphere.json}、2026-10-30)。クライアントの描画だけが読む。 */
    public static AtmosphereTable atmosphere() {
        return atmosphere;
    }

    public static PortalTable portal() {
        return portal;
    }

    /** 覆いの設計値({@code cover.json}、2026-10-30)。 */
    public static CoverTable cover() {
        return cover;
    }

    /** Mob の技の調整値({@code actions.json}、11-05。層 2 の共通部品)。 */
    /** 空と雲の設計値({@code sky.json}、2026-11-08 雲の段階 1)。サーバー(雲の風)とクライアント(描画)が読む。 */
    public static SkyTable sky() {
        return sky;
    }

    /** 重さを軽くする仕組みの設計値({@code perf.json}、2026-11-18 大群のための軽量化 B)。 */
    public static PerfTable perf() {
        return perf;
    }

    public static ActionTable actions() {
        return actions;
    }

    /** グループ3(適応種)の武器への適応({@code adaptation.json}。11-05 にファルキムネスと一緒に戻した)。 */
    public static AdaptationTable adaptation() {
        return adaptation;
    }

    /** 起動時に一度だけ({@code OvergrownVegetation} コンストラクタから)。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        reload();
    }

    /** ファイルを読み直す。{@link #load()} 後ならいつでも呼べる(コマンド用)。 */
    public static synchronized void reload() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("設計データのディレクトリを作成できませんでした: {}", dir, e);
        }

        mobs = new MobStats(readMobs(dir));
        phases = PhaseTable.fromJson(read(dir, "phases.json", PhaseTable.Json.class));
        spread = SpreadTable.fromJson(read(dir, "spread.json", SpreadTable.Json.class));
        portal = PortalTable.fromJson(read(dir, "portal.json", PortalTable.Json.class));
        cover = CoverTable.fromJson(read(dir, "cover.json", CoverTable.Json.class));
        sporeField = SporeFieldTable.fromJson(read(dir, "spore_field.json", SporeFieldTable.Json.class));
        atmosphere = AtmosphereTable.fromJson(read(dir, "atmosphere.json", AtmosphereTable.Json.class));
        effects = EffectTable.fromJson(read(dir, "effects.json", EffectTable.Json.class));
        adaptation = AdaptationTable.fromJson(read(dir, "adaptation.json", AdaptationTable.Json.class));
        actions = ActionTable.fromJson(read(dir, "actions.json", ActionTable.Json.class));
        sky = SkyTable.fromJson(read(dir, "sky.json", SkyTable.Json.class));
        perf = PerfTable.fromJson(read(dir, "perf.json", PerfTable.Json.class));
        netherSpread = NetherSpreadTable.fromJson(read(dir, "nether_spread.json", NetherSpreadTable.Json.class));
        director = DirectorTable.fromJson(read(dir, "director.json", DirectorTable.Json.class));
        spawns = com.yayuy.overgrownvegetation.spawn.SpawnTable.fromJson(read(dir, "spawns.json", com.yayuy.overgrownvegetation.spawn.SpawnTable.Json.class));
        ecosystem = com.yayuy.overgrownvegetation.ecosystem.EcoTable.fromJson(read(dir, "ecosystem.json", com.yayuy.overgrownvegetation.ecosystem.EcoTable.Json.class));
        organs = com.yayuy.overgrownvegetation.organ.OrganTable.fromJson(read(dir, "organs.json", com.yayuy.overgrownvegetation.organ.OrganTable.Json.class));
        worldEvents = com.yayuy.overgrownvegetation.worldevent.WorldEventTable.fromJson(read(dir, "world_events.json", com.yayuy.overgrownvegetation.worldevent.WorldEventTable.Json.class));
        com.yayuy.overgrownvegetation.gear.GearTierTable gt = read(dir, "gear_tiers.json", com.yayuy.overgrownvegetation.gear.GearTierTable.class);
        gearTiers = gt != null ? gt : com.yayuy.overgrownvegetation.gear.GearTierTable.DEFAULT;
        com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable pb = read(dir, "push_back.json", com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable.class);
        pushBack = pb != null ? pb : com.yayuy.overgrownvegetation.corruption.pushback.PushBackTable.DEFAULT;
        com.yayuy.overgrownvegetation.phase.MobClassTable mc = read(dir, "mob_class.json", com.yayuy.overgrownvegetation.phase.MobClassTable.class);
        mobClass = mc != null ? mc : com.yayuy.overgrownvegetation.phase.MobClassTable.DEFAULT;
        com.yayuy.overgrownvegetation.corruption.cliff.CliffTable cl = read(dir, "cliff.json", com.yayuy.overgrownvegetation.corruption.cliff.CliffTable.class);
        cliff = cl != null ? cl : com.yayuy.overgrownvegetation.corruption.cliff.CliffTable.DEFAULT;
        com.yayuy.overgrownvegetation.corruption.fissure.FissureTable fi = read(dir, "fissure.json", com.yayuy.overgrownvegetation.corruption.fissure.FissureTable.class);
        fissure = fi != null ? fi : com.yayuy.overgrownvegetation.corruption.fissure.FissureTable.DEFAULT;
        com.yayuy.overgrownvegetation.drift.DriftTable dr = read(dir, "drift.json", com.yayuy.overgrownvegetation.drift.DriftTable.class);
        drift = dr != null ? dr : com.yayuy.overgrownvegetation.drift.DriftTable.DEFAULT;
        com.yayuy.overgrownvegetation.corruption.cave.CaveTable cv = read(dir, "cave.json", com.yayuy.overgrownvegetation.corruption.cave.CaveTable.class);
        cave = cv != null ? cv : com.yayuy.overgrownvegetation.corruption.cave.CaveTable.DEFAULT;

        OvergrownVegetation.LOGGER.info("設計データを読み込みました({})", dir);
    }

    // --- I/O ---

    private static <T> T read(Path dir, String name, Class<T> type) {
        try (Reader r = openOrSeed(dir, name)) {
            if (r == null) {
                return null;
            }
            return GSON.fromJson(r, type);
        } catch (IOException | JsonSyntaxException e) {
            OvergrownVegetation.LOGGER.error("{} の読み込みに失敗しました。既定値を使います。", name, e);
            return null;
        }
    }

    /**
     * {@code mobs.json}(トップレベルが「id → MobStat」の素のマップ)を読む。
     * {@code _} で始まるキー(例: {@code "_comment"})とオブジェクトでない値は読み飛ばす。
     */
    private static Map<String, MobStat> readMobs(Path dir) {
        try (Reader r = openOrSeed(dir, "mobs.json")) {
            if (r == null) {
                return null;
            }
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) {
                return null;
            }
            Map<String, MobStat> out = new java.util.HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().startsWith("_") || !e.getValue().isJsonObject()) {
                    continue;
                }
                out.put(e.getKey(), GSON.fromJson(e.getValue(), MobStat.class));
            }
            return out;
        } catch (IOException | JsonSyntaxException e) {
            OvergrownVegetation.LOGGER.error("mobs.json の読み込みに失敗しました。既定値を使います。", e);
            return null;
        }
    }

    /**
     * {@code dir/name} を開く。無ければ classpath の同名リソースをそこへ書き出してから開く。
     * リソースも無ければ null(呼び出し側は Java 既定値へフォールバック)。
     */
    private static Reader openOrSeed(Path dir, String name) throws IOException {
        if (Boolean.getBoolean("ov.designFromClasspath")) {
            // GameTest のサーバー(build.gradle の gameTestServer だけが立てる印): config の写しを使わず、いつも classpath の design を直に読む
            // (写しは「無いときだけ写す」ので古いまま残り、テストが落ちていた。見直し ④、2026-11-16)。ふつうのクライアントと利用者の config は変えない
            InputStream in = DesignData.class.getResourceAsStream(RESOURCE_BASE + name);
            return in == null ? null : new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
        }
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (InputStream in = DesignData.class.getResourceAsStream(RESOURCE_BASE + name)) {
                if (in == null) {
                    OvergrownVegetation.LOGGER.warn("バンドル既定値 {} が見つかりません。", RESOURCE_BASE + name);
                    return null;
                }
                Files.copy(in, file);
                OvergrownVegetation.LOGGER.info("既定の設計データを書き出しました: {}", file);
            }
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    private DesignData() {
    }
}
