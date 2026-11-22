package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * サーバー → クライアントへ、HUD の 2 本のゲージの値(2026-11-13 サバイバルの背骨 段 1)。{@code survival.SurvivalSync} が値の刻みが
 * 変わったときに送り、{@code client.survival.SurvivalHud} が経験値バーの上のバーと食料の上の疲労の列に描く。
 *
 * @param exposure      暴露量の感染の量に対する割合(0〜1、1/1024 刻み)
 * @param stage         胞子感染の段階(0 = 未感染。1 以上ならバーは段階の進みに切り替わる)
 * @param stageProgress 今の段階に入ってからの進み(0〜1、1/1024 刻み。段階 10 は死ぬまでの進み)
 * @param fatigue       疲労(0〜1、1/1000 刻み)
 * @param effectsFrom   疲労の効き目が出始める割合(0〜1。列の色を変える)
 */
public record SurvivalHudPayload(float exposure, int stage, float stageProgress, float fatigue, float effectsFrom)
        implements CustomPacketPayload {

    public static final Type<SurvivalHudPayload> TYPE = new Type<>(OvergrownVegetation.id("survival_hud"));

    public static final StreamCodec<FriendlyByteBuf, SurvivalHudPayload> STREAM_CODEC =
            StreamCodec.ofMember(SurvivalHudPayload::write, SurvivalHudPayload::new);

    private SurvivalHudPayload(FriendlyByteBuf buf) {
        this(buf.readFloat(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(exposure);
        buf.writeVarInt(stage);
        buf.writeFloat(stageProgress);
        buf.writeFloat(fatigue);
        buf.writeFloat(effectsFrom);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
