package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * MOD の設定画面の「ゲーム内容」のページ(2026-11-14。ユーザー「同時にいられる数の上限は mod の設定タブで変えれるようにしよう」)。
 * 見た目の設定({@link VisualSettingsScreen})と、下の「見た目の設定…」「ゲーム内容の設定…」のボタンで行き来する。
 *
 * <p>値は COMMON の設定({@code config/overgrown_vegetation-common.toml} の {@code [gameplay]})に書く。シングルプレイではその場で効く
 * (同じゲームの中のサーバーが同じ設定を読む)。マルチプレイでは、サーバーの設定ファイルの値が効く(この画面で変えても、繋いでいるサーバーには届かない。
 * 画面に注意書きを出す)。後で値を足すときは {@link #rows()} に 1 行足す。
 */
public final class GameplaySettingsScreen extends Screen {

    private static final String KEY = "gui.overgrown_vegetation.gameplay_settings.";
    private static final Component TITLE = Component.translatable(KEY + "title");
    private static final int FIELD_WIDTH = 60;

    /** 整数の設定 1 行: 設定の値・幅・空欄(既定)のときの設計データの値。−1 を「設計データを使う」として保存する。 */
    private record IntRow(String id, ModConfigSpec.IntValue value, int min, int max, IntSupplier designDefault) {
    }

    private static List<IntRow> rows() {
        List<IntRow> out = new ArrayList<>();
        out.add(new IntRow("large_raid_max_alive", OvergrownVegetationConfig.COMMON.largeRaidMaxAlive,
                LargeRaids.MAX_ALIVE_MIN, LargeRaids.MAX_ALIVE_MAX,
                () -> com.yayuy.overgrownvegetation.data.DesignData.director().largeRaid.maxAlive));
        return out;
    }

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    private final List<IntRow> rows = rows();
    private final List<EditBox> boxes = new ArrayList<>();
    private Button doneButton;

    public GameplaySettingsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        boxes.clear();
        layout.addTitleHeader(TITLE, font);
        LinearLayout body = layout.addToContents(LinearLayout.vertical().spacing(6));
        body.addChild(new StringWidget(Component.translatable(KEY + "section.raids").withStyle(ChatFormatting.BOLD), font));
        for (IntRow r : rows) {
            LinearLayout line = body.addChild(LinearLayout.horizontal().spacing(6));
            Component name = Component.translatable(KEY + r.id());
            StringWidget label = line.addChild(new StringWidget(220, 20, name, font).alignLeft());
            label.setTooltip(Tooltip.create(Component.translatable(KEY + r.id() + ".desc", r.min(), r.max(), r.designDefault().getAsInt())));
            EditBox box = line.addChild(new EditBox(font, FIELD_WIDTH, 20, name));
            int v = r.value().get();
            box.setValue(v < 0 ? "" : Integer.toString(v));
            box.setHint(Component.literal(Integer.toString(r.designDefault().getAsInt())).withStyle(ChatFormatting.DARK_GRAY));
            box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit) && s.length() <= 4);
            box.setResponder(s -> updateDone());
            box.setTooltip(Tooltip.create(Component.translatable(KEY + "range", r.min(), r.max())));
            boxes.add(box);
            line.addChild(Button.builder(Component.translatable(KEY + "reset"), b -> {
                box.setValue("");
                updateDone();
            }).width(70).build());
        }
        boolean remote = minecraft != null && minecraft.level != null && minecraft.getSingleplayerServer() == null;
        body.addChild(new MultiLineTextWidget(Component.translatable(remote ? KEY + "note_remote" : KEY + "note")
                .withStyle(remote ? ChatFormatting.GOLD : ChatFormatting.GRAY), font).setMaxWidth(340));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("gui.overgrown_vegetation.gameplay_settings.visuals"),
                b -> minecraft.setScreen(new VisualSettingsScreen(parent))).width(120).build());
        doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> done()).width(80).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(80).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        updateDone();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    /** 空欄 = 設計データ(−1)。数なら幅の中だけ受け付ける。 */
    private static Integer parse(String s, IntRow r) {
        if (s.isEmpty()) return -1;
        try {
            int v = Integer.parseInt(s);
            return v >= r.min() && v <= r.max() ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateDone() {
        boolean ok = true;
        for (int i = 0; i < rows.size(); i++) {
            boolean valid = parse(boxes.get(i).getValue(), rows.get(i)) != null;
            boxes.get(i).setTextColor(valid ? 0xE0E0E0 : 0xFF5555);
            ok &= valid;
        }
        if (doneButton != null) doneButton.active = ok;
    }

    private void done() {
        for (int i = 0; i < rows.size(); i++) {
            Integer v = parse(boxes.get(i).getValue(), rows.get(i));
            if (v == null) return;
            rows.get(i).value().set(v);
        }
        OvergrownVegetationConfig.SPEC.save();
        minecraft.setScreen(parent);
    }
}
