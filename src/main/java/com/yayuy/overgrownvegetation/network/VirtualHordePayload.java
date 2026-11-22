package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアントへ「まわりの仮想の群れ」(2026-11-18 大群の軽量化 A。{@code horde/VirtualHordeSync} が 20 tick ごとに送り、
 * {@code client/horde/ClientVirtualHordes} が受ける)。遠景の身代わりを描くのはセッション C(GPU スキニング)。
 * 差分なしの全部入り(取りこぼしても次で揃う)。空の一覧 = まわりに群れが無い。
 */
public record VirtualHordePayload(List<Swarm> swarms) implements CustomPacketPayload {

    public static final int MAX_SWARMS = 256, MAX_COUNTS = 16;

    public static final Type<VirtualHordePayload> TYPE = new Type<>(OvergrownVegetation.id("virtual_horde"));

    /** 群れ 1 つ。速さはブロック/tick(待っている・着いた群れは 0)。firstIndex = counts の先頭の一員の並びの番号(VirtualHordeLayout.offset の index は firstIndex + 通し番号)。 */
    public record Swarm(long id, double x, double y, double z, float dirX, float dirZ, float speed, int firstIndex, List<Count> counts) {
    }

    public record Count(EntityType<?> type, int n) {
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, EntityType<?>> TYPE_CODEC = ByteBufCodecs.registry(Registries.ENTITY_TYPE);

    public static final StreamCodec<RegistryFriendlyByteBuf, VirtualHordePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                int n = Math.min(MAX_SWARMS, p.swarms.size());
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    Swarm s = p.swarms.get(i);
                    buf.writeLong(s.id);
                    buf.writeDouble(s.x);
                    buf.writeDouble(s.y);
                    buf.writeDouble(s.z);
                    buf.writeFloat(s.dirX);
                    buf.writeFloat(s.dirZ);
                    buf.writeFloat(s.speed);
                    buf.writeVarInt(s.firstIndex);
                    int c = Math.min(MAX_COUNTS, s.counts.size());
                    buf.writeVarInt(c);
                    for (int k = 0; k < c; k++) {
                        TYPE_CODEC.encode(buf, s.counts.get(k).type());
                        buf.writeVarInt(s.counts.get(k).n());
                    }
                }
            },
            buf -> {
                int n = Math.min(MAX_SWARMS, buf.readVarInt());
                List<Swarm> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    long id = buf.readLong();
                    double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                    float dx = buf.readFloat(), dz = buf.readFloat(), sp = buf.readFloat();
                    int first = buf.readVarInt();
                    int c = Math.min(MAX_COUNTS, buf.readVarInt());
                    List<Count> counts = new ArrayList<>(c);
                    for (int k = 0; k < c; k++) counts.add(new Count(TYPE_CODEC.decode(buf), Math.min(512, buf.readVarInt())));
                    list.add(new Swarm(id, x, y, z, dx, dz, sp, first, counts));
                }
                return new VirtualHordePayload(list);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
