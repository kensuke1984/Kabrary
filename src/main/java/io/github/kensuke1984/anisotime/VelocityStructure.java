package io.github.kensuke1984.anisotime;

import java.io.Serializable;
import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Structure information for computing travel time.
 *
 * @author Kensuke Konishi
 * @version 0.1.3
 * @see <a href=
 * https://www.sciencedirect.com/science/article/pii/0031920181900479>Woodhouse,
 * 1981</a>
 */
public interface VelocityStructure extends Serializable {

    /**
     * [%] (must be a positive number)
     * If all values (ACFLN&rho;) at a boundary and boundary + {@link ComputationalMesh#EPS} has smaller gap in ratio than this value,
     * the boundary is regarded as the D boundary.
     * If this value is 1 [%], ratio of values (smaller/larger) must be in 99-100 % at continuous point.
     */
    double MAXIMUM_RATIO_OF_D_BOUNDARY = 1e-2;

    /**
     * [km]
     * Radius close to a D boundary by this value, boundary &le; r &le; boundary + this value,
     * the value at the radius will be modified.
     */
    double D_BOUNDARY_ZONE = 0.5;

    /**
     * @param r [km] target radius
     * @return if there is a jump at r. Any of the values (ACFLN&rho;) at r&plusmn;&epsilon;
     * has larger ratio gap than {@link #MAXIMUM_RATIO_OF_D_BOUNDARY},
     * this method returns true.
     */
    default boolean isJump(double r) {
        if (r < 0 || earthRadius() < r) throw new IllegalArgumentException("Input r " + r + " is not correct.");
        else if (r < ComputationalMesh.EPS || earthRadius() - ComputationalMesh.EPS < r) return false;
        Function<DoubleUnaryOperator, Boolean> checker = getter -> {
            double ratio =
                    getter.applyAsDouble(r + ComputationalMesh.EPS) / getter.applyAsDouble(r - ComputationalMesh.EPS);
            if (1 < ratio) ratio = 1 / ratio;
            return ratio < 1 - MAXIMUM_RATIO_OF_D_BOUNDARY / 100;
        };
        return checker.apply(this::getA) || checker.apply(this::getC) || checker.apply(this::getF) ||
                checker.apply(this::getL) || checker.apply(this::getN) || checker.apply(this::getRho);
    }

    /**
     * @param r [km]
     * @return [km/s] vpv
     */
    default double computeVpv(double r) {
        return Math.sqrt(getC(r) / getRho(r));
    }

    /**
     * @param r [km]
     * @return [km/s] vph
     */
    default double computeVph(double r) {
        return Math.sqrt(getA(r) / getRho(r));
    }

    /**
     * @param r [km]
     * @return [km/s] vsv
     */
    default double computeVsv(double r) {
        return Math.sqrt(getL(r) / getRho(r));
    }

    /**
     * @param r [km]
     * @return [km/s] vsh
     */
    default double computeVsh(double r) {
        return Math.sqrt(getN(r) / getRho(r));
    }

    /**
     * @return Transversely isotropic (TI) PREM by Dziewonski &amp; Anderson 1981
     */
    static VelocityStructure prem() {
        return PolynomialStructure.PREM;
    }

    /**
     * @return isotropic PREM by Dziewonski &amp; Anderson 1981
     */
    static VelocityStructure iprem() {
        return PolynomialStructure.ISO_PREM;
    }

    /**
     * @return AK135 by Kennett, Engdahl &amp; Buland (1995)
     */
    static VelocityStructure ak135() {
        return PolynomialStructure.AK135;
    }

    /**
     * @param pp           target phase
     * @param rayParameter target ray parameter
     * @return [km] the <b>largest</b> radius of bounce point
     */
    default double getTurningR(PhasePart pp, double rayParameter) {
        switch (pp) {
            case I:
                return iTurningR(rayParameter);
            case JV:
                return jvTurningR(rayParameter);
//            case JH:
//                return jhTurningR(rayParameter);
            case K:
                return kTurningR(rayParameter);
            case P:
                return pTurningR(rayParameter);
            case SV:
                return svTurningR(rayParameter);
            case SH:
                return shTurningR(rayParameter);
            default:
                throw new RuntimeException("anikusupekuted");
        }
    }

