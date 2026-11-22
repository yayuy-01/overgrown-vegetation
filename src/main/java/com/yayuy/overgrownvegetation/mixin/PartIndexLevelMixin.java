package com.yayuy.overgrownvegetation.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.yayuy.overgrownvegetation.perf.PartIndex;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.entity.PartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

/**
 * 部位の升目の索引({@link PartIndex}、2026-11-18)。NeoForge が {@code getEntities} の 2 つの最後に足した
 * 「世界じゅうの部位を 1 つずつ比べる輪」に渡す一覧を、探す箱の近くの部位だけにする。比べる中身はバニラの輪のまま。
 */
@Mixin(Level.class)
public abstract class PartIndexLevelMixin implements PartIndex.Holder {

    @Unique
    private final PartIndex overgrown_vegetation$partIndex = new PartIndex();

    @Override
    public PartIndex overgrown_vegetation$partIndex() {
        return this.overgrown_vegetation$partIndex;
    }

    @WrapOperation(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPartEntities()Ljava/util/Collection;"))
    private Collection<PartEntity<?>> overgrown_vegetation$nearPartsA(Level level, Operation<Collection<PartEntity<?>>> original,
                                                                      @Local(argsOnly = true) AABB box) {
        return this.overgrown_vegetation$partIndex.candidates(level, original.call(level), box);
    }

    @WrapOperation(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getPartEntities()Ljava/util/Collection;"))
    private Collection<PartEntity<?>> overgrown_vegetation$nearPartsB(Level level, Operation<Collection<PartEntity<?>>> original,
                                                                      @Local(argsOnly = true) AABB box) {
        return this.overgrown_vegetation$partIndex.candidates(level, original.call(level), box);
    }
}
