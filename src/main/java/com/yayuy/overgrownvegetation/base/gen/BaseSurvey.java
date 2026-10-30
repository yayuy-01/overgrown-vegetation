package com.yayuy.overgrownvegetation.base.gen;

import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 開発用の数え上げ {@code /ov base survey [半径] [plans]}(順 2 の着手順 2、2026-10-04): プレイヤーの周りの四角で、村が来うる場所の数と、
 * 世界生成の基地の候補ごとの判定(生まれる・バイオーム・村の近く・水・荒い)を数える。ユーザー指定「村より少し多いくらい」に間隔を合わせるための測り。
 * {@code plans} なら生まれる基地の計画も作り、兵舎のベッドが定員に足りない数・最後のレベルで広がらなかった数も出す(実際の地形での測り)。
 *
 * <p>チャンクは作らない(地形のノイズとバイオームだけ読む)。重いので裏のスレッドで数え、終わったらチャットに出す。
 */
public final class BaseSurvey {

    /** 間隔を試すときの塩(データの構造物の組と同じ値。置き場の候補の並びが変わるだけで、数の見込みには効かない)。 */
    private static final int WHAT_IF_SALT = 1702938461;

    /** 数えて結果をチャットに出す。 */
    public static int run(CommandSourceStack src, int radius, boolean plans) {
        return run(src, radius, plans, 0, 0);
    }

