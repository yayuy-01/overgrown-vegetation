package com.yayuy.overgrownvegetation.client.sky;

/** 今の雲の描き方を選ぶ所(2026-11-08。今はボクセルだけ。{@link CloudRenderer})。 */
public final class CloudRenderers {

    public static CloudRenderer current() {
        return VoxelClouds.INSTANCE;
    }

    private CloudRenderers() {
    }
}
