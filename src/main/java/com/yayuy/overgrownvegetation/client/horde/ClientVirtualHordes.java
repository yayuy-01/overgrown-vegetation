package com.yayuy.overgrownvegetation.client.horde;

import com.yayuy.overgrownvegetation.network.VirtualHordePayload;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;

import java.util.Collection;

/**
 * クライアント側の仮想の群れの置き場(2026-11-18 大群の軽量化 A)。サーバーの {@code horde/VirtualHordeSync} が 20 tick ごとに送る一覧を
 * id で持つ。描くのはセッション C(遠景の身代わり。粗いモデルか絵かは重さを比べてから決める = ユーザー)。ここは描かない。
 *
 * <p>{@link #view} は受け取ってから経った tick ぶん、向き × 速さで前へずらした位置を返す。{@value #STALE_TICKS} tick 更新の無い群れは捨てる。
 */
public final class ClientVirtualHordes {

    /** これだけ更新の無い群れは捨てる(サーバーは 20 tick ごとに送る)。 */
    public static final int STALE_TICKS = 100;

    /** 受け取った群れと、受け取った時刻(クライアントのゲーム時刻)。 */
    public record Entry(VirtualHordePayload.Swarm swarm, long receivedAt) {
    }

    private static final Long2ObjectOpenHashMap<Entry> SWARMS = new Long2ObjectOpenHashMap<>();

    private ClientVirtualHordes() {
    }

    public static void receive(VirtualHordePayload payload) {
        long now = now();
        SWARMS.clear();
        for (VirtualHordePayload.Swarm s : payload.swarms()) SWARMS.put(s.id(), new Entry(s, now));
    }

    /** 今の一覧(古い物は捨ててから)。 */
    public static Collection<Entry> view() {
        long now = now();
        SWARMS.values().removeIf(e -> now - e.receivedAt > STALE_TICKS);
        return SWARMS.values();
    }

    /** 群れの今の位置の x(受け取ってからの進みを足す)。partialTick は描画の途中の割合。 */
    public static double x(Entry e, float partialTick) {
        return e.swarm.x() + e.swarm.dirX() * e.swarm.speed() * (now() - e.receivedAt + partialTick);
    }

    public static double z(Entry e, float partialTick) {
        return e.swarm.z() + e.swarm.dirZ() * e.swarm.speed() * (now() - e.receivedAt + partialTick);
    }

    /** 見えている仮想の頭数(確かめ用)。 */
    public static int heads() {
        int n = 0;
        for (Entry e : view()) for (VirtualHordePayload.Count c : e.swarm.counts()) n += c.n();
        return n;
    }

    private static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime();
    }
}
