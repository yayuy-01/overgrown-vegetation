#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the motes floating in the water (client/atmosphere/WaterMotes.java). Plain coloured cube faces in the
// underwater fog. Keep this file ASCII-only.

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec4 vertexColor;
in float vertexDistance;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
