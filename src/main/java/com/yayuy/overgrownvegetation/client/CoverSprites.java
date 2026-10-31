package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆いの絵({@code cover.json} の {@code textures} の名前 → ブロックのアトラスの {@code block/cover/<名前>})。バニラのブロックのアトラスは
 * {@code textures/block/} の下を名前空間を問わず全部積むので、アトラスの json は要らない。{@code <名前>_top} が積まれていれば上面だけそれ
 * (2026-10-30 の時点で上面の絵は無い。仕組みだけ)。アトラスを焼き直すたびに引き直す(焼き直すとチャンクも全部作り直される)。
 * 2026-10-30 に作り直し側 overgrown_vegetation/mod から持ってきた。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CoverSprites {

    /**
     * 側面(と下面)・上面の絵。同じ番号どうしが対。{@code creep} は這い上がる苔の段階の絵 {@code block/cover/creep_<変種>_<段階>}
     * ([変種 - 1][段階 - 1]、2026-11-01。無ければ null)。
     */
    public record Set(TextureAtlasSprite[] side, TextureAtlasSprite[] top, TextureAtlasSprite[][] creep) {
    }

    private static volatile @Nullable Set current;

    private CoverSprites() {
    }

    public static @Nullable Set current() {
        return current;
    }

    @SubscribeEvent
    public static void onStitched(TextureAtlasStitchedEvent event) {
        TextureAtlas atlas = event.getAtlas();
        if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) {
            return;
        }
        List<TextureAtlasSprite> side = new ArrayList<>();
        List<TextureAtlasSprite> top = new ArrayList<>();
        for (String name : DesignData.cover().textures()) {
            TextureAtlasSprite s = find(atlas, name);
            if (s == null) {
                OvergrownVegetation.LOGGER.warn("覆いの絵 textures/block/cover/{}.png がアトラスに無い(飛ばす)", name);
                continue;
            }
            TextureAtlasSprite t = find(atlas, name + "_top");
            side.add(s);
            top.add(t != null ? t : s);
        }
        TextureAtlasSprite[][] creep = new TextureAtlasSprite[com.yayuy.overgrownvegetation.corruption.CoverCreep.VARIANTS][com.yayuy.overgrownvegetation.corruption.CoverCreep.STAGES];
        int creepCount = 0;
        for (int v = 0; v < creep.length; v++) {
            for (int s = 0; s < creep[v].length; s++) {
                creep[v][s] = find(atlas, "creep_" + (v + 1) + "_" + (s + 1));
                creepCount += creep[v][s] != null ? 1 : 0;
            }
        }
        current = side.isEmpty() ? null : new Set(side.toArray(TextureAtlasSprite[]::new), top.toArray(TextureAtlasSprite[]::new), creep);
        OvergrownVegetation.LOGGER.info("這い上がる苔の段階の絵 {} 枚", creepCount);
        OvergrownVegetation.LOGGER.info("覆いの絵 {} 枚", side.size());
    }

    private static @Nullable TextureAtlasSprite find(TextureAtlas atlas, String name) {
        TextureAtlasSprite s = atlas.getSprite(OvergrownVegetation.id("block/cover/" + name));
        ResourceLocation n = s.contents().name();
        return n.equals(MissingTextureAtlasSprite.getLocation()) ? null : s;
    }
}
