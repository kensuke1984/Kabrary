package io.github.kensuke1984.kibrary.waveformdata;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.math3.complex.Complex;

import io.github.kensuke1984.anisotime.Phase;
import io.github.kensuke1984.kibrary.Operation;
import io.github.kensuke1984.kibrary.Property;
import io.github.kensuke1984.kibrary.butterworth.BandPassFilter;
import io.github.kensuke1984.kibrary.butterworth.ButterworthFilter;
import io.github.kensuke1984.kibrary.datacorrection.SourceTimeFunction;
import io.github.kensuke1984.kibrary.dsminformation.PolynomialStructure;
import io.github.kensuke1984.kibrary.timewindow.TimewindowInformation;
import io.github.kensuke1984.kibrary.timewindow.TimewindowInformationFile;
import io.github.kensuke1984.kibrary.util.Earth;
import io.github.kensuke1984.kibrary.util.EventFolder;
import io.github.kensuke1984.kibrary.util.Location;
import io.github.kensuke1984.kibrary.util.Station;
import io.github.kensuke1984.kibrary.util.Utilities;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTID;
import io.github.kensuke1984.kibrary.util.sac.SACComponent;
import io.github.kensuke1984.kibrary.util.sac.SACData;
import io.github.kensuke1984.kibrary.util.sac.SACFileName;
import io.github.kensuke1984.kibrary.util.spc.DSMOutput;
import io.github.kensuke1984.kibrary.util.spc.PartialType;
import io.github.kensuke1984.kibrary.util.spc.SpcFileComponent;
import io.github.kensuke1984.kibrary.util.spc.SpcFileName;
import io.github.kensuke1984.kibrary.util.spc.ThreeDPartialMaker;

/**
 *
 * shfp、shbp、psvfp,psvbp から作られたSPCファイルがあるディレクトリの操作
 *
 * fpDIR, bpDIR(未定義の場合workDir)の下に イベントごとのフォルダ（FP）、ステーションごとのフォルダ(BP)があり その下の
 * modelNameにspcfileをおく
 *
 * イベントフォルダはeventnameで定義されたもの ステーショフォルダは0000(stationname)
 *
 * spcfileの名前は (point name).(station or eventname).(PB or PF)...(sh or psv).spc
 *
 * halfDurationはevent informationファイルから読み取る
 *
 * time window informationファイルの中からtime windowを見つける。 その中に入っている震源観測点成分の組み合わせのみ計算する
 *
 * バンドパスをかけて保存する
 *
 *
 * TODO station とかの書き出し
 *
 * 例： directory/19841006/*spc directory/0000KKK/*spc
 *
 * 摂動点の情報がない摂動点に対しては計算しない
 *
 * <b>Assume there are no stations with the same name and different networks</b>
 * TODO
 * <p>
 * Because of DSM condition, stations can not have the same name...
 *
 * @version 2.3.0.5
 *
 * @author Kensuke Konishi
 */
public class PartialDatasetMaker implements Operation {

	private Set<SACComponent> components;

	/**
	 * time length (DSM parameter)
	 */
	private double tlen;

	/**
	 * step of frequency domain (DSM parameter)
	 */
	private int np;

	/**
	 * BPinfo このフォルダの直下に 0000????を置く
	 */
	private Path bpPath;
	/**
	 * FPinfo このフォルダの直下に イベントフォルダ（FP）を置く
	 */
	private Path fpPath;

	/**
	 * bp, fp フォルダの下のどこにspcファイルがあるか 直下なら何も入れない（""）
	 */
	private String modelName;

	/**
	 * タイムウインドウ情報のファイル
	 */
	private Path timewindowPath;
	/**
	 * Information file about locations of perturbation points.
	 */
	private Path perturbationPath;

	/**
	 * set of partial type for computation
	 */
	private Set<PartialType> partialTypes;

	/**
	 * bandpassの最小周波数（Hz）
	 */
	private double minFreq;

	/**
	 * bandpassの最大周波数（Hz）
	 */
	private double maxFreq;

	private Properties property;

	private Path workPath;

	private Path timePartialPath;

	/**
	 * spcFileをコンボリューションして時系列にする時のサンプリングHz デフォルトは２０ TODOまだ触れない
	 */
	private double partialSamplingHz = 20;

	@Override
	public Properties getProperties() {
		return (Properties) property.clone();
	}

	@Override
	public Path getWorkPath() {
		return workPath;
	}

	/**
	 * 最後に時系列で切り出す時のサンプリングヘルツ(Hz)
	 */
	private double finalSamplingHz;

	/**
	 * structure for Q partial
	 */
	private PolynomialStructure structure;
	/**
	 * 0:none, 1:boxcar, 2:triangle.
	 */
	private int sourceTimeFunction;
	/**
	 * The folder contains source time functions.
	 */
	private Path sourceTimeFunctionPath;

	/**
	 * 一つのBackPropagationに対して、あるFPを与えた時の計算をさせるスレッドを作る
	 *
	 * @author Kensuke
	 *
	 */
	private class PartialComputation implements Runnable {

