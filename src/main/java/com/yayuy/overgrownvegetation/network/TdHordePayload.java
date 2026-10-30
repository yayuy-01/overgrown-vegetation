package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアントへ、TD の<b>行進中の仮想の群れ</b>(エンティティを持たない、データだけの群れ)を伝える S2C ペイロード
 * (2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」の 2 段目)。クライアントは群れの一員を遠景の身代わり
 * ({@code client/horde/HordeRenderer})で描く。
 *
 * <p>{@code td/TdHordeSync} が帳簿のたび(20 tick ごと)に、近くのプレイヤーへ<b>今ある群れの全部</b>を送る(差分ではない。取りこぼしても
 * 次で揃う)。群れが実体になった帳簿でもすぐ送るので、身代わりが消えるのと本物が出るのが同じ tick になる。空の一覧は「群れは無い」。
 * 位置は送った時点のもので、クライアントはターゲットの向きへ {@code speed} で {@code travel} まで延ばして動かす(行進は直線で、止まる輪の手前まで)。
 *
 * @param swarms 群れの一覧
 */
public record TdHordePayload(List<Swarm> swarms) implements CustomPacketPayload {

    /**
     * 群れ 1 つ。
     *
     * @param id     群れの番号(クライアントが同じ群れを見分ける・隊列の乱数の種)
     * @param x      中心の x
     * @param y      中心の足元の y(空の群れは飛ぶ高さ)
     * @param z      中心の z
     * @param dirX   進む向き(単位ベクトルの x。ふつうはターゲットへ。置けなかった群れが待つ輪に沿って横へ歩く間はその接線。
     *               止まっていても向きはある = 群れが向く方)
     * @param dirZ   進む向き(z)
     * @param speed  1 tick に進む距離(止まる輪に着いた・予告中・一時停止中は 0)
     * @param travel この先まだ進める距離(ブロック。止まる輪まで・横へ歩く残り)
     * @param air    空の群れ
     * @param counts 種ごとの頭数(並びの順)
     */
    public record Swarm(int id, double x, float y, double z, float dirX, float dirZ, float speed, float travel,
                        boolean air, List<Count> counts) {
    }

    /** 種と頭数。 */
    public record Count(EntityType<?> type, int count) {
    }

    public static final Type<TdHordePayload> TYPE = new Type<>(OvergrownVegetation.id("td_horde"));

    /** 読むときの上限(壊れた・悪意のあるパケットで大きな配列を作らない)。送る側もこれより多ければ近い順に切る。 */
    public static final int MAX_SWARMS = 256;
    private static final int MAX_COUNTS = 64;

    private static final StreamCodec<RegistryFriendlyByteBuf, EntityType<?>> ENTITY_TYPE = ByteBufCodecs.registry(Registries.ENTITY_TYPE);

    public static final StreamCodec<RegistryFriendlyByteBuf, TdHordePayload> STREAM_CODEC =
            StreamCodec.of(TdHordePayload::write, TdHordePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, TdHordePayload payload) {
        buf.writeVarInt(payload.swarms.size());
        for (Swarm s : payload.swarms) {
            buf.writeInt(s.id());
            buf.writeDouble(s.x());
            buf.writeFloat(s.y());
            buf.writeDouble(s.z());
            buf.writeFloat(s.dirX());
            buf.writeFloat(s.dirZ());
            buf.writeFloat(s.speed());
            buf.writeFloat(s.travel());
            buf.writeBoolean(s.air());
            buf.writeVarInt(s.counts().size());
            for (Count c : s.counts()) {
                ENTITY_TYPE.encode(buf, c.type());
                buf.writeVarInt(c.count());
            }
        }
    }

    private static TdHordePayload read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_SWARMS) {
            throw new DecoderException("TD の群れが多すぎる: " + n);
        }
        List<Swarm> swarms = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = buf.readInt();
            double x = buf.readDouble();
            float y = buf.readFloat();
            double z = buf.readDouble();
            float dirX = buf.readFloat();
            float dirZ = buf.readFloat();
            float speed = buf.readFloat();
            float travel = buf.readFloat();
            boolean air = buf.readBoolean();
            int m = buf.readVarInt();
            if (m < 0 || m > MAX_COUNTS) {
                throw new DecoderException("TD の群れの種が多すぎる: " + m);
            }
            List<Count> counts = new ArrayList<>(m);
            for (int j = 0; j < m; j++) {
                counts.add(new Count(ENTITY_TYPE.decode(buf), buf.readVarInt()));
            }
            swarms.add(new Swarm(id, x, y, z, dirX, dirZ, speed, travel, air, counts));
        }
        return new TdHordePayload(swarms);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
