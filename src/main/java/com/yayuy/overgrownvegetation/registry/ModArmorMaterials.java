package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * MOD の防具素材({@link ArmorMaterial})。防具値・靭性・耐久係数などの数値は
 * {@link GearTable}(= {@code config/overgrown_vegetation/gear.json})から登録時に読む。
 *
 * <p>テクスチャは {@code assets/overgrown_vegetation/textures/models/armor/<key>_layer_1.png}
 * (兜・胸当て・ブーツ)と {@code <key>_layer_2.png}(レギンス)。
 *
 * <p>{@code DesignData.load()} は MOD コンストラクタ先頭で走るので、この
 * {@code DeferredRegister} が解決される頃には {@link DesignData#gear()} は読み込み済み。
 */
public final class ModArmorMaterials {

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, OvergrownVegetation.MODID);

    /** 「スカトゥリウムの外骨格装備」— スカトゥリウムの装甲板を外骨格で継いだ軽装。修理素材は「スカトゥリウムの外骨格」。 */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> EXOSKELETON =
            register("exoskeleton", SoundEvents.ARMOR_EQUIP_GENERIC, () -> ModItems.SCATURIUM_EXOSKELETON);

    /** 「クロロフィライト装備」— 葉緑を宿した鉱石を精錬して鍛えた装備。修理素材は精錬後の「クロロフィライト」。 */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> CHLOROPHYLLITE =
            register("chlorophyllite", SoundEvents.ARMOR_EQUIP_IRON, () -> ModItems.CHLOROPHYLLITE);

    /** 「クロロフィライト合金装備」— クロロフィライトを他素材と合金化した最上位装備。 */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> CHLOROPHYLLITE_ALLOY =
            register("chlorophyllite_alloy", SoundEvents.ARMOR_EQUIP_NETHERITE, () -> ModItems.CHLOROPHYLLITE_ALLOY);

    /**
     * 「強化クロロフィライト合金装備」— 合金を更に樹液結晶軟化剤で鍛え直した最上位装備。
     * 防御力・耐久はネザライトの 1.5 倍で、過繫茂 Mob からの被ダメージも減らす
     * ({@link com.yayuy.overgrownvegetation.item.ReinforcedChlorophylliteAlloyArmorItem})。
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> REINFORCED_CHLOROPHYLLITE_ALLOY =
            register("reinforced_chlorophyllite_alloy", SoundEvents.ARMOR_EQUIP_NETHERITE,
                    () -> ModItems.CHLOROPHYLLITE_ALLOY);

    /**
     * 「パワードアーマー」(順 1 の着手順 6)の土台。防御点 30・強靭 20 はバニラの属性の上限で、上積みはスキルツリー
     * ({@link com.yayuy.overgrownvegetation.item.PoweredArmorItem})。修理素材はサンライト合金。
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> POWERED_ARMOR =
            register("powered_armor", SoundEvents.ARMOR_EQUIP_NETHERITE, () -> ModItems.SUNLIGHT_ALLOY);

    /**
     * 「防護マスク」({@link com.yayuy.overgrownvegetation.item.ProtectiveMaskItem})専用。防具値は
     * {@code gear.json} に {@code armor.protective_mask} が無ければ汎用値。修理素材は革。
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> PROTECTIVE_MASK =
            register("protective_mask", SoundEvents.ARMOR_EQUIP_LEATHER, () -> net.minecraft.world.item.Items.LEATHER);

    /**
     * 「寄生植物装備」。防御力・耐久は段階で変わるのでアイテム側({@link com.yayuy.overgrownvegetation.item.PlantArmorItem})が
     * 属性で出す。ここは gear.json を通さない(段階制なので表に載せない)。装備音は空: 耐久値が回復するたびに
     * 「装備が変わった」扱いで音が鳴ってしまうため。修理素材は無し(勝手に回復する)。
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> PLANT_ARMOR =
            ARMOR_MATERIALS.register("plant_armor", () -> {
                EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
                for (ArmorItem.Type type : ArmorItem.Type.values()) {
                    defense.put(type, 0);
                }
                return new ArmorMaterial(defense, 0, net.minecraft.core.Holder.direct(SoundEvents.EMPTY),
                        () -> Ingredient.EMPTY, List.of(new ArmorMaterial.Layer(OvergrownVegetation.id("plant_armor"))),
                        0.0F, 0.0F);
            });

    private static DeferredHolder<ArmorMaterial, ArmorMaterial> register(
            String name, Holder<SoundEvent> equipSound, Supplier<? extends ItemLike> repairItem) {
        return ARMOR_MATERIALS.register(name, () -> {
            GearTable.ArmorSpec spec = DesignData.gear().armor(name);
            EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
            for (ArmorItem.Type type : ArmorItem.Type.values()) {
                defense.put(type, spec.defense(type));
            }
            return new ArmorMaterial(
                    defense,
                    spec.enchantmentValue(),
                    equipSound,
                    () -> Ingredient.of(repairItem.get()),
                    List.of(new ArmorMaterial.Layer(OvergrownVegetation.id(name))),
                    spec.toughness(),
                    spec.knockbackResistance());
        });
    }

    /** 指定セットの防具アイテムの耐久係数({@code ArmorItem.Type#getDurability} に渡す)。 */
    public static int durabilityFactor(String name) {
        return DesignData.gear().armor(name).durabilityFactor();
    }

    /** 指定セット・部位の実耐久(gear.json で部位別の絶対値が指定されていればそれ、無ければ係数計算)。 */
    public static int durability(String name, ArmorItem.Type type) {
        return DesignData.gear().armor(name).durability(type);
    }

    public static void register(IEventBus modBus) {
        ARMOR_MATERIALS.register(modBus);
    }

    private ModArmorMaterials() {
    }
}