		private Station station;
		private GlobalCMTID id;
		private DSMOutput primeBP;
		private DSMOutput secondaryBP;
		private SpcFileName primeFPname;
		private SpcFileName secondaryFPname;
		private DSMOutput primeFP;
		private DSMOutput secondaryFP;
		private DSMOutput masterFP;
		private DSMOutput masterBP;


		/**
		 * @param fp
		 * @param bpFile
		 */
		private PartialComputation(DSMOutput bp, Station station, SpcFileName fpFile) {
			this.primeBP = bp;
			this.station = station;
			primeFPname = fpFile;
			id = new GlobalCMTID(primeFPname.getSourceID());
		}

		/**
		 * @param primeBP (toroidal or spheroidal mode)
		 * @param secondaryBP (the other mode for primeBP)
		 * @param station
		 * @param primeFPFile (toroidal or spheroidal mode)
		 * @param secondaryFPFile (the other mode for primeFP)
		 */
		private PartialComputation(DSMOutput primeBP, DSMOutput secondaryBP,
						Station station, SpcFileName primeFPFile, SpcFileName secondaryFPFile) {
			this.primeBP = primeBP;
			this.secondaryBP = secondaryBP;
			this.station = station;
			primeFPname = primeFPFile;
			secondaryFPname = secondaryFPFile;
			id = new GlobalCMTID(primeFPname.getSourceID());
			if (!checkPair(primeBP, secondaryBP))
				throw new RuntimeException("SH and PSV bp files are not a pair" + primeBP + " " + secondaryBP);
		}

		private boolean checkPair(DSMOutput bp1, DSMOutput bp2) {
			boolean res = true;
			if (!bp1.getObserverPosition().equals(bp2.getObserverPosition()))
				res = false;
			return res;
		}

		/**
		 * cut partial derivative in [start-ext, start+ext] The ext is for
		 * filtering .
		 *
		 * @param u
		 * @param property
		 * @return
		 */
		private Complex[] cutPartial(double[] u, TimewindowInformation timewindowInformation) {
			int cutstart = (int) (timewindowInformation.getStartTime() * partialSamplingHz) - ext;
			// cutstartが振り切れた場合0 からにする
			if (cutstart < 0)
				return null;
			int cutend = (int) (timewindowInformation.getEndTime() * partialSamplingHz) + ext;
			Complex[] cut = new Complex[cutend - cutstart];
			Arrays.parallelSetAll(cut, i -> new Complex(u[i + cutstart]));

			return cut;
		}

		private double[] sampleOutput(Complex[] u, TimewindowInformation timewindowInformation) {
			// 書きだすための波形
			int outnpts = (int) ((timewindowInformation.getEndTime() - timewindowInformation.getStartTime())
					* finalSamplingHz);
			double[] sampleU = new double[outnpts];

			// cutting a waveform for outputting
			Arrays.parallelSetAll(sampleU, j -> u[ext + j * step].getReal());
			return sampleU;
		}

		private SourceTimeFunction getSourceTimeFunction() {
			return sourceTimeFunction == 0 ? null : userSourceTimeFunctions.get(id);
		}

