package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.PhaseCuePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;

/**
 * <b>フェーズが上がったときの合図</b>をクライアントへ送る係(2026-11-16。{@link PhaseEvents.PhaseChanged} の受け手)。
 *
 * <p>理由(ユーザー決定、Vault「フェーズの仕組み」): フェーズが上がったことは、数字ではなく現象で知らせる。共通の合図は
 * 低い地鳴り(画面が小さく揺れる)・空が数秒だけ黄緑ににじむ・汚染の森の方角から葉と幹が一斉にざわめく音。世界中のプレイヤーに同時に。
 * 見た目と音はクライアントで作る({@code client/atmosphere/PhaseCue})。ここは「いつ」と「森の方角」だけを決める。
 *
 * <ul>
 *     <li>送るのは上がったときだけ({@link PhaseEvents.PhaseChanged#advanced})。下げた・同じ・ログインでは送らない。コマンドで上げたときも送る(確かめやすい)。</li>
 *     <li>森の方角: オーバーワールドのプレイヤーは、過繁茂の台帳({@link PhaseData#forEachOvergrownChunk})のうちいちばん近いチャンクへの向き。
 *     台帳が空なら、いちばん近い巨大樹。どちらも無い・別のディメンションなら「森なし」(ざわめきは鳴らない)。
 *     自分が森の中(その升の中)にいるときは向きが決まらないので、北を入れる(まわり中が森なので、どこから聞こえても合う)。</li>
 *     <li>F1 は地割れの音が鳴るので、地鳴りの音を重ねない({@code quiet})。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PhaseCues {

    private PhaseCues() {
    }

    @SubscribeEvent
    public static void onPhaseChanged(PhaseEvents.PhaseChanged event) {
        if (!event.advanced() || event.newPhase() < 1) {
            return;
        }
        ServerLevel overworld = event.overworld();
        PhaseData data = PhaseData.get(overworld);
        boolean quiet = event.newPhase() == 1;
        for (ServerPlayer player : overworld.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, payloadFor(player, data, event.newPhase(), quiet));
        }
    }

    /** そのプレイヤーへの合図(森の方角つき)。確かめ用のコマンドからも使う。 */
    public static PhaseCuePayload payloadFor(ServerPlayer player, PhaseData data, int phase, boolean quiet) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return new PhaseCuePayload(phase, false, 0.0F, 0.0F, quiet);
        }
        double px = player.getX();
        double pz = player.getZ();
        double[] best = {Double.MAX_VALUE, 0.0, 0.0};
        data.forEachOvergrownChunk(key -> {
            double dx = ChunkPos.getX(key) * 16 + 8 - px;
            double dz = ChunkPos.getZ(key) * 16 + 8 - pz;
            double d = dx * dx + dz * dz;
            if (d < best[0]) {
                best[0] = d;
                best[1] = dx;
                best[2] = dz;
            }
        });
        if (best[0] == Double.MAX_VALUE) {
            for (long packed : data.giantTreeOriginSnapshot()) {
                double dx = (int) (packed >> 32) - px;
                double dz = (int) packed - pz;
                double d = dx * dx + dz * dz;
                if (d < best[0]) {
                    best[0] = d;
                    best[1] = dx;
                    best[2] = dz;
                }
            }
        }
        if (best[0] == Double.MAX_VALUE) {
            return new PhaseCuePayload(phase, false, 0.0F, 0.0F, quiet);
        }
        double len = Math.sqrt(best[0]);
        if (len < 12.0) {
            return new PhaseCuePayload(phase, true, 0.0F, -1.0F, quiet);
        }
        return new PhaseCuePayload(phase, true, (float) (best[1] / len), (float) (best[2] / len), quiet);
    }
}
