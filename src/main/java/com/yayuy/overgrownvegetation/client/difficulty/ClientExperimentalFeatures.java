package com.yayuy.overgrownvegetation.client.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import com.yayuy.overgrownvegetation.mixin.client.CreativeModeTabsAccessor;
import com.yayuy.overgrownvegetation.network.ExperimentalFeaturesPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 実験的要素のオン・オフのクライアント側(2026-10-09)。サーバーから届いた値を覚え、変わったらクリエイティブタブを作り直させる
 * (バニラはタブの中身を「機能フラグ・OP・レジストリ」が変わったときしか作り直さないので、覚えている組を消す。
 * 次にクリエイティブのインベントリを開いたときに組み直され、{@link ExperimentalFeatures.Tabs} が同盟軍のアイテムを外す/外さない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientExperimentalFeatures {

    public static void accept(ExperimentalFeaturesPayload payload) {
        if (ExperimentalFeatures.setClient(payload.enabled())) {
            CreativeModeTabsAccessor.overgrown_vegetation$setCachedParameters(null);
        }
    }

    /** ワールドを出たらオフに戻す(次のワールドはログインの知らせで決まる)。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (ExperimentalFeatures.setClient(false)) {
            CreativeModeTabsAccessor.overgrown_vegetation$setCachedParameters(null);
        }
    }

    private ClientExperimentalFeatures() {
    }
}
