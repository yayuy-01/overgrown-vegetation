package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.shader.TerrainShaderSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * 段を「なし」へ・「なし」から変えたときの一言(2026-11-23、ユーザー決定「なしを選んだときに再起動すると差し込みも外れますと一言出す」)。
 * 地形のシェーダーの差し込みは起動時の値で決まる({@link TerrainShaderSwitch})ので、外れる・戻るのは次の起動から。右上のお知らせで出す。
 * ビデオ設定・Sodium の画面・見た目の設定の画面のどこから変えても、書いた後にこれを呼ぶ。
 */
public final class RenderTierNotice {

    private static final String KEY = "gui.overgrown_vegetation.render_tier_restart.";

    /** 親「この MOD の演出」が {@code wasOn} から {@code nowOn} へ変わった後に呼ぶ。 */
    public static void afterChange(boolean wasOn, boolean nowOn) {
        if (wasOn == nowOn) {
            return;
        }
        boolean stripped = TerrainShaderSwitch.stripped();
        String which;
        if (!nowOn && !stripped) {
            which = "off"; // なしにした: 再起動で差し込みも外れる
        } else if (nowOn && stripped) {
            which = "on"; // なしで起動していて戻した: 再起動で差し込みが戻る
        } else {
            return; // その起動の差し込みの状態と同じ向き(言うことは無い)
        }
        Minecraft mc = Minecraft.getInstance();
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                Component.translatable(KEY + which + ".title"), Component.translatable(KEY + which + ".body"));
    }

    private RenderTierNotice() {
    }
}
