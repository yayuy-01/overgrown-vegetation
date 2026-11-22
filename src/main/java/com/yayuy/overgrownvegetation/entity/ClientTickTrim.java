package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 画面側(クライアント)の植物側の Mob の tick から、結果の変わらない処理を省く(2026-11-18 大群の軽量化 C。1024 体で 1 tick 約 5 ms あった)。
 * <ul>
 *   <li>押し合い: クライアントのバニラはプレイヤーだけを押す。体に触れているプレイヤーがいなければ、エンティティの区画を探さない
 *       ({@code level.players()} は数人なので速い。押す相手の集まりはバニラと同じ)。</li>
 *   <li>壁の中か・粉雪の凍えの速さ: どちらもサーバーで効くもの(窒息の傷・移動の速さ)。クライアントでは計算しても何にも使われない。</li>
 *   <li>水に触れているか(遠い Mob だけ。{@link #farOnClient})。遠くの水しぶきが出なくなる(ユーザー了承済み)。</li>
 * </ul>
 * 水の判定のほかは、遊び・見た目は変わらない。{@link #enabled} は確かめ用の切り替え(devbridge の {@code crowd ticktrim off|on})。
 */
public final class ClientTickTrim {

    public static volatile boolean enabled = true;

    private ClientTickTrim() {
    }

    /** クライアントで、省いてよいか。 */
    public static boolean skip(Entity e) {
        return enabled && e.level().isClientSide();
    }

    /**
     * クライアントで、どのプレイヤーからも遠い(AI の段階化の midBlocks より遠い。perf.json【仮】64)か。遠い Mob は水に触れているかの
     * 判定を省く(11-18 ユーザー了承「省いてもいいかも」。遠くの水しぶきが出なくなるのは了承済み)。
     */
    public static boolean farOnClient(Entity e) {
        if (!skip(e)) return false;
        double far = com.yayuy.overgrownvegetation.data.DesignData.perf().midBlocks;
        double limit = far * far;
        for (Player p : e.level().players()) {
            if (p.distanceToSqr(e) <= limit) return false;
        }
        return true;
    }

    /** 体の箱に触れているプレイヤーがいるか(クライアントの押し合いの相手がいるか)。 */
    public static boolean anyPlayerTouching(Entity e) {
        AABB box = e.getBoundingBox();
        for (Player p : e.level().players()) {
            if (p.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }
}
