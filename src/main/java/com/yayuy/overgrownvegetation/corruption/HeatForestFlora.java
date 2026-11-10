package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.block.HeatStrandBlock;
import com.yayuy.overgrownvegetation.block.HorsetailBlock;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;

/**
 * <b>ネザーの熱水の森の小さな植物</b>(2026-11-09 段階 1)。ネザーでは {@link CorruptionSpreader#growPlants} と
 * {@code growSurfacePlants} がここへ回り、オーバーワールドの下草・洞窟の植物・壁の植物は生えない。
 *
 * <p>汚染されたブロックの空気に面した面ごとに 1 回引く(オーバーワールドと同じく面ごと、確率は呼び手の {@code chance} = growthChance)。
 * 植物の選び方は繁茂と過繁茂で同じ(オーバーワールドと同じ。過繁茂は深部の一括置換で天井・壁まで届き、繁茂は床だけ)。
 * 値はどれも仮決め(下絵 4 回目の密度の見本: 上面の 3 割が地面に貼る物、残りの 2 割が 2 段の草、残りが 1 段の下草)。
 */
public final class HeatForestFlora {

    // ---- 上面(仮決め) ----
    /** 地面に貼る物の割合(オーバーワールドの floorCarpetChance と同じ)。中身は薄い苔 35・根の網 25・茎の屑 25・綿 15。 */
    static final double CARPET = 0.30;
    /** 根の網のうち、結び目(B)にする割合(仕様: 結び目の目印だけ)。 */
    static final double KNOT_IN_NET = 0.08;
    /** 2 段の草の割合(オーバーワールドの tallGrowthChance と同じ)。 */
    static final double TALL = 0.20;
    /** 1 段の下草の重み(巻きシダが一番多い・管虫と煙突はまれ・棘の灌木は見せ場に少し)。 */
    private static final Object[][] LOW = {
            {ModBlocks.HEAT_CURLED_FERN, 30.0}, {ModBlocks.HEAT_ZENMAI, 12.0}, {ModBlocks.HEAT_CLUBMOSS, 12.0},
            {ModBlocks.HEAT_SPORE_CAP, 12.0}, {ModBlocks.HEAT_FUR_FLOWER, 8.0}, {ModBlocks.HEAT_FUR_TUFT, 8.0},
            {ModBlocks.HEAT_FRUIT_BUSH, 7.0}, {ModBlocks.HEAT_SCALE_SHRUB, 7.0}, {ModBlocks.HEAT_THORN_SCALE_SHRUB, 1.5},
            {ModBlocks.HEAT_TUBE_WORMS, 1.0}, {ModBlocks.HEAT_SMALL_CHIMNEY, 1.0}};

    // ---- 下面(仮決め。オーバーワールドのように毎マスに垂らすと根の壁になるので、割合を絞った) ----
    // 11-09 ゲームで確かめて減らした(12%・3%・10% で長さ 6〜18 だと、高い天井の下が根の林になって向こうが見えなかった)
    static final double SKY_CURTAIN = 0.04, SKY_ROPE = 0.01, SKY_POD = 0.06;
    /** 根のカーテンの長さ(最短と幅)。 */
    static final int CURTAIN_MIN = 4, CURTAIN_SPREAD = 9;
    static final double LOW_HANG_MOSS = 0.18, LOW_HANG_MOSS_SHORT = 0.14, LOW_BEARD = 0.18, LOW_VINE = 0.10, LOW_FIDDLE = 0.14, LOW_STRAWS = 0.01;
    /** 溶岩の真上の低い天井に吊り鐘を下げる確率。 */
    static final double BELL_OVER_LAVA = 0.25;
    /** 天井の下の空きがこれ以下なら垂れ物を下げない(頭の上がふさがるのを避ける)。 */
    static final int MIN_GAP_FOR_HANG = 3;

    // ---- 側面(仮決め) ----
    static final double WALL_SCALE = 0.20, WALL_FERN = 0.15; // 11-09 追記 1: 這い鱗は 4 割 → 2 割(ユーザー決定は「2 割前後」、値は仮)。形の変種 3 つは blockstate で

