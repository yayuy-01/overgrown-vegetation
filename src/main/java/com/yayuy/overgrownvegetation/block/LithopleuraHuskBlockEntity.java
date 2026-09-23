package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 抜け殻ブロックの中身。<b>状態は何も持たない</b> ——
 * GeckoLib でカスタムモデルを描くには {@code GeoBlockEntity} + {@code GeoBlockRenderer} の組が
 * 必要なので、そのためだけに存在する({@link CorruptedCorpseBlockEntity} と同じ理由)。
 *
 * <p>動かない静的モデルなので、アニメーションのコントローラも登録しない。
 */
public class LithopleuraHuskBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public LithopleuraHuskBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LITHOPLEURA_HUSK.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 動かない
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.geoCache;
    }
}
