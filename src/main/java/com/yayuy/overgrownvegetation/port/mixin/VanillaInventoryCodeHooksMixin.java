package com.yayuy.overgrownvegetation.port.mixin;

import com.yayuy.overgrownvegetation.port.capabilities.Capabilities;
import com.yayuy.overgrownvegetation.port.capabilities.OvCaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.VanillaInventoryCodeHooks;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 【1.20.1 だけの物・互換の層】ホッパーなど(Forge の {@link VanillaInventoryCodeHooks})が物の口を探すとき、
 * ブロックエンティティの Capability で見つからなければ、互換の層の表({@link OvCaps})も見る(2026-11-23 統合 I)。
 *
 * <p>NeoForge 1.21 の {@code RegisterCapabilitiesEvent#registerBlock} は、ブロックエンティティの無いブロック(3 ブロックの換気機の上 2 つの部位など)にも
 * 物の口を付けられる。Forge 1.20.1 の Capability はブロックエンティティにしか付かないので、互換の層は表に持つだけで、バニラのホッパーには
 * 見えなかった(本流 8035f27b の「上の部位に当てたホッパーから予備を入れる」が 1.20.1 で効かなかった)。Forge の名前の物なので {@code remap = false}。
 */
@Mixin(value = VanillaInventoryCodeHooks.class, remap = false)
public abstract class VanillaInventoryCodeHooksMixin {

    @Inject(method = "getItemHandler(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/core/Direction;)Ljava/util/Optional;",
            at = @At("RETURN"), cancellable = true)
    private static void overgrown_vegetation$portBlockCapability(Level level, double x, double y, double z, Direction side,
                                                                 CallbackInfoReturnable<Optional<Pair<IItemHandler, Object>>> cir) {
        if (cir.getReturnValue().isPresent()) {
            return;
        }
        BlockPos pos = new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState state = level.getBlockState(pos);
        BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        IItemHandler handler = OvCaps.getCapability(level, Capabilities.ItemHandler.BLOCK, pos, state, be, side);
        if (handler != null) {
            cir.setReturnValue(Optional.of(ImmutablePair.of(handler, be != null ? be : state)));
        }
    }
}
