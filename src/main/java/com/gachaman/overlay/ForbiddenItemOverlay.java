package com.gachaman.overlay;

import com.gachaman.service.*;
import java.awt.*;
import java.awt.image.*;
import javax.inject.*;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.overlay.*;

/**
 * Draws a crossed-circle icon over forbidden equipment in the inventory and
 * bank so the player can see at a glance what their cards do not permit.
 */
@Singleton
public class ForbiddenItemOverlay extends WidgetItemOverlay {
	private static final int ICON_SIZE = 14;

	private final PermissionService permissionService;
	private BufferedImage icon;

	@Inject
	public ForbiddenItemOverlay(PermissionService permissionService) {
		this.permissionService = permissionService;
		showOnInventory();
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
		if (!permissionService.isForbidden(itemId)) {
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null) {
			return;
		}
		graphics.drawImage(getIcon(), bounds.x + bounds.width - ICON_SIZE, bounds.y, null);
	}

	private BufferedImage getIcon() {
		if (icon != null) {
			return icon;
		}
		BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
		g.setColor(new Color(232, 60, 60));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(1, 1, ICON_SIZE - 3, ICON_SIZE - 3);
		int inset = 3;
		g.drawLine(inset, ICON_SIZE - 1 - inset, ICON_SIZE - 1 - inset, inset);
		g.dispose();
		icon = image;
		return image;
	}
}
