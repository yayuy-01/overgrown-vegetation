package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.td.TdDirection;
import com.yayuy.overgrownvegetation.td.TdTicker;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * 見張り台の早期警報(順 2 の着手順 8 の最小限、2026-10-08)。<b>ユーザー指定(10-01)</b>「見張り台が TD の群れを早めに知らせる」。
 * 10-08 に 0.2.0 を最小限に絞り、「TD の予告に群れの方角と規模を足すだけ」にした(予告より早く知らせる・観測基地の強化は後の版)。
 * 仕様は {@code docs/ORDER2_SPEC.md} の 0 節の冒頭。
 *
 * <p>Claude 仮決め:
 * <ul>
 *     <li><b>知らせる基地</b>: TD のターゲットと同じディメンションで、陥落していない基地のうち、中心がターゲットから水平に
 *     {@code alliedBase.watchRadius}(512)以内でいちばん近いもの。建てている途中の基地も数える(見張り台のブロックは見ない)。
 *     近くに無ければ何も足さない(今までどおりの予告だけ)。</li>
 *     <li><b>知らせること</b>: 群れの来る向き(予告のときに振った向き。侵攻もその向きで来る。{@code TdTicker#startWarning})と、
 *     規模 = 予告のときの世界のバイオマス(侵攻に入る瞬間に全部が予算になる。侵攻までに少し動くので 2 桁に丸めて「約」)。
 *     単位は侵攻の始まりの知らせ・ボスバーと同じバイオマス(1 体の値段は種で 1〜1000 と違うので「体」にしない)。</li>
 *     <li><b>誰に</b>: 予告と同じく全員のチャットへ(予告の行の次に 1 行)。</li>
 * </ul>
 */
public final class BaseWatch {

    /**
     * {@code target} を見張る基地: {@code dim} の陥落していない基地のうち、中心が水平 {@code radius} 以内でいちばん近いもの。
     * {@link BaseSites#nearest} は陥落した基地も返すので使わない(近くの廃墟が、少し遠くの生きている基地の知らせを消さないように)。
     */
    @Nullable
    public static BaseSite spotter(BaseSites data, ResourceKey<Level> dim, BlockPos target, double radius) {
        if (!(radius > 0.0D)) {
            return null;
        }
        BaseSite best = null;
        double bestSq = radius * radius;
        for (BaseSite s : data.all()) {
            if (s.fallen() || !s.dimension.equals(dim)) {
                continue;
            }
            double dx = s.center.getX() - target.getX();
            double dz = s.center.getZ() - target.getZ();
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = s;
            }
        }
        return best;
    }

    /** 規模の見込み(バイオマス)を上から 2 桁に丸める(100 未満は整数のまま。2,437 → 2,400、12,345 → 12,000)。 */
    public static long roundSize(double biomass) {
        long n = Math.max(0L, Math.round(biomass));
        if (n < 100L) {
            return n;
        }
        long unit = 1L;
        while (n / unit >= 100L) {
            unit *= 10L;
        }
        return Math.round((double) n / unit) * unit;
    }

    /**
     * 知らせる 1 行(基地の番号・向き・規模)。全方位とそれ以外で文を分ける(英語の "from the north" と "from all sides")。
     * 色は文の中の § ではなく部品ごと黄色に塗る(§ は引数の所で切れて、そこから先が白に戻る。2026-10-08 にクライアントで見つけた)。
     */
    public static Component message(BaseSite site, TdDirection direction, double biomass) {
        String size = TdTicker.biomassText(roundSize(biomass));
        MutableComponent line = direction.around()
                ? Component.translatable("message.overgrown_vegetation.base.watch_td_around", site.id, size)
                : Component.translatable("message.overgrown_vegetation.base.watch_td_from", site.id, direction.label(), size);
        return line.withStyle(ChatFormatting.YELLOW);
    }

    /**
     * TD の予告のときに呼ぶ({@code TdTicker#startWarning})。見張る基地があれば全員へ知らせて true。
     *
     * @param biomass 規模の見込み(予告のときの世界のバイオマス)
     */
    public static boolean report(ServerLevel level, BlockPos target, TdDirection direction, double biomass) {
        MinecraftServer server = level.getServer();
        BaseSite site = spotter(BaseSites.get(server), level.dimension(), target, DesignData.defense().baseWatchRadius());
        if (site == null) {
            return false;
        }
        server.getPlayerList().broadcastSystemMessage(message(site, direction, biomass), false);
        OvergrownVegetation.LOGGER.info("基地 #{} の見張り台: TD の群れは{}、規模はバイオマス約 {}", site.id,
                direction.around() ? "全方位から" : direction.japanese() + "から", roundSize(biomass));
        return true;
    }

    private BaseWatch() {
    }
}
