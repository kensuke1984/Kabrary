package io.github.kensuke1984.anisotime;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Phase name.
 * </p>
 * <p>
 * This class is <b>immutable</b>.
 * <p>
 * Waveform is now digitalized. Waveform is divided into parts. each part has up
 * or downgoing, P or S and the partition in which waveform exists.
 * <p>
 * ???PdiffXX and ???SdiffXX can be used. XX is positive double XX is
 * diffractionAngle diff must be the last part.
 * HOGEdiff is actually a bouncing wave at &plusmn; &epsilon; of a boundary
 * <p>
 * Diffraction can only happen at the final part.  TODO arbitrary
 * <p>
 * Numbers in a name.
 * </p>
 * <dl>
 * <dt>redundancy</dt>
 * <dd>A number in parentheses indicates repetition of
 * arguments. S(2K)S &rarr; SKKS; P(2S)P &rarr; PSSP;<br>
 * Multiple parentheses are accepted. (2ScS)(2SKS) &rarr; ScSScSSKSSKS<br>
 * Nesting is not allowed. (2S(2K)S) &rarr; IllegalArgumentException</dd>
 * <dt>underside reflection</dt>
 * <dd>^???  for the reflection at a depth of ??? km.</dd>
 * <dt>topside reflection</dt>
 * <dd>v???  for the reflection at a depth of ??? km.</dd>
 * <dt>topside diffraction</dt>
 * <dd>under construction</dd>
 * </dl>
 * P and S after transmission strictly are downward, and p and s are upward.
 *
 * @author Kensuke Konishi
 * @version 0.1.10.2.1
 * <p>
 * TODO P2PPcP no exist but exist
 */
public class Phase implements Serializable {

    // no use letters
    private static final Pattern others = Pattern.compile("[a-zA-Z&&[^cdfipsvIJKPS]]|[\\W&&[^.^]]");

    // Pattern for repetition
    private static final Pattern repetition = Pattern.compile("\\((\\d*)([^\\d]+?)\\)");

    // first letter is sSpP
    private static final Pattern firstLetter = Pattern.compile("^[^psSP]");
    // static final letter is psSP
    private static final Pattern finalLetter = Pattern.compile("[^psSP]$");

    // p s must be the first letter or follow a number (depth of reflection or interaction).
    private static final Pattern ps = Pattern.compile("\\D[ps]");
    // c must be next to PS
    private static final Pattern nextC = Pattern.compile("[^PS]c|c[^PS]|[^PSps\\d][PS]c|c[PS][^\\^\\dPS]");
    private static final Pattern nextK = Pattern.compile("[^PSKiIJ]K[^PSKiIJ]|[^\\dpsPS][PS]K|K[PS][^\\^\\dPS]");
    private static final Pattern nextJ = Pattern.compile("[^\\dIJK][IJ]|[IJ][^\\^\\dvIJK]");
    private static final Pattern smallI = Pattern.compile("[^Kd]i|i[^fK]|[^PSK]Ki|iK[^KPS]");
    // phase turning R is above the CMB
    private static final Pattern mantleP = Pattern.compile("^P$|^P[PS]|[psPS]P$|[psPS]P[PS]");
    private static final Pattern mantleS = Pattern.compile("^S$|^S[PS]|[psPS]S$|[psPS]S[PS]");
    // diffraction phase
    private static final Pattern pDiff = Pattern.compile("Pdiff\\d*(\\.\\d+)?$");
    private static final Pattern sDiff = Pattern.compile("Sdiff\\d*(\\.\\d+)?$");
    private static final Pattern diffRule = Pattern.compile("diff.+diff|P.*Pdiff|S.*Sdiff|diff.*[^\\d]$");
    // phase reflected at the cmb
    private static final Pattern cmbP = Pattern.compile("Pc|cP");
    private static final Pattern cmbS = Pattern.compile("Sc|cS");
    // phase turning R r <cmb
    private static final Pattern outercoreP = Pattern.compile("PK|KP");
    private static final Pattern outercoreS = Pattern.compile("SK|KS");

    // phase turning R icb < r < cmb, i.e., the phase does not go to inner core.
    // if a phase name contains "K(depth)K", the phase does not go to inner core.
    private static final Pattern outercore = Pattern.compile("K\\d|[PSK]K[\\^PSK]");

