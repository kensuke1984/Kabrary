package io.github.kensuke1984.kibrary.firsthandler;

import io.github.kensuke1984.kibrary.external.SAC;
import io.github.kensuke1984.kibrary.util.Location;
import io.github.kensuke1984.kibrary.util.globalcmt.GlobalCMTData;
import io.github.kensuke1984.kibrary.util.sac.SACHeaderEnum;
import io.github.kensuke1984.kibrary.util.sac.SACUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Modification of SAC when running {@link SeedSAC}
 *
 * @author Kensuke Konishi
 * @version 0.1.8.4
 */
class SACModifier {

    /**
     * [msec] time window for taper
     */
    private static final int taperTime = 60 * 1000;

    private final GlobalCMTData EVENT;
    private final Path SAC_PATH;
    private Map<SACHeaderEnum, String> headerMap;

    /**
     * extract with PDE (true), CMT (false)
     */
    private final boolean BYPDE;

    private final Path MODIFIED_PATH;

    /**
     * sac start time when this instance is made
     */
    private LocalDateTime initialSacStartTime;

    /**
     * @param globalCMTData cmt data
     * @param sacPath       path of sac file
     * @param byPDE         true: PDE, false: CMT
     */
    SACModifier(GlobalCMTData globalCMTData, Path sacPath, boolean byPDE) throws IOException {
        SAC_PATH = sacPath;
        headerMap = SACUtil.readHeader(sacPath);
        String modifiedFileName = sacPath.getFileName().toString().replace(".SAC", ".MOD");
        MODIFIED_PATH = sacPath.resolveSibling(modifiedFileName);
        EVENT = globalCMTData;
        BYPDE = byPDE;
        setInitialSacStartTime();
    }

    /**
     * Checks of headers CMPINC, khole
     *
     * @return if the header is valid
     */
    boolean checkHeader() {
        // System.out.println("Checking header validity in "+sacFile);
        String channel = SAC_PATH.getFileName().toString().split("\\.")[3];

        // check CMPINC
        if (channel.equals("BHN") || channel.equals("BHE") || channel.equals("BH1") || channel.equals("BH2"))
            if (Double.parseDouble(headerMap.get(SACHeaderEnum.CMPINC)) != 90) return false;

        // check "khole" value
        String khole = headerMap.get(SACHeaderEnum.KHOLE);
        return khole.isEmpty() || khole.equals("00") || khole.equals("01") || khole.equals("02");
    }

    /**
     * operate rtrend and rmean in SAC and the sac file is write to ??.MOD
     */
    void preprocess() throws IOException {
        try (SAC sacProcess = SAC.createProcess()) {
            String cwd = SAC_PATH.getParent().toString();
            sacProcess.inputCMD("cd " + cwd);
            sacProcess.inputCMD("r " + SAC_PATH.getFileName());
            sacProcess.inputCMD("ch lovrok true");
            sacProcess.inputCMD("rtrend");
            sacProcess.inputCMD("rmean");
            sacProcess.inputCMD("w " + MODIFIED_PATH.getFileName());
        }
    }

    /**
     * if the startTime of sac is after the event time, and the gap is bigger
     * than taperTime, interpolation cannot be done This method can be valid
     * before {@link #interpolate()} because the method changes headers.
     *
     * @return if gap between sac starting time and event time is small enough
     * (smaller than {@link #taperTime})
     */
    boolean canInterpolate() {
        LocalDateTime eventTime = BYPDE ? EVENT.getPDETime() : EVENT.getCMTTime();
        return eventTime.until(initialSacStartTime, ChronoUnit.MILLIS) < taperTime;
    }

    /**
     * set {@link #initialSacStartTime}
     */
    private void setInitialSacStartTime() {
        int year = Integer.parseInt(headerMap.get(SACHeaderEnum.NZYEAR));
        int jday = Integer.parseInt(headerMap.get(SACHeaderEnum.NZJDAY));
        int hour = Integer.parseInt(headerMap.get(SACHeaderEnum.NZHOUR));
        int min = Integer.parseInt(headerMap.get(SACHeaderEnum.NZMIN));
        int sec = Integer.parseInt(headerMap.get(SACHeaderEnum.NZSEC));
        int msec = Integer.parseInt(headerMap.get(SACHeaderEnum.NZMSEC));
        double b = Double.parseDouble(headerMap.get(SACHeaderEnum.B));
        long bInNanos = (long) (b * 1000 * 1000 * 1000);
        initialSacStartTime =
                LocalDateTime.of(year, 1, 1, hour, min, sec, msec * 1000 * 1000).plusDays(jday - 1).plusNanos(bInNanos);
    }

