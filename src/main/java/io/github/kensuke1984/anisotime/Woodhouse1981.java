package io.github.kensuke1984.anisotime;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * The class is calculator of the formulation in Woodhouse (1981).
 *
 * @author Kensuke Konishi
 * @version 0.0.7
 * @see <a href=
 * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
 * 1981</a>
 */
class Woodhouse1981 implements Serializable {

    /**
     * 2019/12/28
     */
    private static final long serialVersionUID = -9053845983534798839L;
    private final static Set<Woodhouse1981> WOODHOUSE_CACHE = Collections.synchronizedSet(new HashSet<>());

    static {
        WOODHOUSE_CACHE.addAll(Arrays
                .asList(new Woodhouse1981(VelocityStructure.prem()), new Woodhouse1981(VelocityStructure.ak135()),
                        new Woodhouse1981(VelocityStructure.iprem())));
    }

    private final VelocityStructure STRUCTURE;
    private transient Map<Double, Double> s1;
    private transient Map<Double, Double> s2;
    private transient Map<Double, Double> s3;
    private transient Map<Double, Double> s4;
    private transient Map<Double, Double> s5;

    private transient DoubleUnaryOperator computeS1;
    private transient DoubleUnaryOperator computeS2;
    private transient DoubleUnaryOperator computeS3;
    private transient DoubleUnaryOperator computeS4;
    private transient DoubleUnaryOperator computeS5;

    /**
     * @param structure for Woodhouse computation
     */
    Woodhouse1981(VelocityStructure structure) {
        STRUCTURE = structure;
        copyOrCreate();
        setComputation();
    }

    private void setComputation() {
        computeS1 = x -> 0.5 * STRUCTURE.getRho(x) * (1 / STRUCTURE.getL(x) + 1 / STRUCTURE.getC(x));
        computeS2 = x -> 0.5 * STRUCTURE.getRho(x) * (1 / STRUCTURE.getL(x) - 1 / STRUCTURE.getC(x));
        computeS3 = x -> {
            double c = STRUCTURE.getC(x);
            double f = STRUCTURE.getF(x);
            double l = STRUCTURE.getL(x);
            return 0.5 / l / c * (STRUCTURE.getA(x) * c - f * f - 2 * l * f);
        };
        computeS4 = x -> {
            double s3 = computeS3(x);
            return s3 * s3 - STRUCTURE.getA(x) / STRUCTURE.getC(x);
        };
        computeS5 = x -> 0.5 * STRUCTURE.getRho(x) / STRUCTURE.getC(x) * (1 + STRUCTURE.getA(x) / STRUCTURE.getL(x)) -
                computeS1(x) * computeS3(x);
    }

    private void readObject(ObjectInputStream stream) throws ClassNotFoundException, IOException {
        stream.defaultReadObject();
        createCache();
        setComputation();
    }

    /**
     * Checks if the structure of this exists in the cache, if it does, just copy,
     * otherwise create a new cache.
     */
    private void copyOrCreate() {
        synchronized (WOODHOUSE_CACHE) {
            Optional<Woodhouse1981> inCache =
                    WOODHOUSE_CACHE.stream().filter(w -> w.STRUCTURE == STRUCTURE).findFirst();
            if (inCache.isPresent()) copyCache(inCache.get());
            else {
                createCache();
                WOODHOUSE_CACHE.add(this);
            }
        }
    }

    /**
     * @return VelocityStructure
     */
    public VelocityStructure getStructure() {
        return STRUCTURE;
    }