    // nesting of parenthesis is prohibited
    private static Pattern nestParentheses = Pattern.compile("\\([^\\)]*\\(|\\)[^\\(]*\\)");
    //Number rules as TauP rule 4-6
    //*KxxxK* K wave must go deeper than xxx. xxx is a depth (distance from the Earth surface).
    private static Pattern outercoreSpecification = Pattern.compile("K[\\d\\.]++[^K]");
    private static Pattern mantleSpecification = Pattern.compile("[pPsS]\\d++(\\.\\d++)?+[^pPsS]");
    //interactions without reflection
    //Number rule as TauP rule 7
    private static Pattern bottomSide = Pattern.compile("[pPsS]\\^\\d++(\\.\\d++)?+[^PS]");
    private static Pattern topSide =
            Pattern.compile("[ps]v|[PS]v\\d++(\\.\\d++)?+[^pPsS]|[PS]v\\d++(\\.\\d++)?+[pPsS]c");
    private static Pattern bothSide =
            Pattern.compile("K[\\^|v]\\d++(\\.\\d++)?+[^K]|[IJ][\\^|v]\\d++(\\.\\d++)?+[^IJ]");

    // frequently use
    public static final Phase p = create("p");
    public static final Phase P = create("P");
    public static final Phase PcP = create("PcP");
    public static final Phase PKP = create("PKP");
    public static final Phase PKiKP = create("PKiKP");
    public static final Phase PKIKP = create("PKIKP");
    public static final Phase Pdiff = create("Pdiff");
    public static final Phase s = create("s");
    public static final Phase S = create("S");
    public static final Phase SV = create("S", true);
    public static final Phase ScS = create("ScS");
    public static final Phase SVcS = create("ScS", true);
    public static final Phase SKS = create("SKS");
    public static final Phase SKiKS = create("SKiKS");
    public static final Phase SKIKS = create("SKIKS");
    public static final Phase SKJKS = create("SKJKS");
    public static final Phase Sdiff = create("Sdiff");
    public static final Phase SVdiff = create("Sdiff", true);

    /**
     * If this is P-SV(true) or SH(false).
     */
    private final boolean PSV;
    /**
     * (input) phase name e.g. S(mK)S
     */
    private final String PHASENAME;
    /**
     * name for parsing e.g. SKKKKKS
     */
    private final String EXPANDED_NAME;
    /**
     * nome for displaying (Sdiff for Sdiff??)
     */
    private final String DISPLAY_NAME;


    private PathPart[] passParts;

    /**
     * @param phaseName    of the phase (e.g. SKKS)
     * @param expandedName of the phase (e.g. S2KS)
     * @param psv          true:P-SV, false:SH
     */
    private Phase(String phaseName, String expandedName, boolean psv) {
        PHASENAME = phaseName;
        EXPANDED_NAME = expandedName;
        DISPLAY_NAME = simplify(phaseName);
        PSV = psv;
        countParts();
    }

    private static String simplify(String phaseName) {
        String simple = phaseName;
        //*diff???? -> *diff
        Pattern compile = Pattern.compile("diff[\\d|\\.]+");
        simple = compile.matcher(simple).replaceAll("diff");
        return simple;
    }

    /**
     * Input names must follow some rules.
     * If you want to express a repeating phase like KK, you have to write (2K), note that
     * KK is also accepted.
     *
     * @param name phase name
     * @param sv   true:P-SV, false:SH. If the phase contains "P" or "K", it is
     *             ignored and always is true.
     * @return phase for input
     * @throws IllegalArgumentException if the phase is invalid
     */
    public static Phase create(String name, boolean... sv) {
        if (1 < sv.length) throw new IllegalArgumentException("SV or not");
        String expandedName = expandParentheses(name);
        if (isValid(expandedName))
            return new Phase(name, expandedName, name.contains("P") || name.contains("K") || (sv.length != 0 && sv[0]));
        else throw new IllegalArgumentException("Invalid phase name " + name);
    }

    /**
     * @param name phase name before expansion. e.g. S(2K)S
     * @return an expanded phase name e.g. SKKS
     */
    private static String expandParentheses(String name) {
        if (nestParentheses.matcher(name).find())
            throw new IllegalArgumentException("Invalid phase name (Parentheses nesting is detected)" + name);
        try {
            Matcher m = repetition.matcher(name);
            String expanded = name;
            while (m.find()) {
                String numStr = m.group(1);
                String replace = m.group(2);
                int n = !numStr.isEmpty() ? Integer.parseInt(numStr) : 1;
                for (int i = 0; i < n - 1; i++)
                    replace += m.group(2);
                expanded = expanded.replace(m.group(), replace);
            }
            return expanded;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid phase name " + name);
        }
    }

