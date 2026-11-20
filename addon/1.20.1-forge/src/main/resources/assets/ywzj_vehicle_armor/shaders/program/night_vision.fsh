#version 150

// The Armor Pioneer gunner's night sight.
//
// The base mod's CRT pass runs around this one in the same frame: it owns the barrel distortion,
// the black scope border and its animated scanlines. This pass only turns what it is given into an
// image-intensifier picture - luminance, a steep lift, a green phosphor tint, a little grain. It
// must map black to black, or the black border of the scope would be lifted to dark green.
uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

float rand(vec2 seed) {
    return fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    float luma = dot(scene, vec3(0.299, 0.587, 0.114));

    // Three stops of lift, compressed so a lit daytime scene tops out instead of clipping hard.
    float gain = pow(clamp(luma * 3.2, 0.0, 1.0), 0.55);

    // Grain only where there is something to amplify; a black scope border stays black.
    float mask = smoothstep(0.004, 0.03, luma);
    float grain = (rand(gl_FragCoord.xy * 0.73) - 0.5) * 0.06 * mask;
    float scan = 1.0 - 0.035 * (0.5 + 0.5 * sin(gl_FragCoord.y * 1.4));
    vec2 offset = texCoord - 0.5;
    float vignette = 1.0 - 0.28 * dot(offset, offset);

    float value = clamp(gain + grain, 0.0, 1.0) * scan * vignette;
    fragColor = vec4(value * 0.16, value, value * 0.30, 1.0);
}
