package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

/**
 * サーバー → クライアントへ、今のフェーズと小さな段(2026-11-14)。{@code phase.PhaseSync} がフェーズ・段が変わったとき・
 * 進みが 1% 動いたとき・ログインしたときに送り、{@code client.phase.ClientPhaseState} が持つ。
 *
 * @param phase    今のフェーズ(0〜)
 * @param stage    段の並び(0 = 導入・1 = 定着・2 = 最盛。{@code phase.PhaseStage})
 * @param progress このフェーズの進み(0〜1。次のフェーズの閾値が無いときは -1)
 * @param strength 今のフェーズの物の、今の段の倍率({@code PhaseStages#currentStrength})
 */
public record PhaseStatePayload(int phase, int stage, float progress, float strength) implements CustomPacketPayload {

    public static final Type<PhaseStatePayload> TYPE = new Type<>(OvergrownVegetation.id("phase_state"));

    public static final StreamCodec<FriendlyByteBuf, PhaseStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(PhaseStatePayload::write, PhaseStatePayload::new);

    private PhaseStatePayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(phase);
        buf.writeVarInt(stage);
        buf.writeFloat(progress);
        buf.writeFloat(strength);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
