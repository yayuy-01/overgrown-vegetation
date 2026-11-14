package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.g1.skill.G1SporeCloud;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 監視塔フィロセンスが叩かれて吹く胞子(2026-11-14。Vault「監視塔の能力の穴の答え」2: 身の守り = 叩かれると胞子を吹く)。
 * 相手の暴露量を上げ、少し視界をふさぐ。<b>ダメージ無し・防護マスクで防げる</b>。
 *
 * <p>仕組みは G1 の胞子の煙({@link G1SporeCloud}: 中にいる相手へ暴露量を足す・マスクで防ぐ・植物側には効かない・視界が緑に霞む)をそのまま使う。
 * 鈍足は付けない。音は鳴らさない(この Mob の音はまだ入れない決まり)。量・範囲・長さは {@link PhyllosensWatch} の【仮】の値
 * (G1 のチビゾンビの袋の煙と同じ 1 秒 100 %秒・3 秒)。決まったら全体管理の値に差し替える。
 */
public final class PhyllosensSpores {

    private PhyllosensSpores() {
    }

    /** {@code feet} を底の中心に、半径 {@code radius} の煙を出す。 */
    public static void puff(ServerLevel level, PhyllosensEntity owner, Vec3 feet, float radius) {
        G1SporeCloud.spawn(level, feet, radius, PhyllosensWatch.PUFF_TICKS, PhyllosensWatch.PUFF_DOSE, -1, 0, true, owner, false);
    }
}
