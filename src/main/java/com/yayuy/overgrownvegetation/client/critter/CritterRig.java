package com.yayuy.overgrownvegetation.client.critter;

import java.util.ArrayList;
import java.util.List;

/**
 * 1 種ぶんの「動きの層」の骨の割り当て(2026-10-22、G2・G3)。骨の名前はモデルごとに違うので、種ごとに 1 つ作る。
 * 角度はすべて度。無い骨は黙って飛ばす(モデルを Blockbench で直して骨が消えても落ちない)。
 *
 * <pre>{@code
 * static final CritterRig RIG = CritterRig.create()
 *         .look(40, 25, "neck", 0.4F, "head", 0.6F)   // 首と頭で相手を見る(左右の最大・上下の最大)
 *         .body("body", 1.2F)                          // 坂で前後に傾く骨と、坂を測る長さ(ブロック)
 *         .roll("body", 1.0F, 12)                      // 曲がると内へ傾く(1 度/tick あたりの度・最大)
 *         .chain(1.0F, 0.6F, "tail1", "tail2", "tail3") // 尾は通ってきた道をなぞる(左右・上下の効き)
 *         .antennae(1.0F, "antenna_l", "antenna_r");   // 触角は遅れて揺れる
 * }</pre>
 */
public final class CritterRig {

    String[] lookBones = new String[0];
    float[] lookShare = new float[0];
    float lookMaxYaw;
    float lookMaxPitch;

    String pitchBone;
    float slopeLen = 1.0F;
    float slopeScale = 1.0F;
    float maxSlope = 30.0F;

    String rollBone;
    float rollPerRate;
    float maxRoll;

    /** 飛ぶ・泳ぐ種: 上下の速さで機首を上げ下げする(度 / (ブロック/tick))。 */
    float climbPitch;
    float maxClimb = 35.0F;

    final List<Chain> chains = new ArrayList<>();
    String[] antennae = new String[0];
    float antennaScale;

    LegSet legs;

    /** 体と鎖の上下の傾きを、通ってきた道ではなく真下の地面の高さで決める(地を歩く種。TerrainProbe)。 */
    boolean terrain;
    /** terrain のときに測る点のモデルの z(最初の描画で骨から作る)。 */
    float[] probeZ;

    /** 「今ちょうど道の上にいる」とみなすモデルの z(1/16 ブロック)。これより後ろの骨ほど古い道をなぞる。既定はエンティティの中心 0。 */
    float originZ;

    /** 待機で生きて見せる小さな揺れ(骨・軸・振れ・周期)。 */
    final List<Sway> sways = new ArrayList<>();

    private CritterRig() {
    }

    public static CritterRig create() {
        return new CritterRig();
    }

    /** 相手を見る骨と分け前(合計 1 にする)。 */
    public CritterRig look(float maxYaw, float maxPitch, Object... boneAndShare) {
        int n = boneAndShare.length / 2;
        lookBones = new String[n];
        lookShare = new float[n];
        for (int i = 0; i < n; i++) {
            lookBones[i] = (String) boneAndShare[i * 2];
            lookShare[i] = ((Number) boneAndShare[i * 2 + 1]).floatValue();
        }
        lookMaxYaw = maxYaw;
        lookMaxPitch = maxPitch;
        return this;
    }

    /** 坂で前後に傾ける骨。len は坂を測る前後の長さ(ブロック、体格 1)。 */
    public CritterRig body(String bone, float len) {
        pitchBone = bone;
        slopeLen = len;
        return this;
    }

    public CritterRig body(String bone, float len, float scale, float max) {
        body(bone, len);
        slopeScale = scale;
        maxSlope = max;
        return this;
    }

    /** 曲がるときに内側へ傾ける骨。 */
    public CritterRig roll(String bone, float perRate, float max) {
        rollBone = bone;
        rollPerRate = perRate;
        maxRoll = max;
        return this;
    }

    /** 飛ぶ・泳ぐ種の機首の上げ下げ。 */
    public CritterRig climb(float perSpeed, float max) {
        climbPitch = perSpeed;
        maxClimb = max;
        return this;
    }

    /** 前から後ろへ親子でつながった骨の列(尾・体節)。道をなぞる。 */
    public CritterRig chain(float yawGain, float pitchGain, String... bones) {
        chains.add(new Chain(bones, yawGain, pitchGain, false));
        return this;
    }

    /** 親子でなく、同じ親にぶら下がった骨の列(メガネウラの腹)。位置も動かして鎖のように見せる。 */
    public CritterRig flatChain(float yawGain, float pitchGain, String... bones) {
        chains.add(new Chain(bones, yawGain, pitchGain, true));
        return this;
    }

    public CritterRig antennae(float scale, String... bones) {
        antennae = bones;
        antennaScale = scale;
        return this;
    }

    /** 道をなぞる起点の z。長い体(アースロプレウラ)は頭の節にすると、頭が通った所を体が順にたどる。 */
    public CritterRig origin(float z) {
        originZ = z;
        return this;
    }

    /** 段差・坂で体と鎖を地面に沿わせる(真下の地面を測る)。 */
    public CritterRig terrain() {
        terrain = true;
        return this;
    }

    public CritterRig legs(LegSet set) {
        legs = set;
        return this;
    }

    /** 待機の揺れ。axis 0 = X, 1 = Y, 2 = Z。amp 度、period 秒、phase は周期の割合。 */
    public CritterRig sway(String bone, int axis, float amp, float period, float phase) {
        sways.add(new Sway(bone, axis, amp, period, phase));
        return this;
    }

    record Chain(String[] bones, float yawGain, float pitchGain, boolean flat) {
    }

    record Sway(String bone, int axis, float amp, float period, float phase) {
    }
}
