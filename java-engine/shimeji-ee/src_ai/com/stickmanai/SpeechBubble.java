package com.stickmanai;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;

import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import com.group_finity.mascot.Mascot;

/**
 * Small always-on-top speech balloon that floats above the AI mascot
 * when it uses the "say" action.
 *
 * AIBehavior.next() runs on the Manager's dedicated 40ms tick thread, not
 * the Swing EDT (the rest of the engine gets away with touching Swing off
 * the EDT because Mascot.apply() paints the mascot window through its own
 * native image blit, bypassing normal component layout - a plain
 * JLabel/JWindow does not have that luxury, so every mutation here is
 * marshalled onto the EDT).
 */
public class SpeechBubble {

    private final JWindow window;
    private final JLabel label;

    public SpeechBubble() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);

        label = new JLabel();
        label.setOpaque(true);
        label.setBackground(new Color(255, 255, 255, 235));
        // Force a dark foreground regardless of the active Look & Feel -
        // Shimeji-ee installs a custom light theme (NimROD) whose default
        // label text color is too light to read against a white bubble.
        label.setForeground(Color.BLACK);
        label.setBorder(new javax.swing.border.CompoundBorder(
                new LineBorder(new Color(60, 60, 60), 1, true),
                new EmptyBorder(6, 10, 6, 10)));
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
        window.getContentPane().add(label);
    }

    public void show(final String text, final Mascot mascot) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                label.setText("<html><body style='width: 220px; color: black;'>" + escape(text) + "</body></html>");
                window.pack();
                positionOver(mascot.getBounds());
                window.setVisible(true);
            }
        });
    }

    public void hide() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (window.isVisible()) {
                    window.setVisible(false);
                }
            }
        });
    }

    public void followMascot(final Mascot mascot) {
        if (!window.isVisible()) return;
        final Rectangle bounds = mascot.getBounds();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (window.isVisible()) {
                    positionOver(bounds);
                }
            }
        });
    }

    private void positionOver(Rectangle mascotBounds) {
        window.setLocation(
                mascotBounds.x + mascotBounds.width / 2 - window.getWidth() / 2,
                mascotBounds.y - window.getHeight() - 8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
