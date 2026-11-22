package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.difficulty.DifficultySetting;
import com.yayuy.overgrownvegetation.difficulty.DifficultyValues;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * サーバー → クライアント。このワールドの設定({@link WorldDifficultyRequestPayload} への返事)。
 * 既定値と Mob の一覧もサーバーの config・design から送る(専用サーバーではクライアントの手元の値と違いうる)。
 *
 * @param canEdit  この人が変えてよいか(OP か 1 人プレイのホスト)。false なら画面は見るだけ
 * @param defaults 項目ごとの既定値
 * @param values   ワールドの値(項目と Mob ごとの詳細)
 * @param mobs     Mob の強さの詳細に並べる Mob の id
 */
public record WorldDifficultySyncPayload(boolean canEdit, Map<DifficultySetting, Double> defaults, DifficultyValues values,
                                         List<String> mobs) implements CustomPacketPayload {

    public static final Type<WorldDifficultySyncPayload> TYPE =
            new Type<>(OvergrownVegetation.id("world_difficulty_sync"));

    public static final StreamCodec<FriendlyByteBuf, WorldDifficultySyncPayload> STREAM_CODEC =
            StreamCodec.ofMember(WorldDifficultySyncPayload::write, WorldDifficultySyncPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(canEdit);
        buf.writeVarInt(defaults.size());
        defaults.forEach((s, v) -> {
            buf.writeUtf(s.id());
            buf.writeDouble(v);
        });
        values.write(buf);
        buf.writeVarInt(mobs.size());
        for (String id : mobs) buf.writeUtf(id);
    }

    private static WorldDifficultySyncPayload read(FriendlyByteBuf buf) {
        boolean canEdit = buf.readBoolean();
        int n = buf.readVarInt();
        if (n < 0 || n > DifficultyValues.MAX_ENTRIES) throw new DecoderException("difficulty defaults: " + n);
        EnumMap<DifficultySetting, Double> defaults = new EnumMap<>(DifficultySetting.class);
        for (int i = 0; i < n; i++) {
            DifficultySetting s = DifficultySetting.byId(buf.readUtf(64));
            double v = buf.readDouble();
            if (s != null && Double.isFinite(v)) defaults.put(s, v);
        }
        DifficultyValues values = DifficultyValues.read(buf);
        int m = buf.readVarInt();
        if (m < 0 || m > DifficultyValues.MAX_MOBS) throw new DecoderException("difficulty mobs: " + m);
        List<String> mobs = new ArrayList<>(m);
        for (int i = 0; i < m; i++) mobs.add(buf.readUtf(128));
        return new WorldDifficultySyncPayload(canEdit, defaults, values, mobs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
