package com.yayuy.overgrownvegetation.corruption.cliff;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 絡む根を登る(2026-11-20 崖の汚染。ユーザー決定「プレイヤーも登れる。植物側の Mob のほうが速い」)。
 *
 * <p>バニラのはしごは「足元のマスがはしご」のときだけ登れる({@code LivingEntity#onClimbable} は体の中のマスしか見ない)。
 * 絡む根は当たりが全面(上に乗れる・足場になる)なので体が中に入れない。そこでクモと同じく、<b>体の横が絡む根に触れていれば登れる</b>とする
 * ({@code mixin/LivingEntityRootClimbMixin} が {@code onClimbable} に足す)。地面に立って押し付けていないときは登りにしない
 * (崖の上の這う根・急斜面の網のそばを歩く者が、はしごの横の速さの頭打ち ±0.15 に掛からないように)。
 *
 * <p>速さ: バニラは横にぶつかっている間、縦を 0.2 にしてから重力を引いて 0.98 を掛ける(正味 約 0.118 ブロック / tick)。
 * ここではその 0.2 を {@code 重力 + (0.2 − 重力) × 倍率} に置き換える(正味が倍率どおりになる)。倍率は {@code cliff.json} の climb。
 * プレイヤーはクライアントが自分の動きを決めるので、クライアントでもサーバーでも同じ答えになるよう「プレイヤー = player の倍率」とだけ見る。
 */
public final class RootClimb {
    private RootClimb() {
    }

    /** 体の横を広げる幅(ブロック)。押し付けをやめても、すぐには離れない。 */
    static final double REACH = 0.08D;

    /** 体の横(足元から 1.4 ブロックまで)が絡む根に触れているか。 */
    public static boolean touching(LivingEntity e) {
        Level level = e.level();
        AABB b = e.getBoundingBox();
        double top = b.minY + Math.min(1.4D, e.getBbHeight());
        int x0 = Mth.floor(b.minX - REACH), x1 = Mth.floor(b.maxX + REACH);
        int z0 = Mth.floor(b.minZ - REACH), z1 = Mth.floor(b.maxZ + REACH);
        int y0 = Mth.floor(b.minY + 0.01D), y1 = Mth.floor(top);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (level.getBlockState(p.set(x, y, z)).is(OrganBlocks.TANGLED_ROOT.get())) return true;
                }
            }
        }
        return false;
    }

    /** 絡む根で登っている(はしごの扱い)か。地面に立って押し付けていないときは false。 */
    public static boolean climbing(LivingEntity e) {
        if (e.isSpectator()) return false;
        if (e.onGround() && !e.horizontalCollision) return false;
        return touching(e);
    }

    /** 登る速さの倍率(はしご = 1)。 */
    public static double multiplier(LivingEntity e) {
        CliffTable.Climb c = DesignData.cliff().climb;
        if (e instanceof Player) return c.player;
        return PlantSideRules.isPlantSide(e) ? c.plantSide : c.other;
    }

    /** バニラの上りの縦 0.2 の代わり(正味の上りがはしごの {@link #multiplier} 倍になる値)。 */
    public static double riseFor(LivingEntity e) {
        double g = e.getGravity();
        return g + (0.2D - g) * multiplier(e);
    }
}
