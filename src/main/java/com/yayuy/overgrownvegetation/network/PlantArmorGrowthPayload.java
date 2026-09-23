package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * サーバー → クライアントへ「寄生植物装備」の成長の進み(装着時間)を伝える S2C ペイロード。
 *
 * <p>装着時間 {@code PLANT_ARMOR_TICKS} はサーバーだけが進める添付データで、クライアントには同期されない。
 * HUD とツールチップで残り時間を出すために、着けている間は毎秒と、段階が上がった瞬間に送る
 * ({@link com.yayuy.overgrownvegetation.item.PlantArmorEvents#onPlayerTick})。受け側は
 * {@link com.yayuy.overgrownvegetation.client.ClientPlantArmor}。
 *
 * @param ticks 装着時間(tick)。着けていないときは負の値
 * @param stage いまの段階(1〜3)
 */
public record PlantArmorGrowthPayload(int ticks, int stage) implements CustomPacketPayload {

    public static final Type<PlantArmorGrowthPayload> TYPE = new Type<>(OvergrownVegetation.id("plant_armor_growth"));

    public static final StreamCodec<FriendlyByteBuf, PlantArmorGrowthPayload> STREAM_CODEC =
            StreamCodec.ofMember(PlantArmorGrowthPayload::write, PlantArmorGrowthPayload::new);

    private PlantArmorGrowthPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(ticks);
        buf.writeVarInt(stage);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
