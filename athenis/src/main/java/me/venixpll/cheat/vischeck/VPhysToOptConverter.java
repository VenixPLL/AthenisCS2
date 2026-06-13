package me.venixpll.cheat.vischeck;

import java.io.File;

public class VPhysToOptConverter {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: VPhysToOptConverter <directory_path>");
            return;
        }
        convertDirectory(new File(args[0]));
    }

    public static void convertDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            System.err.println("The specified directory does not exist or is not a directory.");
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            System.err.println("No files found in the directory.");
            return;
        }

        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".vphys")) {
                String rawFile = file.getAbsolutePath();
                String optFile = rawFile.substring(0, rawFile.length() - ".vphys".length()) + ".opt";

                System.out.println("Processing " + file.getName() + " -> " + new File(optFile).getName());
                OptimizedGeometry geom = new OptimizedGeometry();
                if (geom.createOptimizedFile(rawFile, optFile)) {
                    System.out.println("Successfully saved: " + optFile);
                    count++;
                } else {
                    System.err.println("Error converting " + rawFile);
                }
            }
        }
        System.out.println("Completed. Converted " + count + " file(s).");
    }
}
