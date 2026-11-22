// Overgrown Vegetation: the relief of a face from the light and dark of its texture (11-14, shader polish G; off by default,
// client/atmosphere/SurfaceBump.java sets the uniform). Keep this file ASCII-only.
//
// Bark, rock and planks are flat faces; under a low sun their grain should catch the light. Here the brightness of the texture is
// read as a height (lighter = higher): the slope of that height along the face, taken toward the sun, lightens the texels that face
// the sun and darkens the ones behind a ridge. Day and sky-lit faces only; near the camera only (it fades out with distance).
// It assumes 16 x 16 block textures on a 16-texel grid of the atlas (the neighbours of a texel are kept inside its 16 x 16 cell), so
// a resource pack with larger textures shows flat seams every 16 texels: that is why it is off by default.
//
// OvBump = (strength; 0 = off, distance where it has faded out, -, -). The light direction is OvSunDir of ov_light.glsl.
uniform vec4 OvBump;

float ovBumpHeight(sampler2D atlas, ivec2 p, ivec2 cell) {
    vec3 c = texelFetch(atlas, clamp(p, cell, cell + 15), 0).rgb;
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// uv = block atlas coordinates, pos = camera-relative position, n = face normal, sky = sky light of the fragment (0..1).
vec3 ovBump(vec3 color, sampler2D atlas, vec2 uv, vec3 pos, vec3 n, float sky) {
    if (OvBump.x <= 0.0) {
        return color;
    }
    float near = 1.0 - smoothstep(OvBump.y * 0.5, OvBump.y, length(pos));
    float lit = smoothstep(0.5, 0.9, sky);
    if (near <= 0.0 || lit <= 0.0) {
        return color;
    }
    // The directions of +u and +v on the face, in the world (from the screen derivatives).
    vec3 dpx = dFdx(pos);
    vec3 dpy = dFdy(pos);
    vec2 dux = dFdx(uv);
    vec2 duy = dFdy(uv);
    float det = dux.x * duy.y - duy.x * dux.y;
    if (abs(det) < 1.0e-12) {
        return color;
    }
    vec3 t = normalize((dpx * duy.y - dpy * dux.y) / det);
    vec3 b = normalize((dpy * dux.x - dpx * duy.x) / det);
    ivec2 size = textureSize(atlas, 0);
    ivec2 p = ivec2(uv * vec2(size));
    ivec2 cell = (p / 16) * 16;
    float hu = ovBumpHeight(atlas, p + ivec2(1, 0), cell) - ovBumpHeight(atlas, p - ivec2(1, 0), cell);
    float hv = ovBumpHeight(atlas, p + ivec2(0, 1), cell) - ovBumpHeight(atlas, p - ivec2(0, 1), cell);
    // The slope toward the light: positive where the surface rises toward the sun (that side is lit), negative behind a ridge.
    float slope = dot(t * hu + b * hv, OvSunDir.xyz);
    // A light along the normal shows no relief; a grazing light shows the most.
    float graze = 1.0 - abs(dot(n, OvSunDir.xyz));
    return color * clamp(1.0 + slope * OvBump.x * graze * near * lit, 0.6, 1.5);
}
