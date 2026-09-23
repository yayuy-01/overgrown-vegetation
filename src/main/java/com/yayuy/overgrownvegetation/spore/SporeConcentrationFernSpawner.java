package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 胞子濃度({@link SporeConcentration})が高いチャンクほど、過繁茂したブロック以外の地表に
 * 汚染シダ({@link BlightFernEntity})が自然発生しやすくなる。
 *
 * <p>各プレイヤーの周囲でランダムな地点を選び、その足場ブロックが「汚染ブロックではない」
 * (＝過繁茂したブロック以外)場合のみ、そのチャンクの胞子濃度に応じた確率で汚染シダを 1 体湧かせる。
 * 湧いた汚染シダはそこから周囲を侵食していくので、過繁茂バイオームの縁の外側で汚染が新たに始まる。
 *
 * <p>候補地点は試行ごとに {@code sporeConcentrationFernSpawnUndergroundChance}(既定 0.5)の確率で
 * 「地下(洞窟)」を、残りの確率で「地上の地表」を狙う。地下狙いのときはランダムな Y から下方向へ
 * 走査し、天井が閉じている(空が見えない)空洞の床を探す。汚染シダのこの発生経路だけが地下にも湧く
 * (死体ブロックの孵化・自己増殖・レイドの根などは従来どおり)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeConcentrationFernSpawner {

    /** プレイヤーに近すぎる位置には湧かせない下限距離(ブロック)。 */
    private static final int MIN_DISTANCE = 16;
    /** 地下狙いのとき、ランダム Y からこの数だけ下方向へ空洞の床を探す。 */
    private static final int UNDERGROUND_SCAN_DEPTH = 24;
    /** 「近くに汚染シダが多すぎる」判定に使う立方体の半径(ブロック)。負荷のため小さめ。 */
    private static final int CLUMP_CHECK_RADIUS = 32;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.sporeConcentrationFernSpawnEnabled.get()) {
            return;
        }
        int interval = cfg.sporeConcentrationFernSpawnIntervalTicks.get();
        if (level.getGameTime() % interval != 0L) {
            return;
        }

        int attempts = cfg.sporeConcentrationFernSpawnAttempts.get();
        if (attempts <= 0) {
            return;
        }
        int radius = cfg.sporeConcentrationFernSpawnRadius.get();
        if (radius <= MIN_DISTANCE) {
            return;
        }
        double minConcentration = cfg.sporeConcentrationFernSpawnMinConcentration.get();
        int maxNearby = cfg.sporeConcentrationFernSpawnMaxNearby.get();
        // どんなに濃度が高くてもこの確率は超えないので、これを下回らなかった試行は
        // 地点探索も胞子濃度計算もせず即座に捨てる(負荷対策)。
        double maxChance = Math.max(cfg.sporeConcentrationFernSpawnChanceAtMin.get(),
                cfg.sporeConcentrationFernSpawnChanceAtFull.get());
        RandomSource random = level.random;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (int i = 0; i < attempts; i++) {
                double roll = random.nextDouble();
                if (roll >= maxChance) {
                    continue;
                }
                tryOnce(level, player, radius, minConcentration, maxNearby, roll, random);
            }
        }
    }

    /**
     * 胞子濃度({@code concentration}、0〜100)に応じた「発生を試みた 1 回あたりに汚染シダが湧く確率」。
     * 下限濃度({@code sporeConcentrationFernSpawnMinConcentration}、既定 10%)で
     * {@code sporeConcentrationFernSpawnChanceAtMin}(既定 0.001)、100% で
     * {@code sporeConcentrationFernSpawnChanceAtFull}(既定 0.8)。その間は対数(等比)補間 or 線形補間。
     * 下限未満は 0。
     */
    public static double chanceFor(double concentration) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double minConc = cfg.sporeConcentrationFernSpawnMinConcentration.get();
        if (concentration < minConc) {
            return 0.0;
        }
        double chanceAtMin = cfg.sporeConcentrationFernSpawnChanceAtMin.get();
        double chanceAtFull = cfg.sporeConcentrationFernSpawnChanceAtFull.get();
        double span = 100.0 - minConc;
        if (span <= 1.0E-6) {
            return chanceAtFull;
        }
        double t = (concentration - minConc) / span;
        if (t <= 0.0) {
            return chanceAtMin;
        }
        if (t >= 1.0) {
            return chanceAtFull;
        }
        if (cfg.sporeConcentrationFernSpawnChanceLogInterpolation.get() && chanceAtMin > 0.0 && chanceAtFull > 0.0) {
            // 等比補間: chanceAtMin * (chanceAtFull/chanceAtMin)^t
            return chanceAtMin * Math.pow(chanceAtFull / chanceAtMin, t);
        }
        return chanceAtMin + t * (chanceAtFull - chanceAtMin);
    }

    private static void tryOnce(ServerLevel level, ServerPlayer player, int radius, double minConcentration,
                                int maxNearby, double roll, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2.0D;
        int distance = MIN_DISTANCE + random.nextInt(radius - MIN_DISTANCE + 1);
        int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

        if (!level.hasChunk(x >> 4, z >> 4)) {
            return;
        }

        double undergroundChance = OvergrownVegetationConfig.COMMON.sporeConcentrationFernSpawnUndergroundChance.get();
        boolean underground = undergroundChance > 0.0D && random.nextDouble() < undergroundChance;
        BlockPos spawnPos = underground
                ? findUndergroundSpot(level, x, z, random)
                : findSurfaceSpot(level, x, z);
        if (spawnPos == null) {
            return;
        }

        double concentration = SporeConcentration.percentAt(level, new ChunkPos(spawnPos));
        if (concentration < minConcentration) {
            return;
        }

        // 呼び出し元で引いた roll を再利用する(roll < chanceFor(concentration) なら成功、確率は正しい)。
        // 光合成中(寄生植物装備 3 段階目・日なた)のプレイヤーの周りでは確率が上がる。
        // 噴出孔の範囲では、上がった % と同じだけ湧き率も上がる(ユーザー指定 2026-09-19)。
        double chance = chanceFor(concentration)
                * com.yayuy.overgrownvegetation.item.PlantArmor.fernSpawnMultiplier(player)
                * com.yayuy.overgrownvegetation.nest.NestVents.spawnMultiplierAt(level, spawnPos);
        if (roll >= chance) {
            return;
        }

        if (maxNearby > 0) {
            AABB area = new AABB(spawnPos).inflate(CLUMP_CHECK_RADIUS);
            int nearby = level.getEntitiesOfClass(BlightFernEntity.class, area).size();
            if (nearby >= maxNearby) {
                return;
            }
        }

        // 湧く地点が水中なら汚染シダの代わりに汚染水草が湧く(BlightFernPlacement 参照)。
        BlightFernPlacement.spawn(level, spawnPos, MobSpawnType.NATURAL);
    }

    /**
     * 地上の地表(空が見える面)、または水中の列なら海底の足場候補。妥当でなければ {@code null}。
     */
    private static BlockPos findSurfaceSpot(ServerLevel level, int x, int z) {
        int motionBlocking = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int oceanFloor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
        if (oceanFloor < motionBlocking) {
            // 水中の列。海底の 1 つ上を狙う(汚染水草になる)。
            BlockPos pos = new BlockPos(x, oceanFloor, z);
            return isValidWaterSpot(level, pos) ? pos : null;
        }
        BlockPos pos = new BlockPos(x, motionBlocking, z);
        return isValidSpot(level, pos, false) ? pos : null;
    }

    /**
     * {@code pos} に汚染水草を湧かせられるか。足場({@code pos.below()})が固体かつ汚染ブロックでなく、
     * {@code pos} と {@code pos.above()} が水で満たされていること。
     */
    private static boolean isValidWaterSpot(ServerLevel level, BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.blocksMotion() || ground.is(ModTags.CORRUPTED)) {
            return false;
        }
        return level.getFluidState(pos).is(FluidTags.WATER)
                && level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    /**
     * 地下(洞窟)の床候補。ランダムな Y から下方向へ走査し、天井が閉じている(空が見えない)空洞の
     * 床で、足場が汚染ブロックでない地点を探す。見つからなければ {@code null}。
     */
    private static BlockPos findUndergroundSpot(ServerLevel level, int x, int z, RandomSource random) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int top = surfaceY - 2;
        int bottom = level.getMinBuildHeight() + 5;
        if (top <= bottom) {
            return null;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, bottom + random.nextInt(top - bottom + 1), z);
        for (int i = 0; i < UNDERGROUND_SCAN_DEPTH && cursor.getY() > bottom; i++) {
            if (isValidSpot(level, cursor, true)) {
                return cursor.immutable();
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }

    /**
     * {@code pos} に汚染シダを湧かせられるか。足場({@code pos.below()})が固体かつ汚染ブロックでなく、
     * {@code pos} と {@code pos.above()} が塞がっておらず液体でもないこと。
     * {@code requireEnclosed} が true なら、さらに {@code pos} から空が見えない(=地下)ことを要求する。
     */
    private static boolean isValidSpot(ServerLevel level, BlockPos pos, boolean requireEnclosed) {
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.blocksMotion() || ground.is(ModTags.CORRUPTED)) {
            return false;
        }
        // 湧く空間(2 マス)が塞がっていない / 液体でないこと。collisionShape の計算は重いので
        // blocksMotion + fluid の軽いチェックで代用する(汚染シダの当たり判定は 1x1 固定)。
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        if (at.blocksMotion() || above.blocksMotion()) {
            return false;
        }
        if (!at.getFluidState().isEmpty() || !above.getFluidState().isEmpty()) {
            return false;
        }
        return !requireEnclosed || !level.canSeeSky(pos);
    }

    private SporeConcentrationFernSpawner() {
    }
}
