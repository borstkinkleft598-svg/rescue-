package gis2.scenario;

import gis2.GisScenario;
import gis2.ScenarioException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileFilter;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

import lombok.Getter;
import maps.MapException;
import maps.MapReader;
import maps.gml.GMLMap;
import maps.gml.GMLRefuge;
import maps.gml.view.DecoratorOverlay;
import maps.gml.view.FilledShapeDecorator;
import maps.gml.view.GMLMapViewer;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import rescuecore2.config.Config;

import static gis2.scenario.EntityColours.AMBULANCE_CENTRE_COLOUR;
import static gis2.scenario.EntityColours.FIRE_COLOUR;
import static gis2.scenario.EntityColours.FIRE_STATION_COLOUR;
import static gis2.scenario.EntityColours.GAS_STATION_COLOUR;
import static gis2.scenario.EntityColours.HYDRANT_COLOUR;
import static gis2.scenario.EntityColours.POLICE_OFFICE_COLOUR;
import static gis2.scenario.EntityColours.REFUGE_COLOUR;

/**
 * A component for editing scenarios.
 */
public class ScenarioEditor extends JPanel {

  private static final int PREFERRED_WIDTH  = 1280;
  private static final int PREFERRED_HEIGHT =  800;

  @Getter private GMLMap map;
  @Getter private GisScenario scenario;
  private Tool currentTool;

  @Getter private final GMLMapViewer viewer;
  private final JLabel statusLabel;

  private final DecoratorOverlay fireOverlay;
  private final DecoratorOverlay centreOverlay;

  private boolean changed;

  private final UndoManager undoManager;
  private transient Action undoAction;
  private transient Action redoAction;

  private File baseDir;
  private File saveFile;

  private final FilledShapeDecorator fireDecorator         = new FilledShapeDecorator(FIRE_COLOUR, null, null);
  private final FilledShapeDecorator fireStationDecorator  = new FilledShapeDecorator(FIRE_STATION_COLOUR, null, null);
  private final FilledShapeDecorator policeOfficeDecorator = new FilledShapeDecorator(POLICE_OFFICE_COLOUR, null, null);

  private final FilledShapeDecorator ambulanceCentreDecorator = new FilledShapeDecorator(AMBULANCE_CENTRE_COLOUR, null, null);
  private final FilledShapeDecorator refugeDecorator          = new FilledShapeDecorator(REFUGE_COLOUR, null, null);
  private final FilledShapeDecorator gasStationDecorator      = new FilledShapeDecorator(GAS_STATION_COLOUR, null, null);
  private final FilledShapeDecorator hydrantDecorator         = new FilledShapeDecorator(null, HYDRANT_COLOUR, null);

  /**
   * Construct a new ScenarioEditor.
   *
   * @param menuBar
   *   The menu bar to add menus to.
   */
  public ScenarioEditor(JMenuBar menuBar) {
    this(menuBar, null, null);
  }


