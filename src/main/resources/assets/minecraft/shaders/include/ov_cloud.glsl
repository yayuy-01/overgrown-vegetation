#version 150

// Overgrown Vegetation: shadows of the voxel clouds drifting over the land (client/CloudShadows.java holds the explanation).
// Keep this file ASCII-only.
//
// OvCloudMap = cloud shadow map, one texel per cloud column (8 blocks), linear filtering (soft edges).
// OvCloudA = (sun.x / sun.y, sun.z / sun.y, shadow height relative to the camera, strength; 0 = off)
// OvCloudB = (map origin x, map origin z relative to the camera (wind drift included), map span in blocks, edge fade in blocks)
// OvCloudC = (sun direction, -)
uniform sampler2D OvCloudMap;
uniform vec4 OvCloudA;
uniform vec4 OvCloudB;
uniform vec4 OvCloudC;

// Grey-blue of a face that only gets sky light (the direct sun is behind a cloud).
const vec3 OV_CLOUD_SHADOW = vec3(0.62, 0.67, 0.78);

// Vertex stage: everything of the cloud shadow but the facing and the canopy shade (the cloud map is 8 blocks a texel, so it
// interpolates well; 2026-10-23). light = (block light, sky light) from 0 to 1. 0 = no cloud shadow here.
float ovCloudAmount(vec3 pos, vec2 light) {
    if (OvCloudA.w <= 0.0) {
        return 0.0;
    }
    vec2 q = pos.xz + OvCloudA.xy * (OvCloudA.z - pos.y) - OvCloudB.xy;
    if (q.x <= 0.0 || q.y <= 0.0 || q.x >= OvCloudB.z || q.y >= OvCloudB.z) {
        return 0.0;
    }
    float edge = smoothstep(0.0, OvCloudB.w, min(min(q.x, q.y), min(OvCloudB.z - q.x, OvCloudB.z - q.y)));
    float cloud = texture(OvCloudMap, q / OvCloudB.z).r * edge;
    if (cloud <= 0.002) {
        return 0.0;
    }
    float sky = smoothstep(0.55, 0.95, light.y);
    float torch = 1.0 - 0.6 * smoothstep(0.45, 0.9, light.x);
    return cloud * sky * torch * OvCloudA.w;
}

// Fragment stage. facing > 0: faces turned away from the sun lose less (they had little direct sun). canopyShade: how much the
// canopy already shades this fragment (no double shade).
vec3 ovCloudShadowApply(vec3 color, float amount, vec3 n, float facing, float canopyShade) {
    if (amount <= 0.0) {
        return color;
    }
    float direct = facing > 0.0 ? 0.35 + 0.65 * smoothstep(-0.1, 0.4, dot(n, OvCloudC.xyz)) : 1.0;
    float k = clamp(amount * direct * (1.0 - canopyShade), 0.0, 1.0);
    return color * mix(vec3(1.0), OV_CLOUD_SHADOW, k);
}

// Both stages at once (for shaders that do not split them).
vec3 ovCloudShadow(vec3 color, vec3 pos, vec3 n, float facing, vec2 light, float canopyShade) {
    return ovCloudShadowApply(color, ovCloudAmount(pos, light), n, facing, canopyShade);
}
