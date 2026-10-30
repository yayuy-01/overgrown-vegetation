package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * カートリッジに入れる「因子」の種類(順 1 の着手順 8、2026-10-03)。1 個 = {@code poweredArmor.factorPerItem}(2%)。
 *
 * <p>効く相手は攻撃の種類のタグ {@code #overgrown_vegetation:factor/<id>} で決める(足すときはタグに書くだけ)。
 * 種類は絵が届いている 5 つ(Claude 仮決め)。並びはこの enum の順(画面・ツールチップもこの順)。
 */
public enum CartridgeFactor {
    IMPACT("impact", () -> ModItems.FACTOR_IMPACT.get()),
    PIERCE("pierce", () -> ModItems.FACTOR_PIERCE.get()),
    BLAST("blast", () -> ModItems.FACTOR_BLAST.get()),
    TOXIN("toxin", () -> ModItems.FACTOR_TOXIN.get()),
    SPORE("spore", () -> ModItems.FACTOR_SPORE.get());

    private final String id;
    private final Supplier<Item> item;
    private final TagKey<DamageType> tag;

    CartridgeFactor(String id, Supplier<Item> item) {
        this.id = id;
        this.item = item;
        this.tag = TagKey.create(Registries.DAMAGE_TYPE, OvergrownVegetation.id("factor/" + id));
    }

    public String id() {
        return id;
    }

    public Item item() {
        return item.get();
    }

    /** この因子が減らす攻撃の種類のタグ。 */
    public TagKey<DamageType> tag() {
        return tag;
    }

    public boolean matches(DamageSource source) {
        return source.is(tag);
    }

    /** 名前の翻訳キー(「衝撃」など)。 */
    public String nameKey() {
        return "factor.overgrown_vegetation." + id;
    }

    /** 効く相手の翻訳キー(「近接攻撃」など)。 */
    public String targetKey() {
        return "factor.overgrown_vegetation." + id + ".target";
    }

    @Nullable
    public static CartridgeFactor byId(String id) {
        for (CartridgeFactor f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }

    /** そのスタックが因子なら種類、違えば null。 */
    @Nullable
    public static CartridgeFactor of(ItemStack stack) {
        for (CartridgeFactor f : values()) {
            if (stack.is(f.item())) {
                return f;
            }
        }
        return null;
    }
}
