package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.Collection;
import java.util.Iterator;

/**
 * アルボラが<b>自分で狩った獲物の落し物をその場で抱える</b>(ユーザー指定 2026-09-20:
 * 「1 体狩ったら必ず貯蔵胞へ行き、ドロップアイテムを中に入れる」)。
 *
 * <p>落ちたアイテムを見つけてから拾いに行く形だと、群れの動きや別の狩りに割り込まれて
 * 取りこぼす。ここで<b>殺した瞬間に手へ渡してしまえば</b>、あとは
 * {@link com.yayuy.overgrownvegetation.entity.ArboraHaulGoal} が運びきるまで抱えたままになる。
 *
 * <p>抱えられるのは 1 スタックだけ。入り切らなかったぶんは今までどおりその場に落ちるので、
 * 次の運搬で拾いに戻る。近くに巣が無いアルボラは何も抱えない(運び先が無いため)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestHauling {

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getSource().getEntity() instanceof ArboraEntity arbora)) {
            return;
        }
        if (!arbora.carriedLoot().isEmpty() || !(arbora.level() instanceof ServerLevel level)) {
            return;
        }
        NestTable t = DesignData.nest();
        NestData.Nest nest = NestData.get(level).nearest(arbora.blockPosition());
        if (nest == null || nest.route().isEmpty()) {
            return;                                    // 運び先が無い。ふつうに落とす
        }
        if (nest.origin().distSqr(arbora.blockPosition()) > (double) t.haulNestRadius() * t.haulNestRadius()) {
            return;
        }
        Collection<ItemEntity> drops = event.getDrops();
        Iterator<ItemEntity> it = drops.iterator();
        while (it.hasNext()) {
            ItemEntity entity = it.next();
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            arbora.setCarriedLoot(stack.copy());
            it.remove();
            return;                                    // 抱えられるのは 1 スタックだけ
        }
    }

    private NestHauling() {
    }
}
