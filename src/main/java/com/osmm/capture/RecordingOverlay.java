package com.osmm.capture;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

/**
 * "Show recording indicator": a small box, top left (movable like any overlay), saying the plugin is recording (a red
 * dot, the time and ticks) or waiting for combat. Says nothing about the fight. Each frame it only reads the status
 * the plugin publishes on the client thread.
 */
final class RecordingOverlay extends OverlayPanel
{
	private static final Color RED = new Color(230, 50, 50);
	private static final Color GREY = ColorScheme.LIGHT_GRAY_COLOR;

	private final OsmmCapturePlugin plugin;
	private final Dot dot = new Dot();

	// the text for the ticks last shown, so a frame makes no new strings
	private int shownTicks = -1;
	private String time = "";
	private String ticks = "";

	@Inject
	RecordingOverlay(OsmmCapturePlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.indicatorShown())
		{
			return null;
		}
		OsmmCapturePlugin.Status s = plugin.status();
		if (s.file != null)
		{
			if (s.ticks != shownTicks)
			{
				shownTicks = s.ticks;
				time = CaptureFiles.duration(s.ticks);
				ticks = Integer.toString(s.ticks);
			}
			panelComponent.getChildren().add(SplitComponent.builder()
				.orientation(ComponentOrientation.HORIZONTAL)
				.first(dot)
				.second(LineComponent.builder().left("Recording").leftColor(RED).right(time).build())
				.build());
			panelComponent.getChildren().add(LineComponent.builder().left("Ticks").leftColor(GREY).right(ticks).build());
		}
		else if (s.waiting)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Waiting for combat").leftColor(GREY).build());
		}
		else
		{
			return null;
		}
		return super.render(graphics);
	}

	/** A red dot the height of a line of text, centred on the text beside it. */
	private static final class Dot implements LayoutableRenderableEntity
	{
		private static final int SIZE = 7;
		private static final int GAP = 4;

		private final Rectangle bounds = new Rectangle();
		private final Point location = new Point();

		@Override
		public Dimension render(Graphics2D g)
		{
			FontMetrics fm = g.getFontMetrics();
			int height = fm.getHeight();
			// LineComponent draws its text with the baseline at the line's top plus the font height
			int middle = location.y + height - fm.getAscent() / 3;
			Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(RED);
			g.fillOval(location.x, middle - (SIZE + 1) / 2, SIZE, SIZE);
			if (aa != null)
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
			}
			Dimension d = new Dimension(SIZE + GAP, height);
			bounds.setLocation(location);
			bounds.setSize(d);
			return d;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}

		@Override
		public void setPreferredLocation(Point position)
		{
			location.setLocation(position);
		}

		@Override
		public void setPreferredSize(Dimension dimension)
		{
			// its own size
		}
	}
}
