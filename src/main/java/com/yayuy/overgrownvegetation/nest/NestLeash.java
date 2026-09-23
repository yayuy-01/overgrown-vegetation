package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import javax.annotation.Nullable;

/**
 * 巣の住人の<b>綱</b>(2026-09-20)。判定はここ 1 か所だけにしてある ——
 * アルボラ({@code AbstractOvergrownInvaderEntity})とスカトゥリウム
 * ({@code OvergrownShielderEntity})はクラスの系統が違うので、規則を 2 か所に書くと必ずずれる。
 *
 * <h2>規則</h2>
 * <p>住人が<b>巣の中に居るあいだ</b>だけ、巣から {@code guardLeashRadius} より外の相手を狙わない。
 * 壁越しに外の敵へ釣られて湧き出すのを止めるためのもので、狩りに出た個体を縛るものではない
 * —— 縛ると誰も外へ出なくなる(2026-09-20 のユーザー報告)。
 */
public final class NestLeash {

    /** 「巣の中」とみなす入口からの水平距離。掘った巣の広がりより少し広め。 */
    public static final double INSIDE_RADIUS = 96.0D;

    /**
     * 地表からこれだけ下にいれば「巣の中」。<b>坑口に立っているのは外</b>(2026-09-20)。
     *
     * <p>以前は「入口より 3 マス上まで」を中に数えていたので、<b>坑口まで出てきた個体が
     * まだ中に居る扱い</b>になっていた。すると綱が効いたままで遠くの獲物を狙えず、
     * 出かける Goal も「まだ中だ」と思って合図を立て直す —— 出ては戻るの繰り返しで、
     * 「なかなか外に出ず狩りをしない」ように見えていた(ユーザー報告)。
     */
    private static final int INSIDE_DEPTH = 4;

    /** {@code entity} が {@code home} を入口とする巣の中に居るか。 */
    public static boolean isInside(Entity entity, @Nullable BlockPos home) {
        if (home == null) {
            return false;
        }
        if (entity.getBlockY() > home.getY() - INSIDE_DEPTH) {
            return false;                              // 地表に出ている
        }
        double dx = entity.getX() - (home.getX() + 0.5D);
        double dz = entity.getZ() - (home.getZ() + 0.5D);
        return dx * dx + dz * dz <= INSIDE_RADIUS * INSIDE_RADIUS;
    }

    /**
     * その相手を狙うのを<b>綱が止めるか</b>。
     *
     * @param home    自分の巣の入口。無ければ綱は効かない
     * @param leaving 外へ出ようとしている最中か(増援・狩りに出るとき)
     */
    public static boolean blocks(Mob mob, @Nullable BlockPos home, boolean leaving, LivingEntity target) {
        if (home == null || leaving || !isInside(mob, home)) {
            return false;
        }
        double leash = DesignData.nest().guardLeashRadius();
        return target.distanceToSqr(home.getX() + 0.5D, target.getY(), home.getZ() + 0.5D) > leash * leash;
    }

    private NestLeash() {
    }
}
