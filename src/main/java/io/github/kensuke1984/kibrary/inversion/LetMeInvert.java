package io.github.kensuke1984.kibrary.inversion;

import io.github.kensuke1984.kibrary.Operation;
import io.github.kensuke1984.kibrary.Property;
import io.github.kensuke1984.kibrary.util.HorizontalPosition;
import io.github.kensuke1984.kibrary.util.Station;
import io.github.kensuke1984.kibrary.util.Utilities;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTData;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTID;
import io.github.kensuke1984.kibrary.waveformdata.BasicID;
import io.github.kensuke1984.kibrary.waveformdata.BasicIDFile;
import io.github.kensuke1984.kibrary.waveformdata.PartialID;
import io.github.kensuke1984.kibrary.waveformdata.PartialIDFile;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Precision;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Collectors;

/**
 * Let's invert
 *
 * @author Kensuke Konishi
 * @version 2.0.3.6
 */
public class LetMeInvert implements Operation {
    /**
     * path of waveform data
     */
    protected Path waveformPath;

    /**
     * Path of unknown parameter file
     */
    protected Path unknownParameterListPath;

    /**
     * path of partial ID file
     */
    protected Path partialIDPath;

    /**
     * path of partial data
     */
    protected Path partialPath;

    /**
     * path of basic ID file
     */
    protected Path waveIDPath;

    /**
     * path of station file
     */
    protected Path stationInformationPath;

    /**
     * Solvers for equation
     */
    protected Set<InverseMethodEnum> inverseMethods;
    /**
     * α for AIC 独立データ数:n/α
     */
    protected double[] alpha;
    private ObservationEquation eq;
    private Properties property;
    private Path workPath;
    private Path outPath;
    private Set<Station> stationSet;

    public LetMeInvert(Properties property) throws IOException {
        this.property = (Properties) property.clone();
        set();
        if (!canGO()) throw new RuntimeException();
        setEquation();
    }

    public LetMeInvert(Path workPath, Set<Station> stationSet, ObservationEquation equation) throws IOException {
        eq = equation;
        this.stationSet = stationSet;
        outPath = workPath.resolve("lmi" + Utilities.getTemporaryString());
        inverseMethods = new HashSet<>(Arrays.asList(InverseMethodEnum.values()));
    }

