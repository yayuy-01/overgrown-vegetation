package com.yayuy.overgrownvegetation.port.dev;

import com.yayuy.overgrownvegetation.port.PortConst;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * 【1.20.1 だけの物・開発用】Claude 用クライアントを自分で閉じるフック(ユーザーの決定 12、11-22)。
 * タイトル画面に初めて着いたらログに {@code [ov-dev] title screen reached} を出す。
 * {@code -Dov.autoQuit=title}(Gradle では {@code ./gradlew runClaudeClient -Dov.autoQuit=title})のときは、そのまま自分で終わる。
 * クライアントだけ({@code PortSetup} が Dist を見て呼ぶ)。
 */
public final class AutoQuit {

    private AutoQuit() {
    }

    private static boolean reached;

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(AutoQuit::onScreen);
    }

    private static void onScreen(ScreenEvent.Init.Post e) {
        if (reached || !(e.getScreen() instanceof TitleScreen)) {
            return;
        }
        reached = true;
        PortConst.LOGGER.info("[ov-dev] title screen reached");
        if ("title".equals(System.getProperty("ov.autoQuit"))) {
            PortConst.LOGGER.info("[ov-dev] ov.autoQuit=title -> stopping the client");
            Minecraft.getInstance().stop();
        }
    }
}
