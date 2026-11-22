#version 150

// Overgrown Vegetation: the shelf cloud before a thunderstorm (arcus.vsh and client/atmosphere/ArcusCloud.java hold the explanation).
// Keep this file ASCII-only.
// ArcLit / ArcDark = the colour of a lit face and of the underside, ArcFog = the colour far clouds melt into,
// ArcFade = (how much everything is hidden by fog 0..1, how much of the cloud is there 0..1, distance where it starts to melt, where it is gone).
// The cloud is opaque and writes depth; it comes and goes through a screen-door pattern, not through alpha.

uniform vec4 ArcLit;
uniform vec4 ArcDark;
uniform vec4 ArcFog;
uniform vec4 ArcFade;

in vec4 ovShade;
in float ovDist;

out vec4 fragColor;

void main() {
    vec3 c = mix(ArcDark.rgb, ArcLit.rgb, ovShade.r) * (0.92 + 0.16 * ovShade.b);
    // Darker towards the back: the rain core behind the shelf.
    c *= mix(1.0, 0.7, smoothstep(0.25, 1.0, ovShade.g));
    float far = smoothstep(ArcFade.z, ArcFade.w, ovDist);
    c = mix(c, ArcFog.rgb, max(ArcFade.x, far * 0.8));
    float keep = ArcFade.y * (1.0 - smoothstep(ArcFade.w, ArcFade.w * 1.3, ovDist));
    float th = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    if (keep <= th * 0.999) {
        discard;
    }
    fragColor = vec4(c, 1.0);
}
