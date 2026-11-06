#version 150

// Overgrown Vegetation: vanilla fog.glsl plus the spore haze of the overgrown forest and the aerial perspective of the whole world
// (client/atmosphere/MistView.java holds the explanation). Keep this file ASCII-only.
//
// MistView sends a fog start below -1000 as a mark, the integer c = -start - 1000:
//   k = (c mod 10000) / 100000 = the spore haze per block while the camera is in the overgrown forest (0 outside),
//   h = floor(c / 10000) = A + 51 * S = the aerial perspective (11-06): strength a = A / 50 at the fog end, from s = S / 20 of the fog end.
// The fog becomes 1 - (1 - wall) * exp(-k * distance) * (1 - a * q * q), the wall being vanilla's smoothstep from
// end - clamp(end / 10, 4, 64) to end and q = (distance - s * end) / (end - s * end) (0 before the start, 1 at the end).
// Every shader that uses linear_fog (terrain, mobs, particles, clouds) then fades the same way, so mobs do not stand out clearly in
// the haze. Without the mark this is vanilla. The same decoding is in overgrown_vegetation/shaders/sodium/block_layer_opaque.fsh and in
// ov_cave.glsl (ovShadedFogColor, the haze part only).

float ov_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (fogStart < -1000.0) {
        float c = -fogStart - 1000.0;
        float h = floor((c + 0.5) / 10000.0);
        float k = (c - h * 10000.0) / 100000.0;
        float s = floor((h + 0.5) / 51.0);
        float a = (h - s * 51.0) / 50.0;
        s /= 20.0;
        float wallStart = fogEnd - clamp(fogEnd / 10.0, 4.0, 64.0);
        float wall = vertexDistance <= wallStart ? 0.0 : (vertexDistance < fogEnd ? smoothstep(wallStart, fogEnd, vertexDistance) : 1.0);
        float q = clamp((vertexDistance - s * fogEnd) / max(fogEnd * (1.0 - s), 1.0), 0.0, 1.0);
        return 1.0 - (1.0 - wall) * exp(-k * vertexDistance) * (1.0 - a * q * q);
    }
    if (vertexDistance <= fogStart) {
        return 0.0;
    }
    return vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
}

vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    float fogValue = ov_fog_value(vertexDistance, fogStart, fogEnd);
    if (fogValue <= 0.0) {
        return inColor;
    }
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float linear_fog_fade(float vertexDistance, float fogStart, float fogEnd) {
    return 1.0 - ov_fog_value(vertexDistance, fogStart, fogEnd);
}

float fog_distance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    } else {
        float distXZ = length(pos.xz);
        float distY = abs(pos.y);
        return max(distXZ, distY);
    }
}
