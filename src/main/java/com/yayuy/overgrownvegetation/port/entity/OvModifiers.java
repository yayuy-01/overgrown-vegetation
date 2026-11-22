package com.yayuy.overgrownvegetation.port.entity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 の属性の修飾子は id(ResourceLocation)で区別するが、1.20.1 は UUID と名前。
 * id から決まった UUID を作る({@link #uuid})ので、同じ id の修飾子はいつも同じ UUID になる(付け外し・保存が本流と同じに働く)。
 *
 * <p>本流の {@code new AttributeModifier(id, amount, op)} は port-rewrite.pl が {@code OvModifiers.create(id, amount, op)} に書き換える。
 * {@code inst.removeModifier(id)}・{@code inst.hasModifier(id)}・{@code inst.getModifier(id)} は型で見分けられないので手で
 * {@link #remove}・{@link #has}・{@link #get}(か {@code removeModifier(OvModifiers.uuid(id))})にする。
 */
public final class OvModifiers {

    private OvModifiers() {
    }

    public static UUID uuid(ResourceLocation id) {
        return UUID.nameUUIDFromBytes(("overgrown_vegetation:modifier/" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static AttributeModifier create(ResourceLocation id, double amount, AttributeModifier.Operation op) {
        return new AttributeModifier(uuid(id), id.toString(), amount, op);
    }

    public static boolean has(AttributeInstance inst, ResourceLocation id) {
        return inst.getModifier(uuid(id)) != null;
    }

    @Nullable
    public static AttributeModifier get(AttributeInstance inst, ResourceLocation id) {
        return inst.getModifier(uuid(id));
    }

    public static void remove(AttributeInstance inst, ResourceLocation id) {
        inst.removeModifier(uuid(id));
    }
}
