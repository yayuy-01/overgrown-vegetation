package com.yayuy.overgrownvegetation.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * パワードアーマー 1 部位ぶんのスキルツリーの状態(順 1 の着手順 6、2026-10-03)。3 本 × 段(1.0.0 は 3 段)。
 *
 * <p>アイテムが持つ(ユーザー指定「研究の進行度ではなくアイテムに持たせる」)。設計図も同じ記録を持つ。
 * 何も上げていない装備にはコンポーネントを載せない({@link #NONE} として読む)。
 *
 * <ul>
 *   <li>{@link #DEFENSE 防御} —— 受ける被害を減らす(防具の計算の後)</li>
 *   <li>{@link #TOUGHNESS 強靭} —— 防具を貫通する攻撃({@code #overgrown_vegetation:armor_piercing})を減らす</li>
 *   <li>{@link #DURABILITY 耐久} —— 最大耐久を増やす</li>
 * </ul>
 * 防御・強靭がバニラの防御点・強靭の属性ではないのは、強化クロロフィライト合金で属性も軽減も上限に届いていて、
 * 積んでも差が出ないため(ユーザー指定 2026-10-03「A」。計算は {@code docs/ORDER1_IMPL.md} の 3 節)。
 * 1 段あたりの効き目と支払いは {@code gear.json} の {@code poweredArmor}。
 */
public record PoweredArmorTree(int defense, int toughness, int durability) {

    public static final int DEFENSE = 0;
    public static final int TOUGHNESS = 1;
    public static final int DURABILITY = 2;
    public static final int BRANCHES = 3;

    public static final PoweredArmorTree NONE = new PoweredArmorTree(0, 0, 0);

    public static final Codec<PoweredArmorTree> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("defense", 0).forGetter(PoweredArmorTree::defense),
            Codec.INT.optionalFieldOf("toughness", 0).forGetter(PoweredArmorTree::toughness),
            Codec.INT.optionalFieldOf("durability", 0).forGetter(PoweredArmorTree::durability)
    ).apply(i, PoweredArmorTree::new));

    public static final StreamCodec<ByteBuf, PoweredArmorTree> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PoweredArmorTree::defense,
            ByteBufCodecs.VAR_INT, PoweredArmorTree::toughness,
            ByteBufCodecs.VAR_INT, PoweredArmorTree::durability,
            PoweredArmorTree::new);

    /** 本の番号({@link #DEFENSE} ほか)の段。 */
    public int level(int branch) {
        return switch (branch) {
            case DEFENSE -> defense;
            case TOUGHNESS -> toughness;
            case DURABILITY -> durability;
            default -> 0;
        };
    }

    /** 本の番号の段を {@code level} にした写し。 */
    public PoweredArmorTree withLevel(int branch, int level) {
        return switch (branch) {
            case DEFENSE -> new PoweredArmorTree(level, toughness, durability);
            case TOUGHNESS -> new PoweredArmorTree(defense, level, durability);
            case DURABILITY -> new PoweredArmorTree(defense, toughness, level);
            default -> this;
        };
    }

    public boolean isEmpty() {
        return defense == 0 && toughness == 0 && durability == 0;
    }
}
