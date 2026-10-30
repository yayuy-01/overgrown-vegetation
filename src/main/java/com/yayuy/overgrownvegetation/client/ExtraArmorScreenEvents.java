package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 持ち物の画面の見た目(2026-10-15、追加防具の枠 {@code item/ExtraArmor})。
 * <ul>
 *     <li>追加防具の 4 枠の枠線と、動かした左手の枠の枠線を描く(バニラの背景の絵には無いので、防具の枠の絵を切り出して重ねる)。
 *     プレイヤーの絵の右の列のいちばん下は、もともと左手の枠の絵があった場所なので、そこは描かない。</li>
 *     <li>レシピブックのボタンを右へ動かす(左手の枠を作業枠の下へ動かしたため。ユーザー指定)。ボタンは開け閉めのたびに
 *     バニラが元の位置へ戻すので、描くたびに直す。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ExtraArmorScreenEvents {

    /** 枠線を切り出す元(バニラの持ち物の画面のヘルメットの枠)。 */
    private static final int FRAME_U = 7;
    private static final int FRAME_V = 7;
    private static final int FRAME_SIZE = 18;

    /** 持ち物の画面: 追加防具の上 3 枠(4 つ目は元の左手の枠の絵を使う)と、作業枠の下の左手の枠。 */
    private static final int[][] SURVIVAL_FRAMES = {{76, 7}, {76, 25}, {76, 43}, {106, 61}};
    /** クリエイティブの持ち物タブ: 追加防具の 4 枠。 */
    private static final int[][] CREATIVE_FRAMES = {{131, 5}, {131, 32}, {149, 5}, {149, 32}};

    /** レシピブックのボタンの、画面の左端からの位置(完成品の枠の下)。 */
    private static final int RECIPE_BUTTON_X = 152;

    @SubscribeEvent
    public static void onBackground(ContainerScreenEvent.Render.Background event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        int[][] frames;
        if (screen instanceof InventoryScreen) {
            frames = SURVIVAL_FRAMES;
        } else if (screen instanceof CreativeModeInventoryScreen creative && creative.isInventoryOpen()) {
            frames = CREATIVE_FRAMES;
        } else {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        for (int[] f : frames) {
            g.blit(AbstractContainerScreen.INVENTORY_LOCATION, screen.getGuiLeft() + f[0], screen.getGuiTop() + f[1],
                    FRAME_U, FRAME_V, FRAME_SIZE, FRAME_SIZE);
        }
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init.Post event) {
        moveRecipeButton(event.getScreen());
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Pre event) {
        moveRecipeButton(event.getScreen());
    }

    private static void moveRecipeButton(Screen screen) {
        if (!(screen instanceof InventoryScreen inventory)) {
            return;
        }
        for (GuiEventListener child : screen.children()) {
            // レシピブックのボタンは 20 × 18 の ImageButton(中身の絵は private なので大きさで見分ける)。
            if (child instanceof ImageButton button && button.getWidth() == 20 && button.getHeight() == 18) {
                int x = inventory.getGuiLeft() + RECIPE_BUTTON_X;
                if (button.getX() != x) {
                    button.setX(x);
                }
            }
        }
    }

    private ExtraArmorScreenEvents() {
    }
}
