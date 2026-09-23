package gis2.scenario;

import gis2.GisScenario;

import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.undo.AbstractUndoableEdit;

import maps.gml.GMLShape;

/**
 * Function for placing agents.
 */
public class PlaceAgentsFunction extends AbstractFunction {
  private static final int TYPE_FIRE      = 0;
  private static final int TYPE_POLICE    = 1;
  private static final int TYPE_AMBULANCE = 2;
  private static final int TYPE_CIVILIAN  = 3;

  private final Random random;

  /**
   * Construct a place agents function.
   *
   * @param editor The editor instance.
   */
  public PlaceAgentsFunction(ScenarioEditor editor) {
    super(editor);
    random = new Random();
  }

  @Override
  public String getName() {
    return "Place Agents";
  }

  @Override
  public void execute() {
    JPanel panel = new JPanel(new GridLayout(3, 2));

    JComboBox<String> typeCombo = new JComboBox<>(new String[] { "Fire", "Police", "Ambulance", "Civilian" });
    JSpinner numberSpinner = new JSpinner(new SpinnerNumberModel(1, 0, Integer.MAX_VALUE, 1));

    JCheckBox buildingBox = new JCheckBox("In buildings?", false);
    JCheckBox roadBox = new JCheckBox("In Roads?", true);

    JPanel checkPanel = new JPanel();
    checkPanel.add(buildingBox);
    checkPanel.add(roadBox);

    panel.add(new JLabel("Type"));
    panel.add(typeCombo);
    panel.add(new JLabel("Number"));
    panel.add(numberSpinner);
    panel.add(checkPanel);

    if (JOptionPane.showConfirmDialog(null, panel, getName(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;

    List<GMLShape> candidateAreas = new ArrayList<>();
    if (roadBox.isSelected()) candidateAreas.addAll(editor.getMap().getRoads());
    if (buildingBox.isSelected()) candidateAreas.addAll(editor.getMap().getBuildings());
    if (candidateAreas.isEmpty()) {
      String message = "No Area to Place... Please choose In Road or Building...";
      JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
      return;
    }

    List<Integer> locations = new ArrayList<>();
    int number = (int) numberSpinner.getValue();
    for (int i = 0; i < number; ++i) {
      locations.add(candidateAreas.get(random.nextInt(candidateAreas.size())).getID());
    }

    GisScenario scenario = editor.getScenario();
    ScenarioSnapshot beforeSnapshot = new ScenarioSnapshot(scenario);

    int agentType = typeCombo.getSelectedIndex();
    switch (agentType) {
      case TYPE_FIRE      -> locations.forEach(scenario::addFireBrigade);
      case TYPE_AMBULANCE -> locations.forEach(scenario::addAmbulanceTeam);
      case TYPE_POLICE    -> locations.forEach(scenario::addPoliceForce);
      case TYPE_CIVILIAN  -> locations.forEach(scenario::addCivilian);
      default -> throw new IllegalArgumentException("Unexpected type: " + agentType);
    }

    ScenarioSnapshot afterSnapshot = new ScenarioSnapshot(scenario);
    PlaceAgentsEdit placeEdit = new PlaceAgentsEdit(beforeSnapshot, afterSnapshot);

    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(placeEdit);
  }

  private class PlaceAgentsEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot beforeSnapshot;
    private final ScenarioSnapshot afterSnapshot;

    public PlaceAgentsEdit(ScenarioSnapshot beforeSnapshot, ScenarioSnapshot afterSnapshot) {
      this.beforeSnapshot = beforeSnapshot;
      this.afterSnapshot = afterSnapshot;
    }

    @Override
    public void undo() {
      super.undo();
      beforeSnapshot.restore(editor.getScenario());
      editor.updateOverlays();
    }

    @Override
    public void redo() {
      super.redo();
      afterSnapshot.restore(editor.getScenario());
      editor.updateOverlays();
    }
  }
}
