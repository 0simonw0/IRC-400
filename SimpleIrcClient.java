/*
 SimpleIrcClient.java v1.8
 A tiny command-line IRC client in Java (no external libraries).

 Usage:
   javac SimpleIrcClient.java
   java SimpleIrcClient <server> <port> <nick> <realname> [#channel]

 Features:
  - Automatic PING/PONG keepalive
  - CTCP support (VERSION, PING, TIME, FINGER, CLIENTINFO)
  - Commands: /join /part /nick /msg /query /whois /version /raw /quit /help
*/

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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean registered = false;

    public SimpleIrcClient(String server, int port, String nick, String realName, String channel) {
        this.server = server;
        this.port = port;
        this.nick = nick;
        this.realName = realName;
        this.currentChannel = channel;
    }

    /* ===================== START ===================== */

    public void start() throws IOException {
        socket = new Socket(server, port);
        socket.setKeepAlive(true);

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        running.set(true);

        // start reading thread
        new Thread(this::readLoop, "irc-reader").start();

        // delay registration to avoid Connection reset
        sleepQuiet(1000);
        sendRaw("NICK " + nick);
        sleepQuiet(1000);
        sendRaw("USER " + nick + " 0 * :" + realName);

        // start keepalive
        new Thread(this::keepAliveLoop, "irc-keepalive").start();

        // read user input
        readUserInput();

        shutdown();
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void keepAliveLoop() {
        while (running.get()) {
            try {
                Thread.sleep(60000);
                if (registered) {
                    sendRaw("PING keepalive");
                }
            } catch (Exception ignored) {}
        }
    }

    /* ===================== SERVER ===================== */

    private void readLoop() {
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                handleServerLine(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                System.out.println("Connection lost: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    private void handleServerLine(String line) throws IOException {
        System.out.println("< " + line);

        if (line.startsWith("PING ")) {
            sendRaw("PONG " + line.substring(5));
            return;
        }

        if (line.contains(" 001 " + nick)) {
            registered = true;
            if (currentChannel != null) {
                sendRaw("JOIN " + currentChannel);
            }
        }

        if (line.contains(" PRIVMSG ")) {
            handlePrivmsg(line);
        }
    }

    private void handlePrivmsg(String line) throws IOException {
        String[] p = line.split(" ", 4);
        if (p.length < 4) return;

        String prefix = p[0];
        String target = p[2];
        String msg = p[3].startsWith(":") ? p[3].substring(1) : p[3];
        String sender = prefix.substring(1).split("!")[0];

        // CTCP
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

    private void handleCTCP(String sender, String cmd) throws IOException {
        if (cmd.equals("VERSION")) {
            sendNotice(sender, "VERSION IRC/400 v1.8");
        } else if (cmd.startsWith("PING")) {
            sendNotice(sender, "PING " + cmd.substring(4).trim());
        } else if (cmd.equals("TIME")) {
            sendNotice(sender, "TIME " + new Date());
        } else if (cmd.equals("FINGER")) {
            sendNotice(sender, "FINGER Java IRC Client on IBM i");
        } else if (cmd.equals("CLIENTINFO")) {
            sendNotice(sender, "CLIENTINFO VERSION PING TIME FINGER CLIENTINFO");
        }
    }

    private void sendNotice(String target, String text) throws IOException {
        sendRaw("NOTICE " + target + " :\u0001" + text + "\u0001");
    }

    /* ===================== USER INPUT ===================== */

    private void readUserInput() throws IOException {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;

        while (running.get() && (line = stdin.readLine()) != null) {
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                if (!handleCommand(line)) {
                    break;
                }
            } else {
                sendMessage(line);
            }
        }
    }

    private boolean handleCommand(String line) throws IOException {
        String[] p = line.split(" ", 3);
        String cmd = p[0].toLowerCase();

        switch (cmd) {
            case "/join":
                if (p.length < 2) { System.out.println("Usage: /join <#channel>"); break; }
                currentChannel = p[1];
                privateTarget = null;
                sendRaw("JOIN " + currentChannel);
                break;

            case "/part":
                if (currentChannel != null) {
                    sendRaw("PART " + currentChannel);
                    currentChannel = null;
                }
                break;

            case "/query":
                if (p.length < 2) { System.out.println("Usage: /query <nick>"); break; }
                privateTarget = p[1];
                System.out.println("Private chat with " + privateTarget);
                break;

            case "/msg":
                if (p.length < 3) { System.out.println("Usage: /msg <target> <message>"); break; }
                sendPrivmsg(p[1], p[2]);
                break;

            case "/whois":
                if (p.length < 2) { System.out.println("Usage: /whois <nick>"); break; }
                sendRaw("WHOIS " + p[1]);
                break;

            case "/version":
                sendRaw("VERSION");
                break;

            case "/raw":
                if (p.length < 2) { System.out.println("Usage: /raw <line>"); break; }
                sendRaw(line.substring(5));
                break;

            case "/nick":
                if (p.length < 2) { System.out.println("Usage: /nick <newnick>"); break; }
                nick = p[1];
                sendRaw("NICK " + nick);
                break;

            case "/help":
                System.out.println("/join <#channel>  - join channel");
                System.out.println("/part             - leave current channel");
                System.out.println("/nick <newnick>   - change nick");
                System.out.println("/msg <target> <text> - send private or channel message");
                System.out.println("/query <nick>     - start private chat");
                System.out.println("/whois <nick>     - request info");
                System.out.println("/version          - server version");
                System.out.println("/raw <line>       - send raw IRC line");
                System.out.println("/quit             - quit client");
                System.out.println("/help             - show this help");
                break;

            case "/quit":
                sendRaw("QUIT :Bye");
                return false;

            default:
                System.out.println("Unknown command. Type /help");
        }
        return true;
    }

    private void sendMessage(String msg) throws IOException {
        if (privateTarget != null) {
            sendPrivmsg(privateTarget, msg);
        } else if (currentChannel != null) {
            sendPrivmsg(currentChannel, msg);
        } else {
            System.out.println("No active target.");
        }
    }

    private void sendPrivmsg(String target, String msg) throws IOException {
        sendRaw("PRIVMSG " + target + " :" + msg);
        System.out.println("[to " + target + "] " + msg);
    }

    private synchronized void sendRaw(String msg) throws IOException {
        try {
            writer.write(msg + "\r\n");
            writer.flush();
            System.out.println("> " + msg);
        } catch (IOException e) {
            System.out.println("Send failed: " + e.getMessage());
        }
    }

    private void shutdown() {
        running.set(false);
        try {
            socket.close();
        } catch (IOException ignored) {}
        System.out.println("Client stopped.");
    }

    /* ===================== MAIN ===================== */

    public static void main(String[] args) throws Exception {
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
