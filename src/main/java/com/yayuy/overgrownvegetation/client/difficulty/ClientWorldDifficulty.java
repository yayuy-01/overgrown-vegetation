package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.WorldDifficultyRequestPayload;
import com.yayuy.overgrownvegetation.network.WorldDifficultySyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 難易度の画面の入口 2 つ(2026-09-25、ユーザー指定)。
 * <ul>
 *     <li><b>ワールド作成画面</b> — 「難易度」の右隣({@code CreateWorldScreenGameTabMixin} が {@link #createWorldButton()} を置く)。
 *     決めた値は作成画面が持ち({@link CreateWorldDifficultyDraft})、「ワールド作成」で新しいワールドのフォルダへ書かれる。</li>
 *     <li><b>ポーズ画面</b> — 「設定…」の左隣(タイトル画面で言語のボタンが「設定…」の左にあるのと同じ置き方)。押すとサーバーへ
 *     今の値を問い合わせ、返事({@link WorldDifficultySyncPayload})が来たら開く。変えられない人には見るだけの画面になる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientWorldDifficulty {

    /** 返事を待っているポーズ画面。返事が来る前に閉じられていたら開かない。 */
    @Nullable
    private static Screen waitingParent;

    /** ワールド作成画面の「難易度」の右隣に置くボタン。 */
    public static LeafIconButton createWorldButton() {
        return new LeafIconButton(0, 0, button -> openFromCreateWorld());
    }

    private static void openFromCreateWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CreateWorldScreen screen && screen instanceof CreateWorldDifficultyDraft holder) {
            minecraft.setScreen(WorldDifficultyScreen.forCreateWorld(screen, holder.overgrownvegetation$difficultyDraft()));
        }
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) {
            return;
        }
        AbstractWidget options = findButton(event.getListenersList(), "menu.options");
        if (options == null) {
            return;
        }
        event.addListener(new LeafIconButton(options.getX() - LeafIconButton.SIZE - 4, options.getY(), button -> {
            waitingParent = pause;
            PacketDistributor.sendToServer(WorldDifficultyRequestPayload.INSTANCE);
        }));
    }

    /** サーバーの返事。押したポーズ画面がまだ開いていれば難易度の画面を開く。 */
    public static void accept(WorldDifficultySyncPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = waitingParent;
        waitingParent = null;
        if (parent == null || minecraft.screen != parent) {
            return;
        }
        minecraft.setScreen(WorldDifficultyScreen.forServer(parent, payload));
    }

    /** 言語キーでボタンを探す(バニラのボタンは {@code Component.translatable} で名前を持っている)。 */
    @Nullable
    private static AbstractWidget findButton(List<GuiEventListener> listeners, String translationKey) {
        for (GuiEventListener listener : listeners) {
            if (listener instanceof AbstractWidget widget
                    && widget.getMessage().getContents() instanceof TranslatableContents contents
                    && translationKey.equals(contents.getKey())) {
                return widget;
            }
        }
        return null;
    }

    private ClientWorldDifficulty() {
    }
}
