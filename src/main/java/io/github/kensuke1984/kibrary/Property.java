package io.github.kensuke1984.kibrary;

import io.github.kensuke1984.kibrary.util.Utilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * This class will create a default property for a procedure in Kibrary.
 *
 * @author Kensuke Konishi
 * @version 0.0.4
 */
public class Property {

    public static void main(String[] args) throws Exception {
        if (1 < args.length) throw new RuntimeException("Too many arguments. You can specify only one Manhattan.");
        if (args.length == 1) try {
            Manhattan.valueOf(args[0]).writeDefaultPropertiesFile();
            return;
        } catch (IllegalArgumentException iae) {
            System.err.println(args[0] + " is not in Manhattan.\nPlease choose one in:");
            Manhattan.printList();
            return;
        }
        Manhattan.printList();
        System.out.print("For which one do you want to create a property file? [1-" + Manhattan.values().length + "] ");
        String input = Utilities.readInputLine();
        if (input.isEmpty()) System.exit(1);
        Manhattan.valueOf(Integer.parseInt(input)).writeDefaultPropertiesFile();
    }

    public static Properties parse(String[] args) throws IOException {
        Properties property = new Properties();
        if (args.length == 0) property.load(Files.newBufferedReader(Operation.findPath()));
        else if (args.length == 1) property.load(Files.newBufferedReader(Paths.get(args[0])));
        else throw new IllegalArgumentException("too many arguments. It should be 0 or 1(property file name)");
        return property;
    }

    private Property() {
    }
}
