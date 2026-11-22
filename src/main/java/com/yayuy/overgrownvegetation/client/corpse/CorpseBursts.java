package com.yayuy.overgrownvegetation.client.corpse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * 死体の突き破りの値({@code assets/overgrown_vegetation/corpse_bursts/<種>.json}、2026-11-08)。生成器の {@code <版>.burst.json} の写し
 * ({@code tools/g5-models/g1/install_v2.sh})。GeckoLib のフォルダ(geo・animations)の外に置く(置くと全 Mob のアニメが読めなくなる)。
 *
 * <p>{@code swapTick}: 突き破りを流し始めてから、絵を無傷の絵から傷のある絵へ替える tick(GeckoLib の絵はアニメの鍵に載らないのでコードで替える)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CorpseBursts {

    private CorpseBursts() {
    }

    /** 読めなかったときの値(ゾンビ r9 の 24 と牛などの 30 の間)。 */
    private static final int FALLBACK_SWAP = 30;

    private static final Map<String, Integer> SWAP = new HashMap<>();

    /** 種 {@code species}(例 {@code overgrown_zombie})の swapTick。 */
    public static int swapTick(String species) {
        return SWAP.computeIfAbsent(species, CorpseBursts::load);
    }

    private static int load(String species) {
        ResourceLocation id = OvergrownVegetation.id("corpse_bursts/" + species + ".json");
        java.util.Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
        if (res.isEmpty()) {
            OvergrownVegetation.LOGGER.warn("死体の突き破りの値が無い: {}", id);
            return FALLBACK_SWAP;
        }
        try (Reader r = res.get().openAsReader()) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            return o.get("swapTick").getAsInt();
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("死体の突き破りの値を読めない: {}", id, e);
            return FALLBACK_SWAP;
        }
    }

    /** 資源の読み直し(F3+T)で忘れる。 */
    @SubscribeEvent
    public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> SWAP.clear());
    }
}
