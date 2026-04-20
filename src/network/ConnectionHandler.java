package network;

import config.CommonConfig;
import filehandling.FileManager;
import logging.PeerLogger;
import message.HandshakeMessage;
import message.Message;
import message.MessageType;
import peer.BitField;
import peer.PeerConnection;
import peer.PeerManager;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ConnectionHandler implements Runnable {

    private final Socket socket;
    private final int localPeerId;
    private final PeerManager peerManager;
    private final boolean isInitiator;
    private int remotePeerId;

    public ConnectionHandler(Socket socket, int localPeerId, PeerManager peerManager, boolean isInitiator) {
        this.socket = socket;
        this.localPeerId = localPeerId;
        this.peerManager = peerManager;
        this.isInitiator = isInitiator;
    }

    @Override
    public void run() {
        PeerConnection connection = null;
        try {
            connection = new PeerConnection(-1, socket);
            remotePeerId = performHandshake(connection);
            connection.setRemotePeerId(remotePeerId);
            peerManager.addConnection(remotePeerId, connection);

            PeerLogger logger = peerManager.getLogger();
            if (isInitiator) {
                logger.logTCPConnectionTo(remotePeerId);
            } else {
                logger.logTCPConnectionFrom(remotePeerId);
            }

            exchangeBitfields(connection);
            handleMessages(connection);
        } catch (IOException e) {
            if (!peerManager.isAllPeersFinished()) {
                System.err.println("Connection error with peer " + remotePeerId + ": " + e.getMessage());
            }
        } finally {
            if (connection != null) {
                if (remotePeerId > 0) {
                    peerManager.releaseRequestsForPeer(remotePeerId);
                }
            }
        }
    }

    private int performHandshake(PeerConnection connection) throws IOException {
        HandshakeMessage outgoing = new HandshakeMessage(localPeerId);
        outgoing.send(connection.getOutputStream());
        HandshakeMessage incoming = HandshakeMessage.receive(connection.getInputStream());
        return incoming.getPeerId();
    }

    private void exchangeBitfields(PeerConnection connection) throws IOException {
        BitField localBitField = peerManager.getLocalBitField();
        connection.sendMessage(Message.buildBitfield(localBitField.getBytes()));

        Message first = connection.receiveMessage();
        if (first.getType() == MessageType.BITFIELD) {
            BitField remoteBitField = new BitField(CommonConfig.getInstance().getNumberOfPieces(), first.getPayload());
            peerManager.updateNeighborBitField(remotePeerId, remoteBitField);
            if (localBitField.hasInterestingPiece(remoteBitField)) {
                connection.sendMessage(new Message(MessageType.INTERESTED));
                connection.setInterested(true);
            } else {
                connection.sendMessage(new Message(MessageType.NOT_INTERESTED));
                connection.setInterested(false);
            }
        } else {
            handleSingleMessage(connection, first);
        }
    }

    private void handleMessages(PeerConnection connection) throws IOException {
        while (!peerManager.isAllPeersFinished()) {
            Message msg = connection.receiveMessage();
            handleSingleMessage(connection, msg);
        }
    }

    private void handleSingleMessage(PeerConnection connection, Message msg) throws IOException {
        PeerLogger logger = peerManager.getLogger();
        BitField localBitField = peerManager.getLocalBitField();
        FileManager fileManager = peerManager.getFileManager();

        switch (msg.getType()) {
            case CHOKE:
                connection.setRemoteChoked(true);
                peerManager.releaseRequestsForPeer(remotePeerId);
                logger.logChoked(remotePeerId);
                break;

            case UNCHOKE:
                connection.setRemoteChoked(false);
                logger.logUnchoked(remotePeerId);
                sendRequestIfPossible(connection);
                break;

            case INTERESTED:
                connection.setRemoteInterested(true);
                logger.logInterestedMessage(remotePeerId);
                break;

            case NOT_INTERESTED:
                connection.setRemoteInterested(false);
                logger.logNotInterestedMessage(remotePeerId);
                break;

            case HAVE: {
                int pieceIndex = msg.parsePieceIndex();
                peerManager.setNeighborHasPiece(remotePeerId, pieceIndex);
                logger.logHaveMessage(remotePeerId, pieceIndex);
                if (!localBitField.hasPiece(pieceIndex)) {
                    connection.sendMessage(new Message(MessageType.INTERESTED));
                    connection.setInterested(true);
                }
                break;
            }

            case BITFIELD: {
                BitField remoteBitField = new BitField(CommonConfig.getInstance().getNumberOfPieces(), msg.getPayload());
                peerManager.updateNeighborBitField(remotePeerId, remoteBitField);
                if (localBitField.hasInterestingPiece(remoteBitField)) {
                    connection.sendMessage(new Message(MessageType.INTERESTED));
                    connection.setInterested(true);
                } else {
                    connection.sendMessage(new Message(MessageType.NOT_INTERESTED));
                    connection.setInterested(false);
                }
                break;
            }

            case REQUEST: {
                int pieceIndex = msg.parsePieceIndex();
                if (!connection.isChoked()) {
                    try {
                        byte[] data = fileManager.readPiece(pieceIndex);
                        connection.sendMessage(Message.buildPiece(pieceIndex, data));
                    } catch (IOException e) {
                        System.err.println("Could not read piece " + pieceIndex + ": " + e.getMessage());
                    }
                }
                break;
            }

            case PIECE: {
                int pieceIndex = msg.parsePieceIndex();
                byte[] data = msg.parsePieceData();
                peerManager.completeRequestedPiece(pieceIndex);
                try {
                    fileManager.writePiece(pieceIndex, data);
                } catch (IOException e) {
                    System.err.println("Could not write piece " + pieceIndex + ": " + e.getMessage());
                    break;
                }
                connection.addBytesDownloaded(data.length);
                localBitField.setPiece(pieceIndex);
                int count = localBitField.getPieceCount();
                logger.logDownloadedPiece(pieceIndex, remotePeerId, count);

                if (localBitField.isComplete()) {
                    logger.logCompletedDownload();
                }

                broadcastHave(pieceIndex);
                checkAndSendNotInterested();

                if (peerManager.checkAllFinished()) {
                    return;
                }

                sendRequestIfPossible(connection);
                break;
            }

            default:
                break;
        }
    }

    private void sendRequestIfPossible(PeerConnection connection) throws IOException {
        if (connection.isRemoteChoked()) return;
        peerManager.releaseExpiredRequests(15000);
        BitField localBitField = peerManager.getLocalBitField();
        BitField neighborBitField = peerManager.getNeighborBitFields().get(remotePeerId);
        if (neighborBitField == null) return;

        List<Integer> candidates = new ArrayList<>();
        int numPieces = CommonConfig.getInstance().getNumberOfPieces();
        for (int i = 0; i < numPieces; i++) {
            if (!localBitField.hasPiece(i) && neighborBitField.hasPiece(i) && !peerManager.isRequested(i)) {
                candidates.add(i);
            }
        }

        if (!candidates.isEmpty()) {
            int chosen = candidates.get(new Random().nextInt(candidates.size()));
            if (peerManager.tryAssignRequestedPiece(chosen, remotePeerId)) {
                connection.sendMessage(Message.buildRequest(chosen));
            }
        } else {
            if (!localBitField.hasInterestingPiece(neighborBitField)) {
                connection.sendMessage(new Message(MessageType.NOT_INTERESTED));
                connection.setInterested(false);
            }
        }
    }

    private void broadcastHave(int pieceIndex) {
        Message haveMsg = Message.buildHave(pieceIndex);
        for (PeerConnection conn : peerManager.getConnections().values()) {
            try {
                conn.sendMessage(haveMsg);
            } catch (IOException e) {
                System.err.println("Could not send HAVE to " + conn.getRemotePeerId() + ": " + e.getMessage());
            }
        }
    }

    private void checkAndSendNotInterested() {
        BitField localBitField = peerManager.getLocalBitField();
        for (PeerConnection conn : peerManager.getConnections().values()) {
            BitField neighborBitField = peerManager.getNeighborBitFields().get(conn.getRemotePeerId());
            if (neighborBitField != null && conn.isInterested() && !localBitField.hasInterestingPiece(neighborBitField)) {
                try {
                    conn.sendMessage(new Message(MessageType.NOT_INTERESTED));
                    conn.setInterested(false);
                } catch (IOException e) {
                    System.err.println("Could not send NOT_INTERESTED to " + conn.getRemotePeerId());
                }
            }
        }
    }
}
