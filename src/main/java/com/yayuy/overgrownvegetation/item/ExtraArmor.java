package com.yayuy.overgrownvegetation.item;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>追加防具の枠</b>(2026-10-15 ユーザー指定)。バニラの防具の 4 枠とは別に、プレイヤーに頭・体・腰・足の 4 枠を足す。
 * <ul>
 *     <li>持ち物の画面では、プレイヤーの絵の右に縦に並ぶ(バニラの防具の反対側)。左手の枠は 2×2 の作業枠の下へ、
 *     レシピブックのボタンは右へ移した({@code mixin/InventoryMenuExtraArmorMixin}・{@code client/ExtraArmorScreenEvents})。</li>
 *     <li>ふつうの装備は入らない。枠ごとのタグ({@code #overgrown_vegetation:extra_armor/head} など)が付いた物だけ入る。</li>
 *     <li>中身はプレイヤーのアタッチメント {@link ModAttachments#EXTRA_ARMOR} に保存する。死ぬと落とす(keepInventory なら持ち越す。
 *     {@link ExtraArmorEvents})。</li>
 * </ul>
 * Curios は使わず自前で作った(ユーザー指定)。
 */
public final class ExtraArmor {

    /** 4 つの枠。並びは画面の上から(頭・体・腰・足)。 */
    public enum Part {
        HEAD("head", InventoryMenu.EMPTY_ARMOR_SLOT_HELMET),
        BODY("body", InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE),
        WAIST("waist", InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS),
        FEET("feet", InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS);

        private final TagKey<Item> tag;
        private final ResourceLocation emptyIcon;

        Part(String name, ResourceLocation emptyIcon) {
            this.tag = ItemTags.create(OvergrownVegetation.id("extra_armor/" + name));
            this.emptyIcon = emptyIcon;
        }

        /** この枠に入れられる物のタグ。 */
        public TagKey<Item> tag() {
            return tag;
        }

        /** 空のときに薄く描く絵(バニラの防具の枠と同じ)。 */
        public ResourceLocation emptyIcon() {
            return emptyIcon;
        }
    }

    public static final int SIZE = Part.values().length;

    /** 保存の形: 4 つのスタックの並び。 */
    public static final Codec<ExtraArmor> CODEC = ItemStack.OPTIONAL_CODEC.listOf().xmap(ExtraArmor::new, ExtraArmor::asList);

    private final ItemStack[] items = new ItemStack[SIZE];

    public ExtraArmor() {
        java.util.Arrays.fill(items, ItemStack.EMPTY);
    }

    private ExtraArmor(List<ItemStack> list) {
        this();
        for (int i = 0; i < SIZE && i < list.size(); i++) {
            items[i] = list.get(i);
        }
    }

    private List<ItemStack> asList() {
        List<ItemStack> list = new ArrayList<>(SIZE);
        for (ItemStack stack : items) {
            list.add(stack);
        }
        return list;
    }

    public ItemStack get(int index) {
        return items[index];
    }

    public void set(int index, ItemStack stack) {
        items[index] = stack == null ? ItemStack.EMPTY : stack;
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * プレイヤーの追加防具。<b>毎回アタッチメントから読む</b>(保存から読み込むとアタッチメントの中身が差し替わるので、
     * 入れ物を手元に持っておくと読み込み後に空に見える)。
     */
    public static ExtraArmor of(Player player) {
        return player.getData(ModAttachments.EXTRA_ARMOR);
    }

    /** その枠に今付けている物(空なら {@link ItemStack#EMPTY})。 */
    public static ItemStack worn(Player player, Part part) {
        return of(player).get(part.ordinal());
    }

    /** その枠に {@code item} を付けているか。 */
    public static boolean isWearing(Player player, Part part, Item item) {
        return worn(player, part).is(item);
    }

    /**
     * 付けている品の防御力({@link ExtraArmorItem#extraArmorDefense})を、プレイヤーの防御力へ足し直す。
     * バニラは決まった装備の枠の品の属性しか足さないので、ここで一時的な修飾(枠ごとに 1 つ)を張る。
     * 枠が変わったとき({@link ExtraArmorContainer#setItem})と、1 秒ごと({@link ExtraArmorEvents})に呼ぶ
     * (ログイン・リスポーンには「入れた」が無いため)。胞子感染の防御力の低下(掛け算)はこれにも掛かる。
     */
    public static void syncAttributes(Player player) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance armor =
                player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armor == null) {
            return;
        }
        ExtraArmor data = of(player);
        for (Part part : Part.values()) {
            ResourceLocation id = OvergrownVegetation.id("extra_armor." + part.name().toLowerCase(java.util.Locale.ROOT) + ".armor");
            ItemStack stack = data.get(part.ordinal());
            int defense = stack.getItem() instanceof ExtraArmorItem e && e.extraArmorPart() == part ? e.extraArmorDefense() : 0;
            net.minecraft.world.entity.ai.attributes.AttributeModifier existing = armor.getModifier(id);
            if (defense <= 0) {
                if (existing != null) {
                    armor.removeModifier(id);
                }
            } else if (existing == null || existing.amount() != defense) {
                armor.addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                        id, defense, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
            }
        }
    }

    /** サーバーなら、本人とそのプレイヤーを追跡している全員へ中身を配る(見た目用。{@code network/ExtraArmorPayload})。 */
    public static void broadcast(Player player) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp && sp.connection != null) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(sp,
                    new com.yayuy.overgrownvegetation.network.ExtraArmorPayload(sp.getId(), of(sp).copyList()));
        }
    }

    /** 周りへ配る形(エンティティ id + 4 つのスタック)。{@code network/ExtraArmorPayload}。 */
    public List<ItemStack> copyList() {
        List<ItemStack> list = new ArrayList<>(SIZE);
        for (ItemStack stack : items) {
            list.add(stack.copy());
        }
        return list;
    }
}
