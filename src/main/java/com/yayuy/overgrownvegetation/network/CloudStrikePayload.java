package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * 積乱雲から地面への落雷(2026-11-16 weather-system、ユーザー決定「汚染の森への落雷は積乱雲の雷に合わせて森へも落とす」)。サーバー
 * ({@code sky.StormLightning})が雷雨の間に育った積乱雲の下へ落とす場所を決め、2000 ブロック以内のプレイヤーへ 1 回の落雷につき 1 通送る。
 * 稲妻・雲の中の光・森の光と吹き上がり・雷鳴の見た目はクライアント({@code client.atmosphere.WeatherClient#strikeListener})。
 * 汚染の森(forest = 1)に当たったときも {@link ForestLightningPayload} は重ねて送らない。
 *
 * @param stormId    落ちた積乱雲の id({@code CloudField.Storm#id}。確かめ用で雲が無いときは 0)
 * @param x          落ちた所(世界の座標)
 * @param z          落ちた所(世界の座標)
 * @param hitY       当たった高さ(葉を含む MOTION_BLOCKING。稲妻の先)。チャンクが読み込まれていなければ {@link Integer#MIN_VALUE}
 * @param groundY    葉を除いた地面の高さ(MOTION_BLOCKING_NO_LEAVES)。読み込まれていなければ {@link Integer#MIN_VALUE}
 * @param cloudBaseY 雲の底の高さ(稲妻の根元。{@code CloudField.LOW_BASE})
 * @param forest     0 = ふつうの地面・1 = 汚染の森(光って胞子を吹き上げる)
 * @param seed       稲妻の形の種
 */
public record CloudStrikePayload(long stormId, double x, double z, int hitY, int groundY, int cloudBaseY, float forest, long seed)
        implements CustomPacketPayload {

    public static final Type<CloudStrikePayload> TYPE = new Type<>(OvergrownVegetation.id("cloud_strike"));

    public static final StreamCodec<FriendlyByteBuf, CloudStrikePayload> STREAM_CODEC =
            StreamCodec.ofMember(CloudStrikePayload::write, CloudStrikePayload::new);

    private CloudStrikePayload(FriendlyByteBuf buf) {
        this(buf.readLong(), buf.readDouble(), buf.readDouble(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readFloat(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeLong(stormId);
        buf.writeDouble(x);
        buf.writeDouble(z);
        buf.writeInt(hitY);
        buf.writeInt(groundY);
        buf.writeInt(cloudBaseY);
        buf.writeFloat(forest);
        buf.writeLong(seed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
