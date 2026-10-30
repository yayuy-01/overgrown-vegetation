package com.yayuy.overgrownvegetation.menu;

import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.BaseTerminalBlock;
import com.yayuy.overgrownvegetation.base.build.AllyTrust;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.build.BaseTrade;
import com.yayuy.overgrownvegetation.base.build.BaseUpkeep;
import com.yayuy.overgrownvegetation.data.BaseTradeConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 管理端末の画面(順 2 の着手順 5、2026-10-07)。<b>ユーザー指定(10-07)</b>「寄付と取引は管理端末の画面で。タブは状態・寄付・取引。
 * 拡張と着手順 6 の護衛・援軍の指揮もここに足していく。取引の一覧は村人の画面と同じ形」。
 *
 * <h2>枠</h2>
 * 0〜35 = プレイヤーの持ち物、36〜44 = <b>渡す枠</b>(3 × 3。寄付のタブでは寄付する物、取引のタブでは売る物。受け取る物 = 値打ちのある物だけ入る)。
 * 渡す枠は状態のタブでは隠す({@link Slot#isActive})。中身はメニューだけが持ち、閉じると持ち物へ返す。
 *
 * <h2>ボタンの番号({@link #clickMenuButton})</h2>
 * 0〜2 = タブ、{@link #BUTTON_DONATE} = 寄付する、{@link #BUTTON_SELL} = 売る、{@link #BUTTON_UPGRADE} = 拡張する、
 * {@link #BUTTON_BUY} + 何番目 = その品を 1 回買う。作業はタブが合っているときだけ受け付ける。
 *
 * <h2>クライアントへ送るもの</h2>
 * 開いたとき({@link Snapshot}): 基地の番号・種類・状態の文・品の一覧と値段の決め方。開いている間({@link #get}): 信頼・今日あと上がる信頼・
 * 貯蓄・上限・渡す枠の見込み・最後の結果。バニラの数値の同期は 16 ビットなので、1 つの値を 2 つに分けて送る。
 */
public class BaseTerminalMenu extends AbstractContainerMenu {

    public static final int TAB_STATUS = 0;
    public static final int TAB_DONATE = 1;
    public static final int TAB_TRADE = 2;
    public static final int TAB_COUNT = 3;

    public static final int BUTTON_DONATE = 10;
    public static final int BUTTON_SELL = 11;
    public static final int BUTTON_UPGRADE = 12;
    public static final int BUTTON_BUY = 100;
    private static final int MAX_OFFERS = 100;

    /** 画面の大きさと枠の位置(画面の左上から)。 */
    public static final int WIDTH = 256;
    public static final int HEIGHT = 238;
    public static final int GIVE_X = 186;
    public static final int GIVE_Y = 42;
    public static final int INV_X = 48;
    public static final int INV_Y = 156;
    public static final int HOTBAR_Y = 214;
    public static final int SLOT = 18;

    private static final int INV_END = 36;
    private static final int HOTBAR_START = 27;
    public static final int GIVE_START = 36;
    public static final int GIVE_END = 45;

    /** 送る値の番号({@link #get})。×10 は小数 1 桁を整数にしたもの。 */
    public static final int D_TRUST = 0;
    public static final int D_TODAY = 1;
    public static final int D_STOCK = 2;
    public static final int D_CAP = 3;
    public static final int D_P_VALUE = 4;
    public static final int D_P_TRUST = 5;
    public static final int D_P_THANKS = 6;
    public static final int D_P_SELL = 7;
    public static final int D_LAST_CODE = 8;
    public static final int D_LAST_VALUE = 9;
    public static final int D_LAST_TRUST = 10;
    public static final int D_LAST_SCRIP = 11;
    public static final int D_LAST_SEQ = 12;
    public static final int D_LEVEL = 13;
    public static final int D_FLAGS = 14;
    public static final int D_COUNT = 15;

    /** 最後の結果の種類({@link #D_LAST_CODE} = 種類 × 16 + 結果)。 */
    public static final int ACTION_DONATE = 1;
    public static final int ACTION_SELL = 2;
    public static final int ACTION_BUY = 3;
    public static final int ACTION_UPGRADE = 4;

    public static final int FLAG_BUILDING = 1;
    public static final int FLAG_OUTDATED = 2;
    public static final int FLAG_FALLEN = 4;

    private final ContainerLevelAccess access;
    private final Player player;
    private final int siteId;
    public final Snapshot snapshot;
    private final DataSlot tab = DataSlot.standalone();
    private final int[] values = new int[D_COUNT];
    private final SimpleContainer give = new SimpleContainer(9) {
        @Override
        public void setChanged() {
            super.setChanged();
            BaseTerminalMenu.this.slotsChanged(this);
        }
    };
    private BaseTrade.Preview preview = BaseTrade.Preview.NONE;

    /** 品 1 つの見せ方(実物・値打ち・要る信頼)。 */
    public record OfferView(ItemStack stack, int cost, int minTrust) {
    }

    /**
     * 開いたときに送るもの。値段は信頼で変わるので、値段の決め方({@code scripValue}・{@code markup}・{@code discounts})ごと送って画面で計算する
     * (サーバーの {@link BaseTradeConfig#price} と同じ式)。
     */
    public record Snapshot(int siteId, String typeLabel, List<Component> status, List<OfferView> offers, int scripValue, double markup,
                           NavigableMap<Integer, Double> discounts, int trustTrade, int trustGoods, double trustMax, double dailyCap) {

        public static Snapshot of(MinecraftServer server, BaseSite site) {
            BaseTradeConfig cfg = DesignData.defense().baseTrade();
            List<Component> status = new ArrayList<>();
            status.add(BaseEconomy.describe(server, site));
            status.add(com.yayuy.overgrownvegetation.base.road.BaseRoads.describe(server, site));
            status.add(BaseGarrison.describe(site));
            status.add(BaseUpkeep.describe(site));
            List<OfferView> offers = new ArrayList<>();
            for (BaseTradeConfig.Offer o : BaseTrade.offers(site, cfg)) {
                ItemStack goods = BaseTrade.stackOf(o);
                if (offers.size() >= MAX_OFFERS) {
                    break;
                }
                offers.add(new OfferView(goods, goods.isEmpty() ? 0 : BaseTrade.cost(o, goods, cfg), Math.max(cfg.trustTrade, o.minTrust())));
            }
            return new Snapshot(site.id, site.type.label, status, offers, cfg.scripValue, cfg.buyMarkup, cfg.discounts,
                    cfg.trustTrade, cfg.trustGoods, cfg.trustMax, cfg.trustDailyCap);
        }

        void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(siteId);
            buf.writeUtf(typeLabel);
            buf.writeVarInt(status.size());
            for (Component c : status) {
                ComponentSerialization.STREAM_CODEC.encode(buf, c);
            }
            buf.writeVarInt(offers.size());
            for (OfferView o : offers) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, o.stack());
                buf.writeVarInt(o.cost());
                buf.writeVarInt(o.minTrust());
            }
            buf.writeVarInt(scripValue);
            buf.writeDouble(markup);
            buf.writeVarInt(discounts.size());
            for (var e : discounts.entrySet()) {
                buf.writeVarInt(e.getKey());
                buf.writeDouble(e.getValue());
            }
            buf.writeVarInt(trustTrade);
            buf.writeVarInt(trustGoods);
            buf.writeDouble(trustMax);
            buf.writeDouble(dailyCap);
        }

        static Snapshot read(RegistryFriendlyByteBuf buf) {
            int siteId = buf.readVarInt();
            String label = buf.readUtf();
            int n = buf.readVarInt();
            List<Component> status = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                status.add(ComponentSerialization.STREAM_CODEC.decode(buf));
            }
            int m = buf.readVarInt();
            List<OfferView> offers = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                ItemStack s = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                offers.add(new OfferView(s, buf.readVarInt(), buf.readVarInt()));
            }
            int scrip = buf.readVarInt();
            double markup = buf.readDouble();
            int d = buf.readVarInt();
            NavigableMap<Integer, Double> discounts = new TreeMap<>();
            for (int i = 0; i < d; i++) {
                discounts.put(buf.readVarInt(), buf.readDouble());
            }
            return new Snapshot(siteId, label, status, offers, scrip, markup, discounts,
                    buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble());
        }

        /** その信頼での値段(サーバーの {@link BaseTradeConfig#price} と同じ式)。 */
        public int price(int cost, double trust) {
            var e = discounts.floorEntry((int) Math.floor(trust + 1e-9));
            double rate = e != null ? e.getValue() : 0.0D;
            return Math.max(1, (int) Math.ceil(cost * markup * (1.0D - rate) / scripValue - 1e-9));
        }

        public double discount(double trust) {
            var e = discounts.floorEntry((int) Math.floor(trust + 1e-9));
            return e != null ? e.getValue() : 0.0D;
        }
    }

    /** クライアント側(画面を開くとき)。 */
    public BaseTerminalMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, ContainerLevelAccess.NULL, Snapshot.read(buf));
    }

    public BaseTerminalMenu(int containerId, Inventory inventory, ContainerLevelAccess access, Snapshot snapshot) {
        super(ModMenus.BASE_TERMINAL.get(), containerId);
        this.access = access;
        this.player = inventory.player;
        this.siteId = snapshot.siteId();
        this.snapshot = snapshot;
        this.addDataSlot(this.tab);
        this.addDataSlots(new Packed());
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, INV_X + col * SLOT, INV_Y + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, INV_X + col * SLOT, HOTBAR_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                this.addSlot(new Slot(this.give, col + row * 3, GIVE_X + col * SLOT, GIVE_Y + row * SLOT) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return BaseTrade.accepts(stack);
                    }

                    @Override
                    public boolean isActive() {
                        return tab() != TAB_STATUS;
                    }
                });
            }
        }
        if (!this.player.level().isClientSide) {
            refresh();
        }
    }

    /** サーバー: 画面を開く(管理端末を素手で右クリック)。 */
    public static void open(ServerPlayer player, ServerLevel level, BlockPos pos, BaseSite site) {
        Snapshot snap = Snapshot.of(level.getServer(), site);
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new BaseTerminalMenu(id, inv, ContainerLevelAccess.create(level, pos), snap),
                Component.translatable("container.overgrown_vegetation.base_terminal", site.id, site.type.label)), snap::write);
    }

    // ---------- 値 ----------

    /** いま開いているタブ(0〜2)。 */
    public int tab() {
        return Mth.clamp(this.tab.get(), 0, TAB_COUNT - 1);
    }

    /** 送られてきた値({@link #D_TRUST} など)。 */
    public int get(int index) {
        return this.values[index];
    }

    /** 信頼(小数)。 */
    public double trust() {
        return this.values[D_TRUST] / 10.0D;
    }

    /** 1 つの値を 16 ビットずつ 2 つに分けて同期する(バニラの数値の同期は 16 ビット)。 */
    private final class Packed implements ContainerData {
        @Override
        public int get(int index) {
            int v = values[index >> 1];
            return (index & 1) == 0 ? (v & 0xFFFF) : ((v >>> 16) & 0xFFFF);
        }

        @Override
        public void set(int index, int value) {
            int k = index >> 1;
            int v = values[k];
            values[k] = (index & 1) == 0 ? (v & 0xFFFF0000) | (value & 0xFFFF) : (v & 0xFFFF) | ((value & 0xFFFF) << 16);
        }

        @Override
        public int getCount() {
            return D_COUNT * 2;
        }
    }

    private BaseSite site() {
        MinecraftServer server = this.player.getServer();
        return server != null ? BaseSites.get(server).byId(this.siteId) : null;
    }

    /** サーバー: 送る値を今の状態で書き直す。 */
    private void refresh() {
        MinecraftServer server = this.player.getServer();
        BaseSite site = site();
        if (server == null || site == null) {
            return;
        }
        BaseTradeConfig cfg = DesignData.defense().baseTrade();
        AllyTrust trust = AllyTrust.get(server);
        this.values[D_TRUST] = (int) Math.round(trust.trust(this.player.getUUID()) * 10.0D);
        this.values[D_TODAY] = (int) Math.round(trust.todayLeft(this.player.getUUID(), BaseEconomy.today(server), cfg) * 10.0D);
        this.values[D_STOCK] = site.stock();
        this.values[D_CAP] = DesignData.defense().baseStockCap(site.level());
        this.values[D_P_VALUE] = (int) Math.round(this.preview.value() * 10.0D);
        this.values[D_P_TRUST] = (int) Math.round(this.preview.trustGain() * 10.0D);
        this.values[D_P_THANKS] = (int) Math.round(this.preview.thanks() * 10.0D);
        this.values[D_P_SELL] = (int) Math.round(this.preview.sell() * 10.0D);
        this.values[D_LEVEL] = site.level();
        this.values[D_FLAGS] = (site.building() ? FLAG_BUILDING : 0) | (site.outdated() ? FLAG_OUTDATED : 0) | (site.fallen() ? FLAG_FALLEN : 0);
    }

    @Override
    public void broadcastChanges() {
        if (!this.player.level().isClientSide) {
            refresh();
        }
        super.broadcastChanges();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        MinecraftServer server = this.player.getServer();
        if (container == this.give && server != null && !this.player.level().isClientSide) {
            this.preview = BaseTrade.preview(server, this.player, this.give);
        }
    }

    // ---------- ボタン ----------

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= 0 && id < TAB_COUNT) {
            this.tab.set(id);
            return true;
        }
        if (player.level().isClientSide) {
            return id == BUTTON_DONATE || id == BUTTON_SELL || id == BUTTON_UPGRADE || (id >= BUTTON_BUY && id < BUTTON_BUY + MAX_OFFERS);
        }
        MinecraftServer server = player.getServer();
        BaseSite site = site();
        if (server == null || site == null) {
            return false;
        }
        if (id == BUTTON_DONATE && tab() == TAB_DONATE) {
            report(ACTION_DONATE, BaseTrade.donate(server, site, player, this.give));
        } else if (id == BUTTON_SELL && tab() == TAB_TRADE) {
            report(ACTION_SELL, BaseTrade.sell(server, site, player, this.give));
        } else if (id >= BUTTON_BUY && id < BUTTON_BUY + MAX_OFFERS && tab() == TAB_TRADE) {
            report(ACTION_BUY, BaseTrade.buy(server, site, player, id - BUTTON_BUY));
        } else if (id == BUTTON_UPGRADE && tab() == TAB_STATUS) {
            this.access.execute((level, pos) -> {
                BaseTerminalBlock.UpgradeResult r = BaseTerminalBlock.upgrade((ServerLevel) level, pos, player,
                        BaseSites.get(server), site, false);
                this.values[D_LAST_CODE] = ACTION_UPGRADE * 16 + r.ordinal();
                this.values[D_LAST_SEQ]++;
                if (r == BaseTerminalBlock.UpgradeResult.STARTED) {
                    player.closeContainer();
                }
            });
        } else {
            return false;
        }
        this.preview = BaseTrade.preview(server, player, this.give);
        refresh();
        return true;
    }

    private void report(int action, BaseTrade.Outcome outcome) {
        this.values[D_LAST_CODE] = action * 16 + outcome.result().ordinal();
        this.values[D_LAST_VALUE] = (int) Math.round(outcome.value() * 10.0D);
        this.values[D_LAST_TRUST] = (int) Math.round(outcome.trust() * 10.0D);
        this.values[D_LAST_SCRIP] = outcome.scrip();
        this.values[D_LAST_SEQ]++;
        if (outcome.ok()) {
            this.player.playNotifySound(action == ACTION_BUY ? SoundEvents.VILLAGER_YES : SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS, 0.6F, action == ACTION_DONATE ? 1.2F : 1.0F);
        }
    }

    // ---------- 枠 ----------

    /** shift クリック。渡す枠からは持ち物へ。持ち物からは、受け取る物なら渡す枠へ(見えているとき)、それ以外は持ち物 ↔ ホットバー。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();
        if (index >= GIVE_START) {
            if (!this.moveItemStackTo(stack, 0, INV_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (tab() != TAB_STATUS && BaseTrade.accepts(stack) && this.moveItemStackTo(stack, GIVE_START, GIVE_END, false)) {
            // 渡す枠へ入った
        } else if (index < HOTBAR_START) {
            if (!this.moveItemStackTo(stack, HOTBAR_START, INV_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, HOTBAR_START, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return result;
    }

    /** 渡す枠(GameTest 用)。 */
    public Container giveSlots() {
        return this.give;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!AbstractContainerMenu.stillValid(this.access, player, BaseBlocks.BASE_TERMINAL.get())) {
            return false;
        }
        BaseSite site = site();
        return player.level().isClientSide || (site != null && !site.fallen());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.access.execute((level, pos) -> this.clearContainer(player, this.give));
    }
}