		@Override
		public void run() {
			String stationName = primeBP.getSourceID();

			if (station.getPosition().toLocation(0).getDistance(primeBP.getSourceLocation()) > 0.5)
				throw new RuntimeException("There may be a station with the same name but other networks.");

			if (primeBP.tlen() != tlen || primeBP.np() != np)
				throw new RuntimeException("BP for " + station + " has invalid tlen or np.");
			GlobalCMTID id = new GlobalCMTID(primeFPname.getSourceID());

			touchedSet.add(id);

			// Pickup timewindows
			Set<TimewindowInformation> timewindowList = timewindowInformation.stream()
					.filter(info -> info.getStation().toString().equals(stationName))
					.filter(info -> info.getGlobalCMTID().equals(id)).collect(Collectors.toSet());

			// timewindow情報のないときスキップ
			if (timewindowList.isEmpty())
				return;

			// System.out.println("I am " + Thread.currentThread().getName());
			try {
				primeFP = primeFPname.read();
				if (secondaryFPname != null) {
					secondaryFP = secondaryFPname.read();
				}
				else {
//					System.out.print(secondaryFPname.getName()+" does not exist.");
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
//				return;
			}

			//pairSPC(PSV?)のspc bodyを元のSPC(SH?)のbodyに加える	TODO
			for (int ibody = 0; ibody < primeBP.nbody(); ibody++) {
				if (secondaryFP != null && secondaryBP != null) {
					primeFP.getSpcBodyList().get(ibody)
						.addBody(secondaryFP.getSpcBodyList().get(ibody));
					primeBP.getSpcBodyList().get(ibody)
						.addBody(secondaryBP.getSpcBodyList().get(ibody));
				}
			}

//			System.out.println("partial making!");
			ThreeDPartialMaker threedPartialMaker = new ThreeDPartialMaker(primeFP, primeBP);
			threedPartialMaker.setSourceTimeFunction(getSourceTimeFunction());
			if (structure != null)
				threedPartialMaker.setStructure(structure);


//			if (bp.getSourceLocation().getLongitude() == fp.getObserverPosition().getLongitude()) {
//				Path path = Paths.get(fp.getObserverPosition().getLongitude() + "_" + bp.getSourceLocation().getLongitude()
//						+ "." + fp.getSourceID() + ".txt");
//				try {
//					Files.deleteIfExists(path);
//					Files.createFile(path);
//					int lsmooth = (int) (0.5 * 6553.6 * 20. / 1024.);
//					int j = Integer.highestOneBit(lsmooth);
//					if (j < lsmooth)
//						j *= 2;
//					lsmooth = j;
//					SpcBody fpBody = fp.getSpcBodyList().get(0);
//					SpcBody bpBody = bp.getSpcBodyList().get(0);
//
//					System.out.println("To time domain lmsooth=" + lsmooth);
//					fpBody.toTimeDomain(lsmooth);
//					bpBody.toTimeDomain(lsmooth);
//
//					double[] fpserie = fpBody.getTimeseries(SACComponent.T);
//					double[] bpserie = bpBody.getTimeseries(SACComponent.T);
//					System.out.println("FP serie has npts=" + fpserie.length);
//					System.out.println("BP serie has npts=" + bpserie.length);
//
//					for (int i = 0; i < fpserie.length / 20; i++) {
//						Files.write(path, (i + " " + fpserie[i * 20] + " " + bpserie[i * 20] + "\n").getBytes(), StandardOpenOption.APPEND);
//					}
//				} catch (IOException e) {
//					e.printStackTrace();
//				}
//			}

			// i番目の深さの偏微分波形を作る
			for (int ibody = 0, nbody = primeBP.nbody(); ibody < nbody; ibody++) {
//			for (int ibody = 0, nbody = perturbationRs.length; ibody < nbody; ibody++) {
				// とりあえずtransverse（２）成分についての名前
//				Location location = fp.getObserverPosition().toLocation(perturbationRs[ibody]);//fp.getObserverPosition().toLocation(fp.getBodyR()[ibody]);
				Location location = primeBP.getObserverPosition().toLocation(primeBP.getBodyR()[ibody]);
//				System.out.println(location);

				if (!perturbationLocationSet.contains(location))
					continue;

				for (PartialType type : partialTypes)
					for (SACComponent component : components) {
						if (timewindowList.stream().noneMatch(info -> info.getComponent() == component))
							continue;
						double[] partial = threedPartialMaker.createPartial(component, ibody, type);
						timewindowList.stream().filter(info -> info.getComponent() == component).forEach(info -> {
							Complex[] u = cutPartial(partial, info);

							u = filter.applyFilter(u);
							double[] cutU = sampleOutput(u, info);

							PartialID pid = new PartialID(station, id, component, finalSamplingHz, info.getStartTime(),
									cutU.length, 1 / maxFreq, 1 / minFreq, info.getPhases(), 0, sourceTimeFunction != 0, location, type,
									cutU);
							try {
								partialDataWriter.addPartialID(pid);
								System.out.print(".");
							} catch (Exception e) {
								e.printStackTrace();
							}
						});
					}

			}
		}
	}

private class WorkerTimePartial implements Runnable {

		private EventFolder eventDir;
		private GlobalCMTID id;