    // ---- そのまま残るオーバーワールドの植物(仕様の控えの表。数は仮) ----
    /** ポータルのそば(半径 6)の床に、オーバーワールドの植物を置く確率。半径 6 の床およそ 110 マスで 15〜25 株。 */
    static final double PORTAL_OW = 0.18;
    static final int PORTAL_RADIUS = 6;
    /** それ以外の床の「まれ」(チャンクに 0.1 株 ≒ 床 256 マスに 0.1)。 */
    static final double STRAY_OW = 0.0004;

    /** {@code host}(汚染された、または覆われたブロック)の空気に面した面へ植物を生やす。 */
    public static void grow(ServerLevel level, BlockPos host, BlockState hostState, double chance) {
        if (chance <= 0.0D) {
            return;
        }
        RandomSource random = level.random;
        BlockPos up = host.above();
        if (level.isEmptyBlock(up) && hostState.isFaceSturdy(level, host, Direction.UP)) {
            growUp(level, host, up, hostState, chance, random);
        }
        BlockPos down = host.below();
        if (level.isEmptyBlock(down) && hostState.isFaceSturdy(level, host, Direction.DOWN)) {
            growDown(level, down, chance, random);
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = host.relative(dir);
            if (level.isEmptyBlock(side) && hostState.isFaceSturdy(level, host, dir) && random.nextDouble() < chance) {
                growSide(level, side, dir, random);
            }
        }
    }

    // ------------------------------------------------------------------ 上面
    private static void growUp(ServerLevel level, BlockPos host, BlockPos at, BlockState hostState, double chance, RandomSource random) {
        if (nearPortal(level, at)) {
            if (random.nextDouble() < PORTAL_OW) {
                placeOverworld(level, at, random);
            }
            return;
        }
        if (random.nextDouble() < STRAY_OW) {
            placeOverworld(level, at, random);
            return;
        }
        if (HeatForestTrees.tryUp(level, host, at, hostState, random)) {
            return; // 木の段階(11-10): 格子の印の所は普通の木・管の樹・煙突の塚
        }
        if (random.nextDouble() >= chance) {
            return;
        }
        // 殻の地肌(溶岩の海の上・敷物の無い所)には、貼る物だけ(薄い苔と綿)
        if (hostState.is(ModBlocks.HEAT_COOLED_CRUST.get())) {
            if (random.nextDouble() < 0.5) {
                place(level, at, (random.nextDouble() < 0.6 ? ModBlocks.HEAT_THIN_MOSS : ModBlocks.HEAT_VENT_FLOC).get().defaultBlockState());
            }
            return;
        }
        double r = random.nextDouble();
        if (r < CARPET) {
            double c = random.nextDouble();
            DeferredBlock<Block> pick = c < 0.35 ? ModBlocks.HEAT_THIN_MOSS
                    : c < 0.60 ? (random.nextDouble() < KNOT_IN_NET ? ModBlocks.HEAT_ROOT_NET_KNOT : ModBlocks.HEAT_ROOT_NET)
                    : c < 0.85 ? ModBlocks.HEAT_STEM_LITTER : ModBlocks.HEAT_VENT_FLOC;
            place(level, at, pick.get().defaultBlockState());
            return;
        }
        if (random.nextDouble() < TALL) {
            double t = random.nextDouble();
            if (t < 0.35) {
                if (level.isEmptyBlock(at.above(2))) {
                    int height = 2 + random.nextInt(3);
                    while (height > 2 && !level.isEmptyBlock(at.above(height))) {
                        height--;
                    }
                    if (level.isEmptyBlock(at.above())) {
                        HorsetailBlock.placeStem(level, at, height, ModBlocks.HEAT_HORSETAIL.get().defaultBlockState());
                        return;
                    }
                }
            } else {
                DeferredBlock<Block> tall = t < 0.70 ? ModBlocks.HEAT_TALL_FERN : ModBlocks.HEAT_TALL_SPORE_CAP;
                BlockState state = tall.get().defaultBlockState();
                if (level.isEmptyBlock(at.above()) && state.canSurvive(level, at)) {
                    DoublePlantBlock.placeAt(level, state, at, Block.UPDATE_CLIENTS);
                    return;
                }
            }
        }
        place(level, at, pickLow(random).get().defaultBlockState());
    }

