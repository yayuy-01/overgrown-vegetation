#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the motes of light at dusk (client/atmosphere/DuskMotes.java). A soft round point with a bright core,
// added on top. Added light: the height fog (ov_fog.glsl) and vanilla fog fade it out instead of tinting it. Keep this file ASCII-only.

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;

in vec2 local;
in vec4 vertexColor;
in float vertexDistance;
in vec4 ovFog;

out vec4 fragColor;

void main() {
    float r = length(local);
    if (r >= 1.0) {
        discard;
    }
    float core = exp(-r * r * 9.0) + 0.35 * (1.0 - r);
    float seen = (1.0 - ovFog.a) * linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * min(1.0, core) * seen) * ColorModulator;
}