		@Override
		public void run() {
			try {
				writeLog("Running on " + id);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			Path timePartialFolder = eventDir.toPath();

			if (!Files.exists(timePartialFolder)) {
				throw new RuntimeException(timePartialFolder + " does not exist...");
			}

			Set<SACFileName> sacnameSet;
			try {
				sacnameSet = eventDir.sacFileSet()
						.stream()
						.filter(sacname -> sacname.isTemporalPartial())
						.collect(Collectors.toSet());
			} catch (IOException e1) {
				e1.printStackTrace();
				return;
			}

//			System.out.println(sacnameSet.size());
//			sacnameSet.forEach(name -> System.out.println(name));

			Set<TimewindowInformation> timewindowCurrentEvent = timewindowInformation
					.stream()
					.filter(tw -> tw.getGlobalCMTID().equals(id))
					.collect(Collectors.toSet());

			// すべてのsacファイルに対しての処理
			for (SACFileName sacname : sacnameSet) {
				try {
					addTemporalPartial(sacname, timewindowCurrentEvent);
				} catch (ClassCastException e) {
					// 出来上がったインスタンスがOneDPartialSpectrumじゃない可能性
					System.err.println(sacname + "is not 1D partial.");
					continue;
				} catch (Exception e) {
					System.err.println(sacname + " is invalid.");
					e.printStackTrace();
					try {
						writeLog(sacname + " is invalid.");
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					continue;
				}
			}
			System.out.print(".");
//
		}

		private void addTemporalPartial(SACFileName sacname, Set<TimewindowInformation> timewindowCurrentEvent) throws IOException {
			Set<TimewindowInformation> tmpTws = timewindowCurrentEvent.stream()
					.filter(info -> info.getStation().getName().equals(sacname.getStationName()))
					.collect(Collectors.toSet());
			if (tmpTws.size() == 0) {
				return;
			}

			System.out.println(sacname + " (time partials)");

			SACData sacdata = sacname.read();
			Station station = sacdata.getStation();

			for (SACComponent component : components) {
				Set<TimewindowInformation> tw = tmpTws.stream()
						.filter(info -> info.getStation().equals(station))
						.filter(info -> info.getGlobalCMTID().equals(id))
						.filter(info -> info.getComponent().equals(component)).collect(Collectors.toSet());

				if (tw.isEmpty()) {
					tmpTws.forEach(window -> {
						System.out.println(window);
						System.out.println(window.getStation().getPosition());
					});
					System.err.println(station.getPosition());
					System.err.println("Ignoring empty timewindow " + sacname + " " + station);
					continue;
				}

				for (TimewindowInformation t : tw) {
					double[] filteredUt = sacdata.createTrace().getY();
					cutAndWrite(station, filteredUt, t);
				}
			}
		}

		/**
		 * @param u
		 *            partial waveform
		 * @param timewindowInformation
		 *            cut information
		 * @return u cut by considering sampling Hz
		 */
		private double[] sampleOutput(double[] u, TimewindowInformation timewindowInformation) {
			int cutstart = (int) (timewindowInformation.getStartTime() * partialSamplingHz);
			// 書きだすための波形
			int outnpts = (int) ((timewindowInformation.getEndTime() - timewindowInformation.getStartTime())
					* finalSamplingHz);
			double[] sampleU = new double[outnpts];
			// cutting a waveform for outputting
			Arrays.setAll(sampleU, j -> u[cutstart + j * step]);

			return sampleU;
		}

		private void cutAndWrite(Station station, double[] filteredUt, TimewindowInformation t) {

			double[] cutU = sampleOutput(filteredUt, t);
			Location stationLocation = new Location(station.getPosition().getLatitude(), station.getPosition().getLongitude(), Earth.EARTH_RADIUS);

			if (sourceTimeFunction == -1)
				System.err.println("Warning: check that the source time function used for the time partial is the same as the one used here.");

			PartialID PIDReceiverSide = new PartialID(station, id, t.getComponent(), finalSamplingHz, t.getStartTime(), cutU.length,
					1 / maxFreq, 1 / minFreq, t.getPhases(), 0, true, stationLocation, PartialType.TIME_RECEIVER,
					cutU);
			PartialID PIDSourceSide = new PartialID(station, id, t.getComponent(), finalSamplingHz, t.getStartTime(), cutU.length,
					1 / maxFreq, 1 / minFreq, t.getPhases(), 0, true, id.getEvent().getCmtLocation(), PartialType.TIME_SOURCE,
					cutU);

			try {
				partialDataWriter.addPartialID(PIDReceiverSide);
				partialDataWriter.addPartialID(PIDSourceSide);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		private WorkerTimePartial(EventFolder eventDir) {
			this.eventDir = eventDir;
			id = eventDir.getGlobalCMTID();
		};
	}

	private ButterworthFilter filter;

	/**
	 * バンドパスを安定させるためwindowを左右に ext = max period(s) ずつ伸ばす
	 */
	private int ext;

	/**
	 * sacdataを何ポイントおきに取り出すか
	 */
	private int step;

	private Set<TimewindowInformation> timewindowInformation;

	private Set<GlobalCMTID> touchedSet = new HashSet<>();

	public PartialDatasetMaker(Properties property) throws IOException {
		this.property = (Properties) property.clone();
		set();
	}

	public static void writeDefaultPropertiesFile() throws IOException {
		Path outPath = Paths.get(PartialDatasetMaker.class.getName() + Utilities.getTemporaryString() + ".properties");
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outPath, StandardOpenOption.CREATE_NEW))) {
			pw.println("manhattan PartialDatasetMaker");
			pw.println("##Path of a working folder (.)");
			pw.println("#workPath");
			pw.println("##SacComponents to be used (Z R T)");
			pw.println("#components");
			pw.println("##Path of a back propagate spc folder (BPinfo)");
			pw.println("#bpPath");
			pw.println("##Path of a forward propagate spc folder (FPinfo)");
			pw.println("#fpPath");
			pw.println("##String if it is PREM spector file is in bpdir/PREM  (PREM)");
			pw.println("#modelName");
			pw.println("##Type source time function 0:none, 1:boxcar, 2:triangle. (0)");
			pw.println("##or folder name containing *.stf if you want to your own GLOBALCMTID.stf ");
			pw.println("#sourceTimeFunction");
			pw.println("##Path of a time window file, must be set");
			pw.println("#timewindowPath timewindow.dat");
			pw.println("##PartialType[] compute types (MU)");
			pw.println("#partialTypes");
			pw.println("##double time length DSM parameter tlen, must be set");
			pw.println("#tlen 6553.6");
			pw.println("##int step of frequency domain DSM parameter np, must be set");
			pw.println("#np 1024");
			pw.println("##double minimum value of passband (0.005)");
			pw.println("#minFreq");
			pw.println("##double maximum value of passband (0.08)");
			pw.println("#maxFreq");
			pw.println("#double (20)");
			pw.println("#partialSamplingHz cant change now");
			pw.println("##double SamplingHz in output dataset (1)");
			pw.println("#finalSamplingHz");
			pw.println("##perturbationPath, must be set");
			pw.println("#perturbationPath perturbationPoint.inf");
			pw.println("##File for Qstructure (if no file, then PREM)");
			pw.println("#qinf");
			pw.println("##path of the time partials directory, must be set if PartialType containes TIME_SOURCE or TIME_RECEIVER");
			pw.println("#timePartialPath");
		}
		System.err.println(outPath + " is created.");
	}

