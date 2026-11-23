package com.yayuy.overgrownvegetation.survival;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.FatigueSpec;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.event.level.BlockEvent;
import com.yayuy.overgrownvegetation.port.event.tick.PlayerTickEvent;

import java.lang.reflect.Field;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;
import com.yayuy.overgrownvegetation.port.entity.OvModifiers;

/**
 * 疲労(2026-11-13 サバイバルの背骨 段 1。数値は {@code spore_field.json} の fatigue = {@link FatigueSpec})。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *     <li>たまる: 起きている時間 / 濃い胞子の中 / 走る・戦う・掘る / 感染の段階が高い。ふつうに過ごして寝なければ約 2 日で限界(100%)。</li>
 *     <li>効き目: 胞子に弱くなる(暴露量が抜けにくい {@link #exposureHalfLifeMultiplier}・感染が進みやすい {@link #infectionMultiplier})/
 *     動きが鈍る({@link #syncModifiers})。感染の加速は弱めにして上限を付ける。</li>
 *     <li>回復: 夜に寝る(夜を飛ばす)と全部戻る。昼でもベッドに横になれば、時間は飛ばさずに少しずつ戻る。死んで復活したら 0。</li>
 *     <li>ネザーなどベッドが置けない所では、この段では戻せない(野営の手段は段 4)。</li>
 * </ul>
 *
 * <h2>仕組み(仮決め)</h2>
 * <ul>
 *     <li>判定は暴露量と同じ間隔(プレイヤー 20 tick)で、{@code SporeExposureTracker#tickPlayer} から {@link #step} を呼ぶ。
 *     走った tick・殴った回数・壊した数は毎 tick・出来事ごとに数えておき、判定でまとめて足す。</li>
 *     <li>創造・観戦ではたまらない(値はそのまま)。濃い胞子の上乗せは防護マスクが効いていれば 0。</li>
 *     <li>ベッドに横になっている間は昼でも夜でも少しずつ戻る(夜に 1 人だけ寝て朝を待つマルチプレイでも効く)。
 *     夜を飛ばして全員が起こされたとき({@link PlayerWakeUpEvent} の「すぐ起きない・寝ている人の数を数え直さない」= {@code ServerLevel#wakeUpAllPlayers})に 0。</li>
 *     <li>昼の休み: バニラの「夜しか眠れない」({@code NOT_POSSIBLE_NOW})だけを外す。まわりに敵がいれば横になれない(夜と同じ判定)。
 *     朝になっても追い出さない。昼の間は寝ている人の {@code sleepCounter} を 99 未満に抑え、夜を飛ばす判定
 *     ({@code ServerLevel#tick} の「深く眠った人が足りる」)が立たないようにする = 時間は飛ばず、天気も戻らず、
 *     夜の飛ばしの追いつき {@code LedgerSpread#onSleepFinished} も起きない。雷雨の昼はバニラでも眠れる(昼ではない)ので、そのまま夜と同じ。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class FatigueTracker {

    public static final ResourceLocation MOVE_ID = OvergrownVegetation.id("fatigue.move");
    public static final ResourceLocation MINE_ID = OvergrownVegetation.id("fatigue.mine");
    public static final ResourceLocation ATTACK_SPEED_ID = OvergrownVegetation.id("fatigue.attack_speed");

    /** 昼の休みの間、寝ている人の sleepCounter をここで止める(100 で「深く眠った」)。 */
    private static final int DAY_REST_COUNTER_CAP = 98;
    private static Field sleepCounterField;
    private static boolean sleepCounterFieldFailed;

    private FatigueTracker() {
    }

    public static FatigueSpec spec() {
        return DesignData.sporeField().fatigue();
    }

    /** 疲労がこのワールドで働くか(設計の enabled と MOD の設定「疲労」(11-19)の両方)。 */
    public static boolean on() {
        return spec().enabled() && com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.onCurrent(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.FATIGUE);
    }

    /** 疲労(0〜100)。添付が無ければ 0(作らない)。 */
    public static double value(Player player) {
        return OvAttach.hasData(player, ModAttachments.FATIGUE) ? OvAttach.getData(player, ModAttachments.FATIGUE).value : 0.0D;
    }

    /** 確かめ用・コマンド用: 疲労を決めて効き目を張り直す。 */
    public static void set(Player player, double value) {
        Fatigue f = OvAttach.getData(player, ModAttachments.FATIGUE);
        f.value = clamp(value);
        f.clearPending();
        syncModifiers(player);
    }

    /**
     * 判定 1 回({@code interval} tick ぶん)。
     *
     * @param percent その場の胞子濃度(%)
     * @param stage   胞子感染の段階(0 = 未感染)
     * @param masked  防護マスクが効いているか(濃い胞子の上乗せが 0 になる)
     * @return 判定の後の疲労
     */
    public static double step(Player player, int interval, double percent, int stage, boolean masked) {
        FatigueSpec s = spec();
        if (!s.enabled() || !on()) {
            if (OvAttach.hasData(player, ModAttachments.FATIGUE) && value(player) != 0.0D) set(player, 0.0D);
            return 0.0D;
        }
        Fatigue f = OvAttach.getData(player, ModAttachments.FATIGUE);
        double seconds = interval / 20.0D;
        if (player.isSleeping()) {
            f.value = clamp(f.value - s.restPerSecond() * seconds);
        } else if (!player.isCreative() && !player.isSpectator()) {
            double sprintSeconds = Math.min(f.sprintTicks, interval) / 20.0D;
            double gain = s.awakePerSecond() * seconds
                    + s.sprintPerSecond() * sprintSeconds
                    + s.attackEach() * f.attacks
                    + s.breakEach() * f.breaks
                    + (masked ? 0.0D : s.densePerSecond(percent)) * seconds
                    + s.stagePerSecond() * Math.max(0, stage) * seconds;
            f.value = clamp(f.value + gain);
        }
        f.clearPending();
        syncModifiers(player);
        return f.value;
    }

    /** 夜を飛ばして起こされた: 全部戻る。 */
    public static void restoreFully(Player player) {
        if (OvAttach.hasData(player, ModAttachments.FATIGUE)) set(player, 0.0D);
    }

    /** 効き目の強さ(0〜1)。 */
    public static double effectStrength(Player player) {
        return spec().effectStrength(value(player));
    }

    /** 暴露量の半減期の倍率(1〜exposureHalfLifeMax)。プレイヤー以外は 1。 */
    public static double exposureHalfLifeMultiplier(LivingEntity entity) {
        if (!(entity instanceof Player player) || !on()) return 1.0D;
        return 1.0D + (spec().exposureHalfLifeMax() - 1.0D) * effectStrength(player);
    }

    /** 感染の進みの倍率(1〜infectionMultiplierMax。上限はユーザー指定「上限を付ける」)。 */
    public static double infectionMultiplier(Player player) {
        if (!on()) return 1.0D;
        double max = spec().infectionMultiplierMax();
        return Math.min(max, 1.0D + (max - 1.0D) * effectStrength(player));
    }

    /** 創造・観戦の間(11-18 見直し ⑤5): 数えかけを捨て、鈍りの修飾を外す。疲労の値は残す(戻ればまた効く)。 */
    public static void exempt(Player player) {
        if (OvAttach.hasData(player, ModAttachments.FATIGUE)) OvAttach.getData(player, ModAttachments.FATIGUE).clearPending();
        syncModifiers(player);
    }

    /** 動きの鈍り(移動・採掘・攻撃速度)を今の疲労に合わせて張り直す。創造・観戦では外す。 */
    public static void syncModifiers(Player player) {
        FatigueSpec s = spec();
        double t = on() && !player.isCreative() && !player.isSpectator() ? spec().effectStrength(value(player)) : 0.0D;
        apply(player, Attributes.MOVEMENT_SPEED, MOVE_ID, -s.moveSlow() * t);
        apply(player, PortAttributes.BLOCK_BREAK_SPEED.get(), MINE_ID, -s.mineSlow() * t);
        apply(player, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID, -s.attackSpeedSlow() * t);
    }

    private static void apply(LivingEntity entity, Attribute attribute, ResourceLocation id, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier existing = OvModifiers.get(instance, id);
        if (Math.abs(amount) < 1.0E-4D) {
            if (existing != null) OvModifiers.remove(instance, id);
            return;
        }
        if (existing != null && Math.abs(existing.getAmount() - amount) < 1.0E-4D) return;
        OvModifiers.remove(instance, id);
        instance.addTransientModifier(OvModifiers.create(id, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static double clamp(double v) {
        return Double.isFinite(v) ? Math.max(0.0D, Math.min(100.0D, v)) : 0.0D;
    }

    // ---- 出来事を数える ----

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;
        if (player.isSleeping()) {
            // 寝袋は夜でも時刻を飛ばさない(2026-11-16 段 4、決定 40)
            if (isDayRest(player.level())
                    || com.yayuy.overgrownvegetation.survival.expedition.SleepingBagBlock.isInBag(player)) holdDayRest(player);
            return;
        }
        if (player.isSprinting()) OvAttach.getData(player, ModAttachments.FATIGUE).sprintTicks++;
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || !(event.getTarget() instanceof LivingEntity)) return;
        // 連打で疲れすぎないよう、攻撃の溜め(0〜1)で重み付け【仮】
        countAttack(player, player.getAttackStrengthScale(0.5F));
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide() || player.isCreative()) return;
        // 草・花のような一瞬で壊れるものは数えない【仮】
        if (event.getState().getDestroySpeed(event.getLevel(), event.getPos()) <= 0.0F) return;
        countBreak(player);
    }

    /** 殴った 1 回を数える(テストからも呼ぶ)。 */
    public static void countAttack(Player player) {
        countAttack(player, 1.0D);
    }

    /** 溜めの割合 {@code weight}(0〜1)で殴った 1 回を数える。 */
    public static void countAttack(Player player, double weight) {
        OvAttach.getData(player, ModAttachments.FATIGUE).attacks += Math.max(0.0D, Math.min(1.0D, weight));
    }

    /** 壊した 1 つを数える(テストからも呼ぶ)。 */
    public static void countBreak(Player player) {
        OvAttach.getData(player, ModAttachments.FATIGUE).breaks++;
    }

    // ---- 眠る・休む ----

    /** 昼の休みの間か(バニラの「夜しか眠れない」に当たる時間)。 */
    public static boolean isDayRest(Level level) {
        return level.isDay();
    }

    /**
     * 昼でもベッドに横になれる(夜しか眠れない、だけを外す)。昼になっても(昼の休みでも)ベッドから追い出さない。
     * 1.20.1: 1.21 の CanPlayerSleepEvent・CanContinueSleepingEvent の代わりに、Forge の時間の判定 1 つ(横になる時と眠り続ける時の両方で呼ばれる)。
     * 敵がまわりにいれば横になれないのは、バニラの判定(時間の判定の後・創造は除く・同じ 8×5 の箱)がそのまま行う
     * (1.20.1 では {@code dayRestNeedsSafety} が false でもバニラの判定は残る)。
     */
    @SubscribeEvent
    public static void onSleepingTimeCheck(SleepingTimeCheckEvent event) {
        if (on() && sleepCounterField() != null) { // 夜まで飛ぶのを止められないなら、昼の休みは入れない
            event.setResult(Event.Result.ALLOW);
        }
    }

    /** 夜を飛ばして全員が起こされた({@code ServerLevel#wakeUpAllPlayers} = すぐ起きない・数え直さない)→ 全部戻る。 */
    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || event.wakeImmediately() || event.updateLevel()) return;
        restoreFully(player);
    }

    private static boolean monstersNear(Player player, net.minecraft.core.BlockPos bed) {
        Vec3 c = Vec3.atBottomCenterOf(bed);
        return !player.level().getEntitiesOfClass(Monster.class,
                new AABB(c.x - 8.0D, c.y - 5.0D, c.z - 8.0D, c.x + 8.0D, c.y + 5.0D, c.z + 8.0D),
                m -> m.isPreventingPlayerRest(player)).isEmpty();
    }

    /** 昼の休みの間は「深く眠った」に届かせない(夜を飛ばす判定を立てない)。 */
    public static void holdDayRest(Player player) {
        Field f = sleepCounterField();
        if (f == null) return;
        try {
            if (f.getInt(player) > DAY_REST_COUNTER_CAP) f.setInt(player, DAY_REST_COUNTER_CAP);
        } catch (IllegalAccessException e) {
            sleepCounterFieldFailed = true;
        }
    }

    private static Field sleepCounterField() {
        if (sleepCounterField != null || sleepCounterFieldFailed) return sleepCounterField;
        try {
            // 1.20.1: 配る jar は SRG の名前(本流は実行時も Mojang の名前)。f_36110_ = Player#sleepCounter。開発の起動では Forge が Mojang の名前に読み替える
            Field f = net.minecraftforge.fml.util.ObfuscationReflectionHelper.findField(Player.class, "f_36110_");
            f.setAccessible(true);
            sleepCounterField = f;
        } catch (RuntimeException e) { // findField は見つからないと UnableToFindFieldException(実行時の例外)
            sleepCounterFieldFailed = true;
            OvergrownVegetation.LOGGER.error("疲労: Player#sleepCounter に届かない。昼の休みで夜まで飛ぶおそれ", e);
        }
        return sleepCounterField;
    }
}
