package com.project.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.json.JSONObject;
import org.json.JSONArray;

import com.piomatter.PioMatter;
import com.piomatter.UtilsFPS;
import com.piomatter.UtilsImage;
import com.piomatter.UtilsImage.FitMode;

public class Main {

    // Matriu
    private static final int WIDTH = 64, HEIGHT = 64;
    private static final int ADDR = 5;
    private static final int LANES = 2;
    private static final int BRIGHTNESS = 200;
    private static final int FPS_CAP = 60;

    // Dibuix
    private static final int TEXT_X = 5;
    private static final int RESERVED_TOP = 0; // Tret espai FPS
    private static final int TEXT_TOP_PAD = 2;

    // Dibuix del Joc
    private static final int PADDLE_W = 2;
    private static final int PADDLE_H = 10;
    private static final int PADDLE_MARGIN = 5;
    private static final int BALL_SIZE = 3;

    // Mode PREGAME
    private enum Mode { NONE, TEXT, IMAGE, GAME, TEXT_SCROLL, PREGAME } 
    private volatile Mode mode = Mode.NONE;
    
    private final String scrollUrlText;
    private volatile String text = null; 
    
    private volatile BufferedImage image = null;
    private volatile long expireAtMs = 0L;
    
    private volatile int scrollX = WIDTH;

    // Estat del Joc
    private volatile double p1_y = 0.5, p2_y = 0.5;
    private volatile double ball_x = 0.5, ball_y = 0.5;
    private volatile int score1 = 0, score2 = 0; 

    private List<String> knownPlayers = new ArrayList<>();
    private final UtilsWS ws;

    /**
     * Constructor
     */
    public Main(String serverUri) {
        ws = UtilsWS.getSharedInstance(serverUri); 
        ws.onMessage(this::onWsMessage); 

        ws.onOpen(openMessage -> {
            ws.safeSend("NICKNAME:Pantalla_Matrix");
        });

        this.scrollUrlText = serverUri;
        this.mode = Mode.TEXT_SCROLL;
        this.scrollX = WIDTH; 
    }

    /**
     * Gestor principal de tots els missatges del servidor.
     */
    private void onWsMessage(String msg) {
        
        if (msg.equals("ACCEPTED")) {
            return;
        }
        if (msg.startsWith("REJECTED")) {
            System.out.println("[client] ¡Nickname REJECTED!: " + msg);
            this.text = "NICK REJECTED";
            this.mode = Mode.TEXT; // Mode text (expira)
            this.expireAtMs = System.currentTimeMillis() + 10000;
            return;
        }

        try {
            JSONObject o = new JSONObject(msg);
            String t = o.optString("type", "");
            
            switch (t) {
                
                // --- ¡CAMBIO! ---
                // Comprova si es un text pre-partida o temporal
                case "text" -> {
                    String msgText = o.optString("message", "");
                    if (msgText.startsWith("Starts Player")) {
                        //missatge de pre-partida
                        text = msgText.replace("Starts Player", " ");
                        mode = Mode.PREGAME; // Mode centrat, no expira
                    } else {
                        //missatge temporal
                        text = msgText;
                        if (text.startsWith("Hola ")) {
                            text = text.replaceFirst(" ", "\n");
                        }
                        mode = Mode.TEXT;
                        expireAtMs = System.currentTimeMillis() + o.optLong("ttl_ms", 5000L);
                    }
                    System.out.println("[client] TEXT: " + text); 
                }
                case "image" -> {
                    String b64 = o.optString("b64", "");
                    if (b64.isEmpty()) { mode = Mode.NONE; return; }
                    try {
                        byte[] data = Base64.getDecoder().decode(b64);
                        image = ImageIO.read(new ByteArrayInputStream(data));
                        text = null;
                        mode = Mode.IMAGE;
                        expireAtMs = System.currentTimeMillis() + o.optLong("ttl_ms", 5000L);
                    } catch (Exception e) { mode = Mode.NONE; }
                }
                
                case "clients" -> {
                    JSONArray players = o.optJSONArray("list");
                    if (players == null) return;
                    
                    List<String> newPlayers = new ArrayList<>();
                    for (int i=0; i < players.length(); i++) {
                        newPlayers.add(players.getString(i));
                    }
                    // Nomes si estem al lobby
                    if (mode == Mode.TEXT_SCROLL) { 
                        for (String name : newPlayers) {
                            if (!knownPlayers.contains(name) && !name.equalsIgnoreCase("Pantalla_Matrix")) {
                                text = "Hola\n" + name;
                                mode = Mode.TEXT;
                                expireAtMs = System.currentTimeMillis() + 3000;
                                System.out.println("[client] Salutació: " + name);
                                break; 
                            }
                        }
                    }
                    knownPlayers = newPlayers;
                }
                
                // ---FLUX DE PARTIDA ---
                case "game_start" -> {
                    System.out.println("[client] GAME_START rebut");
                    mode = Mode.NONE; 
                }
                
                // Tots els missatges de pre-partida van al mode PREGAME
                case "choosing_starter" -> {
                    mode = Mode.PREGAME;
                    text = "loading...";
                    image = null;
                    System.out.println("[client] LOADING...");
                }
                case "countdown" -> {
                    mode = Mode.PREGAME;
                    text = o.optString("value", "!");
                    image = null;
                    System.out.println("[client] COUNTDOWN: " + text);
                }

                case "game_state" -> {
                    mode = Mode.GAME;
                    p1_y = o.optDouble("p1_y", 0.5);
                    p2_y = o.optDouble("p2_y", 0.5);
                    ball_x = o.optDouble("ball_x", 0.5);
                    ball_y = o.optDouble("ball_y", 0.5);
                    score1 = o.optInt("score1", 0);
                    score2 = o.optInt("score2", 0);
                }
                case "game_over" -> {
                    mode = Mode.TEXT;
                    String winner = o.optString("winner", "");
                    String reason = o.optString("reason", "");
                    
                    if (!winner.isEmpty()) text = winner + "\nWINS!"; 
                    else if (!reason.isEmpty()) text = reason; 
                    else text = "GAME OVER";
                    
                    image = null;
                    expireAtMs = System.currentTimeMillis() + 10000; 
                    System.out.println("[client] GAME OVER: " + text);
                }
                default -> {
                }
            }
        } catch (Exception e) {
            System.out.println("[client] Error processant JSON: " + e.getMessage());
        }
    }