    /**
     * イベント時刻よりSac開始が遅い場合 Sacを補完する 補完の際 テーピングをかけ０で補完する。 <br>
     * 開始時刻の部分を丸みをかけて０にする その幅は {@link #taperTime}
     *
     * @return if success or not
     */
    boolean interpolate() throws IOException {
        double b = Double.parseDouble(headerMap.get(SACHeaderEnum.B));
        long bInMillis = Math.round(b * 1000);
        double e = Double.parseDouble(headerMap.get(SACHeaderEnum.E));
        long eInMillis = Math.round(e * 1000);
        LocalDateTime eventTime = BYPDE ? EVENT.getPDETime() : EVENT.getCMTTime();

        // read sacdata
        double[] sacdata = SACUtil.readSACData(MODIFIED_PATH);

        // イベント時刻と合わせる
        // イベント時刻とSAC時刻の差
        // Sac start time in millisec - event time in millisec
        long timeGapInMillis = eventTime.until(initialSacStartTime, ChronoUnit.MILLIS);

        // if the sac startTime is after the event time, then interpolate the gap.
        // if the gap is bigger than tapertime then the SAC file is skipped.
        // if (sacStartTime.after(eventTime)) {
        if (taperTime < timeGapInMillis) {
            System.err.println(MODIFIED_PATH + " starts too late");
            return false;
        } else if (0 <= timeGapInMillis) {
            System.err.println("seismograms start at after the event time... interpolating...");
            // delta [msec]
            long deltaInMillis = (long) (Double.parseDouble(headerMap.get(SACHeaderEnum.DELTA)) * 1000);

            // 時刻差のステップ数
            int gapPoint = (int) (timeGapInMillis / deltaInMillis);

            // Number of points for the taper
            int taperPoint = (int) (taperTime / deltaInMillis);

            // taper
            for (int i = 0; i < taperPoint; i++)
                sacdata[i] *= Math.sin(i * Math.PI / taperPoint / 2.0);

            // 0で補完
            double[] neosacdata = new double[sacdata.length + gapPoint];
            // gapの部分は0でtaperをかけたsacdataをくっつける
            System.arraycopy(sacdata, 0, neosacdata, gapPoint, neosacdata.length - gapPoint);

            int npts = neosacdata.length;
            headerMap.put(SACHeaderEnum.NPTS, Integer.toString(npts));
            sacdata = neosacdata;
            timeGapInMillis = 0;
            // headerMap.put(SacHeaderEnum.B, Double.toString(0));
        }

        Location sourceLocation = BYPDE ? EVENT.getPDELocation() : EVENT.getCmtLocation();

        headerMap.put(SACHeaderEnum.B, Double.toString((bInMillis + timeGapInMillis) / 1000.0));
        headerMap.put(SACHeaderEnum.E, Double.toString((eInMillis + timeGapInMillis) / 1000.0));

        headerMap.put(SACHeaderEnum.NZYEAR, Integer.toString(eventTime.getYear()));
        headerMap.put(SACHeaderEnum.NZJDAY, Integer.toString(eventTime.getDayOfYear()));
        headerMap.put(SACHeaderEnum.NZHOUR, Integer.toString(eventTime.getHour()));
        headerMap.put(SACHeaderEnum.NZMIN, Integer.toString(eventTime.getMinute()));
        headerMap.put(SACHeaderEnum.NZSEC, Integer.toString(eventTime.getSecond()));
        headerMap.put(SACHeaderEnum.NZMSEC, Integer.toString(eventTime.getNano() / 1000 / 1000));
        headerMap.put(SACHeaderEnum.KEVNM, EVENT.toString());
        headerMap.put(SACHeaderEnum.EVLA, Double.toString(sourceLocation.getLatitude()));
        headerMap.put(SACHeaderEnum.EVLO, Double.toString(sourceLocation.getLongitude()));
        headerMap.put(SACHeaderEnum.EVDP, Double.toString(6371 - sourceLocation.getR()));
        headerMap.put(SACHeaderEnum.LOVROK, Boolean.toString(true));
        SACUtil.writeSAC(MODIFIED_PATH, headerMap, sacdata);
        return true;
    }

    /**
     * @param min [deg]
     * @param max [deg]
     * @return if min <= epicentral distance <= max
     */
    boolean checkEpicentralDistance(double min, double max) {
        double epicentralDistance = Double.parseDouble(headerMap.get(SACHeaderEnum.GCARC));
        return min <= epicentralDistance && epicentralDistance <= max;
    }

    /**
     * Rebuild by SAC.
     *
     * @throws IOException if any
     */
    void rebuild() throws IOException {

        // nptsを元のSacfileのEでのポイントを超えない２の累乗ポイントにする
        Map<SACHeaderEnum, String> headerMap = SACUtil.readHeader(MODIFIED_PATH);
        int npts = (int) (Double.parseDouble(headerMap.get(SACHeaderEnum.E)) /
                Double.parseDouble(headerMap.get(SACHeaderEnum.DELTA)));
        int newNpts = Integer.highestOneBit(npts);
        // System.out.println("rebuilding "+ sacFile);
        String cwd = SAC_PATH.getParent().toString();
        try (SAC sacP1 = SAC.createProcess()) {
            sacP1.inputCMD("cd " + cwd);
            sacP1.inputCMD("r " + MODIFIED_PATH.getFileName());
            sacP1.inputCMD("interpolate b 0");
            sacP1.inputCMD("w over");
        }
        try (SAC sacP2 = SAC.createProcess()) {
            // current directoryをうつす
            sacP2.inputCMD("cd " + cwd);
            sacP2.inputCMD("cut b n " + newNpts);
            sacP2.inputCMD("r " + MODIFIED_PATH.getFileName());
            sacP2.inputCMD("w over");
        }
        // ヘッダーの更新
        this.headerMap = SACUtil.readHeader(MODIFIED_PATH);
    }

}
