package test;

import config.CommonConfig;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public class IntegrationTest {
    
    public static void main(String[] args) throws Exception {
        CommonConfig.getInstance().load("Common.cfg");

        System.out.println("End to end Integration Test.\nOne initial seeder and five leechers.");
    
        String command = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classPath = System.getProperty("java.class.path");

        int numProcesses = 6;

        Process[] processes = new Process[numProcesses];

        System.out.println("Starting peer processes...");

        for (int i = 0; i < numProcesses; i++) {
            int peerId = 1001 + i;
            processes[i] = new ProcessBuilder(command, "-cp", classPath, "peerProcess", String.valueOf(peerId)).inheritIO().start();
            Thread.sleep(1000);
        }

        System.out.println("Peer processes started. Waiting for completion...");

        for (Process p : processes) {
            p.waitFor();
        }

        System.out.println("All peers finished. Verifying file integrity...");

        File seedFile = new File("1001" + File.separator + CommonConfig.getInstance().getFileName());

        for (int i = 0; i < numProcesses; i++) {
            File file = new File((1001 + i) + File.separator + CommonConfig.getInstance().getFileName());
            verifyFiles(seedFile, file);
        }

        System.out.println("All files' integrity verified. Test passed.");
    }

    private static void verifyFiles(File seedFile, File leecherFile) throws Exception {
        if (!leecherFile.exists()) {
            throw new Exception("Leecher file does not exist: " + leecherFile.getPath());
        }

        if (!seedFile.exists()) {
            throw new Exception("Seeder file does not exist: " + seedFile.getPath());
        }

        if (seedFile.length() != leecherFile.length()) {
            throw new Exception("File sizes do not match for " + leecherFile.getPath());
        }

        byte[] seedData = Files.readAllBytes(seedFile.toPath());
        byte[] leecherData = Files.readAllBytes(leecherFile.toPath());

        if (!Arrays.equals(seedData, leecherData)) {
            throw new Exception("File contents do not match for " + leecherFile.getPath());
        }

        System.out.println("\tVerified file integrity for " + leecherFile.getPath());
    }
}
