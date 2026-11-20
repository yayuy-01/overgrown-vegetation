package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.corruption.cliff.RootPath;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 植物側の Mob の地上の経路に、絡む根の縄を上る節を足す(2026-11-20 崖の汚染。{@link RootPath})。
 * 植物側でない Mob の経路は変えない。protected の {@code mob}・{@code currentContext}・{@code getNode} を使うため、親の NodeEvaluator を継いだ形にする。
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorRootMixin extends NodeEvaluator implements RootPath.NodeSource {

    @Override
    public Node overgrown$node(int x, int y, int z) {
        return this.getNode(x, y, z);
    }

    /** 探索 1 回(prepare から done まで)は同じ Mob なので、植物側かを覚えておく。 */
    @Unique
    private Mob overgrown$checkedMob;
    @Unique
    private boolean overgrown$plantSide;

    @org.spongepowered.asm.mixin.Shadow
    protected abstract Node getStartNode(net.minecraft.core.BlockPos pos);

    /**
     * 縄に取り付いている植物側の Mob は、経路を今いる所(宙)から引く。バニラは宙にいると真下の地面から引くので、
     * 登っている途中で経路を引き直すと縄の下へ戻ってしまう。
     */
    @Inject(method = "getStart", at = @At("HEAD"), cancellable = true)
    private void overgrown$startOnRope(CallbackInfoReturnable<Node> cir) {
        Mob m = this.mob;
        if (m == null || m.onGround() || !PlantSideRules.isPlantSide(m)) return;
        if (!com.yayuy.overgrownvegetation.corruption.cliff.RootClimb.touching(m)
                && !com.yayuy.overgrownvegetation.corruption.cliff.RootClimbAssist.onRopePath(m)) return;
        cir.setReturnValue(this.getStartNode(m.blockPosition()));
    }

    @Inject(method = "getNeighbors", at = @At("RETURN"), cancellable = true)
    private void overgrown$addRootClimbNodes(Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir) {
        Mob m = this.mob;
        if (m == null || this.currentContext == null) return;
        if (m != this.overgrown$checkedMob) {
            this.overgrown$checkedMob = m;
            this.overgrown$plantSide = PlantSideRules.isPlantSide(m);
        }
        if (!this.overgrown$plantSide) return;
        int count = cir.getReturnValueI();
        int added = RootPath.addNeighbors(this.currentContext, node, outputArray, count, this.entityHeight, this);
        if (added > 0) cir.setReturnValue(count + added);
    }
}
