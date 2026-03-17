package test;

import config.CommonConfig;
import config.PeerInfo;
import config.PeerInfoConfig;
import filehandling.FileManager;
import message.HandshakeMessage;
import message.Message;
import message.MessageType;
import peer.BitField;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TestRunner {

    private static int passed = 0;
    private static int failed = 0;
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        runBitFieldTests();
        runMessageTests();
        runHandshakeTests();
        // runCommonConfigTests(); // Subject to change
        // runPeerInfoConfigTests(); // Subject to change
        runFileManagerTests();
        runIntegrationHandshakeTest();
        runLoggerFormatTest();

        System.out.println("\n==============================================");
        System.out.println("RESULTS: " + passed + " passed, " + failed + " failed");
        System.out.println("==============================================\n");
        System.out.print(report);
    }

    static void pass(String name) {
        passed++;
        System.out.println("  [PASS] " + name);
    }

    static void fail(String name, String reason) {
        failed++;
        String line = "  [FAIL] " + name + " -> " + reason;
        System.out.println(line);
        report.append(line).append("\n");
    }

    static void section(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    static void assertEquals(String test, Object expected, Object actual) {
        String e = String.valueOf(expected);
        String a = String.valueOf(actual);
        if (e.equals(a)) pass(test);
        else fail(test, "expected=" + e + " actual=" + a);
    }

    static void assertTrue(String test, boolean condition) {
        if (condition) pass(test);
        else fail(test, "condition was false");
    }

    static void assertFalse(String test, boolean condition) {
        if (!condition) pass(test);
        else fail(test, "condition was true");
    }

    static void assertArrayEquals(String test, byte[] expected, byte[] actual) {
        if (Arrays.equals(expected, actual)) pass(test);
        else fail(test, "expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }

    // =========================================================================
    // BitField Tests
    // =========================================================================
    static void runBitFieldTests() {
        section("BitField");

        BitField bf = new BitField(16);
        assertFalse("fresh bitfield: piece 0 not set", bf.hasPiece(0));
        assertFalse("fresh bitfield: piece 15 not set", bf.hasPiece(15));
        assertEquals("fresh bitfield: pieceCount=0", 0, bf.getPieceCount());
        assertFalse("fresh bitfield: isComplete=false", bf.isComplete());

        bf.setPiece(0);
        assertTrue("setPiece(0): hasPiece(0)", bf.hasPiece(0));
        assertFalse("setPiece(0): hasPiece(1) still false", bf.hasPiece(1));
        assertEquals("setPiece(0): first byte high bit = 0x80", (byte)0x80, bf.getBytes()[0]);

        bf.setPiece(7);
        assertTrue("setPiece(7): hasPiece(7)", bf.hasPiece(7));
        assertEquals("setPiece(7): first byte low bit = 0x81", (byte)0x81, bf.getBytes()[0]);
        assertFalse("setPiece(7): second byte untouched", bf.hasPiece(8));

        bf.setPiece(8);
        assertTrue("setPiece(8): hasPiece(8)", bf.hasPiece(8));
        assertEquals("setPiece(8): second byte high bit = 0x80", (byte)0x80, bf.getBytes()[1]);

        BitField full = new BitField(8, true);
        assertTrue("full constructor: isComplete", full.isComplete());
        assertEquals("full constructor: pieceCount=8", 8, full.getPieceCount());
        assertEquals("full constructor: byte = 0xFF", (byte)0xFF, full.getBytes()[0]);

        BitField partial = new BitField(4, true);
        assertTrue("4-piece full: isComplete", partial.isComplete());
        assertEquals("4-piece full: pieceCount=4", 4, partial.getPieceCount());
        assertEquals("4-piece full: byte = 0xF0 (high 4 bits)", (byte)0xF0, partial.getBytes()[0]);

        BitField a = new BitField(8);
        a.setPiece(3);
        BitField b = new BitField(8);
        b.setPiece(3);
        b.setPiece(5);
        assertTrue("hasInterestingPiece: a wants piece 5 from b", a.hasInterestingPiece(b));
        assertFalse("hasInterestingPiece: b has nothing a doesn't", b.hasInterestingPiece(a));

        BitField fromBytes = new BitField(8, new byte[]{(byte)0xA0});
        assertTrue("rawBits ctor: piece 0 set (0xA0=10100000)", fromBytes.hasPiece(0));
        assertFalse("rawBits ctor: piece 1 not set", fromBytes.hasPiece(1));
        assertTrue("rawBits ctor: piece 2 set", fromBytes.hasPiece(2));

        BitField bf306 = new BitField(306);
        assertEquals("306-piece: byte array length = 39", 39, bf306.getBytes().length);
        bf306.setPiece(305);
        assertTrue("306-piece: last piece set", bf306.hasPiece(305));
        assertFalse("306-piece: spare bit not set", bf306.hasPiece(307 % 306 == 0 ? 305 : 306));
    }

    // =========================================================================
    // Message Tests
    // =========================================================================
    static void runMessageTests() throws Exception {
        section("Message Serialization");

        for (MessageType type : new MessageType[]{
                MessageType.CHOKE, MessageType.UNCHOKE,
                MessageType.INTERESTED, MessageType.NOT_INTERESTED}) {
            Message m = new Message(type);
            byte[] bytes = serializeMessage(m);
            assertEquals(type.name() + ": wire length = 5 (4+1+0)", 5, bytes.length);
            assertEquals(type.name() + ": length field = 1", 1, ByteBuffer.wrap(bytes, 0, 4).getInt());
            assertEquals(type.name() + ": type byte correct", type.getValue(), bytes[4]);
            Message back = deserializeMessage(bytes);
            assertEquals(type.name() + ": round-trip type", type, back.getType());
            assertEquals(type.name() + ": round-trip payload length", 0, back.getPayload().length);
        }

        Message have = Message.buildHave(42);
        byte[] haveBytes = serializeMessage(have);
        assertEquals("HAVE: wire length = 9 (4+1+4)", 9, haveBytes.length);
        assertEquals("HAVE: length field = 5", 5, ByteBuffer.wrap(haveBytes, 0, 4).getInt());
        assertEquals("HAVE: type byte = 4", (byte)4, haveBytes[4]);
        Message haveBack = deserializeMessage(haveBytes);
        assertEquals("HAVE: round-trip type", MessageType.HAVE, haveBack.getType());
        assertEquals("HAVE: parsePieceIndex = 42", 42, haveBack.parsePieceIndex());

        Message request = Message.buildRequest(99);
        byte[] reqBytes = serializeMessage(request);
        assertEquals("REQUEST: wire length = 9", 9, reqBytes.length);
        Message reqBack = deserializeMessage(reqBytes);
        assertEquals("REQUEST: round-trip type", MessageType.REQUEST, reqBack.getType());
        assertEquals("REQUEST: parsePieceIndex = 99", 99, reqBack.parsePieceIndex());

        byte[] pieceData = new byte[]{10, 20, 30, 40, 50};
        Message piece = Message.buildPiece(7, pieceData);
        byte[] pieceBytes = serializeMessage(piece);
        assertEquals("PIECE: wire length = 4+1+4+5 = 14", 14, pieceBytes.length);
        assertEquals("PIECE: length field = 10", 10, ByteBuffer.wrap(pieceBytes, 0, 4).getInt());
        Message pieceBack = deserializeMessage(pieceBytes);
        assertEquals("PIECE: round-trip type", MessageType.PIECE, pieceBack.getType());
        assertEquals("PIECE: parsePieceIndex = 7", 7, pieceBack.parsePieceIndex());
        assertArrayEquals("PIECE: parsePieceData correct", pieceData, pieceBack.parsePieceData());

        byte[] bfBytes = new byte[]{(byte)0xFF, (byte)0xA0};
        Message bitfield = Message.buildBitfield(bfBytes);
        byte[] bfWire = serializeMessage(bitfield);
        assertEquals("BITFIELD: wire length = 4+1+2 = 7", 7, bfWire.length);
        Message bfBack = deserializeMessage(bfWire);
        assertEquals("BITFIELD: round-trip type", MessageType.BITFIELD, bfBack.getType());
        assertArrayEquals("BITFIELD: payload matches", bfBytes, bfBack.getPayload());

        try {
            byte[] bad = {0,0,0,1,(byte)99};
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(bad));
            Message.receive(din);
            fail("UNKNOWN type byte: should throw", "no exception thrown");
        } catch (IllegalArgumentException e) {
            pass("UNKNOWN type byte: throws IllegalArgumentException");
        }
    }

    static byte[] serializeMessage(Message m) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        m.send(new DataOutputStream(baos));
        return baos.toByteArray();
    }

    static Message deserializeMessage(byte[] bytes) throws IOException {
        return Message.receive(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    // =========================================================================
    // HandshakeMessage Tests
    // =========================================================================
    static void runHandshakeTests() throws Exception {
        section("HandshakeMessage");

        HandshakeMessage hs = new HandshakeMessage(1001);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        hs.send(new DataOutputStream(baos));
        byte[] bytes = baos.toByteArray();

        assertEquals("Handshake total length = 32", 32, bytes.length);

        String header = new String(bytes, 0, 18, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("Handshake header = P2PFILESHARINGPROJ", "P2PFILESHARINGPROJ", header);

        for (int i = 18; i < 28; i++) {
            if (bytes[i] != 0) {
                fail("Zero bits all zero", "byte[" + i + "] = " + bytes[i]);
                break;
            }
        }
        pass("Zero bits all zero");

        int peerId = ByteBuffer.wrap(bytes, 28, 4).getInt();
        assertEquals("Peer ID encoded correctly = 1001", 1001, peerId);

        HandshakeMessage back = HandshakeMessage.receive(new DataInputStream(new ByteArrayInputStream(bytes)));
        assertEquals("Round-trip peer ID = 1001", 1001, back.getPeerId());

        HandshakeMessage hs2 = new HandshakeMessage(Integer.MAX_VALUE);
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        hs2.send(new DataOutputStream(baos2));
        HandshakeMessage back2 = HandshakeMessage.receive(new DataInputStream(new ByteArrayInputStream(baos2.toByteArray())));
        assertEquals("Large peer ID round-trip", Integer.MAX_VALUE, back2.getPeerId());

        byte[] bad = new byte[32];
        byte[] badHeader = "WRONGHEADERXXXXXXX".getBytes(); // 18 chars
        System.arraycopy(badHeader, 0, bad, 0, 18);
        try {
            HandshakeMessage.receive(new DataInputStream(new ByteArrayInputStream(bad)));
            fail("Bad header throws IOException", "no exception thrown");
        } catch (IOException e) {
            pass("Bad header throws IOException");
        }

        HandshakeMessage hs3 = new HandshakeMessage(0);
        ByteArrayOutputStream baos3 = new ByteArrayOutputStream();
        hs3.send(new DataOutputStream(baos3));
        HandshakeMessage back3 = HandshakeMessage.receive(new DataInputStream(new ByteArrayInputStream(baos3.toByteArray())));
        assertEquals("Peer ID 0 round-trip", 0, back3.getPeerId());
    }

    // =========================================================================
    // CommonConfig Tests
    // =========================================================================
    static void runCommonConfigTests() throws Exception {
        section("CommonConfig");

        CommonConfig cfg = CommonConfig.getInstance();
        cfg.load("Common.cfg");

        assertEquals("NumberOfPreferredNeighbors = 2", 2, cfg.getNumberOfPreferredNeighbors());
        assertEquals("UnchokingInterval = 5", 5, cfg.getUnchokingInterval());
        assertEquals("OptimisticUnchokingInterval = 15", 15, cfg.getOptimisticUnchokingInterval());
        assertEquals("FileName = TheFile.dat", "TheFile.dat", cfg.getFileName());
        assertEquals("FileSize = 10000232", 10000232L, cfg.getFileSize());
        assertEquals("PieceSize = 32768", 32768, cfg.getPieceSize());
        assertEquals("NumberOfPieces = ceil(10000232/32768) = 306", 306, cfg.getNumberOfPieces());

        int manual = (int) Math.ceil((double) 10000232 / 32768);
        assertEquals("NumberOfPieces matches manual ceil calc", manual, cfg.getNumberOfPieces());
    }

    // =========================================================================
    // PeerInfoConfig Tests
    // =========================================================================
    static void runPeerInfoConfigTests() throws Exception {
        section("PeerInfoConfig");

        PeerInfoConfig pic = PeerInfoConfig.getInstance();
        pic.load("PeerInfo.cfg");

        List<PeerInfo> peers = pic.getPeers();
        assertTrue("PeerInfo loaded at least 1 peer", peers.size() >= 1);

        PeerInfo first = peers.get(0);
        assertEquals("First peer ID = 1001", 1001, first.getPeerId());
        assertEquals("First peer port = 6008", 6008, first.getPort());
        assertTrue("First peer hasFile = true", first.hasFile());
        assertEquals("First peer host = localhost", "localhost", first.getHostName());

        PeerInfo second = peers.get(1);
        assertEquals("Second peer ID = 1002", 1002, second.getPeerId());
        assertFalse("Second peer hasFile = false", second.hasFile());

        assertFalse("getPeerById(9999) = null", pic.getPeerById(9999) != null);
        assertTrue("getPeerById(1001) not null", pic.getPeerById(1001) != null);

        List<PeerInfo> before1001 = pic.getPeersBefore(1001);
        assertEquals("getPeersBefore(1001) is empty", 0, before1001.size());

        List<PeerInfo> before1003 = pic.getPeersBefore(1003);
        assertEquals("getPeersBefore(1003) has 2 entries", 2, before1003.size());
        assertEquals("getPeersBefore(1003)[0] = 1001", 1001, before1003.get(0).getPeerId());
        assertEquals("getPeersBefore(1003)[1] = 1002", 1002, before1003.get(1).getPeerId());
    }

    // =========================================================================
    // FileManager Tests
    // =========================================================================
    static void runFileManagerTests() throws Exception {
        section("FileManager");

        int numPieces = 306;
        int pieceSize = 32768;
        long fileSize = 10000232L;
        FileManager fm = new FileManager(99999, "test_piece.dat", fileSize, pieceSize, numPieces);

        assertEquals("Normal piece size = 32768", 32768, fm.getPieceSizeForIndex(0));
        assertEquals("Normal piece size mid = 32768", 32768, fm.getPieceSizeForIndex(150));
        assertEquals("Normal piece size second-to-last = 32768", 32768, fm.getPieceSizeForIndex(304));

        int expectedLast = (int)(fileSize % pieceSize);
        assertEquals("Last piece size = 5992", 5992, fm.getPieceSizeForIndex(305));
        assertEquals("Last piece size matches formula", expectedLast, fm.getPieceSizeForIndex(305));

        FileManager fmEven = new FileManager(99998, "even.dat", 32768L * 10, 32768, 10);
        assertEquals("Even last piece = full pieceSize", 32768, fmEven.getPieceSizeForIndex(9));

        byte[] data = new byte[32];
        for (int i = 0; i < data.length; i++) data[i] = (byte)(i + 1);
        FileManager fmWrite = new FileManager(99997, "write_test.dat", 64L, 32, 2);
        fmWrite.writePiece(0, data);
        byte[] readBack = fmWrite.readPiece(0);
        assertArrayEquals("writePiece then readPiece round-trip", data, readBack);

        new File("peer_99999").delete();
        new File("peer_99998").delete();
        try {
            for (File f : new File("peer_99997").listFiles()) f.delete();
            new File("peer_99997").delete();
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Integration: Two-peer handshake over loopback
    // =========================================================================
    static void runIntegrationHandshakeTest() throws Exception {
        section("Integration: Loopback Handshake");

        int port = 19876;
        AtomicInteger serverSawPeerId = new AtomicInteger(-1);
        AtomicInteger clientSawPeerId = new AtomicInteger(-1);
        final Exception[] serverError = {null};

        Thread server = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                Socket s = ss.accept();
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                HandshakeMessage hs = HandshakeMessage.receive(in);
                serverSawPeerId.set(hs.getPeerId());
                new HandshakeMessage(2002).send(out);
                s.close();
            } catch (Exception e) {
                serverError[0] = e;
            }
        });
        server.setDaemon(true);
        server.start();
        Thread.sleep(200);

        try (Socket clientSocket = new Socket("localhost", port)) {
            DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
            DataInputStream in = new DataInputStream(clientSocket.getInputStream());
            new HandshakeMessage(1001).send(out);
            HandshakeMessage response = HandshakeMessage.receive(in);
            clientSawPeerId.set(response.getPeerId());
        }
        server.join(2000);

        if (serverError[0] != null) fail("Server handshake no exception", serverError[0].getMessage());
        else pass("Server handshake no exception");
        assertEquals("Server received peer ID 1001", 1001, serverSawPeerId.get());
        assertEquals("Client received peer ID 2002", 2002, clientSawPeerId.get());

        section("Integration: Message round-trip over loopback");
        final Message[] receivedMsg = {null};
        final Exception[] msgError = {null};

        Thread msgServer = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port + 1)) {
                Socket s = ss.accept();
                DataInputStream in = new DataInputStream(s.getInputStream());
                receivedMsg[0] = Message.receive(in);
                s.close();
            } catch (Exception e) {
                msgError[0] = e;
            }
        });
        msgServer.setDaemon(true);
        msgServer.start();
        Thread.sleep(200);

        try (Socket cs = new Socket("localhost", port + 1)) {
            DataOutputStream out = new DataOutputStream(cs.getOutputStream());
            Message.buildPiece(55, new byte[]{1, 2, 3}).send(out);
        }
        msgServer.join(2000);

        if (msgError[0] != null) fail("Message round-trip over loopback", msgError[0].getMessage());
        else if (receivedMsg[0] == null) fail("Message round-trip over loopback", "received null");
        else {
            assertEquals("Loopback: PIECE type", MessageType.PIECE, receivedMsg[0].getType());
            assertEquals("Loopback: PIECE index = 55", 55, receivedMsg[0].parsePieceIndex());
            assertArrayEquals("Loopback: PIECE data correct", new byte[]{1,2,3}, receivedMsg[0].parsePieceData());
        }
    }

    // =========================================================================
    // Logger format test
    // =========================================================================
    static void runLoggerFormatTest() throws Exception {
        section("PeerLogger format");

        int testPeerId = 88888;
        String logPath = "log_peer_" + testPeerId + ".log";
        new File(logPath).delete();

        logging.PeerLogger logger = new logging.PeerLogger(testPeerId);
        try {
            logger.logTCPConnectionTo(1002);
            logger.logTCPConnectionFrom(1003);
            logger.logHaveMessage(1002, 7);
            logger.logInterestedMessage(1004);
            logger.logNotInterestedMessage(1005);
            logger.logDownloadedPiece(12, 1002, 50);
            logger.logCompletedDownload();
            logger.logChoked(1002);
            logger.logUnchoked(1003);
            logger.logPreferredNeighbors(Arrays.asList(1002, 1003));
            logger.logOptimisticallyUnchokedNeighbor(1004);
            logger.close();

            List<String> lines = Files.readAllLines(new File(logPath).toPath());
            assertEquals("Logger: 11 lines written", 11, lines.size());
            assertTrue("makes a connection to",   lines.get(0).contains("makes a connection to Peer 1002"));
            assertTrue("is connected from",        lines.get(1).contains("is connected from Peer 1003"));
            assertTrue("have message",             lines.get(2).contains("'have' message") && lines.get(2).contains("piece 7"));
            assertTrue("interested message",       lines.get(3).contains("'interested' message") && lines.get(3).contains("1004"));
            assertTrue("not interested message",   lines.get(4).contains("'not interested' message") && lines.get(4).contains("1005"));
            assertTrue("downloaded piece 12",      lines.get(5).contains("piece 12") && lines.get(5).contains("50"));
            assertTrue("complete file",            lines.get(6).contains("complete file"));
            assertTrue("choked by 1002",           lines.get(7).contains("choked by 1002"));
            assertTrue("unchoked by 1003",         lines.get(8).contains("unchoked by 1003"));
            assertTrue("preferred neighbors list", lines.get(9).contains("1002") && lines.get(9).contains("1003"));
            assertTrue("optimistically unchoked",  lines.get(10).contains("1004"));
        } finally {
            new File(logPath).delete();
        }
    }
}
