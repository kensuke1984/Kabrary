package io.github.kensuke1984.anisotime;

import io.github.kensuke1984.kibrary.Environment;
import io.github.kensuke1984.kibrary.util.Utilities;
import org.apache.commons.math3.analysis.polynomials.PolynomialFunction;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.apache.commons.math3.util.Precision;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;

/**
 * Raypath catalogue for one model
 * <p>
 * If a new catalog is computed which does not exist in Kibrary share, it
 * automatically is stored.
 * <p>
 *
 * @author Kensuke Konishi, Anselme Borgeaud
 * @version 0.1.1
 */
public class RaypathCatalog implements Serializable {
    /**
     * 2019/10/3
     */
    private static final long serialVersionUID = 3822898217586532912L;

    /**
     * Creates a catalog for a model file (model file, or prem, iprem, ak135).
     * The catalog computes so densely as any adjacent raypath pairs has smaller gap
     * than (&delta;&Delta;) in P, PcP, S and ScS.
     * Computation mesh in each part is (inner-core, outer-core and mantle), respectively.
     * (Integral threshold) controls the range for Jeffreys and Jeffreys.
     *
     * @param args [model file (prem, iprem, ak135 or a polynomial file only now)] [&delta;&Delta; (deg)] [inner-core]
     *             [outer-core] [mantle] intervals [integral threshold (0, 1)]
     * @throws IOException if any
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 6) throw new IllegalArgumentException(
                "Usage: [model name, polynomial file] [\u03b4\u0394 (deg)] [inner-core] [outer-core] [mantle] [integral threshold (0,1)]");
        VelocityStructure structure;
        switch (args[0]) {
            case "prem":
            case "PREM":
                structure = VelocityStructure.prem();
                break;
            case "iprem":
            case "iPREM":
                structure = VelocityStructure.iprem();
                break;
            case "ak135":
            case "AK135":
                structure = VelocityStructure.ak135();
                break;
            default:
                structure = new PolynomialStructure(Paths.get(args[0]));
        }
        double dDelta = Math.toRadians(Double.parseDouble(args[1]));
        ComputationalMesh mesh =
                new ComputationalMesh(structure, Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                        Double.parseDouble(args[4]), Double.parseDouble(args[5]));
        computeCatalogue(structure, mesh, dDelta);
    }


    /**
     * Catalog for PREM. &delta;&Delta; = 1. Mesh is simple.
     */
    public final static RaypathCatalog PREM;
    /**
     * Catalog for the isotropic PREM. &delta;&Delta; = 1. Mesh is simple.
     */
    public final static RaypathCatalog ISO_PREM;
    /**
     * Catalog for AK135. &delta;&Delta; = 1. Mesh is simple.
     */
    final static RaypathCatalog AK135;
    private static final Path share = Environment.KIBRARY_HOME.resolve("share");

