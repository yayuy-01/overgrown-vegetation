package com.yayuy.overgrownvegetation.data;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code adaptation.json} — グループ3(適応種)の「倒された武器への適応」の設計値(2026-09-23)。
 *
 * <p>仕組みは {@code adaptation/Adaptation}。ここにあるのは数値と、<b>一撃を武器種のキーへ読み替える規則</b>だけ。
 * <ul>
 *     <li>{@code perKill} — 1 キルでその武器種の欄に足す耐性(既定 0.02 = 2%)。</li>
 *     <li>{@code cap} — 全欄の合計の上限(既定 0.30 = 30%)。超えたら全欄を比例して縮める。</li>
 *     <li>{@code forgetBelow} — 縮んでこれを下回った欄は捨てる(既定 0.001 = 0.1%)。</li>
 *     <li>{@code ignoredDamageTypes} — 数えも軽減もしないダメージタイプ({@code /kill} と奈落)。</li>
 *     <li>{@code classes} — 上から順に照合し、最初に当たった {@code key} を使う。
 *     1 つの規則の中の各欄は「どれか 1 つ当たれば良い」。</li>
 * </ul>
 *
 * <p>規則のどれにも当たらない一撃は、<b>武器のアイテム id → 素手なら {@code unarmed} → 直接当たった
 * エンティティの種類 id → ダメージタイプ id</b> の順で、その id をそのままキーにする。知らない mod の武器を
 * 「その他」1 つにまとめると別々の武器が同じ欄を共有してしまうので、まとめたいものだけ規則に書く。
 */
public final class AdaptationTable {

    public static final class Json {
        public Double perKill;
        public Double cap;
        public Double forgetBelow;
        public List<String> ignoredDamageTypes;
        public List<RuleJson> classes;
    }

    public static final class RuleJson {
        /** 台帳に積むキー。 */
        public String key;
        /** 名前空間。武器のアイテム・直接当たったエンティティ・ダメージタイプのどれかの id がこれなら当たり。 */
        public List<String> namespaces;
        /** 武器のアイテム id。 */
        public List<String> items;
        /** 武器のアイテムタグ(先頭の {@code #} は有っても無くても良い)。 */
        public List<String> itemTags;
        /** 直接当たったエンティティ(矢・弾など)の種類 id。 */
        public List<String> entities;
        /** ダメージタイプ id。 */
        public List<String> damageTypes;
        /** ダメージタイプのタグ。 */
        public List<String> damageTypeTags;
    }

    /** 規則 1 つ。書かれた文字列のまま持つ(設計値の食い違いを比べられるように record にしてある)。 */
    public record Rule(String key, List<String> namespaces, List<String> items, List<String> itemTags,
                       List<String> entities, List<String> damageTypes, List<String> damageTypeTags) {
    }

    /** 照合用に文字列を id とタグへ読み替えた規則。 */
    public record Compiled(String key, List<String> namespaces, List<ResourceLocation> items,
                           List<TagKey<Item>> itemTags, List<ResourceLocation> entities,
                           List<ResourceKey<DamageType>> damageTypes, List<TagKey<DamageType>> damageTypeTags) {
    }

    private final double perKill;
    private final double cap;
    private final double forgetBelow;
    private final List<ResourceKey<DamageType>> ignoredDamageTypes;
    private final List<Rule> rules;
    private final List<Compiled> compiled;

    private AdaptationTable(Json j) {
        Json src = j != null ? j : new Json();
        this.perKill = src.perKill != null ? Math.max(0.0D, src.perKill) : 0.02D;
        this.cap = src.cap != null ? Math.max(0.0D, src.cap) : 0.30D;
        this.forgetBelow = src.forgetBelow != null ? Math.max(0.0D, src.forgetBelow) : 0.001D;
        this.ignoredDamageTypes = damageTypeKeys(src.ignoredDamageTypes != null ? src.ignoredDamageTypes
                : List.of("minecraft:generic_kill", "minecraft:out_of_world"));

        List<Rule> list = new ArrayList<>();
        for (RuleJson r : src.classes != null ? src.classes : defaultRules()) {
            if (r == null || r.key == null || r.key.isBlank()) {
                continue;
            }
            list.add(new Rule(r.key, copy(r.namespaces), copy(r.items), copy(r.itemTags),
                    copy(r.entities), copy(r.damageTypes), copy(r.damageTypeTags)));
        }
        this.rules = List.copyOf(list);

        List<Compiled> out = new ArrayList<>();
        for (Rule r : rules) {
            List<TagKey<Item>> itemTags = new ArrayList<>();
            for (ResourceLocation id : ids(r.itemTags())) {
                itemTags.add(TagKey.create(Registries.ITEM, id));
            }
            List<TagKey<DamageType>> damageTags = new ArrayList<>();
            for (ResourceLocation id : ids(r.damageTypeTags())) {
                damageTags.add(TagKey.create(Registries.DAMAGE_TYPE, id));
            }
            out.add(new Compiled(r.key(), r.namespaces(), ids(r.items()), List.copyOf(itemTags),
                    ids(r.entities()), damageTypeKeys(r.damageTypes()), List.copyOf(damageTags)));
        }
        this.compiled = List.copyOf(out);
    }

