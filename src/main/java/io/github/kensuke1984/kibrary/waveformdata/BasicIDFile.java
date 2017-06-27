package io.github.kensuke1984.kibrary.waveformdata;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.io.FilenameUtils;

import io.github.kensuke1984.anisotime.Phase;
import io.github.kensuke1984.kibrary.util.Station;
import io.github.kensuke1984.kibrary.util.Utilities;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTID;
import io.github.kensuke1984.kibrary.util.sac.SACComponent;
import io.github.kensuke1984.kibrary.util.sac.WaveformType;

/**
 * Utilities for a pair of an ID file and a waveform file. The files are for
 * observed and synthetic waveforms (NOT partial)<br>
 * 
 * The file contains
 * <p>
 * (File information)
 * <dd>Numbers of stations, events and period ranges</dd>
 * <p>
 * (All waveforms information)
 * <dd>
 * <ul>
 * <li>Each station information <br>
 * <ul><li>name, network, position</li></ul></li>
 * <li>Each event<br>
 *   <ul><li>Global CMT ID</li></ul></li>  
 * <li>Each period range<br>
 *   <ul><li>min period, max period</li></ul></li> </ul>
 * </dd>
 * <p>
 * (Each waveform information)
 * <ul>
 * <li>Each BasicID information<br>
 *  <ul><li>see in {@link #readBasicIDFile(Path)}</li></ul></li><br> </ul>
 * </dd>
 * 
 * TODO sampling Hz 
 * 
 * @see {@link BasicID}
 * 
 * @version 0.3.0.2
 * 
 * @author Kensuke Konishi
 * 
 */
public final class BasicIDFile {

	/**
	 * File size for an ID [byte]
	 */
	public static final int oneIDByte = 48;

	private BasicIDFile() {
	}

	private static void outputGlobalCMTID(String header, BasicID[] ids) throws IOException {
		Path outPath = Paths.get(header + ".globalCMTID");
		List<String> lines = Arrays.stream(ids).parallel().map(id -> id.ID.toString()).distinct().sorted()
				.collect(Collectors.toList());
		Files.write(outPath, lines, StandardOpenOption.CREATE_NEW);
		System.err.println(outPath + " is created as a list of global CMT IDs.");
	}

	private static void outputStations(String header, BasicID[] ids) throws IOException {
		Path outPath = Paths.get(header + ".station");
		List<String> lines = Arrays.stream(ids).parallel().map(id -> id.STATION).distinct().sorted()
				.map(s -> s.getName() + " " + s.getNetwork() + " " + s.getPosition())
				.collect(Collectors.toList());
		Files.write(outPath, lines, StandardOpenOption.CREATE_NEW);
		System.err.println(outPath + " is created as a list of stations.");
	}

	/**
	 * Options: -a: show all IDs
	 * 
	 * @param args
	 *            [option] [id file name]
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static void main(String[] args) throws IOException {
		if (args.length == 1) {
			BasicID[] ids = readBasicIDFile(Paths.get(args[0]));
			// print(Paths.get(args[0]));
			String header = FilenameUtils.getBaseName(Paths.get(args[0]).getFileName().toString());
			try {
				outputStations(header, ids);
				outputGlobalCMTID(header, ids);
			} catch (Exception e) {
				System.err.println("Could not write information about " + args[0]);
				System.err.println("If you want to see all IDs inside, then use a '-a' option.");
			}
		} else if (args.length == 2 && args[0].equals("-a")) {
			BasicID[] ids = readBasicIDFile(Paths.get(args[1]));
			Arrays.stream(ids).forEach(System.out::println);
		} else {
			System.err.println("usage:[-a] [id file name]\n if \"-a\", show all IDs");
		}
	}

	/**
	 * @param idPath
	 *            {@link Path} of an ID file, if it does not exist, an
	 *            IOException
	 * @param dataPath
	 *            {@link Path} of an data file, if it does not exist, an
	 *            IOException
	 * @return Array of {@link BasicID} containing waveform data
	 * @throws IOException
	 *             if an I/O error happens,
	 */
	public static BasicID[] readBasicIDandDataFile(Path idPath, Path dataPath) throws IOException {
		BasicID[] ids = readBasicIDFile(idPath);
		long dataSize = Files.size(dataPath);
		long t = System.nanoTime();
		BasicID lastID = ids[ids.length - 1];
		if (dataSize != lastID.START_BYTE + lastID.NPTS * 8)
			throw new RuntimeException(dataPath + " is invalid for " + idPath);
		try (BufferedInputStream bis = new BufferedInputStream(Files.newInputStream(dataPath))) {
			byte[][] bytes = new byte[ids.length][];
			Arrays.parallelSetAll(bytes, i -> new byte[ids[i].NPTS * 8]);
			for (int i = 0; i < ids.length; i++)
				bis.read(bytes[i]);
			IntStream.range(0, ids.length).parallel().forEach(i -> {
				BasicID id = ids[i];
				ByteBuffer bb = ByteBuffer.wrap(bytes[i]);
				double[] data = new double[id.NPTS];
				for (int j = 0; j < data.length; j++)
					data[j] = bb.getDouble();
				ids[i] = id.setData(data);
			});
		}
		System.err.println("Reading waveform done in " + Utilities.toTimeString(System.nanoTime() - t));
		return ids;
	}

