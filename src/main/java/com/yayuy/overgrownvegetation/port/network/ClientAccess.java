package com.yayuy.overgrownvegetation.port.network;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.player.Player;

/** 【1.20.1 だけの物・互換の層】クライアントだけのクラスに触る所(サーバーでは読み込まない。{@link OvChannel} が DistExecutor 越しに呼ぶ)。 */
final class ClientAccess {

    private ClientAccess() {
    }

    static Player player() {
        return Minecraft.getInstance().player;
    }

    static RegistryAccess registryAccess() {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            return conn.registryAccess();
        }
        var level = Minecraft.getInstance().level;
        if (level != null) {
            return level.registryAccess();
        }
        throw new IllegalStateException("no client registry access");
    }
}
