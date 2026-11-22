package com.yayuy.overgrownvegetation.port.entity;

import com.yayuy.overgrownvegetation.port.PortConst;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * 【1.20.1 だけの物・互換の層】1.20.5 で足された属性の代わり(ユーザーの決定 6・7、11-22)。
 * 本流の {@code Attributes.SCALE} などは port-rewrite.pl が {@code PortAttributes.SCALE.get()} に書き換える。
 *
 * <ul>
 *   <li>{@link #SCALE}(既定 1): 自前の属性。{@code LivingEntity#getScale} に掛け、当たり判定({@code getDimensions})も同じだけ変わる
 *       (port の mixin {@code LivingEntityPortMixin})。値が変わったら次の tick で当たり判定を作り直す(1.20.5 と同じ)。
 *       描画の拡大は 1.20.1 のバニラの描画にはかからないので、描画の組(G5)が {@code getScale()} を掛ける。</li>
 *   <li>{@link #JUMP_STRENGTH}(既定 0.42): 自前の属性。{@code getJumpPower} の 0.42 の代わり(port の mixin)。
 *       1.20.1 の {@code Attributes.JUMP_STRENGTH} は馬だけの物なので使わない。</li>
 *   <li>{@link #BLOCK_BREAK_SPEED}(既定 1): 自前の属性。プレイヤーの掘る速さに掛ける({@code PlayerEvent.BreakSpeed})。</li>
 *   <li>{@link #STEP_HEIGHT}: Forge の {@code STEP_HEIGHT_ADDITION}(<b>段差 0.6 への足し分</b>)。1.20.5 の値(そのものの高さ)から 0.6 を引いた値になる。
 *       加える修飾子(ADD_VALUE)はそのまま同じに効く。基本の値を入れる所は {@link #stepHeightBase(double)} を通す。</li>
 * </ul>
 */
public final class PortAttributes {

    private PortAttributes() {
    }

    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, PortConst.MODID);

    public static final RegistryObject<Attribute> SCALE = ATTRIBUTES.register("generic.scale",
            () -> new RangedAttribute("attribute.name.generic.scale", 1.0D, 0.0625D, 16.0D).setSyncable(true));
    public static final RegistryObject<Attribute> JUMP_STRENGTH = ATTRIBUTES.register("generic.jump_strength",
            () -> new RangedAttribute("attribute.name.generic.jump_strength", 0.42D, 0.0D, 32.0D).setSyncable(true));
    public static final RegistryObject<Attribute> BLOCK_BREAK_SPEED = ATTRIBUTES.register("player.block_break_speed",
            () -> new RangedAttribute("attribute.name.player.block_break_speed", 1.0D, 0.0D, 1024.0D).setSyncable(true));
    /** Forge の段差の足し分(1.20.5 の STEP_HEIGHT − 0.6)。 */
    public static final Supplier<Attribute> STEP_HEIGHT = ForgeMod.STEP_HEIGHT_ADDITION;

    /** 1.20.1 の Mob・プレイヤーの段差(LivingEntity の既定)。 */
    public static final double VANILLA_STEP = 0.6D;

    /** 1.20.5 の STEP_HEIGHT の基本の値(そのものの高さ)を、Forge の足し分の基本の値に直す。 */
    public static double stepHeightBase(double absolute) {
        return absolute - VANILLA_STEP;
    }

    /** {@code PortSetup} が起動のときに呼ぶ。 */
    public static void init(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        modBus.addListener(PortAttributes::addToAll);
        MinecraftForge.EVENT_BUS.addListener(PortAttributes::onBreakSpeed);
    }

    private static void addToAll(EntityAttributeModificationEvent e) {
        for (EntityType<? extends LivingEntity> type : e.getTypes()) {
            for (RegistryObject<Attribute> a : java.util.List.of(SCALE, JUMP_STRENGTH)) {
                if (!e.has(type, a.get())) {
                    e.add(type, a.get());
                }
            }
        }
        if (!e.has(EntityType.PLAYER, BLOCK_BREAK_SPEED.get())) {
            e.add(EntityType.PLAYER, BLOCK_BREAK_SPEED.get());
        }
    }

    private static void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        Player p = e.getEntity();
        AttributeInstance inst = p.getAttribute(BLOCK_BREAK_SPEED.get());
        if (inst != null) {
            e.setNewSpeed((float) (e.getNewSpeed() * inst.getValue()));
        }
    }

    /** 属性の値(その Mob に属性が無ければ既定値)。mixin と描画の組が使う。 */
    public static double value(LivingEntity e, RegistryObject<Attribute> attr, double fallback) {
        if (!attr.isPresent()) {
            return fallback;
        }
        AttributeInstance inst = e.getAttributes() == null ? null : e.getAttribute(attr.get());
        return inst != null ? inst.getValue() : fallback;
    }
}
