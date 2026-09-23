package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 巣の中で死んだプレイヤーの持ち物を、貯蔵室の「アルボラの貯蔵胞」へ<b>移す</b>(2026-09-20)。
 *
 * <h2>決まり(ユーザー指定 2026-09-19)</h2>
 * <ul>
 *     <li>移すのは<b>巣の中で死んだときだけ</b>。外で死んだら普通に落ちる。</li>
 *     <li><b>移動</b>であって複製ではない —— 落ちるはずだった分がそのまま貯蔵胞に入る。</li>
 *     <li>貯蔵胞が満杯 / 1 つも無い巣なら、入り切らなかったぶんはその場に落ちる。</li>
 * </ul>
 *
 * <p>「巣の中」の判定は、いちばん近い巣の入口から {@link #NEST_RADIUS} 以内で、かつ
 * その巣の入口より下にいること。巣は掘り方によって広がるので、正確な形ではなく大きめの球で見る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestDeaths {

    /** 「巣の中」とみなす入口からの距離。掘った巣の広がり(既定で半径 60 ほど)より少し広め。 */
    private static final double NEST_RADIUS = 96.0D;
    /** 貯蔵胞を探す半径。 */
    private static final double POD_SEARCH = 128.0D;

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        NestData.Nest nest = insideNest(level, player.blockPosition());
        if (nest == null) {
            return;
        }
        List<BlockPos> pods = NestPods.find(level, nest);
        if (pods.isEmpty()) {
            return;
        }
        Collection<ItemEntity> drops = event.getDrops();
        List<ItemEntity> left = new ArrayList<>();
        int moved = 0;
        for (ItemEntity entity : drops) {
            ItemStack rest = NestPods.insert(level, pods, entity.getItem());
            if (rest.isEmpty()) {
                moved++;
                continue;
            }
            entity.setItem(rest);
            left.add(entity);
        }
        if (moved == 0) {
            return;
        }
        drops.clear();
        drops.addAll(left);
        player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.nest.items_taken", moved));
    }

    /** プレイヤーの居場所を含む巣。無ければ null。 */
    private static NestData.Nest insideNest(ServerLevel level, BlockPos pos) {
        NestData.Nest nest = NestData.get(level).nearest(pos);
        if (nest == null) {
            return null;
        }
        if (pos.getY() > nest.origin().getY()) {
            return null;                               // 地表より上は「巣の中」ではない
        }
        double dx = pos.getX() - nest.origin().getX();
        double dz = pos.getZ() - nest.origin().getZ();
        return dx * dx + dz * dz <= NEST_RADIUS * NEST_RADIUS ? nest : null;
    }

    private NestDeaths() {
    }
}
