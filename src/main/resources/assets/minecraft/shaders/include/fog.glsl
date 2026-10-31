#version 150

// Overgrown Vegetation: vanilla fog.glsl plus the spore haze of the overgrown forest (client/atmosphere/MistView.java holds the
// explanation). Keep this file ASCII-only.
//
// While the camera is in the forest, MistView sends a fog start below -1000 as a mark: the haze per block is
// k = (-start - 1000) / 100000 and the fog becomes 1 - (1 - wall) * exp(-k * distance), the wall being vanilla's smoothstep from
// end - clamp(end / 10, 4, 64) to end. Every shader that uses linear_fog (terrain, mobs, particles, clouds) then fades the same way,
// so mobs do not stand out clearly in the haze. Otherwise this is vanilla.

float ov_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (fogStart < -1000.0) {
        float k = (-fogStart - 1000.0) / 100000.0;
        float wallStart = fogEnd - clamp(fogEnd / 10.0, 4.0, 64.0);
        float wall = vertexDistance <= wallStart ? 0.0 : (vertexDistance < fogEnd ? smoothstep(wallStart, fogEnd, vertexDistance) : 1.0);
        return 1.0 - (1.0 - wall) * exp(-k * vertexDistance);
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