    /**
     * @param pp           target phase
     * @param rayParameter to compute for
     * @param r            [km]
     * @return q<sub>&Delta;</sub> for pp
     * @see <a href=
     * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
     * 1981</a>
     */
    double computeQDelta(PhasePart pp, double rayParameter, double r) {
        double r2 = r * r;
        switch (pp) {
            case P:
            case I:
                return rayParameter / r2 / computeQTau(pp, rayParameter, r) * (computeS3(r) +
                        (computeS4(r) * rayParameter * rayParameter / r2 + computeS5(r)) / computeR(rayParameter, r));
            case SV:
            case JV:
                return rayParameter / r2 / computeQTau(pp, rayParameter, r) * (computeS3(r) -
                        (computeS4(r) * rayParameter * rayParameter / r2 + computeS5(r)) / computeR(rayParameter, r));
            case SH:
//            case JH:
                return rayParameter * STRUCTURE.getN(r) / STRUCTURE.getL(r) / computeQTau(pp, rayParameter, r) / r2;
            case K:
                double v = Math.sqrt(STRUCTURE.getA(r) / STRUCTURE.getRho(r));
                double sin = rayParameter * v / r;
                double cos = Math.sqrt(1 - sin * sin);
                return sin / cos / r;
            default:
                throw new RuntimeException("unexpecTed");
        }
    }
    
    double computeQdeltaNearZero(PhasePart pp, double rayParameter, double r) {
    	double r2 = r * r;
    	switch (pp) {
    		case I:
    			return rayParameter / r2 / computeQTauNear0(pp, rayParameter, r) * (computeS3(0) +
                        (computeS4(0) * rayParameter * rayParameter / r2 + computeS5(0)) / computeRNear0(rayParameter, r));
    		case JV:
    			return rayParameter / r2 / computeQTauNear0(pp, rayParameter, r) * (computeS3(0) -
                        (computeS4(0) * rayParameter * rayParameter / r2 + computeS5(0)) / computeRNear0(rayParameter, r));
    		default:
    			throw new RuntimeException("unexpecTed");
    	}
    }

    /**
     * @param pp           target phase
     * @param rayParameter to compute for
     * @param r            [km]
     * @return Q<sub>T</sub> for pp
     * @see <a href=
     * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
     * 1981</a>
     */
    double computeQT(PhasePart pp, double rayParameter, double r) {
        switch (pp) {
            case K:
                double v = Math.sqrt(STRUCTURE.getA(r) / STRUCTURE.getRho(r));
                double sin = rayParameter * v / r;
                double cos = Math.sqrt(1 - sin * sin);
                return 1 / v / cos;
            case P:
            case I:
                double s2 = computeS2(r);
                return (computeS1(r) -
                        (computeS5(r) * rayParameter * rayParameter / r / r + s2 * s2) / computeR(rayParameter, r)) /
                        computeQTau(pp, rayParameter, r);
            case SH:
//            case JH:
                return STRUCTURE.getRho(r) / STRUCTURE.getL(r) / computeQTau(pp, rayParameter, r);
            case SV:
            case JV:
                s2 = computeS2(r);
                return (computeS1(r) +
                        (computeS5(r) * rayParameter * rayParameter / r / r + s2 * s2) / computeR(rayParameter, r)) /
                        computeQTau(pp, rayParameter, r);
            default:
                throw new RuntimeException("souteigai");
        }
    }

    /**
     * q<sub>&tau;</sub>= (s<sub>1</sub>-s<sub>3</sub>p<sup>2</sup>/r
     * <sup>2</sup>-R)<sup>1/2</sup> for P, (s<sub>1</sub>-s<sub>3</sub>p
     * <sup>2</sup>/r <sup>2</sup>+R)<sup>1/2</sup> for SV<br>
     * <p>
     * (&rho;/L-N/L&middot;P<sup>2</sup>/r<sup>2</sup>)<sup>1/2</sup> for SH.
     *
     * @param pp           target phase
     * @param rayParameter to compute for
     * @param r            [km]
     * @return q<sub>&tau;</sub> for pp
     * @see <a href=
     * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
     * 1981</a>
     */
    double computeQTau(PhasePart pp, double rayParameter, double r) {
        double r2 = r * r;
        switch (pp) {
            case P:
            case I:
                return Math.sqrt(computeS1(r) - computeS3(r) * rayParameter * rayParameter / r2 -
                        computeR(rayParameter, r));
            case K:
                double v = Math.sqrt(STRUCTURE.getA(r) / STRUCTURE.getRho(r));
                return Math.sqrt(1 / v / v - rayParameter * rayParameter / r / r);
            case SH:
//            case JH:
                double L = STRUCTURE.getL(r);
                return Math.sqrt(STRUCTURE.getRho(r) / L - STRUCTURE.getN(r) * rayParameter * rayParameter / L / r2);
            case SV:
            case JV:
                return Math.sqrt(computeS1(r) - computeS3(r) * rayParameter * rayParameter / r2 +
                        computeR(rayParameter, r));
            default:
                throw new RuntimeException(pp + " is Unexpected");
        }
    }
    
