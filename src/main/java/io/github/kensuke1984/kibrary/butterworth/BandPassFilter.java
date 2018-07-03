package io.github.kensuke1984.kibrary.butterworth;

import io.github.kensuke1984.kibrary.util.sac.SACData;
import io.github.kensuke1984.kibrary.util.sac.SACFileName;
import io.github.kensuke1984.kibrary.util.sac.SACHeaderEnum;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.complex.ComplexUtils;
import org.apache.commons.math3.util.FastMath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 斎藤正徳 漸化式ディジタルフィルタ<br>
 * |B(σ)|<sup>2</sup> = 1/(1+σ<sup>2n</sup>)<br>
 * B(σ)= Π{1/i(σ-σ<sub>j</sub>)} <br>
 * Amplitude ratio in a band is equal or more than 1/(1+ap<sup>2</sup>) <br>
 * Amplitude ratio at outside cutoff frequency is reduced to equal or less than 1/(1+as<sup>2</sup>) <br>
 * <p>
 * ω=2πfδt
 *
 * @author Kensuke Konishi
 * @version 0.1.4
 */
public class BandPassFilter extends ButterworthFilter {

    /**
     * @param args [NP] [low limiter (Hz)] [higher limit (Hz)] [SAC file]
     * @throws IOException if any
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4)
            throw new IllegalArgumentException("Usage:[NP] [lower limit (Hz)] [higher limit (Hz)] [SAC file]");
        int n = Integer.parseInt(args[0]);
        Path path = Paths.get(args[3]);
        SACData sac = new SACFileName(path).read();
        if (sac.isFiltered()) throw new RuntimeException(args[3] + " is already filtered.");
        double delta = sac.getValue(SACHeaderEnum.DELTA);

        double low = 2 * Math.PI * Double.parseDouble(args[1]) * delta;
        double high = 2 * Math.PI * Double.parseDouble(args[2]) * delta;

        ButterworthFilter filter = new BandPassFilter(high, low, n);
        Path out = Files.createTempDirectory(Paths.get("."), "filtered");
        sac.applyButterworthFilter(filter).writeSAC(out.resolve(path.getFileName()));
    }

    /**
     * maximum value of transmission band
     * |ω| < omegaH transmits.
     */
    private double omegaH;
    /**
     * minimum value of transmission band
     * |ω| > omegaL transmits
     */
    private double omegaL;
    /**
     * maximum value of stop band
     * |ω| > omegaSh stopped.
     */
    private double omegaSh;
    /**
     * minimum value of stop band
     * |ω| < omegaSl stopped.
     */
    private double omegaSl;
    /**
     * &lambda;<sub>0</sub><sup>2</sup>
     */
    private double lambda02;

    /**
     * &omega; = 2&pi;f&Delta;t
     *
     * @param ap      透過域の最小振幅 （1+A<sub>p</sub><sup>2</sup>）<sup>-1</sup>
     * @param as      遮断域の最大振幅 （1+A<sub>s</sub><sup>2</sup>）<sup>-1</sup>
     * @param omegaH  透過域の最大角周波数 maximum &omega; for permissible region
     * @param omegaL  透過域の最小角周波数 minimum &omega; for permissible region
     * @param omegaSh 大きい側の遮断域の最小角周波数 minimum &omega; for blocking region in higher
     *                part
     */
    public BandPassFilter(double ap, double as, double omegaH, double omegaL, double omegaSh) {
        this.ap = ap;
        this.as = as;
        this.omegaH = omegaH;
        this.omegaL = omegaL;
        this.omegaSh = omegaSh;
        if (!omegaValid()) throw new IllegalArgumentException("Input omegas are invalid");
        if (omegaSh <= omegaH || 0.5 * Math.PI <= omegaSh)
            throw new IllegalArgumentException("Input parameters are invalid");
        setSigmaSoverSigmaP();
        setN();
        setC();
        setOmegaSl();
        setLambda02();
        createRecursiveFilter();
        // printParameters();
    }

