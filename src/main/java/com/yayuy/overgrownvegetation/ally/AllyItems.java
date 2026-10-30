package com.yayuy.overgrownvegetation.ally;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModCreativeTabs;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 同盟軍の持ち物(2026-10-01)。共有の {@link ModItems} を太らせないよう、DeferredRegister だけ借りてここにまとめる
 * ({@link #init()} を {@code ModItems.register} より前に呼ぶ —— {@code base.BaseBlocks#init} と同じ流儀)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AllyItems {

    /** HK416(仮。見た目だけ)。 */
    public static final DeferredItem<Item> HK416 = ModItems.ITEMS.registerItem("hk416", Hk416Item::new,
            new Item.Properties().stacksTo(1));
    /**
     * 補修材(順 2 の着手順 4 = 整備、2026-10-04)。基地の倉庫に貯蓄で買い足され、工兵が壊れて無くなったブロックを 1 マス 1 個で埋める
     * (ユーザー指定 10-02「荷捌き場の倉庫に…補修材…が補充され、工兵はそれを使う」)。
     * 仮のレシピ {@code recipe/repair_material.json}(鉄筋コンクリート 2 + 鉄インゴット 1 → 4、2026-10-04 Claude 仮決め)。倉庫から拾うこともできる。
     */
    public static final DeferredItem<Item> REPAIR_MATERIAL = ModItems.ITEMS.registerItem("repair_material", Item::new,
            new Item.Properties());
    /**
     * 軍票(順 2 の着手順 5 = 信頼・寄付・取引、2026-10-07)。同盟軍の基地との売り買いに使う通貨。素材を売ると・寄付のお礼にもらえ、
     * 管理端末の取引で品を買う(ユーザー指定 10-07「取引は新しい通貨『軍票』」)。作り方は無く、寄付・売却では受け取らない。仮の絵は {@code tools/trade-icons}。
     */
    public static final DeferredItem<Item> MILITARY_SCRIP = ModItems.ITEMS.registerItem("military_scrip", Item::new,
            new Item.Properties());

    public static void init() {
        AllyEntities.init(); // 兵士の種類とスポーンエッグも、登録より前に
    }

    @SubscribeEvent
    public static void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.HUMANITY.getKey()) {
            event.accept(HK416.get());
            event.accept(REPAIR_MATERIAL.get());
            event.accept(MILITARY_SCRIP.get());
        }
    }

    private AllyItems() {
    }
}
