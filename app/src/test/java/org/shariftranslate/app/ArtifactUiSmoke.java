package org.shariftranslate.app;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Verifies the real packaged application UI with its bundled runtime. */
public class ArtifactUiSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("appData", new File(args[0]).getAbsolutePath());
        System.setProperty("shariftranslate.instancePort", "49296");
        Thread app = new Thread(() -> MainKt.main(), "artifact-ui");
        app.setDaemon(true);
        app.start();
        JFrame[] found = new JFrame[1];
        for (int i = 0; i < 120 && found[0] == null; i++) {
            SwingUtilities.invokeAndWait(() -> {
                for (Frame f : Frame.getFrames()) {
                    if (f.getClass().getName().equals("org.shariftranslate.ui.swing.main.MainAppFrame")) found[0] = (JFrame) f;
                }
            });
            Thread.sleep(250);
        }
        if (found[0] == null) throw new AssertionError("Main window not created");
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = found[0];
            if (!f.getTitle().contains("Sharif Translate")) throw new AssertionError(f.getTitle());
            if (f.getIconImages().isEmpty()) throw new AssertionError("No window icon");
            f.setVisible(true);
            f.validate();
            BufferedImage rendered = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = rendered.createGraphics();
            f.paintAll(g);
            g.dispose();
            try { ImageIO.write(rendered, "png", new File(args[1])); } catch (Exception e) { throw new RuntimeException(e); }
            System.out.println("PASS: actual application title, window icons and Swing rendering: " + f.getTitle());
            if (SystemTray.isSupported()) {
                boolean correct = false;
                for (TrayIcon icon : SystemTray.getSystemTray().getTrayIcons()) if ("Sharif Translate".equals(icon.getToolTip())) correct = true;
                if (!correct) throw new AssertionError("Tray branding missing");
                System.out.println("PASS: Sharif Translate tray icon");
            }
        });
        System.exit(0);
    }
}
