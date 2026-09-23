package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code data/<namespace>/grammar/<path>.json}(structure-grammar 文法ファイル)を
 * データパックのリロードで読み込み、{@code GrammarStructureFeature} から引けるようにする。
 *
 * <p>文法は登録レジストリではなく単なる JSON なので、{@link SimpleJsonResourceReloadListener} で
 * {@link JsonObject} のまま保持し、{@link GrammarEngine} にそのまま渡す(ブラウザ側と同じ扱い)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class GrammarRegistry extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static volatile Map<ResourceLocation, JsonObject> GRAMMARS = Map.of();

    public GrammarRegistry() {
        super(GSON, "grammar");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonObject> loaded = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : map.entrySet()) {
            if (e.getValue().isJsonObject()) {
                loaded.put(e.getKey(), e.getValue().getAsJsonObject());
            } else {
                OvergrownVegetation.LOGGER.warn("grammar {} は JSON オブジェクトではありません。無視します", e.getKey());
            }
        }
        GRAMMARS = Map.copyOf(loaded);
        OvergrownVegetation.LOGGER.info("structure-grammar: {} 個の文法を読み込みました {}",
                GRAMMARS.size(), GRAMMARS.keySet());
    }

    /** 見つからなければ {@code null}。 */
    public static JsonObject get(ResourceLocation id) {
        return GRAMMARS.get(id);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new GrammarRegistry());
    }
}