    /**
     * ap 透過域の最小振幅（1+A<sub>p</sub><sup>2</sup>）<sup>-1</sup>: 0.9<br>
     * as 遮断域の最大振幅(1+A<sub>s</sub><sup>2</sup>)<sup>-1</sup>: 0.1
     *
     * @param omegaH 透過域の最大角周波数
     * @param omegaL 透過域の最小角周波
     * @param n      フィルターの個数から
     */
    public BandPassFilter(double omegaH, double omegaL, int n) {
        this.n = n;
        ap = 1 / 3.0;
        as = 3;
        this.omegaH = omegaH;
        this.omegaL = omegaL;
        if (!omegaValid()) throw new IllegalArgumentException("Input parameters are invalid");
        nToSigmaSoverSigmaP(n);
        computeOmegaShSl();
        // setSigmaSvsSigmaP();
        // setN();
        setC();
        // setOmegaSl();
        setLambda02();
        createRecursiveFilter();
        // printParameters();
    }

    /**
     * y[t]=a<sub>0</sub>x[t]+a<sub>1</sub>x[t-1]+a<sub>2</sub>x[t-2]-b<sub>1</sub>y[t-1]-b<sub>2</sub>y[t-2] <br>
     * a<sub>0</sub> =1, a<sub>1</sub>= 0, a<sub>2</sub> = -1
     *
     * @param b1 b<sub>1</sub>
     * @param b2 b<sub>2</sub>
     * @param x  x
     * @return {@link Complex}[] y
     */
    private static Complex[] computeRecursion(double b1, double b2, Complex[] x) {
        Complex[] y = new Complex[x.length];

        y[0] = x[0];
        // y[0] = x[0].multiply(a0);
        y[1] = x[1].subtract(y[0].multiply(b1));
        // y[1] = x[1].multiply(a0).add(x[0].multiply(a1))
        // .subtract(y[0].multiply(b1));
        for (int i = 2; i < x.length; i++)
            y[i] = x[i].subtract(x[i - 2]).subtract(y[i - 1].multiply(b1)).subtract(y[i - 2].multiply(b2));
        // y[i] = x[i].multiply(a0).add(x[i - 1].multiply(a1))
        // .add(x[i - 2].multiply(a2)).subtract(y[i - 1].multiply(b1))
        // .subtract(y[i - 2].multiply(b2));
        return y;
    }

    /**
     * @return &omega;<sub>H</sub>
     */
    public double getOmegaH() {
        return omegaH;
    }

    /**
     * @return &omega;<sub>L</sub>
     */
    public double getOmegaL() {
        return omegaL;
    }

    /**
     * @return &omega;<sub>Sh</sub>
     */
    public double getOmegaSh() {
        return omegaSh;
    }

    /**
     * @return &omega;<sub>Sl</sub>
     */
    public double getOmegaSl() {
        return omegaSl;
    }

    /**
     * @return if input &omega;<sub>H</sub> and &omega;<sub>L</sub> are valid
     */
    private boolean omegaValid() {
        boolean valid = true;
        double halfPI = 0.5 * Math.PI;
        if (omegaH < 0 || halfPI <= omegaH) {
            System.err.println("omegaH: " + omegaH + " is invalid");
            valid = false;
        }
        if (omegaL < 0 || halfPI <= omegaL) {
            System.err.println("omegaL: " + omegaL + " is invalid");
            valid = false;
        }
        if (omegaH <= omegaL) {
            System.err.println("omegaH, omegaL: " + omegaH + ", " + omegaL + " are invalid");
            valid = false;
        }
        return valid;
    }

    @Override
    public String toString() {
        double permeability = 1 / (1 + ap * ap);
        double cut = 1 / (1 + as * as);
        return "Bandpass (Hz):" + omegaL / 2.0 / Math.PI / 0.05 + ", " + omegaH / 2.0 / Math.PI / 0.05 + ". Backward " +
                backward + ".\nFilter permeability, cut: " + permeability + "  " + cut + ".\nCut region (Hz): " +
                omegaSl / 2.0 / Math.PI / 0.05 + ", " + omegaSh / 2.0 / Math.PI / 0.05 + ".";
    }

