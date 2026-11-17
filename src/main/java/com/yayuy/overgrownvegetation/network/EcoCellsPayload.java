package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「まわりの升の G2・G3 の賑わい」(2026-11-17。見た目だけの小さな生き物の数を升の頭数に合わせる。
 * Vault「G2 原生生産種・G3 原生消費種」の【決定 11-17 ユーザー】)。{@code ecosystem.EcoCellsSender} が送り、
 * {@code client.atmosphere.EcoCellsClient} が受ける。
 *
 * <p>升 = 2^shift × 2^shift チャンク(台帳 {@code EcoLedger} の升)。中心の升 (centerX, centerZ) から半径 radius 升の正方形を、
 * x が内側・z が外側の順に並べる(添字 = (dz + r) × (2r + 1) + (dx + r))。値は頭数 ÷ 満員 を 0〜255 に丸めたもの(符号なし)。
 * 配列が空 = 値なし(オーバーワールドの外・台帳が切ってある)。クライアントは値なしを満員(1.0)として読む。
 */
public record EcoCellsPayload(int centerX, int centerZ, byte shift, byte radius, byte[] g2, byte[] g3) implements CustomPacketPayload {

    public static final EcoCellsPayload NONE = new EcoCellsPayload(0, 0, (byte) 2, (byte) 0, new byte[0], new byte[0]);

    public static final Type<EcoCellsPayload> TYPE = new Type<>(OvergrownVegetation.id("eco_cells"));

    public static final StreamCodec<FriendlyByteBuf, EcoCellsPayload> STREAM_CODEC =
            StreamCodec.ofMember(EcoCellsPayload::write, EcoCellsPayload::read);

    private static EcoCellsPayload read(FriendlyByteBuf buf) {
        int cx = buf.readVarInt(), cz = buf.readVarInt();
        byte shift = buf.readByte(), radius = buf.readByte();
        return new EcoCellsPayload(cx, cz, shift, radius, buf.readByteArray(1024), buf.readByteArray(1024));
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(centerX);
        buf.writeVarInt(centerZ);
        buf.writeByte(shift);
        buf.writeByte(radius);
        buf.writeByteArray(g2);
        buf.writeByteArray(g3);
    }

    public boolean empty() {
        return g2.length == 0;
    }

    /** 一辺の升の数。 */
    public int side() {
        return 2 * radius + 1;
    }

    /** 升 (cellX, cellZ) の添字。範囲の外・値なしなら -1。 */
    public int index(int cellX, int cellZ) {
        int dx = cellX - centerX, dz = cellZ - centerZ;
        if (empty() || Math.abs(dx) > radius || Math.abs(dz) > radius) return -1;
        int i = (dz + radius) * side() + (dx + radius);
        return i < g2.length && i < g3.length ? i : -1;
    }

    /** 0〜255 の値を 0〜1 に。 */
    public static float unpack(byte b) {
        return (b & 0xFF) / 255.0F;
    }

    public static byte pack(double fraction) {
        return (byte) Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * 255.0D);
    }

    /** 中身が同じか(record の equals は配列を中身で比べないため)。 */
    public boolean sameAs(EcoCellsPayload o) {
        return o != null && centerX == o.centerX && centerZ == o.centerZ && shift == o.shift && radius == o.radius
                && java.util.Arrays.equals(g2, o.g2) && java.util.Arrays.equals(g3, o.g3);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
