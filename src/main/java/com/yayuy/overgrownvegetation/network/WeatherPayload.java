package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * サーバー → クライアントへ「今の天気」(2026-11-16 weather-system。天気 4 つ: 濃霧・雷雨の強化・凪・強風の日)。
 * {@code sky.WorldWeather} が 5 秒ごと・ワールドに入ったとき・ディメンションを移ったときに送り、クライアントは
 * {@code client.atmosphere.WeatherClient} が受ける。値はどれもサーバーで包絡(立ち上がり)を掛けた後の 0〜1。オーバーワールド以外は全部 0。
 *
 * @param fog        濃霧の度合い(胞子の濃さは変えない)
 * @param fogKind    濃霧の種類 0 = 朝方・1 = 雨上がり
 * @param calm       凪の度合い(地上の風だけ。雲は上空の風で流れ続ける)
 * @param gale       強風の日の度合い
 * @param stormAfter 雷雨が終わってからの残り 1 → 0(風下へ流れる胞子の雲)
 * @param thunder    雷雨の強さ(雷の間 1。差し込みの嵐は嵐の風の倍率 stormScale を掛けた値)。雷雨でなければ 0
 * @param preStorm   予定の雷雨の雷が始まる前の立ち上がり 0 → 1(差し込みの嵐は 0)
 * @param ticksLeft  今の濃霧・凪が終わるまでの tick(どちらも無ければ 0)
 * @param dayTime    送ったときのオーバーワールドの時刻
 */
public record WeatherPayload(float fog, byte fogKind, float calm, float gale, float stormAfter, float thunder, float preStorm,
                             int ticksLeft, long dayTime) implements CustomPacketPayload {

    public static final WeatherPayload NONE = new WeatherPayload(0F, (byte) 0, 0F, 0F, 0F, 0F, 0F, 0, 0L);

    public static final Type<WeatherPayload> TYPE = new Type<>(OvergrownVegetation.id("weather"));

    public static final StreamCodec<FriendlyByteBuf, WeatherPayload> STREAM_CODEC =
            StreamCodec.ofMember(WeatherPayload::write, WeatherPayload::new);

    private WeatherPayload(FriendlyByteBuf buf) {
        this(buf.readFloat(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt(), buf.readLong());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(fog);
        buf.writeByte(fogKind);
        buf.writeFloat(calm);
        buf.writeFloat(gale);
        buf.writeFloat(stormAfter);
        buf.writeFloat(thunder);
        buf.writeFloat(preStorm);
        buf.writeVarInt(Math.max(0, ticksLeft));
        buf.writeLong(dayTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