    /**
     * Bucle principal de dibuixat
     */
    public void run() {
        PioMatter pm = null;
        PioMatter.FB fb = null;
        BufferedImage back = null;
        Graphics2D g = null;
        final UtilsFPS fps = new UtilsFPS();
        Font font = null;
        Font scoreFont = null;

        try {
            pm = new PioMatter(WIDTH, HEIGHT, ADDR, LANES, BRIGHTNESS, 0);
            fb = pm.mapFramebuffer();
            back = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            g = back.createGraphics();
            
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            font = new Font("SansSerif", Font.PLAIN, 12);
            scoreFont = new Font("SansSerif", Font.BOLD, 10); 

            PioMatter.flushBlack(pm, fb, 2, 10);

            while (true) {
                fps.beginFrame();

                g.setColor(Color.BLACK);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                
                int startY = Math.max(0, RESERVED_TOP + TEXT_TOP_PAD);
                int availH = Math.max(0, HEIGHT - startY);
                int availW = Math.max(0, WIDTH - TEXT_X);

                // --- Logica de dibuix ---
                
                if (mode == Mode.GAME) {
                    // --- DIBUIXAR JOC ---
                    g.setColor(Color.WHITE);
                    int p1_draw_y = (int)(p1_y * HEIGHT);
                    int p2_draw_y = (int)(p2_y * HEIGHT);
                    g.fillRect(PADDLE_MARGIN, p1_draw_y - (PADDLE_H / 2), PADDLE_W, PADDLE_H);
                    g.fillRect(WIDTH - PADDLE_MARGIN - PADDLE_W, p2_draw_y - (PADDLE_H / 2), PADDLE_W, PADDLE_H);
                    
                    int ball_draw_x = (int)(ball_x * WIDTH);
                    int ball_draw_y = (int)(ball_y * HEIGHT);
                    g.fillRect(ball_draw_x - (BALL_SIZE / 2), ball_draw_y - (BALL_SIZE / 2), BALL_SIZE, BALL_SIZE);
                    
                    String score = score1 + " - " + score2;
                    g.setFont(scoreFont);
                    FontMetrics fm = g.getFontMetrics();
                    int scoreWidth = fm.stringWidth(score);
                    g.drawString(score, (WIDTH - scoreWidth) / 2, fm.getAscent() + 2); 
                }
                
                else if (mode == Mode.TEXT && text != null) {
                    // --- DIBUIXAR TEXT ESTÀTIC ---
                    if (System.currentTimeMillis() < expireAtMs) {
                        g.setFont(font);
                        g.setColor(Color.WHITE);
                        FontMetrics fm = g.getFontMetrics();

                        // Dibuixar text embolicat
                        List<String> lines = wrapText(text, fm, availW, availH);
                        int y = startY + fm.getAscent();
                        for (String line : lines) {
                            g.drawString(line, TEXT_X, y);
                            y += fm.getHeight();
                        }
                    } else {
                        // El text ha expirat. Tornem al carrusel.
                        mode = Mode.TEXT_SCROLL;
                        text = null;
                    }
                } 

                // Nova lògica de dibuixat pel mode PREGAME
                else if (mode == Mode.PREGAME && text != null) {
                    // --- DIBUIXAR TEXT PRE-PARTIDA ---
                    // (Per "loading...", "Inicia:...", "3", "2", "1", "GO!")
                    g.setFont(font);
                    g.setColor(Color.WHITE);
                    FontMetrics fm = g.getFontMetrics();

                    // Dibuixar text CENTRAT
                    int textWidth = fm.stringWidth(text);
                    int x = (WIDTH - textWidth) / 2;
                    int y = (HEIGHT + fm.getAscent() - fm.getDescent()) / 2; // Centrat vertical
                    g.drawString(text, x, y);
                    
                }

                else if (mode == Mode.IMAGE && image != null) {
                    // --- DIBUIXAR IMATGE ---
                    if (System.currentTimeMillis() < expireAtMs) {
                        UtilsImage.drawImageFit(g, image, 0, 0, WIDTH, HEIGHT, FitMode.CONTAIN);
                    } else {
                        // La imatge ha expirat. Tornem al carrusel.
                        mode = Mode.TEXT_SCROLL;
                        image = null;
                    }
                }
                
                // --- DIBUIXAR CARRUSEL ---
                else if (mode == Mode.TEXT_SCROLL || mode == Mode.NONE) {
                    mode = Mode.TEXT_SCROLL;
                    g.setColor(Color.WHITE);

                    // Dibuixar Header Estàtic
                    g.setFont(scoreFont);
                    FontMetrics fmHeader = g.getFontMetrics();
                    g.drawString("matrix5", 2, fmHeader.getAscent() + 2);

                    // Dibuixar Carrusel a sota
                    g.setFont(font);
                    FontMetrics fmCarousel = g.getFontMetrics();
                    
                    int y = (HEIGHT + fmCarousel.getAscent() - fmCarousel.getDescent()) / 2;
                    g.drawString(scrollUrlText, scrollX, y); 
                    
                    scrollX--;
                    
                    int textWidth = fmCarousel.stringWidth(scrollUrlText);
                    if (scrollX < -textWidth) {
                        scrollX = WIDTH; 
                    }
                }
                
                // Activar para ver FPS
                // fps.drawOverlay(g, 1, 9);
                
                PioMatter.copyBufferedImageToRGB888(back, fb.data, fb.strideBytes, WIDTH, HEIGHT, BRIGHTNESS);
                pm.swap();
                fps.endFrameAndCap(FPS_CAP);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (g != null) g.dispose();
            try { if (pm != null && fb != null) PioMatter.flushBlack(pm, fb, 2, 10); } catch (InterruptedException ignored) {}
            if (pm != null) pm.close();
            ws.forceExit();
        }
    }

    // --- Mètodes 'wrapText' i 'truncateWithEllipsis' ---
    private static List<String> wrapText(String s, FontMetrics fm, int maxW, int maxH) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null || s.isEmpty() || maxW <= 0 || maxH <= 0) return out;
        int lineH = fm.getHeight();
        int maxLines = Math.max(1, maxH / lineH);
        
