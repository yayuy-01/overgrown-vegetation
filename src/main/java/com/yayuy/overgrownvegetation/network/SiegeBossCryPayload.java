package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * サーバー → クライアント「ボスが悲鳴を上げた」。
 *
 * <p>音をパケットで配るのは、ボスがふつう 1000 ブロック先に居るため — バニラの音は
 * 減衰距離(16 × 音量)の外へは届かないし、そもそも未ロードのチャンクに居るので
 * {@code level.playSound} の対象にすらならない。ここではディメンション内の全員へ送り、
 * クライアント側が距離を見て「近ければその方角から、遠ければ地鳴りのように」鳴らす
 * ({@link com.yayuy.overgrownvegetation.client.ClientSiegeCry})。
 *
 * @param at       ボス(頭)のワールド座標
 * @param death    true = 断末魔、false = 被弾の悲鳴
 * @param severity 0〜1。1 発でどれだけ体力を削られたか。大きいほど低く長い悲鳴になる
 */
public record SiegeBossCryPayload(Vec3 at, boolean death, float severity) implements CustomPacketPayload {

    public static final Type<SiegeBossCryPayload> TYPE = new Type<>(OvergrownVegetation.id("siege_boss_cry"));

    public static final StreamCodec<FriendlyByteBuf, SiegeBossCryPayload> STREAM_CODEC =
            StreamCodec.ofMember(SiegeBossCryPayload::write, SiegeBossCryPayload::new);

    private SiegeBossCryPayload(FriendlyByteBuf buf) {
        this(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readBoolean(), buf.readFloat());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(at.x);
        buf.writeDouble(at.y);
        buf.writeDouble(at.z);
        buf.writeBoolean(death);
        buf.writeFloat(severity);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
