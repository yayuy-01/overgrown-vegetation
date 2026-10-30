package com.yayuy.overgrownvegetation.mixin;

import com.mojang.datafixers.util.Pair;
import com.yayuy.overgrownvegetation.base.gen.AlliedBaseStructure;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 実験的要素がオフのワールドで同盟軍の基地を探す({@code /locate structure overgrown_vegetation:allied_base} など)と、すぐ「見つからない」を返す
 * (2026-10-09)。
 *
 * <p>オフでは基地が生まれないので、バニラの探し方は半径 100 の輪を最後まで回る。基地の組は間隔 18 チャンクなので約 4 万の候補を順に調べ、
 * <b>サーバーが約 108 秒止まった</b>(Claude のテストクライアントで測った。オンなら近くで見つかって約 5 秒)。探すのが基地だけのときに頭で打ち切る。
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateExperimentalMixin {

    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void overgrown_vegetation$skipAlliedBasesWhenOff(ServerLevel level, HolderSet<Structure> structures, BlockPos pos,
                                                             int searchRadius, boolean skipKnownStructures,
                                                             CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (ExperimentalFeatures.worldgenEnabled() || structures.size() == 0) {
            return;
        }
        for (Holder<Structure> holder : structures) {
            if (!(holder.value() instanceof AlliedBaseStructure)) {
                return; // ほかの構造物も一緒に探すならバニラに任せる
            }
        }
        cir.setReturnValue(null);
    }
}
