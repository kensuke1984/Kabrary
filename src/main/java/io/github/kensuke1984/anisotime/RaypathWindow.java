package io.github.kensuke1984.anisotime;

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * @author Kensuke Konishi
 * @version 0.1.1
 */
class RaypathWindow extends javax.swing.JFrame {

    /**
     * 2016/8/30
     */
    private static final long serialVersionUID = -1629297386309740844L;

    private final RaypathPanel PANEL;
    private ANISOtimeGUI GUI;

    RaypathWindow(ANISOtimeGUI gui, RaypathPanel raypathPanel) {
        super("Raypath");
        PANEL = raypathPanel;
        GUI = gui;
        initComponents();
    }

    void addPath(double[] x, double[] y) {
        PANEL.addPath(x, y);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">
    private void initComponents() {

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        // getContentPane().setLayout(layout);
        layout.setHorizontalGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(javax.swing.GroupLayout.Alignment.TRAILING,
                        layout.createSequentialGroup().addContainerGap().addComponent(PANEL).addContainerGap()));
        layout.setVerticalGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(javax.swing.GroupLayout.Alignment.TRAILING,
                        layout.createSequentialGroup().addContainerGap().addComponent(PANEL).addContainerGap()));

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int size = getSize().width < getSize().height ? getSize().width : getSize().height;
                setSize(size, size);
            }
        });
//		pack();
        setSize(700, 700);
        setLocation(GUI.getX() - 700, GUI.getY());

    }// </editor-fold>

    void selectPath(int i) {
        PANEL.setFeatured(i);
    }

}