	/**
	 * @param idPath
	 *            {@link Path} of an ID file
	 * @return Array of {@link BasicID} without waveform data
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public static BasicID[] readBasicIDFile(Path idPath) throws IOException, NegativeArraySizeException {
		try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(idPath)))) {
			long t = System.nanoTime();
			long fileSize = Files.size(idPath);
			
			// Read header
			Station[] stations = new Station[dis.readShort()];
			GlobalCMTID[] cmtIDs = new GlobalCMTID[dis.readShort()];
			double[][] periodRanges = new double[dis.readShort()][2];
			Phase[] phases = new Phase[dis.readShort()];
			System.out.println("station number is " + stations.length);
			System.out.println("cmtID number is " + cmtIDs.length);
			System.out.println("period range number is " + periodRanges.length);
			System.out.println("phase number is " + phases.length);
			int headerBytes = 2 * 4 + (8 + 8 + 4 * 2) * stations.length + 15 * cmtIDs.length
					+ 16 * phases.length + 4 * 2 * periodRanges.length;
			long idParts = fileSize - headerBytes;
			System.out.println(fileSize);
			System.out.println(headerBytes);
			System.out.println(idParts);
			System.out.println("please check " + idParts % oneIDByte);
			System.out.println(idParts/oneIDByte);
			if (idParts % oneIDByte != 0)
				throw new RuntimeException(idPath + " is not valid.");
			// name(8),network(8),position(4*2)
			byte[] stationBytes = new byte[24];
			for (int i = 0; i < stations.length; i++) {
				dis.read(stationBytes);
				stations[i] = Station.createStation(stationBytes);
			}
			byte[] cmtIDBytes = new byte[15];
			for (int i = 0; i < cmtIDs.length; i++) {
				dis.read(cmtIDBytes);
				cmtIDs[i] = new GlobalCMTID(new String(cmtIDBytes).trim());
			}
			for (int i = 0; i < periodRanges.length; i++) {
				periodRanges[i][0] = dis.readFloat();
				periodRanges[i][1] = dis.readFloat();
			}
			byte[] phaseBytes = new byte[16];
			for (int i = 0; i < phases.length; i++) {
				dis.read(phaseBytes);
				phases[i] = Phase.create(new String(phaseBytes).trim());
			}
			
			int nid = (int) (idParts / oneIDByte);
			BasicID[] ids = new BasicID[nid];
			byte[][] bytes = new byte[nid][oneIDByte];
			for (int i = 0; i < nid; i++)
				dis.read(bytes[i]);
			IntStream.range(0, nid).parallel().forEach(i -> {
				ids[i] = createID(bytes[i], stations, cmtIDs, periodRanges, phases);
//				System.out.println("kakuninnshiteyo "+ids[i].COMPONENT);
			});
			System.err.println(
					"Reading " + ids.length + " basic IDs done in " + Utilities.toTimeString(System.nanoTime() - t));
			return ids;
		}
	}

	/**
	 * An ID information contains<br>
	 * obs or syn(1)<br>
	 * station number(2)<br>
	 * event number(2)<br>
	 * component(1)<br>
	 * period range(1) <br>
	 * phases numbers(10*2)<br>
	 * start time(4)<br>
	 * number of points(4)<br>
	 * sampling hz(4) <br>
	 * convoluted(or observed) or not(1)<br>
	 * position of a waveform for the ID in the datafile(8)
	 * 
	 * @param bytes
	 *            for one ID
	 * @return an ID written in the bytes
	 */
	private static BasicID createID(byte[] bytes, Station[] stations, GlobalCMTID[] ids, double[][] periodRanges, Phase[] phases) {
		ByteBuffer bb = ByteBuffer.wrap(bytes);
		WaveformType type = 0 < bb.get() ? WaveformType.OBS : WaveformType.SYN;
//		System.out.println(type);
		Station station = stations[bb.getShort()];
//		System.out.println(station);
		GlobalCMTID id = ids[bb.getShort()];
//		System.out.println(id);
//		System.out.println("test ! "+bb.get()+" and type is "+type);
		SACComponent component = SACComponent.getComponent(bb.get());
//		System.out.println(component+" "+type);
		double[] period = periodRanges[bb.get()];
//		System.out.println(period[0] + " " + period[1]+ " "+type);
		Set<Phase> tmpset = new HashSet<>();
		for (int i = 0; i < 10; i++) {
			short iphase = bb.getShort();
//			System.out.println("iphase is "+iphase+" "+i);
			if (iphase != -1)
				tmpset.add(phases[iphase]);
		}
		Phase[] usablephases = new Phase[tmpset.size()];
		usablephases = tmpset.toArray(usablephases);
		double startTime = bb.getFloat(); // starting time
		int npts = bb.getInt(); // データポイント数
		double samplingHz = bb.getFloat();
		boolean isConvolved = 0 < bb.get();
		long startByte = bb.getLong();
		BasicID bid = new BasicID(type, samplingHz, startTime, npts, station, id, component, period[0], period[1],
				usablephases, startByte, isConvolved);
//		System.out.println(bid.toString());
		return bid;
	}
}