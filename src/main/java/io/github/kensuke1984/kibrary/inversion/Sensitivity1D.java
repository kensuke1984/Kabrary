package io.github.kensuke1984.kibrary.inversion;

import io.github.kensuke1984.anisotime.Phase;
import io.github.kensuke1984.kibrary.math.Matrix;
import io.github.kensuke1984.kibrary.util.Earth;
import io.github.kensuke1984.kibrary.util.HorizontalPosition;
import io.github.kensuke1984.kibrary.util.Location;
import io.github.kensuke1984.kibrary.util.Phases;
import io.github.kensuke1984.kibrary.waveformdata.PartialID;
import io.github.kensuke1984.kibrary.waveformdata.PartialIDFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.math3.linear.ArrayRealVector;


public class Sensitivity1D {
	private Map<PerturbationR_distance, Double> sensitivityMap;
	private PartialID[] ids;
	Set<Phases> includePhases;
	private double max = 0;
	
	public Sensitivity1D(PartialID[] ids, Set<Phases> includedPhases) {
		this.ids = ids;
		this.sensitivityMap = new HashMap<>();
		this.includePhases = includedPhases;
		this.compute();
	}
	
	public Sensitivity1D(PartialID[] ids) {
		this(ids, null);
	}
	
	public Sensitivity1D(Sensitivity1D s) {
		this.ids = s.ids;
		this.sensitivityMap = s.sensitivityMap;
		this.includePhases = s.includePhases;
	}
	
	
	public static void main(String[] args) throws IOException {
		if (args.length != 2)
			System.err.println("usage: path for partial.dat and partialID.dat are need.");
//		Path partialPath = Paths.get("partial.dat");
		Path partialPath = Paths.get(args[0]);
//		Path partialIDPath = Paths.get("partialID.dat");
		Path partialIDPath = Paths.get(args[1]);
//		Set<Phase> S_ScS_Sdiff = Arrays.asList(Phase.S, Phase.ScS, Phase.create("Sdiff", false)).stream().collect(Collectors.toSet());
//		Set<Phase> ScS4 = Arrays.asList(Phase.create("ScSScSScSScS", false)).stream().collect(Collectors.toSet());
//		Set<Phase> ScS3 = Arrays.asList(Phase.create("ScSScSScS", false)).stream().collect(Collectors.toSet());
//		Set<Phase> SS_SSS = Arrays.asList(Phase.create("SS", false), Phase.create("SSS", false)).stream().collect(Collectors.toSet());
//		Set<Phase> all = new HashSet<>();
		
		PartialID[] ids = PartialIDFile.readPartialIDandDataFile(partialIDPath, partialPath);
		
		Map<Phases, Double> phasesSensitivityMap = Sensitivity.sensitivityPerWindowType(ids);
		Set<Phases> keySet = phasesSensitivityMap.keySet();
		Set<Phases> lowerMantle = keySet.stream().filter(phases -> phases.isLowerMantle())
				.collect(Collectors.toSet());
		Set<Phases> upperMantle = keySet.stream().filter(phases -> phases.isUpperMantle())
				.collect(Collectors.toSet());
		Set<Phases> mixte = keySet.stream().filter(phases -> phases.isMixte())
				.collect(Collectors.toSet());
		double upperMantleSensitivity = 0;
		double lowerMantleSensitivity = 0;
		for (Map.Entry<Phases, Double> entry : phasesSensitivityMap.entrySet()) {
			Phases p = entry.getKey();
			double s = entry.getValue();
			if (upperMantle.contains(p))
				upperMantleSensitivity += s;
			else if (lowerMantle.contains(p))
				lowerMantleSensitivity += s;
		}
//		System.out.println("Upper mantle " + upperMantleSensitivity);
//		System.out.println("Lower mantle " + lowerMantleSensitivity);
		
//		Path outPath = Paths.get("sensitivity1D.txt");
//		Sensitivity1D s1D = new Sensitivity1D(ids);
//		s1D.write1D(outPath);
		
		List<Phase> phaseList = Arrays.asList(Phase.S, Phase.ScS, Phase.create("Sdiff"), Phase.SKS, Phase.create("SKKS"),
				Phase.create("SKKKS"), Phase.P, Phase.PcP, Phase.PKP, Phase.create("Pdiff"));
		
		for (Phase phase : phaseList) {
			Set<Phases> phaseSet = new HashSet<>();
			phaseSet.add(new Phases(new Phase[] {phase}));
			Path outPath = Paths.get("sensitivity1D-" + phase.toString() + ".inf");
			Path outPath1 = Paths.get("sensitivityMap-" + phase.toString() + ".inf");
			Path outPath2 = Paths.get("sensitivityMapDistanceNormalized-" + phase.toString() + ".inf");
			Sensitivity1D s1D = new Sensitivity1D(ids, phaseSet);
			Sensitivity1D copy = new Sensitivity1D(s1D);
			s1D.write1D(outPath);
			s1D.normalize();
			s1D.writeComplemented(outPath1, 10., 1.);
			copy.normalizePerDistance();
			copy.writeComplemented(outPath2, 10., 1.);
		}
		Set<Phases> phaseSet = new HashSet<>();
		phaseSet.add(new Phases(new Phase[] {Phase.S, Phase.ScS}));
		Path outPath = Paths.get("sensitivity1D-S_ScS.inf");
		Path outPath1 = Paths.get("sensitivityMap-S_ScS.inf");
		Path outPath2 = Paths.get("sensitivityMapDistanceNormalized-S_ScS.inf");
		Sensitivity1D s1D = new Sensitivity1D(ids, phaseSet);
		Sensitivity1D copy = new Sensitivity1D(s1D);
		s1D.write1D(outPath);
		s1D.normalize();
		s1D.writeComplemented(outPath1, 10., 1.);
		copy.normalizePerDistance();
		copy.writeComplemented(outPath2, 10., 1.);
		
//		Path outPath = Paths.get("sensitivity1D-upperMantle.txt");
//		Path outPath1 = Paths.get("sensitivityMap-upperMantle.txt");
//		Path outPath2 = Paths.get("sensitivityMap-upperMantle-normalizedDistance.txt");
//		Sensitivity1D s1D = new Sensitivity1D(ids, upperMantle);
//		Sensitivity1D copy = new Sensitivity1D(s1D);
//		s1D.write1D(outPath);
//		s1D.normalize();
//		s1D.write(outPath1);
//		copy.normalizePerDistance();
//		copy.write(outPath2);
//		
//		outPath = Paths.get("sensitivity1D-lowerMantle.txt");
//		outPath1 = Paths.get("sensitivityMap-lowerMantle.txt");
//		outPath2 = Paths.get("sensitivityMap-lowerMantle-normalizedDistance.txt");
//		s1D = new Sensitivity1D(ids, lowerMantle);
//		copy = new Sensitivity1D(s1D);
//		s1D.write1D(outPath);
//		s1D.normalize();
//		s1D.write(outPath1);
//		copy.normalizePerDistance();
//		copy.write(outPath2);
//		
//		outPath = Paths.get("sensitivity1D-mixte.txt");
//		outPath1 = Paths.get("sensitivityMap-mixte.txt");
//		outPath2 = Paths.get("sensitivityMap-mixte-normalizedDistance.txt");
//		s1D = new Sensitivity1D(ids, mixte);
//		copy = new Sensitivity1D(s1D);
//		s1D.write1D(outPath);
//		s1D.normalize();
//		s1D.write(outPath1);
//		copy.normalizePerDistance();
//		copy.write(outPath2);
	}
	
