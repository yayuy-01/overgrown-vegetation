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

    /** 節の数 = 長さ(ブロック)× この数。 */
    public static final int SEGMENTS_PER_BLOCK = 4;

    private int organId;
    private int knockCooldown;
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
        if (knockCooldown > 0) {
            knockCooldown--;
            return;
        }
        // 先のまわりに来た人を払う
        float now = swayTime(); // 揺れはゲーム時刻で(クライアントの見た目と当たりを合わせる。11-18 見直し ②7。前は各自の tickCount)
        Vec3 tip = position().add(point(segments() * 2 / 3, now)), end = position().add(point(segments(), now));
        double reach = com.yayuy.overgrownvegetation.data.DesignData.organs().tentacleReach;
        AABB box = new AABB(tip, end).inflate(reach * 0.5D);
        for (Player p : server.getEntitiesOfClass(Player.class, box)) {
            if (p.isCreative() || p.isSpectator()) continue;
            Vec3 away = new Vec3(p.getX() - getX(), 0.0D, p.getZ() - getZ());
            if (away.lengthSqr() < 1.0E-4D) away = new Vec3(Math.cos(heading()), 0.0D, Math.sin(heading()));
            away = away.normalize();
            double k = com.yayuy.overgrownvegetation.data.DesignData.organs().tentacleKnockback;
            p.push(away.x * k, 0.35D * k, away.z * k);
            p.hurtMarked = true;
            knockCooldown = 30;
        }
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
