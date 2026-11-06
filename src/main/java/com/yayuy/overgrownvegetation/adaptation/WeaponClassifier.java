package com.yayuy.overgrownvegetation.adaptation;

import com.yayuy.overgrownvegetation.data.AdaptationTable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 一撃を「武器種のキー」へ読み替える(2026-09-23)。台帳({@link AdaptationData})はこのキーごとに積む。
 *
 * <h2>武器追加 mod に設定なしで対応する</h2>
 * <p>規則({@code adaptation.json} の {@code classes})は上から照合し、最初に当たった規則のキーを返す。
 * 既定の規則はバニラと NeoForge 共通のタグ({@code #minecraft:swords} / {@code #c:tools/bow} など)で書いてある。
 * 1.21 の武器 mod はエンチャントを付けさせるためにこれらのタグへ入るしかないので、大半はここで拾える。
 *
 * <p>どれにも当たらなかったら、<b>その武器のアイテム id をそのままキーにする</b>。知らない武器を
 * 「その他」1 つにまとめると、別々の mod の武器 2 本が同じ欄を共有してしまう。
 *
 * <h2>飛び道具は「撃った武器」で見る</h2>
 * <p>{@link DamageSource#getWeaponItem()} は直接当たったエンティティに武器を尋ねる。
 * 近接ならその手の物、矢なら<b>撃ち出した弓</b>(1.21 から矢が覚えている)が返る。
 * だから mod の弓で撃った矢も、その弓のタグで判定できる。武器を覚えていない弾(雪玉や mod の弾)は
 * 弾の種類 id で判定する。
 */
public final class WeaponClassifier {

    /** 素手(プレイヤーが何も持たずに殴った)。 */
    public static final String UNARMED = "unarmed";

    /**
     * {@code source} の武器種のキー。数えないダメージ({@code ignoredDamageTypes})なら {@code null}。
     */
    @Nullable
    public static String classify(DamageSource source, AdaptationTable table) {
        Optional<ResourceKey<DamageType>> typeKey = source.typeHolder().unwrapKey();
        if (typeKey.isPresent() && table.ignoredDamageTypes().contains(typeKey.get())) {
            return null;
        }

        ItemStack weapon = source.getWeaponItem();
        if (weapon == null) {
            weapon = ItemStack.EMPTY;
        }
        Entity direct = source.getDirectEntity();
        ResourceLocation weaponId = weapon.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(weapon.getItem());
        ResourceLocation directId = direct != null ? BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()) : null;
        ResourceLocation typeId = typeKey.map(ResourceKey::location).orElse(null);

        for (AdaptationTable.Compiled rule : table.compiled()) {
            if (matches(rule, source, weapon, weaponId, directId, typeId)) {
                return rule.key();
            }
        }

        if (weaponId != null) {
            return weaponId.toString();
        }
        if (direct instanceof Player) {
            return UNARMED;
        }
        if (directId != null) {
            return directId.toString();
        }
        return typeId != null ? typeId.toString() : "unknown";
    }

    private static boolean matches(AdaptationTable.Compiled rule, DamageSource source, ItemStack weapon,
                                   @Nullable ResourceLocation weaponId, @Nullable ResourceLocation directId,
                                   @Nullable ResourceLocation typeId) {
        for (String ns : rule.namespaces()) {
            if (inNamespace(weaponId, ns) || inNamespace(directId, ns) || inNamespace(typeId, ns)) {
                return true;
            }
        }
        if (weaponId != null && rule.items().contains(weaponId)) {
            return true;
        }
        if (!weapon.isEmpty()) {
            for (TagKey<Item> tag : rule.itemTags()) {
                if (weapon.is(tag)) {
                    return true;
                }
            }
        }
        if (directId != null && rule.entities().contains(directId)) {
            return true;
        }
        for (ResourceKey<DamageType> type : rule.damageTypes()) {
            if (source.is(type)) {
                return true;
            }
        }
        for (TagKey<DamageType> tag : rule.damageTypeTags()) {
            if (source.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inNamespace(@Nullable ResourceLocation id, String namespace) {
        return id != null && id.getNamespace().equals(namespace);
    }

    private WeaponClassifier() {
    }
}
