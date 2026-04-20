package filehandling;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public class FileManager {

    private final int peerId;
    private final String fileName;
    private final long fileSize;
    private final int pieceSize;
    private final int numberOfPieces;
    private final String peerDirectory;

    public FileManager(int peerId, String fileName, long fileSize, int pieceSize, int numberOfPieces) {
        this.peerId = peerId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.numberOfPieces = numberOfPieces;
        this.peerDirectory = String.valueOf(peerId);
        new File(peerDirectory).mkdirs();
    }

    public synchronized byte[] readPiece(int pieceIndex) throws IOException {
        int size = getPieceSizeForIndex(pieceIndex);
        byte[] data = new byte[size];
        try (RandomAccessFile raf = new RandomAccessFile(peerDirectory + File.separator + fileName, "r")) {
            raf.seek((long) pieceIndex * pieceSize);
            raf.readFully(data);
        }
        return data;
    }

    public synchronized void writePiece(int pieceIndex, byte[] data) throws IOException {
        File file = new File(peerDirectory + File.separator + fileName);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek((long) pieceIndex * pieceSize);
            raf.write(data);
        }
    }

    public synchronized void assembleFile() throws IOException {

        // File is assembled by writing pieces in place, so we just need to verify the final size
        // TODO: Not sure why we need this method.

        File file = new File(peerDirectory + File.separator + fileName);
        if (file.length() != fileSize) {
            throw new IOException("Assembled file size does not match expected size");
        }
    }

    public int getPieceSizeForIndex(int pieceIndex) {
        if (pieceIndex == numberOfPieces - 1) {
            int remainder = (int) (fileSize % pieceSize);
            return remainder == 0 ? pieceSize : remainder;
        }
        return pieceSize;
    }

    public String getPeerDirectory() {
        return peerDirectory;
    }
}