    /**
     * By eq. 2.25, computes {@link #sigmaSoverSigmaP}
     */
    @Override
    void setSigmaSoverSigmaP() {
        double tanH = FastMath.tan(omegaH * 0.5);
        double tanL = FastMath.tan(omegaL * 0.5);
        double tanSh = FastMath.tan(omegaSh * 0.5);
        sigmaSoverSigmaP = FastMath.abs(tanSh - tanH * tanL / tanSh) / (tanH - tanL);
    }

    /**
     * By {@link #n}, computes {@link #omegaSh} and {@link #omegaSl}.
     */
    private void computeOmegaShSl() {
        double tanH = FastMath.tan(omegaH * 0.5);
        double tanL = FastMath.tan(omegaL * 0.5);
        double minus = tanH - tanL;
        double mul = tanH * tanL;
        // System.out.println(sigmaSvsSigmaP+"sigma "+minus);
        double x = sigmaSoverSigmaP * minus * 0.5 +
                0.5 * FastMath.sqrt(sigmaSoverSigmaP * sigmaSoverSigmaP * minus * minus + 4 * mul);
        double y = sigmaSoverSigmaP * minus * 0.5 -
                0.5 * FastMath.sqrt(sigmaSoverSigmaP * sigmaSoverSigmaP * minus * minus + 4 * mul);
        omegaSh = FastMath.abs(FastMath.atan(x) * 2);
        omegaSl = FastMath.abs(FastMath.atan(y) * 2);

    }

    private void setC() {
        double c2 = FastMath.pow(ap * as, 1.0 / n) / (FastMath.tan(omegaH / 2) - FastMath.tan(omegaL / 2));
        c2 /= FastMath.abs(FastMath.tan(omegaSh / 2) -
                FastMath.tan(omegaH / 2) * FastMath.tan(omegaL / 2) / FastMath.tan(omegaSh / 2));
        c = FastMath.sqrt(c2);
    }

    private void setOmegaSl() {
        omegaSl = 2 * FastMath.atan(FastMath.tan(omegaH / 2) * FastMath.tan(omegaL / 2) / FastMath.tan(omegaSh / 2));
    }

    private void createRecursiveFilter() {
        // b0 = new double[n / 2];
        b1 = new double[n];
        b2 = new double[n];
        g = 1;
        for (int j = 0; j < n / 2 * 2; j++) {
            Complex lambdaJ = computeLambdaJ(j + 1);
            double muJ = lambdaJ.getReal();
            double nuJ = lambdaJ.getImaginary();
            double b0 = (c + nuJ) * (c + nuJ) + muJ * muJ;
            b1[j] = -2 * (c * c - lambdaJ.abs() * lambdaJ.abs());
            b2[j] = (c - nuJ) * (c - nuJ) + muJ * muJ;
            // System.out.println(j + " b0 " + b0 + " " + getB0(j + 1));
            // System.out.println(j + " b1 " + b1[j] + " " + getB1(j + 1));
            // System.out.println(j + " b2 " + b2[j] + " " + getB2(j + 1));
            g *= c;
            g /= b0;
            b1[j] /= b0;
            b2[j] /= b0;
            // System.out.println(b1[j] + " " + b2[j]);
        }
        // System.out.println(n + " " + n / 2 * 2);
        if (n % 2 == 1) {
            // System.out.println("i");
            int j = n - 1;
            // Math.sin((2 * m + 1) / 2.0 / n * Math.PI)
            double t = FastMath.sin((n / 2 * 2 + 1) / 2.0 / n * Math.PI);
            double b0 = c * c + c * t + lambda02;
            g *= c;
            g /= b0;
            b1[j] = -2 * (c * c - lambda02);
            b2[j] = c * c - c * t + lambda02;
            // System.out.println(j + " b0 " + b0 + " " + getB0(0));
            // System.out.println(j + " b1 " + b1[j] + " " + getB1(0));
            // System.out.println(j + " b2 " + b2[j] + " " + getB2(0));
            b1[j] /= b0;
            b2[j] /= b0;
        }
    }

