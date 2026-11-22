#version 150

// Overgrown Vegetation: shadows of the voxel clouds drifting over the land (client/atmosphere/CloudShadows.java holds the explanation).
// Back from parked/ on 2026-11-08 (clouds, stage 1). Keep this file ASCII-only.
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
// 11-08 (improvement 5): darker than the first 0.62, 0.67, 0.78, which hardly showed on the canopy or on a sunny plain.
// 11-08 finishing 3: a little darker again (0.50, 0.55, 0.68 still read faintly over the canopy).
const vec3 OV_CLOUD_SHADOW = vec3(0.44, 0.49, 0.63);

// How much cloud shadow falls here (0 = none). light = (block light, sky light) from 0 to 1. under = how much the fragment is
// underground by the cave map (ov_cave.glsl ovCaveUnder): no cloud shadow in caves, even where a shaft lets the sky light in.
float ovCloudAmount(vec3 pos, vec2 light, float under) {
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
    // 11-12: from 0.35 (was 0.55): under a tree the sky light drops a step or two, and the cloud shadow faded there, so the ground under
    // the tree stayed lighter than the open ground in the cloud shadow. Caves are kept out by `under`, not by the sky light.
    float sky = smoothstep(0.35, 0.8, light.y);
    float torch = 1.0 - 0.6 * smoothstep(0.45, 0.9, light.x);
    return cloud * sky * torch * (1.0 - clamp(under, 0.0, 1.0)) * OvCloudA.w;
}

// facing > 0: faces turned away from the sun lose less (they had little direct sun). canopyMul: the colour factor the canopy shade and
// the trunk band already put on this fragment (ov_light.glsl ovCanopyMul; vec3(1.0) where there is none).
// Both shades are the direct sun taken away, so they do not stack: the fragment takes the darker of the two (11-12, user report: under
// a cloud shadow the ground under a tree was brighter than around it. Before, the canopy shade took up to 9 tenths of the cloud shadow
// away, and the canopy shade, about 75 % brightness, is lighter than the cloud shadow, about 50 %).
vec3 ovCloudShadow(vec3 color, vec3 pos, vec3 n, float facing, vec2 light, vec3 canopyMul, float under) {
    float amount = ovCloudAmount(pos, light, under);
    if (amount <= 0.0) {
        return color;
    }
    float direct = facing > 0.0 ? 0.35 + 0.65 * smoothstep(-0.1, 0.4, dot(n, OvCloudC.xyz)) : 1.0;
    float k = clamp(amount * direct, 0.0, 1.0);
    vec3 cloud = mix(vec3(1.0), OV_CLOUD_SHADOW, k);
    // What is left to darken after the canopy shade, per channel (1 where the canopy shade is already the darker).
    return color * min(vec3(1.0), cloud / max(canopyMul, vec3(0.001)));
}
