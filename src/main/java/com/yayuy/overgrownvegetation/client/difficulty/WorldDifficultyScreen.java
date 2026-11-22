package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import com.yayuy.overgrownvegetation.difficulty.ModDifficulty;
import com.yayuy.overgrownvegetation.difficulty.MobStrength;
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
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MOD の設定のページ(2026-11-19 ユーザー決定「設定のページを 1 つにまとめ、ワールドを作るときにも遊んでいる途中にも開いて変えられる」)。
 * 前の難易度の画面(2026-09-25)を広げた物。入口は {@link ClientWorldDifficulty}。
 *
 * <p>上から: 注意書き・<b>MOD の難易度</b>(押すたびに Easy → Normal → Hard → Nightmare。今の値がどの段とも違えば「カスタム」)・
 * 節ごとの項目(名前・入力欄・リセット)。Mob の節には「Mob ごとの詳細…」({@link MobStrengthScreen})。
 * 作法はバニラのゲームルールの画面に合わせた: 範囲外や数字でない入力は赤字になり「完了」が押せない、名前にカーソルを乗せると
 * 説明・既定値・範囲が出る、既定値と違う項目は名前が黄色。※ の項目は途中で変えても、すでに起きたことは戻らない。
 *
 * <p>値は下書き({@link DifficultyValues})に持ち、「完了」で作成画面の下書きへ写すか、サーバーへ送る。
 */
public final class WorldDifficultyScreen extends Screen {

    static final String KEY = "gui.overgrown_vegetation.world_difficulty.";
    private static final Component TITLE = Component.translatable(KEY + "title");

    private static final int ROW_WIDTH = 330;
    private static final int ITEM_HEIGHT = 24;
    private static final int INPUT_WIDTH = 64;
    private static final int RESET_WIDTH = 50;
    private static final int GAP = 4;
    private static final int TOOLTIP_WIDTH = 220;
    private static final int FOOTER_HEIGHT = 33;

    static final int LABEL_COLOR = 0xFFFFFF;
    static final int LABEL_CHANGED_COLOR = 0xFFFF55;
    static final int INPUT_COLOR = 0xE0E0E0;
    static final int INPUT_INVALID_COLOR = 0xFF5555;
    static final int NOTE_COLOR = 0xA0A0A0;
    private static final int HEADER_COLOR = 0x9FE07A;
    private static final int WARN_COLOR = 0xFFAA55;