	private void checkAndPutDefaults() {
		if (!property.containsKey("workPath"))
			property.setProperty("workPath", "");
		if (!property.containsKey("components"))
			property.setProperty("components", "Z R T");
		if (!property.containsKey("bpPath"))
			property.setProperty("bpPath", "BPinfo");
		if (!property.containsKey("fpPath"))
			property.setProperty("fpPath", "FPinfo");
		if (!property.containsKey("modelName"))
			property.setProperty("modelName", "PREM");
		if (!property.containsKey("maxFreq"))
			property.setProperty("maxFreq", "0.08");
		if (!property.containsKey("minFreq"))
			property.setProperty("minFreq", "0.005");
		// if (!property.containsKey("backward")) TODO allow user to change
		// property.setProperty("backward", "true");partialSamplingHz
		if (!property.containsKey("sourceTimeFunction"))
			property.setProperty("sourceTimeFunction", "0");
		if (!property.containsKey("partialTypes"))
			property.setProperty("partialTypes", "MU");
		if (!property.containsKey("partialSamplingHz"))
			property.setProperty("partialSamplingHz", "20");
		if (!property.containsKey("finalSamplingHz"))
			property.setProperty("finalSamplingHz", "1");

	}

	/**
	 * parameterのセット
	 */
	private void set() throws IOException {
		checkAndPutDefaults();
		workPath = Paths.get(property.getProperty("workPath"));

		if (!Files.exists(workPath))
			throw new RuntimeException("The workPath: " + workPath + " does not exist");

		bpPath = getPath("bpPath");
		fpPath = getPath("fpPath");
		timewindowPath = getPath("timewindowPath");
		components = Arrays.stream(property.getProperty("components").split("\\s+")).map(SACComponent::valueOf)
				.collect(Collectors.toSet());

		if (property.containsKey("qinf"))
			structure = new PolynomialStructure(getPath("qinf"));
		try {
			sourceTimeFunction = Integer.parseInt(property.getProperty("sourceTimeFunction"));
		} catch (Exception e) {
			sourceTimeFunction = -1;
			sourceTimeFunctionPath = getPath("sourceTimeFunction");
		}
		modelName = property.getProperty("modelName");

		partialTypes = Arrays.stream(property.getProperty("partialTypes").split("\\s+")).map(PartialType::valueOf)
				.collect(Collectors.toSet());

		if (partialTypes.contains(PartialType.TIME_RECEIVER) || partialTypes.contains(PartialType.TIME_SOURCE)) {
			timePartialPath = Paths.get(property.getProperty("timePartialPath"));
			if (!Files.exists(timePartialPath))
				throw new RuntimeException("The timePartialPath: " + timePartialPath + " does not exist");
		}

		tlen = Double.parseDouble(property.getProperty("tlen"));
		np = Integer.parseInt(property.getProperty("np"));
		minFreq = Double.parseDouble(property.getProperty("minFreq"));
		maxFreq = Double.parseDouble(property.getProperty("maxFreq"));
		perturbationPath = getPath("perturbationPath");
		// partialSamplingHz
		// =Double.parseDouble(reader.getFirstValue("partialSamplingHz")); TODO

		finalSamplingHz = Double.parseDouble(property.getProperty("finalSamplingHz"));
	}

	private void setLog() throws IOException {
		synchronized (PartialDatasetMaker.class) {
			do {
				dateString = Utilities.getTemporaryString();
				logPath = workPath.resolve("pdm" + dateString + ".log");
			} while (Files.exists(logPath));
			Files.createFile(logPath);
		}
	}

	private void setOutput() throws IOException {

		// 書き込み準備
		Path idPath = workPath.resolve("partialID" + dateString + ".dat");
		Path datasetPath = workPath.resolve("partial" + dateString + ".dat");

		partialDataWriter = new WaveformDataWriter(idPath, datasetPath, stationSet, idSet, periodRanges,
				phases, perturbationLocationSet);
		writeLog("Creating " + idPath + " " + datasetPath);
		System.out.println("Creating " + idPath + " " + datasetPath);

	}

	// TODO
	private Set<Station> stationSet;
	private Set<GlobalCMTID> idSet;
	private double[][] periodRanges;
	private Phase[] phases;
	private Set<Location> perturbationLocationSet;

