package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * {@link CanopyWind}(汚染地域の風)を毎 tick 進める所。風を読む物 —— 光の筋・木漏れ日({@link CanopyLight}・{@link CanopyDapple})、
 * 落ち葉({@link CanopyLeaves})、環境の胞子({@link DriftingSporeParticle})、風の音({@link com.yayuy.overgrownvegetation.client.sound.CanopyWindSound})
 * —— より先に進めたいので、tick の<b>初め</b>(Pre)で進める。
 *
 * <p>前は光の筋の tick の中で進めていたので、{@code canopyLightEnabled} を切ると風も止まった(2026-10-15 夜に分けた。
 * 胞子と音は光の筋を切っても風で動く)。空の無い次元(ネザー・エンド)では進めない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopyWindDriver {

    private static ClientLevel windLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != windLevel) {
            CanopyWind.reset();
            windLevel = level;
        }
        if (level == null || player == null || mc.isPaused() || !level.dimensionType().hasSkyLight()) {
            return;
        }
        // 卓越風(サーバーと同じ式)を土台に(2026-10-23 胞子の場と風 第 2 段 B)。受け取っていなければ今までどおりさまよう
        com.yayuy.overgrownvegetation.spore.field.PrevailingWind.Sample prevailing = ClientPrevailingWind.sample(level.getDayTime());
        CanopyWind.setPrevailing(prevailing == null ? Double.NaN : Math.atan2(prevailing.dirZ, prevailing.dirX));
        CanopyWind.tick(player.getX(), player.getZ(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F),
                OvergrownVegetationConfig.COMMON.canopyWindStrength.get());
    }

    private CanopyWindDriver() {
    }
}
