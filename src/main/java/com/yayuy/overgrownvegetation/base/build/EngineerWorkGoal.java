package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * 工兵の整備の動き(順 2 の着手順 4、2026-10-04)。{@link BaseUpkeep} が渡した仕事を、倉庫で品を取る → 現場へ歩く(はしごの除去塔は登る)→
 * 直す → 使用済みのフィルターと余りを倉庫へ持ち帰る、の順に片付ける。運んでいる品は左手に見せる(拾えない —— 工兵が倒れたら失われる)。
 *
 * <p><b>ユーザー指定</b>: 整備するのは工兵(10-02)・はしごしか無い除去塔は工兵が登る(10-04)。Claude 仮決め: 敵が {@code upkeepEngageRange} マス以内に
 * いる間は仕事を置いて戦う(登っている途中は登り切る)。行けない・品が無い仕事はしばらく置いておく。
 */
public final class EngineerWorkGoal extends Goal {

    private enum Phase {START, TO_STORE, TO_SITE, CLIMB_UP, WORK, CLIMB_DOWN, RETURN}

    /** はしごを登る速さ(ブロック / tick)。 */
    private static final double CLIMB_SPEED = 0.12;
    /** 設備・直すマスに手が届く距離(目から)。 */
    private static final double REACH = 4.5;
    /** 歩いて着いたとみなす距離。 */
    private static final double ARRIVE = 1.6;
    /** これだけ近づけないと行けないとみなす(tick)。 */
    private static final int STUCK_TICKS = 240;
    /** 設備の整備にかける時間(tick)。 */
    private static final int SERVICE_TICKS = 30;

    private final SoldierEntity eng;
    @Nullable
    private UpkeepJob job;
    private Phase phase = Phase.START;
    private final List<ItemStack> carried = new ArrayList<>();
    @Nullable
    private BaseWarehouse.Store store;
    @Nullable
    private BlockPos dest;
    /** はしご: 下の端の足元のマスと、上の端の y。 */
    @Nullable
    private BlockPos ladder;
    private int ladderTop;
    private int workTicks;
    private int fixIndex;
    private double bestDist = Double.MAX_VALUE;
    private int stuck;
    private int pathCooldown;
    @Nullable
    private Phase tracedPhase;
    /** 立つ所に着いても届かなかったので、マス(設備)そのものへもう一度寄っている。 */
    private boolean nudged;
    /** 壁の外へ(から)出るときに先に通る正門の前後(順に)と、それを決めた行き先。 */
    private final java.util.ArrayDeque<BlockPos> route = new java.util.ArrayDeque<>();
    @Nullable
    private BlockPos routedFor;

