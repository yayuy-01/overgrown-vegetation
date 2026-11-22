package com.yayuy.overgrownvegetation.organ;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 器官の動く蔦の触手(2026-11-17。ユーザー決定「蔦の触手が動く」、0.2.0 に入れる)。器官の幹から外へ伸びて垂れ、ゆっくり揺れる。
 * 先のまわり({@code tentacles.reach})に来た人を払い落とす(はね飛ばし)。斬ると垂れて止まり、{@code reviveSeconds} 後にまた動く
 * (11-17 深夜ユーザー「壊せるが復活する」)。器官が枯れると垂れたまま止まる(器官の記録を 5 秒ごとに見る)。
 * 器官の記録から消えたら消える。
 *
 * <p>形は節の並び({@link #point})で決まり、描画も当たりも同じ式を使う。見た目は仮で、汚染の幹のブロックを小さくして並べる
 * ({@code client/OrganTentacleRenderer})。後でモデルに替えられるよう、形の式はここ 1 か所。
 */
public class OrganTentacle extends Entity {

    private static final EntityDataAccessor<Integer> LENGTH = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> WITHERED = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> HEADING = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> CUT = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.BOOLEAN);
    /** 叩く(当たりの始まり)のゲーム時刻。-1 = まだ叩いていない。終わっても残す(巻き戻りの見た目に使う)。 */
    static final EntityDataAccessor<Long> STRIKE_AT = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.LONG);
    /** 合図 | 当たり << 8 | 巻き戻り << 16(tick。サーバーの設計値をクライアントへ)。 */
    static final EntityDataAccessor<Integer> TIMING = SynchedEntityData.defineId(OrganTentacle.class, EntityDataSerializers.INT);

    /** 節の数 = 長さ(ブロック)× この数。 */
    public static final int SEGMENTS_PER_BLOCK = 4;

    private int organId;
    /** この一振りで払った人(1 振り 1 回)。 */
    private final java.util.Set<java.util.UUID> struck = new java.util.HashSet<>();
    /** 斬られた傷の合計(health に届くと垂れる)と、また動き出す時刻。 */
    private float wounds;
    private long cutUntil;

    public OrganTentacle(EntityType<? extends OrganTentacle> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    /** 器官の幹に付ける。{@code heading} = 外へ伸びる向き(ラジアン、+X から +Z へ)。 */
    public void setup(int organId, int length, float heading) {
        this.organId = organId;
        entityData.set(LENGTH, Math.max(3, length));
        entityData.set(HEADING, heading);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LENGTH, 10);
        builder.define(WITHERED, false);
        builder.define(HEADING, 0.0F);
        builder.define(CUT, false);
        builder.define(STRIKE_AT, -1L);
        builder.define(TIMING, 6 | 4 << 8 | 10 << 16);
    }

    // ---------------------------------------------------------------- 叩く合図(11-20 ユーザー「叩く直前に巻きがほどける短い合図を入れ、ほどけた瞬間だけ当てる」)

    public long strikeAt() {
        return entityData.get(STRIKE_AT);
    }

    public int windupTicks() {
        return entityData.get(TIMING) & 0xFF;
    }

    public int hitTicks() {
        return entityData.get(TIMING) >> 8 & 0xFF;
    }

    public int recoverTicks() {
        return entityData.get(TIMING) >> 16 & 0xFF;
    }

    /**
     * 巻きのほどけ具合(描画用。partialTick 込みで滑らか)。0 = 巻いている、1 = ほどけて伸びた({@link #point} の形 = 当たりの形)。
     * 合図の間に 0→1(直線)、当たりの間は 1、巻き戻りの間に 1→0。
     */
    public float unfurl(float partialTick) {
        return unfurlAt(level().getGameTime() + partialTick);
    }

    /** ゲーム時刻 {@code now}(tick、小数あり)でのほどけ具合。 */
    public float unfurlAt(double now) {
        long at = strikeAt();
        if (at < 0L) return 0.0F;
        int w = Math.max(1, windupTicks()), h = hitTicks(), r = Math.max(1, recoverTicks());
        double d = now - at;
        if (d < -w) return 0.0F;
        if (d < 0.0D) return (float) (1.0D + d / w);
        if (d < h) return 1.0F;
        if (d < h + r) return (float) (1.0D - (d - h) / r);
        return 0.0F;
    }

    /** 当たりの間か(ほどけて伸びた瞬間だけ当てる)。 */
    public boolean striking(long now) {
        long at = strikeAt();
        return at >= 0L && now >= at && now < at + hitTicks();
    }

    /** 叩く範囲(ほどけて伸びた形の、節 2/3 と先のまわり)。 */
    public AABB strikeBox(long now) {
        float t = (float) (now % 100_000L); // swayTime と同じ時刻
        Vec3 tip = position().add(point(segments() * 2 / 3, t)), end = position().add(point(segments(), t));
        double reach = com.yayuy.overgrownvegetation.data.DesignData.organs().tentacleReach;
        return new AABB(tip, end).inflate(reach * 0.5D);
    }

    private static int ticks(double seconds) {
        return Math.max(1, Math.min(255, (int) Math.round(seconds * 20.0D)));
    }

    /**
     * 1 tick ぶんの叩き(サーバー)。{@code near} = 叩く範囲のまわりの人(GameTest は偽の人を渡す)。
     * 休み → 範囲に人が来たら合図を始める(STRIKE_AT = 今 + 合図)→ 合図の間は当てない → 当たりの間だけ範囲の人を払う(1 振り 1 回)
     * → 巻き戻り・次まで(cooldownSeconds)は始めない。
     */
    void strikeStep(long now, java.util.List<? extends Player> near) {
        OrganTable t = com.yayuy.overgrownvegetation.data.DesignData.organs();
        long at = strikeAt();
        if (at >= 0L && now < at) return; // 合図の間(巻きがほどけている)は当てない
        AABB box = strikeBox(now);
        if (striking(now)) {
            for (Player p : near) {
                if (p.isCreative() || p.isSpectator() || !p.getBoundingBox().intersects(box) || !struck.add(p.getUUID())) continue;
                Vec3 away = new Vec3(p.getX() - getX(), 0.0D, p.getZ() - getZ());
                if (away.lengthSqr() < 1.0E-4D) away = new Vec3(Math.cos(heading()), 0.0D, Math.sin(heading()));
                away = away.normalize();
                double k = t.tentacleKnockback;
                p.push(away.x * k, 0.35D * k, away.z * k);
                p.hurtMarked = true;
            }
            return;
        }
        if (at >= 0L && now < at + hitTicks() + ticks(t.tentacleCooldownSeconds)) return; // 次まで
        for (Player p : near) {
            if (p.isCreative() || p.isSpectator() || !p.getBoundingBox().intersects(box)) continue;
            struck.clear();
            entityData.set(TIMING, ticks(t.tentacleWindupSeconds) | ticks(t.tentacleHitSeconds) << 8 | ticks(t.tentacleRecoverSeconds) << 16);
            entityData.set(STRIKE_AT, now + windupTicks());
            return;
        }
    }

    public int length() {
        return entityData.get(LENGTH);
    }

    public boolean withered() {
        return entityData.get(WITHERED);
    }

    /** 斬られて垂れている間。 */
    public boolean cut() {
        return entityData.get(CUT);
    }

    /** 垂れて動かない(枯れた・斬られた)。 */
    public boolean limp() {
        return withered() || cut();
    }

    public float heading() {
        return entityData.get(HEADING);
    }

    public int organId() {
        return organId;
    }

    /** 揺れの時刻(ゲーム時刻。サーバーとクライアントで同じ。float の細かさを保つため 100000 tick で巻き戻す)。 */
    public float swayTime() {
        return (float) (level().getGameTime() % 100_000L);
    }

    /**
     * 節 {@code i}(0 = 付け根)の、付け根からのずれ。外へ弧を描いて出て、先は垂れる。生きている間は揺れる(時刻 {@code t} tick)。
     * 枯れたらまっすぐ垂れて止まる。
     */
    public Vec3 point(int i, float t) {
        int n = length() * SEGMENTS_PER_BLOCK;
        double s = i / (double) Math.max(1, n);           // 0〜1
        double len = length();
        double out = len * 0.55D * Math.sin(s * Math.PI * 0.5D);      // 外へ
        double down = len * (limp() ? 0.95D : 0.75D) * s * s;     // 垂れる
        double sway = limp() ? 0.0D : Math.sin(t * 0.045D + getId() * 1.7D + s * 3.0D) * 1.6D * s;
        double curl = limp() ? 0.0D : Math.sin(t * 0.031D + getId()) * 0.35D * s;
        double h = heading() + curl;
        double ox = Math.cos(h) * out - Math.sin(h) * sway, oz = Math.sin(h) * out + Math.cos(h) * sway;
        return new Vec3(ox, -down + (limp() ? 0.0D : Math.sin(t * 0.06D + getId()) * 0.5D * s), oz);
    }

    public int segments() {
        return length() * SEGMENTS_PER_BLOCK;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) return;
        if (tickCount % 100 == 0) {
            ForestOrgans.Organ o = ForestOrgans.get(server).byId(organId);
            if (o == null) {
                discard();
                return;
            }
            if (o.state == ForestOrgans.State.WITHERED && !withered()) entityData.set(WITHERED, true);
        }
        if (withered()) return;
        if (cut()) {
            if (server.getGameTime() >= cutUntil) {
                entityData.set(CUT, false); // また動き出す
                wounds = 0.0F;
            }
            return;
        }
        // 先のまわりに来た人を、合図(巻きがほどける)の後に払う。揺れはゲーム時刻で(クライアントの見た目と当たりを合わせる。11-18 見直し ②7)
        long now = server.getGameTime();
        strikeStep(now, server.getEntitiesOfClass(Player.class, strikeBox(now)));
    }

    @Override
    public boolean isPickable() {
        return !withered();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 11-17 深夜ユーザー: 壊せるが復活する。斬ると垂れて止まり、しばらくしてまた動く(登る道を切り開く)。枯れたら垂れたまま
        if (!(level() instanceof ServerLevel server) || withered() || cut() || isInvulnerableTo(source)) return false;
        wounds += amount;
        if (wounds >= com.yayuy.overgrownvegetation.data.DesignData.organs().tentacleHealth) {
            entityData.set(CUT, true);
            entityData.set(STRIKE_AT, -1L); // 合図の途中で斬られたら叩かない
            cutUntil = server.getGameTime() + (long) (com.yayuy.overgrownvegetation.data.DesignData.organs().tentacleReviveSeconds * 20.0D);
            server.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.WOOD_BREAK, net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 0.7F);
        }
        return true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128.0D * 128.0D;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        double r = length() + 2.0D;
        return new AABB(getX() - r, getY() - r, getZ() - r, getX() + r, getY() + 2.0D, getZ() + r);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        organId = tag.getInt("Organ");
        entityData.set(LENGTH, Math.max(3, tag.getInt("Length")));
        entityData.set(WITHERED, tag.getBoolean("Withered"));
        entityData.set(HEADING, tag.getFloat("Heading"));
        entityData.set(CUT, tag.getBoolean("Cut"));
        cutUntil = tag.getLong("CutUntil");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Organ", organId);
        tag.putInt("Length", length());
        tag.putBoolean("Withered", withered());
        tag.putFloat("Heading", heading());
        tag.putBoolean("Cut", cut());
        tag.putLong("CutUntil", cutUntil);
    }

    /** 付け根から外へ向いた角度(器官の真ん中から見て)。 */
    static float headingFrom(double dx, double dz) {
        return (float) Mth.atan2(dz, dx);
    }
}
