package com.yayuy.overgrownvegetation.port.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の {@code ArmorMaterial}(登録簿に載る record)を、1.20.1 の {@link ArmorMaterial}(interface)で作る。
 * 本流の {@code new ArmorMaterial(defense, enchant, sound, repair, layers, toughness, knockback)} と同じ並び
 * (port-rewrite.pl が {@code new OvArmorMaterial(} に書き換える。音は 1.20.1 では Holder でなく SoundEvent)。
 *
 * <p>耐久は 1.20.5 と同じく Item.Properties の {@code durability} で決める({@link #getDurabilityForType} は 0)。
 * 名前(防具の絵の場所)は登録の id({@code DeferredRegister} が結び付ける)か、最初の Layer の id。
 */
public final class OvArmorMaterial implements ArmorMaterial {

    public record Layer(ResourceLocation id) {
    }

    private final Map<ArmorItem.Type, Integer> defense;
    private final int enchantmentValue;
    private final SoundEvent equipSound;
    private final Supplier<Ingredient> repairIngredient;
    private final List<Layer> layers;
    private final float toughness;
    private final float knockbackResistance;
    @Nullable
    private volatile ResourceLocation id;

    public OvArmorMaterial(Map<ArmorItem.Type, Integer> defense, int enchantmentValue, SoundEvent equipSound, Supplier<Ingredient> repairIngredient,
                           List<Layer> layers, float toughness, float knockbackResistance) {
        this.defense = defense;
        this.enchantmentValue = enchantmentValue;
        this.equipSound = equipSound;
        this.repairIngredient = repairIngredient;
        this.layers = layers;
        this.toughness = toughness;
        this.knockbackResistance = knockbackResistance;
    }

    /** {@code DeferredRegister}(ARMOR_MATERIAL の鍵)が登録のときに呼ぶ。 */
    public void bind(ResourceLocation id) {
        this.id = id;
    }

    public List<Layer> layers() {
        return layers;
    }

    public Map<ArmorItem.Type, Integer> defense() {
        return defense;
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return 0;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return defense.getOrDefault(type, 0);
    }

    @Override
    public int getEnchantmentValue() {
        return enchantmentValue;
    }

    @Override
    public SoundEvent getEquipSound() {
        return equipSound;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repairIngredient.get();
    }

    @Override
    public String getName() {
        ResourceLocation n = !layers.isEmpty() ? layers.get(0).id() : id;
        return n == null ? "unknown" : n.toString();
    }

    @Override
    public float getToughness() {
        return toughness;
    }

    @Override
    public float getKnockbackResistance() {
        return knockbackResistance;
    }
}
