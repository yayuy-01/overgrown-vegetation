package com.yayuy.overgrownvegetation.survival.decon;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirtSpec;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.registry.ModDecon;
import com.yayuy.overgrownvegetation.survival.room.RoomAir;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.WeakHashMap;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * <b>装備の汚れ</b>(2026-11-14 サバイバルの背骨 段 3b、決定 32・33)。値は {@code spore_field.json} の {@code dirt}({@link DirtSpec})。
 *
 * <ul>
 *     <li><b>たまる</b>: 濃度 {@code minPercent} 以上の所にいると、着ている 4 か所と両手の耐久のある物(道具・武器・盾)に、濃さ × 時間で汚れが付く。
 *     物への書き込みは刻み({@code commitStepArmor}・{@code commitStepHand})ごと —— 手の物は書き換えると掘っている途中が切れるので粗く。</li>
 *     <li><b>吸う</b>: 着ている物の段ごとに、きれいな空気の中でも暴露量がたまる({@link #seepPerSecond}。暴露量の「部屋のゆっくりたまる道」に足す。
 *     マスクが効いていればたまらない)。</li>
 *     <li><b>性能</b>: 防具の守り・強靭さと、利き手の道具の掘る速さが段ごとに落ちる。防具はプレイヤーの属性の修飾にまとめて掛ける
 *     (物の修飾にすると説明文に数字が出るため。決定 33)。</li>
 *     <li><b>部屋</b>: 汚れた物と覆われた○○を持って閉じた部屋にいると、部屋が汚れる(段 3c、{@link RoomAir#contaminateFrom})。</li>
 *     <li><b>見た目</b>: クライアントの {@code client/decon/}(アイコンと体に着た姿。数字は出さない)。</li>
 * </ul>
 * 創造・観戦のプレイヤーは汚れない(手に持った物も)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class GearDirt {

    private GearDirt() {
    }

    /** 汚れの付く場所(着ている 4 か所 + 両手)。 */
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    public static final ResourceLocation ARMOR_ID = OvergrownVegetation.id("gear_dirt_armor");
    public static final ResourceLocation TOUGHNESS_ID = OvergrownVegetation.id("gear_dirt_toughness");

    /** 刻みに満たない汚れの端数(場所ごと。保存しない)。 */
    private static final Map<Player, double[]> CARRY = new WeakHashMap<>();

    public static DirtSpec spec() {
        return DesignData.sporeField().dirt();
    }

    /** 汚れの量(0〜full)。 */
    public static int dirt(ItemStack stack) {
        return stack.isEmpty() ? 0 : OvItemData.getOrDefault(stack, ModDecon.GEAR_DIRT.get(), 0);
    }

    /** 汚れの見た目の段(0 = きれい、1〜3)。 */
    public static int stage(ItemStack stack) {
        return spec().stageOf(dirt(stack));
    }

    public static void setDirt(ItemStack stack, int value) {
        int v = Math.max(0, Math.min(spec().full(), value));
        if (v <= 0) OvItemData.remove(stack, ModDecon.GEAR_DIRT.get());
        else OvItemData.set(stack, ModDecon.GEAR_DIRT.get(), v);
    }

    /** 汚れを {@code amount} 落とす。 */
    public static void wash(ItemStack stack, int amount) {
        if (amount <= 0 || dirt(stack) <= 0) return;
        setDirt(stack, dirt(stack) - amount);
    }

    /** 手洗い 1 回で落とす汚れ。 */
    public static int handStep() {
        return spec().handWashAmount();
    }

    /** 洗い場の枠で 1 回に落とす汚れ。 */
    public static int machineStep() {
        return spec().machineWashAmount();
    }

    /** 汚れが付く物か(1 つずつしか重ならず、防具か耐久のある物)。 */
    public static boolean canGetDirty(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() == 1
                && (stack.getItem() instanceof ArmorItem || stack.isDamageableItem());
    }

    static boolean isHand(EquipmentSlot slot) {
        return slot.getType() == EquipmentSlot.Type.HAND;
    }

    /**
     * 判定 1 回ぶん({@code interval} tick。{@code SporeExposureTracker} のプレイヤーの判定から呼ぶ。GameTest からも)。
     * {@code percent} = その地点の濃度(%)。汚れをため、性能の修飾を掛け直し、部屋を汚す。
     */
    public static void step(ServerLevel level, Player player, int interval, double percent) {
        if (player.isSpectator() || player.isCreative()) {
            applyModifiers(player);
            return;
        }
        DirtSpec s = spec();
        double seconds = interval / 20.0D;
        if (percent >= s.minPercent() && s.perSecondAt100() > 0.0D) {
            accumulate(player, percent / 100.0D * s.perSecondAt100() * seconds);
        }
        applyModifiers(player);
        double points = carriedPoints(player);
        if (points > 0.0D) {
            RoomAir.contaminateFrom(level, player, points, seconds);
        }
    }

    /** 汚れを {@code amount} ずつ、汚れの付く場所の物に足す(刻みで書き込む)。 */
    static void accumulate(Player player, double amount) {
        DirtSpec s = spec();
        double[] carry = CARRY.computeIfAbsent(player, p -> new double[SLOTS.length]);
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack st = player.getItemBySlot(SLOTS[i]);
            if (!canGetDirty(st) || dirt(st) >= s.full()) {
                carry[i] = 0.0D;
                continue;
            }
            carry[i] += amount;
            int step = isHand(SLOTS[i]) ? s.commitStepHand() : s.commitStepArmor();
            if (carry[i] >= step) {
                int whole = (int) Math.floor(carry[i]);
                carry[i] -= whole;
                setDirt(st, dirt(st) + whole);
            }
        }
    }

    /**
     * 着ている汚れた物から吸う暴露量(%秒 / 秒)= 着ている 4 か所の段ごとの {@code seepPerStage} の和。創造・観戦は 0。
     * マスクが効いていればたまらないのは、たまる側({@code SporeExposureTracker#accumulate} の倍率)で決まる。
     */
    public static double seepPerSecond(Player player) {
        if (player.isSpectator() || player.isCreative()) return 0.0D;
        double[] seep = spec().seepPerStage();
        double sum = 0.0D;
        for (int i = 0; i < 4; i++) {
            int st = stage(player.getItemBySlot(SLOTS[i]));
            if (st > 0) sum += seep[st - 1];
        }
        return sum;
    }

    /**
     * 持っている汚れ(部屋を汚す点。段 3c): 持ち物・着ている物・手の汚れた物は段の数(1〜3)、覆われた○○(変換表にある物)は 1 枠 1 点。
     */
    public static double carriedPoints(Player player) {
        double pts = 0.0D;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.isEmpty()) continue;
            int stg = stage(st);
            if (stg > 0) pts += stg;
            else if (Decon.recipe(player.level(), st).isPresent()) pts += 1.0D;
        }
        return pts;
    }

    // ---- 性能 ----

    /** 防具の守り・強靭さの修飾を掛け直す(値が変わったときだけ)。 */
    public static void applyModifiers(Player player) {
        double[] loss = spec().armorLossPerStage();
        double armor = 0.0D, tough = 0.0D;
        boolean exempt = player.isSpectator() || player.isCreative();
        if (!exempt) {
            for (int i = 0; i < 4; i++) {
                EquipmentSlot slot = SLOTS[i];
                ItemStack st = player.getItemBySlot(slot);
                int stg = stage(st);
                if (stg == 0) continue;
                double l = loss[stg - 1];
                armor += l * itemValue(st, slot, Attributes.ARMOR);
                tough += l * itemValue(st, slot, Attributes.ARMOR_TOUGHNESS);
            }
        }
        setModifier(player, Attributes.ARMOR, ARMOR_ID, -armor);
        setModifier(player, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID, -tough);
    }

    /** その物がその場所で足す属性の値(足し算の修飾の和)。 */
    static double itemValue(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attr) {
        double[] sum = {0.0D};
        stack.forEachModifier(slot, (a, m) -> {
            if (a.equals(attr) && m.operation() == AttributeModifier.Operation.ADD_VALUE) sum[0] += m.amount();
        });
        return sum[0];
    }

    private static void setModifier(Player player, Holder<Attribute> attr, ResourceLocation id, double amount) {
        AttributeInstance inst = player.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier old = inst.getModifier(id);
        if (Math.abs(amount) < 1.0E-6D) {
            if (old != null) inst.removeModifier(id);
            return;
        }
        if (old != null && Math.abs(old.amount() - amount) < 1.0E-6D) return;
        if (old != null) inst.removeModifier(id);
        inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    /** 利き手の道具の掘る速さの倍率(1 = そのまま)。 */
    public static double breakSpeedFactor(Player player) {
        if (player.isCreative() || player.isSpectator()) return 1.0D;
        int stg = stage(player.getMainHandItem());
        return stg == 0 ? 1.0D : Math.max(0.0D, 1.0D - spec().breakSpeedLossPerStage()[stg - 1]);
    }

    private static final net.minecraft.util.RandomSource WEAR_RANDOM = net.minecraft.util.RandomSource.create();

    /**
     * 汚れた物の耐久の減り(2026-11-14 決定 36。{@code mixin/ItemStackDirtWearMixin} から)。減らす量 {@code amount} に段ごとの
     * {@code wearExtraPerStage} を掛けた分を足す(整数部は必ず、端数は確率)。汚れていない物・減らさない呼び出しはそのまま。
     */
    public static int wearAmount(ItemStack stack, int amount) {
        if (amount <= 0) return amount;
        int stg = stage(stack);
        if (stg == 0) return amount;
        double extra = amount * spec().wearExtraPerStage()[stg - 1];
        int whole = (int) Math.floor(extra);
        return amount + whole + (WEAR_RANDOM.nextDouble() < extra - whole ? 1 : 0);
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        double f = breakSpeedFactor(event.getEntity());
        if (f < 1.0D) event.setNewSpeed((float) (event.getNewSpeed() * f));
    }

    /** 防護マスクか(フィルターを持つ。洗うとフィルターも洗われる)。 */
    static boolean isMask(ItemStack stack) {
        return com.yayuy.overgrownvegetation.gear.FilterMask.isMask(stack); // 段の頭も(2026-11-17)
    }
}
