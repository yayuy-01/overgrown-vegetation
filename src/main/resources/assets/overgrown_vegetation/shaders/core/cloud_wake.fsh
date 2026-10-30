#version 150

// Overgrown Vegetation: cloud dragged out of a cloud (client/CloudWake.java holds the explanation). Keep this file ASCII-only.
// Haze = (haze colour, blue haze setting) like the voxel clouds. Fade = (inside-a-cloud fade, -, -, -).

uniform vec4 ColorModulator;
uniform vec4 Split;
uniform vec4 Haze;
uniform vec4 Fade;

in vec3 ovPos;
in vec4 ovColor;

out vec4 fragColor;

void main() {
    float dist = length(ovPos);
    // The far pass reaches 24 blocks into the near range (the near pass draws over it), so no gap shows at the split.
    if (Split.y > 0.5 ? dist < Split.x - 24.0 : (Split.y < -0.5 && dist >= Split.x)) {
        discard;
    }
    float a = ovColor.a * (1.0 - Fade.x);
    if (a <= 0.003) {
        discard;
    }
    // Blue haze, the same as the voxel clouds.
    vec3 c = mix(ovColor.rgb, Haze.rgb, Haze.w * (0.45 * (1.0 - exp(-dist / 2000.0)) + 0.3 * smoothstep(2500.0, 7000.0, dist)));
    fragColor = vec4(min(c, vec3(1.0)), clamp(a, 0.0, 1.0)) * ColorModulator;
}
