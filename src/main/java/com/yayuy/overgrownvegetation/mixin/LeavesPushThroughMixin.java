package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock;
import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 荒い個体のファルキムネスは葉を押し分けて通る(11-08 夜、森の中を動く型 ② の段 3。決定「荒い個体は下草や葉を押し分けて近道する。
 * ブロックは壊さない」)。その個体の当たりの判定のときだけ、葉(バニラの葉のタグ・この MOD の葉)の当たりを空にする。
 *
 * <p>エンティティを持つ判定({@link EntityCollisionContext})だけを見るので、ほかの判定(光・描画・湧き・キャッシュした形)は変わらない。
 * 最初に判定の種類と個体を見てすぐ返すので、ふだんの重さはほぼ変わらない。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class LeavesPushThroughMixin {

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$pushThroughLeaves(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (context instanceof EntityCollisionContext ecc && ecc.getEntity() instanceof FalcimnesEntity f && f.pushesThroughLeaves()) {
            BlockState state = (BlockState) (Object) this;
            // 足場の葉(ブロックが丸ごと足より下)は押し分けない。押し分けると葉の上に立った荒い個体が葉を抜けて落ちた(11-08 夜、本物の木生シダの森で 10 ブロック)
            if (pos.getY() + 1.0D <= f.getY() + 1.0E-3D) {
                return;
            }
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof OvergrownLeavesBlock) {
                cir.setReturnValue(Shapes.empty());
            }
        }
    }
}