    static {
        try {
            BiFunction<Path, VelocityStructure, RaypathCatalog> getCatalogue = (p, v) -> {
                RaypathCatalog cat;
                String model = p.getFileName().toString().replace(".cat", "");
                ComputationalMesh simple = ComputationalMesh.simple(v);
                if (Files.exists(p)) {
                    try {
                        cat = read(p);
                    } catch (ClassNotFoundException | IOException ice) {
                        System.err.println("Creating a catalog for " + model +
                                " (due to out of date). This computation is done only once.");
                        (cat = new RaypathCatalog(v, simple, Math.toRadians(1))).create();
                        try {
                            cat.write(p);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    System.err.println("Creating a catalog for " + model + ". This computation is done only once.");
                    (cat = new RaypathCatalog(v, simple, Math.toRadians(1))).create();
                    try {
                        cat.write(p);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                return cat;
            };
            Files.createDirectories(share);
            PREM = getCatalogue.apply(share.resolve("prem.cat"), VelocityStructure.prem());
            ISO_PREM = getCatalogue.apply(share.resolve("iprem.cat"), VelocityStructure.iprem());
            AK135 = getCatalogue.apply(share.resolve("ak135.cat"), VelocityStructure.ak135());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimum value of &delta;p (ray parameter). Even if similar raypaths
     * satisfying {@link #D_DELTA} are not found within this value, a catalogue
     * does not have a denser ray parameter than the value.
     */
    final double MINIMUM_DELTA_P = 0.01;
    /**
     * Woodhouse formula with certain velocity structure
     */
    private final Woodhouse1981 WOODHOUSE;
    /**
     * This value is in [rad].
     * <p>
     * We compute epicentral distances &Delta;<sup>(P)</sup><sub>i</sub> (P or
     * PcP) and &Delta;<sup>(S)</sup><sub>i</sub> (S or ScS) for ray parameters
     * p<sub>i</sub> (p<sub>i</sub> &lt; p<sub>i+1</sub>) for a catalogue. If
     * &delta;&Delta;<sub>i</sub> (|&Delta;<sub>i</sub> - &Delta;<sub>i</sub>|)
     * &lt; this value, both p<sub>i</sub> and p<sub>i+1</sub> are stored,
     * otherwise either only one of them is stored.
     */
    private final double D_DELTA;
    /**
     * Standard &delta;p (ray parameter). In case the &delta;p is too big to
     * have &Delta; satisfying {@link #D_DELTA}, another value (2.5, 1.25) is
     * used instantly.
     */
    private final double DELTA_P = 5;
    /**
     * List of stored raypaths. Ordered by each ray parameter p.
     */
    private final TreeSet<Raypath> raypathList = new TreeSet<>();
    /**
     * Mesh for computation
     */
    private final ComputationalMesh MESH;
    private final transient TreeSet<Raypath> raypathPool = new TreeSet<>();
    /**
     * Raypath of Pdiff
     */
    private Raypath pDiff;
    /**
     * Raypath of SVdiff
     */
    private Raypath svDiff;
    /**
     * Raypath of SHdiff
     */
    private Raypath shDiff;
    /**
     * Raypath of SHdiff
     */
    private Raypath kLimit;

    /**
     * We compute epicentral distances &Delta;<sup>(P)</sup><sub>i</sub> (P or
     * PcP) and &Delta;<sup>(S)</sup><sub>i</sub> (S or ScS) for ray parameters
     * p<sub>i</sub> (p<sub>i</sub> &lt; p<sub>i+1</sub>) for a catalogue.<br>
     * If &delta;&Delta;<sub>i</sub> (|&Delta;<sub>i</sub> -
     * &Delta;<sub>i</sub>|) &lt; this value, both p<sub>i</sub> and
     * p<sub>i+1</sub> are stored, otherwise either only one of them is stored.
     *
     * @param structure for computation of raypaths
     * @param mesh      for computation of raypaths.
     * @param dDelta    &delta;&Delta; [rad] for creation of a catalog.
     */
    private RaypathCatalog(VelocityStructure structure, ComputationalMesh mesh, double dDelta) {
        WOODHOUSE = new Woodhouse1981(structure);
        if (dDelta <= 0) throw new IllegalArgumentException("Input dDelta must be positive.");
        D_DELTA = dDelta;
        MESH = mesh;
    }

    /**
     * We compute epicentral distances &Delta;<sup>(P)</sup><sub>i</sub> (P or
     * PcP) and &Delta;<sup>(S)</sup><sub>i</sub> (S or ScS) for ray parameters
     * p<sub>i</sub> (p<sub>i</sub> &lt; p<sub>i+1</sub>) for a catalogue.<br>
     * If &delta;&Delta;<sub>i</sub> (|&Delta;<sub>i</sub> -
     * &Delta;<sub>i</sub>|) &lt; this value, both p<sub>i</sub> and
     * p<sub>i+1</sub> are stored, otherwise either only one of them is stored.
     * <p>
     * Note that if a catalog for the input parameter already exists in
     * KibraryHOME/share, the stored catalog returns.
     *
     * @param structure for computation of raypaths
     * @param mesh      for computation of raypaths.
     * @param dDelta    &delta;&Delta; [rad] for creation of a catalog.
     * @return Catalogue for the input structure
     */
    public static RaypathCatalog computeCatalogue(VelocityStructure structure, ComputationalMesh mesh, double dDelta) {
        try (DirectoryStream<Path> catalogStream = Files.newDirectoryStream(share, "*.cat")) {
            for (Path p : catalogStream)
                try {
                    RaypathCatalog c;
                    switch (p.getFileName().toString()) {
                        case "iprem.cat":
                            c = ISO_PREM;
                            break;
                        case "prem.cat":
                            c = PREM;
                            break;
                        case "ak135.cat":
                            c = AK135;
                            break;
                        default:
                            c = read(p);
                    }
                    if (c.getStructure().equals(structure) && c.MESH.equals(mesh) && c.D_DELTA == dDelta) {
//                        System.err.println("Catalog is found. " + p);
                        return c;
                    }
                } catch (InvalidClassException ice) {
                    System.err.println(p + " may be out of date.");
                }
        } catch (Exception e) {
            e.printStackTrace();
        }
        RaypathCatalog cat = new RaypathCatalog(structure, mesh, dDelta);
        cat.create();
        try {
            Path p = Files.createTempFile(share, "raypath", ".cat");
            cat.write(p);
            System.err.println(p + " is created.");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return cat;
    }

    /**
     * @param path    the path for the catalogue file.
     * @param options open option
     * @return Catalogue read from the path
     * @throws IOException            if any
     * @throws ClassNotFoundException if any
     */
    public static RaypathCatalog read(Path path, OpenOption... options) throws IOException, ClassNotFoundException {
        try (ObjectInputStream oi = new ObjectInputStream(Files.newInputStream(path, options))) {
            return (RaypathCatalog) oi.readObject();
        }
    }

    /**
     * If an input angle is a radian for 370 deg, then one for 10 deg returns.
     * If an input angle is a radian for 190 deg, then one for 170 deg returns.
     *
     * @param angle [rad]
     * @return a relative angle for the input angle. The angle is [0,180]
     */
    private static double toRelativeAngle(double angle) {
        while (0 < angle) angle -= 2 * Math.PI;
        angle += 2 * Math.PI;
        return angle <= Math.PI ? angle : 2 * Math.PI - angle;
    }

    /**
     * Assume that there is a regression curve f(&Delta;) = T for the small
     * range. The function f is assumed to be a polynomial function. The degree
     * of the function depends on the number of the input raypaths.
     *
     * @param targetPhase target phase
     * @param eventR      [km] radius of event
     * @param targetDelta [rad] epicentral distance to get T for
     * @param raypaths    Polynomial interpolation is done with these. All the raypaths
     *                    must be computed.
     * @return travel time [s] for the target Delta estimated by the polynomial
     * interpolation with the raypaths.
     */
    private static double interpolateTraveltime(Phase targetPhase, double eventR, double targetDelta,
                                                Raypath... raypaths) {
        WeightedObservedPoints deltaTime = new WeightedObservedPoints();
        for (Raypath raypath : raypaths)
            deltaTime.add(raypath.computeDelta(eventR, targetPhase), raypath.computeT(eventR, targetPhase));
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(raypaths.length - 1);
        return new PolynomialFunction(fitter.fit(deltaTime.toList())).value(targetDelta);
    }

    /**
     * @return Woodhouse1981 used in the catalog.
     */
    Woodhouse1981 getWoodhouse1981() {
        return WOODHOUSE;
    }

    /**
     * @return Velocity structure used in the catalog.
     */
    public VelocityStructure getStructure() {
        return WOODHOUSE.getStructure();
    }

    /**
     * @return Raypaths in a catalog in order by the p (ray parameter).
     */
    public Raypath[] getRaypaths() {
        return raypathList.toArray(new Raypath[raypathList.size()]);
    }

    /**
     * @return {@link ComputationalMesh} used in the catalog.
     */
    ComputationalMesh getMesh() {
        return MESH;
    }

    /**
     * TODO boundaries Computes ray parameters of diffraction phases (Pdiff and
     * Sdiff).
     */
    private void computeDiffraction() {
        VelocityStructure structure = WOODHOUSE.getStructure();
        double cmb = structure.coreMantleBoundary() + ComputationalMesh.EPS;
        double rho = structure.getRho(cmb);
        double icb = structure.innerCoreBoundary() + ComputationalMesh.EPS;
        double p_Pdiff = cmb * Math.sqrt(rho / structure.getA(cmb));
        double p_SVdiff = cmb * Math.sqrt(rho / structure.getL(cmb));
        double p_SHdiff = cmb * Math.sqrt(rho / structure.getN(cmb));
        double p_Klimit = icb * Math.sqrt(rho / structure.getA(cmb));
        (pDiff = new Raypath(p_Pdiff, WOODHOUSE, MESH)).compute();
        (svDiff = new Raypath(p_SVdiff, WOODHOUSE, MESH)).compute();
        (shDiff = new Raypath(p_SHdiff, WOODHOUSE, MESH)).compute();
        (kLimit = new Raypath(p_Klimit, WOODHOUSE, MESH)).compute();
    }

    /**
     * @return Raypath of Pdiff
     */
    public Raypath getPdiff() {
        return pDiff;
    }

    /**
     * @return Raypath of SVdiff
     */
    public Raypath getSVdiff() {
        return svDiff;
    }

    /**
     * @return Raypath of SHdiff
     */
    public Raypath getSHdiff() {
        return shDiff;
    }

    /**
     * Look for the raypath which ray parameter is more than or equal to the input min and less than or equal to max
     * has non NaN path in P or S. Step of the ray parameter is {@link #MINIMUM_DELTA_P}.
     *
     * @param min min value of the search range
     * @param max max value of the search range
     * @return the first raypath which P, SV or SH exists. if the ray path does not exist,
     * raypath with rayparameter max value returns.
     */
    private Raypath lookForNextExistingRaypath(double min, double max) {
        for (double p = min; p < max; p += MINIMUM_DELTA_P) {
            Raypath raypath = new Raypath(p, WOODHOUSE, MESH);
            if (exists(raypath)) return raypath;
        }
        return new Raypath(max, WOODHOUSE, MESH);
    }

    private boolean exists(Raypath path) {
        double earthSurface = path.getStructure().earthRadius();
        System.out.println(
                "P:" + path.computeDelta(earthSurface, Phase.P) + " S" + path.computeDelta(earthSurface, Phase.S) +
                        " PcP" + path.computeDelta(earthSurface, Phase.PcP) + " ScS" +
                        path.computeDelta(earthSurface, Phase.ScS));
        System.out.println("P:" + path.computeDelta(earthSurface - 1, Phase.p) + " S" +
                path.computeDelta(earthSurface - 1, Phase.s));
        return !(Double.isNaN(path.computeDelta(earthSurface, Phase.P)) &&
                Double.isNaN(path.computeDelta(earthSurface, Phase.SV)) &&
                Double.isNaN(path.computeDelta(earthSurface, Phase.S)) &&
                Double.isNaN(path.computeDelta(earthSurface, Phase.PcP)) &&
                Double.isNaN(path.computeDelta(earthSurface, Phase.SVcS)) &&
                Double.isNaN(path.computeDelta(earthSurface, Phase.ScS)));
    }

    /**
     * TODO
     * Creates a catalogue.
     * when running into a ray path with all NaN. what should we do.
     */
    private void create() {
        double pMax = computeRayparameterLimit() + DELTA_P;
        // Compute raparameters for diffration phases.
        computeDiffraction();
        long time = System.nanoTime();
        System.err.println("Computing a catalogue. If you use the same model, the catalog is not computed anymore.");
        Raypath firstPath = new Raypath(0, WOODHOUSE, MESH);
        firstPath.compute();
        raypathList.add(firstPath);

        //simply add raypaths
        for (double p = DELTA_P; p < pMax; p += DELTA_P) {
            Raypath candidatePath = new Raypath(p, WOODHOUSE, MESH);
            candidatePath.compute();
            raypathList.add(candidatePath);
        }
        raypathList.add(pDiff);
        raypathList.add(svDiff);
        raypathList.add(shDiff);
        raypathList.add(kLimit);

        supplementRaypaths();

        System.err.println("Catalogue was made in " + Utilities.toTimeString(System.nanoTime() - time));
    }

    /**
     * @param sufficientCondition if 2 raypaths satisfies, it will not supplement a raypath in the two
     * @param supplement          creates a supplement Raypath
     * @return true if any Raypath added
     */
    private boolean supplementRaypathsFor(BiPredicate<Raypath, Raypath> sufficientCondition,
                                          BinaryOperator<Raypath> supplement) {
        List<Raypath> supplementList = new ArrayList<>();
        for (Raypath raypath = raypathList.first(); raypath != raypathList.last();
             raypath = raypathList.higher(raypath)) {
            Raypath higher = raypathList.higher(raypath);
            if (sufficientCondition.test(raypath, higher)) continue;
            supplementList.add(supplement.apply(raypath, higher));
        }
        supplementList.forEach(Raypath::compute);
        return raypathList.addAll(supplementList);
    }

    /**
     * If any of Raypaths has NaN for Phase 'p', the condition becomes true.
     *
     * @param phase  to be checked.
     * @param dDelta [rad] If the gap of deltas (epicentral distance) of Raypaths are equal to or less than this value,
     *               the condition becomes true
     */
    private BiPredicate<Raypath, Raypath> simplePredicate(Phase phase, double dDelta) {
        double r = getStructure().earthRadius();
        return (r1, r2) -> !(Math.abs(r1.computeDelta(r, phase) - r2.computeDelta(r, phase)) > dDelta);

    }

    private void supplementRaypaths() {
        double radian1 = Math.toRadians(.6); //TODO
        double eventR = getStructure().earthRadius();

        BinaryOperator<Raypath> centerRayparameterRaypath =
                (r1, r2) -> new Raypath((r1.getRayParameter() + r2.getRayParameter()) / 2, WOODHOUSE, MESH);

        //P wave 1 deg
        BiPredicate<Raypath, Raypath> pCondition = simplePredicate(Phase.P, radian1);
        while (supplementRaypathsFor(pCondition, centerRayparameterRaypath)) ;
        //PcP wave 1 deg
        BiPredicate<Raypath, Raypath> pcpCondition = simplePredicate(Phase.PcP, radian1);
        while (supplementRaypathsFor(pcpCondition, centerRayparameterRaypath)) ;
        //PKP wave 1 deg
        BiPredicate<Raypath, Raypath> pkpCondition = simplePredicate(Phase.PKP, radian1);
        while (supplementRaypathsFor(pkpCondition, centerRayparameterRaypath)) ;
        //PKIKP wave 1 deg
        BiPredicate<Raypath, Raypath> pkikpCondition = simplePredicate(Phase.PKIKP, radian1);
        while (supplementRaypathsFor(pkikpCondition, centerRayparameterRaypath)) ;
        //S wave 1 deg
        BiPredicate<Raypath, Raypath> sCondition = simplePredicate(Phase.S, radian1);
        while (supplementRaypathsFor(sCondition, centerRayparameterRaypath)) ;
        //ScS wave 1 deg
        BiPredicate<Raypath, Raypath> scsCondition = simplePredicate(Phase.ScS, radian1);
        while (supplementRaypathsFor(scsCondition, centerRayparameterRaypath)) ;
        //SKS wave 1 deg
        BiPredicate<Raypath, Raypath> sksCondition = simplePredicate(Phase.SKS, radian1);
        while (supplementRaypathsFor(sksCondition, centerRayparameterRaypath)) ;

        //SKIKS wave 1 deg TODO
        BiPredicate<Raypath, Raypath> skiksCondition = simplePredicate(Phase.SKIKS, radian1);
        while (supplementRaypathsFor(skiksCondition, centerRayparameterRaypath)) ;

    }

    void tmp_create() {
        create();
        supplementRaypaths();
//        supplementRaypathsFor((ae, a) -> true);
    }

    static RaypathCatalog tmp_getInstance() {
        return new RaypathCatalog(VelocityStructure.iprem(), ComputationalMesh.simple(VelocityStructure.iprem()),
                Math.toRadians(1));
    }


    private void closeDiff(Raypath diffPath) {
        double diffP = diffPath.getRayParameter();
        Raypath last = raypathList.last();
        Raypath diffMinus = new Raypath(diffP - MINIMUM_DELTA_P, WOODHOUSE, MESH);
        Raypath diffPlus = new Raypath(diffP + MINIMUM_DELTA_P, WOODHOUSE, MESH);
        diffMinus.compute();
        diffPlus.compute();
        for (double p = (diffP + last.getRayParameter()) / 2, nextP; ; p = nextP) {
            Raypath candidate = new Raypath(p, WOODHOUSE, MESH);
            candidate.compute();
            if (!closeEnough(raypathList.last(), candidate)) {
                raypathPool.add(candidate);
                nextP = (raypathList.last().getRayParameter() + candidate.getRayParameter()) / 2;
                continue;
            }
            raypathList.add(candidate);
            lookIntoPool();
            candidate = raypathList.last();
            if (!closeEnough(candidate, diffMinus)) {
                nextP = (candidate.getRayParameter() + diffP - MINIMUM_DELTA_P) / 2;
                continue;
            }
            raypathList.add(candidate);
            raypathList.add(diffMinus);
            raypathList.add(diffPlus);
            return;
        }
    }

    /**
     * Look for a raypath to be a next one for the {@link #raypathList}. If one
     * is found and another is also found for the next next one, all are added
     * recursively.
     *
     * @return If any good raypath in the pool
     */
    private boolean lookIntoPool() {
        boolean added = false;
        for (Raypath raypath : raypathPool)
            if (closeEnough(raypathList.last(), raypath)) {
                raypathList.add(raypath);
                added = true;
            }
        return added;
    }

    /**
     * Criterion for the catalog is {@link #D_DELTA} so far in both P and S
     * wave. The ray parameter of raypath1 must be smaller than that of
     * raypath2, otherwise, false returns.
     * If the residual of the two ray parameters is less than {@link #MINIMUM_DELTA_P}, true returns.
     * <p>
     * Both raypaths must be computed before this method.
     * At least one of P, PcP, S, ScS must exist in raypath1.
     *
     * @param raypath1 to be checked
     * @param raypath2 to be checked
     * @return If the paths are similar enough.
     */
    private boolean closeEnough(Raypath raypath1, Raypath raypath2) {
        if (raypath2.getRayParameter() <= raypath1.getRayParameter()) return false;
        if (raypath2.getRayParameter() - raypath1.getRayParameter() < MINIMUM_DELTA_P) return true;
        double earthRadius = WOODHOUSE.getStructure().earthRadius();

        //case P exists
        double p1 = raypath1.computeDelta(earthRadius, Phase.P);
        double p2 = raypath2.computeDelta(earthRadius, Phase.P);
        //case PcP exists
        if (Double.isNaN(p1)) {
            p1 = raypath1.computeDelta(earthRadius, Phase.PcP);
            p2 = raypath2.computeDelta(earthRadius, Phase.PcP);
        }
        if (Double.isNaN(p1) ^ Double.isNaN(p2)) return false;
        if (D_DELTA < Math.abs(p1 - p2)) return false;

        //case SV exists
        double sv1 = raypath1.computeDelta(earthRadius, Phase.SV);
        double sv2 = raypath2.computeDelta(earthRadius, Phase.SV);
        //case ScS(SV) exists
        if (Double.isNaN(sv1)) {
            sv1 = raypath1.computeDelta(earthRadius, Phase.SVcS);
            sv2 = raypath2.computeDelta(earthRadius, Phase.SVcS);
        }
        if (Double.isNaN(sv1) ^ Double.isNaN(sv2)) return false;
        if (D_DELTA < Math.abs(sv1 - sv2)) return false;

        //case SH exists
        double sh1 = raypath1.computeDelta(earthRadius, Phase.S);
        double sh2 = raypath2.computeDelta(earthRadius, Phase.S);
        //case ScS(SH) exists
        if (Double.isNaN(sh1)) {
            sh1 = raypath1.computeDelta(earthRadius, Phase.ScS);
            sh2 = raypath2.computeDelta(earthRadius, Phase.ScS);
        }
        if (Double.isNaN(sh1) ^ Double.isNaN(sh2)) return false;

        if (Double.isNaN(p1) && Double.isNaN(sv1) && Double.isNaN(sh1)) return false;
        return Double.isNaN(sh1) || Math.abs(sh1 - sh2) <= D_DELTA;
    }

    /**
     * Computes ray parameter p with which q<sub>&tau;</sub> = 0 at the next grid to the earth
     * surface for P, SV and SH. Returns the maximum value of them. (basically the one of S)
     * <p>
     * P &rarr; r*(&rho;/A)<sup>1/2</sup> <br>
     * SV &rarr; r*(&rho;/L)<sup>1/2</sup> <br>
     * SH &rarr; r*(&rho;/N)<sup>1/2</sup> <br>
     *
     * @return maximum ray parameter
     * @see "Woodhouse (1981)"
     */
    private double computeRayparameterLimit() {
        VelocityStructure structure = WOODHOUSE.getStructure();
        double r = structure.earthRadius();
        double rho = structure.getRho(r);
        double p = r * Math.sqrt(rho / structure.getA(r));
        double sv = r * Math.sqrt(rho / structure.getL(r));
        double sh = r * Math.sqrt(rho / structure.getN(r));
        return Math.max(Math.max(p, sv), sh);
    }

    /**
     * Assume that there is a regression curve f(&Delta;) = p(ray parameter) for
     * the small range. The function f is assumed to be a polynomial function.
     * The degree of the function is 1.<S>depends on the number of the input raypaths.</S>
     *
     * @param targetPhase   target phase
     * @param eventR        [km] radius of event
     * @param targetDelta   [rad] epicentral distance to get T for
     * @param relativeAngle if the targetDelta is a relative value.
     * @param raypaths      Polynomial interpolation is done with these. All the raypaths
     *                      must be computed.
     * @return travel time [s] for the target Delta estimated by the polynomial
     * interpolation with the raypaths.
     */
    private Raypath interpolateRaypath(Phase targetPhase, double eventR, double targetDelta, boolean relativeAngle,
                                       Raypath... raypaths) {
        WeightedObservedPoints deltaP = new WeightedObservedPoints();
        for (Raypath raypath : raypaths) {
            double delta = raypath.computeDelta(eventR, targetPhase);
            if (relativeAngle) delta = toRelativeAngle(delta);
            deltaP.add(delta, raypath.getRayParameter());
        }
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(2);
        PolynomialFunction pf = new PolynomialFunction(fitter.fit(deltaP.toList()));
        Raypath ray = new Raypath(pf.value(targetDelta), WOODHOUSE, MESH);
        ray.compute();
        return ray;
    }

    /**
     * @param path    the path to the write file
     * @param options if any
     * @throws IOException If an I/O error happens. it throws error.
     */
    public void write(Path path, OpenOption... options) throws IOException {
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(path, options))) {
            o.writeObject(this);
        }
    }

    /**
     * @param targetPhase   target phase
     * @param eventR        [km] event radius
     * @param targetDelta   [rad] target &Delta;
     * @param relativeAngle if the input targetDelta is a relative angle.
     * @return Arrays of raypaths which epicentral distances are close to the targetDelta. Never returns null. zero length array is possible.
     */
    public Raypath[] searchPath(Phase targetPhase, double eventR, double targetDelta, boolean relativeAngle) {
        if (targetPhase.isDiffracted()) return new Raypath[]{targetPhase.toString().contains("Pdiff") ? getPdiff() :
                (targetPhase.isPSV() ? getSVdiff() : getSHdiff())};
        Raypath[] raypaths = getRaypaths();
//        System.err.println("Looking for Phase:" + targetPhase + ", \u0394[\u02da]:" + TODO
//                Precision.round(Math.toDegrees(targetDelta), 4));
        if (targetDelta < 0) throw new IllegalArgumentException("A targetDelta must be non-negative.");
        if (relativeAngle && Math.PI < targetDelta) throw new IllegalArgumentException(
                "When you search paths for a relative angle, a targetDelta must be pi or less.");
        List<Raypath> pathList = new ArrayList<>();
        for (int i = 0; i < raypaths.length - 1; i++) {
            Raypath rayI = raypaths[i];
            Raypath rayP = raypaths[i + 1];
            double deltaI = rayI.computeDelta(eventR, targetPhase);
            double deltaP = rayP.computeDelta(eventR, targetPhase);
            if (Double.isNaN(deltaI) || Double.isNaN(deltaP)) continue;
            if (relativeAngle) {
                deltaI = toRelativeAngle(deltaI);
                deltaP = toRelativeAngle(deltaP);
            }
            if (0 < (deltaI - targetDelta) * (deltaP - targetDelta)) continue;
            Raypath rayC = new Raypath((rayI.getRayParameter() + rayP.getRayParameter()) / 2, WOODHOUSE, MESH);
            rayC.compute();
            if (Double.isNaN(rayC.computeDelta(eventR, targetPhase))) continue;
            Raypath rayIn = interpolateRaypath(targetPhase, eventR, targetDelta, relativeAngle, rayI, rayC, rayP);
            if (Double.isNaN(rayIn.computeDelta(eventR, targetPhase))) continue;
            pathList.add(rayIn);
        }
        return pathList.toArray(new Raypath[0]);
    }

    /**
     * @param targetPhase   target phase
     * @param eventR        [km] event radius
     * @param targetDelta   [rad] target &Delta;
     * @param relativeAngle if the targetDelta is a relative value.
     * @return [s] arrays of travel times.
     */
    public double[] searchTime(Phase targetPhase, double eventR, double targetDelta, boolean relativeAngle) {
        System.err.println("Looking for Phase:" + targetPhase + ", \u0394[\u02da]:" +
                Precision.round(Math.toDegrees(targetDelta), 4));
        List<Double> timeList = new ArrayList<>();
        Raypath[] raypaths = getRaypaths();
        if (targetDelta < 0) throw new IllegalArgumentException("A targetDelta must be non-negative.");
        if (relativeAngle && Math.PI < targetDelta) throw new IllegalArgumentException(
                "When you search paths for a relative angle, a targetDelta must be pi or less.");
        for (int i = 0; i < raypaths.length - 1; i++) {
            Raypath rayI = raypaths[i];
            Raypath rayP = raypaths[i + 1];
            double deltaI = rayI.computeDelta(eventR, targetPhase);
            double deltaP = rayP.computeDelta(eventR, targetPhase);
            if (Double.isNaN(deltaI) || Double.isNaN(deltaP)) continue;
            if (relativeAngle) {
                deltaI = toRelativeAngle(deltaI);
                deltaP = toRelativeAngle(deltaP);
            }
            if (0 < (deltaI - targetDelta) * (deltaP - targetDelta)) continue;
            Raypath rayC = new Raypath((rayI.getRayParameter() + rayP.getRayParameter()) / 2, WOODHOUSE, MESH);
            rayC.compute();
            Raypath rayIn = interpolateRaypath(targetPhase, eventR, targetDelta, relativeAngle, rayI, rayC, rayP);
            double deltaC = rayC.computeDelta(eventR, targetPhase);
            double deltaIn = rayIn.computeDelta(eventR, targetPhase);
            if (Double.isNaN(deltaC) || Double.isNaN(deltaIn)) continue;
            timeList.add(interpolateTraveltime(targetPhase, eventR, targetDelta, rayI, rayC, rayP, rayIn));
        }
        return timeList.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * The returning travel time is computed by the input raypath0 and the 2 raypaths with the closest larger and smaller rayparameters.
     *
     * @param targetPhase   to look for
     * @param eventR        [km] radius of the source
     * @param targetDelta   [rad]
     * @param relativeAngle if the targetDelta is a relative value.
     * @param raypath0      source of raypath
     * @return travel time for the targetDelta [s]
     */
    double travelTimeByThreePointInterpolate(Phase targetPhase, double eventR, double targetDelta,
                                             boolean relativeAngle, Raypath raypath0) {
        if (targetDelta < 0) throw new IllegalArgumentException("A targetDelta must be non-negative.");
        if (relativeAngle && Math.PI < targetDelta) throw new IllegalArgumentException(
                "When you search paths for a relative angle, a targetDelta must be pi or less.");

        double delta0 = raypath0.computeDelta(eventR, targetPhase);
        Raypath lower = raypathList.lower(raypath0);
        Raypath higher = raypathList.higher(raypath0);
        double lowerDelta = lower.computeDelta(eventR, targetPhase);
        double higherDelta = higher.computeDelta(eventR, targetPhase);
        if (Double.isNaN(lowerDelta) || Double.isNaN(higherDelta)) return Double.NaN;
        if (relativeAngle) {
            lowerDelta = toRelativeAngle(lowerDelta);
            higherDelta = toRelativeAngle(higherDelta);
        }
        WeightedObservedPoints pTime = new WeightedObservedPoints();
        pTime.add(delta0, raypath0.computeT(eventR, targetPhase));
        pTime.add(lowerDelta, lower.computeT(eventR, targetPhase));
        pTime.add(higherDelta, higher.computeT(eventR, targetPhase));
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(1);
        PolynomialFunction pf = new PolynomialFunction(fitter.fit(pTime.toList()));
        return pf.value(targetDelta);
    }

    /**
     * The returning travel time is computed by the input raypath0 and the 2 raypaths with the closest larger and smaller rayparameters.
     *
     * @param targetPhase   to look for
     * @param eventR        [km] radius of the source
     * @param targetDelta   [rad]
     * @param relativeAngle if the targetDelta is a relative value.
     * @param raypath0      source of raypath
     * @return travel time for the targetDelta [s]
     */
    double rayParameterByThreePointInterpolate(Phase targetPhase, double eventR, double targetDelta,
                                               boolean relativeAngle, Raypath raypath0) {
        if (targetDelta < 0) throw new IllegalArgumentException("A targetDelta must be non-negative.");
        if (relativeAngle && Math.PI < targetDelta) throw new IllegalArgumentException(
                "When you search paths for a relative angle, a targetDelta must be pi or less.");

        double delta0 = raypath0.computeDelta(eventR, targetPhase);
        Raypath lower = raypathList.lower(raypath0);
        Raypath higher = raypathList.higher(raypath0);
        double lowerDelta = lower.computeDelta(eventR, targetPhase);
        double higherDelta = higher.computeDelta(eventR, targetPhase);

        if (Double.isNaN(lowerDelta) || Double.isNaN(higherDelta)) return Double.NaN;
        if (relativeAngle) {
            lowerDelta = toRelativeAngle(lowerDelta);
            higherDelta = toRelativeAngle(higherDelta);
        }

        WeightedObservedPoints distOfP = new WeightedObservedPoints();
        distOfP.add(delta0, raypath0.getRayParameter());
        distOfP.add(lowerDelta, lower.getRayParameter());
        distOfP.add(higherDelta, higher.getRayParameter());
        PolynomialCurveFitter fitter = PolynomialCurveFitter.create(2);
        PolynomialFunction pf = new PolynomialFunction(fitter.fit(distOfP.toList()));
        return pf.value(targetDelta);

    }

}
