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
    private static volatile SporeFieldTable sporeField = SporeFieldTable.DEFAULT;

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

    public static PortalTable portal() {
        return portal;
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
        sporeField = SporeFieldTable.fromJson(read(dir, "spore_field.json", SporeFieldTable.Json.class));

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
