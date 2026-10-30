package com.yayuy.overgrownvegetation.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * 胞子の嵐(2026-10-23 胞子の場と風 H)。雷雨のオーバーワールドでは場が嵐になる(卓越風が嵐の強さ)。
 * 画面側は雷雨の度合い({@code ClientLevel#getThunderLevel})で、霧の最大・風の音・緑の雨粒・胞子計の振り切れを出す。
 * 警報器は触らない(後で作り直す)。
 */
public final class ClientStorm {

    private ClientStorm() {
    }

    /** 胞子の嵐の度合い 0〜1(オーバーワールドの雷雨。雨の降らない所でも空は濁る)。 */
    public static float sporeStorm() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD) return 0.0F;
        return level.getThunderLevel(1.0F);
    }
}
