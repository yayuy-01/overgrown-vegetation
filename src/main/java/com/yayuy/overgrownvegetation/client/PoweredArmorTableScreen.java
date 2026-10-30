package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableCrafting;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorTableRecipe;
import com.yayuy.overgrownvegetation.crafting.PoweredArmorWorkshop;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.item.ArmorAccessory;
import com.yayuy.overgrownvegetation.item.CartridgeContents;
import com.yayuy.overgrownvegetation.item.CartridgeFactor;
import com.yayuy.overgrownvegetation.item.CartridgeItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprint;
import com.yayuy.overgrownvegetation.item.PoweredArmorBlueprintItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import com.yayuy.overgrownvegetation.item.PoweredArmorTree;
import com.yayuy.overgrownvegetation.menu.PoweredArmorTableMenu;
import com.yayuy.overgrownvegetation.menu.PoweredArmorUi;
import com.yayuy.overgrownvegetation.network.PoweredArmorCraftPayload;
import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * パワードアーマー作成テーブルの UI(順 1 の着手順 5、2026-10-03。2026-10-14 から仮でバニラの窓 —— ユーザー指定、
 * 世界観に合わせて作り直すまでのつなぎ。前の全画面・ドット調の版は {@code tools/ui-backup-2026-10-14/})。
 *
 * <p>座標の扱いは研究台({@link ResearchTableScreen})と同じ: {@link PoweredArmorUi} のキャンバス(320×240、窓の上のタブの帯を含む)を
 * ふつうは等倍で画面の真ん中に置き(狭い画面だけ縮める)、描画も入力もキャンバス座標で行う。{@code leftPos}/{@code topPos} は常に 0
 * (マウス座標をキャンバスへ直してから {@code super} に渡せば、バニラのスロットの当たりがそのまま効く)。
 * 窓・ボタンの描き方は {@link VanillaGui}。
 *
 * <p>作成タブは<b>一覧から選んで「作成」</b>(ユーザー指定「B」)—— 素材は持ち物から引き、足りない行は赤で出す。
 * スキルツリーのタブは<b>作業枠の装備 1 つの 3 本 × 3 段</b>(同「P」、着手順 6)。設計図のタブは作業枠を共用する。
 * 装着のタブは<b>付ける物の枠 1 つ</b>にカートリッジ・装飾品・拡張パーツのどれでも置き、真ん中の説明が中身に合わせて変わる。
 * 右に装備の差し口 3 つと、それぞれの「外す」(着手順 8・9。ユーザー指定「b」)。因子の入れ方の案内は「入れる」のツールチップ。
 *
 * <p>改造・写す・作り直すは {@code handleInventoryButtonClick} の番号で送る(サーバーの
 * {@link PoweredArmorTableMenu#clickMenuButton} が確かめ直す)。押せるかどうかの見た目はクライアントの写しで判定する。
 */
public class PoweredArmorTableScreen extends AbstractContainerScreen<PoweredArmorTableMenu> {

    private static final String KEY = "gui.overgrown_vegetation.powered_armor_table.";

    // 窓の上の文字(バニラ)
    private static final int TEXT = VanillaGui.TEXT;
    private static final int TEXT_DIM = VanillaGui.TEXT_DIM;
    private static final int OK = VanillaGui.GOOD;
    private static final int NG = VanillaGui.BAD;
    // ボタン・へこんだ枠の上の文字(影付き)
    private static final int ON_DARK = 0xFFFFFFFF;
    private static final int ON_DARK_DIM = 0xFFA0A0A0;
    private static final int ON_DARK_GOOD = 0xFF80FF80;
    private static final int SELECTED_TEXT = 0xFFFFFF55;

    // ---- キャンバス → 画面 の変換 ----
    private float uiScale = 1.0F;
    private float originX;
    private float originY;

    /** 作成タブで選んでいるレシピ(id で持つ。一覧が変わっても同じものを指す)。 */
    private ResourceLocation selectedId;
    private int scroll;
    /** スキルツリーのタブで選んでいる本(0 = 防御 / 1 = 強靭 / 2 = 耐久)。上げられるのはその本の次の段。 */
    private int selectedBranch;
    /** 直前の描画で各ボタンが押せたか(クリックはこれを見る。描画はクリックより先に毎フレーム走る)。 */
    private boolean upgradeEnabled;
    private boolean copyEnabled;
    private boolean rebuildEnabled;
    private boolean insertEnabled;
    private boolean attachEnabled;
    /** 差し口ごとの「外す」(0 = カートリッジ、1 = 装飾品、2 = 拡張パーツ)。 */
    private final boolean[] detachEnabled = new boolean[3];
    /** 改造の支払いの素材の枠の左端(研究ポイントの文字の幅で決まる。描画で決めてツールチップも同じ値を使う)。 */
    private int costItemsX = PoweredArmorUi.COST_ITEMS_X;

    public PoweredArmorTableScreen(PoweredArmorTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = PoweredArmorUi.CANVAS_W;
        this.imageHeight = PoweredArmorUi.CANVAS_H;
    }

    @Override
    protected void init() {
        this.imageWidth = PoweredArmorUi.CANVAS_W;
        this.imageHeight = PoweredArmorUi.CANVAS_H;
        super.init();
        this.uiScale = Math.min(1.0F, Math.min(this.width / (float) PoweredArmorUi.CANVAS_W,
                this.height / (float) PoweredArmorUi.CANVAS_H));
        this.originX = Math.round((this.width - PoweredArmorUi.CANVAS_W * uiScale) / 2.0F);
        this.originY = Math.round((this.height - PoweredArmorUi.CANVAS_H * uiScale) / 2.0F);
        this.leftPos = 0;
        this.topPos = 0;
    }

    // ---------- 座標変換 ----------

    private double toCanvasX(double screenX) {
        return (screenX - originX) / uiScale;
    }

    private double toCanvasY(double screenY) {
        return (screenY - originY) / uiScale;
    }

    private static boolean in(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // ---------- 描画 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(g);
        int cmx = (int) Math.floor(toCanvasX(mouseX));
        int cmy = (int) Math.floor(toCanvasY(mouseY));

        g.pose().pushPose();
        g.pose().translate(originX, originY, 0.0F);
        g.pose().scale(uiScale, uiScale, 1.0F);
        super.render(g, cmx, cmy, partialTick);
        g.pose().popPose();

        // ツールチップは等倍・画面座標で(端でのはみ出し処理がバニラの画面幅基準のため。研究台と同じ)。
        this.renderTooltip(g, mouseX, mouseY);
        if (this.menu.getCarried().isEmpty() && this.hoveredSlot == null) {
            ItemStack hover = hoveredStack(cmx, cmy);
            if (!hover.isEmpty()) {
                g.renderTooltip(this.font, hover, mouseX, mouseY);
            } else if (this.menu.tab() == PoweredArmorTableMenu.TAB_FIT && in(cmx, cmy, PoweredArmorUi.INSERT_BTN_X,
                    PoweredArmorUi.INSERT_BTN_Y, PoweredArmorUi.INSERT_BTN_W, PoweredArmorUi.INSERT_BTN_H)) {
                // 因子の入れ方の案内(窓に入りきらないのでツールチップへ)
                boolean cartridge = this.menu.attachStack().getItem() instanceof CartridgeItem;
                g.renderTooltip(this.font, this.font.split(Component.translatable(KEY + (cartridge ? "fit_hint"
                        : "fit_hint_no_cartridge")), 200), mouseX, mouseY);
            }
        }
    }

    /** 暗転は {@link #render} が画面全体に描くので、ここでは UI 本体だけ。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 見出し・ラベルは renderBg で描く(タブごとに置き場所が違う)。
    }

    /** ここに来る {@code mouseX}/{@code mouseY} はキャンバス座標。 */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int active = this.menu.tab();
        for (int i = 0; i < PoweredArmorUi.TAB_COUNT; i++) {
            if (i != active) {
                VanillaGui.tab(g, this.font, Component.translatable(KEY + "tab." + i), tabX(i), PoweredArmorUi.WIN_Y,
                        PoweredArmorUi.TAB_W, PoweredArmorUi.TAB_STRIP_H, false);
            }
        }
        VanillaGui.panel(g, PoweredArmorUi.WIN_X, PoweredArmorUi.WIN_Y, PoweredArmorUi.WIN_W, PoweredArmorUi.WIN_H);
        VanillaGui.tab(g, this.font, Component.translatable(KEY + "tab." + active), tabX(active), PoweredArmorUi.WIN_Y,
                PoweredArmorUi.TAB_W, PoweredArmorUi.TAB_STRIP_H, true);

        g.drawString(this.font, Component.translatable("container.inventory"), PoweredArmorUi.INV_X - 1,
                PoweredArmorUi.labelY(PoweredArmorUi.INV_Y), TEXT, false);
        for (int i = 0; i < this.menu.slots.size(); i++) {
            Slot slot = this.menu.slots.get(i);
            if (!slot.isActive()) {
                continue;
            }
            if (i == PoweredArmorTableMenu.WORK_SLOT || i == PoweredArmorTableMenu.PRINT_SLOT
                    || i == PoweredArmorTableMenu.ATTACH_SLOT) {
                // 作業枠・設計図・付ける物の枠は大きい枠(アイテムはその真ん中)
                VanillaGui.slot(g, slot.x - 5, slot.y - 5, PoweredArmorUi.BIG_FRAME, PoweredArmorUi.BIG_FRAME);
            } else {
                VanillaGui.slotAround(g, slot.x, slot.y);
            }
        }

        switch (active) {
            case PoweredArmorTableMenu.TAB_CRAFT -> renderCraft(g, mouseX, mouseY);
            case PoweredArmorTableMenu.TAB_TREE -> renderTree(g, mouseX, mouseY);
            case PoweredArmorTableMenu.TAB_BLUEPRINT -> renderBlueprint(g, mouseX, mouseY);
            default -> renderFit(g, mouseX, mouseY);
        }
    }

    private static int tabX(int i) {
        return PoweredArmorUi.TAB_X + i * PoweredArmorUi.TAB_STEP;
    }

    // ---------- 作成タブ ----------

    private List<RecipeHolder<PoweredArmorTableRecipe>> recipes() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return List.of();
        }
        return PoweredArmorTableCrafting.recipes(this.minecraft.level.getRecipeManager());
    }

    private RecipeHolder<PoweredArmorTableRecipe> selected(List<RecipeHolder<PoweredArmorTableRecipe>> list) {
        for (RecipeHolder<PoweredArmorTableRecipe> h : list) {
            if (h.id().equals(this.selectedId)) {
                return h;
            }
        }
        RecipeHolder<PoweredArmorTableRecipe> first = list.get(0);
        this.selectedId = first.id();
        return first;
    }

    private List<ItemStack> inventoryItems() {
        return this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getInventory().items : List.of();
    }

    private void renderCraft(GuiGraphics g, int mouseX, int mouseY) {
        List<RecipeHolder<PoweredArmorTableRecipe>> list = recipes();
        if (list.isEmpty()) {
            Component empty = Component.translatable(KEY + "empty");
            g.drawString(this.font, empty, contentCenterX() - this.font.width(empty) / 2, contentCenterY() - 4, TEXT_DIM, false);
            return;
        }
        RecipeHolder<PoweredArmorTableRecipe> sel = selected(list);
        this.scroll = Math.max(0, Math.min(this.scroll, list.size() - PoweredArmorUi.LIST_ROWS));

        for (int row = 0; row < PoweredArmorUi.LIST_ROWS && this.scroll + row < list.size(); row++) {
            RecipeHolder<PoweredArmorTableRecipe> h = list.get(this.scroll + row);
            int y = PoweredArmorUi.LIST_Y + row * PoweredArmorUi.LIST_STEP;
            boolean isSel = h == sel;
            boolean hover = in(mouseX, mouseY, PoweredArmorUi.LIST_X, y, PoweredArmorUi.LIST_W, PoweredArmorUi.LIST_ROW_H);
            VanillaGui.button(g, PoweredArmorUi.LIST_X, y, PoweredArmorUi.LIST_W, PoweredArmorUi.LIST_ROW_H, true, isSel || hover);
            g.renderItem(h.value().result(), PoweredArmorUi.LIST_X + 2, y + 2);
            int color = isLocked(h.id()) ? ON_DARK_DIM : isSel ? SELECTED_TEXT : ON_DARK;
            g.drawString(this.font, trim(listName(h.value()), PoweredArmorUi.LIST_W - 24), PoweredArmorUi.LIST_X + 20,
                    y + 6, color, true);
        }
        if (list.size() > PoweredArmorUi.LIST_ROWS) {
            String more = (this.scroll + 1) + "-" + Math.min(list.size(), this.scroll + PoweredArmorUi.LIST_ROWS)
                    + " / " + list.size();
            g.drawString(this.font, more, PoweredArmorUi.LIST_X + 1,
                    PoweredArmorUi.LIST_Y + PoweredArmorUi.LIST_ROWS * PoweredArmorUi.LIST_STEP + 2, TEXT_DIM, false);
        }
        renderDetail(g, sel, mouseX, mouseY);
    }

    private void renderDetail(GuiGraphics g, RecipeHolder<PoweredArmorTableRecipe> sel, int mouseX, int mouseY) {
        PoweredArmorTableRecipe recipe = sel.value();
        ItemStack result = recipe.result();
        VanillaGui.slotAround(g, PoweredArmorUi.DETAIL_X + 1, PoweredArmorUi.DETAIL_ICON_Y);
        g.renderItem(result, PoweredArmorUi.DETAIL_X + 1, PoweredArmorUi.DETAIL_ICON_Y);
        g.renderItemDecorations(this.font, result, PoweredArmorUi.DETAIL_X + 1, PoweredArmorUi.DETAIL_ICON_Y); // 個数はアイコンの上に
        int nameW = PoweredArmorUi.BTN_X - 4 - PoweredArmorUi.DETAIL_NAME_X;
        List<FormattedCharSequence> name = this.font.split(result.getHoverName(), nameW);
        int nameY = name.size() == 1 ? PoweredArmorUi.DETAIL_NAME_Y + 4 : PoweredArmorUi.DETAIL_NAME_Y - 1;
        for (int i = 0; i < Math.min(2, name.size()); i++) {
            g.drawString(this.font, name.get(i), PoweredArmorUi.DETAIL_NAME_X, nameY + i * PoweredArmorUi.INFO_LINE, TEXT, false);
        }

        g.drawString(this.font, Component.translatable(KEY + "materials"), PoweredArmorUi.DETAIL_X,
                PoweredArmorUi.labelY(PoweredArmorUi.MATS_Y), TEXT, false);
        List<ItemStack> inv = inventoryItems();
        List<SizedIngredient> mats = recipe.ingredients();
        for (int i = 0; i < Math.min(PoweredArmorUi.MATS_ROWS, mats.size()); i++) {
            SizedIngredient need = mats.get(i);
            int y = PoweredArmorUi.MATS_Y + i * PoweredArmorUi.MATS_STEP;
            VanillaGui.slot(g, PoweredArmorUi.DETAIL_X, y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT);
            ItemStack shown = shown(need);
            g.renderItem(shown, PoweredArmorUi.DETAIL_X + 1, y + 1);
            int have = PoweredArmorTableCrafting.count(need.ingredient(), inv);
            String count = have + " / " + need.count();
            int countX = PoweredArmorUi.MATS_COUNT_RIGHT - this.font.width(count);
            g.drawString(this.font, trim(shown.getHoverName(), countX - PoweredArmorUi.MATS_NAME_X - 6),
                    PoweredArmorUi.MATS_NAME_X, y + 5, TEXT, false);
            g.drawString(this.font, count, countX, y + 5, have >= need.count() ? OK : NG, false);
        }

        boolean locked = isLocked(sel.id());
        boolean can = !locked && PoweredArmorTableCrafting.hasAll(recipe, inv);
        boolean hover = in(mouseX, mouseY, PoweredArmorUi.BTN_X, PoweredArmorUi.BTN_Y, PoweredArmorUi.BTN_W, PoweredArmorUi.BTN_H);
        VanillaGui.button(g, this.font, Component.translatable(KEY + "craft"), PoweredArmorUi.BTN_X, PoweredArmorUi.BTN_Y,
                PoweredArmorUi.BTN_W, PoweredArmorUi.BTN_H, can, hover);
        if (!can) {
            Component why = Component.translatable(KEY + (locked ? "locked" : "missing"));
            g.drawString(this.font, why, PoweredArmorUi.MATS_COUNT_RIGHT - this.font.width(why),
                    PoweredArmorUi.labelY(PoweredArmorUi.MATS_Y), NG, false);
        }
    }

    /** 一覧の短い名前。レシピの {@code label}(翻訳キー)、無ければ結果の名前。 */
    private static Component listName(PoweredArmorTableRecipe recipe) {
        return recipe.label().isEmpty() ? recipe.result().getHoverName() : Component.translatable(recipe.label());
    }

    /** 素材の行に見せるアイテム。候補が複数(タグ)なら 1 秒ごとに入れ替える。 */
    private static ItemStack shown(SizedIngredient need) {
        ItemStack[] items = need.ingredient().getItems();
        if (items.length == 0) {
            return ItemStack.EMPTY;
        }
        return items[(int) ((Util.getMillis() / 1000L) % items.length)];
    }

    /** 研究で封印されているか(クライアントの研究の写しで判定。サーバーも作るときに確かめる)。 */
    private static boolean isLocked(ResourceLocation recipeId) {
        String key = recipeId.toString();
        for (ResearchSyncPayload.NodeView node : ClientResearch.nodes()) {
            for (String unlock : node.unlocks()) {
                String id = unlock.contains(":") ? unlock : OvergrownVegetation.MODID + ":" + unlock;
                if (id.equals(key)) {
                    return !ClientResearch.isUnlocked(node.id());
                }
            }
        }
        return false;
    }

    // ---------- スキルツリーのタブ(着手順 6。ユーザー指定「P」)----------

    /** 段の箱の状態。 */
    private enum NodeState { LOCKED, NEXT, DONE }

    private void renderTree(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, Component.translatable(KEY + "work"), PoweredArmorUi.WORK_FRAME_X,
                PoweredArmorUi.labelY(PoweredArmorUi.WORK_FRAME_Y), TEXT, false);
        ItemStack armor = this.menu.workStack();
        boolean has = armor.getItem() instanceof PoweredArmorItem;
        renderArmorInfo(g, armor, PoweredArmorUi.WORK_INFO_X, PoweredArmorUi.WORK_INFO_Y);

        PoweredArmorTree tree = has ? PoweredArmorItem.tree(armor) : PoweredArmorTree.NONE;
        GearTable.PoweredArmor cfg = DesignData.gear().poweredArmor();
        int max = cfg.maxLevel();
        for (int b = 0; b < PoweredArmorTree.BRANCHES; b++) {
            int x = PoweredArmorUi.TREE_X + b * PoweredArmorUi.TREE_COL_STEP;
            Component head = Component.translatable(KEY + "branch." + b);
            g.drawString(this.font, head, x + (PoweredArmorUi.NODE_W - this.font.width(head)) / 2,
                    PoweredArmorUi.TREE_HEAD_Y, TEXT, false);
            int cur = tree.level(b);
            for (int lv = 1; lv <= max; lv++) {
                int y = PoweredArmorUi.NODE_Y + (lv - 1) * PoweredArmorUi.NODE_STEP;
                NodeState st = !has ? NodeState.LOCKED : lv <= cur ? NodeState.DONE
                        : lv == cur + 1 ? NodeState.NEXT : NodeState.LOCKED;
                boolean sel = has && b == this.selectedBranch && lv == Math.min(cur + 1, max);
                boolean hover = in(mouseX, mouseY, x, y, PoweredArmorUi.NODE_W, PoweredArmorUi.NODE_H);
                VanillaGui.button(g, x, y, PoweredArmorUi.NODE_W, PoweredArmorUi.NODE_H, st != NodeState.LOCKED,
                        st == NodeState.NEXT && hover);
                if (sel) {
                    outline(g, x - 1, y - 1, PoweredArmorUi.NODE_W + 2, PoweredArmorUi.NODE_H + 2, 0xFFE0C060);
                }
                int color = st == NodeState.DONE ? ON_DARK_GOOD : st == NodeState.NEXT ? ON_DARK : ON_DARK_DIM;
                g.drawString(this.font, "Lv" + lv, x + 4, y + 5, color, true);
                String effect = trim(effectText(b, lv, cfg), PoweredArmorUi.NODE_W - 26);
                g.drawString(this.font, effect, x + PoweredArmorUi.NODE_W - 4 - this.font.width(effect), y + 5, color, true);
                if (lv < max) {
                    int cx = x + PoweredArmorUi.NODE_W / 2;
                    g.fill(cx - 1, y + PoweredArmorUi.NODE_H, cx + 1, y + PoweredArmorUi.NODE_STEP,
                            lv < cur ? OK : 0xFF8B8B8B);
                }
            }
        }
        if (!has) {
            // 作業枠は装飾品のために通常の防具も受け付ける(着手順 9)。改造できるのはパワードアーマーだけ
            g.drawString(this.font, Component.translatable(KEY + (armor.isEmpty() ? "put_armor" : "tree_powered_only")),
                    PoweredArmorUi.TREE_X, PoweredArmorUi.COST_LABEL_Y, armor.isEmpty() ? TEXT_DIM : NG, false);
            return;
        }

        int cur = tree.level(this.selectedBranch);
        Component branch = Component.translatable(KEY + "branch." + this.selectedBranch);
        GearTable.LevelCost cost = cfg.cost(cur + 1);
        Component upgrade = Component.translatable(KEY + "upgrade");
        if (cost == null) {
            g.drawString(this.font, Component.translatable(KEY + "maxed", branch), PoweredArmorUi.TREE_X,
                    PoweredArmorUi.COST_LABEL_Y, TEXT, false);
            VanillaGui.button(g, this.font, upgrade, PoweredArmorUi.UPGRADE_BTN_X, PoweredArmorUi.UPGRADE_BTN_Y,
                    PoweredArmorUi.UPGRADE_BTN_W, PoweredArmorUi.UPGRADE_BTN_H, false, false);
            return;
        }
        g.drawString(this.font, Component.translatable(KEY + "cost", branch, cur + 1), PoweredArmorUi.TREE_X,
                PoweredArmorUi.COST_LABEL_Y, TEXT, false);
        int points = ClientResearch.points();
        boolean pointsOk = points >= cost.points();
        Component pointsText = Component.translatable(KEY + "points", points, cost.points());
        g.drawString(this.font, pointsText, PoweredArmorUi.TREE_X, PoweredArmorUi.COST_Y + 5, pointsOk ? OK : NG, false);
        // 研究ポイントの桁が増えても素材の枠と重ならないよう、文字の右から並べる
        this.costItemsX = Math.max(PoweredArmorUi.COST_ITEMS_X, PoweredArmorUi.TREE_X + this.font.width(pointsText) + 8);
        List<SizedIngredient> mats = PoweredArmorWorkshop.materials(cost, this.selectedBranch);
        List<ItemStack> inv = inventoryItems();
        for (int i = 0; i < mats.size(); i++) {
            int x = this.costItemsX + i * PoweredArmorUi.COST_ITEM_STEP;
            SizedIngredient need = mats.get(i);
            VanillaGui.slot(g, x, PoweredArmorUi.COST_Y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT);
            g.renderItem(shown(need), x + 1, PoweredArmorUi.COST_Y + 1);
            int have = PoweredArmorTableCrafting.count(need.ingredient(), inv);
            g.drawString(this.font, have + " / " + need.count(), x + 21, PoweredArmorUi.COST_Y + 5,
                    have >= need.count() ? OK : NG, false);
        }
        boolean matsOk = PoweredArmorTableCrafting.hasAll(mats, inv);
        boolean can = pointsOk && matsOk;
        this.upgradeEnabled = can;
        boolean hover = in(mouseX, mouseY, PoweredArmorUi.UPGRADE_BTN_X, PoweredArmorUi.UPGRADE_BTN_Y,
                PoweredArmorUi.UPGRADE_BTN_W, PoweredArmorUi.UPGRADE_BTN_H);
        VanillaGui.button(g, this.font, upgrade, PoweredArmorUi.UPGRADE_BTN_X, PoweredArmorUi.UPGRADE_BTN_Y,
                PoweredArmorUi.UPGRADE_BTN_W, PoweredArmorUi.UPGRADE_BTN_H, can, hover);
        if (!can) {
            Component why = Component.translatable(KEY + (pointsOk ? "missing" : "no_points"));
            g.drawString(this.font, why, PoweredArmorUi.TREE_X, PoweredArmorUi.STATUS_Y, NG, false);
        }
    }

    /** 段の箱に書く効き目(その段までの合計。部位 1 つぶん)。 */
    private static Component effectText(int branch, int level, GearTable.PoweredArmor cfg) {
        double per = switch (branch) {
            case PoweredArmorTree.DEFENSE -> cfg.defensePerLevel();
            case PoweredArmorTree.TOUGHNESS -> cfg.piercingPerLevel();
            default -> cfg.durabilityPerLevel();
        };
        return Component.translatable(KEY + "effect." + branch, PoweredArmorItem.percent(level * per));
    }

    /** 作業枠・設計図の下の説明: 名前(2 行まで)と 3 本の段。空なら案内だけ。 */
    private void renderArmorInfo(GuiGraphics g, ItemStack armor, int x, int y) {
        int w = PoweredArmorUi.WORK_INFO_W;
        if (armor.isEmpty()) {
            drawWrapped(g, Component.translatable(KEY + "put_armor_short"), x, y, w, TEXT_DIM);
            return;
        }
        if (!(armor.getItem() instanceof PoweredArmorItem)) {
            // 装飾品のために置いた通常の防具(着手順 9)
            drawWrapped(g, armor.getHoverName(), x, y, w, TEXT);
            drawWrapped(g, Component.translatable(KEY + "not_powered"), x, y + PoweredArmorUi.INFO_LEVELS_DY, w, NG);
            return;
        }
        drawWrapped(g, armor.getHoverName(), x, y, w, TEXT);
        PoweredArmorTree tree = PoweredArmorItem.tree(armor);
        for (int b = 0; b < PoweredArmorTree.BRANCHES; b++) {
            g.drawString(this.font, Component.translatable(KEY + "level_line", Component.translatable(KEY + "branch." + b),
                            tree.level(b)), x, y + PoweredArmorUi.INFO_LEVELS_DY + b * PoweredArmorUi.INFO_LINE,
                    tree.level(b) > 0 ? OK : TEXT_DIM, false);
        }
    }

    private void drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int color) {
        drawWrapped(g, text, x, y, width, color, 2);
    }

    private void drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int color, int maxLines) {
        List<FormattedCharSequence> lines = this.font.split(text, width);
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            g.drawString(this.font, lines.get(i), x, y + i * PoweredArmorUi.INFO_LINE, color, false);
        }
    }

    // ---------- 設計図のタブ(着手順 6)----------

    private void renderBlueprint(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, Component.translatable(KEY + "work"), PoweredArmorUi.WORK_FRAME_X,
                PoweredArmorUi.labelY(PoweredArmorUi.WORK_FRAME_Y), TEXT, false);
        g.drawString(this.font, Component.translatable(KEY + "blueprint"), PoweredArmorUi.PRINT_FRAME_X,
                PoweredArmorUi.labelY(PoweredArmorUi.PRINT_FRAME_Y), TEXT, false);
        ItemStack armor = this.menu.workStack();
        ItemStack print = this.menu.blueprintStack();
        renderArmorInfo(g, armor, PoweredArmorUi.WORK_INFO_X, PoweredArmorUi.WORK_INFO_Y);
        VanillaGui.arrow(g, PoweredArmorUi.PRINT_ARROW_X, PoweredArmorUi.PRINT_ARROW_Y);

        PoweredArmorBlueprint bp = PoweredArmorBlueprintItem.data(print);
        int ix = PoweredArmorUi.PRINT_INFO_X;
        int iy = PoweredArmorUi.PRINT_INFO_Y;
        int infoW = PoweredArmorUi.REBUILD_X - 6 - ix;
        if (print.isEmpty()) {
            drawWrapped(g, Component.translatable(KEY + "put_blueprint"), ix, iy, infoW, TEXT_DIM);
        } else if (bp == null) {
            g.drawString(this.font, Component.translatable(KEY + "blank"), ix, iy, TEXT, false);
        } else {
            g.drawString(this.font, trim(PoweredArmorBlueprintItem.partName(bp.part()).copy().append(" Mk." + bp.mk()), infoW),
                    ix, iy, TEXT, false);
            for (int b = 0; b < PoweredArmorTree.BRANCHES; b++) {
                int lv = bp.tree().level(b);
                g.drawString(this.font, Component.translatable(KEY + "level_line",
                        Component.translatable(KEY + "branch." + b), lv), ix, iy + 12 + b * PoweredArmorUi.INFO_LINE,
                        lv > 0 ? OK : TEXT_DIM, false);
            }
        }

        // 写す
        String copyWhy = null;
        if (!(armor.getItem() instanceof PoweredArmorItem armorItem)) {
            copyWhy = "put_armor_short";
        } else if (print.isEmpty()) {
            copyWhy = "put_blueprint";
        } else if (bp != null && !bp.part().equals(armorItem.getType().getName())) {
            copyWhy = "wrong_part";
        }
        boolean canCopy = copyWhy == null;
        this.copyEnabled = canCopy;
        boolean hoverCopy = in(mouseX, mouseY, PoweredArmorUi.COPY_BTN_X, PoweredArmorUi.COPY_BTN_Y,
                PoweredArmorUi.COPY_BTN_W, PoweredArmorUi.COPY_BTN_H);
        VanillaGui.button(g, this.font, Component.translatable(KEY + "copy"), PoweredArmorUi.COPY_BTN_X, PoweredArmorUi.COPY_BTN_Y,
                PoweredArmorUi.COPY_BTN_W, PoweredArmorUi.COPY_BTN_H, canCopy, hoverCopy);
        if (copyWhy != null && !copyWhy.startsWith("put_")) {
            g.drawString(this.font, trim(Component.translatable(KEY + copyWhy), infoW), PoweredArmorUi.COPY_BTN_X,
                    PoweredArmorUi.COPY_BTN_Y + 22, NG, false);
        }

        // 作り直す
        g.drawString(this.font, trim(Component.translatable(KEY + "rebuild_head"), PoweredArmorUi.REBUILD_BTN_W),
                PoweredArmorUi.REBUILD_X, PoweredArmorUi.labelY(PoweredArmorUi.REBUILD_MATS_Y), TEXT, false);
        String rebuildWhy = null;
        RecipeHolder<PoweredArmorTableRecipe> recipe = null;
        List<ItemStack> inv = inventoryItems();
        if (bp == null) {
            rebuildWhy = print.isEmpty() ? "put_blueprint" : "blank_cannot";
            drawWrapped(g, Component.translatable(KEY + "rebuild_hint"), PoweredArmorUi.REBUILD_X,
                    PoweredArmorUi.REBUILD_MATS_Y + 2, PoweredArmorUi.REBUILD_BTN_W, TEXT_DIM, 6);
        } else if (this.minecraft != null && this.minecraft.level != null) {
            recipe = PoweredArmorWorkshop.rebuildRecipe(this.minecraft.level.getRecipeManager(), bp);
            if (recipe == null) {
                rebuildWhy = "no_recipe";
            } else {
                List<SizedIngredient> rows = merged(recipe.value().rebuildMaterials());
                for (int i = 0; i < Math.min(PoweredArmorUi.REBUILD_ROWS, rows.size()); i++) {
                    SizedIngredient need = rows.get(i);
                    int y = PoweredArmorUi.REBUILD_MATS_Y + i * PoweredArmorUi.REBUILD_STEP;
                    VanillaGui.slot(g, PoweredArmorUi.REBUILD_X, y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT);
                    ItemStack shown = shown(need);
                    g.renderItem(shown, PoweredArmorUi.REBUILD_X + 1, y + 1);
                    int have = PoweredArmorTableCrafting.count(need.ingredient(), inv);
                    String count = have + " / " + need.count();
                    int countX = PoweredArmorUi.AREA_X1 - this.font.width(count);
                    g.drawString(this.font, trim(shown.getHoverName(), countX - PoweredArmorUi.REBUILD_X - 26),
                            PoweredArmorUi.REBUILD_X + 22, y + 5, TEXT, false);
                    g.drawString(this.font, count, countX, y + 5, have >= need.count() ? OK : NG, false);
                }
                if (isLocked(recipe.id())) {
                    rebuildWhy = "locked";
                } else if (!PoweredArmorTableCrafting.hasAll(recipe.value().rebuildMaterials(), inv)) {
                    rebuildWhy = "missing";
                }
            }
        }
        boolean canRebuild = rebuildWhy == null;
        this.rebuildEnabled = canRebuild;
        boolean hoverRebuild = in(mouseX, mouseY, PoweredArmorUi.REBUILD_BTN_X, PoweredArmorUi.REBUILD_BTN_Y,
                PoweredArmorUi.REBUILD_BTN_W, PoweredArmorUi.REBUILD_BTN_H);
        VanillaGui.button(g, this.font, Component.translatable(KEY + "rebuild"), PoweredArmorUi.REBUILD_BTN_X,
                PoweredArmorUi.REBUILD_BTN_Y, PoweredArmorUi.REBUILD_BTN_W, PoweredArmorUi.REBUILD_BTN_H, canRebuild, hoverRebuild);
        if (rebuildWhy != null && !rebuildWhy.startsWith("put_") && !rebuildWhy.equals("blank_cannot")) {
            g.drawString(this.font, trim(Component.translatable(KEY + rebuildWhy), PoweredArmorUi.REBUILD_BTN_W),
                    PoweredArmorUi.REBUILD_BTN_X, PoweredArmorUi.REBUILD_BTN_Y + 22, NG, false);
        }
    }

    /** 同じ 1 種類のアイテムの行をまとめる(作成の素材と追加の素材が同じ合金のとき 1 行にする。表示だけ)。 */
    private static List<SizedIngredient> merged(List<SizedIngredient> list) {
        List<SizedIngredient> out = new ArrayList<>();
        outer:
        for (SizedIngredient s : list) {
            ItemStack[] a = s.ingredient().getItems();
            for (int i = 0; i < out.size(); i++) {
                ItemStack[] b = out.get(i).ingredient().getItems();
                if (a.length == 1 && b.length == 1 && ItemStack.isSameItemSameComponents(a[0], b[0])) {
                    out.set(i, new SizedIngredient(out.get(i).ingredient(), out.get(i).count() + s.count()));
                    continue outer;
                }
            }
            out.add(s);
        }
        return out;
    }

    // ---------- 装着のタブ(カートリッジ・装飾品・拡張パーツ。着手順 8・9)----------

    /** 左の欄の「付いている効果」の行数の上限(これを超えたら最後の行を「ほか N 件」にする)。 */
    private static final int FIT_EFFECT_LINES = 4;

    private void renderFit(GuiGraphics g, int mouseX, int mouseY) {
        GearTable.PoweredArmor cfg = DesignData.gear().poweredArmor();
        ItemStack armor = this.menu.workStack();
        boolean powered = armor.getItem() instanceof PoweredArmorItem;
        CartridgeContents attached = powered ? PoweredArmorItem.cartridge(armor) : null;
        ArmorAccessory accessory = ArmorAccessory.attached(armor, ArmorAccessory.Kind.ACCESSORY);
        ArmorAccessory extension = powered ? ArmorAccessory.attached(armor, ArmorAccessory.Kind.EXTENSION) : null;

        // 左: 作業枠の防具と、付いているものの効き目
        g.drawString(this.font, Component.translatable(KEY + "work"), PoweredArmorUi.WORK_FRAME_X,
                PoweredArmorUi.labelY(PoweredArmorUi.WORK_FRAME_Y), TEXT, false);
        int lx = PoweredArmorUi.WORK_INFO_X;
        int lw = PoweredArmorUi.WORK_INFO_W;
        if (armor.isEmpty()) {
            drawWrapped(g, Component.translatable(KEY + "put_armor_any"), lx, PoweredArmorUi.WORK_INFO_Y, lw, TEXT_DIM);
        } else {
            drawWrapped(g, armor.getHoverName(), lx, PoweredArmorUi.WORK_INFO_Y, lw, TEXT);
            g.drawString(this.font, trim(Component.translatable(KEY + "fit_effects"), lw), lx,
                    PoweredArmorUi.FIT_EFFECTS_LABEL_Y, TEXT, false);
            List<Component> lines = new ArrayList<>();
            if (attached != null) {
                lines.addAll(effectLines(attached, cfg));
                if (attached.isEmpty()) {
                    lines.add(Component.translatable(KEY + "cartridge_empty"));
                }
            }
            if (accessory != null) {
                lines.add(accessory.effect());
            }
            if (extension != null) {
                lines.add(extension.effect());
            }
            if (lines.isEmpty()) {
                g.drawString(this.font, Component.translatable(KEY + "fit_none"), lx, PoweredArmorUi.FIT_EFFECTS_Y, TEXT_DIM, false);
            }
            for (int i = 0; i < lines.size() && i < FIT_EFFECT_LINES; i++) {
                Component line = i == FIT_EFFECT_LINES - 1 && lines.size() > FIT_EFFECT_LINES
                        ? Component.translatable(KEY + "fit_more", lines.size() - i) : lines.get(i);
                g.drawString(this.font, trim(line, lw), lx, PoweredArmorUi.FIT_EFFECTS_Y + i * PoweredArmorUi.INFO_LINE, OK, false);
            }
        }

        // 真ん中: 付ける物の枠(カートリッジ・装飾品・拡張パーツのどれでも)と、その説明
        g.drawString(this.font, Component.translatable(KEY + "attach_thing"), PoweredArmorUi.CART_FRAME_X,
                PoweredArmorUi.labelY(PoweredArmorUi.CART_FRAME_Y), TEXT, false);
        ItemStack thing = this.menu.attachStack();
        ArmorAccessory acc = ArmorAccessory.of(thing);
        CartridgeContents contents = thing.getItem() instanceof CartridgeItem ? CartridgeItem.contents(thing) : null;
        int tx = PoweredArmorUi.CART_TOTAL_X;
        int tw = PoweredArmorUi.CART_FRAME_X + PoweredArmorUi.MIDDLE_W - tx;
        int dx = PoweredArmorUi.FACTOR_ROWS_X;
        int dw = PoweredArmorUi.MIDDLE_W;
        int dy = PoweredArmorUi.ATTACH_DESC_Y;
        if (contents != null) {
            g.drawString(this.font, trim(thing.getHoverName(), tw), tx, PoweredArmorUi.ATTACH_NAME_Y, TEXT, false);
            g.drawString(this.font, trim(Component.translatable(KEY + "cartridge_placement"), tw), tx,
                    PoweredArmorUi.ATTACH_PART_Y, TEXT_DIM, false);
            g.drawString(this.font, trim(Component.translatable(KEY + "cartridge_total", contents.total(),
                    cfg.cartridgeCapacity(), PoweredArmorItem.percent(cfg.factorPerItem())), dw), dx, dy, TEXT, false);
            renderFactorRows(g, contents, cfg);
        } else if (acc != null) {
            g.drawString(this.font, trim(thing.getHoverName(), tw), tx, PoweredArmorUi.ATTACH_NAME_Y, TEXT, false);
            g.drawString(this.font, trim(acc.forPart(), tw), tx, PoweredArmorUi.ATTACH_PART_Y, TEXT_DIM, false);
            g.drawString(this.font, Component.translatable(KEY + "effect_label"), dx, dy, TEXT, false);
            g.drawString(this.font, trim(acc.effect(), dw), dx, dy + PoweredArmorUi.INFO_LINE, OK, false);
            drawWrapped(g, Component.translatable(KEY + (acc.kind() == ArmorAccessory.Kind.ACCESSORY
                    ? "guide.accessory" : "guide.extension")), dx, dy + 24, dw, TEXT_DIM);
            ArmorAccessory old = ArmorAccessory.attached(armor, acc.kind());
            if (old != null && acc.canHold(armor) == ArmorAccessory.Fit.OK) {
                drawWrapped(g, Component.translatable(KEY + "will_swap", old.item().getDescription()), dx, dy + 46, dw, TEXT_DIM);
            }
        } else {
            // 空: 何を置けるかの案内(名前で付け先の種類が分かる —— ユーザー指定の肝)
            drawWrapped(g, Component.translatable(KEY + "put_attach"), tx, PoweredArmorUi.ATTACH_NAME_Y + 5, tw, TEXT_DIM);
            String[] guide = {"guide.cartridge", "guide.accessory", "guide.extension"};
            int y = dy;
            for (String key : guide) {
                List<FormattedCharSequence> wrapped = this.font.split(Component.translatable(KEY + key), dw);
                for (int i = 0; i < Math.min(2, wrapped.size()) && y <= PoweredArmorUi.FACTOR_FRAME_Y - 10; i++) {
                    g.drawString(this.font, wrapped.get(i), dx, y, TEXT_DIM, false);
                    y += PoweredArmorUi.INFO_LINE;
                }
                y += 2;
            }
        }

        // 真ん中の下: 因子の枠と「入れる」(カートリッジが置いてあるときだけ押せる。案内は「入れる」のツールチップ)
        ItemStack factorStack = this.menu.factorStack();
        boolean full = contents != null && contents.total() >= cfg.cartridgeCapacity();
        boolean canInsert = contents != null && !factorStack.isEmpty() && !full;
        this.insertEnabled = canInsert;
        boolean hoverInsert = in(mouseX, mouseY, PoweredArmorUi.INSERT_BTN_X, PoweredArmorUi.INSERT_BTN_Y,
                PoweredArmorUi.INSERT_BTN_W, PoweredArmorUi.INSERT_BTN_H);
        VanillaGui.button(g, this.font, Component.translatable(KEY + "insert"), PoweredArmorUi.INSERT_BTN_X,
                PoweredArmorUi.INSERT_BTN_Y, PoweredArmorUi.INSERT_BTN_W, PoweredArmorUi.INSERT_BTN_H, canInsert, hoverInsert);
        if (full && !factorStack.isEmpty()) {
            g.drawString(this.font, Component.translatable(KEY + "full"), PoweredArmorUi.INSERT_BTN_X + PoweredArmorUi.INSERT_BTN_W + 4,
                    PoweredArmorUi.INSERT_BTN_Y + 5, NG, false);
        }

        // 右: 「付ける」(付けられないときはボタンに理由を出す)
        int rx = PoweredArmorUi.FIT_RIGHT_X;
        g.drawString(this.font, Component.translatable(KEY + "to_armor"), rx, PoweredArmorUi.labelY(PoweredArmorUi.ATTACH_BTN_Y),
                TEXT, false);
        PoweredArmorWorkshop.Result fit = PoweredArmorWorkshop.canAttach(armor, thing);
        boolean canAttach = fit == PoweredArmorWorkshop.Result.OK;
        this.attachEnabled = canAttach;
        boolean occupied = contents != null ? attached != null
                : acc != null && ArmorAccessory.attached(armor, acc.kind()) != null;
        Component attachLabel = canAttach ? Component.translatable(KEY + (occupied ? "swap" : "attach"))
                : thing.isEmpty() ? Component.translatable(KEY + "attach") : attachReason(fit, acc);
        boolean hoverAttach = in(mouseX, mouseY, rx, PoweredArmorUi.ATTACH_BTN_Y, PoweredArmorUi.FIT_BTN_W,
                PoweredArmorUi.FIT_BTN_H);
        VanillaGui.button(g, this.font, attachLabel, rx, PoweredArmorUi.ATTACH_BTN_Y, PoweredArmorUi.FIT_BTN_W,
                PoweredArmorUi.FIT_BTN_H, canAttach, hoverAttach);

        // 右: 装備の差し口 3 つと「外す」(外した物は付ける物の枠へ戻るので、枠が空のときだけ押せる)
        g.drawString(this.font, Component.translatable(KEY + "sockets"), rx, PoweredArmorUi.labelY(PoweredArmorUi.SOCKET_Y),
                TEXT, false);
        Component none = Component.translatable(KEY + "socket_none");
        Component cartValue = attached == null ? none : attached.isEmpty() ? Component.translatable(KEY + "cartridge_empty")
                : Component.translatable(KEY + "socket_factors", attached.total());
        renderSocket(g, mouseX, mouseY, 0, "socket.cartridge", !armor.isEmpty(), powered, cartValue, attached != null,
                thing.isEmpty());
        renderSocket(g, mouseX, mouseY, 1, "socket.accessory", !armor.isEmpty(), !armor.isEmpty(),
                accessory != null ? Component.translatable(accessory.shortKey()) : none, accessory != null, thing.isEmpty());
        renderSocket(g, mouseX, mouseY, 2, "socket.extension", !armor.isEmpty(), powered,
                extension != null ? Component.translatable(extension.shortKey()) : none, extension != null, thing.isEmpty());
    }

    /** カートリッジの中身の行(種類ごとの因子の数と軽減)。 */
    private void renderFactorRows(GuiGraphics g, CartridgeContents contents, GearTable.PoweredArmor cfg) {
        CartridgeFactor[] factors = CartridgeFactor.values();
        for (int i = 0; i < factors.length; i++) {
            CartridgeFactor f = factors[i];
            int y = PoweredArmorUi.FACTOR_ROWS_Y + i * PoweredArmorUi.FACTOR_ROW_STEP;
            g.pose().pushPose();
            g.pose().translate(PoweredArmorUi.FACTOR_ROWS_X, y - 1, 0.0F);
            g.pose().scale(0.625F, 0.625F, 1.0F);
            g.renderItem(new ItemStack(f.item()), 0, 0);
            g.pose().popPose();
            int n = contents.count(f);
            int color = n > 0 ? TEXT : TEXT_DIM;
            String count = String.valueOf(n);
            int countX = PoweredArmorUi.FACTOR_COUNT_RIGHT - this.font.width(count);
            int textX = PoweredArmorUi.FACTOR_ROWS_X + 12;
            g.drawString(this.font, trim(Component.translatable(KEY + "factor_row", Component.translatable(f.nameKey()),
                    Component.translatable(f.targetKey())), countX - textX - 4), textX, y, color, false);
            g.drawString(this.font, count, countX, y, n > 0 ? OK : TEXT_DIM, false);
            if (n > 0) {
                g.drawString(this.font, "-" + PoweredArmorItem.percent(n * cfg.factorPerItem()) + "%",
                        PoweredArmorUi.FACTOR_COUNT_RIGHT + 4, y, OK, false);
            }
        }
    }

    /**
     * 差し口 1 つ。{@code present} = 防具が置いてある、{@code usable} = その防具にこの差し口がある
     * (カートリッジ・拡張パーツはパワードアーマーだけ)、{@code filled} = 何か付いている、{@code slotFree} = 付ける物の枠が空。
     */
    private void renderSocket(GuiGraphics g, int mouseX, int mouseY, int index, String nameKey, boolean present,
                              boolean usable, Component value, boolean filled, boolean slotFree) {
        int x = PoweredArmorUi.FIT_RIGHT_X;
        int y = PoweredArmorUi.SOCKET_Y + index * PoweredArmorUi.SOCKET_STEP;
        VanillaGui.slot(g, x, y, PoweredArmorUi.SOCKET_W, PoweredArmorUi.SOCKET_H);
        int textW = PoweredArmorUi.SOCKET_BTN_X - x - 5;
        g.drawString(this.font, trim(Component.translatable(KEY + nameKey), textW), x + 3, y + 3,
                usable ? ON_DARK : ON_DARK_DIM, true);
        boolean canDetach = usable && filled && slotFree;
        this.detachEnabled[index] = canDetach;
        if (!present) {
            return;
        }
        if (!usable) {
            g.drawString(this.font, trim(Component.translatable(KEY + "powered_only"), PoweredArmorUi.SOCKET_W - 6), x + 3,
                    y + 13, ON_DARK_DIM, true);
            return;
        }
        g.drawString(this.font, trim(value, textW), x + 3, y + 13, filled ? ON_DARK_GOOD : ON_DARK_DIM, true);
        int bx = PoweredArmorUi.SOCKET_BTN_X;
        int by = y + PoweredArmorUi.SOCKET_BTN_DY;
        boolean hover = in(mouseX, mouseY, bx, by, PoweredArmorUi.SOCKET_BTN_W, PoweredArmorUi.SOCKET_BTN_H);
        VanillaGui.button(g, this.font, Component.translatable(KEY + "detach"), bx, by, PoweredArmorUi.SOCKET_BTN_W,
                PoweredArmorUi.SOCKET_BTN_H, canDetach, hover);
    }

    /** 「付ける」を押せない理由(ボタンに出す。ボタンの幅に収まる短さ)。 */
    private Component attachReason(PoweredArmorWorkshop.Result fit, ArmorAccessory acc) {
        return switch (fit) {
            case NO_ARMOR -> Component.translatable(KEY + "why.no_armor");
            case NOT_POWERED -> Component.translatable(KEY + "powered_only");
            case WRONG_PART -> acc != null ? acc.forPart() : Component.translatable(KEY + "attach");
            case NOT_ACCEPTED -> Component.translatable(KEY + "why.not_accepted");
            default -> Component.translatable(KEY + "attach");
        };
    }

    /** カートリッジの中身の効き目の行(「近接攻撃 -8%」)。 */
    private static List<Component> effectLines(CartridgeContents contents, GearTable.PoweredArmor cfg) {
        List<Component> lines = new ArrayList<>();
        for (CartridgeFactor f : CartridgeFactor.values()) {
            int n = contents.count(f);
            if (n > 0) {
                lines.add(Component.translatable(KEY + "effect_line", Component.translatable(f.targetKey()),
                        PoweredArmorItem.percent(n * cfg.factorPerItem())));
            }
        }
        return lines;
    }

    private boolean clickFit(double cx, double cy) {
        if (in(cx, cy, PoweredArmorUi.INSERT_BTN_X, PoweredArmorUi.INSERT_BTN_Y, PoweredArmorUi.INSERT_BTN_W,
                PoweredArmorUi.INSERT_BTN_H)) {
            return pressAction(this.insertEnabled, PoweredArmorTableMenu.BUTTON_INSERT);
        }
        if (in(cx, cy, PoweredArmorUi.FIT_RIGHT_X, PoweredArmorUi.ATTACH_BTN_Y, PoweredArmorUi.FIT_BTN_W, PoweredArmorUi.FIT_BTN_H)) {
            return pressAction(this.attachEnabled, PoweredArmorTableMenu.BUTTON_ATTACH);
        }
        int[] buttons = {PoweredArmorTableMenu.BUTTON_DETACH, PoweredArmorTableMenu.BUTTON_DETACH_ACCESSORY,
                PoweredArmorTableMenu.BUTTON_DETACH_EXTENSION};
        for (int i = 0; i < buttons.length; i++) {
            int y = PoweredArmorUi.SOCKET_Y + i * PoweredArmorUi.SOCKET_STEP + PoweredArmorUi.SOCKET_BTN_DY;
            if (in(cx, cy, PoweredArmorUi.SOCKET_BTN_X, y, PoweredArmorUi.SOCKET_BTN_W, PoweredArmorUi.SOCKET_BTN_H)) {
                return pressAction(this.detachEnabled[i], buttons[i]);
            }
        }
        return false;
    }

    /** 装着のタブで差し口の上にマウスがあれば、付いている物のアイテム(説明を出すため)。 */
    private ItemStack hoveredSocket(int cx, int cy) {
        ItemStack armor = this.menu.workStack();
        for (int i = 0; i < 3; i++) {
            int y = PoweredArmorUi.SOCKET_Y + i * PoweredArmorUi.SOCKET_STEP;
            if (!in(cx, cy, PoweredArmorUi.FIT_RIGHT_X, y, PoweredArmorUi.SOCKET_BTN_X - PoweredArmorUi.FIT_RIGHT_X,
                    PoweredArmorUi.SOCKET_H)) {
                continue;
            }
            if (i == 0) {
                CartridgeContents c = armor.getItem() instanceof PoweredArmorItem ? PoweredArmorItem.cartridge(armor) : null;
                return c != null ? PoweredArmorWorkshop.cartridgeItem(c) : ItemStack.EMPTY;
            }
            ArmorAccessory a = ArmorAccessory.attached(armor, i == 1 ? ArmorAccessory.Kind.ACCESSORY : ArmorAccessory.Kind.EXTENSION);
            return a != null ? new ItemStack(a.item()) : ItemStack.EMPTY;
        }
        return ItemStack.EMPTY;
    }

    private static int contentCenterX() {
        return (PoweredArmorUi.AREA_X0 + PoweredArmorUi.AREA_X1) / 2;
    }

    private static int contentCenterY() {
        return (PoweredArmorUi.AREA_Y0 + PoweredArmorUi.AREA_Y1) / 2;
    }

    /** 1px の四角い縁(選んでいる段)。 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String trim(Component text, int width) {
        return VanillaGui.trim(this.font, text.getString(), width);
    }

    /** キャンバス座標の下にある、一覧・詳しい表示・支払いの行のアイテム(ツールチップ用)。 */
    private ItemStack hoveredStack(int cx, int cy) {
        if (this.menu.tab() == PoweredArmorTableMenu.TAB_TREE) {
            return hoveredTreeCost(cx, cy);
        }
        if (this.menu.tab() == PoweredArmorTableMenu.TAB_BLUEPRINT) {
            return hoveredRebuildRow(cx, cy);
        }
        if (this.menu.tab() == PoweredArmorTableMenu.TAB_FIT) {
            return hoveredSocket(cx, cy);
        }
        if (this.menu.tab() != PoweredArmorTableMenu.TAB_CRAFT) {
            return ItemStack.EMPTY;
        }
        List<RecipeHolder<PoweredArmorTableRecipe>> list = recipes();
        if (list.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (int row = 0; row < PoweredArmorUi.LIST_ROWS && this.scroll + row < list.size(); row++) {
            int y = PoweredArmorUi.LIST_Y + row * PoweredArmorUi.LIST_STEP;
            if (in(cx, cy, PoweredArmorUi.LIST_X + 2, y + 2, 16, 16)) {
                return list.get(this.scroll + row).value().result();
            }
        }
        RecipeHolder<PoweredArmorTableRecipe> sel = selected(list);
        if (in(cx, cy, PoweredArmorUi.DETAIL_X, PoweredArmorUi.DETAIL_ICON_Y - 1, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT)) {
            return sel.value().result();
        }
        List<SizedIngredient> mats = sel.value().ingredients();
        for (int i = 0; i < Math.min(PoweredArmorUi.MATS_ROWS, mats.size()); i++) {
            int y = PoweredArmorUi.MATS_Y + i * PoweredArmorUi.MATS_STEP;
            if (in(cx, cy, PoweredArmorUi.DETAIL_X, y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT)) {
                return shown(mats.get(i));
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack hoveredTreeCost(int cx, int cy) {
        ItemStack armor = this.menu.workStack();
        if (!(armor.getItem() instanceof PoweredArmorItem)) {
            return ItemStack.EMPTY;
        }
        GearTable.LevelCost cost = PoweredArmorWorkshop.nextCost(armor, this.selectedBranch);
        if (cost == null) {
            return ItemStack.EMPTY;
        }
        List<SizedIngredient> mats = PoweredArmorWorkshop.materials(cost, this.selectedBranch);
        for (int i = 0; i < mats.size(); i++) {
            int x = this.costItemsX + i * PoweredArmorUi.COST_ITEM_STEP;
            if (in(cx, cy, x, PoweredArmorUi.COST_Y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT)) {
                return shown(mats.get(i));
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack hoveredRebuildRow(int cx, int cy) {
        PoweredArmorBlueprint bp = PoweredArmorBlueprintItem.data(this.menu.blueprintStack());
        if (bp == null || this.minecraft == null || this.minecraft.level == null) {
            return ItemStack.EMPTY;
        }
        RecipeHolder<PoweredArmorTableRecipe> recipe =
                PoweredArmorWorkshop.rebuildRecipe(this.minecraft.level.getRecipeManager(), bp);
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        List<SizedIngredient> rows = merged(recipe.value().rebuildMaterials());
        for (int i = 0; i < Math.min(PoweredArmorUi.REBUILD_ROWS, rows.size()); i++) {
            int y = PoweredArmorUi.REBUILD_MATS_Y + i * PoweredArmorUi.REBUILD_STEP;
            if (in(cx, cy, PoweredArmorUi.REBUILD_X, y, PoweredArmorUi.SLOT, PoweredArmorUi.SLOT)) {
                return shown(rows.get(i));
            }
        }
        return ItemStack.EMPTY;
    }

    // ---------- 入力(すべてキャンバス座標に直してから)----------

    /** 作業のボタン(改造・写す・作り直す)。押せるときだけサーバーへ番号を送る。 */
    private boolean pressAction(boolean enabled, int buttonId) {
        if (enabled && this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
            playClick();
        }
        return true;
    }

    private boolean clickTree(double cx, double cy) {
        int max = DesignData.gear().poweredArmor().maxLevel();
        for (int b = 0; b < PoweredArmorTree.BRANCHES; b++) {
            int x = PoweredArmorUi.TREE_X + b * PoweredArmorUi.TREE_COL_STEP;
            for (int lv = 1; lv <= max; lv++) {
                int y = PoweredArmorUi.NODE_Y + (lv - 1) * PoweredArmorUi.NODE_STEP;
                if (in(cx, cy, x, y, PoweredArmorUi.NODE_W, PoweredArmorUi.NODE_H)) {
                    if (this.selectedBranch != b) {
                        this.selectedBranch = b;
                        playClick();
                    }
                    return true;
                }
            }
        }
        if (in(cx, cy, PoweredArmorUi.UPGRADE_BTN_X, PoweredArmorUi.UPGRADE_BTN_Y,
                PoweredArmorUi.UPGRADE_BTN_W, PoweredArmorUi.UPGRADE_BTN_H)) {
            return pressAction(this.upgradeEnabled, PoweredArmorTableMenu.BUTTON_UPGRADE + this.selectedBranch);
        }
        return false;
    }

    private boolean clickBlueprint(double cx, double cy) {
        if (in(cx, cy, PoweredArmorUi.COPY_BTN_X, PoweredArmorUi.COPY_BTN_Y,
                PoweredArmorUi.COPY_BTN_W, PoweredArmorUi.COPY_BTN_H)) {
            return pressAction(this.copyEnabled, PoweredArmorTableMenu.BUTTON_COPY);
        }
        if (in(cx, cy, PoweredArmorUi.REBUILD_BTN_X, PoweredArmorUi.REBUILD_BTN_Y,
                PoweredArmorUi.REBUILD_BTN_W, PoweredArmorUi.REBUILD_BTN_H)) {
            return pressAction(this.rebuildEnabled, PoweredArmorTableMenu.BUTTON_REBUILD);
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);
        if (button == 0) {
            for (int i = 0; i < PoweredArmorUi.TAB_COUNT; i++) {
                if (in(cx, cy, tabX(i), 0, PoweredArmorUi.TAB_W, PoweredArmorUi.TAB_STRIP_H)) {
                    if (i != this.menu.tab() && this.minecraft != null && this.minecraft.gameMode != null) {
                        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, i);
                        playClick();
                    }
                    return true;
                }
            }
            int tab = this.menu.tab();
            if (tab == PoweredArmorTableMenu.TAB_CRAFT && clickCraft(cx, cy)) {
                return true;
            }
            if (tab == PoweredArmorTableMenu.TAB_TREE && clickTree(cx, cy)) {
                return true;
            }
            if (tab == PoweredArmorTableMenu.TAB_BLUEPRINT && clickBlueprint(cx, cy)) {
                return true;
            }
            if (tab == PoweredArmorTableMenu.TAB_FIT && clickFit(cx, cy)) {
                return true;
            }
        }
        return super.mouseClicked(cx, cy, button);
    }

    private boolean clickCraft(double cx, double cy) {
        List<RecipeHolder<PoweredArmorTableRecipe>> list = recipes();
        if (list.isEmpty()) {
            return false;
        }
        for (int row = 0; row < PoweredArmorUi.LIST_ROWS && this.scroll + row < list.size(); row++) {
            int y = PoweredArmorUi.LIST_Y + row * PoweredArmorUi.LIST_STEP;
            if (in(cx, cy, PoweredArmorUi.LIST_X, y, PoweredArmorUi.LIST_W, PoweredArmorUi.LIST_ROW_H)) {
                this.selectedId = list.get(this.scroll + row).id();
                playClick();
                return true;
            }
        }
        if (in(cx, cy, PoweredArmorUi.BTN_X, PoweredArmorUi.BTN_Y, PoweredArmorUi.BTN_W, PoweredArmorUi.BTN_H)) {
            RecipeHolder<PoweredArmorTableRecipe> sel = selected(list);
            if (!isLocked(sel.id()) && PoweredArmorTableCrafting.hasAll(sel.value(), inventoryItems())) {
                PacketDistributor.sendToServer(new PoweredArmorCraftPayload(sel.id()));
                playClick();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return super.mouseDragged(toCanvasX(mx), toCanvasY(my), button, dx / uiScale, dy / uiScale);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return super.mouseReleased(toCanvasX(mx), toCanvasY(my), button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);
        if (this.menu.tab() == PoweredArmorTableMenu.TAB_CRAFT && in(cx, cy, PoweredArmorUi.LIST_X, PoweredArmorUi.LIST_Y,
                PoweredArmorUi.LIST_W, PoweredArmorUi.LIST_ROWS * PoweredArmorUi.LIST_STEP)) {
            this.scroll += dy > 0 ? -1 : dy < 0 ? 1 : 0;
            return true;
        }
        return super.mouseScrolled(cx, cy, dx, dy);
    }

    private void playClick() {
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
