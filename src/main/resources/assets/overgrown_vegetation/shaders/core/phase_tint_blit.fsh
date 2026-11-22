#version 150

// Overgrown Vegetation: the phase cue sky tint, pass 2 (client/atmosphere/PhaseCue.java). Keep this file ASCII-only.
// TintTex = the tint colour with its coverage in alpha, at half size (phase_tint.fsh). Drawn over the screen with the normal alpha blend.
// Two passes because the depth of the screen cannot be read while the screen itself is the draw target.

uniform sampler2D TintTex;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(TintTex, texCoord);
}
