package com.yayuy.overgrownvegetation.client.corpse;

import com.google.gson.JsonArray;
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

import javax.annotation.Nullable;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 崩れ落ち・死体の置き方の値({@code assets/overgrown_vegetation/corpse_poses/<種>.json}、2026-10-09)。
 * 生成器 {@code tools/g5-models/mobs/G1*.java} の生成物 {@code <種>.corpse_pose.json} を写したもの(手で直さない)。
 *
 * <p>値はバニラのモデルの空間(1/16 ブロック、y 下向き、足元 y = 24)。
 * <ul>
 *   <li>{@code hinge}・{@code rotation}・{@code settle}: モデル全体を、倒れる軸のまわりに回して(度、rotationZYX(z, y, x))、最後に上下へずらす。</li>
 *   <li>{@code parts}: 崩れ落ちの終わりのバニラの部位の位置(単位)と角度(ラジアン)。</li>
 *   <li>{@code center}: 死体の外接箱の真ん中(geo の x, z)。手で置いた死体をブロックの真ん中へ寄せるのに使う。</li>
 *   <li>{@code renderScale}: バニラのレンダラーの拡大(村人 0.9375)。感染体・死体もこの大きさで描く。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CorpsePoses {

    private CorpsePoses() {
    }

    public record Part(float x, float y, float z, float xRot, float yRot, float zRot) {
    }

    public record Pose(String species, float renderScale, float[] hinge, float[] rotation, float[] settle,
                       float[] center, Map<String, Part> parts) {
    }

    private static final Map<String, Optional<Pose>> CACHE = new HashMap<>();

    @Nullable
    public static Pose get(String species) {
        return CACHE.computeIfAbsent(species, CorpsePoses::load).orElse(null);
    }

    private static Optional<Pose> load(String species) {
        ResourceLocation id = OvergrownVegetation.id("corpse_poses/" + species + ".json");
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(id);
        if (res.isEmpty()) {
            OvergrownVegetation.LOGGER.warn("崩れ落ちの姿勢が無い: {}", id);
            return Optional.empty();
        }
        try (Reader r = res.get().openAsReader()) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, Part> parts = new LinkedHashMap<>();
            JsonObject po = o.getAsJsonObject("parts");
            for (String name : po.keySet()) {
                JsonObject p = po.getAsJsonObject(name);
                float[] off = floats(p.getAsJsonArray("offset"));
                float[] rot = floats(p.getAsJsonArray("rot"));
                parts.put(name, new Part(off[0], off[1], off[2], rot[0], rot[1], rot[2]));
            }
            return Optional.of(new Pose(species,
                    o.get("render_scale").getAsFloat(),
                    floats(o.getAsJsonArray("hinge_model")),
                    floats(o.getAsJsonArray("rotation")),
                    floats(o.getAsJsonArray("settle_model")),
                    floats(o.getAsJsonArray("center")),
                    parts));
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.error("崩れ落ちの姿勢を読めない: {}", id, e);
            return Optional.empty();
        }
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    /** 資源の読み直し(F3+T)で忘れる。 */
    @SubscribeEvent
    public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> CACHE.clear());
    }
}
