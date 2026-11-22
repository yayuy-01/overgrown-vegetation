package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.gear.FilterMask;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModExpedition;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.yayuy.overgrownvegetation.port.component.ItemContainerContents;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * <b>防具の付け物</b>(2026-11-21 夜 ユーザー「防具ごとに 3 つの枠があって」「すべての防具に付けれるように」「付け外しはできる」)。
 *
 * <ul>
 *   <li>付けた物は防具のスタックのコンポーネント {@link ModArmorAttach#ATTACHMENTS}(枠 3 つの {@link ItemContainerContents})に持つ。
 *   鍛冶台(ネザライト化)・金床・エンチャントはコンポーネントをそのまま写すので、付け物も残る。</li>
 *   <li>付けられる防具 = バニラとこの MOD の {@link ArmorItem}(頭・胴・脚・足。馬鎧・狼の鎧は除く)。ほかの MOD の防具には付かない。</li>
 *   <li>どの付け物がどの部位にいくつ付くかは {@code armor_attachments.json}({@link AttachTable})。</li>
 *   <li>口覆いの布が付いている間、フィルターの状態(詰まり・洗った回数・格)は<b>防具の側</b>に置く(防護マスクと同じ欄)。
 *   胞子の判定({@code SporeExposureTracker})・洗い場は頭のスタックを見るので、そのまま効く。外すと布へ戻す。</li>
 *   <li>収納付きベルトの小物入れの中身はベルトのスタック({@link ModArmorAttach#BELT_CONTENTS})に持つ(外すと中身ごと持ち運べる)。</li>
 * </ul>
 */
public final class ArmorAttachments {

    private ArmorAttachments() {
    }

    public static AttachTable table() {
        return DesignData.armorAttachments();
    }

    public static int slotCount() {
        return Math.max(1, Math.min(3, table().slots));
    }

    // ---- 付けられる防具 ----

    /** {@code armor} が付け物を付けられる防具か(名前空間を問う形。GameTest でほかの MOD の名前空間を試す)。 */
    public static boolean canHold(ItemStack armor) {
        if (armor.isEmpty()) return false;
        return canHold(armor.getItem(), BuiltInRegistries.ITEM.getKey(armor.getItem()).getNamespace());
    }

    /** {@code item} が名前空間 {@code namespace} の防具として付け物を付けられるか。 */
    public static boolean canHold(Item item, String namespace) {
        if (!(item instanceof ArmorItem)) return false; // 1.20.1: 体の鎧(BODY = 狼の鎧)は無い
        return "minecraft".equals(namespace) || OvergrownVegetation.MODID.equals(namespace);
    }

    /** 防具の部位の名前(head / chest / legs / feet)。付けられない物は null。 */
    @Nullable
    public static String partOf(ItemStack armor) {
        if (!(armor.getItem() instanceof ArmorItem a)) return null;
        return switch (a.getType()) {
            case HELMET -> "head";
            case CHESTPLATE -> "chest";
            case LEGGINGS -> "legs";
            case BOOTS -> "feet";
            default -> null;
        };
    }

    // ---- 付け物の決まり ----

    /** 付け物の決まり(付け物でなければ null)。 */
    @Nullable
    public static AttachTable.Entry entry(ItemStack attachment) {
        if (attachment.isEmpty()) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(attachment.getItem());
        if (!OvergrownVegetation.MODID.equals(id.getNamespace())) return null;
        return table().attachments.get(id.getPath());
    }

    public static boolean isAttachment(ItemStack stack) {
        return entry(stack) != null;
    }

    /** {@code attachment} を {@code armor} の空いた枠に付けられるか(部位・数の上限・口覆いの布は防護マスクに付けない)。 */
    public static boolean fits(ItemStack armor, ItemStack attachment) {
        AttachTable.Entry e = entry(attachment);
        String part = partOf(armor);
        if (e == null || part == null || !canHold(armor)) return false;
        if (e.parts.stream().noneMatch(p -> p.toLowerCase(Locale.ROOT).equals(part))) return false;
        if (attachment.is(ModArmorAttach.MOUTH_CLOTH.get()) && armor.getItem() instanceof FilterMask) return false; // マスクはもとからフィルターを使う
        int same = 0;
        for (ItemStack s : list(armor)) {
            if (!s.isEmpty() && s.is(attachment.getItem())) same++;
        }
        return same < Math.max(1, e.max);
    }

    // ---- 読み書き ----

    /** 付いている物(枠の数ぶん。空いた枠は空のスタック)。中身は写し。 */
    public static NonNullList<ItemStack> list(ItemStack armor) {
        NonNullList<ItemStack> all = all(armor);
        NonNullList<ItemStack> out = NonNullList.withSize(slotCount(), ItemStack.EMPTY);
        for (int i = 0; i < out.size() && i < all.size(); i++) out.set(i, all.get(i));
        return out;
    }

    /**
     * 持っている物を全部(枠の数を超えた所も)。設定の枠の数を減らしたとき、超えた所の物を消さずに持ち続けて、持ち主へ返すため
     * (11-22 見直し 16。{@link #takeOverflow})。書き戻しはいつもこちらから。
     */
    private static NonNullList<ItemStack> all(ItemStack armor) {
        ItemContainerContents c = OvItemData.get(armor, ModArmorAttach.ATTACHMENTS.get());
        NonNullList<ItemStack> out = NonNullList.withSize(Math.max(slotCount(), c == null ? 0 : c.getSlots()), ItemStack.EMPTY);
        if (c != null) c.copyInto(out);
        return out;
    }

    /** 枠の数を超えた所に残っている物を外して返す(口覆いの布はフィルターの状態を連れる)。無ければ空。 */
    public static java.util.List<ItemStack> takeOverflow(ItemStack armor) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        if (!hasAny(armor)) return out;
        int n = all(armor).size();
        for (int i = slotCount(); i < n; i++) {
            ItemStack s = detach(armor, i);
            if (!s.isEmpty()) out.add(s);
        }
        if (n > slotCount()) {
            NonNullList<ItemStack> l = all(armor); // 長さを枠の数へ縮める
            write(armor, l.subList(0, Math.min(slotCount(), l.size())));
        }
        return out;
    }

    public static ItemStack get(ItemStack armor, int index) {
        List<ItemStack> l = all(armor);
        return index >= 0 && index < l.size() ? l.get(index) : ItemStack.EMPTY;
    }

    private static void write(ItemStack armor, List<ItemStack> list) {
        if (list.stream().allMatch(ItemStack::isEmpty)) OvItemData.remove(armor, ModArmorAttach.ATTACHMENTS.get());
        else OvItemData.set(armor, ModArmorAttach.ATTACHMENTS.get(), ItemContainerContents.fromItems(list));
    }

    /** {@code attachment} の 1 つを {@code index} の枠に付ける(空いていて {@link #fits} なとき)。付けたら true。{@code attachment} は減らさない。 */
    public static boolean attach(ItemStack armor, int index, ItemStack attachment) {
        if (index < 0 || index >= slotCount() || !get(armor, index).isEmpty() || !fits(armor, attachment)) return false;
        ItemStack one = attachment.copyWithCount(1);
        if (one.is(ModArmorAttach.MOUTH_CLOTH.get())) {
            AirFilters.copyState(one, armor); // フィルターの状態は防具の側へ
            clearFilter(one);
        }
        NonNullList<ItemStack> l = all(armor);
        l.set(index, one);
        write(armor, l);
        return true;
    }

    /** {@code index} の枠の物を外して返す(空なら空)。口覆いの布はフィルターの状態を連れて戻る。 */
    public static ItemStack detach(ItemStack armor, int index) {
        NonNullList<ItemStack> l = all(armor);
        if (index < 0 || index >= l.size() || l.get(index).isEmpty()) return ItemStack.EMPTY;
        ItemStack out = l.get(index);
        l.set(index, ItemStack.EMPTY);
        if (out.is(ModArmorAttach.MOUTH_CLOTH.get())) {
            AirFilters.copyState(armor, out);
            clearFilter(armor);
        }
        write(armor, l);
        return out;
    }

    /**
     * {@code index} の枠の物を、外したらこうなる形で見る(口覆いの布は防具の側のフィルターの状態を載せる)。画面の枠に出すのはこれ
     * (枠の物と外した物が同じでないと、入れ替えの手つきでフィルターの状態が消える)。
     */
    public static ItemStack peek(ItemStack armor, int index) {
        ItemStack s = get(armor, index);
        if (s.is(ModArmorAttach.MOUTH_CLOTH.get())) AirFilters.copyState(armor, s);
        return s;
    }

    /** {@code armor} に何か付いているか。 */
    public static boolean hasAny(ItemStack armor) {
        return !armor.isEmpty() && OvItemData.has(armor, ModArmorAttach.ATTACHMENTS.get());
    }

    /** 付いている物を全部外して返す(口覆いの布はフィルターの状態を連れる)。防具が壊れたとき。 */
    public static java.util.List<ItemStack> detachAll(ItemStack armor) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        int n = all(armor).size();
        for (int i = 0; i < n; i++) {
            ItemStack s = detach(armor, i);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * 修理(砥石・金床・作業台の合成)で付け物を引き継ぐ(2026-11-22 ユーザー「修理では消えない」)。
     * {@code output} の付け物をいったん空にし、{@code inputs} の順に付いていた物を付け直す。付けられなかった物(枠が足りない・同じ物が 2 つ)を返す
     * (呼ぶ側が持ち主へ返す)。{@code output} が付け物を持てない物なら全部を返す。入力のどれにも付いていなければ何もせず空を返す。
     * 決まった順で決まった結果になるので、結果の見本と、受け取ったときの返す物の計算で同じ答えになる。
     */
    public static java.util.List<ItemStack> carryOver(ItemStack output, ItemStack... inputs) {
        java.util.List<ItemStack> rest = new java.util.ArrayList<>();
        boolean any = false;
        for (ItemStack in : inputs) any |= hasAny(in);
        if (!any) return rest;
        if (hasAny(output)) detachAll(output); // 写しで付いてきた物(とフィルターの状態)を捨てて、入力から付け直す
        for (ItemStack in : inputs) {
            if (!hasAny(in)) continue;
            int n = all(in).size();
            for (int i = 0; i < n; i++) {
                ItemStack a = peek(in, i);
                if (a.isEmpty()) continue;
                boolean placed = false;
                if (!output.isEmpty() && canHold(output)) {
                    for (int k = 0; k < slotCount() && !placed; k++) placed = attach(output, k, a);
                }
                if (!placed) rest.add(a);
            }
        }
        return rest;
    }

    /** 物を持ち物へ。入らなければ足元へ落とす(プレイヤーでなければその場に落とす)。 */
    public static void giveBack(@Nullable net.minecraft.world.entity.LivingEntity to, java.util.List<ItemStack> stacks) {
        if (to == null || to.level().isClientSide) return;
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            if (to instanceof net.minecraft.world.entity.player.Player p) {
                if (!p.getInventory().add(s)) p.drop(s, false);
            } else {
                to.spawnAtLocation(s);
            }
        }
    }

    /** 付いている枠の物を差し替える(中身だけ変わるとき。ベルトの小物入れ)。種類が同じでなければ何もしない。 */
    public static void replace(ItemStack armor, int index, ItemStack same) {
        NonNullList<ItemStack> l = all(armor);
        if (index < 0 || index >= l.size() || l.get(index).isEmpty() || !l.get(index).is(same.getItem())) return;
        l.set(index, same.copyWithCount(1));
        write(armor, l);
    }

    private static void clearFilter(ItemStack stack) {
        OvItemData.remove(stack, ModDataComponents.MASK_FILTER_USED);
        OvItemData.remove(stack, ModVent.FILTER_WASHES);
        OvItemData.remove(stack, ModExpedition.FILTER_GRADE);
    }

    /** {@code armor} に {@code item} がいくつ付いているか。 */
    public static int count(ItemStack armor, Item item) {
        ItemContainerContents c = OvItemData.get(armor, ModArmorAttach.ATTACHMENTS.get());
        if (c == null) return 0;
        int n = 0;
        for (ItemStack s : c.nonEmptyItems()) {
            if (s.is(item)) n++;
        }
        return n;
    }

    /** 付いている物の位置(無ければ −1)。 */
    public static int indexOf(ItemStack armor, Item item) {
        List<ItemStack> l = list(armor);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).is(item)) return i;
        }
        return -1;
    }

    // ---- 働き ----

    /** 口覆いの布の付いた頭の防具か(フィルターを使う頭として扱う。{@link FilterMask#isMask})。 */
    public static boolean hasMouthCloth(ItemStack armor) {
        return OvItemData.has(armor, ModArmorAttach.ATTACHMENTS.get()) && count(armor, ModArmorAttach.MOUTH_CLOTH.get()) > 0;
    }

    /** 口覆いの布のフィルターの減りの倍率。 */
    public static double mouthClothWear() {
        AttachTable.Entry e = table().attachments.get("mouth_cloth");
        return e == null ? 1.0D : Math.max(0.0D, e.filterWear);
    }

    /** 防具に付いた物による防御の上乗せの合計(樹皮の当て板)。 */
    public static double armorBonus(ItemStack armor) {
        ItemContainerContents c = OvItemData.get(armor, ModArmorAttach.ATTACHMENTS.get());
        if (c == null) return 0.0D;
        double sum = 0.0D;
        for (ItemStack s : c.nonEmptyItems()) {
            AttachTable.Entry e = entry(s);
            if (e != null) sum += e.armor;
        }
        return sum;
    }

    /**
     * 着けている人の地脈の力の減りの倍率(導晶装飾の布。1 = 減らない)。着ている防具の付け物の {@code leyDrainReduction} の合計を引く(下限 0)。
     * 地脈の力を使う所({@code LeyCharge})は、払う量にこれを掛ける。
     */
    public static double leyCostFactor(@Nullable LivingEntity wearer) {
        if (wearer == null) return 1.0D;
        double cut = 0.0D;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemContainerContents c = OvItemData.get(wearer.getItemBySlot(slot), ModArmorAttach.ATTACHMENTS.get());
            if (c == null) continue;
            for (ItemStack s : c.nonEmptyItems()) {
                AttachTable.Entry e = entry(s);
                if (e != null) cut += e.leyDrainReduction;
            }
        }
        return Math.max(0.0D, 1.0D - cut);
    }

    /** 払う量 {@code base} を {@link #leyCostFactor} で減らした量(1 以上。四捨五入)。 */
    public static int leyCost(@Nullable LivingEntity wearer, int base) {
        double f = leyCostFactor(wearer);
        if (f >= 1.0D) return base;
        return Math.max(1, (int) Math.round(base * f));
    }
}
