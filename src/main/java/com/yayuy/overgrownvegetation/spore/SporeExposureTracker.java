package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.network.SporeExposurePayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 胞子の暴露量を積み、満ちたときの影響を起こす(2026-11-04 感染システムの第 1 段・第 2 段。数値は {@code spore_field.json} の exposure
 * と {@code effects.json} の sporeInfection.player)。
 *
 * <h2>ユーザー指定</h2>
 * <ul>
 *     <li>暴露 = 胞子濃度 % × 秒。1500 %秒が感染の量。5% 未満は積まない。濃い所を出ると 1 分で半分(10-23 追記 2)。Mob も同じ式。</li>
 *     <li>屋根の下(空が見えない所)の Mob は積まない。ただし繁茂・過繁茂のバイオームでは屋根の下でも積む(10-22 G1)。</li>
 *     <li><b>プレイヤー</b>: 1500 に届いたら胞子感染1(11-04)。そこから 10 分ごとに 1 段、加速は濃度に比例({@link SporeInfectionEffects})。</li>
 *     <li><b>Mob</b>: 1500 に届いたら、すべての防御を無視して 20 ダメージ → 暴露量を 0 に戻して繰り返す(11-04)。</li>
 *     <li>防護マスク: 耐久のある間はかからない = 暴露を積まない(11-04)。</li>
 *     <li>前触れは画面のにじみだけ(11-04。クライアントへ {@link SporeExposurePayload} で知らせる)。</li>
 * </ul>
 *
 * <h2>仮決め</h2>
 * <ul>
 *     <li>積むのはプレイヤーと<b>バニラの Mob</b>(id が minecraft: の {@link Mob})。タグ {@code overgrown_vegetation:spore_exposure_immune}
 *     (エンダードラゴン・ウィザー)は測らない。</li>
 *     <li>プレイヤーは屋根の判定を使わず、胞子計に出る値をそのまま積む(11-04 ユーザー「このままでよい」)。</li>
 *     <li>判定の間隔はプレイヤー 20 tick・Mob 40 tick。その 1 回で間隔ぶんの秒を積む / 減らす。</li>
 *     <li>プレイヤーが感染1 にかかったら暴露量を 0 に戻す。感染中は積まず、減っていくだけ(にじみも消える)。</li>
 *     <li>Mob の 20 ダメージが通らなかったら(無敵など)暴露量はそのまま、次の判定でまた試す。</li>
 *     <li>暴露量は保存する(プレイヤーも Mob も)。マスクの摩耗は Mob にも同じに掛ける。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SporeExposureTracker {

    /** クライアントへ送る暴露量の刻み(1/32)。 */
    private static final int SEND_STEPS = 32;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity e = event.getEntity();
        if (!(e instanceof LivingEntity entity) || !(e.level() instanceof ServerLevel level)) return;
        boolean player = entity instanceof Player;
        if (!player && !isMeasuredMob(entity)) return;
        SporeFieldTable t = DesignData.sporeField();
        int interval = player ? t.exposurePlayerIntervalTicks() : t.exposureMobIntervalTicks();
        if (interval > 1 && Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) return;
        if (!entity.isAlive()) return;
        if (player && entity.isSpectator()) {
            if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) entity.getData(ModAttachments.SPORE_EXPOSURE).reset();
            sendWarning((Player) entity);
            return;
        }
        tick(level, entity, interval);
    }

    /** 暴露量を測る Mob か(仮決め: バニラの Mob だけ・ボスは除く)。 */
    public static boolean isMeasuredMob(LivingEntity entity) {
        return entity instanceof Mob
                && "minecraft".equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace())
                && !entity.getType().is(ModTags.SPORE_EXPOSURE_IMMUNE);
    }

    /** 1 体ぶんの判定({@code interval} tick ぶん)。偽プレイヤーは tick しないので GameTest からも呼ぶ。 */
    public static void tick(ServerLevel level, LivingEntity entity, int interval) {
        tickWith(level, entity, interval, SporeConcentration.percentAt(level, entity.blockPosition()));
    }

    /** {@code rawPercent}(その地点の胞子濃度)で 1 回判定する。Mob は屋根の判定を通す。 */
    public static void tickWith(ServerLevel level, LivingEntity entity, int interval, double rawPercent) {
        long now = level.getGameTime();
        if (entity instanceof Player player) {
            tickPlayer(level, player, interval, rawPercent, now);
            return;
        }
        double percent = mobPercent(level, entity, rawPercent);
        boolean protecting = wearMask(entity, percent, interval);
        boolean wasFull = dose(entity) >= DesignData.sporeField().infectDose(); // 減る前に満ちていた分(コマンドで決めたときなど)も拾う
        accumulate(entity, percent, protecting ? 0.0D : 1.0D, interval, now);
        if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            if (wasFull || ex.dose >= DesignData.sporeField().infectDose()) {
                hurtMob(level, entity, ex, now);
            }
        }
    }

    private static void tickPlayer(ServerLevel level, Player player, int interval, double percent, long now) {
        SporeFieldTable t = DesignData.sporeField();
        SporeExposure ex = player.getData(ModAttachments.SPORE_EXPOSURE);
        boolean protecting = wearMask(player, percent, interval);
        int stage = SporeInfectionEffects.stageOf(player);
        if (stage == 0) {
            // 治った・死んだ後の残り(属性と進み)を片付ける
            SporeInfectionEffects.syncModifiers(player);
            if (player.hasData(ModAttachments.SPORE_STAGE_TICKS) && player.getData(ModAttachments.SPORE_STAGE_TICKS) != 0) {
                player.setData(ModAttachments.SPORE_STAGE_TICKS, 0);
            }
            boolean wasFull = ex.dose >= t.infectDose(); // 減る前に満ちていた分も拾う
            accumulate(player, percent, protecting ? 0.0D : 1.0D, interval, now);
            if ((wasFull || ex.dose >= t.infectDose()) && SporeInfectionEffects.startPlayer(player)) {
                ex.dose = 0.0D;
                ex.atDose = false;
                ex.reachedCount++;
                ex.lastReachedGameTime = now;
            }
        } else {
            // 感染中は積まない(減っていくだけ)。進みは場所の倍率を掛けて足す
            accumulate(player, 0.0D, 1.0D, interval, now);
            SporeInfectionEffects.tickInfected(player, ex, interval, percent,
                    ModBiomes.isFullyOvergrown(level.getBiome(player.blockPosition())), protecting);
        }
        sendWarning(player);
    }

    /**
     * Mob の暴露量が満ちた: 防御を無視して 20 ダメージ(ダメージの種類 spore_infection。防具・エンチャント・耐性・盾・無敵時間を貫く、
     * 衝撃吸収は先に減る)。通ったら暴露量を 0 に戻す。
     */
    private static void hurtMob(ServerLevel level, LivingEntity mob, SporeExposure ex, long now) {
        float amount = (float) DesignData.sporeField().mobExposureDamage();
        if (amount <= 0.0F) return;
        if (mob.hurt(ModDamageTypes.sporeInfection(level), amount)) {
            ex.dose = 0.0D;
            ex.atDose = false;
            ex.reachedCount++;
            ex.lastReachedGameTime = now;
        } else {
            ex.atDose = true;
        }
    }

    /**
     * 防護マスクのフィルターをすり減らす: 被っていて残りがあり、濃度が exposure.minPercent 以上なら 濃度 × 秒 を吸わせる(端数は持ち越す)。
     *
     * @return この判定の間、マスクが胞子を防いでいたか(すり減らす前に残りがあったか)
     */
    public static boolean wearMask(LivingEntity entity, double percent, int intervalTicks) {
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (!(head.getItem() instanceof ProtectiveMaskItem) || ProtectiveMaskItem.remaining(head) <= 0.0D) return false;
        SporeFieldTable t = DesignData.sporeField();
        if (percent >= t.exposureMinPercent() && percent > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            double exact = percent * intervalTicks / 20.0D + ex.maskCarry;
            int whole = (int) Math.floor(exact);
            ex.maskCarry = exact - whole;
            ProtectiveMaskItem.absorb(head, whole);
        }
        return true;
    }

    /** にじみの強さを、変わったときだけクライアントへ送る(暴露量の 1/32 刻み)。 */
    private static void sendWarning(Player player) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer || sp.connection == null) return;
        SporeExposure ex = player.getData(ModAttachments.SPORE_EXPOSURE);
        int step = (int) Math.floor(fraction(player) * SEND_STEPS);
        if (step == ex.sentStep) return;
        ex.sentStep = step;
        SporeFieldTable t = DesignData.sporeField();
        PacketDistributor.sendToPlayer(sp, new SporeExposurePayload((float) step / SEND_STEPS,
                (float) t.warnFaintFraction(), (float) t.warnStrongFraction()));
    }

    /**
     * Mob が浴びる濃度: 空の下か、繁茂・過繁茂のバイオームなら {@code rawPercent}、それ以外(屋根の下)は 0(10-22 ユーザー指定)。
     */
    public static double mobPercent(ServerLevel level, LivingEntity mob, double rawPercent) {
        if (rawPercent <= 0.0D) return 0.0D;
        if (underSky(level, mob)) return rawPercent;
        return ModBiomes.isOvergrownLike(level.getBiome(mob.blockPosition())) ? rawPercent : 0.0D;
    }

    /** 暴露の倍率(0〜1)。フィルターの残った防護マスクを被っていれば 0(11-04 ユーザー決定)、そうでなければ 1。 */
    public static double exposureFactor(LivingEntity entity) {
        return ProtectiveMaskItem.isProtecting(entity) ? 0.0D : 1.0D;
    }

    /**
     * 暴露を 1 回積む。濃度 × 倍率 が exposure.minPercent 以上なら 濃度 × 倍率 × 秒 を足し(感染の量で頭打ち)、
     * それ未満なら半減期で減らす(1 %秒 を割ったら 0)。まだ添付を持っていない個体には、積むときまで作らない。
     * 満ちたときの影響は呼ぶ側({@link #tickWith})。
     */
    public static void accumulate(LivingEntity entity, double percent, double factor, int intervalTicks, long gameTime) {
        SporeFieldTable t = DesignData.sporeField();
        double seconds = intervalTicks / 20.0D;
        double effective = percent * factor;
        if (effective >= t.exposureMinPercent() && effective > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.dose = Math.min(t.infectDose(), ex.dose + effective * seconds);
            return;
        }
        if (!entity.hasData(ModAttachments.SPORE_EXPOSURE)) return;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        if (ex.dose > 0.0D) {
            ex.dose *= Math.pow(0.5D, seconds / t.exposureHalfLifeSeconds());
            if (ex.dose < 1.0D) ex.dose = 0.0D;
        }
        if (ex.atDose && ex.dose < t.infectDose() * 0.5D) ex.atDose = false;
    }

    /** 暴露量(%秒)。添付が無ければ 0(作らない)。 */
    public static double dose(LivingEntity entity) {
        return entity.hasData(ModAttachments.SPORE_EXPOSURE) ? entity.getData(ModAttachments.SPORE_EXPOSURE).dose : 0.0D;
    }

    /** 暴露量の感染の量に対する割合(0〜1)。 */
    public static double fraction(LivingEntity entity) {
        return Mth.clamp(dose(entity) / DesignData.sporeField().infectDose(), 0.0D, 1.0D);
    }

    /** 確かめ用: 暴露量を決める(0 なら記録ごと消す)。感染の量にしたら、次の判定で満ちたときの影響が起きる。 */
    public static void setDose(LivingEntity entity, double dose, long gameTime) {
        if (dose <= 0.0D) {
            if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) entity.getData(ModAttachments.SPORE_EXPOSURE).reset();
            return;
        }
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        ex.dose = Math.min(DesignData.sporeField().infectDose(), dose);
        ex.atDose = false;
    }

    /**
     * 空の下か(目の高さより上に、葉を除いた動きを止めるブロック・水が無い)。高さの地図で引き、地図の天辺が目より上なら
     * 間を数える(48 ブロックより深ければ屋根の下)。高さの地図はバリア(テスト場の天井など)も屋根に数えるので、バリアは飛ばす。
     * 10-30 に退避した {@code SporeInfectionTracker#underSky} と同じ。
     */
    public static boolean underSky(ServerLevel level, LivingEntity mob) {
        int x = mob.getBlockX(), z = mob.getBlockZ();
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int eye = Mth.floor(mob.getEyeY());
        if (eye >= top) return true;
        if (top - eye > 48) return false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = eye; y < top; y++) {
            BlockState s = level.getBlockState(p.set(x, y, z));
            if (s.is(Blocks.BARRIER) || s.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            if (s.blocksMotion() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private SporeExposureTracker() {
    }
}