	public void write(Path outpath) throws IOException{
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outpath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))) {
			pw.println("#perturbationR, epicentralDistanceBin, NormalizedSensitivity");
			pw.println("#max " + getMax());
			sensitivityMap.forEach((rDistance, sensitivity) -> {
				pw.println(rDistance.r + " " + rDistance.distance + " " + sensitivity);
			});
		}
	}
	
	public void writeComplemented(Path outpath, double deltaR, double deltaD) throws IOException {
		double[][] complemented = complement(deltaR, deltaD);
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outpath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))) {
			pw.println("#perturbationR, epicentralDistanceBin, NormalizedSensitivity");
			pw.println("#max " + getMax());
			for (double[] c : complemented)
				pw.println(c[0] + " " + c[1] + " " + c[2]);
		}
	}
	
	public double[][] complement(double deltaR, double deltaD) {
		List<Double> distanceList = sensitivityMap.keySet().stream().map(key -> key.distance).collect(Collectors.toList());
		Collections.sort(distanceList);
		double minD = distanceList.get(0);
		double maxD = distanceList.get(distanceList.size() - 1);
		
		int nR = (int) ((Earth.EARTH_RADIUS - 3480.) / deltaR) + 1;
		deltaR = (Earth.EARTH_RADIUS - 3480.) / (nR-1);
		int nD = (int) ((maxD - minD) / deltaD) + 1;
		deltaD = (maxD - minD) / (nD-1);
		
		System.out.println(nR + " " + nD);
		
		double[][] complemented = new double[nR * nD][];
		
		
		Location[] locations = sensitivityMap.keySet().stream().map(key -> new Location(0., key.distance, key.r))
				.toArray(Location[]::new);
		
		Complementation c = new Complementation();
		for (int i = 0; i < nR; i++) {
			for (int j = 0; j < nD; j++) {
				Location loc = new Location(0., minD + j * deltaD, 3480. + i * deltaR);
				Location[] nearPoints = c.getNearest4(locations, loc);
				double[] nearpointsValue = new double[nearPoints.length];
				for (int k = 0; k < nearPoints.length; k++)
					nearpointsValue[k] = sensitivityMap.get(new PerturbationR_distance(nearPoints[k].getR(), nearPoints[k].getLongitude()));
				double value = c.complement(nearPoints, nearpointsValue, loc);
				
				complemented[i*nD + j] = new double[] {loc.getLongitude(), loc.getR(), value};
			}
		}
		
		return complemented;
	}
	
	public void write1D(Path outpath) throws IOException {
		double[][] rSensitvity = shrinkTo1D();
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outpath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))) {
			pw.println("#perturbationR, sensitivity");
			for (double[] rs : rSensitvity)
				pw.println(rs[0] + " " + rs[1]);
		}
	}
	
	public void compute() {
		for (PartialID id : ids) {
			if (id.getPartialType().isTimePartial())
				continue;
			if (includePhases == null || includePhases.contains(new Phases(id.getPhases()))) {
				PerturbationR_distance rDistance = new PerturbationR_distance(id);
				if (sensitivityMap.containsKey(rDistance)) {
					double sensitivity = idToSensitivity(id)
							+ sensitivityMap.get(rDistance);
					sensitivityMap.replace(rDistance, sensitivity);
				}
				else {
					double sensitivity = idToSensitivity(id);
					sensitivityMap.put(rDistance, sensitivity);
				}
			}
		}
	}
	
	public double[][] shrinkTo1D() {
		Map<Double, Double> rSensitivityMap = new HashMap<>();
		sensitivityMap.forEach((rDist, s) -> {
			if (rSensitivityMap.containsKey(rDist.r)) {
				double tmpS = rSensitivityMap.get(rDist.r) + s;
				rSensitivityMap.put(rDist.r, tmpS);
			}
			else
				rSensitivityMap.put(rDist.r, s);
		});
		SortedSet<Double> keys = new TreeSet<>(rSensitivityMap.keySet());
		double[][] rSensitivity = new double[rSensitivityMap.size()][];
		int i = 0;
		for (Double key : keys) {
			rSensitivity[i] = new double[2];
			rSensitivity[i][0] = key;
			rSensitivity[i][1] = rSensitivityMap.get(key);
			i++;
		}
		return rSensitivity;
	}
	
	public PartialID[] getIds() {
		return ids;
	}
	
	private boolean containPhases(PartialID id, Set<Phase> includePhases) {
		boolean contains = false;
		for (Phase phase : id.getPhases()) {
			if (includePhases.contains(phase))
				contains = true;
		}
		return contains;
	}
	
	public double getMaxSensitivity() {
		double max = Double.MIN_VALUE;
		for(Map.Entry<PerturbationR_distance, Double> entry : sensitivityMap.entrySet()) {
			max = Math.max(max, entry.getValue());
		}
		this.max = max;
		return max;
	}
	
	public double getMax() {
		return max;
	}
	
	private Map<Double, Double> getMaxPerDistance() {
		Map<Double, Double> maxPerDistanceMap = new HashMap<>();
		for (int i = 0; i < 180; i+=2)
			maxPerDistanceMap.put((double) i, 0.);
		for(Map.Entry<PerturbationR_distance, Double> entry : sensitivityMap.entrySet()) {
			if (maxPerDistanceMap.get(entry.getKey().distance) < entry.getValue())
				maxPerDistanceMap.put(entry.getKey().distance, entry.getValue());
		}
		return maxPerDistanceMap;
	}
	
	private void normalize() {
		double max = getMaxSensitivity();
		for(Map.Entry<PerturbationR_distance, Double> entry : sensitivityMap.entrySet()) {
			sensitivityMap.replace(entry.getKey(), entry.getValue() / max);
		}
	}
	
	private void normalizePerDistance() {
		Map<Double, Double> maxPerDistanceMap = getMaxPerDistance();
		for(Map.Entry<PerturbationR_distance, Double> entry : sensitivityMap.entrySet()) {
			double max = maxPerDistanceMap.get(entry.getKey().distance);
			if (max > 0)
				sensitivityMap.replace(entry.getKey(), entry.getValue() / max);
			else
				sensitivityMap.replace(entry.getKey(), Double.NaN);
		}
	}
	
	public static double idToSensitivity(PartialID id) {
		return Math.sqrt(new ArrayRealVector(id.getData()).dotProduct(new ArrayRealVector(id.getData())));
	}
	
	public static class PerturbationR_distance {
		public double r;
		public double distance;
		public PerturbationR_distance(double r, double distance) {
			this.r = r;
			this.distance = (int) (distance / 2.) * 2;
		}
		public PerturbationR_distance(PartialID id) {
			this.r = id.getPerturbationLocation().getR();
			double distance = (id.getGlobalCMTID().getEvent().getCmtLocation()
					.getEpicentralDistance(id.getStation().getPosition())
					* 180. / Math.PI);
			this.distance = (int) (distance / 2.) * 2;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PerturbationR_distance other = (PerturbationR_distance) obj;
			if (r != other.r)
				return false;
			if (distance != other.distance)
				return false;
			return true;
		}
		@Override
		public int hashCode() {
			int prime = 31;
			return prime * (int) Double.doubleToLongBits(r) * (int) Double.doubleToLongBits(distance);
		}
	}
}