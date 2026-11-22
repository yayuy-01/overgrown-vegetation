package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 植物側の Mob を呼び寄せる口(2026-11-14。監視塔フィロセンスの「呼ぶ(誘導)」: 視線の印の 1 分の間、半径 64【仮】の植物側の Mob に
 * 印の相手の居場所を配って向かわせる)。監視塔のほかからも呼べる。
 *
 * <ul>
 *   <li>G1(段階 0・1 段目): 印の相手の今の居場所へ {@link G1March} で歩かせる(長さの上限つき)。道が決まった個体(進化・根付き・苗床の途中)・
 *   偵察の途中・起き上がりの途中・ほかの使いの途中(拠点へ送られている)は呼ばない。着いたら、あとはいつもの狙い方で襲う。</li>
 *   <li>G2 以降: 戻したら {@link #registerListener} で、その群れの側が向かわせ方を登録する。</li>
 *   <li><b>貯めている間は呼ばない</b>(貯める間は「襲わず遠巻きにうろつく」決まり。呼ぶと遠巻きが崩れる)。
 *   大きな襲撃の前触れの間は通す(止めるのは植物側の「湧き」だけで、いる個体を動かすのは偵察と同じく続ける)。</li>
 * </ul>
 */
public final class PlantSideCall {

    private PlantSideCall() {
    }

    /** 呼びかけの印(G1March の拠点の番号の代わり)。 */
    public static final int CALL_ID = -2;

    /** G2 以降を向かわせる口。向かわせた数を返す。 */
    public interface Listener {
        int call(ServerLevel level, Vec3 at, LivingEntity target, double radius, int ticks);
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();

    public static void registerListener(Listener l) {
        LISTENERS.add(l);
    }

    /**
     * {@code at} から半径 {@code radius} の植物側の Mob を、{@code target} の居場所へ {@code ticks} の間向かわせる。
     *
     * @return 向かわせた数(G1 + 登録された G2 以降)
     */
    public static int towards(ServerLevel level, Vec3 at, LivingEntity target, double radius, int ticks) {
        if (ForestDirector.hoarding()) return 0;
        int n = 0;
        for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class, new AABB(at, at).inflate(radius, 64.0D, radius))) {
            if (!m.isAlive() || m.isRising() || m.omen() != G1Fate.OMEN_NONE || m.march().active()) continue;
            if (m.distanceToSqr(at) > radius * radius) continue;
            G1Fate f = m.fate();
            if (f != null && (f.route() != ForestDirector.Route.NONE || f.scout().active())) continue;
            m.march().beginTo(target.getX(), target.getZ(), CALL_ID, ticks);
            n++;
        }
        for (Listener l : LISTENERS) n += Math.max(0, l.call(level, at, target, radius, ticks));
        return n;
    }
}
