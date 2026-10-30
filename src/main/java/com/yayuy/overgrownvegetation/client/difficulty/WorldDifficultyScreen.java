package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.network.WorldDifficultyApplyPayload;
import com.yayuy.overgrownvegetation.network.WorldDifficultySyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Overgrown Vegetation の難易度の画面(2026-09-25、ユーザー指定)。ワールド作成画面の「難易度」の右隣と、
 * ポーズ画面の「設定」の左隣の葉っぱのボタン({@link LeafIconButton})から開く。
 *
 * <p>見た目と作法はバニラのゲームルールの画面に合わせた: 項目ごとに名前・数値の入力欄・「リセット」、
 * 範囲外や数字でない入力は赤字になり「完了」が押せない、名前にカーソルを乗せると説明・既定値・範囲が出る。
 * 既定値と違う値になっている項目は名前が黄色。
 *
 * <p><b>結果は「このワールドの値」だけ</b>: 触っていない項目は元のまま(値があれば値、無ければ既定値に従う)、
 * 既定値を打ち直した・リセットした項目は値を消す({@link com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData})。
 */
public final class WorldDifficultyScreen extends Screen {

    private static final Component TITLE = Component.translatable("gui.overgrown_vegetation.world_difficulty.title");
    private static final String KEY = "gui.overgrown_vegetation.world_difficulty.";

    private static final int ROW_WIDTH = 310;
    private static final int ITEM_HEIGHT = 24;
    private static final int INPUT_WIDTH = 64;
    private static final int RESET_WIDTH = 50;
    private static final int GAP = 4;
    private static final int TOOLTIP_WIDTH = 220;
    /** 下の段の高さ(完了・キャンセルの段 + 演出の詳細設定の幅広のボタン)と、そのボタンの幅(完了・キャンセルの 2 つ分)。 */
    private static final int FOOTER_HEIGHT = 58;
    private static final int VISUAL_BUTTON_WIDTH = 308;

    private static final int LABEL_COLOR = 0xFFFFFF;
    private static final int LABEL_CHANGED_COLOR = 0xFFFF55;
    private static final int INPUT_COLOR = 0xE0E0E0;
    private static final int INPUT_INVALID_COLOR = 0xFF5555;
    private static final int NOTE_COLOR = 0xA0A0A0;

    /** 1 項目の状態。画面の作り直し(窓の大きさを変えたとき)でも入力が消えないよう、ウィジェットとは別に持つ。 */
    private static final class Row {
        final DifficultySetting setting;
        final double defaultValue;
        /** 開いたときのワールドの値(無ければ NaN)。 */
        final double initialOverride;
        String text;
        boolean edited;

        Row(DifficultySetting setting, double defaultValue, double initialOverride) {
            this.setting = setting;
            this.defaultValue = defaultValue;
            this.initialOverride = initialOverride;
            this.text = setting.format(Double.isNaN(initialOverride) ? defaultValue : initialOverride);
        }

        boolean valid() {
            return !edited || !Double.isNaN(setting.parse(text));
        }

        /** 「完了」したときのワールドの値(無ければ NaN)。{@link #valid()} のときだけ意味がある。 */
        double resultOverride() {
            if (!edited) {
                return initialOverride;
            }
            double value = setting.parse(text);
            return Double.isNaN(value) || setting.same(value, defaultValue) ? Double.NaN : value;
        }
    }

    private final Screen parent;
    private final boolean editable;
    private final Component note;
    private final Consumer<Map<DifficultySetting, Double>> onDone;
    private final EnumMap<DifficultySetting, Row> rows = new EnumMap<>(DifficultySetting.class);
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    @Nullable
    private SettingList list;
    @Nullable
    private Button doneButton;

    private WorldDifficultyScreen(Screen parent, boolean editable, Component note, Map<DifficultySetting, Double> defaults,
                                  Map<DifficultySetting, Double> overrides,
                                  Consumer<Map<DifficultySetting, Double>> onDone) {
        super(TITLE);
        this.parent = parent;
        this.editable = editable;
        this.note = note;
        this.onDone = onDone;
        for (DifficultySetting setting : DifficultySetting.values()) {
            double defaultValue = defaults.getOrDefault(setting, setting.defaultValue());
            Double override = overrides.get(setting);
            rows.put(setting, new Row(setting, defaultValue, override != null ? override : Double.NaN));
        }
    }

    /**
     * ワールド作成画面から。既定値は手元の config・design(このあと起動する統合サーバーと同じもの)。
     * 「完了」で {@code draft}(作成画面が持っている表)を書き換える。
     */
    public static WorldDifficultyScreen forCreateWorld(Screen parent, Map<DifficultySetting, Double> draft) {
        EnumMap<DifficultySetting, Double> defaults = new EnumMap<>(DifficultySetting.class);
        for (DifficultySetting setting : DifficultySetting.values()) {
            defaults.put(setting, setting.defaultValue());
        }
        return new WorldDifficultyScreen(parent, true, Component.translatable(KEY + "note"), defaults, draft,
                result -> {
                    draft.clear();
                    draft.putAll(result);
                });
    }