    double computeQTauNear0(PhasePart pp, double rayParameter, double r) {
        double r2 = r * r;
        switch (pp) {
            case I:
                return Math.sqrt(computeS1(0) - computeS3(0) * rayParameter * rayParameter / r2 -
                        computeRNear0(rayParameter, r));
            case JV:
                return Math.sqrt(computeS1(0) - computeS3(0) * rayParameter * rayParameter / r2 +
                        computeRNear0(rayParameter, r));
            default:
                throw new RuntimeException(pp + " is Unexpected");
        }
    }

    /**
     * @param rayParameter to compute for
     * @param r            [km]
     * @return R
     * @see <a href=
     * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
     * 1981</a>
     */
    private double computeR(double rayParameter, double r) {
        double s2 = computeS2(r);
        double por = rayParameter / r;
        double por2 = por * por;
        return Math.sqrt(computeS4(r) * por2 * por2 + 2 * computeS5(r) * por2 + s2 * s2);
    }
    
    private double computeRNear0(double rayParameter, double r) {
        double s2 = computeS2(0);
        double por = rayParameter / r;
        double por2 = por * por;
        return Math.sqrt(computeS4(0) * por2 * por2 + 2 * computeS5(0) * por2 + s2 * s2);
    }

    /**
     * Copies cash for s1-s5
     *
     * @param woodhouse source map
     */
    private void copyCache(Woodhouse1981 woodhouse) {
        s1 = woodhouse.s1;
        s2 = woodhouse.s2;
        s3 = woodhouse.s3;
        s4 = woodhouse.s4;
        s5 = woodhouse.s5;
    }

    /**
     * Creates new cache maps
     */
    private void createCache() {
        s1 = new ConcurrentHashMap<>();
        s2 = new ConcurrentHashMap<>();
        s3 = new ConcurrentHashMap<>();
        s4 = new ConcurrentHashMap<>();
        s5 = new ConcurrentHashMap<>();
    }

    void clear(){
        s1.clear();
        s2.clear();
        s3.clear();
        s4.clear();
        s5.clear();
    }

    /**
     * @param r [km]
     * @return S<sub>1</sub>
     */
    private double computeS1(double r) {
        return s1.computeIfAbsent(r, computeS1::applyAsDouble);
//        return computeS1.applyAsDouble(r);
    }

    /**
     * @param r [km]
     * @return S<sub>2</sub>
     */
    private double computeS2(double r) {
        return s2.computeIfAbsent(r, computeS2::applyAsDouble);
//        return computeS2.applyAsDouble(r);
    }

    /**
     * @param r [km]
     * @return S<sub>3</sub>
     */
    private double computeS3(double r) {
        return s3.computeIfAbsent(r, computeS3::applyAsDouble);
//        return computeS3.applyAsDouble(r);
    }

    /**
     * @param r [km]
     * @return S<sub>4</sub>
     */
    private double computeS4(double r) {
        return s4.computeIfAbsent(r, computeS4::applyAsDouble);
//        return computeS4.applyAsDouble(r);
    }

    /**
     * @param r [km]
     * @return S<sub>5</sub>
     */
    private double computeS5(double r) {
        return s5.computeIfAbsent(r, computeS5::applyAsDouble);
//        return computeS5.applyAsDouble(r);
    }
}
