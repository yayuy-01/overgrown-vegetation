package com.yayuy.overgrownvegetation.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * クリエイティブタブの並び({@code items.json} の {@code tabs}。2026-11-13 に戻した)。
 *
 * <p>2026-10-15 に作り、10-30 の退避で外れていたものを、新しい mod マネージャー({@code tools/mod-manager2})が
 * 並べ替えられるように並びだけ戻した(スタック上限・レア度は戻していない)。
 *
 * <ul>
 *     <li>{@code tabs.<タブの鍵>} = アイテム id の並び(名前空間は省略可 = この mod)。<b>書いた物が書いた順で先</b>、
 *     書いていない物は {@code ModCreativeTabs} が積んだ順のまま後ろに付く(消えない)。</li>
 *     <li>ファイルが無い・空なら何もしない(今までどおり Java の順)。</li>
 *     <li>ほかの設計データと同じく {@code config/overgrown_vegetation/items.json} を読み、無ければ同梱の
 *     {@code /overgrown_vegetation/design/items.json} を写してから読む。反映は再起動(開発中は {@code /ov design reload}
 *     の後、ワールドに入り直す。バニラはタブの中身を控えていて、画面を開き直すだけでは組み直さない)。</li>
 * </ul>
 *
 * <p>{@link DesignData} に入れず、このクラスだけで読む(並行するセッションが {@code DesignData} を触っているため)。
 *
 * @see com.yayuy.overgrownvegetation.registry.CreativeTabOrder
 */
public final class ItemTable {

    /** {@code tabs} の鍵: 植物のタブ({@code ModCreativeTabs.PLANTS})。 */
    public static final String PLANTS = "plants";

    private static final String FILE = "items.json";
    private static final String RESOURCE = "/overgrown_vegetation/design/" + FILE;
    private static final ItemTable EMPTY = new ItemTable(null);

    @Nullable
    private static volatile ItemTable current;

    /** Gson の受け皿。 */
    public static final class Json {
        public Map<String, List<String>> tabs;
    }

    private final Map<String, List<ResourceLocation>> order;

    private ItemTable(@Nullable Json src) {
        Map<String, List<ResourceLocation>> order = new LinkedHashMap<>();
        if (src != null && src.tabs != null) {
            for (Map.Entry<String, List<String>> e : src.tabs.entrySet()) {
                List<ResourceLocation> ids = new ArrayList<>();
                for (String raw : e.getValue() != null ? e.getValue() : List.<String>of()) {
                    ResourceLocation id = parse(raw);
                    if (id != null && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
                order.put(e.getKey(), List.copyOf(ids));
            }
        }
        this.order = Map.copyOf(order);
    }

    public static ItemTable fromJson(@Nullable Json json) {
        return json == null ? EMPTY : new ItemTable(json);
    }

    /** 今の並び。最初に呼ばれたときに読む。 */
    public static ItemTable get() {
        ItemTable t = current;
        if (t == null) {
            t = load();
            current = t;
        }
        return t;
    }

    /** ファイルを読み直す({@code /ov design reload})。 */
    public static void reload() {
        current = load();
    }

    /** テスト用: 並びを差し替える({@code null} でファイルの中身へ戻す)。 */
    public static void setForTests(@Nullable ItemTable table) {
        current = table;
    }

    private static ItemTable load() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve(DesignData.DIR_NAME);
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE);
            if (!Files.exists(file)) {
                try (InputStream in = ItemTable.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) {
                        return EMPTY;
                    }
                    Files.copy(in, file);
                }
            }
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return fromJson(new Gson().fromJson(r, Json.class));
            }
        } catch (IOException | JsonSyntaxException e) {
            OvergrownVegetation.LOGGER.error("{} の読み込みに失敗しました。タブは Java の順のままにします。", FILE, e);
            return EMPTY;
        }
    }

    @Nullable
    private static ResourceLocation parse(@Nullable String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("_")) {
            return null;
        }
        String s = raw.trim();
        return ResourceLocation.tryParse(s.contains(":") ? s : OvergrownVegetation.MODID + ":" + s);
    }

    /** {@code tab} に並びが書いてあるか。 */
    public boolean hasOrder(String tab) {
        return !order.getOrDefault(tab, List.of()).isEmpty();
    }

    /** {@code tab} に書いてある並び。 */
    public List<ResourceLocation> order(String tab) {
        return order.getOrDefault(tab, List.of());
    }

    /** {@code tab} の中で何番目か。書いていなければ最後({@link Integer#MAX_VALUE})。 */
    public int indexIn(String tab, ResourceLocation id) {
        int i = order.getOrDefault(tab, List.of()).indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
