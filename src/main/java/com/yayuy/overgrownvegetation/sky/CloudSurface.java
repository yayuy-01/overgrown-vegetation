package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CloudHolePayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 雲の面の演出(2026-10-19。空の巨大生物の「雲海に潜って隠れる・鯨のように飛び出す」、行動パターン「潜伏と飛び出し」で決定済みの 2 つ。
 * 空の巨大生物のセッションからの依頼)。サーバーから呼ぶと、オーバーワールドの全員に見える。どちらも見た目だけ(当たり判定は無い)。
 *
 * <ul>
 *   <li>{@link #bulge}: 飛び出しの予告。雲の面の上に平たい雲の塊({@link CloudPuffs})をゆっくり盛り上げる(雲の面がふくらむように見える)。
 *       高さはその場所の雲の海の天辺({@link CloudSky#columnAt} の seaTop)か、呼ぶ側が渡す雲の面の高さ。</li>
 *   <li>{@link #hole}: 潜ったときの丸い穴。縦の筒(雲の場の座標なので雲と一緒に流れる)を開き、決めた時刻から埋める({@code client/CloudHoles} が描く。
 *       雲の中の霧も穴の中は外として扱う)。サーバーは穴を覚えない({@link CloudSky#columnAt} も穴を数えない)。</li>
 * </ul>
 * 数値はすべて仮決め。
 */
public final class CloudSurface {
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /**
     * 雲の面の盛り上がり(飛び出しの予告)。世界の点 (x, z) の雲の海の天辺({@link CloudSky.CloudColumn#seaTop}。無ければいちばん上の天辺、雲が無ければ y 350)に
     * 盛り上げる({@link #bulge(ServerLevel, double, double, double, float, int, int)})。
     */
    public static int bulge(ServerLevel level, double x, double z, float radius, int rise, int hold) {
        if (level.dimension() != Level.OVERWORLD) {
            return 0;
        }
        CloudSky.CloudColumn c = CloudSky.columnAt(level, x, z, 0.0F);
        double top = c == null ? CloudField.LOW_BASE + 50.0 : c.seaTop() >= 0 ? c.seaTop() : c.top();
        return bulge(level, x, top, z, radius, rise, hold);
    }

    /**
     * 雲の面の盛り上がり(飛び出しの予告)。世界の点 (x, z) の高さ surfaceY(雲の面)に、横の半径 radius・高さ radius × 0.5 の平たい塊を、rise tick かけて
     * 盛り上げ(雲の面から半径の 3 割ほど上がる)、hold tick 保ってから 60 tick で薄れて消える。オーバーワールドでだけ(ほかでは 0)。
     * 最初は真ん中を面の少し下に置いて高さを半径の 0.45 倍にしたが、40 ブロックほどしか上がらず、白い雲海の上で見分けにくかった(10-19 のテストクライアント)。
     *
     * @return 吐いた雲の番号({@link CloudPuffs#puff})
     */
    public static int bulge(ServerLevel level, double x, double surfaceY, double z, float radius, int rise, int hold) {
        if (level.dimension() != Level.OVERWORLD) {
            return 0;
        }
        float height = radius * 0.5F;
        return CloudPuffs.puff(level, x, surfaceY + height * 0.1, z, radius, height, Math.max(rise, 1) + Math.max(hold, 0) + 60, Math.max(rise, 1), 60);
    }

    /**
     * 雲の丸い穴(潜ったとき)。世界の点 (x, z) の bottom〜top(y)を貫く半径 radius の縦の筒を、open tick で開き、開いてから fillFrom tick で埋まり始め、
     * fillTo tick で元に戻す。bottom・top が NaN ならその場所の雲の柱({@link CloudSky#columnAt})の底と天辺を使う(雲が無ければ何もしない)。
     * オーバーワールドでだけ。
     *
     * @return 穴を開けたか
     */
    public static boolean hole(ServerLevel level, double x, double z, double bottom, double top, float radius, int open, int fillFrom, int fillTo) {
        if (level.dimension() != Level.OVERWORLD) {
            return false;
        }
        if (Double.isNaN(bottom) || Double.isNaN(top)) {
            CloudSky.CloudColumn c = CloudSky.columnAt(level, x, z, 0.0F);
            if (c == null) {
                return false;
            }
            bottom = c.bottom();
            top = c.top();
        }
        long now = level.getGameTime();
        int from = Math.max(1, fillFrom);
        CloudHolePayload p = new CloudHolePayload(CloudField.fieldX(x, now, 0.0F), CloudField.fieldZ(z, now, 0.0F), Math.min(bottom, top) - 4.0,
                Math.max(bottom, top) + 4.0, Math.max(4.0F, radius), now, Math.max(1, open), from, Math.max(from + 1, fillTo));
        PacketDistributor.sendToPlayersInDimension(level, p);
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CloudSurface 穴 世界 {} {} 高さ {}〜{} 半径 {} 開く {} 埋まる {}〜{}", Math.round(x), Math.round(z), Math.round(p.bottom()),
                    Math.round(p.top()), Math.round(p.radius()), p.open(), p.fillFrom(), p.fillTo());
        }
        return true;
    }

    private CloudSurface() {
    }
}
