package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。このワールドの難易度({@link WorldDifficultyRequestPayload} への返事)。
 * 既定値もサーバーの config・design から送る(専用サーバーではクライアントの手元の値と違いうる)。
 *
 * @param canEdit この人が変えてよいか(OP か 1 人プレイのホスト)。false なら画面は見るだけ
 * @param entries 項目ごとの既定値とワールドの値
 */
public record WorldDifficultySyncPayload(boolean canEdit, List<Entry> entries) implements CustomPacketPayload {

    /** 一度に送る項目の数の上限(壊れたパケットで巨大なリストを作らないため)。 */
    static final int MAX_ENTRIES = 64;

    /**
     * @param id           {@link DifficultySetting#id()}
     * @param defaultValue ワールドの値が無いときの値
     * @param override     ワールドの値。無ければ NaN
     */
    public record Entry(String id, double defaultValue, double override) {
        public boolean hasOverride() {
            return !Double.isNaN(override);
        }
    }

    public static final Type<WorldDifficultySyncPayload> TYPE =
            new Type<>(OvergrownVegetation.id("world_difficulty_sync"));

    public static final StreamCodec<FriendlyByteBuf, WorldDifficultySyncPayload> STREAM_CODEC =
            StreamCodec.ofMember(WorldDifficultySyncPayload::write, WorldDifficultySyncPayload::read);

    public static WorldDifficultySyncPayload of(WorldDifficultyData data, boolean canEdit) {
        List<Entry> entries = new ArrayList<>();
        for (DifficultySetting setting : DifficultySetting.values()) {
            entries.add(new Entry(setting.id(), setting.defaultValue(),
                    data.override(setting).orElse(Double.NaN)));
        }
        return new WorldDifficultySyncPayload(canEdit, entries);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(canEdit);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUtf(entry.id());
            buf.writeDouble(entry.defaultValue());
            buf.writeDouble(entry.override());
        }
    }

    private static WorldDifficultySyncPayload read(FriendlyByteBuf buf) {
        boolean canEdit = buf.readBoolean();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("world difficulty entries: " + size);
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buf.readUtf(64), buf.readDouble(), buf.readDouble()));
        }
        return new WorldDifficultySyncPayload(canEdit, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
