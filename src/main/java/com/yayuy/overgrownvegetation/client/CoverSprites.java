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
     * {@code water}・{@code sea} は水に接した面の絵(川・池・海、2026-11-01。全部の面で同じ絵。無ければ空)。
     */
    public record Set(TextureAtlasSprite[] side, TextureAtlasSprite[] top, TextureAtlasSprite[][] creep, TextureAtlasSprite[] water,
                      TextureAtlasSprite[] sea) {
    }

    private static volatile @Nullable Set current;
    /** ネザー版の覆いの絵({@code block/cover/heat_cover_1..3}、2026-11-09 熱水の森の追記 1)。無ければ null(ふつうの絵を使う)。 */
    private static volatile @Nullable Set nether;
    /** ネザー版の覆いの絵の名前(仮決め: 熱の敷物の絵から作った 3 枚)。 */
    private static final List<String> NETHER_NAMES = List.of("heat_cover_1", "heat_cover_2", "heat_cover_3");

    private CoverSprites() {
    }

    public static @Nullable Set current() {
        return current;
    }

    /**
     * その次元で使う覆いの絵。ネザーは、植物が適応した後(最初の汚染から 10 分。{@link HeatForestClockClient})だけネザー版(熱水の森の色)、
     * 適応の前はオーバーワールドの覆い(2026-11-10 ユーザー決定: 10 分の前後で分ける)。
     */
    public static @Nullable Set forLevel(@Nullable net.minecraft.world.level.Level level) {
        Set n = nether;
        if (n != null && level != null && level.dimension() == net.minecraft.world.level.Level.NETHER && HeatForestClockClient.adapted()) {
            return n;
        }
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
        TextureAtlasSprite[] water = load(atlas, DesignData.cover().waterTextures());
        TextureAtlasSprite[] sea = load(atlas, DesignData.cover().seaTextures());
        current = side.isEmpty() ? null : new Set(side.toArray(TextureAtlasSprite[]::new), top.toArray(TextureAtlasSprite[]::new), creep,
                water, sea.length > 0 ? sea : water);
        TextureAtlasSprite[] heat = load(atlas, NETHER_NAMES);
        nether = heat.length == 0 ? null : new Set(heat, heat, creep, heat, heat);
        OvergrownVegetation.LOGGER.info("ネザー版の覆いの絵 {} 枚", heat.length);
        OvergrownVegetation.LOGGER.info("覆いの水中の絵 川・池 {} 枚・海 {} 枚", water.length, sea.length);
        OvergrownVegetation.LOGGER.info("這い上がる苔の段階の絵 {} 枚", creepCount);
        OvergrownVegetation.LOGGER.info("覆いの絵 {} 枚", side.size());
    }

    private static TextureAtlasSprite[] load(TextureAtlas atlas, List<String> names) {
        List<TextureAtlasSprite> out = new ArrayList<>();
        for (String name : names) {
            TextureAtlasSprite s = find(atlas, name);
            if (s == null) {
                OvergrownVegetation.LOGGER.warn("覆いの水中の絵 textures/block/cover/{}.png がアトラスに無い(飛ばす)", name);
            } else {
                out.add(s);
            }
        }
        return out.toArray(TextureAtlasSprite[]::new);
    }

    private static @Nullable TextureAtlasSprite find(TextureAtlas atlas, String name) {
        TextureAtlasSprite s = atlas.getSprite(OvergrownVegetation.id("block/cover/" + name));
        ResourceLocation n = s.contents().name();
        return n.equals(MissingTextureAtlasSprite.getLocation()) ? null : s;
    }
}
