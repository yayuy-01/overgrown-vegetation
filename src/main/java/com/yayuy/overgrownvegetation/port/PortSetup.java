package com.yayuy.overgrownvegetation.port;

import com.yayuy.overgrownvegetation.port.attachment.AttachmentEvents;
import com.yayuy.overgrownvegetation.port.event.tick.TickBridge;
import com.yayuy.overgrownvegetation.port.network.OvChannel;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 【1.20.1 だけの物・互換の層】互換の層の起動。起動クラスのコンストラクタの最初に 1 回呼ぶ。
 *
 * <p>互換の層(port/)の決まり: 本流の名前・形をまねる物だけを置く。各組が使い始めた後は<b>足すだけで、変えない・消さない</b>。
 * 一覧と使い方は PORTING.md の「互換の層」。足りない物は F の窓口へ。
 */
public final class PortSetup {

    private PortSetup() {
    }

    public static void init(IEventBus modBus) {
        AttachmentEvents.init(modBus);
        TickBridge.init();
        OvChannel.init();
        // NeoForge の RegisterPayloadHandlersEvent の代わり。@EventBusSubscriber の受け手がつながった後(共通の準備)に 1 回投げる
        modBus.addListener((FMLCommonSetupEvent e) -> modBus.post(new RegisterPayloadHandlersEvent()));
    }
}
