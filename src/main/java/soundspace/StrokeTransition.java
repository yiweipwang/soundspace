package soundspace;

import javafx.animation.Transition;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Animates the stroke colour of a {@link Rectangle} from one colour to another.
 */
public class StrokeTransition extends Transition {

    private final Rectangle node;
    private final Color startColor;
    private final Color endColor;

    public StrokeTransition(Duration duration, Rectangle node,
                            Color startColor, Color endColor) {
        this.node       = node;
        this.startColor = startColor;
        this.endColor   = endColor;
        setCycleDuration(duration);
    }

    @Override
    protected void interpolate(double frac) {
        node.setStroke(startColor.interpolate(endColor, frac));
    }
}
