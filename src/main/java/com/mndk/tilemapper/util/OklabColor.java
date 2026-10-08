package com.mndk.tilemapper.util;

/**
 * Represents a color in the Oklab color space.
 *
 * <p>Oklab (Björn Ottosson, 2020) is a perceptually uniform color space.
 * Euclidean distance in Oklab correlates well with perceived color difference,
 * making it suitable for nearest-colour block matching.
 *
 * <p>Two constructors:
 * <ul>
 *   <li>{@link #OklabColor(double, double, double)} — from pre-computed L,a,b (JSON)</li>
 *   <li>{@link #OklabColor(RGBColorDouble)} — from sRGB (tile pixel), using sRGB gamma decoding + Oklab transform</li>
 * </ul>
 */
public class OklabColor {

    public final double l, a, b;

    /**
     * Construct from pre-computed Oklab values (e.g. parsed from JSON).
     */
    public OklabColor(double l, double a, double b) {
        this.l = l;
        this.a = a;
        this.b = b;
    }

    /**
     * Convert sRGB to Oklab using the standard Oklab transform.
     *
     * <p>Performs sRGB gamma decoding ({@link #removeSrgbGamma})
     * followed by the Oklab linear matrix transform.
     * Matches the Python {@code img2lab()} in assets2blockset.py exactly.
     */
    public OklabColor(RGBColorDouble color) {
        double r = color.red / 255.0;
        double g = color.green / 255.0;
        double b = color.blue / 255.0;

        // sRGB → linear RGB (gamma decode)
        double lr = removeSrgbGamma(r);
        double lg = removeSrgbGamma(g);
        double lb = removeSrgbGamma(b);

        // Linear RGB → LMS
        double lL = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb;
        double mM = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb;
        double sS = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb;

        // LMS → LMS' (cube root)
        double l_ = Math.cbrt(lL);
        double m_ = Math.cbrt(mM);
        double s_ = Math.cbrt(sS);

        // LMS' → Oklab
        this.l = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
        this.a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
        this.b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;
    }

    /**
     * Squared Euclidean distance in Oklab space.
     * Perceptually uniform — smaller = more similar.
     */
    public double distanceSq(OklabColor other) {
        double dl = l - other.l;
        double da = a - other.a;
        double db = b - other.b;
        return dl * dl + da * da + db * db;
    }

    /**
     * Chroma in the Oklch polar representation.
     */
    public double chroma() {
        return Math.sqrt(a * a + b * b);
    }

    /**
     * Hue angle in radians in the Oklch polar representation.
     *
     * <p>For neutral colours (C == 0) the hue is mathematically undefined.
     * Returning 0 keeps downstream calculations deterministic; chord-distance
     * hue error is still 0 when either chroma is 0.</p>
     */
    public double hueRadians() {
        return Math.atan2(b, a);
    }

    /**
     * Squared angular/chord component of Oklab a-b distance.
     *
     * <p>For two Oklch colours, the a-b Euclidean distance decomposes as:
     * {@code (C1 - C2)^2 + deltaH2(C1, h1, C2, h2)}.</p>
     */
    public double deltaH2(OklabColor other) {
        return deltaH2(chroma(), hueRadians(), other.chroma(), other.hueRadians());
    }

    /**
     * Squared angular/chord component between two Oklch colours.
     */
    public static double deltaH2(double chroma1, double hue1, double chroma2, double hue2) {
        if (chroma1 <= 0.0 || chroma2 <= 0.0) return 0.0;

        double dh = Math.abs(hue1 - hue2);
        if (dh > Math.PI) {
            dh = (2.0 * Math.PI) - dh;
        }

        double sin = Math.sin(dh * 0.5);
        double deltaH = 2.0 * Math.sqrt(chroma1 * chroma2) * sin;
        return deltaH * deltaH;
    }

    // ========== sRGB gamma helpers ==========

    /**
     * sRGB gamma decoding (linearization): 8-bit sRGB → linear RGB.
     * Equivalent to IEC 61966-2-1 sRGB transfer function.
     */
    public static double removeSrgbGamma(double c) {
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        if (c >= 0.04045) {
            return Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return c / 12.92;
    }

    /**
     * sRGB gamma encoding: linear RGB → 8-bit sRGB.
     */
    public static double addSrgbGamma(double c) {
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        if (c >= 0.0031308) {
            return 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
        }
        return c * 12.92;
    }
}
