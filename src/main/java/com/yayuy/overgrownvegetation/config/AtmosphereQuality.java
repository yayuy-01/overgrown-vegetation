package com.yayuy.overgrownvegetation.config;

/**
 * 過繁茂の密林の雰囲気の重さの段(2026-10-30、作り直しの第 1 段)。割合は 10-23 にユーザーが承認した品質の段階の表
 * (退避した {@code QualityPreset} の「粒」「光の筋」の列)をそのまま使う。目安の GPU: 高 = RTX 4060 級、中 = GTX 1650 級、低 = 内蔵 GPU。
 */
public enum AtmosphereQuality {
    LOW(50, 30),
    MEDIUM(70, 60),
    HIGH(100, 100);

    /** 光る胞子の粒の量(%)。 */
    public final int particles;
    /** 光の筋・木漏れ日のマスの数(%)。 */
    public final int shafts;

    AtmosphereQuality(int particles, int shafts) {
        this.particles = particles;
        this.shafts = shafts;
    }
}
