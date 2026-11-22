package com.yayuy.overgrownvegetation.difficulty;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * MOD の設定「汚染しない場所」(11-19 ユーザー決定の ② の項目): オーバーワールドのスポーン地点のまわり半径 r(ブロック、水平)の中は
 * ブロックを汚染しない。読むのは汚染の入口 {@code CorruptionRegistry#canCorrupt}(どの経路の汚染もここを通る)。
 *
 * <p>止めるのはブロックの置き換えだけ(台帳の印・Mob の湧き・胞子は止めない)。もう汚染されたブロックは戻らない(画面の ※)。
 * 汚染の判定は 1 tick に何千回も通るので、半径 0(既定)ならすぐ帰る。
 */
public final class SpawnSafeZone {

    private SpawnSafeZone() {
    }

    /** {@code pos} が汚染しない場所の中か。 */
    public static boolean protects(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return false;
        double r = WorldDifficultyData.ofFromAnyThread(level.getServer(), DifficultySetting.SPAWN_SAFE_RADIUS);
        if (r <= 0.0D) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        double dx = pos.getX() + 0.5D - (spawn.getX() + 0.5D);
        double dz = pos.getZ() + 0.5D - (spawn.getZ() + 0.5D);
        return dx * dx + dz * dz <= r * r;
    }
}
