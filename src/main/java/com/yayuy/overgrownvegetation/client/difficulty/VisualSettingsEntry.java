package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * バニラの「設定…」画面に「Overgrown Vegetation…」のボタンを 1 つ足し、{@link VisualSettingsScreen} を開く(2026-11-01、入口は仮決め)。
 * タイトル画面からもゲーム中の Esc → 設定… からも同じ画面なので両方で出る。もう 1 つの入口は Mods 画面の「設定」
 * ({@code client/OvergrownVegetationClientMod})。
 *
 * <p>置き場所: 設定の一覧のボタンのいちばん下の行の下(「完了」より上に入るとき)。入らなければ行の間のいちばん広い空き、
 * それも無い小さな画面では左上の隅に小さく。
 * 窓の大きさが変わると OptionsScreen は init をやり直さず自分の割り付けだけ並べ直す(Init.Post が来ない)ので、描く前に毎回置き直す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class VisualSettingsEntry {

    private static final Component LABEL = Component.translatable("gui.overgrown_vegetation.visual_settings.open");
    private static final int WIDTH = 200;
    private static final int SMALL_WIDTH = 120;
    private static final int HEIGHT = 20;
    private static final int GAP = 4;

    /** 今の設定画面に足したボタン(画面が替わったら使わない)。 */
    @Nullable
    private static Button button;
    /** 最後に置いたときの画面の大きさ(変わったときだけ置き直す)。 */
    private static int placedWidth;
    private static int placedHeight;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen screen)) {
            return;
        }
        button = Button.builder(LABEL, b -> screen.getMinecraft().setScreen(new VisualSettingsScreen(screen)))
                .size(WIDTH, HEIGHT).build();
        place(screen, button);
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (button != null && event.getScreen() instanceof OptionsScreen screen
                && (screen.width != placedWidth || screen.height != placedHeight) && screen.children().contains(button)) {
            place(screen, button);
        }
    }

    private static void place(Screen screen, Button own) {
        placedWidth = screen.width;
        placedHeight = screen.height;
        int doneTop = screen.height;
        List<int[]> rows = new ArrayList<>();
        for (GuiEventListener listener : screen.children()) {
            if (listener == own || !(listener instanceof AbstractWidget widget)) {
                continue;
            }
            if (widget instanceof Button && widget.getMessage().equals(CommonComponents.GUI_DONE)) {
                doneTop = Math.min(doneTop, widget.getY());
            } else if (widget instanceof AbstractButton) { // 題の文字(StringWidget)は数えない
                rows.add(new int[]{widget.getY(), widget.getY() + widget.getHeight()});
            }
        }
        // 縦の空き: 行と行の間・最後の行と「完了」の間。一覧の下が空いていればそこ、無ければいちばん広い間(タイトル画面から開くと
        // FOV の行と一覧の間が空く)。どこにも入らなければ左上の隅。
        rows.sort(Comparator.comparingInt(r -> r[0]));
        int y = -1;
        int widest = 0;
        int bottom = -1;
        for (int[] r : rows) {
            if (bottom >= 0 && r[0] - bottom > widest) {
                widest = r[0] - bottom;
                y = bottom + (widest - HEIGHT) / 2;
            }
            bottom = Math.max(bottom, r[1]);
        }
        if (bottom >= 0 && bottom + GAP + HEIGHT <= doneTop - GAP) {
            y = bottom + GAP;
        } else if (widest < HEIGHT + GAP * 2) {
            y = -1;
        }
        if (y >= 0) {
            own.setWidth(WIDTH);
            own.setPosition((screen.width - WIDTH) / 2, y);
        } else {
            own.setWidth(SMALL_WIDTH);
            own.setPosition(GAP, GAP);
        }
    }

    private VisualSettingsEntry() {
    }
}
