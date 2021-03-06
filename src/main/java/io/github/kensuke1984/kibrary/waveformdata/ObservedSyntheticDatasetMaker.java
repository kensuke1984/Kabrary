package io.github.kensuke1984.kibrary.waveformdata;

import io.github.kensuke1984.kibrary.Operation;
import io.github.kensuke1984.kibrary.datacorrection.StaticCorrection;
import io.github.kensuke1984.kibrary.datacorrection.StaticCorrectionFile;
import io.github.kensuke1984.kibrary.timewindow.TimewindowInformation;
import io.github.kensuke1984.kibrary.timewindow.TimewindowInformationFile;
import io.github.kensuke1984.kibrary.util.EventFolder;
import io.github.kensuke1984.kibrary.util.Station;
import io.github.kensuke1984.kibrary.util.Trace;
import io.github.kensuke1984.kibrary.util.Utilities;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTID;
import io.github.kensuke1984.kibrary.util.sac.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Creates dataset containing observed and synthetic waveforms. <br>
 * The write is a set of an ID and waveform files.
 * <p>
 * Observed and synthetic waveforms in SAC files are collected from the obsDir
 * and synDir, respectively. Only SAC files, which sample rates are
 * {@link #sacSamplingHz}, are used. Both
 * folders must have event folders inside which have waveforms.
 * <p>
 * The static correction is applied as described in {@link StaticCorrection}
 * <p>
 * The sample rates of the data is
 * {@link #finalSamplingHz}.<br>
 * Timewindow information in {@link #timewindowPath} is used for cutting windows.
 * <p>
 * Only pairs of a seismic source and a receiver with both an observed and
 * synthetic waveform are collected.
 * <p>
 * This class does not apply a digital filter, but extract information about
 * pass band written in SAC files.
 * <p>
 * TODO <b> Assume that there are no stations with same name but different
 * network in one event</b>
 *
 * @author Kensuke Konishi
 * @version 0.2.3
 */
public class ObservedSyntheticDatasetMaker implements Operation {

    private Path workPath;
    private final Properties PROPERTY;

    /**
     * components to be included in the dataset
     */
    private Set<SACComponent> components;
    /**
     * {@link Path} of a root folder containing observed dataset
     */
    private Path obsPath;
    /**
     * {@link Path} of a root folder containing synthetic dataset
     */
    private Path synPath;
    /**
     * {@link Path} of a timewindow information file
     */
    private Path timewindowPath;
    /**
     * {@link Path} of a static correction file
     */
    private Path staticCorrectionPath;
    /**
     * Sacのサンプリングヘルツ （これと異なるSACはスキップ）
     */
    private double sacSamplingHz;
    /**
     * sampling Hz in the write file
     */
    private double finalSamplingHz;
    /**
     * if it is true, the dataset will contain synthetic waveforms after
     * convolution
     */
    private boolean convolute;
    /**
     * If it corrects time
     */
    private boolean timeCorrection;
    /**
     * if it corrects amplitude ratio
     */
    private boolean amplitudeCorrection;
    private Set<StaticCorrection> staticCorrectionSet;
    private Set<TimewindowInformation> timewindowInformationSet;
    private WaveformDataWriter dataWriter;
    private Set<EventFolder> eventDirs;
    private Set<Station> stationSet;
    private Set<GlobalCMTID> idSet;
    private double[][] periodRanges;
    /**
     * number of OUTPUT pairs. (excluding ignored traces)
     */
    private AtomicInteger numberOfPairs = new AtomicInteger();
    /**
     * ID for static correction and time window information Default is station
     * name, global CMT id, component.
     */
    private BiPredicate<StaticCorrection, TimewindowInformation> isPair =
            (s, t) -> s.getStation().equals(t.getStation()) && s.getGlobalCMTID().equals(t.getGlobalCMTID()) &&
                    s.getComponent() == t.getComponent();

    public ObservedSyntheticDatasetMaker(Properties property) throws IOException {
        this.PROPERTY = (Properties) property.clone();
        set();
    }

    public static void writeDefaultPropertiesFile() throws IOException {
        Path outPath = Paths.get(
                ObservedSyntheticDatasetMaker.class.getName() + Utilities.getTemporaryString() + ".properties");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath, StandardOpenOption.CREATE_NEW))) {
            pw.println("manhattan ObservedSyntheticDatasetMaker");
            pw.println("##Path of a working directory (.)");
            pw.println("#workPath");
            pw.println("##SacComponents to be used (Z R T)");
            pw.println("#components");
            pw.println("##Path of a root folder containing observed dataset (.)");
            pw.println("#obsPath");
            pw.println("##Path of a root folder containing synthetic dataset (.)");
            pw.println("#synPath");
            pw.println("##boolean convolute (true)");
            pw.println("#convolute");
            pw.println("##boolean timeCorrection (false)");
            pw.println("#timeCorrection");
            pw.println("##boolean amplitudeCorrection (false)");
            pw.println("#amplitudeCorrection");
            pw.println("##Path of a timewindow information file, must be defined");
            pw.println("#timewindowPath timewindow.dat");
            pw.println("##Path of a static correction file, ");
            pw.println("##if any of the corrections are true, the path must be defined");
            pw.println("#staticCorrectionPath staticCorrection.dat");
            pw.println("##double value of sac sampling Hz (20) can't be changed now");
            pw.println("#sacSamplingHz the value will be ignored");
            pw.println("##double value of sampling Hz in write files (1)");
            pw.println("#finalSamplingHz");
        }
        System.err.println(outPath + " is created.");
    }

    /**
     * @param args [a property file name]
     * @throws Exception if any
     */
    public static void main(String[] args) throws Exception {
        Properties property = new Properties();
        if (args.length == 0) property.load(Files.newBufferedReader(Operation.findPath()));
        else if (args.length == 1) property.load(Files.newBufferedReader(Paths.get(args[0])));
        else throw new IllegalArgumentException("too many arguments. It should be 0 or 1(property file name)");
        ObservedSyntheticDatasetMaker osdm = new ObservedSyntheticDatasetMaker(property);
        long startT = System.nanoTime();
        System.err.println(ObservedSyntheticDatasetMaker.class.getName() + " is running.");
        osdm.run();
        System.err.println(ObservedSyntheticDatasetMaker.class.getName() + " finished in " +
                Utilities.toTimeString(System.nanoTime() - startT));
    }

    private void checkAndPutDefaults() {
        if (!PROPERTY.containsKey("workPath")) PROPERTY.setProperty("workPath", "");
        if (!PROPERTY.containsKey("obsPath")) PROPERTY.setProperty("obsPath", "");
        if (!PROPERTY.containsKey("synPath")) PROPERTY.setProperty("synPath", "");
        if (!PROPERTY.containsKey("components")) PROPERTY.setProperty("components", "Z R T");
        if (!PROPERTY.containsKey("convolute")) PROPERTY.setProperty("convolute", "true");
        if (!PROPERTY.containsKey("amplitudeCorrection")) PROPERTY.setProperty("amplitudeCorrection", "false");
        if (!PROPERTY.containsKey("timeCorrection")) PROPERTY.setProperty("timeCorrection", "false");
        if (!PROPERTY.containsKey("timewindowPath"))
            throw new IllegalArgumentException("There is no information about timewindowPath.");
        if (!PROPERTY.containsKey("sacSamplingHz")) PROPERTY.setProperty("sacSamplingHz", "20");
        if (!PROPERTY.containsKey("finalSamplingHz")) PROPERTY.setProperty("finalSamplingHz", "1");
    }

    private void set() throws NoSuchFileException {
        checkAndPutDefaults();
        workPath = Paths.get(PROPERTY.getProperty("workPath"));
        if (!Files.exists(workPath)) throw new NoSuchFileException(workPath + " (workPath)");
        obsPath = getPath("obsPath");
        synPath = getPath("synPath");
        components = Arrays.stream(PROPERTY.getProperty("components").split("\\s+")).map(SACComponent::valueOf)
                .collect(Collectors.toSet());
        timewindowPath = getPath("timewindowPath");
        timeCorrection = Boolean.parseBoolean(PROPERTY.getProperty("timeCorrection"));
        amplitudeCorrection = Boolean.parseBoolean(PROPERTY.getProperty("amplitudeCorrection"));

        if (timeCorrection || amplitudeCorrection) {
            if (!PROPERTY.containsKey("staticCorrectionPath"))
                throw new RuntimeException("staticCorrectionPath is blank");
            staticCorrectionPath = getPath("staticCorrectionPath");
            if (!Files.exists(staticCorrectionPath)) throw new NoSuchFileException(staticCorrectionPath.toString());
        }

        convolute = Boolean.parseBoolean(PROPERTY.getProperty("convolute"));

        // sacSamplingHz
        // =Double.parseDouble(reader.getFirstValue("sacSamplingHz")); TODO
        sacSamplingHz = 20;
        finalSamplingHz = Double.parseDouble(PROPERTY.getProperty("finalSamplingHz"));
    }

    private void readPeriodRanges() {
        try {
            List<double[]> ranges = new ArrayList<>();
            for (SACFileName name : Utilities.sacFileNameSet(obsPath)) {
                if (!name.isOBS()) continue;
                SACHeaderData header = name.readHeader();
                double[] range =
                        new double[]{header.getValue(SACHeaderEnum.USER0), header.getValue(SACHeaderEnum.USER1)};
                boolean exists = false;
                if (ranges.isEmpty()) ranges.add(range);
                for (int i = 0; !exists && i < ranges.size(); i++)
                    if (Arrays.equals(range, ranges.get(i))) exists = true;
                if (!exists) ranges.add(range);
            }
            periodRanges = ranges.toArray(new double[0][]);
        } catch (Exception e) {
            throw new RuntimeException("Error in reading period ranges from SAC files.");
        }
    }


    @Override
    public void run() throws Exception {
        if (timeCorrection || amplitudeCorrection)
            staticCorrectionSet = StaticCorrectionFile.read(staticCorrectionPath);

        // obsDirからイベントフォルダを指定
        eventDirs = Utilities.eventFolderSet(obsPath);
        timewindowInformationSet = TimewindowInformationFile.read(timewindowPath);
        stationSet = timewindowInformationSet.parallelStream().map(TimewindowInformation::getStation)
                .collect(Collectors.toSet());
        idSet = timewindowInformationSet.parallelStream().map(TimewindowInformation::getGlobalCMTID)
                .collect(Collectors.toSet());
        readPeriodRanges();

        int n = Runtime.getRuntime().availableProcessors();
        ExecutorService execs = Executors.newFixedThreadPool(n);
        String dateStr = Utilities.getTemporaryString();
        Path waveIDPath = workPath.resolve("waveformID" + dateStr + ".dat");
        Path waveformPath = workPath.resolve("waveform" + dateStr + ".dat");
        try (WaveformDataWriter bdw = new WaveformDataWriter(waveIDPath, waveformPath, stationSet, idSet,
                periodRanges)) {
            dataWriter = bdw;
            for (EventFolder eventDir : eventDirs)
                execs.execute(new Worker(eventDir));
            execs.shutdown();
            while (!execs.isTerminated()) Thread.sleep(1000);
            System.err.println("\rCreating finished.");
            System.err.println(numberOfPairs.get() + " pairs of observed and synthetic waveforms are wrote.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private StaticCorrection getStaticCorrection(TimewindowInformation window) {
        return staticCorrectionSet.stream().filter(s -> isPair.test(s, window)).findAny().get();
    }

    private double[] cutDataSac(SACData sac, double startTime, int npts) {
        Trace trace = sac.createTrace();
        int step = (int) (sacSamplingHz / finalSamplingHz);
        int startPoint = trace.getNearestXIndex(startTime);
        double[] waveData = trace.getY();
        return IntStream.range(0, npts).parallel().mapToDouble(i -> waveData[i * step + startPoint]).toArray();
    }

    @Override
    public Properties getProperties() {
        return (Properties) PROPERTY.clone();
    }

    @Override
    public Path getWorkPath() {
        return workPath;
    }

    private AtomicInteger numberOfFinishedEvents = new AtomicInteger();

    /**
     * 与えられたイベントフォルダの観測波形と理論波形を書き込む 両方ともが存在しないと書き込まない
     */
    private class Worker implements Runnable {

        private final EventFolder OBS_EVENT_DIR;

        private Worker(EventFolder eventDir) {
            OBS_EVENT_DIR = eventDir;
        }

        @Override
        public void run() {
            Path synEventPath = synPath.resolve(OBS_EVENT_DIR.getGlobalCMTID().toString());
            if (!Files.exists(synEventPath)) throw new RuntimeException(synEventPath + " doesn't exist.");

            Set<SACFileName> obsFiles;
            try {
                (obsFiles = OBS_EVENT_DIR.sacFileSet()).removeIf(sfn -> !sfn.isOBS());
            } catch (IOException e2) {
                e2.printStackTrace();
                return;
            }

            for (SACFileName obsFileName : obsFiles) {
                // データセットに含める成分かどうか
                if (!components.contains(obsFileName.getComponent())) continue;
                String stationName = obsFileName.getStationName();
                GlobalCMTID id = obsFileName.getGlobalCMTID();
                SACComponent component = obsFileName.getComponent();
                String name =
                        convolute ? stationName + "." + id + "." + SACExtension.valueOfConvolutedSynthetic(component) :
                                stationName + "." + id + "." + SACExtension.valueOfSynthetic(component);
                SACFileName synFileName = new SACFileName(synEventPath.resolve(name));

                if (!synFileName.exists()) continue;

                Set<TimewindowInformation> windows = timewindowInformationSet.stream()
                        .filter(info -> info.getStation().getName().equals(stationName))
                        .filter(info -> info.getGlobalCMTID().equals(id))
                        .filter(info -> info.getComponent() == component).collect(Collectors.toSet());

                if (windows.isEmpty()) continue;

                SACData obsSac;
                try {
                    obsSac = obsFileName.read();
                } catch (IOException e1) {
                    System.err.println("error occurred in reading " + obsFileName);
                    e1.printStackTrace();
                    continue;
                }

                SACData synSac;
                try {
                    synSac = synFileName.read();
                } catch (IOException e1) {
                    System.err.println("error occurred in reading " + synFileName);
                    e1.printStackTrace();
                    continue;
                }

                // Sampling Hz of observed and synthetic must be same as the
                // value declared in the input file
                if (obsSac.getValue(SACHeaderEnum.DELTA) != 1 / sacSamplingHz &&
                        obsSac.getValue(SACHeaderEnum.DELTA) == synSac.getValue(SACHeaderEnum.DELTA)) {
                    System.err.println("Values of sampling Hz of observed and synthetic " +
                            (1 / obsSac.getValue(SACHeaderEnum.DELTA)) + ", " +
                            (1 / synSac.getValue(SACHeaderEnum.DELTA)) + " are invalid, they should be " +
                            sacSamplingHz);
                    continue;
                }

                // bandpassの読み込み 観測波形と理論波形とで違えばスキップ
                if (obsSac.getValue(SACHeaderEnum.USER0) != synSac.getValue(SACHeaderEnum.USER0) ||
                        obsSac.getValue(SACHeaderEnum.USER1) != synSac.getValue(SACHeaderEnum.USER1)) {
                    System.err.println("band pass filter difference");
                    continue;
                }
                double minPeriod = obsSac.getValue(SACHeaderEnum.USER0);
                double maxPeriod = obsSac.getValue(SACHeaderEnum.USER1);

                Station station = obsSac.getStation();

                for (TimewindowInformation window : windows) {
                    int npts = (int) ((window.getEndTime() - window.getStartTime()) * finalSamplingHz);
                    double startTime = window.getStartTime();
                    double shift = 0;
                    double ratio = 1;
                    if (timeCorrection || amplitudeCorrection) try {
                        StaticCorrection sc = getStaticCorrection(window);
                        shift = timeCorrection ? sc.getTimeshift() : 0;
                        ratio = amplitudeCorrection ? sc.getAmplitudeRatio() : 1;
                    } catch (NoSuchElementException e) {
                        System.err.println("No static correction information for\n " + window);
                        continue;
                    }

                    double[] obsData = cutDataSac(obsSac, startTime - shift, npts);
                    double[] synData = cutDataSac(synSac, startTime, npts);
                    double correctionRatio = ratio;

                    obsData = Arrays.stream(obsData).map(d -> d / correctionRatio).toArray();
                    BasicID synID =
                            new BasicID(WaveformType.SYN, finalSamplingHz, startTime, npts, station, id, component,
                                    minPeriod, maxPeriod, 0, convolute, synData);
                    BasicID obsID = new BasicID(WaveformType.OBS, finalSamplingHz, startTime - shift, npts, station, id,
                            component, minPeriod, maxPeriod, 0, convolute, obsData);
                    try {
                        dataWriter.addBasicID(obsID);
                        dataWriter.addBasicID(synID);
                        numberOfPairs.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            int p = (int) (100.0 * numberOfFinishedEvents.incrementAndGet() / eventDirs.size());
            System.err.print("\rCreating " + p + "%");
        }
    }

}
