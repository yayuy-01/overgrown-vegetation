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
 *     <li>積むのはプレイヤーと<b>植物側以外のすべての {@link Mob}</b>(11-09 ユーザー決定。他 mod の Mob・この mod の人類側・ボスも入る。
 *     id の名前空間では分けない)。植物側 = {@link #isPlantSide}。データパック用のタグ {@code spore_exposure_immune}(既定は空)も外す。</li>
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
        int interval = player ? t.exposurePlayerIntervalTicks() : MobInfection.intervalFor(entity, t.exposureMobIntervalTicks());
        if (interval > 1 && Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) return;
        if (!entity.isAlive()) return;
        if (player && entity.isSpectator()) {
            if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) entity.getData(ModAttachments.SPORE_EXPOSURE).reset();
            sendWarning((Player) entity);
            return;
        }
        tick(level, entity, interval);
    }

    /** 暴露量を測る Mob か(11-09 ユーザー決定: 植物側以外のすべての Mob。名前空間では分けない)。 */
    public static boolean isMeasuredMob(LivingEntity entity) {
        return entity instanceof Mob && !isPlantSide(entity) && !entity.getType().is(ModTags.SPORE_EXPOSURE_IMMUNE);
    }

    /**
     * 植物側の Mob か: タグ {@code overgrown_vegetation:plant_side}(グループ 1〜10・萌芽体・ハイヴノード)、
     * または G1 の寄生体・萌芽体・ハイヴノードのクラス(タグから抜かれても漏れない保険)。
     */
    public static boolean isPlantSide(LivingEntity entity) {
        return entity.getType().is(ModTags.PLANT_SIDE)
                || entity instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster
                || entity instanceof com.yayuy.overgrownvegetation.entity.BlightFernEntity
                || entity instanceof com.yayuy.overgrownvegetation.entity.HiveNodeEntity
                || com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(entity); // 汎用の感染体(2026-11-15。ほかの MOD の Mob はタグを変えられないので印で)
    }

    /** 1 体ぶんの判定({@code interval} tick ぶん)。偽プレイヤーは tick しないので GameTest からも呼ぶ。 */
    public static void tick(ServerLevel level, LivingEntity entity, int interval) {
        SporeConcentration.Reading r = SporeConcentration.reading(level, entity.blockPosition());
        tickWith(level, entity, interval, r.percent(), r.roomSeepPerSecond());
    }

    /** {@code rawPercent}(その地点の胞子濃度)で 1 回判定する。Mob は屋根の判定を通す。 */
    public static void tickWith(ServerLevel level, LivingEntity entity, int interval, double rawPercent) {
        tickWith(level, entity, interval, rawPercent, 0.0D);
    }

    /** {@code roomSeepPerSecond} = 閉じた部屋の中のゆっくりたまる量(決定 22。{@link SporeConcentration.Reading})。 */
    public static void tickWith(ServerLevel level, LivingEntity entity, int interval, double rawPercent, double roomSeepPerSecond) {
        long now = level.getGameTime();
        if (entity instanceof Player player) {
            tickPlayer(level, player, interval, rawPercent, roomSeepPerSecond, now);
            return;
        }
        double percent = mobPercent(level, entity, rawPercent);
        boolean protecting = wearMask(entity, percent, interval);
        boolean wasFull = dose(entity) >= DesignData.sporeField().infectDose(); // 減る前に満ちていた分(コマンドで決めたときなど)も拾う
        if (MobInfection.isInfected(entity)) {
            // 感染した後(2026-11-14): 減らず、体の中の胞子が育つ。濃い所ではその分も足す(マスクが効いていれば外の分は足さない)
            if (!protecting && percent >= DesignData.sporeField().exposureMinPercent()) accumulate(entity, percent, 1.0D, interval, now);
            MobInfection.grow(entity, interval);
        } else {
            accumulate(entity, percent, protecting ? 0.0D : 1.0D, interval, now, percent > 0.0D ? roomSeepPerSecond : 0.0D);
            MobInfection.countExposure(entity, protecting ? 0.0D : percent, interval, now); // 5 秒で感染(2026-11-14)
        }
        MobInfection.afterCheck(level, entity, percent, now);
        if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            if (wasFull || ex.dose >= DesignData.sporeField().infectDose()) {
                hurtMob(level, entity, ex, now);
            }
        }
    }

    private static void tickPlayer(ServerLevel level, Player player, int interval, double percent, double roomSeepPerSecond, long now) {
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
            accumulate(player, percent, protecting ? 0.0D : 1.0D, interval, now, roomSeepPerSecond
                    + com.yayuy.overgrownvegetation.survival.decon.GearDirt.seepPerSecond(player)); // 汚れた装備から吸う(2026-11-14 段 3b)
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
        // 疲労と、HUD の 2 本のゲージ(2026-11-13 サバイバルの背骨 段 1)
        com.yayuy.overgrownvegetation.survival.FatigueTracker.step(player, interval, percent, SporeInfectionEffects.stageOf(player), protecting);
        // 装備の汚れと、汚れた物で部屋が汚れる(2026-11-14 サバイバルの背骨 段 3b・3c)
        com.yayuy.overgrownvegetation.survival.decon.GearDirt.step(level, player, interval, percent);
        com.yayuy.overgrownvegetation.survival.SurvivalSync.send(player);
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
            // 死ぬ所で汎用の感染体に変わった Mob(2026-11-15)は、傷を外して満タンで変わったので傷を付け直さない
            if (mob.isAlive() && !com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(mob)) {
                scarMaxHealth(mob, DesignData.sporeField().mobMaxHealthLossPerHit());
            }
            ex.dose = 0.0D;
            ex.atDose = false;
            ex.reachedCount++;
            ex.lastReachedGameTime = now;
        } else {
            ex.atDose = true;
        }
    }

    /** 胞子の傷で下がった最大体力の修飾の id(11-09)。 */
    public static final net.minecraft.resources.ResourceLocation SPORE_SCAR_ID = OvergrownVegetation.id("spore_scar");

    /**
     * 胞子の 20 ダメージが入るたびに最大体力を {@code loss} 下げる(2026-11-09 ユーザー決定「受けた分が治らない傷として残る」。元に戻らない)。
     * 修飾は 1 つの id にまとめて下げた量を足していき、属性ごとセーブに残る(addPermanentModifier)。最大体力は 1 より下げず、
     * 今の体力は新しい上限に切り詰める。Mob だけ(プレイヤーは 10 段階の −(段階 − 1) のまま)。
     */
    public static void scarMaxHealth(LivingEntity mob, double loss) {
        if (loss <= 0.0D || mob instanceof Player) return;
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr =
                mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attr == null) return;
        net.minecraft.world.entity.ai.attributes.AttributeModifier old = attr.getModifier(SPORE_SCAR_ID);
        double already = old != null ? -old.amount() : 0.0D;
        double without = attr.getValue() + already; // この傷を除いた最大体力
        double total = Math.min(already + loss, Math.max(0.0D, without - 1.0D));
        if (total <= already) return;
        attr.removeModifier(SPORE_SCAR_ID);
        attr.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SPORE_SCAR_ID, -total,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        if (mob.getHealth() > mob.getMaxHealth()) mob.setHealth(mob.getMaxHealth());
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
        accumulate(entity, percent, factor, intervalTicks, gameTime, 0.0D);
    }

    /**
     * {@code roomSeepPerSecond} 付き(2026-11-13 決定 22): 濃度が境目より下でも、閉じた部屋の中で外が過繁茂の濃さ以上なら、
     * 暴露量がこの速さ(%秒 / 秒)でたまり、抜けない。マスクが効いていれば(factor 0)たまらない。境目以上なら今までどおり。
     */
    public static void accumulate(LivingEntity entity, double percent, double factor, int intervalTicks, long gameTime, double roomSeepPerSecond) {
        SporeFieldTable t = DesignData.sporeField();
        double seconds = intervalTicks / 20.0D;
        double effective = percent * factor;
        if (effective >= t.exposureMinPercent() && effective > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.dose = Math.min(t.infectDose(), ex.dose + effective * seconds);
            return;
        }
        if (roomSeepPerSecond > 0.0D && factor > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.dose = Math.min(t.infectDose(), ex.dose + roomSeepPerSecond * factor * seconds);
            return;
        }
        if (!entity.hasData(ModAttachments.SPORE_EXPOSURE)) return;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        if (ex.dose > 0.0D) {
            // 疲労で抜けにくくなる(2026-11-13 サバイバル段 1。プレイヤーだけ、Mob は 1 倍)
            ex.dose *= Math.pow(0.5D, seconds / (t.exposureHalfLifeSeconds()
                    * com.yayuy.overgrownvegetation.survival.FatigueTracker.exposureHalfLifeMultiplier(entity)));
            if (ex.dose < 1.0D) ex.dose = 0.0D;
        }
        if (ex.atDose && ex.dose < t.infectDose() * 0.5D) ex.atDose = false;
    }

    /**
     * G1 の攻撃が当たった Mob の暴露量を足す(2026-11-08 G1 段階 0、11-05 ユーザー「Mob に当てたら暴露量を足す → 入れる」。1 撃の量は
     * {@code G1StrikeGoal#MOB_DOSE})。測る Mob({@link #isMeasuredMob})だけ。感染の量で頭打ち —— 届いたら次の判定で 20 ダメージになる。
     * プレイヤーは足さない(プレイヤーは {@code ParasitizedAttackInfectionHandler} の 30% / 5% のまま)。
     */
    public static void addDose(LivingEntity entity, double amount) {
        if (amount <= 0.0D || entity instanceof Player || !isMeasuredMob(entity) || !entity.isAlive()) return;
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        ex.dose = Math.min(DesignData.sporeField().infectDose(), ex.dose + amount);
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
