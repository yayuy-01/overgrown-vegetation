package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.network.PrevailingWindPayload;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * サーバーから受け取った卓越風の入力(2026-10-23 胞子の場と風 第 2 段 B)。{@link CanopyWind} の弱い風の向きの土台になる。
 * 受け取る前(サーバーにこの MOD が無い・まだ届いていない)は {@link #synced()} が false で、{@link CanopyWind} は今までどおりさまよう。
 *
 * <p>通信はワールドに入る途中(最初の tick より前)に届くので、受け取る所でワールドの替わりを見て消してから入れる
 * (memory client-payload-before-first-tick)。
 */
public final class ClientPrevailingWind {

    private static PrevailingWindPayload last;
    private static ClientLevel lastLevel;
    private static final PrevailingWind.Sample SAMPLE = new PrevailingWind.Sample();

    private ClientPrevailingWind() {
    }

    public static void accept(PrevailingWindPayload payload) {
        resetIfLevelChanged();
        last = payload;
    }

    private static void resetIfLevelChanged() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            last = null;
            lastLevel = level;
        }
    }

    public static boolean synced() {
        resetIfLevelChanged();
        return last != null;
    }

    /** 今の卓越風(dayTime はクライアントの {@code ClientLevel#getDayTime})。受け取っていなければ null。 */
    public static PrevailingWind.Sample sample(long dayTime) {
        if (!synced()) return null;
        PrevailingWindPayload p = last;
        PrevailingWind.sample(p.windSeed(), dayTime,
                new PrevailingWind.Weather(p.raining(), p.thundering(), p.lastRainStart(), p.lastRainEnd(), p.prevRainStart(), p.prevRainEnd()),
                SAMPLE);
        return SAMPLE;
    }
}