  /**
   * Construct a new ScenarioEditor.
   *
   * @param menuBar
   *   The menu bar to add menus to.
   * @param map
   *   The GMLMap to view.
   * @param scenario
   *   The scenario to edit.
   */
  public ScenarioEditor(JMenuBar menuBar, GMLMap map, GisScenario scenario) {
    super(new BorderLayout());
    this.map = map;
    this.scenario = scenario;


    fireOverlay = new DecoratorOverlay();
    centreOverlay = new DecoratorOverlay();
    AgentOverlay agentOverlay = new AgentOverlay(this);

    viewer = new GMLMapViewer(map);
    viewer.setPaintNodes(false);
    viewer.addOverlay(fireOverlay);
    viewer.addOverlay(centreOverlay);
    viewer.addOverlay(agentOverlay);
    viewer.setBackground(Color.GRAY);
    viewer.getPanZoomListener().setPanOnRightMouse();

    statusLabel = new JLabel("Status");

    undoManager = new UndoManager();

    changed = false;

    JToolBar fileToolbar = new JToolBar("File");
    JToolBar editToolbar = new JToolBar("Edit");
    JToolBar functionsToolbar = new JToolBar("Functions");
    JMenu fileMenu = new JMenu("File", false);
    JMenu editMenu = new JMenu("Edit", false);
    JMenu toolsMenu = new JMenu("Tools", false);
    JMenu functionsMenu = new JMenu("Functions", false);

    createFileActions(fileMenu, fileToolbar);
    createEditActions(editMenu, editToolbar);
    createFunctionActions(functionsMenu, functionsToolbar);

    JToolBar toolBar = new JToolBar();
    toolBar.setFloatable(false);
    toolBar.setLayout(new FlowLayout(FlowLayout.LEFT));
    toolBar.add(fileToolbar);
    toolBar.add(editToolbar);
    toolBar.add(functionsToolbar);

    JToolBar toolPanel = createToolPanel();
    createToolActions(toolsMenu, toolPanel);

    JScrollPane toolBarScroll   = createToolBarScroll(toolBar);
    JScrollPane toolPanelScroll = createToolPanelScroll(toolPanel);

    add(viewer, BorderLayout.CENTER);
    add(toolBarScroll, BorderLayout.NORTH);
    add(toolPanelScroll, BorderLayout.WEST);
    add(statusLabel, BorderLayout.SOUTH);
    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(toolsMenu);
    menuBar.add(functionsMenu);

    baseDir = new File(System.getProperty("user.dir"));
    saveFile = null;
  }

  private JToolBar createToolPanel() {
    JToolBar toolPanel = new JToolBar("Tools");

    toolPanel.addPropertyChangeListener("ancestor", event -> {
      toolPanel.setOrientation(JToolBar.VERTICAL);
      boolean isFloating = toolPanel.getParent() instanceof JPanel;

      if (isFloating) {
        for (Component component : toolPanel.getComponents()) {
          if (!(component instanceof JToggleButton toggle)) continue;
          Action action = toggle.getAction();
          if (action == null) continue;
          toggle.setText((String) action.getValue(Action.NAME));
          toggle.setMaximumSize(new Dimension(500, 30));
        }
      } else {
        for (Component component : toolPanel.getComponents()) {
          if (!(component instanceof JToggleButton toggle)) continue;
          toggle.setText("");
        }
      }
      this.revalidate();
    });

    return toolPanel;
  }

  private JScrollPane createToolBarScroll(JToolBar toolBar) {
    JScrollPane toolBarScroll = new JScrollPane(toolBar,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

    toolBarScroll.getHorizontalScrollBar().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentShown(ComponentEvent e) {
        toolBarScroll.setPreferredSize(new Dimension(PREFERRED_WIDTH, 60));
        toolBarScroll.revalidate();
      }

      @Override
      public void componentHidden(ComponentEvent e) {
        toolBarScroll.setPreferredSize(new Dimension(PREFERRED_WIDTH, 45));
        toolBarScroll.revalidate();
      }
    });

