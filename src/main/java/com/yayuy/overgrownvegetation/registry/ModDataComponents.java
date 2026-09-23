package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODが ItemStack に載せるデータコンポーネント。
 */
public final class ModDataComponents {

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, OvergrownVegetation.MODID);

    /**
     * 「胞子汚染」— そのアイテムがどれだけ胞子に侵されているか(0〜100 の %)。
     * 胞子濃度の高い場所に持ち込んでいると少しずつ上がり、性能を下げる
     * ({@link com.yayuy.overgrownvegetation.spore.GearContamination})。
     * 除染機({@link com.yayuy.overgrownvegetation.block.DecontaminatorBlock})で下げられる。
     *
     * <p>クライアントにも同期する(ツールチップ表示と、属性修飾をクライアント側でも同じ値にするため)。
     * 0% のときはコンポーネント自体を載せない(スタック可能なアイテムを無駄に分けないため)。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> SPORE_CONTAMINATION =
            COMPONENTS.registerComponentType("spore_contamination", builder -> builder
                    .persistent(Codec.FLOAT)
                    .networkSynchronized(ByteBufCodecs.FLOAT));

    /**
     * 「樹液結晶処理済み」— 鍛冶台で樹液結晶を打ち込んだ装備に載る目印。
     * 載っている間、{@link ModDataComponents#SPORE_CONTAMINATION 胞子汚染}の進みが
     * {@code effects.json} の {@code gearContamination.sapCrystalRateFactor}(既定 0.1 = 10 分の 1)倍になる
     * ({@link com.yayuy.overgrownvegetation.spore.GearContamination#rateFactor})。
     *
     * <p>ツールチップに出すのでクライアントにも同期する。処理していないアイテムには載せない
     * (スタック可能なアイテムを無駄に分けないため、false を明示的に載せることはしない)。
     */
    /** 「寄生植物装備」の段階(1〜3)。時間経過で上がる({@link com.yayuy.overgrownvegetation.item.PlantArmor})。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PLANT_ARMOR_STAGE =
            COMPONENTS.registerComponentType("plant_armor_stage", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> SAP_CRYSTAL_TREATED =
            COMPONENTS.registerComponentType("sap_crystal_treated", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /**
     * 「蔓」アイテムが覚えている<b>始点</b>のブロック座標(次元込み)。
     * 1 回目の右クリックで載り、2 回目の右クリック(=終点の指定)で蔦を張ったら外す
     * ({@link com.yayuy.overgrownvegetation.item.LianaItem})。
     *
     * <p>次元も一緒に持つのは、始点を置いたままネザーなどへ移動したときに
     * 座標だけが残って的外れな場所へ蔦が張られるのを防ぐため(違う次元で置き直したら
     * 始点をそこへ引き直す)。
     *
     * <p>クライアントにも同期する——「いま始点を持っているか」で右クリックの意味が変わるので、
     * ツールチップに出せるようにしておく。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> LIANA_ANCHOR =
            COMPONENTS.registerComponentType("liana_anchor", builder -> builder
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
