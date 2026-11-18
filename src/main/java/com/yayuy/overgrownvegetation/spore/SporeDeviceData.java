package com.yayuy.overgrownvegetation.spore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * 胞子装置(除去装置・拡散装置・警報器など)の設置位置の一覧 —— <b>の空の代役</b>(2026-10-30 の整理(退避))。
 *
 * <p>胞子装置のブロックは人類側の道具として {@code parked/} へ退避した。汚染・胞子の場・台帳はこの口から
 * 「近くに装置があるか」を聞くので、口だけ残し、どこにも装置が無いものとして答える。
 * 装置を作り直して戻すときは、{@code parked/src/main/java/.../spore/SporeDeviceData.java}(本物)でこのファイルを置き換える。
 */
public final class SporeDeviceData {
    private static final long[] NONE = new long[0];

    /** 止める物の種類(本物と同じ並び)。 */
    public enum Inhibit {
        /** ブロックの汚染(全経路)。 */
        CORRUPTION,
        /** 汚染シダ/汚染水草のスポーン。 */
        FERN_SPAWN,
        /** チャンクの過繫茂/繫茂バイオーム化。 */
        BIOME_CONVERSION
    }

    public static double deltaAt(ServerLevel level, ChunkPos chunk) {
        return 0.0D;
    }

    public static double purifierStrengthAt(ServerLevel level, BlockPos pos) {
        return 0.0D;
    }

    public static boolean protectsFromCorruption(ServerLevel level, BlockPos pos) {
        return com.yayuy.overgrownvegetation.corruption.pushback.RingWards.inStakeField(level, pos);
    }

    /**
     * 2026-11-18 夜(押し返し): 装置は退避したままだが、<b>侵入を止める物</b>(帯 = 線・杭 = 範囲の場。{@code RingWards})と焼けた荒地の守りをここで答える。
     * ブロックの汚染は帯の線を越えず、杭の範囲に入らない。萌芽体は杭の範囲には湧かない。
     */
    public static boolean inhibits(ServerLevel level, BlockPos pos, Inhibit what) {
        if (com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurnt(level, pos)) {
            return true; // 焼けた荒地の間(1 日【仮】)は汚染が入らない(防火帯。ユーザー指定 10-08 を 11-18 夜に筒へ引き継いだ)
        }
        return switch (what) {
            case CORRUPTION -> com.yayuy.overgrownvegetation.corruption.pushback.RingWards.blocks(level, pos);
            case FERN_SPAWN -> com.yayuy.overgrownvegetation.corruption.pushback.RingWards.inStakeField(level, pos);
            default -> false;
        };
    }

    public static boolean inhibits(ServerLevel level, ChunkPos chunk, Inhibit what) {
        return level.dimension() == net.minecraft.world.level.Level.OVERWORLD && com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurntKey(level.getServer(), chunk.toLong());
    }

    public static boolean mayInhibitBlocksIn(ServerLevel level, ChunkPos chunk, Inhibit what) {
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD && com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurntKey(level.getServer(), chunk.toLong())) return true;
        return what == Inhibit.CORRUPTION && com.yayuy.overgrownvegetation.corruption.pushback.RingWards.mayBlockIn(level, chunk);
    }

    public static long[] activePurifiers(ServerLevel level) {
        return NONE;
    }

    public static long[] activeDiffusers(ServerLevel level) {
        return NONE;
    }

    public static boolean guardsChunkFromLedger(ServerLevel level, int chunkX, int chunkZ) {
        return false;
    }

    public static long[] ledgerGuards(ServerLevel level) {
        return NONE;
    }

    public static boolean guards(long[] guardDevices, int chunkX, int chunkZ) {
        return false;
    }

    public static void invalidateInhibitionAll() {
    }

    /** テスト用(本物は置かれた装置を一時的に外す)。装置が無いので空を返す。 */
    public static long[][] isolateForTests(ServerLevel level) {
        return new long[][]{NONE, NONE};
    }

    public static void restoreForTests(ServerLevel level, long[][] snapshot) {
    }

    private SporeDeviceData() {
    }
}
