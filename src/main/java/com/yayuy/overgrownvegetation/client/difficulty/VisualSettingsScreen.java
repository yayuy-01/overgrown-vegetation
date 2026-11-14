package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.config.AtmosphereQuality;
import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import com.yayuy.overgrownvegetation.config.QualityPreset;
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
import net.neoforged.neoforge.common.ModConfigSpec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 演出(シェーダー)の詳細設定の画面(2026-10-22、ユーザー指定)。前は難易度の画面({@code WorldDifficultyScreen}、退避中)の一番下の幅広のボタンから開いた。
 * 2026-11-01 に parked から戻し、入口はバニラの「設定…」画面のボタン({@link VisualSettingsEntry})と Mods 画面の「設定」
 * ({@code client/OvergrownVegetationClientMod})になった。演出 1 つずつのオンオフ(環境音・光・霧・粒・水中)もここ。
 *
 * <p>作法は難易度の画面と同じ(バニラのゲームルール風: 名前・入力欄かボタン・「リセット」、名前に乗せると説明・既定値・範囲、
 * 既定値と違う項目は名前が黄色、数字でない・範囲外は赤字で「完了」が押せない)。違いは<b>値がこの PC の設定</b>
 * ({@link OvergrownClientConfig}、{@code config/overgrown_vegetation-client.toml})で、ワールドに関係しないこと。「完了」で書き込み、「キャンセル」で捨てる。
 *
 * <p><b>項目を足すとき</b>: {@link OvergrownClientConfig} に 1 つ足し、{@link #settings()} に 1 行({@link Toggle}・{@link Percent}・{@link Choice})、
 * 言語ファイル 3 つに {@code gui.overgrown_vegetation.visual_settings.<id>} と {@code .desc} を足す。見出しの下に入れるなら {@code .section(…)}。
 * 重さに効く項目なら {@code .preset(p -> p.…)} を付けて {@link QualityPreset} に値を足す(高 = 既定値にすること)。
 *
 * <p><b>品質の段階</b>(2026-10-23、ユーザー決定: 手動で選ぶだけ): 一番上の行 {@link PresetEntry}。段階は保存せず、手元の値がどの段階と全部一致するかで
 * 「低・中・高・カスタム」を出す。
 */
public final class VisualSettingsScreen extends Screen {

    private static final Component TITLE = Component.translatable("gui.overgrown_vegetation.visual_settings.title");
    private static final String KEY = "gui.overgrown_vegetation.visual_settings.";
    /** リセット・既定値・範囲の文は難易度の画面のものを使う。 */
    private static final String COMMON_KEY = "gui.overgrown_vegetation.world_difficulty.";

    private static final int ROW_WIDTH = 310;
    private static final int ITEM_HEIGHT = 24;
    private static final int INPUT_WIDTH = 64;
    private static final int RESET_WIDTH = 50;
    private static final int GAP = 4;
    private static final int TOOLTIP_WIDTH = 220;

    private static final int LABEL_COLOR = 0xFFFFFF;
    private static final int LABEL_CHANGED_COLOR = 0xFFFF55;
    private static final int INPUT_COLOR = 0xE0E0E0;
    private static final int INPUT_INVALID_COLOR = 0xFF5555;
    private static final int NOTE_COLOR = 0xA0A0A0;

    /**
     * 画面に並べる項目(上から)。{@code .section(…)} を付けた項目の前に見出しの行({@code <KEY>section.<名前>})が入る。
     * 2026-11-01: 演出 1 つずつのオンオフを足した(ユーザー指定「環境音とシェーダー(光などの演出)をオンオフ」。分け方は仮決め)。
     * 植物のミップマップ・下草の距離は読み手が退避中なので出さない(設定ファイルには残る)。
     */
    private static List<Setting> settings() {
        OvergrownClientConfig.Client c = OvergrownClientConfig.CLIENT;
        // .preset(…) を付けた項目は品質の段階(一番上の行)で一式変わる。演出のオンオフは好みなので段階に入れない(仮決め)。
        Toggle atmosphere = new Toggle("atmosphere_enabled", c.atmosphereEnabled);
        Toggle fog = new Toggle("fog", c.fog, atmosphere);
        return List.of(
                atmosphere.section("general"),
                new Choice<>("atmosphere_quality", c.atmosphereQuality, AtmosphereQuality.values()).preset(p -> p.atmosphere),
                new Choice<>("curled_fern", c.curledFernDetail, CurledFernDetail.values()).preset(p -> p.curledFern),
                new Toggle("effects_with_shader_pack", c.effectsWithShaderPack),
                new Toggle("ambient_insects", c.ambientInsects, atmosphere).section("sound"),
                new Toggle("ambient_wind", c.ambientWind, atmosphere),
                new Toggle("ambient_cave", c.ambientCave, atmosphere),
                new Toggle("ambient_underwater", c.ambientUnderwater, atmosphere),
                new Toggle("light_shafts", c.lightShafts, atmosphere).section("light"),
                new Toggle("canopy_dapple", c.canopyDapple, atmosphere),
                new Toggle("canopy_light", c.canopyLight, atmosphere),
                fog.section("fog"),
                new Toggle("fog_detail", c.fogDetail, fog),
                new Toggle("motes", c.motes, atmosphere).section("motion"),
                new Toggle("critters", c.critters, atmosphere),
                new Toggle("leaf_sway", c.leafSway, atmosphere),
                new Toggle("water_life", c.waterLife, atmosphere),
                new Toggle("underwater", c.underwater, atmosphere).section("underwater"),
                new Toggle("cave_light", c.caveLight, atmosphere).section("cave"),
                new Toggle("glow_bloom", c.glowBloom, atmosphere),
                new Toggle("surface_bump", c.surfaceBump, atmosphere),
                new Toggle("simple_ao", c.simpleAo, atmosphere));
    }

    /** 今の手元の値がどの段階と全部一致するか(どれとも違えば null = カスタム)。 */
    @Nullable
    private QualityPreset currentPreset() {
        for (QualityPreset p : QualityPreset.values()) {
            if (settings.stream().allMatch(s -> s.matches(p))) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 項目の種類

    /** 1 項目。値は画面を閉じるまで手元に持ち、「完了」で設定へ書く。 */
    private abstract static class Setting {
        final String id;

        Setting(String id) {
            this.id = id;
        }

        /** 今の手元の値が正しいか(数字の欄のときだけ偽になりうる)。 */
        boolean valid() {
            return true;
        }

        abstract boolean isDefault();

        abstract void reset();

        abstract void save();

        /** 既定値の表示(説明の「既定値: …」)。 */
        abstract Component defaultDisplay();

        /** 範囲(数字の項目だけ。無ければ null)。 */
        @Nullable
        Component range() {
            return null;
        }

        /** 品質の段階ごとのこの項目の値(段階に入らない項目は null)。 */
        @Nullable
        Function<QualityPreset, ?> presetValue;

        /** この項目の前に入れる見出しの名前(無ければ null)。 */
        @Nullable
        String section;

        /** この項目の前に見出し {@code <KEY>section.<name>} を入れる。 */
        Setting section(String name) {
            this.section = name;
            return this;
        }

        /** 押せるか(親のオンオフがオフの子は押せない)。 */
        boolean active() {
            return true;
        }

        /** この項目を品質の段階に入れる。 */
        Setting preset(Function<QualityPreset, ?> value) {
            this.presetValue = value;
            return this;
        }

        /** 手元の値が段階 {@code p} の値と同じか(段階に入らない項目はいつも真)。 */
        boolean matches(QualityPreset p) {
            return presetValue == null || presetValue.apply(p).equals(value());
        }

        /** 段階 {@code p} の値を手元へ書く(保存は「完了」で)。 */
        void applyPreset(QualityPreset p) {
            if (presetValue != null) {
                set(presetValue.apply(p));
            }
        }

        /** 手元の値(数字の欄が正しくなければ比べても一致しない値)。 */
        abstract Object value();

        abstract void set(Object value);
    }

    /** オン・オフ(押すたびに切り替わるボタン)。 */
    private static final class Toggle extends Setting {
        final ModConfigSpec.BooleanValue value;
        /** 親のオンオフ(雰囲気の演出全体、霧の細部は霧)。親(とその親)が手元でオフの間はこの項目を押せない(値はそのまま)。 */
        @Nullable
        final Toggle parent;
        boolean current;

        Toggle(String id, ModConfigSpec.BooleanValue value) {
            this(id, value, null);
        }

        Toggle(String id, ModConfigSpec.BooleanValue value, @Nullable Toggle parent) {
            super(id);
            this.value = value;
            this.parent = parent;
            this.current = value.get();
        }

        @Override
        boolean active() {
            return parent == null || parent.current && parent.active();
        }

        Component display() {
            return current ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }

        @Override
        boolean isDefault() {
            return current == value.getDefault();
        }

        @Override
        void reset() {
            current = value.getDefault();
        }

        @Override
        void save() {
            value.set(current);
        }

        @Override
        Component defaultDisplay() {
            return value.getDefault() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }

        @Override
        Object value() {
            return current;
        }

        @Override
        void set(Object v) {
            current = (Boolean) v;
        }
    }

    /** 割合(数字の入力欄。既定は 0〜100)。 */
    private static final class Percent extends Setting {
        final ModConfigSpec.IntValue value;
        final int min;
        final int max;
        /** 既定値の表示の後ろに付ける単位(既定は %)。 */
        final String unit;
        String text;

        Percent(String id, ModConfigSpec.IntValue value) {
            this(id, value, 0, 100);
        }

        Percent(String id, ModConfigSpec.IntValue value, int min, int max) {
            this(id, value, min, max, "%");
        }

        Percent(String id, ModConfigSpec.IntValue value, int min, int max, String unit) {
            super(id);
            this.unit = unit;
            this.value = value;
            this.min = min;
            this.max = max;
            this.text = Integer.toString(value.get());
        }

        int parsed() {
            try {
                int v = Integer.parseInt(text.trim());
                return v >= min && v <= max ? v : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        boolean valid() {
            return parsed() >= 0;
        }

        @Override
        boolean isDefault() {
            return parsed() == value.getDefault();
        }

        @Override
        void reset() {
            text = Integer.toString(value.getDefault());
        }

        @Override
        void save() {
            value.set(parsed());
        }

        @Override
        Object value() {
            return parsed();
        }

        @Override
        void set(Object v) {
            text = Integer.toString((Integer) v);
        }

        @Override
        Component defaultDisplay() {
            return Component.literal(value.getDefault() + unit);
        }

        @Override
        Component range() {
            return Component.translatable(COMMON_KEY + "range", Integer.toString(min), Integer.toString(max));
        }
    }

    /** 選択肢を順に回すボタン。表示は {@code <KEY><id>.<選択肢の小文字>}(雰囲気の重さ・巻きシダ)。 */
    private static final class Choice<E extends Enum<E>> extends Setting {
        final ModConfigSpec.EnumValue<E> value;
        final E[] options;
        E current;

        Choice(String id, ModConfigSpec.EnumValue<E> value, E[] options) {
            super(id);
            this.value = value;
            this.options = options;
            this.current = value.get();
        }

        Component display() {
            return name(current);
        }

        Component name(E option) {
            return Component.translatable(KEY + id + "." + option.name().toLowerCase(Locale.ROOT));
        }

        void next() {
            current = options[(current.ordinal() + 1) % options.length];
        }

        @Override
        boolean isDefault() {
            return current == value.getDefault();
        }

        @Override
        void reset() {
            current = value.getDefault();
        }

        @Override
        void save() {
            value.set(current);
        }

        @Override
        Component defaultDisplay() {
            return name(value.getDefault());
        }

        @Override
        Object value() {
            return current;
        }

        @Override
        @SuppressWarnings("unchecked")
        void set(Object v) {
            current = (E) v;
        }
    }

    // ------------------------------------------------------------------ 画面

    private final Screen parent;
    private final List<Setting> settings = settings();
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    @Nullable
    private SettingList list;
    @Nullable
    private Button doneButton;
    /** 一番上の品質の段階の行と、項目の行(段階を選んだら入力欄・ボタンの表示を合わせ直す)。一覧を作り直すたびに作り直す。 */
    @Nullable
    private PresetEntry presetEntry;
    private final List<SettingEntry> settingEntries = new ArrayList<>();

    public VisualSettingsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(TITLE, font);
        list = layout.addToContents(new SettingList(minecraft));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        // 「ゲーム内容」のページへ(2026-11-14。この画面の変更は保存されない = 行き来は「キャンセル」と同じ)
        footer.addChild(Button.builder(Component.translatable("gui.overgrown_vegetation.gameplay_settings.open"),
                button -> minecraft.setScreen(new GameplaySettingsScreen(parent))).width(100).build());
        doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> done()).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).build());
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
        if (!settings.stream().allMatch(Setting::valid)) {
            return;
        }
        settings.forEach(Setting::save);
        OvergrownClientConfig.SPEC.save();
        // 演出のオンオフは描く側が毎 tick・毎フレーム VisualQuality から読むので、ここで何もしなくても次の tick から効く。
        // 巻きシダは変わっていれば焼き直す(値が同じなら何もしない)。
        com.yayuy.overgrownvegetation.client.GeoStaticGeometry.applyDetailChange();
        minecraft.setScreen(parent);
    }

    private void updateDoneButton() {
        if (doneButton != null) {
            doneButton.active = settings.stream().allMatch(Setting::valid);
        }
        if (presetEntry != null) {
            presetEntry.refresh();
        }
    }

    // ------------------------------------------------------------------ 一覧

    // 一覧の中では単純名 Entry が継承した ContainerObjectSelectionList.Entry を指すので、この画面の Entry は必ず修飾して書く。
    private final class SettingList extends ContainerObjectSelectionList<VisualSettingsScreen.Entry> {

        SettingList(Minecraft minecraft) {
            super(minecraft, VisualSettingsScreen.this.width, layout.getContentHeight(), layout.getHeaderHeight(), ITEM_HEIGHT);
            settingEntries.clear();
            presetEntry = new PresetEntry();
            addEntry(new NoteEntry());
            addEntry(presetEntry);
            for (Setting setting : settings) {
                if (setting.section != null) {
                    addEntry(new SectionEntry(setting.section));
                }
                SettingEntry entry = new SettingEntry(setting);
                settingEntries.add(entry);
                addEntry(entry);
            }
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            VisualSettingsScreen.Entry hovered = getHovered();
            if (hovered != null && hovered.tooltip() != null) {
                setTooltipForNextRenderPass(hovered.tooltip());
            }
        }
    }

    private abstract static class Entry extends ContainerObjectSelectionList.Entry<VisualSettingsScreen.Entry> {
        @Nullable
        List<FormattedCharSequence> tooltip() {
            return null;
        }
    }

    /** いちばん上の 1 行: 「この PC だけに効く」。 */
    private final class NoteEntry extends Entry {
        private final Component note = Component.translatable(KEY + "note");
        private final List<FormattedCharSequence> lines = font.split(note, ROW_WIDTH);

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int shown = Math.min(2, lines.size());
            int y = top + (height - 4 - shown * 10) / 2 + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawCenteredString(font, lines.get(i), left + width / 2, y + i * 10, NOTE_COLOR);
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

    /** 見出しの行(環境音・光・霧…、2026-11-01)。バニラのコントロール設定の分類の見出しと同じく、中央に黄色で。 */
    private final class SectionEntry extends Entry {
        private final Component name;

        SectionEntry(String section) {
            this.name = Component.translatable(KEY + "section." + section).withStyle(ChatFormatting.BOLD);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            graphics.drawCenteredString(font, name, left + width / 2, top + height - 4 - 9 - 2, LABEL_CHANGED_COLOR);
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
                    output.add(NarratedElementType.TITLE, name);
                }
            });
        }
    }

    /**
     * 一番上の行: 品質の段階(低・中・高、どれとも違えば「カスタム」)。押すと 高 → 中 → 低 → 高 と回り(10-23 ユーザー指定)、段階に入る項目の手元の値を一式書き換える
     * (保存は「完了」)。段階は保存せず、手元の値から毎回決める({@link #currentPreset()})。リセットは高(既定)へ。
     */
    private final class PresetEntry extends Entry {
        private final Component name = Component.translatable(KEY + "quality");
        private final List<FormattedCharSequence> label = font.split(name, ROW_WIDTH - INPUT_WIDTH - RESET_WIDTH - GAP * 3);
        private final List<FormattedCharSequence> tooltip;
        private final Button button;
        private final Button reset;
        private final List<AbstractWidget> children;

        PresetEntry() {
            List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable(KEY + "quality.desc"), TOOLTIP_WIDTH));
            for (QualityPreset p : QualityPreset.values()) {
                lines.addAll(font.split(Component.translatable(KEY + "quality." + key(p) + ".gpu").withStyle(ChatFormatting.GRAY), TOOLTIP_WIDTH));
            }
            lines.add(Component.translatable(COMMON_KEY + "default", presetName(QualityPreset.HIGH)).withStyle(ChatFormatting.GRAY).getVisualOrderText());
            this.tooltip = lines;
            this.button = Button.builder(CommonComponents.EMPTY, b -> {
                        QualityPreset now = currentPreset();
                        // カスタムからは高へ。段階からは 高 → 中 → 低 → 高(10-23 ユーザー指定。並びは LOW・MEDIUM・HIGH なので 1 つ前へ)。
                        apply(now == null ? QualityPreset.HIGH : QualityPreset.values()[(now.ordinal() + QualityPreset.values().length - 1) % QualityPreset.values().length]);
                    })
                    .size(INPUT_WIDTH, 20)
                    .build();
            this.reset = Button.builder(Component.translatable(COMMON_KEY + "reset"), b -> apply(QualityPreset.HIGH))
                    .size(RESET_WIDTH, 20)
                    .build();
            this.children = List.of(button, reset);
        }

        private void apply(QualityPreset p) {
            for (Setting s : settings) {
                s.applyPreset(p);
            }
            for (SettingEntry e : settingEntries) {
                e.sync();
            }
            refresh();
        }

        void refresh() {
            QualityPreset now = currentPreset();
            button.setMessage(now == null ? Component.translatable(KEY + "quality.custom") : presetName(now));
            reset.active = now != QualityPreset.HIGH;
        }

        private static String key(QualityPreset p) {
            return p.name().toLowerCase(Locale.ROOT);
        }

        private static Component presetName(QualityPreset p) {
            return Component.translatable(KEY + "quality." + key(p));
        }

        @Override
        List<FormattedCharSequence> tooltip() {
            return tooltip;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int color = currentPreset() != QualityPreset.HIGH ? LABEL_CHANGED_COLOR : LABEL_COLOR;
            int shown = Math.min(2, label.size());
            int y = shown == 1 ? top + 6 : top + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawString(font, label.get(i), left, y + i * 10, color, false);
            }
            int right = left + width;
            reset.setPosition(right - RESET_WIDTH, top);
            button.setPosition(right - RESET_WIDTH - GAP - INPUT_WIDTH, top);
            button.render(graphics, mouseX, mouseY, partialTick);
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

    /** 1 項目: 名前・入力欄かボタン・リセット。 */
    private final class SettingEntry extends Entry {
        private final Setting setting;
        private final List<FormattedCharSequence> label;
        private final List<FormattedCharSequence> tooltip;
        @Nullable
        private final EditBox input;
        @Nullable
        private final Button button;
        private final AbstractWidget field;
        private final Button reset;
        private final List<AbstractWidget> children;

        SettingEntry(Setting setting) {
            this.setting = setting;
            Component name = Component.translatable(KEY + setting.id);
            this.label = font.split(name, ROW_WIDTH - INPUT_WIDTH - RESET_WIDTH - GAP * 3);
            this.tooltip = buildTooltip(setting);

            if (setting instanceof Percent percent) {
                this.button = null;
                EditBox box = new EditBox(font, 0, 0, INPUT_WIDTH, 20, name);
                box.setMaxLength(3);
                box.setFilter(text -> text.chars().allMatch(c -> c >= '0' && c <= '9'));
                box.setValue(percent.text);
                box.setResponder(text -> {
                    percent.text = text;
                    refresh();
                });
                this.input = box;
                this.field = box;
            } else {
                this.input = null;
                this.button = Button.builder(CommonComponents.EMPTY, b -> {
                            if (setting instanceof Toggle toggle) {
                                toggle.current = !toggle.current;
                            } else if (setting instanceof Choice<?> choice) {
                                choice.next();
                            }
                            refresh();
                        })
                        .size(INPUT_WIDTH, 20)
                        .build();
                this.field = button;
            }
            this.reset = Button.builder(Component.translatable(COMMON_KEY + "reset"), b -> {
                        setting.reset();
                        if (input != null && setting instanceof Percent percent) {
                            input.setValue(percent.text); // 応答が refresh を呼ぶ
                        }
                        refresh();
                    })
                    .size(RESET_WIDTH, 20)
                    .build();
            this.children = List.of(field, reset);
            refresh();
        }

        private void refresh() {
            if (input != null) {
                input.setTextColor(setting.valid() ? INPUT_COLOR : INPUT_INVALID_COLOR);
            }
            if (button != null) {
                if (setting instanceof Toggle toggle) {
                    button.setMessage(toggle.display());
                } else if (setting instanceof Choice<?> choice) {
                    button.setMessage(choice.display());
                }
            }
            reset.active = !setting.valid() || !setting.isDefault();
            updateDoneButton();
        }

        /** 品質の段階を選んだ後: 手元の値を入力欄・ボタンへ映す。 */
        void sync() {
            if (input != null && setting instanceof Percent percent) {
                input.setValue(percent.text); // 応答が refresh を呼ぶ
            }
            refresh();
        }

        @Override
        List<FormattedCharSequence> tooltip() {
            return tooltip;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            boolean active = setting.active();
            int color = !active ? NOTE_COLOR : setting.valid() && !setting.isDefault() ? LABEL_CHANGED_COLOR : LABEL_COLOR;
            int shown = Math.min(2, label.size());
            int y = shown == 1 ? top + 6 : top + 1;
            for (int i = 0; i < shown; i++) {
                graphics.drawString(font, label.get(i), left, y + i * 10, color, false);
            }
            int right = left + width;
            field.active = active;
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

    /** 説明・既定値・範囲。 */
    private List<FormattedCharSequence> buildTooltip(Setting setting) {
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable(KEY + setting.id + ".desc"), TOOLTIP_WIDTH));
        lines.add(Component.translatable(COMMON_KEY + "default", setting.defaultDisplay()).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        Component range = setting.range();
        if (range != null) {
            lines.add(range.copy().withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        return lines;
    }
}
