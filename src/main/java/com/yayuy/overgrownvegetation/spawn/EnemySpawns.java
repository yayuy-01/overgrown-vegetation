package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;

import javax.annotation.Nullable;

/**
 * 植物側の Mob を作る共通の口と、過繁茂の縁の判定(2026-11-15 の「縁・地下の湧き」の残り)。
 *
 * <p>2026-11-23 片付け E: 縁・地下の湧き(と片付け・名簿 BandRoster・/ov perf spawns の表示と試し)は消した。11-21 から湧きは前線と部隊
 * ({@code front/FrontDirector}・{@code horde/VirtualHordes})の受け持ち。ここに残すのは、部隊の実物化・苗床・前哨・見張り・霧の潜み・
 * 巣の置き場が使う {@link #create} と {@link #isEdgeChunk}・{@link #outside}。
 */
public final class EnemySpawns {

    private EnemySpawns() {
    }

    /**
     * そのチャンクは縁か: 台帳での自分の扱い(載っている / いない)と違う隣が 8 方向に 1 つでもある。ただし「載っていない」側は、
     * 本当の外(まだ汚れていない土地)でなければ数えない = 森の中の小さな穴(焼いたチャンクなど 2 チャンク幅まで)は縁にしない
     * (見直しの疑い、11-16: 穴のまわりが縁になり、森の内側に G2 以降が湧いていた)。
     */
    public static boolean isEdgeChunk(PhaseData data, int cx, int cz) {
        boolean self = data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
        if (!self) {
            if (!outside(data, cx, cz)) return false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz))) return true;
                }
            }
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && !data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz)) && outside(data, cx + dx, cz + dz)) return true;
            }
        }
        return false;
    }

    /**
     * 台帳に載っていないチャンクが「本当の外」か: 2 つ離れた輪(16 チャンク)に、載っていないチャンクが 1 つでもある。
     * 輪が全部台帳なら、2 チャンク幅までの穴の中なので外ではない。純粋な計算(台帳を最大 16 回引く)。
     */
    public static boolean outside(PhaseData data, int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
                if (!data.isOvergrownKey(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    /** Mob を作って置き場へ動かし、はまらなければ finalizeSpawn まで済ませて返す(まだ世界には足さない)。 */
    @Nullable
    public static Mob create(ServerLevel level, EntityType<?> type, BlockPos at, MobSpawnType reason, RandomSource random) {
        Entity e = type.create(level);
        if (!(e instanceof Mob m)) {
            if (e != null) e.discard();
            return null;
        }
        m.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(m)) {
            m.discard();
            return null;
        }
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), reason, null, null);
        return m;
    }

}