    /**
     * 既定の規則。{@code design/adaptation.json} と同じ内容(食い違いは {@code AdaptationGameTests} が見張っている)。
     *
     * <p>順番に意味がある。銃(TaCz)を最初に置くのは、TaCz の弾の爆発や炎上が「爆発」「炎」に取られないため。
     * 爆発・炎・魔法・棘の鎧を武器より先に置くのは、手に剣を持ったまま棘の鎧で倒しても「剣」にならないため
     * (棘の鎧のダメージは装備者を直接の加害者にするので、武器を見ると手に持っている物が出てくる)。
     */
    private static List<RuleJson> defaultRules() {
        return List.of(
                rule("gun", r -> r.namespaces = List.of("tacz")),
                rule("explosion", r -> r.damageTypeTags = List.of("minecraft:is_explosion")),
                rule("fire", r -> r.damageTypeTags = List.of("minecraft:is_fire")),
                rule("magic", r -> r.damageTypes = List.of("minecraft:magic", "minecraft:indirect_magic")),
                rule("thorns", r -> r.damageTypes = List.of("minecraft:thorns")),
                rule("sword", r -> r.itemTags = List.of("minecraft:swords")),
                rule("axe", r -> r.itemTags = List.of("minecraft:axes")),
                rule("mace", r -> r.itemTags = List.of("minecraft:enchantable/mace", "c:tools/mace")),
                rule("trident", r -> r.itemTags = List.of("minecraft:enchantable/trident")),
                rule("spear", r -> r.itemTags = List.of("c:tools/spear")),
                rule("bow", r -> r.itemTags = List.of("minecraft:enchantable/bow", "minecraft:enchantable/crossbow",
                        "c:tools/bow", "c:tools/crossbow")),
                rule("tool", r -> r.itemTags = List.of("minecraft:pickaxes", "minecraft:shovels", "minecraft:hoes")));
    }

    private static RuleJson rule(String key, Consumer<RuleJson> fill) {
        RuleJson r = new RuleJson();
        r.key = key;
        fill.accept(r);
        return r;
    }

    private static List<String> copy(List<String> list) {
        if (list == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s != null && !s.isBlank()) {
                out.add(s.startsWith("#") ? s.substring(1) : s);
            }
        }
        return List.copyOf(out);
    }

    private static List<ResourceLocation> ids(List<String> list) {
        List<ResourceLocation> out = new ArrayList<>();
        for (String s : copy(list)) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id == null) {
                OvergrownVegetation.LOGGER.warn("adaptation.json: id として読めません: {}", s);
                continue;
            }
            out.add(id);
        }
        return List.copyOf(out);
    }

    private static List<ResourceKey<DamageType>> damageTypeKeys(List<String> list) {
        List<ResourceKey<DamageType>> out = new ArrayList<>();
        for (ResourceLocation id : ids(list)) {
            out.add(ResourceKey.create(Registries.DAMAGE_TYPE, id));
        }
        return List.copyOf(out);
    }

    public double perKill() {
        return perKill;
    }

    /** 耐性の合計の上限(記憶線による上乗せは含まない。{@code Adaptation#cap} を参照)。 */
    public double cap() {
        return cap;
    }

    public double forgetBelow() {
        return forgetBelow;
    }

    public List<ResourceKey<DamageType>> ignoredDamageTypes() {
        return ignoredDamageTypes;
    }

    /** 書かれたままの規則(比較用)。 */
    public List<Rule> rules() {
        return rules;
    }

    /** 照合に使う規則。 */
    public List<Compiled> compiled() {
        return compiled;
    }

    /** {@link DesignData} と、規則だけ差し替えた表を作るテストから。 */
    public static AdaptationTable fromJson(Json j) {
        return new AdaptationTable(j);
    }

    public static final AdaptationTable DEFAULT = new AdaptationTable(null);
}
