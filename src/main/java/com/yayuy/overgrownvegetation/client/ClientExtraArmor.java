package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.network.ExtraArmorPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** {@link ExtraArmorPayload} を受け取り、クライアントのそのプレイヤーの追加防具(見た目用)へ書き写す(2026-10-15)。 */
public final class ClientExtraArmor {

    public static void accept(ExtraArmorPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(payload.entityId());
        if (!(entity instanceof Player player)) {
            return;
        }
        ExtraArmor armor = ExtraArmor.of(player);
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            armor.set(i, i < payload.items().size() ? payload.items().get(i) : ItemStack.EMPTY);
        }
    }

    private ClientExtraArmor() {
    }
}