    public EngineerWorkGoal(SoldierEntity eng) {
        this.eng = eng;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ---------- 始める・続ける ----------

    private boolean engaged() {
        LivingEntity t = eng.getTarget();
        double r = DesignData.defense().baseUpkeepEngageRange();
        return t != null && t.isAlive() && eng.distanceToSqr(t) <= r * r;
    }

    private boolean climbing() {
        return phase == Phase.CLIMB_UP || phase == Phase.CLIMB_DOWN || phase == Phase.WORK && ladder != null;
    }

    @Override
    public boolean canUse() {
        if (!(eng.level() instanceof ServerLevel) || !eng.isAlive() || engaged()) {
            return false;
        }
        UpkeepJob j = BaseUpkeep.jobFor(eng);
        if (j == null) {
            if (!carried.isEmpty()) { // 仕事が取り消された(基地を離れた等)のに品を持っている: 倉庫へ返しにいく
                return BaseUpkeep.storesOf(eng) != null;
            }
            return false;
        }
        if (j != job) {
            reset();
            job = j;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!eng.isAlive()) {
            return false;
        }
        if (climbing()) {
            return true; // 登っている途中は登り切る・降り切る
        }
        if (engaged()) {
            return false;
        }
        return job != null ? BaseUpkeep.owns(eng, job) : !carried.isEmpty();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        eng.getNavigation().stop();
        if (climbing()) { // 取り消された: はしごの上で止まらない
            eng.setNoGravity(false);
        }
        if (job != null && !BaseUpkeep.owns(eng, job)) {
            reset();
        }
    }

    private void reset() {
        if (ladder != null) {
            eng.setNoGravity(false);
        }
        job = null;
        phase = Phase.START;
        store = null;
        dest = null;
        ladder = null;
        workTicks = 0;
        fixIndex = 0;
        nudged = false;
        routedFor = null;
        route.clear();
        clearProgress();
    }

    private void clearProgress() {
        bestDist = Double.MAX_VALUE;
        stuck = 0;
        pathCooldown = 0;
    }

    // ---------- 毎 tick ----------

    @Override
    public void tick() {
        if (!(eng.level() instanceof ServerLevel level)) {
            return;
        }
        if (BaseUpkeep.trace && (phase != tracedPhase || eng.tickCount % 40 == 0)) {
            tracedPhase = phase;
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[upkeep] {} phase={} job={} dest={} pos={} nav={} carried={} ladder={}",
                    eng.getId(), phase, job == null ? null : job.kind() + "@" + job.pos().toShortString(), dest == null ? null : dest.toShortString(),
                    String.format("%.2f,%.2f,%.2f", eng.getX(), eng.getY(), eng.getZ()), eng.getNavigation().isDone() ? "done" : "moving",
                    carried, ladder == null ? null : ladder.toShortString());
        }
        if (job == null) { // 品だけ持っている: 返しにいく
            if (phase != Phase.RETURN) {
                phase = Phase.RETURN;
                store = null;
                clearProgress();
            }
            tickReturn(level);
            return;
        }
        switch (phase) {
            case START -> begin(level);
            case TO_STORE -> tickToStore(level);
            case TO_SITE -> tickToSite(level);
            case CLIMB_UP -> tickClimb(true);
            case WORK -> tickWork(level);
            case CLIMB_DOWN -> tickClimb(false);
            case RETURN -> tickReturn(level);
        }
    }

    private void begin(ServerLevel level) {
        if (needsStore(level)) {
            store = BaseUpkeep.nearestStore(eng, job);
            if (store == null) {
                BaseUpkeep.retry(eng, job);
                return;
            }
            go(Phase.TO_STORE, store.stand());
        } else {
            toSite(level);
        }
    }

    /** 倉庫で品を取る必要があるか(弾・レッドストーン・フィルター・補修材のどれかが要る)。 */
    private boolean needsStore(ServerLevel level) {
        return switch (job.kind()) {
            case TURRET -> level.getBlockEntity(job.pos()) instanceof GunTurretBlockEntity t && BaseUpkeep.roundsWanted(t) > 0;
            case PURIFIER -> level.getBlockEntity(job.pos()) instanceof SporeDeviceBlockEntity d
                    && (BaseUpkeep.redstoneWanted(d) > 0 || BaseUpkeep.filterWanted(d));
            case FIX -> job.repairCost() > 0;
        };
    }

    private void go(Phase next, BlockPos to) {
        phase = next;
        dest = to;
        routedFor = null;
        route.clear();
        clearProgress();
    }

    private void tickToStore(ServerLevel level) {
        if (walk(dest, ARRIVE) && job != null) {
            pick(level);
            toSite(level);
        }
    }

