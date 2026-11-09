package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.network.AtmosphereFxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** {@code /ov fx}(11-08)の受け手。G4 胞子の風・G5 光る胞子の夜をすぐ起こす・様子を出す。 */
public final class AtmosphereFx {

    public static void accept(AtmosphereFxPayload p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        switch (p.action()) {
            case AtmosphereFxPayload.GALE -> SporeGale.force(player.getX(), player.getZ());
            case AtmosphereFxPayload.GLOW_NIGHT -> GlowNight.force(p.arg());
            default -> {
            }
        }
        if (p.action() == AtmosphereFxPayload.STATUS) {
            String on = Atmosphere.enabled(mc.level) ? "" : "(雰囲気の演出がオフ: 何も出ない)";
            player.displayClientMessage(Component.literal(SporeGale.describe() + on), false);
            player.displayClientMessage(Component.literal(GlowNight.describe(mc.level)), false);
            player.displayClientMessage(Component.literal(CloudShadows.describe()), false);
        }
    }

    private AtmosphereFx() {
    }
}