    @SuppressWarnings("unchecked")
    private static DeferredBlock<Block> pickLow(RandomSource random) {
        double total = 0;
        for (Object[] e : LOW) total += (Double) e[1];
        double r = random.nextDouble() * total;
        for (Object[] e : LOW) {
            r -= (Double) e[1];
            if (r < 0) return (DeferredBlock<Block>) e[0];
        }
        return ModBlocks.HEAT_CURLED_FERN;
    }

    // ------------------------------------------------------------------ 下面
    private static void growDown(ServerLevel level, BlockPos at, double chance, RandomSource random) {
        if (random.nextDouble() >= chance) {
            return;
        }
        int gap = HeatForestGround.openBelow(level, at.above(), HeatForestGround.HIGH_CEILING);
        if (gap < HeatForestGround.HIGH_CEILING) {
            // 下にあるのが逆さの木(幹・樹冠・気根)なら、天井と樹冠の間のすき間。低い天井の垂れ物は下げない(11-10 木の段階)
            BlockState stop = level.getBlockState(at.below(gap));
            if (stop.is(ModBlocks.HEAT_SKY_LEAVES.get()) || stop.is(ModBlocks.HEAT_BARK.get()) || stop.is(ModBlocks.HEAT_AERIAL_ROOT.get())
                    || stop.is(ModBlocks.HEAT_SKY_LEAF_FRINGE.get())) {
                return;
            }
        }
        if (gap <= MIN_GAP_FOR_HANG) {
            return;
        }
        double r = random.nextDouble();
        if (gap >= HeatForestGround.HIGH_CEILING) {
            if (HeatForestTrees.tryDown(level, at, random)) {
                return; // 木の段階(11-10): 格子の印の所は天井の逆さの木
            }
            // 高い天井 = 空の側(光の苔 A の下)。根のカーテン A が主、B は少し、袋の房はところどころ
            if (r < SKY_CURTAIN) {
                strand(level, at, ModBlocks.HEAT_ROOT_CURTAIN, CURTAIN_MIN + random.nextInt(CURTAIN_SPREAD), gap);
            } else if (r < SKY_CURTAIN + SKY_ROPE) {
                strand(level, at, ModBlocks.HEAT_ROOT_ROPE, CURTAIN_MIN + random.nextInt(CURTAIN_SPREAD), gap);
            } else if (r < SKY_CURTAIN + SKY_ROPE + SKY_POD) {
                place(level, at, ModBlocks.HEAT_POD_CLUSTER.get().defaultBlockState());
            }
            return;
        }
        // 低い天井・張り出しの下 = 地上の側の垂れ物。溶岩の真上は吊り鐘
        if (lavaBelow(level, at, gap) && random.nextDouble() < BELL_OVER_LAVA) {
            place(level, at, ModBlocks.HEAT_BELL.get().defaultBlockState());
            return;
        }
        double acc = LOW_HANG_MOSS;
        if (r < acc) { place(level, at, ModBlocks.HEAT_HANGING_MOSS.get().defaultBlockState()); return; }
        if (r < (acc += LOW_HANG_MOSS_SHORT)) { place(level, at, ModBlocks.HEAT_HANGING_MOSS_SHORT.get().defaultBlockState()); return; }
        if (r < (acc += LOW_BEARD)) { place(level, at, ModBlocks.HEAT_BEARD_LICHEN.get().defaultBlockState()); return; }
        if (r < (acc += LOW_VINE)) { strand(level, at, ModBlocks.HEAT_GLOW_VINE, 2 + random.nextInt(3), gap); return; }
        if (r < (acc += LOW_FIDDLE)) { place(level, at, ModBlocks.HEAT_HANGING_FIDDLEHEAD.get().defaultBlockState()); return; }
        if (r < acc + LOW_STRAWS) { place(level, at, ModBlocks.HEAT_MINERAL_STRAWS.get().defaultBlockState()); }
    }

