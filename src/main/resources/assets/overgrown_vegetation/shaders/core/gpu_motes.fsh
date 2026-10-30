#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: glowing spores in the light shafts (client/atmosphere/ShaftMotes.java). Keep this file ASCII-only.
// A soft round blob: bright in the middle, 0 at the rim (the CPU version drew an octagon of 8 triangles fading to the rim). Added on top.
// The glow is added light, so the height fog (ov_fog.glsl) and vanilla fog fade it out instead of tinting it.

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
    float seen = (1.0 - ovFog.a) * linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * (1.0 - r) * seen) * ColorModulator;
}
