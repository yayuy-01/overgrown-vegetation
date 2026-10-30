package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code items.json} — クリエイティブタブの<b>所属と並び順</b>、アイテムごとの<b>スタック上限・レア度</b>
 * (2026-10-15 ユーザー指定「クリエイティブタブのアイテムの順番を変更できるように」)。
 *
 * <p>2026-10-15 まで、タブの中身も並びも Java のハードコードだけで決まっていた
 * ({@code registry/ModCreativeTabs} の {@code output.accept} の順 + {@code registry/CreativeTabOrder} の区分ソート)。
 * mod マネージャーのアイテムタブから並べ替えられるよう、その答えをこのファイルへ外出しした。
 *
 * <h2>書き方</h2>
 * <pre>{@code
 * {
 *   "tabs": {
 *     "covered":  ["overgrown_stone", ...],   // 並べたい順に id を書く(名前空間は省略可)
 *     "plants":   [...],
 *     "humanity": [...]
 *   },
 *   "items": {
 *     "sap_crystal": { "stackSize": 16, "rarity": "uncommon" }
 *   }
 * }
 * }</pre>
 *
 * <h2>書かなかったものはどうなるか</h2>
 * <ul>
 *     <li><b>どのタブの一覧にも無いアイテム</b>は、今までどおり Java が決めた区分・順で、
 *     書いてあるアイテムの<b>後ろ</b>に並ぶ。新しいアイテムを Java へ足しても消えない。</li>
 *     <li>一覧に書いたアイテムは<b>そのタブへ移る</b>(Java が別のタブへ入れていても、こちらが勝つ)。</li>
 *     <li>{@code items} に書かなかったアイテムのスタック上限・レア度は Java の登録時のまま。</li>
 * </ul>
 *
 * <p>反映は<b>再起動</b>(クリエイティブタブもアイテムの性質も、起動時に 1 度だけ組み立てるため)。
 *
 * @see com.yayuy.overgrownvegetation.registry.CreativeTabOrder
 * @see com.yayuy.overgrownvegetation.registry.ItemComponentOverrides
 */
public final class ItemTable {

    /** タブの鍵({@code items.json} の {@code tabs} のキー)。{@code registry/ModCreativeTabs} の 3 つ。 */
    public static final String COVERED = "covered";
    public static final String PLANTS = "plants";
    public static final String HUMANITY = "humanity";

    /** 書ける鍵の一覧(マネージャーと GameTest が見る)。 */
    public static final List<String> TAB_KEYS = List.of(COVERED, PLANTS, HUMANITY);

    // --- Gson DTO ---

    public static final class Json {
        /** タブの鍵 → そのタブに出すアイテム id(並べたい順)。 */
        public Map<String, List<String>> tabs;
        /** アイテム id → 性質。 */
        public Map<String, Entry> items;
    }

    public static final class Entry {
        /** 1 スタックの上限(1〜99)。省略 = Java の登録時のまま。 */
        public Integer stackSize;
        /** 名前の色({@code common} / {@code uncommon} / {@code rare} / {@code epic})。省略 = そのまま。 */
        public String rarity;
    }

    // --- 解決済みの値 ---

    /** タブの鍵 → 並び(id は名前空間つきに直したもの)。 */
    private final Map<String, List<ResourceLocation>> order;
    /** アイテム id → そのアイテムを出すタブの鍵。 */
    private final Map<ResourceLocation, String> tabOf;
    /** アイテム id → 性質。 */
    private final Map<ResourceLocation, Entry> entries;

    public static final ItemTable DEFAULT = new ItemTable(null);

    public static ItemTable fromJson(Json json) {
        return json == null ? DEFAULT : new ItemTable(json);
    }

    private ItemTable(Json src) {
        Map<String, List<ResourceLocation>> order = new LinkedHashMap<>();
        Map<ResourceLocation, String> tabOf = new HashMap<>();
        for (String key : TAB_KEYS) {
            List<ResourceLocation> ids = new ArrayList<>();
            List<String> raw = src != null && src.tabs != null ? src.tabs.get(key) : null;
            for (String s : raw != null ? raw : List.<String>of()) {
                ResourceLocation id = parse(s);
                // 同じアイテムを 2 つのタブへ書いたときは先に書いた方を採る(バニラのタブは同じ物を
                // 二重に出せるが、この MOD は 3 つで分けると決めてある = CoveredBlockGameTests が見張る)。
                if (id == null || tabOf.containsKey(id)) {
                    continue;
                }
                tabOf.put(id, key);
                ids.add(id);
            }
            order.put(key, List.copyOf(ids));
        }
        this.order = Map.copyOf(order);
        this.tabOf = Map.copyOf(tabOf);

        Map<ResourceLocation, Entry> entries = new HashMap<>();
        if (src != null && src.items != null) {
            for (Map.Entry<String, Entry> e : src.items.entrySet()) {
                ResourceLocation id = parse(e.getKey());
                if (id != null && e.getValue() != null) {
                    entries.put(id, e.getValue());
                }
            }
        }
        this.entries = Map.copyOf(entries);
    }

    /** 名前空間を省いた id は {@code overgrown_vegetation} 扱い。読めない id は null。 */
    private static ResourceLocation parse(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("_")) {
            return null;
        }
        String s = raw.trim();
        return ResourceLocation.tryParse(s.contains(":") ? s : OvergrownVegetation.MODID + ":" + s);
    }

    // --- 引き方 ---

    /** {@code tab} に並べると書いてあるアイテム(書いた順)。無ければ空。 */
    public List<ResourceLocation> order(String tab) {
        return order.getOrDefault(tab, List.of());
    }

    /**
     * {@code id} をどのタブに出すか。どのタブの一覧にも書いていなければ null
     * (= Java が決めたタブのまま)。
     */
    public String tabOf(ResourceLocation id) {
        return tabOf.get(id);
    }

    /** {@code tab} の中での位置。書いていなければ {@link Integer#MAX_VALUE}(= 後ろへ回す)。 */
    public int indexIn(String tab, ResourceLocation id) {
        int i = order.getOrDefault(tab, List.of()).indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 並びをひとつでも書いてあるか(全部空なら、今までどおり Java の順だけで並べる)。 */
    public boolean hasOrder() {
        return !tabOf.isEmpty();
    }

    /** 1 スタックの上限(書いていなければ null)。 */
    public Integer stackSize(ResourceLocation id) {
        Entry e = entries.get(id);
        if (e == null || e.stackSize == null) {
            return null;
        }
        return Math.max(1, Math.min(99, e.stackSize));
    }

    /** 名前の色(書いていない・読めない値なら null)。 */
    public Rarity rarity(ResourceLocation id) {
        Entry e = entries.get(id);
        if (e == null || e.rarity == null) {
            return null;
        }
        return switch (e.rarity.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "common" -> Rarity.COMMON;
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> null;
        };
    }

    /** 性質を書いてあるアイテムの一覧(GameTest 用)。 */
    public java.util.Set<ResourceLocation> tweakedItems() {
        return entries.keySet();
    }
}
