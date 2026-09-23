package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import javax.annotation.Nullable;

/**
 * 「寄生植物装備」({@link PlantArmorItem})の<b>設定表と判定の入口</b>。他の仕組み(勢力判定・胞子感染・胞子濃度・
 * 茂み・容器・果実)はここの静的メソッドだけを呼ぶ。毎 tick の処理は {@link PlantArmorEvents}。
 *
 * <h2>仕様(ユーザー指定 2026-09-18)</h2>
 * <ul>
 *   <li>ハイヴノードの儀式で受け取った瞬間から着けっぱなし(自分では外せない = 束縛の呪い)。</li>
 *   <li>着ている間、プレイヤーは<b>グループ 6 の過繫茂 Mob として扱われる</b>({@code MobGroup.of(Entity)}):
 *       グループ1〜10 から狙われず、スポロフィルのバフを受け、胞子感染にかからない。代わりにゴーレムや
 *       中立 Mob からも狙われる({@link PlantArmorEvents} の注入)。装備自体は胞子汚染しない。</li>
 *   <li>茂みで減速しない。攻撃した Mob に胞子感染 1。Mob を倒すと食料 +4。周囲の胞子濃度 +15%(3 段階目は +30%)。</li>
 *   <li>食料ゲージを常に消費し、代わりに耐久値が勝手に回復する。過繫茂バイオームでは食料の減りが遅く
 *       (2 段階目以降は減らない)、耐久値の回復に食料を使わない。</li>
 *   <li>時間経過で 3 段階に変化する({@link Stage})。段階は装備アイテムの部品、経過時間はプレイヤーの
 *       {@link ModAttachments#PLANT_ARMOR_TICKS}(着けている間だけ進む)。</li>
 * </ul>
 * 各段階の数値は {@link Stage} に、時間・確率は config({@code plantArmor*})にある。
 */
public final class PlantArmor {

    /** 段階ごとの数値。 */
    public enum Stage {
        /** 蔦と胞子の塊が胸に付いた状態。 */
        ONE(1, 8.0, 3.0, 300, 0.5, 0.5, 1, 15.0),
        /** 蔦と苔が増え、巻きシダが生える。 */
        TWO(2, 14.0, 12.0, 450, 1.0, 0.0, 2, 15.0),
        /** 体と頭のほとんどが植物に覆われる。 */
        THREE(3, 20.0, 20.0, 600, 1.5, 0.0, 2, 30.0);

        public final int number;
        /** 最大体力(バニラの 20 との差を属性で足す)。 */
        public final double maxHealth;
        /** 防御力(属性 ARMOR)。 */
        public final double defense;
        /** 耐久値。 */
        public final int durability;
        /** 耐久値を 1 回復するのに使う食料の疲労値(バイオームの外)。4.0 で食料 1 目盛り。 */
        public final double repairExhaustion;
        /** 過繫茂バイオームでのバニラの食料消費の倍率(0 = 減らない)。 */
        public final double biomeHungerFactor;
        /** 未発達の茂み・垂れ草の実の倍率。 */
        public final int fruitMultiplier;
        /** 周囲の胞子濃度への加算 %。 */
        public final double concentrationBonus;

        Stage(int number, double maxHealth, double defense, int durability, double repairExhaustion,
                double biomeHungerFactor, int fruitMultiplier, double concentrationBonus) {
            this.number = number;
            this.maxHealth = maxHealth;
            this.defense = defense;
            this.durability = durability;
            this.repairExhaustion = repairExhaustion;
            this.biomeHungerFactor = biomeHungerFactor;
            this.fruitMultiplier = fruitMultiplier;
            this.concentrationBonus = concentrationBonus;
        }

        public static Stage byNumber(int n) {
            return n <= 1 ? ONE : n == 2 ? TWO : THREE;
        }
    }

    private PlantArmor() {
    }

    /** 着けている装備(胸)。着けていなければ空。 */
    public static ItemStack worn(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof PlantArmorItem ? chest : ItemStack.EMPTY;
    }

    /** 寄生植物装備を着けたプレイヤー(= 過繫茂側の生き物として扱う相手)か。 */
    public static boolean isHost(@Nullable Entity entity) {
        return entity instanceof Player player && !worn(player).isEmpty();
    }