    /**
     * 数えて結果をチャットに出す。{@code spacing} > 0 なら、データの間隔・離れの代わりにこの値で置いたときを数える
     * (ワールドを作り直さずに間隔を試す。実際の生成は変わらない)。
     */
    public static int run(CommandSourceStack src, int radius, boolean plans, int spacing, int separation) {
        ServerLevel level = src.getLevel();
        MinecraftServer server = src.getServer();
        Structure s = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BaseStructures.ALLIED_BASE_STRUCTURE);
        StructureSet ours = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(BaseStructures.ALLIED_BASE_SET);
        StructureSet villages = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(BuiltinStructureSets.VILLAGES);
        if (!(s instanceof AlliedBaseStructure base) || ours == null || villages == null
                || !(ours.placement() instanceof RandomSpreadStructurePlacement data)
                || !(villages.placement() instanceof RandomSpreadStructurePlacement vp)) {
            src.sendFailure(Component.literal("世界生成の基地の構造物か、村の組が見つからない(データパックで変わっている?)"));
            return 0;
        }
        if (spacing > 0 && separation >= spacing) {
            src.sendFailure(Component.literal("離れは間隔より小さく"));
            return 0;
        }
        RandomSpreadStructurePlacement op = spacing > 0
                ? new RandomSpreadStructurePlacement(spacing, separation, data.spreadType(), WHAT_IF_SALT) : data;
        ChunkPos center = new ChunkPos(BlockPos.containing(src.getPosition()));
        src.sendSuccess(() -> Component.literal("数えている…(半径 " + radius + " チャンク" + (plans ? "・計画も作る" : "")
                + (spacing > 0 ? "・間隔 " + spacing + " / 離れ " + separation + " で試す" : "") + ")"), false);
        CompletableFuture.supplyAsync(() -> count(level, base, op, villages, vp, center, radius, plans), Util.backgroundExecutor())
                .whenCompleteAsync((lines, err) -> {
                    if (err != null) {
                        src.sendFailure(Component.literal("数え上げが失敗した: " + err));
                        return;
                    }
                    for (String line : lines) {
                        src.sendSuccess(() -> Component.literal(line), false);
                    }
                }, server);
        return 1;
    }

    private static List<String> count(ServerLevel level, AlliedBaseStructure base, RandomSpreadStructurePlacement op, StructureSet villages,
                                      RandomSpreadStructurePlacement vp, ChunkPos center, int radius, boolean plans) {
        long t0 = System.nanoTime();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        long seed = level.getSeed();
        Map<AlliedBaseStructure.Verdict, Integer> verdicts = new EnumMap<>(AlliedBaseStructure.Verdict.class);
        Map<BaseType, Integer> types = new EnumMap<>(BaseType.class);
        int[] levels = new int[BasePlan.MAX_LEVEL + 1];
        int candidates = 0;
        int born = 0;
        int bedShort = 0;
        int notGrown = 0;
        int spacing = op.spacing();
        List<ChunkPos> spots = new ArrayList<>();
        for (int gx = Math.floorDiv(center.x - radius, spacing); gx <= Math.floorDiv(center.x + radius, spacing); gx++) {
            for (int gz = Math.floorDiv(center.z - radius, spacing); gz <= Math.floorDiv(center.z + radius, spacing); gz++) {
                ChunkPos at = op.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
                if (Math.abs(at.x - center.x) <= radius && Math.abs(at.z - center.z) <= radius) {
                    spots.add(at);
                }
            }
        }
        candidates = spots.size();
        // 候補ごとの判定は互いに関係なく、地形のノイズとバイオームは同時に読めるので並べて数える
        List<int[]> results = spots.parallelStream().map(at -> {
            Structure.GenerationContext ctx = new Structure.GenerationContext(level.registryAccess(), gen, gen.getBiomeSource(), rs,
                    level.getServer().getStructureManager(), seed, at, level, base.biomes()::contains);
            AlliedBaseStructure.Candidate c = base.evaluate(ctx, op);
            int[] r = {c.verdict().ordinal(), -1, 0, 0, 0, at.getMiddleBlockX(), at.getMiddleBlockZ()};
            if (c.verdict() == AlliedBaseStructure.Verdict.OK && c.piece() != null) {
                AlliedBasePiece p = c.piece();
                r[1] = p.baseType().ordinal();
                r[2] = p.level();
                if (plans) {
                    BaseTerrain terrain = p.terrain();
                    BasePlan plan = BasePlanner.plan(p.seed(), p.level(), terrain, p.baseType());
                    r[3] = beds(plan) < quota(p.level()) ? 1 : 0;
                    r[4] = p.level() > 1 && plan.lobes.size() <= BasePlanner.plan(p.seed(), p.level() - 1, terrain, p.baseType()).lobes.size() ? 1 : 0;
                }
            }
            return r;
        }).toList();
        for (int[] r : results) {
            verdicts.merge(AlliedBaseStructure.Verdict.values()[r[0]], 1, Integer::sum);
            if (r[1] < 0) {
                continue;
            }
            born++;
            types.merge(BaseType.values()[r[1]], 1, Integer::sum);
            levels[r[2]]++;
            bedShort += r[3];
            notGrown += r[4];
        }
        // 生まれた基地どうしが重ならないか(隣の候補に譲る判定の点検。届きの和より近い組を数える)
        List<int[]> bornList = results.stream().filter(r -> r[1] >= 0).toList();
        int overlaps = 0;
        int nearest = Integer.MAX_VALUE;
        for (int i = 0; i < bornList.size(); i++) {
            for (int j = i + 1; j < bornList.size(); j++) {
                int[] a = bornList.get(i);
                int[] b = bornList.get(j);
                int d = Math.max(Math.abs(a[5] - b[5]), Math.abs(a[6] - b[6]));
                nearest = Math.min(nearest, d);
                if (d < AlliedBaseStructure.reach(a[2]) + AlliedBaseStructure.reach(b[2])) {
                    overlaps++;
                }
            }
        }
        int villageSpots = 0;
        int vs = vp.spacing();
        for (int gx = Math.floorDiv(center.x - radius, vs); gx <= Math.floorDiv(center.x + radius, vs); gx++) {
            for (int gz = Math.floorDiv(center.z - radius, vs); gz <= Math.floorDiv(center.z + radius, vs); gz++) {
                ChunkPos at = vp.getPotentialStructureChunk(seed, gx * vs, gz * vs);
                if (Math.abs(at.x - center.x) > radius || Math.abs(at.z - center.z) > radius) {
                    continue;
                }
                int bx = at.getMiddleBlockX();
                int bz = at.getMiddleBlockZ();
                int by = gen.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(by), QuartPos.fromBlock(bz),
                        rs.sampler());
                for (StructureSet.StructureSelectionEntry e : villages.structures()) {
                    if (e.structure().value().biomes().contains(biome)) {
                        villageSpots++;
                        break;
                    }
                }
            }
        }
        int side = (2 * radius + 1) * 16;
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "半径 %d チャンク(%d ブロック四方): 村が来うる場所 %d・世界生成の基地 %d(村の %.2f 倍)・間隔 %d / 離れ %d",
                radius, side, villageSpots, born, villageSpots > 0 ? born / (double) villageSpots : 0.0D, op.spacing(), op.separation()));
        out.add(String.format(Locale.ROOT, "  基地の候補 %d: 生まれる %d / バイオーム %d / 村・洋館の近く %d / 水 %d / 荒い %d / 隣に譲った %d",
                candidates, born, verdicts.getOrDefault(AlliedBaseStructure.Verdict.BIOME, 0),
                verdicts.getOrDefault(AlliedBaseStructure.Verdict.NEIGHBOUR, 0), verdicts.getOrDefault(AlliedBaseStructure.Verdict.WATER, 0),
                verdicts.getOrDefault(AlliedBaseStructure.Verdict.STEEP, 0), verdicts.getOrDefault(AlliedBaseStructure.Verdict.CROWDED, 0)));
        out.add(String.format(Locale.ROOT, "  生まれた基地どうしの重なり %d 組・いちばん近い中心どうし %s ブロック", overlaps,
                nearest == Integer.MAX_VALUE ? "-" : String.valueOf(nearest)));
        StringBuilder t = new StringBuilder("  種類:");
        for (BaseType bt : BaseType.values()) {
            t.append(' ').append(bt.label).append(' ').append(types.getOrDefault(bt, 0));
        }
        out.add(t.toString());
        StringBuilder l = new StringBuilder("  レベル:");
        for (int lv = 1; lv <= BasePlan.MAX_LEVEL; lv++) {
            l.append(" L").append(lv).append(' ').append(levels[lv]);
        }
        out.add(l.toString());
        if (plans) {
            out.add(String.format(Locale.ROOT, "  計画: ベッドが定員に足りない %d / %d・最後のレベルで広がらなかった %d / %d(L2 以上 %d)",
                    bedShort, born, notGrown, born, born - levels[1]));
        }
        out.add(String.format(Locale.ROOT, "  かかった時間 %.1f 秒(候補 1 つ平均 %.0f ミリ秒)", (System.nanoTime() - t0) / 1e9,
                candidates > 0 ? (System.nanoTime() - t0) / 1e6 / candidates : 0.0D));
        return out;
    }

    /**
     * GameTest: 間隔 {@code spacing} / 離れ {@code separation} で並べた候補を {@code center} の周り {@code radius} チャンクで判定し、
     * {生まれた数, 隣に譲った数, 生まれた基地どうしの重なりの組} を返す(構造物が無ければ null)。並べずに順に数える。
     */
    @javax.annotation.Nullable
    public static int[] crowding(ServerLevel level, ChunkPos center, int radius, int spacing, int separation) {
        Structure s = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BaseStructures.ALLIED_BASE_STRUCTURE);
        if (!(s instanceof AlliedBaseStructure base)) {
            return null;
        }
        RandomSpreadStructurePlacement op = new RandomSpreadStructurePlacement(spacing, separation,
                net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.LINEAR, WHAT_IF_SALT);
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        long seed = level.getSeed();
        List<int[]> born = new ArrayList<>();
        int crowded = 0;
        for (int gx = Math.floorDiv(center.x - radius, spacing); gx <= Math.floorDiv(center.x + radius, spacing); gx++) {
            for (int gz = Math.floorDiv(center.z - radius, spacing); gz <= Math.floorDiv(center.z + radius, spacing); gz++) {
                ChunkPos at = op.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
                if (Math.abs(at.x - center.x) > radius || Math.abs(at.z - center.z) > radius) {
                    continue;
                }
                Structure.GenerationContext ctx = new Structure.GenerationContext(level.registryAccess(), gen, gen.getBiomeSource(), rs,
                        level.getServer().getStructureManager(), seed, at, level, base.biomes()::contains);
                AlliedBaseStructure.Candidate c = base.evaluate(ctx, op);
                if (c.verdict() == AlliedBaseStructure.Verdict.CROWDED) {
                    crowded++;
                } else if (c.verdict() == AlliedBaseStructure.Verdict.OK && c.piece() != null) {
                    born.add(new int[]{at.getMiddleBlockX(), at.getMiddleBlockZ(), c.piece().level()});
                }
            }
        }
        int overlaps = 0;
        for (int i = 0; i < born.size(); i++) {
            for (int j = i + 1; j < born.size(); j++) {
                int[] a = born.get(i);
                int[] b = born.get(j);
                if (Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1])) < AlliedBaseStructure.reach(a[2]) + AlliedBaseStructure.reach(b[2])) {
                    overlaps++;
                }
            }
        }
        return new int[]{born.size(), crowded, overlaps};
    }

    /** 兵舎のベッドの数(テントの設計図: 小型 6・大型 13。tools/base-preview/PlanCheck と同じ数え方)。 */
    static int beds(BasePlan plan) {
        int n = 0;
        for (Plot q : plan.plots) {
            if (q.kind() == BuildingKind.BARRACKS_TENT) {
                n += 6;
            } else if (q.kind() == BuildingKind.BARRACKS_TENT_LARGE) {
                n += 13;
            }
        }
        return n;
    }

    private static int quota(int level) {
        int n = 0;
        for (int v : BaseGarrison.quota(level).values()) {
            n += v;
        }
        return n;
    }

    private BaseSurvey() {
    }
}