    /** 紐を {@code length} 段垂らす。床の手前 2 段で止める。 */
    private static void strand(ServerLevel level, BlockPos top, DeferredBlock<Block> block, int length, int gap) {
        int len = Math.min(length, gap - 2);
        if (len < 1) {
            return;
        }
        BlockState base = block.get().defaultBlockState();
        if (!base.canSurvive(level, top)) {
            return;
        }
        HeatStrandBlock.placeStrand(level, top, len, base);
    }

    /** 天井の下の空きの下端が溶岩か。 */
    private static boolean lavaBelow(ServerLevel level, BlockPos at, int gap) {
        return level.getBlockState(at.below(gap)).is(Blocks.LAVA);
    }

    // ------------------------------------------------------------------ 側面
    private static void growSide(ServerLevel level, BlockPos at, Direction away, RandomSource random) {
        double r = random.nextDouble();
        DeferredBlock<Block> block = r < WALL_SCALE ? ModBlocks.HEAT_WALL_SCALE : r < WALL_SCALE + WALL_FERN ? ModBlocks.HEAT_WALL_FERN : null;
        if (block == null) {
            return;
        }
        place(level, at, block.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, away));
    }

    // ------------------------------------------------------------------ オーバーワールドの植物(元の絵のまま)
    private static void placeOverworld(ServerLevel level, BlockPos at, RandomSource random) {
        int k = random.nextInt(5);
        switch (k) {
            case 0 -> {
                BlockState fern = ModBlocks.OVERGROWN_TALL_FERN.get().defaultBlockState();
                if (level.isEmptyBlock(at.above()) && fern.canSurvive(level, at)) {
                    DoublePlantBlock.placeAt(level, fern, at, Block.UPDATE_CLIENTS);
                }
            }
            case 1 -> place(level, at, ModBlocks.OVERGROWN_SPORE_BLOOM.get().defaultBlockState());
            case 2 -> place(level, at, ModBlocks.OVERGROWN_MOSS_LAYER.get().defaultBlockState()
                    .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
            case 3 -> place(level, at, ModBlocks.LYCOPSID_CARPET.get().defaultBlockState());
            default -> place(level, at, ModBlocks.PEAT_SPROUTS.get().defaultBlockState());
        }
    }

    // ポータルの近さ(チャンクごとに 10 秒ためる。深部の一括置換は 1 チャンクで何千回もここを通る)
    private static long cacheKey = Long.MIN_VALUE;
    private static long cacheTime = Long.MIN_VALUE;
    private static java.util.List<BlockPos> cachePortals = java.util.List.of();

    static boolean nearPortal(ServerLevel level, BlockPos at) {
        long key = SectionPos.asLong(at.getX() >> 4, 0, at.getZ() >> 4);
        long now = level.getGameTime();
        if (key != cacheKey || now - cacheTime > 200L || now < cacheTime) {
            cacheKey = key;
            cacheTime = now;
            BlockPos center = new BlockPos((at.getX() & ~15) + 8, at.getY(), (at.getZ() & ~15) + 8);
            cachePortals = level.getPoiManager()
                    .getInRange(h -> h.is(PoiTypes.NETHER_PORTAL), center, 16 + PORTAL_RADIUS, PoiManager.Occupancy.ANY)
                    .map(r -> r.getPos()).toList();
        }
        if (cachePortals.isEmpty()) {
            return false;
        }
        int r2 = PORTAL_RADIUS * PORTAL_RADIUS;
        for (BlockPos p : cachePortals) {
            if (p.distSqr(at) <= r2) {
                return true;
            }
        }
        return false;
    }

    private static void place(ServerLevel level, BlockPos at, BlockState state) {
        if (state.canSurvive(level, at)) {
            level.setBlock(at, state, Block.UPDATE_CLIENTS);
        }
    }

    private HeatForestFlora() {
    }
}
