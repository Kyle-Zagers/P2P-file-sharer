import choking.ChokingManager;
import config.CommonConfig;
import config.PeerInfo;
import config.PeerInfoConfig;
import filehandling.FileManager;
import logging.PeerLogger;
import network.ConnectionHandler;
import network.ConnectionListener;
import peer.PeerManager;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

public class peerProcess {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java peerProcess <peerId>");
            System.exit(1);
        }

        int localPeerId = Integer.parseInt(args[0]);

        try {
            CommonConfig.getInstance().load("Common.cfg");
            PeerInfoConfig.getInstance().load("PeerInfo.cfg");

            PeerInfo localPeer = PeerInfoConfig.getInstance().getPeerById(localPeerId);
            if (localPeer == null) {
                System.err.println("Peer ID " + localPeerId + " not found in PeerInfo.cfg");
                System.exit(1);
            }

            CommonConfig cfg = CommonConfig.getInstance();
            PeerLogger logger = new PeerLogger(localPeerId);
            PeerManager peerManager = new PeerManager(localPeerId, localPeer.hasFile());

            FileManager fileManager = new FileManager(
                    localPeerId,
                    cfg.getFileName(),
                    cfg.getFileSize(),
                    cfg.getPieceSize(),
                    cfg.getNumberOfPieces()
            );

            peerManager.setFileManager(fileManager);
            peerManager.setLogger(logger);

            ConnectionListener listener = new ConnectionListener(localPeer.getPort(), localPeerId, peerManager);
            Thread listenerThread = new Thread(listener);
            listenerThread.setDaemon(true);
            listenerThread.start();

            List<PeerInfo> priorPeers = PeerInfoConfig.getInstance().getPeersBefore(localPeerId);
            for (PeerInfo prior : priorPeers) {
                Socket socket = new Socket(prior.getHostName(), prior.getPort());
                Thread handler = new Thread(new ConnectionHandler(socket, localPeerId, peerManager, true));
                handler.setDaemon(true);
                handler.start();
            }

            ChokingManager chokingManager = new ChokingManager(localPeerId, peerManager);
            chokingManager.startPreferredNeighborTimer();
            chokingManager.startOptimisticUnchokingTimer();

            while (!peerManager.isAllPeersFinished()) {
                Thread.sleep(1000);
                peerManager.checkAllFinished();
            }

            if (listener != null) {
                listener.stop();
            }

            chokingManager.stop();
            logger.close();

        } catch (IOException e) {
            System.err.println("Startup error: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
