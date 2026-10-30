package com.yayuy.overgrownvegetation.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

/**
 * {@link Entity} の永続データ({@code persistentData}、NeoForge が足した private フィールド)を
 * <b>作らずに</b>覗くための口(2026-09-24)。
 *
 * <p>{@code getPersistentData()} は無ければ空の入れ物を作り、それが以後ずっと保存される。
 * イベントの印({@link com.yayuy.overgrownvegetation.raid.EventMobs})を探すときは印の無い個体
 * (汚染シダなど、永続の個体はたくさん居る)にも当たるので、そちらには何も残さないようにこれで見る。
 *
 * <p>{@code persistentData} は {@code Entity} 自身に宣言されているフィールド(親クラスのものではない)。
 */
@Mixin(Entity.class)
public interface EntityPersistentDataAccessor {

    /** 永続データ。まだ一度も作られていなければ null。 */
    @Accessor("persistentData")
    @Nullable
    CompoundTag overgrown_vegetation$getPersistentDataOrNull();
}
