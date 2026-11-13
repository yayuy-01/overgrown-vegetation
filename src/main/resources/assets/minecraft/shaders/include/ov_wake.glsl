// Overgrown Vegetation: the lanes parted through the floating films (algae film, spore film, sea spore band) where something swam or
// sailed through (11-13; user: "like a trail left a little while where people or animals passed through water thick with floating
// plants", sample B "a thin lane with a few plants still left in it", closes in about a minute). client/atmosphere/FilmWakes.java bakes
// the map and sets the uniforms; the points come from the server (corruption/FilmTrails.java, the same trail the mobs follow).
// Keep this file ASCII-only.
//
// OvWakeMap: PER texels per block around the camera: r, g = tick of the stamp (low 16 bits), b = strength of the lane's cross section
//   at the texel (1 at its middle, 0 at its edge), a = 1 where stamped.
// OvWakeA   = (map origin x, z relative to the camera, map size in blocks, 1 = on)
// OvWakeB   = (now in ticks, low 16 bits + partial tick, lifetime in ticks, texels per block, -)
// OvWakeC   = (map origin x, z mod 1024, for the world-anchored pixel noise, -, -)
// OvWakeUV0..2 = (u0, v0, u1, v1) of the three film sprites (sheets of four drawings) in the block atlas.
// OvFilmLook = (camera x, z mod 4200, 1 = on, -) for the films' patchy look below.
uniform sampler2D OvWakeMap;
uniform vec4 OvWakeA;
uniform vec4 OvWakeB;
uniform vec4 OvWakeC;
uniform vec4 OvWakeUV0;
uniform vec4 OvWakeUV1;
uniform vec4 OvWakeUV2;
uniform vec4 OvFilmLook;

bool ovWakeInRect(vec2 uv, vec4 r) {
    return uv.x >= r.x && uv.y >= r.y && uv.x <= r.z && uv.y <= r.w;
}

float ovWakeHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// How open the lane is at the texel: strength minus the share of the lifetime gone (<= 0 = closed, -1 = never stamped).
float ovWakeTexel(ivec2 t) {
    int n = int(OvWakeA.z * OvWakeB.z);
    if (t.x < 0 || t.y < 0 || t.x >= n || t.y >= n) {
        return -1.0;
    }
    vec4 m = texelFetch(OvWakeMap, t, 0);
    if (m.a < 0.5) {
        return -1.0;
    }
    float t0 = floor(m.r * 255.0 + 0.5) + floor(m.g * 255.0 + 0.5) * 256.0;
    float age = mod(OvWakeB.x - t0 + 65536.0, 65536.0);
    return m.b - age / OvWakeB.y;
}

// The films' own patchy look (11-13; user: "so they don't look like one pattern repeated"): thick and thin patches over a few blocks
// (thin ones lose pixels in small clumps) and a slow drift of colour, greener / yellower and lighter / darker. The four drawings and
// their rotations are picked per block by the blockstate; this is the larger scale. Values are provisional.
// Value noise anchored to the world, periodic in OvFilmLook's 4200-block wrap (every scale divides 4200).
float ovFilmNoise(vec2 w, float scale, float seed) {
    float period = 4200.0 / scale;
    vec2 p = w / scale;
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    vec2 j = mod(i + 1.0, period) + seed;
    i = mod(i, period) + seed;
    return mix(mix(ovWakeHash(i), ovWakeHash(vec2(j.x, i.y)), f.x),
               mix(ovWakeHash(vec2(i.x, j.y)), ovWakeHash(j), f.x), f.y);
}

// Returns (1 = discard, colour factor for a film pixel that stays). wc = the pixel's centre in world blocks (mod 4200), px = the pixel.
vec4 ovFilmPatch(vec2 wc, vec2 px) {
    float dens = ovFilmNoise(wc, 5.0, 11.0) * 0.65 + ovFilmNoise(wc, 2.0, 23.0) * 0.35;
    float thin = (1.0 - smoothstep(0.25, 0.55, dens)) * 0.55;
    float fine = ovFilmNoise(wc, 0.25, 37.0) * 0.7 + ovWakeHash(mod(px, 67200.0) + 41.0) * 0.3;
    if (fine < thin) {
        return vec4(1.0);
    }
    float hue = ovFilmNoise(wc, 7.0, 53.0) - 0.5;
    float br = 0.9 + ovFilmNoise(wc, 4.0, 67.0) * 0.2;
    return vec4(0.0, br * (1.0 + hue * 0.35), br, br * (1.0 - hue * 0.3));
}

// uv = the fragment's block atlas coordinates, pos = its position relative to the camera.
// Returns (1 = discard this pixel of the film, colour factor for a pixel that stays).
vec4 ovFilmWake(vec2 uv, vec3 pos) {
    if (!(ovWakeInRect(uv, OvWakeUV0) || ovWakeInRect(uv, OvWakeUV1) || ovWakeInRect(uv, OvWakeUV2))) {
        return vec4(0.0, 1.0, 1.0, 1.0);
    }
    vec4 look = vec4(0.0, 1.0, 1.0, 1.0);
    if (OvFilmLook.z > 0.0) {
        vec2 w = OvFilmLook.xy + pos.xz;
        vec2 wpx = floor(w * 16.0);
        look = ovFilmPatch((wpx + 0.5) / 16.0, wpx);
        if (look.x > 0.5) {
            return look;
        }
    }
    if (OvWakeA.w <= 0.0) {
        return look;
    }
    vec2 b = pos.xz - OvWakeA.xy;
    if (b.x < 0.0 || b.y < 0.0 || b.x >= OvWakeA.z || b.y >= OvWakeA.z) {
        return look;
    }
    // One decision per pixel of the film's texture (16 per block), anchored to the world.
    vec2 px = floor(b * 16.0);
    vec2 c = (px + 0.5) / 16.0 * OvWakeB.z - 0.5;
    ivec2 i0 = ivec2(floor(c));
    vec2 f = c - floor(c);
    float v = mix(mix(ovWakeTexel(i0), ovWakeTexel(i0 + ivec2(1, 0)), f.x),
                  mix(ovWakeTexel(i0 + ivec2(0, 1)), ovWakeTexel(i0 + ivec2(1, 1)), f.x), f.y);
    if (v < -0.4) {
        return look;
    }
    vec2 w = px + OvWakeC.xy * 16.0;
    // Ragged edge in clumps of 2 x 2 pixels.
    v += (ovWakeHash(floor(w * 0.5)) - 0.5) * 0.16;
    if (v > 0.0) {
        // Inside the lane a few plants are left (sample B), more toward its edge and as it closes.
        float keep = 0.12 + 0.45 * (1.0 - smoothstep(0.0, 0.45, v));
        return ovWakeHash(w + 17.0) < keep ? vec4(0.0, look.yzw * 0.85) : vec4(1.0);
    }
    // Just outside the lane the plants pushed aside lie thicker: a little darker.
    return v > -0.12 ? vec4(0.0, look.yzw * 0.8) : look;
}