    /**
     * @param dStart index of diff's d
     * @return angle after 'diff'
     */
    private String readAngle(int dStart) {
        int index = dStart + 4;
        if (!EXPANDED_NAME.substring(dStart, index).equals("diff")) throw new RuntimeException("Problem around diff??");
        while (index < EXPANDED_NAME.length() &&
                (Character.isDigit(EXPANDED_NAME.charAt(index)) || EXPANDED_NAME.charAt(index) == '.')) index++;
        return EXPANDED_NAME.substring(dStart + 4, index);
    }

    /**
     * consider adding next
     * counts the number of parts in the phase.
     * P,S,PcP,P410P,Pv410S: 2, pP,P410s: 3, PP,P^410P: 4
     * <p>
     * <p>
     * PcP, ?c?, K
     */
    private void countParts() {
        List<PathPart> partList = new ArrayList<>();
        partList.add(Located.EMISSION);
        for (int i = 0; i < EXPANDED_NAME.length(); i++) {
            PathPart beforePart = partList.get(partList.size() - 1);
            GeneralPart secondLast = null;
            if (1 < partList.size() && partList.get(partList.size() - 2) instanceof GeneralPart)
                secondLast = (GeneralPart) partList.get(partList.size() - 2);
            char nextChar = i + 1 == EXPANDED_NAME.length() ? 10 : EXPANDED_NAME.charAt(i + 1);
            boolean hatFlag = nextChar == '^';
            boolean vFlag = nextChar == 'v';
            char beforeChar = i == 0 ? 10 : EXPANDED_NAME.charAt(i - 1);
            char currentChar = EXPANDED_NAME.charAt(i);
            int start = hatFlag || vFlag ? i + 2 : i + 1;
            while (Character.isDigit(nextChar) || nextChar == 'v' || nextChar == '^' || nextChar == '.') {
                i++;
                nextChar = i + 1 == EXPANDED_NAME.length() ? 10 : EXPANDED_NAME.charAt(i + 1);
            }
            int end = i + 1;
            double nextDepth = start == end ? Double.NaN : Double.parseDouble(EXPANDED_NAME.substring(start, end));
            PassPoint innerPoint, outerPoint;
            switch (currentChar) {
                case 'c':
                case 'i':
                    break;
                case 'p':
                case 's':
                    PhasePart ps = currentChar == 'p' ? PhasePart.P : (PSV ? PhasePart.SV : PhasePart.SH);
                    if (beforePart.isEmission() || beforePart.isTransmission() || beforePart.isTopsideReflection()) {
                        double innerDepth = 0;
                        innerPoint = beforePart.isEmission() ? PassPoint.SEISMIC_SOURCE : PassPoint.OTHER;
                        if (!beforePart.isEmission()) innerDepth =
                                beforePart.isTransmission() ? secondLast.getOuterDepth() : secondLast.getInnerDepth();
                        if (vFlag) throw new RuntimeException("Problem around " + currentChar);
                        else if (hatFlag || !Double.isNaN(nextDepth)) {
                            partList.add(
                                    new GeneralPart(ps, false, innerDepth, nextDepth, innerPoint, PassPoint.OTHER));
                            if (hatFlag) partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            else partList.add(Arbitrary.createTransmission(nextDepth));
                            continue;
                        }
                        //under here, no interactions.
                        switch (nextChar) {
                            case 10:
                            case 'P':
                            case 'S':
                                partList.add(
                                        new GeneralPart(ps, false, innerDepth, 0, innerPoint, PassPoint.EARTH_SURFACE));
                                if (nextChar != 10) partList.add(Located.SURFACE_REFLECTION);
                                continue;
                            default:
                                throw new RuntimeException("Problem around p");
                        }
                    } else throw new RuntimeException("Problem around \"p\"");
//switching current char
                case 'S':
                case 'P':
                    PhasePart PS = currentChar == 'P' ? PhasePart.P : (PSV ? PhasePart.SV : PhasePart.SH);
                    if (beforePart.isEmission() || beforePart.isTransmission() || beforePart.isBottomsideReflection()) {
                        outerPoint = beforePart.isEmission() ? PassPoint.SEISMIC_SOURCE :
                                ((Located) beforePart).getPassPoint();
                        double outerDepth = 0;

                        if (beforePart.isTransmission() && !secondLast.isDownward())
                            throw new RuntimeException("Problem around " + currentChar);

                        if (!beforePart.isEmission()) outerDepth =
                                secondLast.isDownward() ? secondLast.getInnerDepth() : secondLast.getOuterDepth();
                        if (vFlag) {
                            partList.add(new GeneralPart(PS, true, nextDepth, outerDepth, PassPoint.OTHER, outerPoint));
                            partList.add(Arbitrary.createTopsideReflection(nextDepth));
                            continue;
                        } else if (hatFlag) {
                            partList.add(new GeneralPart(PS, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                            partList.add(Located.BOUNCE);
                            partList.add(
                                    new GeneralPart(PS, false, 0, nextDepth, PassPoint.BOUNCE_POINT, PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth)) {
                            if (nextDepth < outerDepth) throw new RuntimeException("Problem around " + currentChar);
                            switch (nextChar) {
                                case 'p':
                                case 's':
                                    partList.add(new GeneralPart(PS, true, 0, outerDepth, PassPoint.BOUNCE_POINT,
                                            outerPoint));
                                    partList.add(Located.BOUNCE);
                                    partList.add(new GeneralPart(PS, false, 0, nextDepth, PassPoint.BOUNCE_POINT,
                                            PassPoint.OTHER));
                                    partList.add(Arbitrary.createTransmission(nextDepth));
                                    continue;
                                case 'P':
                                case 'S':
                                    partList.add(new GeneralPart(PS, true, nextDepth, outerDepth, PassPoint.OTHER,
                                            outerPoint));
                                    partList.add(Arbitrary.createTransmission(nextDepth));
                                    continue;
                                default:
                                    throw new RuntimeException("Problem around P");
                            }
                        }
                        //under here, no interactions
                        switch (nextChar) {
                            case 10:
                            case 'P':
                            case 'S':
//                                if (beforePart.isTransmission() && !secondLast.isDownward())
//                                    throw new RuntimeException("Problem around P");
//                                    partList.add(
//                                        new GeneralPart(PS, false, secondLast.getOuterDepth(), 0, PassPoint.OTHER,
//                                                PassPoint.EARTH_SURFACE));
//                                else {
                                partList.add(
                                        new GeneralPart(PS, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                                partList.add(Located.BOUNCE);
                                partList.add(new GeneralPart(PS, false, 0, 0, PassPoint.BOUNCE_POINT,
                                        PassPoint.EARTH_SURFACE));
//                                }
                                if (nextChar != 10) partList.add(Located.SURFACE_REFLECTION);
                                continue;
                            case 'c':
                            case 'K':
                                partList.add(new GeneralPart(PS, true, 0, outerDepth, PassPoint.CMB, outerPoint));
                                partList.add(nextChar == 'c' ? Located.REFLECTION_C : Located.CMB_PENETRATION);
                                continue;
                            case 'd'://TODO  Passpoint may be another point (not CMB)
                                partList.add(
                                        new GeneralPart(PS, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                                String angle = readAngle(i + 1);
                                i += 4 + angle.length();
                                //TODO arbitrary depth?
                                partList.add(LocatedDiffracted.createCMBDiffraction(true, PS,
                                        Math.toRadians(angle.isEmpty() ? 0 : Double.parseDouble(angle))));
                                if (i + 1 < EXPANDED_NAME.length()) throw new IllegalArgumentException(
                                        "ANISOtime now cannot handle complex diffraction wave");
                                partList.add(new GeneralPart(PS, false, 0, 0, PassPoint.BOUNCE_POINT,
                                        PassPoint.EARTH_SURFACE));
                                continue;
                            default:
                                throw new RuntimeException("Problem around P");
                        }
                    } else if (beforePart.isTopsideReflection() || beforePart.isPenetration()) {
                        innerPoint = ((Located) beforePart).getPassPoint();
                        double innerDepth = secondLast.getInnerDepth();
                        if (vFlag) {
                            throw new RuntimeException("Problem around Pv");
                        } else if (hatFlag) {
                            partList.add(
                                    new GeneralPart(PS, false, innerDepth, nextDepth, innerPoint, PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth)) {
                            partList.add(
                                    new GeneralPart(PS, false, innerDepth, nextDepth, innerPoint, PassPoint.OTHER));
                            partList.add(Arbitrary.createTransmission(nextDepth));
                            continue;
                        }
                        switch (nextChar) {
                            case 'P':
                            case 'S':
                            case 10:
                                partList.add(
                                        new GeneralPart(PS, false, innerDepth, 0, innerPoint, PassPoint.EARTH_SURFACE));
                                if (nextChar != 10) partList.add(Located.SURFACE_REFLECTION);
                                continue;
                            default:
                                throw new RuntimeException("Problem around P");
                        }
                    } else throw new RuntimeException("unexpected");
//switching current char
                case 'J':
                case 'I':
                    PhasePart ij = currentChar == 'I' ? PhasePart.I : PhasePart.JV;
                    if (beforePart.isPenetration() || beforePart.isBottomsideReflection()) {
                        outerPoint = ((Located) beforePart).getPassPoint();
                        double outerDepth = 0;
                        if (beforePart.isBottomsideReflection()) outerDepth = secondLast.getOuterDepth();
                        if (vFlag) {
                            partList.add(new GeneralPart(ij, true, nextDepth, outerDepth, PassPoint.OTHER, outerPoint));
                            partList.add(Arbitrary.createTopsideReflection(nextDepth));
                            continue;
                        } else if (hatFlag) {
                            partList.add(new GeneralPart(ij, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                            partList.add(Located.BOUNCE);
                            partList.add(
                                    new GeneralPart(ij, false, 0, nextDepth, PassPoint.BOUNCE_POINT, PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth))
                            throw new RuntimeException("Transmission of I is prohibited.");
                        switch (nextChar) {
                            case 'K':
                                partList.add(
                                        new GeneralPart(ij, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                                partList.add(Located.BOUNCE);
                                partList.add(new GeneralPart(ij, false, 0, nextDepth, PassPoint.BOUNCE_POINT,
                                        PassPoint.ICB));
                                partList.add(Located.ICB_PENETRATION);
                                continue;
                            case 'I':
                            case 'J':
                                partList.add(
                                        new GeneralPart(ij, true, 0, outerDepth, PassPoint.BOUNCE_POINT, outerPoint));
                                partList.add(Located.BOUNCE);
                                partList.add(new GeneralPart(ij, false, 0, nextDepth, PassPoint.BOUNCE_POINT,
                                        PassPoint.ICB));
                                partList.add(Located.INNERCORE_SIDE_REFLECTION);
                                continue;
                            default:
                                throw new RuntimeException("Problem around I");
                        }
                    } else if (beforePart.isTopsideReflection()) {
                        innerPoint = ((Located) beforePart).getPassPoint();
                        double innerDepth = secondLast.getInnerDepth();
                        if (vFlag) throw new RuntimeException("Problem around I");
                        else if (hatFlag) {
                            partList.add(
                                    new GeneralPart(ij, false, innerDepth, nextDepth, innerPoint, PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth))
                            throw new RuntimeException("Transmission of I is prohibited.");
                        switch (nextChar) {
                            case 'I':
                            case 'J':
                            case 'K':
                                partList.add(new GeneralPart(ij, false, innerDepth, 0, innerPoint, PassPoint.ICB));
                                partList.add(
                                        nextChar == 'K' ? Located.ICB_PENETRATION : Located.INNERCORE_SIDE_REFLECTION);
                                continue;
                            default:
                                throw new RuntimeException("Problem around I");
                        }
                    } else throw new RuntimeException("Problem around I");
// switching current char
                case 'K':
                    if (beforePart.isPenetration()) {
                        switch (beforeChar) {
                            case 'P':
                            case 'S':
                                if (vFlag) {
                                    partList.add(new GeneralPart(PhasePart.K, true, nextDepth, 0, PassPoint.OTHER,
                                            PassPoint.CMB));
                                    partList.add(Arbitrary.createTopsideReflection(nextDepth));
                                    continue;
                                } else if (hatFlag) {
                                    partList.add(new GeneralPart(PhasePart.K, true, 0, 0, PassPoint.BOUNCE_POINT,
                                            PassPoint.CMB));
                                    partList.add(Located.BOUNCE);
                                    partList.add(
                                            new GeneralPart(PhasePart.K, false, 0, nextDepth, PassPoint.BOUNCE_POINT,
                                                    PassPoint.OTHER));
                                    partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                                    continue;
                                } else if (!Double.isNaN(nextDepth))
                                    throw new RuntimeException("Transmission of K is prohibited.");
                                //under here, no interactions. in other words, penetrating or bouncing.
                                switch (nextChar) {
                                    case 'K':
                                    case 'P':
                                    case 'S':
                                        partList.add(new GeneralPart(PhasePart.K, true, 0, 0, PassPoint.BOUNCE_POINT,
                                                PassPoint.CMB));
                                        partList.add(Located.BOUNCE);
                                        partList.add(new GeneralPart(PhasePart.K, false, 0, 0, PassPoint.BOUNCE_POINT,
                                                PassPoint.CMB));
                                        partList.add(nextChar == 'K' ? Located.REFLECTION_K : Located.CMB_PENETRATION);
                                        continue;
                                    case 'i':
                                    case 'I':
                                    case 'J':
                                        partList.add(
                                                new GeneralPart(PhasePart.K, true, 0, 0, PassPoint.ICB, PassPoint.CMB));
                                        partList.add(nextChar == 'i' ? Located.OUTERCORE_SIDE_REFLECTION :
                                                Located.ICB_PENETRATION);
                                        continue;
                                    default:
                                        throw new RuntimeException("Problem around K");
                                }
                                //switching before char
                            case 'I':
                            case 'J':
                                if (vFlag) {
                                    throw new RuntimeException("Problem around K");
                                } else if (hatFlag) {
                                    partList.add(new GeneralPart(PhasePart.K, false, 0, nextDepth, PassPoint.ICB,
                                            PassPoint.OTHER));
                                    partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                                    continue;
                                } else if (!Double.isNaN(nextDepth))
                                    throw new RuntimeException("Transmission of K is prohibited.");
                                //under here, no interactions. in other words, penetrating or bouncing.
                                switch (nextChar) {
                                    case 'K':
                                    case 'P':
                                    case 'S':
                                        partList.add(new GeneralPart(PhasePart.K, false, 0, 0, PassPoint.ICB,
                                                PassPoint.CMB));
                                        partList.add(nextChar == 'K' ? Located.REFLECTION_K : Located.CMB_PENETRATION);
                                        continue;
                                    default:
                                        throw new RuntimeException("Problem around K");
                                }
                            default:
                                throw new RuntimeException("Problem around K");
                        }
                    } else if (beforePart.isBottomsideReflection()) {
                        outerPoint = ((Located) beforePart).getPassPoint();
                        double outerDepth =
                                beforePart.isTransmission() ? secondLast.getInnerDepth() : secondLast.getOuterDepth();
                        if (vFlag) {
                            partList.add(new GeneralPart(PhasePart.K, true, nextDepth, 0, PassPoint.OTHER, outerPoint));
                            partList.add(Arbitrary.createTopsideReflection(nextDepth));
                            continue;
                        } else if (hatFlag) {
                            partList.add(new GeneralPart(PhasePart.K, true, 0, outerDepth, PassPoint.BOUNCE_POINT,
                                    PassPoint.OTHER));
                            partList.add(Located.BOUNCE);
                            partList.add(new GeneralPart(PhasePart.K, false, 0, nextDepth, PassPoint.BOUNCE_POINT,
                                    PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth))
                            throw new RuntimeException("Transmission of K is prohibited.");
                        //under here, no interactions. in other words, penetrating or bouncing.
                        switch (nextChar) {
                            case 'K':
                            case 'P':
                            case 'S':
                                partList.add(new GeneralPart(PhasePart.K, true, 0, outerDepth, PassPoint.BOUNCE_POINT,
                                        outerPoint));
                                partList.add(Located.BOUNCE);
                                partList.add(new GeneralPart(PhasePart.K, false, 0, 0, PassPoint.BOUNCE_POINT,
                                        PassPoint.CMB));
                                partList.add(nextChar == 'K' ? Located.REFLECTION_K : Located.CMB_PENETRATION);
                                continue;
                            case 'I':
                            case 'J':
                            case 'i':
                                partList.add(
                                        new GeneralPart(PhasePart.K, true, 0, outerDepth, PassPoint.ICB, outerPoint));
                                partList.add(
                                        nextChar == 'i' ? Located.OUTERCORE_SIDE_REFLECTION : Located.ICB_PENETRATION);
                                continue;
                            default:
                                throw new RuntimeException("Problem around K");
                        }
                    } else if (beforePart.isTopsideReflection()) {
                        innerPoint = ((Located) beforePart).getPassPoint();
                        double innerDepth = secondLast.getInnerDepth();
                        if (vFlag) {
                            throw new RuntimeException("Problem around K");
                        } else if (hatFlag) {
                            partList.add(new GeneralPart(PhasePart.K, false, innerDepth, nextDepth, innerPoint,
                                    PassPoint.OTHER));
                            partList.add(Arbitrary.createBottomsideReflection(nextDepth));
                            continue;
                        } else if (!Double.isNaN(nextDepth))
                            throw new RuntimeException("Transmission of K is prohibited.");
                        switch (nextChar) {
                            case 'P':
                            case 'S':
                                partList.add(
                                        new GeneralPart(PhasePart.K, false, innerDepth, 0, innerPoint, PassPoint.CMB));
                                partList.add(Located.CMB_PENETRATION);
                                continue;
                            case 'K':
                                partList.add(
                                        new GeneralPart(PhasePart.K, false, innerDepth, 0, innerPoint, PassPoint.CMB));
                                partList.add(Located.REFLECTION_K);
                                continue;
                            default:
                                throw new RuntimeException("Problem around K");
                        }
                        //when the letter means whole path of P.
                    } else throw new RuntimeException("Problem around K");
                default:
                    throw new RuntimeException("unexpected character " + currentChar);
            }
        }
        passParts = partList.toArray(new PathPart[partList.size()]);
    }

    PathPart[] getPassParts() {
        return passParts.clone();
    }

    /**
     * @param phase phase name
     * @return if is well known phase?
     */
    static boolean isValid(String phase) {
        if (phase.isEmpty()) return false;
        // diffraction phase
        if (diffRule.matcher(phase).find()) return false;
        if (!pDiff.matcher(phase).find() && !sDiff.matcher(phase).find() && finalLetter.matcher(phase).find())
            return false;

        // check if other letters are used.
        if (others.matcher(phase).find()) return false;
        if (firstLetter.matcher(phase).find()) return false;

        if (ps.matcher(phase).find()) return false;

        if (nextC.matcher(phase).find()) return false;

        if (nextK.matcher(phase).find()) return false;

        if (nextJ.matcher(phase).find()) return false;

        if (smallI.matcher(phase).find()) return false;

        //check digits
        if (outercoreSpecification.matcher(phase).find()) return false;
        if (mantleSpecification.matcher(phase).find()) return false;
        if (bottomSide.matcher(phase).find()) return false;
        if (topSide.matcher(phase).find()) return false;
        if (bothSide.matcher(phase).find()) return false;

        // phase reflected at the icb
        boolean icb = phase.contains("Ki") || phase.contains("iK");
        boolean innercoreP = phase.contains("I");
        boolean innercoreS = phase.contains("J");

        if (mantleP.matcher(phase).find())
            if (cmbP.matcher(phase).find() || outercoreP.matcher(phase).find() || innercoreP) return false;

        if (mantleS.matcher(phase).find())
            if (cmbS.matcher(phase).find() || outercoreS.matcher(phase).find() || innercoreS) return false;

        if (outercore.matcher(phase).find()) if (innercoreP || icb || innercoreS) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int prime = 31;
        int result = 1;
        result = prime * result + ((EXPANDED_NAME == null) ? 0 : EXPANDED_NAME.hashCode());
        result = prime * result + (PSV ? 1231 : 1237);
        return result;
    }

    /**
     * It returns true, when compiled names are same. In other words, S(2K)S and
     * SKKS are equal (if the polarity is same).
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        Phase other = (Phase) obj;
        if (EXPANDED_NAME == null) {
            if (other.EXPANDED_NAME != null) return false;
        } else if (!EXPANDED_NAME.equals(other.EXPANDED_NAME)) return false;
        return PSV == other.PSV;
    }

    /**
     * @return P-SV (true), SH (false)
     */
    public boolean isPSV() {
        return PSV;
    }


    boolean isDiffracted() {
        return EXPANDED_NAME.contains("diff");
    }

    void printInformation() {
        System.out.println(PHASENAME);
        Arrays.stream(passParts).forEach(System.out::println);
    }

    String getPHASENAME() {
        return PHASENAME;
    }

    String getDISPLAY_NAME() {
        return DISPLAY_NAME;
    }

    @Override
    public String toString() {
        return PHASENAME; //TODO -> DISPLAY_NAME
    }
}
