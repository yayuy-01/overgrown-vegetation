#version 150

// Overgrown Vegetation: glowing spores in the light shafts (client/GpuSpores.java). Keep this file ASCII-only.
// A soft round blob: bright in the middle, 0 at the rim (the CPU version drew an octagon of 8 triangles fading to the rim). Added on top.

uniform vec4 ColorModulator;

in vec2 local;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float r = length(local);
    if (r >= 1.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * (1.0 - r)) * ColorModulator;
}
