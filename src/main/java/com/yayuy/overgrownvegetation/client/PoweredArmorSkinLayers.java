package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

/**
 * パワードアーマーを着た部位では、スキンの外の層(帽子・上着・袖・ズボン)を描かない(ユーザー指定 2026-10-05)。
 * 外の層は体から 0.25〜0.5 外に出ていて、体に沿わせたアーマーの箱を突き抜けて見えるため。
 *
 * <ul>
 *     <li>兜 = 帽子の層、胸当て = 上着と両袖、レギンス = 両脚のズボン。ブーツだけのときはズボンを残す(腿は覆っていないので)。</li>
 *     <li>NeoForge 21.1 の {@code PlayerRenderer#render} は外の層の表示({@code setModelProperties})を決めた<b>後</b>に
 *     {@link RenderPlayerEvent.Pre} を出すので、ここで消せばそのまま効く。毎回の描画の頭で設定し直されるので戻す処理は要らない。</li>
 *     <li>一人称の手は別の道({@code renderHand} が自分で {@code setModelProperties} を呼ぶ)なので、自分の視点の袖はそのまま。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PoweredArmorSkinLayers {

    private PoweredArmorSkinLayers() {
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        if (worn(player, EquipmentSlot.HEAD)) {
            model.hat.visible = false;
        }
        if (worn(player, EquipmentSlot.CHEST)) {
            model.jacket.visible = false;
            model.leftSleeve.visible = false;
            model.rightSleeve.visible = false;
        }
        if (worn(player, EquipmentSlot.LEGS)) {
            model.leftPants.visible = false;
            model.rightPants.visible = false;
        }
    }

    private static boolean worn(Player player, EquipmentSlot slot) {
        return player.getItemBySlot(slot).getItem() instanceof PoweredArmorItem;
    }
}
