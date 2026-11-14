#version 150

// Overgrown Vegetation: simple ambient occlusion, pass 1 (11-14, shader polish H; off by default; client/atmosphere/SimpleAo.java
// holds the explanation). Keep this file ASCII-only.
//
// Drawn into a half-size target from the depth of the screen alone. For every pixel: its position in view space, its normal (from the
// neighbouring depths), and 8 taps on a disc around it (the disc is AoA.x blocks wide in the world, turned by a per-pixel angle).
// A tap that rises above the tangent plane of the pixel shades it, less the farther it is (crevices, the foot of a trunk, the corner
// of a wall, grass against the ground). Sky pixels and pixels beyond AoA.z blocks get none.
// AoA = (radius in blocks, strength, distance where it has faded out, -)
// AoB = (projection m00, m11, m22, m32): view z = m32 / (ndc z + m22), view xy = ndc xy * z / (m00, m11).

uniform sampler2D DepthTex;
uniform vec4 AoA;
uniform vec4 AoB;

in vec2 texCoord;

out vec4 fragColor;

vec3 viewPos(vec2 uv) {
    float d = texture(DepthTex, uv).r;
    float z = AoB.w / (d * 2.0 - 1.0 + AoB.z);
    vec2 ndc = uv * 2.0 - 1.0;
    return vec3(ndc.x * z / AoB.x, ndc.y * z / AoB.y, -z);
}

void main() {
    float depth = texture(DepthTex, texCoord).r;
    if (depth >= 0.99999) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 p = viewPos(texCoord);
    float dist = -p.z;
    float fade = 1.0 - smoothstep(AoA.z * 0.6, AoA.z, dist);
    if (fade <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 texel = 1.0 / vec2(textureSize(DepthTex, 0));
    // The normal from the nearer of the two neighbours on each axis (keeps edges sharp).
    vec3 px1 = viewPos(texCoord + vec2(texel.x, 0.0)) - p;
    vec3 px0 = p - viewPos(texCoord - vec2(texel.x, 0.0));
    vec3 py1 = viewPos(texCoord + vec2(0.0, texel.y)) - p;
    vec3 py0 = p - viewPos(texCoord - vec2(0.0, texel.y));
    vec3 dx = abs(px1.z) < abs(px0.z) ? px1 : px0;
    vec3 dy = abs(py1.z) < abs(py0.z) ? py1 : py0;
    vec3 n = normalize(cross(dx, dy));
    // The disc in screen space: AoA.x blocks at this depth.
    vec2 radius = AoA.x * vec2(AoB.x, AoB.y) * 0.5 / dist;
    float angle = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) * 6.2831853;
    float occlusion = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = angle + float(i) * 0.7853982;
        float r = (float(i) + 0.5) / 8.0;
        vec2 uv = texCoord + vec2(cos(a), sin(a)) * radius * r;
        vec3 v = viewPos(uv) - p;
        float d2 = dot(v, v);
        float rise = dot(n, v) / sqrt(max(d2, 1.0e-6));
        // Above the tangent plane by more than a small bias, and within reach.
        occlusion += max(rise - 0.12, 0.0) / (1.0 + d2 / (AoA.x * AoA.x)) * step(d2, 4.0 * AoA.x * AoA.x);
    }
    float ao = clamp(occlusion / 8.0 * 2.2 * AoA.y, 0.0, 1.0) * fade;
    fragColor = vec4(ao, ao, ao, 1.0);
}
