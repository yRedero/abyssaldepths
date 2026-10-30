#version 150

// The shadow of this world (Parallax): outlines of everything glow purple (where the depth jumps: shapes,
// creatures, the sky's edge; weaker where the colour jumps), the colours drained, dark and cold, the dark
// leaning violet, the outlines pulsing slowly, colour fringes towards the edges, a vignette and grain.
// Amount blends it in during the crossing.

uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform vec2 OutSize;
uniform float Time;
uniform float Amount;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

const float NEAR = 0.05;
const float FAR = 1024.0;
const vec3 OUTLINE = vec3(0.78, 0.32, 1.0);

float depthAt(vec2 uv) {
    float z = texture(DepthSampler, uv).r * 2.0 - 1.0;
    return 2.0 * NEAR * FAR / (FAR + NEAR - z * (FAR - NEAR));
}

float depthEdgeAt(vec2 apart) {
    float w = 1.0 / depthAt(texCoord);
    float wx = abs(1.0 / depthAt(texCoord - vec2(apart.x, 0.0)) + 1.0 / depthAt(texCoord + vec2(apart.x, 0.0)) - 2.0 * w);
    float wy = abs(1.0 / depthAt(texCoord - vec2(0.0, apart.y)) + 1.0 / depthAt(texCoord + vec2(0.0, apart.y)) - 2.0 * w);
    return clamp((wx + wy) / w * 12.0 - 0.1, 0.0, 1.0);
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 centred = texCoord - 0.5;
    // colour fringes, growing towards the edges
    vec2 split = centred * 0.008;
    vec3 base = vec3(texture(DiffuseSampler, texCoord + split).r, texture(DiffuseSampler, texCoord).g, texture(DiffuseSampler, texCoord - split).b);
    vec3 plain = texture(DiffuseSampler, texCoord).rgb;

    // outlines where surfaces meet or end: the second difference of the inverse depth (zero across any
    // flat surface, however slanted), relative to it; close for the line, further out for a glow round it
    float depthEdge = depthEdgeAt(oneTexel * 2.0);
    float glow = depthEdgeAt(oneTexel * 5.0) * 0.45;

    // weaker outlines where the brightness jumps (texture detail)
    float lx = luma(texture(DiffuseSampler, texCoord + vec2(oneTexel.x * 2.0, 0.0)).rgb) - luma(texture(DiffuseSampler, texCoord - vec2(oneTexel.x * 2.0, 0.0)).rgb);
    float ly = luma(texture(DiffuseSampler, texCoord + vec2(0.0, oneTexel.y * 2.0)).rgb) - luma(texture(DiffuseSampler, texCoord - vec2(0.0, oneTexel.y * 2.0)).rgb);
    float colourEdge = clamp(length(vec2(lx, ly)) * 2.5 - 0.2, 0.0, 1.0) * 0.35;

    float edge = max(depthEdge, colourEdge);

    // drained, dark and cold; the dark leans violet
    float l = luma(base);
    vec3 drained = mix(vec3(l), base, 0.15) * vec3(0.52, 0.47, 0.6);
    drained = pow(drained, vec3(1.2));
    drained += vec3(0.07, 0.01, 0.11) * (1.0 - l);

    // the outlines pulse slowly, a wave running down the screen
    float pulse = 0.8 + 0.2 * sin(Time * 6.2831853 + texCoord.y * 9.0);
    vec3 colour = drained + OUTLINE * glow * pulse * 0.6;
    colour = mix(colour, OUTLINE * 1.25 * pulse, edge);

    // vignette and grain
    float vignette = smoothstep(0.85, 0.3, length(centred * vec2(1.0, 0.85)));
    colour *= mix(0.45, 1.0, vignette);
    float grain = fract(sin(dot(texCoord * OutSize + Time * 61.0, vec2(12.9898, 78.233))) * 43758.5453);
    colour += (grain - 0.5) * 0.035;

    fragColor = vec4(mix(plain, clamp(colour, 0.0, 1.0), Amount), 1.0);
}