    /** 倉庫で要る品を取る(足りなければある分だけ)。 */
    private void pick(ServerLevel level) {
        if (store == null) {
            return;
        }
        List<ItemStack> got = new ArrayList<>();
        switch (job.kind()) {
            case TURRET -> {
                if (level.getBlockEntity(job.pos()) instanceof GunTurretBlockEntity t) {
                    got.addAll(BaseWarehouse.take(level, store, BaseWarehouse.Supply.ROUNDS, BaseUpkeep.roundsWanted(t)));
                }
            }
            case PURIFIER -> {
                if (level.getBlockEntity(job.pos()) instanceof SporeDeviceBlockEntity d) {
                    int r = BaseUpkeep.redstoneWanted(d);
                    if (r > 0) {
                        got.addAll(BaseWarehouse.take(level, store, BaseWarehouse.Supply.REDSTONE, r));
                    }
                    if (BaseUpkeep.filterWanted(d)) {
                        got.addAll(BaseWarehouse.take(level, store, BaseWarehouse.Supply.FILTER, 1));
                    }
                }
            }
            case FIX -> got.addAll(BaseWarehouse.take(level, store, BaseWarehouse.Supply.REPAIR, job.repairCost()));
        }
        carried.addAll(got);
        BaseUpkeep.carrying(eng, carried);
        eng.swing(InteractionHand.OFF_HAND);
        show();
    }

    private void toSite(ServerLevel level) {
        ladder = null;
        nudged = false;
        if (job.kind() == UpkeepJob.Kind.FIX) {
            nextFix(level);
            return;
        }
        if (job.kind() == UpkeepJob.Kind.PURIFIER) {
            int[] l = findLadder(level, job.pos());
            if (l != null) {
                ladder = new BlockPos(l[0], l[1], l[2]);
                ladderTop = l[3];
                go(Phase.TO_SITE, ladder);
                return;
            }
        }
        BlockPos stand = standNear(level, job.pos(), 2, -2, 1, null);
        if (stand == null) {
            BaseUpkeep.done(eng, job, false);
            reset();
            return;
        }
        nudged = false;
        go(Phase.TO_SITE, stand);
    }

    private void tickToSite(ServerLevel level) {
        if (job.kind() == UpkeepJob.Kind.FIX) {
            UpkeepJob.Fix f = currentFix();
            if (f == null) {
                finishWork(level);
                return;
            }
            if (reaches(f.pos())) {
                eng.getNavigation().stop();
                phase = Phase.WORK;
                workTicks = 0;
                return;
            }
            if (walk(dest, ARRIVE) && job != null) {
                if (!nudged) { // 立つ所の手前で止まって届かない: マスそのものへもう一度寄る
                    nudged = true;
                    go(Phase.TO_SITE, f.pos());
                    return;
                }
                trace("着いたのに届かない: マス {} 立つ所 {} いる所 {}", f.pos().toShortString(), dest.toShortString(), eng.position());
                BaseUpkeep.skipFix(eng, f); // このマスはしばらく飛ばす
                fixIndex++;
                nextFix(level);
            }
            return;
        }
        if (ladder != null) {
            if (walk(ladder, 1.5)) { // 経路は 1 マスの手前で止まる。登るときに x, z をはしごの真ん中へ寄せる
                eng.getNavigation().stop();
                eng.setNoGravity(true);
                phase = Phase.CLIMB_UP;
            }
            return;
        }
        if (reaches(job.pos())) {
            eng.getNavigation().stop();
            phase = Phase.WORK;
            workTicks = 0;
            return;
        }
        if (walk(dest, ARRIVE) && job != null) {
            if (!nudged) { // 立つ所の手前で止まって届かない: 設備そのものへもう一度寄る
                nudged = true;
                go(Phase.TO_SITE, job.pos());
                return;
            }
            trace("着いたのに届かない: 設備 {} いる所 {}", job.pos().toShortString(), eng.position());
            BaseUpkeep.done(eng, job, false);
            afterWork();
        }
    }

