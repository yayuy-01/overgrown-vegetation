package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.difficulty.VisualSettingsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * クライアントだけの MOD の入口(2026-11-01)。Mods 画面のこの MOD の「設定」ボタンで演出の詳細設定({@link VisualSettingsScreen})を開く。
 * もう 1 つの入口はバニラの「設定…」画面のボタン({@code client/difficulty/VisualSettingsEntry})。
 */
@Mod(value = OvergrownVegetation.MODID, dist = Dist.CLIENT)
public final class OvergrownVegetationClientMod {

    public OvergrownVegetationClientMod(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new VisualSettingsScreen(parent));
    }
}
