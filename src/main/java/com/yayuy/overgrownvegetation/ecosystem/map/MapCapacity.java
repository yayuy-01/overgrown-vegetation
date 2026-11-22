package com.yayuy.overgrownvegetation.ecosystem.map;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 升の満員の頭数と中身(2026-11-21)。<b>チャンクを読み込まない</b>: バイオームは生成器のノイズから(過繁茂へ書き換えた後も元の土地で数える)、
 * 村は構造物の置き場の計算(/locate と同じ「この升に村の起点が来うるか」+ その場所のバイオームが村を許すか)から。
 * <ul>
 *   <li>動物の満員 = {@link MapTable#biomeFull} のバイオームの id か {@code #タグ}(先に書いたものが勝つ)。無ければ、動物(CREATURE)の湧きの表があるバイオームは
 *   {@link MapTable#defaultFull}、無いバイオーム(海・川・洞窟など)は 0。</li>
 *   <li>中身 = そのバイオームの動物の湧きの表の重み(他 MOD のバイオームも同じ道)。村のある升は村人 {@link MapTable#villageFull} を足す。</li>
 * </ul>
 */
public final class MapCapacity {

    private MapCapacity() {
    }

    /** 升の満員と中身。 */
    public record Info(int animals, int villagers, Mix mix) {
        public int full() {
            return animals + villagers;
        }
    }

    /** 動物の中身(種類と重み・元の最大体力)。バイオームごとに 1 つ作って使い回す。 */
    public static final class Mix {
        final EntityType<?>[] types;
        final double[] cum;
        final double[] hp;

        Mix(List<EntityType<?>> t, List<Integer> w) {
            types = t.toArray(new EntityType<?>[0]);
            cum = new double[types.length];
            hp = new double[types.length];
            double s = 0;
            for (int i = 0; i < types.length; i++) {
                s += Math.max(1, w.get(i));
                cum[i] = s;
                hp[i] = maxHealth(types[i]);
            }
            for (int i = 0; i < types.length; i++) cum[i] /= s;
        }

        /** 0〜1 の値から種類を 1 つ。 */
        int pick(double u) {
            for (int i = 0; i < cum.length; i++) if (u < cum[i]) return i;
            return cum.length - 1;
        }
    }

    private static final Map<Holder<Biome>, Mix> MIXES = new IdentityHashMap<>();
    private static Mix fallback;

    /** 湧きの表が無いのに満員を与えられたバイオームの中身(牛・豚・羊・鶏)。 */
    private static Mix fallback() {
        if (fallback == null) {
            fallback = new Mix(List.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN), List.of(8, 10, 12, 10));
        }
        return fallback;
    }

    /** バイオームの動物の中身。 */
    public static Mix mixOf(Holder<Biome> biome) {
        synchronized (MIXES) {
            Mix m = MIXES.get(biome);
            if (m != null) return m;
            List<EntityType<?>> types = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            for (MobSpawnSettings.SpawnerData d : biome.value().getMobSettings().getMobs(MobCategory.CREATURE).unwrap()) {
                if (!DefaultAttributes.hasSupplier(d.type)) continue;
                types.add(d.type);
                weights.add(d.getWeight().asInt());
            }
            m = types.isEmpty() ? fallback() : new Mix(types, weights);
            MIXES.put(biome, m);
            return m;
        }
    }

    /** バイオームの動物の満員(純粋。テストの世界のバイオームに依らない)。 */
    public static int animalsFor(Holder<Biome> biome, MapTable t) {
        for (Map.Entry<String, Integer> e : t.biomeFull.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(k.substring(1));
                if (rl != null && biome.is(TagKey.create(Registries.BIOME, rl))) return Math.max(0, e.getValue());
            } else if (biome.is(ResourceLocation.parse(k))) {
                return Math.max(0, e.getValue());
            }
        }
        return biome.value().getMobSettings().getMobs(MobCategory.CREATURE).isEmpty() ? 0 : t.defaultFull;
    }

    /** 升の中身(純粋)。 */
    public static Info infoFor(Holder<Biome> biome, boolean village, MapTable t) {
        return new Info(animalsFor(biome, t), village ? t.villageFull : 0, mixOf(biome));
    }

    /** 升の中身を世界から(チャンクを読み込まない)。 */
    public static Info compute(ServerLevel level, long cellKey, int shift, MapTable t) {
        Holder<Biome> b = biomeAt(level, cellKey, shift);
        return infoFor(b, villageIn(level, cellKey, shift), t);
    }

    /** 升の真ん中の元のバイオーム(生成器のノイズ。高さ 64)。 */
    public static Holder<Biome> biomeAt(ServerLevel level, long cellKey, int shift) {
        int size = 16 << shift;
        int x = (ChunkPos.getX(cellKey) << (shift + 4)) + size / 2, z = (ChunkPos.getZ(cellKey) << (shift + 4)) + size / 2;
        return noiseBiome(level, x, z);
    }

    private static Holder<Biome> noiseBiome(ServerLevel level, int x, int z) {
        BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        return src.getNoiseBiome(x >> 2, 64 >> 2, z >> 2, sampler);
    }

    /** 升のどれかのチャンクに村の起点が来うるか(置き場の計算 + その場所のバイオームが村を許す)。 */
    public static boolean villageIn(ServerLevel level, long cellKey, int shift) {
        Holder<StructureSet> set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getHolder(BuiltinStructureSets.VILLAGES).orElse(null);
        if (set == null) return false;
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        int n = 1 << shift, bx = ChunkPos.getX(cellKey) << shift, bz = ChunkPos.getZ(cellKey) << shift;
        for (int dx = 0; dx < n; dx++) {
            for (int dz = 0; dz < n; dz++) {
                int cx = bx + dx, cz = bz + dz;
                if (!set.value().placement().isStructureChunk(state, cx, cz)) continue;
                Holder<Biome> b = noiseBiome(level, (cx << 4) + 8, (cz << 4) + 8);
                for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                    if (e.structure().value().biomes().contains(b)) return true;
                }
            }
        }
        return false;
    }

    /** 元の最大体力(属性の既定値。無ければ 10)。 */
    @SuppressWarnings("unchecked")
    static double maxHealth(@Nullable EntityType<?> type) {
        if (type == null || !DefaultAttributes.hasSupplier(type)) return 10.0D;
        try {
            return DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type).getBaseValue(Attributes.MAX_HEALTH);
        } catch (RuntimeException e) {
            return 10.0D;
        }
    }

    static double villagerHealth() {
        return maxHealth(EntityType.VILLAGER);
    }

    static String id(EntityType<?> t) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
    }
}
