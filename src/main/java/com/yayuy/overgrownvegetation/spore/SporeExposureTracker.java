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
        // 観戦・創造のプレイヤーは暴露量をためず、段も進まない(2026-11-16 コードの見直し ③9: 前は観戦だけ外し、創造ではたまっていた。傷・技の側とそろえる)
        if (player && (entity.isSpectator() || ((Player) entity).isCreative())) {
            if (entity.hasData(ModAttachments.SPORE_EXPOSURE)) entity.getData(ModAttachments.SPORE_EXPOSURE).reset();
            // 疲労・装備の汚れの修飾を外し、数えかけを捨てる(値は残す。11-18 見直し ⑤5)
            com.yayuy.overgrownvegetation.survival.FatigueTracker.exempt((Player) entity);
            com.yayuy.overgrownvegetation.survival.decon.GearDirt.applyModifiers((Player) entity);
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
     * 植物側の陣営か。中身は {@link PlantSideRules#isPlantSide} の 1 か所(2026-11-16 コードの見直し ③6 でそろえた。
     * 呼ぶ所が多いので、この名前は残して委ねる)。
     */
    public static boolean isPlantSide(LivingEntity entity) {
        return PlantSideRules.isPlantSide(entity);
    }

    /** 1 体ぶんの判定({@code interval} tick ぶん)。偽プレイヤーは tick しないので GameTest からも呼ぶ。 */
    public static void tick(ServerLevel level, LivingEntity entity, int interval) {
        SporeConcentration.Reading r = SporeConcentration.reading(level, entity.blockPosition(), entity instanceof Player); // プレイヤーは部屋の判定を上限で断らない(③12)
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
        double gear = com.yayuy.overgrownvegetation.gear.GearSpores.exposureFactor(entity); // 段の防具(2026-11-17)
        boolean wasFull = dose(entity) >= DesignData.sporeField().infectDose(); // 減る前に満ちていた分(コマンドで決めたときなど)も拾う
        if (MobInfection.isInfected(entity)) {
            // 感染した後(2026-11-14): 減らず、体の中の胞子が育つ。濃い所ではその分も足す(マスクが効いていれば外の分は足さない)
            if (!protecting && percent >= DesignData.sporeField().exposureMinPercent()) accumulate(entity, percent, 1.0D, interval, now);
            MobInfection.grow(entity, interval);
        } else {
            accumulate(entity, percent, protecting ? 0.0D : gear, interval, now, percent > 0.0D ? roomSeepPerSecond : 0.0D);
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
        if (player.isCreative() || player.isSpectator()) {
            // 創造・観戦は外す(11-16 ③9。tick の入口と同じ。GameTest のように直に呼ばれたときも)
            if (player.hasData(ModAttachments.SPORE_EXPOSURE)) player.getData(ModAttachments.SPORE_EXPOSURE).reset();
            // 疲労・装備の汚れの修飾を外し、数えかけ(殴った・壊した・走った)を捨てる。ためた値そのものは消さない
            // (11-18 見直し ⑤5。前はここで戻るので修飾が残り、創造の間の殴りがサバイバルに戻した瞬間に一度に乗った)
            com.yayuy.overgrownvegetation.survival.FatigueTracker.exempt(player);
            com.yayuy.overgrownvegetation.survival.decon.GearDirt.applyModifiers(player);
            return;
        }
        SporeFieldTable t = DesignData.sporeField();
        SporeExposure ex = player.getData(ModAttachments.SPORE_EXPOSURE);
        boolean protecting = wearMask(player, percent, interval);
        double gear = com.yayuy.overgrownvegetation.gear.GearSpores.exposureFactor(player); // 段の防具(2026-11-17): 息で浴びる分と染みる分を下げる
        int stage = SporeInfectionEffects.stageOf(player);
        if (stage == 0) {
            // 治った・死んだ後の残り(属性と進み)を片付ける
            SporeInfectionEffects.syncModifiers(player);
            if (player.hasData(ModAttachments.SPORE_STAGE_TICKS) && player.getData(ModAttachments.SPORE_STAGE_TICKS) != 0) {
                player.setData(ModAttachments.SPORE_STAGE_TICKS, 0);
            }
            boolean wasFull = ex.dose >= t.infectDose(); // 減る前に満ちていた分も拾う
            double rate = com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.exposureRate(player.getServer()); // MOD の設定「暴露量のたまる速さ」(11-19。難易度の段で変わる)
            accumulate(player, percent, protecting ? 0.0D : gear, interval, now, roomSeepPerSecond
                    + com.yayuy.overgrownvegetation.survival.decon.GearDirt.seepPerSecond(player), rate); // 汚れた装備から吸う(2026-11-14 段 3b)
            if ((wasFull || ex.dose >= t.infectDose()) && SporeInfectionEffects.startPlayer(player)) {
                ex.dose = 0.0D;
                ex.atDose = false;
                ex.reachedCount++;
                ex.lastReachedGameTime = now;
            }
        } else {
            // 感染中(11-15 ユーザー決定「混ぜた形」): 息で浴びた胞子は暴露量の代わりに段の進みへ積む(マスクが効いていれば止める)。
            // 濃度が境目以上ならその濃度、それ未満なら閉じた部屋・汚れた装備から染みる分(未感染の積み方と同じ)。体の中で育つ分は tickInfected が足す。
            // 暴露量そのものは減っていくだけ(バーは感染中は段の進みを塗る)
            accumulate(player, 0.0D, 1.0D, interval, now);
            double seep = roomSeepPerSecond + com.yayuy.overgrownvegetation.survival.decon.GearDirt.seepPerSecond(player);
            double breathed = protecting ? 0.0D : (percent >= t.exposureMinPercent() && percent > 0.0D ? percent : seep) * gear;
            SporeInfectionEffects.tickInfected(player, ex, interval, breathed);
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
        // 入ったかは hurt の戻り値だけでは決めない(2026-11-16 コードの見直し ③1): エンダードラゴンの胴の部位はプレイヤー以外のダメージを
        // 弾いても true を返し、最大体力だけ減っていた。体力 + 吸収体力の前後で比べる(吸収体力で受けた正常な被弾も減ったと数える)。
        // その撃ちで死んだ・汎用の感染体に変わった(死を止めて満タンに戻る)ときは入ったとみなす
        float before = mob.getHealth() + mob.getAbsorptionAmount();
        boolean wasTurned = com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(mob);
        boolean returned = mob.hurt(ModDamageTypes.sporeInfection(level), amount);
        boolean landed = returned && (mob.isDeadOrDying() || mob.isRemoved()
                || (!wasTurned && com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(mob))
                || mob.getHealth() + mob.getAbsorptionAmount() < before - 1.0E-4F);
        if (landed) {
            // 死ぬ所で汎用の感染体に変わった Mob(2026-11-15)は、傷を外して満タンで変わったので傷を付け直さない
            if (mob.isAlive() && !com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(mob)) {
                scarMaxHealth(mob, DesignData.sporeField().mobMaxHealthLossPerHit());
            }
            ex.dose = 0.0D;
            ex.atDose = false;
            ex.reachedCount++;
            ex.lastReachedGameTime = now;
        } else if (com.yayuy.overgrownvegetation.infect.GenericInfection.onSporeHitBlocked(level, mob, amount)) {
            // 胞子のダメージの種類に耐性を持つ Mob(2026-11-15 ユーザー決定「耐性を無視して胞子で変える」): 弾かれても入ったことにして数える
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
        if (!com.yayuy.overgrownvegetation.gear.FilterMask.isMask(head) || ProtectiveMaskItem.remaining(head) <= 0.0D) return false;
        SporeFieldTable t = DesignData.sporeField();
        if (percent >= t.exposureMinPercent() && percent > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            // 段の頭はフィルターの減りが遅い(2026-11-17。防護マスクは 1 倍)
            double exact = percent * intervalTicks / 20.0D * com.yayuy.overgrownvegetation.gear.FilterMask.wearOf(head) + ex.maskCarry;
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
        return ProtectiveMaskItem.isProtecting(entity) ? 0.0D : com.yayuy.overgrownvegetation.gear.GearSpores.exposureFactor(entity);
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
        accumulate(entity, percent, factor, intervalTicks, gameTime, roomSeepPerSecond, 1.0D);
    }

    /**
     * {@code rate} 付き(2026-11-19 MOD の設定「暴露量のたまる速さ」): たまる量だけに掛ける(濃度の境目の判定には掛けない)。
     */
    public static void accumulate(LivingEntity entity, double percent, double factor, int intervalTicks, long gameTime,
                                  double roomSeepPerSecond, double rate) {
        SporeFieldTable t = DesignData.sporeField();
        double seconds = intervalTicks / 20.0D;
        double effective = percent * factor;
        if (effective >= t.exposureMinPercent() && effective > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.dose = Math.min(t.infectDose(), ex.dose + effective * seconds * rate);
            return;
        }
        if (roomSeepPerSecond > 0.0D && factor > 0.0D) {
            SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
            ex.dose = Math.min(t.infectDose(), ex.dose + roomSeepPerSecond * factor * seconds * rate);
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
     * 暴露量を足す共通の口(2026-11-16 コードの見直し ③6 で 3 か所 = 傷の暴露・グレガリアの噛みつき・G1 の胞子の技 をまとめた)。
     * <ul>
     *   <li>創造・観戦のプレイヤー・測らない Mob({@link #isMeasuredMob})・死んでいる相手には足さない。</li>
     *   <li>{@code breathed}(息で浴びる胞子 = G1 の胞子の技): フィルターの残る防護マスクを被っていれば足さず、量ぶんフィルターをすり減らす。
     *   傷の暴露・噛みつきは false(傷口から入るのでマスクでは防げない。11-15 ユーザー決定)。</li>
     *   <li>感染中のプレイヤーは、暴露量の代わりに段の進みへ足す(11-15 の「混ぜた形」)。</li>
     *   <li>それ以外は感染の量で頭打ち(満ちたら次の判定で、プレイヤーは感染 1・Mob は 20 ダメージ)。</li>
     * </ul>
     *
     * @return 足したか(マスク・相手の種類で防がれたら false)
     */
    public static boolean addExposure(LivingEntity entity, double amount, boolean breathed) {
        if (!(amount > 0.0D) || !entity.isAlive()) return false;
        if (entity instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
        } else if (!isMeasuredMob(entity)) {
            return false;
        }
        if (breathed && ProtectiveMaskItem.isProtecting(entity)) {
            ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
            ProtectiveMaskItem.absorb(head, (int) Math.ceil(amount * com.yayuy.overgrownvegetation.gear.FilterMask.wearOf(head)));
            return false;
        }
        if (breathed) {
            amount *= com.yayuy.overgrownvegetation.gear.GearSpores.exposureFactor(entity); // 段の防具(2026-11-17)
            if (!(amount > 0.0D)) return false;
        }
        if (entity instanceof Player p && com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.stageOf(p) > 0) {
            com.yayuy.overgrownvegetation.effect.SporeInfectionEffects.advance(p, amount);
            return true;
        }
        if (entity instanceof Player p && p.getServer() != null) {
            amount *= com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.exposureRate(p.getServer()); // MOD の設定「暴露量のたまる速さ」(11-19)
            if (!(amount > 0.0D)) return false;
        }
        SporeExposure ex = entity.getData(ModAttachments.SPORE_EXPOSURE);
        ex.dose = Math.min(DesignData.sporeField().infectDose(), ex.dose + amount);
        return true;
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
