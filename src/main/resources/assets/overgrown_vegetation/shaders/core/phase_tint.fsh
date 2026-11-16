#version 150

// Overgrown Vegetation: the sky bleeding yellow-green when the world phase rises (11-16; client/atmosphere/PhaseCue.java holds the
// explanation). Keep this file ASCII-only.
//
// Pass 1, drawn into a half-size target (phase_tint_blit.fsh puts it over the screen). The colour goes on the far part only: sky pixels get all
// of it, terrain and clouds get it by their distance (none nearer than TintA.z blocks, 0.8 of it beyond TintA.w), the hand gets none.
// TintColor = (r, g, b, amount)
// TintA = (projection m22, m32, near distance, far distance): view z = m32 / (ndc z + m22).

uniform sampler2D DepthTex;
uniform vec4 TintColor;
uniform vec4 TintA;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthTex, texCoord).r;
    float mask = 1.0;
    if (depth < 0.99999) {
        float dist = TintA.y / (depth * 2.0 - 1.0 + TintA.x);
        mask = 0.8 * smoothstep(TintA.z, TintA.w, dist);
    }
    fragColor = vec4(TintColor.rgb, TintColor.a * mask);
}
