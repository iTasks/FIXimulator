package org.itasks.fixclient;

import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.FileStoreFactory;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.UnsupportedMessageType;
import quickfix.field.BeginSeqNo;
import quickfix.field.BusinessRejectReason;
import quickfix.field.CumQty;
import quickfix.field.EndSeqNo;
import quickfix.field.GapFillFlag;
import quickfix.field.NewSeqNo;
import quickfix.field.TestReqID;
import quickfix.field.CxlRejReason;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.RefTagID;
import quickfix.field.ResetSeqNumFlag;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix42.NewOrderSingle;
import quickfix.fix42.OrderCancelReplaceRequest;
import quickfix.fix42.OrderCancelRequest;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Console FIX 4.2 client that logs on to FIXimulator and sends trades.
 * Run from the fixclient directory so the relative store and log paths in client.cfg resolve.
 */
public final class FixClient implements Application {

    private static final String DEFAULT_CONFIG = "client.cfg";
    private static final long LOGON_TIMEOUT_MS = 15_000;
    private static final long STEP_PAUSE_MS = 1_000;

    private final OrderBook orders = new OrderBook();
    private final String runTag = Long.toString(System.currentTimeMillis() / 1000 % 100_000);
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile SessionID sessionId;
    private volatile boolean resetOnNextLogon;

    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        FixClient client = new FixClient();

        SessionSettings settings;
        try (InputStream in = new FileInputStream(configPath)) {
            settings = new SessionSettings(in);
        }

