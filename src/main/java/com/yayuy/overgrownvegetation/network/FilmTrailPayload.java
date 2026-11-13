package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.FilmTrails;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 水面の膜を通った跡の新しい点(S2C、2026-11-13)。{@link FilmTrails} がその場所のチャンクを見ているプレイヤーへ送る。
 * クライアントは届いた時刻で膜を抜いて描き({@code client.atmosphere.FilmWakes})、同じ持ち主の前の点とつないで筋にする。
 * 1 点 = 持ち主の id・x・y(膜の板の高さ)・z・筋の半分の幅・強さ。最大 {@value #MAX} 点。
 */
public record FilmTrailPayload(int[] owners, float[] values) implements CustomPacketPayload {

    public static final Type<FilmTrailPayload> TYPE = new Type<>(OvergrownVegetation.id("film_trail"));

    public static final StreamCodec<FriendlyByteBuf, FilmTrailPayload> STREAM_CODEC =
            StreamCodec.ofMember(FilmTrailPayload::write, FilmTrailPayload::read);

    /** 1 点の値の数(x, y, z, 幅, 強さ)。 */
    public static final int STRIDE = 5;
    static final int MAX = 256;

    public static FilmTrailPayload of(List<FilmTrails.Point> points) {
        int n = Math.min(points.size(), MAX);
        int[] owners = new int[n];
        float[] values = new float[n * STRIDE];
        for (int i = 0; i < n; i++) {
            FilmTrails.Point p = points.get(i);
            owners[i] = p.owner();
            values[i * STRIDE] = (float) p.x();
            values[i * STRIDE + 1] = (float) p.y();
            values[i * STRIDE + 2] = (float) p.z();
            values[i * STRIDE + 3] = p.width();
            values[i * STRIDE + 4] = p.strength();
        }
        return new FilmTrailPayload(owners, values);
    }

    private static FilmTrailPayload read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX);
        int[] owners = new int[n];
        float[] values = new float[n * STRIDE];
        for (int i = 0; i < n; i++) {
            owners[i] = buf.readVarInt();
            for (int k = 0; k < STRIDE; k++) {
                values[i * STRIDE + k] = buf.readFloat();
            }
        }
        return new FilmTrailPayload(owners, values);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(owners.length);
        for (int i = 0; i < owners.length; i++) {
            buf.writeVarInt(owners[i]);
            for (int k = 0; k < STRIDE; k++) {
                buf.writeFloat(values[i * STRIDE + k]);
            }
        }
    }

    public int size() {
        return owners.length;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