    /** ポーズ画面から(サーバーの返事 {@link WorldDifficultySyncPayload} を受けて開く)。「完了」でサーバーへ送る。 */
    public static WorldDifficultyScreen forServer(Screen parent, WorldDifficultySyncPayload payload) {
        EnumMap<DifficultySetting, Double> defaults = new EnumMap<>(DifficultySetting.class);
        EnumMap<DifficultySetting, Double> overrides = new EnumMap<>(DifficultySetting.class);
        for (WorldDifficultySyncPayload.Entry entry : payload.entries()) {
            DifficultySetting setting = DifficultySetting.byId(entry.id());
            if (setting == null) {
                continue;
            }
            defaults.put(setting, entry.defaultValue());
            if (entry.hasOverride()) {
                overrides.put(setting, entry.override());
            }
        }
        Component note = Component.translatable(KEY + (payload.canEdit() ? "note" : "note_read_only"));
        return new WorldDifficultyScreen(parent, payload.canEdit(), note, defaults, overrides, result -> {
            List<WorldDifficultyApplyPayload.Entry> entries = new ArrayList<>();
            result.forEach((setting, value) -> entries.add(new WorldDifficultyApplyPayload.Entry(setting.id(), value)));
            PacketDistributor.sendToServer(new WorldDifficultyApplyPayload(entries));
        });
    }

