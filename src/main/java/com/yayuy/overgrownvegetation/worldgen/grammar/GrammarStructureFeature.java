package com.yayuy.overgrownvegetation.worldgen.grammar;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code data/<namespace>/grammar/<path>.json}(structure-grammar 文法)を {@link GrammarEngine} で
 * 解釈して配置する Feature。ブラウザ製作ツール({@code tools/structure-grammar/})で形を詰めて
 * 書き出した文法を、Java を書かずにそのまま生やせる。
 *
 * <p>既存の {@code forced_tree} / {@code giant_tree} / {@code structure_tree} と並行。configured_feature:
 * <pre>{@code
 * { "type": "overgrown_vegetation:grammar_structure",
 *   "config": {
 *     "grammar": "overgrown_vegetation:overgrown_tree_short",
 *     "palette_overrides": { "trunk": { "block": "minecraft:oak_log", "properties": { "axis": "y" } } },
 *     "stage_over_ticks": false
 *   } }
 * }</pre>
 *
 * <ul>
 *   <li>シードは {@code context.random().nextLong()}(MC が既にワールドシード + 座標で撹拌済み)。</li>
 *   <li>{@code palette_overrides} … 文法のパレットのキーを差し替える(同じ形で木の種類だけ変える等)。</li>
 *   <li>{@code stage_over_ticks: true} … {@link GrammarGrowthQueue} 経由で複数ティックに分けて設置。
 *       巨大構造向け。苗木成長時のみ有効(configured_feature 単体でも動くが即時設置になる)。</li>
 *   <li>{@code place_nbt} オペ … {@code data/<ns>/structure/<path>.nbt} をその位置に貼る。</li>
 * </ul>
 */
public class GrammarStructureFeature extends Feature<GrammarStructureFeature.Config> {

    /** {@link com.yayuy.overgrownvegetation.worldgen.ForcedTreeFeature} と同じく普通の設置。 */
    private static final int UPDATE_FLAGS = Block.UPDATE_ALL;

    private final Map<String, BlockState> stateCache = new HashMap<>();

