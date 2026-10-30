#version 150

// Overgrown Vegetation: rings of vapour around cloud holes (client/CloudHoles.java). Keep this file ASCII-only.

uniform vec4 ColorModulator;
uniform vec4 Split;

in vec3 ovPos;
in vec4 ovColor;

out vec4 fragColor;

void main() {
    float dist = length(ovPos);
    if (Split.y > 0.5 ? dist < Split.x - 24.0 : (Split.y < -0.5 && dist >= Split.x)) {
        discard;
    }
    if (ovColor.a <= 0.003) {
        discard;
    }
    fragColor = ovColor * ColorModulator;
}