    @Override
    protected void init() {
        layout.addTitleHeader(TITLE, font);
        list = layout.addToContents(new SettingList(minecraft));
        // 一番下に、スペースキーのように幅の広い「演出の詳細設定」のボタン(2026-10-22 ユーザー指定)。完了・キャンセルの段の下に置く。
        layout.setFooterHeight(FOOTER_HEIGHT);
        LinearLayout footerRows = layout.addToFooter(LinearLayout.vertical().spacing(4));
        LinearLayout footer = footerRows.addChild(LinearLayout.horizontal().spacing(8));
        footerRows.addChild(Button.builder(Component.translatable(KEY + "visual_settings"),
                        button -> minecraft.setScreen(new VisualSettingsScreen(this)))
                .width(VISUAL_BUTTON_WIDTH)
                .build());
        if (editable) {
            doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> done()).build());
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).build());
        } else {
            doneButton = null;
            footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());
        }
        layout.visitWidgets(widget -> addRenderableWidget(widget));
        repositionElements();
        updateDoneButton();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void done() {
        if (!rows.values().stream().allMatch(Row::valid)) {
            return;
        }
        if (rows.values().stream().anyMatch(row -> row.edited)) {
            EnumMap<DifficultySetting, Double> result = new EnumMap<>(DifficultySetting.class);
            for (Row row : rows.values()) {
                double value = row.resultOverride();
                if (!Double.isNaN(value)) {
                    result.put(row.setting, value);
                }
            }
            onDone.accept(result);
        }
        minecraft.setScreen(parent);
    }

    private void updateDoneButton() {
        if (doneButton != null) {
            doneButton.active = rows.values().stream().allMatch(Row::valid);
        }
    }

    // ------------------------------------------------------------------ 一覧

    // 一覧の中では単純名 Entry が継承した ContainerObjectSelectionList.Entry を指すので、この画面の Entry は必ず修飾して書く。
    private final class SettingList extends ContainerObjectSelectionList<WorldDifficultyScreen.Entry> {

        SettingList(Minecraft minecraft) {
            super(minecraft, WorldDifficultyScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(),
                    ITEM_HEIGHT);
            addEntry(new NoteEntry());
            for (Row row : rows.values()) {
                addEntry(new SettingEntry(row));
            }
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            WorldDifficultyScreen.Entry hovered = getHovered();
            if (hovered != null && hovered.tooltip() != null) {
                setTooltipForNextRenderPass(hovered.tooltip());
            }
        }
    }

    private abstract static class Entry extends ContainerObjectSelectionList.Entry<WorldDifficultyScreen.Entry> {
        @Nullable
        List<FormattedCharSequence> tooltip() {
            return null;
        }
    }

    /** いちばん上の 1 行: 「このワールドだけに効く」/「見るだけ」。 */
    private final class NoteEntry extends Entry {
        private final List<FormattedCharSequence> lines = font.split(note, ROW_WIDTH);

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int shown = Math.min(2, lines.size());
            int y = top + (height - 4 - shown * 10) / 2 + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawCenteredString(font, lines.get(i), left + width / 2, y + i * 10,
                        editable ? NOTE_COLOR : LABEL_CHANGED_COLOR);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(new NarratableEntry() {
                @Override
                public NarratableEntry.NarrationPriority narrationPriority() {
                    return NarratableEntry.NarrationPriority.HOVERED;
                }

                @Override
                public void updateNarration(NarrationElementOutput output) {
                    output.add(NarratedElementType.TITLE, note);
                }
            });
        }
    }

    /** 1 項目: 名前・入力欄(オン・オフの項目はボタン)・リセット。 */
    private final class SettingEntry extends Entry {
        private final Row row;
        private final List<FormattedCharSequence> label;
        private final List<FormattedCharSequence> tooltip;
        /** 数値の入力欄(オン・オフの項目では null)。 */
        @Nullable
        private final EditBox input;
        /** オン・オフの項目のボタン(押すたびに切り替わる。数値の項目では null)。 */
        @Nullable
        private final Button toggle;
        private final AbstractWidget field;
        private final Button reset;
        private final List<AbstractWidget> children;

        SettingEntry(Row row) {
            this.row = row;
            Component name = Component.translatable(row.setting.translationKey());
            this.label = font.split(name, ROW_WIDTH - INPUT_WIDTH - RESET_WIDTH - GAP * 3);
            this.tooltip = buildTooltip(row);

            if (row.setting.kind() == DifficultySetting.Kind.TOGGLE) {
                this.input = null;
                this.toggle = Button.builder(CommonComponents.EMPTY, button -> {
                            double value = row.setting.parse(row.text);
                            setText(row.setting.format(value >= 0.5D ? 0.0D : 1.0D));
                        })
                        .size(INPUT_WIDTH, 20)
                        .build();
                toggle.active = editable;
                this.field = toggle;
            } else {
                this.toggle = null;
                EditBox box = new EditBox(font, 0, 0, INPUT_WIDTH, 20, name);
                box.setMaxLength(12);
                boolean integer = row.setting.kind() == DifficultySetting.Kind.COUNT;
                box.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || (!integer && c == '.')));
                box.setValue(row.text); // 応答を付ける前に入れる(開いただけで「触った」にしない)
                box.setEditable(editable);
                box.setResponder(text -> {
                    row.text = text;
                    row.edited = true;
                    refresh();
                });
                this.input = box;
                this.field = box;
            }

            this.reset = Button.builder(Component.translatable(KEY + "reset"),
                            button -> setText(row.setting.format(row.defaultValue)))
                    .size(RESET_WIDTH, 20)
                    .build();
            this.children = List.of(field, reset);
            refresh();
        }

        /** 値を書き換える(入力欄なら欄の中身、ボタンなら表示)。 */
        private void setText(String text) {
            if (input != null) {
                input.setValue(text); // 応答が row を書き換える
                return;
            }
            row.text = text;
            row.edited = true;
            refresh();
        }

        private void refresh() {
            double value = row.setting.parse(row.text);
            boolean ok = !Double.isNaN(value);
            if (input != null) {
                input.setTextColor(ok || !row.edited ? INPUT_COLOR : INPUT_INVALID_COLOR);
            }
            if (toggle != null && ok) {
                toggle.setMessage(row.setting.display(value));
            }
            reset.active = editable && (!ok || !row.setting.same(value, row.defaultValue));
            updateDoneButton();
        }

        @Override
        List<FormattedCharSequence> tooltip() {
            return tooltip;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            boolean changed = row.valid() && !Double.isNaN(row.resultOverride());
            int color = changed ? LABEL_CHANGED_COLOR : LABEL_COLOR;
            int shown = Math.min(2, label.size());
            int y = shown == 1 ? top + 6 : top + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawString(font, label.get(i), left, y + i * 10, color, false);
            }
            int right = left + width;
            reset.setPosition(right - RESET_WIDTH, top);
            field.setPosition(right - RESET_WIDTH - GAP - INPUT_WIDTH, top);
            field.render(graphics, mouseX, mouseY, partialTick);
            reset.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return children;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return children;
        }
    }

    /** 説明・既定値・範囲(オン・オフの項目は範囲を出さない)。実績の数は 2026-10-10 から自分の項目になった。 */
    private List<FormattedCharSequence> buildTooltip(Row row) {
        DifficultySetting setting = row.setting;
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(
                Component.translatable(setting.descriptionKey()), TOOLTIP_WIDTH));
        lines.add(Component.translatable(KEY + "default", setting.display(row.defaultValue))
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        if (setting.kind() != DifficultySetting.Kind.TOGGLE) {
            lines.add(Component.translatable(KEY + "range", setting.format(setting.min()), setting.format(setting.max()))
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }
}
