package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * クライアント → サーバー。ポーズ画面から開いた難易度の画面で「完了」が押された。
 * <b>ワールドの値をまるごと</b>送る(入っていない項目は既定値に戻す)。サーバー側で
 * {@link com.yayuy.overgrownvegetation.difficulty.WorldDifficultySync#apply} が権限を確かめ、範囲に収めてから書く。
 */
public record WorldDifficultyApplyPayload(List<Entry> overrides) implements CustomPacketPayload {

    /** @param id {@link com.yayuy.overgrownvegetation.difficulty.DifficultySetting#id()} */
    public record Entry(String id, double value) {
    }

    public static final Type<WorldDifficultyApplyPayload> TYPE =
            new Type<>(OvergrownVegetation.id("world_difficulty_apply"));

    public static final StreamCodec<FriendlyByteBuf, WorldDifficultyApplyPayload> STREAM_CODEC =
            StreamCodec.ofMember(WorldDifficultyApplyPayload::write, WorldDifficultyApplyPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(overrides.size());
        for (Entry entry : overrides) {
            buf.writeUtf(entry.id());
            buf.writeDouble(entry.value());
        }
    }

    private static WorldDifficultyApplyPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > WorldDifficultySyncPayload.MAX_ENTRIES) {
            throw new DecoderException("world difficulty overrides: " + size);
        }
        List<Entry> overrides = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            overrides.add(new Entry(buf.readUtf(64), buf.readDouble()));
        }
        return new WorldDifficultyApplyPayload(overrides);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
