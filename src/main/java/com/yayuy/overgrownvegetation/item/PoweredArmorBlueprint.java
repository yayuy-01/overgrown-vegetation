package com.yayuy.overgrownvegetation.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ArmorItem;

/**
 * 設計図に写したパワードアーマー 1 部位(順 1 の着手順 6、2026-10-03)。
 *
 * <p>白紙の設計図にはコンポーネントを載せない。写すとこれが載る(部位・Mk・ツリー)。
 * 作り直しても設計図は残る(ユーザー指定 2026-10-03)ので、ツリーを進めたら写し直す。
 *
 * @param part 部位({@link ArmorItem.Type#getName()}: helmet / chestplate / leggings / boots)
 * @param mk   Mk(1.0.0 は 1 だけ)
 * @param tree ツリーの状態
 */
public record PoweredArmorBlueprint(String part, int mk, PoweredArmorTree tree) {

    public static final Codec<PoweredArmorBlueprint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("part").forGetter(PoweredArmorBlueprint::part),
            Codec.INT.optionalFieldOf("mk", 1).forGetter(PoweredArmorBlueprint::mk),
            PoweredArmorTree.CODEC.optionalFieldOf("tree", PoweredArmorTree.NONE).forGetter(PoweredArmorBlueprint::tree)
    ).apply(i, PoweredArmorBlueprint::new));

    public static final StreamCodec<ByteBuf, PoweredArmorBlueprint> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PoweredArmorBlueprint::part,
            ByteBufCodecs.VAR_INT, PoweredArmorBlueprint::mk,
            PoweredArmorTree.STREAM_CODEC, PoweredArmorBlueprint::tree,
            PoweredArmorBlueprint::new);

    /** 部位の型。知らない名前なら null。 */
    public ArmorItem.Type type() {
        for (ArmorItem.Type t : ArmorItem.Type.values()) {
            if (t.getName().equals(part)) {
                return t;
            }
        }
        return null;
    }
}