	private void readPerturbationPoints() throws IOException {
		try (Stream<String> lines = Files.lines(perturbationPath)) {
			perturbationLocationSet = lines.map(line -> line.split("\\s+"))
					.map(parts -> new Location(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
							Double.parseDouble(parts[2])))
					.collect(Collectors.toSet());
		}
		if (timePartialPath != null) {
			if (stationSet.isEmpty() || idSet.isEmpty())
				throw new RuntimeException("stationSet and idSet must be set before perturbationLocation");
			stationSet.forEach(station -> perturbationLocationSet.add(new Location(station.getPosition().getLatitude(),
					station.getPosition().getLongitude(), Earth.EARTH_RADIUS)));
			idSet.forEach(id -> perturbationLocationSet.add(id.getEvent().getCmtLocation()));
		}
	}

	private String dateString;

	private WaveformDataWriter partialDataWriter;

	private Path logPath;

	private long startTime = System.nanoTime();
	private long endTime;

	private boolean jointCMT;

	/*
	 * sort timewindows comparing stations
	 */
	private List<TimewindowInformation> sortTimewindows() {
		List<TimewindowInformation> timewindows = timewindowInformation.stream().collect(Collectors.toList());

		Comparator<TimewindowInformation> comparator = new Comparator<TimewindowInformation>() {
			@Override
			public int compare(TimewindowInformation o1, TimewindowInformation o2) {
				int res = o1.getStation().compareTo(o2.getStation());
				if (res != 0)
					return res;
				else {
					return o1.getGlobalCMTID().compareTo(o2.getGlobalCMTID());
				}
			}
		};
		timewindows.sort(comparator);

		return timewindows;
	}