    /**
     * @param r [km] radius
     * @return [g/cm<sup>3</sup>] density &rho;(r)
     */
    double getRho(double r);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(N/&rho;)<sup>1/2</sup>* r = p(N/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return [km] the K turning radius for the raypath or {@link Double#NaN}
     * if there is no valid R. The radius must be in the outercore.
     * @see "Woodhouse (1981)"
     */
    double kTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(N/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return [km] the SH turning radius for the raypath or {@link Double#NaN}
     * if there is no valid R The radius must be in the mantle.
     * @see "Woodhouse (1981)"
     */
    double shTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(L/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return [km] the SV turning radius for the raypath or {@link Double#NaN}
     * if there is no valid R. The radius must be in the mantle.
     * @see "Woodhouse (1981)"
     */
    double svTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(A/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return [km] the P turning radius for the raypath or {@link Double#NaN}
     * if there is no valid radius. The radius must be in the mantle.
     * @see "Woodhouse (1981)"
     */
    double pTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(A/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return the P turning radius [km] for the raypath or {@link Double#NaN}
     * if there is no valid radius. The radius must be in the
     * inner-core.
     * @see "Woodhouse (1981)"
     */
    double iTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(N/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return the SH turning radius [km] for the raypath or {@link Double#NaN}
     * if there is no valid R The radius must be in the inner-core.
     * @see "Woodhouse (1981)"
     */
    double jhTurningR(double rayParameter);

    /**
     * The turning radius is r with which q<sub>&tau;</sub> = 0.
     * <p>
     * r = p(L/&rho;)<sup>1/2</sup>
     *
     * @param rayParameter ray parameter
     * @return the SV turning radius [km] for the raypath or {@link Double#NaN}
     * if there is no valid R. The radius must be in the inner-core.
     * @see "Woodhouse (1981)"
     */
    double jvTurningR(double rayParameter);

    /**
     * @param r [km] radius
     * @return [GPa] A(r) A at r
     */
    double getA(double r);

    /**
     * @param r [km] radius
     * @return [GPa] C(r) C at r
     */
    double getC(double r);

    /**
     * @param r [km] radius
     * @return [GPa] F at r
     */
    double getF(double r);

    /**
     * @param r [km] radius
     * @return [GPa] L at r
     */
    double getL(double r);

    /**
     * @param r [km] radius
     * @return [GPa] N at r
     */
    double getN(double r);

    /**
     * @return [km] radius of CMB
     */
    double coreMantleBoundary();

    /**
     * @return [km] radius of ICB
     */
    double innerCoreBoundary();

    /**
     * @return [km] radius of Earth
     */
    double earthRadius();

    /**
     * When layered structures have boundaries which are in particular
     * not related to CMB, ICB and so on.., the values from this method may be useful
     * for generating a mesh ({@link ComputationalMesh}. Even when the input structure
     * is not layered but you want to arbitrary add layers. (e.g. the boundaries of MTZ)
     * the center (r=0), ICB, CMB and the surface must be included.
     *
     * @return array of radii [km]. Values of the boundaries for layers.
     * Velocity changes at boundaries.
     * It is NOT the depth but radius from the center.
     * The values must be in order (small to large values) and
     * later changes to the array should not affect the velocity structure,
     * in other words, changes in the returning array must not result in changes
     * of their original values.
     */
    default double[] velocityBoundaries() {
        return new double[]{0, innerCoreBoundary(), coreMantleBoundary(), earthRadius()};
    }

    /**
     * @return [km] radii array of boundaries in the mantle. including the CMB and surface.
     */
    default double[] boundariesInMantle() {
        return Arrays.stream(velocityBoundaries()).filter(r -> coreMantleBoundary() <= r).toArray();
    }

    /**
     * @return [km] radii array of boundaries in the mantle. including the CMB and ICB.
     */
    default double[] boundariesInOuterCore() {
        return Arrays.stream(velocityBoundaries()).filter(r -> innerCoreBoundary() <= r && r <= coreMantleBoundary())
                .toArray();
    }

    /**
     * @return [km] radii array of boundaries in the inner-core. including the center (0) and ICB.
     */
    default double[] boundariesInInnerCore() {
        return Arrays.stream(velocityBoundaries()).filter(r -> r <= innerCoreBoundary()).toArray();
    }

    /**
     * Input point must be one of the boundaries in the structure.
     *
     * @param point for the radius
     * @return [km] radius for the point.
     */
    default double getROf(PassPoint point) {
        switch (point) {
            case EARTH_SURFACE:
                return earthRadius();
            case CMB:
                return coreMantleBoundary();
            case ICB:
                return innerCoreBoundary();
            default:
                throw new RuntimeException(point + " is not a boundary.");
        }
    }

}
