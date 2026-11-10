package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.network.HeatForestClockPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * 画面の側の「ネザーの植物は適応したか」(2026-11-10 熱水の森の追記 4)。サーバーの {@code HeatForestAdaptation} の時計を
 * {@link HeatForestClockPayload} で受ける。覆いの絵は、適応の前はオーバーワールドの覆い、後はネザー版({@link CoverSprites#forLevel})。
 * 切り替わったら、ネザーにいればチャンクの形を組み直す(覆いはチャンクの形に焼き込まれるため)。
 */
public final class HeatForestClockClient {

    private static volatile boolean adapted;

    private HeatForestClockClient() {
    }

    public static boolean adapted() {
        return adapted;
    }

    public static void accept(HeatForestClockPayload payload) {
        boolean changed = adapted != payload.adapted();
        adapted = payload.adapted();
        Minecraft mc = Minecraft.getInstance();
        if (changed && mc.level != null && mc.level.dimension() == Level.NETHER) {
            mc.levelRenderer.allChanged();
        }
    }
}
