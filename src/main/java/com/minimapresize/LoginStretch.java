package com.minimapresize;

import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.stretchedmode.StretchedModeConfig;

/** Uses the client's native presentation stretch for the fixed-size login and welcome screens. */
final class LoginStretch implements MouseListener, MouseWheelListener
{
	private final Client client;
	private final MouseManager mouseManager;
	private final StretchedModeConfig stretchedConfig;
	private volatile boolean owned;

	LoginStretch(Client client, MouseManager mouseManager, StretchedModeConfig stretchedConfig)
	{
		this.client = client;
		this.mouseManager = mouseManager;
		this.stretchedConfig = stretchedConfig;
		mouseManager.registerMouseListener(0, this);
		mouseManager.registerMouseWheelListener(0, this);
	}

	void update(boolean stretchedModeActive)
	{
		if (stretchedModeActive)
		{
			// Stretched Mode has installed its own translator and settings.
			owned = false;
			return;
		}
		GameState state = client.getGameState();
		boolean login = state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.LOGGING_IN
			|| (state == GameState.LOGGED_IN || state == GameState.LOADING)
				&& client.getTopLevelInterfaceId() == InterfaceID.TOPLEVEL_DISPLAY;
		if (!login)
		{
			release();
		}
		else if (!client.isStretchedEnabled())
		{
			client.setStretchedIntegerScaling(false);
			client.setStretchedKeepAspectRatio(false);
			client.setStretchedFast(false);
			// The client field is initially zero, not a usable scale multiplier.
			// Stretched Mode normally initializes it through this setter.
			client.setScalingFactor(stretchedConfig.scalingFactor());
			owned = true;
			client.setStretchedEnabled(true);
			client.invalidateStretching(true);
		}
	}

	private void release()
	{
		if (!owned) { return; }
		owned = false;
		client.setStretchedEnabled(false);
		client.setStretchedIntegerScaling(stretchedConfig.integerScaling());
		client.setStretchedKeepAspectRatio(stretchedConfig.keepAspectRatio());
		client.setStretchedFast(stretchedConfig.increasedPerformance());
		client.setScalingFactor(stretchedConfig.scalingFactor());
		client.invalidateStretching(true);
	}

	void close()
	{
		release();
		mouseManager.unregisterMouseListener(this);
		mouseManager.unregisterMouseWheelListener(this);
	}

	void resized()
	{
		if (owned) { client.invalidateStretching(true); }
	}

	private MouseEvent translate(MouseEvent event)
	{
		if (!owned || !client.isStretchedEnabled()) { return event; }
		// Both software and GPU presentation stretch the actual canvas buffer.
		// getRealDimensions() can already describe the NEXT canvas size while a
		// resize is pending, and must not be used to map clicks on this frame.
		int width = client.getCanvasWidth(), height = client.getCanvasHeight();
		Dimension output = client.getStretchedDimensions();
		if (width <= 0 || height <= 0 || output == null || output.width <= 0 || output.height <= 0
			|| width == output.width && height == output.height) { return event; }
		int x = (int) (event.getX() * (double) width / output.width);
		int y = (int) (event.getY() * (double) height / output.height);
		MouseEvent translated;
		if (event instanceof MouseWheelEvent)
		{
			MouseWheelEvent wheel = (MouseWheelEvent) event;
			translated = new MouseWheelEvent(event.getComponent(), event.getID(), event.getWhen(), event.getModifiersEx(),
				x, y, event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(),
				wheel.getScrollType(), wheel.getScrollAmount(), wheel.getWheelRotation(), wheel.getPreciseWheelRotation());
		}
		else
		{
			translated = new MouseEvent(event.getComponent(), event.getID(), event.getWhen(), event.getModifiersEx(),
				x, y, event.getXOnScreen(), event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(), event.getButton());
		}
		if (event.isConsumed()) { translated.consume(); }
		return translated;
	}

	@Override public MouseEvent mouseClicked(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mousePressed(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mouseReleased(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mouseExited(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return translate(e); }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return translate(e); }
	@Override public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) { return (MouseWheelEvent) translate(e); }
}