    private final Screen parent;
    private final boolean editable;
    private final Component note;
    private final Map<DifficultySetting, Double> defaults;
    private final List<String> mobs;
    private final DifficultyValues initial;
    /** 画面の中の下書き(入力が正しい項目だけが入る)。 */
    final DifficultyValues draft;
    private final Consumer<DifficultyValues> onDone;
    /** 入力欄の文字(作り直し = 窓の大きさを変えた・段を選んだときにも消えないよう、ウィジェットとは別に持つ)。 */
    private final EnumMap<DifficultySetting, String> texts = new EnumMap<>(DifficultySetting.class);
    private final EnumSet<DifficultySetting> invalid = EnumSet.noneOf(DifficultySetting.class);
    /** init のたびに作り直す(作り直しで前の一覧が割り付けに残り、重ねて描かれるのを防ぐ)。 */
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT);
    @Nullable
    private SettingList list;
    @Nullable
    private Button doneButton;
    private double restoreScroll = -1.0D;

    private WorldDifficultyScreen(Screen parent, boolean editable, Component note, Map<DifficultySetting, Double> defaults,
                                  DifficultyValues values, List<String> mobs, Consumer<DifficultyValues> onDone) {
        super(TITLE);
        this.parent = parent;
        this.editable = editable;
        this.note = note;
        this.defaults = defaults;
        this.mobs = mobs;
        this.initial = values.copy();
        this.draft = values.copy();
        this.onDone = onDone;
        for (DifficultySetting s : DifficultySetting.values()) {
            texts.put(s, s.format(current(s)));
        }
    }

    /**
     * ワールド作成画面から。既定値と Mob の一覧は手元の config・design(このあと起動する統合サーバーと同じもの)。
     * 「完了」で {@code draft}(作成画面が持っている下書き)を書き換える。
     */
    public static WorldDifficultyScreen forCreateWorld(Screen parent, DifficultyValues draft) {
        EnumMap<DifficultySetting, Double> defaults = new EnumMap<>(DifficultySetting.class);
        for (DifficultySetting setting : DifficultySetting.values()) {
            defaults.put(setting, setting.defaultValue());
        }
        return new WorldDifficultyScreen(parent, true, Component.translatable(KEY + "note_create"), defaults, draft,
                MobStrength.roster(), draft::copyFrom);
    }

    /** 遊んでいる途中(サーバーの返事 {@link WorldDifficultySyncPayload} を受けて開く)。「完了」でサーバーへ送る。 */
    public static WorldDifficultyScreen forServer(Screen parent, WorldDifficultySyncPayload payload) {
        Component note = Component.translatable(KEY + (payload.canEdit() ? "note" : "note_read_only"));
        return new WorldDifficultyScreen(parent, payload.canEdit(), note, payload.defaults(), payload.values(), payload.mobs(),
                result -> PacketDistributor.sendToServer(new WorldDifficultyApplyPayload(result)));
    }

    double defaultOf(DifficultySetting s) {
        return defaults.getOrDefault(s, s.defaultValue());
    }

    /** 下書きでのこの項目の値(無ければ既定値)。 */
    double current(DifficultySetting s) {
        return draft.valueOr(s, defaultOf(s));
    }

    boolean editable() {
        return editable;
    }

    List<String> mobs() {
        return mobs;
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, FOOTER_HEIGHT);
        layout.addTitleHeader(TITLE, font);
        list = layout.addToContents(new SettingList(minecraft));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("gui.overgrown_vegetation.gameplay_settings.visuals"),
                button -> minecraft.setScreen(new VisualSettingsScreen(this))).width(100).build());
        if (editable) {
            doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> done()).build());
            footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).build());
        } else {
            doneButton = null;
            footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).build());
        }
        layout.visitWidgets(widget -> addRenderableWidget(widget));
        repositionElements();
        if (restoreScroll >= 0.0D && list != null) {
            list.setScrollAmount(restoreScroll);
            restoreScroll = -1.0D;
        }
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

    /** 段を選ぶ・詳細から戻ったとき: 下書きから入力欄を作り直す(スクロールは保つ)。 */
    void refreshFromDraft() {
        for (DifficultySetting s : DifficultySetting.values()) {
            if (!invalid.contains(s)) texts.put(s, s.format(current(s)));
        }
        if (list != null) restoreScroll = list.getScrollAmount();
        rebuildWidgets();
    }

    private void done() {
        if (!invalid.isEmpty()) {
            return;
        }
        if (!draft.equals(initial)) {
            onDone.accept(draft.copy());
        }
        minecraft.setScreen(parent);
    }

    private void updateDoneButton() {
        if (doneButton != null) {
            doneButton.active = invalid.isEmpty();
        }
    }

    /** 入力が変わった。正しければ下書きへ(既定値と同じなら値を消す)。Mob の強さの全体の % は詳細のその項目も消す。 */
    private void edited(DifficultySetting s, String text) {
        texts.put(s, text);
        double value = s.parse(text);
        if (Double.isNaN(value)) {
            invalid.add(s);
        } else {
            invalid.remove(s);
            double stored = s.same(value, defaultOf(s)) ? Double.NaN : value;
            if (s.mobStat()) draft.setGlobalMobStat(s, stored);
            else draft.set(s, stored);
        }
        updateDoneButton();
    }

    // ------------------------------------------------------------------ 一覧

    // 一覧の中では単純名 Entry が継承した ContainerObjectSelectionList.Entry を指すので、この画面の Entry は必ず修飾して書く。
    private final class SettingList extends ContainerObjectSelectionList<WorldDifficultyScreen.Entry> {

        SettingList(Minecraft minecraft) {
            super(minecraft, WorldDifficultyScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(),
                    ITEM_HEIGHT);
            addEntry(new TextEntry(note, editable ? NOTE_COLOR : LABEL_CHANGED_COLOR, true));
            addEntry(new TierEntry());
            for (DifficultySetting.Section section : DifficultySetting.Section.values()) {
                boolean any = false;
                boolean irreversible = false;
                for (DifficultySetting s : DifficultySetting.values()) {
                    if (s.section() != section) continue;
                    if (!any) {
                        addEntry(new TextEntry(Component.translatable(section.translationKey()).withStyle(ChatFormatting.BOLD),
                                HEADER_COLOR, false));
                        any = true;
                    }
                    addEntry(new SettingEntry(s));
                    irreversible |= s.irreversible();
                }
                if (section == DifficultySetting.Section.MOBS && any) {
                    addEntry(new DetailEntry());
                }
                if (irreversible) {
                    addEntry(new TextEntry(Component.translatable(KEY + "irreversible_note"), WARN_COLOR, true));
                }
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

    /** 文字だけの 1 行(注意書き・節の見出し)。 */
    private final class TextEntry extends Entry {
        private final Component text;
        private final List<FormattedCharSequence> lines;
        private final int color;
        private final boolean centered;

        TextEntry(Component text, int color, boolean centered) {
            this.text = text;
            this.lines = font.split(text, ROW_WIDTH);
            this.color = color;
            this.centered = centered;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int shown = Math.min(2, lines.size());
            int y = top + (height - 4 - shown * 10) / 2 + 1;
            for (int i = 0; i < shown; i++) {
                if (centered) graphics.drawCenteredString(font, lines.get(i), left + width / 2, y + i * 10, color);
                else graphics.drawString(font, lines.get(i), left, y + i * 10 + 2, color, false);
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
                    output.add(NarratedElementType.TITLE, text);
                }
            });
        }
    }

    /** MOD の難易度: 押すたびに次の段へ。今の値がどの段とも違えばカスタムと出る。 */
    private final class TierEntry extends Entry {
        private final Button button;
        private final List<FormattedCharSequence> tooltip;

        TierEntry() {
            ModDifficulty tier = draft.tier();
            button = Button.builder(Component.translatable(KEY + "tier.button", tier.displayName()), b -> {
                draft.applyTier(draft.tier().next());
                invalid.removeIf(DifficultySetting::tiered);
                refreshFromDraft();
            }).size(ROW_WIDTH, 20).build();
            button.active = editable;
            tooltip = font.split(Component.translatable(KEY + "tier.desc"), TOOLTIP_WIDTH);
        }

        @Override
        List<FormattedCharSequence> tooltip() {
            return tooltip;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            button.setMessage(Component.translatable(KEY + "tier.button", draft.tier().displayName()));
            button.setPosition(left, top);
            button.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** Mob の節の下: 「Mob ごとの詳細…」。 */
    private final class DetailEntry extends Entry {
        private final Button button;

        DetailEntry() {
            button = Button.builder(Component.translatable(KEY + "mob_details"),
                    b -> minecraft.setScreen(new MobStrengthScreen(WorldDifficultyScreen.this))).size(ROW_WIDTH, 20).build();
            button.active = !mobs.isEmpty();
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            button.setPosition(left, top);
            button.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** 1 項目: 名前・入力欄(オン・オフの項目はボタン)・リセット。 */
    private final class SettingEntry extends Entry {
        private final DifficultySetting setting;
        private final List<FormattedCharSequence> label;
        private final List<FormattedCharSequence> tooltip;
        @Nullable
        private final EditBox input;
        @Nullable
        private final Button toggle;
        private final AbstractWidget field;
        private final Button reset;
        private final List<AbstractWidget> children;

        SettingEntry(DifficultySetting setting) {
            this.setting = setting;
            Component name = Component.translatable(setting.translationKey());
            if (setting.irreversible()) name = name.copy().append(" ※");
            this.label = font.split(name, ROW_WIDTH - INPUT_WIDTH - RESET_WIDTH - GAP * 3);
            this.tooltip = buildTooltip(setting);

            if (setting.kind() == DifficultySetting.Kind.TOGGLE) {
                this.input = null;
                this.toggle = Button.builder(CommonComponents.EMPTY, button -> {
                            double value = setting.parse(texts.get(setting));
                            setText(setting.format(value >= 0.5D ? 0.0D : 1.0D));
                        })
                        .size(INPUT_WIDTH, 20)
                        .build();
                toggle.active = editable;
                this.field = toggle;
            } else {
                this.toggle = null;
                EditBox box = new EditBox(font, 0, 0, INPUT_WIDTH, 20, name);
                box.setMaxLength(12);
                boolean integer = setting.kind() == DifficultySetting.Kind.COUNT;
                box.setFilter(text -> text.chars().allMatch(c -> (c >= '0' && c <= '9') || (!integer && c == '.')));
                box.setValue(texts.get(setting)); // 応答を付ける前に入れる(開いただけで「触った」にしない)
                box.setEditable(editable);
                box.setResponder(text -> {
                    edited(setting, text);
                    refresh();
                });
                this.input = box;
                this.field = box;
            }

            this.reset = Button.builder(Component.translatable(KEY + "reset"),
                            button -> setText(setting.format(defaultOf(setting))))
                    .size(RESET_WIDTH, 20)
                    .build();
            this.children = List.of(field, reset);
            refresh();
        }

        private void setText(String text) {
            if (input != null) {
                input.setValue(text); // 応答が下書きを書き換える
                return;
            }
            edited(setting, text);
            refresh();
        }

        private void refresh() {
            double value = setting.parse(texts.get(setting));
            boolean ok = !Double.isNaN(value);
            if (input != null) {
                input.setTextColor(ok ? INPUT_COLOR : INPUT_INVALID_COLOR);
            }
            if (toggle != null && ok) {
                toggle.setMessage(setting.display(value));
            }
            reset.active = editable && (!ok || !setting.same(value, defaultOf(setting)));
        }

        @Override
        List<FormattedCharSequence> tooltip() {
            return tooltip;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            boolean changed = draft.override(setting).isPresent();
            int color = changed ? LABEL_CHANGED_COLOR : LABEL_COLOR;
            int shown = Math.min(2, label.size());
            boolean details = setting.mobStat() && draft.hasMobOverrides(setting);
            int y = shown == 1 && !details ? top + 6 : top + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawString(font, label.get(i), left, y + i * 10, color, false);
            }
            if (details && shown == 1) {
                graphics.drawString(font, Component.translatable(KEY + "mob_details_some"), left, y + 10, NOTE_COLOR, false);
            }
            int right = left + width;
            reset.setPosition(right - RESET_WIDTH, top);
            field.setPosition(right - RESET_WIDTH - GAP - INPUT_WIDTH, top);
            field.render(graphics, mouseX, mouseY, partialTick);
            reset.render(graphics, mouseX, mouseY, partialTick);
            if (setting.kind() == DifficultySetting.Kind.PERCENT && input != null) {
                graphics.drawString(font, "%", field.getX() + INPUT_WIDTH - 9, top + 6, NOTE_COLOR, false);
            }
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

    /** 説明・既定値・範囲(オン・オフの項目は範囲を出さない)・段で変わるか・戻らないか。 */
    private List<FormattedCharSequence> buildTooltip(DifficultySetting setting) {
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(
                Component.translatable(setting.descriptionKey()), TOOLTIP_WIDTH));
        lines.add(Component.translatable(KEY + "default", setting.display(defaultOf(setting)))
                .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        if (setting.kind() != DifficultySetting.Kind.TOGGLE) {
            String unit = setting.kind() == DifficultySetting.Kind.PERCENT ? "%" : "";
            lines.add(Component.translatable(KEY + "range", setting.format(setting.min()) + unit, setting.format(setting.max()) + unit)
                    .withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        if (setting.tiered()) {
            lines.addAll(font.split(Component.translatable(KEY + "tiered_note").withStyle(ChatFormatting.DARK_GREEN), TOOLTIP_WIDTH));
        }
        if (setting.mobStat()) {
            lines.addAll(font.split(Component.translatable(KEY + "mob_global_note").withStyle(ChatFormatting.GRAY), TOOLTIP_WIDTH));
        }
        if (setting.irreversible()) {
            lines.addAll(font.split(Component.translatable(KEY + "irreversible_note").withStyle(ChatFormatting.GOLD), TOOLTIP_WIDTH));
        }
        return lines;
    }
}