    /** 着けている装備の段階番号(1〜3)。着けていなければ 0。 */
    public static int stageOf(@Nullable Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        ItemStack stack = worn(living);
        return stack.isEmpty() ? 0 : stageOf(stack).number;
    }

    /** アイテムの段階(部品 {@link ModDataComponents#PLANT_ARMOR_STAGE}、無ければ 1 段階目)。 */
    public static Stage stageOf(ItemStack stack) {
        return Stage.byNumber(stack.getOrDefault(ModDataComponents.PLANT_ARMOR_STAGE.get(), 1));
    }

    /** 着けた経過時間(tick)から段階を決める(config の {@code plantArmorStage2Minutes} / {@code plantArmorStage3Minutes})。 */
    public static Stage stageForTicks(int ticks) {
        var cfg = OvergrownVegetationConfig.COMMON;
        if (ticks >= cfg.plantArmorStage3Minutes.get() * 1200L) {
            return Stage.THREE;
        }
        if (ticks >= cfg.plantArmorStage2Minutes.get() * 1200L) {
            return Stage.TWO;
        }
        return Stage.ONE;
    }

    /** 未発達の茂み・垂れ草を右クリックしたときの実の数(2 段階目以降は倍)。 */
    public static int fruitCount(@Nullable Player player, int base) {
        if (player == null) {
            return base;
        }
        ItemStack stack = worn(player);
        return stack.isEmpty() ? base : base * stageOf(stack).fruitMultiplier;
    }

    /** 蔦に覆われた過繫茂の容器・扉を必ず開けられるか(3 段階目)。 */
    public static boolean canForceOpen(@Nullable Player player) {
        return stageOf(player) >= 3;
    }

    /** チャンク (cx, cz) の胞子濃度に足す %(近く = 隣接チャンク以内に居るホストの中で最大)。 */
    public static double concentrationBonus(ServerLevel level, int cx, int cz) {
        double best = 0.0;
        for (ServerPlayer player : level.players()) {
            ItemStack stack = worn(player);
            if (stack.isEmpty() || player.isSpectator()) {
                continue;
            }
            if (Math.abs((player.getBlockX() >> 4) - cx) <= 1 && Math.abs((player.getBlockZ() >> 4) - cz) <= 1) {
                best = Math.max(best, stageOf(stack).concentrationBonus);
            }
        }
        return best;
    }

    /** 光合成中(3 段階目・日なた)のプレイヤーの周りで汚染シダが湧く確率の倍率。 */
    public static double fernSpawnMultiplier(ServerPlayer player) {
        if (player.getData(ModAttachments.PLANT_ARMOR_STATE).photosynthesis) {
            return OvergrownVegetationConfig.COMMON.plantArmorPhotosynthesisFernMultiplier.get();
        }
        return 1.0;
    }

    /**
     * 儀式の報酬: 胸の装備を寄生植物装備に置き換える(元の胸装備は足元に落とす)。束縛の呪いを付け、
     * 経過時間を 0 から数え直す。
     */
    public static void grant(ServerPlayer player) {
        ItemStack previous = player.getItemBySlot(EquipmentSlot.CHEST);
        if (previous.getItem() instanceof PlantArmorItem) {
            return;
        }
        if (!previous.isEmpty()) {
            player.drop(previous.copy(), false);
        }
        ItemStack stack = create(player.serverLevel());
        player.setItemSlot(EquipmentSlot.CHEST, stack);
        player.setData(ModAttachments.PLANT_ARMOR_TICKS, 0);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.PLAYERS, 1.5F, 0.5F);
        player.displayClientMessage(Component.translatable("message.overgrown_vegetation.plant_armor.granted"), false);
        OvergrownVegetation.LOGGER.info("PlantArmor: {} に寄生植物装備を装着", player.getName().getString());
    }

    /** 1 段階目の装備を作る(束縛の呪い付き、呪いの表示は隠す)。 */
    public static ItemStack create(ServerLevel level) {
        ItemStack stack = new ItemStack(ModItems.PLANT_ARMOR.get());
        stack.set(ModDataComponents.PLANT_ARMOR_STAGE.get(), 1);
        Holder<Enchantment> binding = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.BINDING_CURSE);
        stack.enchant(binding, 1);
        ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) {
            stack.set(DataComponents.ENCHANTMENTS, enchantments.withTooltip(false));
        }
        return stack;
    }
}
