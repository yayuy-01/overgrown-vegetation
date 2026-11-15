package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.entity.g1.G1Developed;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

/**
 * 司令塔の襲撃が攻め手を湧かせる口(2026-11-14、第 4 段)。大きな襲撃が使い、後で F7 より後の超大規模の襲撃からも呼べるように外へ出した。
 * プレイヤーの目の前に湧いて見えないように、決めた向きの、プレイヤーから離れた見通せない地面を探す(偵察を森の方角から湧かせるのと同じ決まり)。
 */
public final class RaidSpawns {

    private RaidSpawns() {
    }

    /** 確かめ用: 湧かせる所を探して弾かれた数(動いていないチャンク・地面が向かない・プレイヤーから見える)。 */
    public static final long[] lastMisses = new long[3];

    /** 攻め手の種と重み【仮】(進化体のある、地面を歩く種。偵察と同じ)。 */
    private static final G1Species[] SPECIES = {
            G1Species.ZOMBIE, G1Species.ZOMBIE, G1Species.ZOMBIE, G1Species.SKELETON, G1Species.SKELETON,
            G1Species.SPIDER, G1Species.SPIDER, G1Species.CREEPER, G1Species.COW, G1Species.PIG, G1Species.SHEEP};

    /**
     * 湧かせてよい地面を探す: 真ん中 (cx, cz) から角度 {@code angle}(ラジアン)±{@code spreadDeg} の向きへ {@code min}〜{@code max} ブロック、
     * 動いているチャンク・足元が固い・水でない・どのプレイヤーからも {@code playerMin} より遠く見通せない所。見つからなければ null。
     */
    @Nullable
    public static BlockPos findHiddenSpot(ServerLevel level, double cx, double cz, double angle, double spreadDeg, double min, double max,
                                          double playerMin, int tries, RandomSource random) {
        for (int i = 0; i < tries; i++) {
            double ang = angle + Math.toRadians((random.nextDouble() * 2.0D - 1.0D) * spreadDeg);
            double dist = min + random.nextDouble() * (max - min);
            int x = Mth.floor(cx + Math.cos(ang) * dist), z = Mth.floor(cz + Math.sin(ang) * dist);
            BlockPos column = new BlockPos(x, 0, z);
            if (!level.isPositionEntityTicking(column)) {
                lastMisses[0]++;
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                    || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) {
                lastMisses[1]++;
                continue;
            }
            if (BaseWatch.seenByPlayer(level, feet, playerMin)) {
                lastMisses[2]++;
                continue;
            }
            return feet;
        }
        return null;
    }

    /**
     * G4・G5 の攻め手を 1 体湧かせる(2026-11-15 enemy-spawns)。種は呼ぶ側が {@code spawn.DirectorHooks#pickPlant} で選ぶ。
     * finalizeSpawn まで済ませる(空の種などは finalizeSpawn で組むものがある)。置けなければ null(予算は呼ぶ側が払う)。
     */
    @Nullable
    public static net.minecraft.world.entity.Mob spawnPlantAttacker(ServerLevel level, BlockPos feet, net.minecraft.world.entity.EntityType<?> type,
                                                                   RandomSource random) {
        net.minecraft.world.entity.Mob m = com.yayuy.overgrownvegetation.spawn.EnemySpawns.create(level, type, feet,
                net.minecraft.world.entity.MobSpawnType.EVENT, random);
        if (m == null) return null;
        level.addFreshEntity(m);
        return m;
    }

    /**
     * 攻め手を 1 体湧かせる(段階 0 か 1 段目)。湧かせた Mob を返す(置けなければ null。予算は呼ぶ側が払う)。
     *
     * @param developed true なら 1 段目(種の進化体)
     */
    @Nullable
    public static AbstractParasitizedMonster spawnAttacker(ServerLevel level, BlockPos feet, boolean developed, RandomSource random) {
        G1Species s = SPECIES[random.nextInt(SPECIES.length)];
        AbstractParasitizedMonster m = null;
        if (developed) {
            for (G1Developed d : G1Developed.values()) {
                if (d.base() == s) {
                    m = ModG1.developed(d).get().create(level);
                    break;
                }
            }
        }
        if (m == null) m = s.entityType().create(level);
        if (m == null) return null;
        m.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(m)) {
            m.discard();
            return null;
        }
        level.addFreshEntity(m);
        return m;
    }
}
