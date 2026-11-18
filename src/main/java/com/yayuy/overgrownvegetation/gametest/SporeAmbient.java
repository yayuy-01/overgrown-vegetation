package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.spore.PediculaCover;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;

/**
 * 胞子の暴露を数えるテストの、外から来る胞子の内訳(2026-11-18 揺れるテストの調べ)。
 *
 * <p>1 回の実行のテスト場は全部隣り合って並ぶので、ほかのテストの胞子(場・ペディキュラなど)が届くと、浴びていないはずの相手にも暴露量がたまる。
 * 落ちたときにどこから来たかを失敗の文に書き出す。テストの登録({@code @GameTestHolder})は無い = テストの並びを変えない。
 */
final class SporeAmbient {

    /**
     * テスト場のチャンク(読みは隣のチャンクと混ぜるので、まわり 8 ブロックぶん)を {@code ticks} の間「外の場 0」として読ませる。
     * 技が直に与えた暴露量だけを見たいテストの頭(技を出す前)で呼ぶ。技が撒いた乳だまり・莢などの物はそのまま(場の浮遊を読まないだけ)。
     * 場を通して回る分は胞子の場のテストが受け持つ。
     */
    static void isolate(net.minecraft.gametest.framework.GameTestHelper helper, int ticks) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(BlockPos.ZERO), b = helper.absolutePos(new BlockPos(7, 0, 7));
        int x0 = (Math.min(a.getX(), b.getX()) - 8) >> 4, x1 = (Math.max(a.getX(), b.getX()) + 8) >> 4;
        int z0 = (Math.min(a.getZ(), b.getZ()) - 8) >> 4, z1 = (Math.max(a.getZ(), b.getZ()) + 8) >> 4;
        long until = level.getGameTime() + ticks + 20L;
        for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) SporeConcentration.cleanForTests(cx, cz, until);
    }

    /** {@code e} のいる所の濃度の読みと内訳。 */
    static String describe(ServerLevel level, LivingEntity e) {
        BlockPos pos = e.blockPosition();
        ChunkPos c = new ChunkPos(pos);
        return "[外の胞子] 読み " + fmt(SporeConcentration.percentAt(level, pos)) + "%・場 " + SporeFieldServer.cell(level, c.x, c.z)
                + "・ペディキュラ " + fmt(PediculaCover.percentAt(level, c.toLong()))
                + "・地割れ " + fmt(SporeConcentration.riftFloorPercent(level, pos))
                + "・暴露量 " + fmt(SporeExposureTracker.dose(e))
                + "・時刻 " + level.getGameTime() + "/" + level.getDayTime() + "・雨 " + level.isRaining();
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private SporeAmbient() {
    }
}
