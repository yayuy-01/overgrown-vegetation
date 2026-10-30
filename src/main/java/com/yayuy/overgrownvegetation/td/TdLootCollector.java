package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import java.util.Iterator;

/**
 * 侵攻中に倒れた攻め手のドロップをターゲットブロックの収納({@link TargetStorage})へ入れる(2026-09-25、ユーザー指定
 * 「ターゲットブロックにラージチェスト 4 つ分のインベントリを用意し、TD 中は mob からドロップしたアイテムをそこに自動的に入れる。
 * インベントリが埋まった際は mob からそのままドロップするように」)。
 *
 * <ul>
 *     <li>対象: 今の侵攻の印({@link EventMobs.Kind#TD})を持つ個体(群れの一員・護衛・群れから外れた一員)。倒したのが誰でも
 *     (プレイヤー・砲台・落下)。印の無い Mob(プレイヤーの動物・普段の敵)は今までどおり。</li>
 *     <li>入りきらなかった分だけ、その場に落ちる(山の一部だけ入ったときは残りの数で落ちる)。経験値の玉は今までどおり。</li>
 *     <li>ターゲットのチャンクが読み込まれていない・ターゲットが無いときはその場に落ちる。</li>
 * </ul>
 * ほかの MOD・この MOD のドロップの手直し(器官・胞子の感染で落とさない など)が済んだあとに見るよう、優先度は低め。
 * 取り消されたドロップ({@code LivingDropsEvent} の取り消し)は来ない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdLootCollector {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        if (event.getDrops().isEmpty() || !(event.getEntity() instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        String eventId = TdEvents.activeEventId(level);
        BlockPos target = TdEvents.activeTarget(level);
        if (eventId == null || target == null || !EventMobs.has(mob, EventMobs.Kind.TD, eventId)) {
            return;
        }
        TargetBlockEntity entity = TdTargets.get(level, target);
        if (entity == null || entity.isRemoved()) {
            return;
        }
        collect(entity.storage(), event.getDrops().iterator());
    }

    /** 入れられるだけ入れる。全部入った山はドロップの一覧から外し、一部だけなら残りの数にする。 */
    static void collect(TargetStorage storage, Iterator<ItemEntity> drops) {
        while (drops.hasNext()) {
            ItemEntity drop = drops.next();
            ItemStack rest = storage.insert(drop.getItem(), 0);
            if (rest.isEmpty()) {
                drops.remove();
            } else if (rest.getCount() != drop.getItem().getCount()) {
                drop.setItem(rest);
            }
        }
    }

    private TdLootCollector() {
    }
}
