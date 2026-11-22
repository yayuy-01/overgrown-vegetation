#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the ambient spores (client/atmosphere/AmbientSpores.java). Plain coloured cube faces, in the height fog of the
// overgrown forest (ov_fog.glsl, computed per vertex) and vanilla fog like the terrain. Keep this file ASCII-only.

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec4 vertexColor;
in float vertexDistance;
in vec4 ovFog;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
