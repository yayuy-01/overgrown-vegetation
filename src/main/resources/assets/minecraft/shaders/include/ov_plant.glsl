#version 150

// Overgrown Vegetation: this mod's plants in the cutout_mipped layer (client/PlantLayers.java holds the explanation).
// Keep this file ASCII-only.
//
// PlantLayers moves this mod's cutout plants into the cutout_mipped layer and adds a group to the vertex marker
// (code = 255 - alpha byte): 64 = thin plant (was cutout), 128 = undergrowth (also sinks with distance). The sway codes
// (0..63, ov_sway.glsl) stay below. Thin plants keep the look of the cutout layer: no facing-away shade, no leaf colours.
//
// OvPlantLod = (distance where the undergrowth starts to sink, distance where it is gone, -, -); y = 0 off.
uniform vec4 OvPlantLod;
// OvGlow = (multiplier of the block light of glowing faces, -, -, -): 1 at night, less by day (client/NightGlow.java).
uniform vec4 OvGlow;

// Group of a vertex colour: 0 = none, 1 = thin plant, 2 = undergrowth, 3 = glowing face (client/NightGlow.java; drawn
// like a thin plant, its baked block light follows the time of day).
float ovPlantGroup(vec4 color) {
    float mark = floor((1.0 - color.a) * 255.0 + 0.5);
    return mark > 191.5 ? 3.0 : (mark > 127.5 ? 2.0 : (mark > 63.5 ? 1.0 : 0.0));
}

// Lightmap colour of a vertex: glowing faces are dimmed by day. Lowering their block light would turn them amber (the
// lightmap is warm in the middle), so scale the whole colour instead, but never below the sky light of the same vertex
// (full = lightmap(block, sky), skyOnly = lightmap(0, sky)).
vec4 ovGlowLightmap(vec4 full, vec4 skyOnly, float group) {
    return group > 2.5 ? max(skyOnly, full * OvGlow.x) : full;
}

// The alpha with the group taken off (the sway marker alone, as ov_sway.glsl reads it).
float ovPlantAlpha(vec4 color, float group) {
    return color.a + group * (64.0 / 255.0);
}

// Undergrowth sinks into the ground of its block between OvPlantLod.x and .y (camera-relative pos, block-grid position
// local, the offset that makes local camera-relative). At the end every vertex sits a little under the ground of its
// block, so nothing is drawn.
vec3 ovPlantSink(vec3 pos, vec3 local, vec3 offset, float group) {
    if (group < 1.5 || group > 2.5 || OvPlantLod.y <= 0.0) {
        return pos;
    }
    float k = smoothstep(OvPlantLod.x, OvPlantLod.y, length(pos));
    if (k <= 0.0) {
        return pos;
    }
    vec3 cell = floor(local);
    vec3 ground = vec3(cell.x + 0.5, cell.y - 0.05, cell.z + 0.5) + offset;
    return mix(pos, ground, k);
}
