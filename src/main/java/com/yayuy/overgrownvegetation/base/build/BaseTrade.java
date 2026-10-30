package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.data.BaseTradeConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 同盟軍の基地への寄付・売却と、軍票での購入(順 2 の着手順 5、2026-10-07)。数値は {@link BaseTradeConfig}。
 *
 * <p><b>ユーザー指定(10-07)</b>:
 * <ul>
 *   <li>寄付 = 値打ちの表に載っている物なら何でも。値打ちはその基地の貯蓄に入り、信頼は値打ちに比例して上がる(1 日の上限)。お礼に軍票も少し(売るより少ない)</li>
 *   <li>売却 = 軍票だけ(寄付より多い)。値打ちは基地の貯蓄に入る</li>
 *   <li>購入 = 軍票で払い、買った品の値打ちの分だけ基地の貯蓄が減る。足りないと買えない</li>
 *   <li>売り買いは、何度か寄付して信頼が上がってから({@link BaseTradeConfig#trustTrade})</li>
 * </ul>
 * Claude 仮決め: 貯蓄の上限を超える分は貯蓄に入らないが、信頼と軍票は渡した値打ちの全部で数える。陥落した基地とは何もできない。
 */
public final class BaseTrade {

    public enum Result {
        OK, NO_SITE, FALLEN, NOTHING, LOCKED, NO_SCRIP, NO_STOCK, BAD_OFFER
    }

    /**
     * 結果。{@code value} = 渡した物の値打ち(購入では品の値打ち)、{@code trust} = 上がった信頼、{@code scrip} = もらった(購入では払った)軍票、
     * {@code stock} = 基地の貯蓄の増減。
     */
    public record Outcome(Result result, double value, double trust, int scrip, int stock) {
        static Outcome fail(Result r) {
            return new Outcome(r, 0.0D, 0.0D, 0, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** 画面に出す見込み(渡す枠の中身から)。 */
    public record Preview(double value, double trustGain, double thanks, double sell) {
        public static final Preview NONE = new Preview(0.0D, 0.0D, 0.0D, 0.0D);
    }

    private BaseTrade() {
    }

    private static BaseTradeConfig cfg() {
        return DesignData.defense().baseTrade();
    }

    // ---------- 値打ち ----------

    /**
     * 1 個の値打ち(貯蓄の単位。0 = 受け取らない)。順に: 軍票は 0 / 受け取らない物は 0 / 値打ちの表 / 研究ポイントのある物(mob のドロップ)は
     * ポイント × {@link BaseTradeConfig#researchPointValue} / 食べ物は満腹度 × {@link BaseTradeConfig#foodValuePerNutrition}
     * (害のある効果が付く食べ物は 0)。
     */
    public static double unitValue(ItemStack stack) {
        return unitValue(stack, cfg());
    }

    public static double unitValue(ItemStack stack, BaseTradeConfig cfg) {
        if (stack.isEmpty() || stack.is(AllyItems.MILITARY_SCRIP.get())) {
            return 0.0D;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (cfg.deny.contains(id)) {
            return 0.0D;
        }
        Double listed = cfg.values.get(id);
        if (listed != null) {
            return listed;
        }
        int points = DesignData.research().pointsFor(stack);
        if (points > 0) {
            return points * cfg.researchPointValue;
        }
        // 汚染された食べ物(G1 のドロップ)は食べ物としては受け取らない —— 研究ポイントがあれば上で研究の値打ちになる
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null && !harmful(food) && !(stack.getItem() instanceof com.yayuy.overgrownvegetation.item.ContaminatedFoodItem)) {
            return food.nutrition() * cfg.foodValuePerNutrition;
        }
        return 0.0D;
    }

    private static boolean harmful(FoodProperties food) {
        for (FoodProperties.PossibleEffect pe : food.effects()) {
            if (pe.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    /** 受け取る物か(渡す枠に入れられるか)。 */
    public static boolean accepts(ItemStack stack) {
        return unitValue(stack) > 0.0D;
    }

    /** 枠の中の受け取る物の値打ちの合計。 */
    public static double valueOf(Container items, BaseTradeConfig cfg) {
        double sum = 0.0D;
        for (int i = 0; i < items.getContainerSize(); i++) {
            ItemStack s = items.getItem(i);
            sum += unitValue(s, cfg) * s.getCount();
        }
        return sum;
    }

    /** 枠の中の受け取る物を取り出して、値打ちの合計を返す(受け取らない物は枠に残す)。 */
    private static double takeValued(Container items, BaseTradeConfig cfg) {
        double sum = 0.0D;
        for (int i = 0; i < items.getContainerSize(); i++) {
            ItemStack s = items.getItem(i);
            double v = unitValue(s, cfg);
            if (v > 0.0D) {
                sum += v * s.getCount();
                items.setItem(i, ItemStack.EMPTY);
            }
        }
        if (sum > 0.0D) {
            items.setChanged();
        }
        return sum;
    }

    /** 貯蓄へ足す(上限まで。もう上限を超えていれば減らさない)。足せた量を返す。 */
    private static int addStock(BaseSites data, BaseSite site, double value) {
        int cap = DesignData.defense().baseStockCap(site.level());
        int before = site.stock;
        int after = before >= cap ? before : (int) Math.min(cap, before + Math.floor(value + 1e-9));
        if (after != before) {
            site.stock = after;
            data.changed();
        }
        return after - before;
    }

    // ---------- 寄付・売却・購入 ----------

    /** 寄付。信頼(1 日の上限つき)とお礼の軍票、値打ちは基地の貯蓄へ。 */
    public static Outcome donate(MinecraftServer server, BaseSite site, Player player, Container items) {
        if (site == null) {
            return Outcome.fail(Result.NO_SITE);
        }
        if (site.fallen()) {
            return Outcome.fail(Result.FALLEN);
        }
        BaseTradeConfig cfg = cfg();
        double value = takeValued(items, cfg);
        if (value <= 0.0D) {
            return Outcome.fail(Result.NOTHING);
        }
        int added = addStock(BaseSites.get(server), site, value);
        AllyTrust trust = AllyTrust.get(server);
        double gained = trust.gain(player.getUUID(), value * cfg.trustPerValue, BaseEconomy.today(server), cfg);
        int scrip = trust.payScrip(player.getUUID(), value / cfg.scripValue * cfg.donationThanks);
        giveScrip(player, scrip);
        return new Outcome(Result.OK, value, gained, scrip, added);
    }

    /** 売却。軍票だけ(寄付より多い)、値打ちは基地の貯蓄へ。信頼が取引の段に届いていないと売れない。 */
    public static Outcome sell(MinecraftServer server, BaseSite site, Player player, Container items) {
        if (site == null) {
            return Outcome.fail(Result.NO_SITE);
        }
        if (site.fallen()) {
            return Outcome.fail(Result.FALLEN);
        }
        BaseTradeConfig cfg = cfg();
        AllyTrust trust = AllyTrust.get(server);
        if (trust.trust(player.getUUID()) + 1e-9 < cfg.trustTrade) {
            return Outcome.fail(Result.LOCKED);
        }
        double value = takeValued(items, cfg);
        if (value <= 0.0D) {
            return Outcome.fail(Result.NOTHING);
        }
        int added = addStock(BaseSites.get(server), site, value);
        int scrip = trust.payScrip(player.getUUID(), value / cfg.scripValue);
        giveScrip(player, scrip);
        return new Outcome(Result.OK, value, 0.0D, scrip, added);
    }

    /** 購入。軍票で払い、品の値打ちの分だけ基地の貯蓄が減る。{@code index} は {@link #offers} の何番目か。 */
    public static Outcome buy(MinecraftServer server, BaseSite site, Player player, int index) {
        if (site == null) {
            return Outcome.fail(Result.NO_SITE);
        }
        if (site.fallen()) {
            return Outcome.fail(Result.FALLEN);
        }
        BaseTradeConfig cfg = cfg();
        List<BaseTradeConfig.Offer> offers = offers(site, cfg);
        if (index < 0 || index >= offers.size()) {
            return Outcome.fail(Result.BAD_OFFER);
        }
        BaseTradeConfig.Offer offer = offers.get(index);
        ItemStack goods = stackOf(offer);
        if (goods.isEmpty()) {
            return Outcome.fail(Result.BAD_OFFER);
        }
        double t = AllyTrust.get(server).trust(player.getUUID());
        if (t + 1e-9 < Math.max(cfg.trustTrade, offer.minTrust())) {
            return Outcome.fail(Result.LOCKED);
        }
        int cost = cost(offer, goods, cfg);
        int price = cfg.price(cost, t);
        if (countScrip(player) < price) {
            return Outcome.fail(Result.NO_SCRIP);
        }
        if (site.stock < cost) {
            return Outcome.fail(Result.NO_STOCK);
        }
        removeScrip(player, price);
        site.stock -= cost;
        BaseSites.get(server).changed();
        give(player, goods);
        return new Outcome(Result.OK, cost, 0.0D, price, -cost);
    }

    /** 渡す枠の中身での見込み(画面に出す)。信頼の見込みは 1 日の上限と信頼の上限で切る。 */
    public static Preview preview(MinecraftServer server, Player player, Container items) {
        BaseTradeConfig cfg = cfg();
        double value = valueOf(items, cfg);
        if (value <= 0.0D) {
            return Preview.NONE;
        }
        AllyTrust trust = AllyTrust.get(server);
        double room = Math.min(trust.todayLeft(player.getUUID(), BaseEconomy.today(server), cfg),
                Math.max(0.0D, cfg.trustMax - trust.trust(player.getUUID())));
        double gain = Math.min(value * cfg.trustPerValue, room);
        return new Preview(value, gain, value / cfg.scripValue * cfg.donationThanks, value / cfg.scripValue);
    }

    // ---------- 品 ----------

    /** その基地で売る品(種類で絞った並び。画面の一覧と同じ順)。 */
    public static List<BaseTradeConfig.Offer> offers(BaseSite site) {
        return offers(site, cfg());
    }

    public static List<BaseTradeConfig.Offer> offers(BaseSite site, BaseTradeConfig cfg) {
        List<BaseTradeConfig.Offer> out = new ArrayList<>();
        for (BaseTradeConfig.Offer o : cfg.offers) {
            if (o.soldAt(site.type.id)) {
                out.add(o);
            }
        }
        return out;
    }

    /** 品の実物(数・ポーションの中身つき)。アイテムが無ければ空。 */
    public static ItemStack stackOf(BaseTradeConfig.Offer offer) {
        ResourceLocation rl = ResourceLocation.tryParse(offer.item());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
        if (item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item.get(), offer.count());
        if (offer.potion() != null) {
            ResourceLocation prl = ResourceLocation.tryParse(offer.potion());
            Optional<Holder.Reference<Potion>> potion = prl == null ? Optional.empty()
                    : BuiltInRegistries.POTION.getHolder(ResourceKey.create(Registries.POTION, prl));
            if (potion.isEmpty()) {
                return ItemStack.EMPTY;
            }
            stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion.get()));
        }
        return stack;
    }

    /** 品の値打ち(買うと基地の貯蓄がこれだけ減る)。書いていなければ値打ちの表 × 数(切り上げ)。 */
    public static int cost(BaseTradeConfig.Offer offer, ItemStack goods, BaseTradeConfig cfg) {
        if (offer.cost() >= 0) {
            return offer.cost();
        }
        return (int) Math.ceil(unitValue(goods, cfg) * goods.getCount() - 1e-9);
    }

    // ---------- 軍票 ----------

    public static int countScrip(Player player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(AllyItems.MILITARY_SCRIP.get())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void removeScrip(Player player, int count) {
        var inv = player.getInventory();
        int left = count;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(AllyItems.MILITARY_SCRIP.get())) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        inv.setChanged();
    }

    private static void giveScrip(Player player, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(64, left);
            give(player, new ItemStack(AllyItems.MILITARY_SCRIP.get(), n));
            left -= n;
        }
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }
}
