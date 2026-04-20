package peer;

import config.CommonConfig;
import config.PeerInfoConfig;
import filehandling.FileManager;
import logging.PeerLogger;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

public class PeerManager {

    private final int localPeerId;
    private final BitField localBitField;
    private final Map<Integer, PeerConnection> connections;
    private final Map<Integer, BitField> neighborBitFields;
    private final Set<Integer> requestedPieces;
    private final Map<Integer, Integer> pieceToPeer;
    private final Map<Integer, Long> pieceRequestTime;
    private volatile boolean allPeersFinished;
    private FileManager fileManager;
    private PeerLogger logger;

    public PeerManager(int localPeerId, boolean hasFile) {
        this.localPeerId = localPeerId;
        int numPieces = CommonConfig.getInstance().getNumberOfPieces();
        this.localBitField = new BitField(numPieces, hasFile);
        this.connections = new ConcurrentHashMap<>();
        this.neighborBitFields = new ConcurrentHashMap<>();
        this.requestedPieces = ConcurrentHashMap.newKeySet();
        this.pieceToPeer = new ConcurrentHashMap<>();
        this.pieceRequestTime = new ConcurrentHashMap<>();
        this.allPeersFinished = false;
    }

    public void setFileManager(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    public void setLogger(PeerLogger logger) {
        this.logger = logger;
    }

    public FileManager getFileManager() {
        return fileManager;
    }

    public PeerLogger getLogger() {
        return logger;
    }

    public void start() {
    }

    public void addConnection(int remotePeerId, PeerConnection connection) {
        connections.put(remotePeerId, connection);
        neighborBitFields.put(remotePeerId, new BitField(CommonConfig.getInstance().getNumberOfPieces()));
    }

    public void updateNeighborBitField(int remotePeerId, BitField bitField) {
        neighborBitFields.put(remotePeerId, bitField);
    }

    public synchronized void setNeighborHasPiece(int remotePeerId, int pieceIndex) {
        BitField bf = neighborBitFields.get(remotePeerId);
        if (bf != null) {
            bf.setPiece(pieceIndex);
        }
    }

    public synchronized boolean checkAllFinished() {
        if (!localBitField.isComplete()) return false;
        for (BitField bf : neighborBitFields.values()) {
            if (!bf.isComplete()) return false;
        }
        int totalPeers = PeerInfoConfig.getInstance().getPeers().size();
        if (neighborBitFields.size() < totalPeers - 1) return false;
        allPeersFinished = true;
        return true;
    }

    public boolean addRequestedPiece(int pieceIndex) {
        return tryAssignRequestedPiece(pieceIndex, -1);
    }

    public void removeRequestedPiece(int pieceIndex) {
        completeRequestedPiece(pieceIndex);
    }

    public boolean isRequested(int pieceIndex) {
        return requestedPieces.contains(pieceIndex);
    }

    public synchronized boolean tryAssignRequestedPiece(int pieceIndex, int remotePeerId) {
        if (localBitField.hasPiece(pieceIndex)) {
            return false;
        }
        if (requestedPieces.contains(pieceIndex)) {
            return false;
        }
        requestedPieces.add(pieceIndex);
        pieceToPeer.put(pieceIndex, remotePeerId);
        pieceRequestTime.put(pieceIndex, System.currentTimeMillis());
        return true;
    }

    public synchronized void completeRequestedPiece(int pieceIndex) {
        requestedPieces.remove(pieceIndex);
        pieceToPeer.remove(pieceIndex);
        pieceRequestTime.remove(pieceIndex);
    }

    public synchronized void releaseRequestsForPeer(int remotePeerId) {
        Set<Integer> toRelease = new HashSet<>();
        for (Map.Entry<Integer, Integer> entry : pieceToPeer.entrySet()) {
            if (entry.getValue() == remotePeerId) {
                toRelease.add(entry.getKey());
            }
        }
        for (int pieceIndex : toRelease) {
            completeRequestedPiece(pieceIndex);
        }
    }

    public synchronized void releaseExpiredRequests(long timeoutMs) {
        long now = System.currentTimeMillis();
        Set<Integer> toRelease = new HashSet<>();
        for (Map.Entry<Integer, Long> entry : pieceRequestTime.entrySet()) {
            if (now - entry.getValue() > timeoutMs) {
                toRelease.add(entry.getKey());
            }
        }
        for (int pieceIndex : toRelease) {
            completeRequestedPiece(pieceIndex);
        }
    }

    public int getLocalPeerId() {
        return localPeerId;
    }

    public BitField getLocalBitField() {
        return localBitField;
    }

    public Map<Integer, PeerConnection> getConnections() {
        return connections;
    }

    public Map<Integer, BitField> getNeighborBitFields() {
        return neighborBitFields;
    }

    public boolean isAllPeersFinished() {
        return allPeersFinished;
    }
}