    return toolBarScroll;
  }

  private JScrollPane createToolPanelScroll(JToolBar toolPanel) {
    JScrollPane toolPanelScroll = new JScrollPane(toolPanel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

    toolPanelScroll.getVerticalScrollBar().addComponentListener(new ComponentAdapter() {
      @Override
      public void componentShown(ComponentEvent e) {
        toolPanelScroll.setPreferredSize(new Dimension(60, PREFERRED_HEIGHT));
        toolPanelScroll.revalidate();
      }

      @Override
      public void componentHidden(ComponentEvent e) {
        toolPanelScroll.setPreferredSize(new Dimension(45, PREFERRED_HEIGHT));
        toolPanelScroll.revalidate();
      }
    });

    return toolPanelScroll;
  }

  /**
   * Entry point.
   *
   * @param args
   *   Command line arguments.
   */
  public static void main(String[] args) {
    final JFrame frame = new JFrame("Scenario Editor");
    JMenuBar menuBar = new JMenuBar();
    final ScenarioEditor editor = new ScenarioEditor(menuBar);
    if (args.length > 0 && !args[0].isEmpty()) {
      try {
        editor.load(args[0]);
      } catch (CancelledByUserException e) {
        return;
      } catch (MapException | ScenarioException | rescuecore2.scenario.exceptions.ScenarioException e) {
        e.printStackTrace();
      }
    }

    frame.setJMenuBar(menuBar);
    frame.setContentPane(editor);
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.pack();
    frame.setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosing(WindowEvent e) {
        try {
          editor.close();
          frame.setVisible(false);
          frame.dispose();
          System.exit(0);
        } catch (CancelledByUserException ex) {
          frame.setVisible(true);
        }
      }
    });
    frame.setVisible(true);
  }

  /**
   * Load a map and scenario by showing a file chooser dialog.
   *
   * @throws CancelledByUserException
   *   If the user cancels
   *   the change due to
   *   unsaved changes.
   * @throws MapException
   *   If there is a
   *   problem reading the
   *   map.
   * @throws ScenarioException
   *   If there is a
   *   problem reading the
   *   scenario.
   * @throws rescuecore2.scenario.exceptions.ScenarioException
   */
  public void load() throws CancelledByUserException, MapException,
      ScenarioException, rescuecore2.scenario.exceptions.ScenarioException {
    JFileChooser chooser = new JFileChooser(baseDir);
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setFileFilter(new FileFilter() {

      @Override
      public boolean accept(File f) {
        return f.isDirectory();
      }


      @Override
      public String getDescription() {
        return "Directories";
      }
    });
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      load(chooser.getSelectedFile());
    }
  }

  /**
   * Load a map and scenario from a directory.
   *
   * @param filename
   *   The name of the file to read.
   *
   * @throws CancelledByUserException
   *   If the user cancels
   *   the change due to
   *   unsaved changes.
   * @throws MapException
   *   If there is a
   *   problem reading the
   *   map.
   * @throws ScenarioException
   *   If there is a
   *   problem reading the
   *   scenario.
   * @throws rescuecore2.scenario.exceptions.ScenarioException
   */
  public void load(String filename)
      throws CancelledByUserException, MapException, ScenarioException,
      rescuecore2.scenario.exceptions.ScenarioException {
    load(new File(filename));
  }

  /**
   * Load a map and scenario from a directory.
   *
   * @param dir
   *   The directory to read.
   *
   * @throws CancelledByUserException
   *   If the user cancels
   *   the change due to
   *   unsaved changes.
   * @throws MapException
   *   If there is a
   *   problem reading the
   *   map.
   * @throws ScenarioException
   *   If there is a
   *   problem reading the
   *   scenario.
   * @throws rescuecore2.scenario.exceptions.ScenarioException
   */
  public void load(File dir) throws CancelledByUserException, MapException,
      ScenarioException, rescuecore2.scenario.exceptions.ScenarioException {

    try (FileReader r = new FileReader(new File(dir, "scenario.xml"))) {
      GMLMap newMap = (GMLMap) MapReader.readMap(new File(dir, "map.gml"));

      SAXReader saxReader = new SAXReader();
      Document doc = saxReader.read(r);
      GisScenario newScenario = new GisScenario(doc, new Config());
      setScenario(newMap, newScenario);
      baseDir = dir;
      saveFile = new File(dir, "scenario.xml");
    } catch (IOException | DocumentException e) {
      throw new ScenarioException(e);
    }
  }

  /**
   * Set the map and scenario.
   *
   * @param newMap
   *   The new map.
   * @param newScenario
   *   The new scenario.
   *
   * @throws CancelledByUserException
   *   If the user cancels the change due to
   *   unsaved changes.
   */
  public void setScenario(GMLMap newMap, GisScenario newScenario)
      throws CancelledByUserException {
    checkForChanges();
    if (!checkScenario(newMap, newScenario)) {
      JOptionPane.showMessageDialog(null,
          "The scenario file contained errors.");
      return;
    }
    map = newMap;
    scenario = newScenario;
    changed = false;
    viewer.setMap(map);
    updateOverlays();
  }

  public void updateGMLRefuges() {
    for (int next : scenario.getRefuges()) {
      GMLRefuge refuge = new GMLRefuge(next, map.getBuilding(next).getEdges());
      refuge.setBedCapacity(scenario.getRefugeBedCapacity().get(next));
      refuge.setRefillCapacity(scenario.getRefugeRefillCapacity().get(next));
      scenario.addGMLRefuge(refuge);
    }
  }

  /**
   * Save the scenario.
   *
   * @throws ScenarioException
   *   If there is a problem saving the scenario.
   */
  public void save() throws ScenarioException {
    if (saveFile == null) {
      saveAs();
    }
    if (saveFile != null) {
      Document doc = DocumentHelper.createDocument();
      scenario.write(doc);
      try {
        if (!saveFile.exists()) {
          File parent = saveFile.getParentFile();
          if ((!parent.exists()) && (!saveFile.getParentFile().mkdirs())) {
            throw new ScenarioException(
                "Couldn't create file " + saveFile.getPath());
          }
          if (!saveFile.createNewFile()) {
            throw new ScenarioException(
                "Couldn't create file " + saveFile.getPath());
          }
        }
        XMLWriter writer = new XMLWriter(new FileOutputStream(saveFile),
            OutputFormat.createPrettyPrint());
        writer.write(doc);
        writer.flush();
        writer.close();
      } catch (IOException e) {
        throw new ScenarioException(e);
      }
      baseDir = saveFile.getParentFile();
      changed = false;
    }
  }

  /**
   * Save the scenario.
   *
   * @throws ScenarioException
   *   If there is a problem saving the scenario.
   */
  public void saveAs() throws ScenarioException {
    JFileChooser chooser = new JFileChooser(baseDir);
    if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
      saveFile = chooser.getSelectedFile();
      save();
    }
  }

  /**
   * Close the editor.
   *
   * @throws CancelledByUserException
   *   If the user cancels the close due to unsaved changes.
   */
  public void close() throws CancelledByUserException {
    checkForChanges();
  }

  /**
   * Register a change to the map.
   */
  public void setChanged() {
    changed = true;
  }

  /**
   * Register an undoable edit.
   *
   * @param edit
   *   The edit to add.
   */
  public void addEdit(UndoableEdit edit) {
    undoManager.addEdit(edit);
    undoAction.setEnabled(undoManager.canUndo());
    redoAction.setEnabled(undoManager.canRedo());
  }

  /**
   * Update the overlay views.
   */
  public void updateOverlays() {
    updateGMLRefuges();
    updateFireOverlay();
    updateCentreOverlay();
    updateAgentOverlay();
    updateStatusLabel();
    viewer.repaint();
  }

  private void checkForChanges() throws CancelledByUserException {
    if (changed) {
      switch (JOptionPane.showConfirmDialog(null,
          "The current scenario has changes. Do you want to save them?")) {
        case JOptionPane.YES_OPTION:
          try {
            save();
          } catch (ScenarioException e) {
            JOptionPane.showMessageDialog(null, e);
            throw new CancelledByUserException();
          }
          break;

        case JOptionPane.NO_OPTION:
          changed = false;
          return;

        case JOptionPane.CANCEL_OPTION:
          throw new CancelledByUserException();

        default:
          throw new RuntimeException(
              "JOptionPane.showConfirmDialog returned something weird");
      }
    }
  }

  private void createFileActions(JMenu menu, JToolBar toolbar) {
    Action newAction = new AbstractAction("New") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          checkForChanges();
          setScenario(map, new GisScenario());
        } catch (CancelledByUserException ex) {
        }
      }
    };
    Action loadAction = new AbstractAction("Load") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          checkForChanges();
          load();
        } catch (CancelledByUserException ex) {
        } catch (MapException | ScenarioException ex) {
          JOptionPane.showMessageDialog(null, ex);
        } catch (rescuecore2.scenario.exceptions.ScenarioException ex) {
          ex.printStackTrace();
        }
      }
    };
    Action saveAction = new AbstractAction("Save") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          save();
        } catch (ScenarioException ex) {
          JOptionPane.showMessageDialog(null, ex);
        }
      }
    };
    Action saveAsAction = new AbstractAction("Save as") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          saveAs();
        } catch (ScenarioException ex) {
          JOptionPane.showMessageDialog(null, ex);
        }
      }
    };
    toolbar.add(newAction);
    toolbar.add(loadAction);
    toolbar.add(saveAction);
    toolbar.add(saveAsAction);
    menu.add(newAction);
    menu.add(loadAction);
    menu.add(saveAction);
    menu.add(saveAsAction);
  }

  private void createEditActions(JMenu menu, JToolBar toolbar) {
    undoAction = new AbstractAction("Undo") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          undoManager.undo();
        } catch (CannotUndoException ex) {
          JOptionPane.showMessageDialog(null, ex);
        }
        setEnabled(undoManager.canUndo());
        redoAction.setEnabled(undoManager.canRedo());
      }
    };
    redoAction = new AbstractAction("Redo") {

      @Override
      public void actionPerformed(ActionEvent e) {
        try {
          undoManager.redo();
        } catch (CannotUndoException ex) {
          JOptionPane.showMessageDialog(null, ex);
        }
        setEnabled(undoManager.canRedo());
        undoAction.setEnabled(undoManager.canUndo());
      }
    };
    undoAction.setEnabled(false);
    redoAction.setEnabled(false);
    toolbar.add(undoAction);
    toolbar.add(redoAction);
    menu.add(undoAction);
    menu.add(redoAction);
  }

  private void createFunctionActions(JMenu menu, JToolBar toolbar) {
    addFunction(new ClearCiviliansFunction(this), menu, toolbar);
    addFunction(new ClearFireBrigadesFunction(this), menu, toolbar);
    addFunction(new ClearAmbulanceTeamsFunction(this), menu, toolbar);
    addFunction(new ClearPoliceForcesFunction(this), menu, toolbar);
    addFunction(new ClearAgentsFunction(this), menu, toolbar);
    addFunction(new ClearAllFunction(this), menu, toolbar);
    addFunction(new PlaceAgentsFunction(this), menu, toolbar);
    addFunction(new RandomiseFunction(this), menu, toolbar);
    addFunction(new ClearFiresFunction(this), menu, toolbar);
    addFunction(new RandomHydrantPlacementFunction(this), menu, toolbar);
  }

  private void createToolActions(JMenu menu, JToolBar toolbar) {
    ButtonGroup toolbarGroup = new ButtonGroup();
    ButtonGroup menuGroup = new ButtonGroup();

    addTool(new PlaceCivilianTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveCivilianTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceFireBrigadeTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveFireBrigadeTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceAmbulanceTeamTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveAmbulanceTeamTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlacePoliceForceTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemovePoliceForceTool(this), menu, toolbar, menuGroup, toolbarGroup);

    menu.addSeparator();
    toolbar.addSeparator();

    addTool(new PlaceFireStationTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveFireStationTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceAmbulanceCentreTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveAmbulanceCentreTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlacePoliceOfficeTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemovePoliceOfficeTool(this), menu, toolbar, menuGroup, toolbarGroup);

    menu.addSeparator();
    toolbar.addSeparator();

    addTool(new PlaceRefugeTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveRefugeTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceFireTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveFireTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceGasStationTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveGasStationTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new PlaceHydrantTool(this), menu, toolbar, menuGroup, toolbarGroup);
    addTool(new RemoveHydrantTool(this), menu, toolbar, menuGroup, toolbarGroup);
  }

  private void addFunction(final Function f, JMenu menu, JToolBar toolbar) {
    Action action = new AbstractAction(f.getName()) {
      @Override
      public void actionPerformed(ActionEvent e) {
        f.execute();
      }
    };
    toolbar.add(action);
    menu.add(action);
  }

  private void addTool(final Tool t, JMenu menu, JToolBar toolbar, ButtonGroup menuGroup, ButtonGroup toolbarGroup) {
    final JToggleButton toggle = new JToggleButton();
    final JCheckBoxMenuItem check = new JCheckBoxMenuItem();
    final Action action = new AbstractAction(t.getName()) {
      @Override
      public void actionPerformed(ActionEvent e) {
        if (currentTool != null) {
          currentTool.deactivate();
        }
        currentTool = t;
        toggle.setSelected(true);
        check.setSelected(true);
        currentTool.activate();
      }
    };
    action.putValue(Action.SMALL_ICON, t.getIcon());
    action.putValue(Action.SHORT_DESCRIPTION, t.getName());

    toggle.setAction(action);
    toggle.setText("");
    toggle.setHorizontalAlignment(SwingConstants.LEFT);

    check.setAction(action);
    check.setIcon(null);
    check.setToolTipText(null);

    menu.add(check);
    toolbar.add(toggle);
    menuGroup.add(check);
    toolbarGroup.add(toggle);
  }

  private boolean checkScenario(GMLMap newMap, GisScenario newScenario) {
    boolean valid = true;
    for (int id : newScenario.getFires()) {
      if (newMap.getBuilding(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getRefuges()) {
      if (newMap.getBuilding(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getHydrants()) {
      if (newMap.getRoad(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getFireStations()) {
      if (newMap.getBuilding(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getAmbulanceCentres()) {
      if (newMap.getBuilding(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getPoliceOffices()) {
      if (newMap.getBuilding(id) == null) {
        valid = false;
      }
    }

    for (int id : newScenario.getCivilians()) {
      if (newMap.getShape(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getFireBrigades()) {
      if (newMap.getShape(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getAmbulanceTeams()) {
      if (newMap.getShape(id) == null) {
        valid = false;
      }
    }
    for (int id : newScenario.getPoliceForces()) {
      if (newMap.getShape(id) == null) {
        valid = false;
      }
    }
    return valid;
  }

  private void updateStatusLabel() {
    SwingUtilities.invokeLater(() -> statusLabel.setText(scenario.getFires().size() + " fires, "
        + scenario.getRefuges().size() + " refuges, "
        + scenario.getHydrants().size() + " hydrants, "
        + scenario.getGasStations().size() + " gas stations, "
        + scenario.getCivilians().size() + " civilians, "
        + scenario.getFireBrigades().size() + " fb, "
        + scenario.getFireStations().size() + " fs, "
        + scenario.getPoliceForces().size() + " pf, "
        + scenario.getPoliceOffices().size() + " po, "
        + scenario.getAmbulanceTeams().size() + " at, "
        + scenario.getAmbulanceCentres().size() + " ac"));
  }

  private void updateFireOverlay() {
    fireOverlay.clearAllBuildingDecorators();
    for (int next : scenario.getFires()) {
      fireOverlay.setBuildingDecorator(fireDecorator, map.getBuilding(next));
    }
  }

  private void updateCentreOverlay() {
    centreOverlay.clearAllBuildingDecorators();
    centreOverlay.clearAllRoadDecorators();
    for (int next : scenario.getFireStations()) {
      centreOverlay.setBuildingDecorator(fireStationDecorator,
          map.getBuilding(next));
    }
    for (int next : scenario.getPoliceOffices()) {
      centreOverlay.setBuildingDecorator(policeOfficeDecorator,
          map.getBuilding(next));
    }
    for (int next : scenario.getAmbulanceCentres()) {
      centreOverlay.setBuildingDecorator(ambulanceCentreDecorator,
          map.getBuilding(next));
    }
    for (int next : scenario.getRefuges()) {

      centreOverlay.setBuildingDecorator(refugeDecorator,
          scenario.getRefuge(next));
    }
    for (int next : scenario.getGasStations()) {
      centreOverlay.setBuildingDecorator(gasStationDecorator,
          map.getBuilding(next));
    }
    for (int next : scenario.getHydrants()) {
      centreOverlay.setRoadDecorator(hydrantDecorator, map.getRoad(next));
    }
  }

  private void updateAgentOverlay() {}
}