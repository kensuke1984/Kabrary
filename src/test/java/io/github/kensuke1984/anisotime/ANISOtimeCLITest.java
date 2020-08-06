package io.github.kensuke1984.anisotime;

import org.apache.commons.cli.ParseException;

/**
 * @author Kensuke Konishi
 * @version 0.0.1
 */
class ANISOtimeCLITest {
    private ANISOtimeCLITest(){}

    public static void main(String[] args) throws ParseException {
        String line = "-h 50 -mod /mnt/ntfs/kensuke/workspace/anisotime/nd/miasp91_aniso.nd -deg 150";
        ANISOtimeCLI.main(line.split("\\s+"));
    }
}