    public GrammarStructureFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> context) {
        WorldGenLevel level = context.level();
        Config config = context.config();

        JsonObject grammar = GrammarRegistry.get(config.grammar());
        if (grammar == null) {
            OvergrownVegetation.LOGGER.warn("grammar_structure: 文法 {} が見つかりません "
                    + "(data/<ns>/grammar/<path>.json を確認)", config.grammar());
            return false;
        }
        grammar = withOverrides(grammar, config.paletteOverrides());

        GrammarEngine.Result result;
        try {
            long seed = context.random().nextLong();
            result = GrammarEngine.run(grammar, seed, new LevelTerrain(level), null);
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("grammar_structure: 文法 {} の生成に失敗", config.grammar(), e);
            return false;
        }

        for (String w : result.warnings) {
            OvergrownVegetation.LOGGER.warn("grammar_structure {}: {}", config.grammar(), w);
        }
        if (result.truncated) {
            OvergrownVegetation.LOGGER.warn("grammar_structure {}: maxBlocks に達して打ち切られました", config.grammar());
        }
        if (result.blocks.isEmpty() && result.placements.isEmpty()) {
            return false;
        }

        // originMode: sapling = 原点 = そのまま / center = 水平中心を原点に合わせる。
        int shiftX = 0;
        int shiftZ = 0;
        JsonObject settings = grammar.has("settings") ? grammar.getAsJsonObject("settings") : null;
        if (settings != null && "center".equals(optString(settings, "originMode"))) {
            shiftX = -(result.bounds[0] + result.bounds[3]) / 2;
            shiftZ = -(result.bounds[2] + result.bounds[5]) / 2;
        }
        BlockPos origin = context.origin();

        // NBT 断片(place_nbt)を先に貼る。
        placeTemplates(level, context.random(), origin, shiftX, shiftZ, config.grammar(), result);

        // 本体のブロック。
        boolean staged = config.stageOverTicks() && level.getLevel() instanceof ServerLevel;
        List<GrammarGrowthQueue.StagedBlock> jobBlocks = staged ? new ArrayList<>(result.blocks.size()) : null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (GrammarGrid.Cell cell : result.blocks) {
            BlockState state = parseState(level, cell.state);
            if (state == null) {
                continue;
            }
            int wx = origin.getX() + cell.x + shiftX;
            int wy = origin.getY() + cell.y;
            int wz = origin.getZ() + cell.z + shiftZ;
            if (staged) {
                jobBlocks.add(new GrammarGrowthQueue.StagedBlock(BlockPos.asLong(wx, wy, wz), state));
            } else {
                level.setBlock(pos.set(wx, wy, wz), state, UPDATE_FLAGS);
            }
            placed++;
        }

        if (staged && !jobBlocks.isEmpty()) {
            GrammarGrowthQueue.enqueue((ServerLevel) level.getLevel(), jobBlocks);
        } else if (config.stageOverTicks() && !staged) {
            OvergrownVegetation.LOGGER.warn("grammar_structure {}: stage_over_ticks は ServerLevel でのみ有効。即時設置しました",
                    config.grammar());
        }
        return placed > 0 || !result.placements.isEmpty();
    }

    private void placeTemplates(WorldGenLevel level, RandomSource random, BlockPos origin,
                                int shiftX, int shiftZ, ResourceLocation grammarId, GrammarEngine.Result result) {
        if (result.placements.isEmpty() || !((Object) level.getLevel() instanceof ServerLevel serverLevel)) {
            if (!result.placements.isEmpty()) {
                OvergrownVegetation.LOGGER.warn("grammar_structure {}: place_nbt は ServerLevel でのみ動作します", grammarId);
            }
            return;
        }
        var manager = serverLevel.getStructureManager();
        for (GrammarEngine.Placement p : result.placements) {
            Optional<StructureTemplate> tmpl = manager.get(ResourceLocation.parse(p.id()));
            if (tmpl.isEmpty()) {
                OvergrownVegetation.LOGGER.warn("grammar_structure {}: 構造 {} が見つかりません "
                        + "(data/<ns>/structure/<path>.nbt)", grammarId, p.id());
                continue;
            }
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setIgnoreEntities(true)
                    .setRotation(rotationOf(p.rotation()))
                    .setMirror("front_back".equals(p.mirror()) ? Mirror.FRONT_BACK
                            : "left_right".equals(p.mirror()) ? Mirror.LEFT_RIGHT : Mirror.NONE)
                    .addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);
            BlockPos at = new BlockPos(origin.getX() + p.x() + shiftX, origin.getY() + p.y(),
                    origin.getZ() + p.z() + shiftZ);
            tmpl.get().placeInWorld(level, at, at, settings, random, Block.UPDATE_ALL);
        }
    }

    private static Rotation rotationOf(int deg) {
        return switch (((deg % 360) + 360) % 360) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** palette_overrides のキーを文法のパレットに上書きした新しい JsonObject を返す(元は変更しない)。 */
    private static JsonObject withOverrides(JsonObject grammar, JsonObject overrides) {
        if (overrides == null || overrides.size() == 0) {
            return grammar;
        }
        JsonObject copy = grammar.deepCopy();
        JsonObject palette = copy.has("palette") ? copy.getAsJsonObject("palette") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : overrides.entrySet()) {
            palette.add(e.getKey(), e.getValue());
        }
        copy.add("palette", palette);
        return copy;
    }

    private static String optString(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private BlockState parseState(WorldGenLevel level, String normalized) {
        if (stateCache.containsKey(normalized)) {
            return stateCache.get(normalized);
        }
        try {
            BlockState state = BlockStateParser.parseForBlock(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), normalized, false).blockState();
            stateCache.put(normalized, state);
            return state;
        } catch (Exception e) {
            OvergrownVegetation.LOGGER.warn("grammar_structure: blockstate を解釈できません: {}", normalized);
            stateCache.put(normalized, null);
            return null;
        }
    }

    /**
     * @param grammar          文法 ID({@code data/<namespace>/grammar/<path>.json})
     * @param paletteOverrides 文法パレットのキーを差し替える(空可)
     * @param stageOverTicks   大量ブロックを複数ティックに分けて設置するか({@link GrammarGrowthQueue})
     */
    public record Config(ResourceLocation grammar, JsonObject paletteOverrides, boolean stageOverTicks)
            implements FeatureConfiguration {

        public static final Codec<Config> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        ResourceLocation.CODEC.fieldOf("grammar").forGetter(Config::grammar),
                        ExtraCodecs.JSON.optionalFieldOf("palette_overrides", new JsonObject())
                                .xmap(j -> j.isJsonObject() ? j.getAsJsonObject() : new JsonObject(),
                                        j -> (JsonElement) j)
                                .forGetter(Config::paletteOverrides),
                        Codec.BOOL.optionalFieldOf("stage_over_ticks", false).forGetter(Config::stageOverTicks))
                .apply(instance, Config::new));
    }
}