        SocketInitiator initiator = new SocketInitiator(client, new FileStoreFactory(settings), settings,
                new ScreenLogFactory(settings), new DefaultMessageFactory());
        initiator.start();
        System.out.println("Connecting to FIXimulator (see " + configPath + "). Type 'help' for commands.");
        try {
            client.runConsole();
        } finally {
            initiator.stop();
        }
        System.out.println("Disconnected.");
    }

    private void runConsole() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        printHelp();
        try {
            System.out.println(awaitLogon() ? "Logged on to FIXimulator." : "Not logged on yet. Start FIXimulator on port 9878 and retry with 'status'.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        while (true) {
            System.out.print("fix> ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts[0].isEmpty()) {
                continue;
            }
            try {
                boolean keepRunning = dispatch(parts);
                if (!keepRunning) {
                    break;
                }
            } catch (IllegalArgumentException e) {
                System.out.println("[ERR ] " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Returns false when the console should exit. */
    private boolean dispatch(String[] parts) throws InterruptedException {
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "buy" -> {
                requireArgs(parts, 4, "buy <symbol> <qty> <price|MKT>");
                sendNewOrder(Side.BUY, parts[1].toUpperCase(Locale.ROOT), parseQty(parts[2]), parsePrice(parts[3]));
            }
            case "sell" -> {
                requireArgs(parts, 4, "sell <symbol> <qty> <price|MKT>");
                sendNewOrder(Side.SELL, parts[1].toUpperCase(Locale.ROOT), parseQty(parts[2]), parsePrice(parts[3]));
            }
            case "cancel" -> {
                requireArgs(parts, 2, "cancel <clOrdId>");
                cancel(findOrder(parts[1]));
            }
            case "replace" -> {
                requireArgs(parts, 4, "replace <clOrdId> <qty> <price>");
                Double price = parsePrice(parts[3]);
                if (price == null) {
                    throw new IllegalArgumentException("replace needs a limit price, not MKT");
                }
                replace(findOrder(parts[1]), parseQty(parts[2]), price);
            }
            case "orders" -> printOrders();
            case "status" -> System.out.println(isLoggedOn() ? "Logged on to FIXimulator" : "Not logged on");
            case "logon" -> requestLogon();
            case "logout" -> logout();
            case "reset" -> resetSequence();
            case "seq" -> printSequence();
            case "test" -> sendTestRequest(parts.length > 1 ? parts[1] : "TR-" + runTag + "-" + sequence.incrementAndGet());
            case "demo" -> runDemo();
            case "help" -> printHelp();
            case "quit", "exit" -> {
                if (isLoggedOn()) {
                    logout();
                }
                return false;
            }
            default -> System.out.println("Unknown command: " + parts[0] + " (type 'help')");
        }
        return true;
    }

    private void runDemo() throws InterruptedException {
        if (!awaitLogon()) {
            System.out.println("[ERR ] Not logged on after " + LOGON_TIMEOUT_MS / 1000 + "s. Is FIXimulator running on port 9878?");
            return;
        }
        System.out.println("== Demo: limit buy, limit sell, market buy, replace, cancel ==");
        Order ibm = sendNewOrder(Side.BUY, "IBM", 100, 92.00);
        pause();
        Order amzn = sendNewOrder(Side.SELL, "AMZN", 50, 35.00);
        pause();
        sendNewOrder(Side.BUY, "YHOO", 200, null);
        pause();
        if (ibm != null) {
            replace(ibm, 150, 91.50);
            pause();
        }
        if (amzn != null) {
            cancel(amzn);
        }
        pause();
        printOrders();
        System.out.println("== Demo finished. Responses appear as [EXEC] lines; without auto-acknowledge, "
                + "accept orders in the FIXimulator UI. ==");
    }

    private Order sendNewOrder(char side, String symbol, double qty, Double price) {
        String clOrdId = nextClOrdId();
        NewOrderSingle nos = new NewOrderSingle();
        nos.set(new ClOrdID(clOrdId));
        nos.set(new HandlInst('1'));
        nos.set(new Symbol(symbol));
        nos.set(new Side(side));
        nos.set(new TransactTime());
        nos.set(new OrderQty(qty));
        if (price == null) {
            nos.set(new OrdType(OrdType.MARKET));
        } else {
            nos.set(new OrdType(OrdType.LIMIT));
            nos.set(new Price(price));
        }
        nos.set(new TimeInForce(TimeInForce.DAY));

        Order order = new Order(clOrdId, symbol, side, qty, price);
        orders.add(order);
        System.out.printf("[SEND] NewOrderSingle 11=%s 54=%s 55=%s 38=%s 40=%s 44=%s%n",
                clOrdId, sideName(side), symbol, fmt(qty), price == null ? "MKT" : "LIMIT", price == null ? "-" : fmt(price));
        send(nos);
        return order;
    }

    private void cancel(Order order) {
        String newId = nextClOrdId();
        OrderCancelRequest ocr = new OrderCancelRequest();
        ocr.set(new OrigClOrdID(order.currentClOrdId()));
        ocr.set(new ClOrdID(newId));
        ocr.set(new Symbol(order.symbol));
        ocr.set(new Side(order.side));
        ocr.set(new TransactTime());
        ocr.set(new OrderQty(order.qty));
        orders.alias(newId, order);
        System.out.printf("[SEND] OrderCancelRequest 41=%s 11=%s%n", order.originalClOrdId, newId);
        send(ocr);
    }

    private void replace(Order order, double qty, double price) {
        String newId = nextClOrdId();
        OrderCancelReplaceRequest ocrr = new OrderCancelReplaceRequest();
        ocrr.set(new OrigClOrdID(order.currentClOrdId()));
        ocrr.set(new ClOrdID(newId));
        ocrr.set(new HandlInst('1'));
        ocrr.set(new Symbol(order.symbol));
        ocrr.set(new Side(order.side));
        ocrr.set(new TransactTime());
        ocrr.set(new OrderQty(qty));
        ocrr.set(new OrdType(OrdType.LIMIT));
        ocrr.set(new Price(price));
        orders.alias(newId, order);
        order.qty = qty;
        order.price = price;
        System.out.printf("[SEND] OrderCancelReplaceRequest 41=%s 11=%s 38=%s 44=%s%n",
                order.originalClOrdId, newId, fmt(qty), fmt(price));
        send(ocrr);
    }

    private void send(Message message) {
        SessionID sid = sessionId;
        if (sid == null || !isLoggedOn()) {
            System.out.println("[ERR ] Not logged on to FIXimulator; message was not sent.");
            return;
        }
        try {
            Session.sendToTarget(message, sid);
        } catch (SessionNotFound e) {
            System.out.println("[ERR ] Session not found: " + e.getMessage());
        }
    }

    private boolean isLoggedOn() {
        SessionID sid = sessionId;
        if (sid == null) {
            return false;
        }
        Session session = Session.lookupSession(sid);
        return session != null && session.isLoggedOn();
    }

    private boolean awaitLogon() throws InterruptedException {
        return awaitState(true, LOGON_TIMEOUT_MS);
    }

    private boolean awaitState(boolean loggedOn, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isLoggedOn() != loggedOn) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }

    private Session currentSession() {
        SessionID sid = sessionId;
        return sid == null ? null : Session.lookupSession(sid);
    }

    private void requestLogon() {
        Session session = currentSession();
        if (session == null) {
            System.out.println("[ERR ] Session not created yet.");
        } else if (session.isLoggedOn()) {
            System.out.println("Already logged on.");
        } else {
            session.logon();
            System.out.println("[SESS] Logon requested.");
        }
    }

    private void logout() throws InterruptedException {
        Session session = currentSession();
        if (session == null || !session.isLoggedOn()) {
            System.out.println("[ERR ] Not logged on; nothing to log out.");
            return;
        }
        session.logout("Logout requested by console");
        System.out.println("[SESS] Logout sent, waiting for FIXimulator...");
        if (!awaitState(false, LOGON_TIMEOUT_MS)) {
            System.out.println("[WARN] No Logout response within " + LOGON_TIMEOUT_MS / 1000 + "s.");
        }
    }

    private void resetSequence() {
        Session session = currentSession();
        if (session == null) {
            System.out.println("[ERR ] Session not created yet.");
        } else if (session.isLoggedOn()) {
            System.out.println("[ERR ] Log out before resetting sequence numbers.");
        } else {
            session.reset();
            resetOnNextLogon = true;
            System.out.println("[SESS] Sequence numbers reset to 1/1. Next logon sends ResetSeqNumFlag=Y (141=Y); use 'logon'.");
        }
    }

    private void printSequence() {
        Session session = currentSession();
        if (session == null) {
            System.out.println("[ERR ] Session not created yet.");
            return;
        }
        System.out.printf("[SEQ ] next outgoing (34) = %d, next expected incoming = %d%n",
                session.getExpectedSenderNum(), session.getExpectedTargetNum());
    }

    private void sendTestRequest(String testReqId) {
        Session session = currentSession();
        if (session == null || !session.isLoggedOn()) {
            System.out.println("[ERR ] Not logged on; TestRequest was not sent.");
            return;
        }
        session.generateTestRequest(testReqId);
        System.out.println("[SEND] TestRequest 112=" + testReqId);
    }

    private void printOrders() {
        List<Order> snapshot = orders.snapshot();
        if (snapshot.isEmpty()) {
            System.out.println("No orders yet.");
            return;
        }
        System.out.printf("%-16s %-5s %-6s %8s %10s %-18s %8s %8s%n",
                "ClOrdID", "Side", "Symbol", "Qty", "Price", "Status", "Filled", "Leaves");
        for (Order o : snapshot) {
            System.out.printf("%-16s %-5s %-6s %8s %10s %-18s %8s %8s%n",
                    o.currentClOrdId(), sideName(o.side), o.symbol, fmt(o.qty),
                    o.price == null ? "MKT" : fmt(o.price), o.status,
                    fmt(o.filled), fmt(o.leaves));
        }
    }

    private Order findOrder(String clOrdId) {
        Order order = orders.find(clOrdId);
        if (order == null) {
            throw new IllegalArgumentException("unknown ClOrdID " + clOrdId + " (see 'orders')");
        }
        return order;
    }

    private String nextClOrdId() {
        return "C" + runTag + "-" + String.format("%03d", sequence.incrementAndGet());
    }

    private static void requireArgs(String[] parts, int expected, String usage) {
        if (parts.length != expected) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static double parseQty(String text) {
        try {
            double qty = Double.parseDouble(text);
            if (qty <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            return qty;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid quantity: " + text);
        }
    }

    private static Double parsePrice(String text) {
        if ("MKT".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            double price = Double.parseDouble(text);
            if (price <= 0) {
                throw new IllegalArgumentException("price must be positive");
            }
            return price;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid price: " + text);
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(STEP_PAUSE_MS);
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  buy <symbol> <qty> <price|MKT>        send a NewOrderSingle (D)");
        System.out.println("  sell <symbol> <qty> <price|MKT>       send a NewOrderSingle (D)");
        System.out.println("  replace <clOrdId> <qty> <price>       send an OrderCancelReplaceRequest (G)");
        System.out.println("  cancel <clOrdId>                      send an OrderCancelRequest (F)");
        System.out.println("  orders                                show orders and their last status");
        System.out.println("  status                                show logon state");
        System.out.println("  logon | logout                        log on or log out the FIX session");
        System.out.println("  reset                                 reset sequence numbers to 1 (logged out only)");
        System.out.println("  seq                                   show next outgoing and expected incoming seq");
        System.out.println("  test [testReqId]                      send a TestRequest (1); peer answers with Heartbeat (0)");
        System.out.println("  demo                                  run a scripted trade sequence");
        System.out.println("  help | quit                           quit logs out first if logged on");
    }

    private static String sideName(char side) {
        return side == Side.BUY ? "BUY" : "SELL";
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.4f", value);
    }

    private static String str(Message message, int tag) {
        try {
            return message.isSetField(tag) ? message.getString(tag) : "";
        } catch (FieldNotFound e) {
            return "";
        }
    }

    private static double num(Message message, int tag) {
        String value = str(message, tag);
        return value.isEmpty() ? Double.NaN : Double.parseDouble(value);
    }

    private static String describeOrdStatus(String code) {
        return switch (code) {
            case "0" -> "NEW";
            case "1" -> "PARTIALLY_FILLED";
            case "2" -> "FILLED";
            case "4" -> "CANCELED";
            case "5" -> "REPLACED";
            case "6" -> "PENDING_CANCEL";
            case "8" -> "REJECTED";
            case "A" -> "PENDING_NEW";
            case "C" -> "EXPIRED";
            case "E" -> "PENDING_REPLACE";
            default -> "STATUS_" + code;
        };
    }

    // ---- quickfix.Application callbacks (QuickFIX/J network thread) ----

    @Override
    public void onCreate(SessionID sessionID) {
        sessionId = sessionID;
    }

    @Override
    public void onLogon(SessionID sessionID) {
        resetOnNextLogon = false;
        System.out.println("\n[SESS] Logged on: " + sessionID);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        System.out.println("\n[SESS] Logged out: " + sessionID);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionID) {
        if (resetOnNextLogon && isMsgType(message, "A")) {
            message.setBoolean(ResetSeqNumFlag.FIELD, true);
        }
    }

    private static boolean isMsgType(Message message, String expected) {
        try {
            return expected.equals(message.getHeader().getString(MsgType.FIELD));
        } catch (FieldNotFound e) {
            return false;
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        switch (msgType) {
            case "0" -> {
                if (message.isSetField(TestReqID.FIELD)) {
                    System.out.println("\n[ADM ] Heartbeat answering TestRequest 112=" + str(message, TestReqID.FIELD));
                }
            }
            case "1" -> System.out.println("\n[ADM ] TestRequest from FIXimulator 112=" + str(message, TestReqID.FIELD));
            case "2" -> System.out.println("\n[ADM ] ResendRequest from FIXimulator 7=" + str(message, BeginSeqNo.FIELD)
                    + " 16=" + str(message, EndSeqNo.FIELD));
            case "4" -> System.out.println("\n[ADM ] SequenceReset 123=" + str(message, GapFillFlag.FIELD)
                    + " 36=" + str(message, NewSeqNo.FIELD));
            case "5" -> System.out.println("\n[SESS] Logout from FIXimulator Text=" + str(message, Text.FIELD));
            case "3" -> System.out.println("\n[ADM ] Session-level Reject RefMsgType=" + str(message, RefMsgType.FIELD)
                    + " RefTagID=" + str(message, RefTagID.FIELD)
                    + " Text=" + str(message, Text.FIELD));
            default -> {
            }
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionID) throws DoNotSend {
    }

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        switch (msgType) {
            case "8" -> onExecutionReport(message);
            case "9" -> onCancelReject(message);
            case "j" -> onBusinessReject(message);
            default -> System.out.println("\n[IN  ] Unhandled MsgType=" + msgType);
        }
    }

    private void onExecutionReport(Message message) {
        String clOrdId = str(message, ClOrdID.FIELD);
        String origClOrdId = str(message, OrigClOrdID.FIELD);
        Order order = orders.find(clOrdId);
        if (order == null) {
            order = orders.find(origClOrdId);
        }
        String ordStatus = str(message, OrdStatus.FIELD);
        String execType = str(message, ExecType.FIELD);
        if (order == null) {
            System.out.println("\n[EXEC] Unknown order ClOrdID=" + clOrdId + " OrdStatus=" + ordStatus);
            return;
        }

        order.status = describeOrdStatus(ordStatus);
        double cum = num(message, CumQty.FIELD);
        double leaves = num(message, LeavesQty.FIELD);
        if (!Double.isNaN(cum)) {
            order.filled = cum;
        }
        if (!Double.isNaN(leaves)) {
            order.leaves = leaves;
        }
        double ackedQty = num(message, OrderQty.FIELD);
        if (!Double.isNaN(ackedQty)) {
            order.qty = ackedQty;
        }
        double ackedPrice = num(message, Price.FIELD);
        if (!Double.isNaN(ackedPrice)) {
            order.price = ackedPrice;
        }

        StringBuilder line = new StringBuilder("\n[EXEC] ClOrdID=").append(clOrdId)
                .append(" ExecType=").append(execType)
                .append(" OrdStatus=").append(order.status);
        String lastQty = str(message, LastQty.FIELD);
        if (!lastQty.isEmpty() && Double.parseDouble(lastQty) > 0) {
            line.append(" Fill=").append(lastQty).append('@').append(str(message, LastPx.FIELD));
        }
        line.append(" Cum=").append(fmt(order.filled)).append(" Leaves=").append(fmt(order.leaves));
        String text = str(message, Text.FIELD);
        if (!text.isEmpty()) {
            line.append(" Text=").append(text);
        }
        System.out.println(line);
    }

    private void onCancelReject(Message message) {
        System.out.println("\n[REJ ] OrderCancelReject ClOrdID=" + str(message, ClOrdID.FIELD)
                + " OrigClOrdID=" + str(message, OrigClOrdID.FIELD)
                + " Reason=" + str(message, CxlRejReason.FIELD)
                + " Text=" + str(message, Text.FIELD));
    }

    private void onBusinessReject(Message message) {
        System.out.println("\n[REJ ] BusinessMessageReject RefMsgType=" + str(message, RefMsgType.FIELD)
                + " Reason=" + str(message, BusinessRejectReason.FIELD)
                + " Text=" + str(message, Text.FIELD));
    }

    // ---- order tracking ----

    private static final class Order {
        final String originalClOrdId;
        final String symbol;
        final char side;
        volatile double qty;
        volatile Double price;
        volatile String clOrdIdOverride;
        volatile String status = "PENDING_NEW";
        volatile double filled;
        volatile double leaves;

        Order(String clOrdId, String symbol, char side, double qty, Double price) {
            this.originalClOrdId = clOrdId;
            this.symbol = symbol;
            this.side = side;
            this.qty = qty;
            this.price = price;
            this.leaves = qty;
        }

        String currentClOrdId() {
            return clOrdIdOverride != null ? clOrdIdOverride : originalClOrdId;
        }
    }

    /** Thread-safe store: the console thread writes orders, the IO thread updates them. */
    private static final class OrderBook {
        private final List<Order> orders = new ArrayList<>();
        private final Map<String, Order> byClOrdId = new HashMap<>();

        synchronized void add(Order order) {
            orders.add(order);
            byClOrdId.put(order.originalClOrdId, order);
        }

        synchronized void alias(String clOrdId, Order order) {
            byClOrdId.put(clOrdId, order);
            order.clOrdIdOverride = clOrdId;
        }

        synchronized Order find(String clOrdId) {
            return clOrdId == null || clOrdId.isEmpty() ? null : byClOrdId.get(clOrdId);
        }

        synchronized List<Order> snapshot() {
            return new ArrayList<>(orders);
        }
    }
}
