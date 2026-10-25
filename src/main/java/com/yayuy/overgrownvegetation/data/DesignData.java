package com.yayuy.overgrownvegetation.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MOD の「設計データ」— 調整する数値を Java に書かず、{@code config/overgrown_vegetation/*.json} から読む仕組み。
 *
 * <p>数値の置き場所はここだけにする(古い作りは Java の既定値・design json・config の toml の 3 か所に同じ値があり、
 * 1 か所だけ直して効かない罠が続いた)。各表は {@code record} と {@code DEFAULT} を持ち、{@link #load()} の中で
 * {@link #read} を 1 行足して読む。
 *
 * <ul>
 *     <li>ファイルが無ければ、同梱の既定値(classpath の {@code /overgrown_vegetation/design/*.json})をそこへ書き出してから読む。</li>
 *     <li>読み込みに失敗したファイルは、その表の {@code DEFAULT} を使う(起動は止めない)。</li>
 *     <li>反映は再起動。{@link #load()} は {@code OvergrownVegetation} のコンストラクタの先頭で呼ぶ(登録より前)。</li>
 *     <li><b>同梱の既定値を変えても、すでに書き出された {@code run/config} のファイルは古いまま</b>。確かめる前に消すか直す。</li>
 * </ul>
 */
public final class DesignData {

    /** {@code config/} の下のフォルダ名。 */
    public static final String DIR_NAME = OvergrownVegetation.MODID;
    private static final String RESOURCE_BASE = "/overgrown_vegetation/design/";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile boolean loaded;

    private DesignData() {
    }

    /** 起動時に一度だけ。 */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        Path dir = dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("設計データのフォルダを作れませんでした: {}", dir, e);
        }
        // 表はここに 1 行ずつ足す(例: sporeField = SporeFieldTable.fromJson(read(dir, "spore_field.json", SporeFieldTable.Json.class));)
        loaded = true;
        OvergrownVegetation.LOGGER.info("設計データを読み込みました({})", dir);
    }

    public static boolean loaded() {
        return loaded;
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(DIR_NAME);
    }

    /** {@code dir/name} を {@code type} として読む。無い・壊れているときは null(呼んだ側が {@code DEFAULT} を使う)。 */
    static <T> T read(Path dir, String name, Class<T> type) {
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

    /** {@code dir/name} を開く。無ければ同梱の同名ファイルを書き出してから開く。同梱も無ければ null。 */
    private static Reader openOrSeed(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            try (InputStream in = DesignData.class.getResourceAsStream(RESOURCE_BASE + name)) {
                if (in == null) {
                    return null;
                }
                Files.copy(in, file);
            }
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }
}
