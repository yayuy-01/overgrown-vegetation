#version 150

// VoxelClouds far pass after the terrain (client/sky/VoxelClouds.java; 2026-10-24, back from parked 2026-11-23). Keep this file ASCII-only.
// Writes the depth of the far clouds' own depth target: 1 where the main depth is empty (sky), 0 where terrain was drawn,
// so the far clouds (drawn with their own depth range) pass only on sky pixels and the hidden ones are rejected before shading.

uniform sampler2D MainDepth;

out vec4 fragColor;

void main() {
    float d = texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r;
    gl_FragDepth = d >= 1.0 ? 1.0 : 0.0;
    fragColor = vec4(0.0);
}
