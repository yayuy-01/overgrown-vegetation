package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mob の強さの詳細(2026-11-19 ユーザー決定「横に『詳細』ボタン → 1 種ずつ 4 項目をいじれる画面」)。
 *
 * <p>1 行 = 1 種: 名前と、体力・攻撃力・防御力・強靭の % の入力欄。<b>空欄 = 全体の % に従う</b>(薄い字で今の全体の % を出す)。
 * 全体の % を設定のページで動かすと、全員のその項目が全体の % に揃う(ここで入れた値は消える)。
 * 「完了」で設定のページの下書きへ写す(ワールドに書くのは設定のページの「完了」)。「キャンセル」で捨てる。
 */
public final class MobStrengthScreen extends Screen {

    private static final String KEY = WorldDifficultyScreen.KEY;
    private static final Component TITLE = Component.translatable(KEY + "mob_details.title");
    private static final int ROW_WIDTH = 360;
    private static final int ITEM_HEIGHT = 22;
    private static final int FIELD = 44;
    private static final int GAP = 4;

    private final WorldDifficultyScreen parent;
    /** 詳細の下書き(親の下書きの写し。「完了」で親へ戻す)。 */
    private final DifficultyValues draft;
    private final Set<String> invalid = new HashSet<>();
    /** init のたびに作り直す(前の一覧が割り付けに残らないように)。 */
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    @Nullable
    private Button doneButton;

    public MobStrengthScreen(WorldDifficultyScreen parent) {
        super(TITLE);
        this.parent = parent;
        this.draft = parent.draft.copy();
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 33);
        layout.addToHeader(new net.minecraft.client.gui.components.StringWidget(TITLE, font));
        MobList list = addRenderableWidget(new MobList(minecraft)); // 1.20.1: 一覧は割り付けに入れられない(LayoutElement でない)。位置は作るときと updateSize で
        GridLayout.RowHelper footer = layout.addToFooter(new GridLayout().columnSpacing(8)).createRowHelper(3); // 1.20.1: LinearLayout に spacing が無い
        Button clear = footer.addChild(Button.builder(Component.translatable(KEY + "mob_details.clear"), b -> {
            draft.clearMobOverrides();
            invalid.clear();
            rebuildWidgets();
        }).width(120).build());
        clear.active = parent.editable();
        if (parent.editable()) {
            doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> done()).build());
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).build());
        } else {
            footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
        }
        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
        list.updateSize(width, height, layout.getHeaderHeight(), height - layout.getFooterHeight());
        updateDone();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); // 1.20.1: Screen#render は背景を描かない(1.21 は描く)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void done() {
        if (!invalid.isEmpty()) return;
        parent.draft.copyFrom(draft);
        parent.refreshFromDraft();
        minecraft.setScreen(parent);
    }

    private void updateDone() {
        if (doneButton != null) doneButton.active = invalid.isEmpty();
    }

    private static Component nameOf(String id) {
        return EntityType.byString(id).map(t -> (Component) t.getDescription()).orElse(Component.literal(id));
    }

    private final class MobList extends ContainerObjectSelectionList<MobStrengthScreen.Row> {
        MobList(Minecraft minecraft) {
            super(minecraft, MobStrengthScreen.this.width, MobStrengthScreen.this.height, layout.getHeaderHeight(), MobStrengthScreen.this.height - layout.getFooterHeight(), ITEM_HEIGHT);
            addEntry(new HeaderRow());
            for (String id : parent.mobs()) addEntry(new MobRow(id));
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<MobStrengthScreen.Row> {
    }

    /** 列の名前(体力・攻撃力・防御力・強靭)と今の全体の %。 */
    private final class HeaderRow extends Row {
        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            g.drawString(font, Component.translatable(KEY + "mob_details.hint").withStyle(ChatFormatting.GRAY), left, top + 2,
                    WorldDifficultyScreen.NOTE_COLOR, false);
            int x = left + width - DifficultySetting.MOB_STATS.length * (FIELD + GAP) + GAP;
            for (DifficultySetting stat : DifficultySetting.MOB_STATS) {
                Component head = Component.translatable(KEY + stat.id() + ".short");
                g.drawCenteredString(font, head, x + FIELD / 2, top + 2, WorldDifficultyScreen.LABEL_COLOR);
                g.drawCenteredString(font, stat.format(draft.valueOr(stat, 1.0D)) + "%", x + FIELD / 2, top + 12,
                        WorldDifficultyScreen.NOTE_COLOR);
                x += FIELD + GAP;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class MobRow extends Row {
        private final String id;
        private final Component name;
        private final List<EditBox> boxes = new ArrayList<>();

        MobRow(String id) {
            this.id = id;
            this.name = nameOf(id);
            for (DifficultySetting stat : DifficultySetting.MOB_STATS) {
                EditBox box = new EditBox(font, 0, 0, FIELD, 18, Component.translatable(KEY + stat.id() + ".short"));
                box.setMaxLength(8);
                box.setFilter(t -> t.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.'));
                var own = draft.mobOverride(id, stat);
                box.setValue(own.isPresent() ? stat.format(own.getAsDouble()) : "");
                box.setHint(Component.literal(stat.format(draft.valueOr(stat, 1.0D))).withStyle(ChatFormatting.DARK_GRAY));
                box.setEditable(parent.editable());
                String key = id + "|" + stat.id();
                box.setResponder(text -> {
                    if (text.isEmpty()) {
                        invalid.remove(key);
                        draft.setMob(id, stat, Double.NaN);
                    } else {
                        double v = stat.parse(text);
                        if (Double.isNaN(v)) {
                            invalid.add(key);
                        } else {
                            invalid.remove(key);
                            draft.setMob(id, stat, v);
                        }
                    }
                    box.setTextColor(invalid.contains(key) ? WorldDifficultyScreen.INPUT_INVALID_COLOR : WorldDifficultyScreen.INPUT_COLOR);
                    updateDone();
                });
                boxes.add(box);
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            boolean changed = false;
            for (DifficultySetting stat : DifficultySetting.MOB_STATS) changed |= draft.mobOverride(id, stat).isPresent();
            int nameWidth = width - DifficultySetting.MOB_STATS.length * (FIELD + GAP) - GAP;
            g.drawString(font, font.substrByWidth(name, nameWidth).getString(), left, top + 5,
                    changed ? WorldDifficultyScreen.LABEL_CHANGED_COLOR : WorldDifficultyScreen.LABEL_COLOR, false);
            int x = left + width - boxes.size() * (FIELD + GAP) + GAP;
            for (EditBox box : boxes) {
                box.setPosition(x, top);
                box.render(g, mouseX, mouseY, partialTick);
                x += FIELD + GAP;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return boxes;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return boxes;
        }
    }
}