    private void tickClimb(boolean up) {
        eng.getNavigation().stop();
        eng.setNoGravity(true);
        double x = ladder.getX() + 0.5;
        double z = ladder.getZ() + 0.5;
        double top = ladderTop + 1.0;
        double y = up ? Math.min(top, eng.getY() + CLIMB_SPEED) : Math.max(ladder.getY(), eng.getY() - CLIMB_SPEED);
        eng.setPos(x, y, z);
        eng.setDeltaMovement(Vec3.ZERO);
        eng.fallDistance = 0.0F;
        eng.getLookControl().setLookAt(job.pos().getX() + 0.5, job.pos().getY() + 0.5, job.pos().getZ() + 0.5);
        if (up && y >= top - 1.0E-3) {
            phase = Phase.WORK;
            workTicks = 0;
        } else if (!up && y <= ladder.getY() + 1.0E-3) {
            eng.setNoGravity(false);
            ladder = null;
            afterWork();
        }
    }

    private void tickWork(ServerLevel level) {
        if (ladder != null) { // はしごの上: 動かない
            eng.setDeltaMovement(Vec3.ZERO);
            eng.fallDistance = 0.0F;
        }
        if (job.kind() == UpkeepJob.Kind.FIX) {
            tickFix(level);
            return;
        }
        BlockPos p = job.pos();
        eng.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        workTicks++;
        if (workTicks % 10 == 0) {
            eng.swing(InteractionHand.MAIN_HAND);
        }
        if (workTicks < SERVICE_TICKS) {
            return;
        }
        boolean ok = job.kind() == UpkeepJob.Kind.TURRET ? serviceTurret(level, p) : servicePurifier(level, p);
        BaseUpkeep.done(eng, job, ok);
        if (ladder != null) {
            phase = Phase.CLIMB_DOWN;
        } else {
            afterWork();
        }
    }

    /** 仕事を終えた後: 持ち帰る品(使用済みのフィルター・余り)があれば倉庫へ、無ければ終わり。 */
    private void afterWork() {
        job = null;
        if (carried.isEmpty()) {
            reset();
            show();
            return;
        }
        phase = Phase.RETURN;
        store = null;
        clearProgress();
    }

    private void finishWork(ServerLevel level) {
        BaseUpkeep.done(eng, job, true);
        afterWork();
    }

    private void tickReturn(ServerLevel level) {
        if (store == null) {
            store = BaseUpkeep.nearestStore(eng, null);
            if (store == null) {
                carried.clear(); // 倉庫が無い・読み込まれていない: 持ち物は失われる
                BaseUpkeep.carrying(eng, carried);
                reset();
                show();
                return;
            }
            dest = store.stand();
            clearProgress();
        }
        if (walk(dest, ARRIVE)) {
            for (Iterator<ItemStack> it = carried.iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                BaseWarehouse.put(level, store, s);
                it.remove(); // 入りきらない分は捨てる(倉庫がいっぱい)
            }
            BaseUpkeep.carrying(eng, carried);
            eng.swing(InteractionHand.OFF_HAND);
            reset();
            show();
        }
    }

    // ---------- 設備 ----------