        String[] paragraphs = s.split("\\R"); 
        
        for (String para : paragraphs) {
            if (out.size() >= maxLines) break;

            if (para.isEmpty()) {
                out.add("");
                continue;
            }

            String[] words = para.split("\\s+");
            StringBuilder line = new StringBuilder();
            
            for (int i = 0; i < words.length; i++) {
                String w = words[i];
                String candidate = line.length() == 0 ? w : (line + " " + w);
                
                if (fm.stringWidth(candidate) <= maxW) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    if (line.length() == 0) {
                        out.add(truncateWithEllipsis(w, fm, maxW));
                    } else {
                        out.add(line.toString());
                        i--; 
                    }
                    line.setLength(0);
                    if (out.size() >= maxLines) break;
                }
            }
            
            if (out.size() >= maxLines) break;
            if (line.length() > 0) {
                out.add(line.toString());
            }
        }

        if (out.size() > maxLines) {
            while (out.size() > maxLines) out.remove(out.size() - 1);
            String last = out.get(out.size() - 1);
            out.set(out.size() - 1, truncateWithEllipsis(last, fm, maxW));
        }
        return out;
    }

    private static String truncateWithEllipsis(String s, FontMetrics fm, int maxW) {
        if (fm.stringWidth(s) <= maxW) return s;
        String ell = "…";
        int ellW = fm.stringWidth(ell);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            int w = fm.stringWidth(sb.toString() + s.charAt(i));
            if (w + ellW > maxW) break;
            sb.append(s.charAt(i));
        }
        sb.append(ell);
        return sb.toString();
    }

    /**
     * Punt d'entrada principal.
     */
    public static void main(String[] args) {
        String serverURI = (args.length > 0) ? args[0] : "wss://matrixplay5.ieti.site:443";        
        Main app = new Main(serverURI);
        app.run();
    }
}
