package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.network.OvergrownLayerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 届いた「覆われたマス」の記録をクライアントのチャンクへ取り込む(2026-09-22)。
 *
 * <p>取り込んだら<b>そのチャンクを描き直させる</b> —— 苔はチャンクのメッシュに焼き込まれるので、
 * 記録だけ更新してもすぐには見た目が変わらない。
 */
public final class ClientOvergrownLayer {

    private ClientOvergrownLayer() {
    }

    public static void accept(OvergrownLayerPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // まだ届いていないチャンクぶんは捨てる。チャンクが届くときに
        // ChunkWatchEvent.Sent でまるごと送り直されるので取りこぼさない。
        if (!level.hasChunk(payload.chunk().x, payload.chunk().z)) {
            return;
        }
        LevelChunk chunk = level.getChunk(payload.chunk().x, payload.chunk().z);
        OvergrownLayerData.accept(chunk, payload.positions(), payload.replace());
        // どの高さが変わったかは持っていないので、その列のセクションを全部積み直す。
        // 1 チャンク 24 セクションなので、たまに来るぶんには十分安い。
        for (int section = level.getMinSection(); section < level.getMaxSection(); section++) {
            Minecraft.getInstance().levelRenderer.setSectionDirty(payload.chunk().x, section, payload.chunk().z);
        }
    }
}
