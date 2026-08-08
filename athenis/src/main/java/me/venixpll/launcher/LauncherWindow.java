package me.venixpll.launcher;

import imgui.app.Application;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.MemoryLoop;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.AimbotModule;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.cheat.module.impl.RadarHackModule;
import me.venixpll.cheat.module.impl.TriggerBotModule;
import me.venixpll.cheat.module.impl.BunnyHopModule;
import me.venixpll.cheat.module.impl.BombTimerModule;
import me.venixpll.cheat.module.impl.SpectatorListModule;
import me.venixpll.cheat.module.impl.CrosshairOverlayModule;
import me.venixpll.cheat.module.impl.SilentAimModule;
import me.venixpll.cheat.module.impl.NoSpreadModule;
import me.venixpll.cheat.module.impl.VisRayDebugModule;
import me.venixpll.config.ConfigManager;
import me.venixpll.overlay.OverlayWindow;
import me.venixpll.cheat.vischeck.VPhysToOptConverter;
import me.venixpll.Main;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Main launcher window shown at startup before the overlay engine begins.
 * <p>
 * Provides a modern dark-themed GUI with:
 * <ul>
 * <li>A <b>START</b> button that loads offsets, registers modules, starts the
 * memory threads, and launches the transparent overlay window.</li>
 * <li>A <b>STOP</b> button that halts the memory threads and closes the
 * overlay.</li>
 * <li>A live coloured log area that intercepts {@link System#out} and
 * {@link System#err}, timestamps every line, and highlights known patterns
 * (addresses, success messages, errors, tags).</li>
 * <li>An animated pulsing status dot indicating the current engine state.</li>
 * </ul>
 * The launcher is an undecorated window with a custom drag-able title bar,
 * keeping it visually consistent across Windows versions.
 */
public class LauncherWindow extends JFrame {

    // ── Colour palette (Catppuccin Mocha-inspired) ────────────────────────────
    private static final Color C_BG = new Color(0x0A0B0E);
    private static final Color C_SURFACE = new Color(0x111318);
    private static final Color C_SURFACE2 = new Color(0x161B22);
    private static final Color C_BORDER = new Color(0x21262D);
    private static final Color C_ACCENT = new Color(0x00B4D8); // cyan
    private static final Color C_TAG = new Color(0x89B4FA); // lavender — for [Tag] labels
    private static final Color C_TEXT = new Color(0xCDD6F4); // primary text
    private static final Color C_TEXT_DIM = new Color(0x6C7086); // muted text
    private static final Color C_SUCCESS = new Color(0xA6E3A1); // green
    private static final Color C_ERROR = new Color(0xF38BA8); // red
    private static final Color C_WARN = new Color(0xF9E2AF); // yellow
    private static final Color C_TEAL = new Color(0x89DCEB); // hex values / addresses

    // ── Typography ────────────────────────────────────────────────────────────
    private static final Font F_TITLE = new Font("Segoe UI", Font.BOLD, 20);
    private static final Font F_SMALL = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font F_MONO = new Font("Consolas", Font.PLAIN, 12);
    private static final Font F_BTN = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font F_STATUS = new Font("Segoe UI", Font.PLAIN, 13);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Runtime state ─────────────────────────────────────────────────────────
    private volatile boolean overlayRunning = false;
    private boolean isListeningForKey = false;

    /** Screen position at the start of a title-bar drag gesture. */
    private Point dragAnchorScreen;
    /** Window location snapped at the start of a drag gesture. */
    private Point windowLocAtDrag;

    // ── Component references ──────────────────────────────────────────────────
    private JTextPane logPane;
    private StyledDocument logDoc;
    private JButton startBtn;
    private JButton stopBtn;
    private JButton hotkeyBtn;
    private JLabel statusLabel;
    private JPanel pulseDot;

    /** Pulse animation: current opacity of the status dot (0.0–1.0). */
    private float pulseAlpha = 1.0f;
    /** True while the dot is fading out; false while brightening. */
    private boolean pulseFading = true;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs and displays the launcher window.
     * Call only from the Event Dispatch Thread via
     * {@code SwingUtilities.invokeLater(LauncherWindow::new)}.
     */
    public LauncherWindow() {
        setUndecorated(true);
        setSize(860, 580);
        setMinimumSize(new Dimension(700, 460));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        getContentPane().setBackground(C_BG);

        // Register cheat modules early so settings can be loaded at startup
        if (ModuleManager.getModules().isEmpty()) {
            ModuleManager.registerModule(new ESPModule());
            ModuleManager.registerModule(new RadarHackModule());
            ModuleManager.registerModule(new AimbotModule());
            ModuleManager.registerModule(new SilentAimModule());
            ModuleManager.registerModule(new NoSpreadModule());
            ModuleManager.registerModule(new TriggerBotModule());
            ModuleManager.registerModule(new BunnyHopModule());
            ModuleManager.registerModule(new BombTimerModule());
            ModuleManager.registerModule(new SpectatorListModule());
            ModuleManager.registerModule(new CrosshairOverlayModule());
            ModuleManager.registerModule(new VisRayDebugModule());
        }
        ConfigManager.load();

        buildUI();
        redirectStreams();
        startPulseAnimation();
        setupSystemTray();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowIconified(WindowEvent e) {
                setVisible(false);
            }
        });

        setVisible(true);

        log("INFO", "Athenis launcher ready.  Press START to initialise.");
    }

    // ── UI construction ───────────────────────────────────────────────────────

    /** Assembles the top-level layout: title bar → status bar → log → controls. */
    private void buildUI() {
        setLayout(new BorderLayout());
        getRootPane().setBorder(BorderFactory.createLineBorder(C_BORDER, 1));

        add(buildTitleBar(), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(C_BG);
        body.add(buildStatusBar(), BorderLayout.NORTH);
        body.add(buildLogArea(), BorderLayout.CENTER);
        body.add(buildControls(), BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
    }

    /**
     * Builds the custom undecorated title bar with branding, drag support,
     * a minimise button and a close button.
     */
    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(C_SURFACE);
        bar.setPreferredSize(new Dimension(0, 52));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));

        // ── Left — branding ───────────────────────────────────────────────────
        // vgap=13 centres the row vertically inside the 52px title bar (52-26)/2 = 13
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 13));
        left.setBackground(C_SURFACE);
        left.setBorder(new EmptyBorder(0, 6, 0, 0));

        // Small glowing accent dot acting as a logo
        JPanel logoDot = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Outer soft glow ring
                g2.setColor(new Color(C_ACCENT.getRed(), C_ACCENT.getGreen(), C_ACCENT.getBlue(), 55));
                g2.fillOval(-3, -3, getWidth() + 6, getHeight() + 6);
                // Solid fill
                g2.setColor(C_ACCENT);
                g2.fillOval(0, 0, getWidth(), getHeight());
                // Specular highlight
                g2.setColor(new Color(255, 255, 255, 70));
                g2.fillOval(2, 2, getWidth() - 6, getHeight() - 6);
                g2.dispose();
            }
        };
        logoDot.setPreferredSize(new Dimension(14, 14));
        logoDot.setOpaque(false);

        JLabel title = new JLabel("ATHENIS");
        title.setFont(F_TITLE);
        title.setForeground(C_TEXT);

        JLabel sub = new JLabel("CS2 External Overlay");
        sub.setFont(F_SMALL);
        sub.setForeground(C_TEXT_DIM);

        left.add(logoDot);
        left.add(title);
        left.add(Box.createHorizontalStrut(2));
        left.add(sub);

        // ── Right — window controls ───────────────────────────────────────────
        // vgap=13 centres the buttons vertically inside the 52px title bar
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 13));
        right.setBackground(C_SURFACE);
        right.setBorder(new EmptyBorder(0, 0, 0, 8));

        JButton minBtn = buildIconCtrlButton("/assets/menu-burger.png", new Color(0x2D3142));
        minBtn.addActionListener(e -> {
            setState(JFrame.ICONIFIED);
            setVisible(false);
        });

        JButton closeBtn = buildIconCtrlButton("/assets/cross.png", new Color(0x3D1010));
        closeBtn.addActionListener(e -> {
            // Gracefully shut down the engine before exiting
            if (overlayRunning) {
                MemoryLoop.stop();
                OverlayWindow.requestClose();
            }
            System.exit(0);
        });

        right.add(minBtn);
        right.add(closeBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        // ── Drag support ──────────────────────────────────────────────────────
        // Track screen coordinates so drag works regardless of which child fires the
        // event.
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragAnchorScreen = e.getLocationOnScreen();
                windowLocAtDrag = getLocation();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragAnchorScreen == null)
                    return;
                Point now = e.getLocationOnScreen();
                setLocation(windowLocAtDrag.x + now.x - dragAnchorScreen.x,
                        windowLocAtDrag.y + now.y - dragAnchorScreen.y);
            }
        };
        for (Component c : new Component[] { bar, left, logoDot, title, sub }) {
            c.addMouseListener(drag);
            c.addMouseMotionListener(drag);
        }

        return bar;
    }

    /**
     * Builds the slim status row that shows the animated pulse dot and current
     * state text.
     */
    private JPanel buildStatusBar() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(C_SURFACE2);
        row.setBorder(new EmptyBorder(9, 16, 9, 16));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setBackground(C_SURFACE2);

        // Animated dot — colour and opacity driven by pulseTimer
        pulseDot = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = overlayRunning ? C_SUCCESS : C_TEXT_DIM;
                int alpha = Math.max(0, Math.min(255, (int) (pulseAlpha * 255)));
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
                g2.fillOval(0, 2, 10, 10);
                g2.dispose();
            }
        };
        pulseDot.setPreferredSize(new Dimension(10, 14));
        pulseDot.setOpaque(false);

        statusLabel = new JLabel("Idle — waiting to start");
        statusLabel.setFont(F_STATUS);
        statusLabel.setForeground(C_TEXT_DIM);

        left.add(pulseDot);
        left.add(statusLabel);

        JLabel ver = new JLabel("build " + Main.VERSION);
        ver.setFont(F_SMALL);
        ver.setForeground(C_TEXT_DIM);

        row.add(left, BorderLayout.WEST);
        row.add(ver, BorderLayout.EAST);
        return row;
    }

    /**
     * Builds the main log pane — a styled, read-only {@link JTextPane} inside a
     * dark-scrollbar {@link JScrollPane}.
     */
    private JScrollPane buildLogArea() {
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(C_BG);
        logPane.setForeground(C_TEXT);
        logPane.setFont(F_MONO);
        logPane.setBorder(new EmptyBorder(10, 14, 10, 14));
        logDoc = logPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(logPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBackground(C_BG);
        scroll.getViewport().setBackground(C_BG);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, C_BORDER));
        scroll.getVerticalScrollBar().setBackground(C_BG);
        scroll.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        return scroll;
    }

    /**
     * Builds the bottom control bar containing CLEAR LOG, STOP, and START buttons.
     */
    private JPanel buildControls() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(C_SURFACE);
        panel.setBorder(new EmptyBorder(10, 16, 10, 16));

        // Secondary (left) actions
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setBackground(C_SURFACE);

        JButton clearBtn = buildFlatButton("CLEAR LOG", C_TEXT_DIM, C_SURFACE2);
        clearBtn.addActionListener(e -> {
            logPane.setText("");
            log("INFO", "Log cleared.");
        });
        left.add(clearBtn);

        // Hotkey configuration button
        hotkeyBtn = new JButton("MENU KEY: " + KeyEvent.getKeyText(OverlayWindow.toggleKeyJava).toUpperCase()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill;
                if (isListeningForKey) {
                    fill = new Color(0x1B2A4A); // deep blue for active listening state
                } else if (getModel().isRollover()) {
                    fill = C_SURFACE2.brighter();
                } else {
                    fill = C_SURFACE2;
                }
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        hotkeyBtn.setForeground(C_ACCENT);
        hotkeyBtn.setFont(F_BTN);
        hotkeyBtn.setPreferredSize(new Dimension(170, 34));
        styleButtonBase(hotkeyBtn);
        hotkeyBtn.setFocusable(true);

        hotkeyBtn.addActionListener(e -> {
            if (!isListeningForKey) {
                isListeningForKey = true;
                hotkeyBtn.setText("PRESS ANY KEY...");
                hotkeyBtn.repaint();
                hotkeyBtn.requestFocusInWindow();
            }
        });

        hotkeyBtn.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (isListeningForKey) {
                    int code = e.getKeyCode();
                    OverlayWindow.toggleKeyJava = code;
                    ConfigManager.save();

                    isListeningForKey = false;
                    hotkeyBtn.setText("MENU KEY: " + KeyEvent.getKeyText(OverlayWindow.toggleKeyJava).toUpperCase());
                    hotkeyBtn.repaint();
                    logPane.requestFocusInWindow();
                }
            }
        });

        hotkeyBtn.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (isListeningForKey) {
                    isListeningForKey = false;
                    hotkeyBtn.setText("MENU KEY: " + KeyEvent.getKeyText(OverlayWindow.toggleKeyJava).toUpperCase());
                    hotkeyBtn.repaint();
                }
            }
        });

        left.add(hotkeyBtn);

        // VPhys to Opt Converter button
        JButton convertBtn = buildFlatButton("CONVERT MAPS", C_TEXT_DIM, C_SURFACE2);
        convertBtn.addActionListener(e -> {
            LookAndFeel oldLaF = UIManager.getLookAndFeel();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Directory containing .vphys files");

            int result = chooser.showOpenDialog(LauncherWindow.this);

            try {
                UIManager.setLookAndFeel(oldLaF);
            } catch (Exception ignored) {
            }

            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedDir = chooser.getSelectedFile();
                if (selectedDir != null) {
                    new Thread(() -> {
                        log("INFO", "Starting conversion of .vphys files in: " + selectedDir.getAbsolutePath());
                        VPhysToOptConverter.convertDirectory(selectedDir);
                    }, "VPhys-Converter-Thread").start();
                }
            }
        });
        left.add(convertBtn);

        JButton offsetsBtn = buildFlatButton(ConfigManager.offsetsFolder.isEmpty() ? "OFFSETS: DEFAULT" : "OFFSETS: CUSTOM", C_TEXT_DIM, C_SURFACE2);
        if (!ConfigManager.offsetsFolder.isEmpty()) {
            offsetsBtn.setToolTipText("Folder: " + ConfigManager.offsetsFolder);
        }
        offsetsBtn.addActionListener(e -> {
            LookAndFeel oldLaF = UIManager.getLookAndFeel();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Offsets Directory (must contain offsets.json, client_dll.json, buttons.json)");
            if (!ConfigManager.offsetsFolder.isEmpty()) {
                chooser.setCurrentDirectory(new File(ConfigManager.offsetsFolder));
            }

            int result = chooser.showOpenDialog(LauncherWindow.this);

            try {
                UIManager.setLookAndFeel(oldLaF);
            } catch (Exception ignored) {
            }

            if (result == JFileChooser.APPROVE_OPTION) {
                File selectedDir = chooser.getSelectedFile();
                if (selectedDir != null) {
                    ConfigManager.offsetsFolder = selectedDir.getAbsolutePath();
                    ConfigManager.save();
                    offsetsBtn.setText("OFFSETS: CUSTOM");
                    offsetsBtn.setToolTipText("Folder: " + ConfigManager.offsetsFolder);
                    log("INFO", "Offsets folder set to: " + ConfigManager.offsetsFolder);
                }
            } else if (result == JFileChooser.CANCEL_OPTION) {
                int option = JOptionPane.showConfirmDialog(
                    LauncherWindow.this,
                    "Would you like to clear the custom offsets folder and revert to default/online?",
                    "Clear Custom Offsets",
                    JOptionPane.YES_NO_OPTION
                );
                if (option == JOptionPane.YES_OPTION) {
                    ConfigManager.offsetsFolder = "";
                    ConfigManager.save();
                    offsetsBtn.setText("OFFSETS: DEFAULT");
                    offsetsBtn.setToolTipText(null);
                    log("INFO", "Custom offsets folder cleared. Reverted to online/default offsets.");
                }
            }
        });
        left.add(offsetsBtn);

        // Primary (right) actions
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(C_SURFACE);

        stopBtn = buildFlatButton("■  STOP", C_ERROR, new Color(0x2D0D0D));
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> onStop());

        startBtn = buildGradientButton("▶  START", C_ACCENT, new Color(0x005F73));
        startBtn.addActionListener(e -> onStart());

        right.add(stopBtn);
        right.add(startBtn);

        panel.add(left, BorderLayout.WEST);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    // ── Button factories ──────────────────────────────────────────────────────

    /**
     * Creates a compact window chrome button (minimise / close) with a hover fill
     * and an icon.
     *
     * @param iconPath Path to the icon in resources.
     * @param hoverBg  Background colour shown on hover.
     */
    private JButton buildIconCtrlButton(String iconPath, Color hoverBg) {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isRollover()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(hoverBg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        try {
            java.net.URL url = LauncherWindow.class.getResource(iconPath);
            if (url != null) {
                ImageIcon icon = new ImageIcon(url);
                Image img = icon.getImage().getScaledInstance(14, 14, Image.SCALE_SMOOTH);
                btn.setIcon(new ImageIcon(img));
            } else {
                System.err.println("Icon not found: " + iconPath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        btn.setPreferredSize(new Dimension(32, 26));
        styleButtonBase(btn);
        return btn;
    }

    /**
     * Creates a flat rounded action button used for secondary actions like STOP and
     * CLEAR.
     *
     * @param text Label.
     * @param fg   Foreground colour.
     * @param bg   Fill colour.
     */
    private JButton buildFlatButton(String text, Color fg, Color bg) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = isEnabled()
                        ? (getModel().isRollover() ? bg.brighter() : bg)
                        : C_SURFACE2;
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setForeground(fg);
        btn.setFont(F_BTN);
        btn.setPreferredSize(new Dimension(120, 34));
        styleButtonBase(btn);
        return btn;
    }

    /**
     * Creates the primary accent gradient button used for START.
     *
     * @param text   Label.
     * @param topCol Gradient top colour.
     * @param botCol Gradient bottom colour.
     */
    private JButton buildGradientButton(String text, Color topCol, Color botCol) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                Color top = isEnabled() ? topCol : C_TEXT_DIM;
                Color bot = isEnabled() ? botCol : new Color(0x3D3D3D);

                g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bot));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                if (getModel().isRollover() && isEnabled()) {
                    g2.setColor(new Color(255, 255, 255, 28));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setForeground(C_BG);
        btn.setFont(F_BTN);
        btn.setPreferredSize(new Dimension(120, 34));
        styleButtonBase(btn);
        return btn;
    }

    /** Applies the common baseline style shared by all custom buttons. */
    private static void styleButtonBase(JButton btn) {
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    // ── Pulse animation ───────────────────────────────────────────────────────

    /**
     * Starts a 50 ms Swing Timer that drives the breathing animation of the status
     * dot.
     * When the engine is idle the dot is dim; when running it cycles between
     * 30–100% opacity.
     */
    private void startPulseAnimation() {
        new Timer(50, e -> {
            if (!overlayRunning) {
                // Idle: keep dot dim and static
                pulseAlpha = 0.35f;
            } else {
                // Running: smooth breath effect
                if (pulseFading) {
                    pulseAlpha -= 0.04f;
                    if (pulseAlpha <= 0.3f) {
                        pulseAlpha = 0.3f;
                        pulseFading = false;
                    }
                } else {
                    pulseAlpha += 0.04f;
                    if (pulseAlpha >= 1.0f) {
                        pulseAlpha = 1.0f;
                        pulseFading = true;
                    }
                }
            }
            if (pulseDot != null)
                pulseDot.repaint();
        }).start();
    }

    // ── Stream redirect ───────────────────────────────────────────────────────

    /**
     * Replaces {@link System#out} and {@link System#err} with tee streams that
     * forward each byte to the original console <em>and</em> buffer complete lines
     * for display in the log pane with automatic colour coding.
     */
    private void redirectStreams() {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(new TeeOutputStream(origOut, false), true));
        System.setErr(new PrintStream(new TeeOutputStream(origErr, true), true));
    }

    // ── Engine lifecycle ──────────────────────────────────────────────────────

    /**
     * Bootstraps the full cheat engine on a background thread when START is
     * clicked.
     * <ol>
     * <li>Loads CS2 offsets (network or fallback).</li>
     * <li>Registers cheat modules (idempotent on repeated START).</li>
     * <li>Starts the fast position thread and slow data thread via
     * {@link MemoryLoop}.</li>
     * <li>Calls {@code Application.launch()} which blocks until the overlay
     * closes.</li>
     * </ol>
     * All UI mutations are dispatched to the EDT.
     */
    private void onStart() {
        if (overlayRunning)
            return;
        startBtn.setEnabled(false);
        setStatus("Initialising...", C_WARN);

        new Thread(() -> {
            try {
                log("INFO", "Loading CS2 offset tables...");
                CS2Offsets.load();

                // Register modules only once — guard against repeated START clicks.
                if (ModuleManager.getModules().isEmpty()) {
                    ModuleManager.registerModule(new ESPModule());
                    ModuleManager.registerModule(new RadarHackModule());
                    ModuleManager.registerModule(new AimbotModule());
                    ModuleManager.registerModule(new SilentAimModule());
                    ModuleManager.registerModule(new NoSpreadModule());
                    ModuleManager.registerModule(new TriggerBotModule());
                    ModuleManager.registerModule(new BunnyHopModule());
                    ModuleManager.registerModule(new BombTimerModule());
                    ModuleManager.registerModule(new SpectatorListModule());
                    ModuleManager.registerModule(new CrosshairOverlayModule());
                    ModuleManager.registerModule(new VisRayDebugModule());
                }

                // Restore user's last saved configuration before starting the engine.
                log("INFO", "Loading saved configuration...");
                ConfigManager.load();

                log("INFO", "Starting background memory threads...");
                MemoryLoop.start();

                SwingUtilities.invokeLater(() -> {
                    overlayRunning = true;
                    stopBtn.setEnabled(true);
                    setStatus("Running — scanning for CS2...", C_SUCCESS);
                });

                log("INFO", "Launching overlay window...");
                // Blocks this thread until the overlay GLFW window is closed.
                Application.launch(new OverlayWindow());

                // Overlay has been closed — clean up.
                onStopped();

            } catch (Exception ex) {
                System.err.println("[Launcher] Fatal start error: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    overlayRunning = false;
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    setStatus("Error — see logs below", C_ERROR);
                });
            }
        }, "Athenis-Engine").start();
    }

    /**
     * Requests a graceful shutdown of the memory threads and the overlay window
     * when STOP is clicked. The actual cleanup and UI reset happen in
     * {@link #onStopped()}
     * once the overlay's blocking {@code Application.launch()} call returns.
     */
    private void onStop() {
        log("INFO", "Stop requested — halting memory thread...");
        // Persist settings before the overlay is torn down so nothing is lost
        // even if the overlay window closes before postRun() fully executes.
        ConfigManager.save();
        MemoryLoop.stop();
        OverlayWindow.requestClose(); // signals the GLFW window to close
        setStatus("Stopping...", C_WARN);
        stopBtn.setEnabled(false);
    }

    /**
     * Called on the background engine thread after {@code Application.launch()}
     * returns.
     * Resets all UI state so the user can press START again.
     */
    private void onStopped() {
        MemoryLoop.stop();
        overlayRunning = false;
        SwingUtilities.invokeLater(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            setStatus("Idle — stopped", C_TEXT_DIM);
            log("INFO", "Engine stopped. Ready to restart.");
        });
    }

    /**
     * Updates the status bar label and dot colour. Safe to call from any thread.
     */
    private void setStatus(String text, Color color) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        } else {
            SwingUtilities.invokeLater(() -> setStatus(text, color));
        }
    }

    // ── Coloured log output ───────────────────────────────────────────────────

    /**
     * Appends a timestamped, syntax-highlighted line to the log pane.
     * Safe to call from any thread — marshals to the EDT when necessary.
     *
     * @param level  Log level ("INFO", "WARN", "ERROR").
     * @param rawMsg Raw message string, optionally starting with a {@code [Tag]}
     *               prefix.
     */
    void log(String level, String rawMsg) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> log(level, rawMsg));
            return;
        }
        try {
            // Dim timestamp
            appendStyled("[" + LocalTime.now().format(TIME_FMT) + "] ", C_TEXT_DIM, false);

            // If the message already carries a [Tag] prefix, colour that separately
            // from the body so e.g. "[CS2Memory] Found..." shows a distinct teal tag.
            if (rawMsg.startsWith("[") && rawMsg.indexOf(']') > 0) {
                int end = rawMsg.indexOf(']') + 1;
                String tag = rawMsg.substring(0, end);
                String rest = rawMsg.substring(end).stripLeading();
                appendStyled(tag + " ", C_TAG, true);
                appendStyled(rest + "\n", resolveColor(level, rest), false);
            } else {
                appendStyled(rawMsg + "\n", resolveColor(level, rawMsg), false);
            }

            // Always scroll to the newest line
            logPane.setCaretPosition(logDoc.getLength());
        } catch (Exception ignored) {
        }
    }

    /**
     * Picks the foreground colour for a log message based on stream level and
     * keywords.
     *
     * @param level Log level ("INFO", "WARN", "ERROR", …).
     * @param msg   Message body (without the [Tag] prefix).
     * @return A {@link Color} appropriate for the content.
     */
    private Color resolveColor(String level, String msg) {
        if ("ERROR".equalsIgnoreCase(level))
            return C_ERROR;
        String lo = msg.toLowerCase();
        if (lo.contains("error") || lo.contains("fail") || lo.contains("exception"))
            return C_ERROR;
        if (lo.contains("warn"))
            return C_WARN;
        if (lo.contains("success") || lo.contains("attached") || lo.contains("found")
                || lo.contains("registered") || lo.contains("ready"))
            return C_SUCCESS;
        if (lo.contains("0x") || lo.contains("pid") || lo.contains("base"))
            return C_TEAL;
        return C_TEXT;
    }

    /**
     * Inserts {@code text} into the styled log document with the given colour and
     * weight.
     */
    private void appendStyled(String text, Color color, boolean bold) {
        try {
            Style s = logDoc.addStyle(null, null);
            StyleConstants.setForeground(s, color);
            StyleConstants.setBold(s, bold);
            StyleConstants.setFontFamily(s, "Consolas");
            StyleConstants.setFontSize(s, 12);
            logDoc.insertString(logDoc.getLength(), text, s);
        } catch (BadLocationException ignored) {
        }
    }

    // ── TeeOutputStream ───────────────────────────────────────────────────────

    /**
     * A line-buffering {@link OutputStream} that forwards every byte to the
     * original delegate stream and additionally dispatches complete lines to
     * the launcher log pane.
     * <p>
     * Buffers bytes in an internal {@link StringBuilder} and flushes to the
     * log pane whenever a newline character is encountered, ensuring that every
     * {@link System#out} / {@link System#err} call produces exactly one log entry.
     */
    private class TeeOutputStream extends OutputStream {

        private final PrintStream delegate;
        private final boolean isErrorStream;
        private final StringBuilder buf = new StringBuilder(128);

        /**
         * @param delegate      The original stream to forward bytes to.
         * @param isErrorStream {@code true} for {@code stderr}; used to assign ERROR
         *                      level.
         */
        TeeOutputStream(PrintStream delegate, boolean isErrorStream) {
            this.delegate = delegate;
            this.isErrorStream = isErrorStream;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            synchronized (buf) {
                buf.append((char) b);
                if (b == '\n')
                    drainBuffer();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            synchronized (buf) {
                buf.append(new String(b, off, len));
                // Flush each complete line so multi-line writes appear individually.
                int nl;
                while ((nl = buf.indexOf("\n")) >= 0) {
                    String line = buf.substring(0, nl).stripTrailing();
                    buf.delete(0, nl + 1);
                    if (!line.isEmpty())
                        emitLine(line);
                }
            }
        }

        /** Flushes the current buffer contents as a single log entry. */
        private void drainBuffer() {
            String line = buf.toString().stripTrailing();
            buf.setLength(0);
            if (!line.isEmpty())
                emitLine(line);
        }

        /**
         * Determines the display log level from the stream origin and message content,
         * then schedules a log append on the EDT.
         */
        private void emitLine(String line) {
            String level = isErrorStream ? "ERROR" : "INFO";
            if (line.toLowerCase().contains("warn"))
                level = "WARN";
            final String finalLevel = level;
            SwingUtilities.invokeLater(() -> log(finalLevel, line));
        }
    }

    // ── Dark scrollbar ────────────────────────────────────────────────────────

    /**
     * A minimal dark-themed scroll bar UI with rounded thumb and invisible arrow
     * buttons.
     * Applied to the log pane's vertical scrollbar to match the overall colour
     * palette.
     */
    private static class DarkScrollBarUI extends BasicScrollBarUI {

        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0x3D4451);
            trackColor = C_BG;
        }

        /** Replaces the default arrow buttons with invisible zero-size stubs. */
        @Override
        protected JButton createDecreaseButton(int o) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty())
                return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isDragging ? new Color(0x585B70) : thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    private void setupSystemTray() {
        if (!SystemTray.isSupported()) {
            System.err.println("[Launcher] System tray is not supported on this platform.");
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            // Create a custom modern tray icon
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Draw a rounded square background matching C_BG/C_SURFACE
            g.setColor(C_BG);
            g.fillRoundRect(0, 0, 16, 16, 4, 4);
            
            // Draw a nice cyan accent dot in the center representing Athenis
            g.setColor(C_ACCENT); // C_ACCENT cyan
            g.fillOval(3, 3, 10, 10);
            
            // Draw a tiny specular/inner glow dot
            g.setColor(new Color(255, 255, 255, 180));
            g.fillOval(5, 5, 3, 3);
            
            g.dispose();

            PopupMenu popup = new PopupMenu();

            MenuItem openItem = new MenuItem("Open");
            openItem.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));

            MenuItem disableItem = new MenuItem("Disable");
            disableItem.addActionListener(e -> SwingUtilities.invokeLater(() -> {
                if (overlayRunning) {
                    onStop();
                } else {
                    log("INFO", "Cheat is not running.");
                }
            }));

            MenuItem exitItem = new MenuItem("Exit");
            exitItem.addActionListener(e -> {
                if (overlayRunning) {
                    onStop();
                }
                System.exit(0);
            });

            popup.add(openItem);
            popup.add(disableItem);
            popup.addSeparator();
            popup.add(exitItem);

            TrayIcon trayIcon = new TrayIcon(img, "Athenis CS2", popup);
            trayIcon.setImageAutoSize(true);

            // Double click on tray icon restores the window
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::restoreFromTray));

            tray.add(trayIcon);
        } catch (Exception e) {
            System.err.println("[Launcher] Failed to setup system tray: " + e.getMessage());
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }
}
