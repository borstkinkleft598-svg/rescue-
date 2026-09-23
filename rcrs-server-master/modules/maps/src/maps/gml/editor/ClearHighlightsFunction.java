package maps.gml.editor;

public class ClearHighlightsFunction extends AbstractFunction {

    protected ClearHighlightsFunction(final GMLEditor editor) {
        super(editor);
    }

    @Override
    public void execute() {
        editor.getViewer().clearOverlays();
        editor.getViewer().repaint();
    }

    @Override
    public String getName() {
        return "Clear highlights";
    }

}
