package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの<b>見える距離</b>(2026-09-26)。TD の仮想の群れを、近くのプレイヤーの見える範囲の外で生ませるために使う
 * ({@link TdTicker#spawnDistance}。ユーザー指定「mob が絵として湧く場所をもっと遠くから。外周の霧の中から出てくる感じに」)。
 *
 * <p>見える距離 = 描画距離と地形の霧の終わり(過繁茂の霧は 96、極相は 48)の近い方(ブロック、水平)。その先は霧で塗り潰され、
 * 遠景の身代わりの絵も描かない(地平線の嵐になる)。霧はクライアントにしか無いので、クライアントが
 * {@code HordeSightPayload} で知らせてくる値を覚える。まだ知らせていないプレイヤーはサーバーの描画距離(チャンク × 16。
 * クライアントの描画距離はこれを超えない)。知らせてきた値もこれで切る。ログアウト・サーバー停止で忘れる(保存しない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdSight {

    /** 知らせてくる値の上限(ブロック)。 */
    private static final int MAX_SIGHT = 1024;

    /** 見ている人 1 人: 水平の位置と見える距離(ブロック)。 */
    public record Viewer(double x, double z, double sight) {
    }

    /** 知らせてきた見える距離(ブロック)。サーバーのスレッドだけで読み書きする(受け取りは enqueueWork の中)。 */
    private static final Map<UUID, Integer> REPORTED = new HashMap<>();

    /** テスト用: 本物のプレイヤーの代わりに使う見ている人(null = 本物)。GameTest のサーバーにはプレイヤーがいない。 */
    @Nullable
    private static List<Viewer> viewersForTests;

    /** クライアントから(サーバーのスレッドで)。 */
    public static void report(ServerPlayer player, int blocks) {
        REPORTED.put(player.getUUID(), Mth.clamp(blocks, 0, MAX_SIGHT));
    }

    /** そのプレイヤーの見える距離(ブロック、水平)。 */
    public static double of(ServerPlayer player) {
        double server = player.server.getPlayerList().getViewDistance() * 16.0D;
        Integer reported = REPORTED.get(player.getUUID());
        return reported != null ? Math.min(reported, server) : server;
    }

    /**
     * ターゲットのまわりで群れを見うる人: 同じディメンションで、ターゲットから水平に {@link TdHordeSync#SEND_RADIUS}
     * (群れの絵を送る距離)の中のプレイヤー。
     */
    static List<Viewer> around(ServerLevel level, BlockPos target) {
        if (viewersForTests != null) {
            return viewersForTests;
        }
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double reachSq = TdHordeSync.SEND_RADIUS * TdHordeSync.SEND_RADIUS;
        List<Viewer> out = new ArrayList<>(2);
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - tx;
            double dz = player.getZ() - tz;
            if (dx * dx + dz * dz <= reachSq) {
                out.add(new Viewer(player.getX(), player.getZ(), of(player)));
            }
        }
        return out;
    }

    /** テスト用: 見ている人を決める(null で本物のプレイヤーへ戻す)。 */
    static void setViewersForTests(@Nullable List<Viewer> viewers) {
        viewersForTests = viewers != null ? List.copyOf(viewers) : null;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        REPORTED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        REPORTED.clear();
        viewersForTests = null;
    }

    private TdSight() {
    }
}