    private boolean serviceTurret(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!(s.getBlock() instanceof GunTurretBlock block) || !(level.getBlockEntity(p) instanceof GunTurretBlockEntity t)) {
            return false;
        }
        if (s.getValue(GunTurretBlock.OVERGROWN)) {
            block.setOvergrown(level, p, s, false); // 素手で拭うのと同じ
        }
        int want = BaseUpkeep.roundsWanted(t);
        for (Iterator<ItemStack> it = carried.iterator(); it.hasNext() && want > 0; ) {
            ItemStack c = it.next();
            if (!BaseWarehouse.Supply.ROUNDS.is(c)) {
                continue;
            }
            for (int i = 0; i < t.getContainerSize() && !c.isEmpty() && want > 0; i++) {
                ItemStack slot = t.getItem(i);
                int room = slot.isEmpty() ? c.getMaxStackSize() : ItemStack.isSameItemSameComponents(slot, c) ? slot.getMaxStackSize() - slot.getCount() : 0;
                int move = Math.min(Math.min(room, c.getCount()), want);
                if (move <= 0) {
                    continue;
                }
                if (slot.isEmpty()) {
                    t.setItem(i, c.split(move));
                } else {
                    slot.grow(move);
                    c.shrink(move);
                }
                want -= move;
            }
            if (c.isEmpty()) {
                it.remove();
            }
        }
        t.setChanged();
        level.playSound(null, p, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 0.8F, 1.0F);
        BaseUpkeep.carrying(eng, carried);
        show();
        return true;
    }

    private boolean servicePurifier(ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof SporeDeviceBlockEntity d)) {
            return false;
        }
        if (BaseUpkeep.sporesWanted(d)) {
            d.setItem(SporeDeviceBlockEntity.SLOT_SPORE, ItemStack.EMPTY); // 取り出して捨てる(Claude 仮決め)
        }
        // レッドストーンの補給は 2026-10-24 に消した(除去装置は電池で動き、基地の除去装置は電池なしで動く)
        d.setBaseSupplied(true);
        if (BaseUpkeep.filterWanted(d)) {
            for (Iterator<ItemStack> it = carried.iterator(); it.hasNext(); ) {
                ItemStack c = it.next();
                if (!com.yayuy.overgrownvegetation.spore.SporeFilters.isUsable(c)) {
                    continue;
                }
                it.remove();
                ItemStack old = d.getItem(SporeDeviceBlockEntity.SLOT_FILTER);
                d.setItem(SporeDeviceBlockEntity.SLOT_FILTER, c);
                if (!old.isEmpty()) {
                    carried.add(old); // 使用済みは持ち帰って除染機で洗う(ユーザー指定)
                }
                break;
            }
        }
        d.setChanged();
        level.playSound(null, p, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 1.2F);
        BaseUpkeep.carrying(eng, carried);
        show();
        return true;
    }

    // ---------- 修理と解体 ----------

    @Nullable
    private UpkeepJob.Fix currentFix() {
        return fixIndex < job.fixes().size() ? job.fixes().get(fixIndex) : null;
    }

    /** 次の直すマスの近くの立てる場所へ向かう(届けばすぐ直す)。 */
    private void nextFix(ServerLevel level) {
        UpkeepJob.Fix f = currentFix();
        if (f == null) {
            finishWork(level);
            return;
        }
        if (reaches(f.pos())) {
            phase = Phase.WORK;
            workTicks = 0;
            return;
        }
        BlockPos stand = standNear(level, f.pos(), 3, -3, 2, job);
        if (stand == null) {
            trace("立てる所が無い: マス {}", f.pos().toShortString());
            BaseUpkeep.skipFix(eng, f); // 立てる所が無い: しばらく飛ばす
            fixIndex++;
            nextFix(level);
            return;
        }
        nudged = false;
        go(Phase.TO_SITE, stand);
    }

    private void tickFix(ServerLevel level) {
        UpkeepJob.Fix f = currentFix();
        if (f == null) {
            finishWork(level);
            return;
        }
        if (!reaches(f.pos())) {
            nextFix(level);
            return;
        }
        eng.getLookControl().setLookAt(f.pos().getX() + 0.5, f.pos().getY() + 0.5, f.pos().getZ() + 0.5);
        workTicks++;
        if (workTicks < DesignData.defense().baseUpkeepFixTicks()) {
            return;
        }
        workTicks = 0;
        fixIndex++;
        if (f.kind() == UpkeepJob.FixKind.RESTORE && !f.state().isAir() && eng.getBoundingBox().intersects(new AABB(f.pos()))) {
            trace("自分の立っている所: マス {} 体 {}", f.pos().toShortString(), eng.getBoundingBox());
            return; // 自分の立っている所は埋めない(次の見回りでまた拾う)
        }
        if (f.cost() > 0 && !consumeRepair(f.cost())) {
            return; // 補修材が足りない: 飛ばす
        }
        BaseSite site = BaseSites.get(level.getServer()).byId(job.siteId);
        if (site != null && BaseRepair.apply(level, site, f)) {
            eng.swing(InteractionHand.MAIN_HAND);
            BlockState shown = f.kind() == UpkeepJob.FixKind.REMOVE || f.kind() == UpkeepJob.FixKind.UNCOVER ? f.expected() : f.state();
            SoundType sound = shown.getSoundType(level, f.pos(), eng);
            level.playSound(null, f.pos(), f.kind() == UpkeepJob.FixKind.REMOVE ? sound.getBreakSound() : sound.getPlaceSound(),
                    SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            BaseUpkeep.fixed(eng);
        } else {
            trace("直せなかった: マス {} 今 {} 見比べたとき {}", f.pos().toShortString(), level.getBlockState(f.pos()), f.expected());
            if (f.cost() > 0) {
                carried.add(new ItemStack(com.yayuy.overgrownvegetation.ally.AllyItems.REPAIR_MATERIAL.get(), f.cost())); // 直さなかったので戻す
            }
        }
        BaseUpkeep.carrying(eng, carried);
        show();
    }

    private boolean consumeRepair(int n) {
        int have = 0;
        for (ItemStack c : carried) {
            if (BaseWarehouse.Supply.REPAIR.is(c)) {
                have += c.getCount();
            }
        }
        if (have < n) {
            return false;
        }
        for (Iterator<ItemStack> it = carried.iterator(); it.hasNext() && n > 0; ) {
            ItemStack c = it.next();
            if (!BaseWarehouse.Supply.REPAIR.is(c)) {
                continue;
            }
            int take = Math.min(n, c.getCount());
            c.shrink(take);
            n -= take;
            if (c.isEmpty()) {
                it.remove();
            }
        }
        return true;
    }

    // ---------- 歩く・立つ ----------

    /** {@code to} へ歩く(着いたら真)。近づけないまま {@value #STUCK_TICKS} tick たったら仕事をあきらめる。 */
    private boolean walk(BlockPos to, double arrive) {
        if (!to.equals(routedFor)) { // 行き先が変わった: 壁の外へ(から)なら正門を先に通る
            routedFor = to;
            route.clear();
            BlockPos[] gate = BaseUpkeep.gateFor(eng, to);
            if (gate != null) {
                route.add(gate[0]);
                route.add(gate[1]);
                trace("正門を通る: {} → {} → {}", gate[0].toShortString(), gate[1].toShortString(), to.toShortString());
            }
        }
        if (!route.isEmpty()) {
            BlockPos w = route.peekFirst();
            double dx = eng.getX() - (w.getX() + 0.5);
            double dz = eng.getZ() - (w.getZ() + 0.5);
            if (dx * dx + dz * dz <= 1.5 * 1.5) { // 門の前後は高さを見ない(外の地面の高さは建てたときの自然の地面で、計画から分からない)
                route.pollFirst();
                clearProgress();
                return false;
            }
            step(w, Math.sqrt(dx * dx + dz * dz));
            return false;
        }
        double dx = eng.getX() - (to.getX() + 0.5);
        double dz = eng.getZ() - (to.getZ() + 0.5);
        double dy = eng.getY() - to.getY();
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h <= arrive && Math.abs(dy) <= 1.5) {
            eng.getNavigation().stop();
            return true;
        }
        step(to, Math.sqrt(h * h + dy * dy));
        return false;
    }

    /** {@code to} へ経路を引き直しながら進む。近づけないまま {@value #STUCK_TICKS} tick たったらあきらめる。 */
    private void step(BlockPos to, double d) {
        // 歩き終えても届いていない(経路が途中までしか無い・見つからない)ときも、前回から 10 tick は空ける。前は毎 tick 探し直していた
        // (届かない行き先だと、あきらめるまでの STUCK_TICKS(240 tick)ずっと。2026-10-10、0.1.9 の前に直した)
        if (--pathCooldown <= 0 || (eng.getNavigation().isDone() && pathCooldown <= 40 - 10)) {
            eng.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
            pathCooldown = 40;
        }
        if (d < bestDist - 0.5) {
            bestDist = d;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            giveUp();
        }
    }

    private void trace(String msg, Object... args) {
        if (BaseUpkeep.trace) {
            com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[upkeep] " + eng.getId() + " " + msg, args);
        }
    }

    private void giveUp() {
        trace("あきらめた: {} 行き先 {} いる所 {} 仕事 {}", phase, dest == null ? null : dest.toShortString(), eng.position(),
                job == null ? null : job.kind() + "@" + job.pos().toShortString());
        eng.getNavigation().stop();
        if (job != null) {
            BaseUpkeep.done(eng, job, false);
        }
        job = null;
        if (carried.isEmpty() || phase == Phase.RETURN) {
            if (phase == Phase.RETURN) {
                carried.clear(); // 倉庫へ戻れない: 持ち物は失われる
                BaseUpkeep.carrying(eng, carried);
            }
            reset();
            show();
            return;
        }
        phase = Phase.RETURN;
        store = null;
        clearProgress();
    }

    /** 目から {@code p} の真ん中まで手が届くか。 */
    private boolean reaches(BlockPos p) {
        Vec3 eye = eng.getEyePosition();
        return eye.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= REACH * REACH;
    }

    /**
     * {@code at} の周り(横 {@code r}・上下 {@code dyMin}〜{@code dyMax})で立てるマス(足元に当たり判定・足と頭の 2 マスが空き・水なし)のうち、工兵に近いもの。
     * {@code job} の直すマスの上には立たない。
     */
    @Nullable
    private BlockPos standNear(Level level, BlockPos at, int r, int dyMin, int dyMax, @Nullable UpkeepJob job) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 target = Vec3.atCenterOf(at);
        for (int dy = dyMin; dy <= dyMax; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (p.equals(at) || !standable(level, p) || job != null && covers(job, p)) {
                        continue;
                    }
                    // 立つ所の真ん中から 1 マスずれて止まっても届く所だけ(経路は 1 マスの手前で止まる。10-04 のクライアントで 3 マス先を選んで届かなかった)
                    double reach = new Vec3(p.getX() + 0.5, p.getY() + eng.getEyeHeight(), p.getZ() + 0.5).distanceTo(target);
                    if (reach > REACH - 1.0) {
                        continue;
                    }
                    double d = reach * reach * 16.0 + eng.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) * 0.05;
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private static boolean covers(UpkeepJob job, BlockPos p) {
        for (UpkeepJob.Fix f : job.fixes()) {
            if (f.pos().equals(p) || f.pos().equals(p.above())) {
                return true;
            }
        }
        return false;
    }

    public static boolean standable(Level level, BlockPos p) {
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
            return false;
        }
        BlockPos below = p.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                && level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty();
    }

    /**
     * 設備 {@code device} のすぐ下の段の横 2 マス以内に、はしごの上の端があれば {x, 下の端の y, z, 上の端の y}(はしごしか無い除去塔、3 段以上)。
     */
    @Nullable
    public static int[] findLadder(Level level, BlockPos device) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos top = device.offset(dx, -1, dz);
                if (!level.getBlockState(top).is(Blocks.LADDER)) {
                    continue;
                }
                BlockPos b = top;
                while (level.getBlockState(b.below()).is(Blocks.LADDER)) {
                    b = b.below();
                }
                if (top.getY() - b.getY() >= 3) {
                    return new int[]{top.getX(), b.getY(), top.getZ(), top.getY()};
                }
            }
        }
        return null;
    }

    /** 運んでいる品を左手に見せる(1 つ目。拾えない)。 */
    private void show() {
        ItemStack s = carried.isEmpty() ? ItemStack.EMPTY : carried.get(0).copyWithCount(1);
        eng.setItemSlot(EquipmentSlot.OFFHAND, s);
        eng.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
    }
}
