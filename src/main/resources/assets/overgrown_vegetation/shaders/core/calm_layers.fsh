#version 150

// Overgrown Vegetation: the layered haze of a calm day (11-16; client/atmosphere/CalmLayers.java holds the explanation).
// Keep this file ASCII-only.
//
// Pass 1, drawn into a half-size target from the depth of the screen (phase_tint_blit.fsh puts it over the screen with the normal alpha
// blend). On a windless day the air does not mix: thin sheets of haze hang at fixed heights. For every pixel: the ray from the camera to
// what is drawn there, and for each sheet the haze along that ray (a sheet is a Gaussian band in height, so the integral along a straight
// ray is a difference of two error functions). The sheets roll slowly by place. Things close to the camera stay clear.
// InvProjView = inverse of projection * view rotation (camera-relative world position from the screen position and depth)
// LayA = (camera y, height of the lowest sheet, gap between sheets, thickness of a sheet)
// LayB = (camera x, camera z, haze per block at the middle of a sheet, wave height in blocks)
// LayC = (distance where the haze starts to show, distance where it shows fully, most the haze may cover 0..1, amount 0..1)
// LayColor = (r, g, b, number of sheets), LaySun = (sun direction xyz, brightening towards the sun)

uniform sampler2D DepthTex;
uniform mat4 InvProjView;
uniform vec4 LayA;
uniform vec4 LayB;
uniform vec4 LayC;
uniform vec4 LayColor;
uniform vec4 LaySun;

in vec2 texCoord;

out vec4 fragColor;

// Abramowitz-Stegun 7.1.26, good to 1.5e-7.
float ovErf(float x) {
    float s = sign(x);
    float a = abs(x);
    float t = 1.0 / (1.0 + 0.3275911 * a);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-a * a);
    return s * y;
}

void main() {
    float depth = texture(DepthTex, texCoord).r;
    vec4 p = InvProjView * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 rel = p.xyz / p.w;
    float dist = length(rel);
    vec3 dir = rel / max(dist, 0.0001);
    // The sky and far things: the sheets end at a few hundred blocks, so the horizon is a soft band and not a wall.
    float len = min(depth >= 0.99999 ? 4096.0 : dist, 360.0);
    float thick = max(LayA.w, 0.25);
    float tau = 0.0;
    for (int i = 0; i < 3; i++) {
        if (float(i) >= LayColor.a) {
            break;
        }
        float fi = float(i);
        float h = LayA.y + LayA.z * fi;
        // The sheet rolls: its height where the ray would cross it (or at the end of the ray).
        float cross = abs(dir.y) > 0.0005 ? clamp((h - LayA.x) / dir.y, 0.0, len) : len;
        vec2 at = LayB.xy + dir.xz * cross;
        h += LayB.w * (sin(at.x * 0.031 + at.y * 0.017 + fi * 2.1) * 0.6 + sin(at.x * 0.011 - at.y * 0.023 + fi * 4.3) * 0.4);
        float y0 = (LayA.x - h) / thick;
        float y1 = (LayA.x + dir.y * len - h) / thick;
        float along;
        if (abs(dir.y) > 0.002) {
            along = thick * 0.8862269 * abs(ovErf(y1) - ovErf(y0)) / abs(dir.y);
        } else {
            along = exp(-y0 * y0) * len;
        }
        // Patches: a sheet is not even. Slow wide lighter and thicker parts by place.
        float patch = 0.75 + 0.25 * sin(at.x * 0.019 + fi * 1.3) * sin(at.y * 0.023 - fi * 0.7) + 0.2 * sin(at.x * 0.007 - at.y * 0.005 + fi);
        tau += LayB.z * along * max(patch, 0.0) * (1.0 - 0.18 * fi);
    }
    float haze = (1.0 - exp(-tau)) * LayC.z * LayC.w * smoothstep(LayC.x, LayC.y, len);
    float facing = max(dot(dir, LaySun.xyz), 0.0);
    vec3 color = min(LayColor.rgb * (1.0 + LaySun.w * facing * facing), vec3(1.0));
    fragColor = vec4(color, haze);
}
