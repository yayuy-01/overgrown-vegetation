package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.menu.ResearchUi;
import com.yayuy.overgrownvegetation.menu.ResearchTableMenu;
import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;
import com.yayuy.overgrownvegetation.network.ResearchUnlockPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 研究台の UI(2026-10-14 から仮でバニラの窓。ユーザー指定 —— 世界観に合わせて作り直すまでのつなぎ)。
 *
 * <p>窓はタブ 2 つ: <b>研究</b>(Tier の切り替え・進捗画面のような石の面のツリー・右の詳しい表示)と
 * <b>作業</b>(5×5 拡大作業台・解析・インベントリ)。研究のタブではメニューのスロットを全部隠す
 * ({@link ResearchTableMenu#setWorkSlotsShown}。クライアントだけの切り替え)。最後に開いたタブは覚えておく(起動している間だけ)。
 * 配置は {@link ResearchUi}、窓・ボタンの描き方は {@link VanillaGui}。前の全画面版は {@code tools/ui-backup-2026-10-14/}。
 *
 * <h2>座標</h2>
 * {@link ResearchUi} のキャンバス(320×240、タブの帯を含む)で描き、入力もキャンバス座標に直して扱う。
 * ふつうは等倍で画面の真ん中に置くだけ(バニラの GUI の大きさの自動は 320×240 以上を残す)。画面がそれより狭いときだけ一様に縮める。
 * {@code leftPos}/{@code topPos} は<b>常に 0</b> —— バニラのスロットの当たりは {@code isHovering} がこの 2 つを引くだけなので、
 * マウス座標をキャンバス座標に直してから {@code super} に渡せば、そのまま正しく当たる({@link #mouseClicked} ほか)。
 *
 * <p>ボタン類はバニラの {@code Button} ウィジェットを使わず、見た目だけバニラのスプライトで描いて自前で当てる
 * (ウィジェットは画面座標で当たるので、縮めたときに噛み合わない)。ツールチップだけはスケールの外(画面座標)で描く。
 *
 * <h2>ツリー</h2>
 * ノードは進捗の枠(26×26)とアイコンだけ。名前・コストはツールチップと右の詳しい表示に出す。位置は research.json の格子
 * ({@code x}/{@code y})× {@link ResearchUi#CELL_X}/{@link ResearchUi#CELL_Y}(research.json の layout のノードの大きさ・間隔と
 * ノードごとの widthCells/heightCells は、この仮の見た目では使わない)。ドラッグで動かし、ホイールで拡大縮小。
 */
public class ResearchTableScreen extends AbstractContainerScreen<ResearchTableMenu> {

    private static final int TEXT = VanillaGui.TEXT;
    private static final int TEXT_DIM = VanillaGui.TEXT_DIM;

    private static final int PREVIEW_BG = 0xF0100010;
    private static final int PREVIEW_BORDER = 0xFF3A3A3A;
    private static final int PREVIEW_SLOT_BG = 0x66000000;
    /** レシピプレビューの 1 マスのピッチ(枠 16px + 仕切り 2px)。 */
    private static final int PREVIEW_SLOT = 18;
    /** 空きマスを表す「候補なし」。 */
    private static final ItemStack[] NO_CHOICES = new ItemStack[0];

    private static final int C_LINE = 0xFFD0D0D0;
    private static final int C_LINE_ON = 0xFF7FE07F;
    /** 「親のどれか1つでよい」接続(破線)。 */
    private static final int C_LINE_ANY = 0xFFE0B45A;
    /** 二者択一グループの仕切り(破線)。 */
    private static final int C_LINE_EXCL = 0xFFD98CD9;
    /** 開放できるノードの縁。 */
    private static final int C_AVAILABLE = 0xFF55FF55;
    /** ツリーの上に重ねる物(暗くする幕・選択の縁・レシピの見本)をアイコン(z 150 前後)より手前に出す高さ。 */
    private static final float OVERLAY_Z = 200.0F;

    /** 最後に開いたタブ(画面を開き直しても同じタブから。起動している間だけ)。 */
    private static int lastTab = ResearchUi.TAB_TREE;

    // ---- キャンバス → 画面 の変換 ----
    private float uiScale = 1.0F;
    private float originX;
    private float originY;

    private int tab = lastTab;
    private int activeTier = 1;
    private double panX;
    private double panY;
    private float zoom = 1.0F;
    private boolean dragging;
    private boolean needsCenter = true;
    private double lastX;
    private double lastY;

    /** 右の詳しい表示に出しているノード(クリックで選択)。 */
    private String selectedNodeId;
    /** ホバー中ノード。レシピプレビューの対象。{@code unlocks} が複数ならホイールで切替。 */
    private String hoveredNodeId;
    private int recipeIndex;

    public ResearchTableScreen(ResearchTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = ResearchUi.CANVAS_W;
        this.imageHeight = ResearchUi.CANVAS_H;
    }

    @Override
    protected void init() {
        this.imageWidth = ResearchUi.CANVAS_W;
        this.imageHeight = ResearchUi.CANVAS_H;
        super.init();
        // ふつうは等倍で真ん中。画面が 320×240 より狭いときだけ縮める。
        this.uiScale = Math.min(1.0F, Math.min(this.width / (float) ResearchUi.CANVAS_W,
                this.height / (float) ResearchUi.CANVAS_H));
        this.originX = Math.round((this.width - ResearchUi.CANVAS_W * uiScale) / 2.0F);
        this.originY = Math.round((this.height - ResearchUi.CANVAS_H * uiScale) / 2.0F);
        // キャンバス座標 = スロット座標にする(バニラの当たり判定がそのまま使える)。
        this.leftPos = 0;
        this.topPos = 0;
        this.menu.setWorkSlotsShown(this.tab == ResearchUi.TAB_WORK);
    }

    // ---------- 座標変換 ----------

    private double toCanvasX(double screenX) {
        return (screenX - originX) / uiScale;
    }

    private double toCanvasY(double screenY) {
        return (screenY - originY) / uiScale;
    }

    private int toScreenX(double canvasX) {
        return Math.round(originX + (float) canvasX * uiScale);
    }

    private int toScreenY(double canvasY) {
        return Math.round(originY + (float) canvasY * uiScale);
    }

    private static boolean in(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private void setTab(int t) {
        if (t == this.tab) {
            return;
        }
        this.tab = t;
        lastTab = t;
        this.dragging = false;
        this.hoveredNodeId = null;
        this.menu.setWorkSlotsShown(t == ResearchUi.TAB_WORK);
        playClick();
    }

    // ---------- 描画 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (needsCenter && !ClientResearch.nodes().isEmpty()) {
            centerOnTier();
            needsCenter = false;
        }
        // 窓の外はバニラの入れ物の画面と同じく暗くする。
        this.renderTransparentBackground(g);

        int cmx = (int) Math.floor(toCanvasX(mouseX));
        int cmy = (int) Math.floor(toCanvasY(mouseY));

        g.pose().pushPose();
        g.pose().translate(originX, originY, 0.0F);
        g.pose().scale(uiScale, uiScale, 1.0F);
        // super が renderBg → スロット → 掴んでいるアイテム を、すべてキャンバス座標で描く。
        super.render(g, cmx, cmy, partialTick);
        String before = hoveredNodeId;
        hoveredNodeId = null;
        if (tab == ResearchUi.TAB_TREE && !dragging && inTree(cmx, cmy)) {
            ResearchSyncPayload.NodeView hovered = nodeAt(cmx, cmy);
            if (hovered != null) {
                if (!hovered.id().equals(before)) {
                    recipeIndex = 0;
                }
                hoveredNodeId = hovered.id();
                renderRecipePreview(g, hovered);
            }
        }
        g.pose().popPose();

        // ツールチップだけは等倍・画面座標で描く(端でのはみ出し処理がバニラ側の画面幅基準のため)。
        // AbstractContainerScreen#render は renderTooltip を呼ばないので、ここで呼ぶのが本来の流儀。
        this.renderTooltip(g, mouseX, mouseY);
        if (hoveredNodeId != null) {
            ResearchSyncPayload.NodeView n = nodeById(hoveredNodeId);
            if (n != null) {
                g.renderComponentTooltip(font, tooltip(n), mouseX, mouseY);
            }
        } else if (tab == ResearchUi.TAB_TREE) {
            for (int t = 1; t <= ResearchUi.RAIL_TIERS; t++) {
                if (in(cmx, cmy, ResearchUi.railX(t - 1), ResearchUi.RAIL_Y, ResearchUi.RAIL_W, ResearchUi.RAIL_H)) {
                    g.renderComponentTooltip(font, tierTooltip(t), mouseX, mouseY);
                }
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
        // 選んでいないタブ → 窓 → 選んだタブ(窓とつながる)
        for (int i = 0; i < ResearchUi.TAB_COUNT; i++) {
            if (i != tab) {
                VanillaGui.tab(g, font, tabLabel(i), ResearchUi.TAB_X + i * ResearchUi.TAB_STEP, ResearchUi.WIN_Y,
                        ResearchUi.TAB_W, ResearchUi.TAB_H, false);
            }
        }
        VanillaGui.panel(g, ResearchUi.WIN_X, ResearchUi.WIN_Y, ResearchUi.WIN_W, ResearchUi.WIN_H);
        VanillaGui.tab(g, font, tabLabel(tab), ResearchUi.TAB_X + tab * ResearchUi.TAB_STEP, ResearchUi.WIN_Y,
                ResearchUi.TAB_W, ResearchUi.TAB_H, true);

        g.drawString(font, this.title, ResearchUi.TITLE_X, ResearchUi.TITLE_Y, TEXT, false);
        Component pts = Component.translatable("gui.overgrown_vegetation.research.points", ClientResearch.points());
        g.drawString(font, pts, ResearchUi.POINTS_RIGHT - font.width(pts), ResearchUi.TITLE_Y, VanillaGui.GOOD, false);

        if (tab == ResearchUi.TAB_TREE) {
            renderTierRail(g, mouseX, mouseY);
            renderTreeArea(g);
            renderPanel(g, mouseX, mouseY);
        } else {
            renderWork(g, mouseX, mouseY);
        }
    }

    private static Component tabLabel(int i) {
        return Component.translatable(i == ResearchUi.TAB_TREE
                ? "gui.overgrown_vegetation.research.tab.tree" : "gui.overgrown_vegetation.research.tab.work");
    }

    private void renderTierRail(GuiGraphics g, int mouseX, int mouseY) {
        for (int t = 1; t <= ResearchUi.RAIL_TIERS; t++) {
            int x = ResearchUi.railX(t - 1);
            boolean active = t == activeTier;
            boolean hover = in(mouseX, mouseY, x, ResearchUi.RAIL_Y, ResearchUi.RAIL_W, ResearchUi.RAIL_H);
            VanillaGui.button(g, x, ResearchUi.RAIL_Y, ResearchUi.RAIL_W, ResearchUi.RAIL_H, true, active || hover);
            String label = "Tier " + roman(t);
            int color = active ? 0xFFFFFF55 : countNodes(t) == 0 ? VanillaGui.BUTTON_TEXT_OFF : VanillaGui.BUTTON_TEXT;
            g.drawString(font, label, x + (ResearchUi.RAIL_W - font.width(label)) / 2,
                    ResearchUi.RAIL_Y + (ResearchUi.RAIL_H - 8) / 2 + 1, color, true);
        }
    }

    private List<Component> tierTooltip(int t) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Tier " + roman(t)));
        String era = Component.translatableWithFallback("gui.overgrown_vegetation.research.tier." + t, "").getString();
        if (!era.isEmpty()) {
            lines.add(Component.literal(era).withStyle(ChatFormatting.GRAY));
        }
        if (countNodes(t) == 0) {
            lines.add(Component.translatable("gui.overgrown_vegetation.research.empty_tier").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** ツリーの面: へこんだ枠・石の背景(ドラッグに合わせて動く)・ツリー。scissor は画面座標で指定する。 */
    private void renderTreeArea(GuiGraphics g) {
        VanillaGui.slot(g, ResearchUi.TREE_FRAME_X, ResearchUi.TREE_FRAME_Y, ResearchUi.TREE_FRAME_W, ResearchUi.TREE_FRAME_H);
        g.enableScissor(toScreenX(ResearchUi.TREE_X0), toScreenY(ResearchUi.TREE_Y0),
                toScreenX(ResearchUi.TREE_X1), toScreenY(ResearchUi.TREE_Y1));
        VanillaGui.stone(g, ResearchUi.TREE_X0, ResearchUi.TREE_Y0, ResearchUi.TREE_X1, ResearchUi.TREE_Y1,
                -(int) Math.round(panX), -(int) Math.round(panY));
        renderTree(g);
        g.disableScissor();
    }

    // ---------- 右の詳しい表示 ----------

    private void renderPanel(GuiGraphics g, int mouseX, int mouseY) {
        ResearchSyncPayload.NodeView n = selectedNodeId != null ? nodeById(selectedNodeId) : null;
        if (n == null) {
            int y = ResearchUi.PANEL_Y + 2;
            for (FormattedCharSequence line : font.split(
                    Component.translatable("gui.overgrown_vegetation.research.select_hint"), ResearchUi.PANEL_TEXT_W)) {
                g.drawString(font, line, ResearchUi.PANEL_TEXT_X, y, TEXT_DIM, false);
                y += 10;
            }
            return;
        }

        VanillaGui.slot(g, ResearchUi.PANEL_ICON_X, ResearchUi.PANEL_ICON_Y, 18, 18);
        drawIcon(g, n, ResearchUi.PANEL_ICON_X + 1, ResearchUi.PANEL_ICON_Y + 1);
        List<FormattedCharSequence> name = font.split(nodeName(n), ResearchUi.PANEL_TITLE_W);
        if (name.size() == 1) {
            g.drawString(font, name.get(0), ResearchUi.PANEL_TITLE_X, ResearchUi.PANEL_TITLE_Y + 5, TEXT, false);
        } else {
            for (int i = 0; i < Math.min(2, name.size()); i++) {
                g.drawString(font, name.get(i), ResearchUi.PANEL_TITLE_X, ResearchUi.PANEL_TITLE_Y + i * 10, TEXT, false);
            }
        }

        int y = ResearchUi.PANEL_DESC_Y;
        for (FormattedCharSequence line : font.split(nodeDesc(n), ResearchUi.PANEL_TEXT_W)) {
            if (y > ResearchUi.PANEL_LIST_Y - 10) {
                break;
            }
            g.drawString(font, line, ResearchUi.PANEL_TEXT_X, y, TEXT_DIM, false);
            y += 10;
        }

        // 前提研究と、この研究で開放されるもの。素材要求を足すならこの区画を使う。
        y = ResearchUi.PANEL_LIST_Y;
        if (!n.parents().isEmpty()) {
            g.drawString(font, trim(Component.translatable(n.anyParent()
                            ? "gui.overgrown_vegetation.research.parents_any"
                            : "gui.overgrown_vegetation.research.parents_all").getString(), ResearchUi.PANEL_TEXT_W),
                    ResearchUi.PANEL_TEXT_X, y, TEXT, false);
            y += 10;
            for (String pid : n.parents()) {
                if (y > ResearchUi.PANEL_COST_Y - 30) {
                    break;
                }
                ResearchSyncPayload.NodeView p = nodeById(pid);
                boolean ok = ClientResearch.isUnlocked(pid);
                g.drawString(font, trim((ok ? "✔ " : "✖ ") + (p != null ? nodeName(p).getString() : pid),
                                ResearchUi.PANEL_TEXT_W - 2),
                        ResearchUi.PANEL_TEXT_X + 2, y, ok ? VanillaGui.GOOD : VanillaGui.BAD, false);
                y += 10;
            }
            y += 2;
        }
        List<RecipeHolder<?>> recipes = resolveRecipes(n);
        if (!recipes.isEmpty() && y <= ResearchUi.PANEL_COST_Y - 28) {
            g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.unlocks"),
                    ResearchUi.PANEL_TEXT_X, y, TEXT, false);
            y += 10;
            int ix = ResearchUi.PANEL_TEXT_X;
            for (RecipeHolder<?> h : recipes) {
                if (ix + 16 > ResearchUi.PANEL_TEXT_X + ResearchUi.PANEL_TEXT_W) {
                    break;
                }
                // getResultItem が空になるレシピ(樹液結晶の打ち込みなど)もあるので、
                // プレビューと同じ経路で成果物を求める。
                ItemStack out = layoutOf(h).result();
                if (!out.isEmpty()) {
                    g.renderFakeItem(out, ix, y);
                    ix += 17;
                }
            }
        }

        // コスト行
        boolean unlocked = ClientResearch.isUnlocked(n.id());
        boolean enough = ClientResearch.points() >= n.cost();
        g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.points_needed"),
                ResearchUi.PANEL_TEXT_X, ResearchUi.PANEL_COST_Y, TEXT_DIM, false);
        String costTxt = n.cost() + " / " + ClientResearch.points();
        g.drawString(font, costTxt,
                ResearchUi.PANEL_TEXT_X + ResearchUi.PANEL_TEXT_W - font.width(costTxt),
                ResearchUi.PANEL_COST_Y, unlocked ? TEXT_DIM : enough ? VanillaGui.GOOD : VanillaGui.BAD, false);

        // 研究開始ボタン
        boolean can = ClientResearch.canUnlock(n);
        boolean hover = in(mouseX, mouseY, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
                ResearchUi.PANEL_BTN_W, ResearchUi.PANEL_BTN_H);
        Component btnLbl;
        if (unlocked) {
            btnLbl = Component.translatable("gui.overgrown_vegetation.research.done");
        } else if (ClientResearch.exclusiveConflict(n) != null) {
            btnLbl = Component.translatable("gui.overgrown_vegetation.research.exclusive_tag");
        } else if (!ClientResearch.parentsSatisfied(n)) {
            btnLbl = Component.translatable("gui.overgrown_vegetation.research.locked");
        } else if (!enough) {
            btnLbl = Component.translatable("gui.overgrown_vegetation.research.not_enough");
        } else {
            btnLbl = Component.translatable("gui.overgrown_vegetation.research.start");
        }
        VanillaGui.button(g, font, btnLbl, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
                ResearchUi.PANEL_BTN_W, ResearchUi.PANEL_BTN_H, can, hover);
    }

    // ---------- 作業のタブ ----------

    private void renderWork(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.crafting_5x5"),
                ResearchUi.GRID_X - 1, ResearchUi.labelY(ResearchUi.GRID_Y), TEXT, false);
        g.drawString(font, playerInventoryTitle,
                ResearchUi.INV_X - 1, ResearchUi.labelY(ResearchUi.INV_Y), TEXT, false);
        Component analyzeLbl = Component.translatable("gui.overgrown_vegetation.research.analyze");
        g.drawString(font, analyzeLbl, ResearchUi.ANALYZE_X - 1, ResearchUi.labelY(ResearchUi.ANALYZE_Y), TEXT, false);

        for (Slot s : menu.slots) {
            if (s.x == ResearchUi.RESULT_X && s.y == ResearchUi.RESULT_Y) {
                VanillaGui.slot(g, s.x - 5, s.y - 5, 26, 26); // 作業台の成果と同じ大きい枠
            } else {
                VanillaGui.slotAround(g, s.x, s.y);
            }
        }
        VanillaGui.arrow(g, ResearchUi.ARROW_X, ResearchUi.ARROW_Y);

        boolean hover = in(mouseX, mouseY, ResearchUi.ANALYZE_BTN_X, ResearchUi.ANALYZE_BTN_Y,
                ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H);
        VanillaGui.button(g, font, analyzeLbl, ResearchUi.ANALYZE_BTN_X, ResearchUi.ANALYZE_BTN_Y,
                ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H, true, hover);
    }

    // ---------- ツリー ----------

    private static int centerX() {
        return (ResearchUi.TREE_X0 + ResearchUi.TREE_X1) / 2;
    }

    private static int centerY() {
        return (ResearchUi.TREE_Y0 + ResearchUi.TREE_Y1) / 2;
    }

    /** ワールド原点(= 格子 0,0)のキャンバス位置。 */
    private double originTreeX() {
        return centerX() + panX;
    }

    private double originTreeY() {
        return centerY() + panY;
    }

    /** ノードの枠の左上(ワールド座標)。 */
    private static int worldX(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(n.x() * ResearchUi.CELL_X) - ResearchUi.NODE / 2;
    }

    private static int worldY(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(n.y() * ResearchUi.CELL_Y) - ResearchUi.NODE / 2;
    }

    /** ワールド長 → キャンバス長。 */
    private int scaled(int v) {
        return Math.max(1, Math.round(v * zoom));
    }

    private int nodeX(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(originTreeX() + worldX(n) * zoom);
    }

    private int nodeY(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(originTreeY() + worldY(n) * zoom);
    }

    private List<ResearchSyncPayload.NodeView> tierNodes() {
        List<ResearchSyncPayload.NodeView> out = new ArrayList<>();
        for (ResearchSyncPayload.NodeView n : ClientResearch.nodes()) {
            if (n.tier() == activeTier) {
                out.add(n);
            }
        }
        return out;
    }

    private ResearchSyncPayload.NodeView nodeById(String id) {
        for (ResearchSyncPayload.NodeView n : ClientResearch.nodes()) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    private void renderTree(GuiGraphics g) {
        List<ResearchSyncPayload.NodeView> nodes = tierNodes();
        if (nodes.isEmpty()) {
            Component empty = Component.translatable("gui.overgrown_vegetation.research.empty_tier");
            g.drawString(font, trim(empty.getString(), ResearchUi.TREE_X1 - ResearchUi.TREE_X0 - 8),
                    ResearchUi.TREE_X0 + 6, centerY() - 4, 0xFFFFFFFF, true);
            return;
        }

        // ここから下はワールド座標。ズームは PoseStack が一様にかけるので、枠・アイコン・線も同じ倍率で拡大縮小される。
        g.pose().pushPose();
        g.pose().translate(originTreeX(), originTreeY(), 0.0);
        g.pose().scale(zoom, zoom, 1.0F);

        // 線は進捗画面と同じく、黒い太線の上に色の細線を重ねる。
        for (int pass = 0; pass < 2; pass++) {
            for (ResearchSyncPayload.NodeView n : nodes) {
                int cx = worldX(n);
                int cy = worldY(n) + ResearchUi.NODE / 2;
                boolean dashed = n.anyParent() && n.parents().size() > 1;
                for (String pid : n.parents()) {
                    ResearchSyncPayload.NodeView p = nodeById(pid);
                    if (p == null || p.tier() != activeTier) {
                        continue;
                    }
                    int ppx = worldX(p) + ResearchUi.NODE;
                    int ppy = worldY(p) + ResearchUi.NODE / 2;
                    int color = pass == 0 ? 0xFF000000
                            : ClientResearch.isUnlocked(pid) ? C_LINE_ON : dashed ? C_LINE_ANY : C_LINE;
                    int midX = (ppx + cx) / 2;
                    int w = pass == 0 ? 1 : 0;
                    hline(g, ppx, midX, ppy, w, color, dashed);
                    vline(g, midX, ppy, cy, w, color, dashed);
                    hline(g, midX, cx, cy, w, color, dashed);
                }
            }
        }

        renderExclusiveGroups(g, nodes);

        for (ResearchSyncPayload.NodeView n : nodes) {
            int x = worldX(n);
            int y = worldY(n);
            boolean unlocked = ClientResearch.isUnlocked(n.id());
            g.blitSprite(unlocked ? VanillaGui.FRAME_OBTAINED : VanillaGui.FRAME_UNOBTAINED, x, y, ResearchUi.NODE, ResearchUi.NODE);
            drawIcon(g, n, x + 5, y + 5);
        }
        // 状態の幕と縁はアイコンより手前に。
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, OVERLAY_Z);
        for (ResearchSyncPayload.NodeView n : nodes) {
            int x = worldX(n);
            int y = worldY(n);
            int s = ResearchUi.NODE;
            boolean unlocked = ClientResearch.isUnlocked(n.id());
            boolean available = ClientResearch.canUnlock(n);
            boolean excluded = !unlocked && ClientResearch.exclusiveConflict(n) != null;
            boolean locked = !unlocked && !excluded && !ClientResearch.parentsSatisfied(n);
            if (excluded) {
                g.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0x90A01818);
            } else if (locked) {
                g.fill(x + 2, y + 2, x + s - 2, y + s - 2, 0x99000000);
            }
            if (available) {
                outline(g, x - 1, y - 1, s + 2, s + 2, C_AVAILABLE);
            }
            if (n.id().equals(selectedNodeId)) {
                outline(g, x - 2, y - 2, s + 4, s + 4, 0xFFFFFFFF);
            }
        }
        g.pose().popPose();

        g.pose().popPose();
    }

    /** 1px の四角い縁。 */
    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private void drawIcon(GuiGraphics g, ResearchSyncPayload.NodeView n, int x, int y) {
        if ("texture".equals(n.iconType())) {
            ResourceLocation tex = OvergrownVegetation.id("textures/research/" + n.iconId() + ".png");
            g.blit(tex, x, y, 0.0F, 0.0F, 16, 16, 16, 16);
        } else {
            ResourceLocation id = ResourceLocation.tryParse(n.iconId());
            ItemStack stack = id != null ? new ItemStack(BuiltInRegistries.ITEM.get(id)) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                g.renderFakeItem(stack, x, y);
            }
        }
    }

    private String trim(String s, int maxWidth) {
        return VanillaGui.trim(font, s, maxWidth);
    }

    /**
     * 同じ二者択一グループのノードを破線でつなぎ、「ここから1つだけ」であることを示す。
     * y(なければ x)順に並べ、隣り合う2つの間を結ぶ。
     */
    private void renderExclusiveGroups(GuiGraphics g, List<ResearchSyncPayload.NodeView> nodes) {
        java.util.Map<String, List<ResearchSyncPayload.NodeView>> groups = new java.util.LinkedHashMap<>();
        for (ResearchSyncPayload.NodeView n : nodes) {
            if (!n.exclusiveGroup().isEmpty()) {
                groups.computeIfAbsent(n.exclusiveGroup(), k -> new ArrayList<>()).add(n);
            }
        }
        String tag = Component.translatable("gui.overgrown_vegetation.research.exclusive_tag").getString();
        for (List<ResearchSyncPayload.NodeView> members : groups.values()) {
            if (members.size() < 2) {
                continue;
            }
            members.sort((a, b) -> a.y() != b.y() ? Double.compare(a.y(), b.y()) : Double.compare(a.x(), b.x()));
            for (int i = 0; i + 1 < members.size(); i++) {
                ResearchSyncPayload.NodeView a = members.get(i);
                ResearchSyncPayload.NodeView b = members.get(i + 1);
                int ax = worldX(a) + ResearchUi.NODE / 2;
                int ay = worldY(a) + ResearchUi.NODE;
                int bx = worldX(b) + ResearchUi.NODE / 2;
                int by = worldY(b);
                dashLine(g, ax, ay, bx, by, C_LINE_EXCL);
                g.drawString(font, tag, (ax + bx) / 2 - font.width(tag) / 2, (ay + by) / 2 - 4, C_LINE_EXCL, true);
            }
        }
    }

    /** 任意の 2 点を結ぶ破線(等間隔サンプリングで 2px の点を打つ)。 */
    private static void dashLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i += 6) {
            int x = x1 + (x2 - x1) * i / Math.max(1, steps);
            int y = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    /** 横線。{@code w} は太らせる幅(0 = 1px、1 = 上下に 1px ずつ足して 3px)。 */
    private static void hline(GuiGraphics g, int x1, int x2, int y, int w, int color, boolean dashed) {
        int a = Math.min(x1, x2) - w;
        int b = Math.max(x1, x2) + w;
        if (!dashed) {
            g.fill(a, y - w, b + 1, y + 1 + w, color);
            return;
        }
        for (int x = a; x <= b; x += 6) {
            g.fill(x, y - w, Math.min(x + 3 + 2 * w, b + 1), y + 1 + w, color);
        }
    }

    private static void vline(GuiGraphics g, int x, int y1, int y2, int w, int color, boolean dashed) {
        int a = Math.min(y1, y2) - w;
        int b = Math.max(y1, y2) + w;
        if (!dashed) {
            g.fill(x - w, a, x + 1 + w, b + 1, color);
            return;
        }
        for (int y = a; y <= b; y += 6) {
            g.fill(x - w, y, x + 1 + w, Math.min(y + 3 + 2 * w, b + 1), color);
        }
    }

    /** キャンバス座標でのツリー領域判定。 */
    private static boolean inTree(double cx, double cy) {
        return cx >= ResearchUi.TREE_X0 && cx < ResearchUi.TREE_X1
                && cy >= ResearchUi.TREE_Y0 && cy < ResearchUi.TREE_Y1;
    }

    private ResearchSyncPayload.NodeView nodeAt(double cx, double cy) {
        for (ResearchSyncPayload.NodeView n : tierNodes()) {
            int x = nodeX(n);
            int y = nodeY(n);
            if (cx >= x && cx < x + scaled(ResearchUi.NODE) && cy >= y && cy < y + scaled(ResearchUi.NODE)) {
                return n;
            }
        }
        return null;
    }

    private void setTier(int t) {
        activeTier = t;
        needsCenter = true;
    }

    /**
     * 選択中の Tier のノード群がツリーの面に収まるようにパン・ズームを合わせる。アイコンが小さくなりすぎないよう、
     * ズームは 0.6 より下げない(収まらない分はドラッグで見る。進捗画面と同じ)。
     */
    private void centerOnTier() {
        List<ResearchSyncPayload.NodeView> ns = tierNodes();
        if (ns.isEmpty()) {
            panX = 0;
            panY = 0;
            zoom = 1.0F;
            return;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ResearchSyncPayload.NodeView n : ns) {
            double wx = n.x() * ResearchUi.CELL_X;
            double wy = n.y() * ResearchUi.CELL_Y;
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minY = Math.min(minY, wy);
            maxY = Math.max(maxY, wy);
        }
        double bw = (maxX - minX) + ResearchUi.NODE + 16;
        double bh = (maxY - minY) + ResearchUi.NODE + 16;
        double availW = ResearchUi.TREE_X1 - ResearchUi.TREE_X0;
        double availH = ResearchUi.TREE_Y1 - ResearchUi.TREE_Y0;
        zoom = (float) Math.max(0.6, Math.min(1.0, Math.min(availW / bw, availH / bh)));
        // 収まらないときは左上(ツリーの根元)から見せる。
        double cx = bw * zoom > availW ? minX + (availW / zoom - ResearchUi.NODE - 16) / 2.0 : (minX + maxX) / 2.0;
        double cy = bh * zoom > availH ? minY + (availH / zoom - ResearchUi.NODE - 16) / 2.0 : (minY + maxY) / 2.0;
        panX = -cx * zoom;
        panY = -cy * zoom;
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> "VI";
        };
    }

    private int countNodes(int tier) {
        int c = 0;
        for (ResearchSyncPayload.NodeView n : ClientResearch.nodes()) {
            if (n.tier() == tier) {
                c++;
            }
        }
        return c;
    }

    /**
     * ノード名。lang に {@code research.overgrown_vegetation.<ノード id>} があればそれを使い、
     * 無ければ {@code research.json} の {@code name} をそのまま出す(既存データが壊れない)。
     */
    static Component nodeName(ResearchSyncPayload.NodeView n) {
        return Component.translatableWithFallback(
                "research." + OvergrownVegetation.MODID + "." + n.id(), n.name());
    }

    /** ノードの説明。キーは {@code research.overgrown_vegetation.<ノード id>.desc}。 */
    static Component nodeDesc(ResearchSyncPayload.NodeView n) {
        return Component.translatableWithFallback(
                "research." + OvergrownVegetation.MODID + "." + n.id() + ".desc", n.description());
    }

    private List<Component> tooltip(ResearchSyncPayload.NodeView n) {
        List<Component> t = new ArrayList<>();
        t.add(nodeName(n).copy().withStyle(ChatFormatting.WHITE));
        String conflict = ClientResearch.exclusiveConflict(n);
        if (ClientResearch.isUnlocked(n.id())) {
            t.add(Component.translatable("gui.overgrown_vegetation.research.done").withStyle(ChatFormatting.GREEN));
        } else if (conflict != null) {
            ResearchSyncPayload.NodeView other = nodeById(conflict);
            t.add(Component.translatable("gui.overgrown_vegetation.research.exclusive_blocked",
                    other != null ? nodeName(other).getString() : conflict).withStyle(ChatFormatting.RED));
        } else {
            ChatFormatting cf = ClientResearch.canUnlock(n) ? ChatFormatting.AQUA
                    : ClientResearch.parentsSatisfied(n) ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY;
            t.add(Component.translatable("gui.overgrown_vegetation.research.cost", n.cost()).withStyle(cf));
        }
        t.add(Component.translatable("gui.overgrown_vegetation.research.click_select")
                .withStyle(ChatFormatting.DARK_GRAY));
        List<RecipeHolder<?>> recipes = resolveRecipes(n);
        if (recipes.size() > 1) {
            int idx = Math.floorMod(recipeIndex, recipes.size());
            t.add(Component.translatable("gui.overgrown_vegetation.research.recipe_scroll_hint", idx + 1, recipes.size())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return t;
    }

    /** {@code n.unlocks()} のうち、実際に読み込まれているレシピだけを解決する。 */
    private List<RecipeHolder<?>> resolveRecipes(ResearchSyncPayload.NodeView n) {
        List<RecipeHolder<?>> out = new ArrayList<>();
        if (minecraft == null || minecraft.level == null) {
            return out;
        }
        for (String raw : n.unlocks()) {
            ResourceLocation id = raw.contains(":") ? ResourceLocation.tryParse(raw) : OvergrownVegetation.id(raw);
            if (id == null) {
                continue;
            }
            minecraft.level.getRecipeManager().byKey(id).ifPresent(out::add);
        }
        return out;
    }

    /**
     * レシピを「何列×何行の枠に、どのマスへ何を置くか」まで落としたもの。
     *
     * <p>枠の大きさは<b>加工台の大きさ</b>で決める(作業台 3×3 / 研究台の拡大作業台 5×5 /
     * かまど・石切台 1 / 鍛冶台 3)。材料が無いマスも枠として描くので、2×2 のレシピでも
     * 3×3 が丸ごと出るし、5×5 のレシピなら 25 マス全部が出る。
     *
     * @param cells  長さ {@code cols * rows} の行優先。1 マスに置ける候補一覧(タグ材料なら複数)。
     *               空きマスは長さ 0。
     * @param result 成果物。{@code deriveResult} が true のときは「今表示しているベース」から
     *               毎フレーム作り直す(樹液結晶の打ち込みのように、入力がそのまま成果物になる加工)。
     */
    private record RecipeLayout(int cols, int rows, ItemStack[][] cells, ItemStack result,
                                boolean deriveResult, ItemStack station) {
    }

    /** レシピ 1 つぶんの枠計算はレシピが変わらない限り同じなので、画面ごとに覚えておく。 */
    private final java.util.Map<ResourceLocation, RecipeLayout> layoutCache = new java.util.HashMap<>();

    private RecipeLayout layoutOf(RecipeHolder<?> holder) {
        return layoutCache.computeIfAbsent(holder.id(), id -> buildLayout(holder.value()));
    }

    private RecipeLayout buildLayout(Recipe<?> recipe) {
        net.minecraft.core.HolderLookup.Provider registries = minecraft.level.registryAccess();
        // 加工台の見出しに使うアイコン。バニラが種類ごとに正しい台を返してくれる。
        ItemStack station = recipe.getToastSymbol();
        ItemStack result = recipe.getResultItem(registries);

        // 鍛冶台。SmithingRecipe は材料の Ingredient を公開しないので、判定メソッドに
        // アイテムレジストリを通して候補を集める(ベースが「汚染できる装備すべて」でも出せる)。
        if (recipe instanceof net.minecraft.world.item.crafting.SmithingRecipe smithing) {
            ItemStack[][] cells = {
                    itemsMatching(smithing::isTemplateIngredient),
                    itemsMatching(smithing::isBaseIngredient),
                    itemsMatching(smithing::isAdditionIngredient),
            };
            boolean derive = result.isEmpty();
            if (derive) {
                result = smithingResult(smithing, first(cells[0]), first(cells[1]), first(cells[2]), registries);
            }
            return new RecipeLayout(3, 1, cells, result, derive, station);
        }

        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        // かまど・溶鉱炉・燻製器・焚き火・石切台 — 入力は 1 マスだけ。
        if (recipe instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe
                || recipe instanceof net.minecraft.world.item.crafting.SingleItemRecipe) {
            ItemStack[][] cells = {ingredients.isEmpty() ? new ItemStack[0] : ingredients.get(0).getItems()};
            return new RecipeLayout(1, 1, cells, result, false, station);
        }

        // 定形クラフト。パターンは枠の左上に詰めて置き、残りは空きマスとして描く。
        int cols;
        int patW;
        if (recipe instanceof com.yayuy.overgrownvegetation.crafting.SizedCraftingRecipe sized) {
            cols = ResearchUi.GRID;
            patW = Math.max(1, sized.width());
        } else if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            cols = 3;
            patW = Math.max(1, shaped.getWidth());
        } else {
            // 不定形クラフトなど。並び順に意味が無いので左上から順に詰める。
            cols = ingredients.size() <= 1 ? 1 : ingredients.size() <= 9 ? 3 : ResearchUi.GRID;
            patW = cols;
        }
        int rows = cols == 1 ? 1 : cols;
        patW = Math.min(patW, cols);

        ItemStack[][] cells = new ItemStack[cols * rows][];
        java.util.Arrays.fill(cells, NO_CHOICES);
        for (int i = 0; i < ingredients.size(); i++) {
            int slotIndex = (i / patW) * cols + (i % patW);
            if (slotIndex >= cells.length) {
                break;
            }
            cells[slotIndex] = ingredients.get(i).getItems();
        }
        return new RecipeLayout(cols, rows, cells, result, false, station);
    }

    /** {@code test} に通るアイテムを、レジストリ順に最大 64 個まで集める(枠のなかで 1 秒ごとに切り替えて見せる)。 */
    private static ItemStack[] itemsMatching(java.util.function.Predicate<ItemStack> test) {
        List<ItemStack> out = new ArrayList<>();
        for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            try {
                ItemStack stack = item.getDefaultInstance();
                if (!stack.isEmpty() && test.test(stack)) {
                    out.add(stack);
                    if (out.size() >= 64) {
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
                // 他 mod のアイテムが判定で落ちても、プレビューごと死なせない。
            }
        }
        return out.toArray(ItemStack[]::new);
    }

    /** 成果物が「入力そのもの」の鍛冶レシピ用。表示中のベースから実際に組み立てて見せる。 */
    private static ItemStack smithingResult(net.minecraft.world.item.crafting.SmithingRecipe recipe,
                                            ItemStack template, ItemStack base, ItemStack addition,
                                            net.minecraft.core.HolderLookup.Provider registries) {
        if (base.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            return recipe.assemble(
                    new net.minecraft.world.item.crafting.SmithingRecipeInput(template, base, addition), registries);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack first(ItemStack[] choices) {
        return choices.length == 0 ? ItemStack.EMPTY : choices[0];
    }

    /** タグ材料などで候補が複数あるマスは 1 秒ごとに切り替える。 */
    private ItemStack cycled(ItemStack[] choices) {
        if (choices.length == 0) {
            return ItemStack.EMPTY;
        }
        long second = minecraft.level.getGameTime() / 20L;
        return choices[(int) Math.floorMod(second, choices.length)];
    }

    /**
     * ホバー中ノードが開放するレシピを、ノードのすぐ下(はみ出す場合は上)に
     * 加工台名 → 材料の枠 → 矢印 → 成果物 の形で表示する。窓の中ならツリーの面の外へはみ出してよい。
     * {@code unlocks} が複数あるときはホイールで切り替える。
     */
    private void renderRecipePreview(GuiGraphics g, ResearchSyncPayload.NodeView n) {
        List<RecipeHolder<?>> recipes = resolveRecipes(n);
        if (recipes.isEmpty() || minecraft == null || minecraft.level == null) {
            return;
        }
        int idx = Math.floorMod(recipeIndex, recipes.size());
        recipeIndex = idx;
        RecipeHolder<?> holder = recipes.get(idx);
        RecipeLayout layout = layoutOf(holder);

        int slot = PREVIEW_SLOT;
        int gridW = layout.cols() * slot;
        int gridH = layout.rows() * slot;
        int arrowW = VanillaGui.ARROW_W + 4;
        int pad = 5;
        int headH = 18;
        int contentH = Math.max(gridH, slot);
        String stationName = layout.station().isEmpty() ? ""
                : trim(layout.station().getHoverName().getString(), 110);
        int contentW = Math.max(gridW + arrowW + slot, 18 + font.width(stationName));
        int panelW = contentW + pad * 2;
        boolean showIndex = recipes.size() > 1;
        int panelH = headH + contentH + pad * 2 + (showIndex ? 11 : 0);

        int left = ResearchUi.WIN_X + 4;
        int right = ResearchUi.WIN_X + ResearchUi.WIN_W - 4;
        int top = ResearchUi.WIN_Y + 4;
        int bottom = ResearchUi.WIN_Y + ResearchUi.WIN_H - 4;
        int nx = nodeX(n);
        int ny = nodeY(n);
        int px = Math.max(left, Math.min(nx, right - panelW));
        int py = ny + scaled(ResearchUi.NODE) + 4;
        if (py + panelH > bottom) {
            py = ny - panelH - 4;
        }
        py = Math.max(top, py);

        // ツリーのアイコン(z 150 前後)より手前に出す。ツールチップ(z 400)よりは奥。
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, OVERLAY_Z);
        g.fill(px - 1, py - 1, px + panelW + 1, py + panelH + 1, PREVIEW_BORDER);
        g.fill(px, py, px + panelW, py + panelH, PREVIEW_BG);

        // 見出し: どの加工台で作るのか。
        if (!layout.station().isEmpty()) {
            g.renderFakeItem(layout.station(), px + pad, py + pad);
            g.drawString(font, stationName, px + pad + 18, py + pad + 4, 0xFFAAAAAA, false);
        }

        // 材料の枠。空きマスも描くので、加工台の大きさがそのまま見える。
        int gx = px + pad;
        int gy = py + pad + headH + (contentH - gridH) / 2;
        g.fill(gx, gy, gx + gridW, gy + gridH, PREVIEW_BORDER);
        ItemStack shownBase = ItemStack.EMPTY;
        for (int i = 0; i < layout.cells().length; i++) {
            int cx = gx + (i % layout.cols()) * slot;
            int cy = gy + (i / layout.cols()) * slot;
            g.fill(cx + 1, cy + 1, cx + 17, cy + 17, PREVIEW_SLOT_BG);
            ItemStack show = cycled(layout.cells()[i]);
            if (!show.isEmpty()) {
                g.renderFakeItem(show, cx + 1, cy + 1);
                if (i == 1) {
                    shownBase = show;
                }
            }
        }

        VanillaGui.arrow(g, gx + gridW + 2, py + pad + headH + contentH / 2 - 7);

        ItemStack result = layout.result();
        if (layout.deriveResult()
                && holder.value() instanceof net.minecraft.world.item.crafting.SmithingRecipe smithing) {
            // ベースが 1 秒ごとに切り替わるので、成果物もそれに合わせて作り直す。
            result = smithingResult(smithing, first(layout.cells()[0]), shownBase, first(layout.cells()[2]),
                    minecraft.level.registryAccess());
        }
        int rx = px + panelW - pad - 17;
        int ry = py + pad + headH + (contentH - 16) / 2;
        if (!result.isEmpty()) {
            g.renderFakeItem(result, rx, ry);
            g.renderItemDecorations(font, result, rx, ry);
        }

        if (showIndex) {
            String idxTxt = (idx + 1) + " / " + recipes.size();
            g.drawString(font, idxTxt, px + (panelW - font.width(idxTxt)) / 2, py + panelH - pad - 9, 0xFFAAAAAA, false);
        }
        g.pose().popPose();
    }

    // ---------- 入力 ----------
    // バニラのスロット当たり判定は leftPos/topPos(= 0)を引くだけなので、マウス座標を
    // キャンバス座標に直してから super に渡せばそのまま正しく当たる。

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);

        if (button == 0) {
            for (int i = 0; i < ResearchUi.TAB_COUNT; i++) {
                if (in(cx, cy, ResearchUi.TAB_X + i * ResearchUi.TAB_STEP, 0, ResearchUi.TAB_W, ResearchUi.TAB_H)) {
                    setTab(i);
                    return true;
                }
            }
            if (tab == ResearchUi.TAB_WORK) {
                if (in(cx, cy, ResearchUi.ANALYZE_BTN_X, ResearchUi.ANALYZE_BTN_Y,
                        ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H)) {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ResearchTableMenu.ANALYZE_BUTTON);
                    }
                    playClick();
                    return true;
                }
            } else {
                for (int t = 1; t <= ResearchUi.RAIL_TIERS; t++) {
                    if (in(cx, cy, ResearchUi.railX(t - 1), ResearchUi.RAIL_Y, ResearchUi.RAIL_W, ResearchUi.RAIL_H)) {
                        if (t != activeTier) {
                            setTier(t);
                            playClick();
                        }
                        return true;
                    }
                }
                if (in(cx, cy, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
                        ResearchUi.PANEL_BTN_W, ResearchUi.PANEL_BTN_H)) {
                    ResearchSyncPayload.NodeView sel = selectedNodeId != null ? nodeById(selectedNodeId) : null;
                    if (sel != null && ClientResearch.canUnlock(sel)) {
                        PacketDistributor.sendToServer(new ResearchUnlockPayload(sel.id()));
                        playClick();
                    }
                    return true;
                }
                if (inTree(cx, cy)) {
                    ResearchSyncPayload.NodeView n = nodeAt(cx, cy);
                    if (n != null) {
                        selectedNodeId = n.id();
                        return true;
                    }
                    dragging = true;
                    lastX = cx;
                    lastY = cy;
                    return true;
                }
            }
        }
        return super.mouseClicked(cx, cy, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);
        if (dragging) {
            panX += cx - lastX;
            panY += cy - lastY;
            lastX = cx;
            lastY = cy;
            return true;
        }
        return super.mouseDragged(cx, cy, button, dx / uiScale, dy / uiScale);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(toCanvasX(mx), toCanvasY(my), button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);
        if (tab == ResearchUi.TAB_TREE && inTree(cx, cy)) {
            ResearchSyncPayload.NodeView hovered = nodeAt(cx, cy);
            if (hovered != null) {
                List<RecipeHolder<?>> recipes = resolveRecipes(hovered);
                if (recipes.size() > 1) {
                    recipeIndex = Math.floorMod(recipeIndex + (dy > 0 ? 1 : -1), recipes.size());
                    return true;
                }
            }
            float old = zoom;
            zoom = (float) Math.max(0.5, Math.min(1.5, zoom * (dy > 0 ? 1.1 : 0.9)));
            double f = zoom / old - 1.0;
            panX -= (cx - (centerX() + panX)) * f;
            panY -= (cy - (centerY() + panY)) * f;
            return true;
        }
        return super.mouseScrolled(cx, cy, dx, dy);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
