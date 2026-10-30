package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@link Mob} の {@code persistenceRequired}(private)を<b>下ろす</b>ための口(2026-09-24)。
 *
 * <p>バニラには {@code setPersistenceRequired()}(立てるだけ)しか無く、一度立てた永続は外せない。
 * イベント(レイド・攻城戦・小規模レイド)で湧いた個体は、
 * 終わったあとも永続のまま溜まり続けていたので、後始末で元に戻すのに使う
 * ({@link com.yayuy.overgrownvegetation.raid.EventMobs#restorePersistence})。
 *
 * <p>{@code persistenceRequired} は {@code Mob} 自身に宣言されているフィールド(親クラスのものではない)。
 *
 * <p>装備の落ちる確率({@code getEquipmentDropChance}、protected)も読む(2026-09-25)。拾った持ち物は確率が 1 を超える
 * (必ず落ちる)ので、「拾って永続になった Mob」と「ワールドの生成で置かれた永続の Mob」を見分けるのに使う
 * ({@link com.yayuy.overgrownvegetation.td.TdHostiles})。
 */
@Mixin(Mob.class)
public interface MobPersistenceAccessor {

    @Accessor("persistenceRequired")
    void overgrown_vegetation$setPersistenceRequired(boolean persistent);

    @Invoker("getEquipmentDropChance")
    float overgrown_vegetation$getEquipmentDropChance(EquipmentSlot slot);
}
