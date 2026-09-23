package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.menu.ResearchUi;
import com.yayuy.overgrownvegetation.menu.ResearchTableMenu;
import com.yayuy.overgrownvegetation.network.ResearchSyncPayload;
import com.yayuy.overgrownvegetation.network.ResearchUnlockPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.NonNullList;
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
 * 研究台の全画面 UI。
 *
 * <h2>1 枚絵で作るための仕組み</h2>
 * UI は {@link ResearchUi#CANVAS_W}×{@link ResearchUi#CANVAS_H} の<b>固定キャンバス</b>として設計し、
 * 画面サイズには<b>一様スケール 1 つ</b>({@link #uiScale})で追従する。描画も入力もすべて
 * キャンバス座標で行い、{@code PoseStack} の scale と {@link #toCanvasX}/{@link #toCanvasY} が
 * 画面座標との橋渡しをする。縦横比が変わらないので、キャンバスと同じ比率で描いた 1 枚の
 * PNG({@code textures/gui/research_ui.png})をそのまま背景にできる。
 *
 * <p>そのため {@code leftPos}/{@code topPos} は<b>常に 0</b>。バニラのスロット当たり判定は
 * {@code isHovering} 経由でこの 2 つを引くだけなので、マウス座標をキャンバス座標に変換してから
 * {@code super} に渡せば、追加の細工なしでスロットが正しく当たる({@link #mouseClicked} ほか)。
 *
 * <p>ボタン類はバニラの {@code Button} ウィジェットを使わない(ウィジェットは画面座標で
 * 当たり判定するのでスケールと噛み合わない)。Tier タブ・解析・研究開始・閉じるは、すべて
 * {@link ResearchUi} の矩形に対する自前の当たり判定で処理する。
 *
 * <p>ツールチップだけはスケールの外(等倍・画面座標)で描く。画面端でのはみ出し防止が
 * バニラ側の画面幅基準で行われるため。
 */
public class ResearchTableScreen extends AbstractContainerScreen<ResearchTableMenu> {

    /** UI テクスチャはこの 1 枚だけ。 */
    private static final ResourceLocation UI_TEX = OvergrownVegetation.id("textures/gui/research_ui.png");

    private static final int LABEL = 0xFF3A3A32;
    private static final int TEXT = 0xFFE8E8DC;
    private static final int TEXT_DIM = 0xFF9AA090;
    private static final int ACCENT = 0xFF9CE39C;

    private static final int PREVIEW_BG = 0xF0100010;
    private static final int PREVIEW_BORDER = 0xFF3A3A3A;
    private static final int PREVIEW_SLOT_BG = 0x66000000;
    /** レシピプレビューの 1 マスのピッチ(枠 16px + 仕切り 2px)。 */
    private static final int PREVIEW_SLOT = 18;
    /** 空きマスを表す「候補なし」。 */
    private static final ItemStack[] NO_CHOICES = new ItemStack[0];

    private static final int C_LINE = 0xFF9AA0A6;
    private static final int C_LINE_ON = 0xFF7FE07F;
    /** 「親のどれか1つでよい」接続(破線)。 */
    private static final int C_LINE_ANY = 0xFFE0B45A;
    /** 二者択一グループの仕切り(破線)。 */
    private static final int C_LINE_EXCL = 0xFFD98CD9;

    // ---- キャンバス → 画面 の変換 ----
    private float uiScale = 1.0F;
    private float originX;
    private float originY;

    private int activeTier = 1;
    private double panX;
    private double panY;
    private float zoom = 1.0F;
    private boolean dragging;
    private boolean needsCenter = true;
    private double lastX;
    private double lastY;

    /** 右のパネルに出しているノード(クリックで選択)。 */
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
        // キャンバスを画面に一様スケールで収め、中央寄せ(余りは余白)。
        this.uiScale = Math.min(this.width / (float) ResearchUi.CANVAS_W,
                this.height / (float) ResearchUi.CANVAS_H);
        this.originX = (this.width - ResearchUi.CANVAS_W * uiScale) / 2.0F;
        this.originY = (this.height - ResearchUi.CANVAS_H * uiScale) / 2.0F;
        // キャンバス座標 = スロット座標にする(バニラの当たり判定がそのまま使える)。
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

    private int toScreenX(double canvasX) {
        return Math.round(originX + (float) canvasX * uiScale);
    }

    private int toScreenY(double canvasY) {
        return Math.round(originY + (float) canvasY * uiScale);
    }

    private static boolean in(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    // ---------- アトラス描画 ----------

    private static void blit(GuiGraphics g, ResearchUi.Sprite s, int x, int y, int w, int h) {
        g.blit(UI_TEX, x, y, w, h, s.u(), s.v(), s.tw(), s.th(), ResearchUi.TEX_W, ResearchUi.TEX_H);
    }

    private static void blit(GuiGraphics g, ResearchUi.Sprite s, int x, int y) {
        blit(g, s, x, y, s.w(), s.h());
    }

    // ---------- 描画 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (needsCenter && !ClientResearch.nodes().isEmpty()) {
            centerOnTier();
            needsCenter = false;
        }
        // 余白(レターボックス)も含めて画面全体を暗くする。
        g.fill(0, 0, this.width, this.height, 0xC0101015);

        int cmx = (int) Math.round(toCanvasX(mouseX));
        int cmy = (int) Math.round(toCanvasY(mouseY));

        g.pose().pushPose();
        g.pose().translate(originX, originY, 0.0F);
        g.pose().scale(uiScale, uiScale, 1.0F);
        // super が renderBg → スロット → 掴んでいるアイテム を、すべてキャンバス座標で描く。
        super.render(g, cmx, cmy, partialTick);
        if (inTree(cmx, cmy)) {
            ResearchSyncPayload.NodeView hovered = nodeAt(cmx, cmy);
            if (hovered != null) {
                if (!hovered.id().equals(hoveredNodeId)) {
                    hoveredNodeId = hovered.id();
                    recipeIndex = 0;
                }
                renderRecipePreview(g, hovered);
            } else {
                hoveredNodeId = null;
            }
        } else {
            hoveredNodeId = null;
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
        }
    }

    /** 暗転は {@link #render} が画面全体に描くので、ここでは UI 本体だけ。 */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 独自レイアウトなのでバニラのタイトル描画は行わない。
    }

    /** ここに来る {@code mouseX}/{@code mouseY} はキャンバス座標。 */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // ① UI 全体 = アトラス上部の 1 枚絵。
        blit(g, ResearchUi.BG, 0, 0, ResearchUi.CANVAS_W, ResearchUi.CANVAS_H);

        // ② 研究ツリー(scissor は画面座標で指定する)。
        g.enableScissor(toScreenX(ResearchUi.TREE_X0), toScreenY(ResearchUi.TREE_Y0),
                toScreenX(ResearchUi.TREE_X1), toScreenY(ResearchUi.TREE_Y1));
        for (int ty = ResearchUi.TREE_Y0; ty < ResearchUi.TREE_Y1; ty += 64) {
            for (int tx = ResearchUi.TREE_X0; tx < ResearchUi.TREE_X1; tx += 64) {
                blit(g, ResearchUi.TREE_TILE, tx, ty);
            }
        }
        renderTree(g);
        g.disableScissor();

        renderHeader(g, mouseX, mouseY);
        renderTierTabs(g, mouseX, mouseY);
        renderPanel(g, mouseX, mouseY);
        renderBarChrome(g, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, this.title, ResearchUi.TITLE_X, ResearchUi.TITLE_Y, TEXT, true);
        Component sub = Component.translatable("gui.overgrown_vegetation.research.title_sub");
        g.drawString(font, sub, ResearchUi.TITLE_X, ResearchUi.SUBTITLE_Y, TEXT_DIM, false);

        Component pts = Component.translatable("gui.overgrown_vegetation.research.points", ClientResearch.points());
        g.drawString(font, pts, ResearchUi.POINTS_TEXT_X, ResearchUi.POINTS_TEXT_Y, ACCENT, true);

        boolean hover = in(mouseX, mouseY, ResearchUi.CLOSE_X, ResearchUi.CLOSE_Y,
                ResearchUi.CLOSE_W, ResearchUi.CLOSE_H);
        blit(g, hover ? ResearchUi.CLOSE_HOVER : ResearchUi.CLOSE, ResearchUi.CLOSE_X, ResearchUi.CLOSE_Y);
    }

    private void renderTierTabs(GuiGraphics g, int mouseX, int mouseY) {
        for (int t = 1; t <= ResearchUi.RAIL_TIERS; t++) {
            int y = ResearchUi.railY(t - 1);
            boolean active = t == activeTier;
            blit(g, active ? ResearchUi.TAB_ACTIVE : ResearchUi.TAB, ResearchUi.RAIL_X, y);

            String label = "Tier " + roman(t);
            g.drawString(font, label, ResearchUi.RAIL_X + 8, y + 5, active ? ACCENT : TEXT, true);
            Component era = Component.translatableWithFallback(
                    "gui.overgrown_vegetation.research.tier." + t, "");
            String eraTxt = era.getString();
            if (!eraTxt.isEmpty()) {
                g.drawString(font, trim(eraTxt, ResearchUi.RAIL_W - 24), ResearchUi.RAIL_X + 8, y + 15,
                        TEXT_DIM, false);
            }
            int count = countNodes(t);
            String badge = String.valueOf(count);
            g.drawString(font, badge, ResearchUi.RAIL_X + ResearchUi.RAIL_W - 6 - font.width(badge), y + 5,
                    count == 0 ? 0xFF6A6A6A : TEXT_DIM, false);
        }
    }

    // ---------- 右の詳細パネル ----------

    private void renderPanel(GuiGraphics g, int mouseX, int mouseY) {
        ResearchSyncPayload.NodeView n = selectedNodeId != null ? nodeById(selectedNodeId) : null;
        if (n == null) {
            List<FormattedCharSequence> hint = font.split(
                    Component.translatable("gui.overgrown_vegetation.research.select_hint"),
                    ResearchUi.PANEL_TEXT_W);
            int y = ResearchUi.PANEL_DESC_Y;
            for (FormattedCharSequence line : hint) {
                g.drawString(font, line, ResearchUi.PANEL_TEXT_X, y, TEXT_DIM, false);
                y += 10;
            }
            return;
        }

        drawIcon(g, n, ResearchUi.PANEL_ICON_X, ResearchUi.PANEL_ICON_Y);
        g.drawString(font, trim(nodeName(n).getString(), ResearchUi.PANEL_W - 38),
                ResearchUi.PANEL_TITLE_X, ResearchUi.PANEL_TITLE_Y, TEXT, true);
        g.drawString(font, "Tier " + roman(n.tier()), ResearchUi.PANEL_TITLE_X, ResearchUi.PANEL_TIER_Y,
                TEXT_DIM, false);

        int y = ResearchUi.PANEL_DESC_Y;
        for (FormattedCharSequence line : font.split(nodeDesc(n), ResearchUi.PANEL_TEXT_W)) {
            if (y > ResearchUi.PANEL_LIST_Y - 10) {
                break;
            }
            g.drawString(font, line, ResearchUi.PANEL_TEXT_X, y, TEXT_DIM, false);
            y += 10;
        }

        // 「必要な素材」に当たる欄。研究の要求は現状ポイントのみ(素材要求はデータ側に無い)なので、
        // ここでは前提研究と、この研究で開放されるものを出す。素材要求を足すならこの区画を使う。
        y = ResearchUi.PANEL_LIST_Y;
        if (!n.parents().isEmpty()) {
            g.drawString(font, Component.translatable(n.anyParent()
                            ? "gui.overgrown_vegetation.research.parents_any"
                            : "gui.overgrown_vegetation.research.parents_all"),
                    ResearchUi.PANEL_TEXT_X, y, TEXT, false);
            y += 11;
            for (String pid : n.parents()) {
                ResearchSyncPayload.NodeView p = nodeById(pid);
                boolean ok = ClientResearch.isUnlocked(pid);
                g.drawString(font, trim((ok ? "✔ " : "✖ ") + (p != null ? nodeName(p).getString() : pid),
                                ResearchUi.PANEL_TEXT_W),
                        ResearchUi.PANEL_TEXT_X + 2, y, ok ? ACCENT : 0xFFC98A8A, false);
                y += 10;
            }
            y += 2;
        }
        List<RecipeHolder<?>> recipes = resolveRecipes(n);
        if (!recipes.isEmpty() && y < ResearchUi.PANEL_COST_Y - 22) {
            g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.unlocks"),
                    ResearchUi.PANEL_TEXT_X, y, TEXT, false);
            y += 11;
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
        Component costLbl = Component.translatable("gui.overgrown_vegetation.research.points_needed");
        g.drawString(font, costLbl, ResearchUi.PANEL_TEXT_X, ResearchUi.PANEL_COST_Y, TEXT_DIM, false);
        String costTxt = n.cost() + " / " + ClientResearch.points();
        g.drawString(font, costTxt,
                ResearchUi.PANEL_TEXT_X + ResearchUi.PANEL_TEXT_W - font.width(costTxt),
                ResearchUi.PANEL_COST_Y, unlocked ? TEXT_DIM : enough ? ACCENT : 0xFFE07F7F, true);

        // 研究開始ボタン
        boolean can = ClientResearch.canUnlock(n);
        boolean hover = in(mouseX, mouseY, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
                ResearchUi.PANEL_BTN_W, ResearchUi.PANEL_BTN_H);
        ResearchUi.Sprite btn = !can ? ResearchUi.BTN_DISABLED : hover ? ResearchUi.BTN_HOVER : ResearchUi.BTN;
        blit(g, btn, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
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
        String txt = trim(btnLbl.getString(), ResearchUi.PANEL_BTN_W - 8);
        g.drawString(font, txt,
                ResearchUi.PANEL_BTN_X + (ResearchUi.PANEL_BTN_W - font.width(txt)) / 2,
                ResearchUi.PANEL_BTN_Y + (ResearchUi.PANEL_BTN_H - 8) / 2,
                can ? 0xFFFFFFFF : 0xFF8A8A8A, true);
    }

    // ---------- 下部バー ----------

    private void renderBarChrome(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.crafting_5x5"),
                ResearchUi.GRID_X, ResearchUi.labelY(ResearchUi.GRID_Y), LABEL, false);
        g.drawString(font, playerInventoryTitle,
                ResearchUi.INV_X, ResearchUi.labelY(ResearchUi.INV_Y), LABEL, false);
        Component analyzeLbl = Component.translatable("gui.overgrown_vegetation.research.analyze");
        g.drawString(font, analyzeLbl,
                ResearchUi.analyzeCenterX(menu.analyzeCount()) - font.width(analyzeLbl) / 2,
                ResearchUi.labelY(ResearchUi.ANALYZE_Y), LABEL, false);

        // スロットの窪み(アトラスのスプライト。背景に描き込むなら透明にしておけばよい)。
        for (Slot s : menu.slots) {
            blit(g, ResearchUi.SLOT_BG, s.x - 1, s.y - 1);
        }

        blit(g, ResearchUi.ARROW_RIGHT, ResearchUi.ARROW_X, ResearchUi.RESULT_Y + 2);
        blit(g, ResearchUi.ARROW_DOWN, ResearchUi.analyzeCenterX(menu.analyzeCount()) - 7,
                ResearchUi.analyzeArrowY(menu.analyzeCount()));

        int bx = ResearchUi.analyzeCenterX(menu.analyzeCount()) - ResearchUi.ANALYZE_BTN_W / 2;
        int by = ResearchUi.analyzeButtonY(menu.analyzeCount());
        boolean hover = in(mouseX, mouseY, bx, by, ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H);
        blit(g, hover ? ResearchUi.BTN_HOVER : ResearchUi.BTN, bx, by,
                ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H);
        String txt = analyzeLbl.getString();
        g.drawString(font, txt, bx + (ResearchUi.ANALYZE_BTN_W - font.width(txt)) / 2,
                by + (ResearchUi.ANALYZE_BTN_H - 8) / 2, 0xFFFFFFFF, true);
    }

    // ---------- ツリー ----------

    private static int nodeW(ResearchSyncPayload.NodeView n) {
        return n.width() > 0 ? n.width() : ClientResearch.nodeWidth();
    }

    private static int nodeH(ResearchSyncPayload.NodeView n) {
        return n.height() > 0 ? n.height() : ClientResearch.nodeHeight();
    }

    private static int spacingX() {
        return ClientResearch.spacingX();
    }

    private static int spacingY() {
        return ClientResearch.spacingY();
    }

    private static int centerX() {
        return (ResearchUi.TREE_X0 + ResearchUi.TREE_X1) / 2;
    }

    private static int centerY() {
        return (ResearchUi.TREE_Y0 + ResearchUi.TREE_Y1) / 2;
    }

    /** ワールド原点(= ノード座標 0,0 の中心)のキャンバス位置。 */
    private double originTreeX() {
        return centerX() + panX;
    }

    private double originTreeY() {
        return centerY() + panY;
    }

    private int worldX(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(n.x() * spacingX() - nodeW(n) / 2.0);
    }

    private int worldY(ResearchSyncPayload.NodeView n) {
        return (int) Math.round(n.y() * spacingY() - nodeH(n) / 2.0);
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
            g.drawString(font, Component.translatable("gui.overgrown_vegetation.research.empty_tier"),
                    ResearchUi.TREE_X0 + 16, centerY(), TEXT_DIM, false);
            return;
        }

        // ここから下はワールド座標。ズームは PoseStack が一様にかけるので、枠・アイコン・文字も
        // ツリー全体と同じ倍率で拡大縮小される。
        g.pose().pushPose();
        g.pose().translate(originTreeX(), originTreeY(), 0.0);
        g.pose().scale(zoom, zoom, 1.0F);

        for (ResearchSyncPayload.NodeView n : nodes) {
            int cx = worldX(n);
            int cy = worldY(n) + nodeH(n) / 2;
            boolean dashed = n.anyParent() && n.parents().size() > 1;
            for (String pid : n.parents()) {
                ResearchSyncPayload.NodeView p = nodeById(pid);
                if (p == null || p.tier() != activeTier) {
                    continue;
                }
                int ppx = worldX(p) + nodeW(p);
                int ppy = worldY(p) + nodeH(p) / 2;
                int color = ClientResearch.isUnlocked(pid) ? C_LINE_ON : dashed ? C_LINE_ANY : C_LINE;
                int midX = (ppx + cx) / 2;
                hline(g, ppx, midX, ppy, color, dashed);
                vline(g, midX, ppy, cy, color, dashed);
                hline(g, midX, cx, cy, color, dashed);
            }
        }

        renderExclusiveGroups(g, nodes);

        for (ResearchSyncPayload.NodeView n : nodes) {
            int x = worldX(n);
            int y = worldY(n);
            int nw = nodeW(n);
            int nh = nodeH(n);
            boolean unlocked = ClientResearch.isUnlocked(n.id());
            boolean available = ClientResearch.canUnlock(n);
            boolean excluded = !unlocked && ClientResearch.exclusiveConflict(n) != null;
            boolean pending = !unlocked && !available && !excluded && ClientResearch.parentsSatisfied(n);
            ResearchUi.Sprite frame = unlocked ? ResearchUi.NODE_UNLOCKED
                    : available ? ResearchUi.NODE_AVAILABLE
                    : excluded ? ResearchUi.NODE_EXCLUDED
                    : pending ? ResearchUi.NODE_PENDING : ResearchUi.NODE_LOCKED;
            blit(g, frame, x, y, nw, nh);
            if (n.id().equals(selectedNodeId)) {
                selectionOutline(g, x, y, nw, nh);
            }

            drawIcon(g, n, x + 6, y + Math.max(2, (nh - 16) / 2));
            g.drawString(font, trim(nodeName(n).getString(), nw - 28), x + 26,
                    y + Math.max(3, nh / 2 - 11), 0xFFFFFFFF, true);
            String sub = unlocked
                    ? Component.translatable("gui.overgrown_vegetation.research.done").getString()
                    : Component.translatable("gui.overgrown_vegetation.research.cost", n.cost()).getString();
            g.drawString(font, sub, x + 26, y + Math.max(3, nh / 2), unlocked ? 0xFFB9F6CA : 0xFFE0E0E0, false);
        }

        g.pose().popPose();
    }

    /** 選択中ノードの縁取り(枠テクスチャの上に 1px)。 */
    private static void selectionOutline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y, ACCENT);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, ACCENT);
        g.fill(x - 1, y - 1, x, y + h + 1, ACCENT);
        g.fill(x + w, y - 1, x + w + 1, y + h + 1, ACCENT);
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
        if (font.width(s) <= maxWidth) {
            return s;
        }
        while (s.length() > 1 && font.width(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
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
                int ax = worldX(a) + nodeW(a) / 2;
                int ay = worldY(a) + nodeH(a);
                int bx = worldX(b) + nodeW(b) / 2;
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

    private static void hline(GuiGraphics g, int x1, int x2, int y, int color, boolean dashed) {
        int a = Math.min(x1, x2);
        int b = Math.max(x1, x2);
        if (!dashed) {
            g.fill(a, y, b + 1, y + 1, color);
            return;
        }
        for (int x = a; x <= b; x += 6) {
            g.fill(x, y, Math.min(x + 3, b + 1), y + 1, color);
        }
    }

    private static void vline(GuiGraphics g, int x, int y1, int y2, int color, boolean dashed) {
        int a = Math.min(y1, y2);
        int b = Math.max(y1, y2);
        if (!dashed) {
            g.fill(x, a, x + 1, b + 1, color);
            return;
        }
        for (int y = a; y <= b; y += 6) {
            g.fill(x, y, x + 1, Math.min(y + 3, b + 1), color);
        }
    }

    /** キャンバス座標でのツリー領域判定。 */
    private static boolean inTree(double cx, double cy) {
        return cx >= ResearchUi.TREE_X0 && cx <= ResearchUi.TREE_X1
                && cy >= ResearchUi.TREE_Y0 && cy <= ResearchUi.TREE_Y1;
    }

    private ResearchSyncPayload.NodeView nodeAt(double cx, double cy) {
        for (ResearchSyncPayload.NodeView n : tierNodes()) {
            int x = nodeX(n);
            int y = nodeY(n);
            if (cx >= x && cx <= x + scaled(nodeW(n)) && cy >= y && cy <= y + scaled(nodeH(n))) {
                return n;
            }
        }
        return null;
    }

    private void setTier(int t) {
        activeTier = t;
        needsCenter = true;
    }

    /** 選択中の Tier のノード群がツリー領域に収まるよう、パン・ズームを自動調整する。 */
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
        int biggestW = 0;
        int biggestH = 0;
        for (ResearchSyncPayload.NodeView n : ns) {
            double wx = n.x() * spacingX();
            double wy = n.y() * spacingY();
            minX = Math.min(minX, wx);
            maxX = Math.max(maxX, wx);
            minY = Math.min(minY, wy);
            maxY = Math.max(maxY, wy);
            biggestW = Math.max(biggestW, nodeW(n));
            biggestH = Math.max(biggestH, nodeH(n));
        }
        double bw = (maxX - minX) + biggestW + 40;
        double bh = (maxY - minY) + biggestH + 40;
        double availW = Math.max(80, ResearchUi.TREE_X1 - ResearchUi.TREE_X0 - 20);
        double availH = Math.max(80, ResearchUi.TREE_Y1 - ResearchUi.TREE_Y0 - 20);
        zoom = (float) Math.max(0.25, Math.min(1.0, Math.min(availW / bw, availH / bh)));
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
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
     * 加工台名 → 材料の枠 → 矢印 → 成果物 の形で表示する。
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
        int arrowW = 24;
        int pad = 5;
        int headH = 18;
        int contentH = Math.max(gridH, slot);
        String stationName = layout.station().isEmpty() ? ""
                : trim(layout.station().getHoverName().getString(), 110);
        int contentW = Math.max(gridW + arrowW + slot, 18 + font.width(stationName));
        int panelW = contentW + pad * 2;
        boolean showIndex = recipes.size() > 1;
        int panelH = headH + contentH + pad * 2 + (showIndex ? 11 : 0);

        int nx = nodeX(n);
        int ny = nodeY(n);
        int px = Math.max(ResearchUi.TREE_X0, Math.min(nx, ResearchUi.TREE_X1 - panelW));
        int py = ny + scaled(nodeH(n)) + 6;
        if (py + panelH > ResearchUi.TREE_Y1) {
            py = ny - panelH - 6;
        }
        py = Math.max(ResearchUi.TREE_Y0, py);

        g.fill(px - 1, py - 1, px + panelW + 1, py + panelH + 1, PREVIEW_BORDER);
        g.fill(px, py, px + panelW, py + panelH, PREVIEW_BG);

        // 見出し: どの加工台で作るのか。
        if (!layout.station().isEmpty()) {
            g.renderFakeItem(layout.station(), px + pad, py + pad);
            g.drawString(font, stationName, px + pad + 18, py + pad + 4, TEXT_DIM, false);
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

        int arrowX = gx + gridW + 2;
        blit(g, ResearchUi.ARROW_RIGHT, arrowX, py + pad + headH + contentH / 2 - 7);

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
            g.drawString(font, idxTxt, px + (panelW - font.width(idxTxt)) / 2, py + panelH - pad - 9, TEXT_DIM, false);
        }
    }

    // ---------- 入力 ----------
    // バニラのスロット当たり判定は leftPos/topPos(= 0)を引くだけなので、マウス座標を
    // キャンバス座標に直してから super に渡せばそのまま正しく当たる。

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);

        if (button == 0) {
            if (in(cx, cy, ResearchUi.CLOSE_X, ResearchUi.CLOSE_Y, ResearchUi.CLOSE_W, ResearchUi.CLOSE_H)) {
                onClose();
                return true;
            }
            for (int t = 1; t <= ResearchUi.RAIL_TIERS; t++) {
                if (in(cx, cy, ResearchUi.RAIL_X, ResearchUi.railY(t - 1), ResearchUi.RAIL_W, ResearchUi.RAIL_H)) {
                    if (t != activeTier) {
                        setTier(t);
                    }
                    return true;
                }
            }
            int abx = ResearchUi.analyzeCenterX(menu.analyzeCount()) - ResearchUi.ANALYZE_BTN_W / 2;
            int aby = ResearchUi.analyzeButtonY(menu.analyzeCount());
            if (in(cx, cy, abx, aby, ResearchUi.ANALYZE_BTN_W, ResearchUi.ANALYZE_BTN_H)) {
                if (minecraft != null && minecraft.gameMode != null) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ResearchTableMenu.ANALYZE_BUTTON);
                }
                return true;
            }
            if (in(cx, cy, ResearchUi.PANEL_BTN_X, ResearchUi.PANEL_BTN_Y,
                    ResearchUi.PANEL_BTN_W, ResearchUi.PANEL_BTN_H)) {
                ResearchSyncPayload.NodeView sel = selectedNodeId != null ? nodeById(selectedNodeId) : null;
                if (sel != null && ClientResearch.canUnlock(sel)) {
                    PacketDistributor.sendToServer(new ResearchUnlockPayload(sel.id()));
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
        dragging = false;
        return super.mouseReleased(toCanvasX(mx), toCanvasY(my), button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        double cx = toCanvasX(mx);
        double cy = toCanvasY(my);
        if (inTree(cx, cy)) {
            ResearchSyncPayload.NodeView hovered = nodeAt(cx, cy);
            if (hovered != null) {
                List<RecipeHolder<?>> recipes = resolveRecipes(hovered);
                if (recipes.size() > 1) {
                    recipeIndex = Math.floorMod(recipeIndex + (dy > 0 ? 1 : -1), recipes.size());
                    return true;
                }
            }
            float old = zoom;
            zoom = (float) Math.max(0.25, Math.min(2.0, zoom * (dy > 0 ? 1.1 : 0.9)));
            double f = zoom / old - 1.0;
            panX -= (cx - (centerX() + panX)) * f;
            panY -= (cy - (centerY() + panY)) * f;
            return true;
        }
        return super.mouseScrolled(cx, cy, dx, dy);
    }
}