    @Override
    public Complex getFrequencyResponse(double omega) {
        Complex response = Complex.valueOf(g);
        Complex numerator = Complex.valueOf(0, 2 * FastMath.sin(omega));
        for (int j = 0; j < n / 2 * 2; j++) {
            // Saito 1.7
            // Hj = (a1j +(a2j+1) cos ω -i(a2j-1)sinω)
            // /(b2j+1) cos ω -i(b2j-1)sinω)
            Complex denominator =
                    Complex.valueOf(b1[j] + FastMath.cos(omega) * (b2[j] + 1), -FastMath.sin(omega) * (b2[j] - 1));
            response = response.multiply(numerator).divide(denominator);
        }
        if (n % 2 == 1) {
            int j = n - 1;
            // Complex numerator = Complex.valueOf(1 + Math.cos(omega),
            // Math.sin(omega));
            Complex denominator =
                    Complex.valueOf(b1[j] + FastMath.cos(omega) * (b2[j] + 1), -FastMath.sin(omega) * (b2[j] - 1));
            response = response.multiply(numerator).divide(denominator);
        }

        return backward ? Complex.valueOf(response.abs() * response.abs()) : response;
    }

    @Override
    public Complex[] applyFilter(Complex[] data) {
    	System.out.println(data.length);
        Complex[] y = new Complex[data.length];
        System.arraycopy(data, 0, y, 0, data.length);
        // Complex[] x = data;
        for (int j = 0; j < n / 2 * 2; j++) {
            Complex[] x = y;
            y = computeRecursion(b1[j], b2[j], x);
        }
        if (n % 2 == 1) {
            int j = n - 1;
            Complex[] x = y;
            y = computeRecursion(b1[j], b2[j], x);
        }
        // System.out.println("G" + g);
        for (int i = 0; i < y.length; i++)
            y[i] = y[i].multiply(g);

        if (backward) {
            Complex[] reverseY = new Complex[y.length];
            for (int i = 0; i < y.length; i++)
                reverseY[i] = y[y.length - i - 1];

            for (int j = 0; j < n / 2 * 2; j++) {
                Complex[] x = reverseY;
                reverseY = computeRecursion(b1[j], b2[j], x);
            }
            if (n % 2 == 1) {
                int j = n - 1;
                Complex[] x = reverseY;
                reverseY = computeRecursion(b1[j], b2[j], x);
            }
            for (int i = 0; i < reverseY.length; i++)
                reverseY[i] = reverseY[i].multiply(g);
            for (int i = 0; i < y.length; i++)
                y[i] = reverseY[y.length - i - 1];
        }
        return y;
    }

    private void setLambda02() {
        lambda02 = c * c * FastMath.tan(omegaH / 2) * FastMath.tan(omegaL / 2);
    }

    /**
     * A root of &lambda;<sup>2</sup>-&sigma;<sub>j</sub>&lambda;-&lambda;
     * <sub>0</sub><sup>2</sup>=0
     *
     * @param j j =1, 2, ..., n
     * @return &lambda;<sub>j</sub>
     */
    private Complex computeLambdaJ(int j) {
        int jj = (n / 2) < j ? j - n / 2 : j;
        Complex sigmaJ = ComplexUtils.polar2Complex(1, Math.PI * (2 * jj - 1) / (2 * n));
        Complex lambdaJ = n / 2 < j ? sigmaJ.add(((sigmaJ.pow(2)).add(4 * lambda02)).sqrt()) :
                sigmaJ.subtract(((sigmaJ.pow(2)).add(4 * lambda02)).sqrt());
        lambdaJ = lambdaJ.divide(2);
        return lambdaJ;
    }

}
