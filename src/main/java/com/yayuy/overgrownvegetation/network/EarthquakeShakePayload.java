package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * サーバー → クライアントへ「地割れ(地震)の画面揺れ」の状態を伝える S2C ペイロード。
 *
 * <p>{@link com.yayuy.overgrownvegetation.quake.EarthquakeData} が演出中、10 tick おきに
 * オーバーワールドの全プレイヤーへ送る。1 パケットに「震央の位置・演出開始からの経過 tick・
 * タイムラインの各区間長・最大強度・減衰半径」を全部載せてあるので、パケットを取りこぼしても
 * 次の再送で必ず追従でき、途中ログインしたプレイヤーもそのまま演出に乗れる。
 *
 * <p>実際の揺れの計算(区間ごとのエンベロープと距離減衰)はクライアント側
 * {@link com.yayuy.overgrownvegetation.client.ClientEarthquake} が行う。
 */
public record EarthquakeShakePayload(double x, double y, double z, int elapsed,
                                     int preTicks, int formTicks, int fadeTicks,
                                     float intensity, int radius) implements CustomPacketPayload {

    public static final Type<EarthquakeShakePayload> TYPE =
            new Type<>(OvergrownVegetation.id("earthquake_shake"));

    public static final StreamCodec<FriendlyByteBuf, EarthquakeShakePayload> STREAM_CODEC =
            StreamCodec.ofMember(EarthquakeShakePayload::write, EarthquakeShakePayload::new);

    private EarthquakeShakePayload(FriendlyByteBuf buf) {
        this(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(elapsed);
        buf.writeVarInt(preTicks);
        buf.writeVarInt(formTicks);
        buf.writeVarInt(fadeTicks);
        buf.writeFloat(intensity);
        buf.writeVarInt(radius);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
