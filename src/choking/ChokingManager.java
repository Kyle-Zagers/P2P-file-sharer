package choking;

import config.CommonConfig;
import logging.PeerLogger;
import message.Message;
import message.MessageType;
import peer.BitField;
import peer.PeerConnection;
import peer.PeerManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChokingManager {

    private final int localPeerId;
    private final PeerManager peerManager;
    private volatile int optimisticallyUnchokedPeerId;
    private final ScheduledExecutorService scheduler;

    public ChokingManager(int localPeerId, PeerManager peerManager) {
        this.localPeerId = localPeerId;
        this.peerManager = peerManager;
        this.optimisticallyUnchokedPeerId = -1;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public void startPreferredNeighborTimer() {
        int interval = CommonConfig.getInstance().getUnchokingInterval();
        scheduler.scheduleAtFixedRate(() -> {
            Set<Integer> preferred = selectPreferredNeighbors();
            applyPreferredNeighbors(preferred);
            PeerLogger logger = peerManager.getLogger();
            List<Integer> preferredList = new ArrayList<>(preferred);
            Collections.sort(preferredList);
            logger.logPreferredNeighbors(preferredList);
        }, interval, interval, TimeUnit.SECONDS);
    }

    public void startOptimisticUnchokingTimer() {
        int interval = CommonConfig.getInstance().getOptimisticUnchokingInterval();
        scheduler.scheduleAtFixedRate(() -> {
            int chosen = selectOptimisticallyUnchokedNeighbor();
            if (chosen != -1) {
                optimisticallyUnchokedPeerId = chosen;
                PeerConnection conn = peerManager.getConnections().get(chosen);
                if (conn != null) {
                    try {
                        conn.sendMessage(new Message(MessageType.UNCHOKE));
                        conn.setChoked(false);
                    } catch (IOException e) {
                        System.err.println("Failed to send UNCHOKE to optimistic neighbor " + chosen);
                    }
                }
                peerManager.getLogger().logOptimisticallyUnchokedNeighbor(chosen);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    public Set<Integer> selectPreferredNeighbors() {
        int k = CommonConfig.getInstance().getNumberOfPreferredNeighbors();
        BitField localBitField = peerManager.getLocalBitField();
        Map<Integer, PeerConnection> connections = peerManager.getConnections();

        List<Map.Entry<Integer, PeerConnection>> interested = new ArrayList<>();
        for (Map.Entry<Integer, PeerConnection> entry : connections.entrySet()) {
            if (entry.getValue().isRemoteInterested()) {
                interested.add(entry);
            }
        }

        if (localBitField.isComplete()) {
            Collections.shuffle(interested);
        } else {
            // Shuffle first so ties are broken randomly (stable sort preserves shuffle order)
            Collections.shuffle(interested);
            interested.sort((a, b) -> {
                long diff = b.getValue().getBytesDownloaded() - a.getValue().getBytesDownloaded();
                return diff > 0 ? 1 : (diff < 0 ? -1 : 0);
            });
            for (Map.Entry<Integer, PeerConnection> entry : interested) {
                entry.getValue().resetBytesDownloaded();
            }
        }

        Set<Integer> preferred = new HashSet<>();
        for (int i = 0; i < Math.min(k, interested.size()); i++) {
            preferred.add(interested.get(i).getKey());
        }
        return preferred;
    }

    public int selectOptimisticallyUnchokedNeighbor() {
        List<Map.Entry<Integer, PeerConnection>> interested = new ArrayList<>();
        for (Map.Entry<Integer, PeerConnection> entry : peerManager.getConnections().entrySet()) {
            if (entry.getValue().isRemoteInterested() && entry.getValue().isChoked()) {
                interested.add(entry);
            }
        }
        if (interested.isEmpty()) return -1;
        Collections.shuffle(interested);
        return interested.get(0).getKey();
    }

    public void applyPreferredNeighbors(Set<Integer> preferredIds) {
        int optId = optimisticallyUnchokedPeerId;
        for (Map.Entry<Integer, PeerConnection> entry : peerManager.getConnections().entrySet()) {
            int peerId = entry.getKey();
            PeerConnection conn = entry.getValue();
            if (preferredIds.contains(peerId)) {
                if (conn.isChoked()) {
                    try {
                        conn.sendMessage(new Message(MessageType.UNCHOKE));
                        conn.setChoked(false);
                    } catch (IOException e) {
                        System.err.println("Failed to unchoke preferred neighbor " + peerId);
                    }
                }
            } else if (peerId != optId) {
                if (!conn.isChoked()) {
                    try {
                        conn.sendMessage(new Message(MessageType.CHOKE));
                        conn.setChoked(true);
                    } catch (IOException e) {
                        System.err.println("Failed to choke neighbor " + peerId);
                    }
                }
            }
        }
    }

    public int getOptimisticallyUnchokedPeerId() {
        return optimisticallyUnchokedPeerId;
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