    public static void writeDefaultPropertiesFile() throws IOException {
        Path outPath = Paths.get(LetMeInvert.class.getName() + Utilities.getTemporaryString() + ".properties");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath, StandardOpenOption.CREATE_NEW))) {
            pw.println("manhattan LetMeInvert");
            pw.println("##These properties for LetMeInvert");
            pw.println("##Path of a work folder (.)");
            pw.println("#workPath");
            pw.println("##Path of an write folder (workPath/lmiyymmddhhmmss)");
            pw.println("#outPath");
            pw.println("##Path of a waveID file, must be set");
            pw.println("#waveIDPath waveID.dat");
            pw.println("##Path of a waveform file, must be set");
            pw.println("#waveformPath waveform.dat");
            pw.println("##Path of a partial id file, must be set");
            pw.println("#partialIDPath partialID.dat");
            pw.println("##Path of a partial waveform file must be set");
            pw.println("#partialPath partial.dat");
            pw.println("##Path of a unknown parameter list file, must be set");
            pw.println("#unknownParameterListPath unknowns.inf");
            pw.println("##Path of a station information file, must be set");
            pw.println("#stationInformationPath station.inf");
            pw.println("##double[] alpha it self, if it is set, compute aic for each alpha.");
            pw.println("#alpha");
            pw.println("##inverseMethods[] names of inverse methods (CG SVD)");
            pw.println("#inverseMethods");
        }
        System.err.println(outPath + " is created.");
    }

    /**
     * @param args [parameter file name]
     * @throws IOException if an I/O error occurs
     */
    public static void main(String[] args) throws IOException {
        LetMeInvert lmi = new LetMeInvert(Property.parse(args));
        System.err.println(LetMeInvert.class.getName() + " is running.");
        long startT = System.nanoTime();
        lmi.run();
        System.err.println(
                LetMeInvert.class.getName() + " finished in " + Utilities.toTimeString(System.nanoTime() - startT));
    }

    private static void writeDat(Path out, double[] dat) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardOpenOption.CREATE_NEW))) {
            Arrays.stream(dat).forEach(pw::println);
        }
    }

    private void checkAndPutDefaults() {
        if (!property.containsKey("workPath")) property.setProperty("workPath", "");
        if (!property.containsKey("stationInformationPath"))
            throw new IllegalArgumentException("There is no information about stationInformationPath.");
        if (!property.containsKey("waveIDPath"))
            throw new IllegalArgumentException("There is no information about 'waveIDPath'.");
        if (!property.containsKey("waveformPath"))
            throw new IllegalArgumentException("There is no information about 'waveformPath'.");
        if (!property.containsKey("partialIDPath"))
            throw new IllegalArgumentException("There is no information about 'partialIDPath'.");
        if (!property.containsKey("partialPath"))
            throw new IllegalArgumentException("There is no information about 'partialPath'.");
        if (!property.containsKey("inverseMethods")) property.setProperty("inverseMethods", "CG SVD");
    }

    private void set() {
        checkAndPutDefaults();
        workPath = Paths.get(property.getProperty("workPath"));
        if (!Files.exists(workPath)) throw new RuntimeException("The workPath: " + workPath + " does not exist");
        if (property.containsKey("outPath")) outPath = getPath("outPath");
        else outPath = workPath.resolve(Paths.get("lmi" + Utilities.getTemporaryString()));
        stationInformationPath = getPath("stationInformationPath");
        waveIDPath = getPath("waveIDPath");
        waveformPath = getPath("waveformPath");
        partialPath = getPath("partialPath");
        partialIDPath = getPath("partialIDPath");
        unknownParameterListPath = getPath("unknownParameterListPath");
        if (property.containsKey("alpha")) alpha =
                Arrays.stream(property.getProperty("alpha").split("\\s+")).mapToDouble(Double::parseDouble).toArray();
        inverseMethods = Arrays.stream(property.getProperty("inverseMethods").split("\\s+")).map(InverseMethodEnum::of)
                .collect(Collectors.toSet());
    }

    /**
     * Create an equation
     *
     * @throws IOException if any
     */
    private void setEquation() throws IOException {
    	// set weighting
//    	ToDoubleBiFunction<BasicID,
//    	BasicID> weightingFunction = (obs,syn) 
//    			-> 1 / new ArrayRealVector(obs.getData()).getLInfNorm();
//    	double[] weight = new double[7];
//    	weight[0] = 67.;
//    	weight[1] = 478.;
//    	weight[2] = 116.;
//    	weight[3] = 29.;
//    	weight[4] = 8.;
    	ToDoubleBiFunction<BasicID,
    	BasicID> weightingFunction = (obs,syn) 
    			-> 1. ;/// weight[(int)(obs.getStation().getPosition()
//   			.getEpicentralDistance(obs.getGlobalCMTID().getEvent().getCmtLocation())* 180 / Math.PI/5)];
    			
    	
        BasicID[] ids = BasicIDFile.read(waveIDPath, waveformPath);

        // set Dvector
        System.err.println("Creating D vector.");
//		Dvector dVector = new Dvector(ids);
        Dvector dVector = new Dvector(ids, id -> true, weightingFunction);
        
        // set unknown parameter
        System.err.println("Setting up unknown parameter set.");
        List<UnknownParameter> parameterList = UnknownParameterFile.read(unknownParameterListPath);

        // set partial matrix
        PartialID[] partialIDs = PartialIDFile.read(partialIDPath, partialPath);
        eq = new ObservationEquation(partialIDs, parameterList, dVector);
//        eq.outputAtA(outPath.resolve("AtA"));
    }

    /**
     * Output information of equation
     */
    private Future<Void> output() throws IOException {
        System.err.print("reading station Information");
        if (stationSet == null) stationSet = StationInformationFile.read(stationInformationPath);
        System.err.println(" done");
        Dvector dVector = eq.getDVector();
        Callable<Void> output = () -> {
            outputDistribution(outPath.resolve("stationEventDistribution.inf"));
            dVector.outOrder(outPath);
            outEachTrace(outPath.resolve("trace"));
            UnknownParameterFile.write(outPath.resolve("unknownParameterOrder.inf"), eq.getparameterList());
            eq.outputA(outPath.resolve("partial"));
            
            return null;
        };
        eq.outputAtA(outPath.resolve("AtA"));
        FutureTask<Void> future = new FutureTask<>(output);

        new Thread(future).start();
        return future;
    }

    @Override
    public void run() {
        try {
            System.err.println("The write folder: " + outPath);
//            Files.createDirectory(outPath);
            if (property != null) writeProperties(outPath.resolve("lmi.properties"));
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Can not create " + outPath);
        }

        long start = System.nanoTime();

        // equation
        Future<Void> future;
        try {
            future = output();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // inverse problem
        solve();
        try {
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        System.err.println("Inversion is done in " + Utilities.toTimeString(System.nanoTime() - start));
    }

    /**
     * outDirectory下にtraceフォルダを作りその下に理論波形と観測波形を書き込む
     *
     * @param outPath {@link Path} for write folder
     * @throws IOException if an I/O error occurs
     */
    public void outEachTrace(Path outPath) throws IOException {
        if (Files.exists(outPath)) throw new FileAlreadyExistsException(outPath.toString());
        Files.createDirectories(outPath);
        Dvector d = eq.getDVector();

        Path eventPath = outPath.resolve("eventVariance.inf");
        Path stationPath = outPath.resolve("stationVariance.inf");
        try (PrintWriter pwEvent = new PrintWriter(Files.newBufferedWriter(eventPath));
             PrintWriter pwStation = new PrintWriter(Files.newBufferedWriter(stationPath))) {
            pwEvent.println("#id latitude longitude radius variance");
            d.getEventVariance().entrySet().forEach(entry -> pwEvent.println(
                    entry.getKey() + " " + entry.getKey().getEvent().getCmtLocation() + " " + entry.getValue()));
            pwStation.println("#name network latitude longitude variance");
            d.getStationVariance().entrySet().forEach(entry -> pwStation.println(
                    entry.getKey() + " " + entry.getKey().getNetwork() + " " + entry.getKey().getPosition() + " " +
                            entry.getValue()));

        }
        for (GlobalCMTID id : d.getUsedGlobalCMTIDset()) {
            Path eventFolder = outPath.resolve(id.toString());
            Files.createDirectories(eventFolder);
            Path obs = eventFolder.resolve("recordOBS.plt");
            Path syn = eventFolder.resolve("recordSYN.plt");
            Path w = eventFolder.resolve("recordW.plt");
            Path wa = eventFolder.resolve("recordWa.plt");
            try (PrintWriter plotO = new PrintWriter(
                    Files.newBufferedWriter(obs, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotS = new PrintWriter(
                         Files.newBufferedWriter(syn, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotW = new PrintWriter(
                         Files.newBufferedWriter(w, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotWa = new PrintWriter(
                         Files.newBufferedWriter(wa, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                String title = "set title\"" + id + "\"";
                plotW.println(title);
                plotW.print("p ");
                plotO.println(title);
                plotO.print("p ");
                plotS.println(title);
                plotS.print("p ");
                plotWa.println(title);
                plotWa.print("p ");
            }

        }

        BasicID[] obsIDs = d.getObsIDs();
        BasicID[] synIDs = d.getSynIDs();
        RealVector[] obsVec = d.getObsVectors();
        RealVector[] synVec = d.getSynVectors();
        RealVector[] delVec = d.getDVectors();
        // each trace variance
        Path eachVariancePath = outPath.resolve("eachVariance.txt");
        try (PrintWriter pw1 = new PrintWriter(Files.newBufferedWriter(eachVariancePath))) {
            pw1.println("#i station network EventID variance correlation");
            for (int i = 0; i < d.getNTimeWindow(); i++) {
                double variance = delVec[i].dotProduct(delVec[i]) / obsVec[i].dotProduct(obsVec[i]);
                double correlation = obsVec[i].dotProduct(synVec[i]) / obsVec[i].getNorm() / synVec[i].getNorm();
                pw1.println(i + " " + obsIDs[i].getStation() + " " + obsIDs[i].getStation().getNetwork() + " " +
                        obsIDs[i].getGlobalCMTID() + " " + variance + " " + correlation);
            }
        }
        for (int i = 0; i < d.getNTimeWindow(); i++) {
            String name =
                    obsIDs[i].getStation() + "." + obsIDs[i].getGlobalCMTID() + "." + obsIDs[i].getSacComponent() +
                            "." + i + ".txt";

            HorizontalPosition eventLoc = obsIDs[i].getGlobalCMTID().getEvent().getCmtLocation();
            HorizontalPosition stationPos = obsIDs[i].getStation().getPosition();
            double gcarc = Precision.round(Math.toDegrees(eventLoc.getEpicentralDistance(stationPos)), 2);
            double azimuth = Precision.round(Math.toDegrees(eventLoc.getAzimuth(stationPos)), 2);
            Path eventFolder = outPath.resolve(obsIDs[i].getGlobalCMTID().toString());
            // eventFolder.mkdir();
            Path plotPath = eventFolder.resolve("recordOBS.plt");
            Path plotPath2 = eventFolder.resolve("recordSYN.plt");
            Path plotPath3 = eventFolder.resolve("recordW.plt");
            Path plotPath4 = eventFolder.resolve("recordWa.plt");
            Path tracePath = eventFolder.resolve(name);
            try (PrintWriter pwTrace = new PrintWriter(Files.newBufferedWriter(tracePath));
                 PrintWriter plotO = new PrintWriter(
                         Files.newBufferedWriter(plotPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotS = new PrintWriter(
                         Files.newBufferedWriter(plotPath2, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotW = new PrintWriter(
                         Files.newBufferedWriter(plotPath3, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotWa = new PrintWriter(
                         Files.newBufferedWriter(plotPath4, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {

                if (i < (d.getNTimeWindow() - 1)) {
                    plotO.println("\"" + name + "\" u 1:($3+" + gcarc + ") ti\"" + obsIDs[i].getStation() + "\", \\");
                    plotS.println("\"" + name + "\" u 2:($4+" + gcarc + ") ti\"" + obsIDs[i].getStation() + "\", \\");
                    plotW.println(
                            "\"" + name + "\" u 2:($3+" + gcarc + ") lc rgb \"red\" noti ,  \"" + name + "\" u 2:($4+" +
                                    gcarc + ") lc rgb \"blue\" ti\"" + obsIDs[i].getStation() + "\", \\");
                    plotWa.println("\"" + name + "\" u 2:($3+" + azimuth + ") lc rgb \"red\" noti ,  \"" + name +
                            "\" u 2:($4+" + azimuth + ") lc rgb \"blue\" ti\"" + obsIDs[i].getStation() + "\", \\");
                } else {

                    plotO.println("\"" + name + "\" u 1:($3+" + gcarc + ") ti\"" + obsIDs[i].getStation() + "\"");
                    plotS.println("\"" + name + "\" u 2:($4+" + gcarc + ") ti\"" + obsIDs[i].getStation() + "\"");
                    plotW.println(
                            "\"" + name + "\" u 2:($3+" + gcarc + ") lc rgb \"red\" noti ,  \"" + name + "\" u 2:($4+" +
                                    gcarc + ") lc rgb \"blue\" ti\"" + obsIDs[i].getStation() + "\"");
                    plotWa.println("\"" + name + "\" u 2:($3+" + azimuth + ") lc rgb \"red\" noti ,  \"" + name +
                            "\" u 2:($4+" + azimuth + ") lc rgb \"blue\" ti\"" + obsIDs[i].getStation() + "\"");
                }
                double obsStart = obsIDs[i].getStartTime();
                double synStart = synIDs[i].getStartTime();
                double samplingHz = obsIDs[i].getSamplingHz();
                pwTrace.println("#obstime syntime obs syn");
                for (int j = 0; j < obsIDs[i].getNpts(); j++) {
                    pwTrace.println((obsStart + j / samplingHz) + " " + (synStart + j / samplingHz) + " " +
                            obsVec[i].getEntry(j) + " " + synVec[i].getEntry(j));
                }
            }
        }

    }

    /**
     * vectorsをidの順の波形だとしてファイルに書き込む
     *
     * @param outPath {@link File} for write
     * @param vectors {@link RealVector}s for write
     * @throws IOException if an I/O error occurs
     */
    public void outEachTrace(Path outPath, RealVector[] vectors) throws IOException {
        // if (outDirectory.exists())
        // return;
        int nTimeWindow = eq.getDVector().getNTimeWindow();
        if (vectors.length != nTimeWindow) return;
        for (int i = 0; i < nTimeWindow; i++) {
            if (vectors[i].getDimension() != eq.getDVector().getSynVectors()[i].getDimension()) return;
        }
        Files.createDirectories(outPath);
        for (GlobalCMTID id : eq.getDVector().getUsedGlobalCMTIDset()) {
            Path eventPath = outPath.resolve(id.toString());
            Files.createDirectories(eventPath);
            Path record = eventPath.resolve("record.plt");
            Path recorda = eventPath.resolve("recorda.plt");
            try (PrintWriter pw = new PrintWriter(
                    Files.newBufferedWriter(record, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter pwa = new PrintWriter(
                         Files.newBufferedWriter(recorda, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                pw.println("set title\"" + id + "\"");
                pw.print("p ");
                pwa.println("set title\"" + id + "\"");
                pwa.print("p ");
            }
        }
        BasicID[] obsIDs = eq.getDVector().getObsIDs();
        BasicID[] synIDs = eq.getDVector().getSynIDs();
        for (int i = 0; i < nTimeWindow; i++) {
            Path out = outPath.resolve(
                    obsIDs[i].getGlobalCMTID() + "/" + obsIDs[i].getStation() + "." + obsIDs[i].getGlobalCMTID() + "." +
                            obsIDs[i].getSacComponent() + "." + i + ".txt"); // TODO
            Path plotFile = outPath.resolve(obsIDs[i].getGlobalCMTID() + "/record.plt");
            Path plotFilea = outPath.resolve(obsIDs[i].getGlobalCMTID() + "/recorda.plt");
            HorizontalPosition eventLoc = obsIDs[i].getGlobalCMTID().getEvent().getCmtLocation();
            HorizontalPosition stationPos = obsIDs[i].getStation().getPosition();
            double gcarc = Precision.round(Math.toDegrees(eventLoc.getEpicentralDistance(stationPos)), 2);
            double azimuth = Precision.round(Math.toDegrees(eventLoc.getAzimuth(stationPos)), 2);
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out)); PrintWriter plotW = new PrintWriter(
                    Files.newBufferedWriter(plotFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                 PrintWriter plotWa = new PrintWriter(
                         Files.newBufferedWriter(plotFilea, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {

                plotW.println("\"" + out.getFileName() + "\" u 2:($3+" + gcarc + ") ti\"" + obsIDs[i].getStation() +
                        "\", \\");
                plotWa.println("\"" + out.getFileName() + "\" u 2:($3+" + azimuth + ") ti\"" + obsIDs[i].getStation() +
                        "\", \\");
                pw.println("#syntime synthetic+");
                for (int j = 0; j < vectors[i].getDimension(); j++) {
                    double step = j * obsIDs[i].getSamplingHz();
                    pw.println((synIDs[i].getStartTime() + step) + " " + vectors[i].getEntry(j));
                }
            }

        }

    }

    private void solve() {
        inverseMethods.forEach(method -> {
            try {
                if (method == InverseMethodEnum.LEAST_SQUARES_METHOD) return; // TODO
                solve(outPath.resolve(method.simple()), method.getMethod(eq.getAtA(), eq.getAtD()));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void solve(Path outPath, InverseProblem inverseProblem) throws IOException {
        // invOutDir.mkdir();
        inverseProblem.compute();
        inverseProblem.outputAns(outPath);
        outVariance(outPath, inverseProblem);

        // 基底ベクトルの書き出し SVD: vt, CG: cg ベクトル
        RealMatrix p = inverseProblem.getBaseVectors();
        for (int j = 0; j < eq.getMlength(); j++) {
            writeDat(outPath.resolve("p" + j + ".txt"), p.getColumn(j));
        }
    }

    /**
     * outPath下にvarianceを書き込む
     *
     * @param outPath root path
     */
    private void outVariance(Path outPath, InverseProblem inverse) throws IOException {

        Path out = outPath.resolve("variance.txt");
        if (Files.exists(out)) throw new FileAlreadyExistsException(out.toString());
        double[] variance = new double[eq.getMlength() + 1];
        variance[0] = eq.getDVector().getVariance();
        for (int i = 0; i < eq.getMlength(); i++) {
            variance[i + 1] = eq.varianceOf(inverse.getANS().getColumnVector(i));
        }
        writeDat(out, variance);
        if (alpha == null) return;
        for (int i = 0; i < alpha.length; i++) {
            out = outPath.resolve("aic" + i + ".txt");
            double[] aic = computeAIC(variance, alpha[i]);
            writeDat(out, aic);
        }
        writeDat(outPath.resolve("aic.inf"), alpha);
    }

    /**
     * 自由度iに対してAICを計算する 独立データは n / alpha 各々のAIC群
     *
     * @param variance varianceの列
     * @param alpha    alpha redundancy
     * @return array of aic
     */
    private double[] computeAIC(double[] variance, double alpha) {
        double[] aic = new double[variance.length];
        int independentN = (int) (eq.getDlength() / alpha);
        for (int i = 0; i < aic.length; i++)
            aic[i] = Utilities.computeAIC(variance[i], independentN, i);

        return aic;
    }

    private boolean canGO() {
        boolean cango = true;
        if (Files.exists(outPath)) {
            new FileAlreadyExistsException(outPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(partialIDPath)) {
            new NoSuchFileException(partialIDPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(partialPath)) {
            cango = false;
            new NoSuchFileException(partialPath.toString()).printStackTrace();
        }
        if (!Files.exists(stationInformationPath)) {
            new NoSuchFileException(stationInformationPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(unknownParameterListPath)) {
            new NoSuchFileException(unknownParameterListPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(waveformPath)) {
            new NoSuchFileException(waveformPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(waveIDPath)) {
            new NoSuchFileException(waveIDPath.toString()).printStackTrace();
            cango = false;
        }
        if (!Files.exists(workPath)) {
            new NoSuchFileException(workPath.toString()).printStackTrace();
            cango = false;
        }
        return cango;
    }

    /**
     * station と 震源の位置関係の出力
     *
     * @param outPath {@link File} for write
     * @throws IOException if an I/O error occurs
     */
    public void outputDistribution(Path outPath) throws IOException {

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath, StandardOpenOption.CREATE_NEW))) {
            BasicID[] obsIDs = eq.getDVector().getObsIDs();
            pw.println("#station(lat lon) event(lat lon r) EpicentralDistance Azimuth ");
            Arrays.stream(obsIDs).forEach(id -> {
                GlobalCMTData event = id.getGlobalCMTID().getEvent();
                Station station = id.getStation();
                double epicentralDistance =
                        Math.toDegrees(station.getPosition().getEpicentralDistance(event.getCmtLocation()));
                double azimuth = Math.toDegrees(station.getPosition().getAzimuth(event.getCmtLocation()));
                pw.println(station + " " + station.getPosition() + " " + id.getGlobalCMTID() + " " +
                        event.getCmtLocation() + " " + Precision.round(epicentralDistance, 2) + " " +
                        Precision.round(azimuth, 2));
            });

        }
    }

    @Override
    public Path getWorkPath() {
        return workPath;
    }

    @Override
    public Properties getProperties() {
        return (Properties) property.clone();
    }

}
