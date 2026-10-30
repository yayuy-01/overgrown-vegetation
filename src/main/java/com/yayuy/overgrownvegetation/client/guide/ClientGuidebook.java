package com.yayuy.overgrownvegetation.client.guide;

import com.yayuy.overgrownvegetation.network.OpenGuidebookPayload;
import net.minecraft.client.Minecraft;

/** ガイドブックを開く通信が届いたら本を開く(2026-10-15)。 */
public final class ClientGuidebook {

    public static void open(OpenGuidebookPayload payload) {
        Minecraft.getInstance().setScreen(new GuidebookScreen(payload.mobs()));
    }

    private ClientGuidebook() {
    }
}
