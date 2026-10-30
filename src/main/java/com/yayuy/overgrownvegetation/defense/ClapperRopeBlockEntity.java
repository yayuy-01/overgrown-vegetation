package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 鳴子の紐 1 本の中身(2026-10-15、サバイバル用アイテム ㊵)。目印のブロック({@link ClapperRopeBlock})は始点の柱のすぐ隣に置かれ、
 * 実際の紐は 2 本の柱の結び目の間に張られる(蔦の主アンカーと同じ考え方)。
 *
 * <ul>
 *     <li>持つもの: 始点・終点の柱、垂れ、吊った板の位置(紐の t の並び)。</li>
 *     <li>サーバー: 10 tick ごとに両端の柱が残っているか見て、無ければ紐を落とす。2 tick ごとに、紐か板に動いている物が触れたら
 *     {@link ClapperNetwork#ring} でつながった紐を全部鳴らす(スニークしているプレイヤー・観戦者・落ちている物は鳴らさない)。</li>
 *     <li>クライアント: 揺れ({@link ClapperRopePhysics})を毎 tick 進める。鳴った合図はブロックのイベント 1 番で届く。</li>
 * </ul>
 */
public class ClapperRopeBlockEntity extends BlockEntity {

    /** ブロックのイベント: 鳴った(クライアントで揺する)。 */
    static final int EVENT_RING = 1;
    /** 吊るした物が焚き火の煙に当たっているかを見る間隔(tick)。 */
    static final int SMOKE_STEP = 20;

    @Nullable
    private BlockPos source;
    @Nullable
    private BlockPos target;
    private float sag;
    private float[] boards = new float[0];
    /**
     * 吊るした物(2026-10-22、地脈技術の 2 段目。燻製器のレシピの入力を 1 つずつ。焚き火の真上の煙で燻す)。紐の t の順に並べる。
     * 場所取りは板と同じ決まり(板と合わせて {@link ClapperRope#maxBoards} まで・すぐ隣には吊れない)。
     */
    private final List<Hanging> hangings = new ArrayList<>();
    /** 次に鳴れるゲーム時刻(サーバーだけ。保存しない)。 */
    private long cooldownUntil;
    /** 壊したときに紐と板を落とすか(クリエイティブで壊したときは落とさない)。 */
    boolean dropOnRemove = true;

    /** 揺れ(クライアントだけで使う)。 */
    final ClapperRopePhysics physics = new ClapperRopePhysics();
    @Nullable
    private AABB boxCache;

    public ClapperRopeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLAPPER_ROPE.get(), pos, state);
    }

    // ------------------------------------------------------------------------------------------------
    // 中身
    // ------------------------------------------------------------------------------------------------

    public boolean hasRope() {
        return source != null && target != null;
    }

    @Nullable
    public BlockPos getSource() {
        return source;
    }

    @Nullable
    public BlockPos getTarget() {
        return target;
    }

    public float getSag() {
        return sag;
    }

    public float[] getBoards() {
        return boards;
    }

    public Vec3 start() {
        return ClapperRope.knotPoint(source);
    }

    public Vec3 end() {
        return ClapperRope.knotPoint(target);
    }

    public double length() {
        return hasRope() ? start().distanceTo(end()) : 0.0D;
    }

    /** 紐を張る(サーバー)。 */
    public void setRope(BlockPos source, BlockPos target) {
        if (level != null && hasRope()) {
            ClapperNetwork.unregister(level, worldPosition, this.source, this.target);
        }
        this.source = source.immutable();
        this.target = target.immutable();
        this.sag = ClapperRope.sagFor(Math.sqrt(source.distSqr(target)));
        this.boxCache = null;
        if (level != null) {
            ClapperNetwork.register(level, worldPosition, this.source, this.target);
        }
        changed();
    }

    /** 紐の t の位置に板を吊る(上限まで)。吊れたら true。 */
    public boolean addBoard(float t) {
        float clamped = clampT(t);
        if (!hasRoomAt(clamped)) {
            return false;
        }
        float[] next = Arrays.copyOf(boards, boards.length + 1);
        next[boards.length] = clamped;
        Arrays.sort(next);
        boards = next;
        changed();
        return true;
    }

    private static float clampT(float t) {
        return Math.max(0.05F, Math.min(0.95F, t));
    }

    /** t に板か吊るす物を 1 つ足せるか(板と吊るした物を合わせて上限まで・すぐ隣に既にあれば足せない)。 */
    private boolean hasRoomAt(float clamped) {
        if (!hasRope() || boards.length + hangings.size() >= ClapperRope.maxBoards(length())) {
            return false;
        }
        for (float b : boards) {
            if (Math.abs(b - clamped) * length() < 0.3D) {
                return false; // すぐ隣に既にある
            }
        }
        for (Hanging h : hangings) {
            if (Math.abs(h.t - clamped) * length() < 0.3D) {
                return false;
            }
        }
        return true;
    }

    /** 吊るした物 1 つ(紐の位置・品 1 個・煙に当たった tick)。 */
    public static final class Hanging {
        final float t;
        ItemStack stack;
        int smoke;

        Hanging(float t, ItemStack stack, int smoke) {
            this.t = t;
            this.stack = stack;
            this.smoke = smoke;
        }

        public float t() {
            return t;
        }

        public ItemStack stack() {
            return stack;
        }

        public int smoke() {
            return smoke;
        }
    }

    public List<Hanging> getHangings() {
        return hangings;
    }

    /** 紐の t の位置に {@code one}(1 個)を吊るす。吊るせたら true(サーバー)。 */
    public boolean addHanging(float t, ItemStack one) {
        float clamped = clampT(t);
        if (one.isEmpty() || !hasRoomAt(clamped)) {
            return false;
        }
        hangings.add(new Hanging(clamped, one.copyWithCount(1), 0));
        hangings.sort((a, b) -> Float.compare(a.t, b.t));
        changed();
        return true;
    }

    /** t のすぐ近く(0.4 ブロック以内)に吊るした物があれば外して返す。無ければ空。 */
    public ItemStack takeHanging(float t) {
        Hanging best = null;
        double bestGap = 0.4D;
        for (Hanging h : hangings) {
            double gap = Math.abs(h.t - t) * length();
            if (gap <= bestGap) {
                best = h;
                bestGap = gap;
            }
        }
        if (best == null) {
            return ItemStack.EMPTY;
        }
        hangings.remove(best);
        changed();
        return best.stack;
    }

    /** t のすぐ近くに吊るした物があるか(右クリックで外せるか)。 */
    public boolean hasHangingNear(float t) {
        for (Hanging h : hangings) {
            if (Math.abs(h.t - t) * length() <= 0.4D) {
                return true;
            }
        }
        return false;
    }

    /** 吊るした物の真ん中(燻されているかを見る点。揺れは入れない)。 */
    public Vec3 hangingPoint(Hanging h) {
        return ClapperRope.curvePoint(start(), end(), sag, h.t).subtract(0.0D, ClapperRope.HANG_ITEM_DROP, 0.0D);
    }

    /**
     * 焚き火の煙(2026-10-22): 点いた焚き火の煙の柱({@link ClapperRope#inCampfireSmoke})の中にある吊るした物を {@code ticks} だけ燻す。
     * {@link ClapperRope#SMOKE_TICKS} に達したら燻製器のレシピの出力に置き換える(経験値は出さない。ユーザー指定)。燻し上がった数を返す。
     * 初めは送風機で横に流した煙で燻したが、同じ日のユーザー指定で焚き火の真上だけにした(送風機は上昇気流機に作り替え)。
     */
    public int smoke(ServerLevel level, int ticks) {
        if (!hasRope() || hangings.isEmpty()) {
            return 0;
        }
        int done = 0;
        boolean dirty = false;
        for (Hanging h : hangings) {
            if (!ClapperRope.inCampfireSmoke(level, hangingPoint(h))) {
                continue;
            }
            var recipe = ClapperRope.smokingRecipe(level, h.stack).orElse(null);
            if (recipe == null) {
                continue; // もう燻し上がった物(燻製肉など)
            }
            h.smoke += ticks;
            dirty = true;
            if (h.smoke >= ClapperRope.SMOKE_TICKS) {
                ItemStack result = recipe.value().assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(h.stack), level.registryAccess());
                if (!result.isEmpty()) {
                    h.stack = result.copyWithCount(Math.max(1, result.getCount()));
                    done++;
                }
                h.smoke = 0;
            }
        }
        if (done > 0) {
            changed();
        } else if (dirty) {
            setChanged(); // 進み具合は保存だけ(毎回は送らない)
        }
        return done;
    }

    /** 壊したとき・切ったときに落とす物(紐 1 + 吊っていた板 + 吊るした物)。 */
    public List<ItemStack> drops() {
        List<ItemStack> out = new ArrayList<>();
        if (hasRope()) {
            out.add(new ItemStack(ModItems.CLAPPER_ROPE.get()));
        }
        if (boards.length > 0) {
            out.add(new ItemStack(ModItems.CLAPPER_BOARD.get(), boards.length));
        }
        for (Hanging h : hangings) {
            out.add(h.stack.copy());
        }
        return out;
    }

    /** 紐の真ん中(落とす・鳴らす位置)。 */
    public Vec3 middle() {
        return hasRope() ? ClapperRope.curvePoint(start(), end(), sag, 0.5D) : Vec3.atCenterOf(worldPosition);
    }

    /** 紐と板が占める範囲(描画・触れた物を探す)。 */
    public AABB ropeBox() {
        if (boxCache == null) {
            boxCache = hasRope()
                    ? new AABB(start(), end()).expandTowards(0.0D, -sag - ClapperRope.BOARD_DROP - ClapperRope.BOARD_HEIGHT, 0.0D).inflate(0.4D)
                    : new AABB(worldPosition);
        }
        return boxCache;
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * 描画用: 紐の点の位置(前の tick と今の tick の間を補間)。揺れの準備がまだなら張った時の形。
     */
    public void interpolatedPoints(float partialTick, double[] x, double[] y, double[] z) {
        int n = ClapperRope.SEGMENTS + 1;
        if (physics.isReady()) {
            for (int i = 0; i < n; i++) {
                x[i] = physics.ox[i] + (physics.px[i] - physics.ox[i]) * partialTick;
                y[i] = physics.oy[i] + (physics.py[i] - physics.oy[i]) * partialTick;
                z[i] = physics.oz[i] + (physics.pz[i] - physics.oz[i]) * partialTick;
            }
            return;
        }
        Vec3 a = start();
        Vec3 b = end();
        for (int i = 0; i < n; i++) {
            Vec3 p = ClapperRope.curvePoint(a, b, sag, (double) i / ClapperRope.SEGMENTS);
            x[i] = p.x;
            y[i] = p.y;
            z[i] = p.z;
        }
    }

    /** 描画用: {@code k} 枚目の板の振れ(紐と直角の向き・紐に沿った向き。補間済み)。 */
    public float[] swing(int k, float partialTick) {
        if (k >= physics.swingA.length) {
            return new float[]{0.0F, 0.0F};
        }
        return new float[]{physics.prevA[k] + (physics.swingA[k] - physics.prevA[k]) * partialTick,
                physics.prevB[k] + (physics.swingB[k] - physics.prevB[k]) * partialTick};
    }

    // ------------------------------------------------------------------------------------------------
    // 鳴らす
    // ------------------------------------------------------------------------------------------------

    /** 次に鳴れるゲーム時刻(GameTest・確かめ用)。 */
    public long cooldownUntil() {
        return cooldownUntil;
    }

    /** この紐を今鳴らす(間を置いていれば何もしない)。鳴らしたら true。 */
    boolean ringNow(ServerLevel level) {
        long now = level.getGameTime();
        if (now < cooldownUntil || !hasRope()) {
            return false;
        }
        cooldownUntil = now + ClapperNetwork.COOLDOWN_TICKS;
        level.blockEvent(worldPosition, getBlockState().getBlock(), EVENT_RING, 0);
        if (boards.length > 0) {
            Vec3 mid = middle();
            float volume = Math.min(1.6F, 0.8F + boards.length * 0.1F);
            level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON, SoundSource.BLOCKS, volume, 0.75F);
            level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BAMBOO_HIT, SoundSource.BLOCKS, volume, 1.3F);
        }
        return true;
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id == EVENT_RING) {
            if (level != null && level.isClientSide()) {
                physics.shake(level.random);
            }
            return true;
        }
        return super.triggerEvent(id, param);
    }

    // ------------------------------------------------------------------------------------------------
    // 毎 tick
    // ------------------------------------------------------------------------------------------------

    static void serverTick(Level level, BlockPos pos, BlockState state, ClapperRopeBlockEntity be) {
        if (!(level instanceof ServerLevel server) || !be.hasRope()) {
            return;
        }
        long time = level.getGameTime();
        if ((time + pos.asLong()) % 10L == 0L) {
            if (!ClapperRope.isPost(level.getBlockState(be.source)) || !ClapperRope.isPost(level.getBlockState(be.target))) {
                ClapperRopeBlock.cut(server, pos, true);
                return;
            }
        }
        if (!be.hangings.isEmpty() && (time + pos.asLong()) % SMOKE_STEP == 0L) {
            be.smoke(server, SMOKE_STEP);
        }
        if (time % 2L == 0L && time >= be.cooldownUntil && be.isTouched(server)) {
            ClapperNetwork.ring(server, pos);
        }
    }

    static void clientTick(Level level, BlockPos pos, BlockState state, ClapperRopeBlockEntity be) {
        if (!be.hasRope()) {
            return;
        }
        if (!be.physics.isReady()) {
            be.physics.reset(be.start(), be.end(), be.sag, be.boards.length);
        }
        be.physics.resizeBoards(be.boards.length);
        be.physics.tick(level, be.ropeBox(), be.boards, be.middle());
    }

    /** 動いている物が紐か板に触れているか(サーバー)。 */
    private boolean isTouched(ServerLevel level) {
        List<Entity> near = level.getEntities((Entity) null, ropeBox(), ClapperRopeBlockEntity::canRing);
        if (near.isEmpty()) {
            return false;
        }
        Vec3 a = start();
        Vec3 b = end();
        for (Entity e : near) {
            if (e.position().distanceToSqr(e.xo, e.yo, e.zo) < 1.0E-5D) {
                continue; // 止まっている物は鳴らさない
            }
            AABB eb = e.getBoundingBox().inflate(0.12D);
            for (int i = 1; i < ClapperRope.SEGMENTS; i++) {
                Vec3 p = ClapperRope.curvePoint(a, b, sag, (double) i / ClapperRope.SEGMENTS);
                if (eb.contains(p)) {
                    return true;
                }
            }
            for (float t : boards) {
                Vec3 p = ClapperRope.curvePoint(a, b, sag, t);
                AABB board = new AABB(p.x - 0.2D, p.y - ClapperRope.BOARD_DROP - ClapperRope.BOARD_HEIGHT, p.z - 0.2D,
                        p.x + 0.2D, p.y - ClapperRope.BOARD_DROP, p.z + 0.2D);
                if (eb.intersects(board)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 鳴らせる物: 動くもの全部(ユーザー指定)。ただしスニークしているプレイヤー・観戦者・落ちている物・経験値は除く。矢などの飛び道具は鳴らす。 */
    private static boolean canRing(Entity e) {
        if (!e.isAlive() || e.isSpectator()) {
            return false;
        }
        if (e instanceof Player p) {
            return !p.isShiftKeyDown();
        }
        return e instanceof net.minecraft.world.entity.LivingEntity || e instanceof Projectile
                || e instanceof net.minecraft.world.entity.vehicle.AbstractMinecart || e instanceof net.minecraft.world.entity.vehicle.Boat;
    }

    // ------------------------------------------------------------------------------------------------
    // 台帳・保存・同期
    // ------------------------------------------------------------------------------------------------

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && hasRope()) {
            ClapperNetwork.register(level, worldPosition, source, target);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && hasRope()) {
            ClapperNetwork.unregister(level, worldPosition, source, target);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level != null && hasRope()) {
            ClapperNetwork.unregister(level, worldPosition, source, target);
        }
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (hasRope()) {
            tag.put("Source", NbtUtils.writeBlockPos(source));
            tag.put("Target", NbtUtils.writeBlockPos(target));
            tag.putFloat("Sag", sag);
            int[] ts = new int[boards.length];
            for (int i = 0; i < boards.length; i++) {
                ts[i] = Math.round(boards[i] * 10000.0F);
            }
            tag.putIntArray("Boards", ts);
            if (!hangings.isEmpty()) {
                net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
                for (Hanging h : hangings) {
                    CompoundTag e = new CompoundTag();
                    e.putFloat("T", h.t);
                    e.putInt("Smoke", h.smoke);
                    e.put("Item", h.stack.save(registries));
                    list.add(e);
                }
                tag.put("Hangings", list);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        BlockPos oldSource = source;
        BlockPos oldTarget = target;
        source = NbtUtils.readBlockPos(tag, "Source").orElse(null);
        target = NbtUtils.readBlockPos(tag, "Target").orElse(null);
        sag = tag.getFloat("Sag");
        int[] ts = tag.getIntArray("Boards");
        boards = new float[ts.length];
        for (int i = 0; i < ts.length; i++) {
            boards[i] = ts[i] / 10000.0F;
        }
        hangings.clear();
        net.minecraft.nbt.ListTag list = tag.getList("Hangings", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            ItemStack stack = ItemStack.parseOptional(registries, e.getCompound("Item"));
            if (!stack.isEmpty()) {
                hangings.add(new Hanging(e.getFloat("T"), stack, e.getInt("Smoke")));
            }
        }
        boxCache = null;
        if (level != null && level.isClientSide()) {
            // 張り直された・板が増えたときは揺れの形を作り直す(板が増えただけなら揺れは続ける)。
            if (oldSource == null || !oldSource.equals(source) || oldTarget == null || !oldTarget.equals(target)) {
                if (oldSource != null && oldTarget != null) {
                    ClapperNetwork.unregister(level, worldPosition, oldSource, oldTarget);
                }
                if (hasRope()) {
                    ClapperNetwork.register(level, worldPosition, source, target);
                    physics.reset(start(), end(), sag, boards.length);
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
