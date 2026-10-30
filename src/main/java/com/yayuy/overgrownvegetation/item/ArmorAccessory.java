package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 装飾品・拡張パーツ(順 1 の着手順 9、2026-10-03)。パワードアーマー作成テーブルの装着タブで防具に付ける、
 * 軽減ではない「ちょっとした役に立つ機能」(ユーザー指定)。
 *
 * <ul>
 *   <li><b>装飾品</b>({@link Kind#ACCESSORY})—— どの防具にも付く(寄生植物装備だけ除く。段階が上がるとアイテムが作り直されるため)</li>
 *   <li><b>拡張パーツ</b>({@link Kind#EXTENSION})—— パワードアーマー専用</li>
 * </ul>
 * <b>名前で付け先の種類が分かる</b>のがユーザー指定の肝。付く部位は 1 つに決まっていて、1 部位に装飾品 1 つ・拡張パーツ 1 つ。
 * 防具には id の文字列で載る({@link Kind#component()})。外すと同じアイテムに戻る。
 *
 * <p>一覧・部位・数値は Claude 仮決め(数値は {@code gear.json} の {@code accessories})。効き目は 2 通り:
 * 属性({@link #modifier} を {@code ArmorAccessoryEffects} が足す)と、見た目だけのもの
 * (暗視 = ライトマップ、索敵 = 発光の輪郭。どちらもクライアントの mixin が {@code client/ArmorAccessoryClient} を見る)。
 */
public enum ArmorAccessory {
    NIGHT_VISION("night_vision", Kind.ACCESSORY, ArmorItem.Type.HELMET, () -> ModItems.NIGHT_VISION_ACCESSORY.get()),
    ENEMY_SENSE("enemy_sense", Kind.ACCESSORY, ArmorItem.Type.HELMET, () -> ModItems.ENEMY_SENSE_ACCESSORY.get()),
    SWIFT("swift", Kind.ACCESSORY, ArmorItem.Type.CHESTPLATE, () -> ModItems.SWIFT_ACCESSORY.get()),
    CUSHION("cushion", Kind.ACCESSORY, ArmorItem.Type.BOOTS, () -> ModItems.CUSHION_ACCESSORY.get()),
    MINING("mining", Kind.EXTENSION, ArmorItem.Type.CHESTPLATE, () -> ModItems.MINING_EXTENSION.get()),
    ANCHOR("anchor", Kind.EXTENSION, ArmorItem.Type.LEGGINGS, () -> ModItems.ANCHOR_EXTENSION.get());

    /** 装飾品か拡張パーツか。 */
    public enum Kind {
        ACCESSORY("accessory"),
        EXTENSION("extension");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 防具に載るコンポーネント(中身は {@link ArmorAccessory#id()})。 */
        public DataComponentType<String> component() {
            return this == ACCESSORY ? ModDataComponents.ARMOR_ACCESSORY.get() : ModDataComponents.ARMOR_EXTENSION.get();
        }

        /** 呼び名の翻訳キー(「装飾品」「拡張パーツ」)。 */
        public String nameKey() {
            return "accessory.overgrown_vegetation.kind." + id;
        }
    }

    /** {@link #canHold} の答え。 */
    public enum Fit {
        OK, NO_ARMOR, NOT_ACCEPTED, NOT_POWERED, WRONG_PART
    }

    private final String id;
    private final Kind kind;
    private final ArmorItem.Type type;
    private final Supplier<Item> item;

    ArmorAccessory(String id, Kind kind, ArmorItem.Type type, Supplier<Item> item) {
        this.id = id;
        this.kind = kind;
        this.type = type;
        this.item = item;
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** 付く部位。 */
    public ArmorItem.Type type() {
        return type;
    }

    public Item item() {
        return item.get();
    }

    /** 差し口に出す短い名前の翻訳キー(「暗視」など)。 */
    public String shortKey() {
        return "accessory.overgrown_vegetation." + id + ".short";
    }

    /** 効き目の 1 行(「移動速度 +10%」など。数値は {@code gear.json} から)。 */
    public Component effect() {
        GearTable.Accessories c = DesignData.gear().accessories();
        String key = "accessory.overgrown_vegetation." + id + ".effect";
        return switch (this) {
            case NIGHT_VISION -> Component.translatable(key, PoweredArmorItem.percent(c.nightVision()));
            case ENEMY_SENSE -> Component.translatable(key, (int) Math.round(c.enemySenseRadius()));
            case SWIFT -> Component.translatable(key, PoweredArmorItem.percent(c.swiftSpeed()));
            case CUSHION -> Component.translatable(key, PoweredArmorItem.percent(c.cushionFall()));
            case MINING -> Component.translatable(key, PoweredArmorItem.percent(c.miningSpeed()));
            case ANCHOR -> Component.translatable(key, PoweredArmorItem.percent(c.anchorKnockback()));
        };
    }

    /** 付け先の部位の名前(テーブルの部位名と同じ翻訳キー)。 */
    public Component partName() {
        return PoweredArmorBlueprintItem.partName(type.getName());
    }

    /** 「ヘルメット用」(画面の狭い所・付けられない理由に出す短い付け先)。 */
    public net.minecraft.network.chat.MutableComponent forPart() {
        return Component.translatable("accessory.overgrown_vegetation.for_part", partName());
    }

    /** 付け先の 1 行(説明用。「付け先: ヘルメット(どの防具にも付く)」/「付け先: パワードアーマーのチェストプレート」)。 */
    public net.minecraft.network.chat.MutableComponent placement() {
        return Component.translatable("accessory.overgrown_vegetation.placement." + kind.id, partName());
    }

    /** 属性で効くものの属性(見た目だけのものは null)。 */
    @Nullable
    public Holder<Attribute> attribute() {
        return switch (this) {
            case SWIFT -> Attributes.MOVEMENT_SPEED;
            case CUSHION -> Attributes.FALL_DAMAGE_MULTIPLIER;
            case MINING -> Attributes.BLOCK_BREAK_SPEED;
            case ANCHOR -> Attributes.KNOCKBACK_RESISTANCE;
            default -> null;
        };
    }

    /**
     * 属性の修飾(見た目だけのものは null)。id に部位を入れる —— 同じ id の修飾が 2 つ付くとバニラが例外を投げるので、
     * 後で付く部位を緩めても重ならないように。
     */
    @Nullable
    public AttributeModifier modifier() {
        GearTable.Accessories c = DesignData.gear().accessories();
        var rl = OvergrownVegetation.id("accessory/" + id + "/" + type.getName());
        return switch (this) {
            case SWIFT -> new AttributeModifier(rl, c.swiftSpeed(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            case CUSHION -> new AttributeModifier(rl, -c.cushionFall(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            case MINING -> new AttributeModifier(rl, c.miningSpeed(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            case ANCHOR -> new AttributeModifier(rl, c.anchorKnockback(), AttributeModifier.Operation.ADD_VALUE);
            default -> null;
        };
    }

    /** {@code armor} にこれを付けられるか。 */
    public Fit canHold(ItemStack armor) {
        if (armor.isEmpty() || !(armor.getItem() instanceof ArmorItem armorItem)) {
            return Fit.NO_ARMOR;
        }
        if (!acceptsAccessories(armor)) {
            return Fit.NOT_ACCEPTED;
        }
        if (kind == Kind.EXTENSION && !(armorItem instanceof PoweredArmorItem)) {
            return Fit.NOT_POWERED;
        }
        return armorItem.getType() == type ? Fit.OK : Fit.WRONG_PART;
    }

    // ---------- 引き ----------

    @Nullable
    public static ArmorAccessory byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (ArmorAccessory a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    /** そのスタックが装飾品・拡張パーツのアイテムなら種類、違えば null。 */
    @Nullable
    public static ArmorAccessory of(ItemStack stack) {
        return stack.getItem() instanceof ArmorAccessoryItem item ? item.accessory() : null;
    }

    /** 防具に付いている装飾品(または拡張パーツ)。無い・知らない id なら null。 */
    @Nullable
    public static ArmorAccessory attached(ItemStack armor, Kind kind) {
        return armor.isEmpty() ? null : byId(armor.get(kind.component()));
    }

    /** 装飾品を付けられる防具か(防具なら全部。寄生植物装備だけ除く)。作成テーブルの作業枠もこれで受け付ける。 */
    public static boolean acceptsAccessories(ItemStack armor) {
        return armor.getItem() instanceof ArmorItem && !(armor.getItem() instanceof PlantArmorItem);
    }

    /** {@code entity} がその部位に着けている防具に、これが付いているか(見た目だけの効き目の判定)。 */
    public boolean wornBy(LivingEntity entity) {
        return attached(entity.getItemBySlot(type.getSlot()), kind) == this;
    }
}
