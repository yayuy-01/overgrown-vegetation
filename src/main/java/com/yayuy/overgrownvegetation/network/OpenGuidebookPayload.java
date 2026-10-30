package com.yayuy.overgrownvegetation.network;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * サーバー → クライアント。ガイドブックを開く(2026-10-15)。Mob の章に載せる Mob を、グループ順・タグの順で全部送る。
 * 体力などの数値とドロップはサーバーの値(専用サーバーではクライアントの手元の設計データ・ルートテーブルと違いうるため)。
 *
 * @param mobs Mob の章の行。出会っていない Mob も「？？？」の行として送る(数値とドロップは空)
 */
public record OpenGuidebookPayload(List<Mob> mobs) implements CustomPacketPayload {

    static final int MAX_MOBS = 512;
    static final int MAX_DROPS = 32;

    /**
     * @param id         エンティティの id
     * @param group      グループの番号(1〜10)
     * @param discovered この人がもう出会ったか(倒した / 倒された)
     * @param health     体力(既定の属性)。出会っていなければ 0
     * @param attack     攻撃力(既定の属性。持っていない Mob は 0)
     * @param armor      防御力(既定の属性)
     * @param drops      ドロップするアイテム(ルートテーブルに書かれているもの)
     */
    public record Mob(ResourceLocation id, int group, boolean discovered, float health, float attack, float armor,
                      List<ResourceLocation> drops) {
    }

    public static final Type<OpenGuidebookPayload> TYPE = new Type<>(OvergrownVegetation.id("open_guidebook"));

    public static final StreamCodec<FriendlyByteBuf, OpenGuidebookPayload> STREAM_CODEC =
            StreamCodec.ofMember(OpenGuidebookPayload::write, OpenGuidebookPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(mobs.size());
        for (Mob m : mobs) {
            buf.writeResourceLocation(m.id());
            buf.writeVarInt(m.group());
            buf.writeBoolean(m.discovered());
            if (m.discovered()) {
                buf.writeFloat(m.health());
                buf.writeFloat(m.attack());
                buf.writeFloat(m.armor());
                buf.writeVarInt(m.drops().size());
                for (ResourceLocation d : m.drops()) {
                    buf.writeResourceLocation(d);
                }
            }
        }
    }

    private static OpenGuidebookPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_MOBS) {
            throw new DecoderException("guidebook mobs: " + size);
        }
        List<Mob> mobs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ResourceLocation id = buf.readResourceLocation();
            int group = buf.readVarInt();
            boolean discovered = buf.readBoolean();
            if (!discovered) {
                mobs.add(new Mob(id, group, false, 0, 0, 0, List.of()));
                continue;
            }
            float health = buf.readFloat(), attack = buf.readFloat(), armor = buf.readFloat();
            int n = buf.readVarInt();
            if (n < 0 || n > MAX_DROPS) {
                throw new DecoderException("guidebook drops: " + n);
            }
            List<ResourceLocation> drops = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                drops.add(buf.readResourceLocation());
            }
            mobs.add(new Mob(id, group, true, health, attack, armor, List.copyOf(drops)));
        }
        return new OpenGuidebookPayload(List.copyOf(mobs));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
