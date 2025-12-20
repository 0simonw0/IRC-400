import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleIrcClient {

    private final String server;
    private final int port;
    private volatile String nick;
    private final String realName;

    private volatile String currentChannel;
    private volatile String privateTarget;

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile boolean registered = false;
    private volatile boolean userQuit = false;

    /* keepalive */
    private volatile long lastKeepalive = 0;

    /* reconnect */
    private Thread reconnectThread;

    public SimpleIrcClient(String server, int port, String nick, String realName, String channel) {
        this.server = server;
        this.port = port;
        this.nick = nick;
        this.realName = realName;
        this.currentChannel = channel;
    }

    /* ===================== START ===================== */

    public void start() {
        connect();
        readUserInput();
        userQuit = true;
        disconnect();
    }

    /* ===================== CONNECT ===================== */

    private synchronized void connect() {
        try {
            System.out.println("Connecting to " + server + ":" + port + "...");
            socket = new Socket(server, port);

            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            connected.set(true);
            registered = false;

            sendRaw("NICK " + nick);
            sendRaw("USER " + nick + " 0 * :" + realName);

            new Thread(this::readLoop, "irc-reader").start();
            startKeepalive();

        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (userQuit) return;
        if (reconnectThread != null && reconnectThread.isAlive()) return;

        reconnectThread = new Thread(() -> {
            try {
                System.out.println("Reconnecting in 5 seconds...");
                Thread.sleep(5000);
                connect();
            } catch (InterruptedException ignored) {}
        }, "irc-reconnect");

        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void disconnect() {
        connected.set(false);
        try { socket.close(); } catch (Exception ignored) {}
        System.out.println("Disconnected.");
    }

    /* ===================== KEEPALIVE ===================== */

    private void startKeepalive() {
        new Thread(() -> {
            while (connected.get()) {
                try {
                    Thread.sleep(60000);
                    if (connected.get() && registered) {
                        lastKeepalive = System.currentTimeMillis();
                        sendRaw("PING :keepalive");
                    }
                } catch (Exception ignored) {}
            }
        }, "irc-keepalive").start();
    }

    /* ===================== SERVER ===================== */

    private void readLoop() {
        try {
            String line;
            while (connected.get() && (line = reader.readLine()) != null) {
                handleServerLine(line);
            }
        } catch (IOException e) {
            if (!userQuit) System.out.println("Connection lost.");
        } finally {
            connected.set(false);
            if (!userQuit) scheduleReconnect();
        }
    }

    private void handleServerLine(String line) throws IOException {

        /* FILTER KEEPALIVE */
        if (line.contains(" PONG ") && line.contains("keepalive")) return;
        if (line.startsWith("PING ")) {
            sendRaw("PONG " + line.substring(5));
            return;
        }

        System.out.println("< " + line);

        if (line.contains(" 001 " + nick)) {
            registered = true;
            if (currentChannel != null) sendRaw("JOIN " + currentChannel);
        }

        if (line.contains(" PRIVMSG ")) handlePrivmsg(line);
    }

    private void handlePrivmsg(String line) throws IOException {
        String[] p = line.split(" ", 4);
        if (p.length < 4) return;

        String sender = p[0].substring(1).split("!")[0];
        String target = p[2];
        String msg = p[3].substring(1);

        if (msg.startsWith("\u0001") && msg.endsWith("\u0001")) {
            handleCTCP(sender, msg.substring(1, msg.length() - 1));
            return;
        }

        if (target.equalsIgnoreCase(nick)) {
            privateTarget = sender;
            System.out.println("[PM] <" + sender + "> " + msg);
        } else {
            System.out.println("[" + target + "] <" + sender + "> " + msg);
        }
    }

    /* ===================== CTCP ===================== */

    private void handleCTCP(String nick, String cmd) throws IOException {
        if (cmd.equals("VERSION")) sendRaw("NOTICE " + nick + " :\u0001VERSION IRC/400 v1.9.1\u0001");
        if (cmd.equals("TIME")) sendRaw("NOTICE " + nick + " :\u0001TIME " + new Date() + "\u0001");
    }

    /* ===================== USER INPUT ===================== */

    private void readUserInput() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("/")) handleCommand(line);
                else sendMessage(line);
            }
        } catch (IOException ignored) {}
    }

    private void handleCommand(String line) throws IOException {
        String[] p = line.split(" ", 3);

        switch (p[0]) {
            case "/join": currentChannel = p[1]; sendRaw("JOIN " + p[1]); break;
            case "/part": sendRaw("PART " + currentChannel); currentChannel = null; break;
            case "/msg": sendRaw("PRIVMSG " + p[1] + " :" + p[2]); break;
            case "/query": privateTarget = p[1]; break;
            case "/whois": sendRaw("WHOIS " + p[1]); break;
            case "/nick": nick = p[1]; sendRaw("NICK " + nick); break;
            case "/raw": sendRaw(line.substring(5)); break;
            case "/away": sendRaw("AWAY :" + (p.length > 1 ? p[1] : "away")); break;
            case "/back": sendRaw("AWAY"); break;
            case "/status":
                System.out.println("Connected: " + connected.get());
                System.out.println("Registered: " + registered);
                System.out.println("Channel: " + currentChannel);
                System.out.println("Last keepalive: " +
                        (System.currentTimeMillis() - lastKeepalive) / 1000 + "s ago");
                break;
            case "/help":
                System.out.println("/join /part /msg /query /whois /nick /away /back /status /quit");
                break;
            case "/quit":
                userQuit = true;
                sendRaw("QUIT :Bye");
                disconnect();
                System.exit(0);
        }
    }

    private void sendMessage(String msg) throws IOException {
        if (privateTarget != null)
            sendRaw("PRIVMSG " + privateTarget + " :" + msg);
        else if (currentChannel != null)
            sendRaw("PRIVMSG " + currentChannel + " :" + msg);
    }

    private synchronized void sendRaw(String msg) throws IOException {
        if (!connected.get()) return;
        writer.write(msg + "\r\n");
        writer.flush();
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) {
        if (args.length < 4) {
            System.out.println("Usage: java SimpleIrcClient <server> <port> <nick> <realname> [#channel]");
            return;
        }

        new SimpleIrcClient(
                args[0],
                Integer.parseInt(args[1]),
                args[2],
                args[3],
                args.length > 4 ? args[4] : null
        ).start();
    }
}
