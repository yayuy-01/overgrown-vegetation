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
    /** 鳴子の紐の始点(2 回クリックの 1 回目。2026-10-15、{@code defense/ClapperRopeItem})。 */
    /** 地脈の羅針盤の針の先(2026-10-16、⑦)。サーバーが 2 秒ごとに書き、クライアントが針を回す。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> LEY_COMPASS_TARGET =
            COMPONENTS.registerComponentType("ley_compass_target", builder -> builder
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));
    /** 前の地脈の羅針盤の向き先(true = 汚染されていない土地)。2026-10-22 の地脈探しで使わなくなった。古いセーブの読み込みのため登録だけ残す。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LEY_COMPASS_CLEAN =
            COMPONENTS.registerComponentType("ley_compass_clean", builder -> builder
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    /** ⑩ 地脈渡りの石が帰る対の石(2026-10-16)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> WAYSTONE_HOME =
            COMPONENTS.registerComponentType("waystone_home", builder -> builder
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));

    /** ① 陽溜まりのランタンの燃料(tick)と、灯しているか(2026-10-16)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> LANTERN_FUEL =
            COMPONENTS.registerComponentType("lantern_fuel", builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    /**
     * 防護マスクのフィルターが吸い取った胞子の量(%秒、2026-11-04)。無い = 新品。spore_field.json の exposure.maskCapacity に届くと
     * 効かなくなる(外れない・壊れない)。耐久のバーとツールチップに出すのでクライアントにも同期する。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MASK_FILTER_USED =
            COMPONENTS.registerComponentType("mask_filter_used", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 電池を入れて持ち運ぶ機械の、入っている電池の残り(2026-10-22、地脈灯。無い = 電池が入っていない)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> BATTERY_CHARGE =
            COMPONENTS.registerComponentType("battery_charge", builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> LANTERN_LIT =
            COMPONENTS.registerComponentType("lantern_lit", builder -> builder
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> CLAPPER_ROPE_ANCHOR =
            COMPONENTS.registerComponentType("clapper_rope_anchor", builder -> builder
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> LIANA_ANCHOR =
            COMPONENTS.registerComponentType("liana_anchor", builder -> builder
                    .persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));

    /** パワードアーマーの Mk(1.0.0 は 1 だけ。載っていなければ 1)。名前の後ろに付く。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> POWERED_ARMOR_MK =
            COMPONENTS.registerComponentType("powered_armor_mk", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * 防具に付いている<b>装飾品</b>の id(順 1 の着手順 9。{@link com.yayuy.overgrownvegetation.item.ArmorAccessory})。
     * どの防具にも載る(寄生植物装備を除く)。付いていなければ載せない。説明・属性の計算に使うのでクライアントにも同期する。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ARMOR_ACCESSORY =
            COMPONENTS.registerComponentType("armor_accessory", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    /** パワードアーマーに付いている<b>拡張パーツ</b>の id(同上。パワードアーマーにだけ載る)。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> ARMOR_EXTENSION =
            COMPONENTS.registerComponentType("armor_extension", builder -> builder
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
