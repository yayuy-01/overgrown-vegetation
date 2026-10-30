package com.yayuy.overgrownvegetation.crafting;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.data.ResearchState;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprint;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprintItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorTree;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * パワードアーマー作成テーブルの<b>スキルツリー</b>と<b>設計図</b>のタブの中身(順 1 の着手順 6、2026-10-03)。
 * テーブル・タブの確かめは呼ぶ側({@code PoweredArmorTableMenu#clickMenuButton})がする。ここは GameTest から直接呼べる形。
 *
 * <ul>
 *   <li><b>改造</b>: 次の段の支払い(研究ポイント + 素材。{@code gear.json} の {@code poweredArmor.levels})を払って 1 段上げる。
 *   支払いはユーザー指定「研究ポイント + 素材」。素材は G5 の「適応の記録」で、本ごとに決まっている
 *   (着手順 11、ユーザー指定: 防御 ← 盾・喰らい / 強靭 ← 狙い・掴み / 耐久 ← 水・闇・掘削。{@link #materials})</li>
 *   <li><b>写す</b>: 装備の部位・Mk・ツリーを設計図へ。白紙か同じ部位の設計図だけ(違う部位を消さない)。支払いは無し</li>
 *   <li><b>作り直す</b>: 設計図の部位の作成レシピの素材 + {@code rebuild_extra} を引き、ツリーを写した装備を渡す。
 *   <b>設計図は残る</b>(ユーザー指定)</li>
 * </ul>
 */
public final class PoweredArmorWorkshop {

    public enum Result {
        OK, NO_ARMOR, MAX, NO_POINTS, MISSING, NO_BLUEPRINT, BLANK, WRONG_PART, NO_RECIPE, LOCKED,
        NO_CARTRIDGE, NO_FACTOR, FULL, NOT_ATTACHED, SLOT_BUSY,
        /** 付ける物の枠が空・付けられない物(着手順 9)。 */
        NOTHING,
        /** カートリッジ・拡張パーツをパワードアーマー以外に付けようとした。 */
        NOT_POWERED,
        /** 装飾品を付けられない防具(寄生植物装備)。 */
        NOT_ACCEPTED
    }

    // ---------- 改造 ----------

    /** {@code branch} を次の段へ上げる支払い。もう上げられないなら null。 */
    @Nullable
    public static GearTable.LevelCost nextCost(ItemStack armor, int branch) {
        int next = PoweredArmorItem.tree(armor).level(branch) + 1;
        return DesignData.gear().poweredArmor().cost(next);
    }

    /**
     * 本 {@code branch} を上げる支払いの素材を {@link SizedIngredient} にする(どの本でも払うもの + その本のもの)。
     * {@code #} で始まるものはアイテムのタグ(適応の記録は本ごとのタグ。順 1 の着手順 11)。知らないアイテムの id は飛ばし、
     * 読めないタグは警告を出す(空のタグは誰も持てないので、その段は上げられなくなる —— ただで上がることはない)。
     */
    public static List<SizedIngredient> materials(GearTable.LevelCost cost, int branch) {
        List<SizedIngredient> list = new ArrayList<>();
        for (GearTable.ItemCost c : cost.items(branch)) {
            if (c.count() <= 0) {
                continue;
            }
            if (c.item().startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(c.item().substring(1));
                if (tagId == null) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.warn(
                            "gear.json の poweredArmor の支払いのタグが読めない: {}", c.item());
                    continue;
                }
                list.add(new SizedIngredient(net.minecraft.world.item.crafting.Ingredient.of(
                        net.minecraft.tags.ItemTags.create(tagId)), c.count()));
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(c.item());
            Item item = id != null ? BuiltInRegistries.ITEM.get(id) : Items.AIR;
            if (item != Items.AIR) {
                list.add(SizedIngredient.of(item, c.count()));
            }
        }
        return list;
    }

    /** 1 段上げる。研究ポイントはプレイヤーの研究の状態から、素材は持ち物から引く。 */
    public static Result upgrade(Player player, ItemStack armor, int branch) {
        if (!(armor.getItem() instanceof PoweredArmorItem) || branch < 0 || branch >= PoweredArmorTree.BRANCHES) {
            return Result.NO_ARMOR;
        }
        PoweredArmorTree tree = PoweredArmorItem.tree(armor);
        int next = tree.level(branch) + 1;
        GearTable.LevelCost cost = DesignData.gear().poweredArmor().cost(next);
        if (cost == null) {
            return Result.MAX;
        }
        ResearchState research = player.getData(ModAttachments.RESEARCH);
        if (research.points() < cost.points()) {
            return Result.NO_POINTS;
        }
        List<SizedIngredient> mats = materials(cost, branch);
        if (!PoweredArmorTableCrafting.hasAll(mats, player.getInventory().items)) {
            return Result.MISSING;
        }
        PoweredArmorTableCrafting.takeFromInventory(player, mats);
        research.spendPoints(cost.points());
        player.setData(ModAttachments.RESEARCH, research);
        PoweredArmorItem.setTree(armor, tree.withLevel(branch, next));
        return Result.OK;
    }

    // ---------- 設計図 ----------

    /** 装備のツリーを設計図へ写す(設計図のスタックを書き換える)。 */
    public static Result copy(ItemStack armor, ItemStack blueprint) {
        if (!(armor.getItem() instanceof PoweredArmorItem item)) {
            return Result.NO_ARMOR;
        }
        if (!(blueprint.getItem() instanceof PoweredArmorBlueprintItem) || blueprint.getCount() != 1) {
            return Result.NO_BLUEPRINT;
        }
        String part = item.getType().getName();
        PoweredArmorBlueprint old = PoweredArmorBlueprintItem.data(blueprint);
        if (old != null && !old.part().equals(part)) {
            return Result.WRONG_PART;
        }
        blueprint.set(ModDataComponents.POWERED_ARMOR_BLUEPRINT.get(),
                new PoweredArmorBlueprint(part, PoweredArmorItem.mk(armor), PoweredArmorItem.tree(armor)));
        return Result.OK;
    }

    /** 設計図の部位を作る作成レシピ(並びの先頭)。無ければ null。 */
    @Nullable
    public static RecipeHolder<PoweredArmorTableRecipe> rebuildRecipe(RecipeManager manager, PoweredArmorBlueprint blueprint) {
        ArmorItem.Type type = blueprint.type();
        Item target = type != null ? ModItems.poweredArmorItem(type) : null;
        if (target == null) {
            return null;
        }
        for (RecipeHolder<PoweredArmorTableRecipe> h : PoweredArmorTableCrafting.recipes(manager)) {
            if (h.value().result().is(target)) {
                return h;
            }
        }
        return null;
    }

    /** 設計図から作り直す(研究の封印は呼ぶ側で)。設計図は残る。 */
    public static Result rebuild(Player player, ItemStack blueprint, RecipeHolder<PoweredArmorTableRecipe> recipe) {
        PoweredArmorBlueprint bp = PoweredArmorBlueprintItem.data(blueprint);
        if (!(blueprint.getItem() instanceof PoweredArmorBlueprintItem)) {
            return Result.NO_BLUEPRINT;
        }
        if (bp == null) {
            return Result.BLANK;
        }
        List<SizedIngredient> mats = recipe.value().rebuildMaterials();
        if (!PoweredArmorTableCrafting.hasAll(mats, player.getInventory().items)) {
            return Result.MISSING;
        }
        PoweredArmorTableCrafting.takeFromInventory(player, mats);
        ItemStack out = recipe.value().result().copy();
        PoweredArmorItem.setTree(out, bp.tree());
        if (bp.mk() != 1) {
            out.set(ModDataComponents.POWERED_ARMOR_MK.get(), bp.mk());
        }
        PoweredArmorTableCrafting.give(player, out);
        return Result.OK;
    }

    // ---------- カートリッジ(着手順 8)----------

    /**
     * 因子をカートリッジへ入れる(入るだけ入れ、因子のスタックを減らす)。<b>入れた因子は取り出せない</b>(ユーザー指定)。
     */
    public static Result insertFactors(ItemStack cartridge, ItemStack factorStack) {
        if (!(cartridge.getItem() instanceof com.yayuy.overgrownvegetation.item.CartridgeItem) || cartridge.getCount() != 1) {
            return Result.NO_CARTRIDGE;
        }
        com.yayuy.overgrownvegetation.item.CartridgeFactor factor =
                com.yayuy.overgrownvegetation.item.CartridgeFactor.of(factorStack);
        if (factor == null) {
            return Result.NO_FACTOR;
        }
        com.yayuy.overgrownvegetation.item.CartridgeContents contents =
                com.yayuy.overgrownvegetation.item.CartridgeItem.contents(cartridge);
        int room = DesignData.gear().poweredArmor().cartridgeCapacity() - contents.total();
        if (room <= 0) {
            return Result.FULL;
        }
        int n = Math.min(room, factorStack.getCount());
        cartridge.set(ModDataComponents.CARTRIDGE_FACTORS.get(), contents.plus(factor, n));
        factorStack.shrink(n);
        return Result.OK;
    }

    /** カートリッジのアイテム(中身が 0 ならコンポーネントを載せない)。 */
    public static ItemStack cartridgeItem(com.yayuy.overgrownvegetation.item.CartridgeContents contents) {
        ItemStack stack = new ItemStack(ModItems.CARTRIDGE.get());
        if (!contents.isEmpty()) {
            stack.set(ModDataComponents.CARTRIDGE_FACTORS.get(), contents);
        }
        return stack;
    }

    /**
     * カートリッジを装備に付ける(カートリッジのスタックは 1 つ減る)。前に付いていたものがあれば、
     * そのカートリッジのアイテムを返す(入れ替え。無ければ空)。付けられないときは null。
     */
    @Nullable
    public static ItemStack attach(ItemStack armor, ItemStack cartridge) {
        if (!(armor.getItem() instanceof PoweredArmorItem)
                || !(cartridge.getItem() instanceof com.yayuy.overgrownvegetation.item.CartridgeItem)) {
            return null;
        }
        com.yayuy.overgrownvegetation.item.CartridgeContents old = PoweredArmorItem.cartridge(armor);
        armor.set(ModDataComponents.POWERED_ARMOR_CARTRIDGE.get(),
                com.yayuy.overgrownvegetation.item.CartridgeItem.contents(cartridge));
        cartridge.shrink(1);
        return old != null ? cartridgeItem(old) : ItemStack.EMPTY;
    }

    /** 装備からカートリッジを外して、そのアイテムを返す。付いていなければ null。 */
    @Nullable
    public static ItemStack detach(ItemStack armor) {
        com.yayuy.overgrownvegetation.item.CartridgeContents old =
                armor.getItem() instanceof PoweredArmorItem ? PoweredArmorItem.cartridge(armor) : null;
        if (old == null) {
            return null;
        }
        armor.remove(ModDataComponents.POWERED_ARMOR_CARTRIDGE.get());
        return cartridgeItem(old);
    }

    // ---------- 付ける物の枠(着手順 9: カートリッジ・装飾品・拡張パーツ)----------

    /** 付ける物の枠のスタックを装備へ付けられるか(付けられれば OK、だめなら理由)。 */
    public static Result canAttach(ItemStack armor, ItemStack thing) {
        if (thing.getItem() instanceof com.yayuy.overgrownvegetation.item.CartridgeItem) {
            if (armor.isEmpty()) {
                return Result.NO_ARMOR;
            }
            return armor.getItem() instanceof PoweredArmorItem ? Result.OK : Result.NOT_POWERED;
        }
        com.yayuy.overgrownvegetation.item.ArmorAccessory a = com.yayuy.overgrownvegetation.item.ArmorAccessory.of(thing);
        if (a == null) {
            return Result.NOTHING;
        }
        return switch (a.canHold(armor)) {
            case OK -> Result.OK;
            case NO_ARMOR -> Result.NO_ARMOR;
            case NOT_ACCEPTED -> Result.NOT_ACCEPTED;
            case NOT_POWERED -> Result.NOT_POWERED;
            case WRONG_PART -> Result.WRONG_PART;
        };
    }

    /**
     * 付ける物の枠のもの(カートリッジ・装飾品・拡張パーツ)を装備に付ける(枠のスタックは 1 つ減る)。
     * 同じ差し口に前のものが付いていれば、そのアイテムを返す(入れ替え。無ければ空)。付けられないときは null。
     */
    @Nullable
    public static ItemStack attachAny(ItemStack armor, ItemStack thing) {
        if (canAttach(armor, thing) != Result.OK) {
            return null;
        }
        if (thing.getItem() instanceof com.yayuy.overgrownvegetation.item.CartridgeItem) {
            return attach(armor, thing);
        }
        com.yayuy.overgrownvegetation.item.ArmorAccessory a = com.yayuy.overgrownvegetation.item.ArmorAccessory.of(thing);
        com.yayuy.overgrownvegetation.item.ArmorAccessory old =
                com.yayuy.overgrownvegetation.item.ArmorAccessory.attached(armor, a.kind());
        armor.set(a.kind().component(), a.id());
        thing.shrink(1);
        return old != null ? new ItemStack(old.item()) : ItemStack.EMPTY;
    }

    /** 装飾品(または拡張パーツ)を外して、そのアイテムを返す。付いていなければ null。 */
    @Nullable
    public static ItemStack detachAccessory(ItemStack armor, com.yayuy.overgrownvegetation.item.ArmorAccessory.Kind kind) {
        com.yayuy.overgrownvegetation.item.ArmorAccessory old =
                com.yayuy.overgrownvegetation.item.ArmorAccessory.attached(armor, kind);
        if (old == null) {
            return null;
        }
        armor.remove(kind.component());
        return new ItemStack(old.item());
    }

    private PoweredArmorWorkshop() {
    }
}
