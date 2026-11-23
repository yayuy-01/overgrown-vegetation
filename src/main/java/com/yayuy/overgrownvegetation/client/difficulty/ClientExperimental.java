package com.yayuy.overgrownvegetation.client.difficulty;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * 実験的要素の値がサーバーから届いて変わったとき(2026-11-23 夜)。クリエイティブのタブは同じ条件では作り直されないので、
 * いったん違う条件(OP の物の有無を逆)で作り直し、次に持ち物の画面が開いた・動いたときに正しい条件でもう一度作り直させる
 * (そのとき検索の索引も作り直される)。オフの要素の物がタブから消え、オンにすると戻る。
 */
public final class ClientExperimental {

    private ClientExperimental() {
    }

    public static void onChanged() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean op = mc.options.operatorItemsTab().get() && mc.player.canUseGameMasterBlocks();
        CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), !op, mc.level.registryAccess());
    }
}