	@Override
	public void run() throws IOException {
		setLog();
		final int N_THREADS = Runtime.getRuntime().availableProcessors();
		writeLog("Running " + N_THREADS + " threads");
		setTimeWindow();
		// filter設計
		setBandPassFilter();
		// read a file for perturbation points.
		readPerturbationPoints();

		// バンドパスを安定させるためwindowを左右に ext = max period(s) ずつ伸ばす
		ext = (int) (1 / minFreq * partialSamplingHz);

		// sacdataを何ポイントおきに取り出すか
		step = (int) (partialSamplingHz / finalSamplingHz);
		setOutput();
		int bpnum = 0;
		setSourceTimeFunctions();

		// time partials for each event
		if (timePartialPath != null) {
			ExecutorService execs = Executors.newFixedThreadPool(N_THREADS);
			Set<EventFolder> timePartialEventDirs = Utilities.eventFolderSet(timePartialPath);
			for (EventFolder eventDir : timePartialEventDirs) {
				execs.execute(new WorkerTimePartial(eventDir));
				System.out.println("Working for time partials for " + eventDir);
			}
			execs.shutdown();
			while (!execs.isTerminated()) {
				try {
					Thread.sleep(100);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			partialDataWriter.flush();
			System.out.println();
		}

		for (Station station : stationSet) {
			Path bp0000Path = bpPath.resolve("0000" + station.toString());
			Path bpModelPath = bp0000Path.resolve(modelName);

			// Set of global cmt IDs for the station in the timewindow.
			Set<GlobalCMTID> idSet = timewindowInformation.stream()
					.filter(info -> components.contains(info.getComponent()))
					.filter(info -> info.getStation().equals(station)).map(TimewindowInformation::getGlobalCMTID)
					.collect(Collectors.toSet());

			if (idSet.isEmpty())
				continue;

			// bpModelFolder内 spectorfile
			Set<SpcFileName> bpSHFiles = Utilities.collectSHSpcFileName(bpModelPath);
			System.out.println(bpSHFiles.size() + " SH bpfiles are found");

			// stationに対するタイムウインドウが存在するfp内のmodelフォルダ
			Path[] fpEventPaths = null;
			List<Path[]> fpPathList = null;
			if (!jointCMT) {
				fpEventPaths = idSet.stream().map(id -> fpPath.resolve(id + "/" + modelName))
					.filter(Files::exists).toArray(Path[]::new);
			}
			else {
				fpPathList = collectFP_jointCMT(idSet);
			}


//			int donebp = 0;
//			AtomicInteger donebp = new AtomicInteger(0);
			// bpフォルダ内の各bpファイルに対して
			// create ThreadPool
			ExecutorService execs = Executors.newFixedThreadPool(N_THREADS);

			/**
			bpFiles.stream().forEachOrdered(bpname -> {
				System.out.println("Working for " + bpname.getName() + " " + donebp.incrementAndGet() + "/" + bpFiles.size());
				DSMOutput bp;
				try {
					bp = bpname.read();
					String pointName = bp.getObserverName();
					Arrays.stream(fpEventPaths).forEachOrdered(fpp -> {

					});
				} catch (IOException e) {
					// TODO 自動生成された catch ブロック
					e.printStackTrace();
				}
			});
			**/

			//SH modeのBPファイルに対して
			for (SpcFileName bpSHname : bpSHFiles) {
//				System.out.println("Working for " + bpname.getName() + " " + ++donebp + "/" + bpFiles.size());
				// 摂動点の名前
				DSMOutput bpSH = bpSHname.read();
				String pointName = bpSH.getObserverName();

				SpcFileName bpPSVname = pairBPFile(bpPath, bpSHname);

				try {
					if(bpPSVname.exists() && bpSHname.exists()) {
					DSMOutput bpPSV = bpPSVname.read();
					System.out.println(bpSHname+" & "+bpPSVname);
					for (Path fpEventPath : fpEventPaths) {
						String eventName = fpEventPath.getParent().getFileName().toString();
						SpcFileName fpSHfile = new SpcFileName(
								fpEventPath.resolve(pointName + "." + eventName + ".PF..." + bpSHname.getMode() + ".spc"));
						SpcFileName fpPSVfile = new SpcFileName(
								fpEventPath.resolve(pointName + "." + eventName + ".PF..." + bpPSVname.getMode() + ".spc"));
						if (!fpSHfile.exists())
							continue;
						PartialComputation pc = new PartialComputation(bpSH, bpPSV, station, fpSHfile, fpPSVfile);
						execs.execute(pc);
					}
					}
					else if (!bpPSVname.exists() && bpSHname.exists()) {
						System.out.println(bpSHname+" only!");
						for (Path fpEventPath : fpEventPaths) {
							String eventName = fpEventPath.getParent().getFileName().toString();
							SpcFileName fpSHfile = new SpcFileName(
									fpEventPath.resolve(pointName + "." + eventName + ".PF..." + bpSHname.getMode() + ".spc"));
//							System.out.println(fpSHfile.toString()+" "+fpSHfile.exists());
							if (!fpSHfile.exists())
								continue;
							PartialComputation pc = new PartialComputation(bpSH, station, fpSHfile);
							execs.execute(pc);
						}
					}
				} catch (IOException e) {
					continue;
				}

				// timewindowの存在するfpdirに対して
				// bpファイルに対する全てのfpファイルを
				// SH modeを基準にして適切なpair(PSV)があれば用意する
//				if (!jointCMT)
//				{

//				}
//				else {
//					for (Path[] fpEventGreenPath : fpPathList) {
//						String eventName = fpEventGreenPath[0].getParent().getFileName().toString().split("_")[0];
//						System.out.println(eventName);
//						SpcFileName[] fpfiles = new SpcFileName[6];
//						for (int i = 0; i < 6; i++) {
//							fpfiles[i] = new SpcFileName(fpEventGreenPath[i]
//									.resolve(pointName + "." + eventName + ".PF..." + bpname.getMode() + ".spc"));
//						}
//						SpcFileName fpfile = computeFP(SpcFileName[] fpGreenFiles);
////						if (!fpfile.exists())
////							continue;
//						PartialComputation pc = new PartialComputation(bp, station, fpfile);
//						execs.execute(pc);
//					}
//				}
			}
			execs.shutdown();
			while (!execs.isTerminated()) {
				try {
					Thread.sleep(100);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			partialDataWriter.flush();
			System.out.println();
			writeLog(+bpnum++ + "th " + bp0000Path + " was done ");
		}
		terminate();
	}

	private SpcFileName pairBPFile(Path spcFolder, SpcFileName spcFileName) {
		if (spcFileName.getMode() == SpcFileComponent.SH)
			return new SpcFileName(spcFolder.resolve("0000"+spcFileName.getSourceID()
				+ "/" + modelName + "/"
				+ spcFileName.getName().replace("SH.spc", "PSV.spc")));
		else
			return new SpcFileName(spcFolder.resolve("0000"+spcFileName.getSourceID()
					+ "/" + modelName + "/"
					+ spcFileName.getName().replace("PSV.spc", "SH.spc")));
	}

	private List<Path[]> collectFP_jointCMT(Set<GlobalCMTID> idSet) {
		List<Path[]> paths = new ArrayList<>();

		for (GlobalCMTID id : idSet) {
			Path[] tmpPaths = new Path[6];
			for (int i = 0; i < 6; i++)
				tmpPaths[i] = fpPath.resolve(id + "_mt" + i + "/" + modelName);
			paths.add(tmpPaths);
		}

		return paths;
	}

//	private DSMOutput computeFP(SpcFileName[] fpGreenFiles) {
//		DSMOutput fp = fpGreenFiles[0];
//		MomentTensor mt = new GlobalCMTID(fpGreenFiles[0].getSourceID())
//			.getEvent().getCmt();
//
//		if (fpGreenFiles.length != 6)
//			System.err.println("FP green functions must have 6 components");
//
//		for (int i = 0; i < 6; i++) {
//
//		}
//
//		return fp;
//	}

	private Map<GlobalCMTID, SourceTimeFunction> userSourceTimeFunctions;

	private void setSourceTimeFunctions() throws IOException {
		if (sourceTimeFunction == 0)
			return;
		if (sourceTimeFunction == -1) {
			readSourceTimeFunctions();
			return;
		}
		userSourceTimeFunctions = new HashMap<>();
		idSet.forEach(id -> {
			double halfDuration = id.getEvent().getHalfDuration();
			SourceTimeFunction stf = sourceTimeFunction == 1
					? SourceTimeFunction.boxcarSourceTimeFunction(np, tlen, partialSamplingHz, halfDuration)
					: SourceTimeFunction.triangleSourceTimeFunction(np, tlen, partialSamplingHz, halfDuration);
			userSourceTimeFunctions.put(id, stf);
		});

	}

	private void readSourceTimeFunctions() throws IOException {
		userSourceTimeFunctions = idSet.stream().collect(Collectors.toMap(id -> id, id -> {
			try {
				Path sourceTimeFunctionPath = this.sourceTimeFunctionPath.resolve(id + ".stf");
				return SourceTimeFunction.readSourceTimeFunction(sourceTimeFunctionPath);
			} catch (Exception e) {
				throw new RuntimeException("Source time function file for " + id + " is broken.");
			}
		}));

	}

	/**
	 * @param args
	 *            [parameter file name]
	 */
	public static void main(String[] args) throws IOException {
		PartialDatasetMaker pdm = new PartialDatasetMaker(Property.parse(args));
		long startTime = System.nanoTime();

		System.err.println(PartialDatasetMaker.class.getName() + " is going..");
		pdm.run();
		System.err.println(PartialDatasetMaker.class.getName() + " finished in "
				+ Utilities.toTimeString(System.nanoTime() - startTime));
	}

	private void terminate() throws IOException {
		partialDataWriter.close();
		endTime = System.nanoTime();
		long nanoSeconds = endTime - startTime;
		String endLine = "Everything is done in " + Utilities.toTimeString(nanoSeconds) + ". Over n out! ";
		System.err.println(endLine);
		writeLog(endLine);
		writeLog(partialDataWriter.getIDPath() + " " + partialDataWriter.getDataPath() + " were created");
	}

	private synchronized void writeLog(String line) throws IOException {
		try (PrintWriter pw = new PrintWriter(
				Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
			pw.print(new Date() + " : ");
			pw.println(line);
		}
	}

	private void setBandPassFilter() throws IOException {
		System.err.println("Designing filter.");
		double omegaH = maxFreq * 2 * Math.PI / partialSamplingHz;
		double omegaL = minFreq * 2 * Math.PI / partialSamplingHz;
		filter = new BandPassFilter(omegaH, omegaL, 4);
		writeLog(filter.toString());
		periodRanges = new double[][] { { 1 / maxFreq, 1 / minFreq } };
	}

	/**
	 * Reads timewindow information
	 *
	 * @throws IOException if any
	 */
	private void setTimeWindow() throws IOException {
		// タイムウインドウの情報を読み取る。
		System.err.println("Reading timewindow information");
		timewindowInformation = TimewindowInformationFile.read(timewindowPath);
		idSet = new HashSet<>();
		stationSet = new HashSet<>();
		timewindowInformation.forEach(t -> {
			idSet.add(t.getGlobalCMTID());
			stationSet.add(t.getStation());
		});
		phases = timewindowInformation.parallelStream().map(TimewindowInformation::getPhases).flatMap(p -> Stream.of(p))
				.distinct().toArray(Phase[]::new);

		stationSet.stream().forEach(s ->
			System.out.println(s.getName()+" "+s.getNetwork()+" "+s.getPosition()));
		System.out.println();
		stationSet.stream().map(Station::toString).distinct().forEach(s ->
		System.out.println(s));

		// TODO
		if (stationSet.size() != stationSet.stream().map(Station::toString).distinct().count()) {
			System.err.println("CAUTION!! Stations with same name and network but different positions detected!");
			Map<String, List<Station>> nameToStation = new HashMap<>();
			stationSet.forEach(sta -> {
				if (nameToStation.containsKey(sta.toString())) {
					List<Station> tmp = nameToStation.get(sta.toString());
					tmp.add(sta);
					nameToStation.put(sta.toString(), tmp);
				}
				else {
					List<Station> tmp = new ArrayList<>();
					tmp.add(sta);
					nameToStation.put(sta.toString(), tmp);
				}
			});
			nameToStation.forEach((name, sta) -> {
				if (sta.size() > 1) {
					sta.stream().forEach(s -> System.out.println(s+" "+s.getNetwork()+" "+s.getPosition()));
				}
			});
			throw new RuntimeException("Station duplication");
		}

		boolean fpExistence = idSet.stream().allMatch(id -> Files.exists(fpPath.resolve(id.toString())));
		boolean bpExistence = stationSet.stream().allMatch(station -> Files.exists(bpPath.resolve("0000" + station)));
		if (!fpExistence) {
			idSet.stream().filter(id -> !Files.exists(fpPath.resolve(id.toString())))
				.forEach(id -> System.out.println(id));
			throw new RuntimeException("propagation spectors are not enough for " + timewindowPath);
		}
		if (!bpExistence) {
			stationSet.stream().filter(station -> !Files.exists(bpPath.resolve("0000" + station)))
				.forEach(sta -> System.out.println(sta));
			throw new RuntimeException("propagation spectors are not enough for " + timewindowPath);
		}
		writeLog(timewindowInformation.size() + " timewindows are found in " + timewindowPath + ". " + idSet.size()
				+ " events and " + stationSet.size() + " stations.");
	}

}
