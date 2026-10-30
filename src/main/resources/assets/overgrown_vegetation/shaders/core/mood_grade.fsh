#version 150

// Overgrown Vegetation: full-screen color grade (client/light/MoodPost.java). Keep this file ASCII-only.
// Lut: 256x64. Four 16x16x16 tables stacked vertically (0 outside, 1 forest, 2 climax, 3 night);
// inside a table, blue picks one of 16 slices laid out left to right, red is x and green is y in the slice.
// Weights = (outside, forest, climax, night). Grade = (lut on, vignette strength, aspect, grade strength).

uniform sampler2D Scene;
uniform sampler2D Lut;
uniform vec4 Weights;
uniform vec4 Grade;

in vec2 texCoord;

out vec4 fragColor;

vec3 lut(float row, vec3 c) {
    c = clamp(c, 0.0, 1.0) * 15.0;
    float b0 = floor(c.b);
    float b1 = min(b0 + 1.0, 15.0);
    float t = c.b - b0;
    float v = (row * 16.0 + c.g + 0.5) / 64.0;
    vec3 s0 = texture(Lut, vec2((b0 * 16.0 + c.r + 0.5) / 256.0, v)).rgb;
    vec3 s1 = texture(Lut, vec2((b1 * 16.0 + c.r + 0.5) / 256.0, v)).rgb;
    return mix(s0, s1, t);
}

void main() {
    vec3 src = texture(Scene, texCoord).rgb;
    vec3 col = src;
    if (Grade.x > 0.5) {
        vec3 graded = vec3(0.0);
        if (Weights.x > 0.001) graded += lut(0.0, src) * Weights.x;
        if (Weights.y > 0.001) graded += lut(1.0, src) * Weights.y;
        if (Weights.z > 0.001) graded += lut(2.0, src) * Weights.z;
        float sum = Weights.x + Weights.y + Weights.z;
        col = sum > 0.001 ? graded / sum : src;
        if (Weights.w > 0.001) col = mix(col, lut(3.0, src), Weights.w);
        col = mix(src, col, Grade.w);
    }
    if (Grade.y > 0.001) {
        vec2 d = (texCoord - 0.5) * vec2(Grade.z, 1.0);
        float r = length(d) / length(vec2(0.5 * Grade.z, 0.5));
        float v = smoothstep(0.35, 1.15, r);
        col *= 1.0 - Grade.y * v * v;
    }
    fragColor = vec4(col, 1.0);
}
