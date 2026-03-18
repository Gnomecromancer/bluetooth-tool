package com.bluetoothtool;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/**
 * Emulator tab — sends keyboard, mouse, and virtual gamepad commands
 * to the Python bridge via BleBridgeManager (WebSocket JSON protocol).
 *
 * Layout (BorderLayout):
 *   NORTH  — status bar
 *   CENTER — JTabbedPane: Keyboard | Mouse | Gamepad
 */
public class EmulatorPanel extends JPanel implements BleBridgeManager.BridgeListener {

    private final BleBridgeManager bridge;

    // ── Status bar ────────────────────────────────────────────────────────────
    private JLabel statusLabel;

    // ── Gamepad state labels (updated from listener) ──────────────────────────
    private JLabel gamepadStatusLabel;

    // ─────────────────────────────────────────────────────────────────────────

    public EmulatorPanel(BleBridgeManager bridge) {
        super(new BorderLayout(0, 4));
        this.bridge = bridge;
        buildUI();
        bridge.addListener(this);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        // NORTH: status bar
        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setForeground(Color.GRAY);
        statusLabel.setBorder(new EmptyBorder(4, 8, 2, 8));
        add(statusLabel, BorderLayout.NORTH);

        // CENTER: tabbed pane
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Keyboard", buildKeyboardTab());
        tabs.addTab("Mouse",    buildMouseTab());
        tabs.addTab("Gamepad",  buildGamepadTab());
        add(tabs, BorderLayout.CENTER);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Keyboard tab
    // ══════════════════════════════════════════════════════════════════════════

    private JPanel buildKeyboardTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(buildTypeTextSection());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildPressKeySection());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildCommonKeysSection());
        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildTypeTextSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Type Text"));

        JTextArea textArea = new JTextArea(3, 30);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane areaScroll = new JScrollPane(textArea);

        SpinnerNumberModel intervalModel = new SpinnerNumberModel(0, 0, 500, 10);
        JSpinner intervalSpinner = new JSpinner(intervalModel);
        JLabel intervalLabel = new JLabel("Interval (ms):");

        JPanel controlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controlRow.add(intervalLabel);
        controlRow.add(intervalSpinner);

        JButton typeBtn = new JButton("Type");
        typeBtn.addActionListener(e -> {
            String text = textArea.getText();
            if (text.isEmpty()) return;
            double intervalSec = ((Number) intervalSpinner.getValue()).intValue() / 1000.0;
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "emu_key_type");
            cmd.put("text", text);
            cmd.put("interval", intervalSec);
            bridge.send(cmd);
        });

        JPanel bottomRow = new JPanel(new BorderLayout(4, 0));
        bottomRow.add(controlRow, BorderLayout.WEST);
        bottomRow.add(typeBtn, BorderLayout.EAST);

        p.add(areaScroll, BorderLayout.CENTER);
        p.add(bottomRow, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildPressKeySection() {
        JPanel p = new JPanel(new BorderLayout(4, 6));
        p.setBorder(new TitledBorder("Press Key / Hotkey"));

        // Key name row
        JTextField keyField = new JTextField(20);
        keyField.setToolTipText("e.g. enter, ctrl, a, f5");
        // Placeholder text via a simple focus listener
        setPlaceholder(keyField, "e.g. enter, ctrl, a, f5");

        JButton pressBtn   = new JButton("Press");
        JButton holdBtn    = new JButton("Hold");
        JButton releaseBtn = new JButton("Release");

        pressBtn.addActionListener(e -> {
            String key = keyField.getText().trim();
            if (key.isEmpty() || key.equals("e.g. enter, ctrl, a, f5")) return;
            bridge.send(new JSONObject().put("cmd", "emu_key_press").put("key", key));
        });
        holdBtn.addActionListener(e -> {
            String key = keyField.getText().trim();
            if (key.isEmpty() || key.equals("e.g. enter, ctrl, a, f5")) return;
            bridge.send(new JSONObject().put("cmd", "emu_key_down").put("key", key));
        });
        releaseBtn.addActionListener(e -> {
            String key = keyField.getText().trim();
            if (key.isEmpty() || key.equals("e.g. enter, ctrl, a, f5")) return;
            bridge.send(new JSONObject().put("cmd", "emu_key_up").put("key", key));
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(pressBtn);
        btnRow.add(holdBtn);
        btnRow.add(releaseBtn);

        JPanel keyRow = new JPanel(new BorderLayout(4, 0));
        keyRow.add(keyField, BorderLayout.CENTER);
        keyRow.add(btnRow, BorderLayout.EAST);

        // Separator
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);

        // Hotkey row
        JTextField hotkeyField = new JTextField(20);
        setPlaceholder(hotkeyField, "e.g. ctrl shift s");

        JButton hotkeyBtn = new JButton("Send Hotkey");
        hotkeyBtn.addActionListener(e -> {
            String combo = hotkeyField.getText().trim();
            if (combo.isEmpty() || combo.equals("e.g. ctrl shift s")) return;
            String[] parts = combo.split("\\s+");
            JSONArray keysArr = new JSONArray();
            for (String part : parts) {
                keysArr.put(part);
            }
            bridge.send(new JSONObject().put("cmd", "emu_key_hotkey").put("keys", keysArr));
        });

        JPanel hotkeyRow = new JPanel(new BorderLayout(4, 0));
        hotkeyRow.add(hotkeyField, BorderLayout.CENTER);
        hotkeyRow.add(hotkeyBtn, BorderLayout.EAST);

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.add(keyRow);
        inner.add(Box.createVerticalStrut(4));
        inner.add(sep);
        inner.add(Box.createVerticalStrut(4));
        inner.add(hotkeyRow);

        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildCommonKeysSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Common Keys"));

        // Each entry: display label, pyautogui key name
        String[][] keys = {
            {"Enter",       "enter"},
            {"Tab",         "tab"},
            {"Escape",      "escape"},
            {"Space",       "space"},
            {"Backspace",   "backspace"},
            {"Delete",      "delete"},
            {"Up",          "up"},
            {"Down",        "down"},
            {"Left",        "left"},
            {"Right",       "right"},
            {"Home",        "home"},
            {"End",         "end"},
            {"Page Up",     "pageup"},
            {"Page Down",   "pagedown"},
            {"Print Screen","printscreen"},
        };

        JPanel grid = new JPanel(new GridLayout(0, 5, 4, 4));
        for (String[] entry : keys) {
            String label   = entry[0];
            String keyName = entry[1];
            JButton btn = new JButton(label);
            btn.setMargin(new Insets(2, 4, 2, 4));
            btn.addActionListener(e ->
                bridge.send(new JSONObject().put("cmd", "emu_key_press").put("key", keyName))
            );
            grid.add(btn);
        }

        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Mouse tab
    // ══════════════════════════════════════════════════════════════════════════

    private JPanel buildMouseTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(buildMouseMoveSection());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildMouseClickSection());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildMouseScrollSection());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildMouseHoldSection());
        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildMouseMoveSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Move"));

        SpinnerNumberModel xModel = new SpinnerNumberModel(0, 0, 9999, 1);
        SpinnerNumberModel yModel = new SpinnerNumberModel(0, 0, 9999, 1);
        JSpinner xSpinner = new JSpinner(xModel);
        JSpinner ySpinner = new JSpinner(yModel);

        JCheckBox relativeBox = new JCheckBox("Relative movement");

        SpinnerNumberModel durModel = new SpinnerNumberModel(0, 0, 2000, 50);
        JSpinner durSpinner = new JSpinner(durModel);

        JButton moveBtn = new JButton("Move");
        moveBtn.addActionListener(e -> {
            int x = ((Number) xSpinner.getValue()).intValue();
            int y = ((Number) ySpinner.getValue()).intValue();
            boolean relative = relativeBox.isSelected();
            double duration = ((Number) durSpinner.getValue()).intValue() / 1000.0;
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "emu_mouse_move");
            cmd.put("x", x);
            cmd.put("y", y);
            cmd.put("relative", relative);
            cmd.put("duration", duration);
            bridge.send(cmd);
        });

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE;
        fields.add(new JLabel("X:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 0.5;
        fields.add(xSpinner, gc);

        gc.gridx = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(new JLabel("Y:"), gc);
        gc.gridx = 3; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 0.5;
        fields.add(ySpinner, gc);

        gc.gridx = 0; gc.gridy = 1; gc.gridwidth = 2; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(relativeBox, gc);

        gc.gridx = 2; gc.gridwidth = 1;
        fields.add(new JLabel("Duration (ms):"), gc);
        gc.gridx = 3; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 0.5;
        fields.add(durSpinner, gc);

        p.add(fields, BorderLayout.CENTER);
        p.add(moveBtn, BorderLayout.EAST);
        return p;
    }

    private JPanel buildMouseClickSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Click"));

        // 3 columns: Left, Right, Middle
        // Each column has a normal click + double click button
        String[] buttons = {"left", "right", "middle"};
        String[] labels  = {"Left Click", "Right Click", "Middle Click"};

        JPanel grid = new JPanel(new GridLayout(2, 3, 6, 4));

        for (String[] entry : new String[][]{{"left","Left Click"},{"right","Right Click"},{"middle","Middle Click"}}) {
            final String btnName  = entry[0];
            final String btnLabel = entry[1];
            JButton btn = new JButton(btnLabel);
            btn.addActionListener(e ->
                bridge.send(new JSONObject().put("cmd", "emu_mouse_click").put("button", btnName).put("double", false))
            );
            grid.add(btn);
        }
        for (String[] entry : new String[][]{{"left","Double Left"},{"right","Double Right"},{"middle","Double Middle"}}) {
            final String btnName  = entry[0];
            final String btnLabel = entry[1];
            JButton btn = new JButton(btnLabel);
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 10f));
            btn.addActionListener(e ->
                bridge.send(new JSONObject().put("cmd", "emu_mouse_click").put("button", btnName).put("double", true))
            );
            grid.add(btn);
        }

        p.add(grid, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildMouseScrollSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Scroll"));

        JSlider scrollSlider = new JSlider(SwingConstants.HORIZONTAL, -10, 10, 0);
        scrollSlider.setMajorTickSpacing(5);
        scrollSlider.setMinorTickSpacing(1);
        scrollSlider.setPaintTicks(true);

        JLabel valueLabel = new JLabel("0");
        valueLabel.setPreferredSize(new Dimension(30, 16));
        scrollSlider.addChangeListener(e -> valueLabel.setText(String.valueOf(scrollSlider.getValue())));

        JButton scrollBtn = new JButton("Scroll");
        scrollBtn.addActionListener(e -> {
            int dy = scrollSlider.getValue();
            bridge.send(new JSONObject().put("cmd", "emu_mouse_scroll").put("dx", 0).put("dy", dy));
            scrollSlider.setValue(0);
        });

        JLabel noteLabel = new JLabel("(positive = scroll up)");
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.ITALIC, 10f));
        noteLabel.setForeground(Color.GRAY);

        JPanel sliderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sliderRow.add(new JLabel("Vertical:"));
        sliderRow.add(scrollSlider);
        sliderRow.add(valueLabel);
        sliderRow.add(scrollBtn);

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.add(sliderRow);
        inner.add(noteLabel);

        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildMouseHoldSection() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(new TitledBorder("Hold / Release"));

        JButton holdLeft    = new JButton("Hold Left");
        JButton releaseLeft = new JButton("Release Left");
        JButton holdRight   = new JButton("Hold Right");
        JButton releaseRight = new JButton("Release Right");

        holdLeft.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_mouse_down").put("button", "left")));
        releaseLeft.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_mouse_up").put("button", "left")));
        holdRight.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_mouse_down").put("button", "right")));
        releaseRight.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_mouse_up").put("button", "right")));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.add(holdLeft);
        btnRow.add(releaseLeft);
        btnRow.add(holdRight);
        btnRow.add(releaseRight);

        p.add(btnRow, BorderLayout.CENTER);
        return p;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gamepad tab
    // ══════════════════════════════════════════════════════════════════════════

    private JPanel buildGamepadTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(buildGamepadControllerSection(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildGamepadButtonsPanel(), buildGamepadAxesPanel());
        split.setDividerLocation(300);
        split.setResizeWeight(0.4);
        panel.add(split, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildGamepadControllerSection() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setBorder(new TitledBorder("Controller"));

        JRadioButton xboxBtn = new JRadioButton("Xbox 360", true);
        JRadioButton ds4Btn  = new JRadioButton("DualShock 4", false);
        ButtonGroup  group   = new ButtonGroup();
        group.add(xboxBtn);
        group.add(ds4Btn);

        JButton connectBtn    = new JButton("Connect");
        JButton disconnectBtn = new JButton("Disconnect");
        JButton updateBtn     = new JButton("Update (Send Report)");

        gamepadStatusLabel = new JLabel("Not connected");
        gamepadStatusLabel.setFont(gamepadStatusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        gamepadStatusLabel.setForeground(Color.GRAY);

        connectBtn.addActionListener(e -> {
            String type = ds4Btn.isSelected() ? "ds4" : "xbox360";
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_connect").put("type", type));
        });
        disconnectBtn.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_disconnect")));
        updateBtn.addActionListener(e ->
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update")));

        p.add(xboxBtn);
        p.add(ds4Btn);
        p.add(connectBtn);
        p.add(disconnectBtn);
        p.add(updateBtn);
        p.add(gamepadStatusLabel);
        return p;
    }

    private JPanel buildGamepadButtonsPanel() {
        JPanel outer = new JPanel(new BorderLayout(4, 6));
        outer.setBorder(new TitledBorder("Buttons"));

        // Face buttons (A=green, B=red, X=blue, Y=yellow) + shoulder/misc
        String[][] faceButtons = {
            {"A",     "A",     "009900"},
            {"B",     "B",     "CC0000"},
            {"X",     "X",     "0044CC"},
            {"Y",     "Y",     "CC8800"},
            {"LB",    "LB",    null},
            {"RB",    "RB",    null},
            {"START", "START", null},
            {"BACK",  "BACK",  null},
            {"LS",    "LS",    null},
            {"RS",    "RS",    null},
        };

        JPanel faceGrid = new JPanel(new GridLayout(0, 4, 4, 4));
        for (String[] entry : faceButtons) {
            final String btnId    = entry[0];
            final String btnLabel = entry[1];
            final String color    = entry[2];
            JButton btn = new JButton(btnLabel);
            if (color != null) {
                btn.setForeground(Color.decode("#" + color));
                btn.setFont(btn.getFont().deriveFont(Font.BOLD));
            }
            btn.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    bridge.send(new JSONObject().put("cmd", "emu_gamepad_button")
                            .put("button", btnId).put("pressed", true));
                }
                @Override
                public void mouseReleased(MouseEvent e) {
                    bridge.send(new JSONObject().put("cmd", "emu_gamepad_button")
                            .put("button", btnId).put("pressed", false));
                }
            });
            faceGrid.add(btn);
        }

        // DPad (3x3 grid with blank center)
        JPanel dpadPanel = new JPanel(new BorderLayout(4, 4));
        dpadPanel.setBorder(new TitledBorder("DPad"));

        JPanel dpadGrid = new JPanel(new GridLayout(3, 3, 2, 2));
        String[][] dpadLayout = {
            {null,        null,       null       },
            {"DPAD_LEFT", "DPAD_UP",  "DPAD_RIGHT"},
            {null,        "DPAD_DOWN",null       },
        };
        // Row 0: blank, UP, blank
        dpadGrid.add(new JLabel());
        addDpadButton(dpadGrid, "DPAD_UP",    "▲");
        dpadGrid.add(new JLabel());
        // Row 1: LEFT, blank, RIGHT
        addDpadButton(dpadGrid, "DPAD_LEFT",  "◀");
        dpadGrid.add(new JLabel());
        addDpadButton(dpadGrid, "DPAD_RIGHT", "▶");
        // Row 2: blank, DOWN, blank
        dpadGrid.add(new JLabel());
        addDpadButton(dpadGrid, "DPAD_DOWN",  "▼");
        dpadGrid.add(new JLabel());

        dpadPanel.add(dpadGrid, BorderLayout.CENTER);

        outer.add(faceGrid, BorderLayout.NORTH);
        outer.add(dpadPanel, BorderLayout.CENTER);
        return outer;
    }

    private void addDpadButton(JPanel grid, String btnId, String label) {
        JButton btn = new JButton(label);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 14f));
        btn.setMargin(new Insets(2, 4, 2, 4));
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                bridge.send(new JSONObject().put("cmd", "emu_gamepad_button")
                        .put("button", btnId).put("pressed", true));
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                bridge.send(new JSONObject().put("cmd", "emu_gamepad_button")
                        .put("button", btnId).put("pressed", false));
            }
        });
        grid.add(btn);
    }

    private JPanel buildGamepadAxesPanel() {
        JPanel outer = new JPanel(new BorderLayout(4, 6));
        outer.setBorder(new TitledBorder("Axes & Triggers"));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));

        // Left stick
        JSlider lsX = makeAxisSlider();
        JSlider lsY = makeAxisSlider();
        lsX.addChangeListener(e -> {
            float x = lsX.getValue() / 100.0f;
            float y = lsY.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_left_stick").put("x", x).put("y", y));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });
        lsY.addChangeListener(e -> {
            float x = lsX.getValue() / 100.0f;
            float y = lsY.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_left_stick").put("x", x).put("y", y));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });

        JButton lsReset = new JButton("Reset LS");
        lsReset.addActionListener(e -> { lsX.setValue(0); lsY.setValue(0); });

        inner.add(makeSliderRow("LS X", lsX));
        inner.add(makeSliderRow("LS Y", lsY));
        JPanel lsResetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        lsResetRow.add(lsReset);
        inner.add(lsResetRow);
        inner.add(Box.createVerticalStrut(8));

        // Right stick
        JSlider rsX = makeAxisSlider();
        JSlider rsY = makeAxisSlider();
        rsX.addChangeListener(e -> {
            float x = rsX.getValue() / 100.0f;
            float y = rsY.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_right_stick").put("x", x).put("y", y));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });
        rsY.addChangeListener(e -> {
            float x = rsX.getValue() / 100.0f;
            float y = rsY.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_right_stick").put("x", x).put("y", y));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });

        JButton rsReset = new JButton("Reset RS");
        rsReset.addActionListener(e -> { rsX.setValue(0); rsY.setValue(0); });

        inner.add(makeSliderRow("RS X", rsX));
        inner.add(makeSliderRow("RS Y", rsY));
        JPanel rsResetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rsResetRow.add(rsReset);
        inner.add(rsResetRow);
        inner.add(Box.createVerticalStrut(8));

        // Left Trigger
        JSlider lt = makeTriggerSlider();
        lt.addChangeListener(e -> {
            float value = lt.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_trigger").put("side", "left").put("value", value));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });
        inner.add(makeSliderRow("L2/LT", lt));
        inner.add(Box.createVerticalStrut(4));

        // Right Trigger
        JSlider rt = makeTriggerSlider();
        rt.addChangeListener(e -> {
            float value = rt.getValue() / 100.0f;
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_trigger").put("side", "right").put("value", value));
            bridge.send(new JSONObject().put("cmd", "emu_gamepad_update"));
        });
        inner.add(makeSliderRow("R2/RT", rt));

        inner.add(Box.createVerticalGlue());

        outer.add(new JScrollPane(inner), BorderLayout.CENTER);
        return outer;
    }

    // ── Slider factories ──────────────────────────────────────────────────────

    private JSlider makeAxisSlider() {
        JSlider s = new JSlider(SwingConstants.HORIZONTAL, -100, 100, 0);
        s.setMajorTickSpacing(50);
        s.setMinorTickSpacing(10);
        s.setPaintTicks(true);
        return s;
    }

    private JSlider makeTriggerSlider() {
        JSlider s = new JSlider(SwingConstants.HORIZONTAL, 0, 100, 0);
        s.setMajorTickSpacing(50);
        s.setMinorTickSpacing(10);
        s.setPaintTicks(true);
        return s;
    }

    private JPanel makeSliderRow(String label, JSlider slider) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBorder(new EmptyBorder(2, 4, 2, 4));
        JLabel lbl = new JLabel(label);
        lbl.setPreferredSize(new Dimension(50, 16));
        JLabel valLabel = new JLabel(String.valueOf(slider.getValue()));
        valLabel.setPreferredSize(new Dimension(34, 16));
        slider.addChangeListener(e -> valLabel.setText(String.valueOf(slider.getValue())));
        row.add(lbl,      BorderLayout.WEST);
        row.add(slider,   BorderLayout.CENTER);
        row.add(valLabel, BorderLayout.EAST);
        return row;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BridgeListener
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void onEvent(JSONObject event) {
        SwingUtilities.invokeLater(() -> handleEvent(event));
    }

    private void handleEvent(JSONObject ev) {
        String type = ev.optString("event", "");

        if ("emu_ok".equals(type)) {
            String cmd = ev.optString("cmd", "");
            statusLabel.setForeground(Color.GRAY);
            statusLabel.setText("OK: " + cmd);

        } else if ("emu_gamepad_connected".equals(type)) {
            String gpType = ev.optString("type", "");
            gamepadStatusLabel.setForeground(new Color(0, 140, 0));
            gamepadStatusLabel.setText("Connected (" + gpType + ")");
            statusLabel.setForeground(Color.GRAY);
            statusLabel.setText("Gamepad connected: " + gpType);

        } else if ("emu_gamepad_disconnected".equals(type)) {
            gamepadStatusLabel.setForeground(Color.GRAY);
            gamepadStatusLabel.setText("Not connected");
            statusLabel.setForeground(Color.GRAY);
            statusLabel.setText("Gamepad disconnected");

        } else if ("error".equals(type)) {
            String msg = ev.optString("message", "");
            statusLabel.setForeground(new Color(200, 0, 0));
            statusLabel.setText("Error: " + msg);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Simulates placeholder text on a JTextField using a focus listener.
     * The placeholder is shown in gray when the field is empty and unfocused.
     */
    private void setPlaceholder(JTextField field, String placeholder) {
        field.setForeground(Color.GRAY);
        field.setText(placeholder);

        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (field.getText().equals(placeholder)) {
                    field.setText("");
                    field.setForeground(UIManager.getColor("TextField.foreground"));
                }
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (field.getText().isEmpty()) {
                    field.setForeground(Color.GRAY);
                    field.setText(placeholder);
                }
            }
        });
    }
}
