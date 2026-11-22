package com.yayuy.overgrownvegetation.client.voice;

import com.yayuy.overgrownvegetation.network.CrushedVoicePayload;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 汚した声の知らせ(サーバー → クライアント)を受けて鳴らす。通信の登録は {@code network/VoiceNetwork}。 */
@OnlyIn(Dist.CLIENT)
public final class CrushedVoiceClient {

    private CrushedVoiceClient() {
    }

    public static void play(CrushedVoicePayload p) {
        Minecraft.getInstance().getSoundManager().play(new CrushedVoiceInstance(p.sound(), p.source(), p.x(), p.y(), p.z(),
                p.volume(), p.pitch(), p.mode(), p.small(), p.strength(), p.seed()));
    }
}
